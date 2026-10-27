import Foundation
import Darwin

@main struct MobileUnreadBadgeSmoke {
  static func main() throws {
    if CommandLine.arguments.contains("receive") {
      let event = try JSONDecoder().decode(TaskNotificationEvent.self, from: FileHandle.standardInput.readDataToEndOfFile())
      print(try MobileUnreadBadge.receive(event)); return
    }
    if CommandLine.arguments.contains("count") { print(MobileUnreadBadge.count); return }
    if CommandLine.arguments.contains("cache-invalidation") {
      try MobileUnreadBadge.reset()
      let target = AgentRuntimeTarget(agentId: "codex", hostId: "cached-host", workspaceId: "space", runtimeId: "runtime")
      try MobileUnreadBadge.reconcile(scopes: [target], snapshots: [])
      let before = MobileUnreadBadge.snapshot()
      precondition(before.count == 0)
      let url = TaskNotificationShared.directory.appendingPathComponent("mobile-unread-v1.json")
      let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
      let event = TaskNotificationEvent(version: 1, id: String(repeating: "f", count: 64), target: target,
        kind: .approval, occurredAt: "2026-10-24T00:00:00Z", threadId: "external-update", requestId: "new-request")
      let process = Process(), input = Pipe()
      process.executableURL = URL(fileURLWithPath: CommandLine.arguments[0])
      process.arguments = ["receive"]; process.standardInput = input; process.standardOutput = Pipe()
      try process.run()
      try input.fileHandleForWriting.write(contentsOf: JSONEncoder().encode(event))
      try input.fileHandleForWriting.close(); process.waitUntilExit()
      precondition(process.terminationStatus == 0)
      var changed = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
      changed["generation"] = Int(before.revision)!
      try JSONSerialization.data(withJSONObject: changed).write(to: url, options: .atomic)
      try FileManager.default.setAttributes([.modificationDate: attributes[.modificationDate]!], ofItemAtPath: url.path)
      let retained = try Data(contentsOf: url)
      // A live process must see an extension's replacement even when revision
      // and modification time are unchanged, and readers must not rewrite it.
      for _ in 0..<100 {
        let snapshot = MobileUnreadBadge.snapshot()
        precondition(snapshot.count == 1 && snapshot.revision == before.revision)
        precondition(snapshot.isUnread(target: target, threadId: "external-update"))
        var anotherRuntime = target; anotherRuntime.runtimeId = "another-runtime"
        precondition(!snapshot.isUnread(target: anotherRuntime, threadId: "external-update"))
      }
      let afterReads = try Data(contentsOf: url)
      precondition(afterReads == retained)
      print("Warm ledger cache: cross-process replacement, unchanged generation/mtime, runtime isolation and 100 read-only projections passed")
      return
    }
    if CommandLine.arguments.contains("locked-visible") {
      let target = AgentRuntimeTarget(agentId: "codex", hostId: "host-a", workspaceId: "space", runtimeId: "desktop-runtime")
      var other = target; other.hostId = "host-b"
      let head = ReadPosition(revision: 100, messageId: "async-read", contentVersion: String(repeating: "a", count: 64))
      try MobileUnreadBadge.reconcile(scopes: [target, other], snapshots: [
        MobileUnreadSnapshot(target: target, threadId: "async-read", latest: head, read: nil, unread: true),
      ])
      let path = TaskNotificationShared.directory.appendingPathComponent("mobile-unread.lock").path
      let fd = open(path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
      precondition(fd >= 0 && flock(fd, LOCK_EX) == 0)
      // A notification extension outlives the old two-second UI lock deadline.
      DispatchQueue.global().asyncAfter(deadline: .now() + 2.4) { flock(fd, LOCK_UN); close(fd) }
      let writer = MobileUnreadReconciler(), done = DispatchSemaphore(value: 0)
      let start = ProcessInfo.processInfo.systemUptime
      for revision in 1...100 {
        let position = ReadPosition(revision: revision, messageId: "async-read", contentVersion: head.contentVersion)
        writer.submitVisible(MobileVisibleRead(target: target, threadId: "async-read", position: position, noticeIds: [], legacyReply: nil)) { result in
          if case .failure(let error) = result { fatalError("Async visible read failed: \(error)") }
          done.signal()
        }
      }
      precondition(ProcessInfo.processInfo.systemUptime - start < 0.1, "Visible reads must not wait for the file lock")
      for _ in 0..<100 { precondition(done.wait(timeout: .now() + 8) == .success) }
      precondition(!MobileUnreadBadge.snapshot().isUnread(target: target, threadId: "async-read"))
      // A stale completion/read cannot roll back the newest committed position.
      try MobileUnreadBadge.readVisible(target: target, threadId: "async-read", position: ReadPosition(revision: 1, messageId: "async-read", contentVersion: "old"), noticeIds: [])
      precondition(!MobileUnreadBadge.snapshot().isUnread(target: target, threadId: "async-read"))
      // Corrupt data is reported accurately and retained byte-for-byte, never reset.
      let ledgerURL = TaskNotificationShared.directory.appendingPathComponent("mobile-unread-v1.json")
      let original = try Data(contentsOf: ledgerURL), corrupt = Data("{invalid-ledger".utf8)
      defer { try? original.write(to: ledgerURL, options: .atomic) }
      try corrupt.write(to: ledgerURL, options: .atomic)
      do {
        try MobileUnreadBadge.readVisible(target: target, threadId: "async-read", position: head, noticeIds: [])
        preconditionFailure("Corrupt ledger must fail explicitly")
      } catch let error as MobileUnreadPersistenceError {
        precondition(error.phase == "decode" && !error.lockBusy)
        let retained = try Data(contentsOf: ledgerURL)
        precondition(retained == corrupt)
        precondition(!error.description.contains("存储空间"))
      }
      print("Background visible reads: lock retry, 100 coalesced completions, monotonic read and non-destructive decode failure passed")
      return
    }
    if CommandLine.arguments.contains("locked-read") {
      let path = TaskNotificationShared.directory.appendingPathComponent("mobile-unread.lock").path
      let fd = open(path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
      precondition(fd >= 0 && flock(fd, LOCK_EX) == 0)
      defer { flock(fd, LOCK_UN); close(fd) }
      // An existing app/extension writer must not block a read or trigger a migration write.
      let start = ProcessInfo.processInfo.systemUptime
      precondition(MobileUnreadBadge.snapshot().count == 26)
      precondition(ProcessInfo.processInfo.systemUptime - start < 1)
      return
    }
    try MobileUnreadBadge.reset()
    let a = AgentRuntimeTarget(agentId: "codex", hostId: "host-a", workspaceId: "space", runtimeId: "desktop-runtime", account: AgentAccount(authority: "local", subject: "account-a"))
    var b = a; b.hostId = "host-b"
    var otherAccount = a; otherAccount.account?.subject = "account-b"
    // A large directory is visited by successive event/history refreshes. Only
    // the first visit encodes identities; changing the actual runtime invalidates them.
    var memo = CodexMachineStorage.ReadingKeyMemo(), encoded = 0
    for target in [a, a, otherAccount] {
      for row in 0..<10_000 {
        let value = memo.value(hostId: target.hostId, target: target, candidate: target, threadId: String(row)) {
          encoded += 1
          let key = CodexMachineStorage.readingKey(target, threadId: String(row))
          return .init(canonical: key, candidate: key, selected: key)
        }
        if row == 0 { precondition(value.canonical == CodexMachineStorage.readingKey(a, threadId: "0")) }
      }
    }
    precondition(encoded == 10_000, "Polls and historical Codex account metadata must reuse machine reading keys")
    var changedRuntime = a; changedRuntime.runtimeId = "another-runtime"
    let changedKey = memo.value(hostId: a.hostId, target: changedRuntime, candidate: changedRuntime, threadId: "0") {
      encoded += 1
      let key = CodexMachineStorage.readingKey(changedRuntime, threadId: "0")
      return .init(canonical: key, candidate: key, selected: key)
    }
    precondition(encoded == 10_001 && changedKey.canonical != CodexMachineStorage.readingKey(a, threadId: "0"))
    let first = ReadPosition(revision: 1, messageId: "reply", contentVersion: String(repeating: "a", count: 64))
    let second = ReadPosition(revision: 2, messageId: "reply", contentVersion: String(repeating: "b", count: 64))
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [
      MobileUnreadSnapshot(target: a, threadId: "same-thread", latest: first, read: nil, unread: true),
      MobileUnreadSnapshot(target: b, threadId: "same-thread", latest: first, read: nil, unread: true),
    ])
    precondition(MobileUnreadBadge.count == 2)
    let projection = MobileUnreadBadge.snapshot()
    precondition(projection.count == 2 && projection.isUnread(target: a, threadId: "same-thread"))
    precondition(projection.count(for: a) == 1 && projection.count(for: b) == 1)
    precondition(projection.count(for: otherAccount) == 1)
    precondition(projection.isUnread(target: otherAccount, threadId: "same-thread"))
    // The same chat in Recent/project views, duplicate routes and snapshots count once.
    try MobileUnreadBadge.reconcile(scopes: [a, b, a], snapshots: [
      MobileUnreadSnapshot(target: a, threadId: "same-thread", latest: first, read: nil, unread: true),
    ])
    precondition(MobileUnreadBadge.count == 2)
    try MobileUnreadBadge.readVisible(target: a, threadId: "same-thread", position: first, noticeIds: [])
    precondition(MobileUnreadBadge.count == 1)
    // UI projections remain immutable; a fresh projection observes committed writes.
    precondition(projection.isUnread(target: a, threadId: "same-thread"))
    let updatedProjection = MobileUnreadBadge.snapshot()
    precondition(updatedProjection.count == 1 && !updatedProjection.isUnread(target: a, threadId: "same-thread"))
    precondition(updatedProjection.count(for: a) == 0 && updatedProjection.count(for: b) == 1)
    precondition(projection.count(for: a) == 1)
    precondition(updatedProjection.revision != projection.revision)
    // A pending mobile acknowledgement and desktop flag changes cannot revive read content.
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [
      MobileUnreadSnapshot(target: a, threadId: "same-thread", latest: first, read: first, unread: true),
    ])
    precondition(MobileUnreadBadge.count == 1)
    var event = TaskNotificationEvent(version: 1, id: String(repeating: "c", count: 64), target: a,
      kind: .completed, occurredAt: "2026-10-11T00:00:00Z", threadId: "same-thread", runId: "run", readTarget: a, readPosition: first)
    try MobileUnreadBadge.receive(event); precondition(MobileUnreadBadge.count == 1)
    event.id = String(repeating: "d", count: 64); event.readPosition = second
    try MobileUnreadBadge.receive(event); try MobileUnreadBadge.receive(event)
    precondition(MobileUnreadBadge.count == 2)
    let batchedVersion = ReadPosition.contentVersion(messageId: "batch-old-host", text: "旧连接正文", status: "completed")
    let batched = MobileUnreadSnapshot(target: a, threadId: "batched-old-host", latest: nil, read: nil,
      unread: false, localReply: ("batch-old-host", batchedVersion))
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [batched])
    let batchedPosition = MobileUnreadBadge.localPosition(target: a, threadId: "batched-old-host",
      messageId: "batch-old-host", text: "旧连接正文", status: "completed")
    precondition(batchedPosition.revision == 1)
    try MobileUnreadBadge.readVisible(target: a, threadId: "batched-old-host", position: batchedPosition, noticeIds: [])
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [batched])
    precondition(!MobileUnreadBadge.snapshot().isUnread(target: a, threadId: "batched-old-host"))
    var newerBatch = batched
    newerBatch.localReply = ("batch-old-host", ReadPosition.contentVersion(messageId: "batch-old-host", text: "同 ID 新正文", status: "completed"))
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [newerBatch])
    precondition(MobileUnreadBadge.snapshot().isUnread(target: a, threadId: "batched-old-host"))
    // Keep the remaining count scenarios independent of the extra legacy fixture.
    let latestBatch = MobileUnreadBadge.localPosition(target: a, threadId: "batched-old-host",
      messageId: "batch-old-host", text: "同 ID 新正文", status: "completed")
    try MobileUnreadBadge.readVisible(target: a, threadId: "batched-old-host", position: latestBatch, noticeIds: [])
    // Old workspace responses and old reads cannot clear a newer push version.
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [
      MobileUnreadSnapshot(target: a, threadId: "same-thread", latest: first, read: first, unread: false),
    ])
    try MobileUnreadBadge.readVisible(target: a, threadId: "same-thread", position: first, noticeIds: [])
    precondition(MobileUnreadBadge.count == 2)
    try MobileUnreadBadge.readVisible(target: a, threadId: "same-thread", position: second, noticeIds: [])
    event.readPosition = first; try MobileUnreadBadge.receive(event)
    precondition(MobileUnreadBadge.count == 1)
    // Reading a request removes unread status while its actionable notification remains stored.
    event.kind = .approval; event.readPosition = nil; event.requestId = "approval-a"
    event.id = String(repeating: "e", count: 64)
    try TaskNotificationShared.remember(event); try MobileUnreadBadge.receive(event)
    var question = event; question.kind = .question; question.id = String(repeating: "f", count: 64)
    try MobileUnreadBadge.receive(question)
    precondition(MobileUnreadBadge.count == 2)
    try MobileUnreadBadge.readVisible(target: a, threadId: "same-thread", position: second, noticeIds: [event.id, question.id])
    try MobileUnreadBadge.receive(event)
    precondition(MobileUnreadBadge.count == 1)
    precondition(TaskNotificationShared.notices.contains { $0.id == event.id && $0.kind.actionable })
    // Removing a computer filters only its scope; offline snapshots preserve its count.
    try MobileUnreadBadge.reconcile(scopes: [a], snapshots: [])
    precondition(MobileUnreadBadge.count == 0)
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [])
    precondition(MobileUnreadBadge.count == 1)
    try MobileUnreadBadge.reconcile(scopes: [otherAccount, b], snapshots: [
      MobileUnreadSnapshot(target: otherAccount, threadId: "same-thread", latest: first, read: nil, unread: true),
    ])
    precondition(MobileUnreadBadge.count == 1)
    try MobileUnreadBadge.readVisible(target: otherAccount, threadId: "same-thread", position: first, noticeIds: [])
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [])
    precondition(MobileUnreadBadge.count == 1)
    // Historical Codex account metadata cannot revive read content; mismatched machines and host-wide notices are rejected.
    event.target = otherAccount; event.readTarget = otherAccount
    try MobileUnreadBadge.receive(event); precondition(MobileUnreadBadge.count == 1)
    event.target = a; event.readTarget = b
    precondition(!TaskNotificationValidation.event(event))
    try MobileUnreadBadge.receive(event); precondition(MobileUnreadBadge.count == 1)
    event.readTarget = a; event.threadId = nil; event.kind = .connection
    try MobileUnreadBadge.receive(event); precondition(MobileUnreadBadge.count == 1)
    let legacy = MobileUnreadBadge.localPosition(target: a, threadId: "legacy", messageId: "old", text: "旧回复", status: "completed")
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [MobileUnreadSnapshot(target: a, threadId: "legacy", latest: legacy, read: nil, unread: false)])
    try MobileUnreadBadge.readVisible(target: a, threadId: "legacy", position: nil, noticeIds: [], legacyReply: ("old", "旧回复", "completed"))
    let changed = MobileUnreadBadge.localPosition(target: a, threadId: "legacy", messageId: "old", text: "同 ID 的新回复", status: "completed")
    try MobileUnreadBadge.reconcile(scopes: [a, b], snapshots: [MobileUnreadSnapshot(target: a, threadId: "legacy", latest: changed, read: nil, unread: false)])
    precondition(MobileUnreadBadge.count == 2)
    print("Mobile unread ledger: cross-host/account isolation, dedup, read/late push, pending request, offline and same-ID content passed")
  }
}
