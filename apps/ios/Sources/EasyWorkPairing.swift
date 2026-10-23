import AuthenticationServices
import CryptoKit
import Foundation
import SwiftUI
import VisionKit

struct EasyWorkLANOffer: Decodable {
  var endpoints: [String]
  var fingerprint: String
  var publicKey: String
  var target: EasyWorkTarget
  var offerId: String
  var pairingCode: String
  var expiresAt: String
  var connectionMode: String?
  var endpointHints: [EasyWorkEndpointHint]?
  static func parse(_ text: String) throws -> Self {
    guard let uri = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), uri.scheme == "easywork-host", uri.host == "pair", let encoded = uri.queryItems?.first(where: { $0.name == "descriptor" })?.value, let data = Data(base64URL: encoded), data.count < 16384 else { throw HostServiceError.message(L10n.tr("请扫描或粘贴桌面的局域网配对二维码")) }
    let value = try JSONDecoder().decode(Self.self, from: data)
    let date = ISO8601DateFormatter(); date.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    guard value.fingerprint.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil, value.publicKey.count == 43, value.pairingCode.range(of: "^[0-9]{6}$", options: .regularExpression) != nil, let expiry = date.date(from: value.expiresAt), value.connectionMode == "automatic" || expiry > Date(), !value.endpoints.isEmpty, value.endpoints.count <= 32, value.endpoints.allSatisfy({ (try? EasyWorkPairing.localEndpoint($0)) != nil }) else { throw HostServiceError.message(L10n.tr("连接信息无效或已过期，请在桌面重新生成")) }
    return value
  }
}

enum EasyWorkPairing {
  static func lan(_ text: String, name: String) async throws -> (EasyWorkBinding, [AgentConnectionCandidate]) {
    let offer = try EasyWorkLANOffer.parse(text)
    let identity = try EasyWorkIdentity.load()
    var lastError: Error = HostServiceError.unavailable
    for endpoint in offer.endpoints {
      if offer.connectionMode == "automatic" {
        do {
          let connected = try await connect(endpoint: endpoint, name: name, offer: offer)
          guard connected.0.target == offer.target, connected.0.desktopPublicKey == offer.publicKey, connected.1.first?.peerFingerprint == offer.fingerprint.lowercased() else { throw HostServiceError.wrongHost }
          return connected
        }
        catch { lastError = error; continue }
      }
      let delegate = PairedHostTrust(endpoint: endpoint, fingerprint: offer.fingerprint)
      let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
      defer { session.invalidateAndCancel() }
      do {
        let host = try await readHost(endpoint: endpoint, fingerprint: offer.fingerprint, target: offer.target, publicKey: offer.publicKey)
        var request = HostService.request(try HostService.baseURL(endpoint).appendingPathComponent("pairing/claim"), method: "POST", body: try easyWorkJSON(["offerId": offer.offerId, "code": offer.pairingCode, "mobileDeviceId": identity.deviceId, "publicKey": identity.publicKey, "name": name]))
        request.timeoutInterval = 90 // Desktop confirmation is part of this exchange.
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse, response.statusCode == 200 else { throw HostServiceError.message(L10n.tr("桌面拒绝配对或配对码已失效")) }
        let value = try easyWorkObject(data)
        let binding = try JSONDecoder().decode(EasyWorkBinding.self, from: easyWorkJSON(value["binding"] ?? [:]))
        guard binding.target == offer.target, binding.desktopPublicKey == offer.publicKey, binding.mobileDeviceId == identity.deviceId, binding.mobilePublicKey == identity.publicKey, binding.status == "active" else { throw HostServiceError.wrongHost }
        return (binding, try directCandidates([endpoint] + offer.endpoints + (host.endpoints ?? []), hints: (host.endpointHints ?? []) + (offer.endpointHints ?? []), fingerprint: offer.fingerprint))
      } catch { lastError = error; if case HostServiceError.message = error { throw error } }
    }
    throw lastError
  }
  static func relay(endpoint: String, code: String, token: String) async throws -> EasyWorkBinding {
    let identity = try EasyWorkIdentity.load()
    let api = try RelayAgentTransport(candidate: AgentConnectionCandidate(id: "relay", transportId: "relay-http", endpoint: endpoint), token: token)
    let claimed = try easyWorkObject(await api.api("/pairing/claim", method: "POST", body: ["pairingCode": code]))
    let pending = try JSONDecoder().decode(EasyWorkBinding.self, from: easyWorkJSON(claimed))
    guard pending.mobileDeviceId == identity.deviceId, pending.mobilePublicKey == identity.publicKey else { throw HostServiceError.wrongHost }
    if pending.status == "active" { return pending }
    let deadline = Date().addingTimeInterval(90)
    while Date() < deadline {
      let value = try easyWorkObject(await api.api("/bindings"))
      let bindings = try JSONDecoder().decode([EasyWorkBinding].self, from: easyWorkJSON(value["bindings"] ?? []))
      if let binding = bindings.first(where: { $0.bindingId == pending.bindingId }) {
        if binding.status == "active" { return binding }
        if binding.status == "revoked" { throw HostServiceError.message(L10n.tr("桌面拒绝了配对")) }
      }
      try await Task.sleep(for: .milliseconds(500))
    }
    throw HostServiceError.message(L10n.tr("等待桌面确认超时"))
  }
}

struct EasyWorkOIDCSession: Codable {
  var accessToken: String
  var refreshToken: String?
  var expiresAt: Date
  var tokenEndpoint: String
  var clientId: String
  var audience: String
}
/// Mirrors the existing mobile OIDC device-bound PKCE login, with secrets in Keychain.
@MainActor final class EasyWorkLogin: NSObject, ASWebAuthenticationPresentationContextProviding {
  static let shared = EasyWorkLogin()
  private var browser: ASWebAuthenticationSession?
  func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
  }
  func login(issuer: String, clientId: String, audience: String) async throws -> String {
    guard let issuerURL = URL(string: issuer), issuerURL.scheme == "https" else { throw HostServiceError.invalidAddress }
    let (data, response) = try await URLSession.shared.data(from: issuerURL.appendingPathComponent(".well-known/openid-configuration"))
    guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw HostServiceError.unavailable }
    let discovery = try easyWorkObject(data)
    guard let authorization = discovery["authorization_endpoint"] as? String, let tokenEndpoint = discovery["token_endpoint"] as? String, var url = URLComponents(string: authorization), url.scheme == "https", URL(string: tokenEndpoint)?.scheme == "https" else { throw HostServiceError.invalidResponse }
    let identity = try EasyWorkIdentity.load()
    let verifier = (0..<2).map { _ in UUID().uuidString.replacingOccurrences(of: "-", with: "") }.joined()
    let state = UUID().uuidString
    let redirect = "com.easyai.remote://oauth/callback"
    var values = Self.deviceFields(identity)
    values.merge(["response_type": "code", "client_id": clientId, "redirect_uri": redirect, "scope": "openid profile offline_access", "audience": audience, "state": state, "code_challenge": Data(SHA256.hash(data: Data(verifier.utf8))).base64URL, "code_challenge_method": "S256"]) { _, rhs in rhs }
    url.queryItems = values.map { URLQueryItem(name: $0.key, value: $0.value) }
    guard let authorizationURL = url.url else { throw HostServiceError.invalidAddress }
    let callback: URL = try await withCheckedThrowingContinuation { continuation in
      browser = ASWebAuthenticationSession(url: authorizationURL, callbackURLScheme: "com.easyai.remote") { url, error in
        if let url { continuation.resume(returning: url) } else { continuation.resume(throwing: error ?? HostServiceError.unavailable) }
      }
      browser?.presentationContextProvider = self
      if browser?.start() != true { continuation.resume(throwing: HostServiceError.unavailable) }
    }
    browser = nil
    let query = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
    guard callback.host == "oauth", callback.path == "/callback", query.first(where: { $0.name == "state" })?.value == state, let code = query.first(where: { $0.name == "code" })?.value else { throw HostServiceError.message(L10n.tr("登录状态校验失败")) }
    var fields = Self.deviceFields(identity)
    fields.merge(["grant_type": "authorization_code", "client_id": clientId, "redirect_uri": redirect, "code": code, "code_verifier": verifier, "audience": audience]) { _, rhs in rhs }
    let session = try await Self.exchange(endpoint: tokenEndpoint, fields: fields, clientId: clientId, audience: audience)
    try Self.save(session)
    return session.accessToken
  }
  static func validToken(fallback: String?) async throws -> String {
    let saved = CredentialVault.token("easywork-oidc-session")
    guard let data = saved.data(using: .utf8), let session = try? JSONDecoder().decode(EasyWorkOIDCSession.self, from: data) else { guard let fallback, !fallback.isEmpty else { throw HostServiceError.message(L10n.tr("请先登录 EasyAI")) }; return fallback }
    if session.expiresAt > Date().addingTimeInterval(60) { return session.accessToken }
    guard let refresh = session.refreshToken else { throw HostServiceError.message(L10n.tr("请重新登录 EasyAI")) }
    var fields = deviceFields(try EasyWorkIdentity.load())
    fields.merge(["grant_type": "refresh_token", "refresh_token": refresh, "client_id": session.clientId, "audience": session.audience]) { _, rhs in rhs }
    var next = try await exchange(endpoint: session.tokenEndpoint, fields: fields, clientId: session.clientId, audience: session.audience)
    next.refreshToken = next.refreshToken ?? refresh
    try save(next); return next.accessToken
  }
  private static func deviceFields(_ identity: EasyWorkIdentity) -> [String: String] { ["device_id": identity.deviceId, "device_platform": "ios", "device_public_key": identity.publicKey] }
  private static func save(_ session: EasyWorkOIDCSession) throws { try CredentialVault.set(String(decoding: JSONEncoder().encode(session), as: UTF8.self), id: "easywork-oidc-session") }
  private static func exchange(endpoint: String, fields: [String: String], clientId: String, audience: String) async throws -> EasyWorkOIDCSession {
    guard let url = URL(string: endpoint), url.scheme == "https" else { throw HostServiceError.invalidAddress }
    var form = URLComponents(); form.queryItems = fields.map { URLQueryItem(name: $0.key, value: $0.value) }
    var request = URLRequest(url: url); request.httpMethod = "POST"; request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "content-type")
    request.httpBody = Data((form.percentEncodedQuery ?? "").replacingOccurrences(of: "+", with: "%2B").utf8)
    let (data, response) = try await URLSession.shared.data(for: request)
    guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw HostServiceError.message(L10n.tr("EasyAI 登录失效，请重新登录")) }
    let value = try easyWorkObject(data)
    guard let token = value["access_token"] as? String, !token.isEmpty else { throw HostServiceError.invalidResponse }
    return EasyWorkOIDCSession(accessToken: token, refreshToken: value["refresh_token"] as? String, expiresAt: Date().addingTimeInterval(value["expires_in"] as? Double ?? 900), tokenEndpoint: endpoint, clientId: clientId, audience: audience)
  }
}

enum AgentCache {
  private static let prefix = Data("agent-cache-v1\n".utf8)
  private static let keyLock = NSLock()
  private static func key(create: Bool) throws -> SymmetricKey {
    keyLock.lock(); defer { keyLock.unlock() }
    if let stored = try CredentialVault.existingToken("agent-cache-key-v1") {
      guard let data = Data(base64URL: stored), data.count == 32 else { throw HostServiceError.invalidResponse }
      return SymmetricKey(data: data)
    }
    guard create else { throw HostServiceError.message(L10n.tr("本地数据密钥不可用，已保留原数据。")) }
    let key = SymmetricKey(size: .bits256)
    try CredentialVault.set(key.withUnsafeBytes { Data($0).base64URL }, id: "agent-cache-key-v1")
    return key
  }
  static func seal<T: Encodable>(_ value: T) throws -> Data { let box = try AES.GCM.seal(JSONEncoder().encode(value), using: key(create: true), authenticating: prefix); guard let combined = box.combined else { throw HostServiceError.invalidResponse }; return prefix + combined }
  static func open<T: Decodable>(_ data: Data, as type: T.Type) throws -> T {
    if !data.starts(with: prefix) { return try JSONDecoder().decode(type, from: data) }
    let box = try AES.GCM.SealedBox(combined: data.dropFirst(prefix.count))
    return try JSONDecoder().decode(type, from: AES.GCM.open(box, using: key(create: false), authenticating: prefix))
  }
  static func encode(_ value: SavedSession) throws -> Data { try seal(value) }
  static func decode(_ data: Data) throws -> SavedSession {
    try open(data, as: SavedSession.self)
  }
}

extension SessionStore {
  private func bindingPin(_ candidates: [AgentConnectionCandidate]) -> String? { candidates.first(where: { $0.transportId == "lan-wss" })?.peerFingerprint }
  func saveEasyWork(name: String, binding: EasyWorkBinding, candidates: [AgentConnectionCandidate], token: String? = nil, grantRef: String? = nil) async throws {
    let identity = try EasyWorkIdentity.load()
    let target = AgentRuntimeTarget(agentId: "easywork", hostId: binding.desktopDeviceId, workspaceId: binding.target.workspaceId, runtimeId: binding.target.runtimeId, account: binding.account)
    // Full identity is the cache/draft/receipt scope; endpoint changes cannot change it.
    let encoded = try JSONEncoder().encode(target)
    let id = "easywork-" + HostService.hash(try easyWorkJSON(JSONSerialization.jsonObject(with: encoded)))
    let existing = saved.clients.first(where: { $0.id == id })
    let ref = existing?.agentConnection?.credentialRef ?? "agent-" + id
    var credential = (try? JSONDecoder().decode(EasyWorkCredential.self, from: Data(CredentialVault.token(ref).utf8))) ?? EasyWorkCredential(binding: binding, privateKey: identity.privateKey)
    let replacingGrantWithExplicitLegacyPairing = grantRef == nil && credential.grantRef != nil
    if replacingGrantWithExplicitLegacyPairing { credential = EasyWorkCredential(binding: binding, privateKey: identity.privateKey) }
    if let grantRef { credential = EasyWorkCredential(binding: binding, privateKey: identity.privateKey, relayBinding: binding, grantRef: grantRef) }
    else if candidates.first?.transportId == "relay-http" { credential.relayBinding = binding; credential.accessToken = token }
    else { credential.binding = binding }
    let previousCredential = CredentialVault.token(ref)
    let oldDirect = (existing?.agentConnection?.candidates ?? []).filter { $0.transportId == "lan-wss" && (bindingPin(candidates) == nil || $0.peerFingerprint == bindingPin(candidates)) }
    let oldHints = oldDirect.compactMap { value in value.networkKind.map { EasyWorkEndpointHint(endpoint: value.endpoint, networkKind: $0) } }
    let direct = try EasyWorkPairing.directCandidates(candidates.filter { $0.transportId == "lan-wss" }.map(\.endpoint) + oldDirect.map(\.endpoint), hints: oldHints + candidates.compactMap { value in value.networkKind.map { EasyWorkEndpointHint(endpoint: value.endpoint, networkKind: $0) } }, fingerprint: bindingPin(candidates) ?? oldDirect.first?.peerFingerprint ?? "")
    let merged = (replacingGrantWithExplicitLegacyPairing ? [] : existing?.agentConnection?.candidates ?? []).filter { $0.transportId != "lan-wss" && !Set(candidates.map(\.id)).contains($0.id) } + candidates.filter { $0.transportId != "lan-wss" } + direct
    var connection = AgentConnection(target: target, candidates: merged, preference: existing?.agentConnection?.preference ?? .automatic, credentialRef: ref)
    var client = Client(id: id, name: name, endpoint: candidates.first?.endpoint, isDemo: false, online: false, agentConnection: connection)
    // Validate the new peer without overwriting an existing trusted credential.
    invalidateAgentConnection(id)
    if let existing { await (try AgentClients.resolve(existing)).close(existing) }
    // Pairing must prove the incoming authorization, irrespective of a saved
    // path preference. A previous Relay consumer must close before this check.
    let incomingConnection = AgentConnection(target: target, candidates: candidates, preference: .automatic, credentialRef: ref)
    let validation = try EasyWorkRemoteSession(connection: incomingConnection, credential: credential)
    do {
      let context = try easyWorkObject(await validation.request("runtime/context", [:]))
      let actual = try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(context["target"] as Any))
      guard actual == binding.target else { throw HostServiceError.wrongHost }
      if grantRef != nil {
        let routes = context["connectionCandidates"] as? [[String: Any]] ?? []
        guard routes.count <= 32 else { throw HostServiceError.invalidResponse }
        var authenticated: [AgentConnectionCandidate] = []
        for route in routes {
          guard route["transportId"] as? String == "lan-wss", let endpoint = route["endpoint"] as? String, let pin = route["peerFingerprint"] as? String, pin.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil else { throw HostServiceError.invalidResponse }
          authenticated += try EasyWorkPairing.directCandidates([endpoint], fingerprint: pin)
        }
        let endpoints = Set(authenticated.map(\.endpoint))
        connection.candidates = authenticated + connection.candidates.filter { !endpoints.contains($0.endpoint) }
        client.agentConnection = connection
      }
      await validation.close()
    }
    catch { await validation.close(); throw error }
    try CredentialVault.set(String(decoding: JSONEncoder().encode(credential), as: UTF8.self), id: ref)
    let snapshot: WorkspaceSnapshot
    do { snapshot = try await AgentClients.resolve(client).workspace(client, projectCursor: nil, threadCursor: nil) }
    catch {
      await (try AgentClients.resolve(client)).close(client)
      try CredentialVault.set(previousCredential, id: ref)
      throw error
    }
    client.online = true
    if let index = saved.clients.firstIndex(where: { $0.id == id }) { saved.clients[index] = client } else { saved.clients.append(client) }
    saved.workspaces[id] = snapshot
    pendingConnection = nil
    selectClient(id); persist()
  }
  func setConnectionPreference(_ client: Client, _ preference: AgentConnectionPreference) {
    guard let index = saved.clients.firstIndex(where: { $0.id == client.id }) else { return }
    saved.clients[index].agentConnection?.preference = preference
    invalidateAgentConnection(client.id)
    persist(); Task { if let provider = try? AgentClients.resolve(client) { await provider.close(client) }; await refresh() }
  }
  func renameAgentClient(_ id: String, name: String) {
    guard let index = saved.clients.firstIndex(where: { $0.id == id }), !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
    saved.clients[index].name = name; persist()
  }
}

struct EasyWorkConnectionForm: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  @Environment(\.dismiss) private var dismiss
  var clientToEdit: Client?
  @State private var name = "EasyWork"
  @State private var pairingURI = ""
  @State private var relayURL = ""
  @State private var code = ""
  @State private var pairingStatus: String?
  @State private var token = ""
  @StateObject private var discovery = EasyWorkDiscovery()
  @State private var scanning = false
  @State private var manualAddress = ""
  @State private var busy = false
  @State private var error: String?
  private enum ConnectionMethod: String, CaseIterable {
    case discovery = "自动发现"
    case qr = "扫码连接"
    case manual = "手动连接"
    case relay = "通过中继连接"

  var title: String {
    switch self {
    case .discovery: return L10n.tr("自动发现")
    case .qr: return L10n.tr("扫码连接")
    case .manual: return L10n.tr("手动连接")
    case .relay: return L10n.tr("通过中继连接")
    }
  }
}
  @State private var connectionMethod = ConnectionMethod.discovery
  var body: some View {
    Group {
      Section("EasyWork") {
        TextField(L10n.tr("名称"), text: $name).accessibilityIdentifier("easywork-name")
          .onChange(of: name) { _, value in if let clientToEdit { store.renameAgentClient(clientToEdit.id, name: value) } }
        if let clientToEdit, let connection = clientToEdit.agentConnection {
          Picker(L10n.tr("连接偏好"), selection: Binding(get: { store.saved.clients.first(where: { $0.id == clientToEdit.id })?.agentConnection?.preference ?? connection.preference }, set: { store.setConnectionPreference(clientToEdit, $0) })) {
            ForEach(AgentConnectionPreference.allCases, id: \.self) { Text($0.title).tag($0) }
          }.accessibilityIdentifier("connection-preference")
        }
        Picker(L10n.tr("连接方式"), selection: $connectionMethod) {
          ForEach(ConnectionMethod.allCases, id: \.self) { Text($0.title).tag($0) }
        }.pickerStyle(.menu).accessibilityIdentifier("easywork-connection-method")
        if let error { Text(error).foregroundStyle(.red).accessibilityIdentifier("easywork-pair-error") }
      }
      if busy, let pairingStatus { Section { ProgressView(pairingStatus).accessibilityIdentifier("easywork-connecting") } }
      switch connectionMethod {
      case .discovery: discoveredHosts
      case .qr:
        Section {
          Button(L10n.tr("扫描电脑二维码"), systemImage: "qrcode.viewfinder") {
            if DataScannerViewController.isSupported && DataScannerViewController.isAvailable { scanning = true }
            else { error = L10n.tr("当前设备无法使用相机，请粘贴电脑提供的连接链接。") }
          }.accessibilityIdentifier("easywork-scan")
          TextField(L10n.tr("或粘贴连接二维码内容"), text: $pairingURI)
            .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("easywork-qr-description")
          Button(L10n.tr("连接电脑")) { connectLink() }.disabled(pairingURI.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .accessibilityIdentifier("easywork-pair-lan")
        } header: { Text(L10n.tr("扫码连接")) } footer: { Text(L10n.tr("首次连接扫描电脑二维码，之后自动发现局域网与 VPN 入口。")) }
      case .manual:
        Section {
          TextField(L10n.tr("电脑地址"), text: $manualAddress).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("easywork-manual-address")
          Button(L10n.tr("连接已保存的电脑")) { connectKnown(manualAddress) }.disabled(manualAddress.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty).accessibilityIdentifier("easywork-manual-connect")
        } header: { Text(L10n.tr("手动连接")) } footer: { Text(L10n.tr("填写已配对电脑的地址；首次接入请使用电脑提供的连接二维码。")) }
      case .relay:
        Section(L10n.tr("通过中继连接")) {
          TextField(L10n.tr("中继服务地址"), text: $relayURL).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("easywork-relay-url")
          #if DEBUG
          #if targetEnvironment(simulator)
          if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev") {
            Button(L10n.tr("使用本地 DEV 登录")) { perform {
              let endpoint = try HostService.baseURL(relayURL)
              guard endpoint.scheme == "http", ["127.0.0.1", "localhost"].contains(endpoint.host ?? "") else { throw HostServiceError.invalidAddress }
              let identity = try EasyWorkIdentity.load()
              token = "easyai-ios-simulator-v1." + (try easyWorkJSON(["deviceId": identity.deviceId, "publicKey": identity.publicKey])).base64URL
            } }.accessibilityIdentifier("easywork-dev-login")
            SecureField(L10n.tr("本地 DEV 凭据"), text: $token).textInputAutocapitalization(.never).accessibilityIdentifier("easywork-dev-token")
          }
          #endif
          #endif
          connectionCodeField
          Button(L10n.tr("连接电脑")) { connectDeviceCode() }.disabled(code.isEmpty || relayURL.isEmpty).accessibilityIdentifier("easywork-pair-relay")
          Text(L10n.tr("输入电脑显示的连接码，再在已登录的电脑上确认此设备。手机无需登录账号。")).font(.caption).foregroundStyle(.secondary)
        }
      }
      if busy && pairingStatus == nil { ProgressView(L10n.tr("正在连接…")).accessibilityIdentifier("easywork-connecting") }
    }.disabled(busy).interactiveDismissDisabled(busy)
      .sheet(isPresented: $scanning) { EasyWorkQRScanner { pairingURI = $0; scanning = false; connectLink() }.ignoresSafeArea() }
      .onAppear {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev") {
          pairingURI = ProcessInfo.processInfo.environment["EASYWORK_DEV_LAN_OFFER"] ?? pairingURI
          relayURL = ProcessInfo.processInfo.environment["EASYWORK_DEV_RELAY_URL"] ?? relayURL
          code = ProcessInfo.processInfo.environment["EASYWORK_DEV_RELAY_CODE"] ?? code
        }
        #endif
        applyIncomingLink(); discovery.start(); Task { await EasyWorkKnownHosts.shared.scan(store); await VPNHostDiscovery.shared.scan(store) }
        name = clientToEdit?.name ?? "EasyWork"
        relayURL = clientToEdit?.agentConnection?.candidates.first(where: { $0.transportId == "relay-http" })?.endpoint ?? relayURL
        if relayURL.isEmpty { relayURL = "https://51easyai.com" }
      }.onDisappear { discovery.stop() }
      .onChange(of: store.pendingConnection?.presentation) { _, _ in applyIncomingLink() }
      .onChange(of: connectionMethod) { _, _ in
        error = nil
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
      }
  }
  private var discoveryRows: [EasyWorkDiscovery.Host] {
    let saved = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target.agentId == "easywork" }
    return saved.map { client in
      let found = discovery.hosts.first { $0.knownClientId == client.id || $0.deviceId == client.agentConnection?.target.hostId }
      return EasyWorkDiscovery.Host(id: client.id, name: client.name,
        endpoint: found?.endpoint ?? client.endpoint ?? "", knownClientId: client.id,
        detail: client.online ? L10n.tr("已连接") : L10n.tr("未连接 · ") + store.connectionLabel(for: client), deviceId: client.agentConnection?.target.hostId)
    } + discovery.hosts.filter { host in !saved.contains { $0.id == host.knownClientId || $0.agentConnection?.target.hostId == host.deviceId } }
  }
  private var discoveredHosts: some View {
    Section(L10n.tr("自动发现")) {
      if discoveryRows.isEmpty { Text(L10n.tr("正在查找同一网络的电脑…")).foregroundStyle(.secondary) }
      ForEach(discoveryRows) { host in
        Button {
          if let id = host.knownClientId {
            if store.saved.clients.first(where: { $0.id == id })?.online == true { store.selectClient(id); dismiss() }
            else { perform { store.selectClient(id, refreshAutomatically: false); await store.refresh(); guard store.client?.online == true else { throw HostServiceError.unavailable }; dismiss() } }
          } else { perform {
            let (binding, candidates) = try await EasyWorkPairing.connectDiscovered(endpoint: host.endpoint, name: UIDevice.current.name)
            try await store.saveEasyWork(name: host.name, binding: binding, candidates: candidates)
            dismiss()
          } }
        } label: {
          HStack {
            Image(systemName: "desktopcomputer")
            VStack(alignment: .leading, spacing: 4) {
              Text(host.name)
              Text(host.detail ?? L10n.tr("未连接 · 点选连接")).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if store.saved.clients.first(where: { $0.id == host.knownClientId })?.online == true { Image(systemName: "checkmark").foregroundStyle(.green) }
          }
        }.buttonStyle(.plain).foregroundStyle(Color.primary).disabled(busy)
          .accessibilityIdentifier("easywork-discovered-" + host.id).accessibilityValue(host.endpoint)
      }
      Button(L10n.tr("重新查找"), systemImage: "arrow.clockwise") { Task { await EasyWorkKnownHosts.shared.scan(store); await VPNHostDiscovery.shared.scan(store) } }
    }
  }
  private func applyIncomingLink() {
    guard let pending = store.pendingConnection, pending.agentId == "easywork" else { return }
    pairingURI = pending.payload
    connectionMethod = .qr
  }
  private func connectKnown(_ endpoint: String) {
    perform {
      let normalized = try EasyWorkPairing.localEndpoint(endpoint).absoluteString
      if let known = store.saved.clients.first(where: { client in
        client.agentConnection?.target.agentId == "easywork" && client.agentConnection?.candidates.contains(where: { $0.transportId == "lan-wss" && (try? EasyWorkPairing.localEndpoint($0.endpoint).absoluteString) == normalized }) == true
      }) {
        store.selectClient(known.id, refreshAutomatically: false); await store.refresh()
      } else {
        let (binding, candidates) = try await EasyWorkPairing.connectDiscovered(endpoint: normalized, name: UIDevice.current.name)
        try await store.saveEasyWork(name: name, binding: binding, candidates: candidates)
      }
      guard store.client?.online == true else { throw HostServiceError.unavailable }
      dismiss()
    }
  }
  private func connectLink() {
    perform {
      if URLComponents(string: pairingURI)?.scheme == "easywork" {
        let offer = try EasyWorkDeviceOffer.parse(pairingURI)
        try await pairDevice(offer)
        dismiss(); return
      }
      let (binding, candidates) = try await EasyWorkPairing.lan(pairingURI, name: UIDevice.current.name)
      try await store.saveEasyWork(name: name, binding: binding, candidates: candidates)
      dismiss()
    }
  }
  private func connectDeviceCode() {
    perform {
      #if DEBUG
      #if targetEnvironment(simulator)
      if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"), !token.isEmpty {
        let binding = try await EasyWorkPairing.relay(endpoint: relayURL, code: code, token: token)
        try await store.saveEasyWork(name: name, binding: binding, candidates: [AgentConnectionCandidate(id: "relay", transportId: "relay-http", endpoint: relayURL)], token: token)
        dismiss(); return
      }
      #endif
      #endif
      let offer = EasyWorkDeviceOffer(origin: try EasyWorkDeviceOffer.origin(relayURL), code: try EasyWorkDeviceOffer.code(code))
      try await pairDevice(offer); dismiss()
    }
  }
  @ViewBuilder private var connectionCodeField: some View {
    #if DEBUG
    #if targetEnvironment(simulator)
    if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"), !token.isEmpty {
      SecureField(L10n.tr("本地 DEV 电脑连接码"), text: $code).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("easywork-relay-code")
    } else {
      TextField(L10n.tr("电脑连接码"), text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled().accessibilityIdentifier("easywork-relay-code")
    }
    #else
    TextField(L10n.tr("电脑连接码"), text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled().accessibilityIdentifier("easywork-relay-code")
    #endif
    #else
    TextField(L10n.tr("电脑连接码"), text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled().accessibilityIdentifier("easywork-relay-code")
    #endif
  }
  private func pairDevice(_ offer: EasyWorkDeviceOffer) async throws {
    let (binding, ref) = try await EasyWorkDeviceAuthorization.shared.pair(offer, deviceName: UIDevice.current.name) { pairingStatus = $0 }
    try await store.saveEasyWork(name: name, binding: binding, candidates: [AgentConnectionCandidate(id: "relay", transportId: "relay-http", endpoint: offer.origin)], grantRef: ref)
  }
  private func perform(_ work: @escaping @MainActor () async throws -> Void) { guard !busy else { return }; busy = true; error = nil; pairingStatus = nil; Task { do { try await work() } catch { self.error = error.localizedDescription }; busy = false } }
}

#if DEBUG
extension SessionStore {
  func importEasyWorkDEVInputs() {
    guard ProcessInfo.processInfo.arguments.contains("-easywork-local-dev") else { return }
    let imageCode = "IMG_" + ["2", "4", "5", "6", "7", "9"].shuffled().joined()
    let textCode = "TXT_" + String(Int.random(in: 100000...999999))
    let image = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 400)).image { context in
      UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: 1200, height: 400))
      ("Image verification code" as NSString).draw(at: CGPoint(x: 64, y: 32), withAttributes: [.font: UIFont.systemFont(ofSize: 48), .foregroundColor: UIColor.black])
      (String(imageCode) as NSString).draw(at: CGPoint(x: 64, y: 160), withAttributes: [.font: UIFont.monospacedSystemFont(ofSize: 140, weight: .bold), .foregroundColor: UIColor.black])
    }
    do {
      guard let png = image.pngData() else { throw HostServiceError.invalidResponse }
      try importData(png, name: "native-input.png", isImage: true, target: targetKey)
      try importData(Data("The verification code in this attachment is \(textCode).\n".utf8), name: "native-input.txt", isImage: false, target: targetKey)
      devAttachmentEvidence = "\(imageCode)|\(textCode)"
    } catch { self.error = error.localizedDescription }
  }
}
#endif
