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

const preflightOnly = process.env.ANDROID_OPERATION_RECOVERY_PREFLIGHT_ONLY === '1';
assert(preflightOnly || process.env.ANDROID_OPERATION_RECOVERY_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const projectId = process.env.ANDROID_OPERATION_RECOVERY_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_OPERATION_RECOVERY_PROJECT_ROOT ?? '');
const output = process.env.MOBILE_OPERATION_RECOVERY_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
const root = join(output, 'operation-recovery-live-' + Date.now());
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
const cases = ['ORIGIN', 'REPLY'].map((name, index) => {
  const marker = 'ANDROID_P4_RECOVERY_' + nonce + '_' + name;
  return {
    index,
    marker,
    text: `安卓原操作恢复专用验收。最终仅原样回复${marker}。不调用工具、不读写文件、不访问网络、不联系其他聊天或Agent。`,
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
      'scripts/mobile/android-operation-recovery-live.mjs',
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
  phase: 'P4-4g.1',
  scope:
    'Actual original Electron/model/native phone under an explicit proxy confirmation loss; detach, persist a new draft, restart only the owned app and isolated Host, query original receipt with zero replay. USB loopback, not network-path proof.',
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
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android original operation recovery read-only witness',
);
assert(process.env.CODEX_EXECUTABLE);
let service,
  proxy,
  control,
  token,
  endpoint,
  fixture,
  faultActive = false;
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
const idFor = (index, entry) => (index === 0 ? 'first-' + entry.operationId : entry.operationId);
async function owner() {
  await witness.ipc.follow(report.threadId, 5000, true);
  const value = witness.ipc.state(report.threadId);
  assert(
    value &&
      value.id === report.threadId &&
      value.hostId === 'local' &&
      witness.ipc.hasOwner(report.threadId),
  );
  assert.equal(await realpath(value.cwd), projectRoot);
  return value;
}
async function audit(expected, completed, name) {
  const value = await wait(async () => {
    const snapshot = await owner(),
      turns = desktopTurns(snapshot);
    return turns.length === expected && (!completed || turns.every((t) => t.status === 'completed'))
      ? snapshot
      : null;
  }, 'Exact original input outcomes');
  const turns = desktopTurns(value),
    accepted = [];
  assert.equal(value.requests.length, 0);
  for (const [index, entry] of submitted) {
    const clientId = idFor(index, entry),
      found = turns.filter((t) =>
        t.items?.some((i) => i.type === 'userMessage' && i.clientId === clientId),
      );
    assert.equal(found.length, 1);
    const turn = found[0];
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
    accepted.push({
      index,
      operationId: entry.operationId,
      clientId,
      turnId: turn.turnId,
      status: turn.status,
    });
  }
  const canonical = await witness.reader.read('thread/read', {
    threadId: report.threadId,
    includeTurns: true,
  });
  const count = canonical.thread.turns
    .flatMap((t) => t.items ?? [])
    .filter((i) => i.type === 'userMessage').length;
  assert.equal(count, expected);
  assert.equal(accepted.length, expected);
  report.consumedOriginalInputs = accepted;
  report.newRealModelInputsConfirmed = expected;
  report.canonicalInputs = count;
  await writeFile(join(root, name + '-original-owner.json'), JSON.stringify(value, null, 2), {
    mode: 0o600,
  });
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
    HOST_AGENT_NAME: 'Android operation recovery DEV',
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
    '/v1/history',
    '/v1/events',
    '/v1/receipts',
    '/v1/replies',
    '/v1/replies/status',
    '/v1/commands/status',
  ]);
  const posts = new Set(['/v1/desktop/creations', '/v1/desktop/creations/submit', '/v1/replies']);
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
      if (req.method === 'GET' && faultActive && fencedGets.has(url.pathname)) {
        report.injectedReadFaults.push({ path: url.pathname, kind: 'confirmation-fence-503' });
        return json(res, { error: 'Owned recovery confirmation fence' }, 503);
      }
      let bytes;
      if (req.method === 'POST') {
        assert(
          !preflightOnly && report.nativePhase === 0,
          'Restoration and queries cannot send any write',
        );
        const value = await body(req);
        assert.equal(value.hostId, hostId);
        const key = url.pathname + ':' + (value.clientMessageId ?? value.clientOperationId);
        assert(!methods.has(key), 'No original write replay');
        methods.add(key);
        if (Object.hasOwn(value, 'text')) {
          const index = report.activeInput;
          assert(claims.has(index) && !submitted.has(index));
          assert.equal(value.text, cases[index].text);
          assert.equal(value.mode ?? 'next-turn', 'next-turn');
          assert.equal(value.model, model.id);
          assert.equal(value.effort, 'xhigh');
          assert.equal(value.speed, 'fast');
          assert.equal(value.permissionMode, 'read-only');
          assert.equal(value.collaborationMode, 'default');
          if (index === 0) {
            assert.equal(url.pathname, '/v1/desktop/creations');
            assert.equal(value.projectId, projectId);
            assert.deepEqual(value.attachments, []);
          } else {
            assert.equal(index, 1);
            assert.equal(url.pathname, '/v1/replies');
            assert.equal(value.threadId, report.threadId);
            assert.deepEqual(value.attachmentIds, []);
          }
          submitted.set(index, {
            operationId: value.clientMessageId ?? value.clientOperationId,
            body: value,
          });
          if (index === 1) {
            faultActive = true;
            for (const pending of upstreams)
              if (pending.method === 'GET') {
                pending.faultAbort = true;
                pending.controller.abort(new Error('owned-confirmation-fence'));
              }
          }
        } else {
          assert.equal(url.pathname, '/v1/desktop/creations/submit');
          assert.equal(value.clientOperationId, submitted.get(0)?.operationId);
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
        if (req.method === 'POST' && url.pathname === '/v1/replies') {
          assert(response.ok);
          report.originalReplyResponseDropped = true;
          report.originalReplyResponseSha256 = sha(data);
          await save();
          json(
            res,
            { error: 'Owned proxy lost confirmation after forwarding the original input' },
            504,
          );
          return;
        }
        if (req.method === 'GET' && faultActive && fencedGets.has(url.pathname)) {
          report.injectedReadFaults.push({ path: url.pathname, kind: 'late-read-fenced-503' });
          return json(res, { error: 'Owned recovery confirmation fence' }, 503);
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
        assert(
          report.nativePhase === 0 &&
            index === claims.size &&
            [0, 1].includes(index) &&
            !claims.has(index),
        );
        assert.equal(value.text, cases[index].text);
        if (index === 0) assert.equal(value.threadId, '');
        else {
          assert(report.originComplete);
          assert.equal(value.threadId, report.threadId);
        }
        claims.set(index, value);
        report.claims.push(value);
        report.activeInput = index;
        await save();
        return json(res, { ready: true });
      }
      if (req.url === '/origin-complete') {
        assert(!report.originComplete && submitted.size === 1);
        report.threadId = value.threadId;
        verifyRecord(value.record, 0);
        await audit(1, true, 'origin');
        report.originComplete = true;
        await save();
        return json(res, { ok: true });
      }
      assert.equal(value.threadId, report.threadId);
      verifyRecord(value.record, 1);
      assert(value.record.detached);
      assert(report.originalReplyResponseDropped);
      if (req.url === '/detached-complete') {
        assert(
          report.nativePhase === 0 &&
            !report.detachedComplete &&
            value.record.phase === 'uncertain',
        );
        await audit(2, true, 'detached');
        report.detachedRecord = value.record;
        report.detachedComplete = true;
        await writeFile(
          join(root, 'native-detached-record.json'),
          JSON.stringify(value.record, null, 2),
          { mode: 0o600 },
        );
        await save();
        return json(res, { ok: true });
      }
      assert.equal(req.url, '/recovery-complete');
      assert(report.nativePhase === 1 && report.hostRestart && report.detachedComplete);
      assert.equal(value.record.phase, 'completed');
      assert.equal(value.record.intentSha256, report.detachedRecord.intentSha256);
      assert.equal(value.record.intent, report.detachedRecord.intent);
      await audit(2, true, 'recovered');
      report.recoveryComplete = true;
      await writeFile(
        join(root, 'native-recovered-record.json'),
        JSON.stringify(value.record, null, 2),
        { mode: 0o600 },
      );
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
    independentDraft: 'Android 原操作结束等待后的独立草稿 ' + nonce,
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
  for (const phase of preflightOnly ? [0] : [0, 1]) {
    report.nativePhase = phase;
    if (phase === 1) {
      assert(report.detachedComplete && report.newRealModelInputsConfirmed === 2);
      await android('shell', 'am', 'force-stop', pkg);
      const oldHostPort = service.port,
        oldHostPid = service.process.pid,
        tokenSha256 = sha(token);
      await stopOwnedHost();
      await startOwnedHost();
      assert.notEqual(service.process.pid, oldHostPid);
      assert.equal(sha(token), tokenSha256);
      const restored = await hostRead('/v1/workspace');
      assert.equal(restored.hostId, hostId);
      report.hostRestart = {
        oldHostPort,
        newHostPort: service.port,
        oldHostPid,
        newHostPid: service.process.pid,
        hostIdPreserved: true,
        tokenSha256,
        newInputsBeforeRestart: report.newRealModelInputsConfirmed,
      };
      fixture.threadId = report.threadId;
      fixture.operationId = report.detachedRecord.id;
      fixture.originalIntentSha256 = report.detachedRecord.intentSha256;
      faultActive = false;
      await save();
    }
    await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-operation-recovery-config.json'",
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
          'com.agenthub.android.MobileOperationRecoveryModelLiveTest#' +
            (phase === 0
              ? 'submittedUnknownReplyDetachesWithANewDraft'
              : 'sameOriginalRecordRecoversAfterAppAndHostRestart'),
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
    assert(report.recoveryComplete && report.hostRestart && report.detachedComplete);
    assert.equal(report.posts.length, 3);
    assert(report.posts.every((p) => p.nativePhase === 0));
    assert.equal(report.newRealModelInputsConfirmed, 2);
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  if (!report.threadId && submitted.has(0)) {
    try {
      const original = JSON.parse(
        await readFile(join(state, 'desktop-creations/creations.json'), 'utf8'),
      )[submitted.get(0).operationId];
      const localState = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
      assert.equal(localState.hostId, hostId);
      assert(
        original &&
          original.clientOperationId === submitted.get(0).operationId &&
          original.text === cases[0].text &&
          original.projectId === projectId,
      );
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
      const value = await owner(),
        turns = desktopTurns(value),
        accepted = [];
      for (const [index, entry] of submitted) {
        const clientId = idFor(index, entry),
          found = turns.filter((t) =>
            t.items?.some((i) => i.type === 'userMessage' && i.clientId === clientId),
          );
        assert(found.length <= 1);
        if (found.length)
          accepted.push({
            index,
            operationId: entry.operationId,
            clientId,
            turnId: found[0].turnId,
          });
      }
      report.consumedOriginalInputs = accepted;
      report.newRealModelInputsConfirmed = accepted.length;
      const canonical = await witness.reader.read('thread/read', {
        threadId: report.threadId,
        includeTurns: true,
      });
      report.finalCanonicalInputs = canonical.thread.turns
        .flatMap((t) => t.items ?? [])
        .filter((i) => i.type === 'userMessage').length;
      assert.equal(report.finalCanonicalInputs, accepted.length);
      await writeFile(join(root, 'final-original-owner.json'), JSON.stringify(value, null, 2), {
        mode: 0o600,
      });
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
          'files/acceptance/P4-operation-recovery/' + nonce + '/' + fixture.evidenceRun,
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
