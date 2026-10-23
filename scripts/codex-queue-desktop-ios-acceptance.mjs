import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { DesktopNativeQueue } from '../packages/codex-adapter/dist/desktop-native-queue.js';
import { DesktopThreadPreparer } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import { openOriginalDesktopThread } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { controllerAdapterDigest } from '../packages/codex-adapter/dist/desktop-controller.js';

const device = process.env.IOS_SIMULATOR_ID;
assert(device, 'Use a dedicated native simulator');
const directory = resolve('.local/native-queue-dev', randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
const resume = process.env.NATIVE_QUEUE_RESUME_REPORT
  ? JSON.parse(await readFile(resolve(process.env.NATIVE_QUEUE_RESUME_REPORT), 'utf8'))
  : undefined;
const nonce = resume?.nonce ?? randomUUID().slice(0, 8),
  home = join(homedir(), '.codex');
const executable =
  process.env.CODEX_EXECUTABLE ??
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const report = {
  kind: 'real-original-desktop-native-queue-eventual-sync',
  directory,
  nonce,
  device,
  scenarios: {},
  status: 'running',
  adapterSha256: controllerAdapterDigest(),
  desktopVisualObservation:
    'Computer Use denies the Codex app; original-owner and native queue API evidence are recorded separately.',
};
const nativeProject = resolve(process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj');
const nativeRoot = dirname(nativeProject);
const sourceDigest = async () => {
  const hash = createHash('sha256');
  for (const name of (await readdir(join(nativeRoot, 'Sources')))
    .filter((name) => name.endsWith('.swift'))
    .sort())
    hash.update(name).update(await readFile(join(nativeRoot, 'Sources', name)));
  return hash
    .update(await readFile(join(nativeRoot, 'Tests/CodexManageUITests.swift')))
    .digest('hex');
};
report.nativeSourceSha256 = await sourceDigest();
report.nativeProject = nativeProject;
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const wait = async (probe, label, timeout = 300000) => {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await probe();
    if (value) return value;
    await new Promise((done) => setTimeout(done, 500));
  }
  throw new Error('Timed out: ' + label);
};
const ipc = new DesktopIpc(join(home, 'ipc/ipc.sock'));
const queue = new DesktopNativeQueue(executable, home);
let host, server, child;
let token = '';
try {
  const preparer = new DesktopThreadPreparer(executable, home);
  const threadId =
    resume?.threadId ??
    (await preparer.prepare(
      { cwd: directory, projectId: null, title: 'Host 队列 DEV ' + nonce },
      {
        starting() {},
        identified(id) {
          report.threadId = id;
        },
        materialized() {},
        exited(code) {
          assert.equal(code, 0);
        },
      },
    ));
  report.threadId = threadId;
  await openOriginalDesktopThread(threadId);
  await ipc.connect();
  await wait(
    async () => {
      await ipc.follow(threadId, 1000, true);
      return ipc.hasOwner(threadId);
    },
    'original desktop owner',
    30000,
  );
  if (!resume) assert.equal(desktopTurns(ipc.state(threadId)).length, 0);
  else {
    report.resumedFrom = process.env.NATIVE_QUEUE_RESUME_REPORT;
    await writeFile(join(directory, 'original-attempt.json'), JSON.stringify(resume, null, 2), {
      mode: 0o600,
    });
  }
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE: executable,
    HOST_AGENT_STATE_DIR: join(resume?.directory ?? directory, 'host'),
    HOST_AGENT_NAME: 'Native Queue DEV',
    HOST_AGENT_ROOT: directory,
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG;
  host = await startRuntimeServer();
  token = (
    await readFile(join(resume?.directory ?? directory, 'host/access-token'), 'utf8')
  ).trim();
  const port = host.server.address().port;
  process.env.HOST_AGENT_PORT = process.env.PORT = String(port);
  await host.session.open(threadId);
  assert(host.session.capabilities.includes('queue:native'));
  report.synchronizationPolicy =
    'Eventual sync; no guarantee for simultaneous desktop/mobile actions. Host attempts remain durable and deduplicated.';
  report.hostId = host.session.hostId;
  // The desktop sidebar omits zero-Turn prepared metadata. Establish this new
  // QA conversation through one real original-owner input before opening it on iOS.
  const setupReceipt = resume
    ? host.session.receipt('setup-' + nonce)
    : await host.session.submit({
        hostId: host.session.hostId,
        threadId,
        clientMessageId: 'setup-' + nonce,
        mode: 'next-turn',
        text: 'Reply exactly QUEUE_QA_READY_' + nonce + '. Do not use tools.',
        attachmentIds: [],
        effort: 'xhigh',
      });
  assert.notEqual(setupReceipt.state, 'uncertain', 'Unknown QA setup is never replayed');
  await wait(async () => {
    await host.session.refreshHistory(threadId);
    return host.session.receipt('setup-' + nonce).state === 'completed';
  }, 'original-owner QA setup');
  const setupTurnId = host.session.receipt('setup-' + nonce).turnId;
  report.setup = {
    clientMessageId: 'setup-' + nonce,
    turnId: setupTurnId,
    purpose: 'Make the new zero-Turn metadata visible in the original desktop sidebar',
    state: 'completed',
    excludedFromQueueSceneTurnCounts: true,
  };
  const workspace = await host.session.workspace();
  const selected = workspace.threads.find((thread) => thread.id === threadId);
  assert(selected, 'The prepared QA thread must appear in the real workspace');
  const fixture = {
    endpoint: 'http://127.0.0.1:' + port,
    token,
    hostId: host.session.hostId,
    threadId,
    threadTitle: selected.title,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  server = createServer(async (request, response) => {
    if (
      request.headers.origin ||
      !['127.0.0.1:8796', 'localhost:8796'].includes(request.headers.host)
    ) {
      response.writeHead(403).end();
      return;
    }
    try {
      let result;
      if (request.method === 'GET' && request.url === '/') result = fixture;
      else if (request.method === 'GET' && request.url === '/host-queue') {
        const items = await queue.list(threadId);
        result = {
          items: items.map((item) => ({ ...item, clientMessageId: item.clientUserMessageId })),
        };
        report.queueObservations ??= [];
        report.queueObservations.push({ at: new Date().toISOString(), items });
        await save();
      } else if (request.method === 'POST' && request.url === '/desktop-cancel') {
        const items = await queue.list(threadId);
        const item = items.find((item) =>
          item.input.some((part) => String(part.text ?? '').includes('_CANCEL')),
        );
        assert(item, 'Only cancel this scene queue item');
        assert(await queue.remove(threadId, item.id, () => {}));
        report.scenarios.desktopCancel = {
          outcome: 'passed',
          clientMessageId: item.clientUserMessageId,
        };
        await save();
        result = { ok: true };
      } else if (request.method === 'POST' && request.url === '/restart') {
        const before = (await queue.list(threadId)).map((item) => item.clientUserMessageId);
        const closed = new Promise((done) => host.server.once('close', done));
        host.close();
        await closed;
        host = await startRuntimeServer();
        assert.equal(host.session.hostId, fixture.hostId);
        assert.equal(
          (
            await readFile(join(resume?.directory ?? directory, 'host/access-token'), 'utf8')
          ).trim(),
          token,
        );
        await host.session.open(threadId);
        assert.deepEqual(
          (await queue.list(threadId)).map((item) => item.clientUserMessageId),
          before,
        );
        report.scenarios.hostRestart = {
          outcome: 'passed',
          queuedIds: before,
        };
        await save();
        result = { ok: true };
      } else {
        response.writeHead(404).end();
        return;
      }
      response
        .writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
        .end(JSON.stringify(result));
    } catch (error) {
      response.writeHead(500).end(JSON.stringify({ error: error.message }));
    }
  });
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(8796, '127.0.0.1', done);
  });
  report.resultBundle = join(directory, 'native.xcresult');
  await save();
  child = spawn(
    'xcodebuild',
    [
      '-project',
      nativeProject,
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      process.env.NATIVE_QUEUE_DERIVED_DATA ?? join(directory, 'build'),
      '-resultBundlePath',
      report.resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testCodexNativeQueueSyncAndExecuteNow',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    {
      env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  const log = createWriteStream(join(directory, 'native.log'), { mode: 0o600 });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      log.write(line.replaceAll(token, '[REDACTED]') + '\n');
      if (/^Test Case|: error:|^\*\* TEST|NATIVE_QUEUE_DEV/.test(line))
        console.log(line.replaceAll(token, '[REDACTED]'));
    });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('exit', done);
  });
  await new Promise((done) => log.end(done));
  assert.equal(code, 0, 'Native UI scenario failed; evidence is preserved');
  const owner = await wait(async () => {
    const state = await ipc.follow(threadId, 5000, true);
    return state && !desktopTurns(state).some((turn) => turn.status === 'inProgress')
      ? state
      : undefined;
  }, 'original owner final completion');
  const history = await host.session.open(threadId),
    turns = desktopTurns(owner).filter((turn) => (turn.turnId ?? turn.id) !== setupTurnId);
  const receipts = history.runtime.receipts.filter((receipt) =>
    receipt.text?.includes('NATIVE_QUEUE_' + nonce),
  );
  assert.equal(receipts.length, 4);
  const origin = receipts.find((receipt) => receipt.mode === 'next-turn');
  const fifo = receipts.find((receipt) => receipt.text.includes('_FIFO'));
  const promoted = receipts.find((receipt) => receipt.text.includes('_PROMOTE'));
  const cancelled = receipts.find((receipt) => receipt.text.includes('_CANCEL'));
  const matches = (receipt, source = turns) =>
    source.flatMap((turn) =>
      (turn.items ?? []).filter(
        (item) =>
          (item.type === 'userMessage' && item.clientId === receipt.clientMessageId) ||
          (item.type === 'steeringUserMessage' &&
            item.status === 'accepted' &&
            item.clientUserMessageId === receipt.clientMessageId),
      ),
    );
  for (const receipt of [origin, fifo, promoted]) {
    assert.equal(matches(receipt).length, 1);
    assert.equal(receipt.state, 'completed');
  }
  assert.equal(cancelled.state, 'cancelled');
  assert.equal(matches(cancelled).length, 0);
  assert.equal(promoted.turnId, origin.turnId);
  assert.notEqual(fifo.turnId, origin.turnId);
  assert.equal(turns.filter((turn) => turn.status === 'completed').length, 2);
  assert.equal(
    await host.session.executeQueue(threadId, promoted.clientMessageId).then((value) => value.ok),
    true,
  );
  const afterRepeat = desktopTurns(await ipc.follow(threadId, 5000, true)).filter(
    (turn) => (turn.turnId ?? turn.id) !== setupTurnId,
  );
  for (const receipt of [origin, fifo, promoted])
    assert.equal(matches(receipt, afterRepeat).length, 1);
  assert.equal(matches(cancelled, afterRepeat).length, 0);
  assert.equal(afterRepeat.length, turns.length);
  assert.equal(history.runtime.receipts.filter((item) => item.state === 'queued').length, 0);
  assert.equal(controllerAdapterDigest(), report.adapterSha256);
  assert.equal(
    await sourceDigest(),
    report.nativeSourceSha256,
    'Frozen native sources changed during acceptance',
  );
  const originalModel =
    turns[0]?.params?.collaborationMode?.settings?.model ?? turns[0]?.params?.model;
  const actualEffort =
    turns[0]?.requestedReasoningEffort ??
    turns[0]?.params?.collaborationMode?.settings?.reasoning_effort ??
    turns[0]?.params?.effort;
  assert.equal(actualEffort, 'xhigh');
  await writeFile(
    join(directory, 'original-owner-proof.json'),
    JSON.stringify(
      turns.map((turn) => ({
        turnId: turn.turnId ?? turn.id,
        status: turn.status,
        requestedReasoningEffort: turn.requestedReasoningEffort,
        settings: turn.params?.collaborationMode?.settings,
        items: (turn.items ?? []).map((item) => ({
          id: item.id,
          type: item.type,
          status: item.status,
          clientId: item.clientId,
          clientUserMessageId: item.clientUserMessageId,
        })),
      })),
      null,
      2,
    ),
    { mode: 0o600 },
  );
  assert.deepEqual(await queue.list(threadId), []);
  report.scenarios.nativeQueueSync = {
    outcome: 'passed',
    receipts,
    originalOwner: true,
    uniqueInputs: true,
    originalModel,
    actualEffort,
  };
  report.status = 'passed';
  await save();
  console.log(
    JSON.stringify({
      status: report.status,
      directory,
      threadId,
      nativeQueue: true,
      hostQueueExecute: true,
      promotionSameTurn: true,
      cancelledNotExecuted: true,
    }),
  );
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  await save();
  throw error;
} finally {
  if (child?.exitCode === null) child.kill('SIGTERM');
  server?.closeAllConnections();
  server?.close();
  host?.close();
  queue.close();
  ipc.close();
  await save();
}
