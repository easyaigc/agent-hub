import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { controllerAdapterDigest } from '../packages/codex-adapter/dist/desktop-controller.js';

// The metadata server only connects the native app to a fresh Host cache.
// All inputs execute in the existing original Codex owner, independently checked below.
const threadId = process.env.DESKTOP_ACCEPTANCE_THREAD_ID;
const device = process.env.IOS_SIMULATOR_ID;
assert(threadId && device, 'Select a dedicated idle Codex acceptance chat and simulator');
const nonce = crypto.randomUUID().slice(0, 8);
const directory = resolve('.local/desktop-composer-acceptance/' + nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE:
    '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_NAME: 'Codex Composer Acceptance',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
});
delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
await ipc.connect();
const state = await ipc.follow(threadId);
assert(ipc.hasOwner(threadId), 'The dedicated chat must have the original desktop owner');
assert(!desktopTurns(state).some((turn) => turn.status === 'inProgress'));
const service = await startRuntimeServer();
const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
let child, server;
const originalProcesses = () => {
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
        main.some((parent) => parent[1] === row[2]) &&
        row[3].includes('/codex-cli/') &&
        row[3].includes('app-server'),
    ),
  ].map((row) => [row[1], row[2]]);
};
const evidence = {
  nonce,
  threadId,
  appMode: 'ordinary',
  status: 'running',
  adapterSha256: controllerAdapterDigest(),
  beforeProcesses: originalProcesses(),
};
const save = () =>
  writeFile(join(directory, 'run.json'), JSON.stringify(evidence, null, 2), { mode: 0o600 });
try {
  let projectCursor, threadCursor;
  do {
    const workspace = await service.session.workspace(projectCursor, threadCursor);
    projectCursor = workspace.nextProjectCursor ?? undefined;
    threadCursor = workspace.nextThreadCursor ?? undefined;
    assert(workspace.capabilities.includes('queue') && workspace.capabilities.includes('steer'));
  } while (projectCursor || threadCursor);
  const initial = await service.session.open(threadId);
  assert.equal(initial.runtime.canControl, true);
  const fixture = {
    endpoint: `http://127.0.0.1:${service.server.address().port}`,
    token,
    hostId: service.session.hostId,
    threadId,
    threadTitle: initial.thread.title,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  server = createServer((request, response) => {
    if (
      request.method !== 'GET' ||
      request.url !== '/' ||
      request.headers.origin ||
      !['127.0.0.1:8795', 'localhost:8795'].includes(request.headers.host)
    ) {
      response.writeHead(403);
      response.end();
      return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(fixture));
  });
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(8795, '127.0.0.1', done);
  });
  const resultBundle = join(directory, 'result.xcresult');
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
      '.build/ios-composer',
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testCodexComposerQueueAndSteer',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
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
  Object.assign(evidence, {
    passedTests: summary.passedTests,
    failedTests: summary.failedTests,
    skippedTests: summary.skippedTests,
  });
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  const owner = await ipc.follow(threadId);
  assert(ipc.hasOwner(threadId));
  const marker = 'COMPOSER_REAL_' + nonce;
  const turns = desktopTurns(owner);
  const history = await service.session.open(threadId);
  const receipts = history.runtime.receipts.filter((value) => value.text?.includes(marker));
  const origin = receipts.find((value) => value.mode === 'next-turn');
  const steer = receipts.find((value) => value.mode === 'steer');
  const queued = receipts.filter((value) => value.mode === 'queue');
  assert.equal(receipts.length, 5);
  assert.equal(queued.length, 3);
  assert.equal(queued[1].state, 'cancelled');
  assert.equal(origin.state, 'completed');
  assert.equal(steer.state, 'completed');
  assert.equal(origin.turnId, steer.turnId);
  assert.equal(queued[0].state, 'completed');
  assert.equal(queued[2].state, 'completed');
  const matchingTurn = (receipt) =>
    turns.filter((turn) =>
      (turn.items ?? []).some(
        (item) =>
          (item.type === 'userMessage' && item.clientId === receipt.clientMessageId) ||
          (item.type === 'steeringUserMessage' &&
            item.status === 'accepted' &&
            item.serverUserMessageId &&
            item.clientUserMessageId === receipt.clientMessageId),
      ),
    );
  for (const receipt of [origin, steer, queued[0], queued[2]])
    assert.equal(matchingTurn(receipt).length, 1);
  assert.equal(matchingTurn(queued[1]).length, 0);
  const indexes = [origin, queued[0], queued[2]].map((receipt) =>
    turns.indexOf(matchingTurn(receipt)[0]),
  );
  assert(
    indexes[0] < indexes[1] && indexes[1] < indexes[2],
    'Original owner must execute the FIFO turns in order',
  );
  const finalText = (receipt) =>
    (matchingTurn(receipt)[0].items ?? []).filter((item) => item.type === 'agentMessage').at(-1)
      ?.text;
  assert.equal(finalText(origin), marker + '_STEER');
  assert.equal(finalText(queued[0]), marker + '_QUEUE_1');
  assert.equal(finalText(queued[2]), marker + '_QUEUE_3');
  evidence.afterProcesses = originalProcesses();
  assert.deepEqual(evidence.afterProcesses, evidence.beforeProcesses);
  assert.equal(controllerAdapterDigest(), evidence.adapterSha256);
  Object.assign(evidence, {
    originalOwner: true,
    queueOrderVerified: true,
    cancelledNotSubmitted: true,
    steerSameTurn: true,
    completedEmptyDisabledSend: true,
    status: 'passed',
    verifiedAt: new Date().toISOString(),
  });
  await save();
  console.log(
    JSON.stringify({
      status: evidence.status,
      passedTests: evidence.passedTests,
      queueOrderVerified: true,
      steerSameTurn: true,
      resultBundle,
    }),
  );
} catch (error) {
  evidence.status = 'failed';
  evidence.error = error.message;
  await save();
  throw error;
} finally {
  child?.kill('SIGTERM');
  server?.closeAllConnections();
  server?.close();
  service.close();
  ipc.close();
}
