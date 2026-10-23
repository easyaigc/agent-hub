import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, cp } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import {
  openOriginalDesktopThread,
  preparedAdapterDigest,
} from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// Actual desktop pins, an isolated Host/native build and one dedicated model input.
// Never changes desktop pin preferences or sends input to the user's pinned chats.
const device = process.env.IOS_SIMULATOR_ID;
const executable = process.env.CODEX_EXECUTABLE;
assert(device && executable, 'Select the dedicated simulator and installed official CLI');
// A verifier failure after native success must never repeat the model task.
if (process.env.PIN_RECEIPT_VERIFY_REPORT) {
  const priorDirectory = resolve(process.env.PIN_RECEIPT_VERIFY_REPORT);
  const bytes = await readFile(join(priorDirectory, 'report.json'));
  const prior = JSON.parse(bytes);
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        join(priorDirectory, 'native.xcresult'),
        '--format',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  );
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  const verifier = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
  try {
    await verifier.connect();
    const state = await verifier.completeHistory(prior.threadId);
    const turns = desktopTurns(state);
    assert.equal(turns.length, 1);
    assert.equal(turns[0].status, 'completed');
    assert.equal(
      turns[0].params.effort ?? turns[0].params.collaborationMode?.settings?.reasoning_effort,
      'xhigh',
    );
    assert(
      turns[0].items.some(
        (item) => item.type === 'agentMessage' && item.text === 'PIN_RECEIPT_' + prior.nonce,
      ),
    );
    assert.equal(turns[0].items.filter((item) => item.type === 'userMessage').length, 1);
    const persisted = JSON.parse(await readFile(join(priorDirectory, 'host/runtime.json'), 'utf8'));
    const receipts = persisted.receipts.filter((receipt) => receipt.threadId === prior.threadId);
    assert.equal(receipts.length, 1);
    assert.equal(receipts[0].state, 'completed');
    const result = {
      status: 'passed',
      kind: 'read-only-verifier-recovery',
      originalReportSha256: createHash('sha256').update(bytes).digest('hex'),
      verifierAdapterSha256: preparedAdapterDigest(),
      nativePassed: 1,
      originalTurnId: turns[0].turnId,
      originalEffort: 'xhigh',
      uniqueInputs: 1,
      receiptState: 'completed',
      replayedInputs: 0,
      checkedAt: new Date().toISOString(),
    };
    await writeFile(
      join(priorDirectory, 'verification-recovery.json'),
      JSON.stringify(result, null, 2),
      { mode: 0o600 },
    );
    console.log(JSON.stringify(result));
  } finally {
    verifier.close();
  }
  process.exit(0);
}
const nonce = randomUUID().slice(0, 8);
const directory = resolve('.local/pinned-receipts/live-' + nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
await cp('apps/ios', join(directory, 'native'), { recursive: true });
const report = {
  kind: 'real-desktop-pins-native-model',
  nonce,
  device,
  adapterSha256: preparedAdapterDigest(),
  status: 'running',
};
report.nativeSha256 = createHash('sha256')
  .update(await readFile(join(directory, 'native/Sources/RootView.swift')))
  .update(await readFile(join(directory, 'native/Sources/Models.swift')))
  .digest('hex');
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let service, metadata, child;
try {
  report.threadId =
    process.env.PIN_RECEIPT_ACCEPTANCE_THREAD_ID ??
    (await new DesktopThreadPreparer(executable, join(homedir(), '.codex')).prepare(
      { cwd: process.cwd(), projectId: null, title: '置顶同步 DEV ' + nonce },
      {
        starting() {},
        identified() {},
        materialized() {},
        exited(code) {
          report.preparationExit = code;
        },
      },
    ));
  if (!process.env.PIN_RECEIPT_ACCEPTANCE_THREAD_ID) assert.equal(report.preparationExit, 0);
  await openOriginalDesktopThread(report.threadId);
  await ipc.connect();
  const deadline = Date.now() + 60000;
  while (Date.now() < deadline) {
    if (await ipc.follow(report.threadId, 1000, true)) break;
    await delay(500);
  }
  const before = ipc.state(report.threadId);
  assert(ipc.hasOwner(report.threadId));
  assert.equal(desktopTurns(before).length, 0);
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_STATE_DIR: join(directory, 'host'),
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: '置顶与回执 DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  for (const key of [
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
    'HOST_AGENT_TOKEN',
  ])
    delete process.env[key];
  service = await startRuntimeServer();
  await service.session.workspace();
  await service.session.open(report.threadId);
  const endpoint = 'http://127.0.0.1:' + service.server.address().port;
  const token = (await readFile(join(directory, 'host/access-token'), 'utf8')).trim();
  const workspace = await (
    await fetch(endpoint + '/v1/workspace', { headers: { Authorization: 'Bearer ' + token } })
  ).json();
  const listed = new Set(workspace.threads.map((t) => t.id));
  report.pinnedThreadIds = workspace.pinnedThreadIds.filter((id) => listed.has(id));
  assert(report.pinnedThreadIds.length > 0, 'Actual desktop must have at least one pin');
  const thread = workspace.threads.find((t) => t.id === report.threadId);
  assert(thread);
  let replyRequests = 0;
  service.server.prependListener('request', (request) => {
    if (request.method === 'POST' && request.url === '/v1/replies') replyRequests++;
  });
  metadata = createServer((_, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(
      JSON.stringify({
        endpoint,
        token,
        hostId: workspace.hostId,
        threadId: report.threadId,
        threadTitle: thread.title,
        nonce,
        secondEndpoint: endpoint,
        secondToken: token,
        secondHostId: workspace.hostId,
        delayedEndpoint: endpoint,
        pinnedThreadIds: report.pinnedThreadIds,
      }),
    );
  });
  await new Promise((done) => metadata.listen(8806, '127.0.0.1', done));
  await save();
  child = spawn(
    'xcodebuild',
    [
      '-project',
      join(directory, 'native/CodexManage.xcodeproj'),
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      join(directory, 'build'),
      '-resultBundlePath',
      join(directory, 'native.xcresult'),
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopPinnedSidebarDEV',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { stdio: 'inherit', env: process.env },
  );
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  report.nativeExitCode = code;
  await save();
  assert.equal(code, 0);
  const complete = await ipc.completeHistory(report.threadId);
  const turns = desktopTurns(complete);
  assert.equal(turns.length, 1, 'The single native send must not be replayed');
  assert.equal(turns[0].status, 'completed');
  assert.equal(
    turns[0].params.effort ?? turns[0].params.collaborationMode?.settings?.reasoning_effort,
    'xhigh',
  );
  const marker = 'PIN_RECEIPT_' + nonce;
  assert(turns[0].items.some((item) => item.type === 'agentMessage' && item.text === marker));
  assert.equal(replyRequests, 1);
  const receipt = service.session.history(report.threadId).runtime.receipts[0];
  assert.equal(receipt.state, 'completed');
  report.owner = {
    turnId: turns[0].turnId,
    status: turns[0].status,
    model: turns[0].params.model,
    effort: 'xhigh',
    replyRequests,
  };
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  await save();
  child?.kill('SIGTERM');
  ipc.close();
  if (metadata) {
    metadata.closeAllConnections();
    await new Promise((done) => metadata.close(done));
  }
  if (service) await service.close();
  console.log(JSON.stringify({ directory, status: report.status }));
}
