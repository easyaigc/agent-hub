import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('Native Codex authorization alert check requires macOS Swift.');
  process.exit(0);
}
const source = await readFile('apps/ios/Sources/SessionStore.swift', 'utf8');
function method(marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, `Missing ${marker}`);
  let end = source.indexOf('{', start) + 1,
    depth = 1;
  while (depth && end < source.length) {
    if (source[end] === '{') depth++;
    else if (source[end] === '}') depth--;
    end++;
  }
  assert.equal(depth, 0, `Unterminated ${marker}`);
  return source.slice(start, end);
}
const getter = method('  func connectionFailure(for id: String)');
const failure = method('  private func recordConnectionFailure(');
const success = method('  private func recordConnectionSuccess(');
const sheets = await readFile('apps/ios/Sources/Sheets.swift', 'utf8');
const authorizationCondition = 'host.known, case HostServiceError.rejected(401, _) = error';
assert(
  sheets.includes(`if ${authorizationCondition} { failedKnownHost = host }`),
  'Reconnect authorization condition changed',
);
const directory = await mkdtemp(join(tmpdir(), 'codex-authorization-alert-native-'));
const input = join(directory, 'main.swift');
const binary = join(directory, 'check');
let passed = false;
try {
  await writeFile(
    input,
    `import Foundation
typealias LocalizedText = String
struct L10n { static func tr(_ text: String) -> String { text } }
struct AgentRuntimeTarget: Equatable { let id: String }
struct AgentConnection { let authorizationNoticeIdentity: ConnectionAuthorizationIdentity<AgentRuntimeTarget>? }
struct Client { var id: String; var online = true; var agentConnection: AgentConnection? = nil; var known = true }
struct Saved { var clients: [Client] }
enum Phase { case failed, waiting }
enum HostServiceError: Error { case unavailable, rejected(Int, String) }
struct AgentFailure: ConnectionAuthorizationFailure { let authorizationNoticeKey: String? }
struct ReadFailure: RecoverableConnectionReadFailure { let marker: Int }
final class Store {
  var saved = Saved(clients: [Client(id: "codex")])
  var client: Client? = Client(id: "codex")
  var lastConnectionSuccess: [String: TimeInterval] = [:]
  var connectionFailures: [String: Error] = [:]
  var connectionAuthorizationNotices = ConnectionAuthorizationNotices<ConnectionAuthorizationIdentity<AgentRuntimeTarget>?>()
  var suggestionNeedsFreshHistory = false
  var connectionPhases: [String: Phase] = [:]
  var alerts = 0
  func presentError(_ error: Error) { alerts += 1 }
${getter}
${success}
${failure}
  func fail(_ error: Error, host: Client = Client(id: "codex"), startedAt: TimeInterval? = nil) {
    recordConnectionFailure(error, host: host, startedAt: startedAt)
  }
  func succeed(_ host: Client = Client(id: "codex")) { recordConnectionSuccess(host) }
}
func requestsAuthorization(_ error: Error, host: Client = Client(id: "codex")) -> Bool {
  if ${authorizationCondition} { return true }
  return false
}
func check(_ condition: @autoclosure () -> Bool, _ message: String) {
  guard condition() else { fputs("FAIL: \\(message)\\n", stderr); exit(1) }
}
let store = Store()
let network = URLError(.timedOut, userInfo: ["marker": "original"])
store.fail(network)
let retained = store.connectionFailure(for: "codex") as? URLError
check(retained?.code == .timedOut && retained?.userInfo["marker"] as? String == "original", "network error retained")
check(store.connectionPhases["codex"] == .waiting && store.alerts == 0, "network waits without alert")
check(!requestsAuthorization(retained!), "network is not authorization")
check(!requestsAuthorization(HostServiceError.rejected(403, "denied")), "403 is not 401")
check(requestsAuthorization(HostServiceError.rejected(401, "denied")), "401 requires authorization")
check(!requestsAuthorization(HostServiceError.rejected(401, "denied"), host: Client(id: "codex", known: false)), "unknown host does not reauthorize")
store.succeed()
check(store.connectionFailure(for: "codex") == nil && store.saved.clients[0].online && store.connectionPhases["codex"] == nil, "success clears failure and restores connection")
let oldStart = store.lastConnectionSuccess["codex"]! - 1
store.fail(ReadFailure(marker: 42), startedAt: oldStart)
check(store.connectionFailure(for: "codex") == nil && store.saved.clients[0].online && store.connectionPhases["codex"] == nil, "late transient read ignored after newer success")
store.fail(ReadFailure(marker: 42))
check((store.connectionFailure(for: "codex") as? ReadFailure)?.marker == 42 && store.connectionPhases["codex"] == .waiting, "recoverable read retains actual error")
store.succeed()
for _ in 0..<20 { store.fail(HostServiceError.rejected(401, "authorization")) }
check(store.alerts == 0 && !store.saved.clients[0].online && store.connectionPhases["codex"] == .failed, "background Codex 401 remains nonmodal")
check(requestsAuthorization(store.connectionFailure(for: "codex")!), "actual stored 401 is authorization")
store.fail(HostServiceError.rejected(403, "denied"))
check(store.alerts == 1, "unrelated rejection remains visible")
let identity = ConnectionAuthorizationIdentity(target: AgentRuntimeTarget(id: "agent"), credentialRef: "synthetic")
let agent = Client(id: "codex", agentConnection: AgentConnection(authorizationNoticeIdentity: identity))
store.fail(AgentFailure(authorizationNoticeKey: "revoked"), host: agent)
store.fail(AgentFailure(authorizationNoticeKey: "revoked"), host: agent)
check(store.alerts == 2, "other Agent repeated authorization is deduplicated")
store.succeed(agent)
store.fail(AgentFailure(authorizationNoticeKey: "revoked"), host: agent)
check(store.alerts == 2, "ordinary success does not reset authorization notice")
store.fail(AgentFailure(authorizationNoticeKey: "changed"), host: agent)
check(store.alerts == 3, "different authorization failure is visible")
store.fail(HostServiceError.rejected(401, "authorization"), host: agent)
check(store.alerts == 4, "other Agent 401 remains visible")
store.client = Client(id: "other")
store.fail(HostServiceError.rejected(403, "denied"))
check(store.alerts == 4 && store.client?.id == "other", "target switch suppresses unrelated alert")
print("PASS: retained network/read errors, actual 401 classification, recovery and stale-read fence, background Codex 401, Agent authorization deduplication and target switch")
`,
  );
  const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
  const compile = spawnSync(
    'xcrun',
    [
      'swiftc',
      input,
      'apps/ios/Sources/OperationFailure.swift',
      'apps/ios/Sources/SharedFailureCopy.swift',
      '-o',
      binary,
    ],
    { env, encoding: 'utf8' },
  );
  if (compile.stdout) process.stdout.write(compile.stdout);
  if (compile.stderr) process.stderr.write(compile.stderr);
  assert.equal(
    compile.status,
    0,
    `swiftc failed: ${compile.error ?? compile.signal ?? compile.status}`,
  );
  const result = spawnSync(binary, [], { encoding: 'utf8' });
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  assert.equal(
    result.status,
    0,
    `native check failed: ${result.error ?? result.signal ?? result.status}`,
  );
  passed = true;
} finally {
  if (passed) {
    await rm(directory, { recursive: true });
    console.log(`Removed temporary directory: ${directory}`);
  } else {
    console.error(`Failure artifacts retained: ${directory}`);
  }
}
