import SwiftUI
import QuickLook
import WebKit
import CryptoKit
import MobileCore
import FilePreviewKit

/// An immutable downloaded snapshot. Renderers receive no credentials or transport.
struct FilePreviewItem: Identifiable {
  let url: URL
  let name: String
  let mediaType: String
  let scopeKey: String
  var id: String { scopeKey + "\n" + url.absoluteString }

  init(url: URL, file: RemoteAttachment, client: Client) {
    self.url = url; name = file.name; mediaType = file.mediaType
    let target = client.readingTarget ?? client.agentConnection?.target
    let machine = target.map { SharedMobileRules.machineReadingKey($0, threadId: file.threadId) }
      ?? client.id + ":" + (client.readingScopeId ?? "") + ":" + file.threadId
    scopeKey = machine + "\n" + file.resourceIdentity
  }
}

struct FilePreviewCapabilities {
  let source: Bool
  let outline: Bool
  let search: Bool
}

/// Register new renderers here; file access and presentation remain independent.
enum FilePreviewRegistry {
  struct Renderer {
    let id: String
    let capabilities: FilePreviewCapabilities
    let load: @Sendable (URL, Int) throws -> PreviewDocument
  }
  static let renderers = [Renderer(id: "markdown",
    capabilities: .init(source: true, outline: true, search: true),
    load: { try PreviewDocument.load($0, maximumBytes: $1) })]
  static func renderer(_ item: FilePreviewItem) -> Renderer? {
    let id = FilePreviewRules.shared.renderer(fileName: item.name, mediaType: item.mediaType)
    return renderers.first { $0.id == id }
  }
}

extension View {
  func filePreview(_ selection: Binding<FilePreviewItem?>, onDismiss: (() -> Void)? = nil) -> some View {
    sheet(item: Binding(get: {
      selection.wrappedValue.flatMap { FilePreviewRegistry.renderer($0) != nil || VisualizationPreview.accepts($0) ? $0 : nil }
    }, set: { selection.wrappedValue = $0 }), onDismiss: onDismiss) { item in
      if VisualizationPreview.accepts(item) { VisualizationFilePreview(item: item).id(item.id) }
      else if let renderer = FilePreviewRegistry.renderer(item) { DocumentFilePreview(item: item, renderer: renderer).id(item.id) }
    }.quickLookPreview(Binding(get: {
      selection.wrappedValue.flatMap { FilePreviewRegistry.renderer($0) == nil && !VisualizationPreview.accepts($0) ? $0.url : nil }
    }, set: { if $0 == nil { selection.wrappedValue = nil; onDismiss?() } }))
  }
}

@MainActor private final class FilePreviewPositions {
  static let shared = FilePreviewPositions()
  // Bounded process-local reading positions; never content, credentials or permissions.
  private struct Position { var block: Int?; var offset: CGFloat? }
  private var values: [String: Position] = [:]
  private var order: [String] = []
  func get(_ key: String) -> Int? { values[key]?.block }
  func offset(_ key: String) -> CGFloat? { values[key]?.offset }
  private func insert(_ key: String) {
    guard values[key] == nil else { return }
    order.append(key); values[key] = Position()
    if order.count > 128 { values.removeValue(forKey: order.removeFirst()) }
  }
  func set(_ value: Int?, key: String) {
    guard let value else { return }
    insert(key); values[key]?.block = value
  }
  func setOffset(_ value: CGFloat, key: String) {
    insert(key); values[key]?.offset = max(0, value)
  }
}

private struct DocumentFilePreview: View {
  let item: FilePreviewItem
  let renderer: FilePreviewRegistry.Renderer
  @Environment(\.dismiss) private var dismiss
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @State private var document: PreviewDocument?
  @State private var failure: String?
  @State private var source = false
  @State private var outline = false
  @State private var searching = false
  @State private var query = ""
  @State private var matches: [Int] = []
  @State private var hit = 0
  @State private var position: Int?
  @State private var sourceOffset: CGFloat = 0
  @State private var jump: Int?
  @State private var share: PreparedFileShare?
  @State private var systemURL: URL?
  @State private var linkError: String?
  @State private var webURL: PreviewWebLink?
  @FocusState private var searchFocused: Bool

  private var positionKey: String { item.scopeKey + ":" + (document?.digest ?? "") }

  var body: some View {
    NavigationStack {
      VStack(spacing: 0) {
        if let document {
          if renderer.capabilities.source { Picker(L10n.tr("显示方式"), selection: $source) {
            Text(L10n.tr("预览")).tag(false)
            Text(L10n.tr("源码")).tag(true)
          }.pickerStyle(.segmented).padding(.horizontal).padding(.vertical, 8)
            .accessibilityIdentifier("file-preview-mode") }
          if searching { searchBar }
          Divider()
          if source {
            HStack {
              Text(L10n.tr("原始文本")).font(.caption).foregroundStyle(Palette.secondary)
              Spacer()
              Button(L10n.tr("复制源码")) { UIPasteboard.general.string = document.source }
                .accessibilityIdentifier("file-preview-copy-source")
            }.padding(.horizontal).padding(.top, 8)
            SourcePreviewText(text: document.source, offset: $sourceOffset)
              .accessibilityIdentifier("file-preview-source")
          } else { reader(document) }
        } else if let failure {
          ContentUnavailableView {
            Label(L10n.tr("暂时无法排版此文件"), systemImage: "doc.text")
          } description: { Text(failure) } actions: {
            Button(L10n.tr("使用系统预览")) { systemURL = item.url }
            Button(L10n.tr("保存或分享文件")) { share = PreparedFileShare(url: item.url) }
          }.accessibilityIdentifier("file-preview-error")
        } else { ProgressView(L10n.tr("正在准备预览…")).frame(maxWidth: .infinity, maxHeight: .infinity) }
      }
      .background(Palette.background)
      .navigationTitle(item.name).navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .topBarLeading) {
          Button(L10n.tr("完成")) { dismiss() }.accessibilityIdentifier("file-preview-close")
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
          if document != nil {
            if renderer.capabilities.search { Button { searching.toggle() } label: { Image(systemName: "magnifyingglass") }
              .accessibilityLabel(L10n.tr("搜索文件")).accessibilityIdentifier("file-preview-search")
            }
            if renderer.capabilities.outline { Button { outline = true } label: { Image(systemName: "list.bullet") }
              .disabled(document?.headings.isEmpty != false)
              .accessibilityLabel(L10n.tr("目录")).accessibilityIdentifier("file-preview-outline") }
          }
          Button { share = PreparedFileShare(url: item.url) } label: { Image(systemName: "square.and.arrow.up") }
            .accessibilityLabel(L10n.tr("保存或分享文件"))
        }
      }
      .sheet(isPresented: $outline) {
        NavigationStack {
          List(document?.headings ?? []) { heading in
            Button { source = false; jump = heading.id; outline = false } label: {
              Text(heading.text).padding(.leading, CGFloat(max(0, heading.indent) * 10))
            }.accessibilityIdentifier("file-preview-heading-\(heading.id)")
          }.navigationTitle(L10n.tr("目录"))
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(L10n.tr("完成")) { outline = false } } }
        }.presentationDetents([.medium, .large])
      }
      .sheet(item: $share) { FileShareSheet(url: $0.url) }
      .sheet(item: $webURL) { MessageWebPreview(url: $0.url) }
      .quickLookPreview($systemURL)
      .alert(L10n.tr("无法打开链接"), isPresented: Binding(get: { linkError != nil }, set: { if !$0 { linkError = nil } })) {
        Button(L10n.tr("好"), role: .cancel) { linkError = nil }
      } message: { Text(linkError ?? "") }
      .environment(\.openURL, OpenURLAction { url in openLink(url); return .handled })
      .task(id: item.id) {
        let url = item.url
        let load = renderer.load
        let maximum = Int(FilePreviewRules.shared.maximumMarkdownBytes)
        let task = Task.detached(priority: .userInitiated) {
          try load(url, maximum)
        }
        do {
          let value = try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
          try Task.checkCancellation()
          document = value
          position = FilePreviewPositions.shared.get(item.scopeKey + ":" + value.digest)
        } catch {
          guard !Task.isCancelled else { return }
          switch error {
          case PreviewError.tooLarge: failure = L10n.tr("文件较大，请使用系统预览或保存原文件。")
          case PreviewError.encoding: failure = L10n.tr("暂不支持此文件的文本编码，请使用系统预览或保存原文件。")
          case PreviewError.complexity: failure = L10n.tr("文档结构较复杂，请使用系统预览或保存原文件。")
          default: failure = error.localizedDescription
          }
        }
      }
      .onChange(of: position) { _, value in
        guard document != nil, !source else { return }
        FilePreviewPositions.shared.set(value, key: positionKey)
      }
      .onChange(of: searching) { _, value in if !value { query = "" } }
      .task(id: query) {
        let input = query
        guard !input.isEmpty, let document else { matches = []; return }
        try? await Task.sleep(for: .milliseconds(120))
        guard !Task.isCancelled else { return }
        let values = await Task.detached {
          document.blocks.filter { $0.text.localizedCaseInsensitiveContains(input) }.map(\.id)
        }.value
        guard !Task.isCancelled, input == query else { return }
        matches = values; hit = 0
        if !values.isEmpty { source = false; jump = values.first }
      }
    }.accessibilityIdentifier("file-preview-screen")
  }

  private var searchBar: some View {
    HStack {
      TextField(L10n.tr("搜索文件"), text: $query).textFieldStyle(.roundedBorder)
        .focused($searchFocused).submitLabel(.search).onSubmit { searchFocused = false }
        .autocorrectionDisabled().accessibilityIdentifier("file-preview-query")
      Text(query.isEmpty ? "" : "\(matches.isEmpty ? 0 : hit + 1)/\(matches.count)").font(.caption).monospacedDigit()
        .accessibilityIdentifier("file-preview-match-count")
      Button { goToMatch(-1) } label: { Image(systemName: "chevron.up") }
        .disabled(matches.isEmpty).accessibilityLabel(L10n.tr("上一个结果"))
      Button { goToMatch(1) } label: { Image(systemName: "chevron.down") }
        .disabled(matches.isEmpty).accessibilityLabel(L10n.tr("下一个结果"))
    }.padding(.horizontal).padding(.bottom, 8)
  }

  private func goToMatch(_ step: Int) {
    let values = matches
    guard !values.isEmpty else { return }
    hit = (hit + step + values.count) % values.count
    jump = values[hit]
  }

  private func reader(_ document: PreviewDocument) -> some View {
    ScrollViewReader { proxy in
      ScrollView {
        LazyVStack(alignment: .leading, spacing: 16) {
          ForEach(document.blocks) { block in
            PreviewBlockView(block: block, highlighted: matches.contains(block.id), openLink: openLink)
              .padding(.leading, CGFloat(min(block.indent, 6) * 12))
              .frame(maxWidth: .infinity, alignment: .leading).id(block.id)
          }
        }.scrollTargetLayout().padding(16)
          .background {
            PreviewScrollObserver(key: positionKey, saved: FilePreviewPositions.shared.offset(positionKey)) { value in
              FilePreviewPositions.shared.setOffset(value, key: positionKey)
            }
          }
      }.scrollPosition(id: $position, anchor: .top)
        .accessibilityIdentifier("file-preview-content")
        .onAppear {
          if let value = jump { proxy.scrollTo(value, anchor: .top); jump = nil }
        }
        .onChange(of: jump) { _, value in
          guard let value else { return }
          if source { source = false }
          proxy.scrollTo(value, anchor: .top)
          jump = nil
        }
    }
  }

  private func openLink(_ url: URL) {
    let kind = FilePreviewRules.shared.linkKind(reference: url.absoluteString)
    if kind == "anchor" {
      let anchor = String(url.absoluteString.dropFirst()).removingPercentEncoding ?? ""
      if let block = document?.headings.first(where: { $0.anchor == anchor }) { jump = block.id }
      else { linkError = L10n.tr("文档中没有找到此章节。") }
    } else if kind == "web", case .web = MessageLinkDestination.resolve(url) {
      webURL = PreviewWebLink(url: url)
    } else if kind == "external" {
      UIApplication.shared.open(url)
    } else {
      linkError = L10n.tr("此引用尚未由电脑提供为可访问资源，可在源码中查看原链接。")
    }
  }
}

/// Record the native offset without publishing per-frame SwiftUI state. A block
/// anchor alone cannot restore a location inside a long table or code block.
private struct PreviewScrollObserver: UIViewRepresentable {
  let key: String
  let saved: CGFloat?
  let record: @MainActor (CGFloat) -> Void
  func makeUIView(context: Context) -> Probe { Probe() }
  func updateUIView(_ view: Probe, context: Context) {
    view.record = record
    if view.key != key { view.key = key; view.pending = saved }
    view.attach()
  }
  static func dismantleUIView(_ view: Probe, coordinator: ()) { view.detach() }

  @MainActor final class Probe: UIView {
    var key = ""
    var pending: CGFloat?
    var record: @MainActor (CGFloat) -> Void = { _ in }
    private weak var scroll: UIScrollView?
    private var offsetObservation: NSKeyValueObservation?
    private var sizeObservation: NSKeyValueObservation?
    private var deadline: TimeInterval = 0
    private var earliestRestore: TimeInterval = 0
    private var scheduled = false
    override init(frame: CGRect) {
      super.init(frame: frame); isUserInteractionEnabled = false
      isAccessibilityElement = false; accessibilityElementsHidden = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func didMoveToWindow() {
      super.didMoveToWindow()
      if window == nil { detach() }
      else { DispatchQueue.main.async { [weak self] in self?.attach() } }
    }
    func attach() {
      guard window != nil else { return }
      var ancestor = superview
      while let view = ancestor, !(view is UIScrollView) { ancestor = view.superview }
      guard let found = ancestor as? UIScrollView, found !== scroll else { return }
      detach(); scroll = found; deadline = ProcessInfo.processInfo.systemUptime + 1
      earliestRestore = ProcessInfo.processInfo.systemUptime + 0.15
      offsetObservation = found.observe(\.contentOffset) { [weak self] value, _ in
        MainActor.assumeIsolated { if let self, self.pending == nil { self.record(value.contentOffset.y) } }
      }
      sizeObservation = found.observe(\.contentSize) { [weak self] _, _ in
        MainActor.assumeIsolated { self?.scheduleRestore() }
      }
      scheduleRestore()
    }
    private func restore() {
      guard let scroll else { return }
      guard let pending else { record(scroll.contentOffset.y); return }
      let maximum = max(0, scroll.contentSize.height + scroll.adjustedContentInset.bottom - scroll.bounds.height)
      if scroll.isDragging || scroll.isTracking {
        self.pending = nil; record(scroll.contentOffset.y); return
      }
      if scroll.bounds.height > 0 && ProcessInfo.processInfo.systemUptime >= earliestRestore &&
        (maximum >= pending || ProcessInfo.processInfo.systemUptime >= deadline) {
        self.pending = nil
        scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: min(pending, maximum)), animated: false)
        record(scroll.contentOffset.y)
      } else { scheduleRestore() }
    }
    private func scheduleRestore() {
      guard !scheduled else { return }; scheduled = true
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.04) { [weak self] in
        self?.scheduled = false; self?.restore()
      }
    }
    func detach() {
      offsetObservation?.invalidate(); sizeObservation?.invalidate()
      offsetObservation = nil; sizeObservation = nil; scroll = nil
    }
  }
}

private struct PreviewWebLink: Identifiable { let url: URL; var id: String { url.absoluteString } }

private struct PreviewBlockView: View {
  let block: PreviewBlock
  let highlighted: Bool
  let openLink: (URL) -> Void
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      switch block.kind {
      case .heading(let level):
        Text(block.attributed).font(level == 1 ? .title2 : level == 2 ? .title3 : .headline)
          .fontWeight(.semibold).accessibilityAddTraits(.isHeader)
      case .code(let language):
        VStack(alignment: .leading, spacing: 8) {
          HStack {
            Text(language.isEmpty ? L10n.tr("代码") : language).font(.caption).foregroundStyle(Palette.secondary)
            Spacer()
            Button(L10n.tr("复制")) { UIPasteboard.general.string = block.text }
              .font(.caption).accessibilityIdentifier("file-preview-copy-code-\(block.id)")
          }
          ScrollView(.horizontal) {
            Text(verbatim: block.text).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
              .fixedSize(horizontal: true, vertical: false)
          }
        }.padding(12).background(Palette.sidebar, in: RoundedRectangle(cornerRadius: 10))
      case .table:
        PreviewTable(id: block.id, rows: block.rows, attributed: block.attributedRows)
      case .list(let marker):
        HStack(alignment: .top, spacing: 8) { Text(marker); Text(block.attributed) }
      case .quote:
        Label(L10n.tr("引用"), systemImage: "text.quote").font(.caption).foregroundStyle(Palette.secondary)
      case .divider: Divider()
      case .html: Text(verbatim: block.text).font(.system(.callout, design: .monospaced)).textSelection(.enabled)
      case .paragraph: Text(block.attributed)
      }
      ForEach(Array(block.images.enumerated()), id: \.offset) { _, image in
        if let url = URL(string: image.destination) {
          Button { openLink(url) } label: { Label(image.label.isEmpty ? L10n.tr("查看图片") : image.label, systemImage: "photo") }
        }
      }
    }.textSelection(.enabled).tint(Palette.link).lineSpacing(5).background(highlighted ? Palette.link.opacity(0.12) : Color.clear)
      .accessibilityElement(children: .contain)
      .accessibilityIdentifier("file-preview-block-\(block.id)")
  }
}

private struct PreviewTable: View {
  let id: Int
  let rows: [[String]]
  let attributed: [[AttributedString]]
  private let widths: [CGFloat]
  init(id: Int, rows: [[String]], attributed: [[AttributedString]]) {
    self.id = id; self.rows = rows; self.attributed = attributed
    widths = (0..<(rows.map(\.count).max() ?? 0)).map { column in
      let longest = rows.prefix(100).compactMap { $0.indices.contains(column) ? $0[column].count : nil }.max() ?? 0
      return CGFloat(min(300, max(120, longest * 7)))
    }
  }
  var body: some View {
    VStack(alignment: .leading, spacing: 5) {
      Text(L10n.tr("左右滑动查看完整表格")).font(.caption).foregroundStyle(Palette.secondary)
      ScrollView(.horizontal) {
        VStack(alignment: .leading, spacing: 0) {
          ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
            HStack(alignment: .top, spacing: 0) {
              ForEach(Array(row.enumerated()), id: \.offset) { column, cell in
                Text(attributed[index][column])
                  .font(.subheadline).fontWeight(index == 0 ? .semibold : .regular)
                  .textSelection(.enabled).frame(width: widths[column], alignment: .leading).padding(10)
              }
            }.background(index == 0 ? Palette.sidebar : Palette.background)
            Divider()
          }
        }
      }.accessibilityIdentifier("file-preview-table-\(id)")
    }
  }
}

private struct SourcePreviewText: UIViewRepresentable {
  let text: String
  @Binding var offset: CGFloat
  func makeCoordinator() -> Coordinator { Coordinator(offset: $offset) }
  func makeUIView(context: Context) -> UITextView {
    let view = UITextView()
    view.isEditable = false; view.isSelectable = true; view.dataDetectorTypes = []
    view.backgroundColor = .clear
    view.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
    view.adjustsFontForContentSizeCategory = true
    view.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedSystemFont(ofSize: 16, weight: .regular))
    view.text = text; view.delegate = context.coordinator
    view.accessibilityIdentifier = "file-preview-source"
    let saved = offset
    DispatchQueue.main.async { view.setContentOffset(CGPoint(x: 0, y: saved), animated: false) }
    return view
  }
  func updateUIView(_ view: UITextView, context: Context) {}
  final class Coordinator: NSObject, UITextViewDelegate {
    let offset: Binding<CGFloat>
    init(offset: Binding<CGFloat>) { self.offset = offset }
    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { offset.wrappedValue = scrollView.contentOffset.y }
    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate: Bool) {
      if !willDecelerate { offset.wrappedValue = scrollView.contentOffset.y }
    }
  }
}

// HTML is an immutable downloaded resource. Its scripts receive no native bridge,
// credentials, local file access, persistent website data or top-level navigation.
enum VisualizationPreview {
  static let maximumBytes = 2 * 1024 * 1024
  static func accepts(_ item: FilePreviewItem) -> Bool {
    ["html", "htm"].contains((item.name as NSString).pathExtension.lowercased())
      || item.mediaType.lowercased().split(separator: ";").first == "text/html"
  }
  static let externalSources = [
    "https://cdnjs.cloudflare.com", "https://esm.sh", "https://cdn.jsdelivr.net", "https://unpkg.com",
    "https://fonts.googleapis.com", "https://fonts.gstatic.com", "https://fonts.bunny.net",
    "https://persistent.oaistatic.com/plugins/visualize/"
  ]
  static func document(_ fragment: String, allowExternalResources: Bool = false) -> String {
    let sources = allowExternalResources ? externalSources.joined(separator: " ") : ""
    let connections = allowExternalResources ? sources : "'none'"
    let policy = "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' \(sources); style-src 'unsafe-inline' \(sources); img-src data: blob: \(sources); font-src data: \(sources); connect-src \(connections); worker-src blob:; media-src data: blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    func escape(_ text: String) -> String {
      text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "\"", with: "&quot;")
        .replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }
    let inner = """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="\(escape(policy))"><style>\(styles)</style></head><body>\(fragment)</body></html>
    """
    let outerPolicy = policy.replacingOccurrences(of: "frame-src 'none'", with: "frame-src about:")
    return """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="\(escape(outerPolicy))"><style>html,body{margin:0;height:100%;background:transparent}iframe{display:block;width:100%;height:100%;border:0}</style></head><body><iframe title="可视化" sandbox="allow-scripts" referrerpolicy="no-referrer" srcdoc="\(escape(inner))"></iframe></body></html>
    """
  }
  // The visualization fragment contract uses these semantic tokens. Keep them
  // independent of the conversation layout so desktop fixed-width charts can scroll.
  static let styles = """
  :root{color-scheme:light dark;--background:light-dark(#fff,#181818);--foreground:light-dark(#1a1c1f,#fff);--card:light-dark(#f5f5f5,#242424);--card-foreground:var(--foreground);--popover:var(--background);--popover-foreground:var(--foreground);--primary:light-dark(#007aff,#83c3ff);--primary-foreground:#fff;--secondary:var(--card);--secondary-foreground:var(--foreground);--muted:light-dark(#f0f0f0,#303030);--muted-foreground:light-dark(#737373,#aaa);--accent:light-dark(#e5f2ff,#0d273f);--accent-foreground:var(--primary);--border:light-dark(#ddd,#444);--input:var(--border);--ring:var(--primary);--destructive:#e25507;--blue:#339cff;--orange:#f3883b;--green:#5dc977;--red:#eb77b1;--purple:#9b79ec;--yellow:#ffc300;--viz-series-1:var(--primary);--viz-series-2:var(--orange);--viz-series-3:var(--green);--viz-series-4:var(--red);--viz-series-5:var(--purple);--viz-series-6:#3ab9b1;--color-background-primary:var(--background);--color-background-secondary:var(--card);--color-text-primary:var(--foreground);--color-text-secondary:var(--muted-foreground);--color-border-primary:var(--border);--color-border-secondary:var(--border);--color-text-info:var(--primary);--font-sans:-apple-system,BlinkMacSystemFont,system-ui,sans-serif;--font-mono:ui-monospace,monospace;--radius:12px}
  *{box-sizing:border-box}body{margin:0;padding:16px;font:16px/1.5 var(--font-sans);background:var(--background);color:var(--foreground);overflow-wrap:break-word;-webkit-text-size-adjust:100%}button,input,select,textarea{font:inherit}button,select,input[type=number]{min-height:44px}button{color:var(--foreground);background:var(--card);border:1px solid var(--border);border-radius:8px;padding:8px 14px;touch-action:manipulation}input[type=range]{max-width:100%}a{color:var(--primary)}svg,canvas,img{max-width:100%}pre{overflow:auto}table{border-collapse:collapse}td,th{padding:8px;border:1px solid var(--border)}.card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:16px}.muted,.subtitle{color:var(--muted-foreground)}.chart-container{position:relative;width:100%}svg text{fill:var(--foreground)}.axis text{fill:var(--muted-foreground)}.axis path,.axis line,.grid line{stroke:var(--border)}
  """
}

private struct VisualizationFilePreview: View {
  let item: FilePreviewItem
  @Environment(\.dismiss) private var dismiss
  @State private var fragment: String?
  @State private var loadedItemId: String?
  @State private var externalResourcesForItem: String?
  @State private var showExternalResourceDisclosure = false
  @State private var disclosureItemId: String?
  @State private var failure: String?
  @State private var share: PreparedFileShare?
  var body: some View {
    NavigationStack {
      Group {
        if let fragment, loadedItemId == item.id, failure == nil {
          VStack(spacing: 0) {
            HStack {
              Text(L10n.tr(externalResourcesForItem == item.id ? "已允许本次预览联网" : "默认离线预览，外部资源已阻止"))
                .font(.caption).foregroundStyle(.secondary)
              Spacer()
              Button {
                if externalResourcesForItem == item.id { externalResourcesForItem = nil }
                else { disclosureItemId = item.id; showExternalResourceDisclosure = true }
              } label: {
                Text(L10n.tr(externalResourcesForItem == item.id ? "切回离线" : "允许联网…"))
                  .font(.caption)
              }.accessibilityIdentifier("visualization-network-permission")
            }.padding(.horizontal).padding(.vertical, 8)
            VisualizationWebView(html: VisualizationPreview.document(fragment,
              allowExternalResources: externalResourcesForItem == item.id), failure: $failure)
              .id(item.id + (externalResourcesForItem == item.id ? ":online" : ":offline"))
          }
        }
        else if failure == nil { ProgressView(L10n.tr("正在准备预览…")) }
        if let failure { ContentUnavailableView(L10n.tr("无法显示可视化"), systemImage: "chart.xyaxis.line", description: Text(failure)) }
      }
      .navigationTitle(item.name).navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .topBarLeading) {
          Button(L10n.tr("完成")) { dismiss() }.accessibilityIdentifier("visualization-preview-close")
        }
        ToolbarItem(placement: .topBarTrailing) {
          Button { share = PreparedFileShare(url: item.url) } label: { Image(systemName: "square.and.arrow.up") }
            .accessibilityLabel(L10n.tr("保存或分享文件"))
        }
      }
      .sheet(item: $share) { FileShareSheet(url: $0.url) }
      .sheet(isPresented: $showExternalResourceDisclosure, onDismiss: { disclosureItemId = nil }) {
        NavigationStack {
          Form {
            Section {
              Text(L10n.tr("允许后，此文档可从下列第三方站点加载脚本、字体和图片。这些站点会收到你的 IP 地址和请求信息；加载的脚本也能读取此文档及你在预览中输入的内容，并可向下列站点发送数据。请只为你信任的文档允许联网。"))
              ForEach(VisualizationPreview.externalSources, id: \.self) { Text($0).font(.caption).textSelection(.enabled) }
              Text(L10n.tr("预览不提供电脑凭据或原生操作接口。联网许可仅适用于当前打开的这个文件，关闭后不保存。选择离线仍可查看不依赖外部资源的内容。"))
              Link(L10n.tr("阅读隐私政策"), destination: L10n.privacyPolicyURL)
            }
            Section {
              Button(L10n.tr("允许本次预览联网")) {
                guard disclosureItemId == item.id, loadedItemId == item.id, fragment != nil else { return }
                externalResourcesForItem = item.id
                showExternalResourceDisclosure = false
              }.accessibilityIdentifier("visualization-network-allow")
              Button(L10n.tr("保持离线"), role: .cancel) { showExternalResourceDisclosure = false }
                .accessibilityIdentifier("visualization-network-decline")
            }
          }
          .navigationTitle(L10n.tr("预览联网许可")).navigationBarTitleDisplayMode(.inline)
          .toolbar { ToolbarItem(placement: .cancellationAction) {
            Button(L10n.tr("取消")) { showExternalResourceDisclosure = false }
          } }
        }
      }
      .task(id: item.id) {
        fragment = nil; loadedItemId = nil; externalResourcesForItem = nil
        showExternalResourceDisclosure = false; disclosureItemId = nil; failure = nil
        let url = item.url
        do {
          let fragment = try await Task.detached(priority: .userInitiated) {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max
            guard size <= VisualizationPreview.maximumBytes else { throw PreviewError.tooLarge }
            let data = try Data(contentsOf: url)
            guard data.count <= VisualizationPreview.maximumBytes else { throw PreviewError.tooLarge }
            guard let text = String(data: data, encoding: .utf8) else { throw PreviewError.encoding }
            return text
          }.value
          guard !Task.isCancelled else { return }
          self.fragment = fragment; loadedItemId = item.id
        } catch {
          guard !Task.isCancelled else { return }
          failure = L10n.tr("此可视化文件过大、编码不支持或无法读取，可保存原文件后查看。")
        }
      }
    }.accessibilityIdentifier("visualization-preview-screen")
  }
}

private struct VisualizationWebView: UIViewRepresentable {
  let html: String
  @Binding var failure: String?
  func makeCoordinator() -> Coordinator { Coordinator(failure: $failure) }
  func makeUIView(context: Context) -> WKWebView {
    let configuration = WKWebViewConfiguration()
    configuration.websiteDataStore = .nonPersistent()
    configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
    let view = WKWebView(frame: .zero, configuration: configuration)
    view.isOpaque = false
    view.backgroundColor = .clear
    view.navigationDelegate = context.coordinator
    view.accessibilityIdentifier = "visualization-web"
    view.loadHTMLString(html, baseURL: nil)
    return view
  }
  func updateUIView(_ view: WKWebView, context: Context) {}
  static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
    view.stopLoading(); view.navigationDelegate = nil
    view.configuration.websiteDataStore.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
  }
  final class Coordinator: NSObject, WKNavigationDelegate {
    let failure: Binding<String?>
    init(failure: Binding<String?>) { self.failure = failure }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
      // Only WebKit's initial document and sandbox frame may navigate.
      let url = action.request.url?.absoluteString ?? ""
      decisionHandler(action.navigationType == .other && ["about:blank", "about:srcdoc"].contains(url) ? .allow : .cancel)
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
      guard (error as NSError).code != NSURLErrorCancelled else { return }
      failure.wrappedValue = error.localizedDescription
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { failure.wrappedValue = L10n.tr("预览已中断，请关闭后重新打开。") }
  }
}
