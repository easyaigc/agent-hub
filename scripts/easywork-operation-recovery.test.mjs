import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const source = await readFile('apps/ios/Sources/EasyWorkSession.swift', 'utf8');
const fixture = JSON.parse(
  await readFile('packages/protocol/fixtures/mobile/operation-recovery-v1.json', 'utf8'),
);
function declaration(start, end) {
  const a = source.indexOf(start),
    b = source.indexOf(end, a);
  assert.ok(a >= 0 && b > a, `missing ${start}`);
  return source.slice(a, b);
}
const reads = declaration(
  'private static let readMethods:',
  'private static let subscriptionMethods:',
);
const subs = declaration('private static let subscriptionMethods:', 'private(set) var epoch');
const strings = (s) => [...s.matchAll(/"([^"\n]+)"/g)].map((x) => x[1]).sort();
test('fixture matches actual Swift recovery sets', () => {
  assert.deepEqual(
    strings(reads),
    [...fixture.readQueries, ...fixture.originalReceiptQueries].sort(),
  );
  assert.deepEqual(strings(subs), [...fixture.subscriptionManagement].sort());
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
      '  private func requestData(',
    );
    const harness = `import Foundation
func easyWorkJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]) }
func easyWorkObject(_ data: Data) throws -> [String: Any] { try JSONSerialization.jsonObject(with: data) as! [String: Any] }
enum HostServiceError: Error { case message(String), rejected(Int,String), unavailable, invalidResponse }
struct EasyWorkRemoteRejection: Error { let code: String?; let message: String }
struct EasyWorkDeviceAuthorizationFailure: Error {}
enum L10n { static func tr(_ s: String) -> String { s } }
enum HostService { static func hash(_ data: Data) -> String { "unused" } }
struct EasyWorkTarget { let dictionary: [String: Any] = ["account": ["authority": "https://account.example", "subject": "owner"], "desktopDeviceId": "desktop", "workspaceId": "workspace", "runtimeId": "runtime"] }
struct Connection { enum Preference { case automatic, direct }; let preference: Preference = .direct }
actor Harness {
 ${reads}
 ${subs}
 var pendingRequestIds = Set<String>(); var responses: [String: [String: Any]] = [:]
 var lastError: Error?; var closed = false; var epoch = "epoch"; var activeCandidate = "route"
 let target = EasyWorkTarget(); let mobileDeviceId = "device"; let connection = Connection()
 var published: [Data] = []; var connects = 0; var disconnects = 0; var status = 0
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
 func request(_ method: String, _ params: [String: Any]) async throws -> Data { precondition(method == "command/status" && params["commandId"] as? String == "original"); status += 1; return try easyWorkJSON(NSNull()) }
 ${verified}
 ${method}
 func verify(_ method: String, _ params: Data, _ shouldSucceed: Bool, _ expectedStatus: Int) async throws {
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
   let h = Harness(fault, failTwice); try await h.verify(item["method"] as! String, try easyWorkJSON(item["params"]!), !failTwice, 0)
 } } }
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
