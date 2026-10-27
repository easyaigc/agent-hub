import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import {
  DesktopRuntime,
  desktopTurns,
  desktopProposedPlan,
} from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const preflightOnly = process.env.ANDROID_PLAN_PREFLIGHT_ONLY === '1';
assert(preflightOnly || process.env.ANDROID_PLAN_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const projectId = process.env.ANDROID_PLAN_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_PLAN_PROJECT_ROOT ?? '');
const output = process.env.MOBILE_PLAN_ACCEPTANCE_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
const root = join(output, 'plan-model-live-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const priorPath = process.env.ANDROID_PLAN_PRIOR_EVIDENCE;
let prior;
if (priorPath) {
  assert(resolve(priorPath).startsWith(resolve(output) + '/'));
  prior = JSON.parse(await readFile(priorPath, 'utf8'));
  assert.equal(prior.status, 'failed');
  assert.equal(prior.scenarios.length, 1);
  assert.equal(prior.scenarios[0].index, 0);
  assert.equal(prior.newRealModelInputsConfirmed, 1);
  assert.equal(prior.unresolvedOriginalCases.length, 0);
  assert.equal(prior.claims.length, 1);
  assert(prior.posts.every((p) => p.index === 0));
  assert.equal(prior.rejectedPosts.length, 0);
  assert.equal(prior.rejectedReads.length, 0);
  assert.equal(prior.projectId, projectId);
  assert.equal(await realpath(prior.projectRoot), projectRoot);
}
const state = join(root, 'host');
await mkdir(state, { mode: 0o700 });
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
const hostId = prior?.hostId ?? randomUUID(),
  nonce = prior?.nonce ?? randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
const sha = (value) => createHash('sha256').update(value).digest('hex');
const finalMarker = 'ANDROID_P4_PLAN_' + nonce + '_IMPLEMENTED';
const cases = [
  {
    index: 0,
    marker: 'ANDROID_P4_PLAN_' + nonce + '_INITIAL',
    text: `安卓专用真实计划验收：制定仅含一步的计划，唯一目标是在实施时最终只回复 ${finalMarker}。本轮仅提出计划，不能实施，不调用工具、不更改文件、不提问、不派生任务、不联系其他聊天或Agent。使用 proposed_plan 格式使原桌面显示待实施计划，计划正文包含 ANDROID_P4_PLAN_${nonce}_INITIAL。`,
  },
  {
    index: 1,
    marker: 'ANDROID_P4_PLAN_' + nonce + '_REVISED',
    text: `请修订刚才的计划：仍只有最终只回复 ${finalMarker} 这一步，删除旧版标识，计划正文改为包含 ANDROID_P4_PLAN_${nonce}_REVISED。仍然仅提出待实施计划，不实施、不调用工具、不更改文件、不提问、不派生任务、不联系其他聊天或Agent。`,
  },
  {
    index: 2,
    marker: finalMarker,
    text: null,
    dynamicIntent:
      'PLEASE IMPLEMENT THIS PLAN followed by the entire independently verified original revised plan, frozen when leased',
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
      'scripts/mobile/android-plans-live.mjs',
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
  phase: 'P4-4b',
  scope:
    'Physical native plan proposal/revision/full revised implementation, original Electron owner and preserved independent composer; USB loopback, not network path proof',
  status: 'running',
  preflightOnly,
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
  newRealModelInputsConfirmed: 0,
  ...(prior
    ? {
        threadId: prior.threadId,
        initialPlan: prior.initialPlan,
        priorComponent: {
          path: priorPath,
          reportSha256: sha(await readFile(priorPath)),
          originalScenario: prior.scenarios[0],
          consumedInputs: 1,
          neverReplay: true,
        },
      }
    : {}),
  unresolvedOriginalCases: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android original plan read-only witness',
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
const originalText = (index) =>
  index === 2 ? 'PLEASE IMPLEMENT THIS PLAN:\n' + report.revisedPlan.text : cases[index].text;
const expectedPlan = (index) =>
  index === 1 ? report.initialPlan : index === 2 ? report.revisedPlan : undefined;
async function observe(index, value) {
  const sent = submitted.get(index);
  assert(sent && sent.operationId === value.record.id);
  const clientId = index === 0 ? 'first-' + sent.operationId : sent.operationId;
  const owner = await wait(async () => {
    await witness.ipc.follow(value.threadId, 5000, true);
    const owner = witness.ipc.state(value.threadId);
    if (!owner || !witness.ipc.hasOwner(value.threadId)) return null;
    return desktopTurns(owner).some(
      (turn) =>
        turn.status === 'completed' &&
        turn.items?.some((item) => item.type === 'userMessage' && item.clientId === clientId),
    )
      ? owner
      : null;
  }, 'Exact original plan owner completion');
  assert.equal(owner.id, value.threadId);
  assert.equal(owner.hostId, 'local');
  assert.equal(await realpath(owner.cwd), projectRoot);
  const turns = desktopTurns(owner).filter((turn) =>
    turn.items?.some((item) => item.type === 'userMessage' && item.clientId === clientId),
  );
  assert.equal(turns.length, 1);
  const turn = turns[0];
  assert.equal(turn.status, 'completed');
  assert.equal(turn.params.collaborationMode.mode, index === 2 ? 'default' : 'plan');
  assert.equal(turn.params.model ?? turn.params.collaborationMode.settings?.model, fixture.model);
  assert.equal(
    turn.params.effort ?? turn.params.collaborationMode.settings?.reasoning_effort,
    'xhigh',
  );
  assert.equal(turn.params.serviceTier, 'priority');
  assert.equal(turn.params.sandboxPolicy.type, 'readOnly');
  assert.equal(turn.params.approvalPolicy, 'on-request');
  const canonical = await witness.reader.read('thread/read', {
    threadId: value.threadId,
    includeTurns: true,
  });
  assert.equal(canonical.thread.id, value.threadId);
  assert.equal(await realpath(canonical.thread.cwd), projectRoot);
  assert.equal(
    canonical.thread.turns
      .flatMap((turn) => turn.items ?? [])
      .filter((item) => item.type === 'userMessage').length,
    index + 1,
  );
  assert.equal(sha(value.record.intent), value.record.intentSha256);
  assert.deepEqual(JSON.parse(value.record.intent), sent.body);
  if (index === 0) report.threadId = value.threadId;
  else assert.equal(value.threadId, report.threadId);
  const plan = desktopProposedPlan(owner);
  if (index < 2) {
    assert(plan && plan.text.includes(cases[index].marker) && plan.text.includes(finalMarker));
    assert.deepEqual(value.plan, plan);
    if (index === 1) assert.notEqual(plan.id, report.initialPlan.id);
    report[index === 0 ? 'initialPlan' : 'revisedPlan'] = plan;
  } else {
    assert.equal(plan, undefined);
    assert.equal(value.plan, null);
    assert(
      turn.items.some((item) => item.type === 'agentMessage' && item.text.trim() === finalMarker),
    );
  }
  if (index > 0) {
    const receipt = (await service.session.open(value.threadId)).runtime.receipts.find(
      (r) => r.clientMessageId === clientId,
    );
    assert(receipt);
    assert.equal(receipt.expectedPlanId, sent.body.expectedPlanId);
    assert.equal(receipt.text, originalText(index));
    assert.equal(sent.body.expectedPlanId, expectedPlan(index).id);
  }
  const result = {
    index,
    threadId: value.threadId,
    operationId: sent.operationId,
    clientId,
    turnId: turn.turnId,
    expectedPlanId: sent.body.expectedPlanId ?? null,
    collaborationMode: turn.params.collaborationMode.mode,
    canonicalTotalInputs: index + 1,
    exactOriginalInputCount: 1,
    textSha256: sha(originalText(index)),
    originalPlan: plan ?? null,
    nativePid: value.pid,
  };
  report.scenarios.push(result);
  report.newRealModelInputsConfirmed = report.scenarios.length;
  await save();
  return result;
}
try {
  await witness.connect();
  if (prior) {
    await witness.ipc.follow(prior.threadId, 15000, true);
    assert(witness.ipc.hasOwner(prior.threadId));
    assert.deepEqual(desktopProposedPlan(witness.ipc.state(prior.threadId)), prior.initialPlan);
    const canonical = await witness.reader.read('thread/read', {
      threadId: prior.threadId,
      includeTurns: true,
    });
    const users = canonical.thread.turns
      .flatMap((t) => t.items ?? [])
      .filter((i) => i.type === 'userMessage');
    assert.equal(users.length, 1);
    assert.equal(users[0].clientId, prior.scenarios[0].clientId);
    report.priorComponent.canonicalInputsBeforeContinuation = 1;
  }
  report.desktopVersion = witness.version;
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
    HOST_AGENT_NAME: 'Android real plan DEV',
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
        const key = url.pathname + ':' + (value.clientMessageId ?? value.clientOperationId);
        assert(!submittedMethods.has(key), 'No original write replay');
        submittedMethods.add(key);
        if (Object.hasOwn(value, 'text')) {
          const index = fixture.activeCase;
          assert(claims.has(index) && !submitted.has(index));
          assert.equal(value.text, originalText(index));
          assert.equal(value.model, model.id);
          assert.equal(value.effort, 'xhigh');
          assert.equal(value.speed, 'fast');
          assert.equal(value.permissionMode, 'read-only');
          assert.equal(value.collaborationMode, index === 2 ? 'default' : 'plan');
          if (index === 0) {
            assert.equal(url.pathname, '/v1/desktop/creations');
            assert.equal(value.projectId, projectId);
            assert.deepEqual(value.attachments, []);
          } else {
            assert.equal(url.pathname, '/v1/replies');
            assert.equal(value.threadId, report.threadId);
            assert.equal(value.expectedPlanId, expectedPlan(index).id);
            assert.deepEqual(value.attachmentIds, []);
          }
          submitted.set(index, {
            operationId: value.clientOperationId ?? value.clientMessageId,
            body: value,
          });
        }
        if (url.pathname === '/v1/desktop/creations/submit')
          assert.equal(value.clientOperationId, submitted.get(0)?.operationId);
        if (url.pathname === '/v1/desktop/open') assert.equal(value.threadId, report.threadId);
        report.posts.push({
          path: url.pathname,
          index: fixture.activeCase,
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
      if (req.url === '/claim-case') {
        const index = value.index;
        assert(index === fixture.activeCase && index >= 0 && index <= 2 && !claims.has(index));
        assert.equal(value.text, originalText(index));
        if (index === 0) {
          assert.equal(value.threadId, '');
          assert.equal(value.plan, null);
          assert.equal(report.posts.length, 0);
        } else {
          assert.equal(value.threadId, report.threadId);
          await witness.ipc.follow(report.threadId, 15000, true);
          assert(witness.ipc.hasOwner(report.threadId));
          assert.deepEqual(value.plan, desktopProposedPlan(witness.ipc.state(report.threadId)));
          assert.deepEqual(value.plan, expectedPlan(index));
        }
        claims.set(index, value);
        report.claims.push(value);
        await save();
        return json(res, { ready: true });
      }
      assert.equal(req.url, '/case-complete');
      assert.equal(value.index, fixture.activeCase);
      return json(res, await observe(value.index, value));
    })().catch((e) => {
      report.witnessError = clean(e.stack);
      void save();
      json(res, { error: clean(e.message) }, 500);
    });
  });
  const controlPort = await listen(control);
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
    resumeRemaining: Boolean(prior),
    finalMarker,
    independentDraft: 'Android 另一份独立草稿 ' + nonce,
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
  for (const index of preflightOnly ? [prior ? -2 : -1] : prior ? [1, 2] : [0, 1, 2]) {
    await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
    fixture.activeCase = index;
    fixture.threadId = report.threadId ?? '';
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-plan-model-config.json'",
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
          'com.agenthub.android.MobilePlanModelLiveTest#originalPlanModelCase',
          '-e',
          'planModelCase',
          String(index),
          pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
        ],
        { encoding: 'utf8', timeout: 350000, maxBuffer: 16 * 1024 * 1024 },
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
    assert.equal(report.scenarios.length, prior ? 2 : 3);
    assert.equal(submitted.size, prior ? 2 : 3);
    assert.equal(report.newRealModelInputsConfirmed, prior ? 2 : 3);
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  for (const [index, sent] of submitted)
    if (!report.scenarios.some((s) => s.index === index))
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
          'files/acceptance/P4-plan-model/' + nonce + '/' + fixture.evidenceRun,
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
