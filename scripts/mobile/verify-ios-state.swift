import Foundation
import MobileCore

struct PolicyAccess: Decodable {
  let demo: Bool, online: Bool, runtimeConnected: Bool?, capabilities: [String]
  let threadSelected: Bool, hasProject: Bool, projectlessDraft: Bool, canControl: Bool, testing: Bool
  var core: SessionAccess { SessionAccess(demo: demo, online: online,
    runtimeConnected: runtimeConnected.map { KotlinBoolean(bool: $0) }, capabilities: capabilities,
    threadSelected: threadSelected, hasProject: hasProject, projectlessDraft: projectlessDraft, canControl: canControl, testing: testing) }
}
struct PolicyState: Decodable {
  let historyLoading: Bool, sending: Bool, stopping: Bool, replyPending: Bool, creationPending: Bool, importing: Bool, running: Bool
  let preferredReplyMode: String
  var core: SubmissionState { SubmissionState(historyLoading: historyLoading, sending: sending, stopping: stopping,
    replyPending: replyPending, creationPending: creationPending, importing: importing, running: running, preferredReplyMode: preferredReplyMode) }
}
struct PolicyExpected: Decodable { let writable: Bool, compose: Bool, submit: Bool, configure: Bool, plan: Bool, mode: String }
struct PolicyCase: Decodable { let name: String, access: PolicyAccess, state: PolicyState, text: String, attachmentCount: Int32, expected: PolicyExpected }
struct ProjectionMessage: Decodable {
  let id: String, role: String, text: String, kind: String?, status: String?, runId: String?, replyPhase: String?, replyMessageId: String?
  var core: TimelineMessage { TimelineMessage(id: id, role: role, text: text, kind: kind, status: status, runId: runId, replyPhase: replyPhase, replyMessageId: replyMessageId) }
}
struct ProjectionRow: Decodable, Equatable { let kind: String, id: String, indices: [Int], text: String? }
struct ProjectionExpected: Decodable { let rows: [ProjectionRow], hasEarlierMessages: Bool }
struct ProjectionCase: Decodable { let name: String, messages: [ProjectionMessage], limit: Int32, activeRunId: String?, running: Bool, expected: ProjectionExpected }
struct ContractCase: Decodable { let name: String, definition: String, json: String, valid: Bool }
struct ToolCase: Decodable { let operation: String, expected: String, messages: [ProjectionMessage], value: String?, flag: Bool }
struct DiscoveryCase: Decodable { let name: String, json: String, valid: Bool }
struct StateVectors: Decodable { let policy: [PolicyCase], timeline: [ProjectionCase], contracts: [ContractCase], http: [ContractCase], toolPresentation: [ToolCase], discovery: [DiscoveryCase] }

func verifyStateVectors(_ path: String) throws {
  try verifyChatManagementVectors(path)
  try verifyChatSearchVectors(path)
  try verifySidebarVectors(path)
  try verifyRunningCountVectors(path)
  try verifyArchiveEligibilityVectors(path)
  try verifyQueueOwnershipVectors(path)
  let vectors = try JSONDecoder().decode(StateVectors.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
  for test in vectors.discovery { precondition((CodexDiscoveryContract.shared.validate(json: test.json) == nil) == test.valid, test.name) }
  print("Swift/Kotlin Codex discovery boundary passed: \(vectors.discovery.count) cases")
  for test in vectors.toolPresentation {
    let p = ToolPresentation.shared
    let actual: String
    switch test.operation {
    case "summary": actual = p.summary(messages: test.messages.map(\.core))
    case "title": actual = p.title(message: test.messages[0].core, single: test.flag)
    case "compaction": actual = p.compaction(status: test.value, live: test.flag)
    case "status": actual = p.status(status: test.value)
    case "style": let style = p.style(kind: test.value); actual = [style.category, style.verb, style.detailTitle].joined(separator: "|")
    default: preconditionFailure("Unknown presentation operation")
    }
    precondition(actual == test.expected, test.operation)
  }
  print("Swift/Kotlin tool presentation passed: \(vectors.toolPresentation.count) cases")
  for test in vectors.policy {
    let a = test.access.core, s = test.state.core, e = test.expected, p = SessionPolicies.shared
    precondition(p.writable(access: a) == e.writable && p.compose(access: a) == e.compose, test.name)
    precondition(p.submit(access: a, state: s, text: test.text, attachmentCount: test.attachmentCount) == e.submit, test.name)
    precondition(p.configureGeneration(access: a, state: s) == e.configure && p.selectPlan(access: a, state: s) == e.plan, test.name)
    precondition(p.activeReplyMode(access: a, preferred: s.preferredReplyMode) == e.mode, test.name)
  }
  for test in vectors.timeline {
    let actual = MessageProjection.shared.project(messages: test.messages.map(\.core), messageLimit: test.limit, activeRunId: test.activeRunId, running: test.running)
    let rows = actual.rows.map { ProjectionRow(kind: $0.kind, id: $0.id, indices: $0.indices.map { $0.intValue }, text: $0.text) }
    precondition(rows == test.expected.rows && actual.hasEarlierMessages == test.expected.hasEarlierMessages, test.name)
  }
  for test in vectors.contracts { precondition((DomainContract.shared.validate(json: test.json, definition: test.definition) == nil) == test.valid, test.name) }
  for test in vectors.http { precondition((CodexHttpContract.shared.validate(json: test.json, definition: test.definition) == nil) == test.valid, test.name) }
  print("Swift/Kotlin state boundary passed: \(vectors.policy.count) policies, \(vectors.timeline.count) projections, \(vectors.contracts.count) contracts")
  print("Swift/Kotlin Codex HTTP boundary passed: \(vectors.http.count) cases")
}

private struct ChatSearchVector: Decodable {
  let name: String, text: String, query: String, expectedSnippet: String
  let expectedRanges: [[Int32]], expectedSnippetRanges: [[Int32]]
}
private struct ChatSearchVectors: Decodable { let chatSearch: [ChatSearchVector] }
func verifyChatSearchVectors(_ path: String) throws {
  let rows = try JSONDecoder().decode(ChatSearchVectors.self, from: Data(contentsOf: URL(fileURLWithPath: path))).chatSearch
  func spans(_ values: [ChatSearchSpan]) -> [[Int32]] { values.map { [$0.start, $0.end] } }
  for row in rows {
    let native = NativeChatSearchText.shared
    let snippet = ChatSearchRules.shared.snippet(text: row.text, value: row.query, native: native)
    precondition(spans(ChatSearchRules.shared.ranges(text: row.text, value: row.query, native: native)) == row.expectedRanges, row.name)
    precondition(snippet.text == row.expectedSnippet && spans(snippet.spans) == row.expectedSnippetRanges, row.name)
    precondition(ChatSearchRules.shared.matches(text: row.text, value: row.query, native: native) == !row.expectedRanges.isEmpty, row.name)
  }
  print("Swift/Kotlin chat search passed: \(rows.count) native Unicode/snippet cases")
}

private final class ReplyBox: @unchecked Sendable {
  private let lock = NSLock()
  private var reply: HistoryReply?
  private var deliveries = 0
  var value: HistoryReply? { lock.lock(); defer { lock.unlock() }; return reply }
  var count: Int { lock.lock(); defer { lock.unlock() }; return deliveries }
  func deliver(_ value: HistoryReply) { lock.lock(); reply = value; deliveries += 1; lock.unlock() }
}
private final class NativeCancellation: NSObject, MobileCancellation {
  let onCancel: () -> Void
  init(_ onCancel: @escaping () -> Void = {}) { self.onCancel = onCancel }
  func cancel() { onCancel() }
}
private final class BoundaryGateway: NSObject, MobileHistoryGateway {
  let action: (String, @escaping (HistoryReply) -> Void) -> MobileCancellation
  init(_ action: @escaping (String, @escaping (HistoryReply) -> Void) -> MobileCancellation) { self.action = action }
  func read(targetKey: String, threadId: String, cursor: String?, completion: @escaping (HistoryReply) -> Void) -> MobileCancellation {
    action(targetKey, completion)
  }
}
private func waitFor(_ predicate: () -> Bool, seconds: Double = 5) {
  let deadline = Date().addingTimeInterval(seconds)
  while !predicate(), Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.01)) }
  precondition(predicate(), "Asynchronous boundary timed out")
}
func verifyAsyncBoundary() {
  let result = ReplyBox()
  var gateway: BoundaryGateway? = BoundaryGateway { key, completion in
    completion(HistoryReply(targetKey: key, json: "first", failure: nil))
    completion(HistoryReply(targetKey: key, json: "duplicate", failure: nil))
    return NativeCancellation()
  }
  weak var releasedGateway = gateway
  var operation: MobileCancellation? = HistoryRequests.shared.start(gateway: gateway!, targetKey: "synthetic-target", threadId: "synthetic-thread", cursor: nil) { reply in
    // Exactly the dispatch used by the Swift production facade.
    Task { @MainActor in precondition(Thread.isMainThread); result.deliver(reply) }
  }
  gateway = nil
  waitFor { result.value != nil }
  precondition(result.count == 1 && result.value?.json == "first")
  operation = nil
  // ARC disposal can wait for Kotlin GC; allow allocation-driven collection.
  let deadline = Date().addingTimeInterval(20)
  while releasedGateway != nil, Date() < deadline {
    autoreleasepool { for _ in 0..<10_000 { _ = TargetScope(agentId: "synthetic", accountAuthority: nil, accountSubject: nil, hostId: "h", workspaceId: "w", runtimeId: "r") } }
    RunLoop.current.run(until: Date().addingTimeInterval(0.02))
  }
  precondition(releasedGateway == nil, "Completed boundary retained the Swift gateway")

  let waiting = ReplyBox(), cancelled = ReplyBox(), finished = ReplyBox()
  let pendingGateway = BoundaryGateway { key, completion in
    waiting.deliver(HistoryReply(targetKey: key, json: nil, failure: nil))
    return NativeCancellation {
      cancelled.deliver(HistoryReply(targetKey: key, json: nil, failure: nil))
      completion(HistoryReply(targetKey: key, json: "late", failure: nil))
    }
  }
  let pending = HistoryRequests.shared.start(gateway: pendingGateway, targetKey: "synthetic-target", threadId: "thread", cursor: nil) { finished.deliver($0) }
  waitFor { waiting.value != nil }; pending.cancel()
  waitFor { cancelled.value != nil && finished.value != nil }
  precondition(finished.count == 1 && finished.value?.failure?.code == "cancelled")
  let wrong = ReplyBox()
  _ = HistoryRequests.shared.start(gateway: BoundaryGateway { _, completion in
    completion(HistoryReply(targetKey: "other-target", json: "wrong", failure: nil)); return NativeCancellation()
  }, targetKey: "synthetic-target", threadId: "thread", cursor: nil) { wrong.deliver($0) }
  waitFor { wrong.value != nil }; precondition(wrong.value?.failure?.code == "wrong-target")
  print("Swift/Kotlin async boundary passed: one delivery, MainActor, ARC release, cancellation, late reply and typed error")
}

precondition(SessionPolicies.shared.codexCapabilities(capabilities: ["history:read"], connectionMode: "desktop-runtime") == ["history:read", "runtime:control-required", "threads:create:staged"])
precondition(SessionPolicies.shared.codexCapabilities(capabilities: ["reply"], connectionMode: "shared-runtime") == ["reply"])
print("Swift/Kotlin Codex capability normalization passed")

struct SidebarThreadVector: Decodable {
  let id: String, hostId: String, projectId: String?, projectIds: [String], archived: Bool
  var core: SidebarThreadState { SidebarThreadState(id: id, hostId: hostId, projectId: projectId, projectIds: projectIds, archived: archived) }
}
struct SidebarProjectVector: Decodable {
  let id: String, hostId: String
  var core: SidebarProjectState { SidebarProjectState(id: id, hostId: hostId) }
}
struct SidebarPinVector: Decodable, Equatable {
  let kind: String, id: String
  var core: SidebarPinnedItem { SidebarPinnedItem(kind: kind, id: id) }
}
struct SidebarSourceVector: Decodable {
  let version: Int32
  let pinned: [SidebarPinVector]?, recentThreadIds: [String]?, projectIds: [String]?, projectThreadIds: [String: [String]]?
  var core: SidebarSource {
    guard version == 1 else { return SidebarSource(version: version, pinned: [], recentThreadIds: [], projectIds: [], projectThreads: []) }
    return SidebarSource(version: version, pinned: pinned!.map(\.core), recentThreadIds: recentThreadIds!, projectIds: projectIds!,
      projectThreads: projectThreadIds!.map { SidebarProjectThreads(projectId: $0.key, threadIds: $0.value) })
  }
}
struct SidebarInputVector: Decodable {
  let hostId: String, threads: [SidebarThreadVector], projects: [SidebarProjectVector], pinnedThreadIds: [String], source: SidebarSourceVector?
}
struct SidebarExpectedVector: Decodable {
  let pinned: [SidebarPinVector], recentThreadIds: [String], projectIds: [String], projectThreadIds: [String: [String]]
}
struct SidebarCaseVector: Decodable { let name: String, input: SidebarInputVector, expected: SidebarExpectedVector }
struct SidebarVisibilityVector: Decodable {
  let name: String, ids: [String], expanded: Bool, searching: Bool, expectedExpanded: Bool, expected: [String]
}
struct SidebarVectorFile: Decodable { let sidebar: [SidebarCaseVector], sidebarVisibility: [SidebarVisibilityVector] }

func verifySidebarVectors(_ path: String) throws {
  let file = try JSONDecoder().decode(SidebarVectorFile.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
  for test in file.sidebar {
    let input = test.input
    let result = WorkspaceSidebarRules.shared.order(hostId: input.hostId, threads: input.threads.map(\.core), projects: input.projects.map(\.core),
      pinnedThreadIds: input.pinnedThreadIds, source: input.source?.core)
    precondition(result.pinned.map { SidebarPinVector(kind: $0.kind, id: $0.id) } == test.expected.pinned, test.name)
    precondition(result.recentThreadIds == test.expected.recentThreadIds && result.projectIds == test.expected.projectIds, test.name)
    precondition(Dictionary(uniqueKeysWithValues: result.projectThreads.map { ($0.projectId, $0.threadIds) }) == test.expected.projectThreadIds, test.name)
  }
  for test in file.sidebarVisibility {
    precondition(WorkspaceSidebarRules.shared.expanded(preference: test.expanded, searching: test.searching) == test.expectedExpanded, test.name)
    precondition(WorkspaceSidebarRules.shared.visibleIds(ids: test.ids, expanded: test.expanded, searching: test.searching) == test.expected, test.name)
  }
  print("Swift/Kotlin sidebar ordering passed: \(file.sidebar.count) projections, \(file.sidebarVisibility.count) visibility cases")
}

private struct RunningCountConversation: Decodable {
  let id: String, hostId: String, state: String
  var core: ConversationActivity { ConversationActivity(id: id, hostId: hostId, state: state) }
}
private struct RunningCountInput: Decodable {
  let hostId: String, online: Bool, demo: Bool, workspaceAvailable: Bool, connectionMode: String?
  let conversations: [RunningCountConversation]
}
private struct RunningCountCase: Decodable { let name: String, input: RunningCountInput, expected: Int? }
func verifyRunningCountVectors(_ path: String) throws {
  struct Vectors: Decodable { let runningCounts: [RunningCountCase] }
  let rows = try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
  for row in rows.runningCounts {
    let i = row.input
    precondition(WorkspaceSidebarRules.shared.runningCount(hostId: i.hostId, online: i.online, demo: i.demo,
      workspaceAvailable: i.workspaceAvailable, connectionMode: i.connectionMode, conversations: i.conversations.map(\.core))?.intValue == row.expected, row.name)
  }
  print("Swift/Kotlin running counts passed: \(rows.runningCounts.count) cases")
}

func verifyArchiveEligibilityVectors(_ path: String) throws {
  struct Input: Decodable { let knownTarget: Bool, archived: Bool, singleBusy: Bool, singlePending: Bool, batchPending: Bool }
  struct Row: Decodable { let name: String, input: Input, expected: Bool }
  struct Vectors: Decodable { let workspaceArchiveEligibility: [Row] }
  let rows = try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
  for row in rows.workspaceArchiveEligibility {
    let i = row.input
    precondition(WorkspaceArchiveRules.shared.eligible(knownTarget: i.knownTarget, archived: i.archived,
      singleBusy: i.singleBusy, singlePending: i.singlePending, batchPending: i.batchPending) == row.expected, row.name)
  }
  print("Swift/Kotlin archive eligibility passed: \(rows.workspaceArchiveEligibility.count) cases")
}

struct ChatLifecycleCase: Decodable {
  let name: String, state: String, lastTurnStatus: String?, archived: Bool, online: Bool, unconfirmed: Bool, code: String, lastKnown: Bool
}
struct ChatAvailabilityCase: Decodable {
  let name: String, demo: Bool, online: Bool, supported: Bool, knownTarget: Bool, busy: Bool, pending: Bool, expected: Bool
}
func verifyChatManagementVectors(_ path: String) throws {
  struct Vectors: Decodable { let chatLifecycle: [ChatLifecycleCase], chatManagementAvailability: [ChatAvailabilityCase] }
  let rows = try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
  for row in rows.chatLifecycle {
    let status = ChatManagementRules.shared.taskStatus(state: row.state, lastTurnStatus: row.lastTurnStatus, archived: row.archived, online: row.online, unconfirmed: row.unconfirmed)
    precondition(status.code == row.code && status.lastKnown == row.lastKnown, row.name)
  }
  for row in rows.chatManagementAvailability {
    precondition(ChatManagementRules.shared.canManage(demo: row.demo, online: row.online, supported: row.supported, knownTarget: row.knownTarget, busy: row.busy, pending: row.pending) == row.expected, row.name)
  }
  precondition(rows.chatLifecycle.count == 17 && rows.chatManagementAvailability.count == 7)
  print("Swift/Kotlin chat management passed: 17 lifecycle and 7 availability vectors")
}

do {
  let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
  let root = try JSONSerialization.jsonObject(with: data) as! [String: Any]
  let rows = root["workspaceArchive"] as! [[String: Any]]
  func wire(_ value: Any) throws -> String { String(data: try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), encoding: .utf8)! }
  for row in rows {
    let previous = row["previous"] as? [String: Any]
    let merged = WorkspaceArchiveRules.shared.merge(original: try wire(row["original"]!), previous: try previous.map(wire), incoming: try wire(row["incoming"]!))
    if let expected = row["expected"] as? [String: Any] {
      precondition(merged != nil)
      let actual = try JSONSerialization.jsonObject(with: Data(merged!.utf8)) as! NSDictionary
      precondition(actual.isEqual(to: expected), row["name"] as! String)
    } else { precondition(merged == nil, row["name"] as! String) }
  }
  precondition(rows.count == 12)
  precondition(WorkspaceArchiveRules.shared.select(current: ["hidden"], eligible: (0..<120).map { "chat-\($0)" }, allSelected: false).count == 100)
  print("Swift/Kotlin workspace archive passed: 12 exact receipt/terminal vectors and bounded selection")
}

private struct QueueOwnershipCase: Decodable {
  let name: String, state: String, source: String?, present: Bool?, disposition: String?, active: Bool, legacy: Bool
}
private struct QueueActionCase: Decodable {
  let name: String, state: String, source: String?, present: Bool?, disposition: String?
  let online: Bool, writable: Bool, pending: Bool, execute: Bool, supportsExecute: Bool, canExecuteNow: Bool?, allowed: Bool
}
private struct NoticeReceipt: Decodable {
  let id: String, state: String, supersededByTurnId: String?, queueSyncError: String?
  var core: ReceiptState { ReceiptState(id: id, state: state, supersededByTurnId: supersededByTurnId, queueSyncError: queueSyncError) }
}
private struct NoticeCase: Decodable { let name: String, receipts: [NoticeReceipt], dismissed: [String], pendingId: String?, expected: [Int] }
func verifyQueueOwnershipVectors(_ path: String) throws {
  struct Vectors: Decodable { let queueOwnership: [QueueOwnershipCase], queueActions: [QueueActionCase], receipts: [NoticeCase] }
  let rows = try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
  for r in rows.queueOwnership {
    precondition(QueueRules.shared.legacy(state: r.state, source: r.source, disposition: r.disposition) == r.legacy, r.name)
    precondition(QueueRules.shared.active(state: r.state, source: r.source, present: r.present.map { KotlinBoolean(bool: $0) }, disposition: r.disposition) == r.active, r.name)
  }
  for r in rows.queueActions {
    precondition(QueueRules.shared.actionAllowed(state: r.state, source: r.source, present: r.present.map { KotlinBoolean(bool: $0) }, disposition: r.disposition,
      online: r.online, writable: r.writable, pending: r.pending, execute: r.execute, supportsExecute: r.supportsExecute,
      canExecuteNow: r.canExecuteNow.map { KotlinBoolean(bool: $0) }) == r.allowed, r.name)
  }
  for r in rows.receipts {
    let actual = SessionPolicies.shared.noticeIndices(receipts: r.receipts.map(\.core), dismissed: r.dismissed, pendingId: r.pendingId).map { $0.intValue }
    precondition(actual == r.expected, r.name)
  }
  print("Swift/Kotlin queue ownership passed: \(rows.queueOwnership.count) cases")
  print("Swift/Kotlin queue actions passed: \(rows.queueActions.count) cases")
  print("Swift/Kotlin receipt notices passed: \(rows.receipts.count) cases")
}
