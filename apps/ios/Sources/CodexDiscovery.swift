import Foundation
import CryptoKit
import Network
import Combine
import MobileCore
import Darwin

// Versioned domain binding is shared; CryptoKit remains the native key-agreement/AEAD implementation.
enum CodexPrivateCredential {
  static func open(_ data: Data, target: TargetScope, privateKey: String, publicKey: String) throws -> String {
    guard let json = String(data: data, encoding: .utf8),
      PrivateConnectionRules.shared.replyMatches(json: json, captured: target, publicKey: publicKey) else { throw HostServiceError.invalidResponse }
    struct Reply: Decodable { var ephemeralPublicKey: String; var nonce: String; var ciphertext: String }
    let reply = try JSONDecoder().decode(Reply.self, from: data)
    func raw(_ value: String, count: Int? = nil) throws -> Data {
      guard let bytes = Data(base64URL: value), bytes.base64URL == value, count == nil || bytes.count == count else { throw HostServiceError.invalidResponse }
      return bytes
    }
    let key = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw(privateKey, count: 32))
    guard key.publicKey.rawRepresentation == (try raw(publicKey, count: 32)) else { throw HostServiceError.invalidResponse }
    let secret = try key.sharedSecretFromKeyAgreement(with: Curve25519.KeyAgreement.PublicKey(rawRepresentation: raw(reply.ephemeralPublicKey, count: 32)))
    let context = Data(PrivateConnectionRules.shared.context(target: target, publicKey: publicKey).utf8)
    let derived = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(), sharedInfo: context, outputByteCount: 32)
    let ciphertext = try raw(reply.ciphertext)
    guard (17...1024).contains(ciphertext.count) else { throw HostServiceError.invalidResponse }
    let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: raw(reply.nonce, count: 12)), ciphertext: ciphertext.dropLast(16), tag: ciphertext.suffix(16))
    let clear = try AES.GCM.open(box, using: derived, authenticating: context)
    guard let token = String(data: clear, encoding: .utf8), token.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil else { throw HostServiceError.invalidResponse }
    return token
  }
}

struct CodexDirectConnection: Codable, Equatable, Sendable {
  var target: AgentRuntimeTarget
  var candidates: [AgentConnectionCandidate]
  var fingerprint: String
  var systemEndpoints: [String]? = nil
  var systemRoutes: [String] { (systemEndpoints ?? []).prefix(4).compactMap { CodexRouteRules.shared.systemEndpoint(value: $0) } }
}
struct CodexHostDescriptor: Codable, Sendable {
  var version: Int
  var agentId: String
  var name: String
  var target: AgentRuntimeTarget
  var fingerprint: String
  var endpoints: [String]
  var endpointHints: [EasyWorkEndpointHint]?
  var connectionMode: String? = nil
  var token: String? = nil
  // Learned only from the separate authenticated routes contract; /host stays v1 compatible.
  var systemEndpoints: [String]? = nil
  static func validatePayload(_ data: Data, allowToken: Bool = false) throws {
    guard var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw HostServiceError.invalidResponse }
    if allowToken { object.removeValue(forKey: "token") }
    let descriptor = try JSONSerialization.data(withJSONObject: object)
    guard let json = String(data: descriptor, encoding: .utf8), CodexDiscoveryContract.shared.validate(json: json) == nil else { throw HostServiceError.invalidResponse }
  }
  static func parse(_ text: String) throws -> Self {
    guard let uri = URLComponents(string: text), uri.scheme == "codex-host", uri.host == "pair", uri.path.isEmpty,
      uri.queryItems?.count == 1, let encoded = uri.queryItems?.first, encoded.name == "descriptor",
      let data = Data(base64URL: encoded.value ?? ""), data.count <= 16384 else { throw HostServiceError.invalidAddress }
    try validatePayload(data, allowToken: true)
    let value = try JSONDecoder().decode(Self.self, from: data)
    try value.validate()
    guard let token = value.token, (32...512).contains(token.count), !token.contains(where: \.isWhitespace) else { throw HostServiceError.invalidResponse }
    return value
  }
  func validate() throws {
    guard version == 1, agentId == "codex", target.agentId == "codex", TaskNotificationValidation.target(target),
      fingerprint.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil,
      !name.isEmpty, name.count <= 256, !endpoints.isEmpty, endpoints.count <= 32, (endpointHints?.count ?? 0) <= 32 else { throw HostServiceError.invalidResponse }
    _ = try candidates()
  }
  func candidates() throws -> [AgentConnectionCandidate] {
    try EasyWorkPairing.directCandidates(endpoints, hints: endpointHints ?? [], fingerprint: fingerprint).map { value in
      var candidate = value; candidate.transportId = "codex-https"; return candidate
    }
  }
}

/// Applies the paired certificate to every Codex request, including uploads and receipts.
/// A submitted request is never retried here. Existing operation recovery owns uncertainty.
final class CodexHostRoutes: @unchecked Sendable {
  static let shared = CodexHostRoutes()
  private let lock = NSLock()
  private var pins: [String: String] = [:]
  private var sessions: [String: URLSession] = [:]
  private var systemSession = URLSession(configuration: .ephemeral, delegate: CodexSystemTrust(), delegateQueue: nil)
  private let pathMonitor = NWPathMonitor()
  private var pathSignature: String?
  init() {
    pathMonitor.pathUpdateHandler = { [weak self] path in
      let signature = "\(path.status):\(path.isExpensive):\(path.isConstrained):" + path.availableInterfaces.filter { path.usesInterfaceType($0.type) }.map { "\($0.type):\($0.index)" }.sorted().joined(separator: ",")
      guard let self else { return }
      let changed = self.lock.withLock {
        defer { self.pathSignature = signature }
        return self.pathSignature != nil && self.pathSignature != signature
      }
      if changed { self.resetReadConnections() }
    }
    pathMonitor.start(queue: DispatchQueue(label: "agent-hub.codex-network-path"))
  }
  deinit { pathMonitor.cancel() }
  func resetReadConnections() {
    let previous = lock.withLock {
      let previous = Array(sessions.values) + [systemSession]
      sessions.removeAll()
      systemSession = URLSession(configuration: .ephemeral, delegate: CodexSystemTrust(), delegateQueue: nil)
      return previous
    }
    for session in previous {
      session.getAllTasks { tasks in
        for task in tasks where task.originalRequest?.httpMethod == "GET" {
          if let path = task.originalRequest?.url?.path, ["/v1/events", "/v1/history"].contains(path) { task.cancel() }
        }
        // Let every submitted write finish on its original connection. Recovery
        // only rotates read connections and never retries a command.
        session.finishTasksAndInvalidate()
      }
    }
    DispatchQueue.main.async { NotificationCenter.default.post(name: Notification.Name("CodexNetworkPathChanged"), object: nil) }
  }
  private func origin(_ url: URL) -> String? {
    guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
    components.path = ""; components.query = nil; components.fragment = nil
    return components.url?.absoluteString
  }
  func register(_ client: Client) {
    guard let connection = client.codexDirect else { return }
    lock.withLock {
      for endpoint in connection.systemRoutes { if let url = URL(string: endpoint), let origin = origin(url) { pins.removeValue(forKey: origin) } }
      for candidate in connection.candidates where !connection.systemRoutes.contains(candidate.endpoint) {
        if let url = URL(string: candidate.endpoint), let origin = origin(url) { pins[origin] = connection.fingerprint }
      }
    }
  }
  func data(for request: URLRequest) async throws -> (Data, URLResponse) {
    let session = lock.withLock { () -> URLSession in
      guard let url = request.url, let origin = origin(url), let fingerprint = pins[origin] else { return systemSession }
      let key = origin + "|" + fingerprint
      if let session = sessions[key] { return session }
      let session = URLSession(configuration: .ephemeral, delegate: PairedHostTrust(endpoint: url.absoluteString, fingerprint: fingerprint), delegateQueue: nil)
      sessions[key] = session; return session
    }
    return try await session.data(for: request)
  }
}
final class CodexSystemTrust: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

enum CodexDiscoveryAPI {
  static func systemEndpoints(_ session: URLSession, base: URL, token: String, target: AgentRuntimeTarget) async -> [String] {
    guard !token.isEmpty else { return [] }
    do {
      var url = URLComponents(url: base.appendingPathComponent("v1/connection-routes"), resolvingAgainstBaseURL: false)!
      url.queryItems = [URLQueryItem(name: "hostId", value: target.hostId)]
      let (data, response) = try await session.data(for: HostService.request(url.url!, token: token))
      guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 8192,
        let wire = String(data: data, encoding: .utf8), CodexDiscoveryContract.shared.validateNamed(json: wire, definition: "connectionRoutes") == nil else { return [] }
      struct Routes: Decodable { var target: AgentRuntimeTarget; var httpsEndpoints: [String] }
      let routes = try JSONDecoder().decode(Routes.self, from: data)
      guard routes.target == target else { return [] }
      let values = routes.httpsEndpoints.compactMap { CodexRouteRules.shared.systemEndpoint(value: $0) }
      guard values.count == routes.httpsEndpoints.count else { return [] }
      return values
    } catch { return [] } // Older Hosts have no routes endpoint; retain saved routes.
  }
  static func read(_ endpoint: String, fingerprint: String?, target: AgentRuntimeTarget?, token: String = "", discover: Bool = false, timeout: TimeInterval = 2) async throws -> CodexHostDescriptor {
    try await EasyWorkProbeBudget.shared.perform {
      let url = try HostService.baseURL(endpoint)
      if fingerprint != nil || discover { _ = try EasyWorkPairing.localEndpoint(endpoint) }
      let discovered = DiscoveredHostTrust(endpoint: url)
      let delegate: any URLSessionDelegate = fingerprint.map { PairedHostTrust(endpoint: endpoint, fingerprint: $0) } ?? (discover ? discovered : CodexSystemTrust())
      let configuration = URLSessionConfiguration.ephemeral
      configuration.timeoutIntervalForRequest = timeout; configuration.timeoutIntervalForResource = timeout
      let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
      defer { session.invalidateAndCancel() }
      var request = HostService.request(url.appendingPathComponent("host"), token: token); request.timeoutInterval = timeout
      let startedAt = ProcessInfo.processInfo.systemUptime
      NativeReadAcceptanceTiming.discovery(endpoint, event: "start", startedAt: startedAt)
      let data: Data, response: URLResponse
      do {
        (data, response) = try await session.data(for: request)
        NativeReadAcceptanceTiming.discovery(endpoint, event: "response", startedAt: startedAt)
      } catch {
        NativeReadAcceptanceTiming.discovery(endpoint, event: "failed", startedAt: startedAt, error: error)
        throw error
      }
      guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 65536 else { throw HostServiceError.unavailable }
      try CodexHostDescriptor.validatePayload(data)
      var value = try JSONDecoder().decode(CodexHostDescriptor.self, from: data); try value.validate()
      guard target == nil || value.target == target, fingerprint == nil || value.fingerprint.lowercased() == fingerprint?.lowercased() else { throw HostServiceError.wrongHost }
      if discover { guard value.fingerprint.lowercased() == discovered.fingerprint else { throw HostServiceError.wrongHost } }
      value.systemEndpoints = await systemEndpoints(session, base: url, token: token, target: value.target)
      return value
    }
  }
  static func readPeers(_ endpoint: String, fingerprint: String, target: AgentRuntimeTarget) async throws -> VPNPeerSnapshot {
    try await EasyWorkProbeBudget.shared.perform {
      let base = try EasyWorkPairing.localEndpoint(endpoint)
      let configuration = URLSessionConfiguration.ephemeral
      configuration.timeoutIntervalForRequest = 6; configuration.timeoutIntervalForResource = 6
      let session = URLSession(configuration: configuration, delegate: PairedHostTrust(endpoint: endpoint, fingerprint: fingerprint), delegateQueue: nil)
      defer { session.invalidateAndCancel() }
      var url = URLComponents(url: base.appendingPathComponent("discovery/peers"), resolvingAgainstBaseURL: false)!
      url.queryItems = [URLQueryItem(name: "hostId", value: target.hostId)]
      var request = HostService.request(url.url!); request.timeoutInterval = 6
      let (data, response) = try await session.data(for: request)
      guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 32768 else { throw HostServiceError.unavailable }
      guard let json = String(data: data, encoding: .utf8), CodexDiscoveryContract.shared.validateNamed(json: json, definition: "vpnSnapshot") == nil else { throw HostServiceError.invalidResponse }
      let snapshot = try JSONDecoder().decode(VPNPeerSnapshot.self, from: data)
      try snapshot.validate(target)
      return snapshot
    }
  }
  static func connectDiscovered(_ endpoint: String, hostId: String, name: String, hostKey: String = "", expected: CodexDirectConnection? = nil) async throws -> (CodexHostDescriptor, Client, WorkspaceSnapshot) {
    let hostKey = hostKey.trimmingCharacters(in: .whitespacesAndNewlines)
    // An explicit connection must tolerate a cold VPN/TLS handshake. Keep the
    // short background scan independent from this user-initiated read budget.
    var host = try await read(endpoint, fingerprint: expected?.fingerprint, target: expected?.target, discover: expected == nil, timeout: 8)
    guard host.target.hostId == hostId else { throw HostServiceError.wrongHost }
    guard host.connectionMode == "host-key" || host.connectionMode == "automatic" else { throw HostServiceError.message(L10n.tr("请更新这台电脑的 Host 后使用密钥授权连接")) }
    if host.connectionMode == "host-key" {
      guard !hostKey.isEmpty else { throw HostServiceError.hostKeyRequired }
      guard (32...512).contains(hostKey.count), !hostKey.contains(where: \.isWhitespace) else { throw HostServiceError.message(L10n.tr("请输入这台电脑的 Host Key")) }
    }
    let identity = try EasyWorkIdentity.load()
    struct Claim: Encodable { var version = 1; var target: AgentRuntimeTarget; var publicKey: String }
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 15; configuration.timeoutIntervalForResource = 30
    let session = URLSession(configuration: configuration, delegate: PairedHostTrust(endpoint: endpoint, fingerprint: host.fingerprint), delegateQueue: nil)
    defer { session.invalidateAndCancel() }
    let base = try EasyWorkPairing.localEndpoint(endpoint)
    let claimTarget = host.target
    let request = HostService.request(base.appendingPathComponent("connection/claim"), token: host.connectionMode == "host-key" ? hostKey : "", method: "POST", body: try JSONEncoder().encode(Claim(target: claimTarget, publicKey: identity.publicKey)))
    let (data, response) = try await session.data(for: request)
    if (response as? HTTPURLResponse)?.statusCode == 401 { throw HostServiceError.message(L10n.tr("Host Key 不正确，请在电脑端核对后重试")) }
    guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 8192 else { throw HostServiceError.message(L10n.tr("无法连接这台电脑，请检查 Host 的直连访问设置")) }
    let token = try CodexPrivateCredential.open(data, target: SharedMobileRules.scope(claimTarget), privateKey: identity.privateKey, publicKey: identity.publicKey)
    let (workspaceData, workspaceResponse) = try await session.data(for: HostService.request(base.appendingPathComponent("v1/workspace"), token: token))
    guard (workspaceResponse as? HTTPURLResponse)?.statusCode == 200 else { throw HostServiceError.unavailable }
    guard let wire = String(data: workspaceData, encoding: .utf8), CodexHttpContract.shared.validate(json: wire, definition: "workspace") == nil else { throw HostServiceError.invalidResponse }
    let workspace = try JSONDecoder().decode(WorkspaceSnapshot.self, from: workspaceData)
    guard workspace.hostId == host.target.hostId, workspace.readTarget == host.target, workspace.connectionMode == "desktop-runtime" else { throw HostServiceError.wrongHost }
    let candidates = try EasyWorkPairing.directCandidates([endpoint] + host.endpoints, hints: host.endpointHints ?? [], fingerprint: host.fingerprint).map { value in var candidate = value; candidate.transportId = "codex-https"; return candidate }
    host.systemEndpoints = await systemEndpoints(session, base: base, token: token, target: host.target)
    let client = Client(id: host.target.hostId, name: name.isEmpty ? host.name : name, endpoint: endpoint, isDemo: false, online: false, codexDirect: CodexDirectConnection(target: host.target, candidates: candidates, fingerprint: host.fingerprint.lowercased(), systemEndpoints: host.systemEndpoints))
    host.token = token
    return (host, client, workspace)
  }
  static func connect(_ text: String, name: String) async throws -> (CodexHostDescriptor, Client, WorkspaceSnapshot) {
    let offer = try CodexHostDescriptor.parse(text)
    var lastError: Error = HostServiceError.unavailable
    for candidate in try offer.candidates() {
      do {
        let host = try await read(candidate.endpoint, fingerprint: offer.fingerprint, target: offer.target, token: offer.token!)
        let merged = try EasyWorkPairing.directCandidates([candidate.endpoint] + host.endpoints + offer.endpoints, hints: (host.endpointHints ?? []) + (offer.endpointHints ?? []), fingerprint: host.fingerprint).map { value in var candidate = value; candidate.transportId = "codex-https"; return candidate }
        let client = Client(id: host.target.hostId, name: name.isEmpty ? host.name : name, endpoint: candidate.endpoint, isDemo: false, online: false, codexDirect: CodexDirectConnection(target: host.target, candidates: merged, fingerprint: host.fingerprint.lowercased(), systemEndpoints: host.systemEndpoints))
        let session = URLSession(configuration: .ephemeral, delegate: PairedHostTrust(endpoint: candidate.endpoint, fingerprint: offer.fingerprint), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: HostService.request(try HostService.baseURL(candidate.endpoint).appendingPathComponent("v1/workspace"), token: offer.token!))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw HostServiceError.unavailable }
        guard let wire = String(data: data, encoding: .utf8), CodexHttpContract.shared.validate(json: wire, definition: "workspace") == nil else { throw HostServiceError.invalidResponse }
        let workspace = try JSONDecoder().decode(WorkspaceSnapshot.self, from: data)
        guard workspace.hostId == host.target.hostId, workspace.readTarget == host.target, workspace.connectionMode == "desktop-runtime" else { throw HostServiceError.wrongHost }
        return (offer, client, workspace)
      } catch { lastError = error }
    }
    throw lastError
  }
}

struct VPNPeerSnapshot: Decodable, Sendable {
  struct Peer: Decodable, Sendable { var name: String; var addresses: [String] }
  var version: Int
  var target: AgentRuntimeTarget
  var peers: [Peer]
  static func isVPNAddress(_ address: String) -> Bool {
    PrivateConnectionRules.shared.isVPNAddress(value: address)
  }
  static func orderedEndpoints(_ endpoints: [String]) -> [String] {
    var seen = Set<String>()
    let unique = endpoints.filter { seen.insert($0).inserted }
    func priority(_ endpoint: String) -> Int {
      let host = URL(string: endpoint)?.host?.trimmingCharacters(in: CharacterSet(charactersIn: "[]")) ?? ""
      if isVPNAddress(host) { return 0 }
      if host.hasSuffix(".ts.net") { return 1 }
      return host.hasSuffix(".local") ? 3 : 2
    }
    return unique.enumerated().sorted {
      let left = priority($0.element), right = priority($1.element)
      return left == right ? $0.offset < $1.offset : left < right
    }.map(\.element)
  }
  func validate(_ expected: AgentRuntimeTarget) throws {
    guard version == 1, target == expected, peers.count <= 32,
      peers.allSatisfy({ !$0.name.isEmpty && $0.name.count <= 256 && (1...2).contains($0.addresses.count) && $0.addresses.allSatisfy(Self.isVPNAddress) }) else { throw HostServiceError.invalidResponse }
  }
}

/// An automatically discovered private Host supplies hints before any client is paired.
/// Discovery never sends another computer's key or creates a binding.
@MainActor final class VPNHostDiscovery: ObservableObject {
  static let shared = VPNHostDiscovery()
  @Published private(set) var codexHosts: [CodexHostDiscovery.Host] = []
  @Published private(set) var easyworkHosts: [EasyWorkDiscovery.Host] = []
  @Published private(set) var searching = false
  private var generation = UUID()
  private var scanning: UUID?
  private var active = false
  private var monitor: NWPathMonitor?
  private var revision = UUID()
  private var rerun = false
  private struct Source: Codable { var target: AgentRuntimeTarget; var fingerprint: String; var endpoints: [String] }
  private static let cacheKey = "agent-hub-public-discovery-sources-v1"
  private var sources: [Source] = []
  private var seenCodex: [String: (host: CodexHostDiscovery.Host, at: TimeInterval)] = [:]
  private var bonjour: [String: String] = [:]
  private weak var store: SessionStore?
  private init() {
    if let data = UserDefaults.standard.data(forKey: Self.cacheKey), data.count <= 65536,
      let cached = try? JSONDecoder().decode([Source].self, from: data) {
      sources = Array(cached.filter { source in
        TaskNotificationValidation.target(source.target) && source.target.agentId == "codex" &&
        source.fingerprint.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil &&
        (1...32).contains(source.endpoints.count) && source.endpoints.allSatisfy { (try? EasyWorkPairing.localEndpoint($0)) != nil }
      }.prefix(4))
    }
  }
  func offerBonjour(endpoint: String, hostId: String) {
    guard active, (try? EasyWorkPairing.localEndpoint(endpoint)) != nil,
      bonjour[endpoint] != nil || bonjour.count < 8 else { return }
    bonjour[endpoint] = hostId
    if let store { Task { await self.scan(store) } }
  }
  private func remember(_ host: CodexHostDescriptor, endpoint: String) {
    var seen = Set<String>()
    let endpoints = ([endpoint] + host.endpoints).filter { seen.insert($0).inserted }
    sources.removeAll { $0.target.hostId == host.target.hostId }
    sources.insert(Source(target: host.target, fingerprint: host.fingerprint.lowercased(), endpoints: Array(VPNPeerSnapshot.orderedEndpoints(endpoints).prefix(32))), at: 0)
    sources = Array(sources.prefix(4))
    if let data = try? JSONEncoder().encode(sources) { UserDefaults.standard.set(data, forKey: Self.cacheKey) }
  }
  func stop() {
    active = false; generation = UUID(); revision = UUID(); searching = false
    scanning = nil; rerun = false
    monitor?.cancel(); monitor = nil
    codexHosts = []; seenCodex = [:]; easyworkHosts = []; bonjour = [:]; store = nil
  }
  func run(_ store: SessionStore) async {
    stop(); active = true; self.store = store; let current = generation
    let monitor = NWPathMonitor(); self.monitor = monitor
    monitor.pathUpdateHandler = { [weak self, weak store] _ in Task { @MainActor in
      guard let self, let store, self.active, self.generation == current else { return }
      self.revision = UUID(); await self.scan(store)
    } }
    monitor.start(queue: DispatchQueue(label: "agent-hub-vpn-discovery"))
    defer { if generation == current { stop() } }
    while !Task.isCancelled {
      await scan(store)
      do { try await Task.sleep(for: .seconds(15)) } catch { return }
    }
  }
  private func readDirectory(_ source: Source) async -> VPNPeerSnapshot? {
    let endpoints = Array(VPNPeerSnapshot.orderedEndpoints(source.endpoints).prefix(32))
    return await withTaskGroup(of: VPNPeerSnapshot?.self) { group in
      var index = 0
      func enqueue() {
        guard index < endpoints.count else { return }
        let endpoint = endpoints[index]; index += 1
        group.addTask {
          try? await CodexDiscoveryAPI.readPeers(endpoint, fingerprint: source.fingerprint, target: source.target)
        }
      }
      for _ in 0..<min(4, endpoints.count) { enqueue() }
      while let result = await group.next() {
        if Task.isCancelled { group.cancelAll(); return nil }
        if let result, !result.peers.isEmpty { group.cancelAll(); return result }
        enqueue()
      }
      return nil
    }
  }
  func scan(_ store: SessionStore) async {
    guard active else { return }
    if scanning != nil { rerun = true; return }
    let current = generation, path = revision, scanId = UUID()
    scanning = scanId; searching = true
    publishCodex()
    defer {
      if scanning == scanId { scanning = nil }
      if generation == current {
        searching = false
        if rerun { rerun = false; Task { await self.scan(store) } }
      }
    }
    // Bonjour supplies an address, never a client credential. Check the actual
    // descriptor/certificate before retaining any source across network changes.
    for (endpoint, hostId) in Array(bonjour.prefix(8)) {
      guard active, generation == current, revision == path, !Task.isCancelled else { return }
      if sources.contains(where: { $0.target.hostId == hostId && $0.endpoints.contains(endpoint) }) { continue }
      if let host = try? await CodexDiscoveryAPI.read(endpoint, fingerprint: nil, target: nil, discover: true), host.target.hostId == hostId {
        remember(host, endpoint: endpoint)
      }
    }
    var peers: [VPNPeerSnapshot.Peer] = []
    var foundDirectory = false
    let savedSources = store.saved.clients.filter { !$0.isDemo && $0.agentConnection == nil && ($0.codexDirect != nil || store.saved.workspaces[$0.id]?.readTarget?.agentId == "codex") }
      .sorted { $0.online && !$1.online }
    // A saved connection is an independent seed if the public directory cache is absent.
    for client in savedSources.prefix(4) {
      guard let direct = client.codexDirect else { continue }
      let endpoints = VPNPeerSnapshot.orderedEndpoints(direct.candidates.map(\.endpoint) + (client.endpoint.map { [$0] } ?? []))
        .filter { (try? EasyWorkPairing.localEndpoint($0)) != nil }
      guard !endpoints.isEmpty else { continue }
      if let index = sources.firstIndex(where: { $0.target == direct.target && $0.fingerprint == direct.fingerprint }) {
        sources[index].endpoints = Array(VPNPeerSnapshot.orderedEndpoints(sources[index].endpoints + endpoints).prefix(32))
      } else if sources.count < 4 {
        sources.append(Source(target: direct.target, fingerprint: direct.fingerprint, endpoints: Array(endpoints.prefix(32))))
      }
    }
    for source in sources.prefix(4) {
      guard active, generation == current, revision == path, !Task.isCancelled else { return }
      if let snapshot = await readDirectory(source), !snapshot.peers.isEmpty {
        peers = snapshot.peers; foundDirectory = true; break
      }
    }
    // Compatibility with older Hosts. This path does not gate initial discovery.
    for source in savedSources.prefix(foundDirectory ? 0 : 4) {
      guard active, generation == current, revision == path, !Task.isCancelled else { return }
      guard let endpoint = source.endpoint, let expected = source.codexDirect?.target ?? store.saved.workspaces[source.id]?.readTarget,
        !CredentialVault.token(source.id).isEmpty else { continue }
      do {
        CodexHostRoutes.shared.register(source)
        var url = URLComponents(url: try HostService.baseURL(endpoint).appendingPathComponent("v1/discovery/peers"), resolvingAgainstBaseURL: false)!
        url.queryItems = [URLQueryItem(name: "hostId", value: source.id)]
        var request = HostService.request(url.url!, token: CredentialVault.token(source.id)); request.timeoutInterval = 6
        let probeRequest = request
        let (data, response) = try await EasyWorkProbeBudget.shared.perform { try await CodexHostRoutes.shared.data(for: probeRequest) }
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 32768 else { continue }
        guard let json = String(data: data, encoding: .utf8), CodexDiscoveryContract.shared.validateNamed(json: json, definition: "vpnSnapshot") == nil else { throw HostServiceError.invalidResponse }
      let snapshot = try JSONDecoder().decode(VPNPeerSnapshot.self, from: data); try snapshot.validate(expected)
        peers += snapshot.peers
        break
      } catch { continue }
    }
    guard active, generation == current, revision == path, !Task.isCancelled else { return }
    struct Probe: Sendable { var key: String; var endpoint: String; var codex: Bool }
    enum Result: Sendable { case codex(Probe, CodexHostDescriptor), easywork(Probe, EasyWorkHostDescriptor) }
    var probes: [Probe] = [], seen = Set<String>()
    for peer in peers.prefix(32) {
      for (port, codex) in [(8796, true), (4319, false)] {
        let key = peer.addresses[0] + ":" + String(port)
        for address in peer.addresses {
          let endpoint = "https://" + (address.contains(":") ? "[\(address)]" : address) + ":" + String(port)
          if seen.insert(endpoint).inserted { probes.append(Probe(key: key, endpoint: endpoint, codex: codex)) }
        }
      }
    }
    var easyworkRows: [EasyWorkDiscovery.Host] = []
    await withTaskGroup(of: Result?.self) { group in
      var index = 0, found = Set<String>()
      func enqueue() {
        while index < probes.count && found.contains(probes[index].key) { index += 1 }
        guard index < probes.count else { return }; let probe = probes[index]; index += 1
        group.addTask {
          do {
            if probe.codex { return .codex(probe, try await CodexDiscoveryAPI.read(probe.endpoint, fingerprint: nil, target: nil, discover: true)) }
            return .easywork(probe, try await EasyWorkPairing.readHost(endpoint: probe.endpoint))
          } catch { return nil }
        }
      }
      for _ in 0..<4 { enqueue() }
      while let result = await group.next() {
        guard active, generation == current, revision == path, !Task.isCancelled else { group.cancelAll(); return }
        switch result {
        case .codex(let probe, let host):
          remember(host, endpoint: probe.endpoint)
          if found.insert(probe.key).inserted {
            let known = store.saved.clients.first { $0.id == host.target.hostId && $0.codexDirect?.target == host.target && $0.codexDirect?.fingerprint == host.fingerprint.lowercased() }
            if let known, !known.online { _ = store.updateCodexDiscovery(known, host: host, reachableEndpoint: probe.endpoint) }
            let row = CodexHostDiscovery.Host(id: host.target.hostId, name: host.name, endpoint: probe.endpoint, known: known != nil, reachable: true, networkKind: .vpn)
            seenCodex[row.id] = (row, ProcessInfo.processInfo.systemUptime)
            // Publish a validated target immediately, before unrelated peers
            // exhaust their scan budgets. A missed scan does not erase it.
            publishCodex()
          }
        case .easywork(let probe, let host):
          if found.insert(probe.key).inserted {
            let known = store.saved.clients.first { client in
              guard let connection = client.agentConnection,
                let credential = try? JSONDecoder().decode(EasyWorkCredential.self, from: Data(CredentialVault.token(connection.credentialRef).utf8)) else { return false }
              return credential.binding.target == host.target && credential.binding.desktopPublicKey == host.publicKey && connection.candidates.contains { $0.peerFingerprint?.lowercased() == host.fingerprint.lowercased() }
            }
            EasyWorkKnownHosts.shared.offerBonjour(endpoint: probe.endpoint, deviceId: host.target.desktopDeviceId)
            if !easyworkRows.contains(where: { $0.deviceId == host.target.desktopDeviceId }) {
              easyworkRows.append(.init(id: "vpn-" + host.target.desktopDeviceId, name: host.name, endpoint: probe.endpoint, knownClientId: known?.id, detail: "VPN · " + (known == nil ? L10n.tr("点选连接") : L10n.tr("已发现")), deviceId: host.target.desktopDeviceId))
            }
          }
        case nil: break
        }
        enqueue()
      }
    }
    guard active, generation == current, revision == path, !Task.isCancelled else { return }
    publishCodex(); easyworkHosts = easyworkRows.sorted { $0.name < $1.name }
  }
  private func publishCodex() {
    let now = ProcessInfo.processInfo.systemUptime
    seenCodex = seenCodex.filter { now - $0.value.at < 60 }
    codexHosts = seenCodex.values.sorted { $0.host.name < $1.host.name }.prefix(32).map { value in
      var row = value.host
      // This is a recently discovered address, never an online/readiness claim.
      row.reachable = now - value.at < 15
      return row
    }
  }
}

@MainActor final class CodexHostDiscovery: NSObject, ObservableObject, @preconcurrency NetServiceBrowserDelegate, @preconcurrency NetServiceDelegate {
  static let shared = CodexHostDiscovery()
  struct Host: Identifiable { var id: String; var name: String; var endpoint: String; var known: Bool; var reachable: Bool; var networkKind: AgentConnectionCandidate.NetworkKind? = nil }
  @Published private(set) var hosts: [Host] = []
  private var browser: NetServiceBrowser?
  private var services: [NetService] = []
  private var bonjour: [String: Host] = [:]
  private weak var store: SessionStore?
  private var generation = UUID()
  private var pathRevision = UUID()
  private var scanning: UUID?
  private var rescanRequested = false
  private var active = false
  private var monitor: NWPathMonitor?
  func stop() {
    active = false; generation = UUID(); pathRevision = UUID(); browser?.stop(); browser = nil
    scanning = nil; rescanRequested = false
    services.forEach { $0.stop() }; services.removeAll(); bonjour.removeAll()
    monitor?.cancel(); monitor = nil
  }
  func run(_ store: SessionStore) async {
    self.store = store; active = true; generation = UUID(); let current = generation
    store.saved.clients.forEach { CodexHostRoutes.shared.register($0) }
    let browser = NetServiceBrowser(); self.browser = browser; browser.delegate = self
    browser.schedule(in: .main, forMode: .common); browser.searchForServices(ofType: "_codex-host._tcp.", inDomain: "local.")
    let monitor = NWPathMonitor(); self.monitor = monitor
    monitor.pathUpdateHandler = { [weak self] _ in Task { @MainActor in guard let self, self.active, self.generation == current, let store = self.store else { return }; self.pathRevision = UUID(); await self.scan(store) } }
    monitor.start(queue: DispatchQueue(label: "codex-host-path"))
    defer { if generation == current { stop() } }
    while !Task.isCancelled {
      await scan(store)
      if store.client?.agentConnection == nil, store.client?.online == false { await store.refresh() }
      do { try await Task.sleep(for: .seconds(10)) } catch { return }
    }
  }
  func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) { services.append(service); service.delegate = self; service.startMonitoring(); service.resolve(withTimeout: 5) }
  func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
    bonjour.removeValue(forKey: service.name)
    services.removeAll { $0 == service }; rebuild()
  }
  func netServiceDidResolveAddress(_ sender: NetService) {
    guard active, let resolvedHost = sender.hostName, sender.port > 0,
      let id = sender.txtRecordData().flatMap({ NetService.dictionary(fromTXTRecord: $0)["hostId"] }).flatMap({ String(data: $0, encoding: .utf8) }), !id.isEmpty, id.count <= 256 else { return }
    // Bonjour returns an absolute DNS name. Use the .local spelling for iOS local-network ATS rules.
    let host = resolvedHost.hasSuffix(".") ? String(resolvedHost.dropLast()) : resolvedHost
    bonjour[sender.name] = Host(id: id, name: sender.name, endpoint: "https://\(host):\(sender.port)", known: false, reachable: true)
    VPNHostDiscovery.shared.offerBonjour(endpoint: "https://\(host):\(sender.port)", hostId: id)
    rebuild(); if let store { Task { await scan(store) } }
  }
  func netService(_ sender: NetService, didUpdateTXTRecord data: Data) { if active { sender.resolve(withTimeout: 2) } }
  private func rebuild() {
    guard let store else { return }
    hosts = hosts.filter { host in host.known && store.saved.clients.contains(where: { $0.id == host.id }) }
    var ids = Set(hosts.map(\.id)); hosts += bonjour.values.sorted { $0.name < $1.name }.filter { ids.insert($0.id).inserted }
  }
  func scan(_ store: SessionStore) async {
    guard active, !Task.isCancelled else { return }
    if scanning != nil { rescanRequested = true; return }
    let current = generation, revision = pathRevision, scanId = UUID(); scanning = scanId
    defer {
      if scanning == scanId {
        scanning = nil
        if active, generation == current, rescanRequested {
          rescanRequested = false
          // A Bonjour/path update can arrive while old routes are being probed.
          // Consume it before the periodic loop waits on a stale workspace route.
          Task { await self.scan(store) }
        }
      }
    }
    struct Probe: Sendable { var client: Client; var endpoint: String; var fingerprint: String?; var target: AgentRuntimeTarget }
    struct Result: Sendable { var probe: Probe; var host: CodexHostDescriptor }
    var probes: [Probe] = []
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection == nil && ($0.codexDirect != nil || store.saved.workspaces[$0.id]?.readTarget?.agentId == "codex") }
      .sorted { $0.id == store.client?.id && $1.id != store.client?.id }
    for client in clients {
      guard let target = client.codexDirect?.target ?? store.saved.workspaces[client.id]?.readTarget else { continue }
      var endpoints = (client.endpoint.map { [$0] } ?? []) + (client.codexDirect?.candidates.map(\.endpoint) ?? []) + (client.codexDirect?.systemRoutes ?? [])
      if client.codexDirect != nil { endpoints += bonjour.values.filter { $0.id == client.id }.map(\.endpoint) }
      // Probe the saved route together with VPN alternatives, before a long
      // tail of unreachable LAN hints. Keep the current route when healthy.
      endpoints = (client.endpoint.map { [$0] } ?? []) + VPNPeerSnapshot.orderedEndpoints(endpoints.filter { $0 != client.endpoint })
      var seen = Set<String>()
      for endpoint in endpoints where seen.insert(endpoint).inserted {
        let pin = client.codexDirect?.candidates.first(where: { $0.endpoint == endpoint })?.peerFingerprint ?? (bonjour.values.contains(where: { $0.id == client.id && $0.endpoint == endpoint }) ? client.codexDirect?.fingerprint : nil)
        // Legacy discovery authenticates only the existing, system-trusted HTTPS route.
        if pin == nil && !endpoint.hasPrefix("https://") { continue }
        probes.append(Probe(client: client, endpoint: endpoint, fingerprint: pin, target: target))
      }
      if !hosts.contains(where: { $0.id == client.id }) { hosts.append(Host(id: client.id, name: client.name, endpoint: client.endpoint ?? "", known: true, reachable: false)) }
    }
    await withTaskGroup(of: Result?.self) { group in
      var index = 0, results: [Result] = [], resolved = Set<String>()
      func enqueue() {
        while index < probes.count && resolved.contains(probes[index].client.id) { index += 1 }
        guard index < probes.count else { return }; let probe = probes[index]; index += 1
        group.addTask {
          do { let token = CredentialVault.token(probe.client.id)
            return Result(probe: probe, host: try await CodexDiscoveryAPI.read(probe.endpoint, fingerprint: probe.fingerprint, target: probe.target, token: token))
          } catch { return nil }
        }
      }
      for _ in 0..<4 { enqueue() }
      while let result = await group.next() {
        guard active, generation == current, pathRevision == revision, !Task.isCancelled else { group.cancelAll(); return }
        if let result {
          results.append(result)
          // An offline client's first authenticated route is usable now. Do
          // not wait for every dead address or unrelated Host to time out.
          if !result.probe.client.online, !resolved.contains(result.probe.client.id),
            store.updateCodexDiscovery(result.probe.client, host: result.host, reachableEndpoint: result.probe.endpoint) {
            resolved.insert(result.probe.client.id)
            if let row = hosts.firstIndex(where: { $0.id == result.probe.client.id }) {
              hosts[row].reachable = true; hosts[row].known = true; hosts[row].endpoint = result.probe.endpoint
            }
            rebuild()
          }
        }
        enqueue()
      }
      guard active, generation == current, pathRevision == revision, !Task.isCancelled else { return }
      var found = resolved
      for client in clients where !resolved.contains(client.id) {
        let available = results.filter { $0.probe.client.id == client.id }
        // Keep a healthy current route even if another probe returns sooner.
        guard let selected = available.first(where: { $0.probe.endpoint == client.endpoint }) ?? available.first,
          store.updateCodexDiscovery(selected.probe.client, host: selected.host, reachableEndpoint: selected.probe.endpoint) else { continue }
        found.insert(client.id)
        if let row = hosts.firstIndex(where: { $0.id == client.id }) { hosts[row].reachable = true; hosts[row].known = true; hosts[row].endpoint = selected.probe.endpoint }
      }
      for row in hosts.indices where hosts[row].known && !found.contains(hosts[row].id) { hosts[row].reachable = false }
      rebuild()
    }
  }
}
