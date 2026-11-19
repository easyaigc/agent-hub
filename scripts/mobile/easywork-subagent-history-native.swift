import Foundation
import CoreFoundation
import CryptoKit

enum HostServiceError: Error { case invalidResponse, wrongHost, message(String) }
struct Client { let id: String }
struct WorkspaceSnapshot { var connectionMode: String?; var capabilities: [String]? }
struct AgentAccount: Codable { var authority: String; var subject: String }
struct EasyWorkTarget: Codable {
  var account: AgentAccount; var desktopDeviceId: String; var workspaceId: String; var runtimeId: String
  var dictionary: [String: Any] { (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(self))) as? [String: Any] ?? [:] }
}
extension Data { var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") } }
func easyWorkJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .withoutEscapingSlashes, .fragmentsAllowed]) }
struct AgentJSONValue: Codable, Equatable {
  let value: String
  init(from decoder: Decoder) throws { value = "value" }
  func encode(to encoder: Encoder) throws { var c = encoder.singleValueContainer(); try c.encode(value) }
}
enum AgentContentPart: Codable, Equatable {
  case text(String), extensionData(namespace: String, data: [String: AgentJSONValue]), resource(id: String, name: String, mediaType: String), tool(id: String, name: String, status: String, input: String?, output: String?), diff(id: String, text: String)
}
struct RemoteAttachment: Codable, Equatable {}
struct PlanArtifactSnapshot: Codable, Equatable {}
enum EasyWorkPlanArtifactProjection { static func decode(_ data: [String: Any]) -> PlanArtifactSnapshot? { PlanArtifactSnapshot() } }
enum L10n { static func tr(_ text: String) -> String { text } }
struct ChatMessage: Codable, Equatable {
  let id: String; let role: String; var text: String; var files: [String] = []; var kind: String?; var status: String?; var detail: String?; var attachments: [RemoteAttachment]?; var blocks: [AgentContentPart]? = nil; var runId: String? = nil; var replyMessageId: String? = nil; var planArtifact: PlanArtifactSnapshot? = nil
}
struct SubagentHistorySnapshot {
  struct Child { let id: String; let title: String; let status: String }
  let version: Int; let hostId: String; let readTarget: EasyWorkTarget; let parentThreadId: String; let messageId: String; let children: [Child]; let childId: String; let threadId: String; let source: String; let messages: [ChatMessage]; let nextCursor: String?
}
struct Activity { let id: String; let title: String }
final class EasyWorkRemoteSession {
  var target: EasyWorkTarget; var page: [String: Any]; var calls: [String] = []
  init(_ target: EasyWorkTarget, _ page: [String: Any]) { self.target = target; self.page = page }
  func synchronizedThread(_ id: String) async throws -> Any { calls.append("thread/synchronize"); return page }
  func subagentActivities(_ data: Data, threadId: String) async -> [Activity] { [] }
  func request(_ method: String, _ params: [String: Any]) async throws -> Any { calls.append(method); throw HostServiceError.invalidResponse }
}
var testSession: EasyWorkRemoteSession!
func session(_ client: Client) async throws -> EasyWorkRemoteSession { testSession }
func object(_ value: Any) throws -> [String: Any] { guard let value = value as? [String: Any] else { throw HostServiceError.invalidResponse }; return value }
func readingTarget(_ target: EasyWorkTarget) -> EasyWorkTarget { target }
func resource(_ client: Client, threadId: String, data: [String: Any]) -> RemoteAttachment? { RemoteAttachment() }
protocol AgentClient {
  func supportsSubagentHistory(_ client: Client, workspace: WorkspaceSnapshot?) -> Bool
  func subagentHistory(_ client: Client, parentThreadId: String, messageId: String, childId: String?, cursor: String?) async throws -> SubagentHistorySnapshot
}
// PRODUCTION_BOUNDARIES
struct HostClient: AgentClient {}
func check(_ condition: @autoclosure () -> Bool, _ label: String) { if !condition() { fatalError(label) } }
func rejects(_ label: String, _ block: () throws -> Void) { do { try block(); fatalError("accepted \(label)") } catch {} }
func page(_ messages: [[String: Any]], revision: Any = 7, root: String = "root", child: String = "child") -> [String: Any] {
  let run: [String: Any] = ["id": child, "kind": "child", "threadId": "parent", "rootRunId": root, "branchRevision": revision, "status": "failed", "checkpoint": ["subagentTranscript": ["messages": messages]], "error": ["message": "failure"]]
  return ["branchRevision": revision, "snapshot": ["thread": ["id": "parent", "branchRevision": revision], "subagentRunTrees": [["root": ["id": root, "threadId": "parent", "branchRevision": revision], "runs": [run]]]]]
}
@main struct Main {
 static func main() async throws {
  let target = EasyWorkTarget(account: .init(authority: "authority", subject: "account"), desktopDeviceId: "host", workspaceId: "workspace", runtimeId: "runtime")
  let client = Client(id: "host")
  let originals: [[String: Any]] = (0..<173).map { ["id": "m\($0)", "role": "assistant", "content": [["type": "text", "text": "value-\($0)"]]] }
  let data = page(originals); testSession = EasyWorkRemoteSession(target, data)
  let provider: any AgentClient = EasyWorkAgentClient()
  check(provider.supportsSubagentHistory(client, workspace: .init(connectionMode: "easywork-runtime", capabilities: nil)), "provider dispatch")
  check(!provider.supportsSubagentHistory(client, workspace: .init(connectionMode: "other", capabilities: ["subagents:history:read:v1"])), "provider mode")
  let host: any AgentClient = HostClient()
  check(host.supportsSubagentHistory(client, workspace: .init(connectionMode: nil, capabilities: ["subagents:history:read:v1"])), "host capability")
  check(!host.supportsSubagentHistory(client, workspace: nil), "host missing capability")
  var all: [ChatMessage] = []; var cursor: String?; var count = 0
  repeat { let result = try await provider.subagentHistory(client, parentThreadId: "parent", messageId: "subagent:child", childId: "child", cursor: cursor); all = result.messages + all; cursor = result.nextCursor; count += 1 } while cursor != nil
  check(count == 3 && all.count == 174, "three ordered pages")
  check(all.prefix(173).map(\.id) == (0..<173).map { "m\($0):0" }, "order and uniqueness")
  check(all.last?.kind == "turnFailure", "failure")
  check(testSession.calls == Array(repeating: "thread/synchronize", count: 3), "read-only requests")
  let selected = try EasyWorkSubagentHistory.select(data, threadId: "parent", messageId: "subagent:child", childId: nil)
  let scope = try EasyWorkSubagentHistory.scope(target: target, selected: selected, parentThreadId: "parent", messageId: "subagent:child")
  let first = try EasyWorkSubagentHistory.page(all, scope: scope, cursor: nil)
  let legacy = try EasyWorkSubagentHistory.page(all, scope: scope, cursor: first.messages.first!.id)
  let next = try EasyWorkSubagentHistory.page(all, scope: scope, cursor: first.nextCursor)
  check(legacy.messages == next.messages, "legacy cursor")
  rejects("expired") { _ = try EasyWorkSubagentHistory.page(all, scope: scope, cursor: "missing") }
  rejects("duplicate") { _ = try EasyWorkSubagentHistory.page([all[0], all[0]], scope: scope, cursor: nil) }
  for bad in [true, -1, 1.5, "7"] as [Any] { rejects("integer") { _ = try EasyWorkSubagentHistory.select(page([], revision: bad), threadId: "parent", messageId: "subagent:child", childId: nil) } }
  rejects("thread") { _ = try EasyWorkSubagentHistory.select(data, threadId: "wrong", messageId: "subagent:child", childId: nil) }
  rejects("child") { _ = try EasyWorkSubagentHistory.select(data, threadId: "parent", messageId: "subagent:child", childId: "wrong") }
  var other = target
  for change in 0..<5 { other = target; switch change { case 0: other.account.subject = "other"; case 1: other.desktopDeviceId = "other"; case 2: other.workspaceId = "other"; case 3: other.account.authority = "other"; default: other.runtimeId = "other" }; let otherScope = try EasyWorkSubagentHistory.scope(target: other, selected: selected, parentThreadId: "parent", messageId: "subagent:child"); rejects("target cursor") { _ = try EasyWorkSubagentHistory.page(all, scope: otherScope, cursor: first.nextCursor) } }
  for changed in [try EasyWorkSubagentHistory.scope(target: target, selected: selected, parentThreadId: "other", messageId: "subagent:child"), try EasyWorkSubagentHistory.scope(target: target, selected: selected, parentThreadId: "parent", messageId: "subagent:other"), try EasyWorkSubagentHistory.scope(target: target, selected: EasyWorkSubagentHistory.select(page([], revision: 8, root: "other"), threadId: "parent", messageId: "subagent:child", childId: nil), parentThreadId: "parent", messageId: "subagent:child")] { rejects("scope cursor") { _ = try EasyWorkSubagentHistory.page(all, scope: changed, cursor: first.nextCursor) } }
  for (rev, root, child) in [(7, "other", "child"), (8, "root", "child"), (7, "root", "other")] {
    let alternative = try EasyWorkSubagentHistory.select(page([], revision: rev, root: root, child: child), threadId: "parent", messageId: "subagent:" + child, childId: child)
    let changed = try EasyWorkSubagentHistory.scope(target: target, selected: alternative, parentThreadId: "parent", messageId: "subagent:child")
    rejects("independent root child branch") { _ = try EasyWorkSubagentHistory.page(all, scope: changed, cursor: first.nextCursor) }
  }
  let large = [ChatMessage(id: "a", role: "assistant", text: String(repeating: "汉", count: 400_000)), ChatMessage(id: "b", role: "assistant", text: "tail")]
  let largePage = try EasyWorkSubagentHistory.page(large, scope: scope, cursor: nil)
  check(largePage.messages.map(\.id) == ["b"] && largePage.nextCursor != nil, "UTF8 budget")
  var ambiguous = data
  var snapshot = ambiguous["snapshot"] as! [String: Any]
  let tree = (snapshot["subagentRunTrees"] as! [[String: Any]])[0]
  snapshot["subagentRunTrees"] = [tree, tree]; ambiguous["snapshot"] = snapshot
  rejects("ambiguous child") { _ = try EasyWorkSubagentHistory.select(ambiguous, threadId: "parent", messageId: "subagent:child", childId: nil) }
  for field in ["threadId", "rootRunId", "branchRevision", "kind"] {
    var altered = data; var snap = altered["snapshot"] as! [String: Any]; var tree = (snap["subagentRunTrees"] as! [[String: Any]])[0]; var run = (tree["runs"] as! [[String: Any]])[0]
    run[field] = field == "branchRevision" ? 8 : "wrong"; tree["runs"] = [run]; snap["subagentRunTrees"] = [tree]; altered["snapshot"] = snap
    rejects("run identity \(field)") { _ = try EasyWorkSubagentHistory.select(altered, threadId: "parent", messageId: "subagent:child", childId: nil) }
  }
  let complex: [[String: Any]] = [["id": "complex", "role": "assistant", "status": "failed", "sourceRunId": "child", "content": [
    ["type": "text", "text": "原始\nUTF-8 👩🏽‍💻"], ["type": "reasoning", "text": "思考\noriginal"],
    ["type": "plan-artifact", "markdown": "# plan\n- step"],
    ["type": "tool-call", "toolName": "shell", "args": ["target": "shell", "patch": "*** Begin Patch\n+x\n"], "result": ["output": "原输出\n\u{0009}end", "diff": "+x\n"], "isError": true],
    ["type": "agent-debug", "status": "failed", "errorMessage": "failed 原因"]]]]
  testSession = EasyWorkRemoteSession(target, page(complex))
  let projected = try await EasyWorkAgentClient().testProject(complex, client: client, channel: testSession)
  let childResult = try await provider.subagentHistory(client, parentThreadId: "parent", messageId: "subagent:child", childId: nil, cursor: nil)
  check(Array(childResult.messages.dropLast()) == projected, "parent child projection equivalence")
  check(projected.count == 5 && projected[0].text == "原始\nUTF-8 👩🏽‍💻" && projected[1].detail == "思考\noriginal", "text reasoning bytes")
  check(projected[2].kind == "plan" && projected[3].kind == "fileChange" && projected[3].status == "failed" && projected[3].detail == "+x\n" && projected[4].kind == "turnFailure", "plan tool diff failure")
  if case .tool(_, _, _, _, let output)? = projected[3].blocks?.dropFirst().first { let decoded = try JSONSerialization.jsonObject(with: Data((output ?? "").utf8)) as? [String: String]; check(decoded?["output"] == "原输出\n\u{0009}end", "tool output preserved") } else { fatalError("tool block") }
  var fallback = page([]); var fallbackSnap = fallback["snapshot"] as! [String: Any]; var fallbackTree = (fallbackSnap["subagentRunTrees"] as! [[String: Any]])[0]; var fallbackRun = (fallbackTree["runs"] as! [[String: Any]])[0]
  fallbackRun["result"] = ["content": [["type": "text", "text": "fallback 原文"]]]; fallbackTree["runs"] = [fallbackRun]; fallbackSnap["subagentRunTrees"] = [fallbackTree]; fallback["snapshot"] = fallbackSnap
  testSession = EasyWorkRemoteSession(target, fallback)
  let fallbackResult = try await provider.subagentHistory(client, parentThreadId: "parent", messageId: "subagent:child", childId: nil, cursor: nil)
  check(fallbackResult.messages.first?.text == "fallback 原文", "result content fallback")
  check(testSession.calls == ["thread/synchronize"], "complex reads only")
  func withTranscriptMessages(_ value: Any) -> [String: Any] {
    var copy = fallback
    var snap = copy["snapshot"] as! [String: Any]
    var tree = (snap["subagentRunTrees"] as! [[String: Any]])[0]
    var run = (tree["runs"] as! [[String: Any]])[0]
    run["checkpoint"] = ["subagentTranscript": ["messages": value]]
    tree["runs"] = [run]; snap["subagentRunTrees"] = [tree]; copy["snapshot"] = snap
    return copy
  }
  let nullSelected = try EasyWorkSubagentHistory.select(withTranscriptMessages(NSNull()), threadId: "parent", messageId: "subagent:child", childId: nil)
  let nullMessages = try EasyWorkSubagentHistory.originalMessages(nullSelected)
  check((nullMessages.first?["content"] as? [[String: Any]])?.first?["text"] as? String == "fallback 原文", "legacy null transcript falls back to saved result")
  rejects("non-null malformed transcript messages") {
    let malformed = try EasyWorkSubagentHistory.select(withTranscriptMessages("not-an-array"), threadId: "parent", messageId: "subagent:child", childId: nil)
    _ = try EasyWorkSubagentHistory.originalMessages(malformed)
  }
  let attachmentParts: [[String: Any]] = [["id": "file", "role": "assistant", "content": [
    ["type": "attachment", "resourceId": "valid", "name": "a.txt", "sha256": String(repeating: "a", count: 64), "size": 1],
    ["type": "attachment", "resourceId": "bad", "name": "b.txt", "sha256": "bad", "size": 1]]]]
  testSession = EasyWorkRemoteSession(target, page(attachmentParts))
  let attachmentResult = try await provider.subagentHistory(client, parentThreadId: "parent", messageId: "subagent:child", childId: nil, cursor: nil)
  check(attachmentResult.messages[0].attachments?.count == 1 && attachmentResult.messages[1].attachments == nil && attachmentResult.messages[1].files == ["b.txt"], "attachment validation")
  check(testSession.calls == ["thread/synchronize"], "attachment inline no request")
  print("PASS dispatch capability pagination cursor identity strict integers readonly UTF8")
 }
}
