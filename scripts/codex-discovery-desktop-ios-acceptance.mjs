import assert from 'node:assert/strict';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { randomUUID, createHash } from 'node:crypto';
import { resolve, join } from 'node:path';
import { execFileSync, spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

const directory = resolve('.local/codex-discovery-20261011/real-' + randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
const executable =
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const seed = new RuntimeSession({
  mode: 'desktop-runtime',
  directory,
  root: process.cwd(),
  executable,
});
const hostId = seed.hostId;
seed.close();
const desktopVersion = execFileSync(
  '/usr/libexec/PlistBuddy',
  ['-c', 'Print :CFBundleShortVersionString', '/Applications/ChatGPT.app/Contents/Info.plist'],
  { encoding: 'utf8' },
).trim();
const config = join(directory, 'bridge.json');
await writeFile(config, JSON.stringify({ hostId, desktopVersion }), { mode: 0o600 });
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE: executable,
  CODEX_DESKTOP_VERSION: desktopVersion,
  CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: config,
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_ROOT: process.cwd(),
  HOST_AGENT_NAME: 'Codex Discovery DEV',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_DISCOVERY: '1',
  HOST_AGENT_DISCOVERY_PORT: '0',
});
delete process.env.HOST_AGENT_DISCOVERY_BONJOUR;
let host = await startRuntimeServer();
assert.ok(host.direct);
const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
await fetch('http://127.0.0.1:' + host.server.address().port + '/v1/workspace', {
  headers: { authorization: 'Bearer ' + token },
  signal: AbortSignal.timeout(90000),
}).then((response) => {
  assert.equal(response.status, 200);
  return response.arrayBuffer();
});
const marker = 'CODEX_DIRECT_' + randomUUID().slice(0, 8);
const fixture = {
  hostId,
  marker,
  uri:
    'codex-host://pair?descriptor=' +
    Buffer.from(JSON.stringify({ ...host.direct.descriptor(), token })).toString('base64url'),
};
const originalFingerprint = host.direct.descriptor().fingerprint;
const originalPort = host.direct.server.address().port;
const reader = new DesktopRuntime(
  { mode: 'desktop-runtime', directory, executable, root: process.cwd() },
  hostId,
  'Independent Discovery Verification',
);
const control = createServer(async (req, res) => {
  if (req.headers.origin || !['127.0.0.1:8797', 'localhost:8797'].includes(req.headers.host)) {
    res.writeHead(403);
    res.end();
    return;
  }
  if (req.method === 'POST' && req.url === '/restart') {
    host.close();
    host = await startRuntimeServer();
    assert.equal(host.direct.descriptor().fingerprint, originalFingerprint);
    assert.notEqual(host.direct.server.address().port, originalPort);
    res.writeHead(200);
    res.end('{}');
    return;
  }
  if (req.method !== 'GET' || req.url !== '/') {
    res.writeHead(404);
    res.end();
    return;
  }
  res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  res.end(JSON.stringify(fixture));
});
await new Promise((done, reject) => {
  control.once('error', reject);
  control.listen(8797, '127.0.0.1', done);
});
try {
  const simulator = process.env.IOS_SIMULATOR_ID ?? '381A3225-80F2-4354-AED9-B384EB0FF31C';
  const resultBundle = join(directory, 'native.xcresult');
  const log = await import('node:fs').then((fs) =>
    fs.createWriteStream(join(directory, 'native.log'), { mode: 0o600 }),
  );
  const child = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + simulator,
      '-derivedDataPath',
      process.env.IOS_DERIVED_DATA_PATH ?? join(directory, 'build'),
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-only-testing:CodexManageUITests/CodexManageUITests/testCodexDirectDiscoveryRealDEV',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    {
      env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  assert.equal(code, 0, 'Native acceptance failed: ' + join(directory, 'native.log'));
  await reader.connect();
  const creations = JSON.parse(
    await readFile(join(directory, 'desktop-creations/creations.json'), 'utf8'),
  );
  const matches = Object.values(creations).filter((row) => JSON.stringify(row).includes(marker));
  assert.equal(matches.length, 1, 'Exactly one submitted creation');
  const operation = matches[0];
  assert.ok(operation.threadId);
  await reader.ipc.follow(operation.threadId, 15000, true);
  const thread = reader.ipc.state(operation.threadId);
  assert.ok(reader.ipc.hasOwner(operation.threadId), 'Original owner must remain live');
  const turns = desktopTurns(thread);
  const matchingTurns = turns.filter((turn) => JSON.stringify(turn).includes(marker));
  assert.equal(matchingTurns.length, 1, 'Exactly one model turn');
  const items = matchingTurns[0].items;
  const output = items
    .filter((item) => item.type === 'agentMessage')
    .map((item) => item.text ?? '')
    .join('\n')
    .trim();
  assert.equal(output, marker);
  const proof = {
    passed: true,
    desktopVersion,
    hostId,
    threadId: operation.threadId,
    operationId: operation.clientOperationId,
    marker,
    modelTurns: matchingTurns.length,
    output,
    originalPort,
    recoveredPort: host.direct.server.address().port,
    certificatePreserved: true,
    resultBundle,
    sourceSHA256: createHash('sha256')
      .update(await readFile('apps/host-agent/dist/direct-discovery.js'))
      .update(await readFile('apps/ios/Sources/CodexDiscovery.swift'))
      .digest('hex'),
    scenarios: [
      'native QR description with actual private host',
      'real original-desktop model',
      'App restart and cache',
      'Host restart changed port via Bonjour',
      'independent owner and one submission',
    ],
  };
  await writeFile(join(directory, 'report.json'), JSON.stringify(proof, null, 2), { mode: 0o600 });
  console.log(JSON.stringify({ passed: true, report: join(directory, 'report.json') }));
} finally {
  control.close();
  control.closeAllConnections();
  host.close();
  reader.close();
}
