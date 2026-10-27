import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';
import { DesktopNativeQueue } from '../../packages/codex-adapter/dist/desktop-native-queue.js';
import { homedir } from 'node:os';

const resumePath = process.env.ANDROID_QUEUE_RESUME_REPORT;
const immediateAppend = process.env.ANDROID_QUEUE_SCENARIO === 'immediate-append';
assert(!immediateAppend || !resumePath, 'Immediate append always uses a fresh isolated case');
const resume = resumePath ? JSON.parse(await readFile(resumePath, 'utf8')) : null;
const firstAudit = resume
  ? JSON.parse(await readFile(resolve(resumePath, '../first-input-readonly-audit.json'), 'utf8'))
  : null;
const preflightOnly = process.env.ANDROID_QUEUE_PREFLIGHT_ONLY === '1';
assert(preflightOnly || process.env.ANDROID_QUEUE_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const projectId = process.env.ANDROID_QUEUE_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_QUEUE_PROJECT_ROOT ?? '');
const output = process.env.MOBILE_QUEUE_ACCEPTANCE_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
const root = join(
  output,
  (immediateAppend ? 'queue-append-live-' : 'queue-model-live-') + Date.now(),
);
await mkdir(root, { recursive: true, mode: 0o700 });
const state = resume ? resolve(resumePath, '../host') : join(root, 'host');
if (!resume) await mkdir(state, { mode: 0o700 });
if (resume) {
  assert.equal(resume.projectId, projectId);
  assert.equal(resume.projectRoot, projectRoot);
  assert.equal(resume.posts.length, 2);
  assert.equal(resume.claims.length, 1);
  assert.equal(firstAudit.canonicalInputs, 1);
  assert.equal(firstAudit.operationId, resume.posts[0].operationId);
}
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(local.androidSdk, 'platform-tools/adb'),
  pkg = 'com.easyai.agenthub.android.acceptance';
const execute = promisify(execFile);
const android = async (...args) =>
  (
    await execute(adb, ['-s', serial, ...args], {
      encoding: 'utf8',
      timeout: 30000,
      maxBuffer: 16 * 1024 * 1024,
    })
  ).stdout.trim();
assert.equal(await android('get-state'), 'device');
assert((await android('shell', 'dumpsys', 'power')).includes('mWakefulness=Awake'));
const hostId = resume?.hostId ?? randomUUID(),
  nonce = resume?.nonce ?? randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
const sha = (value) => createHash('sha256').update(value).digest('hex');
const marker = 'ANDROID_P4_QUEUE_' + nonce + '_';
const cases = immediateAppend
  ? [
      {
        index: 0,
        mode: 'next-turn',
        text: `专用安卓队列立即追加验收。仅调用exec_command执行sleep 120，yield_time_ms=1000，不读写文件，不调用其他命令、网络，不提权、不联系其他聊天或Agent。允许write_stdin等待这一命令自然结束，保持任务进行中；结束后最终只回复${marker}ORIGIN。期间若收到追加指令，保持等待原命令自然结束，然后按追加要求回复。`,
      },
      {
        index: 1,
        mode: 'queue',
        text: `追加要求：继续等待原sleep命令自然结束，本轮最终仅回复${marker}APPENDED。不停止、不重试、不调用其他命令，不修改文件或其他聊天。`,
      },
      {
        index: 2,
        mode: 'queue',
        text: `仅原样最终回复${marker}FINAL。不调用工具、不改文件、不创建其他聊天或联系Agent。`,
      },
    ]
  : [
      {
        index: 0,
        mode: 'next-turn',
        text: `专用安卓原生队列验收。仅调用exec_command执行sleep 120，yield_time_ms=1000，不读写文件，不调用其他命令、网络，不提权、不联系其他聊天或Agent。允许write_stdin等待这一命令自然结束，保持任务进行中；结束后最终只回复${marker}ORIGIN。期间若收到追加指令，保持等待原命令自然结束，然后按追加要求回复。`,
      },
      ...[1, 2, 3].map((index) => ({
        index,
        mode: 'queue',
        text: `仅原样最终回复${marker}QUEUE_${index}。不调用工具、不改文件、不创建其他聊天或联系Agent。`,
      })),
      {
        index: 4,
        mode: 'steer',
        text: `追加要求：原sleep命令自然结束后，本轮最终仅回复${marker}STEER。保持等待当前命令，不停止、不重试、不调用其他命令，不修改文件或其他聊天。`,
      },
    ];
await writeFile(
  join(root, 'immutable-cases.json'),
  JSON.stringify({ nonce, projectId, projectRoot, cases }, null, 2),
  { mode: 0o600 },
);
const fingerprint = async () => {
  const files = execFileSync(
    'git',
    [
      'ls-files',
      '-co',
      '--exclude-standard',
      'apps/android',
      'apps/host-agent',
      'packages/codex-adapter',
      'packages/mobile-core',
      'packages/protocol',
      'scripts/mobile/android-queue-live.mjs',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const hash = createHash('sha256');
  for (const file of new Set(files))
    hash
      .update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return hash.digest('hex');
};
const report = {
  phase: immediateAppend ? 'P4-4f.9' : 'P4-4f.2',
  scenario: immediateAppend ? 'immediate-append' : 'cancel-and-steer',
  scope: immediateAppend
    ? 'Physical native queue row immediately appends the same original ID to its active original Turn; remaining original FIFO; USB loopback only'
    : 'Physical native original-owner queue numbering, middle cancellation, same-Turn steer and natural original FIFO outcomes; USB loopback, not network path proof',
  status: 'running',
  preflightOnly,
  resumedOriginalInput: resume ? firstAudit : null,
  priorConsumedInputs: resume ? 1 : 0,
  threadId: firstAudit?.threadId,
  nonce,
  hostId,
  serial,
  projectId,
  projectRoot,
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  claims: [],
  posts: [],
  native: [],
  scenarios: [],
  rejectedReads: [],
  rejectedPosts: [],
  cleanupReadCancellations: [],
  newRealModelInputsConfirmed: preflightOnly ? 0 : null,
  executionClaims: [],
  executionPosts: [],
  cancellationClaims: [],
  cancellationPosts: [],
  consumedOriginalInputs: [],
  unresolvedOriginalCases: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android original queue read-only witness',
);
assert(process.env.CODEX_EXECUTABLE);
let service, proxy, control, token, endpoint, fixture;
const reverses = [],
  claims = new Map(),
  submitted = new Map(),
  submittedMethods = new Set(),
  upstreams = new Set();
const clean = (value) =>
  String(value)
    .replaceAll(token ?? '[NO_TOKEN]', '[REDACTED]')
    .replaceAll(controlKey, '[REDACTED]');
const json = (res, value, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
const body = async (req) => {
  const chunks = [];
  let size = 0;
  for await (const part of req) {
    size += part.length;
    assert(size < 180000);
    chunks.push(part);
  }
  return JSON.parse(Buffer.concat(chunks).toString());
};
const listen = async (server) => {
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', done);
  });
  return server.address().port;
};
const wait = async (fn, label) => {
  const until = Date.now() + 25000;
  let last;
  do {
    try {
      const value = await fn();
      if (value) return value;
    } catch (e) {
      last = e;
    }
    await new Promise((done) => setTimeout(done, 500));
  } while (Date.now() < until);
  throw Error(label + ': ' + (last?.message ?? 'unavailable'));
};
const originalText = (index) => cases[index].text;
const queueWitness = new DesktopNativeQueue(
  process.env.CODEX_EXECUTABLE,
  join(homedir(), '.codex'),
);
const matches = (turn, id) =>
  turn.items?.some(
    (i) =>
      (i.type === 'userMessage' && i.clientId === id) ||
      (i.type === 'steeringUserMessage' &&
        i.status === 'accepted' &&
        i.serverUserMessageId &&
        i.clientUserMessageId === id),
  );
const clientId = (index, sent) => (index === 0 ? 'first-' + sent.operationId : sent.operationId);
const owner = async () => {
  await witness.ipc.follow(report.threadId, 5000, true);
  const value = witness.ipc.state(report.threadId);
  assert(
    value &&
      witness.ipc.hasOwner(report.threadId) &&
      value.id === report.threadId &&
      value.hostId === 'local',
  );
  assert.equal(await realpath(value.cwd), projectRoot);
  return value;
};
const effective = (turn) => {
  assert.equal(turn.params.collaborationMode.mode, 'default');
  assert.equal(turn.params.model ?? turn.params.collaborationMode.settings.model, fixture.model);
  assert.equal(
    turn.params.effort ?? turn.params.collaborationMode.settings.reasoning_effort,
    'xhigh',
  );
  assert.equal(turn.params.serviceTier, 'priority');
  assert.equal(turn.params.sandboxPolicy.type, 'readOnly');
  assert.equal(turn.params.approvalPolicy, 'on-request');
};
async function countAccepted(value) {
  const turns = desktopTurns(value);
  report.consumedOriginalInputs = [];
  if (resume && !submitted.has(0)) {
    const id = 'first-' + firstAudit.operationId,
      found = turns.filter((t) => matches(t, id));
    assert.equal(found.length, 1);
    report.consumedOriginalInputs.push({
      index: 0,
      operationId: firstAudit.operationId,
      clientId: id,
      turnId: found[0].turnId,
      recoveredOriginal: true,
    });
  }
  for (const [index, sent] of submitted) {
    const id = clientId(index, sent),
      found = turns.filter((t) => matches(t, id));
    assert(found.length <= 1);
    if (found.length)
      report.consumedOriginalInputs.push({
        index,
        operationId: sent.operationId,
        clientId: id,
        turnId: found[0].turnId,
      });
  }
  report.workflowAcceptedInputs = report.consumedOriginalInputs.length;
  report.newRealModelInputsConfirmed = report.workflowAcceptedInputs - report.priorConsumedInputs;
}
function verifyRecords(records) {
  assert.equal(records.length, submitted.size);
  for (const sent of submitted.values()) {
    const record = records.find((r) => r.id === sent.operationId);
    assert(record && sha(record.intent) === record.intentSha256);
    assert.deepEqual(JSON.parse(record.intent), sent.body);
  }
}
async function observeOrigin(value) {
  if (resume && !submitted.has(0)) {
    const intent = JSON.parse(value.record.intent);
    assert.equal(sha(JSON.stringify(intent)), resume.posts[0].bodySha256);
    assert.equal(intent.clientOperationId, firstAudit.operationId);
    submitted.set(0, { operationId: firstAudit.operationId, body: intent });
  }
  if (!report.threadId) report.threadId = value.threadId;
  if (report.threadId) assert.equal(value.threadId, report.threadId);
  const sent = submitted.get(0);
  assert(
    sent &&
      value.record.id === sent.operationId &&
      sha(value.record.intent) === value.record.intentSha256,
  );
  assert.deepEqual(JSON.parse(value.record.intent), sent.body);
  const o = await wait(async () => {
    const v = await owner();
    return desktopTurns(v).some(
      (t) =>
        matches(t, clientId(0, sent)) &&
        t.status === 'inProgress' &&
        t.items.some((i) => i.type === 'commandExecution'),
    )
      ? v
      : null;
  }, 'Original owned command before queue input');
  const turn = desktopTurns(o).find((t) => matches(t, clientId(0, sent)));
  assert.equal(value.turnId, turn.turnId);
  assert.equal(o.requests.length, 0);
  effective(turn);
  const cmds = turn.items.filter((i) => i.type === 'commandExecution');
  assert.equal(cmds.length, 1);
  assert(
    ['/bin/zsh -lc "sleep 120"', "/bin/zsh -lc 'sleep 120'"].includes(cmds[0].command),
    'Only bounded authorized sleep command',
  );
  assert.equal(cmds[0].status, 'inProgress');
  report.originTurnId = turn.turnId;
  report.originReady = true;
  await countAccepted(o);
  await save();
  return { ok: true };
}
async function queuedOriginal() {
  const entries = await queueWitness.list(report.threadId);
  const expected = (immediateAppend ? [1, 2] : [1, 2, 3]).map((index) => submitted.get(index));
  assert(expected.every(Boolean));
  assert.deepEqual(
    entries.map((e) => e.clientUserMessageId),
    expected.map((s) => s.operationId),
  );
  entries.forEach((e, n) =>
    assert.deepEqual(e.input, [{ type: 'text', text: cases[n + 1].text, text_elements: [] }]),
  );
  return entries;
}
async function complete(value) {
  verifyRecords(value.records);
  const o = await owner(),
    turns = desktopTurns(o);
  assert.equal(o.requests.length, 0);
  const runtime = (await service.session.open(report.threadId)).runtime;
  if (immediateAppend) return completeAppend(value, o, turns, runtime);
  assert.equal(turns.length, 3);
  for (const index of [0, 1, 3, 4]) {
    const sent = submitted.get(index),
      id = clientId(index, sent),
      found = turns.filter((t) => matches(t, id));
    assert.equal(found.length, 1);
    assert.equal(found[0].status, 'completed');
    effective(found[0]);
  }
  assert.equal(turns.filter((t) => matches(t, submitted.get(2).operationId)).length, 0);
  assert(
    matches(turns[0], clientId(0, submitted.get(0))) &&
      matches(turns[0], submitted.get(4).operationId),
  );
  assert.equal(turns[0].turnId, report.originTurnId);
  for (const [n, suffix] of ['STEER', 'QUEUE_1', 'QUEUE_3'].entries())
    assert(
      turns[n].items.some(
        (i) =>
          i.type === 'agentMessage' &&
          i.phase === 'final_answer' &&
          i.text.trim() === marker + suffix,
      ),
    );
  const canceled = runtime.receipts.find((r) => r.clientMessageId === submitted.get(2).operationId);
  assert(canceled && canceled.state === 'cancelled');
  assert.equal(report.cancellationPosts.length, 1);
  assert.equal((await queueWitness.list(report.threadId)).length, 0);
  for (const index of [1, 3, 4]) {
    const r = runtime.receipts.find((r) => r.clientMessageId === submitted.get(index).operationId);
    assert(r && r.state === 'completed');
  }
  const canonical = await witness.reader.read('thread/read', {
    threadId: report.threadId,
    includeTurns: true,
  });
  const users = canonical.thread.turns
    .flatMap((t) => t.items ?? [])
    .filter((i) => i.type === 'userMessage');
  assert.equal(users.length, 4);
  for (const t of turns)
    assert(!t.items.some((i) => ['fileChange', 'mcpToolCall', 'dynamicToolCall'].includes(i.type)));
  await countAccepted(o);
  assert.equal(report.workflowAcceptedInputs, 4);
  report.scenarios.push({
    threadId: report.threadId,
    originTurnId: report.originTurnId,
    turnIds: turns.map((t) => t.turnId),
    canonicalInputs: 4,
    modelInputs: report.newRealModelInputsConfirmed,
    cancelledOperationId: submitted.get(2).operationId,
    originalQueueSource: 'native',
    nativePid: value.pid,
  });
  await writeFile(join(root, 'independent-original-owner.json'), JSON.stringify(o, null, 2), {
    mode: 0o600,
  });
  await save();
  return { ok: true };
}

async function completeAppend(value, o, turns, runtime) {
  assert.equal(turns.length, 2);
  for (const index of [0, 1, 2]) {
    const found = turns.filter((t) => matches(t, clientId(index, submitted.get(index))));
    assert.equal(found.length, 1);
    assert.equal(found[0].status, 'completed');
    effective(found[0]);
  }
  assert.equal(turns[0].turnId, report.originTurnId);
  assert(matches(turns[0], submitted.get(1).operationId));
  assert(matches(turns[1], submitted.get(2).operationId));
  for (const [index, suffix] of ['APPENDED', 'FINAL'].entries())
    assert(
      turns[index].items.some(
        (i) =>
          i.type === 'agentMessage' &&
          i.phase === 'final_answer' &&
          i.text.trim() === marker + suffix,
      ),
    );
  const commands = turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'commandExecution');
  assert.equal(commands.length, 1);
  assert.equal(commands[0].status, 'completed');
  assert.equal(commands[0].exitCode, 0);
  for (const t of turns)
    assert(!t.items.some((i) => ['fileChange', 'mcpToolCall', 'dynamicToolCall'].includes(i.type)));
  for (const index of [1, 2]) {
    const receipt = runtime.receipts.find(
      (r) => r.clientMessageId === submitted.get(index).operationId,
    );
    assert(receipt && receipt.state === 'completed');
  }
  const promoted = runtime.receipts.find((r) => r.clientMessageId === submitted.get(1).operationId);
  assert.equal(promoted.turnId, report.originTurnId);
  assert.equal(report.executionPosts.length, 1);
  assert.equal((await queueWitness.list(report.threadId)).length, 0);
  const canonical = await witness.reader.read('thread/read', {
    threadId: report.threadId,
    includeTurns: true,
  });
  const users = canonical.thread.turns
    .flatMap((t) => t.items ?? [])
    .filter((i) => i.type === 'userMessage');
  assert.equal(users.length, 3);
  await countAccepted(o);
  assert.equal(report.workflowAcceptedInputs, 3);
  report.scenarios.push({
    threadId: report.threadId,
    originTurnId: report.originTurnId,
    turnIds: turns.map((t) => t.turnId),
    canonicalInputs: 3,
    modelInputs: report.newRealModelInputsConfirmed,
    appendedOriginalId: submitted.get(1).operationId,
    remainingOriginalId: submitted.get(2).operationId,
    originalQueueSource: 'native',
    nativePid: value.pid,
  });
  await writeFile(join(root, 'independent-original-owner.json'), JSON.stringify(o, null, 2), {
    mode: 0o600,
  });
  await save();
  return { ok: true };
}

try {
  await witness.connect();
  report.desktopVersion = witness.version;
  if (process.env.ANDROID_QUEUE_EXPECT_DESKTOP_VERSION)
    assert.equal(witness.version, process.env.ANDROID_QUEUE_EXPECT_DESKTOP_VERSION);
  if (process.env.ANDROID_QUEUE_EXPECT_ADAPTER_SHA256)
    assert.equal(report.adapterSha256, process.env.ANDROID_QUEUE_EXPECT_ADAPTER_SHA256);
  const priorPath = process.env.ANDROID_QUEUE_PRIOR_REPORT;
  assert(
    priorPath &&
      isAbsolute(priorPath) &&
      priorPath.startsWith(resolve(output) + '/question-model-live-') &&
      priorPath.endsWith('/report.json'),
  );
  const prior = JSON.parse(await readFile(priorPath, 'utf8'));
  assert.equal(prior.status, 'passed');
  assert.equal(prior.projectId, projectId);
  assert.equal(prior.projectRoot, projectRoot);
  assert.equal(prior.newRealModelInputsConfirmed, 0);
  const history = await witness.reader.read('thread/read', {
    threadId: prior.threadId,
    includeTurns: true,
  });
  assert.equal(await realpath(history.thread.cwd), projectRoot);
  const oldQueue = await queueWitness.list(prior.threadId);
  report.nativeQueueReadOnlyPreflight = {
    priorReportSha256: sha(await readFile(priorPath)),
    ownedPriorThreadId: prior.threadId,
    existingQueueItems: oldQueue.length,
    readOnly: true,
  };
  const bridge = join(state, 'bridge.json');
  await writeFile(bridge, JSON.stringify({ hostId, desktopVersion: witness.version }), {
    mode: 0o600,
  });
  if (!resume)
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
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Android real native queue DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  service = await startRuntimeServer();
  token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  endpoint = 'http://127.0.0.1:' + service.server.address().port;
  const workspace = await service.session.workspace(),
    project = workspace.projects.find((p) => p.id === projectId);
  assert(
    project &&
      (await Promise.all(project.roots.map((path) => realpath(path)))).includes(projectRoot),
  );
  assert(workspace.capabilities.includes('desktop:creations'));
  const model = (await service.session.models()).models.find((m) => m.isDefault);
  assert(
    model && model.supportedEfforts.includes('xhigh') && model.supportedSpeeds.includes('fast'),
  );
  const gets = new Set([
    '/healthz',
    '/v1/workspace',
    '/v1/history',
    '/v1/models',
    '/v1/connection-routes',
    '/v1/events',
    '/v1/receipts',
    '/v1/replies',
    '/v1/replies/status',
    '/v1/desktop/creation-receipts',
    '/v1/desktop/creation-status',
    '/v1/threads/fork-options',
    '/v1/desktop/open-status',
    '/v1/desktop/creations',
    '/v1/commands/status',
  ]);
  const posts = new Set([
    '/v1/desktop/creations',
    '/v1/desktop/creations/submit',
    '/v1/replies',
    '/v1/desktop/open',
    immediateAppend ? '/v1/queue/execute' : '/v1/queue/cancel',
  ]);
  proxy = createServer((req, res) => {
    const entry = { controller: new AbortController(), cleanupAbort: false };
    upstreams.add(entry);
    void (async () => {
      assert(!req.headers.origin && req.headers.authorization === 'Bearer ' + token);
      const url = new URL(req.url, endpoint);
      assert(
        req.method === 'GET'
          ? gets.has(url.pathname)
          : req.method === 'POST' && posts.has(url.pathname),
      );
      if (req.method === 'GET' && url.pathname === '/v1/connection-routes') {
        assert.equal(url.searchParams.get('hostId'), hostId);
        assert.deepEqual([...url.searchParams.keys()], ['hostId']);
        assert.equal(url.searchParams.getAll('hostId').length, 1);
      }
      let bytes;
      if (req.method === 'POST') {
        assert(!preflightOnly);
        const value = await body(req);
        assert.equal(value.hostId, hostId);
        const key =
          url.pathname +
          ':' +
          (value.clientMessageId ??
            value.clientOperationId ??
            value.approvalId + ':' + value.turnId);
        assert(!submittedMethods.has(key), 'No original write replay');
        submittedMethods.add(key);
        if (Object.hasOwn(value, 'text')) {
          const index = report.activeInput;
          assert(claims.has(index) && !submitted.has(index));
          assert.equal(value.text, originalText(index));
          assert.equal(value.mode ?? 'next-turn', cases[index].mode);
          if (index !== 4) {
            assert.equal(value.model, model.id);
            assert.equal(value.effort, 'xhigh');
            assert.equal(value.speed, 'fast');
            assert.equal(value.permissionMode, 'read-only');
            assert.equal(value.collaborationMode, 'default');
          } else {
            for (const key of ['model', 'effort', 'speed', 'permissionMode', 'collaborationMode'])
              assert(!Object.hasOwn(value, key));
            assert.equal(value.expectedTurnId, report.originTurnId);
          }
          if (index === 0) {
            assert.equal(url.pathname, '/v1/desktop/creations');
            assert.equal(value.projectId, projectId);
            assert.deepEqual(value.attachments, []);
          } else {
            assert.equal(url.pathname, '/v1/replies');
            assert.equal(value.threadId, report.threadId);
            assert(!Object.hasOwn(value, 'expectedPlanId'));
            assert.deepEqual(value.attachmentIds, []);
          }
          submitted.set(index, {
            operationId: value.clientOperationId ?? value.clientMessageId,
            body: value,
          });
        }
        if (url.pathname === '/v1/queue/cancel') {
          const lease = report.cancellationClaims[0];
          assert(lease && report.cancellationPosts.length === 0);
          assert.equal(value.threadId, report.threadId);
          assert.equal(value.clientMessageId, lease.operationId);
          report.cancellationPosts.push({
            operationId: lease.operationId,
            bodySha256: sha(JSON.stringify(value)),
          });
        }
        if (url.pathname === '/v1/queue/execute') {
          assert(immediateAppend);
          const lease = report.executionClaims[0];
          assert(lease && report.executionPosts.length === 0);
          assert.equal(value.threadId, report.threadId);
          assert.equal(value.clientMessageId, lease.operationId);
          assert.equal(value.expectedTurnId, report.originTurnId);
          assert.deepEqual(Object.keys(value).sort(), [
            'clientMessageId',
            'expectedTurnId',
            'hostId',
            'threadId',
          ]);
          report.executionPosts.push({
            operationId: lease.operationId,
            bodySha256: sha(JSON.stringify(value)),
          });
        }
        if (url.pathname === '/v1/desktop/creations/submit')
          assert.equal(value.clientOperationId, submitted.get(0)?.operationId);
        if (url.pathname === '/v1/desktop/open') assert.equal(value.threadId, report.threadId);
        report.posts.push({
          path: url.pathname,
          index: report.activeInput,
          operationId: value.clientMessageId ?? value.clientOperationId,
          bodySha256: sha(JSON.stringify(value)),
        });
        await save();
        bytes = JSON.stringify(value);
      }
      const timeout = setTimeout(
        () => entry.controller.abort(new Error('upstream-read-deadline')),
        30000,
      );
      try {
        const response = await fetch(endpoint + req.url, {
          method: req.method,
          headers: {
            Authorization: 'Bearer ' + token,
            ...(bytes ? { 'Content-Type': 'application/json' } : {}),
          },
          ...(bytes ? { body: bytes } : {}),
          redirect: 'error',
          signal: entry.controller.signal,
        });
        const data = Buffer.from(await response.arrayBuffer());
        if (
          req.method === 'POST' &&
          url.pathname.startsWith('/v1/desktop/creations') &&
          response.ok
        ) {
          const created = JSON.parse(data);
          assert.equal(created.hostId, hostId);
          assert.equal(created.clientOperationId, submitted.get(0).operationId);
          if (created.threadId) {
            if (report.threadId) assert.equal(created.threadId, report.threadId);
            else report.threadId = created.threadId;
            await save();
          }
        }
        res.writeHead(response.status, {
          'Content-Type': response.headers.get('content-type') ?? 'application/json',
        });
        res.end(data);
      } finally {
        clearTimeout(timeout);
      }
    })()
      .catch((e) => {
        const error = clean(e.message);
        if (entry.cleanupAbort && req.method === 'GET')
          report.cleanupReadCancellations.push({ path: req.url, error });
        else
          (req.method === 'POST' ? report.rejectedPosts : report.rejectedReads).push({
            path: req.url,
            error,
          });
        void save();
        if (!res.destroyed && !res.headersSent) json(res, { error }, 500);
        else res.destroy();
      })
      .finally(() => upstreams.delete(entry));
  });
  const proxyPort = await listen(proxy);
  control = createServer((req, res) => {
    void (async () => {
      assert(
        req.method === 'POST' &&
          !req.headers.origin &&
          req.headers.authorization === 'Bearer ' + controlKey,
      );
      const value = await body(req);
      assert.equal(value.nonce, nonce);
      if (req.url === '/preflight-complete') {
        assert(preflightOnly && report.posts.length === 0);
        report.preflightNative = value;
        await save();
        return json(res, { ok: true });
      }
      assert(!preflightOnly);
      if (req.url === '/claim-input') {
        const index = value.index;
        assert(Number.isInteger(index) && index >= 0 && index < cases.length && !claims.has(index));
        assert.equal(value.text, cases[index].text);
        assert.equal(value.mode, cases[index].mode);
        assert.equal(index, claims.size);
        if (index === 0) {
          assert.equal(value.threadId, '');
          assert.equal(report.posts.length, 0);
        } else {
          assert.equal(value.threadId, report.threadId);
          assert(report.originReady);
          const current = desktopTurns(await owner()).find((t) => t.turnId === report.originTurnId);
          assert.equal(current.status, 'inProgress');
        }
        if (index === 4) {
          assert(report.middleCancelled);
          assert.equal(value.expectedTurnId, report.originTurnId);
        } else assert.equal(value.expectedTurnId, null);
        claims.set(index, value);
        report.claims.push(value);
        report.activeInput = index;
        await save();
        return json(res, { ready: true });
      }
      if (req.url === '/origin-ready') return json(res, await observeOrigin(value));
      assert.equal(value.threadId, report.threadId);
      if (req.url === '/queue-ready') {
        assert.equal(submitted.size, immediateAppend ? 3 : 4);
        verifyRecords(value.records);
        const entries = await queuedOriginal();
        const o = await owner();
        assert.equal(
          desktopTurns(o).find((t) => t.turnId === report.originTurnId).status,
          'inProgress',
        );
        report.nativeQueueItems = entries.map((e) => ({
          id: e.id,
          operationId: e.clientUserMessageId,
        }));
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/execute-ready') {
        assert(immediateAppend && report.executionClaims.length === 0);
        assert.equal(value.operationId, submitted.get(1).operationId);
        assert.equal(value.turnId, report.originTurnId);
        const entries = await queuedOriginal();
        const item = entries.find((e) => e.clientUserMessageId === value.operationId);
        assert(item);
        const turn = desktopTurns(await owner()).find((t) => t.turnId === report.originTurnId);
        assert.equal(turn.status, 'inProgress');
        report.executionClaims.push({
          threadId: report.threadId,
          operationId: value.operationId,
          nativeQueueId: item.id,
          expectedTurnId: value.turnId,
        });
        await save();
        return json(res, { ready: true });
      }
      if (req.url === '/queue-executed') {
        assert(immediateAppend && report.executionPosts.length === 1);
        verifyRecords(value.records);
        const entries = await queueWitness.list(report.threadId);
        assert.deepEqual(
          entries.map((e) => e.clientUserMessageId),
          [submitted.get(2).operationId],
        );
        const o = await owner();
        const turn = desktopTurns(o).find((t) => t.turnId === report.originTurnId);
        assert(turn && matches(turn, submitted.get(1).operationId));
        assert.equal(desktopTurns(o).length, 1);
        report.immediateAppendObserved = true;
        await countAccepted(o);
        await writeFile(
          join(root, 'immediate-append-original-owner.json'),
          JSON.stringify(o, null, 2),
          { mode: 0o600 },
        );
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/cancel-ready') {
        assert.equal(report.cancellationClaims.length, 0);
        assert.equal(value.operationId, submitted.get(2).operationId);
        const entries = await queuedOriginal();
        const item = entries.find((e) => e.clientUserMessageId === value.operationId);
        assert(item);
        report.cancellationClaims.push({
          threadId: report.threadId,
          operationId: value.operationId,
          nativeQueueId: item.id,
        });
        await save();
        return json(res, { ready: true });
      }
      if (req.url === '/queue-cancelled') {
        assert.equal(report.cancellationPosts.length, 1);
        const entries = await queueWitness.list(report.threadId);
        assert.deepEqual(
          entries.map((e) => e.clientUserMessageId),
          [1, 3].map((n) => submitted.get(n).operationId),
        );
        const runtime = (await service.session.open(report.threadId)).runtime;
        assert(
          runtime.receipts.some(
            (r) => r.clientMessageId === submitted.get(2).operationId && r.state === 'cancelled',
          ),
        );
        report.middleCancelled = true;
        await save();
        return json(res, { ok: true });
      }
      assert.equal(req.url, '/case-complete');
      return json(res, await complete(value));
    })().catch((e) => {
      report.witnessError = clean(e.stack);
      void save();
      json(res, { error: clean(e.message) }, 500);
    });
  });
  const controlPort = await listen(control);
  report.ownedPorts = {
    host: service.server.address().port,
    proxy: proxyPort,
    control: controlPort,
  };
  await save();
  fixture = {
    nonce,
    evidenceRun: root.split('/').at(-1),
    hostId,
    token,
    endpoint: 'http://127.0.0.1:' + proxyPort,
    control: 'http://127.0.0.1:' + controlPort,
    controlKey,
    projectId,
    model: model.id,
    preflightOnly,
    scenario: report.scenario,
    resume: !!resume,
    priorOperationId: firstAudit?.operationId,
    doneMarker: marker + (immediateAppend ? 'FINAL' : 'QUEUE_3'),
    cases,
  };
  for (const port of [proxyPort, controlPort]) {
    await android('reverse', '--no-rebind', 'tcp:' + port, 'tcp:' + port);
    reverses.push(port);
  }
  for (const [name, source, packageName] of [
    ['app', '.build/native/android/outputs/apk/debug/android-debug.apk', pkg],
    [
      'test',
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
      pkg + '.test',
    ],
  ]) {
    const apk = join(root, name + '.apk');
    await writeFile(apk, await readFile(source), { mode: 0o600 });
    report[name + 'Sha256'] = sha(await readFile(apk));
    await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: true });
  }
  if (resume) {
    claims.set(0, resume.claims[0]);
    report.claims.push({ ...resume.claims[0], reusedOriginalClaim: true });
  }
  for (const index of preflightOnly ? [-1] : [0]) {
    await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
    fixture.activeCase = index;
    fixture.threadId = report.threadId ?? '';
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-queue-model-config.json'",
      ],
      { input: JSON.stringify(fixture), encoding: 'utf8', timeout: 10000 },
    );
    let result;
    try {
      result = await execute(
        adb,
        [
          '-s',
          serial,
          'shell',
          'am',
          'instrument',
          '-w',
          '-r',
          '-e',
          'class',
          'com.agenthub.android.MobileQueueModelLiveTest#' +
            (immediateAppend
              ? 'originalNativeQueueImmediateAppend'
              : 'originalNativeQueueCancellationAndSteer'),
          '-e',
          'queueModelCase',
          String(index),
          pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
        ],
        { encoding: 'utf8', timeout: 650000, maxBuffer: 16 * 1024 * 1024 },
      );
    } catch (e) {
      await writeFile(
        join(root, `case-${index}-command-failure.json`),
        JSON.stringify(
          { code: e.code, killed: e.killed, stdout: clean(e.stdout), stderr: clean(e.stderr) },
          null,
          2,
        ),
        { mode: 0o600 },
      );
      throw e;
    }
    const log = clean(result.stdout + result.stderr);
    await writeFile(join(root, `case-${index}-instrumentation.log`), log, { mode: 0o600 });
    assert(
      /OK \(1 test\)/.test(log) &&
        !/FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed/.test(log),
    );
    report.native.push({ index, status: 'passed', logSha256: sha(log) });
    await save();
  }
  assert.equal(await fingerprint(), report.sourceSha256);
  assert.equal(report.rejectedPosts.length, 0);
  assert.equal(report.rejectedReads.length, 0);
  if (preflightOnly)
    assert(report.preflightNative && report.posts.length === 0 && submitted.size === 0);
  else {
    assert.equal(report.scenarios.length, 1);
    assert.equal(submitted.size, immediateAppend ? 3 : 5);
    assert.equal(report.workflowAcceptedInputs, immediateAppend ? 3 : 4);
    assert.equal(
      immediateAppend ? report.executionPosts.length : report.cancellationPosts.length,
      1,
    );
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  // Only the exact owned broker record may fill a hidden thread ID for final read-only counting.
  // It never grants another input or queue action, even when creation confirmation was unknown.
  if (!report.threadId && submitted.has(0)) {
    try {
      const operation = submitted.get(0);
      const localState = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
      assert.equal(localState.hostId, hostId);
      const records = JSON.parse(
        await readFile(join(state, 'desktop-creations/creations.json'), 'utf8'),
      );
      const original = records[operation.operationId];
      assert(original && original.clientOperationId === operation.operationId);
      assert.equal(original.text, cases[0].text);
      assert.equal(original.projectId, projectId);
      assert.equal(await realpath(original.directory), projectRoot);
      assert.match(original.threadId ?? '', /^[a-f0-9-]{36}$/);
      assert(!original.existingThreadIds.includes(original.threadId));
      report.threadId = original.threadId;
      report.finalAuditUsedUnconfirmedOwnedRecord = true;
    } catch (e) {
      report.unconfirmedOriginalAuditError = clean(e.message);
    }
  }
  if (report.threadId) {
    try {
      const value = await owner();
      await countAccepted(value);
      await writeFile(join(root, 'final-original-owner.json'), JSON.stringify(value, null, 2), {
        mode: 0o600,
      });
    } catch (e) {
      report.finalReadOnlyAuditError = clean(e.message);
    }
  }
  for (const [index, sent] of submitted)
    if (!report.scenarios.length)
      report.unresolvedOriginalCases.push({
        index,
        operationId: sent.operationId,
        bodySha256: sha(JSON.stringify(sent.body)),
      });
  try {
    const archive = (
      await execute(
        adb,
        [
          '-s',
          serial,
          'exec-out',
          'run-as',
          pkg,
          'tar',
          '-cf',
          '-',
          'files/acceptance/P4-queue-model/' + nonce + '/' + fixture.evidenceRun,
        ],
        { encoding: 'buffer', timeout: 10000, maxBuffer: 32 * 1024 * 1024 },
      )
    ).stdout;
    await writeFile(join(root, 'native-evidence.tar'), archive, { mode: 0o600 });
  } catch (e) {
    report.artifactError = clean(e.message);
  }
  await android('shell', 'am', 'force-stop', pkg).catch(() => {});
  for (const port of reverses) await android('reverse', '--remove', 'tcp:' + port).catch(() => {});
  for (const entry of upstreams) {
    entry.cleanupAbort = true;
    entry.controller.abort(new Error('owned-examiner-close'));
  }
  for (const server of [proxy, control])
    if (server) {
      server.closeAllConnections();
      await new Promise((done) => server.close(done));
    }
  queueWitness.close();
  witness.close();
  try {
    await service?.close();
    report.ownedHostClosed = true;
  } catch (e) {
    report.hostCloseError = clean(e.stack);
    report.status = 'failed';
    process.exitCode = 1;
  }
  if ((report.rejectedPosts.length || report.rejectedReads.length) && report.status === 'passed') {
    report.status = 'failed';
    report.error = 'Unexpected original reads/writes; preserve original IDs and never replay';
    process.exitCode = 1;
  }
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      preflightOnly,
      nativePhases: report.native.length,
      confirmedNewModelInputs: report.newRealModelInputsConfirmed,
      unresolvedOriginalCases: report.unresolvedOriginalCases.length,
      error: report.error,
    }),
  );
}
