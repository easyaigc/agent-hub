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
