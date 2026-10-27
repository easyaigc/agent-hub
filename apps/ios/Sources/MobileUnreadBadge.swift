import CryptoKit
import Foundation
import Darwin

// The app supplies a background execution lease. Extensions use their system-owned
// request lifetime. Reads never acquire a file lock or write migration results.
final class MobileUnreadTransactionLease: @unchecked Sendable {
  private let lock = NSLock()
  private var cancelled = false
  private let completion: @Sendable () -> Void
  init(completion: @escaping @Sendable () -> Void = {}) { self.completion = completion }
  func cancel() { lock.lock(); cancelled = true; lock.unlock() }
  func check() throws {
    lock.lock(); let stop = cancelled; lock.unlock()
    if stop { throw CocoaError(.userCancelled) }
  }
  func finish() { completion() }
}

enum MobileUnreadTransactionLifetime {
  private final class Storage: @unchecked Sendable {
    let lock = NSLock()
    var factory: (@Sendable () throws -> MobileUnreadTransactionLease)?
  }
  private static let storage = Storage()
  static func install(_ value: @escaping @Sendable () throws -> MobileUnreadTransactionLease) {
    storage.lock.lock(); storage.factory = value; storage.lock.unlock()
  }
  static func acquire() throws -> MobileUnreadTransactionLease {
    storage.lock.lock(); let create = storage.factory; storage.lock.unlock()
    return try create?() ?? MobileUnreadTransactionLease()
  }
}

struct MobileUnreadSnapshot: Sendable {
  var target: AgentRuntimeTarget
  var threadId: String
  var latest: ReadPosition?
  var read: ReadPosition?
  var unread: Bool
  var localReply: (messageId: String, contentVersion: String)? = nil
}

struct MobileVisibleRead: Sendable {
  var target: AgentRuntimeTarget
  var threadId: String
  var position: ReadPosition?
  var noticeIds: Set<String>
  var legacyReply: (messageId: String, contentVersion: String)?
}

struct MobileUnreadPersistenceError: Error {
  var phase: String
  var underlying: NSError
  var lockBusy: Bool { phase == "lock" && underlying.domain == NSPOSIXErrorDomain && underlying.code == Int(EWOULDBLOCK) }
  var description: String {
    if lockBusy { return L10n.tr("手机阅读状态暂未保存：通知更新繁忙，将在下次刷新时重试。") }
    if underlying.domain == NSCocoaErrorDomain && underlying.code == NSUserCancelledError {
      return L10n.tr("手机阅读状态暂未保存：后台保存已中断，将在回到 App 后重试。")
    }
    if underlying.domain == NSCocoaErrorDomain && underlying.code == NSFileWriteOutOfSpaceError
      || underlying.domain == NSPOSIXErrorDomain && underlying.code == Int(ENOSPC) {
      return L10n.tr("无法保存手机阅读状态：设备存储空间不足。")
    }
    return L10n.tr("无法保存手机阅读状态（\(phase)，\(underlying.domain) \(underlying.code)）。已有记录已保留，请稍后重试。")
  }
}

// Counts and error codes only. Never export ledger rows, targets, paths or text.
enum MobileUnreadPersistenceDiagnostics {
  private final class Storage: @unchecked Sendable {
    let queue = DispatchQueue(label: "agent-hub.mobile-unread-diagnostics", qos: .utility)
    var successes = 0, failures = 0
    var errors: [[String: Any]] = []
  }
  private static let storage = Storage()
  static func record(_ operation: String, result: Result<Void, Error>) {
    storage.queue.async {
      let status: String
      switch result {
      case .success: storage.successes += 1; status = "saved"
      case .failure(let error):
        storage.failures += 1; status = "failed"
        let failure = error as? MobileUnreadPersistenceError
        let native = failure?.underlying ?? error as NSError
        storage.errors.append(["at": Date().timeIntervalSince1970, "operation": operation,
          "phase": failure?.phase ?? "unknown", "domain": native.domain, "code": native.code])
        storage.errors = Array(storage.errors.suffix(20))
      }
      guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return }
      let directory = support.appendingPathComponent("CodexManage")
      try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      let value: [String: Any] = ["at": Date().timeIntervalSince1970, "operation": operation,
        "status": status, "successes": storage.successes, "failures": storage.failures, "errors": storage.errors]
      if let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) {
        try? data.write(to: directory.appendingPathComponent("unread-persistence-report.json"), options: .atomic)
      }
    }
  }
}

/// All app-owned ledger writes share one background writer. Visible reads must
/// not race workspace reconciliation or block the UI for the extension lock.
final class MobileUnreadReconciler: @unchecked Sendable {
  private struct Request {
    var scopes: [AgentRuntimeTarget]
    var snapshots: [MobileUnreadSnapshot]
    var completions: [@Sendable (Result<Void, Error>) -> Void]
  }
  private let lock = NSLock()
  private let queue = DispatchQueue(label: "agent-hub.mobile-unread-reconcile", qos: .utility)
  private var pending: Request?
  private struct VisibleRequest {
    var value: MobileVisibleRead
    var completions: [@Sendable (Result<Void, Error>) -> Void]
  }
  private var visible: [VisibleRequest] = []
  private var running = false

  func submit(scopes: [AgentRuntimeTarget], snapshots: [MobileUnreadSnapshot],
    completion: @escaping @Sendable (Result<Void, Error>) -> Void) {
    lock.lock()
    var callbacks = pending?.completions ?? []
    callbacks.append(completion)
    pending = Request(scopes: scopes, snapshots: snapshots, completions: callbacks)
    let start = !running
    running = true
    lock.unlock()
    if start { queue.async { self.drain() } }
  }

  func submitVisible(_ value: MobileVisibleRead, completion: @escaping @Sendable (Result<Void, Error>) -> Void) {
    lock.lock()
    if let index = visible.firstIndex(where: { $0.value.target == value.target && $0.value.threadId == value.threadId }) {
      var merged = value
      let old = visible[index].value
      if let position = old.position, position.revision > (value.position?.revision ?? 0) { merged.position = position }
      merged.noticeIds.formUnion(old.noticeIds)
      visible[index].value = merged
      visible[index].completions.append(completion)
    } else { visible.append(VisibleRequest(value: value, completions: [completion])) }
    let start = !running
    running = true
    lock.unlock()
    if start { queue.async { self.drain() } }
  }

  private func save(_ action: () throws -> Void) -> Result<Void, Error> {
    // Only retry a local file-lock conflict; no remote command or input is retried.
    for attempt in 0..<3 {
      do { try action(); return .success(()) }
      catch let error as MobileUnreadPersistenceError where error.lockBusy && attempt < 2 {
        Foundation.Thread.sleep(forTimeInterval: 0.1)
      } catch { return .failure(error) }
    }
    return .failure(CocoaError(.fileWriteUnknown))
  }

  private func drain() {
    while true {
      lock.lock()
      guard pending != nil || !visible.isEmpty else { running = false; lock.unlock(); return }
      let request = pending, reads = visible
      pending = nil
      visible = []
      lock.unlock()
      if let request {
        let result = save { _ = try MobileUnreadBadge.reconcile(scopes: request.scopes, snapshots: request.snapshots) }
        MobileUnreadPersistenceDiagnostics.record("workspace", result: result)
        for completion in request.completions { completion(result) }
      }
      for read in reads {
        let result = save { _ = try MobileUnreadBadge.readVisible(read.value) }
        MobileUnreadPersistenceDiagnostics.record("visible", result: result)
        for completion in read.completions { completion(result) }
      }
    }
  }
}

/// One device-owned ledger, shared with the notification extension. Never stores message text.
enum MobileUnreadBadge {
  /// Immutable UI projection. Rendering a row never locks or reads the shared file.
  struct Snapshot: Equatable, Sendable {
    var count = 0
    var revision = "0"
    fileprivate var unreadKeys: Set<String> = []
    fileprivate var countsByTarget: [String: Int] = [:]

    func count(for target: AgentRuntimeTarget) -> Int { countsByTarget[MobileUnreadBadge.key(target, ""), default: 0] }

    func isUnread(target: AgentRuntimeTarget, threadId: String) -> Bool {
      unreadKeys.contains(MobileUnreadBadge.key(target, threadId))
    }
  }
  struct Notice: Codable, Equatable {
    var id: String
    var kind: TaskNoticeKind
    var runId: String?
    var requestId: String?
    var operationId: String?
    var seen = false
  }
  private struct Thread: Codable, Equatable {
    var target: AgentRuntimeTarget
    var threadId: String
    var latest: ReadPosition?
    var read: ReadPosition?
    var replyUnread = false
    var notices: [String: Notice] = [:]
    var unread: Bool { replyUnread || notices.values.contains { !$0.seen } }
  }
  private struct Ledger: Codable, Equatable {
    var version = 1
    var generation = 0
    var scopes: [AgentRuntimeTarget] = []
    var threads: [String: Thread] = [:]
    var count: Int { threads.filter { id, row in
      scopes.contains(row.target) && row.unread && (row.target.agentId != "codex" || id == MobileUnreadBadge.key(row.target, row.threadId))
    }.count }
  }
  private static let filename = "mobile-unread-v1.json"
  private struct StorageKey: Hashable {
    var agentId: String, hostId: String, workspaceId: String, runtimeId: String, threadId: String
    var authority: String?, subject: String?
  }
  private final class KeyCache: @unchecked Sendable {
    let lock = NSLock()
    var values: [StorageKey: String] = [:]
  }
  private static let keyCache = KeyCache()
  private static func key(_ target: AgentRuntimeTarget, _ threadId: String) -> String {
    let input = StorageKey(agentId: target.agentId, hostId: target.hostId,
      workspaceId: target.workspaceId, runtimeId: target.runtimeId, threadId: threadId,
      authority: target.account?.authority, subject: target.account?.subject)
    keyCache.lock.lock()
    let cached = keyCache.values[input]
    keyCache.lock.unlock()
    if let cached { return cached }
    let identity = ReadingIdentity.key(agentId: target.agentId, authority: target.account?.authority,
      subject: target.account?.subject, hostId: target.hostId, workspaceId: target.workspaceId,
      runtimeId: target.runtimeId, threadId: threadId)
    // Formatting each byte through NSString was a hot path during migration
    // and counting. Preserve exactly the same lowercase SHA-256 storage key.
    let digits = Array("0123456789abcdef".utf8)
    let bytes = SHA256.hash(data: Data(identity.utf8)).flatMap {
      [digits[Int($0 >> 4)], digits[Int($0 & 15)]]
    }
    let result = String(decoding: bytes, as: UTF8.self)
    keyCache.lock.lock()
    if keyCache.values.count >= 16_384 { keyCache.values.removeAll(keepingCapacity: true) }
    keyCache.values[input] = result
    keyCache.lock.unlock()
    return result
  }
  // Read-view migration only: preserve every old ledger row, materialize one
  // machine projection, and count that projection once across account metadata.
  private static func normalizeCodexRows(_ ledger: inout Ledger) {
    let groups = Dictionary(grouping: ledger.threads.values.filter { $0.target.agentId == "codex" }) { key($0.target, $0.threadId) }
    for (id, rows) in groups where ledger.threads[id] == nil {
      let ordered = rows.sorted {
        if ($0.latest?.revision ?? 0) != ($1.latest?.revision ?? 0) { return ($0.latest?.revision ?? 0) > ($1.latest?.revision ?? 0) }
        return ($0.latest?.contentVersion ?? "") < ($1.latest?.contentVersion ?? "")
      }
      guard var combined = ordered.first else { continue }
      combined.replyUnread = rows.contains { $0.replyUnread }
      for row in rows {
        if let latest = row.latest, latest.revision > (combined.latest?.revision ?? 0) { combined.latest = latest }
        if let read = row.read, read.revision > (combined.read?.revision ?? 0) { combined.read = read }
        for (noticeId, notice) in row.notices {
          if combined.notices[noticeId] == nil { combined.notices[noticeId] = notice }
          else if notice.seen { combined.notices[noticeId]?.seen = true }
        }
      }
      let latestHeads = rows.compactMap { $0.latest }.filter { $0.revision == combined.latest?.revision }
      let ambiguousHead = latestHeads.contains { $0 != combined.latest }
      if ambiguousHead { combined.replyUnread = true }
      else if let latest = combined.latest, let read = combined.read, read == latest || read.revision > latest.revision { combined.replyUnread = false }
      ledger.threads[id] = combined
    }
  }
  private struct LoadedLedger {
    var stored: Ledger
    var view: Ledger
  }
  private final class LedgerCache: @unchecked Sendable {
    let lock = NSLock()
    var url: URL?
    var bytes: Data?
    var value: LoadedLedger?
  }
  private static let ledgerCache = LedgerCache()
  private static func loadLedger() throws -> LoadedLedger {
    do {
      let url = TaskNotificationShared.directory.appendingPathComponent(filename)
      // Read the actual atomic file on every access. File timestamps and ledger
      // generation alone cannot detect another process replacing its contents.
      let bytes = try Data(contentsOf: url)
      ledgerCache.lock.lock(); defer { ledgerCache.lock.unlock() }
      if ledgerCache.url == url, ledgerCache.bytes == bytes, let value = ledgerCache.value { return value }
      let stored = try JSONDecoder().decode(Ledger.self, from: bytes)
      var view = stored
      normalizeCodexRows(&view)
      let value = LoadedLedger(stored: stored, view: view)
      ledgerCache.url = url; ledgerCache.bytes = bytes; ledgerCache.value = value
      return value
    } catch {
      let failure = error as NSError
      if failure.domain == NSCocoaErrorDomain && failure.code == NSFileReadNoSuchFileError { return LoadedLedger(stored: Ledger(), view: Ledger()) }
      throw error
    }
  }
  private static func readOnly<T>(_ read: (Ledger) -> T) throws -> T {
    return read(try loadLedger().view)
  }
  private static func transaction<T>(_ change: (inout Ledger) throws -> T) throws -> T {
    func stage<V>(_ phase: String, _ action: () throws -> V) throws -> V {
      do { return try action() }
      catch { throw MobileUnreadPersistenceError(phase: phase, underlying: error as NSError) }
    }
    let lifetime = try stage("lifetime") { try MobileUnreadTransactionLifetime.acquire() }
    defer { lifetime.finish() }
    try stage("lifetime") { try lifetime.check() }
    let directory = TaskNotificationShared.directory
    try stage("directory") { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
    let descriptor = open(directory.appendingPathComponent("mobile-unread.lock").path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
    guard descriptor >= 0 else { throw MobileUnreadPersistenceError(phase: "open-lock", underlying: NSError(domain: NSPOSIXErrorDomain, code: Int(errno))) }
    defer { close(descriptor) }
    let deadline = ProcessInfo.processInfo.systemUptime + 2
    while flock(descriptor, LOCK_EX | LOCK_NB) != 0 {
      let lockError = errno
      try stage("lifetime") { try lifetime.check() }
      guard lockError == EWOULDBLOCK, ProcessInfo.processInfo.systemUptime < deadline else {
        throw MobileUnreadPersistenceError(phase: "lock", underlying: NSError(domain: NSPOSIXErrorDomain, code: Int(lockError)))
      }
      Foundation.Thread.sleep(forTimeInterval: 0.005)
    }
    defer { flock(descriptor, LOCK_UN) }
    let loaded = try stage("decode") { try loadLedger() }
    let before = loaded.stored
    var ledger = loaded.view
    let result = try change(&ledger)
    try stage("lifetime") { try lifetime.check() }
    if before != ledger {
      ledger.generation = before.generation + 1
      try stage("write") { try TaskNotificationShared.write(ledger, name: filename) }
    }
    return result
  }
  static var count: Int { (try? readOnly { $0.count }) ?? 0 }
  static var revision: String { String((try? readOnly { $0.generation }) ?? 0) }
  static func snapshot() -> Snapshot {
    (try? readOnly { ledger in
      let unread = ledger.threads.filter { id, row in ledger.scopes.contains(row.target) && row.unread && (row.target.agentId != "codex" || id == key(row.target, row.threadId)) }
      return Snapshot(count: unread.count, revision: String(ledger.generation),
        unreadKeys: Set(unread.keys),
        countsByTarget: unread.values.reduce(into: [:]) { $0[key($1.target, ""), default: 0] += 1 })
    }) ?? Snapshot()
  }
  static func localPosition(target: AgentRuntimeTarget, threadId: String, messageId: String, text: String, status: String) -> ReadPosition {
    let version = ReadPosition.contentVersion(messageId: messageId, text: text, status: status)
    let latest = try? readOnly { $0.threads[key(target, threadId)]?.latest }
    return ReadPosition(revision: Int(SharedReadingPolicy.nextRevision(previousRevision: Int64(latest?.revision ?? 0), sameVersion: latest?.contentVersion == version)),
      messageId: messageId, contentVersion: version)
  }
  static func isUnread(target: AgentRuntimeTarget, threadId: String) -> Bool {
    (try? readOnly { $0.threads[key(target, threadId)]?.unread ?? false }) ?? false
  }
  static func notices(target: AgentRuntimeTarget, threadId: String) -> [Notice] {
    (try? readOnly { Array($0.threads[key(target, threadId)]?.notices.values ?? [:].values).filter { !$0.seen } }) ?? []
  }
  @discardableResult static func reconcile(scopes: [AgentRuntimeTarget], snapshots: [MobileUnreadSnapshot]) throws -> Int {
    try transaction { ledger in
      ledger.scopes = scopes.reduce(into: []) { if !$0.contains($1) { $0.append($1) } }
      for value in snapshots where ledger.scopes.contains(value.target) {
        let id = key(value.target, value.threadId)
        var row = ledger.threads[id] ?? Thread(target: value.target, threadId: value.threadId, replyUnread: value.unread)
        // Resolve old-host content versions inside this same transaction, rather
        // than rereading the whole ledger for each cached conversation.
        var latest = value.latest
        if latest == nil, let reply = value.localReply {
          latest = ReadPosition(revision: Int(SharedReadingPolicy.nextRevision(previousRevision: Int64(row.latest?.revision ?? 0), sameVersion: row.latest?.contentVersion == reply.contentVersion)),
            messageId: reply.messageId, contentVersion: reply.contentVersion)
        }
        let accepted = latest.map { $0.revision >= (row.latest?.revision ?? 0) } ?? false
        let hadLatest = row.latest != nil
        let changed = latest != row.latest
        if accepted { row.latest = latest }
        if let read = value.read, read.revision >= (row.read?.revision ?? 0) { row.read = read }
        let settled = row.latest.map { latest in row.read.map { $0 == latest || $0.revision > latest.revision } ?? false } ?? false
        row.replyUnread = SharedReadingPolicy.reconciledUnread(previousUnread: row.replyUnread,
          hadLatest: hadLatest, latestAccepted: accepted, latestChanged: changed,
          sourceUnread: value.unread, readSettles: settled)
        ledger.threads[id] = row
      }
      return ledger.count
    }
  }
  /// Notification identity deduplicates deliveries; reply versions prevent late pushes reviving read content.
  @discardableResult static func receive(_ event: TaskNotificationEvent) throws -> Int {
    try transaction { ledger in
      let target = event.readTarget ?? event.target
      guard TaskNotificationValidation.event(event), ledger.scopes.contains(target),
        let threadId = event.threadId, event.kind != .connection else { return ledger.count }
      let id = key(target, threadId)
      var row = ledger.threads[id] ?? Thread(target: target, threadId: threadId)
      if let position = event.readPosition {
        if position.revision > (row.latest?.revision ?? 0) {
          row.latest = position
          row.replyUnread = SharedReadingPolicy.newPushUnread(readSame: row.read == position, readRevision: Int64(row.read?.revision ?? 0), latestRevision: Int64(position.revision))
        }
      } else if row.notices[event.id] == nil {
        row.notices[event.id] = Notice(id: event.id, kind: event.kind, runId: event.runId,
          requestId: event.requestId, operationId: event.operationId)
      }
      ledger.threads[id] = row
      return ledger.count
    }
  }
  @discardableResult static func readVisible(target: AgentRuntimeTarget, threadId: String,
    position: ReadPosition?, noticeIds: Set<String>, legacyReply: (id: String, text: String, status: String)? = nil) throws -> Int {
    try readVisible(MobileVisibleRead(target: target, threadId: threadId, position: position, noticeIds: noticeIds,
      legacyReply: legacyReply.map { ($0.id, ReadPosition.contentVersion(messageId: $0.id, text: $0.text, status: $0.status)) }))
  }
  @discardableResult static func readVisible(_ value: MobileVisibleRead) throws -> Int {
    try transaction { ledger in
      guard ledger.scopes.contains(value.target) else { return ledger.count }
      let id = key(value.target, value.threadId)
      var row = ledger.threads[id] ?? Thread(target: value.target, threadId: value.threadId)
      var position = value.position
      if let reply = value.legacyReply {
        let version = reply.contentVersion
        position = ReadPosition(revision: Int(SharedReadingPolicy.nextRevision(previousRevision: Int64(row.latest?.revision ?? 0), sameVersion: row.latest?.contentVersion == version)),
          messageId: reply.messageId, contentVersion: version)
        row.latest = position
      }
      if let position, position.revision >= (row.read?.revision ?? 0) {
        row.read = position
        if row.latest == position { row.replyUnread = false }
      }
      for noticeId in value.noticeIds { row.notices[noticeId]?.seen = true }
      ledger.threads[id] = row
      return ledger.count
    }
  }
  static func reset() throws { try transaction { $0 = Ledger() } }
}
