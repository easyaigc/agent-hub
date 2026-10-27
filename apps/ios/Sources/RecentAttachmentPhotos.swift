import Foundation
import SwiftUI
@preconcurrency import Photos
import MobileCore

struct RecentAttachmentPhoto: Identifiable, @unchecked Sendable {
  let id: String
  let asset: PHAsset
  let addedAt: Date?
  let thumbnail: UIImage?
}

@MainActor final class RecentAttachmentPhotos: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
  @Published private(set) var recent: [RecentAttachmentPhoto] = []
  @Published private(set) var suggestion: RecentAttachmentPhoto?
  @Published private(set) var authorization: PHAuthorizationStatus
  @Published private(set) var baselineEstablished = false
  private let defaults: UserDefaults
  private var active = false
  private var observing = false
  private var baseline: Set<String>?
  private var baselineAuthorization: PHAuthorizationStatus?
  private var ignored: [String]
  private var refreshTask: Task<Void, Never>?
  private var generation = UUID()
  private static let ignoredKey = "attachments-recent-reminder-ignored"

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
    ignored = defaults.stringArray(forKey: Self.ignoredKey) ?? []
    authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    super.init()
  }

  var readable: Bool { authorization == .authorized || authorization == .limited }

  // Request only when the user opens the attachment panel, never on chat launch.
  func requestAccess() async {
    if PHPhotoLibrary.authorizationStatus(for: .readWrite) == .notDetermined {
      authorization = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
    }
    setActive(active)
  }

  func setActive(_ value: Bool) {
    active = value
    let current = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    if current != baselineAuthorization { baseline = nil; baselineEstablished = false; suggestion = nil }
    authorization = current
    baselineAuthorization = current
    generation = UUID()
    refreshTask?.cancel()
    if observing {
      PHPhotoLibrary.shared().unregisterChangeObserver(self)
      observing = false
    }
    guard active, readable else { recent = []; suggestion = nil; return }
    PHPhotoLibrary.shared().register(self)
    observing = true
    refresh()
  }

  nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
    Task { @MainActor [weak self] in self?.refresh() }
  }

  func dismiss(_ photo: RecentAttachmentPhoto) {
    if !ignored.contains(photo.id) { ignored.append(photo.id) }
    ignored = Array(ignored.suffix(256))
    defaults.set(ignored, forKey: Self.ignoredKey)
    if suggestion?.id == photo.id { suggestion = nil }
  }

  func refresh() {
    guard active, readable else { return }
    refreshTask?.cancel()
    let flight = generation
    refreshTask = Task { [weak self] in
      do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
      let worker = Task.detached(priority: .utility) { Self.snapshot() }
      let snapshot = await withTaskCancellationHandler { await worker.value } onCancel: { worker.cancel() }
      guard let self, !Task.isCancelled, generation == flight, active, readable else { return }
      let known = baseline != nil
      let previous = baseline ?? []
      baseline = Set(snapshot.ids)
      baselineEstablished = true
      recent = snapshot.photos
      if let candidate = suggestion,
        !AttachmentPreparationRules.shared.canSuggest(enabled: true, readable: readable, baselineKnown: true,
          ignored: ignored.contains(candidate.id), addedAtSeconds: candidate.addedAt.map { KotlinDouble(value: $0.timeIntervalSince1970) },
          nowSeconds: Date().timeIntervalSince1970) { suggestion = nil }
      // A bounded window cannot prove old additions on earlier OS versions.
      // Manual recent selection remains available, without inventing a date.
      if suggestion == nil {
        suggestion = snapshot.photos.first { photo in
          !previous.contains(photo.id) && AttachmentPreparationRules.shared.canSuggest(enabled: true, readable: readable,
            baselineKnown: known && authorization == .authorized, ignored: ignored.contains(photo.id),
            addedAtSeconds: photo.addedAt.map { KotlinDouble(value: $0.timeIntervalSince1970) }, nowSeconds: Date().timeIntervalSince1970)
        }
      }
    }
  }

  func manageLimitedAccess() {
    guard let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
      var controller = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { return }
    while let presented = controller.presentedViewController { controller = presented }
    baseline = nil; baselineEstablished = false; suggestion = nil
    PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: controller) { [weak self] _ in
      Task { @MainActor in self?.refresh() }
    }
  }

  nonisolated private static func snapshot() -> (ids: [String], photos: [RecentAttachmentPhoto]) {
    let options = PHFetchOptions()
    options.fetchLimit = 64
    if #available(iOS 26, *) { options.sortDescriptors = [NSSortDescriptor(key: "addedDate", ascending: false)] }
    else { options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)] }
    let result = PHAsset.fetchAssets(with: .image, options: options)
    var ids: [String] = [], photos: [RecentAttachmentPhoto] = []
    for index in 0..<result.count {
      guard !Task.isCancelled else { break }
      let asset = result.object(at: index)
      ids.append(asset.localIdentifier)
      guard index < 30 else { continue }
      let imageOptions = PHImageRequestOptions()
      imageOptions.isNetworkAccessAllowed = false
      imageOptions.isSynchronous = true
      imageOptions.deliveryMode = .highQualityFormat
      imageOptions.resizeMode = .exact
      var thumbnail: UIImage?
      PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 180, height: 180),
        contentMode: .aspectFill, options: imageOptions) { image, _ in thumbnail = image }
      if let original = thumbnail {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        thumbnail = UIGraphicsImageRenderer(size: CGSize(width: 180, height: 180), format: format).image { _ in
          let scale = max(180 / original.size.width, 180 / original.size.height)
          let size = CGSize(width: original.size.width * scale, height: original.size.height * scale)
          original.draw(in: CGRect(x: (180 - size.width) / 2, y: (180 - size.height) / 2, width: size.width, height: size.height))
        }
      }
      let addedAt: Date?
      if #available(iOS 26, *) { addedAt = asset.addedDate } else { addedAt = nil }
      photos.append(RecentAttachmentPhoto(id: asset.localIdentifier, asset: asset, addedAt: addedAt, thumbnail: thumbnail))
    }
    return (ids, photos)
  }

  func input(for photo: RecentAttachmentPhoto) async throws -> LocalAttachmentInput {
    guard PHPhotoLibrary.authorizationStatus(for: .readWrite) == .authorized ||
      PHPhotoLibrary.authorizationStatus(for: .readWrite) == .limited,
      let asset = PHAsset.fetchAssets(withLocalIdentifiers: [photo.id], options: nil).firstObject,
      let resource = PHAssetResource.assetResources(for: asset).first(where: { $0.type == .photo || $0.type == .fullSizePhoto }) else {
      throw AttachmentError.unsupportedImage
    }
    let data = try await PhotoAttachmentBytes.read(resource)
    return .data(data, name: resource.originalFilename, image: true)
  }
}

private final class PhotoAttachmentBytes: @unchecked Sendable {
  private let lock = NSLock()
  private var bytes = Data()
  private var oversized = false
  private var cancelled = false
  private var requestId: PHAssetResourceDataRequestID?
  private func cancel() {
    lock.lock(); cancelled = true; let id = requestId; lock.unlock()
    if let id { PHAssetResourceManager.default().cancelDataRequest(id) }
  }
  private func setRequest(_ id: PHAssetResourceDataRequestID) {
    lock.lock(); requestId = id; let stop = oversized || cancelled; lock.unlock()
    if stop { PHAssetResourceManager.default().cancelDataRequest(id) }
  }
  private func receive(_ chunk: Data) {
    lock.lock()
    if chunk.count > LocalAttachmentPreparation.maximumBytes - bytes.count { oversized = true }
    else if !oversized && !cancelled { bytes.append(chunk) }
    let id = oversized || cancelled ? requestId : nil
    lock.unlock()
    if let id { PHAssetResourceManager.default().cancelDataRequest(id) }
  }
  private func finish(_ error: Error?) -> Result<Data, Error> {
    lock.lock(); defer { lock.unlock() }
    if oversized { return .failure(AttachmentError.tooLarge) }
    if cancelled { return .failure(CancellationError()) }
    if let error { return .failure(error) }
    return .success(bytes)
  }
  static func read(_ resource: PHAssetResource) async throws -> Data {
    try Task.checkCancellation()
    let buffer = PhotoAttachmentBytes()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { continuation in
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true // Only reached after the user taps Add.
        let id = PHAssetResourceManager.default().requestData(for: resource, options: options) { chunk in
          buffer.receive(chunk)
        } completionHandler: { error in
          continuation.resume(with: buffer.finish(error))
        }
        buffer.setRequest(id)
      }
    } onCancel: { buffer.cancel() }
  }
}
