import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// Fresh Host state and native simulator; inputs execute only in the original desktop.
// The metadata endpoint provides credentials, never simulated model responses.
const device = process.env.IOS_SIMULATOR_ID;
assert(device, 'Select a dedicated iOS simulator');
const nonce = randomUUID().slice(0, 8);
const directory = resolve('.local/permissions-desktop', nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
const hostId = randomUUID();
const probeDirectory = join(homedir(), '.codex', 'agenthub-permission-dev', nonce);
await mkdir(probeDirectory, { recursive: true, mode: 0o700 });
const permissionProbePath = join(probeDirectory, 'probe.txt');
const desktopVersion = execFileSync(
  '/usr/libexec/PlistBuddy',
  ['-c', 'Print :CFBundleShortVersionString', '/Applications/ChatGPT.app/Contents/Info.plist'],
  { encoding: 'utf8' },
).trim();
const config = join(directory, 'bridge.json');
await writeFile(config, JSON.stringify({ hostId, desktopVersion, validationOnly: true }), {
  mode: 0o600,
});
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
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE:
    '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
  CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: config,
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_NAME: 'Permissions DEV',
  HOST_AGENT_ROOT: process.cwd(),
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
});
delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
const service = await startRuntimeServer();
const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
const nativeFiles = [
  'Models.swift',
  'SessionStore.swift',
  'Sheets.swift',
  'RootView.swift',
  'HostService.swift',
];
const digest = createHash('sha256');
for (const name of nativeFiles) digest.update(await readFile(join('apps/ios/Sources', name)));
const processes = () => {
  const rows = execFileSync('ps', ['-axo', 'pid,ppid,command'], { encoding: 'utf8' })
    .split('\n')
    .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/))
    .filter(Boolean);
  const main = rows.filter((row) =>
    /^\/Applications\/ChatGPT.app\/Contents\/MacOS\/ChatGPT(?: |$)/.test(row[3]),
  );
  return [
    ...main,
    ...rows.filter(
      (row) =>
        main.some((p) => p[1] === row[2]) &&
        row[3].includes('/codex-cli/') &&
        row[3].includes('app-server'),
    ),
  ].map((row) => [row[1], row[2]]);
};
const evidence = {
  kind: 'real-desktop-permissions',
  nonce,
  hostId,
  desktopVersion,
  adapterSha256: preparedAdapterDigest(),
  nativeSha256: digest.digest('hex'),
  beforeProcesses: processes(),
  testSourceSha256: createHash('sha256')
    .update(await readFile('apps/ios/Tests/CodexManageUITests.swift'))
    .digest('hex'),
  status: 'running',
  scenarios: [],
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(evidence, null, 2), { mode: 0o600 });
let metadata, child;
try {
  await ipc.connect();
  const workspace = await service.session.workspace();
  assert(workspace.capabilities.includes('desktop:creations:testing'));
  const defaults = await service.session.runtime.defaultPermissions(process.cwd());
  assert.equal(
    defaults.sandboxPolicy.type,
    'dangerFullAccess',
    'This scenario requires the computer default to be full access',
  );
  assert.equal(defaults.approvalPolicy, 'never');
  evidence.computerDefault = 'full-access';
  metadata = createServer((req, res) => {
    if (
      req.method !== 'GET' ||
      req.url !== '/' ||
      req.headers.origin ||
      !['localhost:8806', '127.0.0.1:8806'].includes(req.headers.host)
    ) {
      res.writeHead(403);
      res.end();
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(
      JSON.stringify({
        endpoint: `http://127.0.0.1:${service.server.address().port}`,
        token,
        hostId,
        threadId: '',
        threadTitle: '',
        nonce,
        permissionProbePath,
        creationProjectId: null,
        creationProjectName: null,
        secondEndpoint: '',
        secondToken: '',
        secondHostId: '',
        delayedEndpoint: '',
      }),
    );
  });
  await new Promise((done, reject) => {
    metadata.once('error', reject);
    metadata.listen(8806, '127.0.0.1', done);
  });
  const resultBundle = join(directory, 'native.xcresult');
  evidence.resultBundle = resultBundle;
  await save();
  child = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS Simulator,id=${device}`,
      '-derivedDataPath',
      '.build/ios-permissions-desktop',
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopPermissionDefaults',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      process.env.PERMISSIONS_TEST_WITHOUT_BUILDING === '1' ? 'test-without-building' : 'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = line
        .replaceAll(token, '[REDACTED]')
        .replaceAll(token.slice(0, 16), '[REDACTED]');
      log.write(safe + '\n');
      if (/^Test Case|: error:|^\s*Executed |^\*\* TEST (SUCCEEDED|FAILED)/.test(safe))
        console.log(safe);
    });
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  await new Promise((done) => log.end(done));
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        resultBundle,
        '--format',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  );
  evidence.native = {
    passed: summary.passedTests,
    failed: summary.failedTests,
    skipped: summary.skippedTests,
  };
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  const state = JSON.parse(await readFile(join(directory, 'runtime.json'), 'utf8'));
  const markers = ['PERMISSION_0_', 'PERMISSION_1_', 'PERMISSION_2_', 'PERMISSION_3_'];
  for (const [index, prefix] of markers.entries()) {
    const marker = prefix + nonce;
    const receipts = state.receipts.filter((r) => r.reply.text.includes(marker));
    assert.equal(receipts.length, 1, 'Exactly one submitted intent');
    const receipt = receipts[0];
    const snapshot = await ipc.follow(receipt.threadId, 5000, true);
    assert(ipc.hasOwner(receipt.threadId));
    assert.equal(snapshot.id, receipt.threadId);
    assert.equal(snapshot.hostId, 'local');
    const turns = desktopTurns(snapshot);
    const matching = turns.filter((t) =>
      (t.items ?? []).some(
        (item) => item.type === 'userMessage' && item.clientId === receipt.clientMessageId,
      ),
    );
    assert.equal(matching.length, 1);
    const turn = matching[0];
    assert.equal(turn.status, 'completed');
    assert(turn.items.some((item) => item.type === 'agentMessage' && item.text.trim() === marker));
    const expected = index === 1 ? 'readOnly' : index === 3 ? 'workspaceWrite' : 'dangerFullAccess';
    assert.equal(turn.params.sandboxPolicy.type, expected);
    assert.equal(turn.params.approvalPolicy, index === 1 || index === 3 ? 'on-request' : 'never');
    if (index === 3)
      assert(['guardian_subagent', 'auto_review'].includes(turn.params.approvalsReviewer));
    else assert.equal(turn.params.approvalsReviewer, 'user');
    if (index === 0 || index === 2 || index === 3) {
      const commands = turn.items.filter((item) => item.type === 'commandExecution');
      assert(
        commands.some(
          (item) =>
            item.status === 'completed' &&
            item.exitCode === 0 &&
            item.aggregatedOutput?.includes(marker),
        ),
        'A real successful tool command is required',
      );
    }
    const history = await service.session.open(receipt.threadId);
    assert.equal(history.runtime.generationSettings.permissionMode, 'guardian-approvals');
    assert.equal(history.runtime.approvals.length, 0);
    evidence.scenarios.push({
      marker,
      threadId: receipt.threadId,
      turnId: turn.turnId,
      outcome: 'passed',
      permission: expected,
      concreteTool: index !== 1,
      originalOwner: true,
      exactInputCount: 1,
    });
  }
  assert.equal(await readFile(permissionProbePath, 'utf8'), 'PERMISSION_2_' + nonce);
  evidence.afterProcesses = processes();
  assert.deepEqual(evidence.afterProcesses, evidence.beforeProcesses);
  evidence.status = 'passed';
} catch (error) {
  evidence.status = 'failed';
  evidence.error = error.message;
  process.exitCode = 1;
} finally {
  child?.kill('SIGTERM');
  metadata?.closeAllConnections();
  if (metadata) await new Promise((done) => metadata.close(done));
  ipc.close();
  service.session.close();
  service.server.closeAllConnections();
  await new Promise((done) => service.server.close(done));
  await save();
  console.log(
    JSON.stringify({
      status: evidence.status,
      scenarios: evidence.scenarios.length,
      report: join(directory, 'report.json'),
      error: evidence.error,
    }),
  );
}
