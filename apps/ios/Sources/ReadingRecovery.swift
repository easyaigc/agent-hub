import CryptoKit
import Foundation

/// Memory-only tickets fence delayed callbacks without consulting mutable UI state.
final class ReadingRecoveryFence: @unchecked Sendable {
  private let lock = NSLock()
  private var epoch = 0
  private var content: [String: Int] = [:]
  private var durable: [String: Int] = [:]
  private var requested: [String: Int] = [:]
  struct Ticket: Sendable { let epoch: Int; let content: Int; let key: String }
  func invalidate() { lock.lock(); defer { lock.unlock() }; epoch += 1 }
  func changed(_ key: String) { lock.lock(); defer { lock.unlock() }; content[key, default: 0] += 1 }
  func ticket(_ key: String) -> Ticket {
    lock.lock(); defer { lock.unlock() }; return Ticket(epoch: epoch, content: content[key, default: 0], key: key)
  }
  func current(_ ticket: Ticket) -> Bool {
    lock.lock(); defer { lock.unlock() }; return epoch == ticket.epoch && content[ticket.key, default: 0] == ticket.content
  }
  func requestBody(_ key: String) { lock.lock(); defer { lock.unlock() }; requested[key] = content[key, default: 0] }
  func needsBody(_ ticket: Ticket, stable: Bool) -> Bool {
    lock.lock(); defer { lock.unlock() }
    return durable[ticket.key] != ticket.content && (stable || (requested[ticket.key] ?? -1) > (durable[ticket.key] ?? -1))
  }
  func published(_ ticket: Ticket) {
    lock.lock(); defer { lock.unlock() }
    if epoch == ticket.epoch && content[ticket.key, default: 0] == ticket.content { durable[ticket.key] = ticket.content }
  }
}

/// Independent, encrypted reading-only family. The persistence history queue
/// exclusively owns this object; runtime, input and receipt state never enter it.
final class ReadingRecovery: @unchecked Sendable {
  static var admitted: Bool {
    #if R2_REAL_DEV_ACCEPTANCE || READING_RECOVERY_DISABLED
    false
    #else
    true
    #endif
  }
  static let bodyLimit = 32 * 1024 * 1024, indexLimit = 256 * 1024, messageLimit = 50_000
  static let entryLimit = 128, totalLimit = 128 * 1024 * 1024
  struct Binding: Codable, Equatable {
    let namespace: String; let key: String; let host: String; let thread: String
    let reader: String; let target: AgentRuntimeTarget
  }
  struct Body: Codable { let version: Int; let id: UUID; let binding: Binding; let messages: [ChatMessage] }
  struct Entry: Codable {
    let binding: Binding; let id: UUID; let sha256: String; let bytes: Int; let count: Int
    let paging: HistoryPagingCheckpoint?; var viewport: HistoryViewportBookmark?
  }
  struct Index: Codable {
    var version = 1; let namespace: String; var entries: [String: Entry] = [:]
    var staging: UUID?; var garbage: UUID?
  }
  struct Snapshot { let messages: [ChatMessage]; let paging: HistoryPagingCheckpoint?; let viewport: HistoryViewportBookmark? }
  struct Candidate: @unchecked Sendable {
    let binding: Binding; let messages: [ChatMessage]; let paging: HistoryPagingCheckpoint?; let viewport: HistoryViewportBookmark?
    let ticket: ReadingRecoveryFence.Ticket
    let stableBody: Bool
  }
  enum Failure: Error { case invalidCache, budget, publication }
  private let root: URL
  private let publicationProbe: @Sendable (String) throws -> Void
  private var blocked = false
  private var anchorCache: [UUID: Set<String>] = [:]
  private(set) var bodyWrites = 0, bodyBytes = 0, indexWrites = 0, indexBytes = 0
  init(directory: URL, publicationProbe: @escaping @Sendable (String) throws -> Void = { _ in }) {
    root = directory.appendingPathComponent("History-v1/ReadingRecovery-v1", isDirectory: true)
    self.publicationProbe = publicationProbe
  }
  private func digest(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
  private func directory(_ namespace: String) -> URL { root.appendingPathComponent(digest(Data(namespace.utf8)), isDirectory: true) }
  private func indexURL(_ namespace: String) -> URL { directory(namespace).appendingPathComponent("index") }
  private func bodyURL(_ id: UUID, _ namespace: String) -> URL { directory(namespace).appendingPathComponent(id.uuidString + ".body") }
  private func read(_ url: URL, limit: Int) throws -> Data? {
    guard FileManager.default.fileExists(atPath: url.path) else { return nil }
    let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
    guard attributes[.type] as? FileAttributeType == .typeRegular,
      let size = attributes[.size] as? NSNumber, size.intValue <= limit else { throw Failure.budget }
    let bytes = try Data(contentsOf: url); guard bytes.count <= limit else { throw Failure.budget }; return bytes
  }
  private func write(_ bytes: Data, _ url: URL) throws {
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    #if os(iOS)
    try bytes.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    #else
    try bytes.write(to: url, options: .atomic)
    #endif
    guard try read(url, limit: bytes.count) == bytes else { throw Failure.publication }
  }
  private func loadIndex(_ namespace: String) throws -> Index {
    guard let bytes = try read(indexURL(namespace), limit: Self.indexLimit) else { return Index(namespace: namespace) }
    let index = try AgentCache.open(bytes, as: Index.self)
    guard index.version == 1, index.namespace == namespace, index.entries.count <= Self.entryLimit else { throw Failure.invalidCache }
    var total = 0
    for (key, entry) in index.entries {
      guard entry.binding.key == key, entry.binding.namespace == namespace, !entry.binding.reader.isEmpty,
        key.utf8.count <= 4096, entry.binding.reader.utf8.count <= 512,
        entry.bytes > 0, entry.bytes <= Self.bodyLimit, entry.count > 0, entry.count <= Self.messageLimit,
        entry.sha256.count == 64 else { throw Failure.invalidCache }
      total += entry.bytes
    }
    guard total <= Self.totalLimit else { throw Failure.budget }
    return index
  }
  private func publish(_ index: Index) throws {
    let bytes = try AgentCache.seal(index)
    guard bytes.count <= Self.indexLimit else { throw Failure.budget }
    try write(bytes, indexURL(index.namespace)); indexWrites += 1; indexBytes += bytes.count
  }
  private func collect(_ index: inout Index) throws {
    let referenced = Set(index.entries.values.map(\.id))
    for id in [index.staging, index.garbage].compactMap({ $0 }) where !referenced.contains(id) {
      let url = bodyURL(id, index.namespace)
      if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
      guard !FileManager.default.fileExists(atPath: url.path) else { throw Failure.publication }
      anchorCache.removeValue(forKey: id)
    }
    if index.staging != nil || index.garbage != nil {
      index.staging = nil; index.garbage = nil; try publish(index)
    }
  }
  private func checked<T>(_ work: () throws -> T) throws -> T {
    do { return try work() } catch { blocked = true; throw error }
  }
  /// Called before any carrier is initialized. Never revive a removed/rebuilt client.
  func reconcile(namespace: String, clients: [Client], target: (Client) -> AgentRuntimeTarget,
    cacheKey: (Client, String) -> String) throws {
    guard Self.admitted, !blocked else { return }
    try checked {
      var index = try loadIndex(namespace); try collect(&index)
      for (key, entry) in index.entries {
        let host = clients.first { $0.id == entry.binding.host && !$0.isDemo && $0.migrationNeedsVerification != true }
        if host == nil || host?.readingScopeId != entry.binding.reader || host.map(target) != entry.binding.target
          || host.map({ cacheKey($0, entry.binding.thread) }) != key {
          index.entries.removeValue(forKey: key); index.garbage = entry.id; try publish(index); try collect(&index)
        }
      }
    }
  }
  private func anchors(_ messages: [ChatMessage]) -> Set<String> {
    let items = ChatTimeline(messages: messages, messageLimit: messages.count).items
    var result = Set(items.map(\.id))
    // A grouped row's first ID can change when a later visible window is chosen.
    for item in items {
      switch item {
      case .tools(_, let values): for message in values { result.insert("tools-" + message.id) }
      case .errors(_, let values): for message in values { result.insert("errors-" + message.id) }
      default: break
      }
    }
    return result
  }
  private func loadBody(_ entry: Entry) throws -> Body {
    guard let bytes = try read(bodyURL(entry.id, entry.binding.namespace), limit: Self.bodyLimit),
      bytes.count == entry.bytes, digest(bytes) == entry.sha256 else { throw Failure.invalidCache }
    let body = try AgentCache.open(bytes, as: Body.self)
    guard body.version == 1, body.id == entry.id, body.binding == entry.binding, body.messages.count == entry.count,
      Set(body.messages.map(\.id)).count == body.messages.count else { throw Failure.invalidCache }
    let values = anchors(body.messages)
    guard entry.viewport.map({ values.contains($0.anchorId) && $0.offset.isFinite && abs($0.offset) <= 100_000 }) ?? true else { throw Failure.invalidCache }
    anchorCache[entry.id] = values
    if anchorCache.count > 2 { anchorCache = [entry.id: values] }
    return body
  }
  func restore(namespace: String, clients: [Client], target: (Client) -> AgentRuntimeTarget,
    cacheKey: (Client, String) -> String) throws -> [String: Snapshot] {
    guard Self.admitted, !blocked else { return [:] }
    return try checked {
      try reconcile(namespace: namespace, clients: clients, target: target, cacheKey: cacheKey)
      let index = try loadIndex(namespace)
      var result: [String: Snapshot] = [:]
      for (key, entry) in index.entries {
        let body = try loadBody(entry)
        result[key] = Snapshot(messages: body.messages, paging: entry.paging, viewport: entry.viewport)
      }
      return result
    }
  }
  func checkpoint(_ candidate: Candidate, fence: ReadingRecoveryFence) throws {
    guard Self.admitted, !blocked, fence.current(candidate.ticket), !candidate.messages.isEmpty else { return }
    try checked {
      let binding = candidate.binding
      var index = try loadIndex(binding.namespace); try collect(&index)
      let previous = index.entries[binding.key]
      if let previous, previous.binding == binding {
        // Viewport work never reads/decrypts/re-encodes a body after memo eviction.
        // The next explicit stable checkpoint can publish the captured messages.
        if !fence.needsBody(candidate.ticket, stable: candidate.stableBody), anchorCache[previous.id] == nil { return }
        if !fence.needsBody(candidate.ticket, stable: candidate.stableBody), candidate.viewport.map({ anchorCache[previous.id]?.contains($0.anchorId) == true }) ?? true {
          var next = previous; next.viewport = candidate.viewport
          let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
          if try encoder.encode(next) != encoder.encode(previous), fence.current(candidate.ticket) {
            index.entries[binding.key] = next; try publish(index)
          }
          return
        }
      }
      guard candidate.messages.count <= Self.messageLimit, Set(candidate.messages.map(\.id)).count == candidate.messages.count,
        previous != nil || index.entries.count < Self.entryLimit else { throw Failure.budget }
      let validAnchors = anchors(candidate.messages)
      guard candidate.viewport.map({ validAnchors.contains($0.anchorId) && $0.offset.isFinite && abs($0.offset) <= 100_000 }) ?? true else { throw Failure.invalidCache }
      let id = UUID()
      let bytes = try AgentCache.seal(Body(version: 1, id: id, binding: binding, messages: candidate.messages))
      guard bytes.count <= Self.bodyLimit,
        index.entries.filter({ $0.key != binding.key }).values.reduce(0, { $0 + $1.bytes }) + bytes.count <= Self.totalLimit else { throw Failure.budget }
      guard fence.current(candidate.ticket) else { return }
      index.staging = id; try publish(index)
      try publicationProbe("before-body")
      try write(bytes, bodyURL(id, binding.namespace)); bodyWrites += 1; bodyBytes += bytes.count
      try publicationProbe("after-body")
      guard fence.current(candidate.ticket) else { try collect(&index); return }
      index.entries[binding.key] = Entry(binding: binding, id: id, sha256: digest(bytes), bytes: bytes.count,
        count: candidate.messages.count, paging: candidate.paging, viewport: candidate.viewport)
      index.staging = nil; index.garbage = previous?.id
      try publicationProbe("before-index")
      try publish(index); try publicationProbe("after-index")
      anchorCache = [id: validAnchors]; fence.published(candidate.ticket); try collect(&index)
    }
  }
  func discard(namespace: String, key: String) throws {
    guard Self.admitted, !blocked else { return }
    try checked {
      var index = try loadIndex(namespace); try collect(&index)
      guard let previous = index.entries.removeValue(forKey: key) else { return }
      index.garbage = previous.id; try publish(index); try collect(&index)
    }
  }
}
