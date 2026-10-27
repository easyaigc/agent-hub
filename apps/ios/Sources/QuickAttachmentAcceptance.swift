#if DEBUG
import SwiftUI
import Photos
import UniformTypeIdentifiers

@MainActor enum QuickAttachmentAcceptance {
  static var enabled: Bool {
    ProcessInfo.processInfo.arguments.contains("-quick-attachments-acceptance") &&
      ["com.easyai.agenthub.quickattachments.acceptance", "com.easyai.agenthub.externalattachments.acceptance", "com.easyai.agenthub.attachmentsplan.acceptance", "com.easyai.agenthub.imagepreview.acceptance"].contains(Bundle.main.bundleIdentifier ?? "")
  }
  static var code: String { ProcessInfo.processInfo.environment["QUICK_ATTACHMENT_CODE"] ?? "LOCAL-ATTACHMENT-TEST" }
  private static var resetOwnedInboxDone = false
  static func resetOwnedInboxIfRequested() {
    guard enabled, Bundle.main.bundleIdentifier == "com.easyai.agenthub.externalattachments.acceptance",
      !resetOwnedInboxDone, !ProcessInfo.processInfo.arguments.contains("-restore-testing"),
      ProcessInfo.processInfo.arguments.contains("-external-owned-fixtures-reset") else { return }
    resetOwnedInboxDone = true
    let original = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("qa-wechat.zip")
    let fixture = Data("PK\u{03}\u{04} controlled ZIP attachment".utf8)
    for batch in (try? AttachmentInbox.pending()) ?? [] {
      let owned = batch.files.allSatisfy { file in
        guard let payload = try? AttachmentInbox.payload(batch: batch, file: file) else { return false }
        if payload.data == fixture { return true }
        guard payload.data.count <= 65_536,
          let url = try? NSURL.object(withItemProviderData: payload.data, typeIdentifier: UTType.fileURL.identifier) else { return false }
        return (url as URL).standardizedFileURL == original.standardizedFileURL
      }
      if owned { try? AttachmentInbox.remove(batch) }
    }
  }
  static func copyLocalImage() {
    guard enabled, let bytes = image().pngData() else { return }
    UIPasteboard.general.setItems([[UTType.png.identifier: bytes]], options: [.localOnly: true])
  }
  static func copyLocalFile(url: Bool) throws {
    guard enabled else { return }
    let bytes = Data("PK\u{03}\u{04} controlled ZIP attachment".utf8)
    let file = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("qa-wechat.zip")
    try bytes.write(to: file, options: .atomic)
    let provider = NSItemProvider()
    provider.suggestedName = "qa-wechat.zip"
    if url {
      provider.registerItem(forTypeIdentifier: UTType.fileURL.identifier, loadHandler: { done, _, _ in done?(file as NSURL, nil) })
    } else {
      provider.registerDataRepresentation(forTypeIdentifier: UTType.zip.identifier, visibility: .all) { done in
        done(bytes, nil); return nil
      }
    }
    UIPasteboard.general.itemProviders = [provider]
  }
  static func copyLocalDocument() throws {
    guard enabled else { return }
    let file = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("qa-input.txt")
    let provider = NSItemProvider(contentsOf: file)
    guard let provider else { throw AttachmentIntakeError.invalid }
    provider.suggestedName = file.lastPathComponent
    UIPasteboard.general.itemProviders = [provider]
  }
  static func checkFileProviders() async throws {
    guard enabled else { return }
    let ordinaryText = NSItemProvider(object: "ordinary composer text" as NSString)
    guard !ordinaryText.canImportAttachmentFile else { throw AttachmentIntakeError.invalid }
    let link = NSItemProvider(object: URL(string: "https://example.com/attachment.zip")! as NSURL)
    guard !link.canImportAttachmentFile else { throw AttachmentIntakeError.invalid }
    let bytes = Data("PK\u{03}\u{04} provider data".utf8)
    let provider = NSItemProvider()
    provider.suggestedName = "资料.zip"
    provider.registerDataRepresentation(forTypeIdentifier: UTType.zip.identifier, visibility: .all) { done in done(bytes, nil); return nil }
    let payload = try await provider.attachmentFilePayload()
    guard payload.data == bytes, payload.name == "资料.zip", !payload.image else { throw AttachmentIntakeError.invalid }
    let original = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".txt")
    let originalBytes = Data(("original file bytes " + UUID().uuidString).utf8)
    try originalBytes.write(to: original)
    defer { try? FileManager.default.removeItem(at: original) }
    guard let fileProvider = NSItemProvider(contentsOf: original) else { throw AttachmentIntakeError.invalid }
    fileProvider.suggestedName = "original.txt"
    let loaded = try await fileProvider.attachmentFilePayload()
    guard loaded.data == originalBytes, loaded.name == "original.txt" else { throw AttachmentIntakeError.invalid }
    let batch = try await AttachmentInbox.stage(loaders: [{ payload }])
    defer { try? AttachmentInbox.remove(batch) }
    guard try AttachmentInbox.pending().contains(where: { $0.id == batch.id }),
      try AttachmentInbox.payload(batch: batch, file: batch.files[0]).data == bytes else { throw AttachmentIntakeError.invalid }
    try Data("tampered".utf8).write(to: AttachmentInbox.batchDirectory(batch.id).appendingPathComponent(batch.files[0].id))
    do { _ = try AttachmentInbox.payload(batch: batch, file: batch.files[0]); throw AttachmentError.unsupportedImage }
    catch AttachmentIntakeError.invalid { }
    let oversized = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    FileManager.default.createFile(atPath: oversized.path, contents: Data([1]))
    defer { try? FileManager.default.removeItem(at: oversized) }
    let handle = try FileHandle(forWritingTo: oversized)
    try handle.truncate(atOffset: UInt64(AttachmentFileIntake.maximumBytes + 1)); try handle.close()
    do { _ = try AttachmentFileIntake.read(oversized); throw AttachmentError.unsupportedImage }
    catch AttachmentIntakeError.tooLarge { }
  }
  static func checkPreviewMetadata() throws {
    guard enabled else { return }
    let old = Data(#"{"id":"legacy","name":"old.png","size":1,"fileName":"local","isImage":true}"#.utf8)
    guard try JSONDecoder().decode(LocalAttachment.self, from: old).photoAssetId == nil else {
      throw AttachmentError.unsupportedImage
    }
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let destination = AttachmentDraftDestination(key: "owned-preview-test", generation: UUID().uuidString)
    let prepared = try LocalAttachmentPreparation.prepare(.photo(image().pngData()!, name: "owned.png", identifier: "owned-photo"),
      directory: directory, destination: destination)
    guard let journal = prepared.journal,
      prepared.file.photoAssetId == "owned-photo",
      try JSONDecoder().decode(PreparedAttachmentRecord.self, from: Data(contentsOf: journal)).file.photoAssetId == "owned-photo" else {
      throw AttachmentError.unsupportedImage
    }
  }
  static func image() -> UIImage {
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 900, height: 500))
    return renderer.image { context in
      UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: 900, height: 500))
      ("Attachment verification\n" + code).draw(in: CGRect(x: 45, y: 140, width: 820, height: 240),
        withAttributes: [.font: UIFont.monospacedSystemFont(ofSize: 44, weight: .medium), .foregroundColor: UIColor.black])
    }
  }
  static func prepareDocuments() throws {
    let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    try FileManager.default.createDirectory(at: documents, withIntermediateDirectories: true)
    try image().pngData()!.write(to: documents.appendingPathComponent("qa-attachment.png"), options: .atomic)
    let textCode = "QA-TXT" + UUID().uuidString.prefix(8).uppercased()
    try Data(("Attachment verification: " + textCode).utf8).write(to: documents.appendingPathComponent("qa-input.txt"), options: .atomic)
  }
  static func addOldPhoto() async throws {
    guard enabled else { return }
    let image = image()
    var id: String?
    try await PHPhotoLibrary.shared().performChanges {
      let request = PHAssetChangeRequest.creationRequestForAsset(from: image)
      request.creationDate = Date(timeIntervalSince1970: 946_684_800)
      id = request.placeholderForCreatedAsset?.localIdentifier
    }
    if let id {
      let key = "quick-attachment-acceptance-assets"
      var ids = UserDefaults.standard.stringArray(forKey: key) ?? []
      ids.append(id); UserDefaults.standard.set(ids, forKey: key)
    }
  }
  static func cleanupPhotos() async throws {
    guard enabled else { return }
    let key = "quick-attachment-acceptance-assets"
    let ids = UserDefaults.standard.stringArray(forKey: key) ?? []
    let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
    guard assets.count > 0 else { return }
    try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(assets) }
    UserDefaults.standard.removeObject(forKey: key)
    UserDefaults.standard.removeObject(forKey: "quick-attachment-acceptance-strip-assets")
  }
}

struct QuickAttachmentAcceptanceControls: View {
  @ObservedObject var store: SessionStore
  @ObservedObject var library: RecentAttachmentPhotos
  @State private var status = "idle"
  var body: some View {
    if QuickAttachmentAcceptance.enabled {
      Section("DEV acceptance") {
        Text(status).accessibilityIdentifier("quick-attachment-acceptance-status")
        Text(library.baselineEstablished ? "baseline-ready" : "baseline-waiting")
          .accessibilityIdentifier("quick-attachment-baseline")
        Text(UserDefaults.standard.stringArray(forKey: "quick-attachment-acceptance-assets")?.contains(library.suggestion?.id ?? "") == true
          ? "owned-photo-suggestion" : "waiting-for-owned-photo")
          .accessibilityIdentifier("quick-attachment-owned-candidate")
        Text(library.recent.filter {
          UserDefaults.standard.stringArray(forKey: "quick-attachment-acceptance-strip-assets")?.contains($0.id) == true
        }.map(\.id).joined(separator: "|"))
          .lineLimit(1)
          .accessibilityIdentifier("quick-attachment-owned-photos")
        Button("Check image preview metadata") { run { try QuickAttachmentAcceptance.checkPreviewMetadata(); return "preview-metadata-passed" } }
          .accessibilityIdentifier("quick-attachment-test-preview-metadata")
        Button("Prepare test files") { run { try QuickAttachmentAcceptance.prepareDocuments(); return "files-ready" } }
          .accessibilityIdentifier("quick-attachment-test-files")
        Button("Copy test image") { QuickAttachmentAcceptance.copyLocalImage(); status = "clipboard-ready" }
          .accessibilityIdentifier("quick-attachment-test-copy")
        Button("Copy test file URL") { run { try QuickAttachmentAcceptance.copyLocalFile(url: true); return "file-url-copied" } }
          .accessibilityIdentifier("quick-attachment-test-copy-file-url")
        Button("Copy test file data") { run { try QuickAttachmentAcceptance.copyLocalFile(url: false); return "file-data-copied" } }
          .accessibilityIdentifier("quick-attachment-test-copy-file-data")
        Button("Copy test document") { run { try QuickAttachmentAcceptance.copyLocalDocument(); return "document-copied" } }
          .accessibilityIdentifier("quick-attachment-test-copy-document")
        Button("Check file providers") { run { try await QuickAttachmentAcceptance.checkFileProviders(); return "file-providers-passed" } }
          .accessibilityIdentifier("quick-attachment-test-file-providers")
        Button("Check ZIP draft bytes") {
          run {
            let files = store.drafts.values[store.targetKey]?.attachments.filter { $0.name == "qa-wechat.zip" } ?? []
            guard files.count == 2 else { throw AttachmentIntakeError.invalid }
            let expected = Data("PK\u{03}\u{04} controlled ZIP attachment".utf8)
            for file in files {
              guard try LocalAttachmentPreparation.read(store.attachmentURL(file)) == expected else { throw AttachmentIntakeError.invalid }
            }
            return "zip-bytes-passed"
          }
        }.accessibilityIdentifier("quick-attachment-test-zip-bytes")
        Button("Share test file") {
          do {
            try QuickAttachmentAcceptance.copyLocalFile(url: true)
            let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("qa-wechat.zip")
            NotificationCenter.default.post(name: Notification.Name("AgentHubShareAcceptanceFile"), object: url)
          } catch { status = error.localizedDescription }
        }.accessibilityIdentifier("quick-attachment-test-share-file")
        Button("Add old test photo") { run { try await QuickAttachmentAcceptance.addOldPhoto(); return "photo-added" } }
          .accessibilityIdentifier("quick-attachment-test-photo")
        Button("Prepare test photo strip") {
          run {
            let key = "quick-attachment-acceptance-assets"
            let previous = Set(UserDefaults.standard.stringArray(forKey: key) ?? [])
            for _ in 0..<6 { try await QuickAttachmentAcceptance.addOldPhoto() }
            let created = (UserDefaults.standard.stringArray(forKey: key) ?? []).filter { !previous.contains($0) }
            UserDefaults.standard.set(created, forKey: "quick-attachment-acceptance-strip-assets")
            return "photo-strip-ready"
          }
        }.accessibilityIdentifier("quick-attachment-test-photo-strip")
        Button("Check local import boundaries") { run { try await store.checkAttachmentImportAcceptance(); return "local-boundaries-passed" } }
          .accessibilityIdentifier("quick-attachment-test-boundaries")
        Button("Prepare interrupted import") { run { try await store.prepareAttachmentRecoveryAcceptance(); return "recovery-prepared" } }
          .accessibilityIdentifier("quick-attachment-test-recovery")
        Button("Clean up test photos") { run { try await QuickAttachmentAcceptance.cleanupPhotos(); return "photos-cleaned" } }
          .accessibilityIdentifier("quick-attachment-test-cleanup")
      }
    }
  }
  private func run(_ body: @escaping () async throws -> String) {
    status = "working"
    Task {
      do { status = try await body() }
      catch { status = "failed: " + error.localizedDescription }
    }
  }
}
#endif
