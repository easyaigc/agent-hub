import assert from 'node:assert/strict';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { randomUUID, createHash, randomBytes } from 'node:crypto';
import { resolve, join } from 'node:path';
import { execFileSync, spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { prepareIsolatedIOSRunner } from './mobile/isolated-ios-runner.mjs';
import { verifyIOSResultDevice } from './mobile/ios-result-device.mjs';

const directory = resolve('.local/codex-discovery-20261011/real-' + randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
const physical = process.env.HOST_KEY_PHYSICAL_DEVICE;
const controlBind = physical ? process.env.HOST_KEY_CONTROL_LAN_ADDRESS : '127.0.0.1';
assert(controlBind && (!physical || /^192\.168\.\d{1,3}\.\d{1,3}$/.test(controlBind)));
const controlToken = randomBytes(32).toString('base64url');
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
let claimPosts = 0;
const observe = () =>
  host.direct.server.on('request', (req) => {
    if (req.method === 'POST' && req.url === '/connection/claim') claimPosts++;
  });
observe();
const fixture = {
  hostId,
  marker,
  hostKey: token,
  uri:
    'codex-host://pair?descriptor=' +
    Buffer.from(JSON.stringify({ ...host.direct.descriptor(), token })).toString('base64url'),
};
const remoteId = process.env.HOST_KEY_REMOTE_HOST_ID;
if (remoteId) {
  const inventory = JSON.parse(
    await readFile(join(process.env.HOME, '.config/agent-hub/hosts.json'), 'utf8'),
  );
  const remote = inventory.hosts.find((row) => row.hostId === remoteId);
  assert(
    remote && remoteId !== inventory.ownerHostId,
    'Select the explicitly authorized remote target distinct from this Host',
  );
  fixture.remoteHostId = remoteId;
  fixture.remoteHostKey = remote.clientKey;
  const local = inventory.hosts.find((row) => row.hostId === inventory.ownerHostId);
  const descriptor = await fetch(local.endpoint + '/host', {
    headers: { Authorization: 'Bearer ' + local.clientKey },
    redirect: 'error',
  }).then((r) => r.json());
  if (descriptor.connectionMode === 'automatic') fixture.legacyHostId = local.hostId;
}
const originalFingerprint = host.direct.descriptor().fingerprint;
const originalPort = host.direct.server.address().port;
fixture.originalDiscoveryPort = String(originalPort);
fixture.stageNonce = randomUUID();
const observedStages = new Set();
let restartPromise;
let stageFailure;
const reader = new DesktopRuntime(
  { mode: 'desktop-runtime', directory, executable, root: process.cwd() },
  hostId,
  'Independent Discovery Verification',
);
const control = createServer(async (req, res) => {
  if (
    req.headers.origin ||
    ![controlBind + ':8797', 'localhost:8797'].includes(req.headers.host) ||
    (physical && req.headers.authorization !== 'Bearer ' + controlToken)
  ) {
    res.writeHead(403);
    res.end();
    return;
  }
  if (req.method === 'POST' && req.url === '/restart') {
    host.close();
    host = await startRuntimeServer();
    observe();
    assert.equal(host.direct.descriptor().fingerprint, originalFingerprint);
    assert.notEqual(host.direct.server.address().port, originalPort);
    res.writeHead(200);
    res.end('{}');
    return;
  }
  if (req.method === 'GET' && req.url === '/stats') {
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ claimPosts }));
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
  control.listen(8797, controlBind, done);
});
try {
  const simulator = process.env.IOS_SIMULATOR_ID ?? '381A3225-80F2-4354-AED9-B384EB0FF31C';
  let runner;
  if (physical) {
    const prepared = await prepareIsolatedIOSRunner({
      template: process.env.HOST_KEY_PHYSICAL_XCTESTRUN,
      directory: join(directory, 'native-runner'),
      expectedAppBundleId:
        process.env.HOST_KEY_PHYSICAL_BUNDLE_ID ?? 'com.easyai.agenthub.hostkeydev',
    });
    const template = prepared.template;
    runner = join(resolve(template, '..'), 'HostKey-' + randomUUID() + '.xctestrun');
    const input = join(directory, 'native-environment.json');
    await writeFile(
      input,
      JSON.stringify({
        HOST_KEY_DEV_CONTROL_URL: 'http://' + controlBind + ':8797',
        HOST_KEY_DEV_CONTROL_TOKEN: controlToken,
        HOST_KEY_DEV_APP_BUNDLE_ID:
          process.env.HOST_KEY_PHYSICAL_BUNDLE_ID ?? 'com.easyai.agenthub.hostkeydev',
        HOST_KEY_DEV_FIXTURE: Buffer.from(JSON.stringify(fixture)).toString('base64'),
      }),
      { mode: 0o600 },
    );
    execFileSync('python3', [
      '-c',
      `import plistlib,json,sys,os
p=plistlib.load(open(sys.argv[1],'rb'));e=json.load(open(sys.argv[2]));count=0
for key,target in p.items():
 if key!='__xctestrun_metadata__' and isinstance(target,dict):
  app=target.get('UITargetAppPath','').replace('__TESTROOT__',os.path.dirname(sys.argv[1]))
  info=plistlib.load(open(os.path.join(app,'Info.plist'),'rb'))
  assert info.get('CFBundleIdentifier')==sys.argv[4],'Require the isolated Host Key App'
  target.setdefault('EnvironmentVariables',{}).update(e);count+=1
assert count==1
with open(sys.argv[3],'wb') as f:plistlib.dump(p,f)
os.chmod(sys.argv[3],0o600)
`,
      template,
      input,
      runner,
      process.env.HOST_KEY_PHYSICAL_BUNDLE_ID ?? 'com.easyai.agenthub.hostkeydev',
    ]);
  }
  const resultBundle = join(directory, 'native.xcresult');
  const log = await import('node:fs').then((fs) =>
    fs.createWriteStream(join(directory, 'native.log'), { mode: 0o600 }),
  );
  const child = spawn(
    'xcodebuild',
    [
      ...(physical
        ? ['-xctestrun', runner]
        : ['-project', 'apps/ios/CodexManage.xcodeproj', '-scheme', 'CodexManage']),
      '-destination',
      physical ? 'platform=iOS,id=' + physical : 'platform=iOS Simulator,id=' + simulator,
      ...(physical
        ? []
        : ['-derivedDataPath', process.env.IOS_DERIVED_DATA_PATH ?? join(directory, 'build')]),
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testCodexDirectDiscoveryRealDEV',
      ...(remoteId
        ? [
            '-only-testing:CodexManageUITests/CodexManageUITests/testCodexM5AndLegacyDiscoveryRealDEV',
          ]
        : []),
      ...(physical ? ['test-without-building'] : ['CODE_SIGN_IDENTITY=-', 'test']),
    ],
    {
      env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  if (physical) {
    let pending = '';
    const observeStages = (chunk) => {
      pending = (pending + chunk.toString()).slice(-65536);
      for (const stage of ['NO_CLAIM', 'RESTART']) {
        if (
          observedStages.has(stage) ||
          !pending.includes('HOST_KEY_DEV_' + fixture.stageNonce + '_' + stage)
        )
          continue;
        observedStages.add(stage);
        try {
          if (stage === 'NO_CLAIM')
            assert.equal(claimPosts, 0, 'No device claim before explicit key submission');
          else {
            restartPromise = (async () => {
              host.close();
              host = await startRuntimeServer();
              observe();
              assert.equal(host.direct.descriptor().fingerprint, originalFingerprint);
              assert.notEqual(host.direct.server.address().port, originalPort);
            })().catch((error) => {
              stageFailure = error;
            });
          }
        } catch (error) {
          stageFailure = error;
        }
      }
    };
    child.stdout.on('data', observeStages);
    child.stderr.on('data', observeStages);
  }
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  await restartPromise;
  if (stageFailure) throw stageFailure;
  assert.equal(code, 0, 'Native acceptance failed: ' + join(directory, 'native.log'));
  if (physical) assert.deepEqual([...observedStages].sort(), ['NO_CLAIM', 'RESTART']);
  let physicalDevices;
  if (physical) {
    const summary = JSON.parse(
      execFileSync(
        'xcrun',
        ['xcresulttool', 'get', 'test-results', 'summary', '--path', resultBundle],
        { encoding: 'utf8' },
      ),
    );
    await writeFile(join(directory, 'native-summary.json'), JSON.stringify(summary, null, 2), {
      mode: 0o600,
    });
    physicalDevices = verifyIOSResultDevice(summary, physical);
    assert.equal(summary.result, 'Passed');
    assert.equal(
      summary.passedTests,
      remoteId ? 2 : 1,
      'All selected physical tests must actually run',
    );
    assert.equal(summary.failedTests, 0);
    assert.equal(summary.skippedTests, 0);
  }
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
      'native Bonjour discovery, empty key, wrong key, cancel, explicit Host Key authorization',
      'real original-desktop model',
      'App restart and cache',
      'Host restart changed port via Bonjour',
      'independent owner and one submission',
    ],
    claimPosts,
    physicalDevices,
    device: physical
      ? 'physical iPhone; isolated com.easyai.agenthub.hostkeydev'
      : 'dedicated iOS simulator',
  };
  assert.equal(claimPosts, 2, 'One rejected key and one authorized claim; no claim on reconnect');
  await writeFile(join(directory, 'report.json'), JSON.stringify(proof, null, 2), { mode: 0o600 });
  console.log(JSON.stringify({ passed: true, report: join(directory, 'report.json') }));
} finally {
  control.close();
  control.closeAllConnections();
  host.close();
  reader.close();
}
