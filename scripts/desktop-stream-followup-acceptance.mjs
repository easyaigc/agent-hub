import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';

// Resume only this verified completed first operation; never repeat it.
const original = resolve(process.env.STREAM_ORIGINAL_RUN ?? '');
const output = resolve(process.env.STREAM_FOLLOWUP_OUTPUT ?? '');
const executable = process.env.CODEX_EXECUTABLE,
  device = process.env.IOS_SIMULATOR_ID;
assert(
  process.env.STREAM_FOLLOWUP_AUTHORIZE_RUN === '1' &&
    executable &&
    device &&
    process.env.STREAM_ORIGINAL_RUN &&
    process.env.STREAM_FOLLOWUP_OUTPUT,
);
const prior = JSON.parse(await readFile(join(original, 'report.json'), 'utf8'));
const reconciliation = JSON.parse(
  await readFile(join(original, 'readonly-reconciliation.json'), 'utf8'),
);
const state = join(original, 'host');
assert.equal(prior.status, 'failed');
assert(Number.isSafeInteger(prior.hostPort) && prior.hostPort > 0 && prior.hostPort <= 65535);
assert(/^[a-f0-9]{8}$/.test(prior.nonce));
assert.equal(reconciliation.hasOwner, true);
assert.equal(reconciliation.turns.length, 1);
assert.equal(reconciliation.turns[0].status, 'completed');
const threadId = reconciliation.threadId,
  firstId = reconciliation.creations[0].id;
assert.equal(reconciliation.creations.length, 1);
assert.equal(reconciliation.creations[0].phase, 'confirmed');
assert.equal(reconciliation.receipts.length, 1);
assert.equal(reconciliation.receipts[0].id, 'first-' + firstId);
assert.equal(reconciliation.receipts[0].state, 'completed');
await mkdir(output, { mode: 0o700 });
const report = {
  status: 'running',
  original,
  hostId: prior.hostId,
  threadId,
  firstId,
  nonce: prior.nonce,
  device,
  startedAt: new Date().toISOString(),
};
const save = () =>
  writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
await save();
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  prior.hostId,
  'V13 follow-up independent witness',
);
let service,
  fixtureServer,
  child,
  fixtureBound = false;
const wire = [];
const receive = witness.ipc.receive.bind(witness.ipc);
witness.ipc.receive = (message) => {
  receive(message);
  const p = message.params;
  if (
    message.type === 'broadcast' &&
    message.method === 'thread-stream-state-changed' &&
    p?.hostId === 'local' &&
    p.conversationId === threadId &&
    witness.ipc.owners.get(threadId) === message.sourceClientId &&
    ['snapshot', 'patches'].includes(p.change?.type)
  )
    wire.push({ type: p.change.type, version: message.version });
};
try {
  await witness.connect();
  const before = await witness.ipc.follow(threadId, 15000, true);
  const turnsBefore = desktopTurns(before ?? {});
  assert(witness.ipc.hasOwner(threadId));
  assert.equal(turnsBefore.length, 1);
  assert.equal(turnsBefore[0].status, 'completed');
  assert.equal(turnsBefore[0].items.filter((i) => i.type === 'userMessage').length, 1);
  assert(
    turnsBefore[0].items
      .find((i) => i.type === 'userMessage')
      .content.some(
        (part) => part.type === 'text' && part.text.includes('V13_CREATE_' + prior.nonce),
      ),
  );
  assert.equal(
    turnsBefore[0].items.find((i) => i.type === 'userMessage').clientId,
    'first-' + firstId,
  );
  const saved = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
  assert.equal(saved.hostId, prior.hostId);
  assert.equal(saved.receipts.length, 1);
  assert.equal(saved.receipts[0].clientMessageId, 'first-' + firstId);
  assert.equal(saved.receipts[0].state, 'completed');
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'V13 isolated acceptance',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: String(prior.hostPort),
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(original, 'bridge.json'),
  });
  for (const key of [
    'HOST_AGENT_TOKEN',
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  ])
    delete process.env[key];
  service = await startRuntimeServer();
  assert.equal(service.session.hostId, prior.hostId);
  assert.equal(service.server.address().port, prior.hostPort);
  const token = (await readFile(join(state, 'access-token'), 'utf8')).trim(),
    workspace = await service.session.workspace(),
    thread = workspace.threads.find((t) => t.id === threadId);
  assert(thread?.title);
  const fixture = {
    endpoint: 'http://127.0.0.1:' + prior.hostPort,
    token,
    hostId: prior.hostId,
    threadId,
    threadTitle: thread.title,
    nonce: prior.nonce,
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
  const inputs = [
    'packages/codex-adapter/src/desktop-ipc.ts',
    'packages/codex-adapter/src/desktop-platform.ts',
    'packages/codex-adapter/src/runtime-session.ts',
    'apps/ios/Sources/SessionStore.swift',
    'scripts/desktop-stream-followup-acceptance.mjs',
    process.env.XCODE_XCCONFIG_FILE,
  ];
  assert(inputs.every(Boolean));
  report.inputSha256 = Object.fromEntries(
    await Promise.all(
      inputs.map(async (file) => [
        file,
        createHash('sha256')
          .update(await readFile(file))
          .digest('hex'),
      ]),
    ),
  );
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
      join(original, 'build'),
      '-resultBundlePath',
      join(output, 'native.xcresult'),
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      'NOTIFICATION_APP_BUNDLE_ID=com.easyai.agenthub.v13acceptance',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      '-only-testing:CodexManageUITests/CodexManageUITests/testDesktopStreamCompatibilityExistingFollowup',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const chunks = [];
  for (const stream of [child.stdout, child.stderr]) stream.on('data', (c) => chunks.push(c));
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
  const owner = await witness.ipc.follow(threadId, 15000, true),
    turns = desktopTurns(owner ?? {});
  assert(witness.ipc.hasOwner(threadId));
  assert.equal(turns.length, 2);
  for (const t of turns) {
    assert.equal(t.status, 'completed');
    assert.equal(t.items.filter((i) => i.type === 'userMessage').length, 1);
  }
  assert.equal(turns[0].items.find((i) => i.type === 'userMessage').clientId, 'first-' + firstId);
  const follow = turns[1],
    user = follow.items.find((i) => i.type === 'userMessage');
  assert(user.clientId && user.clientId !== 'first-' + firstId);
  assert(
    user.content.some((p) => p.type === 'text' && p.text.includes('V13_FOLLOW_' + prior.nonce)),
  );
  assert.equal(
    follow.params.model ?? follow.params.collaborationMode?.settings?.model,
    'gpt-6.1-sol',
  );
  assert.equal(
    follow.params.effort ?? follow.params.collaborationMode?.settings?.reasoning_effort,
    'xhigh',
  );
  const opened = await service.session.open(threadId),
    receipts = opened.runtime.receipts;
  assert.equal(
    receipts.filter((r) => r.clientMessageId === user.clientId && r.state === 'completed').length,
    1,
  );
  report.wire = {
    versions: [...new Set(wire.map((x) => x.version))],
    snapshots: wire.filter((x) => x.type === 'snapshot').length,
    patches: wire.filter((x) => x.type === 'patches').length,
  };
  assert.deepEqual(report.wire.versions, [13]);
  assert(report.wire.snapshots > 0 && report.wire.patches > 0);
  report.turns = turns.map((t) => ({
    id: t.turnId,
    status: t.status,
    userMessageId: t.items.find((i) => i.type === 'userMessage')?.clientId,
    model: t.params.model ?? t.params.collaborationMode?.settings?.model,
    effort: t.params.effort ?? t.params.collaborationMode?.settings?.reasoning_effort ?? null,
  }));
  report.receipts = receipts.map((r) => ({ id: r.clientMessageId, state: r.state }));
  report.status = 'passed';
  await save();
} catch (error) {
  report.status = 'failed';
  report.error = String(error.stack ?? error);
  await save();
  throw error;
} finally {
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
  const free = async (port) => {
    const p = createServer();
    try {
      await new Promise((done, reject) => {
        p.once('error', reject);
        p.listen(port, '127.0.0.1', done);
      });
      await new Promise((done) => p.close(done));
      return true;
    } catch {
      return false;
    }
  };
  report.cleanup = {
    childExited: !child || child.exitCode !== null,
    hostPortReleased: service ? await free(prior.hostPort) : null,
    fixturePortReleased: fixtureBound ? await free(8795) : null,
  };
  if (
    report.cleanup.hostPortReleased === false ||
    report.cleanup.fixturePortReleased === false ||
    !report.cleanup.childExited
  )
    report.status = 'failed';
  await save();
}
