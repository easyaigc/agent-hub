import Foundation
import Darwin

@main struct ReadingIndexSmoke {
  static func elapsed(_ work: () -> Void) -> Double {
    let start = ProcessInfo.processInfo.systemUptime
    work()
    return (ProcessInfo.processInfo.systemUptime - start) * 1000
  }

  static func main() throws {
    precondition(ProcessInfo.processInfo.environment["CODEX_NOTIFICATION_TEST_DIRECTORY"] != nil)
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "synthetic-host",
      workspaceId: "synthetic-workspace", runtimeId: "desktop-runtime")
    func legacy(_ target: AgentRuntimeTarget, _ thread: String?, _ subject: String) -> String {
      ReadingIdentity.legacyKey(agentId: target.agentId, authority: "local", subject: subject,
        hostId: target.hostId, workspaceId: target.workspaceId, runtimeId: target.runtimeId,
        threadId: thread)
    }
    var otherHost = target; otherHost.hostId = "other-host"
    var otherRuntime = target; otherRuntime.runtimeId = "other-runtime"
    var otherAgent = target; otherAgent.agentId = "easywork"
    var keys: Set<String> = [
      legacy(target, nil, "old-account"), legacy(target, "unique", "old-account"),
      legacy(target, "ambiguous", "old-account"), legacy(target, "ambiguous", "new-account"),
      legacy(target, "canonical", "old-account"),
      CodexMachineStorage.readingKey(target, threadId: "canonical"),
      legacy(otherHost, "unique", "old-account"), legacy(otherRuntime, "unique", "old-account"),
      legacy(otherAgent, "unique", "old-account"), "not-base64", "e30=",
      CodexMachineStorage.encoded(["mobile-reading-v1", "codex", NSNull(), NSNull(),
        "synthetic-host", "synthetic-workspace", "desktop-runtime", 123]),
    ]
    let index = CodexMachineStorage.ReadingKeyIndex(keys: keys)
    for scope in [target, otherHost, otherRuntime, otherAgent] {
      for thread: String? in [nil, "unique", "ambiguous", "canonical", "missing"] {
        let canonical = CodexMachineStorage.readingKey(scope, threadId: thread)
        let expected = CodexMachineStorage.preferredKey(canonical,
          candidates: CodexMachineStorage.compatibleReadingKeys(scope, threadId: thread, keys: Array(keys)))
        precondition(index.preferredKey(canonical) == expected,
          "Indexed lookup must preserve legacy ambiguity and complete machine isolation")
      }
    }
    // A caller's legacy canonical scope can differ from its current candidate scope.
    let alternate = CodexMachineStorage.readingKey(otherRuntime, threadId: "canonical")
    let candidate = CodexMachineStorage.readingKey(target, threadId: "canonical")
    precondition(index.preferredKey(alternate, candidateKey: candidate) == alternate)

    var measurements: [[String: Any]] = []
    for count in [50, 250, 1000] {
      keys = Set((0..<count).map { legacy(target, "thread-\($0)", "old-account") })
      let array = Array(keys)
      var before: [Double] = [], after: [Double] = []
      for _ in 0..<3 {
        before.append(elapsed {
          for i in 0..<count {
            let canonical = CodexMachineStorage.readingKey(target, threadId: "thread-\(i)")
            precondition(CodexMachineStorage.preferredKey(canonical,
              candidates: CodexMachineStorage.compatibleReadingKeys(target, threadId: "thread-\(i)", keys: array)) == legacy(target, "thread-\(i)", "old-account"))
          }
        })
        after.append(elapsed {
          // Include index construction, so this compares a complete refresh.
          let projection = CodexMachineStorage.ReadingKeyIndex(keys: keys)
          for i in 0..<count {
            precondition(projection.preferredKey(CodexMachineStorage.readingKey(target,
              threadId: "thread-\(i)")) == legacy(target, "thread-\(i)", "old-account"))
          }
        })
      }
      measurements.append(["threads": count, "beforeMedianMS": before.sorted()[1],
        "afterMedianMS": after.sorted()[1]])
    }
    try MobileUnreadBadge.reset()
    let position = ReadPosition(revision: 1, messageId: "synthetic-reply", contentVersion: String(repeating: "a", count: 64))
    let original = MobileUnreadSnapshot(target: target, threadId: "read-thread", latest: position,
      read: nil, unread: true)
    try MobileUnreadBadge.reconcile(scopes: [target], snapshots: [original])
    try MobileUnreadBadge.readVisible(target: target, threadId: "read-thread", position: position, noticeIds: [])
    let file = TaskNotificationShared.directory.appendingPathComponent("mobile-unread.lock").path
    let descriptor = open(file, O_RDWR)
    precondition(descriptor >= 0 && flock(descriptor, LOCK_EX) == 0)
    let writer = MobileUnreadReconciler()
    let completions = DispatchGroup()
    for i in 0..<50 {
      completions.enter()
      writer.submit(scopes: i == 49 ? [target] : [target, otherHost], snapshots: [original]) { result in
        precondition(!Thread.isMainThread, "Reconciliation completion must run off the UI thread")
        if case .failure = result { preconditionFailure("Background reconciliation failed") }
        completions.leave()
      }
    }
    // Submission remains responsive while a notification extension owns the lock.
    precondition(completions.wait(timeout: .now()) == .timedOut)
    precondition(flock(descriptor, LOCK_UN) == 0); close(descriptor)
    precondition(completions.wait(timeout: .now() + 15) == .success,
      "Coalescing must complete all requests, including background-save callbacks")
    precondition(!MobileUnreadBadge.snapshot().isUnread(target: target, threadId: "read-thread"),
      "An older queued workspace snapshot cannot revive a visible read")
    precondition(MobileUnreadBadge.count == 0)
    print(String(decoding: try JSONSerialization.data(withJSONObject: [
      "status": "passed", "evidence": "macOS-native-synthetic-reading-index",
      "legacyCompatibilityPassed": true, "asyncReconciliationPassed": true,
      "measurements": measurements,
    ], options: [.prettyPrinted, .sortedKeys]), as: UTF8.self))
  }
}
