import Foundation

/// macOS Swift/actual iOS ledger code, synthetic EasyWork identities only; no OS notification claim.
@main struct EasyWorkNotificationPresentationNative {
  @MainActor static func main() throws {
    guard let selected = ProcessInfo.processInfo.environment["CODEX_NOTIFICATION_TEST_DIRECTORY"] else { fatalError("Select a new private fixture directory") }
    let directory = URL(fileURLWithPath: selected, isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let state = directory.appendingPathComponent("easywork-presentation-v1.json")
    precondition(!FileManager.default.fileExists(atPath: state.path))
    let protected = directory.appendingPathComponent("original-reading-and-draft.fixture")
    let original = Data("Original independent reader and unsent draft bytes".utf8); try original.write(to: protected)
    let target = AgentRuntimeTarget(agentId: "easywork", hostId: "desktop", workspaceId: "workspace", runtimeId: "runtime", account: AgentAccount(authority: "https://account.example", subject: "human"))
    let event = TaskNotificationEvent(version: 1, id: String(repeating: "a", count: 64), target: target, kind: .approval, occurredAt: "2026-10-25T00:00:00.000Z", threadId: "original-thread", runId: "original-run", requestId: "original-request", threadTitle: "Original conversation", taskSummary: "原审批")
    var preferences = TaskNotificationPreferences(); preferences.enabled = true; preferences.revision = "current-preference"
    func expect(_ candidate: TaskNotificationEvent, active: [String]?, revision: String?, _ expected: Bool) throws {
      let value = try EasyWorkNoticePresentationLedger.mayRestore(candidate, activeIds: active, remoteRevision: revision, preferences: preferences)
      precondition(value == expected)
    }
    func rejects(_ body: () throws -> Void) {
      var rejected = false; do { try body() } catch { rejected = true }; precondition(rejected)
    }
    try EasyWorkNoticePresentationLedger.posted(event)
    try expect(event, active: [event.id], revision: preferences.revision, false)
    preferences.kinds.removeAll { $0 == .approval }; try EasyWorkNoticePresentationLedger.suppress(preferences)
    preferences.kinds.append(.approval)
    let pending = try Data(contentsOf: state)
    try expect(event, active: nil, revision: preferences.revision, false)
    try expect(event, active: [], revision: preferences.revision, false)
    try expect(event, active: [event.id], revision: nil, false)
    try expect(event, active: [event.id], revision: "old-revision", false)
    try expect(event, active: [event.id], revision: preferences.revision, true)
    let afterPending = try Data(contentsOf: state); precondition(afterPending == pending)
    print("1: same immutable actionable requires current active authority and exact preference revision")
    preferences.enabled = false; try expect(event, active: [event.id], revision: preferences.revision, false)
    preferences.enabled = true; preferences.excludedHosts = [target.hostId]; try expect(event, active: [event.id], revision: preferences.revision, false)
    preferences.excludedHosts = []; preferences.foregroundBanner = false; try expect(event, active: [event.id], revision: preferences.revision, false)
    preferences.foregroundBanner = true
    var completed = event; completed.id = String(repeating: "b", count: 64); completed.kind = .completed
    preferences.kinds.append(.completed); try EasyWorkNoticePresentationLedger.posted(completed)
    preferences.enabled = false; try EasyWorkNoticePresentationLedger.suppress(preferences); preferences.enabled = true
    try expect(completed, active: [completed.id], revision: preferences.revision, false)
    print("2: disabled, excluded, banner-off and completed results cannot reappear")
    var changed = event; changed.taskSummary = "Changed immutable body"
    let unchanged = try Data(contentsOf: state)
    rejects { try EasyWorkNoticePresentationLedger.posted(changed) }
    rejects { try expect(changed, active: [event.id], revision: preferences.revision, false) }
    var wrongTarget = event; wrongTarget.target.runtimeId = "other-runtime"
    rejects { try EasyWorkNoticePresentationLedger.posted(wrongTarget) }
    let afterChanged = try Data(contentsOf: state); precondition(afterChanged == unchanged)
    print("3: same ID with another body or target preserves original durable bytes")
    var other = event; other.id = String(repeating: "c", count: 64); other.target.runtimeId = "other-runtime"
    try EasyWorkNoticePresentationLedger.posted(event); try EasyWorkNoticePresentationLedger.posted(other)
    try EasyWorkNoticePresentationLedger.resolve(target, activeIds: [])
    try expect(event, active: [event.id], revision: preferences.revision, true)
    try expect(other, active: [other.id], revision: preferences.revision, false)
    try EasyWorkNoticePresentationLedger.seedPosted(event)
    try expect(event, active: [event.id], revision: preferences.revision, true)
    print("4: resolution and legacy seeding cannot erase another target or reactivate a posted notice")
    let valid = try Data(contentsOf: state)
    try Data("{broken original".utf8).write(to: state)
    let corrupt = try Data(contentsOf: state)
    rejects { try EasyWorkNoticePresentationLedger.suppress(preferences) }
    rejects { try EasyWorkNoticePresentationLedger.posted(event) }
    let afterCorrupt = try Data(contentsOf: state); precondition(afterCorrupt == corrupt)
    try valid.write(to: state)
    var raw = try JSONSerialization.jsonObject(with: valid) as! [String: Any]; raw["future-field"] = true
    try JSONSerialization.data(withJSONObject: raw).write(to: state)
    let future = try Data(contentsOf: state); rejects { try EasyWorkNoticePresentationLedger.suppress(preferences) }
    let afterFuture = try Data(contentsOf: state); precondition(afterFuture == future)
    print("5: corrupt or unknown-format state is rejected without creating an empty replacement")
    try FileManager.default.removeItem(at: state)
    let unique = directory.appendingPathComponent("unique-original.fixture"); try valid.write(to: unique)
    try FileManager.default.createSymbolicLink(at: state, withDestinationURL: unique)
    rejects { try EasyWorkNoticePresentationLedger.posted(event) }
    let afterUnique = try Data(contentsOf: unique); precondition(afterUnique == valid)
    let afterProtected = try Data(contentsOf: protected); precondition(afterProtected == original)
    print("6: symlink targets and unrelated reading/draft data stay unchanged")
    print("PASS: 6 scoped native Swift auxiliary groups; real OS notification/model/iPhone claims: false; Codex tests: false")
  }
}
