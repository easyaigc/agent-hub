import Foundation
import Combine

// PRODUCTION_APPROVAL_MODELS
struct Client { var endpoint: String? = "https://example.invalid"; var online = true; var codexDirect: Certificate? = Certificate(); var target = "agent/host/workspace/runtime" }
struct Certificate { var fingerprint: String? = "certificate-v1" }
struct Runtime { var approvals: [Approval]; var generationSettings: Generation? = Generation() }
struct Generation { var model: String? = "model" }
struct Access { var capabilities = ["approvals"] }
struct SessionPolicies { static let shared = Self(); func supports(access: Access, capability: String) -> Bool { access.capabilities.contains(capability) } }
struct AISharingRules { static let shared = Self(); func requiresContentGrant(decision: String) -> Bool { decision == "" || decision == "accept" || decision == "acceptForSession" } }
enum L10n { static func tr(_ value: String) -> String { value } }
enum HostServiceError: Error { case message(String) }
struct AISharingScope { let target: String }
struct CommandResult { let ok: Bool }
@MainActor final class UIApplication { enum State { case active, background }; static let shared = UIApplication(); var applicationState = State.active }
@MainActor final class FakeAgent {
  struct Call { let host: Client; let approval: Approval; let decision: String; let answers: [String: [String]]; let continuation: CheckedContinuation<CommandResult, Error> }
  var calls: [Call] = []
  func approve(_ host: Client, approval: Approval, decision: String, answers: [String: [String]]) async throws -> CommandResult {
    try await withCheckedThrowingContinuation { continuation in calls.append(Call(host: host, approval: approval, decision: decision, answers: answers, continuation: continuation)) }
  }
  func finish(_ index: Int, ok: Bool = true, fail: Bool = false) {
    if fail { calls[index].continuation.resume(throwing: HostServiceError.message("late failure")) }
    else { calls[index].continuation.resume(returning: CommandResult(ok: ok)) }
  }
}
@MainActor enum AgentClients { static var agent = FakeAgent(); static func resolve(_ client: Client) throws -> FakeAgent { agent } }
@MainActor final class Harness {
  var targetKey = "full-target:thread", selectedThreadId: String? = "thread"
  var client: Client? = Client(), runtime: Runtime?
  var historyGeneration = UUID(), clientGeneration = UUID(), loadedHistoryGeneration: UUID?
  var errorRevision: UInt64 = 0, isWritable = true, accessPolicy = Access(), sharing = true
  var error: String?, refreshes: [String] = [], text = "independent draft", attachments = ["independent file"]
  @Published private var answeringApprovals: [UUID: ApprovalAnswerFlight] = [:]
  func readingClient(_ host: Client) -> Client { host }
  func mobileReadingTarget(_ host: Client) -> String { host.target }
  func aiSharingScope(_ host: Client, model: String?) -> AISharingScope { AISharingScope(target: host.target) }
  func requireAISharing(_ host: Client, scope: AISharingScope) -> Bool { sharing }
  func validateAISharing(_ host: Client, scope: AISharingScope) throws { if !sharing || host.target != scope.target { throw HostServiceError.message("grant revoked") } }
  func presentError(_ failure: Error) { error = String(describing: failure); errorRevision &+= 1 }
  func loadHistory(threadId: String) async { refreshes.append(threadId) }
  // PRODUCTION_APPROVAL_BOUNDARY
  init(_ approval: Approval) { runtime = Runtime(approvals: [approval]); loadedHistoryGeneration = historyGeneration }
}
@MainActor func sample(id: String = "same-id", detail: String = "old", kind: String = "approval", async: Bool = false) -> Approval {
  Approval(id: id, threadId: "thread", turnId: "turn", kind: kind, title: "title", detail: detail,
    questions: kind == "question" ? [ApprovalQuestion(id: "q", question: "question")] : [],
    decisions: kind == "question" ? ["skip"] : ["accept", "cancel"], interactionMode: async ? "async" : nil)
}
@MainActor func settle() async { for _ in 0..<8 { await Task.yield() }; try? await Task.sleep(for: .milliseconds(10)) }
@MainActor func ready(_ count: Int) async { for _ in 0..<100 { if AgentClients.agent.calls.count == count { return }; await settle() }; preconditionFailure("Expected exact dispatch count") }
@MainActor func fresh(_ approval: Approval) -> Harness { AgentClients.agent = FakeAgent(); UIApplication.shared.applicationState = .active; return Harness(approval) }
@main struct Run {
  @MainActor static func main() async {
    let a = sample(), replacement = sample(detail: "new schema")
    var checks = 0
    for mutate in [
      { (h: Harness) in h.runtime = Runtime(approvals: [replacement]) },
      { (h: Harness) in h.loadedHistoryGeneration = nil },
      { (h: Harness) in h.client?.online = false },
      { (h: Harness) in h.isWritable = false },
      { (h: Harness) in h.accessPolicy.capabilities = [] },
      { (h: Harness) in h.sharing = false },
    ] {
      let h = fresh(a); mutate(h); h.answer(a, decision: "accept"); await settle()
      precondition(AgentClients.agent.calls.isEmpty && !h.isAnswering(a)); checks += 1
    }
    do {
      let h = fresh(a), context = h.approvalActionContext; h.historyGeneration = UUID(); h.loadedHistoryGeneration = h.historyGeneration
      h.answer(a, decision: "accept", expectedContext: context); h.answer(a, decision: "decline"); await settle()
      precondition(AgentClients.agent.calls.isEmpty); checks += 2
    }
    do {
      let h = fresh(a); h.answer(a, decision: "accept"); h.answer(a, decision: "accept"); await ready(1)
      precondition(h.isAnswering(a)); AgentClients.agent.finish(0); await settle()
      precondition(!h.isAnswering(a) && h.refreshes == ["thread"] && h.text == "independent draft" && h.attachments == ["independent file"]); checks += 2
    }
    do {
      let h = fresh(a); h.answer(a, decision: "accept"); h.sharing = false; await settle()
      precondition(AgentClients.agent.calls.isEmpty && !h.isAnswering(a)); checks += 1
    }
    // The original wait survives navigation, but its late callback cannot mutate a returned view.
    do {
      let h = fresh(a); h.answer(a, decision: "accept"); await ready(1)
      h.targetKey = "other"; h.historyGeneration = UUID(); h.targetKey = "full-target:thread"; h.historyGeneration = UUID()
      h.answer(a, decision: "accept"); await settle(); precondition(AgentClients.agent.calls.count == 1)
      AgentClients.agent.finish(0, fail: true); await settle()
      precondition(h.error == nil && h.refreshes.isEmpty && !h.isAnswering(a)); checks += 2
    }
    // A task invalidated before its first dispatch cannot write at all.
    do {
      let h = fresh(a); h.answer(a, decision: "accept"); h.historyGeneration = UUID(); await settle()
      precondition(AgentClients.agent.calls.isEmpty && !h.isAnswering(a)); checks += 1
    }
    for change in [
      { (h: Harness) in h.clientGeneration = UUID() },
      { (h: Harness) in h.client?.endpoint = "https://changed.invalid" },
      { (h: Harness) in h.client?.codexDirect?.fingerprint = "new-certificate" },
      { (h: Harness) in h.client?.target = "other-runtime" },
      { (_: Harness) in UIApplication.shared.applicationState = .background },
    ] {
      let h = fresh(a); h.answer(a, decision: "accept"); await ready(1); change(h); AgentClients.agent.finish(0, fail: true); await settle()
      precondition(h.error == nil && h.refreshes.isEmpty && !h.isAnswering(a)); checks += 1
    }
    // A changed projection keeps the same remote write deduplicated and rejects old callback authority.
    do {
      let h = fresh(a); h.answer(a, decision: "accept"); await ready(1); h.runtime = Runtime(approvals: [replacement])
      h.answer(replacement, decision: "accept"); await settle(); precondition(AgentClients.agent.calls.count == 1 && h.isAnswering(replacement))
      AgentClients.agent.finish(0, fail: true); await settle(); precondition(h.error == nil && h.refreshes.isEmpty && !h.isAnswering(replacement)); checks += 2
    }
    for field in ["sourceItemId", "notificationRequestId"] {
      var changed = a
      if field == "sourceItemId" { changed.sourceItemId = "new-source" } else { changed.notificationRequestId = "new-notification" }
      let h = fresh(a); h.answer(a, decision: "accept"); await ready(1); h.runtime = Runtime(approvals: [changed])
      h.answer(changed, decision: "accept"); await settle(); precondition(AgentClients.agent.calls.count == 1)
      AgentClients.agent.finish(0, fail: true); await settle(); precondition(h.error == nil && h.refreshes.isEmpty && !h.isAnswering(changed)); checks += 1
    }
    // A distinct original request in the same turn owns a separate wait.
    do {
      let replacement = sample(id: "new-request-id", detail: "new schema")
      let h = fresh(a); h.answer(a, decision: "accept"); await ready(1); h.runtime = Runtime(approvals: [replacement])
      precondition(!h.isAnswering(replacement) && !h.canEditApproval(a, context: h.approvalActionContext))
      h.answer(replacement, decision: "accept"); await ready(2); AgentClients.agent.finish(0, fail: true); await settle()
      precondition(h.isAnswering(replacement) && h.error == nil && h.refreshes.isEmpty)
      AgentClients.agent.finish(1); await settle(); precondition(!h.isAnswering(replacement) && h.refreshes == ["thread"]); checks += 3
    }
    do {
      let h = fresh(a); h.answer(a, decision: "accept"); await ready(1); h.presentError(HostServiceError.message("newer error")); let value = h.error
      AgentClients.agent.finish(0, fail: true); await settle(); precondition(h.error == value); checks += 1
    }
    do {
      let q = sample(kind: "question", async: true), h = fresh(q)
      for answers in [["wrong": ["text"]], ["q": ["   "]], ["q": []]] { h.answer(q, decision: "", answers: answers) }
      await settle(); precondition(AgentClients.agent.calls.isEmpty)
      var answers = ["q": ["captured answer"]]; h.answer(q, decision: "", answers: answers); answers["q"] = ["new local edit"]
      await ready(1); precondition(AgentClients.agent.calls[0].answers == ["q": ["captured answer"]]); AgentClients.agent.finish(0); await settle(); checks += 2
    }
    do {
      let q = sample(kind: "question"), h = fresh(q); h.answer(q, decision: "skip"); await settle(); precondition(AgentClients.agent.calls.isEmpty); checks += 1
      let async = sample(kind: "question", async: true), next = fresh(async); next.answer(async, decision: "skip"); await ready(1); AgentClients.agent.finish(0); await settle(); precondition(next.refreshes == ["thread"]); checks += 1
    }
    print("PASS: \(checks) production approval dispatch/callback boundaries (synthetic transport; not native model acceptance)")
  }
}
