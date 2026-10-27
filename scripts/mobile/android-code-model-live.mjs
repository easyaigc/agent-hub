import assert from 'node:assert/strict';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { createHash, randomUUID, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath } from 'node:fs/promises';
import { join, resolve, isAbsolute } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';
assert.equal(process.platform, 'darwin');
const originalReport = process.env.ANDROID_CODE_ORIGINAL_REPORT;
const prior = originalReport ? JSON.parse(await readFile(originalReport, 'utf8')) : null;
if (!prior) assert.equal(process.env.ANDROID_CODE_ALLOW_NEW_INPUTS, '1');
if (prior) {
  assert.equal(prior.phase, 'P3-6c.2');
  assert.equal(prior.newRealModelInputsConfirmed, 1);
  assert(prior.nativeProof && prior.original && prior.posts.length === 2);
}
const serial = process.env.ANDROID_SERIAL,
  pkg = 'com.easyai.agenthub.android.acceptance';
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const output = process.env.ANDROID_CODE_OUTPUT,
  projectId = process.env.ANDROID_CODE_PROJECT_ID;
assert(isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
assert.match(projectId, /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_CODE_PROJECT_ROOT),
  nonce = randomUUID(),
  hostId = prior?.hostId ?? randomUUID(),
  controlKey = randomBytes(32).toString('base64url');
const root = join(output, 'code-model-' + Date.now() + '-' + nonce.slice(0, 8)),
  state = prior ? await realpath(join(originalReport, '..', 'host')) : join(root, 'host');
await mkdir(root, { recursive: true, mode: 0o700 });
await mkdir(state, { recursive: true, mode: 0o700 });
const execute = promisify(execFile),
  sha = (x) => createHash('sha256').update(x).digest('hex');
const adb = resolve(
  JSON.parse(await readFile('.local/mobile-toolchain.json')).androidSdk,
  'platform-tools/adb',
);
const android = async (...a) =>
  (
    await execute(adb, ['-s', serial, ...a], {
      encoding: 'utf8',
      timeout: 30000,
      maxBuffer: 16 * 1024 * 1024,
    })
  ).stdout.trim();
assert.equal(await android('get-state'), 'device');
assert((await android('shell', 'dumpsys', 'power')).includes('mWakefulness=Awake'));
const body =
  prior?.nativeProof.copied ??
  'MODEL_CODE_' +
    nonce.slice(0, 8) +
    ' original selectable words remain here\nval 中文 = "😀"\n\tprintln(中文)\n';
const expected = prior?.nativeProof.reply ?? '```kotlin\n' + body + '```';
const text =
  '安卓真实模型代码选择复制专用验收。请只输出以下完整内容，原样保留代码围栏、正文、制表符和换行，不加其他文字。不调用任何工具，不读写文件、不访问网络、不联系其他聊天或 Agent：\n' +
  expected;
await writeFile(
  join(root, 'immutable-input.json'),
  JSON.stringify({ nonce, hostId, projectId, projectRoot, text, expected, body }, null, 2),
  { mode: 0o600, flag: 'wx', flush: true },
);
const fingerprint = async () => {
  const h = createHash('sha256');
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
      'scripts/mobile/android-code-model-live.mjs',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .sort();
  for (const f of new Set(files))
    h.update(f + '\0')
      .update(await readFile(f))
      .update('\0');
  return h.digest('hex');
};
const report = {
  phase: 'P3-6c.2',
  status: 'running',
  nonce,
  hostId,
  projectId,
  projectRoot,
  serial,
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  ownedPorts: [],
  posts: [],
  rejected: [],
  newRealModelInputsConfirmed: 0,
  matchingOriginalModelInputs: prior ? 1 : 0,
  readCancellations: [],
  readOnly: !!prior,
  scope:
    'Fresh/explicit original-read phone scene. One fresh phone-created actual model code reply, SDK selection and exact whole-body copy. Original owner and canonical input audit; isolated native storage. Existing64 inputs untouched. USB loopback, not cellular/VPN or whole P3 proof.',
};
let service,
  token,
  endpoint,
  proxy,
  control,
  fixture,
  submitted = prior ? JSON.parse(prior.nativeProof.intent) : undefined,
  completed = false;
const reverses = [],
  active = new Set();
const clean = (x) =>
  String(x)
    .replaceAll(token ?? '[NO_TOKEN]', '[REDACTED]')
    .replaceAll(controlKey, '[REDACTED]');
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const json = (res, v, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(v));
};
const listen = async (s) => {
  await new Promise((ok, no) => {
    s.once('error', no);
    s.listen(0, '127.0.0.1', ok);
  });
  return s.address().port;
};
const bodyJson = async (req) => {
  let size = 0;
  const a = [];
  for await (const p of req) {
    size += p.length;
    assert(size < 150000);
    a.push(p);
  }
  return JSON.parse(Buffer.concat(a).toString());
};
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Actual model code owner read-only examiner',
);
assert(process.env.CODEX_EXECUTABLE);
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

async function direct(path) {
  const r = await fetch(endpoint + path, {
    headers: { Authorization: 'Bearer ' + token },
    redirect: 'error',
    signal: AbortSignal.timeout(30000),
  });
  assert(r.ok);
  return r.json();
}
async function audit(label) {
  if (!submitted) return;
  const receipt = await direct(
    '/v1/desktop/creation-receipts?' +
      new URLSearchParams({ hostId, id: submitted.clientOperationId }),
  );
  if (!receipt.threadId) {
    report.unresolvedOperationId = submitted.clientOperationId;
    await save();
    return;
  }
  const id = receipt.threadId;
  await witness.ipc.follow(id, 10000, true);
  const owner = witness.ipc.state(id);
  assert(owner && owner.id === id && owner.hostId === 'local' && witness.ipc.hasOwner(id));
  assert.equal(await realpath(owner.cwd), projectRoot);
  const turns = desktopTurns(owner);
  assert.equal(turns.length, 1);
  const t = turns[0],
    users = t.items.filter((x) => x.type === 'userMessage');
  assert.equal(users.length, 1);
  assert.equal(users[0].clientId, 'first-' + submitted.clientOperationId);
  assert.equal(t.params.model ?? t.params.collaborationMode?.settings.model, fixture.model);
  assert.equal(t.params.effort ?? t.params.collaborationMode?.settings.reasoning_effort, 'xhigh');
  assert.equal(t.params.sandboxPolicy.type, 'readOnly');
  assert.equal(t.params.serviceTier, 'priority');
  assert(
    !t.items.some((x) =>
      ['commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall'].includes(x.type),
    ),
  );
  const canonical = await witness.reader.read('thread/read', { threadId: id, includeTurns: true });
  assert.equal(canonical.thread.id, id);
  assert.equal(
    canonical.thread.turns.flatMap((t) => t.items ?? []).filter((x) => x.type === 'userMessage')
      .length,
    1,
  );
  if (completed) {
    assert.equal(t.status, 'completed');
    assert(
      t.items.some(
        (x) => x.type === 'agentMessage' && x.phase === 'final_answer' && x.text === expected,
      ),
    );
  }
  await writeFile(join(root, label + '-owner.json'), JSON.stringify(owner, null, 2), {
    mode: 0o600,
  });
  await writeFile(join(root, label + '-canonical.json'), JSON.stringify(canonical, null, 2), {
    mode: 0o600,
  });
  report.original = {
    threadId: id,
    operationId: submitted.clientOperationId,
    clientId: users[0].clientId,
    turnId: t.turnId,
    status: t.status,
    canonicalInputs: 1,
  };
  if (!prior) report.newRealModelInputsConfirmed = 1;
  await save();
}
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  assert.equal(witness.version, process.env.ANDROID_CODE_EXPECT_DESKTOP_VERSION);
  assert.equal(report.adapterSha256, process.env.ANDROID_CODE_EXPECT_ADAPTER_SHA256);
  const bridge = join(state, 'bridge.json');
  if (!prior) {
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
  }
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Android actual model code DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  await startOwnedHost();
  let workspace, project;
  const workspaceDeadline = Date.now() + 30000;
  do {
    workspace = await direct('/v1/workspace?' + new URLSearchParams({ hostId }));
    assert.equal(workspace.hostId, hostId);
    project = workspace.projects.find((x) => x.id === projectId);
    if (project) break;
    await new Promise((ok) => setTimeout(ok, 500));
  } while (Date.now() < workspaceDeadline);
  await writeFile(
    join(root, 'workspace-targets.json'),
    JSON.stringify(
      {
        hostId: workspace.hostId,
        connectionMode: workspace.connectionMode,
        projects: workspace.projects,
      },
      null,
      2,
    ),
    { mode: 0o600 },
  );
  assert(
    project && (await Promise.all(project.roots.map((p) => realpath(p)))).includes(projectRoot),
  );
  const model = (await direct('/v1/models?' + new URLSearchParams({ hostId }))).models.find(
    (x) => x.isDefault,
  );
  assert(model.supportedEfforts.includes('xhigh') && model.supportedSpeeds.includes('fast'));
  const gets = new Set([
    '/healthz',
    '/v1/workspace',
    '/v1/history',
    '/v1/models',
    '/v1/events',
    '/v1/connection-routes',
    '/v1/desktop/creation-receipts',
    '/v1/desktop/creation-status',
    '/v1/desktop/creations',
    '/v1/threads/fork-options',
  ]);
  const methods = new Set();
  proxy = createServer((req, res) => {
    void (async () => {
      assert.equal(req.headers.authorization, 'Bearer ' + token);
      const url = new URL(req.url, endpoint);
      let bytes;
      if (req.method === 'POST') {
        assert(!prior, 'Readonly original scene forbids every product POST');
        assert(['/v1/desktop/creations', '/v1/desktop/creations/submit'].includes(url.pathname));
        const v = await bodyJson(req);
        assert.equal(v.hostId, hostId);
        assert.match(v.clientOperationId, /^[a-f0-9-]{36}$/);
        const key = url.pathname + ':' + v.clientOperationId;
        assert(!methods.has(key), 'Never replay input');
        methods.add(key);
        if (url.pathname === '/v1/desktop/creations') {
          assert(!submitted);
          assert.equal(v.text, text);
          assert.equal(v.projectId, projectId);
          assert.deepEqual(v.attachments, []);
          assert.equal(v.model, model.id);
          assert.equal(v.effort, 'xhigh');
          assert.equal(v.speed, 'fast');
          assert.equal(v.permissionMode, 'read-only');
          assert.equal(v.collaborationMode, 'default');
          submitted = v;
          await writeFile(join(root, 'original-native-request.json'), JSON.stringify(v, null, 2), {
            mode: 0o600,
            flag: 'wx',
            flush: true,
          });
        } else assert.equal(v.clientOperationId, submitted?.clientOperationId);
        bytes = JSON.stringify(v);
        report.posts.push({
          path: url.pathname,
          operationId: v.clientOperationId,
          sha256: sha(bytes),
        });
        await save();
      } else {
        assert.equal(req.method, 'GET');
        assert(gets.has(url.pathname));
        if (['/v1/workspace', '/v1/models'].includes(url.pathname))
          assert([null, hostId].includes(url.searchParams.get('hostId')));
        else if (url.pathname !== '/healthz') assert.equal(url.searchParams.get('hostId'), hostId);
      }
      const controller = new AbortController();
      active.add(controller);
      req.on('close', () => {
        if (res.destroyed) controller.abort();
      });
      try {
        const r = await fetch(endpoint + req.url, {
          method: req.method,
          headers: {
            Authorization: 'Bearer ' + token,
            ...(bytes ? { 'Content-Type': 'application/json' } : {}),
          },
          ...(bytes ? { body: bytes } : {}),
          redirect: 'error',
          signal: controller.signal,
        });
        res.writeHead(r.status, {
          'Content-Type': r.headers.get('content-type') ?? 'application/json',
        });
        res.end(Buffer.from(await r.arrayBuffer()));
      } catch (e) {
        if (req.method === 'GET' && controller.signal.aborted && res.destroyed) {
          report.readCancellations.push({ path: url.pathname, reason: 'native-reader-closed' });
          await save();
          return;
        }
        throw e;
      } finally {
        active.delete(controller);
      }
    })().catch((e) => {
      report.rejected.push(clean(e.message));
      void save();
      if (!res.headersSent) json(res, { error: 'Exact acceptance boundary rejected request' }, 403);
      else res.end();
    });
  });
  const proxyPort = await listen(proxy);
  control = createServer((req, res) => {
    void (async () => {
      assert.equal(req.method, 'POST');
      assert.equal(req.url, '/complete');
      assert.equal(req.headers.authorization, 'Bearer ' + controlKey);
      assert(!completed);
      const v = await bodyJson(req);
      assert.equal(v.operationId, submitted.clientOperationId);
      assert.equal(sha(v.intent), v.intentSha256);
      assert.deepEqual(JSON.parse(v.intent), submitted);
      assert.equal(v.reply, expected);
      assert.equal(v.copied, body);
      assert(v.draftPreserved && v.sdkSelection);
      completed = true;
      await audit('native-complete');
      assert.equal(v.threadId, report.original.threadId);
      report.nativeProof = v;
      await save();
      json(res, { ok: true });
    })().catch((e) => {
      report.controlError = clean(e.stack);
      void save();
      json(res, { error: 'Exact owner audit failed' }, 500);
    });
  });
  const controlPort = await listen(control);
  fixture = {
    nonce,
    hostId,
    token,
    endpoint: 'http://127.0.0.1:' + proxyPort,
    control: 'http://127.0.0.1:' + controlPort,
    controlKey,
    projectId,
    model: model.id,
    text,
    body,
    expected,
    readOnly: !!prior,
    threadId: prior?.original.threadId,
    originalRecord: prior?.nativeProof,
  };
  for (const port of [proxyPort, controlPort]) {
    await android('reverse', '--no-rebind', 'tcp:' + port, 'tcp:' + port);
    reverses.push(port);
  }
  for (const [name, src, packageName] of [
    ['app', '.build/native/android/outputs/apk/debug/android-debug.apk', pkg],
    [
      'test',
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
      pkg + '.test',
    ],
  ]) {
    const apk = join(root, name + '.apk');
    await writeFile(apk, await readFile(src), { mode: 0o600 });
    report[name + 'Sha256'] = sha(await readFile(apk));
    await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: true });
  }
  await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
  execFileSync(
    adb,
    [
      '-s',
      serial,
      'shell',
      `run-as ${pkg} sh -c 'umask 077; cat > files/mobile-code-model-config.json'`,
    ],
    { input: JSON.stringify(fixture), encoding: 'utf8', timeout: 10000 },
  );
  const result = await execute(
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
      'nativeCodeModel',
      '1',
      '-e',
      'nativeForeground',
      '1',
      '-e',
      'nativeThreadSnapshots',
      '1',
      '-e',
      'class',
      'com.agenthub.android.MobileCodeModelLiveTest#' +
        (prior
          ? 'readOriginalActualModelCodeReplySelectionAndWholeBodyCopy'
          : 'actualModelCodeReplySelectionAndWholeBodyCopy'),
      pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { encoding: 'utf8', timeout: 480000, maxBuffer: 16 * 1024 * 1024 },
  );
  const log = clean(result.stdout + result.stderr);
  await writeFile(join(root, 'instrumentation.log'), log, { mode: 0o600 });
  assert(
    /OK \(1 test\)/.test(log) && !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(log),
  );
  assert(completed);
  assert.equal(report.posts.length, prior ? 0 : 2);
  assert.equal(report.rejected.length, 0);
  assert.equal(await fingerprint(), report.sourceSha256);
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  if (e.stdout)
    await writeFile(join(root, 'instrumentation-command-failure.log'), clean(e.stdout + e.stderr), {
      mode: 0o600,
    });
  process.exitCode = 1;
} finally {
  if (submitted)
    try {
      await audit('final');
    } catch (e) {
      report.finalAuditError = clean(e.stack);
      report.status = 'failed';
      process.exitCode = 1;
    }
  try {
    const r = await execute(
      adb,
      ['-s', serial, 'shell', `run-as ${pkg} tar -cf - files/acceptance/P3-code-model/${nonce}`],
      { encoding: 'buffer', timeout: 30000, maxBuffer: 64 * 1024 * 1024 },
    );
    await writeFile(join(root, 'native-evidence.tar'), r.stdout, { mode: 0o600 });
  } catch (e) {
    report.nativeArchiveError = clean(e.message);
  }
  for (const c of active) c.abort();
  for (const s of [proxy, control])
    if (s)
      await new Promise((ok) => {
        s.close(ok);
        s.closeAllConnections();
      });
  for (const port of reverses) await android('reverse', '--remove', 'tcp:' + port);
  try {
    await stopOwnedHost();
  } catch (e) {
    report.cleanupError = clean(e.stack);
    report.status = 'failed';
    process.exitCode = 1;
  }
  await witness.close();
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      newInputs: report.newRealModelInputsConfirmed,
      error: report.error,
      finalAuditError: report.finalAuditError,
    }),
  );
}
