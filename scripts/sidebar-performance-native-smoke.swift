import Foundation

@main struct SidebarPerformanceSmoke {
  static func measure(_ body: () -> Void) -> Double {
    let start = ProcessInfo.processInfo.systemUptime
    body()
    return (ProcessInfo.processInfo.systemUptime - start) * 1000
  }
  static func main() throws {
    precondition(ProcessInfo.processInfo.environment["CODEX_NOTIFICATION_TEST_DIRECTORY"] != nil,
      "Performance evidence must use an isolated synthetic ledger")
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "synthetic-host",
      workspaceId: "synthetic-workspace", runtimeId: "desktop")
    var results: [[String: Any]] = []
    for count in [50, 250, 1000] {
      try MobileUnreadBadge.reset()
      let position = ReadPosition(revision: 1, messageId: "synthetic-reply",
        contentVersion: String(repeating: "a", count: 64))
      let inputs = (0..<count).map {
        MobileUnreadSnapshot(target: target, threadId: "synthetic-thread-\($0)",
          latest: position, read: nil, unread: true)
      }
      try MobileUnreadBadge.reconcile(scopes: [target], snapshots: inputs)
      let snapshot = MobileUnreadBadge.snapshot()
      precondition(snapshot.count == count)
      var original: [Double] = [], optimized: [Double] = []
      for _ in 0..<5 {
        original.append(measure { for index in 0..<25 {
          precondition(MobileUnreadBadge.isUnread(target: target, threadId: "synthetic-thread-\(index)"))
        } })
        optimized.append(measure { for index in 0..<25 {
          precondition(snapshot.isUnread(target: target, threadId: "synthetic-thread-\(index)"))
        } })
      }
      // A render projection must work even while the shared file is unavailable.
      // This also catches accidentally reintroducing file I/O in row lookups.
      let ledger = TaskNotificationShared.directory.appendingPathComponent("mobile-unread-v1.json")
      let parked = ledger.appendingPathExtension("parked")
      try FileManager.default.moveItem(at: ledger, to: parked)
      for index in 0..<25 {
        precondition(snapshot.isUnread(target: target, threadId: "synthetic-thread-\(index)"))
      }
      try FileManager.default.moveItem(at: parked, to: ledger)
      results.append(["ledgerThreads": count, "sidebarRows": 25,
        "originalRowReadMedianMS": original.sorted()[2],
        "snapshotRowReadMedianMS": optimized.sorted()[2], "rowLookupsRequireFile": false])
    }
    print(String(decoding: try JSONSerialization.data(withJSONObject: results,
      options: [.sortedKeys, .prettyPrinted]), as: UTF8.self))
  }
}
