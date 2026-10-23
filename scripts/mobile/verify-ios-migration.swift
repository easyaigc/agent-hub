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
