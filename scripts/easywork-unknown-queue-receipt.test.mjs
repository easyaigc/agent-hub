import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
const repository = fileURLToPath(new URL('../', import.meta.url));
function declaration(source, marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, `Missing production declaration: ${marker}`);
  let depth = 0;
  let opened = false;
  for (let index = start; index < source.length; index++) {
    if (source[index] === '{') {
      depth++;
      opened = true;
    }
    if (source[index] === '}' && --depth === 0 && opened) return source.slice(start, index + 1);
  }
  assert.fail(`Unclosed production declaration: ${marker}`);
}

test(
  'actual iOS receipt method reconciles unknown queue records without a new write',
  {
    skip: process.platform !== 'darwin',
  },
  async (t) => {
    const [source, models, transport, session, notificationModels] = await Promise.all([
      readFile(join(repository, 'apps/ios/Sources/EasyWorkAgentClient.swift'), 'utf8'),
      readFile(join(repository, 'apps/ios/Sources/Models.swift'), 'utf8'),
      readFile(join(repository, 'apps/ios/Sources/EasyWorkTransport.swift'), 'utf8'),
      readFile(join(repository, 'apps/ios/Sources/EasyWorkSession.swift'), 'utf8'),
      readFile(join(repository, 'apps/ios/Sources/NotificationModels.swift'), 'utf8'),
    ]);
    await mkdir(join(repository, '.local'), { recursive: true, mode: 0o700 });
    const directory = await mkdtemp(join(repository, '.local/easywork-unknown-queue-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    const swift = join(directory, 'QueueReceiptFixture.swift');
    const binary = join(directory, 'queue-receipt-fixture');
    const program = String.raw`import Foundation
enum L10n { static func tr(_ text: String) -> String { text } }
enum HostServiceError: Error { case invalidResponse, wrongHost, message(String) }
${declaration(notificationModels, 'struct AgentAccount:')}
${declaration(notificationModels, 'struct AgentRuntimeTarget:')}
${declaration(transport, 'struct EasyWorkTarget:')}
struct AgentConnection { let target: AgentRuntimeTarget }
struct Client { let id: String; let agentConnection: AgentConnection? }
func fixtureTarget() -> EasyWorkTarget {
  EasyWorkTarget(account: AgentAccount(authority: "https://issuer.example", subject: "fixture-user"),
    desktopDeviceId: "fixture-desktop", workspaceId: "fixture-workspace", runtimeId: "fixture-runtime")
}
${declaration(models, 'struct DeliveryReceipt:')}
${declaration(transport, 'func easyWorkJSON(')}
${declaration(transport, 'func easyWorkObject(')}
${declaration(session, 'struct EasyWorkRemoteRejection:')}
${declaration(source, 'enum EasyWorkReceiptNormalization {')}
@MainActor final class ScriptedSession {
  let target = fixtureTarget()
  let record: [String: Any]
  let queued: Any
  let rejection: String?
  var calls: [String] = []
  init(record: [String: Any], queued: Any, rejection: String? = nil) {
    self.record = record; self.queued = queued; self.rejection = rejection
  }
  func request(_ method: String, _ params: [String: Any]) async throws -> Data {
    calls.append(method)
    guard method == "command/status" || method == "queue/status" else { throw HostServiceError.invalidResponse }
    if method == "command/status" {
      guard params["commandId"] as? String == "fixture-operation" else { throw HostServiceError.wrongHost }
      return try easyWorkJSON(record)
    }
    guard params["threadId"] as? String == "fixture-thread",
      params["messageId"] as? String == "fixture-operation" else { throw HostServiceError.wrongHost }
    if let rejection { throw EasyWorkRemoteRejection(code: rejection, message: "fixture rejection") }
    return try easyWorkJSON(queued)
  }
}
@MainActor struct ActualClientMethod {
  let gateway: ScriptedSession
  func session(_ client: Client) async throws -> ScriptedSession { gateway }
  ${declaration(source, 'private func object(_ value: Any)')}
  ${declaration(source, 'private func readingTarget(_ target: EasyWorkTarget)')}
  ${declaration(source, 'func receipt(_ client: Client, id: String)')}
}
@main struct QueueReceiptFixture {
  @MainActor static func main() async throws {
    let name = CommandLine.arguments[1]
    var record: [String: Any] = ["method": "turn/start", "state": "uncertain", "threadId": "fixture-thread"]
    var queued: Any = ["clientMessageId": "fixture-operation", "threadId": "fixture-thread",
      "state": "uncertain", "queueSource": "native", "queuePresent": true,
      "canExecuteNow": false, "text": "frozen queued input"]
    var rejection: String?
    switch name {
    case "submitting": record["state"] = "submitting"
    case "queued-admission": var q = queued as! [String: Any]; q["state"] = "queued"; queued = q
    case "nonqueue": queued = NSNull()
    case "submitting-nonqueue": record["state"] = "submitting"; queued = NSNull()
    case "other-method": record["method"] = "thread/create"
    case "completed": record["state"] = "completed"; record["response"] = ["result": ["threadId": "fixture-thread", "runId": "fixture-run"]]
    case "null-completed": record["state"] = "completed"; record["response"] = ["result": NSNull()]
    case "empty-queue": queued = [String: Any]()
    case "malformed-result": record["response"] = ["result": "not-an-object"]
    case "wrong-command": record["operationId"] = "another-original"
    case "wrong-result-operation": record["state"] = "completed"; record["response"] = ["result": ["threadId": "fixture-thread", "clientMessageId": "another-original", "state": "failed"]]
    case "queue-unavailable": rejection = "queue_unavailable"
    case "wrong-thread": var q = queued as! [String: Any]; q["threadId"] = "other-thread"; queued = q
    case "wrong-operation": var q = queued as! [String: Any]; q["clientMessageId"] = "other-operation"; queued = q
    case "unavailable": rejection = "method_unavailable"
    case "denied": rejection = "device_not_authorized"
    default: break
    }
    let identityCase = name.split(separator: ":").map(String.init)
    let phase = identityCase.count == 2 ? identityCase[0] : ""
    let field = identityCase.count == 2 ? identityCase[1] : ""
    let capturedWireTarget = fixtureTarget()
    var clientTarget = AgentRuntimeTarget(agentId: "easywork", hostId: capturedWireTarget.desktopDeviceId,
      workspaceId: capturedWireTarget.workspaceId, runtimeId: capturedWireTarget.runtimeId, account: capturedWireTarget.account)
    if phase == "owner" { clientTarget.runtimeId = "another-runtime" }
    if ["outer", "result", "queue"].contains(phase) {
      var target = fixtureTarget().dictionary
      var identity: [String: Any] = ["target": target, "hostId": "fixture-desktop"]
      switch field {
      case "authority", "subject":
        var account = target["account"] as! [String: Any]
        account[field] = "another-identity"; target["account"] = account; identity["target"] = target
      case "desktopDeviceId", "workspaceId", "runtimeId": target[field] = "another-target"; identity["target"] = target
      case "target-type": identity["target"] = "not-a-target"
      case "hostId": identity["hostId"] = "another-desktop"
      case "host-type": identity["hostId"] = 17
      case "local-host-alias": identity["hostId"] = "fixture-host"
      case "legacy-null": identity["target"] = NSNull(); identity["hostId"] = NSNull()
      default: break
      }
      if phase == "outer" { record.merge(identity) { _, new in new } }
      if phase == "result" {
        record["state"] = "completed"
        var result: [String: Any] = ["threadId": "fixture-thread", "runId": "fixture-run"]
        result.merge(identity) { _, new in new }; record["response"] = ["result": result]
      }
      if phase == "queue" {
        var originalQueue = queued as! [String: Any]
        originalQueue.merge(identity) { _, new in new }; queued = originalQueue
      }
    }
    let gateway = ScriptedSession(record: record, queued: queued, rejection: rejection)
    var result: DeliveryReceipt?, failure: Error?
    do { result = try await ActualClientMethod(gateway: gateway).receipt(
      Client(id: "fixture-host", agentConnection: AgentConnection(target: clientTarget)), id: "fixture-operation") }
    catch { failure = error }
    func require(_ condition: Bool, _ detail: String) throws {
      if !condition { throw NSError(domain: name + ": " + detail, code: 1) }
    }
    let wrongIdentity = !phase.isEmpty && field != "valid" && field != "legacy-null"
    if wrongIdentity || name == "wrong-thread" || name == "wrong-operation" || name == "wrong-command" || name == "empty-queue" || name == "wrong-result-operation" {
      guard let failure, case HostServiceError.wrongHost = failure else { throw NSError(domain: name + ": wrong target was not rejected", code: 2) }
    } else if name == "malformed-result" {
      guard let failure, case HostServiceError.invalidResponse = failure else { throw NSError(domain: name + ": malformed value was adopted", code: 3) }
    } else if name == "denied" {
      try require((failure as? EasyWorkRemoteRejection)?.code == "device_not_authorized", "authorization rejection must remain visible")
    } else {
      if let failure { throw failure }
      guard let result else { throw HostServiceError.invalidResponse }
      try require(result.clientMessageId == "fixture-operation" && result.threadId == "fixture-thread" && result.hostId == "fixture-host", "original identity")
      if name == "completed" || phase == "result" { try require(result.state == "submitted" && result.turnId == "fixture-run", "completed original root") }
      else {
        try require(result.state == (name == "queued-admission" ? "queued" : "uncertain") && result.turnId == nil, "original outcome never borrows a current root")
        if ["queue", "submitting", "queued-admission"].contains(name) || phase == "outer" || phase == "queue" {
          try require(result.queueSource == "native" && result.queuePresent == true && result.canExecuteNow == false && result.text == "frozen queued input", "authoritative original queue fields")
        } else { try require(result.queueSource == nil && result.queuePresent == nil, "nonqueue unknown is not adopted") }
      }
    }
    let expected = phase == "owner" ? [] :
      (phase == "result" || phase == "outer" && wrongIdentity ||
        ["other-method", "completed", "null-completed", "wrong-command", "malformed-result", "wrong-result-operation"].contains(name)
        ? ["command/status"] : ["command/status", "queue/status"])
    try require(gateway.calls == expected, "only original status reads; no execute or resend")
    print("passed")
  }
}
`;
    await writeFile(swift, program, { mode: 0o600 });
    const env = Object.fromEntries(
      Object.entries(process.env).filter(([key]) =>
        /^(PATH|HOME|TMPDIR|SDKROOT|DEVELOPER_DIR)$/i.test(key),
      ),
    );
    await run(
      '/usr/bin/swiftc',
      [
        '-parse-as-library',
        '-module-cache-path',
        join(directory, 'module-cache'),
        '-o',
        binary,
        swift,
      ],
      { env, timeout: 30000 },
    );
    const originalCases = [
      'queue',
      'queued-admission',
      'submitting',
      'nonqueue',
      'submitting-nonqueue',
      'other-method',
      'completed',
      'wrong-thread',
      'wrong-operation',
      'unavailable',
      'denied',
      'queue-unavailable',
      'null-completed',
      'empty-queue',
      'malformed-result',
      'wrong-command',
      'wrong-result-operation',
    ];
    const targetCases = ['outer', 'result', 'queue'].flatMap((phase) =>
      [
        'valid',
        'legacy-null',
        'authority',
        'subject',
        'desktopDeviceId',
        'workspaceId',
        'runtimeId',
        'target-type',
        'hostId',
        'host-type',
        'local-host-alias',
      ].map((field) => `${phase}:${field}`),
    );
    for (const name of [...originalCases, ...targetCases, 'owner:runtimeId']) {
      await t.test(name, async () => {
        const result = await run(binary, [name], { env, timeout: 5000 });
        assert.equal(result.stdout.trim(), 'passed');
      });
    }
  },
);
