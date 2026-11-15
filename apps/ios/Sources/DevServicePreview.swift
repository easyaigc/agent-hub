import SwiftUI
import WebKit
import Network
import CryptoKit

struct DevPreviewConfiguration: Codable, Sendable {
  var endpoint: String
  var token: String
  var target: AgentRuntimeTarget
  var threadId: String
  var url: String
  var computerName: String? = nil
  var client: Client? = nil
}
struct DevServiceSource: Identifiable { let client: Client; let threadId: String; var id: String { client.id + ":" + threadId } }
extension SessionStore {
  var supportsDevPreview: Bool { client.map { saved.workspaces[$0.id]?.capabilities?.contains("preview:http:v1") == true } ?? false }
  var devPreviewClient: Client? {
    guard var result = client else { return nil }
    result.readingTarget = mobileReadingTarget(result)
    return result
  }
}
private struct DevPreviewReceipt: Decodable { var id: String; var target: AgentRuntimeTarget; var threadId: String; var expiresAt: String }

/// Only the native layer holds Host credentials. Browser traffic uses a short lived cookie.
final class DevPreviewGateway: @unchecked Sendable {
  private let queue = DispatchQueue(label: "agent-hub.dev-preview")
  private let listener: NWListener
  private var connections: [UUID: (NWConnection, URLSessionWebSocketTask)] = [:]
  private var ready: CheckedContinuation<UInt16, Error>?
  private var stopped = false
  private var connectRequest: URLRequest?
  private var session: URLSession?
  private var ownsSession = false
  init() throws {
    let parameters = NWParameters.tcp
    parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
    listener = try NWListener(using: parameters)
  }
  func listen() async throws -> UInt16 {
    try await withCheckedThrowingContinuation { continuation in
      queue.async {
        self.ready = continuation
        self.listener.stateUpdateHandler = { state in
          switch state {
          case .ready:
            if let port = self.listener.port { self.ready?.resume(returning: port.rawValue); self.ready = nil }
          case .failed(let error): self.ready?.resume(throwing: error); self.ready = nil; self.stopOnQueue()
          case .cancelled: self.ready?.resume(throwing: CancellationError()); self.ready = nil
          default: break
          }
        }
        self.listener.newConnectionHandler = { self.accept($0) }
        self.listener.start(queue: self.queue)
      }
    }
  }
  func configure(_ request: URLRequest, session: URLSession, ownsSession: Bool = false) async {
    await withCheckedContinuation { continuation in queue.async { self.connectRequest = request; self.session = session; self.ownsSession = ownsSession; continuation.resume() } }
  }
  private func accept(_ connection: NWConnection) {
    guard !stopped, connections.count < 24, let request = connectRequest, let session else { connection.cancel(); return }
    let id = UUID(), socket = session.webSocketTask(with: request)
    socket.maximumMessageSize = 1024 * 1024
    connections[id] = (connection, socket)
    connection.stateUpdateHandler = { state in if case .failed = state { self.finish(id) } }
    connection.start(queue: queue); socket.resume()
    readBrowser(id); readHost(id)
  }
  private func readBrowser(_ id: UUID) {
    guard let (connection, socket) = connections[id] else { return }
    connection.receive(minimumIncompleteLength: 1, maximumLength: 32768) { data, _, complete, error in
      guard error == nil, !complete, let data, !data.isEmpty else { self.finish(id); return }
      socket.send(.data(data)) { error in self.queue.async { if error != nil { self.finish(id) } else { self.readBrowser(id) } } }
    }
  }
  private func readHost(_ id: UUID) {
    guard let (connection, socket) = connections[id] else { return }
    socket.receive { result in
      self.queue.async {
        guard case .success(.data(let data)) = result else { self.finish(id); return }
        connection.send(content: data, completion: .contentProcessed { error in self.queue.async { if error != nil { self.finish(id) } else { self.readHost(id) } } })
      }
    }
  }
  private func finish(_ id: UUID) { guard let pair = connections.removeValue(forKey: id) else { return }; pair.0.stateUpdateHandler = nil; pair.0.cancel(); pair.1.cancel(with: .goingAway, reason: nil) }
  private func stopOnQueue() {
    stopped = true
    ready?.resume(throwing: CancellationError()); ready = nil
    listener.newConnectionHandler = nil; listener.stateUpdateHandler = nil; listener.cancel()
    for id in Array(connections.keys) { finish(id) }
    if ownsSession { session?.invalidateAndCancel(); session = nil }
  }
  func stop() { queue.async { self.stopOnQueue() } }
}

@MainActor final class DevPreviewModel: ObservableObject {
  @Published var url: URL?
  @Published var error: String?
  var webView: WKWebView?
  let dataStore = WKWebsiteDataStore.nonPersistent()
  private var gateway: DevPreviewGateway?
  private var config: DevPreviewConfiguration?
  private var easyWorkChannel: EasyWorkRemoteSession?
  private var id: String?
  private var ended = false
  func start(_ config: DevPreviewConfiguration) async {
    guard gateway == nil, !ended else { return }
    self.config = config
    do {
      guard ["codex", "easywork"].contains(config.target.agentId), let source = URL(string: config.url), source.scheme == "http",
        source.user == nil, source.password == nil, let port = source.port, port >= 1024,
        ["localhost", "127.0.0.1", "0.0.0.0", "[::1]", "[::]", "::1", "::"].contains(source.host ?? "") else { throw HostServiceError.message(L10n.tr("仅支持带明确端口的本地 HTTP DEV 地址。")) }
      let gateway = try DevPreviewGateway(); self.gateway = gateway
      let localPort = try await gateway.listen()
      try Task.checkCancellation()
      let secret = SHA256.hash(data: Data((UUID().uuidString + UUID().uuidString).utf8)).map { String(format: "%02x", $0) }.joined()
      let id = UUID().uuidString; self.id = id
      let target = try JSONSerialization.jsonObject(with: JSONEncoder().encode(config.target))
      var serviceURL = URLComponents(url: source, resolvingAgainstBaseURL: false)!
      serviceURL.fragment = nil
      let payload: [String: Any] = ["id": id, "target": target, "threadId": config.threadId, "url": serviceURL.url!.absoluteString, "browserPort": localPort, "browserSecret": secret]
      let receipt: DevPreviewReceipt
      let session: URLSession
      var connect: URLRequest
      if config.target.agentId == "easywork" {
        guard let client = config.client, client.agentConnection?.target == config.target else { throw HostServiceError.wrongHost }
        let channel = try await EasyWorkSessions.shared.session(client)
        easyWorkChannel = channel
        let prepared = try await EasyWorkDevPreview.prepare(channel: channel, configuration: config, id: id,
          url: serviceURL.url!.absoluteString, port: localPort, secret: secret)
        receipt = try JSONDecoder().decode(DevPreviewReceipt.self, from: prepared.receipt)
        connect = prepared.request; session = prepared.session
      } else {
      let control = try endpoint(config, "/v1/dev-previews")
      let health = try await HostService.bytes(HostService.request(try endpoint(config, "/health"), token: config.token))
      guard let healthJSON = try JSONSerialization.jsonObject(with: health) as? [String: Any],
        (healthJSON["capabilities"] as? [String])?.contains("preview:http:v1") == true else { throw HostServiceError.message(L10n.tr("当前电脑连接尚不支持开发服务预览，请检查连接方式。")) }
      try Task.checkCancellation()
      let request = HostService.request(control, token: config.token, method: "POST", body: try JSONSerialization.data(withJSONObject: payload))
      receipt = try await HostService.load(request)
      guard receipt.id == id, receipt.target == config.target, receipt.threadId == config.threadId else { throw HostServiceError.invalidResponse }
      try Task.checkCancellation()
      guard !ended else { throw CancellationError() }
      var tunnel = URLComponents(url: try endpoint(config, "/v1/dev-previews/connect"), resolvingAgainstBaseURL: false)!
      tunnel.queryItems = [URLQueryItem(name: "hostId", value: config.target.hostId), URLQueryItem(name: "id", value: id), URLQueryItem(name: "target", value: String(decoding: try JSONEncoder().encode(config.target), as: UTF8.self))]
      // Choose trust using the paired HTTPS origin before switching to WSS.
      let httpsRequest = HostService.request(tunnel.url!, token: config.token)
      session = CodexHostRoutes.shared.session(for: httpsRequest)
      tunnel.scheme = tunnel.scheme == "https" ? "wss" : "ws"
      connect = HostService.request(tunnel.url!, token: config.token); connect.timeoutInterval = 30
      }
      guard receipt.id == id, receipt.target == config.target, receipt.threadId == config.threadId else { throw HostServiceError.invalidResponse }
      try Task.checkCancellation()
      guard !ended else { throw CancellationError() }
      await gateway.configure(connect, session: session, ownsSession: config.target.agentId == "easywork")
      let cookie = HTTPCookie(properties: [.domain: "127.0.0.1", .path: "/", .name: "__agent_hub_preview", .value: secret, HTTPCookiePropertyKey("HttpOnly"): "TRUE"])!
      await dataStore.httpCookieStore.setCookie(cookie)
      var browser = URLComponents(url: source, resolvingAgainstBaseURL: false)!
      browser.host = "127.0.0.1"; browser.port = Int(localPort)
      guard !ended else { throw CancellationError() }
      url = browser.url
    } catch { self.error = error.localizedDescription; close(); await revoke() }
  }
  private func endpoint(_ config: DevPreviewConfiguration, _ path: String) throws -> URL {
    let base = try HostService.baseURL(config.endpoint)
    return base.appendingPathComponent(String(path.dropFirst()))
  }
  func close() {
    guard !ended else { return }; ended = true
    webView?.stopLoading(); webView?.loadHTMLString("", baseURL: nil); webView = nil
    gateway?.stop(); gateway = nil
    Task { await revoke() }
  }
  private func revoke() async {
    if let config, let id {
        if let channel = easyWorkChannel {
          _ = try? await channel.request("dev/preview/close", ["id": id, "threadId": config.threadId])
          return
        }
        guard let endpoint = try? endpoint(config, "/v1/dev-previews"), let target = try? JSONSerialization.jsonObject(with: JSONEncoder().encode(config.target)),
          let data = try? JSONSerialization.data(withJSONObject: ["id": id, "target": target, "threadId": config.threadId]) else { return }
        // Idempotent close; expiry remains the fallback if the Host is unreachable.
        _ = try? await HostService.bytes(HostService.request(endpoint, token: config.token, method: "DELETE", body: data))
    }
  }
}

struct DevServicePreview: View {
  let configuration: DevPreviewConfiguration
  @Environment(\.dismiss) private var dismiss
  @Environment(\.scenePhase) private var phase
  @StateObject private var model = DevPreviewModel()
  var body: some View {
    NavigationStack {
      VStack(spacing: 0) {
        Text(configuration.computerName ?? configuration.target.hostId).font(.caption).foregroundStyle(.secondary)
        if let error = model.error { Text(error).padding().accessibilityIdentifier("dev-preview-error") }
        if let url = model.url { DevPreviewWebView(url: url, model: model).accessibilityIdentifier("dev-preview-web") }
        else if model.error == nil { ProgressView(L10n.tr("正在连接开发服务…")) }
      }
      .navigationTitle(L10n.tr("开发服务"))
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("关闭")) { model.close(); dismiss() }.accessibilityIdentifier("dev-preview-close") }
        ToolbarItemGroup(placement: .bottomBar) {
          Button { model.webView?.goBack() } label: { Image(systemName: "chevron.left") }.accessibilityLabel(L10n.tr("后退"))
          Button { model.webView?.goForward() } label: { Image(systemName: "chevron.right") }.accessibilityLabel(L10n.tr("前进"))
          Spacer()
          Text(URL(string: configuration.url)?.host ?? "").font(.caption)
          Button { model.webView?.reload() } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel(L10n.tr("重新加载网页"))
        }
      }
    }
    .task { await model.start(configuration) }
    .onDisappear { model.close() }
    .onChange(of: phase) { _, phase in if phase == .background { model.close(); dismiss() } }
  }
}
private struct DevPreviewWebView: UIViewRepresentable {
  let url: URL
  @ObservedObject var model: DevPreviewModel
  func makeCoordinator() -> Coordinator { Coordinator(model: model, origin: url) }
  func makeUIView(context: Context) -> WKWebView {
    let config = WKWebViewConfiguration(); config.websiteDataStore = model.dataStore
    let view = WKWebView(frame: .zero, configuration: config); view.navigationDelegate = context.coordinator
    model.webView = view; view.load(URLRequest(url: url)); return view
  }
  func updateUIView(_ view: WKWebView, context: Context) {}
  static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) { view.stopLoading(); view.navigationDelegate = nil }
  final class Coordinator: NSObject, WKNavigationDelegate {
    let model: DevPreviewModel; let origin: URL
    init(model: DevPreviewModel, origin: URL) { self.model = model; self.origin = origin }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
      guard let url = action.request.url, url.scheme == origin.scheme, url.host == origin.host, url.port == origin.port else {
        model.error = L10n.tr("此链接需要单独打开或配置服务地址。"); decisionHandler(.cancel); return
      }
      decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { model.error = L10n.tr("开发服务连接中断，请核对网页操作结果后重新打开。") }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { model.close() }
  }
}

struct DevServiceEntry: View {
  let client: Client
  let threadId: String
  var initialURL = "http://localhost:5173"
  @Environment(\.dismiss) private var dismiss
  @State private var address = ""
  @State private var preview = false
  var body: some View {
    NavigationStack {
      Form {
        Section { Text(client.name); TextField(L10n.tr("本地服务地址"), text: $address).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("dev-service-address") }
        Section { Text(L10n.tr("允许手机交互访问此电脑上的指定开发服务；网页操作可能修改开发数据。")); Button(L10n.tr("在手机预览")) { preview = true }.accessibilityIdentifier("dev-service-open") }
      }.navigationTitle(L10n.tr("开发服务"))
      .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("关闭")) { dismiss() } } }
      .fullScreenCover(isPresented: $preview) {
        if let target = client.readingTarget, let endpoint = client.endpoint { DevServicePreview(configuration: DevPreviewConfiguration(endpoint: endpoint, token: CredentialVault.token(client.id), target: AgentRuntimeTarget(agentId: target.agentId, hostId: target.hostId, workspaceId: target.workspaceId, runtimeId: target.runtimeId, account: target.agentId == "easywork" ? target.account : nil), threadId: threadId, url: Int(address) != nil ? "http://localhost:" + address : address, computerName: client.name, client: client)) }
        else { Text(L10n.tr("未取得电脑运行目标，请重新连接。")) }
      }
    }.onAppear { address = initialURL; CodexHostRoutes.shared.register(client) }
  }
}

#if DEBUG
struct DevPreviewAcceptanceView: View {
  static var enabled: Bool { ProcessInfo.processInfo.arguments.contains("-dev-preview-acceptance") }
  var body: some View {
    if let raw = ProcessInfo.processInfo.environment["DEV_PREVIEW_CONFIG"], let config = try? JSONDecoder().decode(DevPreviewConfiguration.self, from: Data(raw.utf8)) { DevServicePreview(configuration: config) }
    else { Text("Missing preview acceptance configuration") }
  }
}
#endif
