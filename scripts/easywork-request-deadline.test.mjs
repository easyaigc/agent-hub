import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Executes the actual request/recovery methods with a transport whose send stays
// suspended until that exact transport is closed. No network, account or model.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sourcePath = 'apps/ios/Sources/EasyWorkSession.swift';
if (process.platform !== 'darwin') {
  console.log('EasyWork request deadline Swift boundary skipped: macOS and Xcode are required.');
  process.exit(0);
}
const current = readFileSync(join(root, sourcePath), 'utf8');
const targetSource = readFileSync(join(root, 'apps/ios/Sources/EasyWorkTransport.swift'), 'utf8');
const targetDeclaration = between(
  targetSource,
  'struct EasyWorkTarget:',
  'struct EasyWorkBinding:',
);
const accountDeclaration = readFileSync(
  join(root, 'apps/ios/Sources/NotificationModels.swift'),
  'utf8',
).match(/^struct AgentAccount:.*$/m)?.[0];
assert(accountDeclaration, 'production AgentAccount declaration');
// Development can reproduce the frozen old-source failure. CI needs only its
// checked-out production input and does not depend on shallow Git history.
const baselineOption = process.argv.indexOf('--baseline-source');
const baseline =
  baselineOption < 0 ? undefined : readFileSync(process.argv[baselineOption + 1], 'utf8');
const parent = join(root, '.local/easywork-request-deadline');
mkdirSync(parent, { recursive: true, mode: 0o700 });
const scratch = mkdtempSync(join(parent, 'scratch-'));
const evidence = mkdtempSync(join(parent, 'result-'));
const report = {
  kind: 'production-method-controlled-transport-boundary',
  sourceSha256: createHash('sha256').update(current).digest('hex'),
  targetDeclarationSha256: createHash('sha256').update(targetDeclaration).digest('hex'),
  accountDeclarationSha256: createHash('sha256').update(accountDeclaration).digest('hex'),
  baselineSha256: baseline && createHash('sha256').update(baseline).digest('hex'),
  cases: [],
  realNetwork: false,
  realModel: false,
};

function between(source, begin, end) {
  const a = source.indexOf(begin);
  assert(a >= 0, begin);
  const b = source.indexOf(end, a + begin.length);
  assert(b > a, end);
  return source.slice(a, b);
}

function program(source) {
  let request = between(source, '  private func requestData(', '  func synchronizedThread(');
  // Only the production thirty-second duration is shortened. Ordering, expiry,
  // transport close, operation IDs and original-receipt checks are unchanged.
  request = request
    .replace('.seconds(30)', '.milliseconds(120)')
    .replace('Date().addingTimeInterval(30)', 'Date().addingTimeInterval(0.12)');
  const expiry = source.includes('  private func expireRequest(')
    ? between(source, '  private func expireRequest(', '  private func publish(')
    : '  private func expireRequest(_ id: String, epoch requestEpoch: String) async {}\n';
  const verify = between(
    source,
    '  nonisolated static func verifiedWriteResult(',
    '  private func requestData(',
  );
  const gate = source.includes('enum EasyWorkRequestDeadlineAcceptance {')
    ? between(
        source,
        'enum EasyWorkRequestDeadlineAcceptance {',
        '/// One original synthetic prepare response',
      )
    : 'enum EasyWorkRequestDeadlineAcceptance { static func matches(bundleIdentifier: String?, arguments: [String], environment: [String: String], target: [String: Any], request: [String: Any]) -> Bool { false } }';
  return String.raw`import Foundation
import CryptoKit

enum L10n { static func tr(_ value: String) -> String { value } }
enum HostServiceError: Error { case message(String), unavailable, invalidResponse, wrongHost, rejected(Int, String) }
struct EasyWorkDeviceAuthorizationFailure: Error {}
struct EasyWorkRemoteRejection: Error { let code: String?; let message: String }
${accountDeclaration}
${targetDeclaration}
func fixtureTarget() -> EasyWorkTarget { EasyWorkTarget(account: AgentAccount(authority: "https://account.example", subject: "owner"), desktopDeviceId: "fixture-host", workspaceId: "fixture-workspace", runtimeId: "local") }
enum HostService { static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() } }
func easyWorkJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]) }
func easyWorkObject(_ data: Data) throws -> [String: Any] { try JSONSerialization.jsonObject(with: data) as! [String: Any] }
actor BlockedTransport {
  var waiter: CheckedContinuation<Void, Error>?
  func send() async throws { try await withCheckedThrowingContinuation { waiter = $0 } }
  func close() { waiter?.resume(throwing: URLError(.networkConnectionLost)); waiter = nil }
}
${gate}
actor Client {
  enum Preference { case automatic, lanOnly }
  struct Connection { var preference = Preference.lanOnly }
  let connection = Connection()
  let target = fixtureTarget()
  let mobileDeviceId = "fixture-mobile"
  let blocked = BlockedTransport()
  let scenario: String
  var closed = false
  var connected = false
  var epoch = "initial"
  var activeCandidate: String? = "fixture-route"
  var lastError: Error?
  var responses: [String: [String: Any]] = [:]
  var pendingRequestIds: Set<String> = []
  static let readMethods: Set<String> = ["workspace/list", "command/status"]
  static let subscriptionMethods: Set<String> = []
  var sends: [(String, String)] = []
  var closes = 0
  var original: (String, String, Data)?
  init(_ scenario: String) { self.scenario = scenario }
  func diagnostic(_ a: String, _ b: String = "") {}
  func connect(excluding: String? = nil) async throws {
    try Task.checkCancellation()
    if !connected { connected = true; epoch = UUID().uuidString; lastError = nil }
  }
  func disconnect() async { closes += 1; connected = false; await blocked.close() }
  func publish(_ payload: [String: Any]) async throws {
    let envelope = payload["envelope"] as! [String: Any]
    let method = envelope["method"] as! String, id = envelope["requestId"] as! String
    let params = envelope["params"] as! [String: Any]
    sends.append((method, id))
    if method == "command/status" {
      let (originalMethod, originalId, originalParams) = original!
      let saved = try easyWorkObject(originalParams)
      let record: [String: Any] = ["state": "completed", "operationId": originalId, "method": originalMethod,
        "deviceId": mobileDeviceId, "target": target.dictionary,
        "requestHash": HostService.hash(try easyWorkJSON(["target": target.dictionary, "method": originalMethod, "params": saved])),
        "response": ["protocolVersion": 2, "kind": "response", "ok": true, "result": ["original": originalId]]]
      precondition(params["commandId"] as? String == originalId)
      responses[id] = ["ok": true, "result": record]
      return
    }
    if original == nil { original = (method, id, try easyWorkJSON(params)) }
    let mustBlock = scenario != "fast" && (sends.count == 1 || scenario == "both-blocked")
    if mustBlock {
      if scenario == "authorization" { lastError = EasyWorkDeviceAuthorizationFailure() }
      if scenario == "response-first" { responses[id] = ["ok": true, "result": ["value": "original-read"]] }
      try await blocked.send()
    } else { responses[id] = ["ok": true, "result": ["value": "original-read"]] }
  }
  nonisolated func request(_ method: String, _ params: [String: Any], id: String = UUID().uuidString) async throws -> Data {
    try await requestData(method, params: easyWorkJSON(params), id: id)
  }
${verify}
${expiry}
${request}
  func stats() -> ([(String, String)], Int) { (sends, closes) }
  func staleExpiryDoesNotClose() async {
    let old = epoch; pendingRequestIds.insert("old")
    epoch = "new-connection"
    await expireRequest("old", epoch: old)
    precondition(closes == 0)
    pendingRequestIds.remove("old")
  }
}
@main struct Probe {
  static func main() async {
    let scenario = CommandLine.arguments[1]
    let client = Client(scenario)
    let clock = ContinuousClock(), began = clock.now
    do {
      switch scenario {
      case "read":
        let value = try await client.request("workspace/list", [:], id: "same-read")
        let decoded = try easyWorkObject(value)
        precondition(decoded["value"] as? String == "original-read")
        let (calls, closes) = await client.stats()
        precondition(calls.map { $0.1 } == ["same-read", "same-read"] && closes >= 1)
      case "write":
        let value = try await client.request("resource/upload", ["transferId": "owned-transfer"], id: "same-write")
        let decoded = try easyWorkObject(value)
        precondition(decoded["original"] as? String == "same-write")
        let (calls, _) = await client.stats()
        precondition(calls.map { $0.0 } == ["resource/upload", "command/status"] && calls[0].1 == "same-write")
      case "reply":
        do { _ = try await client.request("turn/start", ["text": "fixture"], id: "same-reply"); preconditionFailure("reply replay/adoption") }
        catch HostServiceError.message {}
        let (calls, _) = await client.stats()
        precondition(calls.map { $0.0 } == ["turn/start", "command/status"])
      case "both-blocked":
        do { _ = try await client.request("workspace/list", [:], id: "same-read"); preconditionFailure("unbounded retry") } catch {}
        let (calls, _) = await client.stats(); precondition(calls.count == 2 && calls.allSatisfy { $0.1 == "same-read" })
      case "cancel":
        let task = Task { try await client.request("workspace/list", [:], id: "cancelled") }
        try await Task.sleep(for: .milliseconds(20)); task.cancel()
        do { _ = try await task.value; preconditionFailure("cancel ignored") } catch is CancellationError {}
        let (calls, _) = await client.stats(); precondition(calls.count == 1)
      case "authorization":
        do { _ = try await client.request("workspace/list", [:], id: "authorization"); preconditionFailure("authorization lost") }
        catch is EasyWorkDeviceAuthorizationFailure {}
        let (calls, _) = await client.stats(); precondition(calls.count == 1)
      case "fast":
        _ = try await client.request("workspace/list", [:], id: "fast")
        try await Task.sleep(for: .milliseconds(180))
        await client.staleExpiryDoesNotClose()
        let (_, closes) = await client.stats(); precondition(closes == 0)
      case "response-first":
        let value = try await client.request("workspace/list", [:], id: "response-before-send-callback")
        let decoded = try easyWorkObject(value)
        precondition(decoded["value"] as? String == "original-read")
        let (calls, closes) = await client.stats(); precondition(calls.count == 1 && closes == 1)
      case "guard":
        let target = fixtureTarget().dictionary
        let encoded = try easyWorkJSON(target)
        let args = ["-easywork-local-dev", "-request-deadline-acceptance"]
        let env = ["EASYWORK_REQUEST_DEADLINE_THREAD_ID": "fixture-thread", "EASYWORK_REQUEST_DEADLINE_TARGET_SHA256": HostService.hash(encoded)]
        let request: [String: Any] = ["requestId": UUID().uuidString, "method": "thread/history", "target": target, "params": ["threadId": "fixture-thread"]]
        func permits(_ bundle: String = "com.easyai.agenthub.easyworkacceptance", _ arguments: [String]? = nil, _ environment: [String: String]? = nil, _ payload: [String: Any]? = nil) -> Bool {
          EasyWorkRequestDeadlineAcceptance.matches(bundleIdentifier: bundle, arguments: arguments ?? args, environment: environment ?? env, target: target, request: payload ?? request)
        }
        precondition(permits())
        precondition(!permits("com.easyai.agenthub"))
        precondition(!permits("com.easyai.agenthub.easyworkacceptance", []))
        precondition(!permits("com.easyai.agenthub.easyworkacceptance", args + ["-ui-testing"]))
        precondition(!permits("com.easyai.agenthub.easyworkacceptance", nil, [:]))
        var bad = request; bad["method"] = "turn/start"; precondition(!permits("com.easyai.agenthub.easyworkacceptance", nil, nil, bad))
        bad = request; bad["requestId"] = "not-an-operation"; precondition(!permits("com.easyai.agenthub.easyworkacceptance", nil, nil, bad))
        bad = request; bad["params"] = ["threadId": "different-thread"]; precondition(!permits("com.easyai.agenthub.easyworkacceptance", nil, nil, bad))
        bad = request; var otherTarget = target; otherTarget["desktopDeviceId"] = "other"; bad["target"] = otherTarget
        precondition(!permits("com.easyai.agenthub.easyworkacceptance", nil, nil, bad))
      default: preconditionFailure("unknown scenario")
      }
      precondition(began.duration(to: clock.now) < .seconds(1))
      print("PASS " + scenario)
    } catch { print("FAIL " + scenario + " " + String(describing: error)); exit(1) }
  }
}
`;
}

let failure;
try {
  for (const [name, source] of baseline === undefined
    ? [['new', current]]
    : [
        ['old', baseline],
        ['new', current],
      ]) {
    const swift = join(scratch, name + '.swift');
    writeFileSync(swift, program(source));
    const executable = join(scratch, name);
    const compile = spawnSync(
      'xcrun',
      [
        'swiftc',
        '-parse-as-library',
        '-swift-version',
        '5',
        '-module-cache-path',
        join(scratch, 'module-cache'),
        swift,
        '-o',
        executable,
      ],
      { cwd: root, encoding: 'utf8', timeout: 120_000 },
    );
    writeFileSync(
      join(evidence, name + '-compile.log'),
      (compile.stdout ?? '') + (compile.stderr ?? ''),
    );
    assert.equal(compile.status, 0, name + ' compilation failed; ' + evidence);
    for (const scenario of name === 'old'
      ? ['read']
      : [
          'read',
          'write',
          'reply',
          'both-blocked',
          'cancel',
          'authorization',
          'fast',
          'response-first',
          'guard',
        ]) {
      const result = spawnSync(executable, [scenario], { encoding: 'utf8', timeout: 1400 });
      writeFileSync(
        join(evidence, name + '-' + scenario + '.log'),
        (result.stdout ?? '') + (result.stderr ?? ''),
      );
      report.cases.push({
        source: name,
        scenario,
        status: result.status,
        signal: result.signal,
        error: result.error?.code,
        stdout: result.stdout,
      });
      if (name === 'old')
        assert.equal(
          result.error?.code,
          'ETIMEDOUT',
          'old blocked send must demonstrate missing deadline',
        );
      else {
        assert.equal(result.status, 0, scenario + ' failed; ' + evidence);
        assert.match(result.stdout, /PASS /);
      }
    }
  }
  report.passed = true;
} catch (error) {
  failure = error;
  report.failure = { message: error.message, code: error.code };
} finally {
  // Keep diagnostics separate; a failed compiler must not retain binaries/cache.
  let saved = false;
  try {
    writeFileSync(join(evidence, 'result.json'), JSON.stringify(report, null, 2) + '\n');
    saved = true;
    console.log('Deadline boundary report: ' + join(evidence, 'result.json'));
  } catch (error) {
    console.error('Diagnostics could not be saved; scratch retained: ' + scratch);
    if (!failure) failure = error;
  }
  if (saved) rmSync(scratch, { recursive: true });
}

if (failure) throw failure;
