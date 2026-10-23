import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { promisify } from 'node:util';

const run = promisify(execFile);
const simulator = process.env.IOS_SIMULATOR_ID;
assert(simulator, 'Select a dedicated iOS simulator');
const root = '.local/android-development/upstream-migration/UM02/ios-wire-' + Date.now();
await mkdir(root, { recursive: true, mode: 0o700 });
const paths = [
  'apps/ios/Sources/HostService.swift',
  'apps/ios/Sources/Models.swift',
  'apps/ios/Sources/NotificationModels.swift',
  'packages/protocol/schemas/codex-mobile-http-v1.schema.json',
  'packages/protocol/fixtures/mobile/reply-recovery-v2.json',
  'scripts/mobile/reply-recovery-ios-wire.mjs',
];
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const inputs = Object.fromEntries(
  await Promise.all(paths.map(async (name) => [name, sha(await readFile(name))])),
);
const [host, models, notification] = await Promise.all(
  paths.slice(0, 3).map((name) => readFile(name, 'utf8')),
);
const slice = (text, start, end) => {
  const a = text.indexOf(start),
    b = text.indexOf(end, a + start.length);
  assert(a >= 0 && b > a, 'Production extraction boundary changed');
  return text.slice(a, b);
};
const methods = slice(host, '  static func bytes(', '  static func workspace(');
const source =
  host.split('// Tokens never enter')[0] +
  '\n' +
  notification.split('// Machine identity is distinct')[0] +
  '\n' +
  slice(models, 'struct LocalAttachment:', 'struct ClientType:') +
  '\n' +
  slice(models, 'struct DeliveryReceipt:', 'struct ThreadRuntime:') +
  '\n' +
  slice(models, 'struct PendingReply:', 'struct CodexModel:') +
  '\n' +
  slice(models, 'struct AISharingScope:', 'struct AISharingGrant:') +
  `
// Unexercised generic comparison types are fail-closed, never fixture successes.
struct WorkspaceSnapshot: Decodable { init(from: Decoder) throws { throw HostServiceError.invalidResponse } }
struct HistorySnapshot: Decodable { init(from: Decoder) throws { throw HostServiceError.invalidResponse } }
struct EventBatch: Decodable { init(from: Decoder) throws { throw HostServiceError.invalidResponse } }
// Network fixture seam only; production bytes/load methods below are unchanged.
final class CodexHostRoutes {
  static let shared = CodexHostRoutes()
  func data(for request: URLRequest) async throws -> (Data, URLResponse) { try await URLSession.shared.data(for: request) }
}
enum HostService {
${methods}
}
let fixture = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))) as! [String: Any]
func data(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.withoutEscapingSlashes]) }
let base = (fixture["fingerprints"] as! [[String: Any]])[0]
let pending = try JSONDecoder().decode(PendingReply.self, from: data(base["reply"]!))
let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: data(base["target"]!))
let record = ReplyRecoveryRecord(original: pending, target: target, trust: "synthetic-only", fingerprint: base["fingerprint"] as! String, phase: "strong-not-accepted")
let now = Date(timeIntervalSince1970: (fixture["nowEpochMillis"] as! NSNumber).doubleValue / 1000)
Task {
  let proofs = fixture["proofs"] as! [[String: Any]]
  for (index, test) in proofs.enumerated() {
    var accepted = false
    do {
      let value: ReplyDeliveryInspection = try await HostService.load(URLRequest(url: URL(string: CommandLine.arguments[1] + "/proof/" + String(index))!))
      accepted = ReplyRecoveryPolicy.strongNotAccepted(value, record: record, now: now)
    } catch { accepted = false }
    precondition(accepted == test["expected"] as! Bool, test["name"] as! String)
  }
  print("NATIVE_REPLY_WIRE_PASSED " + String(proofs.count))
  exit(0)
}
RunLoop.main.run()
`;
await writeFile(root + '/main.swift', source);
const vectors = JSON.parse(await readFile(paths[4], 'utf8'));
const requests = [];
const server = createServer((request, response) => {
  const index = Number(request.url?.match(/^\/proof\/(\d+)$/)?.[1]);
  requests.push({ method: request.method, index });
  if (request.method !== 'GET' || !Number.isInteger(index) || !vectors.proofs[index]) {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(vectors.proofs[index].value));
});
const report = {
  status: 'running',
  simulator,
  inputs,
  productionMethodsSha256: sha(methods),
  scope:
    'Exact production HostService bytes/load and native DTO/policy, actual simulator URLSession HTTP with synthetic proof vectors. Routes delegate is a fixture seam; not App UI, paired TLS or real desktop/model acceptance.',
  realModelTasks: 0,
};
try {
  const sdk = (await run('xcrun', ['--sdk', 'iphonesimulator', '--show-sdk-path'])).stdout.trim();
  const binary = resolve(root, 'verify-wire');
  const compile = await run('xcrun', [
    'swiftc',
    '-sdk',
    sdk,
    '-target',
    'arm64-apple-ios17.0-simulator',
    '-F',
    '.build/native/mobile-core/bin/iosSimulatorArm64/debugFramework',
    '-framework',
    'MobileCore',
    root + '/main.swift',
    '-o',
    binary,
  ]);
  await writeFile(root + '/compile.log', compile.stdout + compile.stderr);
  await run('codesign', ['--force', '--sign', '-', binary]);
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const result = await run(
    'xcrun',
    [
      'simctl',
      'spawn',
      simulator,
      binary,
      'http://127.0.0.1:' + server.address().port,
      resolve(paths[4]),
    ],
    { timeout: 120_000, maxBuffer: 1024 * 1024 },
  );
  await writeFile(root + '/native.log', result.stdout + result.stderr);
  assert(result.stdout.includes('NATIVE_REPLY_WIRE_PASSED 26'));
  assert.equal(requests.length, 26);
  assert.equal(new Set(requests.map((value) => value.index)).size, 26);
  for (const path of paths)
    assert.equal(sha(await readFile(path)), inputs[path], 'Wire acceptance input changed: ' + path);
  report.status = 'passed';
  report.passed = 26;
  report.failed = report.skipped = 0;
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
  await writeFile(
    root + '/failure.log',
    (error.stdout ?? '') + (error.stderr ?? '') + '\n' + error.message,
  );
} finally {
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
  report.requests = requests;
  await writeFile(root + '/report.json', JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(
    JSON.stringify({ status: report.status, passed: report.passed, report: root + '/report.json' }),
  );
}
