import Foundation
import CryptoKit
import MobileCore

func signature(_ timeline: ChatTimeline) -> [String] {
  timeline.items.map { item in
    switch item {
    case .message(let m): return "m:" + m.id
    case .tools(let id, let messages): return "t:" + id + ":" + messages.map(\.id).joined(separator: ",")
    case .compaction(let m): return "c:" + m.id
    case .errors(let id, let messages): return "e:" + id + ":" + messages.map(\.id).joined(separator: ",")
    case .replyActions(let id, let text, _): return "r:" + id + ":" + text
    }
  }
}
func signature(_ timeline: LegacyChatTimeline) -> [String] {
  timeline.items.map { item in
    switch item {
    case .message(let m): return "m:" + m.id
    case .tools(let id, let messages): return "t:" + id + ":" + messages.map(\.id).joined(separator: ",")
    case .compaction(let m): return "c:" + m.id
    case .errors(let id, let messages): return "e:" + id + ":" + messages.map(\.id).joined(separator: ",")
    case .replyActions(let id, let text): return "r:" + id + ":" + text
    }
  }
}
// Deterministic varied runs exercise production Swift projection against the A1 implementation.
var seed: UInt64 = 0xA205
func random(_ count: Int) -> Int { seed = seed &* 6364136223846793005 &+ 1; return Int((seed >> 32) % UInt64(count)) }
for index in 0..<2000 {
  let messages = (0..<random(45)).map { i in
    ChatMessage(id: "m-\(i)", role: ["user", "assistant", "tool"][random(3)],
      text: ["中文 😀", "", "\u{200B}", "answer", "part\nnext"][random(5)],
      kind: [nil, "agentMessage", "reasoning", "plan", "error", "contextCompaction"][random(6)],
      status: [nil, "completed", "streaming", "failed"][random(4)],
      runId: [nil, "", "one", "two"][random(4)], replyPhase: [nil, "commentary", "final_answer"][random(3)],
      replyMessageId: [nil, "source-a", "source-b"][random(3)])
  }
  let limit = random(50) - 3, active: String? = [nil, "one", "two"][random(3)], running = random(2) == 1
  let actual = ChatTimeline(messages: messages, messageLimit: limit, activeRunId: active, isRunning: running)
  let old = LegacyChatTimeline(messages: messages, messageLimit: limit, activeRunId: active, isRunning: running)
  precondition(signature(actual) == signature(old) && actual.hasEarlierMessages == old.hasEarlierMessages, "projection \(index)")
}

let host = Client(id: "synthetic-host", name: "Fixture", isDemo: false, online: false)
let target = AgentRuntimeTarget(agentId: "easywork", hostId: host.id, workspaceId: "synthetic-workspace", runtimeId: "desktop-runtime")
let workspace = WorkspaceSnapshot(hostId: host.id, name: "Fixture", connectionMode: "desktop-runtime", projects: [], threads: [], nextProjectCursor: nil, nextThreadCursor: nil, readTarget: target)
let old = host.id + ":thread"
let key = SharedMobileRules.cacheKey(host, threadId: "thread", projectId: nil, workspace: workspace)
let creation = PendingDesktopCreation(hostId: host.id, clientOperationId: "immutable-operation", projectId: nil, text: "\u{FEFF}原始草稿 😀", attachments: [], manifest: [], phase: "outcome-unknown")
var saved = SavedSession()
saved.clients = [host]; saved.workspaces = [host.id: workspace]
saved.history = [old: [ChatMessage(id: "reply", role: "assistant", text: "kept", status: "completed")]]
saved.drafts = [old: Draft(text: "new editable draft", operationId: "draft-operation")]
saved.runtimes = [old: ThreadRuntime(state: "unknown", turnId: nil, plan: [], error: nil, approvals: [], receipts: [])]
saved.outbox = [old: PendingReply(hostId: host.id, threadId: "thread", clientMessageId: "reply-operation", text: "submitted immutable", attachmentIds: [], mode: "queue", expectedTurnId: nil)]
saved.desktopCreations = [old: creation]; saved.detachedDesktopCreations = [old: [creation]]
saved.dismissedReceiptFailures = [old: ["dismissed"]]
saved.agentEventCursors = [old: AgentEventCursor(target: target, threadId: "thread", epoch: "epoch", sequence: 12)]
saved.generationPreferences = ["generation:" + host.id + ":new:none": GenerationOptions(model: "model", effort: "high", permissionMode: nil)]
let originalBytes = try JSONEncoder().encode(saved)
var migrationChecks = 0
func checkMigration(_ condition: Bool) { precondition(condition); migrationChecks += 1 }
let dirty = SharedMobileRules.migrateCache(&saved)
checkMigration(saved.mobileCacheVersion == 2 && dirty == [key])
checkMigration(saved.history[key]?.first?.id == "reply" && saved.drafts[key]?.text == "new editable draft")
checkMigration(saved.outbox?[key]?.clientMessageId == "reply-operation" && saved.outbox?[key]?.text == "submitted immutable")
checkMigration(saved.desktopCreations?[key]?.clientOperationId == "immutable-operation" && saved.desktopCreations?[key]?.phase == "outcome-unknown")
checkMigration(saved.detachedDesktopCreations?[key]?.first?.text == creation.text && saved.dismissedReceiptFailures?[key] == ["dismissed"])
checkMigration(saved.agentEventCursors?[key]?.sequence == 12 && saved.runtimes?[key]?.state == "unknown")
let preferenceKey = "generation:" + SharedMobileRules.cacheKey(host, threadId: nil, projectId: nil, workspace: workspace)
checkMigration(saved.generationPreferences?[preferenceKey]?.model == "model" && SharedMobileRules.migrateCache(&saved).isEmpty)
var conflict = try JSONDecoder().decode(SavedSession.self, from: originalBytes)
conflict.drafts[key] = Draft(text: "existing scoped")
_ = SharedMobileRules.migrateCache(&conflict)
checkMigration(conflict.drafts[key]?.text == "existing scoped" && conflict.drafts[old]?.text == "new editable draft")
let restored = try JSONDecoder().decode(SavedSession.self, from: originalBytes)
checkMigration(restored.mobileCacheVersion == nil && restored.desktopCreations?[old]?.clientOperationId == "immutable-operation")

struct ReadingVector: Decodable {
  struct Target: Decodable { let agentId: String, accountAuthority: String?, accountSubject: String?, hostId: String, workspaceId: String, runtimeId: String }
  let name: String, target: Target, threadId: String, messageId: String, text: String, status: String, preimage: String, key: String, sha256: String
}
struct ReadingVectors: Decodable { let reading: [ReadingVector] }
let vectors = try JSONDecoder().decode(ReadingVectors.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
for value in vectors.reading {
  let t = value.target
  let scope = TargetScope(agentId: t.agentId, accountAuthority: t.accountAuthority, accountSubject: t.accountSubject, hostId: t.hostId, workspaceId: t.workspaceId, runtimeId: t.runtimeId)
  precondition(MobileRules.shared.readingKey(target: scope, threadId: value.threadId) == value.key, value.name)
  precondition(ReadingIdentity.legacyKey(agentId: t.agentId, authority: t.accountAuthority, subject: t.accountSubject, hostId: t.hostId, workspaceId: t.workspaceId, runtimeId: t.runtimeId, threadId: value.threadId) == value.key, value.name)
  precondition(ReadingIdentity.key(agentId: t.agentId, authority: t.accountAuthority, subject: t.accountSubject, hostId: t.hostId, workspaceId: t.workspaceId, runtimeId: t.runtimeId, threadId: value.threadId) == RuntimeIdentity.shared.readingKey(target: scope, threadId: value.threadId), value.name)
  precondition(MobileRules.shared.contentVersionPreimage(messageId: value.messageId, text: value.text, status: value.status) == value.preimage, value.name)
  precondition(ReadPosition.contentVersion(messageId: value.messageId, text: value.text, status: value.status) == value.sha256, value.name)
}
precondition(migrationChecks == 9)
print("2000 projection comparisons; 9 migration checks; 5 UTF-8/identity checks passed")

// The notification extensions use the bounded native codec without linking KMP.
// Verify its actual bytes agree with the common core, including JSON escapes.
var identityChecks = 0
func checkIdentity(_ condition: Bool) { precondition(condition, "machine identity check \(identityChecks + 1)"); identityChecks += 1 }
for selector: String? in [nil, "", "thread/中文 😀", "quote\"\\\n\t", "line\u{2028}\u{2029}"] {
  let captured = AgentRuntimeTarget(agentId: "codex", hostId: "host/中文", workspaceId: "workspace", runtimeId: "runtime", account: AgentAccount(authority: "legacy", subject: "account-a"))
  var current = captured; current.account = nil
  checkIdentity(current == captured && !current.matchesCapturedPayload(captured))
  checkIdentity(CodexMachineStorage.cacheKey(current, threadId: selector, projectId: "project/") == SharedMobileRules.machineCacheKey(current, threadId: selector, projectId: "project/"))
  checkIdentity(CodexMachineStorage.readingKey(current, threadId: selector) == SharedMobileRules.machineReadingKey(current, threadId: selector))
  let legacy = MobileRules.shared.cacheKey(clientId: host.id, target: SharedMobileRules.scope(captured), threadId: selector, projectId: "project/")
  checkIdentity(RuntimeIdentity.shared.compatibleCacheKeys(target: SharedMobileRules.scope(current), threadId: selector, projectId: "project/", keys: [legacy]) == [legacy])
}
var codexSaved = try JSONDecoder().decode(SavedSession.self, from: originalBytes)
codexSaved.workspaces[host.id]?.readTarget?.agentId = "codex"
let immutableEncoder = JSONEncoder(); immutableEncoder.outputFormatting = [.sortedKeys]
let capturedOutbox = try immutableEncoder.encode(codexSaved.outbox)
let capturedCreations = try immutableEncoder.encode(codexSaved.desktopCreations)
checkIdentity(SharedMobileRules.migrateCache(&codexSaved).isEmpty)
checkIdentity(codexSaved.drafts[old]?.text == "new editable draft" && codexSaved.history[old]?.first?.id == "reply")
checkIdentity(try immutableEncoder.encode(codexSaved.outbox) == capturedOutbox)
checkIdentity(try immutableEncoder.encode(codexSaved.desktopCreations) == capturedCreations)
let canonicalReview = SharedMobileRules.machineCacheKey(AgentRuntimeTarget(agentId: "codex", hostId: host.id, workspaceId: "workspace", runtimeId: "runtime"), threadId: "thread", projectId: nil)
checkIdentity(CodexMachineStorage.preferredKey(canonicalReview, candidates: [canonicalReview, "old-record"], selectedLegacy: "old-record") == "old-record")
checkIdentity(SharedMobileRules.preferredRuntimeKey(canonicalReview, candidates: [canonicalReview, "old-record"], selectedLegacy: "old-record") == "old-record")
precondition(identityChecks == 26)
print("26 machine identity/native codec/non-destructive migration checks passed")

do {
  let current = CodexMachineStorage.ReadingKeyMemo.Value(canonical: "current", candidate: "current", selected: "current")
  var probes = 0
  for _ in 0..<6000 {
    precondition(current.preferredKey { probes += 1; return false } == "current")
  }
  precondition(probes == 0, "An already-selected canonical key needs no stored-record probe")
  let legacy = CodexMachineStorage.ReadingKeyMemo.Value(canonical: "current", candidate: "current", selected: "legacy")
  var canonicalPresent = false
  precondition(legacy.preferredKey { canonicalPresent } == "legacy")
  canonicalPresent = true
  precondition(legacy.preferredKey { canonicalPresent } == "current", "A newly-created canonical record wins before rebuilding the index")
  canonicalPresent = false
  precondition(legacy.preferredKey { canonicalPresent } == "legacy", "Record presence must remain live")
  let differentTarget = CodexMachineStorage.ReadingKeyMemo.Value(canonical: "current", candidate: "other-target", selected: "legacy")
  precondition(differentTarget.preferredKey { probes += 1; return true } == "legacy")
  precondition(probes == 0, "A different candidate target cannot override the original selection")
  print("Reading key selection passed: 6000 canonical fast paths; live legacy precedence and target isolation")
}

do {
  let previous = ReadPosition(revision: 2, messageId: "reply", contentVersion: String(repeating: "a", count: 64))
  let latest = ReadPosition(revision: 3, messageId: "reply", contentVersion: String(repeating: "b", count: 64))
  let settled = MobileReadRecord(position: previous, pending: false)
  precondition(ReadingReconciliation.acknowledgedChange(current: settled, incoming: previous, latest: latest) == nil)
  let pending = MobileReadRecord(position: previous, pending: true)
  precondition(ReadingReconciliation.acknowledgedChange(current: pending, incoming: previous, latest: latest) == settled)
  precondition(ReadingReconciliation.acknowledgedChange(current: nil, incoming: previous, latest: latest) == settled)
  precondition(ReadingReconciliation.acknowledgedChange(current: settled, incoming: latest, latest: latest) == MobileReadRecord(position: latest, pending: false))
  var stale = previous; stale.revision = 1
  precondition(ReadingReconciliation.acknowledgedChange(current: pending, incoming: stale, latest: latest) == nil)
  var conflicting = previous; conflicting.contentVersion = latest.contentVersion
  precondition(ReadingReconciliation.acknowledgedChange(current: pending, incoming: conflicting, latest: latest) == nil)
  var future = latest; future.revision = 4
  precondition(ReadingReconciliation.acknowledgedChange(current: pending, incoming: future, latest: latest) == nil)
  print("Reading acknowledgement merge passed: 7 duplicate, pending, missing, advancing, stale, conflicting and future boundaries")
}

// Compile the real coordinator method with narrow storage/transport boundaries.
// This exercises directory size and pending retries without a model or UI.
private final class ReadingCoordinatorHarness {
  struct Workspace { var threads: [Conversation] }
  struct Saved {
    var workspaces: [String: Workspace] = [:]
    var recordWrites = 0
    var mobileReads: [String: MobileReadRecord]? = [:] { didSet { recordWrites += 1 } }
    var mobileReadBaselines: [String: MobileReadBaseline]? = [:]
  }
  var saved = Saved()
  var keyComputations = 0
  var eligibleRetries: [String] = []
  func readingClient(_ host: Client) -> Client { host }
  func readingKey(_ host: Client, threadId: String?) -> String {
    keyComputations += 1
    return host.id + ":" + (threadId ?? "")
  }
  func syncReading(_ host: Client, threadId: String, knownKey: String? = nil) {
    let key = knownKey ?? readingKey(host, threadId: threadId)
    if saved.mobileReads?[key]?.pending == true { eligibleRetries.append(key) }
  }
  // PRODUCTION_RECONCILE_READING
  func run(_ host: Client, updated: String? = nil) { reconcileReading(host, updatedThreadId: updated) }
}
private final class SelectionLookupHarness {
  final class Saved {
    var selected: String?
    var idReads = 0
    var selectedClientId: String? { get { idReads += 1; return selected } set { selected = newValue } }
    var clients: [Client] = []
  }
  let saved = Saved()
  var selectedThread: String?
  var threadReads = 0
  var selectedThreadId: String? { get { threadReads += 1; return selectedThread } set { selectedThread = newValue } }
  var selectedProjectId: String?
  var threads: [Conversation] = []
  var projects: [Project] = []
  // PRODUCTION_SELECTION_GETTERS
}
do {
  let h = SelectionLookupHarness()
  h.saved.clients = (0..<64).map { Client(id: "host-\($0)", name: "Synthetic", isDemo: false, online: false) }
  h.saved.selectedClientId = "host-63"
  precondition(h.client?.id == "host-63" && h.saved.idReads == 1)
  h.threads = (0..<818).map { Conversation(id: "row-\($0)", hostId: "host-63", projectId: "project-299", title: "Synthetic", state: "idle") }
  h.projects = (0..<300).map { Project(id: "project-\($0)", hostId: "host-63", name: "Synthetic", roots: []) }
  h.selectedThreadId = "row-817"
  precondition(h.project?.id == "project-299" && h.threadReads == 1,
    "Finding the project must not rescan the conversation for every project row")
  h.threadReads = 0; h.selectedThreadId = nil; h.selectedProjectId = "project-4"
  precondition(h.project?.id == "project-4" && h.threadReads == 1)
  h.selectedThreadId = "missing"
  precondition(h.conversation == nil)
  h.threads.append(Conversation(id: "missing", hostId: "host-63", projectId: nil, title: "Arrived", state: "idle"))
  precondition(h.conversation?.title == "Arrived")
  h.threads[h.threads.count - 1].title = "Changed"
  precondition(h.conversation?.title == "Changed", "Selection reads must observe metadata changes immediately")
  print("Production selection lookup passed: one observable read per lookup, project fallback and immediate metadata changes")
}
do {
  let h = ReadingCoordinatorHarness()
  let position = ReadPosition(revision: 1, messageId: "reply", contentVersion: String(repeating: "a", count: 64))
  let host = Client(id: "directory", name: "Fixture", isDemo: false, online: true, readingScopeId: "reader")
  let threads = (0..<818).map { i in
    Conversation(id: "row-\(i)", hostId: host.id, projectId: nil, title: "Synthetic", state: "idle",
      readState: ThreadReadState(version: 1, latest: position, readerId: "reader", read: position))
  }
  h.saved.workspaces[host.id] = .init(threads: threads)
  h.run(host, updated: "row-0")
  precondition(h.keyComputations == 1 && h.saved.mobileReadBaselines?.count == 1 && h.saved.mobileReads?.count == 1)
  h.run(host)
  precondition(h.saved.mobileReadBaselines?.count == 818 && h.saved.mobileReads?.count == 818)
  let writes = h.saved.recordWrites
  h.keyComputations = 0
  h.run(host, updated: "row-0")
  precondition(h.keyComputations == 1 && h.saved.recordWrites == writes, "Repeated snapshots must neither scan every key nor rewrite identical acknowledgements")
  let otherKey = host.id + ":row-1"
  h.saved.mobileReads?[otherKey] = MobileReadRecord(position: position, pending: true)
  h.run(host, updated: "row-0")
  precondition(h.eligibleRetries == [otherKey], "An unrelated pending read must still retry")
  precondition(h.saved.mobileReads?[otherKey]?.pending == true, "The unchanged summary cannot prematurely acknowledge a different pending row")
  let second = Client(id: "other-directory", name: "Other", isDemo: false, online: true, readingScopeId: "reader")
  h.saved.workspaces[second.id] = .init(threads: [Conversation(id: "row-1", hostId: second.id, projectId: nil, title: "Other", state: "idle")])
  h.run(second, updated: "row-1")
  precondition(h.eligibleRetries == [otherKey] && h.saved.mobileReads?[otherKey]?.pending == true)
  print("Production reading coordinator passed: 818-row full refresh, one-row snapshot, other pending retry and host isolation")
}

// PRODUCTION_UNREAD_SNAPSHOT_TYPE
private final class UnreadProjectionHarness {
  var storage = SavedSession()
  var savedReads = 0
  var saved: SavedSession { savedReads += 1; return storage }
  var codexReadingIndex = CodexMachineStorage.ReadingKeyIndex()
  var codexReadingMemo = CodexMachineStorage.ReadingKeyMemo()
  func cacheKey(_ host: Client, threadId: String?, state: SavedSession, machine: AgentRuntimeTarget) -> String {
    SharedMobileRules.machineCacheKey(machine, threadId: threadId, projectId: nil)
  }
  // PRODUCTION_UNREAD_PROJECTION
  func run() async throws -> (scopes: [AgentRuntimeTarget], snapshots: [MobileUnreadSnapshot]) { try await mobileUnreadSnapshots() }
}
do {
  let p = ReadPosition(revision: 3, messageId: "reply", contentVersion: String(repeating: "a", count: 64))
  var thread = Conversation(id: "row", hostId: "host", projectId: nil, title: "Synthetic", state: "idle",
    hasUnreadTurn: true, readState: ThreadReadState(version: 1, latest: p))
  func unread(_ thread: Conversation, record: MobileReadRecord?, baseline: MobileReadBaseline?) -> Bool {
    ReadingReconciliation.unread(state: thread.readState, legacyUnread: thread.hasUnreadTurn == true,
      fallbackUnread: thread.showsUnreadDot, record: record, baseline: baseline)
  }
  let same = MobileReadRecord(position: p, pending: true)
  precondition(!unread(thread, record: same, baseline: nil))
  var newer = p; newer.revision += 1
  precondition(!unread(thread, record: .init(position: newer, pending: false), baseline: nil))
  var older = p; older.revision -= 1
  precondition(unread(thread, record: .init(position: older, pending: false), baseline: nil))
  var changed = p; changed.contentVersion = String(repeating: "b", count: 64)
  precondition(unread(thread, record: .init(position: changed, pending: false), baseline: nil))
  precondition(!unread(thread, record: nil, baseline: .init(position: p, unread: false)))
  precondition(unread(thread, record: nil, baseline: .init(position: p, unread: true)))
  precondition(unread(thread, record: nil, baseline: .init(position: older, unread: false)))
  precondition(unread(thread, record: nil, baseline: .init(position: changed, unread: false)))
  thread.readState = nil
  precondition(unread(thread, record: same, baseline: nil))
  thread.hasUnreadTurn = false
  precondition(!unread(thread, record: nil, baseline: nil))
  print("Unread predicate passed: 10 record, baseline, legacy and content-change boundaries")
}
do {
  let h = UnreadProjectionHarness()
  let host = Client(id: "directory", name: "Fixture", isDemo: false, online: true)
  let target = AgentRuntimeTarget(agentId: "codex", hostId: host.id, workspaceId: "workspace", runtimeId: "runtime")
  let p = ReadPosition(revision: 1, messageId: "reply", contentVersion: String(repeating: "a", count: 64))
  var rows = (0..<818).map { i in
    Conversation(id: "row-\(i)", hostId: host.id, projectId: nil, title: "Synthetic", state: "idle",
      hasUnreadTurn: true, readState: ThreadReadState(version: 1, latest: p))
  }
  rows[817].readState = nil
  h.storage.clients = [host, Client(id: "demo", name: "Demo", isDemo: true, online: false)]
  h.storage.workspaces[host.id] = WorkspaceSnapshot(hostId: host.id, name: "Synthetic", connectionMode: "desktop-runtime",
    projects: [], threads: rows, nextProjectCursor: nil, nextThreadCursor: nil, readTarget: target)
  let k = SharedMobileRules.nativeReadingKey(target, threadId: "row-0")
  h.storage.mobileReads = [k: .init(position: p, pending: true)]
  let legacyKey = SharedMobileRules.machineCacheKey(target, threadId: "row-817", projectId: nil)
  h.storage.history[legacyKey] = [ChatMessage(id: "reply", role: "assistant", text: "Synthetic", status: "completed")]
  let first = try await h.run()
  precondition(h.savedReads == 1 && first.scopes == [target] && first.snapshots.count == 818)
  precondition(first.snapshots[0].read == p && !first.snapshots[0].unread)
  precondition(first.snapshots[1].unread && first.snapshots[817].localReply?.messageId == "reply")
  precondition(first.snapshots.allSatisfy { $0.target == target })
  h.storage.mobileReads = [:]
  let second = try await h.run()
  precondition(h.savedReads == 2 && second.snapshots[0].read == nil && second.snapshots[0].unread,
    "Each new projection must observe current storage rather than retain a stale read snapshot")
  let other = Client(id: "other", name: "Other", isDemo: false, online: true)
  let otherTarget = AgentRuntimeTarget(agentId: "codex", hostId: other.id, workspaceId: "workspace", runtimeId: "runtime")
  h.storage.clients.append(other)
  h.storage.workspaces[other.id] = WorkspaceSnapshot(hostId: other.id, name: "Other", connectionMode: "desktop-runtime",
    projects: [], threads: [rows[0]], nextProjectCursor: nil, nextThreadCursor: nil, readTarget: otherTarget)
  let otherKey = SharedMobileRules.nativeReadingKey(otherTarget, threadId: rows[0].id)
  h.storage.mobileReads = [otherKey: .init(position: p, pending: false)]
  let third = try await h.run()
  precondition(h.savedReads == 3 && third.scopes == [target, otherTarget] && third.snapshots.count == 819)
  precondition(third.snapshots[0].read == nil && third.snapshots[0].unread)
  precondition(third.snapshots[818].target == otherTarget && third.snapshots[818].read == p && !third.snapshots[818].unread,
    "Same thread selector on a different Host must use its own reading record")
  let cancelled = Task { try await h.run() }
  try await Task.sleep(for: .milliseconds(2))
  cancelled.cancel()
  do { _ = try await cancelled.value; preconditionFailure("Cancelled projection must not produce a stale snapshot") }
  catch is CancellationError {} catch { preconditionFailure("Unexpected projection error") }
  print("Production unread projection passed: one observable snapshot for 818 rows; live reads, legacy reply and target isolation")
}

// Compare the unchanged production Swift recovery policy with the same Host vectors
// used by the KMP consumer. No copied implementation can substitute for this check.
let recoveryRoot = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))) as! [String: Any]
func recoveryJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.withoutEscapingSlashes]) }
let recoveryFingerprints = recoveryRoot["fingerprints"] as! [[String: Any]]
for test in recoveryFingerprints {
  let reply = try JSONDecoder().decode(PendingReply.self, from: recoveryJSON(test["reply"]!))
  let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: recoveryJSON(test["target"]!))
  let actualFingerprint = try ReplyRecoveryPolicy.fingerprint(reply, target: target)
  precondition(actualFingerprint == test["fingerprint"] as! String, test["name"] as! String)
}
let recoveryFirst = recoveryFingerprints[0]
let recoveryOriginal = try JSONDecoder().decode(PendingReply.self, from: recoveryJSON(recoveryFirst["reply"]!))
let recoveryTarget = try JSONDecoder().decode(AgentRuntimeTarget.self, from: recoveryJSON(recoveryFirst["target"]!))
let recoveryRecord = ReplyRecoveryRecord(original: recoveryOriginal, target: recoveryTarget, trust: "synthetic-fixture-only",
  fingerprint: recoveryFirst["fingerprint"] as! String, phase: "strong-not-accepted")
let recoveryNow = Date(timeIntervalSince1970: (recoveryRoot["nowEpochMillis"] as! NSNumber).doubleValue / 1000)
for test in recoveryRoot["proofs"] as! [[String: Any]] {
  let data = try recoveryJSON(test["value"]!)
  // The production HostService.load path validates this contract before decode.
  let validWire = CodexHttpContract.shared.validate(json: String(decoding: data, as: UTF8.self), definition: "replyInspection") == nil
  let inspection: ReplyDeliveryInspection?
  if validWire { inspection = try? JSONDecoder().decode(ReplyDeliveryInspection.self, from: data) }
  else { inspection = nil }
  let actual = inspection.map { ReplyRecoveryPolicy.strongNotAccepted($0, record: recoveryRecord, now: recoveryNow) } ?? false
  precondition(actual == test["expected"] as! Bool, test["name"] as! String)
}
print("Swift reply wire and recovery policy passed: 4 Host fingerprints and 26 proof boundaries")

// Native persistence must keep the consent model captured before a nil-model
// wire send; restoring an operation never resolves it from the current thread.
var scopedRecovery = recoveryRecord
scopedRecovery.sharingScope = AISharingScope(target: recoveryTarget, model: "original-consented-model")
let scopedRestore = try JSONDecoder().decode(ReplyRecoveryRecord.self, from: JSONEncoder().encode(scopedRecovery))
precondition(scopedRestore.original.model == recoveryOriginal.model)
precondition(scopedRestore.sharingScope == scopedRecovery.sharingScope)
precondition(ReplyRecoveryPolicy.sameIntent(scopedRestore.original, record: scopedRestore))
let oldRecovery = try JSONDecoder().decode(ReplyRecoveryRecord.self, from: JSONEncoder().encode(recoveryRecord))
precondition(oldRecovery.sharingScope == nil)
let creationWire: [String: Any] = ["hostId": "native-fixture", "clientOperationId": "original-operation", "projectId": NSNull(),
  "phase": "outcome-unknown", "threadId": NSNull(), "error": NSNull(), "uploaded": [],
  "preparation": ["preparedThreadId": "original-empty-thread", "preparationPhase": "input-retryable",
    "diagnostic": ["stage": "generation", "code": "generation-preflight-failed", "disposition": "retry-original-preflight"]]]
let nativeCreation = try JSONDecoder().decode(DesktopCreationOperation.self, from: recoveryJSON(creationWire))
precondition(nativeCreation.phase == "outcome-unknown")
precondition(nativeCreation.preparation?.preparedThreadId == "original-empty-thread")
precondition(nativeCreation.preparation?.preparationPhase == "input-retryable")
precondition(nativeCreation.preparation?.diagnostic?.disposition == "retry-original-preflight")
var legacyCreationWire = creationWire
legacyCreationWire.removeValue(forKey: "preparation")
let legacyNativeCreation = try JSONDecoder().decode(DesktopCreationOperation.self, from: recoveryJSON(legacyCreationWire))
precondition(legacyNativeCreation.preparation == nil)
print("Native recovery consent persistence and nested creation codec passed: 9 boundaries")

// Exercise the actual Swift -> Kotlin projection and ensure cache invalidation
// includes hidden content, non-text changes, runtime/focus and the full target.
do {
  let cache = ChatTimelineCache()
  let messages = [ChatMessage(id: "user", role: "user", text: "question", runId: "run"),
    ChatMessage(id: "tool", role: "tool", text: "command", kind: "commandExecution", status: "completed", runId: "run"),
    ChatMessage(id: "answer", role: "assistant", text: "answer", status: "completed", runId: "run", replyPhase: "final_answer")]
  func project(_ values: [ChatMessage] = messages, target: String = "full-target-a", limit: Int = 20,
    active: String? = nil, running: Bool = false, focus: String? = nil) -> ChatTimeline {
    cache.project(targetKey: target, messages: values, messageLimit: limit,
      activeRunId: active, isRunning: running, focusedMessageId: focus)
  }
  let original = project()
  for _ in 0..<500 { precondition(project() === original, "Unchanged scroll renders must reuse the projection") }
  let mutations: [(inout ChatMessage) -> Void] = [
    { $0.text += " streamed" }, { $0.detail = "updated output" }, { $0.status = "streaming" },
    { $0.kind = "plan" }, { $0.runId = "other-run" }, { $0.replyPhase = "commentary" },
    { $0.replyMessageId = "other-reply" }, { $0.files = ["new-file"] },
    { $0.blocks = [.text("updated content block")] },
    { $0.attachments = [RemoteAttachment(id: "file", hostId: "fixture-host", threadId: "fixture-thread",
        name: "fixture.txt", mediaType: "text/plain", sizeBytes: 1, sha256: String(repeating: "a", count: 64))] }
  ]
  for mutate in mutations {
    let before = project()
    var changed = messages; mutate(&changed[2])
    let after = project(changed)
    precondition(after !== before, "Changed message fields must invalidate the projection")
    precondition(signature(after) == signature(ChatTimeline(messages: changed, messageLimit: 20)))
  }
  var before = project()
  precondition(project(target: "full-target-b") !== before)
  before = project(); precondition(project(limit: 1) !== before)
  before = project(); precondition(project(active: "run") !== before)
  before = project(); precondition(project(running: true) !== before)
  before = project(); precondition(project(focus: "tool") !== before)
  before = project(); precondition(project(Array(messages.dropFirst())) !== before)
  before = project(limit: 1)
  var hiddenChange = messages; hiddenChange[0].text = "changed hidden input"
  precondition(project(hiddenChange, limit: 1) !== before, "Hidden run context must invalidate final actions too")
  print("History projection cache passed: 500 scroll reuses and 17 invalidation boundaries")
}

// Exercise the real codecs on cache misses, then prove warm event/render loops
// do no encoding. The memo stores pure bytes only; provider and target changes
// remain isolated, and eviction can never return another tuple's bytes.
do {
  let cache = RuntimeIdentityKeyCache(capacity: 64)
  let target = AgentRuntimeTarget(agentId: "codex", hostId: "cache-host",
    workspaceId: "cache-workspace", runtimeId: "desktop-runtime")
  var encodes = 0
  func reference(_ t: AgentRuntimeTarget, _ kind: RuntimeIdentityKeyCache.Kind,
    _ thread: String?, _ project: String?) -> String {
    switch kind {
    case .cache:
      return RuntimeIdentity.shared.cacheKey(clientId: t.hostId, target: SharedMobileRules.scope(t), threadId: thread, projectId: project)
    case .reading:
      return RuntimeIdentity.shared.readingKey(target: SharedMobileRules.scope(t), threadId: thread)
    case .nativeReading:
      return ReadingIdentity.key(agentId: t.agentId, authority: t.account?.authority,
        subject: t.account?.subject, hostId: t.hostId, workspaceId: t.workspaceId,
        runtimeId: t.runtimeId, threadId: thread)
    }
  }
  func encoded(_ t: AgentRuntimeTarget = target, _ kind: RuntimeIdentityKeyCache.Kind,
    _ thread: String? = "thread", _ project: String? = nil) -> String {
    cache.value(t, kind: kind, threadId: thread, projectId: project) {
      encodes += 1
      return reference(t, kind, thread, project)
    }
  }
  let storage = encoded(target, .cache), reading = encoded(target, .reading)
  let native = encoded(target, .nativeReading)
  for _ in 0..<2000 {
    precondition(encoded(target, .cache) == storage)
    precondition(encoded(target, .reading) == reading)
    precondition(encoded(target, .nativeReading) == native)
  }
  precondition(encodes == 3, "Warm workspace reconciliation must not re-encode identity tuples")
  var legacyAccount = target
  legacyAccount.account = AgentAccount(authority: "old-authority", subject: "changed-subject")
  precondition(encoded(legacyAccount, .cache) == storage && encodes == 3,
    "Codex account metadata cannot split a machine identity or invalidate its pure key")
  var targets = [target]
  var changed = target; changed.hostId += "/other"; targets.append(changed)
  changed = target; changed.workspaceId += "/other"; targets.append(changed)
  changed = target; changed.runtimeId += "/other"; targets.append(changed)
  changed = target; changed.agentId = "other-agent"; targets.append(changed)
  changed.account = AgentAccount(authority: "authority", subject: "one"); targets.append(changed)
  changed.account?.subject = "two"; targets.append(changed)
  changed.account?.authority = "other-authority"; targets.append(changed)
  for t in targets {
    for kind in [RuntimeIdentityKeyCache.Kind.cache, .reading, .nativeReading] {
      for selector in [(nil, nil), (nil, ""), (nil, "project"), ("", nil), ("thread", nil)] as [(String?, String?)] {
        let expected = reference(t, kind, selector.0, selector.1)
        precondition(encoded(t, kind, selector.0, selector.1) == expected)
        let actual: String
        switch kind {
        case .cache: actual = SharedMobileRules.machineCacheKey(t, threadId: selector.0, projectId: selector.1)
        case .reading: actual = SharedMobileRules.machineReadingKey(t, threadId: selector.0)
        case .nativeReading: actual = SharedMobileRules.nativeReadingKey(t, threadId: selector.0)
        }
        precondition(actual == expected, "Memoized facade must preserve the original codec bytes")
      }
    }
  }
  precondition(encoded(target, .cache) != encoded(targets[1], .cache))
  precondition(encoded(targets[5], .reading) != encoded(targets[6], .reading))
  let tiny = RuntimeIdentityKeyCache(capacity: 2)
  var coldEncodes = 0
  for id in ["a", "b", "c", "a"] {
    let value = tiny.value(target, kind: .cache, threadId: id) { coldEncodes += 1; return id }
    precondition(value == id)
  }
  precondition(coldEncodes == 4, "Bounded eviction must recompute, never reuse another target")
  DispatchQueue.concurrentPerform(iterations: 200) { index in
    let id = String(index % 12)
    precondition(tiny.value(target, kind: .reading, threadId: id) { id } == id)
  }
  print("Runtime identity key cache passed: 6000 warm reuses; full-target isolation and bounded concurrent eviction")
}
