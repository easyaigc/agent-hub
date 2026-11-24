import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const source = await readFile('apps/ios/Sources/EasyWorkSession.swift', 'utf8');
const connectionSource = await readFile('apps/ios/Sources/AgentClient.swift', 'utf8');
const fixture = JSON.parse(
  await readFile('packages/protocol/fixtures/mobile/operation-recovery-v1.json', 'utf8'),
);
function declaration(start, end, input = source) {
  const a = input.indexOf(start),
    b = input.indexOf(end, a);
  assert.ok(a >= 0 && b > a, `missing ${start}`);
  return input.slice(a, b);
}
const reads = declaration(
  'private static let readMethods:',
  'private static let subscriptionMethods:',
);
// Extract only the actual method set. Unrelated session state may be added
// before epoch without becoming part of this controlled recovery harness.
const subs = source.match(
  /private static let subscriptionMethods:\s*Set<String>\s*=\s*\[[\s\S]*?\]/,
)?.[0];
assert.ok(subs, 'missing production subscription method set');
const strings = (s) => [...s.matchAll(/"([^"\n]+)"/g)].map((x) => x[1]).sort();
test('fixture matches actual Swift recovery sets', () => {
  assert.deepEqual(
    strings(reads),
    [...fixture.readQueries, ...fixture.originalReceiptQueries].sort(),
  );
  assert.deepEqual(strings(subs), [...fixture.subscriptionManagement].sort());
  for (const method of ['resource/resolve', 'resource/resolve-document-link']) {
    assert.ok(!fixture.readQueries.includes(method));
    assert.ok(fixture.writeExamples.includes(method));
  }
  for (const item of fixture.cases) {
    assert.equal(
      item.kind,
      fixture.readQueries.includes(item.method) ? 'read' : 'original-receipt-only',
    );
  }
});
test(
  'actual requestData recovers reads and never republishes writes',
  { skip: process.platform !== 'darwin' },
  async (t) => {
    const dir = await mkdtemp(join(tmpdir(), 'easywork-recovery-'));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const method = declaration('private func requestData(', '  func synchronizedThread(').replace(
      'private func requestData(',
      'func requestData(',
    );
    const verified = declaration(
      'nonisolated static func verifiedWriteResult(',
      '  private func verifySessionLiveness(',
    );
    const recoveryPolicy = declaration(
      '  nonisolated static func recoveryExcludedCandidate(',
      '  nonisolated static func verifiedWriteResult(',
    );
    const expiry = declaration('private func expireRequest(', '  private func publish(');
    const harness = `import Foundation
func easyWorkJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]) }
func easyWorkObject(_ data: Data) throws -> [String: Any] { try JSONSerialization.jsonObject(with: data) as! [String: Any] }
enum HostServiceError: Error { case message(String), rejected(Int,String), unavailable, invalidResponse, wrongHost }
enum ReadPublishError: Error { case capacity }
struct EasyWorkRemoteRejection: Error { let code: String?; let message: String }
struct EasyWorkDeviceAuthorizationFailure: Error {}
enum L10n { static func tr(_ s: String) -> String { s } }
enum HostService { static func hash(_ data: Data) -> String { "unused" } }
struct EasyWorkTarget { let dictionary: [String: Any] = ["account": ["authority": "https://account.example", "subject": "owner"], "desktopDeviceId": "desktop", "workspaceId": "workspace", "runtimeId": "runtime"] }
${declaration('struct AgentConnectionCandidate:', 'struct AgentConnection:', connectionSource)}
struct Connection { let preference = AgentConnectionPreference.lanOnly; let candidates: [AgentConnectionCandidate] = [] }
actor Harness {
 ${reads}
 ${subs}
 var pendingRequestIds = Set<String>(); var responses: [String: [String: Any]] = [:]
 var lastError: Error?; var closed = false; var epoch = "epoch"; var activeCandidate = "route"
 var lastVerifiedTraffic: ContinuousClock.Instant?
 func verifySessionLiveness(epoch: String) async -> Bool { false }
 let target = EasyWorkTarget(); let mobileDeviceId = "device"; let connection = Connection()
 var published: [Data] = []; var connects = 0; var disconnects = 0; var status = 0; var expectedOperationId = "same-id"
 let failure: String; let failTwice: Bool
 init(_ failure: String, _ failTwice: Bool) { self.failure = failure; self.failTwice = failTwice }
 func diagnostic(_ a: String, _ b: String = "") {}
 func connect(excluding: String? = nil) async throws { connects += 1; lastError = nil }
 func disconnect() async { disconnects += 1; epoch += "x" }
 func publish(_ envelope: [String: Any]) async throws {
   let request = envelope["envelope"] as! [String: Any]; published.append(try easyWorkJSON(request))
   if published.count == 1 || failTwice {
     if failure == "throw" { throw URLError(.networkConnectionLost) }
     lastError = URLError(.timedOut); return
   }
   responses[request["requestId"] as! String] = ["ok": true, "result": ["fresh": true]]
 }
 func publishRead(_ payload: [String: Any], id: String, deadline: ContinuousClock.Instant) async throws { try await publish(payload) }
 func request(_ method: String, _ params: [String: Any]) async throws -> Data { precondition(method == "command/status" && params["commandId"] as? String == expectedOperationId); status += 1; return try easyWorkJSON(NSNull()) }
 ${recoveryPolicy}
 ${verified}
 ${expiry}
 ${method}
 func verify(_ method: String, _ params: Data, _ shouldSucceed: Bool, _ expectedStatus: Int) async throws {
   let values = try easyWorkObject(params)
   expectedOperationId = values["clientOperationId"] as? String ?? values["clientRequestId"] as? String ?? values["operationId"] as? String ?? values["guidanceId"] as? String ?? "same-id"
   do { let result = try await requestData(method, params: params, id: "same-id"); precondition(shouldSucceed); let value = try easyWorkObject(result); precondition(value["fresh"] as? Bool == true) }
   catch { precondition(!shouldSucceed, "unexpected error: \\(error)") }
   let sent = try easyWorkObject(published[0]); precondition(sent["method"] as? String == method && sent["requestId"] as? String == "same-id"); let sentTarget = try easyWorkJSON(sent["target"]!); let expectedTarget = try easyWorkJSON(target.dictionary); let sentParams = try easyWorkJSON(sent["params"]!); precondition(sentTarget == expectedTarget && sentParams == params);
   precondition(published.count == (expectedStatus == 0 ? 2 : 1)); if published.count == 2 { precondition(published[0] == published[1]) }
   precondition(status == expectedStatus && disconnects >= 1 && connects >= 2)
 }
}
@main struct Main { static func main() async throws {
 let fixture = try easyWorkObject(Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
 for item in fixture["cases"] as! [[String: Any]] { for fault in ["throw", "timeout"] { for failTwice in [false, true] {
   let read = item["kind"] as? String == "read"
   let h = Harness(fault, failTwice); try await h.verify(item["method"] as! String, try easyWorkJSON(item["params"]!), read && !failTwice, read ? 0 : 1)
 } } }
 for fault in ["throw", "timeout"] { let h = Harness(fault, false); try await h.verify("resource/resolve", try easyWorkJSON(["threadId": "thread", "reference": "./child.txt"]), false, 1) }
 for method in ["turn/start", "future/unknown"] { let h = Harness("throw", false); try await h.verify(method, try easyWorkJSON(["clientOperationId": "original"]), false, 1) }
 print("requestData scenarios passed")
} }
`;
    const swift = join(dir, 'Harness.swift'),
      binary = join(dir, 'Harness');
    await writeFile(swift, harness);
    const compile = spawnSync(
      'xcrun',
      [
        'swiftc',
        '-parse-as-library',
        '-module-cache-path',
        join(dir, 'cache'),
        swift,
        '-o',
        binary,
      ],
      {
        encoding: 'utf8',
        timeout: 60000,
        maxBuffer: 1024 * 1024,
        env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
      },
    );
    assert.equal(compile.status, 0, compile.stderr);
    const run = spawnSync(
      binary,
      ['packages/protocol/fixtures/mobile/operation-recovery-v1.json'],
      { encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024 },
    );
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /requestData scenarios passed/);
  },
);
