import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { promisify } from 'node:util';

const run = promisify(execFile);
const repository = fileURLToPath(new URL('../', import.meta.url));
function declaration(source, marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, 'Missing production declaration: ' + marker);
  let depth = 0;
  let opened = false;
  for (let index = start; index < source.length; index++) {
    if (source[index] === '{') {
      depth++;
      opened = true;
    }
    if (source[index] === '}' && --depth === 0 && opened) return source.slice(start, index + 1);
  }
  assert.fail('Unclosed production declaration: ' + marker);
}

test(
  'production EasyWork approval decisions preserve identity and gate session grants',
  { skip: process.platform !== 'darwin' },
  async () => {
    const paths = [
      'EasyWorkAgentClient',
      'Models',
      'NotificationModels',
      'EasyWorkTransport',
      'EasyWorkQuestions',
    ];
    const [source, models, targets, transport, questionsSource] = await Promise.all(
      paths.map((name) =>
        readFile(join(repository, 'apps/ios/Sources/' + name + '.swift'), 'utf8'),
      ),
    );
    await mkdir(join(repository, '.local'), { recursive: true, mode: 0o700 });
    const directory = await mkdtemp(join(repository, '.local/easywork-approval-policy-'));
    let passed = false;
    try {
      const definitions = [
        declaration(models, 'struct ApprovalQuestion:'),
        declaration(models, 'struct QuestionOption:'),
        declaration(models, 'struct Approval:'),
        declaration(targets, 'struct AgentAccount:'),
        declaration(targets, 'struct AgentRuntimeTarget:'),
        declaration(transport, 'struct EasyWorkTarget:'),
        declaration(transport, 'extension Data {'),
        declaration(transport, 'func easyWorkJSON('),
        declaration(transport, 'func easyWorkObject('),
        declaration(questionsSource, 'enum EasyWorkQuestionProjection {'),
      ].join('\n');
      const methods = [
        declaration(source, 'private func object(_ value: Any)'),
        declaration(source, 'private func runState('),
        declaration(source, 'private func readingTarget(_ target: EasyWorkTarget)'),
        declaration(source, 'private func permissionDecisionResult(').replace(
          'private func',
          'func',
        ),
        declaration(source, 'private func permissionApproval(').replace('private func', 'func'),
        declaration(source, 'func approve(_ client: Client, approval: Approval,'),
      ].join('\n');
      const swift = String.raw`import Foundation
import CoreFoundation
${definitions}
enum L10n { static func tr(_ text: String) -> String { text } }
enum HostServiceError: Error { case invalidResponse, wrongHost, unavailable, message(String) }
struct CommandResult { let ok: Bool }
struct AgentConnection { var target: AgentRuntimeTarget }
struct Client {
  var agentConnection: AgentConnection?
  var id = "host"
  var readingTarget: AgentRuntimeTarget? { agentConnection?.target }
}
let wireTarget = EasyWorkTarget(account: AgentAccount(authority: "fixture", subject: "owner"),
  desktopDeviceId: "host", workspaceId: "workspace", runtimeId: "runtime")
func client() -> Client {
  Client(agentConnection: AgentConnection(target: AgentRuntimeTarget(agentId: "easywork",
    hostId: "host", workspaceId: "workspace", runtimeId: "runtime", account: wireTarget.account)))
}
final class ScriptedSession {
  let target = wireTarget
  var capabilities = ["approvals", "approvals:remember:session:v1", "approvals:decision-receipt:v1"]
  var response: [String: Any] = ["accepted": true]
  var calls: [(String, [String: Any])] = []
  var writes: [[String: Any]] { calls.filter { $0.0 == "approval/respond" }.map { $0.1 } }
  func request(_ method: String, _ params: [String: Any]) async throws -> Data {
    calls.append((method, params))
    if method == "runtime/context" { return try easyWorkJSON(["capabilities": capabilities]) }
    guard method == "approval/respond" || method == "serverRequest/resolve" else { throw HostServiceError.invalidResponse }
    return try easyWorkJSON(response)
  }
}
struct ActualClient {
  let channel: ScriptedSession
  func session(_ client: Client) async throws -> ScriptedSession { channel }
  ${methods}
  func state(_ snapshot: [String: Any], threadId: String) throws -> String {
    ${source.slice(source.indexOf('    let active = snapshot["activeRun"]'), source.indexOf('    var approvals: [Approval] = []', source.indexOf('    let active = snapshot["activeRun"]')))}
    return state
  }
}
@main struct ApprovalPolicySmoke {
  static func main() async throws {
    var checks = 0
    func expect(_ condition: Bool, _ name: String) throws {
      guard condition else { throw NSError(domain: name, code: 1) }
      checks += 1
    }
    let root = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [String: Any]
    guard root["version"] as? Int == 1 else { throw HostServiceError.invalidResponse }
    let vectors = root["cases"] as! [[String: Any]]
    func vector(_ name: String) -> [String: Any] { vectors.first { $0["name"] as? String == name }! }
    let request = vector("once-first")["identity"] as! [String: Any]
    let thread = request["threadId"] as! String, run = request["runId"] as! String
    let channel = ScriptedSession(), agent = ActualClient(channel: channel)
    let allCapabilities = channel.capabilities
    let approval = try agent.permissionApproval(request, threadId: thread, capabilities: allCapabilities)
    try expect(approval.decisions == ["approve", "acceptForSession", "deny"], "session choice has receipt capability")
    for policy: Any in ["once_only", "future-policy", NSNull()] {
      var limited = request; limited["approvalScopePolicy"] = policy
      let row = try agent.permissionApproval(limited, threadId: thread, capabilities: allCapabilities)
      try expect(row.decisions == ["approve", "deny"], "restricted and unknown scopes never offer session")
    }
    for caps in [["approvals"], ["approvals", "approvals:remember:session:v1"]] {
      let legacy = try agent.permissionApproval(request, threadId: thread, capabilities: caps)
      try expect(legacy.decisions == ["approve", "deny"], "legacy receipt contract never offers session")
    }
    let unavailable = try agent.permissionApproval(request, threadId: thread, capabilities: [])
    try expect(unavailable.decisions.isEmpty, "undeclared host has no actions")
    var wrong = request; wrong["threadId"] = "other"
    do { _ = try agent.permissionApproval(wrong, threadId: thread, capabilities: allCapabilities)
      throw NSError(domain: "wrong thread adopted", code: 2)
    } catch HostServiceError.wrongHost { checks += 1 }
    for key in ["threadId", "runId", "toolCallId", "operationId", "requestHash"] {
      var incomplete = request; incomplete.removeValue(forKey: key)
      do { _ = try agent.permissionApproval(incomplete, threadId: thread, capabilities: allCapabilities)
        throw NSError(domain: "missing identity accepted", code: 3)
      } catch HostServiceError.invalidResponse { checks += 1 }
    }
    // These wire results come from the executed production confirmation service and remote validator.
    for row in vectors {
      let identity = row["identity"] as! [String: Any], decision = row["decision"] as! String
      let response = row["response"] as! [String: Any], expected = row["expectedAccepted"] as! Bool
      let parsed = try agent.permissionDecisionResult(response, identity: identity, decision: decision, receiptRequired: true)
      try expect(parsed.ok == expected, (row["name"] as! String) + " actual service result retains original effective decision and scope")
      var metadata = identity
      metadata.merge(row["requestPolicy"] as! [String: Any]) { _, new in new }
      let projected = try agent.permissionApproval(metadata, threadId: identity["threadId"] as! String, capabilities: allCapabilities)
      channel.calls = []; channel.response = response; channel.capabilities = allCapabilities
      if !projected.decisions.contains(decision) {
        var refused = false
        do { _ = try await agent.approve(client(), approval: projected, decision: decision, answers: [:]) }
        catch { refused = true }
        try expect(refused && channel.writes.isEmpty, "once-only command is refused before any write")
        continue
      }
      let result = try await agent.approve(client(), approval: projected, decision: decision, answers: [:])
      try expect(result.ok == expected && channel.writes.count == 1, "actual service result through production dispatcher")
      let params = channel.writes[0]
      try expect(params["serverRequestId"] as? String == projected.id && params["threadId"] as? String == identity["threadId"] as? String, "original request id")
      try expect(params["decision"] as? String == (decision == "deny" ? "deny" : "approve"), "named wire decision")
      try expect((params["remember"] as? Bool == true) == (decision == "acceptForSession"), "only session choice requests remember")
    }
    for capabilities in [[], ["approvals"], ["approvals", "approvals:remember:session:v1"], ["approvals", "approvals:decision-receipt:v1"], ["approvals:remember:session:v1", "approvals:decision-receipt:v1"]] {
      channel.calls = []; channel.capabilities = capabilities
      do { _ = try await agent.approve(client(), approval: approval, decision: "acceptForSession", answers: [:])
        throw NSError(domain: "missing capability dispatched", code: 4)
      } catch HostServiceError.unavailable { checks += 1 }
      try expect(channel.writes.isEmpty, "no write without all declared capabilities")
    }
    channel.capabilities = allCapabilities
    for decision in ["accept", "acceptAlways", "future", ""] {
      channel.calls = []
      do { _ = try await agent.approve(client(), approval: approval, decision: decision, answers: [:])
        throw NSError(domain: "invalid decision dispatched", code: 5)
      } catch HostServiceError.invalidResponse { checks += 1 }
      try expect(channel.writes.isEmpty, "undeclared decision writes zero")
    }
    for field in ["agentId", "hostId", "workspaceId", "runtimeId", "authority", "subject"] {
      var other = client()
      switch field {
      case "agentId": other.agentConnection!.target.agentId = "other"
      case "hostId": other.agentConnection!.target.hostId = "other"
      case "workspaceId": other.agentConnection!.target.workspaceId = "other"
      case "runtimeId": other.agentConnection!.target.runtimeId = "other"
      case "authority": other.agentConnection!.target.account!.authority = "other"
      default: other.agentConnection!.target.account!.subject = "other"
      }
      channel.calls = []
      do { _ = try await agent.approve(other, approval: approval, decision: "approve", answers: [:])
        throw NSError(domain: "foreign target dispatched", code: 6)
      } catch HostServiceError.wrongHost { checks += 1 }
      try expect(channel.writes.isEmpty, "foreign target writes zero")
    }
    for field in ["threadId", "runId"] {
      var other = request; other[field] = "other"
      let forged = Approval(id: "approval." + (try easyWorkJSON(other)).base64URL,
        threadId: thread, turnId: run, kind: "command", title: "fixture", detail: "",
        questions: [], decisions: ["approve"])
      channel.calls = []
      do { _ = try await agent.approve(client(), approval: forged, decision: "approve", answers: [:])
        throw NSError(domain: "identity mismatch dispatched", code: 7)
      } catch HostServiceError.wrongHost { checks += 1 }
      try expect(channel.writes.isEmpty, "id and displayed identity match")
    }
    let sessionVector = vector("session-first"), sessionIdentity = sessionVector["identity"] as! [String: Any]
    let authentic = sessionVector["response"] as! [String: Any]
    var wrongScope = authentic; var originalReceipt = authentic["decisionReceipt"] as! [String: Any]
    originalReceipt["approvalScope"] = "once"; wrongScope["decisionReceipt"] = originalReceipt
    try expect(try !agent.permissionDecisionResult(wrongScope, identity: sessionIdentity, decision: "acceptForSession", receiptRequired: true).ok,
      "accepted alone cannot confirm a different effective scope")
    for malformed in ["missing-receipt", "wrong-id", "wrong-version", "unknown-scope", "numeric-accepted"] {
      var response = authentic
      var receipt = response["decisionReceipt"] as! [String: Any]
      switch malformed {
      case "missing-receipt": response.removeValue(forKey: "decisionReceipt")
      case "wrong-id": receipt["operationId"] = "other"; response["decisionReceipt"] = receipt
      case "wrong-version": receipt["version"] = true; response["decisionReceipt"] = receipt
      case "unknown-scope": receipt["approvalScope"] = "always"; response["decisionReceipt"] = receipt
      default: response["accepted"] = 1
      }
      var refused = false
      do { _ = try agent.permissionDecisionResult(response, identity: sessionIdentity, decision: "acceptForSession", receiptRequired: true) }
      catch { refused = true }
      try expect(refused, "malformed effective receipt rejected")
    }
    // Legacy hosts can still acknowledge their original once-only action.
    channel.capabilities = ["approvals"]; channel.calls = []; channel.response = ["accepted": true]
    try expect(try await agent.approve(client(), approval: approval, decision: "approve", answers: [:]).ok, "legacy once acknowledgement")
    try expect(checks == 141, "original 141 approval checks retained")
    print("passed 141 original production approval policy checks")
    let oldChecks = checks
    func question(_ run: String, _ call: String, _ async: Bool = true, _ status: String = "pending") -> [String: Any] {
      ["threadId": thread, "runId": run, "sessionId": "session", "toolCallId": call, "async": async, "status": status,
       "questions": [["header": "q", "question": "Choose", "multiple": false, "custom": false,
                      "options": [["label": "A", "description": "a"], ["label": "B", "description": "b"]]]]]
    }
    let one = question("done", "call1"), two = question("running", "call2")
    let snapshot: [String: Any] = ["questionStates": [one, two, question("r", "answered", true, "answered"), question("r", "abandoned", true, "abandoned")], "activeRun": ["status": "running"]]
    let projected = try EasyWorkQuestionProjection.approvals(snapshot: snapshot, threadId: thread)
    try expect(projected.count == 2 && projected.allSatisfy { $0.interactionMode == "async" }, "all pending async projected including completed run")
    try expect(try agent.state(snapshot, threadId: thread) == "running", "async does not block running")
    var ended = snapshot; ended["activeRun"] = nil
    try expect(try agent.state(ended, threadId: thread) == "idle", "old async does not block ended run")
    let blocking = question("run", "blocking", false)
    try expect(try agent.state(["pendingQuestion": blocking, "activeRun": ["status": "running"]], threadId: thread) == "waiting-for-approval", "blocking waits")
    try expect(try EasyWorkQuestionProjection.approvals(snapshot: ["pendingQuestion": one], threadId: thread).count == 1, "legacy async fallback")
    var changed = one; changed["status"] = "pending"; changed["answer"] = ["arbitrary": "metadata"]
    try expect(try EasyWorkQuestionProjection.approvals(snapshot: ["questionStates": [changed]], threadId: thread)[0].id == projected[0].id, "mutable metadata does not change identity")
    func rejects(_ snapshot: [String: Any]) throws {
      do { _ = try EasyWorkQuestionProjection.approvals(snapshot: snapshot, threadId: thread); throw NSError(domain: "invalid projection accepted", code: 1) }
      catch HostServiceError.invalidResponse { checks += 1 }
    }
    try rejects(["questionStates": [one, one]])
    for key in ["threadId", "runId", "sessionId", "toolCallId"] {
      var bad = one; bad[key] = key == "threadId" ? "other" : ""
      try rejects(["questionStates": [bad]])
    }
    for badItems: Any in [[], [["header": "", "question": "Q"]], [["header": "q", "question": "Q", "options": [["label": "", "description": "d"]]]], [["header": "q", "question": "Q"], ["header": "q", "question": "Q2"]]] {
      var bad = one; bad["questions"] = badItems; try rejects(["questionStates": [bad]])
    }
    let qchannel = ScriptedSession(), qagent = ActualClient(channel: qchannel), approvalQ = projected[0]
    qchannel.response = ["accepted": true]
    try expect(try await qagent.approve(client(), approval: approvalQ, decision: "answer", answers: ["q": ["A"]]).ok, "answer accepted")
    let params = qchannel.calls.last!.1
    let answer = params["answer"] as! [String: Any], rows = answer["questions"] as! [[String: Any]]
    try expect(params["threadId"] as? String == thread && params["runId"] as? String == "done" && params["sessionId"] as? String == "session" && params["serverRequestId"] as? String == "call1" && params["action"] as? String == "answer" && rows[0]["selectedOptions"] as? [String] == ["A"] && Set(rows[0].keys) == Set(["header", "selectedOptions"]), "exact answer identity and keys")
    for decision in ["skip", "deny"] {
      qchannel.response = ["abandoned": true]
      try expect(try await qagent.approve(client(), approval: approvalQ, decision: decision, answers: [:]).ok, "abandon acknowledged")
      qchannel.response = ["accepted": true]
      try expect(try await !qagent.approve(client(), approval: approvalQ, decision: decision, answers: [:]).ok, "opposite acknowledgement rejected")
    }
    qchannel.response = ["abandoned": true]
    try expect(try await !qagent.approve(client(), approval: approvalQ, decision: "answer", answers: ["q": ["A"]]).ok, "answer requires accepted")
    for (decision, answers) in [("bad", ["q": ["A"]]), ("answer", ["wrong": ["A"]]), ("answer", ["q": ["A", "B"]]), ("answer", ["q": ["custom"]])] {
      qchannel.calls = []; var refused = false
      do { _ = try await qagent.approve(client(), approval: approvalQ, decision: decision, answers: answers) } catch { refused = true }
      try expect(refused && qchannel.calls.isEmpty, "invalid resolution makes zero writes")
    }
    for key in ["threadId", "runId", "sessionId", "toolCallId"] {
      var forgedIdentity = try easyWorkObject(Data(base64URL: String(approvalQ.id.dropFirst(9)))!)
      forgedIdentity[key] = key == "threadId" || key == "runId" ? "other" : ""
      let forged = Approval(id: "question." + (try easyWorkJSON(forgedIdentity)).base64URL,
        threadId: approvalQ.threadId, turnId: approvalQ.turnId, kind: "question", title: "fixture", detail: "",
        questions: approvalQ.questions, decisions: [], interactionMode: "async")
      qchannel.calls = []; var refused = false
      do { _ = try await qagent.approve(client(), approval: forged, decision: "answer", answers: ["q": ["A"]]) } catch { refused = true }
      try expect(refused && qchannel.calls.isEmpty, "forged identity makes zero writes")
    }
    var multi = one
    multi["questions"] = [["header": "q", "question": "Choose", "multiple": true, "custom": true, "options": [["label": "A", "description": "a"], ["label": "B", "description": "b"]]]]
    let multiApproval = try EasyWorkQuestionProjection.approvals(snapshot: ["questionStates": [multi]], threadId: thread)[0]
    let multiParams = try EasyWorkQuestionProjection.resolution(approval: multiApproval, decision: "answer", answers: ["q": ["A", "B", "custom"]])
    let multiRows = (multiParams["answer"] as! [String: Any])["questions"] as! [[String: Any]]
    try expect(multiRows[0]["selectedOptions"] as? [String] == ["A", "B"] && multiRows[0]["customAnswer"] as? String == "custom" && Set(multiRows[0].keys) == Set(["header", "selectedOptions", "customAnswer"]), "multi and custom keys")
    print("passed \(checks - oldChecks) additional question projection checks")
  }
}
`;
      const file = join(directory, 'ApprovalPolicy.swift');
      const binary = join(directory, 'approval-policy');
      await writeFile(file, swift, { mode: 0o600 });
      process.stdout.write(
        'Swift input SHA-256: ' + createHash('sha256').update(swift).digest('hex') + '\n',
      );
      await run(
        'xcrun',
        [
          'swiftc',
          '-parse-as-library',
          '-module-cache-path',
          join(directory, 'module-cache'),
          file,
          '-o',
          binary,
        ],
        { timeout: 60000 },
      );
      const result = await run(
        binary,
        [join(repository, 'packages/protocol/fixtures/mobile/easywork-approval-decisions-v1.json')],
        { timeout: 10000 },
      );
      assert.match(result.stdout, /passed 141 original production approval policy checks/);
      process.stdout.write(result.stdout);
      passed = true;
    } finally {
      if (passed) await rm(directory, { recursive: true, force: true });
      else {
        // Keep the exact failed Swift input; compiler caches are reproducible.
        await rm(join(directory, 'module-cache'), { recursive: true, force: true });
        await rm(join(directory, 'approval-policy'), { force: true });
      }
    }
  },
);
