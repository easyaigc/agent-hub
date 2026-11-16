import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// This driver sends two real model inputs. Never resume or replay an unknown run.
const executable = process.env.CODEX_EXECUTABLE;
const device = process.env.IOS_SIMULATOR_ID;
const output =
  process.env.STREAM_ACCEPTANCE_OUTPUT && resolve(process.env.STREAM_ACCEPTANCE_OUTPUT);
assert(
  executable && device && output,
  'Require CODEX_EXECUTABLE, exclusive IOS_SIMULATOR_ID and new STREAM_ACCEPTANCE_OUTPUT',
);
assert(
  process.env.STREAM_ACCEPTANCE_AUTHORIZE_RUN === '1',
  'Explicitly enable this real-input run',
);
await mkdir(output, { mode: 0o700 }); // fails if the run already exists
const state = join(output, 'host');
await mkdir(state, { mode: 0o700 });
const hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8);
const report = {
  status: 'running',
  hostId,
  nonce,
  device,
  bundleId: 'com.easyai.agenthub.v13acceptance',
  adapterSha256: preparedAdapterDigest(),
  startedAt: new Date().toISOString(),
};
const save = () =>
  writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
await save();
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  hostId,
  'V13 independent witness',
);
let service,
  fixtureServer,
  child,
  monitor,
  fixtureBound = false;
let watchedThread,
  followPending = false;
const wire = [];
const originalReceive = witness.ipc.receive.bind(witness.ipc);
witness.ipc.receive = (message) => {
  originalReceive(message);
  const p = message.params;
  if (
    message.type === 'broadcast' &&
    message.method === 'thread-stream-state-changed' &&
    p?.hostId === 'local' &&
    p.conversationId === watchedThread &&
    witness.ipc.owners.get(watchedThread) === message.sourceClientId &&
    ['snapshot', 'patches'].includes(p.change?.type)
  ) {
    wire.push({ version: message.version, type: p.change.type });
  }
};
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  const config = join(output, 'bridge.json');
  await writeFile(
    config,
    JSON.stringify({ hostId, desktopVersion: witness.version, validationOnly: true }),
    { mode: 0o600 },
  );
  await writeFile(
    join(state, 'runtime.json'),
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
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'V13 isolated acceptance',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: config,
  });
  for (const key of [
    'HOST_AGENT_TOKEN',
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  ])
    delete process.env[key];
  service = await startRuntimeServer();
  assert.equal(service.session.hostId, hostId);
  const token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  const endpoint = 'http://127.0.0.1:' + service.server.address().port;
  const fixture = {
    endpoint,
    token,
    hostId,
    threadId: '',
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  fixtureServer = createServer((req, res) => {
    if (
      req.method !== 'GET' ||
      req.url !== '/' ||
      req.headers.origin ||
      !['127.0.0.1:8795', 'localhost:8795'].includes(req.headers.host)
    )
      return res.writeHead(403).end();
    res
      .writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
      .end(JSON.stringify(fixture));
  });
  await new Promise((done, reject) => {
    fixtureServer.once('error', reject);
    fixtureServer.listen(8795, '127.0.0.1', done);
  });
  fixtureBound = true;
  report.hostPort = service.server.address().port;
  monitor = setInterval(async () => {
    if (followPending || (watchedThread && witness.ipc.hasOwner(watchedThread))) return;
    followPending = true;
    try {
      const records = Object.values(
        JSON.parse(await readFile(join(state, 'desktop-creations/creations.json'), 'utf8')),
      );
      const created = records.find(
        (item) => item.text.includes('V13_CREATE_' + nonce) && item.threadId,
      );
      if (created) {
        watchedThread = created.threadId;
        await witness.ipc.follow(watchedThread, 15000, true);
      }
    } catch (error) {
      if (error.code !== 'ENOENT') report.monitorError = String(error);
    } finally {
      followPending = false;
    }
  }, 100);
  report.testSha256 = createHash('sha256')
    .update(await readFile('apps/ios/Tests/CodexManageUITests.swift'))
    .digest('hex');
  await save();
  child = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      join(output, 'build'),
      '-resultBundlePath',
      join(output, 'native.xcresult'),
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      'NOTIFICATION_APP_BUNDLE_ID=' + report.bundleId,
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      '-only-testing:CodexManageUITests/CodexManageUITests/testDesktopStreamCompatibilityCreationAndReply',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const chunks = [];
  for (const stream of [child.stdout, child.stderr])
    stream.on('data', (chunk) => chunks.push(chunk));
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('exit', done);
  });
  child = undefined;
  await writeFile(
    join(output, 'xcode.log'),
    Buffer.concat(chunks).toString().replaceAll(token, '[REDACTED]'),
    { mode: 0o600 },
  );
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        join(output, 'native.xcresult'),
        '--format',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  );
  report.native = {
    exit: code,
    passed: summary.passedTests,
    failed: summary.failedTests,
    skipped: summary.skippedTests,
  };
  await save();
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests + summary.skippedTests, 0);
  const creations = Object.values(
    JSON.parse(await readFile(join(state, 'desktop-creations/creations.json'), 'utf8')),
  ).filter((v) => v.text.includes('V13_CREATE_' + nonce));
  assert.equal(creations.length, 1);
  const creation = creations[0];
  report.operationId = creation.clientOperationId;
  report.threadId = creation.threadId;
  await save();
  await witness.ipc.follow(creation.threadId, 15000, true);
  const owner = witness.ipc.state(creation.threadId),
    turns = desktopTurns(owner);
  assert(witness.ipc.hasOwner(creation.threadId));
  assert.equal(turns.length, 2);
  report.wire = {
    versions: [...new Set(wire.map((item) => item.version))],
    snapshots: wire.filter((item) => item.type === 'snapshot').length,
    patches: wire.filter((item) => item.type === 'patches').length,
  };
  await save();
  assert.deepEqual(report.wire.versions, [13]);
  assert(
    report.wire.snapshots > 0 && report.wire.patches > 0,
    'Require observed original-owner v13 snapshot and patches',
  );
  for (const [index, turn] of turns.entries()) {
    assert.equal(turn.status, 'completed');
    if (index === 1)
      assert.equal(
        turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort,
        'xhigh',
      );
    assert.equal(
      turn.params.model ?? turn.params.collaborationMode?.settings?.model,
      'gpt-6.1-sol',
    );
    const messages = turn.items.filter((item) => item.type === 'userMessage');
    assert.equal(messages.length, 1);
    const marker = (index ? 'V13_FOLLOW_' : 'V13_CREATE_') + nonce;
    assert(messages[0].content.some((part) => part.type === 'text' && part.text.includes(marker)));
  }
  assert.equal(
    turns[0].items.find((item) => item.type === 'userMessage').clientId,
    'first-' + creation.clientOperationId,
  );
  const creationReceipt = await service.session.queryDesktopCreation(creation.clientOperationId);
  assert.equal(creationReceipt.phase, 'confirmed');
  assert.equal(creationReceipt.threadId, creation.threadId);
  const opened = await service.session.open(creation.threadId);
  report.receipts = opened.runtime.receipts.map(({ clientMessageId, state: receiptState }) => ({
    clientMessageId,
    state: receiptState,
  }));
  const followId = turns[1].items.find((item) => item.type === 'userMessage').clientId;
  assert(followId && followId !== 'first-' + creation.clientOperationId);
  assert.equal(
    report.receipts.filter(
      (receipt) => receipt.clientMessageId === followId && receipt.state === 'completed',
    ).length,
    1,
  );
  report.creationReceipt = { phase: creationReceipt.phase, threadId: creationReceipt.threadId };
  report.turns = turns.map((turn) => ({
    id: turn.turnId,
    status: turn.status,
    model: turn.params.model ?? turn.params.collaborationMode?.settings?.model ?? null,
    effort: turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort ?? null,
    userMessageId: turn.items.find((item) => item.type === 'userMessage')?.clientId,
  }));
  report.status = 'passed';
  await save();
} catch (error) {
  report.status = 'failed';
  report.error = String(error.stack ?? error);
  await save();
  throw error;
} finally {
  clearInterval(monitor);
  if (child && child.exitCode === null) {
    const pending = child;
    await new Promise((done) => {
      pending.once('close', done);
      pending.kill('SIGTERM');
    });
  }
  if (service) {
    const closed = service.server.listening
      ? new Promise((done) => service.server.once('close', done))
      : Promise.resolve();
    await service.close();
    await closed;
  }
  witness.close();
  if (fixtureServer?.listening) {
    fixtureServer.closeAllConnections();
    await new Promise((done) => fixtureServer.close(done));
  }
  report.cleanup = {
    childExited: !child || child.exitCode !== null,
    hostListening: service?.server.listening ?? false,
    fixtureListening: fixtureServer?.listening ?? false,
  };
  const checkReleased = async (port) => {
    const probe = createServer();
    try {
      await new Promise((done, reject) => {
        probe.once('error', reject);
        probe.listen(port, '127.0.0.1', done);
      });
      await new Promise((done) => probe.close(done));
      return true;
    } catch {
      return false;
    }
  };
  report.cleanup.hostPortReleased = report.hostPort ? await checkReleased(report.hostPort) : null;
  report.cleanup.fixturePortReleased = fixtureBound ? await checkReleased(8795) : null;
  if (
    report.cleanup.hostListening ||
    report.cleanup.fixtureListening ||
    !report.cleanup.childExited ||
    report.cleanup.hostPortReleased === false ||
    report.cleanup.fixturePortReleased === false
  ) {
    report.status = 'failed';
    report.cleanup.error = 'Unable to verify all owned resources released';
  }
  await save();
}
