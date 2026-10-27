import Foundation
import UIKit
import ImageIO
import UniformTypeIdentifiers
import PhotosUI
import CoreTransferable
import CryptoKit

struct AttachmentDraftDestination: Equatable, Sendable {
  let key: String
  let generation: String
}

enum LocalAttachmentInput: @unchecked Sendable {
  case data(Data, name: String, image: Bool)
  case file(URL, name: String? = nil)
  case image(UIImage)
}

enum LocalAttachmentDiagnostics {
  static func trace(_ message: String) {
    #if DEBUG
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.quickattachments.acceptance",
      ProcessInfo.processInfo.arguments.contains("-quick-attachments-acceptance"),
      let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
    let url = documents.appendingPathComponent("qa-import-stages.json")
    var stages = (try? JSONDecoder().decode([String].self, from: Data(contentsOf: url))) ?? []
    stages.append(String(Date().timeIntervalSince1970) + " " + message)
    if let data = try? JSONEncoder().encode(Array(stages.suffix(40))) {
      try? data.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
    }
    #endif
  }
}

struct PreparedLocalAttachment: Sendable {
  let file: LocalAttachment
  let url: URL
  let journal: URL?
}

struct PreparedAttachmentRecord: Codable {
  let destination: AttachmentDraftDestination
  let file: LocalAttachment
  let sha256: String
}

enum LocalAttachmentPreparation {
  static let maximumBytes = AttachmentFileIntake.maximumBytes

  static func read(_ url: URL) throws -> Data {
    let scoped = url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var data = Data()
    while let chunk = try handle.read(upToCount: 65_536), !chunk.isEmpty {
      guard chunk.count <= maximumBytes - data.count else { throw AttachmentError.tooLarge }
      data.append(chunk)
    }
    guard !data.isEmpty else { throw AttachmentError.tooLarge }
    return data
  }

  static func normalize(_ data: Data, name: String, image: Bool) throws -> (Data, String) {
    guard !data.isEmpty, data.count <= maximumBytes else { throw AttachmentError.tooLarge }
    guard image else { return (data, (name as NSString).lastPathComponent) }
    guard let source = CGImageSourceCreateWithData(data as CFData, nil),
      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
      let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
      let height = properties[kCGImagePropertyPixelHeight] as? NSNumber,
      width.doubleValue > 0, height.doubleValue > 0,
      width.doubleValue * height.doubleValue <= 100_000_000,
      let image = UIImage(data: data) else { throw AttachmentError.unsupportedImage }
    let png = (name as NSString).pathExtension.lowercased() == "png"
    guard let bytes = png ? image.pngData() : image.jpegData(compressionQuality: 0.92) else {
      throw AttachmentError.unsupportedImage
    }
    guard bytes.count <= maximumBytes else { throw AttachmentError.tooLarge }
    let base = ((name as NSString).lastPathComponent as NSString).deletingPathExtension
    return (bytes, (base.isEmpty ? "image" : base) + (png ? ".png" : ".jpg"))
  }

  static func prepare(_ input: LocalAttachmentInput, directory: URL,
    destination: AttachmentDraftDestination? = nil, attachmentId: String? = nil) throws -> PreparedLocalAttachment {
    try Task.checkCancellation()
    let raw: Data, name: String, image: Bool
    var alreadyNormalized = false
    switch input {
    case let .data(value, title, isImage): raw = value; name = title; image = isImage
    case let .file(url, title):
      raw = try read(url); name = title ?? url.lastPathComponent
      image = UTType(filenameExtension: (name as NSString).pathExtension)?.conforms(to: .image) == true
    case let .image(value):
      guard value.size.width * value.size.height * value.scale * value.scale <= 100_000_000,
        let bytes = value.jpegData(compressionQuality: 0.92) else { throw AttachmentError.unsupportedImage }
      raw = bytes; name = "photo.jpg"; image = true
      alreadyNormalized = true
    }
    guard !raw.isEmpty, raw.count <= maximumBytes else { throw AttachmentError.tooLarge }
    let (bytes, normalizedName) = alreadyNormalized ? (raw, name) : try normalize(raw, name: name, image: image)
    try Task.checkCancellation()
    let file = LocalAttachment(id: attachmentId ?? UUID().uuidString, name: normalizedName, size: bytes.count,
      fileName: UUID().uuidString, isImage: image)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let url = directory.appendingPathComponent(file.fileName)
    try bytes.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen])
    var journal: URL?
    if let destination {
      let journals = directory.appendingPathComponent("Prepared", isDirectory: true)
      try FileManager.default.createDirectory(at: journals, withIntermediateDirectories: true)
      let record = PreparedAttachmentRecord(destination: destination, file: file,
        sha256: SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined())
      let path = journals.appendingPathComponent(file.id + ".json")
      do { try JSONEncoder().encode(record).write(to: path, options: [.atomic, .completeFileProtectionUnlessOpen]) }
      catch { try? FileManager.default.removeItem(at: url); throw error }
      journal = path
    }
    return PreparedLocalAttachment(file: file, url: url, journal: journal)
  }
}

extension AttachmentDraftDestination: Codable {}

/// Copy a provider's temporary representation before its callback returns.
struct PickedLocalImage: Transferable, Sendable {
  let data: Data
  let name: String
  static var transferRepresentation: some TransferRepresentation {
    FileRepresentation(importedContentType: .image) { received in
      PickedLocalImage(data: try LocalAttachmentPreparation.read(received.file), name: received.file.lastPathComponent)
    }
  }
}

extension NSItemProvider {
  func localAttachmentImage() async throws -> LocalAttachmentInput {
    traceLocalAttachmentProvider("types=" + registeredTypeIdentifiers.joined(separator: ",") + "; UIImage=" + String(canLoadObject(ofClass: UIImage.self)))
    let types = registeredTypeIdentifiers.filter { $0 != UTType.image.identifier && UTType($0)?.conforms(to: .image) == true }
      + [UTType.image.identifier]
    for type in types {
      try Task.checkCancellation()
      if let file = try await localAttachmentImageFile(type: type) { return file }
    }
    // A system paste provider may vend UIImage without any file representation.
    // The local preparation worker still enforces pixel and encoded-byte limits.
    try Task.checkCancellation()
    return try await withCheckedThrowingContinuation { continuation in
      loadItem(forTypeIdentifier: UTType.image.identifier, options: nil) { object, error in
        self.traceLocalAttachmentProvider("item=" + (object.map { String(reflecting: Swift.type(of: $0)) } ?? "nil") + "; error=" + String((error as NSError?)?.code ?? 0))
        do {
          if let error { throw error }
          if let image = object as? UIImage { continuation.resume(returning: .image(image)) }
          else if let data = object as? Data {
            guard !data.isEmpty, data.count <= LocalAttachmentPreparation.maximumBytes else { throw AttachmentError.tooLarge }
            continuation.resume(returning: .data(data, name: "pasted-image.png", image: true))
          } else if let url = object as? URL {
            continuation.resume(returning: .data(try LocalAttachmentPreparation.read(url), name: url.lastPathComponent, image: true))
          } else { throw AttachmentError.unsupportedImage }
        } catch { continuation.resume(throwing: error) }
      }
    }
  }

  private func localAttachmentImageFile(type: String) async throws -> LocalAttachmentInput? {
    try await withCheckedThrowingContinuation { continuation in
      loadFileRepresentation(forTypeIdentifier: type) { url, error in
        self.traceLocalAttachmentProvider("file=" + type + "; error=" + String((error as NSError?)?.code ?? 0))
        guard error == nil, let url else { continuation.resume(returning: nil); return }
        do {
          continuation.resume(returning: .data(try LocalAttachmentPreparation.read(url), name: url.lastPathComponent, image: true))
        } catch { continuation.resume(throwing: error) }
      }
    }
  }

  private func traceLocalAttachmentProvider(_ message: String) {
    #if DEBUG
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.quickattachments.acceptance",
      ProcessInfo.processInfo.arguments.contains("-quick-attachments-acceptance") else { return }
    print("QUICK_ATTACHMENT_PROVIDER " + message)
    LocalAttachmentDiagnostics.trace(message)
    #endif
  }
}
