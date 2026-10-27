import Foundation
import UIKit
import UniformTypeIdentifiers
import CryptoKit

enum AttachmentIntakeError: LocalizedError {
  case unsupported, tooLarge, unavailable, invalid
  var errorDescription: String? {
    switch self {
    case .unsupported: return NSLocalizedString("剪贴板中没有可读取的文件或图片。请在来源应用中选择分享或用其他应用打开。", comment: "")
    case .tooLarge: return NSLocalizedString("附件为空或超过 20 MB，无法添加。", comment: "")
    case .unavailable: return NSLocalizedString("暂时无法读取共享文件，请稍后重试。", comment: "")
    case .invalid: return NSLocalizedString("共享文件已变化或不可读取，请重新分享。", comment: "")
    }
  }
}

struct AttachmentFilePayload: Sendable {
  let data: Data
  let name: String
  let image: Bool
}

/// A received provider is immutable here; only its asynchronous load APIs are used.
struct AttachmentFileProvider: @unchecked Sendable {
  let provider: NSItemProvider
  func load() async throws -> AttachmentFilePayload { try await provider.attachmentFilePayload() }
}

/// Used by the app and the Action extension; never contacts a Host or reads the clipboard.
enum AttachmentFileIntake {
  static let maximumBytes = 20 * 1024 * 1024
  static func read(_ url: URL) throws -> Data {
    guard url.isFileURL else { throw AttachmentIntakeError.unsupported }
    let scoped = url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    var result: Result<Data, Error> = .failure(AttachmentIntakeError.unavailable)
    var coordinationError: NSError?
    NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readable in
      result = Result {
        let values = try readable.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true else { throw AttachmentIntakeError.unsupported }
        guard (values.fileSize ?? 0) <= maximumBytes else { throw AttachmentIntakeError.tooLarge }
        let handle = try FileHandle(forReadingFrom: readable)
        defer { try? handle.close() }
        var data = Data()
        while let chunk = try handle.read(upToCount: 65_536), !chunk.isEmpty {
          guard chunk.count <= maximumBytes - data.count else { throw AttachmentIntakeError.tooLarge }
          data.append(chunk)
        }
        guard !data.isEmpty else { throw AttachmentIntakeError.tooLarge }
        return data
      }
    }
    if let coordinationError { throw coordinationError }
    return try result.get()
  }

  static func name(_ suggestion: String?, fallback: String, type: UTType?) -> String {
    var name = ((suggestion?.isEmpty == false ? suggestion! : fallback) as NSString).lastPathComponent
    if name.isEmpty || name == "." || name == ".." { name = "attachment" }
    if (name as NSString).pathExtension.isEmpty, let suffix = type?.preferredFilenameExtension { name += "." + suffix }
    return name
  }
  static func hash(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
  static func payload(_ url: URL, suggestedName: String?) throws -> AttachmentFilePayload {
    guard url.isFileURL else { throw AttachmentIntakeError.unsupported }
    let name = name(suggestedName, fallback: url.lastPathComponent, type: UTType(filenameExtension: url.pathExtension))
    return AttachmentFilePayload(data: try read(url), name: name,
      image: UTType(filenameExtension: (name as NSString).pathExtension)?.conforms(to: .image) == true)
  }
  static func rejectURLArchive(_ bytes: Data) throws {
    guard bytes.count <= 65_536, bytes.starts(with: Data("bplist00".utf8)) else { return }
    if (try? NSURL.object(withItemProviderData: bytes, typeIdentifier: UTType.fileURL.identifier)) != nil {
      throw AttachmentIntakeError.unsupported
    }
  }
}

extension NSItemProvider {
  var attachmentNameAllowsImage: Bool {
    guard let name = suggestedName, let type = UTType(filenameExtension: (name as NSString).pathExtension) else { return true }
    return type.conforms(to: .image)
  }
  var attachmentFileTypes: [String] {
    registeredTypeIdentifiers.filter {
      guard let type = UTType($0), type.conforms(to: .data), !type.conforms(to: .url) else { return false }
      // Ordinary copied text belongs in the composer. A named text document is a file.
      if type.conforms(to: .image), !attachmentNameAllowsImage { return false }
      return !type.conforms(to: .text) || (suggestedName.map { !($0 as NSString).pathExtension.isEmpty } ?? false)
    }
  }
  var canImportAttachmentFile: Bool {
    hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) || !attachmentFileTypes.isEmpty
      || (attachmentNameAllowsImage && canLoadObject(ofClass: UIImage.self))
  }
  func attachmentFilePayload() async throws -> AttachmentFilePayload {
    try Task.checkCancellation()
    let suggestedName = suggestedName
    var fileURLError: Error?
    if hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
      do {
        if canLoadObject(ofClass: NSURL.self) {
          return try await withCheckedThrowingContinuation { continuation in
            loadObject(ofClass: NSURL.self) { object, error in
              do {
                if let error { throw error }
                guard let url = object as? URL else { throw AttachmentIntakeError.unsupported }
                continuation.resume(returning: try AttachmentFileIntake.payload(url, suggestedName: suggestedName))
              } catch { continuation.resume(throwing: error) }
            }
          }
        }
        return try await withCheckedThrowingContinuation { continuation in
        loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { value, error in
          do {
            if let error { throw error }
            let url: URL?
            if let value = value as? URL { url = value }
            else if let data = value as? Data, data.count <= 65_536 {
              // NSURL's provider representation is a property list, not file contents.
              if let object = try? NSURL.object(withItemProviderData: data, typeIdentifier: UTType.fileURL.identifier) {
                url = object as URL
              } else { url = URL(dataRepresentation: data, relativeTo: nil) }
            }
            else if let text = value as? String { url = URL(string: text) }
            else { url = nil }
            guard let url, url.isFileURL else { throw AttachmentIntakeError.unsupported }
            // Temporary and security-scoped URLs must be copied inside this callback.
            continuation.resume(returning: try AttachmentFileIntake.payload(url, suggestedName: suggestedName))
          } catch { continuation.resume(throwing: error) }
        }
      } } catch AttachmentIntakeError.tooLarge { throw AttachmentIntakeError.tooLarge }
      catch { fileURLError = error }
    }
    let resolvingFileURL = fileURLError != nil
    for identifier in attachmentFileTypes {
      // A failed URL decoder must never turn an NSURL archive into an attachment.
      if fileURLError != nil && identifier == UTType.data.identifier { continue }
      try Task.checkCancellation()
      let payload: AttachmentFilePayload? = try await withCheckedThrowingContinuation { continuation in
        loadFileRepresentation(forTypeIdentifier: identifier) { url, error in
          guard error == nil, let url else { continuation.resume(returning: nil); return }
          do {
            let type = UTType(identifier)
            let bytes = try AttachmentFileIntake.read(url)
            if resolvingFileURL { try AttachmentFileIntake.rejectURLArchive(bytes) }
            continuation.resume(returning: AttachmentFilePayload(data: bytes,
              name: AttachmentFileIntake.name(suggestedName, fallback: url.lastPathComponent, type: type),
              image: type?.conforms(to: .image) == true))
          } catch { continuation.resume(throwing: error) }
        }
      }
      if let payload { return payload }
    }
    if attachmentNameAllowsImage && (hasItemConformingToTypeIdentifier(UTType.image.identifier) || canLoadObject(ofClass: UIImage.self)) {
      return try await withCheckedThrowingContinuation { continuation in
        loadItem(forTypeIdentifier: UTType.image.identifier, options: nil) { value, error in
          do {
            if let error { throw error }
            let data: Data
            if let image = value as? UIImage {
              guard image.size.width * image.size.height * image.scale * image.scale <= 100_000_000,
                let bytes = image.pngData() else { throw AttachmentIntakeError.unsupported }
              data = bytes
            } else if let bytes = value as? Data { data = bytes }
            else if let url = value as? URL { data = try AttachmentFileIntake.read(url) }
            else { throw AttachmentIntakeError.unsupported }
            guard !data.isEmpty, data.count <= AttachmentFileIntake.maximumBytes else { throw AttachmentIntakeError.tooLarge }
            continuation.resume(returning: AttachmentFilePayload(data: data,
              name: AttachmentFileIntake.name(suggestedName, fallback: "pasted-image.png", type: .png), image: true))
          } catch { continuation.resume(throwing: error) }
        }
      }
    }
    guard let identifier = attachmentFileTypes.first(where: { fileURLError == nil || $0 != UTType.data.identifier }) else {
      throw fileURLError ?? AttachmentIntakeError.unsupported
    }
    return try await withCheckedThrowingContinuation { continuation in
      loadDataRepresentation(forTypeIdentifier: identifier) { data, error in
        if let error { continuation.resume(throwing: error); return }
        guard let data, !data.isEmpty, data.count <= AttachmentFileIntake.maximumBytes else {
          continuation.resume(throwing: AttachmentIntakeError.tooLarge); return
        }
        if resolvingFileURL {
          do { try AttachmentFileIntake.rejectURLArchive(data) }
          catch { continuation.resume(throwing: error); return }
        }
        let type = UTType(identifier)
        continuation.resume(returning: AttachmentFilePayload(data: data,
          name: AttachmentFileIntake.name(suggestedName, fallback: "attachment", type: type),
          image: type?.conforms(to: .image) == true))
      }
    }
  }
}

struct AttachmentInboxBatch: Codable, Identifiable, Sendable {
  struct File: Codable, Identifiable, Sendable {
    let id: String
    let name: String
    let size: Int
    let sha256: String
    let image: Bool
  }
  let id: String
  let files: [File]
}

enum AttachmentInbox {
  static func directory() throws -> URL {
    guard let group = Bundle.main.object(forInfoDictionaryKey: "NotificationAppGroup") as? String,
      let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else {
      throw AttachmentIntakeError.unavailable
    }
    let directory = container.appendingPathComponent("AttachmentInbox", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return directory
  }
  static func batchDirectory(_ id: String) throws -> URL {
    guard UUID(uuidString: id) != nil else { throw AttachmentIntakeError.invalid }
    return try directory().appendingPathComponent(id, isDirectory: true)
  }
  static func stage(loaders: [@Sendable () async throws -> AttachmentFilePayload]) async throws -> AttachmentInboxBatch {
    guard !loaders.isEmpty, loaders.count <= 10 else { throw AttachmentIntakeError.unsupported }
    let id = UUID().uuidString
    let final = try batchDirectory(id)
    let temporary = final.appendingPathExtension("pending")
    try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: temporary) }
    var files: [AttachmentInboxBatch.File] = []
    for loader in loaders {
      try Task.checkCancellation()
      let payload = try await loader()
      guard !payload.data.isEmpty, payload.data.count <= AttachmentFileIntake.maximumBytes else { throw AttachmentIntakeError.tooLarge }
      let file = AttachmentInboxBatch.File(id: UUID().uuidString,
        name: AttachmentFileIntake.name(payload.name, fallback: "attachment", type: nil), size: payload.data.count,
        sha256: AttachmentFileIntake.hash(payload.data), image: payload.image)
      try payload.data.write(to: temporary.appendingPathComponent(file.id), options: [.atomic, .completeFileProtectionUnlessOpen])
      files.append(file)
    }
    let batch = AttachmentInboxBatch(id: id, files: files)
    try JSONEncoder().encode(batch).write(to: temporary.appendingPathComponent("manifest.json"), options: [.atomic, .completeFileProtectionUnlessOpen])
    try Task.checkCancellation()
    try FileManager.default.moveItem(at: temporary, to: final)
    return batch
  }
  static func pending() throws -> [AttachmentInboxBatch] {
    try FileManager.default.contentsOfDirectory(at: directory(), includingPropertiesForKeys: nil)
      .filter { UUID(uuidString: $0.lastPathComponent) != nil }.sorted { $0.lastPathComponent < $1.lastPathComponent }.map { url in
        let batch = try JSONDecoder().decode(AttachmentInboxBatch.self, from: AttachmentFileIntake.read(url.appendingPathComponent("manifest.json")))
        guard batch.id == url.lastPathComponent, !batch.files.isEmpty, batch.files.count <= 10,
          Set(batch.files.map(\.id)).count == batch.files.count,
          batch.files.allSatisfy({ UUID(uuidString: $0.id) != nil && $0.size > 0 && $0.size <= AttachmentFileIntake.maximumBytes }) else {
          throw AttachmentIntakeError.invalid
        }
        return batch
      }
  }
  static func payload(batch: AttachmentInboxBatch, file: AttachmentInboxBatch.File) throws -> AttachmentFilePayload {
    guard UUID(uuidString: file.id) != nil else { throw AttachmentIntakeError.invalid }
    let data = try AttachmentFileIntake.read(batchDirectory(batch.id).appendingPathComponent(file.id))
    guard data.count == file.size, AttachmentFileIntake.hash(data) == file.sha256 else { throw AttachmentIntakeError.invalid }
    return AttachmentFilePayload(data: data, name: file.name, image: file.image)
  }
  static func remove(_ batch: AttachmentInboxBatch) throws { try FileManager.default.removeItem(at: batchDirectory(batch.id)) }
}
