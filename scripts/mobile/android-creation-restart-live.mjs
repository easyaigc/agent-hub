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

const preflightOnly = process.env.ANDROID_CREATION_RESTART_PREFLIGHT_ONLY === '1';
assert(preflightOnly || process.env.ANDROID_CREATION_RESTART_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const projectId = process.env.ANDROID_CREATION_RESTART_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_CREATION_RESTART_PROJECT_ROOT ?? '');
const output = process.env.MOBILE_CREATION_RESTART_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
const root = join(output, 'creation-restart-live-' + Date.now());
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
  const marker = 'ANDROID_P4_CREATION_RESTART_' + nonce + '_' + name;
  return {
    index,
    marker,
    text: `安卓原新建权威前置恢复专用验收。最终仅原样回复${marker}。不调用工具、不读写文件、不访问网络、不联系其他聊天或Agent。`,
  };
});
await writeFile(
  join(root, 'immutable-cases.json'),
  JSON.stringify({ nonce, hostId, projectId, projectRoot, cases }, null, 2),
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
      'scripts/mobile/android-creation-restart-live.mjs',
      'scripts/mobile/android-creation-restart-host.mjs',
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
  phase: 'P4-4g.7',
  scope:
    'Fresh original creation generation-preflight fault in an isolated Host child. Production boundary must attest not-written; GET observations cannot continue it. Native default encrypted restore across its own app and Host process restart must recover the original receipt and independent next draft/file/settings with GET only; only a later explicit same-ID original continuation may accept one input. Local API import is not a system picker proof. USB loopback, not a network-path proof.',
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
  methods = new Map();
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
async function ownedPreparation() {
  const entry = submitted.get(0);
  assert(entry);
  const broker = JSON.parse(
    await readFile(join(state, 'desktop-creations/creations.json'), 'utf8'),
  )[entry.operationId];
  assert(
    broker &&
      broker.clientOperationId === entry.operationId &&
      broker.text === cases[0].text &&
      broker.projectId === projectId,
  );
  assert.equal(await realpath(broker.directory), projectRoot);
  assert.equal(JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8')).hostId, hostId);
  const prep = JSON.parse(
    await readFile(join(state, 'desktop-prepared-bridge', entry.operationId + '.json'), 'utf8'),
  );
  assert.equal(prep.hostId, hostId);
  assert.equal(prep.clientOperationId, entry.operationId);
  assert.equal(await realpath(prep.cwd), projectRoot);
  assert.match(prep.threadId ?? '', /^[a-f0-9-]{36}$/);
  assert(!broker.existingThreadIds.includes(prep.threadId));
  if (report.threadIds[0]) assert.equal(report.threadIds[0], prep.threadId);
  report.threadIds[0] = prep.threadId;
  return prep;
}
async function faultTrace() {
  const value = JSON.parse(await readFile(join(state, 'controlled-generation-fault.json'), 'utf8'));
  assert.equal(value.pid, service.process.pid);
  assert.equal(value.nonce, nonce);
  assert.equal(value.hostId, hostId);
  assert(value.calls.length >= 1);
  assert.equal(value.calls[0].kind, 'controlled-preflight-fault');
  const prep = await ownedPreparation();
  for (const call of value.calls) {
    assert.equal(
      call.writerPid,
      call.kind === 'controlled-preflight-fault'
        ? report.ownedPorts.find((p) => p.label === 'host').pid
        : service.process.pid,
    );
    assert(
      ['controlled-preflight-fault', 'delegate-production-validation'].includes(call.kind),
      'Unexpected generation validation before explicit native claim',
    );
    assert.equal(call.clientMessageId, 'first-' + submitted.get(0).operationId);
    assert.equal(call.threadId, prep.threadId);
    assert.equal(call.textSha256, sha(cases[0].text));
  }
  assert.equal(value.calls.filter((c) => c.kind === 'controlled-preflight-fault').length, 1);
  return value;
}
async function zeroAuthority() {
  const prep = await ownedPreparation(),
    trace = await faultTrace();
  assert.equal(prep.phase, 'input-retryable');
  assert.equal(prep.exitCode, 0);
  assert.equal(prep.cancelled ?? false, false);
  assert.equal(prep.inputBoundary?.state, 'not-written');
  const checkpoint = prep.inputBoundary.checkpoint;
  assert.equal(checkpoint.version, 1);
  assert.equal(checkpoint.hostId, hostId);
  assert.equal(checkpoint.threadId, prep.threadId);
  assert.equal(checkpoint.clientMessageId, 'first-' + submitted.get(0).operationId);
  assert.equal(checkpoint.preparationFingerprint, prep.fingerprint);
  const originHost = report.ownedPorts.find((p) => p.label === 'host');
  assert.equal(checkpoint.writerPid, originHost.pid);
  if (service.process.pid !== originHost.pid) {
    assert(
      report.hostRestart &&
        report.hostProcessExits.some(
          (e) => e.pid === originHost.pid && (e.code === 0 || e.signal === 'SIGTERM'),
        ),
    );
    assert.equal(report.hostRestart.newHostPid, service.process.pid);
    assert.equal(sha(JSON.stringify(checkpoint)), report.preRestartCheckpointSha256);
  }
  assert.match(checkpoint.replyFingerprint, /^[a-f0-9]{64}$/);
  assert.match(checkpoint.attemptId, /^[a-f0-9-]{36}$/);
  for (const [k, v] of Object.entries({
    agentId: 'codex',
    hostId,
    workspaceId: hostId,
    runtimeId: 'desktop-runtime',
  }))
    assert.equal(checkpoint.target[k], v);
  assert.equal(prep.diagnostic?.stage, 'generation');
  assert.equal(prep.diagnostic?.code, 'generation-preflight-failed');
  assert.equal(prep.diagnostic?.disposition, 'retry-original-preflight');
  assert.equal(
    trace.calls.length,
    1,
    'No generation validation may resume before explicit continuation',
  );
  report.authoritativeCheckpointSha256 = sha(JSON.stringify(checkpoint));
  return prep;
}
async function owner() {
  const prep = await ownedPreparation();
  await witness.ipc.follow(prep.threadId, 5000, true);
  const value = witness.ipc.state(prep.threadId);
  assert(
    value &&
      value.id === prep.threadId &&
      value.hostId === 'local' &&
      witness.ipc.hasOwner(prep.threadId),
  );
  assert.equal(await realpath(value.cwd), projectRoot);
  return value;
}
async function audit(expected, completed, name) {
  const value = await wait(async () => {
    const state = await owner(),
      turns = desktopTurns(state);
    return turns.length === expected && (!completed || turns.every((t) => t.status === 'completed'))
      ? state
      : null;
  }, 'Original creation canonical outcome');
  assert.equal(value.requests.length, 0);
  const turns = desktopTurns(value);
  const canonical = await witness.reader.read('thread/read', {
    threadId: report.threadIds[0],
    includeTurns: true,
  });
  assert.equal(canonical.thread.turns.length, expected);
  const count = canonical.thread.turns
    .flatMap((t) => t.items ?? [])
    .filter((i) => i.type === 'userMessage').length;
  assert.equal(count, expected);
  if (expected === 1) {
    const turn = turns[0],
      clientId = 'first-' + submitted.get(0).operationId;
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
            i.text.trim() === cases[0].marker,
        ),
      );
    report.consumedOriginalInputs = [
      {
        index: 0,
        operationId: submitted.get(0).operationId,
        clientId,
        threadId: report.threadIds[0],
        turnId: turn.turnId,
        status: turn.status,
      },
    ];
  } else {
    await zeroAuthority();
    report.consumedOriginalInputs = [];
  }
  report.newRealModelInputsConfirmed = expected;
  report.canonicalInputs = count;
  await writeFile(join(root, name + '-owner.json'), JSON.stringify(value, null, 2), {
    mode: 0o600,
  });
  await save();
}
function verifyRecord(record) {
  const entry = submitted.get(0);
  assert(
    entry &&
      record.id === entry.operationId &&
      record.submissionAttempted &&
      sha(record.intent) === record.intentSha256,
  );
  assert.deepEqual(JSON.parse(record.intent), entry.body);
  assert(record.sharingScope);
  assert.equal(record.sharingScope.model, fixture.model);
  for (const [k, v] of Object.entries({
    agentId: 'codex',
    hostId,
    workspaceId: hostId,
    runtimeId: 'desktop-runtime',
  }))
    assert.equal(record.sharingScope.target[k], v);
}
async function originalRead(path) {
  assert(['/v1/desktop/creation-receipts', '/v1/desktop/creations'].includes(path));
  const url = new URL(path, endpoint);
  url.searchParams.set('hostId', hostId);
  url.searchParams.set('id', submitted.get(0).operationId);
  const response = await fetch(url, {
    headers: { Authorization: 'Bearer ' + token },
    redirect: 'error',
    signal: AbortSignal.timeout(30000),
  });
  assert.equal(response.status, 200);
  const value = await response.json();
  assert.equal(value.hostId, hostId);
  assert.equal(value.clientOperationId, submitted.get(0).operationId);
  assert.equal(value.phase, 'outcome-unknown');
  assert.equal(value.preparation?.preparedThreadId, report.threadIds[0]);
  assert.equal(value.preparation?.preparationPhase, 'input-retryable');
  assert.equal(value.preparation?.diagnostic?.disposition, 'retry-original-preflight');
  report.directReadOnlyQueries ??= [];
  report.directReadOnlyQueries.push({ path, value });
  await save();
  return value;
}
async function startOwnedHost() {
  const child = spawn(process.execPath, ['scripts/mobile/android-creation-restart-host.mjs'], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      ANDROID_CREATION_RESTART_OWNED_ROOT: root,
      ANDROID_CREATION_RESTART_NONCE: nonce,
      ANDROID_CREATION_RESTART_HOST_START_INDEX: String(
        report.ownedPorts.filter((p) => p.label === 'host').length,
      ),
    },
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
  const posts = new Set(['/v1/desktop/creations', '/v1/desktop/creations/submit']);
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
      let bytes;
      if (req.method === 'POST') {
        assert(!preflightOnly, 'Preflight cannot send any write');
        const value = await body(req);
        assert.equal(value.hostId, hostId);
        const operationId = value.clientOperationId;
        assert.match(operationId ?? '', /^[a-f0-9-]{36}$/);
        const key = url.pathname + ':' + operationId,
          count = methods.get(key) ?? 0;
        if (url.pathname === '/v1/desktop/creations') {
          assert.equal(count, 0);
          assert(claims.has(0) && !submitted.has(0));
          assert.equal(value.text, cases[0].text);
          assert.equal(value.projectId, projectId);
          assert.deepEqual(value.attachments, []);
          assert.equal(value.model, model.id);
          assert.equal(value.effort, 'xhigh');
          assert.equal(value.speed, 'fast');
          assert.equal(value.permissionMode, 'read-only');
          assert.equal(value.collaborationMode, 'default');
          submitted.set(0, { operationId, body: value });
        } else {
          assert.equal(url.pathname, '/v1/desktop/creations/submit');
          assert.equal(operationId, submitted.get(0)?.operationId);
          assert(
            count === 0 ||
              (count === 1 && report.explicitContinuationClaim && !report.retrySubmitted),
            'Only one proven-not-written explicit original continuation',
          );
          if (count === 1) {
            report.retrySubmitted = true;
            report.newRealModelInputsConfirmed = null;
          }
        }
        methods.set(key, count + 1);
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
          report.submitResponses ??= [];
          report.submitResponses.push(JSON.parse(data.toString()));
          await save();
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
  const verifyLocalDraft = (value) => {
    assert.equal(value.localText, fixture.independentDraft);
    assert.equal(value.localFiles?.length, 1);
    const file = value.localFiles[0];
    assert.match(file.id ?? '', /^[a-f0-9-]{36}$/);
    assert.equal(file.name, fixture.nextFileName);
    assert.equal(file.mediaType, 'text/plain');
    assert.equal(file.sizeBytes, Buffer.byteLength(fixture.nextFileText));
    assert.equal(file.sha256, sha(Buffer.from(fixture.nextFileText)));
    assert.equal(value.localBytesSha256, file.sha256);
    assert.equal(value.generation.model, fixture.model);
    assert.equal(value.generation.effort, 'xhigh');
    assert.equal(value.generation.speed, 'fast');
    assert.equal(value.generation.permissionMode, 'read-only');
    assert.equal(value.generation.collaborationMode, 'default');
    if (fixture.sourceKey) assert.equal(value.sourceKey, fixture.sourceKey);
  };
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
        verifyLocalDraft(value);
        report.preflightNative = value;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/preflight-restored') {
        assert(
          preflightOnly &&
            report.preflightNative &&
            report.hostRestart &&
            report.posts.length === 0,
        );
        verifyLocalDraft(value);
        assert.notEqual(value.pid, report.preflightNative.pid);
        assert.deepEqual(value.localFiles, report.preflightNative.localFiles);
        assert.deepEqual(value.generation, report.preflightNative.generation);
        report.preflightRestored = value;
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
      assert.equal(value.threadId, '');
      verifyRecord(value.record);
      assert.equal(value.record.detached, false);
      if (req.url === '/not-written-observed') {
        assert(
          !report.notWrittenObserved &&
            !report.explicitContinuationClaim &&
            report.posts.length === 2,
        );
        const prep = await zeroAuthority();
        const native = JSON.parse(value.record.result);
        assert.equal(value.record.phase, 'outcome-unknown');
        assert.equal(native.preparation?.preparedThreadId, prep.threadId);
        assert.equal(native.preparation?.preparationPhase, 'input-retryable');
        assert.equal(native.preparation?.diagnostic?.disposition, 'retry-original-preflight');
        await audit(0, false, 'before-read-only-probes');
        for (const path of [
          '/v1/desktop/creation-receipts',
          '/v1/desktop/creations',
          '/v1/desktop/creation-receipts',
        ]) {
          await originalRead(path);
          await zeroAuthority();
        }
        await audit(0, false, 'after-read-only-probes');
        report.firstNativeRecord = value.record;
        report.notWrittenObserved = true;
        await save();
        return json(res, { zeroAcceptedInputsVerified: true });
      }
      assert.equal(value.record.intent, report.firstNativeRecord.intent);
      assert.equal(value.record.intentSha256, report.firstNativeRecord.intentSha256);
      assert.deepEqual(value.record.sharingScope, report.firstNativeRecord.sharingScope);
      if (req.url === '/restart-ready') {
        assert(
          report.notWrittenObserved &&
            !report.restartReadyNative &&
            report.posts.length === 2 &&
            report.nativePhase === 0,
        );
        verifyLocalDraft(value);
        await zeroAuthority();
        await audit(0, false, 'before-process-restart');
        report.restartReadyNative = value;
        report.preRestartCheckpointSha256 = report.authoritativeCheckpointSha256;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/restored-read-only') {
        assert(
          report.hostRestart &&
            !report.restoredReadOnly &&
            report.posts.length === 2 &&
            report.nativePhase === 1,
        );
        verifyLocalDraft(value);
        assert.notEqual(value.pid, report.restartReadyNative.pid);
        assert.deepEqual(value.localFiles, report.restartReadyNative.localFiles);
        assert.deepEqual(value.generation, report.restartReadyNative.generation);
        await originalRead('/v1/desktop/creation-receipts');
        await originalRead('/v1/desktop/creations');
        await zeroAuthority();
        await audit(0, false, 'after-process-restart-readonly');
        report.restoredReadOnly = value;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/claim-continuation') {
        assert(report.hostRestart && report.restoredReadOnly && report.nativePhase === 1);
        verifyLocalDraft(value);
        report.localDraftBeforeOriginal = value;
        assert(
          report.notWrittenObserved &&
            !report.explicitContinuationClaim &&
            report.posts.length === 2,
        );
        await zeroAuthority();
        await audit(0, false, 'explicit-native-claim');
        await writeFile(
          join(state, 'examiner-explicit-original-claim.json'),
          JSON.stringify({
            nonce,
            hostId,
            clientMessageId: 'first-' + submitted.get(0).operationId,
            threadId: report.threadIds[0],
            textSha256: sha(cases[0].text),
          }) + '\n',
          { mode: 0o600 },
        );
        report.explicitContinuationClaim = true;
        await save();
        return json(res, { allowExplicitOriginal: true });
      }
      assert.equal(req.url, '/case-complete');
      verifyLocalDraft(value);
      assert.deepEqual(value.localFiles, report.localDraftBeforeOriginal.localFiles);
      assert.equal(value.localText, report.localDraftBeforeOriginal.localText);
      assert.equal(value.localBytesSha256, report.localDraftBeforeOriginal.localBytesSha256);
      report.localDraftAfterOriginal = value;
      assert(report.retrySubmitted && !report.caseComplete);
      assert.equal(value.record.phase, 'confirmed');
      assert.equal(JSON.parse(value.record.result).threadId, report.threadIds[0]);
      const prep = await ownedPreparation();
      assert.equal(prep.phase, 'submitted');
      const trace = await faultTrace();
      assert(
        trace.calls.length >= 2 &&
          trace.calls.slice(1).every((c) => c.kind === 'delegate-production-validation'),
      );
      await audit(1, true, 'explicit-original-complete');
      report.finalNativeRecord = value.record;
      report.finalGenerationTrace = trace;
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
    independentDraft: 'Android 原前置恢复过程中的独立草稿 ' + nonce,
    nextFileName: 'next-draft-' + nonce + '.txt',
    nextFileText: 'Local next-draft bytes ' + nonce,
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
  for (const phase of [0, 1]) {
    report.nativePhase = phase;
    if (phase === 1) {
      const before = preflightOnly ? report.preflightNative : report.restartReadyNative;
      assert(
        before &&
          (preflightOnly
            ? report.posts.length === 0
            : report.notWrittenObserved &&
              report.posts.length === 2 &&
              report.newRealModelInputsConfirmed === 0),
      );
      await android('shell', 'am', 'force-stop', pkg);
      const oldHostPid = service.process.pid,
        oldHostPort = service.port,
        tokenSha256 = sha(token);
      await stopOwnedHost();
      await startOwnedHost();
      assert.notEqual(service.process.pid, oldHostPid);
      assert.equal(sha(token), tokenSha256);
      assert.equal((await hostRead('/v1/workspace')).hostId, hostId);
      report.hostRestart = {
        oldHostPid,
        newHostPid: service.process.pid,
        oldHostPort,
        newHostPort: service.port,
        hostIdPreserved: true,
        tokenSha256,
        newInputsBeforeRestart: report.newRealModelInputsConfirmed,
      };
      fixture.sourceKey = before.sourceKey;
      fixture.priorAppPid = before.pid;
      fixture.localFileId = before.localFiles[0].id;
      if (!preflightOnly) {
        fixture.operationId = before.record.id;
        fixture.originalIntentSha256 = before.record.intentSha256;
        await zeroAuthority();
      }
      await save();
    }
    await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-creation-restart-config.json'",
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
          'com.agenthub.android.MobileCreationRestartModelLiveTest#' +
            (phase === 0
              ? 'originalCreationSavesIndependentDraftBeforeRestart'
              : 'originalCreationRestoresReadOnlyBeforeExplicitContinuation'),
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
    assert(
      report.preflightNative &&
        report.preflightRestored &&
        report.hostRestart &&
        report.native.length === 2 &&
        report.posts.length === 0 &&
        submitted.size === 0,
    );
  else {
    assert(
      report.caseComplete &&
        report.hostRestart &&
        report.restoredReadOnly &&
        report.notWrittenObserved &&
        report.explicitContinuationClaim &&
        report.retrySubmitted,
    );
    assert.equal(report.posts.length, 3);
    assert.deepEqual(
      report.posts.map((p) => p.nativePhase),
      [0, 0, 1],
    );
    assert.equal(report.newRealModelInputsConfirmed, 1);
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  if (!preflightOnly) {
    try {
      const writes = report.posts.filter((p) => p.path === '/v1/desktop/creations/submit').length;
      if (writes === 0) {
        report.newRealModelInputsConfirmed = 0;
        report.finalCanonicalInputs = 0;
        report.noSubmissionEndpointEntered = true;
      } else {
        await ownedPreparation();
        const snapshot = await owner(),
          turns = desktopTurns(snapshot);
        const canonical = await witness.reader.read('thread/read', {
          threadId: report.threadIds[0],
          includeTurns: true,
        });
        const count = canonical.thread.turns
          .flatMap((t) => t.items ?? [])
          .filter((i) => i.type === 'userMessage').length;
        report.finalObservedCanonicalInputs = count;
        if (count === 1) await audit(1, false, 'final-read-only');
        else {
          assert.equal(count, 0);
          assert.equal(turns.length, 0);
          assert.equal(writes, 1);
          await zeroAuthority();
          await audit(0, false, 'final-proven-not-written');
        }
        report.finalCanonicalInputs = report.canonicalInputs;
      }
    } catch (e) {
      report.finalReadOnlyAuditError = clean(e.message);
      if (report.newRealModelInputsConfirmed !== 1) report.newRealModelInputsConfirmed = null;
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
          'files/acceptance/P4-creation-restart/' + nonce + '/' + fixture.evidenceRun,
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
