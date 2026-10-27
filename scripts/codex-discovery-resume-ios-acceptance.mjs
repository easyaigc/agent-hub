import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { randomUUID, createHash } from 'node:crypto';
import { resolve, join, dirname } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { prepareIsolatedIOSRunner } from './mobile/isolated-ios-runner.mjs';
import { verifyIOSResultDevice } from './mobile/ios-result-device.mjs';

const prefix = resolve(process.env.HOST_KEY_RESUME_DIRECTORY);
assert(prefix.startsWith(resolve('.local/codex-discovery-20261011/real-')));
const device = process.env.HOST_KEY_PHYSICAL_DEVICE;
assert(
  device && process.env.HOST_KEY_PHYSICAL_XCTESTRUN && process.env.HOST_KEY_PHYSICAL_BUNDLE_ID,
);
const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
const summaryOf = (path) =>
  JSON.parse(
    execFileSync('xcrun', ['xcresulttool', 'get', 'test-results', 'summary', '--path', path], {
      env,
      encoding: 'utf8',
    }),
  );
const oldSummary = summaryOf(join(prefix, 'native.xcresult'));
verifyIOSResultDevice(oldSummary, device);
assert.equal(oldSummary.passedTests, 1);
assert.equal(oldSummary.failedTests, 1);
assert.equal(oldSummary.skippedTests, 0);
assert(
  oldSummary.testFailures.every((failure) =>
    failure.testIdentifierString.includes('testCodexM5AndLegacyDiscoveryRealDEV'),
  ),
);
const log = await readFile(join(prefix, 'native.log'), 'utf8');
assert(log.includes("testCodexDirectDiscoveryRealDEV]' passed"));
assert(log.includes("Added attachment named 'actual-m5-key-authorized'"));
assert(log.includes("Added attachment named 'legacy-automatic-connected-without-key'"));
const rows = Object.values(
  JSON.parse(await readFile(join(prefix, 'desktop-creations/creations.json'), 'utf8')),
);
const entries = rows.filter((row) => /CODEX_DIRECT_[a-f0-9]{8}/.test(JSON.stringify(row)));
assert.equal(entries.length, 1);
const operation = entries[0],
  marker = JSON.stringify(operation).match(/CODEX_DIRECT_[a-f0-9]{8}/)[0];
const state = JSON.parse(await readFile(join(prefix, 'runtime.json'), 'utf8'));
const reader = new DesktopRuntime(
  {
    mode: 'desktop-runtime',
    directory: prefix,
    executable:
      '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
    root: process.cwd(),
  },
  state.hostId,
  'Independent completed-input verification',
);
async function auditModel() {
  await reader.ipc.follow(operation.threadId, 15000, true);
  const thread = reader.ipc.state(operation.threadId);
  const turns = desktopTurns(thread).filter((turn) => JSON.stringify(turn).includes(marker));
  assert.equal(turns.length, 1);
  assert(reader.ipc.hasOwner(operation.threadId));
  const output = turns[0].items
    .filter((item) => item.type === 'agentMessage')
    .map((item) => item.text ?? '')
    .join('\n')
    .trim();
  assert.equal(output, marker);
  return {
    hostId: state.hostId,
    threadId: operation.threadId,
    operationId: operation.clientOperationId,
    modelTurns: turns.length,
    output,
  };
}
const directory = resolve('.local/codex-discovery-20261011/resume-' + randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
try {
  await reader.connect();
  const before = await auditModel();
  const inventory = JSON.parse(
    await readFile(join(process.env.HOME, '.config/agent-hub/hosts.json'), 'utf8'),
  );
  const remote = inventory.hosts.find((row) => row.hostId === process.env.HOST_KEY_REMOTE_HOST_ID);
  assert(remote && remote.hostId !== inventory.ownerHostId);
  const response = await fetch(remote.endpoint + '/host', {
    headers: { Authorization: 'Bearer ' + remote.clientKey },
    redirect: 'error',
  });
  assert.equal(response.status, 200);
  const descriptor = await response.json();
  assert.equal(descriptor.target.hostId, remote.hostId);
  assert.equal(descriptor.connectionMode, 'host-key');
  const prepared = await prepareIsolatedIOSRunner({
    template: process.env.HOST_KEY_PHYSICAL_XCTESTRUN,
    directory: join(directory, 'runner'),
    expectedAppBundleId: process.env.HOST_KEY_PHYSICAL_BUNDLE_ID,
  });
  const runner = join(dirname(prepared.template), 'M5-resume-' + randomUUID() + '.xctestrun');
  const parameters = join(directory, 'parameters.json');
  await writeFile(
    parameters,
    JSON.stringify({
      HOST_KEY_DEV_REMOTE_HOST_ID: remote.hostId,
      HOST_KEY_DEV_REMOTE_NAME: descriptor.name,
    }),
    { mode: 0o600 },
  );
  execFileSync('python3', [
    '-c',
    `import plistlib,json,sys,os
p=plistlib.load(open(sys.argv[1],'rb'));e=json.load(open(sys.argv[2]))
for k,v in p.items():
 if k!='__xctestrun_metadata__' and isinstance(v,dict):v.setdefault('EnvironmentVariables',{}).update(e)
with open(sys.argv[3],'wb') as f:plistlib.dump(p,f)
os.chmod(sys.argv[3],0o600)`,
    prepared.template,
    parameters,
    runner,
  ]);
  const result = join(directory, 'native.xcresult'),
    nativeLog = createWriteStream(join(directory, 'native.log'), { mode: 0o600 });
  const child = spawn(
    'xcodebuild',
    [
      '-xctestrun',
      runner,
      '-destination',
      'platform=iOS,id=' + device,
      '-resultBundlePath',
      result,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testCodexM5ReconnectAfterRestoreRealDEV',
      'test-without-building',
    ],
    { env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  child.stdout.pipe(nativeLog);
  child.stderr.pipe(nativeLog);
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  assert.equal(code, 0, 'M5 resume failed: ' + directory);
  const summary = summaryOf(result),
    devices = verifyIOSResultDevice(summary, device);
  assert.equal(summary.result, 'Passed');
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  const after = await auditModel();
  assert.deepEqual(after, before);
  const proof = {
    passed: true,
    prefixDirectory: prefix,
    prefixOverallPassed: false,
    reusedPassedScope:
      'First Host Key UI, wrong key, cancel/method switching, one actual original-desktop model, App restart, Host restart and credential reuse',
    recoveredScope:
      'Actual M5 discovered and key authorized in prefix; restored existing authorized connection and selected known row without key in current run',
    legacyAutomatic:
      'Actual old M4 discovery and connection passed before prefix navigation failure',
    model: after,
    native: {
      result: summary.result,
      passedTests: summary.passedTests,
      failedTests: summary.failedTests,
      skippedTests: summary.skippedTests,
      devices,
    },
    noNewModelInputSubmitted: true,
    productionSourceSHA256: createHash('sha256')
      .update(await readFile('apps/host-agent/dist/direct-discovery.js'))
      .update(await readFile('apps/ios/Sources/CodexDiscovery.swift'))
      .digest('hex'),
  };
  await writeFile(join(directory, 'report.json'), JSON.stringify(proof, null, 2), { mode: 0o600 });
  console.log(JSON.stringify({ passed: true, report: join(directory, 'report.json') }));
} finally {
  reader.close();
}
