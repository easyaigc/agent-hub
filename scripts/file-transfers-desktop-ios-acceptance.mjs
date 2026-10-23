import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { join, resolve, basename } from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';

// Isolated DEV Host and simulator, real official desktop/model, no shared service restart.
const nonce = randomUUID().slice(0, 8),
  hostId = randomUUID();
const directory = resolve('.local/file-transfers-desktop-ios', nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
const device = process.env.IOS_SIMULATOR_ID;
assert(device, 'Choose a dedicated iOS simulator');
const executable =
  process.env.CODEX_EXECUTABLE ??
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const desktopVersion = execFileSync(
  '/usr/libexec/PlistBuddy',
  ['-c', 'Print :CFBundleShortVersionString', '/Applications/ChatGPT.app/Contents/Info.plist'],
  { encoding: 'utf8' },
).trim();
await writeFile(
  join(directory, 'runtime.json'),
  JSON.stringify({
    hostId,
    projects: [],
    threads: [],
    attachments: [],
    receipts: [],
    creations: {},
  }),
  { mode: 0o600 },
);
await writeFile(
  join(directory, 'bridge.json'),
  JSON.stringify({ hostId, desktopVersion, validationOnly: true }),
  { mode: 0o600 },
);
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE: executable,
  CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(directory, 'bridge.json'),
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_ROOT: process.cwd(),
  HOST_AGENT_NAME: '文件互传 DEV',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_TRANSFER_DIR: join(directory, 'Downloads', 'Agent Hub'),
  HOST_AGENT_DISCOVERY_BONJOUR: '0',
});
delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
const service = await startRuntimeServer();
const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
const endpoint = `http://127.0.0.1:${service.server.address().port}`;
const content = 'TRANSFER_CONTENT_' + randomUUID();
const run = (command, args) => execFileSync(command, args, { encoding: 'utf8', env: process.env });
const fingerprint = createHash('sha256');
for (const file of [
  'apps/host-agent/dist/file-transfers.js',
  'apps/host-agent/dist/runtime-server.js',
  'packages/protocol/dist/file-transfers.js',
  'apps/ios/CodexManage.xcodeproj/project.pbxproj',
  ...(await readdir('apps/ios/Sources'))
    .filter((name) => name.endsWith('.swift'))
    .sort()
    .map((name) => 'apps/ios/Sources/' + name),
])
  fingerprint.update(await readFile(file));
const report = {
  kind: 'real-original-desktop-file-transfer-native',
  nonce,
  hostId,
  desktopVersion,
  fingerprint: fingerprint.digest('hex'),
  startedAt: new Date().toISOString(),
  status: 'running',
  device,
  scenarios: [],
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
await save();
let metadata, witness;
try {
  const request = async (path) => {
    const result = await fetch(endpoint + path, { headers: { Authorization: 'Bearer ' + token } });
    assert.equal(result.status, 200, path);
    return result.json();
  };
  metadata = createServer(async (req, res) => {
    try {
      if (
        req.method !== 'GET' ||
        req.headers.origin ||
        !['127.0.0.1:8821', 'localhost:8821'].includes(req.headers.host)
      ) {
        res.writeHead(403).end();
        return;
      }
      res.setHeader('Content-Type', 'application/json');
      res.setHeader('Cache-Control', 'no-store');
      if (req.url === '/')
        res.end(
          JSON.stringify({
            endpoint,
            token,
            hostId,
            threadId: '',
            threadTitle: '',
            nonce,
            secondEndpoint: '',
            secondToken: '',
            secondHostId: '',
            delayedEndpoint: '',
          }),
        );
      else if (req.url === '/verified') {
        const listing = await request('/v1/file-transfers?hostId=' + hostId);
        assert.equal(listing.files.length, 1);
        const file = listing.files[0];
        assert.equal(file.phase, 'completed');
        assert.equal(await readFile(file.destinationPath, 'utf8'), content);
        assert.equal(file.sha256, createHash('sha256').update(content).digest('hex'));
        const workspace = await service.session.workspace();
        assert(
          !workspace.threads.some(
            (thread) => thread.hostId === hostId && thread.title.includes('IOS_TRANSFER_' + nonce),
          ),
          'Upload alone must not start a task',
        );
        report.scenarios.push({
          name: 'native-system-file-picker-upload-and-relaunch',
          status: 'passed',
          transferId: file.id,
          destination: basename(file.destinationPath),
          sha256: file.sha256,
        });
        await save();
        res.end(JSON.stringify({ path: file.destinationPath, content }));
      } else res.writeHead(404).end();
    } catch (error) {
      res.writeHead(500).end(JSON.stringify({ error: error.message }));
    }
  });
  await new Promise((done, reject) => {
    metadata.once('error', reject);
    metadata.listen(8821, '127.0.0.1', done);
  });
  run('xcrun', ['simctl', 'bootstatus', device, '-b']);
  const derived = resolve(
    process.env.FILE_TRANSFER_IOS_DERIVED_DATA ?? '.build/ios-file-transfers',
  );
  const xcode = async (action) => {
    const log = createWriteStream(join(directory, action + '.log'), { mode: 0o600 });
    const child = spawn(
      'xcodebuild',
      [
        '-project',
        'apps/ios/CodexManage.xcodeproj',
        '-scheme',
        'CodexManage',
        '-destination',
        `platform=iOS Simulator,id=${device}`,
        '-derivedDataPath',
        derived,
        '-parallel-testing-enabled',
        'NO',
        '-only-testing:CodexManageUITests/CodexManageUITests/testComputerFileTransferRealDesktopDEV',
        ...(action === 'test-without-building'
          ? ['-resultBundlePath', join(directory, 'native.xcresult')]
          : []),
        'CODE_SIGNING_ALLOWED=YES',
        'CODE_SIGN_IDENTITY=-',
        action,
      ],
      { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
    );
    child.stdout.pipe(log, { end: false });
    child.stderr.pipe(log, { end: false });
    const code = await new Promise((done, reject) => {
      child.once('error', reject);
      child.once('close', done);
    });
    await new Promise((done) => log.end(done));
    assert.equal(code, 0, `Native ${action} failed: ${directory}`);
  };
  await xcode('build-for-testing');
  run('xcrun', [
    'simctl',
    'install',
    device,
    join(derived, 'Build/Products/Debug-iphonesimulator/CodexManage.app'),
  ]);
  const bundleId = run('/usr/libexec/PlistBuddy', [
    '-c',
    'Print :CFBundleIdentifier',
    join(derived, 'Build/Products/Debug-iphonesimulator/CodexManage.app/Info.plist'),
  ]).trim();
  const container = run('xcrun', ['simctl', 'get_app_container', device, bundleId, 'data']).trim();
  await mkdir(join(container, 'Documents'), { recursive: true });
  await writeFile(join(container, 'Documents/transfer-input.txt'), content);
  await xcode('test-without-building');
  report.nativeSummary = JSON.parse(
    run('xcrun', [
      'xcresulttool',
      'get',
      'test-results',
      'summary',
      '--path',
      join(directory, 'native.xcresult'),
    ]),
  );
  assert.equal(report.nativeSummary.passedTests, 1);
  assert.equal(report.nativeSummary.failedTests, 0);
  assert.equal(report.nativeSummary.skippedTests, 0);
  const creations = Object.values(
    JSON.parse(await readFile(join(directory, 'desktop-creations/creations.json'), 'utf8')),
  ).filter((record) => record.text.includes('IOS_TRANSFER_' + nonce));
  assert.equal(creations.length, 1);
  const creation = creations[0];
  assert(creation.threadId);
  witness = new DesktopRuntime(
    { mode: 'desktop-runtime', executable },
    hostId,
    'File transfer independent witness',
  );
  await witness.connect();
  const snapshot = await witness.ipc.follow(creation.threadId, 15000, true);
  assert(witness.ipc.hasOwner(creation.threadId));
  assert.equal(snapshot.id, creation.threadId);
  assert.equal(snapshot.hostId, 'local');
  const turns = desktopTurns(snapshot);
  assert(
    JSON.stringify(turns).includes(content),
    'Original desktop owner must contain the tool-read result',
  );
  const markerTurns = turns.filter((turn) =>
    JSON.stringify(turn.params?.input ?? []).includes('IOS_TRANSFER_' + nonce),
  );
  assert.equal(markerTurns.length, 1, 'Exactly one user input in the original desktop');
  report.scenarios.push({
    name: 'real-model-tool-read-and-independent-original-owner',
    status: 'passed',
    threadId: creation.threadId,
    userInputs: markerTurns.length,
  });
  report.status = 'passed';
  await save();
  console.log(JSON.stringify({ status: report.status, report: join(directory, 'report.json') }));
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  await save();
  throw error;
} finally {
  witness?.close();
  if (metadata) await new Promise((done) => metadata.close(done));
  await service.close();
}
