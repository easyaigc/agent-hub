import SwiftUI
import SafariServices
import QuickLook

private struct OpeningMessageLinkKey: EnvironmentKey {
  static let defaultValue: URL? = nil
}
private struct OpeningMessageLinkAnchorKey: EnvironmentKey {
  static let defaultValue: UUID? = nil
}
private struct MessageLinkOpenKey: EnvironmentKey {
  static let defaultValue: (@MainActor @Sendable (URL, UUID) -> Void)? = nil
}

extension EnvironmentValues {
  var openingMessageLink: URL? {
    get { self[OpeningMessageLinkKey.self] }
    set { self[OpeningMessageLinkKey.self] = newValue }
  }
  var openingMessageLinkAnchor: UUID? {
    get { self[OpeningMessageLinkAnchorKey.self] }
    set { self[OpeningMessageLinkAnchorKey.self] = newValue }
  }
  var messageLinkOpen: (@MainActor @Sendable (URL, UUID) -> Void)? {
    get { self[MessageLinkOpenKey.self] }
    set { self[MessageLinkOpenKey.self] = newValue }
  }
}

enum MessageLinkDestination: Equatable {
  case attachment(String), web(URL), external(URL), computerFile, computerWeb, unsupported

  static func resolve(_ url: URL) -> Self {
    switch url.scheme?.lowercased() {
    case "codex-attachment":
      if url.host == "open" {
        guard let id = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "id" })?.value, !id.isEmpty else { return .unsupported }
        return .attachment(id)
      }
      guard let id = url.host, !id.isEmpty else { return .unsupported }
      return .attachment(id)
    case "http", "https":
      guard let host = url.host?.lowercased(), !host.isEmpty,
        url.user == nil, url.password == nil else { return .unsupported }
      let normalized = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]."))
      if normalized == "localhost" || normalized.hasSuffix(".localhost")
        || normalized == "::1" || normalized == "::" || normalized == "0.0.0.0"
        || normalized.hasPrefix("127.") || normalized.hasPrefix("::ffff:127.") {
        return .computerWeb
      }
      return .web(url)
    case "mailto", "tel": return .external(url)
    case nil, "file", "sandbox": return .computerFile
    default:
      if url.absoluteString.range(of: #"^[A-Za-z]:[/\\]"#, options: .regularExpression) != nil { return .computerFile }
      return .unsupported
    }
  }
}

struct MessageLinkOpening: ViewModifier {
  let messageId: String
  let threadId: String?
  let client: Client?
  let attachments: [RemoteAttachment]
  @State private var preview: FilePreviewItem?
  @State private var webURL: BrowserLink?
  @State private var error: String?
  @State private var selectedURL: URL?
  @State private var selectedAnchor: UUID?
  @State private var loading = false
  @State private var download: Task<Void, Never>?

  func body(content: Content) -> some View {
    content
    .environment(\.openingMessageLink, loading ? selectedURL : nil)
    .environment(\.openingMessageLinkAnchor, loading ? selectedAnchor : nil)
    .environment(\.messageLinkOpen, { url, anchor in open(url, anchor: anchor) })
    .environment(\.openURL, OpenURLAction { url in open(url); return .handled })
    .filePreview($preview)
    .sheet(item: $webURL) { link in MessageWebPreview(url: link.url) }
    .alert(L10n.tr("无法打开链接"), isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
      if let selectedURL {
        Button(L10n.tr("复制链接")) { UIPasteboard.general.string = selectedURL.absoluteString }
      }
      Button(L10n.tr("好"), role: .cancel) { error = nil }
    } message: { Text(error ?? "") }
    .onDisappear {
      download?.cancel()
      download = nil
      loading = false
    }
    .onChange(of: threadId) { _, _ in reset() }
    .onChange(of: messageId) { _, _ in reset() }
    .onChange(of: client?.id) { _, _ in reset() }
    .onChange(of: client?.readingTarget) { _, _ in reset() }
    .onChange(of: client?.agentConnection?.target) { _, _ in reset() }
    .onChange(of: attachments.map(\.resourceIdentity)) { _, _ in reset() }
  }

  private func reset() {
    download?.cancel(); download = nil; loading = false
    preview = nil; webURL = nil; error = nil; selectedURL = nil; selectedAnchor = nil
  }

  private func open(_ url: URL, anchor: UUID? = nil) {
    guard !loading else { return }
    selectedURL = url
    selectedAnchor = anchor
    UISelectionFeedbackGenerator().selectionChanged()
    switch MessageLinkDestination.resolve(url) {
    case .attachment(let id):
      guard let client,
        let file = attachments.first(where: {
          ($0.id == id || (UUID(uuidString: id) != nil && UUID(uuidString: $0.id) == UUID(uuidString: id))) && $0.hostId == client.id
        }) else {
        error = L10n.tr("此文件尚未由电脑提供，请刷新对话后重试。")
        return
      }
      loading = true
      download = Task { @MainActor in
        defer { if !Task.isCancelled { loading = false; download = nil } }
        do {
          let local = try await AgentClients.resolve(client).download(client, file: file)
          guard !Task.isCancelled else { return }
          preview = FilePreviewItem(url: local, file: file, client: client)
        } catch {
          guard !Task.isCancelled else { return }
          self.error = error.localizedDescription
        }
      }
    case .web(let url): webURL = BrowserLink(url: url)
    case .external(let url):
      UIApplication.shared.open(url) { opened in
        if !opened && selectedURL == url { error = L10n.tr("手机上没有可打开此链接的应用。") }
      }
    case .computerFile:
      guard let client, let threadId else {
        error = L10n.tr("未取得这条消息对应的电脑或聊天，请重新打开原对话后重试。")
        return
      }
      loading = true
      download = Task { @MainActor in
        defer { if !Task.isCancelled { loading = false; download = nil } }
        do {
          let service = try AgentClients.resolve(client)
          let file = try await service.resolveMessageFile(client, threadId: threadId, messageId: messageId, reference: url.absoluteString)
          let local = try await service.download(client, file: file)
          guard !Task.isCancelled else { return }
          preview = FilePreviewItem(url: local, file: file, client: client)
        } catch {
          guard !Task.isCancelled else { return }
          self.error = error.localizedDescription
        }
      }
    case .computerWeb:
      error = L10n.tr("此网址指向电脑的本机地址，手机无法直接访问。请使用手机可连接的局域网或 VPN 网址。")
    case .unsupported:
      error = L10n.tr("手机暂不支持此类链接，可复制后在对应应用中打开。")
    }
  }
}

struct PreparedFileShare: Identifiable {
  let url: URL
  var id: String { url.absoluteString }
}

struct FileShareSheet: UIViewControllerRepresentable {
  let url: URL
  func makeUIViewController(context: Context) -> UIActivityViewController {
    UIActivityViewController(activityItems: [url], applicationActivities: nil)
  }
  func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

private struct BrowserLink: Identifiable {
  let url: URL
  var id: String { url.absoluteString }
}

struct MessageWebPreview: View {
  let url: URL
  @State private var failed = false
  var body: some View {
    VStack(spacing: 0) {
      if failed {
        Text(L10n.tr("网页未能加载，请检查手机网络或网址。"))
          .font(.callout).padding().accessibilityIdentifier("message-web-error")
      }
      MessageSafariView(url: url, failed: $failed)
        .accessibilityIdentifier("message-web-preview")
    }.ignoresSafeArea()
  }
}

private struct MessageSafariView: UIViewControllerRepresentable {
  let url: URL
  @Binding var failed: Bool
  func makeCoordinator() -> Coordinator { Coordinator(failed: $failed) }
  func makeUIViewController(context: Context) -> SFSafariViewController {
    let controller = SFSafariViewController(url: url)
    controller.delegate = context.coordinator
    return controller
  }
  func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
  final class Coordinator: NSObject, SFSafariViewControllerDelegate {
    let failed: Binding<Bool>
    init(failed: Binding<Bool>) { self.failed = failed }
    func safariViewController(_ controller: SFSafariViewController, didCompleteInitialLoad success: Bool) {
      failed.wrappedValue = !success
    }
  }
}
