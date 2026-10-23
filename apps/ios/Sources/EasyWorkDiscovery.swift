import Foundation
import Darwin
import Network
import Combine
import SwiftUI
import VisionKit

@MainActor final class EasyWorkDiscovery: NSObject, ObservableObject, @preconcurrency NetServiceBrowserDelegate, @preconcurrency NetServiceDelegate {
  struct Host: Identifiable { var id: String; var name: String; var endpoint: String; var knownClientId: String? = nil; var detail: String? = nil; var deviceId: String? = nil }
  @Published var hosts: [Host] = []
  private let browser = NetServiceBrowser()
  private var services: [NetService] = []
  private var searching = false
  private var localHosts: [Host] = []
  private var knownSubscription: AnyCancellable?
  private var vpnSubscription: AnyCancellable?
  func start() { guard !searching else { return }; searching = true; knownSubscription = EasyWorkKnownHosts.shared.$hosts.receive(on: RunLoop.main).sink { [weak self] _ in self?.rebuild() }; vpnSubscription = VPNHostDiscovery.shared.$easyworkHosts.receive(on: RunLoop.main).sink { [weak self] _ in self?.rebuild() }; rebuild(); browser.schedule(in: .main, forMode: .common); browser.delegate = self; browser.searchForServices(ofType: "_easywork-host._tcp.", inDomain: "local.") }
  func stop() { browser.stop(); services.forEach { $0.stop() }; services.removeAll(); localHosts.removeAll(); hosts.removeAll(); knownSubscription = nil; vpnSubscription = nil; searching = false }
  func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) { services.append(service); service.delegate = self; service.resolve(withTimeout: 5) }
  func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) { localHosts.removeAll { $0.id == service.name }; services.removeAll { $0 == service }; rebuild() }
  func netServiceDidResolveAddress(_ sender: NetService) {
    guard searching, let resolvedHost = sender.hostName, sender.port > 0 else { return }
    // Keep Bonjour's address within the existing .local transport policy.
    let host = resolvedHost.hasSuffix(".") ? String(resolvedHost.dropLast()) : resolvedHost
    let endpoint = "https://\(host):\(sender.port)"
    let deviceId = sender.txtRecordData().flatMap { NetService.dictionary(fromTXTRecord: $0)["deviceId"] }.flatMap { String(data: $0, encoding: .utf8) }
    localHosts.removeAll { $0.id == sender.name }
    localHosts.append(Host(id: sender.name, name: sender.name, endpoint: endpoint, deviceId: deviceId))
    EasyWorkKnownHosts.shared.offerBonjour(endpoint: endpoint, deviceId: deviceId)
    rebuild()
  }
  private func rebuild() {
    guard searching else { return }
    let known = EasyWorkKnownHosts.shared.hosts
    hosts = known.map { Host(id: $0.id, name: $0.name, endpoint: $0.endpoint, knownClientId: $0.id, detail: $0.reachable ? L10n.tr("已发现 · ") + ($0.networkKind == .vpn ? "VPN" : L10n.tr("直连")) : L10n.tr("已保存，暂不可达"), deviceId: $0.deviceId) }
    for item in VPNHostDiscovery.shared.easyworkHosts + localHosts where !hosts.contains(where: { $0.deviceId == item.deviceId && item.deviceId != nil || $0.endpoint == item.endpoint }) { hosts.append(item) }
  }
}

struct EasyWorkQRScanner: UIViewControllerRepresentable {
  var scanned: (String) -> Void
  var prefix: String = "easywork-host://pair?"
  func makeCoordinator() -> Coordinator { Coordinator(scanned: scanned, prefix: prefix) }
  func makeUIViewController(context: Context) -> DataScannerViewController {
    let view = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced, recognizesMultipleItems: false, isHighlightingEnabled: true)
    view.delegate = context.coordinator
    try? view.startScanning()
    return view
  }
  func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {}
  @MainActor final class Coordinator: NSObject, DataScannerViewControllerDelegate {
    let scanned: (String) -> Void
    let prefix: String
    init(scanned: @escaping (String) -> Void, prefix: String) { self.scanned = scanned; self.prefix = prefix }
    func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
      for case .barcode(let barcode) in addedItems {
        if let value = barcode.payloadStringValue, value.hasPrefix(prefix) || prefix == "easywork-host://pair?" && value.hasPrefix("easywork://pair?") { dataScanner.stopScanning(); scanned(value); return }
      }
    }
  }
}

struct EasyWorkEndpointHint: Codable, Sendable {
  var endpoint: String
  var networkKind: AgentConnectionCandidate.NetworkKind
}
struct EasyWorkHostDescriptor: Decodable, Sendable {
  var version: Int
  var name: String
  var fingerprint: String
  var publicKey: String
  var target: EasyWorkTarget
  var connectionMode: String?
  var endpoints: [String]?
  var endpointHints: [EasyWorkEndpointHint]?
}

/// Foreground discovery and reconnects share one limit, including during path changes.
actor EasyWorkProbeBudget {
  static let shared = EasyWorkProbeBudget()
  private var active = 0
  private var waiting: [CheckedContinuation<Void, Never>] = []
  func perform<T: Sendable>(_ operation: @Sendable () async throws -> T) async throws -> T {
    if active < 4 { active += 1 }
    else { await withCheckedContinuation { waiting.append($0) } }
    defer {
      if waiting.isEmpty { active -= 1 }
      else { waiting.removeFirst().resume() }
    }
    try Task.checkCancellation()
    return try await operation()
  }
}

extension EasyWorkPairing {
  static func localEndpoint(_ endpoint: String) throws -> URL {
    let address = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
    let url = try HostService.baseURL(address.contains("://") ? address : "https://" + address)
    let host = (url.host ?? "").lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]")).trimmingCharacters(in: CharacterSet(charactersIn: "."))
    let ipv4Host = host.hasPrefix("::ffff:") ? String(host.dropFirst(7)) : host
    let parts = ipv4Host.split(separator: ".", omittingEmptySubsequences: false)
    let octets = parts.compactMap { UInt8($0) }
    let ipv4 = parts.count == 4 && octets.count == 4 && (octets[0] == 10 || octets[0] == 127 || (octets[0] == 100 && (64...127).contains(octets[1])) || (octets[0] == 172 && (16...31).contains(octets[1])) || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 169 && octets[1] == 254))
    var ipv6Address = in6_addr()
    let ipv6Host = String(host.split(separator: "%").first ?? "")
    let isIPv6 = inet_pton(AF_INET6, ipv6Host, &ipv6Address) == 1
    let bytes = withUnsafeBytes(of: &ipv6Address) { Array($0) }
    let mapped = isIPv6 && bytes.prefix(10).allSatisfy { $0 == 0 } && bytes[10] == 255 && bytes[11] == 255
    let mappedPrivate = mapped && (bytes[12] == 10 || bytes[12] == 127 || (bytes[12] == 100 && (64...127).contains(bytes[13])) || (bytes[12] == 172 && (16...31).contains(bytes[13])) || (bytes[12] == 192 && bytes[13] == 168) || (bytes[12] == 169 && bytes[13] == 254))
    let ipv6 = isIPv6 && (bytes.dropLast().allSatisfy { $0 == 0 } && bytes[15] == 1 || bytes[0] & 0xfe == 0xfc || bytes[0] == 0xfe && bytes[1] & 0xc0 == 0x80 || mappedPrivate)
    guard url.scheme == "https", ipv4 || ipv6 || host == "localhost" || host.hasSuffix(".local") || host.hasSuffix(".ts.net") else { throw HostServiceError.message(L10n.tr("请填写局域网或 VPN 电脑地址")) }
    var normalized = URLComponents(url: url, resolvingAgainstBaseURL: false)!
    var canonicalHost = host
    if isIPv6 {
      var buffer = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
      if inet_ntop(AF_INET6, &ipv6Address, &buffer, socklen_t(buffer.count)) != nil { canonicalHost = String(cString: buffer) + (host.contains("%") ? "%" + String(host.split(separator: "%").last!) : "") }
    }
    normalized.host = canonicalHost.contains(":") ? "[" + canonicalHost + "]" : canonicalHost
    normalized.path = ""
    if normalized.port == 443 { normalized.port = nil }
    guard let result = normalized.url else { throw HostServiceError.invalidAddress }
    return result
  }
  static func directCandidates(_ endpoints: [String], hints: [EasyWorkEndpointHint] = [], fingerprint: String) throws -> [AgentConnectionCandidate] {
    guard endpoints.count <= 96, hints.count <= 96 else { throw HostServiceError.invalidResponse }
    var result: [AgentConnectionCandidate] = []
    for endpoint in endpoints {
      let url = try localEndpoint(endpoint)
      let normalized = url.absoluteString
      if result.contains(where: { $0.endpoint == normalized }) { continue }
      let hint = hints.first { (try? localEndpoint($0.endpoint).absoluteString) == normalized }
      let host = url.host ?? ""
      let kind = hint?.networkKind ?? (host.hasSuffix(".ts.net") || host.hasPrefix("100.") || host.contains("fd7a:115c:a1e0:") ? .vpn : .unknown)
      result.append(AgentConnectionCandidate(id: "direct-" + HostService.hash(Data(normalized.utf8)), transportId: "lan-wss", endpoint: normalized, peerFingerprint: fingerprint.lowercased(), networkKind: kind))
    }
    return Array(result.prefix(32))
  }
  static func readHost(endpoint: String, fingerprint: String? = nil, target: EasyWorkTarget? = nil, publicKey: String? = nil, timeout: TimeInterval = 2) async throws -> EasyWorkHostDescriptor {
    try await EasyWorkProbeBudget.shared.perform {
      try await readHostWithinBudget(endpoint: endpoint, fingerprint: fingerprint, target: target, publicKey: publicKey, timeout: timeout)
    }
  }
  private static func readHostWithinBudget(endpoint: String, fingerprint: String?, target: EasyWorkTarget?, publicKey: String?, timeout: TimeInterval) async throws -> EasyWorkHostDescriptor {
    let url = try localEndpoint(endpoint)
    let discovered = DiscoveredHostTrust(endpoint: url)
    let delegate: any URLSessionDelegate
    if let fingerprint { delegate = PairedHostTrust(endpoint: url.absoluteString, fingerprint: fingerprint) }
    else { delegate = discovered }
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = timeout
    configuration.timeoutIntervalForResource = timeout
    let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    defer { session.invalidateAndCancel() }
    let (data, response) = try await session.data(from: url.appendingPathComponent("host"))
    guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 65536 else { throw HostServiceError.unavailable }
    let value = try JSONDecoder().decode(EasyWorkHostDescriptor.self, from: data)
    let pin = fingerprint?.lowercased() ?? discovered.fingerprint
    guard value.version == 1, pin == value.fingerprint.lowercased(), value.publicKey.count == 43 else { throw HostServiceError.invalidResponse }
    guard target == nil || target == value.target, publicKey == nil || publicKey == value.publicKey else { throw HostServiceError.wrongHost }
    guard (value.endpoints?.count ?? 0) <= 32, (value.endpointHints?.count ?? 0) <= 32 else { throw HostServiceError.invalidResponse }
    _ = try directCandidates(value.endpoints ?? [], hints: value.endpointHints ?? [], fingerprint: value.fingerprint)
    return value
  }
  static func reachableCandidates(_ candidates: [AgentConnectionCandidate], binding: EasyWorkBinding) async -> [AgentConnectionCandidate] {
    let direct = candidates.filter { $0.transportId == "lan-wss" && $0.peerFingerprint != nil }
    return await withTaskGroup(of: [AgentConnectionCandidate]?.self) { group in
      var index = 0
      func enqueue() {
        guard index < direct.count else { return }
        let candidate = direct[index]; index += 1
        group.addTask {
          do {
            let host = try await readHost(endpoint: candidate.endpoint, fingerprint: candidate.peerFingerprint, target: binding.target, publicKey: binding.desktopPublicKey)
            return try directCandidates([candidate.endpoint] + (host.endpoints ?? []), hints: host.endpointHints ?? [], fingerprint: host.fingerprint)
          } catch { return nil }
        }
      }
      for _ in 0..<4 { enqueue() }
      while let result = await group.next() {
        if Task.isCancelled { group.cancelAll(); return candidates }
        if let result {
          group.cancelAll()
          let endpoints = Set(result.map(\.endpoint))
          return result + candidates.filter { !endpoints.contains($0.endpoint) }
        }
        enqueue()
      }
      return candidates
    }
  }
  static func connect(endpoint: String, name: String, offer: EasyWorkLANOffer) async throws -> (EasyWorkBinding, [AgentConnectionCandidate]) {
    let url = try localEndpoint(endpoint)
    let host = try await readHost(endpoint: endpoint, fingerprint: offer.fingerprint, target: offer.target, publicKey: offer.publicKey, timeout: 5)
    guard host.connectionMode == "automatic" else { throw HostServiceError.message(L10n.tr("请更新电脑端后使用直接连接")) }
    let identity = try EasyWorkIdentity.load()
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 15; configuration.timeoutIntervalForResource = 30
    let session = URLSession(configuration: configuration, delegate: PairedHostTrust(endpoint: url.absoluteString, fingerprint: host.fingerprint), delegateQueue: nil)
    defer { session.invalidateAndCancel() }
    let request = HostService.request(url.appendingPathComponent("connections"), method: "POST", body: try easyWorkJSON(["mobileDeviceId": identity.deviceId, "publicKey": identity.publicKey, "name": name]))
    let (claimed, reply) = try await session.data(for: request)
    guard (reply as? HTTPURLResponse)?.statusCode == 200 else { throw HostServiceError.message(L10n.tr("无法连接这台电脑，请检查桌面的直连访问设置")) }
    let binding = try JSONDecoder().decode(EasyWorkBinding.self, from: easyWorkJSON(try easyWorkObject(claimed)["binding"] ?? [:]))
    guard binding.target == host.target, binding.account == host.target.account, binding.desktopDeviceId == host.target.desktopDeviceId, binding.desktopPublicKey == host.publicKey, binding.mobileDeviceId == identity.deviceId, binding.mobilePublicKey == identity.publicKey, binding.status == "active" else { throw HostServiceError.wrongHost }
    let candidates = try directCandidates([url.absoluteString] + (host.endpoints ?? []) + offer.endpoints, hints: (host.endpointHints ?? []) + (offer.endpointHints ?? []), fingerprint: host.fingerprint)
    return (binding, candidates)
  }
  static func connectDiscovered(endpoint: String, name: String) async throws -> (EasyWorkBinding, [AgentConnectionCandidate]) {
    let host = try await readHost(endpoint: endpoint, timeout: 5)
    guard host.connectionMode == "automatic" else { throw HostServiceError.message(L10n.tr("请更新电脑端后使用直接连接")) }
    let offer = EasyWorkLANOffer(endpoints: [endpoint], fingerprint: host.fingerprint, publicKey: host.publicKey, target: host.target, offerId: "", pairingCode: "", expiresAt: "", connectionMode: "automatic", endpointHints: host.endpointHints)
    return try await connect(endpoint: endpoint, name: name, offer: offer)
  }
}

/// A saved peer is rediscovered by pinned reads, never by creating a new authorization.
@MainActor final class EasyWorkKnownHosts: ObservableObject {
  static let shared = EasyWorkKnownHosts()
  struct Host: Identifiable {
    var id: String; var name: String; var deviceId: String; var endpoint: String
    var networkKind: AgentConnectionCandidate.NetworkKind?; var reachable: Bool
  }
  private struct Probe: Sendable {
    var clientId: String; var candidate: AgentConnectionCandidate; var binding: EasyWorkBinding
  }
  private struct Result: Sendable {
    var probe: Probe; var candidates: [AgentConnectionCandidate]
  }
  @Published private(set) var hosts: [Host] = []
  private weak var store: SessionStore?
  private var generation = UUID()
  private var scanning: UUID?
  private var active = false
  private var monitor: NWPathMonitor?
  private var bonjour: [String: String] = [:]
  func stop() {
    active = false; generation = UUID(); monitor?.cancel(); monitor = nil
  }
  func offerBonjour(endpoint: String, deviceId: String?) {
    guard let deviceId, store?.saved.clients.contains(where: { $0.agentConnection?.target.hostId == deviceId }) == true else { return }
    bonjour[deviceId] = endpoint
    if active, let store { Task { await scan(store) } }
  }
  func run(_ store: SessionStore) async {
    self.store = store; active = true; generation = UUID()
    let runGeneration = generation
    let monitor = NWPathMonitor(); self.monitor = monitor
    monitor.pathUpdateHandler = { [weak self] _ in Task { @MainActor in
      guard let self, self.active, self.generation == runGeneration, let store = self.store else { return }
      await self.scan(store)
    } }
    monitor.start(queue: DispatchQueue(label: "easywork-known-host-path"))
    defer { if generation == runGeneration { active = false; generation = UUID(); monitor.cancel(); self.monitor = nil } }
    while !Task.isCancelled {
      await scan(store)
      if store.client?.agentConnection != nil, store.client?.online == false, !Task.isCancelled { await store.refresh() }
      do { try await Task.sleep(for: .seconds(10)) } catch { return }
    }
  }
  func scan(_ store: SessionStore) async {
    guard active, scanning != generation else { return }
    let scanGeneration = generation
    scanning = scanGeneration; defer { if scanning == scanGeneration { scanning = nil } }
    let clients = store.saved.clients.filter { $0.agentConnection?.target.agentId == "easywork" }
    hosts = clients.compactMap { client in
      guard let connection = client.agentConnection, let endpoint = connection.candidates.first(where: { $0.transportId == "lan-wss" }) else { return nil }
      let previous = hosts.first { $0.id == client.id }
      return Host(id: client.id, name: client.name, deviceId: connection.target.hostId, endpoint: previous?.endpoint ?? endpoint.endpoint, networkKind: previous?.networkKind ?? endpoint.networkKind, reachable: previous?.reachable ?? false)
    }
    var probes: [Probe] = []
    for client in clients.sorted(by: { $0.id == store.client?.id && $1.id != store.client?.id }) {
      guard let connection = client.agentConnection, connection.preference != .relayOnly,
        let data = CredentialVault.token(connection.credentialRef).data(using: .utf8),
        let credential = try? JSONDecoder().decode(EasyWorkCredential.self, from: data),
        connection.target.account == credential.binding.target.account, connection.target.hostId == credential.binding.target.desktopDeviceId,
        connection.target.workspaceId == credential.binding.target.workspaceId, connection.target.runtimeId == credential.binding.target.runtimeId else { continue }
      var candidates = connection.candidates.filter { $0.transportId == "lan-wss" && $0.peerFingerprint != nil }
      if let endpoint = bonjour[connection.target.hostId], let pin = candidates.first?.peerFingerprint,
        let candidate = try? EasyWorkPairing.directCandidates([endpoint], fingerprint: pin).first,
        !candidates.contains(where: { $0.endpoint == candidate.endpoint }) { candidates.insert(candidate, at: 0) }
      probes += candidates.prefix(32).map { Probe(clientId: client.id, candidate: $0, binding: credential.binding) }
    }
    await withTaskGroup(of: Result?.self) { group in
      var index = 0
      var found = Set<String>()
      func enqueue() {
        while index < probes.count && found.contains(probes[index].clientId) { index += 1 }
        guard index < probes.count else { return }
        let probe = probes[index]; index += 1
        group.addTask {
          do {
            let host = try await EasyWorkPairing.readHost(endpoint: probe.candidate.endpoint, fingerprint: probe.candidate.peerFingerprint, target: probe.binding.target, publicKey: probe.binding.desktopPublicKey)
            let candidates = try EasyWorkPairing.directCandidates([probe.candidate.endpoint] + (host.endpoints ?? []), hints: host.endpointHints ?? [], fingerprint: host.fingerprint)
            return Result(probe: probe, candidates: candidates)
          } catch { return nil }
        }
      }
      for _ in 0..<4 { enqueue() }
      while let result = await group.next() {
        guard active, generation == scanGeneration, !Task.isCancelled else { group.cancelAll(); return }
        if let result, !found.contains(result.probe.clientId) {
          found.insert(result.probe.clientId)
          if await store.updateEasyWorkCandidates(result.probe.clientId, binding: result.probe.binding, candidates: result.candidates) {
            guard active, generation == scanGeneration, !Task.isCancelled else { group.cancelAll(); return }
            if let i = hosts.firstIndex(where: { $0.id == result.probe.clientId }) {
              hosts[i].reachable = true; hosts[i].endpoint = result.probe.candidate.endpoint
              hosts[i].networkKind = result.candidates.first?.networkKind
            }
          }
        }
        enqueue()
      }
      guard active, generation == scanGeneration, !Task.isCancelled else { return }
      for index in hosts.indices where !found.contains(hosts[index].id) { hosts[index].reachable = false }
    }
  }
}

extension SessionStore {
  func updateEasyWorkCandidates(_ clientId: String, binding: EasyWorkBinding, candidates: [AgentConnectionCandidate]) async -> Bool {
    guard let index = saved.clients.firstIndex(where: { $0.id == clientId }),
      var connection = saved.clients[index].agentConnection,
      let credential = try? JSONDecoder().decode(EasyWorkCredential.self, from: Data(CredentialVault.token(connection.credentialRef).utf8)),
      credential.binding == binding, connection.target.account == binding.target.account,
      connection.target.hostId == binding.target.desktopDeviceId, connection.target.workspaceId == binding.target.workspaceId,
      connection.target.runtimeId == binding.target.runtimeId else { return false }
    let old = connection.candidates.filter { $0.transportId == "lan-wss" && $0.peerFingerprint == candidates.first?.peerFingerprint }
    let hints = (candidates + old).compactMap { value in value.networkKind.map { EasyWorkEndpointHint(endpoint: value.endpoint, networkKind: $0) } }
    guard let direct = try? EasyWorkPairing.directCandidates(candidates.map(\.endpoint) + old.map(\.endpoint), hints: hints, fingerprint: candidates.first?.peerFingerprint ?? "") else { return false }
    let merged = direct + connection.candidates.filter { $0.transportId != "lan-wss" }
    if merged != connection.candidates {
      connection.candidates = merged
      saved.clients[index].agentConnection = connection
      persist()
    }
    await EasyWorkSessions.shared.updateCandidates(clientId, connection: connection)
    return true
  }
}
