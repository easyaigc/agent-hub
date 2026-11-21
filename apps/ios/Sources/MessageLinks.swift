import SwiftUI
import SafariServices
import QuickLook
import ImageIO

private struct MessageMediaSnapshotsKey: EnvironmentKey {
  static let defaultValue: [URL: URL] = [:]
}

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
  var messageMediaSnapshots: [URL: URL] {
    get { self[MessageMediaSnapshotsKey.self] }
    set { self[MessageMediaSnapshotsKey.self] = newValue }
  }
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


struct MessageLinkOpening: ViewModifier {
  @Environment(\.mediaPlayback) private var mediaPlayback
  let messageId: String
  let threadId: String?
  let client: Client?
  let attachments: [RemoteAttachment]
  @State private var preview: FilePreviewItem?
  @State private var webURL: BrowserLink?
  @State private var devURL: BrowserLink?
  @State private var error: String?
  @State private var selectedURL: URL?
  @State private var selectedAnchor: UUID?
  @State private var loading = false
  @State private var download: Task<Void, Never>?
  @State private var mediaSnapshots: [URL: URL] = [:]

  func body(content: Content) -> some View {
    content
    .environment(\.openingMessageLink, loading ? selectedURL : nil)
    .environment(\.openingMessageLinkAnchor, loading ? selectedAnchor : nil)
    .environment(\.messageLinkOpen, { url, anchor in open(url, anchor: anchor) })
    .environment(\.messageMediaSnapshots, mediaSnapshots)
    .environment(\.openURL, OpenURLAction { url in open(url); return .handled })
    .filePreview($preview)
    .sheet(item: $webURL) { link in MessageWebPreview(url: link.url) }
    .sheet(item: $devURL) { link in
      if let client, let threadId { DevServiceEntry(client: client, threadId: threadId, initialURL: link.url.absoluteString) }
    }
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
    preview = nil; webURL = nil; devURL = nil; error = nil; selectedURL = nil; selectedAnchor = nil
    mediaSnapshots = [:]
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
          if MediaPlaybackFormat.accepts(name: file.name, type: file.mediaType), let mediaPlayback {
            mediaPlayback.open(client: client, file: file, anchor: anchor); return
          }
          let local = try await AgentClients.resolve(client).download(client, file: file)
          guard !Task.isCancelled else { return }
          rememberMedia(url, local: local)
          preview = FilePreviewItem(url: local, file: file, client: client)
        } catch {
          guard !Task.isCancelled else { return }
          self.error = error.localizedDescription
        }
      }
    case .web(let url):
      if MediaPlaybackFormat.external(url), let mediaPlayback { mediaPlayback.open(url: url) }
      else { webURL = BrowserLink(url: url) }
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
          guard !Task.isCancelled else { return }
          if MediaPlaybackFormat.accepts(name: file.name, type: file.mediaType), let mediaPlayback {
            mediaPlayback.open(client: client, file: file, anchor: anchor); return
          }
          let local = try await service.download(client, file: file)
          guard !Task.isCancelled else { return }
          rememberMedia(url, local: local)
          preview = FilePreviewItem(url: local, file: file, client: client)
        } catch {
          guard !Task.isCancelled else { return }
          self.error = error.localizedDescription
        }
      }
    case .computerWeb:
      guard let client, threadId != nil, ["codex", "easywork"].contains(client.readingTarget?.agentId ?? "") else {
        error = L10n.tr("当前电脑连接尚不支持开发服务预览，请检查连接方式。")
        return
      }
      devURL = BrowserLink(url: url)
    case .unsupported:
      error = L10n.tr("手机暂不支持此类链接，可复制后在对应应用中打开。")
    }
  }

  private func rememberMedia(_ reference: URL, local: URL) {
    // Only file URLs, never decoded media bytes. The authenticated downloader
    // owns the versioned cache; rows discard these pointers on target changes.
    if mediaSnapshots.count >= 8 { mediaSnapshots = [:] }
    mediaSnapshots[reference] = local
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

/// Compatibility for cached messages and Hosts which still return desktop directives.
enum MobileVisualizationLinks {
  static func render(_ text: String) -> String {
    guard text.contains("visualize"), let regex = try? NSRegularExpression(
      pattern: "(`+)[\\s\\S]*?\\1|visualize([^]{1,16384})") else { return text }
    var result = text
    for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).reversed() {
      guard match.range(at: 1).location == NSNotFound,
        let payload = Range(match.range(at: 2), in: text),
        let data = String(text[payload]).data(using: .utf8),
        let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
        let path = object["path"] as? String, path.utf8.count <= 8192,
        ["html", "htm"].contains((path as NSString).pathExtension.lowercased()),
        !path.unicodeScalars.contains(where: { $0.value < 32 }),
        path.hasPrefix("/") || path.hasPrefix("~/") || path.range(of: #"^[A-Za-z]:[/\\]"#, options: .regularExpression) != nil,
        let range = Range(match.range, in: result) else { continue }
      var allowed = CharacterSet.urlPathAllowed
      allowed.remove(charactersIn: "()<>#?%")
      guard let destination = path.addingPercentEncoding(withAllowedCharacters: allowed) else { continue }
      let name = (path.replacingOccurrences(of: "\\", with: "/") as NSString).lastPathComponent
        .replacingOccurrences(of: "\\", with: "\\\\")
        .replacingOccurrences(of: "[", with: "\\[").replacingOccurrences(of: "]", with: "\\]")
      let label = ["`", "*", "_", "~"].reduce(name) { value, character in
        value.replacingOccurrences(of: character, with: "\\" + character)
      }
      result.replaceSubrange(range, with: "[" + L10n.tr("可视化") + " · " + label + "](<" + destination + ">)")
    }
    return result
  }
}

/// Preserve media destinations before Text's inline Markdown projection drops them.
/// Parsing performs no I/O. Code examples and incomplete streaming syntax stay text.
enum MessageMediaMarkdown {
  enum Kind: String { case image, video, audio }
  enum Part: Equatable {
    case text(String)
    case media(title: String, reference: String, kind: Kind)
  }
  static func parts(_ source: String) -> [Part] {
    guard source.contains("![") else { return [.text(source)] }
    let text = source as NSString, units = Array(source.utf16)
    var parts: [Part] = [], index = 0, start = 0
    func slice(_ a: Int, _ b: Int) -> String { text.substring(with: NSRange(location: a, length: b - a)) }
    func unescape(_ value: String) -> String {
      value.replacingOccurrences(of: ##"\\([!"#$%&'()*+,\-./:;<=>?@\[\]\\^_`{|}~])"##, with: "$1", options: .regularExpression)
    }
    func space(_ value: UInt16) -> Bool { [9, 10, 13, 32].contains(value) }
    while index < units.count {
      if units[index] == 92 { index += 2; continue }
      if units[index] == 96 {
        var end = index + 1
        while end < units.count && units[end] == 96 { end += 1 }
        let marker = slice(index, end)
        let search = NSRange(location: end, length: units.count - end)
        let close = text.range(of: marker, range: search)
        index = close.location == NSNotFound ? units.count : NSMaxRange(close)
        continue
      }
      guard units[index] == 33, index + 1 < units.count, units[index + 1] == 91 else { index += 1; continue }
      var cursor = index + 2, depth = 0
      let labelStart = cursor
      while cursor < units.count && cursor - index <= 8192 {
        let char = units[cursor]
        if char == 92 { cursor += 2; continue }
        if char == 91 { depth += 1 }
        if char == 93 { if depth == 0 { break }; depth -= 1 }
        if depth > 32 { break }
        cursor += 1
      }
      guard cursor + 1 < units.count, units[cursor] == 93, units[cursor + 1] == 40 else { index += 2; continue }
      let labelEnd = cursor
      cursor += 2
      while cursor < units.count && space(units[cursor]) { cursor += 1 }
      let angled = cursor < units.count && units[cursor] == 60
      if angled { cursor += 1 }
      let destinationStart = cursor
      depth = 0
      while cursor < units.count && cursor - destinationStart <= 8192 {
        let char = units[cursor]
        if char == 92 { cursor += 2; continue }
        if angled { if char == 62 || char == 10 { break } }
        else {
          if (char == 41 && depth == 0) || space(char) { break }
          if char == 40 { depth += 1 }
          if char == 41 { depth -= 1 }
          if depth > 32 { break }
        }
        cursor += 1
      }
      let destinationEnd = cursor
      guard cursor < units.count, cursor - destinationStart <= 8192, depth == 0,
        destinationEnd > destinationStart, !angled || units[cursor] == 62 else { index += 2; continue }
      if angled { cursor += 1 }
      let beforeSpace = cursor
      while cursor < units.count && space(units[cursor]) { cursor += 1 }
      if cursor > beforeSpace, cursor < units.count, [34, 39, 40].contains(units[cursor]) {
        let quote: UInt16 = units[cursor] == 40 ? 41 : units[cursor]
        cursor += 1
        while cursor < units.count && cursor - destinationEnd <= 8192 && units[cursor] != quote {
          cursor += units[cursor] == 92 ? 2 : 1
        }
        guard cursor < units.count && units[cursor] == quote else { index += 2; continue }
        cursor += 1
        while cursor < units.count && space(units[cursor]) { cursor += 1 }
      }
      guard cursor < units.count, units[cursor] == 41 else { index += 2; continue }
      let reference = unescape(slice(destinationStart, destinationEnd))
      guard let url = URL(string: reference),
        !reference.unicodeScalars.contains(where: { $0.value < 32 }),
        url.user == nil, url.password == nil else { index = cursor + 1; continue }
      let kind: Kind
      switch url.pathExtension.lowercased() {
      case "mp4", "m4v", "mov", "webm", "mkv": kind = .video
      case "mp3", "m4a", "wav", "aac", "flac", "ogg": kind = .audio
      default: kind = .image
      }
      if start < index { parts.append(.text(slice(start, index))) }
      parts.append(.media(title: unescape(slice(labelStart, labelEnd)), reference: reference, kind: kind))
      index = cursor + 1; start = index
    }
    if start < units.count { parts.append(.text(slice(start, units.count))) }
    return parts.isEmpty ? [.text(source)] : parts
  }
}

struct MarkdownParagraph: View {
  let text: String
  @State private var parsed: [MessageMediaMarkdown.Part]?
  @State private var parsedSource: String?
  var body: some View {
    if !text.contains("![") {
      MarkdownTextParagraph(text: text)
    } else {
    VStack(alignment: .leading, spacing: 8) {
      ForEach(Array((parsedSource == text ? parsed ?? [.text(text)] : [.text(text)]).enumerated()), id: \.offset) { _, part in
        switch part {
        case .text(let value): MarkdownTextParagraph(text: value)
        case .media(let title, let reference, let kind):
          MessageMediaCard(title: title, reference: reference, kind: kind)
        }
      }
    }
    .task(id: text) {
      let source = text
      let result = await Task.detached(priority: .userInitiated) { MessageMediaMarkdown.parts(source) }.value
      if !Task.isCancelled { parsed = result; parsedSource = source }
    }
    }
  }
}

private struct MessageMediaCard: View {
  let title: String
  let reference: String
  let kind: MessageMediaMarkdown.Kind
  @Environment(\.messageLinkOpen) private var messageLinkOpen
  @Environment(\.openURL) private var openURL
  @Environment(\.openingMessageLink) private var openingLink
  @Environment(\.openingMessageLinkAnchor) private var openingAnchor
  @Environment(\.messageMediaSnapshots) private var snapshots
  @Environment(\.mediaPlayback) private var mediaPlayback
  @State private var anchor = UUID()
  @State private var thumbnail: UIImage?
  private var url: URL? { URL(string: reference) }
  private var name: String {
    if !title.isEmpty { return title }
    return url?.lastPathComponent.isEmpty == false ? url!.lastPathComponent : L10n.tr("预览")
  }
  private var loading: Bool { openingLink == url && openingAnchor == anchor }
  var body: some View {
    Group {
      if kind == .video, let mediaPlayback { MediaInlineCard(coordinator: mediaPlayback, anchor: anchor, fallback: card) }
      else { card }
    }
  }
  private var card: some View {
    Button {
      guard let url else { return }
      if let messageLinkOpen { messageLinkOpen(url, anchor) } else { openURL(url) }
    } label: {
      VStack(alignment: .leading, spacing: 8) {
        if let thumbnail {
          Image(uiImage: thumbnail).resizable().scaledToFit().frame(maxHeight: 220)
            .accessibilityIdentifier("message-media-thumbnail")
        }
        HStack(spacing: 12) {
          Image(systemName: kind == .video ? "play.rectangle.fill" : kind == .audio ? "waveform" : "photo")
            .font(.title2)
          Text(name).multilineTextAlignment(.leading)
          Spacer(minLength: 8)
          if loading { ProgressView() } else { Image(systemName: "arrow.up.right") }
        }
      }.padding(12).frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
        .background(Palette.sidebar, in: RoundedRectangle(cornerRadius: 12)).contentShape(Rectangle())
    }.buttonStyle(.plain).tint(Palette.link)
      .disabled(loading).accessibilityLabel(name)
      .accessibilityIdentifier("message-media-" + kind.rawValue)
      .task(id: url.flatMap { snapshots[$0] }) {
        thumbnail = nil
        guard kind == .image, let url, let local = snapshots[url] else { return }
        // Explicit open already authenticated and downloaded this file. Generate
        // only a small local thumbnail; never fetch media while rendering history.
        let image = await Task.detached(priority: .utility) { () -> UIImage? in
          guard let source = CGImageSourceCreateWithURL(local as CFURL, nil),
            let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [
              kCGImageSourceCreateThumbnailFromImageAlways: true,
              kCGImageSourceCreateThumbnailWithTransform: true,
              kCGImageSourceThumbnailMaxPixelSize: 640,
            ] as CFDictionary) else { return nil }
          return UIImage(cgImage: cg)
        }.value
        if !Task.isCancelled { thumbnail = image }
      }
  }
}
