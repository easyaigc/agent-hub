import assert from 'node:assert/strict';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const preflightOnly = process.env.ANDROID_CREATION_DETACH_PREFLIGHT_ONLY === '1';
assert(preflightOnly || process.env.ANDROID_CREATION_DETACH_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const projectId = process.env.ANDROID_CREATION_DETACH_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_CREATION_DETACH_PROJECT_ROOT ?? '');
const output = process.env.MOBILE_CREATION_DETACH_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
const root = join(output, 'creation-detach-live-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const state = join(root, 'host');
await mkdir(state, { mode: 0o700 });
const toolchain = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(toolchain.androidSdk, 'platform-tools/adb'),
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
assert(Number(await android('shell', 'getprop', 'ro.build.version.sdk')) >= 29);
const hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
const sha = (value) => createHash('sha256').update(value).digest('hex');
const cases = ['FIRST'].map((name, index) => {
  const marker = 'ANDROID_P4_DETACH_' + nonce + '_' + name;
  return {
    index,
    marker,
    text: `安卓原新建结束等待通知失败专用验收。最终仅原样回复${marker}。不调用工具、不读写文件、不访问网络、不联系其他聊天或Agent。`,
  };
});
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
      'scripts/mobile/android-creation-detach-live.mjs',
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
  phase: 'P4-4g.3',
  scope:
    'Actual creation accepted by the original desktop, local detach survives a transparently forwarded detach acknowledgement loss, diagnostic is persisted only on the original row, late actual submit return preserves a new draft, explicit original receipt GET confirms with no replay. USB loopback, not a network-path proof.',
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
  rejectedReads: [],
  rejectedPosts: [],
  injectedReadFaults: [],
  cleanupReadCancellations: [],
  ownedPorts: [],
  newRealModelInputsConfirmed: preflightOnly ? 0 : null,
  nativePhase: 0,
  threadIds: {},
  actualSubmitResponses: {},
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android independent creation isolation read-only witness',
);
assert(process.env.CODEX_EXECUTABLE);
let service, proxy, control, token, endpoint, fixture;
const reverses = [],
  upstreams = new Set(),
  claims = new Map(),
  submitted = new Map(),
  methods = new Set();
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
    await new Promise((done) => setTimeout(done, 400));
  } while (Date.now() < until);
  throw Error(label + ': ' + (last?.message ?? 'unavailable'));
};
async function bindOriginal(index) {
  const entry = submitted.get(index);
  assert(entry);
  const original = JSON.parse(
    await readFile(join(state, 'desktop-creations/creations.json'), 'utf8'),
  )[entry.operationId];
  const local = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
  assert.equal(local.hostId, hostId);
  assert(
    original &&
      original.clientOperationId === entry.operationId &&
      original.text === cases[index].text &&
      original.projectId === projectId,
  );
  assert.equal(await realpath(original.directory), projectRoot);
  assert.match(original.threadId ?? '', /^[a-f0-9-]{36}$/);
  assert(!original.existingThreadIds.includes(original.threadId));
  if (report.threadIds[index]) assert.equal(report.threadIds[index], original.threadId);
  report.threadIds[index] = original.threadId;
  if (Object.keys(report.threadIds).length === 2)
    assert.notEqual(report.threadIds[0], report.threadIds[1]);
  return original.threadId;
}
async function owner(index) {
  const id = await bindOriginal(index);
  await witness.ipc.follow(id, 5000, true);
  const value = witness.ipc.state(id);
  assert(value && value.id === id && value.hostId === 'local' && witness.ipc.hasOwner(id));
  assert.equal(await realpath(value.cwd), projectRoot);
  return value;
}
async function audit(expected, completed, name) {
  const accepted = [];
  let canonicalCount = 0;
  for (const [index, entry] of submitted) {
    const value = await wait(async () => {
      const snapshot = await owner(index),
        turns = desktopTurns(snapshot);
      return turns.length === 1 && (!completed || turns[0].status === 'completed')
        ? snapshot
        : null;
    }, 'Exact independent creation outcome');
    const turns = desktopTurns(value),
      turn = turns[0],
      clientId = 'first-' + entry.operationId;
    assert.equal(value.requests.length, 0);
    assert.equal(turn.items.filter((i) => i.type === 'userMessage').length, 1);
    assert(turn.items.some((i) => i.type === 'userMessage' && i.clientId === clientId));
    assert.equal(turn.params.collaborationMode.mode, 'default');
    assert.equal(turn.params.model ?? turn.params.collaborationMode.settings.model, fixture.model);
    assert.equal(
      turn.params.effort ?? turn.params.collaborationMode.settings.reasoning_effort,
      'xhigh',
    );
    assert.equal(turn.params.serviceTier, 'priority');
    assert.equal(turn.params.sandboxPolicy.type, 'readOnly');
    assert.equal(turn.params.approvalPolicy, 'on-request');
    assert(
      !turn.items.some((i) =>
        ['commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall'].includes(i.type),
      ),
    );
    if (completed)
      assert(
        turn.items.some(
          (i) =>
            i.type === 'agentMessage' &&
            i.phase === 'final_answer' &&
            i.text.trim() === cases[index].marker,
        ),
      );
    const canonical = await witness.reader.read('thread/read', {
      threadId: report.threadIds[index],
      includeTurns: true,
    });
    const count = canonical.thread.turns
      .flatMap((t) => t.items ?? [])
      .filter((i) => i.type === 'userMessage').length;
    assert.equal(count, 1);
    canonicalCount += count;
    accepted.push({
      index,
      operationId: entry.operationId,
      clientId,
      threadId: report.threadIds[index],
      turnId: turn.turnId,
      status: turn.status,
    });
    await writeFile(
      join(root, name + '-owner-' + index + '.json'),
      JSON.stringify(value, null, 2),
      { mode: 0o600 },
    );
  }
  assert.equal(accepted.length, expected);
  assert.equal(canonicalCount, expected);
  report.consumedOriginalInputs = accepted;
  report.newRealModelInputsConfirmed = expected;
  report.canonicalInputs = canonicalCount;
  await save();
}
function verifyRecord(record, index) {
  const entry = submitted.get(index);
  assert(
    entry &&
      record.id === entry.operationId &&
      record.submissionAttempted &&
      sha(record.intent) === record.intentSha256,
  );
  assert.deepEqual(JSON.parse(record.intent), entry.body);
}
async function originalReceipt(index) {
  const id = submitted.get(index).operationId;
  const url = new URL('/v1/desktop/creation-receipts', endpoint);
  url.searchParams.set('hostId', hostId);
  url.searchParams.set('id', id);
  const response = await fetch(url, {
    headers: { Authorization: 'Bearer ' + token },
    redirect: 'error',
    signal: AbortSignal.timeout(30000),
  });
  assert.equal(response.status, 200);
  const value = await response.json();
  assert.equal(value.hostId, hostId);
  assert.equal(value.clientOperationId, id);
  assert.equal(value.phase, 'confirmed');
  assert.equal(value.threadId, report.threadIds[index]);
  report.directReadOnlyReceipts ??= [];
  report.directReadOnlyReceipts.push(value);
  await save();
  return value;
}
async function startOwnedHost() {
  const child = spawn(process.execPath, ['apps/host-agent/dist/runtime-entry.js'], {
    cwd: process.cwd(),
    env: { ...process.env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const index = report.ownedPorts.filter((p) => p.label === 'host').length;
  service = { process: child, index, logs: [] };
  const instance = service;
  const lines = createInterface({ input: child.stdout });
  const ready = await new Promise((done, reject) => {
    let settled = false;
    const timeout = setTimeout(() => reject(Error('Owned Host readiness deadline')), 30000);
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      clearTimeout(timeout);
      if (!settled) reject(Error('Owned Host exited before readiness: ' + code + '/' + signal));
    });
    child.stderr.on('data', (bytes) => instance.logs.push(clean(bytes.toString())));
    lines.on('line', (line) => {
      instance.logs.push(clean(line));
      try {
        const value = JSON.parse(line);
        if (
          value.service === 'host-agent' &&
          value.ready === true &&
          value.mode === 'desktop-runtime'
        ) {
          settled = true;
          clearTimeout(timeout);
          done(value);
        }
      } catch {
        /* Only the fixed readiness JSON selects the owned endpoint. */
      }
    });
  });
  assert.equal(ready.address.address, '127.0.0.1');
  assert.equal(ready.tokenFile, join(state, 'access-token'));
  assert(Number.isSafeInteger(ready.address.port) && ready.address.port > 0);
  service.port = ready.address.port;
  const currentToken = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  if (token) assert.equal(currentToken, token);
  else token = currentToken;
  endpoint = 'http://127.0.0.1:' + service.port;
  report.ownedPorts.push({ label: 'host', port: service.port, pid: child.pid });
  await save();
}
async function stopOwnedHost() {
  if (!service) return;
  const instance = service,
    child = instance.process;
  if (child.exitCode === null && child.signalCode === null) {
    await new Promise((done, reject) => {
      const timeout = setTimeout(
        () => reject(Error('Owned Host did not exit after SIGTERM')),
        15000,
      );
      child.once('exit', (code, signal) => {
        clearTimeout(timeout);
        instance.exit = { code, signal };
        done();
      });
      child.kill('SIGTERM');
    });
  }
  await writeFile(join(root, 'host-process-' + instance.index + '.log'), instance.logs.join('\n'), {
    mode: 0o600,
  });
  report.hostProcessExits ??= [];
  report.hostProcessExits.push({
    pid: child.pid,
    port: instance.port,
    code: child.exitCode,
    signal: child.signalCode,
  });
  assert(child.exitCode === 0 || child.signalCode === 'SIGTERM');
}
async function hostRead(path) {
  assert(['/v1/workspace', '/v1/models'].includes(path));
  const response = await fetch(endpoint + path + '?hostId=' + encodeURIComponent(hostId), {
    headers: { Authorization: 'Bearer ' + token },
    redirect: 'error',
    signal: AbortSignal.timeout(30000),
  });
  assert.equal(response.status, 200);
  return response.json();
}
try {
  await witness.connect();
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
    HOST_AGENT_NAME: 'Android creation isolation DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  await startOwnedHost();
  const workspace = await hostRead('/v1/workspace'),
    project = workspace.projects.find((p) => p.id === projectId);
  assert.equal(workspace.hostId, hostId);
  assert(
    project &&
      (await Promise.all(project.roots.map((path) => realpath(path)))).includes(projectRoot),
  );
  assert(workspace.capabilities.includes('desktop:creations'));
  const model = (await hostRead('/v1/models')).models.find((m) => m.isDefault);
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
  const fencedGets = new Set([
    '/v1/desktop/creation-receipts',
    '/v1/desktop/creation-status',
    '/v1/desktop/creations',
  ]);
  const fenced = (url) =>
    submitted.has(0) &&
    !report.allowOriginalQuery &&
    fencedGets.has(url.pathname) &&
    url.searchParams.get('id') === submitted.get(0).operationId;
  const posts = new Set([
    '/v1/desktop/creations',
    '/v1/desktop/creations/submit',
    '/v1/desktop/creations/detach',
  ]);
  proxy = createServer((req, res) => {
    const entry = {
      controller: new AbortController(),
      method: req.method,
      faultAbort: false,
      cleanupAbort: false,
    };
    upstreams.add(entry);
    void (async () => {
      assert(!req.headers.origin && req.headers.authorization === 'Bearer ' + token);
      const url = new URL(req.url, endpoint);
      assert(
        req.method === 'GET'
          ? gets.has(url.pathname)
          : req.method === 'POST' && posts.has(url.pathname),
      );
      if (req.method === 'GET' && fenced(url)) {
        report.injectedReadFaults.push({ path: url.pathname, kind: 'confirmation-fence-503' });
        return json(res, { error: 'Owned first creation observation fence' }, 503);
      }
      let bytes;
      if (req.method === 'POST') {
        assert(!preflightOnly, 'Preflight cannot send any write');
        const value = await body(req);
        assert.equal(value.hostId, hostId);
        const operationId = value.clientOperationId;
        assert.match(operationId ?? '', /^[a-f0-9-]{36}$/);
        const key = url.pathname + ':' + operationId;
        assert(!methods.has(key), 'No original write replay');
        methods.add(key);
        if (url.pathname === '/v1/desktop/creations') {
          const index = report.activeInput;
          assert(claims.has(index) && !submitted.has(index));
          assert.equal(value.text, cases[index].text);
          assert.equal(value.projectId, projectId);
          assert.deepEqual(value.attachments, []);
          assert.equal(value.model, model.id);
          assert.equal(value.effort, 'xhigh');
          assert.equal(value.speed, 'fast');
          assert.equal(value.permissionMode, 'read-only');
          assert.equal(value.collaborationMode, 'default');
          submitted.set(index, { operationId, body: value });
        } else {
          const pair = [...submitted].find(([, entry]) => entry.operationId === operationId);
          assert(pair);
          if (url.pathname === '/v1/desktop/creations/detach') {
            assert.equal(pair[0], 0);
            assert(report.firstPending);
            assert.equal(value.acknowledgeUncertain, true);
          } else assert.equal(url.pathname, '/v1/desktop/creations/submit');
        }
        report.posts.push({
          path: url.pathname,
          index: report.activeInput,
          operationId: value.clientMessageId ?? value.clientOperationId,
          bodySha256: sha(JSON.stringify(value)),
          nativePhase: report.nativePhase,
        });
        await save();
        bytes = JSON.stringify(value);
      }
      const timeout = setTimeout(
        () => entry.controller.abort(new Error('upstream-deadline')),
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
        if (req.method === 'POST' && url.pathname === '/v1/desktop/creations/submit') {
          assert(response.ok);
          const result = JSON.parse(data.toString());
          const pair = [...submitted].find(
            ([, value]) => value.operationId === result.clientOperationId,
          );
          assert(pair);
          report.actualSubmitResponses[pair[0]] = result;
          await save();
        }
        if (req.method === 'POST' && url.pathname === '/v1/desktop/creations/detach') {
          assert(response.ok);
          report.actualDetachResponse = JSON.parse(data.toString());
          report.detachAcknowledgementLost = true;
          await save();
          return json(res, { error: 'Owned detach acknowledgement lost ' + nonce }, 503);
        }
        if (req.method === 'GET' && fenced(url)) {
          report.injectedReadFaults.push({ path: url.pathname, kind: 'late-read-fenced-503' });
          return json(res, { error: 'Owned first creation observation fence' }, 503);
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
        else if (entry.faultAbort && req.method === 'GET')
          report.injectedReadFaults.push({ path: req.url, kind: 'inflight-read-fenced', error });
        else
          (req.method === 'POST' ? report.rejectedPosts : report.rejectedReads).push({
            path: req.url,
            error,
          });
        void save();
        if (!res.destroyed && !res.headersSent) json(res, { error }, entry.faultAbort ? 503 : 500);
        else res.destroy();
      })
      .finally(() => upstreams.delete(entry));
  });
  const proxyPort = await listen(proxy);
  report.ownedPorts.push({ label: 'proxy', port: proxyPort });
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
        assert(index === 0 && claims.size === 0 && !claims.has(index));
        assert.equal(value.text, cases[index].text);
        assert.equal(value.threadId, '');
        claims.set(index, value);
        report.claims.push(value);
        report.activeInput = index;
        await save();
        return json(res, { ready: true });
      }
      if (req.url === '/first-pending') {
        assert(!report.firstPending && submitted.size === 1);
        verifyRecord(value.record, 0);
        const actual = report.actualSubmitResponses[0];
        assert(actual);
        for (const field of [
          'hostId',
          'clientOperationId',
          'projectId',
          'phase',
          'threadId',
          'error',
          'detached',
        ])
          assert.deepEqual(
            value.actualReturn[field] ?? null,
            actual[field] ?? (field === 'detached' ? false : null),
          );
        assert.deepEqual(value.actualReturn.uploaded, actual.uploaded ?? []);
        report.firstActualReturn = value.actualReturn;
        report.firstPendingRecord = value.record;
        await audit(1, true, 'first-pending');
        await originalReceipt(0);
        report.firstPending = true;
        await save();
        return json(res, { ok: true });
      }
      assert.equal(value.threadId, '');
      verifyRecord(value.record, 0);
      assert(value.record.detached);
      if (req.url === '/detached-failure') {
        assert(
          report.firstPending &&
            !report.detachedFailure &&
            submitted.size === 1 &&
            report.detachAcknowledgementLost,
        );
        assert(value.record.error?.includes('Owned detach acknowledgement lost ' + nonce));
        assert.equal(report.actualDetachResponse.phase, 'confirmed');
        assert.equal(report.actualDetachResponse.detached, true);
        assert.equal(report.actualDetachResponse.threadId, report.threadIds[0]);
        await audit(1, true, 'detach-failure');
        report.detachedFailure = true;
        report.failedNativeRecord = value.record;
        await save();
        return json(res, { releaseOriginalReturn: true });
      }
      if (req.url === '/query-ready') {
        assert(report.detachedFailure && !report.allowOriginalQuery);
        assert.equal(value.record.error, report.failedNativeRecord.error);
        report.lateNativeRecord = value.record;
        report.allowOriginalQuery = true;
        await save();
        return json(res, { queryOnly: true });
      }
      assert.equal(req.url, '/case-complete');
      assert(report.allowOriginalQuery && !report.caseComplete);
      assert.equal(value.record.phase, 'confirmed');
      assert.equal(value.record.error, null);
      assert.equal(JSON.parse(value.record.result).threadId, report.threadIds[0]);
      await audit(1, true, 'original-query-complete');
      report.finalNativeRecord = value.record;
      report.caseComplete = true;
      await save();
      return json(res, { ok: true });
    })().catch((e) => {
      report.witnessError = clean(e.stack);
      void save();
      json(res, { error: clean(e.message) }, 500);
    });
  });
  const controlPort = await listen(control);
  report.ownedPorts.push({ label: 'control', port: controlPort });
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
    cases,
    detachErrorMarker: 'Owned detach acknowledgement lost ' + nonce,
    independentDraft: 'Android 原结束等待后的独立草稿 ' + nonce,
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
  for (const phase of [0]) {
    report.nativePhase = phase;
    await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-creation-detach-config.json'",
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
          'com.agenthub.android.MobileCreationDetachModelLiveTest#originalCreationDetachFailurePersistsAndOnlyReadsItsAcceptedReceipt',
          pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
        ],
        { encoding: 'utf8', timeout: 650000, maxBuffer: 16 * 1024 * 1024 },
      );
    } catch (e) {
      await writeFile(
        join(root, `phase-${phase}-command-failure.json`),
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
    await writeFile(join(root, `phase-${phase}-instrumentation.log`), log, { mode: 0o600 });
    assert(
      /OK \(1 test\)/.test(log) &&
        !/FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed/.test(log),
    );
    report.native.push({ phase, status: 'passed', logSha256: sha(log) });
    await save();
  }
  assert.equal(await fingerprint(), report.sourceSha256);
  assert.equal(report.rejectedPosts.length, 0);
  assert.equal(report.rejectedReads.length, 0);
  if (preflightOnly)
    assert(report.preflightNative && report.posts.length === 0 && submitted.size === 0);
  else {
    assert(
      report.caseComplete &&
        report.detachedFailure &&
        report.detachAcknowledgementLost &&
        report.allowOriginalQuery,
    );
    assert.equal(report.posts.length, 3);
    assert(report.posts.every((p) => p.nativePhase === 0));
    assert.equal(report.newRealModelInputsConfirmed, 1);
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  if (submitted.size) {
    try {
      await audit(submitted.size, false, 'final-read-only');
      report.finalCanonicalInputs = report.canonicalInputs;
    } catch (e) {
      report.finalReadOnlyAuditError = clean(e.message);
    }
  }
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
          'files/acceptance/P4-creation-detach/' + nonce + '/' + fixture.evidenceRun,
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
  try {
    await stopOwnedHost();
  } catch (e) {
    report.hostProcessCloseError = clean(e.message);
    if (report.status === 'passed') {
      report.status = 'failed';
      process.exitCode = 1;
    }
  }
  if (
    (report.rejectedPosts.length ||
      report.rejectedReads.length ||
      report.finalReadOnlyAuditError) &&
    report.status === 'passed'
  ) {
    report.status = 'failed';
    report.error = 'Original scope audit failed; preserve all inputs without replay';
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
      error: report.error,
    }),
  );
}
