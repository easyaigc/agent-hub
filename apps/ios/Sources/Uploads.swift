import CryptoKit
import Foundation
import CoreFoundation
import SwiftUI
import UniformTypeIdentifiers
import Network

struct UploadManifest: Codable, Equatable, Sendable {
  var version = 1
  let hostId: String
  let id: String
  let name: String
  let mediaType: String
  let sizeBytes: Int
  let sha256: String
  let purpose: String
  let threadId: String?
  let creationId: String?
  let attachmentId: String?
  // Null purpose fields are part of the strict versioned contract.
  enum CodingKeys: String, CodingKey { case version, hostId, id, name, mediaType, sizeBytes, sha256, purpose, threadId, creationId, attachmentId }
  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: CodingKeys.self)
    try c.encode(version, forKey: .version); try c.encode(hostId, forKey: .hostId)
    try c.encode(id, forKey: .id); try c.encode(name, forKey: .name); try c.encode(mediaType, forKey: .mediaType)
    try c.encode(sizeBytes, forKey: .sizeBytes); try c.encode(sha256, forKey: .sha256); try c.encode(purpose, forKey: .purpose)
    try c.encode(threadId, forKey: .threadId); try c.encode(creationId, forKey: .creationId); try c.encode(attachmentId, forKey: .attachmentId)
  }
}
struct UploadResult: Codable, Sendable {
  var hostId: String?; var id: String?; var threadId: String?; var name: String?; var mediaType: String?
  var sizeBytes: Int?; var sha256: String?; var phase: String?; var destinationPath: String?; var createdAt: String?; var clientOperationId: String?
}
struct UploadReceipt: Codable, Sendable {
  let version: Int
  let manifest: UploadManifest
  let phase: String
  let committedBytes: Int
  let maxChunkBytes: Int
  let createdAt: String
  let updatedAt: String
  let result: UploadResult?
  var reason: String? = nil
  func validate(_ expected: UploadManifest) throws {
    guard version == 1, manifest == expected, (0...manifest.sizeBytes).contains(committedBytes),
      (1...1024 * 1024).contains(maxChunkBytes), ["uploading", "verifying", "completed", "cancelled"].contains(phase),
      phase != "completed" || (committedBytes == manifest.sizeBytes && result != nil)
    else { throw HostServiceError.invalidResponse }
  }
  func value<T: Decodable>(_ type: T.Type) throws -> T {
    guard phase == "completed", let result else { throw HostServiceError.invalidResponse }
    return try JSONDecoder().decode(type, from: JSONEncoder().encode(result))
  }
}
enum UploadAction: Sendable { case prepare, status, chunk(Int), complete, cancel }
enum UploadRequestContext {
  @TaskLocal static var capturedFile: DesktopCreationFile? = nil
}
struct UploadBatch: Codable, Sendable {
  let id: String
  let index: Int
  let count: Int
  let totalBytes: Int
  @TaskLocal static var current: UploadBatch? = nil
}

extension AgentClient {
  func uploadFile(_ client: Client, threadId: String, file: LocalAttachment, source: URL) async throws -> RemoteAttachment {
    try await upload(client, threadId: threadId, file: file, data: Data(contentsOf: source))
  }
  /// The first input keeps its captured bytes while using the existing resumable pipeline.
  func uploadFile(_ client: Client, threadId: String, file: LocalAttachment, source: URL, manifest expected: DesktopCreationFile) async throws -> RemoteAttachment {
    let computed = try await uploadManifest(client, file: file, source: source, purpose: "attachment", parent: threadId)
    guard UUID(uuidString: expected.id) != nil,
      expected.id == file.id, expected.name == file.name,
      expected.mediaType == computed.mediaType, expected.sizeBytes == computed.sizeBytes,
      expected.sha256 == computed.sha256 else { throw HostServiceError.invalidResponse }
    // Older captured first inputs already identify their remote upload by this
    // file ID. Keep it across restart/upgrade instead of allocating another ID.
    let manifest = UploadManifest(hostId: computed.hostId, id: expected.id,
      name: computed.name, mediaType: computed.mediaType, sizeBytes: computed.sizeBytes,
      sha256: computed.sha256, purpose: computed.purpose, threadId: computed.threadId,
      creationId: computed.creationId, attachmentId: computed.attachmentId)
    let receipt = try await UploadCenter.shared.transfer(client, manifest: manifest, source: source, capturedFile: expected)
    let result = try receipt.value(RemoteAttachment.self)
    guard result.hostId == client.id, result.threadId == threadId,
      result.sha256 == manifest.sha256, result.sizeBytes == manifest.sizeBytes,
      result.name == manifest.name, result.mediaType == manifest.mediaType else { throw HostServiceError.wrongHost }
    return result
  }
  func uploadCreationFile(_ client: Client, id: String, file: LocalAttachment, source: URL) async throws {
    try await uploadCreation(client, id: id, file: file, data: Data(contentsOf: source))
  }
  func uploadTransferFile(_ client: Client, file: ComputerFileManifest, source: URL) async throws -> ComputerFileReceipt {
    try await uploadFileTransfer(client, file: file, data: Data(contentsOf: source))
  }
  func uploadRequest(_ client: Client, manifest: UploadManifest, action: UploadAction, bytes: Data?, sent: (@Sendable (Int64) -> Void)?) async throws -> UploadReceipt { throw HostServiceError.unavailable }
}

/// Keeps session-level certificate pinning and redirect rejection for upload progress.
final class UploadWireOperation: @unchecked Sendable {
  private let lock = NSLock()
  private var task: URLSessionDataTask?
  private var observation: NSKeyValueObservation?
  private var cancelled = false
  private var lastReport: TimeInterval = 0
  func run(session: URLSession, request: URLRequest, file: URL? = nil, sent: (@Sendable (Int64) -> Void)?) async throws -> (Data, URLResponse) {
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { continuation in
        let completion: @Sendable (Data?, URLResponse?, Error?) -> Void = { [self] data, response, error in
          let previous = lock.withLock { () -> (URLSessionDataTask?, NSKeyValueObservation?) in
            let previous = (self.task, self.observation)
            self.task = nil; self.observation = nil
            return previous
          }
          // KVO invalidation and URLSession cancellation can reenter callbacks.
          // Retain both objects while clearing state, then release outside the lock.
          previous.1?.invalidate()
          withExtendedLifetime(previous) {}
          if let error { continuation.resume(throwing: error) }
          else if let data, let response { continuation.resume(returning: (data, response)) }
          else { continuation.resume(throwing: HostServiceError.invalidResponse) }
        }
        let task: URLSessionDataTask = file.map { session.uploadTask(with: request, fromFile: $0, completionHandler: completion) } ?? session.dataTask(with: request, completionHandler: completion)
        let observation: NSKeyValueObservation = task.observe(\URLSessionDataTask.countOfBytesSent, options: [.new]) { [self] observedTask, _ in
          let now = ProcessInfo.processInfo.systemUptime, count = observedTask.countOfBytesSent
          let report = lock.withLock { () -> Bool in
            guard now - lastReport >= 0.25 || count == Int64(request.httpBody?.count ?? 0) else { return false }
            lastReport = now; return true
          }
          if report { sent?(count) }
        }
        let cancelNow = lock.withLock { self.task = task; self.observation = observation; return cancelled }
        if cancelNow { task.cancel() }
        task.resume()
      }
    } onCancel: {
      let task = self.lock.withLock { self.cancelled = true; return self.task }
      task?.cancel()
    }
  }
}

enum CodexUploadAPI {
  static func target(_ client: Client) throws -> String {
    guard let value = client.codexDirect?.target ?? client.readingTarget else { throw HostServiceError.wrongHost }
    return String(decoding: try JSONEncoder().encode(value), as: UTF8.self)
  }
  static func supported(_ client: Client) async throws -> Bool {
    struct Capability: Decodable { let version: Int; let target: AgentRuntimeTarget; let maxChunkBytes: Int }
    do {
      let value: Capability = try await HostService.load(HostService.request(try HostService.url(client, "v1/uploads/capabilities", ["target": target(client)]), token: CredentialVault.token(client.id)))
      guard value.version == 1, value.target == (client.codexDirect?.target ?? client.readingTarget), (1...1024 * 1024).contains(value.maxChunkBytes) else { throw HostServiceError.wrongHost }
      return true
    } catch HostServiceError.rejected(404, _) { return false }
  }
  static func request(_ client: Client, manifest: UploadManifest, action: UploadAction, bytes: Data?, sent: (@Sendable (Int64) -> Void)?) async throws -> UploadReceipt {
    var path = "v1/uploads", query = ["target": try target(client), "id": manifest.id]
    var method = "POST", body: Data?
    switch action {
    case .prepare: body = try JSONEncoder().encode(manifest)
    case .status: method = "GET"
    case .chunk(let offset): path += "/chunk"; query["offset"] = String(offset); body = bytes
    case .complete: path += "/complete"
    case .cancel: path += "/cancel"
    }
    var request = HostService.request(try HostService.url(client, path, query), token: CredentialVault.token(client.id), method: method, body: body)
    if case .chunk = action {
      request.timeoutInterval = 30
      request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
      request.setValue(HostService.hash(bytes ?? Data()), forHTTPHeaderField: "X-Content-SHA256")
    }
    let session = CodexHostRoutes.shared.session(for: request)
    let (data, response) = try await UploadWireOperation().run(session: session, request: request, sent: sent)
    guard let http = response as? HTTPURLResponse else { throw HostServiceError.invalidResponse }
    guard http.statusCode == 200 else {
      let value = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
      throw HostServiceError.rejected(http.statusCode, value?["error"] as? String ?? L10n.tr("上传请求失败"))
    }
    let receipt = try JSONDecoder().decode(UploadReceipt.self, from: data)
    try receipt.validate(manifest)
    return receipt
  }
}

// Presentation only: keep the stored diagnostic and all transfer/receipt semantics unchanged.
// Older checkpoints contain Foundation's cancellation domain rather than user-facing copy.
enum UploadFailurePresentation {
  static func text(_ original: String) -> String {
    if original.contains("(Swift.CancellationError") || original.contains("（Swift.CancellationError") {
      return L10n.tr("上次上传过程已中断，文件和消息已保留。")
    }
    if original.trimmingCharacters(in: .whitespacesAndNewlines)
      .replacingOccurrences(of: "-", with: "_").uppercased() == "TRANSFER_NOT_FOUND" {
      return L10n.tr("电脑端未找到这次上传记录。")
    }
    return original
  }
}

struct UploadJob: Codable, Identifiable, Sendable {
  var client: Client
  let manifest: UploadManifest
  var phase: String
  var committedBytes: Int = 0
  var sentBytes: Int = 0
  var bytesPerSecond: Double = 0
  var sampledAt: TimeInterval = 0
  var error: String?
  var receipt: UploadReceipt?
  var batch: UploadBatch? = nil
  var cancelRequested: Bool? = nil
  var capturedFile: DesktopCreationFile? = nil
  var id: String { manifest.id }
}

@MainActor final class UploadCenter: ObservableObject {
  static let shared = UploadCenter()
  @Published private(set) var jobs: [String: UploadJob] = [:]
  private var running = Set<String>()
  private var slots = Set<String>()
  private var autoResume = Set<String>()
  private var requests: [String: Task<UploadReceipt, Error>] = [:]
  private var requestGenerations: [String: UUID] = [:]
  private var paused = Set<String>()
  private var cancelled = Set<String>()
  private let directory: URL
  private let pathMonitor = NWPathMonitor()
  init(directory: URL? = nil) {
    self.directory = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("ResumableUploads", isDirectory: true)
    if let files = try? FileManager.default.contentsOfDirectory(at: self.directory, includingPropertiesForKeys: nil) {
      for file in files where file.pathExtension == "journal" {
        if let bytes = try? Data(contentsOf: file), var job = try? AgentCache.open(bytes, as: UploadJob.self), UUID(uuidString: job.id) != nil {
          if job.phase != "completed" && job.phase != "cancelled" {
            job.bytesPerSecond = 0
            if job.cancelRequested == true { job.phase = "cancel-pending"; cancelled.insert(job.id) }
            else { job.phase = "paused"; paused.insert(job.id) }
          }
          jobs[job.id] = job
        }
      }
    }
    pathMonitor.pathUpdateHandler = { [weak self] path in
      guard path.status == .satisfied else { return }
      Task { @MainActor in
        guard let self else { return }
        for id in self.autoResume where self.jobs[id]?.phase == "waiting" && !self.paused.contains(id) { self.resume(id) }
      }
    }
    pathMonitor.start(queue: DispatchQueue(label: "agent-hub.upload-path"))
  }
  private func source(_ id: String) -> URL { directory.appendingPathComponent(id + ".payload") }
  private func save(_ id: String) throws {
    guard let job = jobs[id] else { return }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    try AgentCache.seal(job).write(to: directory.appendingPathComponent(id + ".journal"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
  }
  nonisolated static func fileHash(_ source: URL) throws -> (Int, String) {
    let file = try FileHandle(forReadingFrom: source); defer { try? file.close() }
    var hash = SHA256(), count = 0
    while let data = try file.read(upToCount: 256 * 1024), !data.isEmpty { hash.update(data: data); count += data.count }
    return (count, hash.finalize().map { String(format: "%02x", $0) }.joined())
  }
  nonisolated static func identifier(_ client: Client, purpose: String, parent: String, fileId: String) -> String {
    var target = client.agentConnection?.target ?? client.codexDirect?.target ?? client.readingTarget
    if target?.agentId == "codex" { target?.account = nil }
    let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
    let encoded = (try? encoder.encode(target)) ?? Data()
    let hex = HostService.hash(encoded + Data("\(client.id)|\(purpose)|\(parent)|\(fileId)".utf8))
    let chars = Array(hex.prefix(32)); return [String(chars[0..<8]), String(chars[8..<12]), String(chars[12..<16]), String(chars[16..<20]), String(chars[20..<32])].joined(separator: "-")
  }
  nonisolated static func sameTarget(_ a: Client, _ b: Client) -> Bool {
    a.id == b.id && (a.agentConnection?.target ?? a.codexDirect?.target ?? a.readingTarget) ==
      (b.agentConnection?.target ?? b.codexDirect?.target ?? b.readingTarget)
  }
  func reconcile(_ client: Client) async {
    for job in jobs.values.filter({ Self.sameTarget($0.client, client) && !running.contains($0.id) && !["completed", "cancelled"].contains($0.phase) }) {
      do {
        let receipt = try await UploadRequestContext.$capturedFile.withValue(job.capturedFile) {
          try await AgentClients.resolve(client).uploadRequest(client, manifest: job.manifest, action: .status, bytes: nil, sent: nil)
        }
        try receipt.validate(job.manifest)
        jobs[job.id]?.client = client; jobs[job.id]?.receipt = receipt; jobs[job.id]?.committedBytes = receipt.committedBytes
        jobs[job.id]?.phase = ["completed", "cancelled"].contains(receipt.phase) ? receipt.phase : (job.cancelRequested == true ? "cancel-pending" : "paused")
        jobs[job.id]?.bytesPerSecond = 0; try save(job.id)
        if ["completed", "cancelled"].contains(receipt.phase) {
          jobs[job.id]?.cancelRequested = false; cancelled.remove(job.id); paused.remove(job.id); autoResume.remove(job.id)
          try save(job.id); try? FileManager.default.removeItem(at: source(job.id))
        }
      } catch { jobs[job.id]?.error = error.localizedDescription }
    }
  }
  func legacyUpload(_ client: Client, manifest: UploadManifest, source: URL, request: URLRequest) async throws -> Data {
    guard jobs[manifest.id] == nil else { throw HostServiceError.message(L10n.tr("原上传进度已保留，请使用支持续传的电脑端继续")) }
    jobs[manifest.id] = UploadJob(client: client, manifest: manifest, phase: "legacy")
    jobs[manifest.id]?.batch = UploadBatch.current
    defer { jobs.removeValue(forKey: manifest.id) }
    let started = ProcessInfo.processInfo.systemUptime
    let (data, response) = try await UploadWireOperation().run(session: CodexHostRoutes.shared.session(for: request), request: request, file: source, sent: { count in
      Task { @MainActor in
        self.jobs[manifest.id]?.sentBytes = min(manifest.sizeBytes, Int(count))
        if count >= manifest.sizeBytes { self.jobs[manifest.id]?.phase = "legacy-verifying" }
        self.jobs[manifest.id]?.bytesPerSecond = Double(count) / max(0.001, ProcessInfo.processInfo.systemUptime - started)
        self.jobs[manifest.id]?.sampledAt = ProcessInfo.processInfo.systemUptime
      }
    })
    guard let http = response as? HTTPURLResponse else { throw HostServiceError.invalidResponse }
    guard http.statusCode == 200 else {
      let value = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
      throw HostServiceError.rejected(http.statusCode, value?["error"] as? String ?? L10n.tr("上传请求失败"))
    }
    return data
  }
  func pause(_ id: String) { paused.insert(id); jobs[id]?.phase = "paused"; jobs[id]?.bytesPerSecond = 0; try? save(id); requests[id]?.cancel() }
  func resume(_ id: String) {
    guard let job = jobs[id], job.phase != "completed", job.phase != "cancelled" else { return }
    paused.remove(id)
    if job.cancelRequested != true { autoResume.insert(id) }
    if !running.contains(id) { Task { _ = try? await self.transfer(job.client, manifest: job.manifest, source: self.source(id)) } }
  }
  func cancel(_ id: String) {
    cancelled.insert(id); paused.remove(id); autoResume.remove(id)
    jobs[id]?.cancelRequested = true; jobs[id]?.phase = "cancel-pending"
    do { try save(id) } catch { jobs[id]?.error = error.localizedDescription }
    requests[id]?.cancel()
    guard !running.contains(id), let job = jobs[id] else { return }
    Task {
      do {
        let receipt = try await stop(job.client, manifest: job.manifest)
        jobs[id]?.phase = receipt.phase; jobs[id]?.receipt = receipt; jobs[id]?.cancelRequested = false; try save(id)
        cancelled.remove(id); paused.remove(id)
        if ["completed", "cancelled"].contains(receipt.phase) { try? FileManager.default.removeItem(at: source(id)) }
      } catch { jobs[id]?.error = error.localizedDescription; try? save(id) }
    }
  }
  private func stop(_ client: Client, manifest: UploadManifest, current: UploadReceipt? = nil) async throws -> UploadReceipt {
    var receipt: UploadReceipt
    if let current { receipt = current }
    else {
      do { receipt = try await call(client, manifest: manifest, action: .status) }
      catch HostServiceError.rejected(404, _) { receipt = try await call(client, manifest: manifest, action: .prepare) }
    }
    var conflicts = 0
    while true {
      if ["completed", "cancelled"].contains(receipt.phase) { return receipt }
      do {
        do { return try await call(client, manifest: manifest, action: .cancel) }
        catch HostServiceError.rejected(409, let message) {
          receipt = try await call(client, manifest: manifest, action: .status)
          if ["completed", "cancelled"].contains(receipt.phase) { return receipt }
          // Reconcile the original file publication, then stop before business submit.
          if receipt.phase == "verifying" { return try await call(client, manifest: manifest, action: .complete) }
          throw HostServiceError.rejected(409, message)
        }
      } catch HostServiceError.rejected(409, _) where conflicts < 3 {
        conflicts += 1
        try await Task.sleep(for: .milliseconds(250 * conflicts))
        receipt = try await call(client, manifest: manifest, action: .status)
      }
    }
  }
  private func call(_ client: Client, manifest: UploadManifest, action: UploadAction, bytes: Data? = nil) async throws -> UploadReceipt {
    let id = manifest.id, base = jobs[id]?.committedBytes ?? 0, generation = UUID()
    requestGenerations[id] = generation
    let capturedFile = jobs[id]?.capturedFile
    let task = Task { try await UploadRequestContext.$capturedFile.withValue(capturedFile) {
      try await AgentClients.resolve(client).uploadRequest(client, manifest: manifest, action: action, bytes: bytes, sent: { count in
      Task { @MainActor in
        guard self.jobs[id]?.phase == "uploading", self.requestGenerations[id] == generation else { return }
        self.jobs[id]?.sentBytes = max(self.jobs[id]?.committedBytes ?? 0, min(manifest.sizeBytes, base + Int(count)))
        // Socket bursts are not end-to-end throughput. Rate/ETA are sampled only
        // when the Host acknowledges this chunk, including network and disk time.
      }
      })
    } }
    requests[id] = task
    defer { requests.removeValue(forKey: id); requestGenerations.removeValue(forKey: id) }
    return try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
  }
  func transfer(_ client: Client, manifest: UploadManifest, source original: URL, capturedFile: DesktopCreationFile? = nil) async throws -> UploadReceipt {
    let id = manifest.id
    guard UUID(uuidString: id) != nil, manifest.hostId == client.id else { throw HostServiceError.wrongHost }
    if let old = jobs[id], old.manifest != manifest || !Self.sameTarget(old.client, client) { throw HostServiceError.wrongHost }
    if let capturedFile, let old = jobs[id], old.capturedFile != capturedFile { throw HostServiceError.wrongHost }
    guard !running.contains(id) else { throw HostServiceError.message(L10n.tr("此文件正在上传")) }
    running.insert(id)
    defer { running.remove(id); slots.remove(id); requests[id]?.cancel(); requests.removeValue(forKey: id) }
    if jobs[id] == nil {
      jobs[id] = UploadJob(client: client, manifest: manifest, phase: "preparing", capturedFile: capturedFile)
      let destination = source(id), directory = directory
      do {
        try await Task.detached {
          try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
          if !FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.copyItem(at: original, to: destination) }
          try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: destination.path)
          let (size, hash) = try Self.fileHash(destination)
          guard size == manifest.sizeBytes, hash == manifest.sha256 else { throw HostServiceError.invalidResponse }
        }.value
        try save(id)
      } catch { jobs[id]?.phase = jobs[id]?.cancelRequested == true ? "cancel-pending" : "failed"; jobs[id]?.error = error.localizedDescription; throw error }
    }
    if let batch = UploadBatch.current { jobs[id]?.batch = batch }
    if jobs[id]?.cancelRequested == true { cancelled.insert(id) }
    while slots.filter({ jobs[$0]?.client.id == client.id }).count >= 2 {
      if cancelled.contains(id) { break }
      jobs[id]?.phase = paused.contains(id) ? "paused" : "queued"
      try await Task.sleep(for: .milliseconds(200)); try Task.checkCancellation()
    }
    if !cancelled.contains(id) { slots.insert(id); autoResume.insert(id) }
    var failures = 0, needsReconcile = false, chunkLimit = 256 * 1024
    do {
      while paused.contains(id) && !cancelled.contains(id) { try await Task.sleep(for: .milliseconds(200)); try Task.checkCancellation() }
      jobs[id]?.phase = cancelled.contains(id) ? "cancel-pending" : "reconciling"
      var receipt: UploadReceipt
      while true {
        do {
          do { receipt = try await call(client, manifest: manifest, action: .status) }
          catch HostServiceError.rejected(404, _) { receipt = try await call(client, manifest: manifest, action: .prepare) }
          break
        } catch {
          if Task.isCancelled { throw error }
          let interrupted = error is CancellationError || (error as? URLError)?.code == .cancelled
          guard interrupted && (paused.contains(id) || cancelled.contains(id)) else { throw error }
          while paused.contains(id) && !cancelled.contains(id) { try await Task.sleep(for: .milliseconds(200)); try Task.checkCancellation() }
        }
      }
      while true {
        try Task.checkCancellation()
        if cancelled.contains(id) {
          receipt = try await stop(client, manifest: manifest, current: receipt)
          jobs[id]?.receipt = receipt; jobs[id]?.committedBytes = receipt.committedBytes
          cancelled.remove(id)
          jobs[id]?.phase = receipt.phase; jobs[id]?.cancelRequested = false
          try save(id); try? FileManager.default.removeItem(at: source(id))
          // A completed file remains delivered, but an explicit stop must not start model input.
          throw CancellationError()
        }
        while paused.contains(id) && !cancelled.contains(id) { try await Task.sleep(for: .milliseconds(200)); try Task.checkCancellation() }
        if cancelled.contains(id) { continue }
        if needsReconcile { receipt = try await call(client, manifest: manifest, action: .status); needsReconcile = false }
        try receipt.validate(manifest)
        if receipt.phase != "cancelled" { jobs[id]?.error = nil }
        jobs[id]?.committedBytes = receipt.committedBytes; jobs[id]?.receipt = receipt
        if receipt.phase == "completed" {
          jobs[id]?.phase = "completed"; jobs[id]?.bytesPerSecond = 0; autoResume.remove(id); try save(id)
          try? FileManager.default.removeItem(at: source(id)); return receipt
        }
        if receipt.phase == "cancelled" { throw HostServiceError.message(receipt.reason == "expired" ? L10n.tr("电脑上的上传暂存已过期，请重新上传") : L10n.tr("上传已取消，请移除此附件后重新选择")) }
        jobs[id]?.phase = receipt.committedBytes == manifest.sizeBytes ? "verifying" : "uploading"
        try save(id)
        let started = ProcessInfo.processInfo.systemUptime
        do {
          if receipt.committedBytes == manifest.sizeBytes { receipt = try await call(client, manifest: manifest, action: .complete) }
          else {
            let offset = receipt.committedBytes, length = min(receipt.maxChunkBytes, chunkLimit, manifest.sizeBytes - offset), file = source(id)
            let data = try await Task.detached { () throws -> Data in
              let handle = try FileHandle(forReadingFrom: file); defer { try? handle.close() }; try handle.seek(toOffset: UInt64(offset))
              guard let data = try handle.read(upToCount: length), data.count == length else { throw HostServiceError.invalidResponse }; return data
            }.value
            receipt = try await call(client, manifest: manifest, action: .chunk(offset), bytes: data)
            jobs[id]?.bytesPerSecond = Double(data.count) / max(0.001, ProcessInfo.processInfo.systemUptime - started)
            jobs[id]?.sampledAt = ProcessInfo.processInfo.systemUptime
          }
          failures = 0
        } catch {
          if Task.isCancelled { throw error }
          jobs[id]?.bytesPerSecond = 0
          if paused.contains(id) || cancelled.contains(id) { needsReconcile = true; continue }
          let status: Int? = { if case HostServiceError.rejected(let status, _) = error { return status }; return nil }()
          let transient = error is URLError || status.map { [408, 409, 429, 502, 503, 504].contains($0) } == true
          guard transient, failures < 3 else { throw error }
          chunkLimit = max(16 * 1024, chunkLimit / 2)
          failures += 1; jobs[id]?.phase = "waiting"; try save(id)
          try await Task.sleep(for: .seconds(Double(failures * 2)))
          // Unknown chunk/completion results are queried, never blindly appended or resubmitted.
          receipt = try await call(client, manifest: manifest, action: .status)
        }
      }
    } catch {
      jobs[id]?.bytesPerSecond = 0
      if !["cancelled", "completed"].contains(jobs[id]?.phase ?? "") {
        jobs[id]?.phase = jobs[id]?.cancelRequested == true ? "cancel-pending" : (paused.contains(id) ? "paused" : (error is URLError ? "waiting" : "failed"))
        jobs[id]?.error = error.localizedDescription
      }
      try? save(id); throw error
    }
  }
}

struct UploadProgressView: View {
  let job: UploadJob
  private var legacy: Bool { job.phase.hasPrefix("legacy") }
  private var title: String {
    switch job.phase {
    case "preparing": return L10n.tr("正在准备文件…")
    case "queued": return L10n.tr("排队等待上传")
    case "paused": return L10n.tr("已暂停")
    case "waiting": return L10n.tr("等待网络恢复")
    case "reconciling": return L10n.tr("正在核对上传进度…")
    case "verifying": return L10n.tr("正在校验文件…")
    case "completed": return L10n.tr("上传完成")
    case "cancelled": return L10n.tr("上传已取消")
    case "cancel-pending": return L10n.tr("正在确认取消结果…")
    case "failed": return L10n.tr("上传中断，进度已保留")
    case "legacy": return L10n.tr("正在上传（不支持续传）")
    case "legacy-verifying": return L10n.tr("文件已发送，正在等待电脑确认…")
    default: return L10n.tr("正在上传…")
    }
  }
  var body: some View {
    TimelineView(.periodic(from: .now, by: 1)) { _ in content }
  }
  private var content: some View {
    VStack(alignment: .leading, spacing: 7) {
      HStack {
        Text(job.manifest.name).lineLimit(1); Spacer(); Text(title)
        Text("\(Int(Double(legacy ? job.sentBytes : job.committedBytes) / Double(max(1, job.manifest.sizeBytes)) * 100))%").monospacedDigit()
      }.font(.caption)
      ProgressView(value: Double(legacy ? job.sentBytes : job.committedBytes), total: Double(max(1, job.manifest.sizeBytes)))
      HStack {
        Text("\(ByteCountFormatter.string(fromByteCount: Int64(legacy ? job.sentBytes : job.committedBytes), countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: Int64(job.manifest.sizeBytes), countStyle: .file))")
        Spacer()
        if ["uploading", "legacy"].contains(job.phase), job.bytesPerSecond > 0, ProcessInfo.processInfo.systemUptime - job.sampledAt < 3 {
          Text(ByteCountFormatter.string(fromByteCount: Int64(job.bytesPerSecond), countStyle: .file) + "/s")
          Text(L10n.tr("剩余约 \(max(1, Int(Double(job.manifest.sizeBytes - job.committedBytes) / job.bytesPerSecond))) 秒"))
        } else if job.phase == "uploading" { Text(L10n.tr("正在等待传输进展…")) }
      }.font(.caption2).monospacedDigit().foregroundStyle(.secondary)
      if let error = job.error, !error.isEmpty, ["failed", "paused", "waiting"].contains(job.phase) {
        Text(UploadFailurePresentation.text(error)).font(.caption2).foregroundStyle(.red)
          .accessibilityIdentifier("upload-error-" + job.id)
      }
      if legacy { Text(L10n.tr("此电脑版本不支持断点续传，中断后需重新上传")).font(.caption2).foregroundStyle(.secondary) }
      if !["completed", "cancelled", "verifying"].contains(job.phase) && !legacy {
        HStack {
          Button {
            if ["paused", "failed", "waiting", "cancel-pending"].contains(job.phase) { UploadCenter.shared.resume(job.id) } else { UploadCenter.shared.pause(job.id) }
          } label: {
            Text(job.phase == "cancel-pending" ? L10n.tr("查询结果") : (["paused", "failed", "waiting"].contains(job.phase) ? L10n.tr("继续上传") : L10n.tr("暂停")))
              .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
          }.accessibilityIdentifier("upload-pause-resume-\(job.id)")
          if job.phase != "cancel-pending" {
            Button { UploadCenter.shared.cancel(job.id) } label: {
              Text(job.client.agentConnection == nil ? L10n.tr("取消上传") : L10n.tr("停止上传"))
                .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
            }.accessibilityIdentifier("upload-cancel-\(job.id)")
          }
        }.font(.caption).buttonStyle(.borderless)
        if job.client.agentConnection != nil { Text(L10n.tr("停止后保留消息，电脑暂存由电脑端管理")).font(.caption2).foregroundStyle(.secondary) }
      }
    }.accessibilityElement(children: .contain).accessibilityIdentifier("upload-progress-\(job.id)")
  }
}

extension CodexAgentClient {
  func uploadRequest(_ client: Client, manifest: UploadManifest, action: UploadAction, bytes: Data?, sent: (@Sendable (Int64) -> Void)?) async throws -> UploadReceipt {
    try await CodexUploadAPI.request(client, manifest: manifest, action: action, bytes: bytes, sent: sent)
  }
  func uploadFile(_ client: Client, threadId: String, file: LocalAttachment, source: URL) async throws -> RemoteAttachment {
    let manifest = try await uploadManifest(client, file: file, source: source, purpose: "attachment", parent: threadId)
    let result: RemoteAttachment
    if try await CodexUploadAPI.supported(client) {
      let receipt = try await UploadCenter.shared.transfer(client, manifest: manifest, source: source)
      result = try receipt.value(RemoteAttachment.self)
    } else {
      result = try await legacyFile(client, manifest: manifest, source: source, path: "v1/attachments", query: ["threadId": threadId, "name": file.name])
    }
    guard result.hostId == client.id, result.threadId == threadId, result.sha256 == manifest.sha256, result.sizeBytes == manifest.sizeBytes,
      result.name == manifest.name, result.mediaType == manifest.mediaType else { throw HostServiceError.wrongHost }
    return result
  }
  func uploadCreationFile(_ client: Client, id: String, file: LocalAttachment, source: URL) async throws {
    let manifest = try await uploadManifest(client, file: file, source: source, purpose: "creation-attachment", parent: id)
    let result: UploadResult
    if try await CodexUploadAPI.supported(client) {
      let receipt = try await UploadCenter.shared.transfer(client, manifest: manifest, source: source)
      guard let value = receipt.result else { throw HostServiceError.invalidResponse }; result = value
    } else {
      result = try await legacyFile(client, manifest: manifest, source: source, path: "v1/desktop/creation-attachments", query: ["id": id, "attachmentId": file.id])
    }
    guard result.hostId == client.id, result.clientOperationId == id,
      result.id == file.id, result.sha256 == manifest.sha256, result.sizeBytes == manifest.sizeBytes
    else { throw HostServiceError.wrongHost }
  }
  func uploadTransferFile(_ client: Client, file: ComputerFileManifest, source: URL) async throws -> ComputerFileReceipt {
    let manifest = UploadManifest(hostId: client.id, id: file.id, name: file.name, mediaType: file.mediaType,
      sizeBytes: file.sizeBytes, sha256: file.sha256, purpose: "file-transfer", threadId: nil, creationId: nil, attachmentId: nil)
    let result: ComputerFileReceipt
    if try await CodexUploadAPI.supported(client) {
      let receipt = try await UploadCenter.shared.transfer(client, manifest: manifest, source: source)
      result = try receipt.value(ComputerFileReceipt.self)
    } else {
      result = try await legacyFile(client, manifest: manifest, source: source, path: "v1/file-transfers/content", query: ["id": file.id])
    }
    try result.validate(file); return result
  }
}

private func legacyFile<T: Decodable>(_ client: Client, manifest: UploadManifest, source: URL, path: String, query: [String: String]) async throws -> T {
  var request = HostService.request(try HostService.url(client, path, query), token: CredentialVault.token(client.id), method: "POST")
  request.setValue(manifest.mediaType, forHTTPHeaderField: "Content-Type")
  request.setValue(manifest.sha256, forHTTPHeaderField: "X-Content-SHA256")
  let data = try await UploadCenter.shared.legacyUpload(client, manifest: manifest, source: source, request: request)
  return try JSONDecoder().decode(T.self, from: data)
}

private func uploadManifest(_ client: Client, file: LocalAttachment, source: URL, purpose: String, parent: String) async throws -> UploadManifest {
  let (size, hash) = try await Task.detached { try UploadCenter.fileHash(source) }.value
  guard size > 0, size == file.size else { throw HostServiceError.invalidResponse }
  let name = client.agentConnection?.target.agentId == "easywork" ? EasyWorkAttachmentUpload.displayName(file.name) : file.name
  return UploadManifest(hostId: client.id, id: UploadCenter.identifier(client, purpose: purpose, parent: parent, fileId: file.id),
    name: name, mediaType: UTType(filenameExtension: (file.name as NSString).pathExtension)?.preferredMIMEType ?? "application/octet-stream", sizeBytes: size, sha256: hash, purpose: purpose,
    threadId: purpose == "attachment" ? parent : nil, creationId: purpose == "creation-attachment" ? parent : nil,
    attachmentId: purpose == "creation-attachment" ? file.id : nil)
}

struct UploadJobsView: View {
  @ObservedObject private var center = UploadCenter.shared
  let client: Client
  var threadId: String? = nil
  var creationId: String? = nil
  var independent = false
  private var matching: [UploadJob] {
    center.jobs.values.filter {
      UploadCenter.sameTarget($0.client, client) &&
      !["completed", "cancelled"].contains($0.phase) &&
      (independent ? $0.manifest.purpose == "file-transfer" :
        ($0.manifest.purpose == "attachment" && $0.manifest.threadId == threadId) ||
          ($0.manifest.purpose == "creation-attachment" && creationId != nil && $0.manifest.creationId == creationId))
    }.sorted { $0.id < $1.id }
  }
  var body: some View {
    Group {
      if let batch = matching.first?.batch, batch.count > 1, matching.allSatisfy({ $0.batch?.id == batch.id }) {
        let done = center.jobs.values.filter { $0.batch?.id == batch.id }.reduce(0) { $0 + $1.committedBytes }
        VStack(alignment: .leading, spacing: 5) {
          Text(L10n.tr("文件 \(batch.index + 1)/\(batch.count)")).font(.caption)
          ProgressView(L10n.tr("总体进度"), value: Double(done), total: Double(max(1, batch.totalBytes)))
        }.accessibilityIdentifier("upload-batch-progress")
      }
      ForEach(matching) { job in UploadProgressView(job: job) }
    }.task(id: client.endpoint) { await center.reconcile(client) }
  }
}

/// Captured first inputs retain their original prepare/chunk/complete identities.
/// Ordinary uploads keep the current digest-qualified chunk request IDs.
enum EasyWorkCapturedUploadIdentity {
  static func validate(_ captured: DesktopCreationFile?, manifest m: UploadManifest) throws {
    guard let captured else { return }
    guard UUID(uuidString: captured.id) != nil, captured.id == m.id,
      EasyWorkAttachmentUpload.displayName(captured.name) == m.name,
      captured.mediaType == m.mediaType, captured.sizeBytes == m.sizeBytes,
      captured.sha256 == m.sha256 else { throw HostServiceError.invalidResponse }
  }
  static func chunkId(_ m: UploadManifest, offset: Int, bytes: Data, captured: DesktopCreationFile?) -> String {
    if captured != nil { return "chunk-\(m.id)-\(offset)" }
    return "chunk-\(m.id)-\(offset)-\(HostService.hash(bytes).prefix(12))"
  }
}

/// Preserve EasyWork's authenticated device/file scope for every resumable response.
enum EasyWorkUploadValidation {
  static func transfer(_ response: [String: Any], manifest m: UploadManifest, deviceId: String, completed: Bool = false) throws -> [String: Any] {
    guard let record = response["transfer"] as? [String: Any], !deviceId.isEmpty,
      record["transferId"] as? String == m.id, record["threadId"] as? String == m.threadId,
      record["mobileDeviceId"] as? String == deviceId, record["direction"] as? String == "mobile-to-desktop",
      record["purpose"] as? String == "attachment", record["name"] as? String == m.name,
      record["contentType"] as? String == m.mediaType, try EasyWorkReading.integer(record["size"]) == m.sizeBytes,
      !completed || (record["status"] as? String == "completed" && record["sha256"] as? String == m.sha256)
    else { throw HostServiceError.invalidResponse }
    let offset = try EasyWorkReading.integer(record["transferredBytes"])
    guard (0...m.sizeBytes).contains(offset), !completed || offset == m.sizeBytes else { throw HostServiceError.invalidResponse }
    return record
  }
  static func chunk(_ response: [String: Any], end: Int, size: Int) throws {
    guard try EasyWorkReading.integer(response["nextOffset"]) == end,
      let complete = response["complete"] as? NSNumber, CFGetTypeID(complete) == CFBooleanGetTypeID(),
      complete.boolValue == (end == size) else { throw HostServiceError.invalidResponse }
  }
  static func completion(_ response: [String: Any], manifest m: UploadManifest, deviceId: String) throws -> UploadResult {
    let record = try transfer(response, manifest: m, deviceId: deviceId, completed: true)
    guard let a = response["attachment"] as? [String: Any], a["id"] as? String == m.id,
      let resourceId = a["resourceId"] as? String, !resourceId.isEmpty, record["resourceId"] as? String == resourceId,
      a["name"] as? String == m.name, a["contentType"] as? String == m.mediaType,
      a["sha256"] as? String == m.sha256, try EasyWorkReading.integer(a["size"]) == m.sizeBytes
    else { throw HostServiceError.invalidResponse }
    return UploadResult(hostId: m.hostId, id: resourceId, threadId: m.threadId, name: m.name,
      mediaType: m.mediaType, sizeBytes: m.sizeBytes, sha256: m.sha256)
  }
}

extension EasyWorkAgentClient {
  func uploadFile(_ client: Client, threadId: String, file: LocalAttachment, source: URL) async throws -> RemoteAttachment {
    let manifest = try await uploadManifest(client, file: file, source: source, purpose: "attachment", parent: threadId)
    let receipt = try await UploadCenter.shared.transfer(client, manifest: manifest, source: source)
    let result = try receipt.value(RemoteAttachment.self)
    guard result.hostId == client.id, result.threadId == threadId, result.sha256 == manifest.sha256, result.sizeBytes == manifest.sizeBytes,
      result.name == manifest.name, result.mediaType == manifest.mediaType else { throw HostServiceError.wrongHost }
    return result
  }
  func uploadRequest(_ client: Client, manifest m: UploadManifest, action: UploadAction, bytes: Data?, sent: (@Sendable (Int64) -> Void)?) async throws -> UploadReceipt {
    guard m.purpose == "attachment", let threadId = m.threadId else { throw HostServiceError.unavailable }
    let captured = UploadRequestContext.capturedFile
    guard m.sizeBytes > 0, m.sizeBytes <= 20 * 1024 * 1024 else {
      if captured != nil { throw HostServiceError.message(L10n.tr("此连接暂不能继续该大小的附件，原文件和原记录已保留。")) }
      throw HostServiceError.message(L10n.tr("原附件内容已改变，未提交"))
    }
    try EasyWorkCapturedUploadIdentity.validate(captured, manifest: m)
    let channel = try await session(client), deviceId = await channel.mobileDeviceId
    func object(_ data: Data) throws -> [String: Any] {
      guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw HostServiceError.invalidResponse }; return value
    }
    var result: UploadResult?, phase = "uploading", offset = 0
    var knownStatus: [String: Any]?
    switch action {
    case .prepare:
      if captured != nil {
        do {
          let status = try object(await channel.request("resource/upload-status", ["transferId": m.id]))
          _ = try EasyWorkUploadValidation.transfer(status, manifest: m, deviceId: deviceId)
          knownStatus = status
        } catch HostServiceError.rejected(404, _) {
          let original = try await channel.request("command/status", ["commandId": "upload-" + m.id])
          guard try JSONSerialization.jsonObject(with: original, options: [.fragmentsAllowed]) is NSNull else {
            throw HostServiceError.message(L10n.tr("原附件已有上传回执，请先核对原记录。"))
          }
        }
      }
      if knownStatus == nil {
        let response = try object(await channel.request("resource/upload", ["transferId": m.id, "name": captured?.name ?? m.name, "mediaType": m.mediaType, "size": m.sizeBytes, "expectedSha256": m.sha256, "purpose": "attachment", "threadId": threadId], id: "upload-" + m.id))
        _ = try EasyWorkUploadValidation.transfer(response, manifest: m, deviceId: deviceId)
      }
    case .status: break
    case .chunk(let position):
      guard let bytes, bytes.count <= 192 * 1024 else { throw HostServiceError.invalidResponse }
      let response = try object(await channel.request("resource/upload-chunk", ["transferId": m.id, "offset": position, "dataBase64": bytes.base64URL], id: EasyWorkCapturedUploadIdentity.chunkId(m, offset: position, bytes: bytes, captured: captured)))
      try EasyWorkUploadValidation.chunk(response, end: position + bytes.count, size: m.sizeBytes)
      sent?(Int64(bytes.count))
    case .complete:
      let response = try object(await channel.request("resource/upload-complete", ["transferId": m.id], id: "complete-" + m.id))
      result = try EasyWorkUploadValidation.completion(response, manifest: m, deviceId: deviceId)
      phase = "completed"; offset = m.sizeBytes
    case .cancel:
      // EWDAP does not advertise a remote abort operation. Stop our bytes, preserve its receipt.
      phase = "cancelled"
    }
    if phase == "uploading" {
      let response: [String: Any]
      if let knownStatus { response = knownStatus }
      else { response = try object(await channel.request("resource/upload-status", ["transferId": m.id])) }
      let transfer = try EasyWorkUploadValidation.transfer(response, manifest: m, deviceId: deviceId)
      offset = try EasyWorkReading.integer(transfer["transferredBytes"])
      if transfer["status"] as? String == "completed" {
        return try await uploadRequest(client, manifest: m, action: .complete, bytes: nil, sent: nil)
      }
      guard ["transferring", "verifying"].contains(transfer["status"] as? String ?? "")
        || (captured != nil && transfer["status"] as? String == "waiting") else { throw HostServiceError.invalidResponse }
    }
    let now = ISO8601DateFormatter().string(from: Date())
    return UploadReceipt(version: 1, manifest: m, phase: phase, committedBytes: offset,
      maxChunkBytes: 192 * 1024, createdAt: now, updatedAt: now, result: result)
  }
}

#if DEBUG
/// Only the independent XCTest app may enter this file-only acceptance surface.
struct UploadAcceptanceView: View {
  struct Configuration: Decodable { let client: Client; let manifest: UploadManifest; let token: String? }
  static var enabled: Bool {
    Bundle.main.bundleIdentifier == "com.easyai.agenthub.uploadresume.acceptance" && ProcessInfo.processInfo.arguments.contains("-upload-resume-acceptance")
  }
  @ObservedObject private var center = UploadCenter.shared
  @State private var message = ""
  private var configuration: Configuration? {
    guard let text = ProcessInfo.processInfo.environment["UPLOAD_ACCEPTANCE_CONFIG"],
      let config = try? JSONDecoder().decode(Configuration.self, from: Data(text.utf8)),
      config.client.endpoint.flatMap({ URL(string: $0)?.host }) == "127.0.0.1" else { return nil }
    return config
  }
  var body: some View {
    NavigationStack {
      List {
        if let config = configuration {
          Button("开始验收上传") {
            Task {
              do {
                let source = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("acceptance-input.bin")
                if let token = config.token { try CredentialVault.set(token, id: config.client.id) }
                let size = config.manifest.sizeBytes
                try await Task.detached {
                  if FileManager.default.fileExists(atPath: source.path) { try FileManager.default.removeItem(at: source) }
                  FileManager.default.createFile(atPath: source.path, contents: nil)
                  let handle = try FileHandle(forWritingTo: source); defer { try? handle.close() }
                  var remaining = size
                  while remaining > 0 { let n = min(256 * 1024, remaining); try handle.write(contentsOf: Data(repeating: 65, count: n)); remaining -= n }
                }.value
                _ = try await center.transfer(config.client, manifest: config.manifest, source: source)
                message = "验收上传完成"
              } catch { message = error.localizedDescription }
            }
          }.accessibilityIdentifier("acceptance-start-upload")
          ForEach(center.jobs.values.filter { $0.client.id == config.client.id }.sorted { $0.id < $1.id }) { job in UploadProgressView(job: job) }
          Text(message).accessibilityIdentifier("acceptance-upload-result")
        } else { Text("验收配置不可用") }
      }.navigationTitle("上传恢复验收")
    }
  }
}
#endif
