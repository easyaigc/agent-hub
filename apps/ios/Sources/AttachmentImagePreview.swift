import SwiftUI
@preconcurrency import Photos
import ImageIO

struct AttachmentPreviewItem: Identifiable {
  enum Source { case photo(PHAsset), file(URL) }
  let id: String
  let name: String
  let source: Source
  init(photo: RecentAttachmentPhoto) {
    id = photo.id
    name = PHAssetResource.assetResources(for: photo.asset).first?.originalFilename ?? L10n.tr("图片")
    source = .photo(photo.asset)
  }
  init(file: LocalAttachment, url: URL) { id = file.id; name = file.name; source = .file(url) }
}

/// PhotoKit requests are cancellable and never import an attachment or contact a Host.
private final class AttachmentPhotoImageRequest: @unchecked Sendable {
  private let lock = NSLock()
  private var continuation: CheckedContinuation<UIImage, Error>?
  private var request: PHImageRequestID?
  private var cancelled = false
  private var finished = false

  private func complete(_ result: Result<UIImage, Error>) {
    lock.lock()
    guard !finished else { lock.unlock(); return }
    finished = true
    let pending = continuation; continuation = nil
    lock.unlock()
    pending?.resume(with: result)
  }
  func cancel() {
    lock.lock(); cancelled = true; let id = request; lock.unlock()
    if let id { PHImageManager.default().cancelImageRequest(id) }
    complete(.failure(CancellationError()))
  }
  func start(asset: PHAsset, pixels: CGFloat, network: Bool,
    continuation: CheckedContinuation<UIImage, Error>) {
    lock.lock()
    guard !cancelled else { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
    self.continuation = continuation
    lock.unlock()
    let options = PHImageRequestOptions()
    options.isNetworkAccessAllowed = network
    options.deliveryMode = .highQualityFormat
    options.resizeMode = .fast
    let id = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: pixels, height: pixels),
      contentMode: .aspectFit, options: options) { [self] image, info in
      if (info?[PHImageCancelledKey] as? Bool) == true { complete(.failure(CancellationError())) }
      else if let error = info?[PHImageErrorKey] as? Error { complete(.failure(error)) }
      else if (info?[PHImageResultIsDegradedKey] as? Bool) != true {
        if let image { complete(.success(image)) }
        else { complete(.failure(AttachmentError.unsupportedImage)) }
      }
    }
    lock.lock(); request = id; let stop = cancelled; lock.unlock()
    if stop { PHImageManager.default().cancelImageRequest(id) }
  }
  static func image(asset: PHAsset, pixels: CGFloat, network: Bool) async throws -> UIImage {
    let request = AttachmentPhotoImageRequest()
    return try await withTaskCancellationHandler {
      try Task.checkCancellation()
      return try await withCheckedThrowingContinuation { request.start(asset: asset, pixels: pixels,
        network: network, continuation: $0) }
    } onCancel: { request.cancel() }
  }
}

struct AttachmentPhotoThumbnail: View {
  let photo: RecentAttachmentPhoto
  @State private var loaded: UIImage?
  var body: some View {
    GeometryReader { geometry in
      Group {
        if let image = photo.thumbnail ?? loaded { Image(uiImage: image).resizable().scaledToFill() }
        else { ZStack { Palette.sidebar; Image(systemName: "photo").foregroundStyle(Palette.secondary) } }
      }.frame(width: geometry.size.width, height: geometry.size.height).clipped().contentShape(Rectangle())
    }.clipShape(RoundedRectangle(cornerRadius: 10)).contentShape(Rectangle()).accessibilityHidden(true)
      .task(id: photo.id) {
        guard photo.thumbnail == nil else { return }
        loaded = try? await AttachmentPhotoImageRequest.image(asset: photo.asset, pixels: 320, network: false)
      }
  }
}

struct AttachmentPhotoTile: View {
  let photo: RecentAttachmentPhoto
  let order: Int?
  let alreadyAdded: Bool
  let preview: () -> Void
  let select: () -> Void
  var body: some View {
    ZStack(alignment: .topTrailing) {
      Button(action: preview) { AttachmentPhotoThumbnail(photo: photo).contentShape(Rectangle()) }
        .buttonStyle(.plain).accessibilityLabel(L10n.tr("预览图片"))
        .accessibilityIdentifier("recent-photo-" + photo.id)
      Button(action: select) {
        ZStack {
          Circle().fill(alreadyAdded ? Color.secondary : order == nil ? Color.black.opacity(0.35) : Color.accentColor)
          Circle().strokeBorder(.white, lineWidth: 2)
          if alreadyAdded { Image(systemName: "checkmark").font(.caption.bold()).foregroundStyle(.white) }
          else if let order { Text(String(order)).font(.caption.bold()).foregroundStyle(.white) }
        }.frame(width: 26, height: 26).frame(width: 44, height: 44).contentShape(Rectangle())
      }.buttonStyle(.plain).disabled(alreadyAdded).zIndex(1)
        .accessibilityLabel(L10n.tr(alreadyAdded ? "已添加" : order == nil ? "选择图片" : "取消选择图片"))
        .accessibilityValue(order.map(String.init) ?? "0")
        .accessibilityIdentifier("select-photo-" + photo.id)
      if alreadyAdded {
        Text(L10n.tr("已添加")).font(.caption2).foregroundStyle(.white).padding(5)
          .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 5))
          .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading).padding(5)
          .allowsHitTesting(false)
      }
    }.overlay { RoundedRectangle(cornerRadius: 10).strokeBorder(order == nil ? .clear : Color.accentColor, lineWidth: 2).allowsHitTesting(false) }
  }
}

@MainActor final class AttachmentPhotoSelection: ObservableObject {
  @Published var photos: [RecentAttachmentPhoto] = []
  @Published var error: String?
  func toggle(_ photo: RecentAttachmentPhoto, maximum: Int) {
    error = nil
    if photos.contains(where: { $0.id == photo.id }) { photos.removeAll { $0.id == photo.id } }
    else if photos.count < maximum { photos.append(photo) }
    else { error = L10n.tr("最多添加 5 个附件，请先移除部分附件。") }
  }
}

struct AttachmentPhotoPreviewRequest: Identifiable {
  let id = UUID()
  let photos: [RecentAttachmentPhoto]
  let initialId: String
}

struct AttachmentPhotoSelectionFooter: View {
  @ObservedObject var selection: AttachmentPhotoSelection
  let adding: Bool
  let add: () -> Void
  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      if let error = selection.error { Text(error).font(.caption).foregroundStyle(.red) }
      HStack {
        Text(L10n.tr("仅添加到草稿，发送时再上传。")).font(.caption).foregroundStyle(.secondary)
        Spacer(minLength: 8)
        Button(action: add) {
          if adding { ProgressView() }
          else { Text(L10n.tr("添加") + "（\(selection.photos.count)）") }
        }.buttonStyle(.borderedProminent).controlSize(.large)
          .disabled(adding || selection.photos.isEmpty).accessibilityIdentifier("attachment-add-selected")
      }
    }.padding(.horizontal, 16).padding(.vertical, 10)
  }
}

struct AttachmentPhotoSelectionPreview: View {
  let request: AttachmentPhotoPreviewRequest
  @ObservedObject var selection: AttachmentPhotoSelection
  let alreadyAdded: Set<String>
  let maximum: Int
  let adding: Bool
  let add: () -> Void
  @Environment(\.dismiss) private var dismiss
  @State private var current: String
  init(request: AttachmentPhotoPreviewRequest, selection: AttachmentPhotoSelection,
    alreadyAdded: Set<String>, maximum: Int, adding: Bool, add: @escaping () -> Void) {
    self.request = request; self.selection = selection; self.alreadyAdded = alreadyAdded
    self.maximum = maximum; self.adding = adding; self.add = add
    _current = State(initialValue: request.initialId)
  }
  var body: some View {
    AttachmentImageViewer(items: request.photos.map(AttachmentPreviewItem.init(photo:)),
      selection: $current, close: { dismiss() }, closeTitle: L10n.tr("返回")) {
      VStack(alignment: .leading) {
        if let error = selection.error { Text(error).font(.caption).foregroundStyle(.red) }
        HStack {
          let added = alreadyAdded.contains(current)
          let selected = selection.photos.contains { $0.id == current }
          Button {
            if let photo = request.photos.first(where: { $0.id == current }) { selection.toggle(photo, maximum: maximum) }
          } label: {
            Label(L10n.tr(added ? "已添加" : selected ? "已选择" : "选择此图"),
              systemImage: added || selected ? "checkmark.circle.fill" : "circle").frame(minHeight: 44).foregroundStyle(.white)
              .transaction { $0.animation = nil }
          }.disabled(added || adding).accessibilityIdentifier("attachment-preview-select")
            .accessibilityValue(selected || added ? "1" : "0")
          Spacer()
          Button(L10n.tr("添加") + "（\(selection.photos.count)）", action: add)
            .buttonStyle(.borderedProminent).controlSize(.large).disabled(adding || selection.photos.isEmpty)
            .accessibilityIdentifier("attachment-preview-add-selected")
        }
      }
    }
  }
}

@MainActor final class AttachmentPhotoCatalog: ObservableObject {
  @Published private(set) var photos: [RecentAttachmentPhoto] = []
  @Published private(set) var hasMore = false
  private var assets: PHFetchResult<PHAsset>?
  func load() {
    let access = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    guard access == .authorized || access == .limited else { assets = nil; photos = []; hasMore = false; return }
    let options = PHFetchOptions()
    if #available(iOS 26, *) { options.sortDescriptors = [NSSortDescriptor(key: "addedDate", ascending: false)] }
    else { options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)] }
    assets = PHAsset.fetchAssets(with: .image, options: options)
    photos = []; loadMore()
  }
  func loadMore() {
    guard let assets else { return }
    let end = min(photos.count + 60, assets.count)
    guard photos.count < end else { hasMore = false; return }
    photos += (photos.count..<end).map { index in
      let asset = assets.object(at: index)
      return RecentAttachmentPhoto(id: asset.localIdentifier, asset: asset, addedAt: nil, thumbnail: nil)
    }
    hasMore = end < assets.count
  }
}

struct AttachmentImageViewer<Actions: View>: View {
  let items: [AttachmentPreviewItem]
  @Binding var selection: String
  let close: () -> Void
  var closeTitle: String = L10n.tr("完成")
  @ViewBuilder let actions: () -> Actions
  @State private var zoom: CGFloat = 1
  private var current: AttachmentPreviewItem? { items.first { $0.id == selection } }
  var body: some View {
    VStack(spacing: 0) {
      HStack {
        Button(action: close) { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
          .accessibilityLabel(L10n.tr("返回")).accessibilityIdentifier("attachment-preview-back")
        VStack(spacing: 3) {
          Text("\((items.firstIndex { $0.id == selection } ?? 0) + 1) / \(items.count)").font(.headline)
          Text(current?.name ?? L10n.tr("图片不可用")).accessibilityIdentifier("attachment-preview-current").accessibilityValue(selection).font(.caption).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
        }.frame(maxWidth: .infinity)
        Button(closeTitle, action: close).frame(minWidth: 44, minHeight: 44)
          .accessibilityIdentifier("attachment-preview-done")
      }.padding(.horizontal, 8)
      TabView(selection: $selection) {
        ForEach(items) { item in
          AttachmentPreviewPage(item: item, active: item.id == selection, zoom: $zoom).tag(item.id)
        }
      }.tabViewStyle(.page(indexDisplayMode: .never)).accessibilityIdentifier("attachment-preview-pages")
      HStack(spacing: 12) {
        Button { zoom = max(1, zoom - 0.5) } label: { Image(systemName: "minus").frame(width: 44, height: 44) }
          .accessibilityLabel(L10n.tr("缩小图片")).accessibilityIdentifier("attachment-preview-zoom-out")
        Text("\(Int(zoom * 100))%").font(.caption.monospacedDigit()).frame(minWidth: 44)
          .accessibilityIdentifier("attachment-preview-zoom-value")
        Button { zoom = min(6, zoom + 0.5) } label: { Image(systemName: "plus").frame(width: 44, height: 44) }
          .accessibilityLabel(L10n.tr("放大图片")).accessibilityIdentifier("attachment-preview-zoom-in")
      }
      Text(L10n.tr("左右滑动切换 · 双击或双指缩放")).font(.caption).foregroundStyle(.white.opacity(0.7))
      ScrollViewReader { proxy in
        ScrollView(.horizontal) {
          LazyHStack(spacing: 8) {
            ForEach(items) { item in
              Button { selection = item.id } label: {
                AttachmentPreviewMiniature(item: item).frame(width: 44, height: 48).clipped().contentShape(Rectangle())
                  .overlay { RoundedRectangle(cornerRadius: 5).strokeBorder(item.id == selection ? .white : .clear, lineWidth: 2) }
              }.buttonStyle(.plain).accessibilityLabel(item.name).id(item.id)
                .accessibilityIdentifier("preview-page-" + item.id)
            }
          }.padding(.horizontal, 16).padding(.vertical, 12)
        }.scrollIndicators(.hidden).frame(height: 72)
          .onChange(of: selection) { _, id in proxy.scrollTo(id, anchor: .center) }
          .task { proxy.scrollTo(selection, anchor: .center) }
      }
      Divider().overlay(.white.opacity(0.2))
      actions().padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 8)
    }.foregroundStyle(.white).tint(.blue).background(Color(white: 0.06).ignoresSafeArea())
      .onChange(of: selection) { _, _ in zoom = 1 }
      .onChange(of: items.map(\.id)) { _, ids in
        if !ids.contains(selection), let first = ids.first { selection = first }
        if ids.isEmpty { close() }
      }
  }
}

private struct AttachmentPreviewPage: View {
  let item: AttachmentPreviewItem
  let active: Bool
  @Binding var zoom: CGFloat
  @State private var image: UIImage?
  @State private var failed = false
  @State private var attempt = 0
  var body: some View {
    Group {
      if let image, active { AttachmentZoomImage(image: image, id: item.id, zoom: $zoom) }
      else if failed {
        VStack(spacing: 12) {
          Text(L10n.tr("图片加载失败"))
          Button(L10n.tr("重试")) { attempt += 1 }.frame(minHeight: 44)
        }
      } else { ProgressView().tint(.white) }
    }.task(id: "\(item.id)-\(active)-\(attempt)") {
      guard active else { image = nil; return }
      failed = false
      do {
        let loaded: UIImage
        switch item.source {
        case .photo(let asset): loaded = try await AttachmentPhotoImageRequest.image(asset: asset, pixels: 4096, network: true)
        case .file(let url):
          loaded = try await Task.detached(priority: .userInitiated) { try attachmentPreviewImage(url, pixels: 4096) }.value
        }
        try Task.checkCancellation(); image = loaded
      } catch { if !Task.isCancelled { failed = true } }
    }
  }
}

private struct AttachmentPreviewMiniature: View {
  let item: AttachmentPreviewItem
  @State private var image: UIImage?
  var body: some View {
    Group {
      if let image { Image(uiImage: image).resizable().scaledToFill() }
      else { Color.gray.opacity(0.3) }
    }.task(id: item.id) {
      switch item.source {
      case .photo(let asset): image = try? await AttachmentPhotoImageRequest.image(asset: asset, pixels: 120, network: false)
      case .file(let url): image = try? await Task.detached(priority: .utility) { try attachmentPreviewImage(url, pixels: 120) }.value
      }
    }
  }
}

private func attachmentPreviewImage(_ url: URL, pixels: Int) throws -> UIImage {
  guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
    let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceCreateThumbnailWithTransform: true,
      kCGImageSourceThumbnailMaxPixelSize: pixels,
      kCGImageSourceShouldCacheImmediately: true,
    ] as CFDictionary) else { throw AttachmentError.unsupportedImage }
  return UIImage(cgImage: image)
}

private struct AttachmentZoomImage: UIViewRepresentable {
  let image: UIImage
  let id: String
  @Binding var zoom: CGFloat
  func makeUIView(context: Context) -> AttachmentZoomScrollView { AttachmentZoomScrollView() }
  func updateUIView(_ view: AttachmentZoomScrollView, context: Context) {
    view.changed = { value in
      DispatchQueue.main.async { if abs(zoom - value) > 0.01 { zoom = value } }
    }
    view.display(image, id: id)
    if !view.isZooming, !view.isDragging, !view.isDecelerating, abs(view.zoomScale - zoom) > 0.01 { view.setZoomScale(zoom, animated: false) }
  }
}

private final class AttachmentZoomScrollView: UIScrollView, UIScrollViewDelegate {
  let picture = UIImageView()
  var changed: ((CGFloat) -> Void)?
  private var imageId: String?
  private var laidOutSize = CGSize.zero
  override init(frame: CGRect) {
    super.init(frame: frame)
    delegate = self; minimumZoomScale = 1; maximumZoomScale = 6
    showsHorizontalScrollIndicator = false; showsVerticalScrollIndicator = false
    contentInsetAdjustmentBehavior = .never
    picture.contentMode = .scaleAspectFit
    addSubview(picture)
    let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
    doubleTap.numberOfTapsRequired = 2; addGestureRecognizer(doubleTap)
    accessibilityIdentifier = "attachment-zoom-image"
    picture.isAccessibilityElement = true; picture.accessibilityLabel = L10n.tr("图片预览")
  }
  required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
  func display(_ image: UIImage, id: String) {
    guard imageId != id || picture.image !== image else { return }
    imageId = id; picture.image = image; laidOutSize = .zero; setNeedsLayout()
  }
  override func layoutSubviews() {
    super.layoutSubviews()
    guard bounds.width > 0, bounds.height > 0, let image = picture.image else { return }
    if laidOutSize != bounds.size {
      laidOutSize = bounds.size; setZoomScale(1, animated: false)
      let scale = min(bounds.width / image.size.width, bounds.height / image.size.height)
      picture.frame = CGRect(origin: .zero, size: CGSize(width: image.size.width * scale, height: image.size.height * scale))
      contentSize = picture.frame.size
    }
    centerPicture()
  }
  private func centerPicture() {
    picture.center = CGPoint(x: max(contentSize.width, bounds.width) / 2, y: max(contentSize.height, bounds.height) / 2)
  }
  func viewForZooming(in scrollView: UIScrollView) -> UIView? { picture }
  func scrollViewDidZoom(_ scrollView: UIScrollView) { centerPicture(); changed?(zoomScale) }
  @objc private func doubleTapped(_ recognizer: UITapGestureRecognizer) {
    if zoomScale > 1.01 { setZoomScale(1, animated: true) }
    else {
      let point = recognizer.location(in: picture), scale: CGFloat = 2.5
      let size = CGSize(width: bounds.width / scale, height: bounds.height / scale)
      zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: true)
    }
  }
}

struct DraftAttachmentPreviewRequest: Identifiable {
  let id = UUID()
  let destination: AttachmentDraftDestination
  let initialId: String
}

struct DraftAttachmentImagePreview: View {
  @ObservedObject var store: SessionStore
  @ObservedObject var drafts: ComposerDraftStore
  let request: DraftAttachmentPreviewRequest
  @Environment(\.dismiss) private var dismiss
  @State private var selection: String
  init(store: SessionStore, drafts: ComposerDraftStore, request: DraftAttachmentPreviewRequest) {
    self.store = store; self.drafts = drafts; self.request = request
    _selection = State(initialValue: request.initialId)
  }
  private var files: [LocalAttachment] { drafts.values[request.destination.key]?.attachments.filter(\.isImage) ?? [] }
  var body: some View {
    AttachmentImageViewer(items: files.map { AttachmentPreviewItem(file: $0, url: store.attachmentURL($0)) },
      selection: $selection, close: { dismiss() }) {
      HStack {
        Button(role: .destructive) {
          guard store.targetKey == request.destination.key,
            drafts.values[request.destination.key]?.attachmentImportGeneration == request.destination.generation,
            let file = files.first(where: { $0.id == selection }) else { dismiss(); return }
          store.removeAttachment(file)
        } label: { Label(L10n.tr("从草稿移除"), systemImage: "trash").frame(minHeight: 44) }
          .tint(.red).accessibilityIdentifier("attachment-preview-remove")
        Spacer()
        Button(L10n.tr("完成")) { dismiss() }.buttonStyle(.borderedProminent).controlSize(.large)
          .accessibilityIdentifier("attachment-preview-finish")
      }
    }.onChange(of: store.targetKey) { _, _ in dismiss() }
  }
}
