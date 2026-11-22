import Foundation
import CryptoKit
import Darwin

/// Maps EasyWork's authenticated WSS profile to the common fixed-version reader.
/// Player state, partial cache, renewal and AVFoundation remain in MediaPlayback.
enum EasyWorkMediaReads {
  typealias Request = @Sendable (String, [String: Any], String?) async throws -> [String: Any]
  private struct Envelope: Decodable {
    let version: Int
    let hostId: String
    let readTarget: AgentRuntimeTarget
    let threadId: String
    let sourceAttachmentId: String
    let readId: String
    let phase: String?
    let preparedBytes: Int64?
    let totalBytes: Int64?
    let remainingSeconds: Int?
    let attachment: RemoteAttachment?
    let etag: String?
    let status: Int?
    let contentRange: String?
    let dataBase64: String?
    let cancelled: Bool?
  }
  private static func target(_ client: Client, file: RemoteAttachment, readId: String) throws -> AgentRuntimeTarget {
    guard let target = client.agentConnection?.target, target.agentId == "easywork", target.account != nil,
      file.hostId == client.id, !file.threadId.isEmpty, !file.id.isEmpty,
      UUID(uuidString: readId) != nil else { throw HostServiceError.wrongHost }
    return target
  }
  private static func params(_ file: RemoteAttachment, _ readId: String) -> [String: Any] {
    ["read": "range-v1", "resourceId": file.id, "threadId": file.threadId, "readId": readId.lowercased()]
  }
  private static func call(_ method: String, _ params: [String: Any], operationId: String? = nil, request: Request) async throws -> [String: Any] {
    do { return try await request(method, params, operationId) }
    catch let error as EasyWorkRemoteRejection {
      let code = error.code ?? ""
      if code.hasPrefix("attachment_read_"), let status = Int(code.dropFirst("attachment_read_".count)),
        [400, 403, 409, 410, 412, 416, 428, 500, 503, 504, 507].contains(status) {
        throw HostServiceError.rejected(status, L10n.tr("读取电脑资源失败，请重试。"))
      }
      if code == "transfer_forbidden" { throw HostServiceError.rejected(403, L10n.tr("读取电脑资源失败，请重试。")) }
      if ["resource_not_found", "source_file_unavailable"].contains(code) {
        throw HostServiceError.rejected(410, L10n.tr("读取电脑资源失败，请重试。"))
      }
      throw error
    }
  }
  private static func envelope(_ raw: [String: Any], expected: AgentRuntimeTarget,
    file: RemoteAttachment, readId: String) throws -> Envelope {
    let value = try JSONDecoder().decode(Envelope.self, from: easyWorkJSON(raw))
    guard value.version == 1, value.hostId == expected.hostId, value.readTarget == expected,
      value.threadId == file.threadId, value.sourceAttachmentId == file.id,
      value.readId.lowercased() == readId.lowercased() else { throw HostServiceError.wrongHost }
    return value
  }
  private static func context(_ expected: AgentRuntimeTarget, request: Request) async throws -> [String: Any] {
    let context = try await call("runtime/context", [:], request: request)
    let actual = try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(context["target"] ?? [:]))
    let confirmed = AgentRuntimeTarget(agentId: "easywork", hostId: actual.desktopDeviceId,
      workspaceId: actual.workspaceId, runtimeId: actual.runtimeId, account: actual.account)
    guard confirmed == expected else { throw HostServiceError.wrongHost }
    return context
  }
  private static func supports(_ expected: AgentRuntimeTarget, request: Request) async throws -> Bool {
    (try await context(expected, request: request)["capabilities"] as? [String] ?? []).contains("attachments:range-read:v1")
  }
  static func prepare(_ client: Client, file: RemoteAttachment, readId: String,
    progress: @escaping @Sendable (Int64, Int64) -> Void, request: Request) async throws -> MediaReadDescriptor? {
    let expected = try target(client, file: file, readId: readId)
    guard try await supports(expected, request: request) else { return nil }
    let started = ContinuousClock.now, deadline = ContinuousClock.now.advanced(by: .seconds(125))
    while ContinuousClock.now < deadline {
      try Task.checkCancellation()
      let raw = try await call("resource/read", params(file, readId), request: request)
      try Task.checkCancellation()
      guard ContinuousClock.now < deadline else { throw URLError(.timedOut) }
      let value = try envelope(raw, expected: expected, file: file, readId: readId)
      if value.phase == "ready" {
        guard let wire = value.attachment, let etag = value.etag,
          wire.id == file.id, wire.hostId == expected.hostId, wire.threadId == file.threadId,
          wire.name == file.name, wire.mediaType == file.mediaType,
          wire.sizeBytes >= 0, wire.sizeBytes <= 2 * 1024 * 1024 * 1024,
          wire.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
          etag == "\"" + wire.sha256 + "\"" else { throw HostServiceError.invalidResponse }
        let elapsed = started.duration(to: ContinuousClock.now).components
        let seconds = Double(elapsed.seconds) + Double(elapsed.attoseconds) / 1e18
        let remaining = value.remainingSeconds.map(Double.init) ?? max(0, 600 - seconds)
        guard remaining > 0, remaining <= 600 else { throw URLError(.timedOut) }
        var mapped = RemoteAttachment(id: wire.id, hostId: client.id, threadId: wire.threadId,
          name: wire.name, mediaType: wire.mediaType, sizeBytes: wire.sizeBytes, sha256: wire.sha256)
        mapped.resourceId = file.resourceId; mapped.documentSource = file.documentSource
        mapped.documentMessageSource = file.documentMessageSource
        return MediaReadDescriptor(readId: readId, etag: etag, file: mapped,
          renewAfter: ContinuousClock.now.advanced(by: .seconds(max(0, remaining - min(60, max(1, remaining / 5))))))
      }
      guard value.phase == "preparing", (value.preparedBytes ?? 0) >= 0,
        (value.totalBytes ?? 0) >= 0 else { throw HostServiceError.invalidResponse }
      progress(value.preparedBytes ?? 0, value.totalBytes ?? 0)
      try await Task.sleep(for: .milliseconds(200))
    }
    throw URLError(.timedOut)
  }
  static func bytes(_ client: Client, source: RemoteAttachment, read: MediaReadDescriptor,
    offset: Int64, count: Int, request: Request) async throws -> Data {
    let expected = try target(client, file: source, readId: read.readId)
    guard read.file.hostId == client.id, read.file.threadId == source.threadId, read.file.id == source.id,
      read.etag == "\"" + read.file.sha256 + "\"", offset >= 0, count > 0,
      count <= 256 * 1024, offset <= Int64(read.file.sizeBytes) - Int64(count) else { throw HostServiceError.invalidResponse }
    var result = Data(capacity: count)
    while result.count < count {
      try Task.checkCancellation()
      let length = min(192 * 1024, count - result.count), start = offset + Int64(result.count)
      var input = params(source, read.readId)
      input["range"] = "bytes=\(start)-\(start + Int64(length) - 1)"; input["ifMatch"] = read.etag
      let raw = try await call("resource/download", input, request: request)
      try Task.checkCancellation()
      let value = try envelope(raw, expected: expected, file: source, readId: read.readId)
      guard value.status == 206, value.etag == read.etag,
        value.contentRange == "bytes \(start)-\(start + Int64(length) - 1)/\(read.file.sizeBytes)",
        let encoded = value.dataBase64, encoded.utf8.count <= ((length + 2) / 3) * 4,
        let bytes = Data(base64URL: encoded), bytes.count == length else { throw HostServiceError.invalidResponse }
      result.append(bytes)
    }
    return result
  }
  static func cancel(_ client: Client, file: RemoteAttachment, readId: String, request: Request) async -> Bool {
    do {
      let expected = try target(client, file: file, readId: readId)
      guard try await supports(expected, request: request) else { return false }
      let raw = try await call("resource/read-cancel", params(file, readId), request: request)
      let value = try envelope(raw, expected: expected, file: file, readId: readId)
      return value.cancelled == true
    } catch HostServiceError.rejected(410, _) { return true }
    catch { return false }
  }
  private static func destination(_ client: Client, file: RemoteAttachment) throws -> URL {
    guard let target = client.agentConnection?.target, target.agentId == "easywork", target.account != nil,
      file.hostId == client.id, !file.threadId.isEmpty, !file.id.isEmpty,
      file.sizeBytes >= 0, file.sizeBytes <= 2 * 1024 * 1024 * 1024,
      !file.name.isEmpty, ![".", ".."].contains(file.name), file.name == (file.name as NSString).lastPathComponent,
      file.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw HostServiceError.invalidResponse }
    let key = HostService.hash(try easyWorkJSON([target.agentId, target.hostId, target.workspaceId,
      target.runtimeId, target.account?.authority ?? "", target.account?.subject ?? "", file.threadId, file.id, file.sha256]))
    let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].resolvingSymlinksInPath()
    let path = base.appendingPathComponent("MediaComplete/v1/" + key, isDirectory: true).appendingPathComponent(file.name)
    guard path.standardizedFileURL.path == path.resolvingSymlinksInPath().path else { throw HostServiceError.invalidResponse }
    return path
  }
  static func cached(_ client: Client, file: RemoteAttachment) throws -> URL? {
    let url = try destination(client, file: file)
    let fd = Darwin.open(url.path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW)
    guard fd >= 0 else { return nil }
    let input = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    defer { try? input.close() }
    var before = stat()
    guard fstat(fd, &before) == 0, before.st_mode & S_IFMT == S_IFREG, before.st_size == file.sizeBytes else { return nil }
    var digest = SHA256(), size = 0
    while size < file.sizeBytes {
      let count = try autoreleasepool { () throws -> Int in
        let bytes = try input.read(upToCount: min(64 * 1024, file.sizeBytes - size)) ?? Data()
        digest.update(data: bytes); return bytes.count
      }
      guard count > 0 else { return nil }; size += count
    }
    var after = stat()
    guard fstat(fd, &after) == 0, before.st_ino == after.st_ino, before.st_dev == after.st_dev,
      before.st_size == after.st_size, before.st_mtimespec.tv_sec == after.st_mtimespec.tv_sec,
      before.st_mtimespec.tv_nsec == after.st_mtimespec.tv_nsec,
      before.st_ctimespec.tv_sec == after.st_ctimespec.tv_sec, before.st_ctimespec.tv_nsec == after.st_ctimespec.tv_nsec,
      digest.finalize().map({ String(format: "%02x", $0) }).joined() == file.sha256 else { return nil }
    return url
  }
  private struct CompleteRecord: Decodable {
    let transferId: String; let resourceId: String; let threadId: String; let mobileDeviceId: String
    let name: String; let contentType: String; let size: Int; let sha256: String
    let status: String; let transferredBytes: Int
  }
  private static func completeRecord(_ raw: [String: Any], file: RemoteAttachment, deviceId: String) throws -> CompleteRecord {
    let record = try JSONDecoder().decode(CompleteRecord.self, from: easyWorkJSON(raw["transfer"] ?? [:]))
    guard !record.transferId.isEmpty, record.transferId.utf8.count <= 480,
      record.resourceId == file.id, record.threadId == file.threadId, record.mobileDeviceId == deviceId,
      record.name == file.name, record.contentType == file.mediaType, record.size == file.sizeBytes,
      record.sha256 == file.sha256, ["waiting", "transferring", "verifying", "completed"].contains(record.status)
      else { throw HostServiceError.invalidResponse }
    return record
  }
  /// Complete-file reads retain the original transfer/receipt protocol. Progressive
  /// playback never marks a transfer complete, and partial caches never become exports.
  static func download(_ client: Client, file: RemoteAttachment, deviceId: String,
    progress: FileDownloadProgressHandler?, request: Request) async throws -> URL? {
    let expected = try target(client, file: file, readId: UUID().uuidString)
    let snapshot = try await context(expected, request: request)
    let capabilities = snapshot["capabilities"] as? [String] ?? []
    guard capabilities.contains("attachments:download") else { return nil }
    var lookup: [String: Any] = ["resourceId": file.id]
    if capabilities.contains("attachments:download:thread-bound") { lookup["threadId"] = file.threadId }
    let record = try completeRecord(await call("resource/read", lookup, request: request), file: file, deviceId: deviceId)
    let url = try destination(client, file: file), parent = url.deletingLastPathComponent()
    try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    _ = try destination(client, file: file)
    let free = (try FileManager.default.attributesOfFileSystem(forPath: parent.path)[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
    guard free >= Int64(file.sizeBytes) + 16 * 1024 * 1024 else { throw CocoaError(.fileWriteOutOfSpace) }
    let temporary = parent.appendingPathComponent(UUID().uuidString + ".partial")
    let fd = Darwin.open(temporary.path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0o600)
    guard fd >= 0 else { throw CocoaError(.fileWriteUnknown) }
    let output = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    defer { try? output.close(); try? FileManager.default.removeItem(at: temporary) }
    var offset = 0, digest = SHA256()
    let meter = FileDownloadMeter(progress)
    progress?(FileDownloadProgress(phase: .downloading, totalBytes: Int64(file.sizeBytes)))
    while offset < file.sizeBytes {
      try Task.checkCancellation()
      let count = min(192 * 1024, file.sizeBytes - offset)
      let raw = try await call("resource/download", ["resourceId": file.id, "transferId": record.transferId,
        "offset": offset, "length": count], request: request)
      try Task.checkCancellation()
      guard raw["resourceId"] as? String == file.id, raw["transferId"] as? String == record.transferId,
        try EasyWorkReading.integer(raw["offset"]) == offset,
        let encoded = raw["dataBase64"] as? String, encoded.utf8.count <= ((count + 2) / 3) * 4,
        let bytes = Data(base64URL: encoded), bytes.count == count,
        try EasyWorkReading.integer(raw["nextOffset"]) == offset + count,
        let complete = raw["complete"] as? NSNumber, CFGetTypeID(complete) == CFBooleanGetTypeID(),
        complete.boolValue == (offset + count == file.sizeBytes) else { throw HostServiceError.invalidResponse }
      try output.write(contentsOf: bytes); digest.update(data: bytes); offset += bytes.count
      meter.receive(Int64(offset), total: Int64(file.sizeBytes))
    }
    try Task.checkCancellation()
    progress?(FileDownloadProgress(phase: .verifying, receivedBytes: Int64(offset), totalBytes: Int64(file.sizeBytes)))
    guard digest.finalize().map({ String(format: "%02x", $0) }).joined() == file.sha256 else {
      throw HostServiceError.message(L10n.tr("文件完整性校验失败"))
    }
    try output.synchronize(); try output.close()
    // The original stable command ID and exact body retain original-receipt-only
    // recovery. A lost reply cannot turn into another completion operation.
    let ack = try await call("resource/download-complete", ["resourceId": file.id, "transferId": record.transferId],
      operationId: "download-complete-" + record.transferId, request: request)
    let confirmed = try completeRecord(ack, file: file, deviceId: deviceId)
    guard confirmed.transferId == record.transferId, confirmed.status == "completed",
      confirmed.transferredBytes == file.sizeBytes else { throw HostServiceError.invalidResponse }
    try Task.checkCancellation(); _ = try destination(client, file: file)
    guard Darwin.rename(temporary.path, url.path) == 0 else { throw CocoaError(.fileWriteUnknown) }
    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
    return url
  }
}
