import CryptoKit
import CoreFoundation
import Darwin
import Foundation

/// A standalone offer has no conversation. Its desktop IDs remain unchanged.
struct IncomingComputerFile: Codable, Equatable, Sendable, Identifiable {
  let hostId: String
  let id: String
  let resourceId: String
  let mobileDeviceId: String
  let name: String
  let mediaType: String
  let sizeBytes: Int
  let sha256: String
  let phase: String
  let createdAt: String

  func validate(hostId: String, deviceId: String? = nil) throws {
    guard !hostId.isEmpty, self.hostId == hostId, deviceId.map({ $0 == mobileDeviceId }) ?? true,
      !mobileDeviceId.isEmpty, [id, resourceId].allSatisfy({ !$0.isEmpty && $0.utf8.count <= 256 && !$0.contains("/") && !$0.contains("\\") && !$0.contains("\0") && ![".", ".."].contains($0) }),
      !name.isEmpty, name.utf8.count <= 1024, name == (name as NSString).lastPathComponent,
      !name.contains("\\"), !name.contains("\0"), ![".", ".."].contains(name),
      !mediaType.isEmpty, sizeBytes >= 0,
      sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
      ["waiting", "transferring", "verifying", "completed", "failed", "cancelled"].contains(phase),
      !createdAt.isEmpty else { throw HostServiceError.invalidResponse }
  }
  func sameFile(as other: Self) -> Bool {
    hostId == other.hostId && id == other.id && resourceId == other.resourceId && mobileDeviceId == other.mobileDeviceId &&
      name == other.name && mediaType == other.mediaType && sizeBytes == other.sizeBytes && sha256 == other.sha256 && createdAt == other.createdAt
  }
  var snapshotKey: String {
    let values = [hostId, id, resourceId, mobileDeviceId, name, mediaType, String(sizeBytes), sha256, createdAt]
    return SHA256.hash(data: try! JSONEncoder().encode(values)).map { String(format: "%02x", $0) }.joined()
  }
}

enum EasyWorkIncomingFiles {
  static func record(_ value: [String: Any], hostId: String, deviceId: String) throws -> IncomingComputerFile {
    guard value["purpose"] as? String == "transfer", value["direction"] as? String == "desktop-to-mobile",
      value["threadId"] == nil, let id = value["transferId"] as? String, let resource = value["resourceId"] as? String,
      let device = value["mobileDeviceId"] as? String, let name = value["name"] as? String,
      let media = value["contentType"] as? String, let digest = value["sha256"] as? String,
      let phase = value["status"] as? String, let created = value["createdAt"] as? String else { throw HostServiceError.invalidResponse }
    let file = IncomingComputerFile(hostId: hostId, id: id, resourceId: resource, mobileDeviceId: device, name: name,
      mediaType: media, sizeBytes: try EasyWorkReading.integer(value["size"]), sha256: digest, phase: phase, createdAt: created)
    try file.validate(hostId: hostId, deviceId: deviceId)
    let transferred = try EasyWorkReading.integer(value["transferredBytes"])
    guard transferred <= file.sizeBytes, phase != "completed" || transferred == file.sizeBytes else { throw HostServiceError.invalidResponse }
    return file
  }

  static func destination(_ file: IncomingComputerFile, directory: URL) throws -> URL {
    try file.validate(hostId: file.hostId)
    // Resolve the trusted container first (system paths can contain /var aliases).
    // Inspect each owned component itself: resolving a nonexistent leaf alone
    // does not reliably detect a linked parent on all Apple platforms.
    var rootInfo = stat()
    if lstat(directory.path, &rootInfo) == 0 {
      guard rootInfo.st_mode & S_IFMT == S_IFDIR else { throw HostServiceError.invalidResponse }
    } else if errno != ENOENT { throw HostServiceError.invalidResponse }
    let parent = directory.resolvingSymlinksInPath().appendingPathComponent(file.snapshotKey, isDirectory: true)
    let result = parent.appendingPathComponent(file.name)
    for (url, expected) in [(parent, S_IFDIR), (result, S_IFREG)] {
      var info = stat()
      if lstat(url.path, &info) == 0 {
        guard info.st_mode & S_IFMT == expected else { throw HostServiceError.invalidResponse }
      } else if errno != ENOENT { throw HostServiceError.invalidResponse }
    }
    return result
  }

  static func cached(_ file: IncomingComputerFile, directory: URL) throws -> URL? {
    let result = try destination(file, directory: directory)
    let descriptor = Darwin.open(result.path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW)
    guard descriptor >= 0 else { return nil }
    let input = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
    defer { try? input.close() }
    var info = stat()
    guard fstat(descriptor, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_size == file.sizeBytes else { return nil }
    var digest = SHA256(), count = 0
    while let bytes = try input.read(upToCount: 192 * 1024), !bytes.isEmpty {
      try Task.checkCancellation()
      guard bytes.count <= file.sizeBytes - count else { return nil }
      digest.update(data: bytes); count += bytes.count
    }
    let actual = digest.finalize().map { String(format: "%02x", $0) }.joined()
    return count == file.sizeBytes && actual == file.sha256 ? result : nil
  }

  /// Fixed named requests, bounded chunks, hash-before-publication and original-ID acknowledgement.
  static func download(_ file: IncomingComputerFile, directory: URL, progress: FileDownloadProgressHandler?,
    request: (String, [String: Any], String?) async throws -> [String: Any]) async throws -> URL {
    let destination = try destination(file, directory: directory)
    let cached = try cached(file, directory: directory)
    if cached == nil {
      let parent = destination.deletingLastPathComponent()
      try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
      _ = try self.destination(file, directory: directory)
      let temporary = parent.appendingPathComponent(UUID().uuidString + ".partial")
      let descriptor = Darwin.open(temporary.path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0o600)
      guard descriptor >= 0 else { throw CocoaError(.fileWriteUnknown) }
      let output = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
      defer { try? output.close(); try? FileManager.default.removeItem(at: temporary) }
      var offset = 0, digest = SHA256()
      let meter = FileDownloadMeter(progress)
      progress?(FileDownloadProgress(phase: .downloading, totalBytes: Int64(file.sizeBytes)))
      while offset < file.sizeBytes {
        try Task.checkCancellation()
        let length = min(192 * 1024, file.sizeBytes - offset)
        let value = try await request("resource/download", ["resourceId": file.resourceId, "transferId": file.id, "offset": offset, "length": length], nil)
        guard value["resourceId"] as? String == file.resourceId, value["transferId"] as? String == file.id,
          try EasyWorkReading.integer(value["offset"]) == offset,
          let bytes = Data(base64URL: value["dataBase64"] as? String ?? ""), !bytes.isEmpty, bytes.count <= length,
          try EasyWorkReading.integer(value["nextOffset"]) == offset + bytes.count,
          let complete = value["complete"] as? NSNumber, CFGetTypeID(complete) == CFBooleanGetTypeID(),
          complete.boolValue == (offset + bytes.count == file.sizeBytes)
        else { throw HostServiceError.invalidResponse }
        try output.write(contentsOf: bytes); digest.update(data: bytes); offset += bytes.count
        meter.receive(Int64(offset), total: Int64(file.sizeBytes))
      }
      try Task.checkCancellation()
      progress?(FileDownloadProgress(phase: .verifying, receivedBytes: Int64(offset), totalBytes: Int64(file.sizeBytes)))
      guard digest.finalize().map({ String(format: "%02x", $0) }).joined() == file.sha256 else {
        throw HostServiceError.message(L10n.tr("文件完整性校验失败"))
      }
      try output.synchronize(); try output.close()
      _ = try self.destination(file, directory: directory)
      // rename replaces only this immutable snapshot's exact destination, never another offer.
      guard Darwin.rename(temporary.path, destination.path) == 0 else { throw CocoaError(.fileWriteUnknown) }
      try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: destination.path)
    }
    try Task.checkCancellation()
    let reply = try await request("resource/download-complete", ["resourceId": file.resourceId, "transferId": file.id], "file-receive-complete-" + file.snapshotKey)
    guard let raw = reply["transfer"] as? [String: Any] else { throw HostServiceError.invalidResponse }
    let confirmed = try record(raw, hostId: file.hostId, deviceId: file.mobileDeviceId)
    guard confirmed.sameFile(as: file), confirmed.phase == "completed" else { throw HostServiceError.invalidResponse }
    progress?(FileDownloadProgress(phase: .cached, receivedBytes: Int64(file.sizeBytes), totalBytes: Int64(file.sizeBytes)))
    return destination
  }
}
