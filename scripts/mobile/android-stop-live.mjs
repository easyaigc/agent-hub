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

const preflightOnly = process.env.ANDROID_STOP_PREFLIGHT_ONLY === '1';
const coordinateMenu = process.env.ANDROID_STOP_MENU_GESTURE === 'coordinates';
const lateStop = process.env.ANDROID_STOP_LATE_RESPONSE === '1';
const nonemptyDraft = process.env.ANDROID_STOP_NONEMPTY_DRAFT === '1';
assert(!nonemptyDraft || !lateStop, 'Draft retention and held-stop callbacks are separate cases');
assert(preflightOnly || process.env.ANDROID_STOP_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(
  serial &&
    (serial.startsWith('emulator-')
      ? process.env.ANDROID_STOP_ALLOW_EMULATOR === '1'
      : process.env.ANDROID_ALLOW_PHYSICAL === '1'),
);
const projectId = process.env.ANDROID_STOP_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_STOP_PROJECT_ROOT ?? ''),
  output = process.env.MOBILE_STOP_ACCEPTANCE_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
const root = join(output, 'stop-model-live-' + Date.now() + '-' + randomBytes(6).toString('hex')),
  state = join(root, 'host');
await mkdir(output, { recursive: true, mode: 0o700 });
await mkdir(root, { mode: 0o700 });
await mkdir(state, { mode: 0o700 });
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')),
  adb = resolve(local.androidSdk, 'platform-tools/adb'),
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
if (serial.startsWith('emulator-'))
  assert(
    ['AgentHub_Alignment_API26', 'AgentHub_Alignment_API37'].includes(
      (await android('emu', 'avd', 'name')).split('\n')[0].trim(),
    ),
  );
assert((await android('shell', 'dumpsys', 'power')).includes('mWakefulness=Awake'));
const hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url'),
  sha = (value) => createHash('sha256').update(value).digest('hex'),
  marker = 'ANDROID_P4_STOP_' + nonce + '_';
const cases = [
  {
    index: 0,
    mode: 'next-turn',
    text: `专用安卓停止和恢复验收。仅调用exec_command执行sleep 120，yield_time_ms=1000，不读写文件，不调用其他命令、网络，不提权、不联系其他聊天或Agent。允许write_stdin等待这一命令自然结束，保持任务进行中，结束后最终只回复${marker}ORIGIN。若手机中断此任务，之后收到“继续完成上一个任务。”或“请重新执行上一个任务。”时，每次只回复${marker}RECOVER，不再调用工具，不修改文件或其他聊天。`,
  },
  { index: 1, mode: 'next-turn', text: '继续完成上一个任务。' },
  { index: 2, mode: 'next-turn', text: '请重新执行上一个任务。' },
];
const retainedDraft = nonemptyDraft ? marker + 'LOCAL_DRAFT_NEVER_SEND\n保留独立草稿' : '';
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
      'scripts/mobile/android-stop-live.mjs',
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
  phase: lateStop ? 'P4-4o.5' : 'P4-4f.7',
  scope:
    lateStop && !coordinateMenu
      ? 'Native original-owner menus using exact owned-row accessibility actions; controlled delay of the unchanged actual stop response, not natural-network delay proof. Loopback, not cellular/VPN.'
      : coordinateMenu
        ? 'Native original-owner long-press stop/continue/restart with SDK coordinate pointer; optional controlled delay of the actual stop response. Loopback, not cellular/VPN or natural-network delay proof.'
        : 'Native original-owner long-press stop/continue/restart, exact owned-row accessibility actions; serial and physicalDevice identify environment; loopback, not cellular/VPN',
  status: 'running',
  preflightOnly,
  nonce,
  hostId,
  serial,
  physicalDevice: !serial.startsWith('emulator-'),
  menuGesture: coordinateMenu
    ? 'SDK screen-coordinate pointer on exact own popup rows'
    : 'SDK accessibility ACTION_CLICK on exact own popup rows after native long press',
  lateStop,
  nonemptyDraft,
  projectId,
  projectRoot,
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  claims: [],
  posts: [],
  native: [],
  scenarios: [],
  completedCases: [],
  rejectedReads: [],
  rejectedPosts: [],
  cleanupReadCancellations: [],
  newRealModelInputsConfirmed: preflightOnly ? 0 : null,
  stopClaims: [],
  stopPosts: [],
  consumedOriginalInputs: [],
  unresolvedOriginalCases: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android original stop read-only witness',
);
assert(process.env.CODEX_EXECUTABLE);
let service, proxy, control, token, endpoint, fixture;
let releaseStop;
const stopRelease = new Promise((resolve) => {
  releaseStop = resolve;
});
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
  report.newRealModelInputsConfirmed = report.consumedOriginalInputs.length;
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
  }, 'Original owned command before native stop');
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
async function stopped(value) {
  verifyRecords(value.records);
  const o = await owner(),
    turns = desktopTurns(o);
  assert.equal(turns.length, 1);
  assert.equal(turns[0].turnId, report.originTurnId);
  assert.equal(turns[0].status, 'interrupted');
  assert.equal(o.requests.length, 0);
  effective(turns[0]);
  assert.equal(report.stopPosts.length, 1);
  await countAccepted(o);
  assert.equal(report.newRealModelInputsConfirmed, 1);
  if (nonemptyDraft) {
    assert.equal(value.retainedDraft, retainedDraft);
    assert.equal(value.composerAction, 'send');
    assert.equal(value.originalTurnId, report.originTurnId);
    assert.equal(submitted.size, 1);
    assert.equal(report.claims.length, 1);
    report.retainedDraftProof = {
      sha256: sha(value.retainedDraft),
      originalTurnId: value.originalTurnId,
      composerAction: value.composerAction,
      acceptedInputs: 1,
      notSent: true,
    };
  }
  report.stopped = true;
  await save();
  return { ok: true };
}
async function complete(value) {
  const index = value.index;
  assert([1, 2].includes(index) && !report.completedCases.includes(index));
  assert.equal(report.completedCases.length, index - 1);
  verifyRecords(value.records);
  const o = await owner(),
    turns = desktopTurns(o);
  assert.equal(o.requests.length, 0);
  assert.equal(turns.length, index + 1);
  assert.equal(turns[0].status, 'interrupted');
  for (let n = 0; n <= index; n++) {
    const sent = submitted.get(n);
    assert(sent);
    const found = turns.filter((t) => matches(t, clientId(n, sent)));
    assert.equal(found.length, 1);
    assert.equal(found[0].turnId, turns[n].turnId);
    effective(found[0]);
    if (n > 0) {
      assert.equal(found[0].status, 'completed');
      assert(
        found[0].items.some(
          (i) =>
            i.type === 'agentMessage' &&
            i.phase === 'final_answer' &&
            i.text.trim() === marker + 'RECOVER',
        ),
      );
      assert(
        !found[0].items.some((i) =>
          ['commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall'].includes(i.type),
        ),
      );
    }
  }
  const history = await witness.reader.read('thread/read', {
    threadId: report.threadId,
    includeTurns: true,
  });
  const users = history.thread.turns
    .flatMap((t) => t.items ?? [])
    .filter((i) => i.type === 'userMessage');
  assert.equal(users.length, index + 1);
  await countAccepted(o);
  assert.equal(report.newRealModelInputsConfirmed, index + 1);
  const runtime = (await service.session.open(report.threadId)).runtime;
  for (let n = 1; n <= index; n++)
    assert(
      runtime.receipts.some(
        (r) => r.clientMessageId === submitted.get(n).operationId && r.state === 'completed',
      ),
    );
  report.completedCases.push(index);
  report.scenarios.push({
    index,
    turnId: turns[index].turnId,
    canonicalInputs: users.length,
    nativePid: value.pid,
  });
  await writeFile(
    join(root, 'independent-original-owner-' + index + '.json'),
    JSON.stringify(o, null, 2),
    { mode: 0o600 },
  );
  await save();
  return { ok: true };
}
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  const priorPath = process.env.ANDROID_STOP_PRIOR_REPORT;
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
  report.priorReadOnlyPreflight = {
    priorReportSha256: sha(await readFile(priorPath)),
    readOnly: true,
  };
  const bridge = join(state, 'bridge.json');
  await writeFile(bridge, JSON.stringify({ hostId, desktopVersion: witness.version }), {
    mode: 0o600,
  });
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
    HOST_AGENT_NAME: 'Android real native stop DEV',
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
    '/v1/stop',
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
      let bytes;
      if (req.method === 'POST') {
        assert(!preflightOnly);
        const value = await body(req);
        assert.equal(value.hostId, hostId);
        const key =
          url.pathname + ':' + (value.clientMessageId ?? value.clientOperationId ?? value.turnId);
        assert(!submittedMethods.has(key), 'No original write replay');
        submittedMethods.add(key);
        if (Object.hasOwn(value, 'text')) {
          const index = report.activeInput;
          assert(claims.has(index) && !submitted.has(index));
          assert.equal(value.text, cases[index].text);
          assert.equal(value.mode ?? 'next-turn', 'next-turn');
          for (const [k, v] of Object.entries({
            model: model.id,
            effort: 'xhigh',
            speed: 'fast',
            permissionMode: 'read-only',
            collaborationMode: 'default',
          }))
            assert.equal(value[k], v);
          assert(
            !Object.hasOwn(value, 'expectedPlanId') && !Object.hasOwn(value, 'expectedTurnId'),
          );
          if (index === 0) {
            assert.equal(url.pathname, '/v1/desktop/creations');
            assert.equal(value.projectId, projectId);
            assert.deepEqual(value.attachments, []);
          } else {
            assert.equal(url.pathname, '/v1/replies');
            assert.equal(value.threadId, report.threadId);
            assert.deepEqual(value.attachmentIds, []);
          }
          submitted.set(index, {
            operationId: value.clientOperationId ?? value.clientMessageId,
            body: value,
          });
        }
        if (url.pathname === '/v1/stop') {
          assert.equal(report.stopClaims.length, 1);
          assert.equal(report.stopPosts.length, 0);
          assert.deepEqual(Object.keys(value).sort(), ['hostId', 'threadId', 'turnId']);
          assert.equal(value.threadId, report.threadId);
          assert.equal(value.turnId, report.originTurnId);
          report.stopPosts.push({
            threadId: value.threadId,
            turnId: value.turnId,
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
        if (lateStop && req.method === 'POST' && url.pathname === '/v1/stop') {
          assert.equal(response.status, 200);
          report.stopResponseHeld = {
            status: response.status,
            sha256: sha(data),
            receivedAt: Date.now(),
          };
          await save();
          let heldTimer;
          try {
            await Promise.race([
              stopRelease,
              new Promise((_, reject) => {
                heldTimer = setTimeout(
                  () => reject(Error('Owned stop response release deadline')),
                  20_000,
                );
              }),
            ]);
          } finally {
            clearTimeout(heldTimer);
          }
        }
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
        if (lateStop && req.method === 'POST' && url.pathname === '/v1/stop') {
          report.stopResponseDelivered = { at: Date.now(), sha256: sha(data) };
          await save();
        }
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
      if (req.url === '/stop-held') {
        assert(lateStop && value.threadId === report.threadId);
        await wait(() => report.stopResponseHeld, 'Actual stop response held');
        const turns = desktopTurns(await owner());
        assert.equal(turns.length, 1);
        assert.equal(turns[0].status, 'interrupted');
        await countAccepted(await owner());
        report.stopHeldOriginalInterrupted = true;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/release-stop') {
        assert(
          lateStop &&
            report.stopHeldOriginalInterrupted &&
            value.threadId === report.threadId &&
            !report.stopResponseDelivered,
        );
        releaseStop();
        await wait(() => report.stopResponseDelivered, 'Actual stop response delivery');
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/late-stop-complete') {
        assert(lateStop && report.stopResponseDelivered && value.threadId === report.threadId);
        assert(value.draftProtected && value.newerErrorProtected && value.bDraftProtected);
        report.lateStopNative = value;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/claim-input') {
        const index = value.index;
        assert(Number.isInteger(index) && index >= 0 && index < 3 && !claims.has(index));
        assert.equal(index, claims.size);
        assert.equal(value.text, cases[index].text);
        assert.equal(value.mode, 'next-turn');
        assert.equal(value.expectedTurnId, null);
        if (index === 0) {
          assert.equal(value.threadId, '');
          assert.equal(report.posts.length, 0);
        } else {
          assert(report.stopped);
          assert.equal(value.threadId, report.threadId);
          const ts = desktopTurns(await owner());
          assert.equal(ts.length, index);
          assert.equal(ts.at(-1).status, index === 1 ? 'interrupted' : 'completed');
          assert.equal(report.completedCases.length, index - 1);
        }
        claims.set(index, value);
        report.claims.push(value);
        report.activeInput = index;
        await save();
        return json(res, { ready: true });
      }
      if (req.url === '/origin-ready') return json(res, await observeOrigin(value));
      assert.equal(value.threadId, report.threadId);
      if (req.url === '/stop-ready') {
        assert(report.originReady && report.stopClaims.length === 0);
        assert.equal(value.turnId, report.originTurnId);
        const ts = desktopTurns(await owner());
        assert.equal(ts.length, 1);
        assert.equal(ts[0].turnId, report.originTurnId);
        assert.equal(ts[0].status, 'inProgress');
        report.stopClaims.push({ threadId: report.threadId, turnId: report.originTurnId });
        await save();
        return json(res, { ready: true });
      }
      if (req.url === '/stopped') return json(res, await stopped(value));
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
    coordinateMenu,
    lateStop,
    nonemptyDraft,
    retainedDraft,
    doneMarker: marker + 'RECOVER',
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
    await installAndroidApkIfNeeded({
      adb,
      serial,
      packageName,
      apk,
      allowPhysical: !serial.startsWith('emulator-'),
    });
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
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-stop-model-config.json'",
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
          'com.agenthub.android.MobileStopModelLiveTest#originalNativeStopResumeAndRestart',
          '-e',
          'stopModelCase',
          String(index),
          '-e',
          'nativeForeground',
          '1',
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
    assert.deepEqual(report.completedCases, [1, 2]);
    assert.equal(submitted.size, 3);
    assert.equal(report.newRealModelInputsConfirmed, 3);
    assert.equal(report.stopPosts.length, 1);
    if (nonemptyDraft) assert(report.retainedDraftProof?.notSent);
    if (lateStop)
      assert(
        report.lateStopNative &&
          report.stopResponseHeld.sha256 === report.stopResponseDelivered.sha256,
      );
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  releaseStop();
  // The public creation response intentionally hides an unconfirmed thread ID.
  // Bind the exact owned broker record only for this final read-only audit;
  // it never authorizes another input, stop, open or model execution.
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
    if (index === 0 ? !report.stopped : !report.completedCases.includes(index))
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
          'files/acceptance/P4-stop-model/' + nonce + '/' + fixture.evidenceRun,
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
  witness.close();
  service?.close();
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
