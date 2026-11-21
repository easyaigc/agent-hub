import AVFoundation
import SwiftUI
import UniformTypeIdentifiers
import CryptoKit
import Darwin

struct MediaReadDescriptor: Sendable {
  let readId: String
  let etag: String
  let file: RemoteAttachment
  let renewAfter: ContinuousClock.Instant
}

enum MediaPlaybackFormat {
  static func accepts(name: String, type: String) -> Bool {
    type.lowercased().hasPrefix("video/") || ["mp4", "m4v", "mov"].contains((name as NSString).pathExtension.lowercased())
  }
  static func external(_ url: URL) -> Bool {
    ["https", "http"].contains(url.scheme?.lowercased() ?? "") && url.user == nil && url.password == nil
      && ["mp4", "m4v", "mov", "m3u8"].contains(url.pathExtension.lowercased())
  }
}

extension HostService {
  private static func mediaJSON<T: Decodable>(_ request: URLRequest) async throws -> T {
    try JSONDecoder().decode(T.self, from: try await bytes(request, maxBytes: 64 * 1024))
  }
  private static func mediaQuery(_ client: Client, file: RemoteAttachment, readId: String) throws -> [String: String] {
    guard let target = client.readingTarget ?? client.codexDirect?.target, target.hostId == client.id,
      file.hostId == client.id, !file.threadId.isEmpty, !file.id.isEmpty,
      UUID(uuidString: readId) != nil else { throw HostServiceError.wrongHost }
    return ["target": String(decoding: try JSONEncoder().encode(target), as: UTF8.self),
      "threadId": file.threadId, "id": file.id, "read": "range-v1", "readId": readId]
  }
  static func prepareMediaRead(_ client: Client, file: RemoteAttachment, readId: String,
    progress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> MediaReadDescriptor? {
    guard (client.readingTarget ?? client.codexDirect?.target) != nil else { return nil }
    struct Health: Decodable { let capabilities: [String] }
    CodexHostRoutes.shared.register(client)
    let health: Health
    var probe = request(try url(client, "healthz"), token: CredentialVault.token(client.id)); probe.timeoutInterval = 8
    do { health = try await mediaJSON(probe) }
    catch HostServiceError.rejected(404, _) { return nil }
    guard health.capabilities.contains("attachments:range-read:v1") else { return nil }
    struct State: Decodable {
      let version: Int; let hostId: String; let readTarget: AgentRuntimeTarget
      let threadId: String; let sourceAttachmentId: String; let readId: String; let phase: String
      let preparedBytes: Int64?; let totalBytes: Int64?; let attachment: RemoteAttachment?; let etag: String?
      let remainingSeconds: Int?
    }
    let requestedAt = ContinuousClock.now
    let query = try mediaQuery(client, file: file, readId: readId)
    let deadline = ContinuousClock.now.advanced(by: .seconds(125))
    while ContinuousClock.now < deadline {
      try Task.checkCancellation()
      var poll = request(try url(client, "v1/attachments/resolve", query), token: CredentialVault.token(client.id))
      let remaining = ContinuousClock.now.duration(to: deadline).components
      poll.timeoutInterval = max(0.1, min(10, Double(remaining.seconds) + Double(remaining.attoseconds) / 1e18))
      let value: State = try await mediaJSON(poll)
      guard ContinuousClock.now < deadline else { throw URLError(.timedOut) }
      guard value.version == 1, value.hostId == client.id, value.threadId == file.threadId,
        value.sourceAttachmentId == file.id, value.readId.lowercased() == readId.lowercased(),
        let target = client.readingTarget ?? client.codexDirect?.target,
        SharedMobileRules.machineReadingKey(value.readTarget, threadId: file.threadId)
          == SharedMobileRules.machineReadingKey(target, threadId: file.threadId) else { throw HostServiceError.wrongHost }
      if value.phase == "ready" {
        guard let current = value.attachment, let etag = value.etag,
          current.hostId == file.hostId, current.threadId == file.threadId,
          current.name == file.name, current.mediaType == file.mediaType, current.sizeBytes >= 0,
          current.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
          etag == "\"" + current.sha256 + "\"" else { throw HostServiceError.invalidResponse }
        // range-v1 has a ten-minute absolute lease. Older Hosts omit the
        // relative field, so conservatively count from our first request.
        let elapsed = requestedAt.duration(to: ContinuousClock.now).components
        let elapsedSeconds = Double(elapsed.seconds) + Double(elapsed.attoseconds) / 1e18
        let remaining = value.remainingSeconds.map(Double.init) ?? max(0, 600 - elapsedSeconds)
        guard remaining > 0, remaining <= 600 else { throw URLError(.timedOut) }
        let margin = min(60, max(1, remaining / 5))
        return MediaReadDescriptor(readId: readId, etag: etag, file: current,
          renewAfter: ContinuousClock.now.advanced(by: .seconds(max(0, remaining - margin))))
      }
      guard value.phase == "preparing", (value.preparedBytes ?? 0) >= 0, (value.totalBytes ?? 0) >= 0 else { throw HostServiceError.invalidResponse }
      progress(value.preparedBytes ?? 0, value.totalBytes ?? 0)
      try await Task.sleep(for: .milliseconds(200))
    }
    throw URLError(.timedOut)
  }
  static func mediaBytes(_ client: Client, source: RemoteAttachment, read: MediaReadDescriptor,
    offset: Int64, count: Int) async throws -> Data {
    guard offset >= 0, count > 0, count <= 256 * 1024, offset <= Int64(read.file.sizeBytes) - Int64(count) else { throw HostServiceError.invalidResponse }
    let query = try mediaQuery(client, file: source, readId: read.readId)
    var request = request(try url(client, "v1/attachments", query), token: CredentialVault.token(client.id))
    request.timeoutInterval = 8
    request.setValue(read.etag, forHTTPHeaderField: "If-Match")
    request.setValue("bytes=\(offset)-\(offset + Int64(count) - 1)", forHTTPHeaderField: "Range")
    request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
    let (bytes, response) = try await CodexHostRoutes.shared.data(for: request, maxBytes: max(8192, count))
    guard let http = response as? HTTPURLResponse else { throw HostServiceError.invalidResponse }
    guard http.statusCode == 206 else {
      let detail = ((try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any])?["error"] as? String
      throw HostServiceError.rejected(http.statusCode, detail ?? L10n.tr("读取电脑资源失败，请重试。"))
    }
    guard bytes.count == count, http.value(forHTTPHeaderField: "ETag") == read.etag,
      http.value(forHTTPHeaderField: "Content-Range") == "bytes \(offset)-\(offset + Int64(count) - 1)/\(read.file.sizeBytes)",
      http.value(forHTTPHeaderField: "Content-Encoding").map({ $0.lowercased() == "identity" }) ?? true
    else { throw HostServiceError.invalidResponse }
    return bytes
  }
  @discardableResult static func cancelMediaRead(_ client: Client, file: RemoteAttachment, readId: String) async -> Bool {
    guard let query = try? mediaQuery(client, file: file, readId: readId),
      let address = try? url(client, "v1/attachments/resolve", query) else { return false }
    var request = request(address, token: CredentialVault.token(client.id), method: "DELETE")
    request.timeoutInterval = 5
    guard let (_, response) = try? await CodexHostRoutes.shared.data(for: request, maxBytes: 4096),
      let http = response as? HTTPURLResponse else { return false }
    return [200, 204, 410].contains(http.statusCode)
  }
}

/// App-private, bounded partial cache. A fresh authorized descriptor is required before use.
private actor MediaDiskCache {
  static let shared = MediaDiskCache()
  private var leases: [String: Int] = [:]
  private var inventory: [String: (bytes: Int64, used: Date)]?
  private let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    .resolvingSymlinksInPath().appendingPathComponent("MediaParts/v1", isDirectory: true)
  func acquire(_ key: String) { leases[key, default: 0] += 1 }
  func release(_ key: String) { if let count = leases[key] { if count <= 1 { leases.removeValue(forKey: key) } else { leases[key] = count - 1 } } }
  private func folder(_ key: String) throws -> URL {
    let folder = root.appendingPathComponent(key, isDirectory: true)
    for directory in [root.deletingLastPathComponent(), root, folder] {
      if let attributes = try? FileManager.default.attributesOfItem(atPath: directory.path) {
        guard attributes[.type] as? FileAttributeType == .typeDirectory,
          directory.standardizedFileURL.path == directory.resolvingSymlinksInPath().path else { throw HostServiceError.invalidResponse }
      } else { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]) }
    }
    return folder
  }
  func load(_ key: String, offset: Int64, count: Int) -> Data? {
    guard let folder = try? folder(key) else { return nil }
    let path = folder.appendingPathComponent(String(offset) + ".part")
    let descriptor = Darwin.open(path.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
    guard descriptor >= 0 else { return nil }
    let input = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
    defer { try? input.close() }
    var info = stat()
    guard fstat(descriptor, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_size == Int64(count + 32),
      let bytes = try? input.read(upToCount: count + 32), bytes.count == count + 32 else { return nil }
    let content = bytes.dropFirst(32)
    guard Data(SHA256.hash(data: content)) == bytes.prefix(32) else { return nil }
    try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: folder.path)
    if let entry = inventory?[key] { inventory?[key] = (entry.bytes, Date()) }
    return Data(content)
  }
  func store(_ key: String, offset: Int64, data: Data) {
    let manager = FileManager.default
    guard let folder = try? folder(key) else { return }
    let target = folder.appendingPathComponent(String(offset) + ".part")
    let temporary = folder.appendingPathComponent(UUID().uuidString + ".tmp")
    defer { try? manager.removeItem(at: temporary) }
    do {
      // Reserve space by evicting only inactive version directories. The cache
      // is optional: low space must not turn into unbounded disk writes.
      if inventory == nil {
        var values: [String: (bytes: Int64, used: Date)] = [:]
        for directory in try manager.contentsOfDirectory(at: root, includingPropertiesForKeys: [.contentModificationDateKey]) {
          guard directory.standardizedFileURL.path == directory.resolvingSymlinksInPath().path else { continue }
          let files = (try? manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])) ?? []
          let size = files.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
          values[directory.lastPathComponent] = (size, (try? directory.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
        }
        inventory = values
      }
      let previous = ((try? manager.attributesOfItem(atPath: target.path)[.size]) as? NSNumber)?.int64Value ?? 0
      let increase = Int64(data.count + 32) - previous
      var total = inventory?.values.reduce(Int64(0), { $0 + $1.bytes }) ?? 0
      for (candidate, value) in (inventory ?? [:]).sorted(by: { $0.value.used < $1.value.used }) where total + increase > 64 * 1024 * 1024 {
        guard leases[candidate] == nil else { continue }
        let directory = root.appendingPathComponent(candidate, isDirectory: true)
        guard directory.standardizedFileURL.path == directory.resolvingSymlinksInPath().path else { continue }
        if manager.fileExists(atPath: directory.path) { try manager.removeItem(at: directory) }
        total -= value.bytes; inventory?.removeValue(forKey: candidate)
      }
      guard total + increase <= 64 * 1024 * 1024 else { return }
      let volume = try folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
      guard (volume.volumeAvailableCapacityForImportantUsage ?? 0) > 32 * 1024 * 1024 else { return }
      var bytes = Data(SHA256.hash(data: data)); bytes.append(data)
      guard manager.createFile(atPath: temporary.path, contents: bytes,
        attributes: [.posixPermissions: 0o600, .protectionKey: FileProtectionType.completeUnlessOpen]) else { return }
      guard Darwin.rename(temporary.path, target.path) == 0 else { return }
      let updatedBytes = (inventory?[key]?.bytes ?? 0) + increase
      inventory?[key] = (updatedBytes, Date())
    } catch { /* Cache misses retain normal authenticated playback. */ }
  }
}

/// Four MiB of immutable blocks in memory. Partial bytes never enter the complete-file cache.
private actor MediaByteReader {
  let client: Client
  let source: RemoteAttachment
  private var descriptor: MediaReadDescriptor
  let service: any AgentClient
  private var blocks: [Int64: Data] = [:]
  private var order: [Int64] = []
  private var closed = false
  private let cacheKey: String
  private var lease = false
  private var readIds: Set<String>
  private var renewal: Task<MediaReadDescriptor, Error>?
  private var renewalFailure: Error?
  private var networkUses = 0
  let received: @Sendable (Int) -> Void
  init(client: Client, source: RemoteAttachment, descriptor: MediaReadDescriptor,
    service: any AgentClient, received: @escaping @Sendable (Int) -> Void) {
    self.client = client; self.source = source; self.descriptor = descriptor; self.service = service; self.received = received
    readIds = [descriptor.readId]
    let scope = (client.readingTarget ?? client.codexDirect?.target).map { SharedMobileRules.machineReadingKey($0, threadId: source.threadId) } ?? client.id + "\n" + source.threadId
    cacheKey = HostService.hash(Data((scope + "\n" + source.id + "\n" + descriptor.file.sha256).utf8))
  }
  func bytes(at offset: Int64) async throws -> Data {
    try Task.checkCancellation()
    guard !closed, offset >= 0, offset < Int64(descriptor.file.sizeBytes) else { throw CancellationError() }
    let read = try await activeRead()
    defer { networkUses -= 1 }
    try Task.checkCancellation(); guard !closed else { throw CancellationError() }
    let start = offset / 262_144 * 262_144
    if let cached = blocks[start] { return cached }
    let count = Int(min(262_144, Int64(descriptor.file.sizeBytes) - start))
    if !lease { lease = true; await MediaDiskCache.shared.acquire(cacheKey) }
    if let cached = await MediaDiskCache.shared.load(cacheKey, offset: start, count: count) {
      try Task.checkCancellation(); guard !closed else { throw CancellationError() }
      blocks[start] = cached; order.append(start)
      while order.count > 16 { blocks.removeValue(forKey: order.removeFirst()) }
      return cached
    }
    var result: Data?
    for attempt in 0..<3 {
      do { result = try await service.mediaBytes(client, source: source, read: read, offset: start, count: count); break }
      catch let error as URLError where [.networkConnectionLost, .notConnectedToInternet, .timedOut, .cannotConnectToHost].contains(error.code) && attempt < 2 {
        try await Task.sleep(for: .milliseconds(attempt == 0 ? 300 : 1000))
      }
    }
    try Task.checkCancellation()
    guard !closed, let result, result.count == count else { throw HostServiceError.invalidResponse }
    received(count)
    if blocks[start] == nil { order.append(start) }
    blocks[start] = result
    while order.count > 16 { blocks.removeValue(forKey: order.removeFirst()) }
    await MediaDiskCache.shared.store(cacheKey, offset: start, data: result)
    return result
  }
  private func activeRead() async throws -> MediaReadDescriptor {
    while true {
      try Task.checkCancellation(); guard !closed else { throw CancellationError() }
      if let renewalFailure { throw renewalFailure }
      if let renewal { _ = try await renewal.value; continue }
      if ContinuousClock.now < descriptor.renewAfter {
        networkUses += 1; return descriptor
      }
      let old = descriptor, identifier = UUID().uuidString.lowercased()
      readIds.insert(identifier)
      let task = Task { try await self.renew(old, identifier: identifier) }
      renewal = task
      _ = try await task.value
    }
  }
  private func renew(_ old: MediaReadDescriptor, identifier: String) async throws -> MediaReadDescriptor {
    defer { renewal = nil }
    do {
      // Drain old reads before releasing their snapshot. This also frees the
      // Host budget before preparing another potentially two-GiB version.
      while networkUses > 0 { try Task.checkCancellation(); try await Task.sleep(for: .milliseconds(25)) }
      try Task.checkCancellation(); guard !closed else { throw CancellationError() }
      let oldCancelled = await service.cancelMediaRead(client, file: source, readId: old.readId)
      guard oldCancelled else { throw URLError(.networkConnectionLost) }
      readIds.remove(old.readId)
      try Task.checkCancellation(); guard !closed else { throw CancellationError() }
      guard let next = try await service.prepareMediaRead(client, file: source, readId: identifier, progress: { _, _ in }) else { throw HostServiceError.unavailable }
      try Task.checkCancellation(); guard !closed else { throw CancellationError() }
      guard next.etag == old.etag, next.file.sha256 == old.file.sha256,
        next.file.sizeBytes == old.file.sizeBytes, next.file.name == old.file.name,
        next.file.mediaType == old.file.mediaType else { throw HostServiceError.rejected(409, L10n.tr("文件完整性校验失败")) }
      descriptor = next
      return next
    } catch {
      renewalFailure = error
      await service.cancelMediaRead(client, file: source, readId: identifier)
      // A cancelled renewal task cannot confirm its own DELETE. Keep the ID
      // for close(), whose independent cleanup context retries it.
      throw error
    }
  }
  func close() async {
    closed = true; renewal?.cancel()
    if let renewal { _ = try? await renewal.value }
    for identifier in Array(readIds) { await service.cancelMediaRead(client, file: source, readId: identifier) }
    readIds.removeAll(); blocks.removeAll(); order.removeAll()
    if lease { lease = false; await MediaDiskCache.shared.release(cacheKey) }
  }
}

/// Resource-loader requests may ask for the entire tail. Keep such a request
/// open and feed a bounded forward window instead of eagerly downloading it.
private actor MediaReadWindow {
  private var bufferedAhead = 0.0
  private var awaitingProgress = false
  private var progressAllowance = 4 * 1024 * 1024
  private var allowanceHead = 0.0
  private var updateSequence = 0
  private var available = 1_048_576
  private var refill = ContinuousClock.now.advanced(by: .milliseconds(100))
  private var closed = false
  func update(bufferedAhead: Double, head: Double, awaitingProgress: Bool, sequence: Int) {
    guard !closed, sequence > updateSequence else { return }
    updateSequence = sequence
    self.bufferedAhead = bufferedAhead; self.awaitingProgress = awaitingProgress
    if abs(head - allowanceHead) > 0.25 {
      allowanceHead = head; progressAllowance = 4 * 1024 * 1024
    }
  }
  func close() { closed = true }
  func wait(count: Int) async throws {
    while true {
      try Task.checkCancellation()
      guard !closed else { throw CancellationError() }
      if ContinuousClock.now >= refill { available = 1_048_576; refill = ContinuousClock.now.advanced(by: .milliseconds(100)) }
      // Use decoded time ranges, never average bitrate: VBR and large indexes
      // must not wait for a clock that needs these very bytes to advance.
      // loadedTimeRanges can exceed the normal window before the decoder can
      // advance after a seek. Allow a bounded bootstrap instead of deadlocking
      // on the clock that needs these bytes. Paused, settled playback stays bounded.
      let needsBootstrap = bufferedAhead >= 5
      if available >= count, !needsBootstrap || (awaitingProgress && progressAllowance >= count) {
        available -= count
        if needsBootstrap { progressAllowance -= count }
        return
      }
      try await Task.sleep(for: .milliseconds(25))
    }
  }
}

private final class MediaResourceLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
  let queue = DispatchQueue(label: "agent-hub.media-resource")
  private let reader: MediaByteReader
  private let file: RemoteAttachment
  private let readId: String
  private let window = MediaReadWindow()
  private var tasks: [ObjectIdentifier: (task: Task<Void, Never>, request: AVAssetResourceLoadingRequest)] = [:]
  private var stopped = false
  private let failed: @Sendable (Error) -> Void
  init(reader: MediaByteReader, file: RemoteAttachment, readId: String, failed: @escaping @Sendable (Error) -> Void) { self.reader = reader; self.file = file; self.readId = readId; self.failed = failed }
  func resourceLoader(_ loader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
    guard let url = request.request.url, url.scheme == "agent-hub-media", url.host?.lowercased() == readId.lowercased(), url.path == "/asset.mp4" else {
      request.finishLoading(with: URLError(.unsupportedURL)); return true
    }
    guard !stopped, tasks.count < 4 else { request.finishLoading(with: URLError(.cancelled)); return true }
    if let information = request.contentInformationRequest {
      information.contentLength = Int64(file.sizeBytes)
      information.contentType = UTType(mimeType: file.mediaType)?.identifier ?? UTType.mpeg4Movie.identifier
      information.isByteRangeAccessSupported = true
    }
    guard let data = request.dataRequest else { request.finishLoading(); return true }
    let key = ObjectIdentifier(request)
    let size = Int64(file.sizeBytes)
    let begin = max(data.currentOffset, data.requestedOffset)
    guard data.requestedOffset >= 0, data.requestedOffset <= size, data.requestedLength >= 0, begin >= 0, begin <= size else { request.finishLoading(with: HostServiceError.invalidResponse); return true }
    let end = data.requestsAllDataToEndOfResource ? size : data.requestedOffset + min(Int64(data.requestedLength), size - data.requestedOffset)
    guard end >= begin else { request.finishLoading(with: HostServiceError.invalidResponse); return true }
    let work = Task { [weak self] in
      guard let self else { return }
      do {
        var offset = begin
        while offset < end {
          try Task.checkCancellation()
          try await window.wait(count: Int(min(262_144, end - offset)))
          let block = try await reader.bytes(at: offset)
          let blockStart = offset / 262_144 * 262_144
          let lower = Int(offset - blockStart), count = min(block.count - lower, Int(end - offset))
          guard count > 0 else { throw HostServiceError.invalidResponse }
          let payload = block.subdata(in: lower..<(lower + count))
          try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.queue.async {
              guard !self.stopped, self.tasks[key] != nil, !request.isCancelled else { continuation.resume(throwing: CancellationError()); return }
              data.respond(with: payload); continuation.resume()
            }
          }
          offset += Int64(count)
        }
        queue.async { if self.tasks.removeValue(forKey: key) != nil, !request.isCancelled { request.finishLoading() } }
      } catch {
        queue.async { if self.tasks.removeValue(forKey: key) != nil, !request.isCancelled { request.finishLoading(with: error); self.failed(error) } }
      }
    }
    tasks[key] = (work, request)
    return true
  }
  func resourceLoader(_ loader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) {
    tasks.removeValue(forKey: ObjectIdentifier(request))?.task.cancel()
  }
  func stop() {
    queue.async {
      self.stopped = true
      for value in self.tasks.values { value.task.cancel(); if !value.request.isCancelled { value.request.finishLoading(with: URLError(.cancelled)) } }
      self.tasks.removeAll()
      Task { await self.window.close(); await self.reader.close() }
    }
  }
  func update(bufferedAhead: Double, head: Double, awaitingProgress: Bool, sequence: Int) {
    Task { await window.update(bufferedAhead: bufferedAhead, head: head, awaitingProgress: awaitingProgress, sequence: sequence) }
  }
}

@MainActor final class MediaPlaybackSession: ObservableObject, Identifiable {
  let id = UUID()
  let name: String
  @Published private(set) var player: AVPlayer?
  @Published private(set) var phase = "preparing"
  @Published private(set) var failure: String?
  @Published private(set) var position: Double = 0
  @Published private(set) var duration: Double = 0
  @Published private(set) var mode = ""
  @Published private(set) var firstFrame = false
  @Published private(set) var wantsPlayback = true
  @Published private(set) var receivedBytes = 0
  @Published private(set) var preparingProgress: Double?
  @Published private(set) var downloading = false
  @Published private(set) var downloadFailure: String?
  @Published private(set) var export: VerifiedResourceExport?
  private let client: Client?
  private let file: RemoteAttachment?
  private let external: URL?
  private var operation: Task<Void, Never>?
  private var downloadOperation: Task<Void, Never>?
  private var downloadVersion: RemoteAttachment?
  private var loader: MediaResourceLoader?
  private var statusObservation: NSKeyValueObservation?
  private var playerObservation: NSKeyValueObservation?
  private var bufferObservation: NSKeyValueObservation?
  private var endObservation: NSObjectProtocol?
  private var clockObserver: Any?
  private var readId: String?
  private var startupWatch: Task<Void, Never>?
  private var progressWatch: Task<Void, Never>?
  private var seekOperation: Task<Void, Never>?
  private var pendingSeek: (id: UUID, seconds: Double, started: Double)?
  private var ended = false
  private var lastProgress = ProcessInfo.processInfo.systemUptime
  private var previousPosition = 0.0
  private var bufferUpdateSequence = 0
  private var stopped = false
  private let started = ProcessInfo.processInfo.systemUptime
  init(client: Client, file: RemoteAttachment) { self.client = client; self.file = file; external = nil; name = file.name }
  init(url: URL) { client = nil; file = nil; external = url; name = url.lastPathComponent }
  var canDownload: Bool { client != nil && file != nil }
  var externalLink: URL? { external?.isFileURL == false ? external : nil }
  func start() {
    guard operation == nil, !stopped else { return }
    operation = Task { [weak self] in
      guard let self else { return }
      do {
        let item: AVPlayerItem
        if let client, let file {
          let service = try AgentClients.resolve(client)
          let identifier = UUID().uuidString.lowercased(); readId = identifier
          if let descriptor = try await service.prepareMediaRead(client, file: file, readId: identifier, progress: { [weak self] bytes, total in
            Task { @MainActor in if self?.stopped == false { self?.preparingProgress = total > 0 ? Double(bytes) / Double(total) : nil } }
          }) {
            try Task.checkCancellation()
            downloadVersion = descriptor.file
            mode = "streaming"
            let reader = MediaByteReader(client: client, source: file, descriptor: descriptor, service: service) { [weak self] count in
              Task { @MainActor in guard let self, !self.stopped else { return }; self.receivedBytes += count; self.record("bytes") }
            }
            let resource = MediaResourceLoader(reader: reader, file: descriptor.file, readId: identifier) { [weak self] error in
              Task { @MainActor in guard let self, !self.stopped, self.failure == nil else { return }; self.fail(error) }
            }; loader = resource
            let asset = AVURLAsset(url: URL(string: "agent-hub-media://\(identifier)/asset.mp4")!,
              options: [AVURLAssetReferenceRestrictionsKey: NSNumber(value: AVAssetReferenceRestrictions.forbidAll.rawValue)])
            asset.resourceLoader.setDelegate(resource, queue: resource.queue)
            item = AVPlayerItem(asset: asset)
          } else {
            readId = nil; mode = "downloaded"
            let local = try await service.download(client, file: file)
            try Task.checkCancellation(); export = VerifiedResourceExport(file: file, url: local); item = AVPlayerItem(url: local)
          }
        } else if let external {
          mode = external.isFileURL ? "downloaded" : "external"
          item = AVPlayerItem(url: external)
        } else { throw HostServiceError.invalidResponse }
        try Task.checkCancellation()
        guard !stopped else { return }
        item.preferredForwardBufferDuration = 3
        let next = AVPlayer(playerItem: item)
        next.automaticallyWaitsToMinimizeStalling = mode != "streaming"
        player = next; phase = "buffering"
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] observed, _ in
          Task { @MainActor in
            guard let self, !self.stopped, self.failure == nil else { return }
            if observed.status == .failed { self.fail(observed.error ?? HostServiceError.invalidResponse) }
            if observed.status == .readyToPlay {
              let total = observed.duration.seconds
              if total.isFinite, total > 0 { self.duration = total }
              self.updateBuffer()
            }
          }
        }
        playerObservation = next.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] observed, _ in
          Task { @MainActor in guard let self, !self.stopped, self.failure == nil else { return }
            if !self.wantsPlayback && self.pendingSeek == nil { self.phase = "paused" }
            else if observed.timeControlStatus != .playing || self.phase != "playing" || self.pendingSeek != nil { self.phase = "buffering" }
            self.updateBuffer()
          }
        }
        bufferObservation = item.observe(\.loadedTimeRanges, options: [.initial, .new]) { [weak self] _, _ in
          Task { @MainActor in self?.updateBuffer() }
        }
        endObservation = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
          Task { @MainActor in
            guard let self, !self.stopped, self.failure == nil, self.pendingSeek == nil else { return }
            self.ended = true; self.wantsPlayback = false; self.phase = "paused"; self.record("ended")
          }
        }
        clockObserver = next.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] _ in
          Task { @MainActor in
            guard let self, !self.stopped else { return }
            let current = self.player?.currentTime().seconds ?? 0
            if self.pendingSeek == nil { self.position = current.isFinite ? max(0, current) : 0 }
            let total = self.player?.currentItem?.duration.seconds ?? 0
            self.duration = total.isFinite ? max(0, total) : 0
            self.updateBuffer()
            self.record("time")
          }
        }
        lastProgress = ProcessInfo.processInfo.systemUptime
        next.play(); armStartupWatch(); monitorProgress(); record("started")
      } catch {
        if !Task.isCancelled, !stopped { fail(error) }
      }
    }
  }
  func displayedFrame() {
    guard !stopped, failure == nil, !firstFrame else { return }
    firstFrame = true; startupWatch?.cancel(); startupWatch = nil; record("first-frame")
  }
  private func armStartupWatch() {
    guard !firstFrame else { return }; startupWatch?.cancel()
    startupWatch = Task { [weak self] in
      do { try await Task.sleep(for: .seconds(20)) } catch { return }
      guard let self, !self.stopped, !self.firstFrame, self.failure == nil, self.phase != "paused" else { return }
      self.fail(URLError(.timedOut))
    }
  }
  private func monitorProgress() {
    progressWatch?.cancel()
    progressWatch = Task { [weak self] in
      while !Task.isCancelled {
        do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
        guard let self, !self.stopped, self.failure == nil else { return }
        let now = ProcessInfo.processInfo.systemUptime
        let position = self.player?.currentTime().seconds ?? 0
        if self.pendingSeek == nil, position.isFinite { self.position = max(0, position) }
        self.updateBuffer()
        if let seek = self.pendingSeek, now - seek.started > 30 { self.fail(URLError(.timedOut)); return }
        if !self.wantsPlayback || self.pendingSeek != nil {
          self.previousPosition = position.isFinite ? position : 0
          if self.pendingSeek == nil { self.lastProgress = now; self.phase = "paused" }
        } else if position.isFinite, position - self.previousPosition > 0.05 {
          self.lastProgress = now; self.previousPosition = position
          self.phase = "playing"
        } else {
          // A custom resource loader can report a nonzero rate before its
          // clock advances. Show buffering until playback actually progresses.
          if now - self.lastProgress > 0.75 || self.phase != "playing" { self.phase = "buffering" }
          if self.firstFrame, now - self.lastProgress > 30 { self.fail(URLError(.timedOut)); return }
        }
        self.record("progress-probe", probe: true)
      }
    }
  }
  private func updateBuffer() {
    guard !stopped, failure == nil, let player, let item = player.currentItem else { return }
    let head = pendingSeek?.seconds ?? position
    var ahead = 0.0
    var coversEnd = false
    for value in item.loadedTimeRanges {
      let range = value.timeRangeValue
      let start = range.start.seconds, end = CMTimeRangeGetEnd(range).seconds
      if start.isFinite, end.isFinite, start <= head + 0.05, end > head {
        ahead = max(ahead, end - head)
        coversEnd = coversEnd || (duration > head && end + 0.001 >= duration)
      }
    }
    bufferUpdateSequence += 1
    let awaitingProgress = pendingSeek != nil || (wantsPlayback && ProcessInfo.processInfo.systemUptime - lastProgress > 0.75)
    loader?.update(bufferedAhead: ahead, head: head, awaitingProgress: awaitingProgress, sequence: bufferUpdateSequence)
    if mode == "streaming", wantsPlayback, pendingSeek == nil, item.status == .readyToPlay,
      ahead > 0, (ahead > 0.15 || coversEnd), player.rate == 0 { player.play() }
  }
  func toggle() {
    guard failure == nil, let player else { return }; wantsPlayback.toggle(); lastProgress = ProcessInfo.processInfo.systemUptime
    if wantsPlayback {
      let current = player.currentTime().seconds
      previousPosition = current.isFinite ? current : 0
      phase = "buffering"
      if ended { ended = false; seek(0) } else { player.play() }
      armStartupWatch()
    } else { player.pause(); phase = pendingSeek == nil ? "paused" : "buffering" }
  }
  func download() {
    guard !stopped, !downloading, export == nil, let client, let original = file else { return }
    let version = downloadVersion ?? original
    downloading = true; downloadFailure = nil
    downloadOperation = Task { [weak self] in
      do {
        let service = try AgentClients.resolve(client)
        let local = try await service.download(client, file: version)
        try Task.checkCancellation()
        guard let self, !self.stopped else { return }
        // Only the original complete-file API can supply an export. ShareLink rechecks
        // this exact version through VerifiedResourceExport before handing it out.
        self.export = VerifiedResourceExport(file: version, url: local)
        self.downloading = false; self.downloadOperation = nil
      } catch {
        guard let self, !Task.isCancelled, !self.stopped else { return }
        self.downloadFailure = error.localizedDescription
        self.downloading = false; self.downloadOperation = nil
      }
    }
  }
  func seek(_ seconds: Double) {
    guard !stopped, failure == nil, seconds.isFinite, let player else { return }
    let target = max(0, min(duration, seconds)), identifier = UUID()
    ended = false; pendingSeek = (identifier, target, ProcessInfo.processInfo.systemUptime)
    seekOperation?.cancel()
    lastProgress = ProcessInfo.processInfo.systemUptime; phase = "buffering"; updateBuffer()
    // Accessibility and slider updates can arrive faster than AVPlayer completes a
    // seek. Commit the final target so intermediate seeks cannot stall its clock.
    seekOperation = Task { [weak self] in
      do { try await Task.sleep(for: .milliseconds(150)) } catch { return }
      guard let self, !self.stopped, self.failure == nil, self.pendingSeek?.id == identifier else { return }
      self.seekOperation = nil
      self.record("seek-committed")
      player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
        Task { @MainActor in
          guard let self, !self.stopped, self.failure == nil, self.pendingSeek?.id == identifier else { return }
          guard finished else { self.fail(HostServiceError.invalidResponse); return }
          self.pendingSeek = nil
          let time = player.currentTime().seconds
          self.position = time.isFinite ? max(0, time) : 0
          self.previousPosition = self.position; self.lastProgress = ProcessInfo.processInfo.systemUptime
          self.phase = self.wantsPlayback ? "buffering" : "paused"
          self.updateBuffer()
          self.record("seek-completed")
          if !self.wantsPlayback { self.phase = "paused" }
          if self.wantsPlayback { player.play() }
        }
      }
    }
    record("seek")
  }
  func replacement() -> MediaPlaybackSession {
    if let client, let file { return MediaPlaybackSession(client: client, file: file) }
    return MediaPlaybackSession(url: external!)
  }
  private func fail(_ error: Error) {
    startupWatch?.cancel(); startupWatch = nil
    progressWatch?.cancel(); progressWatch = nil
    seekOperation?.cancel(); seekOperation = nil
    pendingSeek = nil; player?.currentItem?.cancelPendingSeeks()
    failure = error.localizedDescription; phase = "failed"; player?.pause(); loader?.stop(); cancelRead(); record("failed")
  }
  private func cancelRead() {
    guard let client, let file, let identifier = readId else { return }; readId = nil
    Task { if let service = try? AgentClients.resolve(client) { await service.cancelMediaRead(client, file: file, readId: identifier) } }
  }
  func stop() {
    guard !stopped else { return }; stopped = true
    operation?.cancel(); operation = nil; loader?.stop(); loader = nil
    downloadOperation?.cancel(); downloadOperation = nil; downloading = false
    startupWatch?.cancel(); startupWatch = nil
    progressWatch?.cancel(); progressWatch = nil
    seekOperation?.cancel(); seekOperation = nil
    if let player, let clockObserver { player.removeTimeObserver(clockObserver) }
    clockObserver = nil; statusObservation = nil; playerObservation = nil; bufferObservation = nil; pendingSeek = nil
    if let endObservation { NotificationCenter.default.removeObserver(endObservation) }; endObservation = nil
    player?.pause(); player?.replaceCurrentItem(with: nil); player = nil; phase = "closed"
    cancelRead()
    record("closed")
  }
  private func record(_ event: String, probe: Bool = false) {
    #if DEBUG
    guard ProcessInfo.processInfo.arguments.contains("-media-streaming-acceptance"),
      !ProcessInfo.processInfo.arguments.contains("-media-streaming-no-trace") else { return }
    let diagnostics: [String: String]? = probe ? [
      "actualPosition": String(player?.currentTime().seconds ?? 0),
      "rate": String(player?.rate ?? 0),
      "status": player.map { String($0.timeControlStatus.rawValue) } ?? "unavailable",
      "waiting": player?.reasonForWaitingToPlay?.rawValue ?? "",
      "pendingSeek": String(pendingSeek != nil),
      "wantsPlayback": String(wantsPlayback),
      "ranges": String(describing: player?.currentItem?.loadedTimeRanges)
    ] : nil
    MediaPlaybackEvidence.record(id: id.uuidString, event: event, phase: phase, mode: mode,
      position: position, duration: duration, bytes: receivedBytes, elapsed: ProcessInfo.processInfo.systemUptime - started,
      diagnostics: diagnostics)
    #endif
  }
}

#if DEBUG
@MainActor private enum MediaPlaybackEvidence {
  private static var rows: [[String: Any]] = []
  static func record(id: String, event: String, phase: String, mode: String, position: Double, duration: Double, bytes: Int, elapsed: Double, diagnostics: [String: String]?) {
    if event == "time", rows.last?["event"] as? String == "time", Int(elapsed * 2) % 2 == 0 { return }
    rows.append(["id": id, "event": event, "phase": phase, "mode": mode, "position": position, "duration": duration, "bytes": bytes, "elapsed": elapsed])
    if let diagnostics { rows[rows.count - 1]["diagnostics"] = diagnostics }
    if rows.count > 512 { rows.removeFirst(rows.count - 512) }
    guard let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first,
      let data = try? JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys]) else { return }
    try? data.write(to: root.appendingPathComponent("media-playback-evidence.json"), options: [.atomic, .completeFileProtectionUnlessOpen])
  }
}
#endif

private final class MediaPlaybackLifetime {
  var cleanup: (@MainActor @Sendable () -> Void)?
  deinit {
    let action = cleanup
    Task { @MainActor in action?() }
  }
}

@MainActor final class MediaPlaybackCoordinator: ObservableObject {
  @Published private(set) var session: MediaPlaybackSession?
  @Published private(set) var anchor: UUID?
  private var context = ""
  var readingCover: (@MainActor (UUID, String, Bool) -> Void)?
  weak var window: UIWindow?
  private var fullScreen: UIViewController?
  private var visibleAnchors = Set<UUID>()
  private let lifetime = MediaPlaybackLifetime()
  private func updateLifetime() {
    let active = session, controller = fullScreen
    lifetime.cleanup = { active?.stop(); controller?.dismiss(animated: false) }
  }
  func appeared(_ anchor: UUID) { visibleAnchors.insert(anchor) }
  func disappeared(_ anchor: UUID) {
    visibleAnchors.remove(anchor)
    if self.anchor == anchor && fullScreen == nil { stop() }
  }
  func activate(_ value: String) { if context != value { stop(); context = value } }
  func open(client: Client, file: RemoteAttachment, anchor: UUID? = nil) {
    let destination = anchor.flatMap { visibleAnchors.contains($0) ? $0 : nil }
    stop(); self.anchor = destination
    let next = MediaPlaybackSession(client: client, file: file); session = next; readingCover?(next.id, context, true); updateLifetime(); next.start()
    if destination == nil { enterFullScreen() }
  }
  func open(url: URL) {
    stop(); let next = MediaPlaybackSession(url: url); session = next; readingCover?(next.id, context, true); updateLifetime(); next.start(); enterFullScreen()
  }
  func retry() {
    // A retry starts a new read-only playback session; no Agent input is resubmitted.
    guard let old = session else { return }
    let next = old.replacement(); readingCover?(old.id, context, false); old.stop(); session = next; readingCover?(next.id, context, true)
    if let controller = fullScreen as? UIHostingController<MediaPlaybackPanel> { controller.rootView = panel(next) }
    updateLifetime(); next.start()
  }
  func stop() {
    if let session { readingCover?(session.id, context, false) }
    session?.stop(); session = nil; anchor = nil
    if let controller = fullScreen { fullScreen = nil; controller.dismiss(animated: false) }
    lifetime.cleanup = nil
  }
  func enterFullScreen() {
    guard fullScreen == nil, let session, var presenter = window?.rootViewController else { return }
    while let next = presenter.presentedViewController, !next.isBeingDismissed { presenter = next }
    let controller = UIHostingController(rootView: panel(session))
    controller.modalPresentationStyle = .fullScreen; fullScreen = controller
    updateLifetime()
    presenter.present(controller, animated: true)
  }
  private func panel(_ session: MediaPlaybackSession) -> MediaPlaybackPanel {
    MediaPlaybackPanel(session: session, fullScreen: true, expand: {}, collapse: { [weak self] in self?.leaveFullScreen() },
      close: { [weak self] in self?.stop() }, retry: { [weak self] in self?.retry() })
  }
  private func leaveFullScreen() {
    guard let controller = fullScreen else { return }; fullScreen = nil
    updateLifetime()
    controller.dismiss(animated: true) { [weak self] in
      DispatchQueue.main.async {
        guard let self else { return }
        if self.anchor.map({ !self.visibleAnchors.contains($0) }) ?? true { self.stop() }
      }
    }
  }
}

private struct MediaPlaybackKey: EnvironmentKey { static let defaultValue: MediaPlaybackCoordinator? = nil }
extension EnvironmentValues {
  var mediaPlayback: MediaPlaybackCoordinator? { get { self[MediaPlaybackKey.self] } set { self[MediaPlaybackKey.self] = newValue } }
}

struct MediaPlaybackWindowProbe: UIViewRepresentable {
  let coordinator: MediaPlaybackCoordinator
  func makeUIView(context: Context) -> UIView { Probe(coordinator) }
  func updateUIView(_ view: UIView, context: Context) { if let window = view.window { coordinator.window = window } }
  private final class Probe: UIView {
    weak var coordinator: MediaPlaybackCoordinator?
    init(_ coordinator: MediaPlaybackCoordinator) { self.coordinator = coordinator; super.init(frame: .zero); isUserInteractionEnabled = false }
    required init?(coder: NSCoder) { fatalError() }
    override func didMoveToWindow() { super.didMoveToWindow(); if let window { coordinator?.window = window } }
  }
}

struct MediaInlineCard<Fallback: View>: View {
  @ObservedObject var coordinator: MediaPlaybackCoordinator
  let anchor: UUID
  let fallback: Fallback
  var body: some View {
    Group {
      if coordinator.anchor == anchor, let session = coordinator.session {
        MediaPlaybackPanel(session: session, expand: { coordinator.enterFullScreen() },
          close: { coordinator.stop() }, retry: { coordinator.retry() })
      } else { fallback }
    }.onAppear { coordinator.appeared(anchor) }.onDisappear { coordinator.disappeared(anchor) }
  }
}

private struct MediaPlaybackSurface: UIViewRepresentable {
  @ObservedObject var session: MediaPlaybackSession
  private final class Surface: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var observation: NSKeyValueObservation?
  }
  func makeUIView(context: Context) -> UIView {
    let view = Surface(); view.backgroundColor = .black
    view.isAccessibilityElement = true; view.accessibilityIdentifier = "media-player"; view.accessibilityLabel = session.name
    let layer = view.layer as! AVPlayerLayer; layer.videoGravity = .resizeAspect
    view.observation = layer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak session] layer, _ in
      if layer.isReadyForDisplay { Task { @MainActor in session?.displayedFrame() } }
    }
    return view
  }
  func updateUIView(_ view: UIView, context: Context) { (view.layer as? AVPlayerLayer)?.player = session.player }
  static func dismantleUIView(_ view: UIView, coordinator: ()) { (view.layer as? AVPlayerLayer)?.player = nil; (view as? Surface)?.observation = nil }
}

struct MediaPlaybackPanel: View {
  @ObservedObject var session: MediaPlaybackSession
  var fullScreen = false
  let expand: () -> Void
  var collapse: () -> Void = {}
  let close: () -> Void
  var retry: () -> Void = {}
  @State private var seeking = false
  @State private var fraction = 0.0
  private var surface: some View {
    ZStack {
      MediaPlaybackSurface(session: session).id(session.id)
      if session.player == nil && session.failure == nil { ProgressView(value: session.preparingProgress).tint(.white) }
    }
  }
  var body: some View {
    VStack(spacing: 8) {
      HStack {
        Text(session.name).lineLimit(1); Spacer()
        Button(action: close) { Image(systemName: "xmark").frame(width: 44, height: 44) }
          .accessibilityLabel(L10n.tr("关闭")).accessibilityIdentifier("media-player-close")
      }
      if fullScreen { surface.frame(maxWidth: .infinity, maxHeight: .infinity) }
      else { surface.aspectRatio(16 / 9, contentMode: .fit).frame(maxHeight: 260) }
      if session.failure == nil, session.phase == "preparing" || session.phase == "buffering" {
        Text(L10n.tr(session.phase == "preparing" ? "正在准备视频…" : "正在缓冲…")).font(.caption)
      }
      if let failure = session.failure {
        Text(failure).font(.caption).foregroundStyle(.red).accessibilityIdentifier("media-player-error")
        Button(L10n.tr("重试"), action: retry).accessibilityIdentifier("media-player-retry")
      }
      HStack {
        if let export = session.export {
          ShareLink(item: export, preview: SharePreview(session.name)) { Label(L10n.tr("保存或分享文件"), systemImage: "square.and.arrow.up") }
            .accessibilityIdentifier("media-player-share")
        } else if session.canDownload {
          Button { session.download() } label: {
            if session.downloading { ProgressView().tint(.white) }
            else { Label(L10n.tr("下载"), systemImage: "arrow.down.circle") }
          }.disabled(session.downloading).accessibilityIdentifier("media-player-download")
        }
        if let url = session.externalLink {
          ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
            .accessibilityLabel(L10n.tr("保存或分享文件"))
          Link(destination: url) { Image(systemName: "safari") }
            .accessibilityLabel(L10n.tr("打开")).accessibilityIdentifier("media-player-browser")
        }
        Spacer()
      }.font(.caption)
      if let failure = session.downloadFailure {
        Text(failure).font(.caption).foregroundStyle(.red).accessibilityIdentifier("media-player-download-error")
      }
      HStack {
        Button { session.toggle() } label: { Image(systemName: session.wantsPlayback ? "pause.fill" : "play.fill").frame(width: 44, height: 44) }
          .disabled(session.player == nil || session.failure != nil).accessibilityLabel(L10n.tr(session.wantsPlayback ? "暂停" : "播放")).accessibilityIdentifier("media-player-play-pause")
        Slider(value: $fraction, in: 0...1, onEditingChanged: { editing in
          seeking = editing; if !editing { session.seek(fraction * session.duration) }
        }).disabled(session.duration <= 0 || session.failure != nil).accessibilityIdentifier("media-player-seek")
        Text(clock(session.position) + " / " + clock(session.duration)).font(.caption2).monospacedDigit().accessibilityIdentifier("media-player-time")
        Button(action: fullScreen ? collapse : expand) { Image(systemName: fullScreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right").frame(width: 44, height: 44) }
          .accessibilityLabel(L10n.tr(fullScreen ? "退出全屏" : "全屏")).accessibilityIdentifier(fullScreen ? "media-player-exit-fullscreen" : "media-player-fullscreen")
      }
      #if DEBUG
      if ProcessInfo.processInfo.arguments.contains("-media-streaming-acceptance") {
        if session.firstFrame { Text("Frame").font(.caption2).accessibilityIdentifier("media-player-first-frame") }
        Text(session.phase).font(.caption2).accessibilityIdentifier("media-player-state")
        Text(session.mode).font(.caption2).accessibilityIdentifier("media-player-mode")
      }
      #endif
    }.padding(12).foregroundStyle(.white).background(.black, in: RoundedRectangle(cornerRadius: 12))
      .tint(.white)
      .frame(maxWidth: .infinity, maxHeight: fullScreen ? .infinity : nil).background(fullScreen ? Color.black : .clear)
      .onChange(of: session.position) { _, value in if !seeking { fraction = session.duration > 0 ? min(1, value / session.duration) : 0 } }
      .onChange(of: session.id) { _, _ in fraction = 0; seeking = false }
  }
  private func clock(_ seconds: Double) -> String { let n = Int(max(0, seconds)); return "\(n / 60):" + String(format: "%02d", n % 60) }
}
