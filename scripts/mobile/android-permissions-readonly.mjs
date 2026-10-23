import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdir, realpath } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

// A completed four-case run may only be continued with reads. No original input is replayed.
assert.equal(process.env.ANDROID_PERMISSION_READ_ONLY, '1');
const originalRoot = await realpath(process.env.ANDROID_PERMISSION_ORIGINAL_ROOT ?? '');
const output = await realpath(process.env.MOBILE_PERMISSION_ACCEPTANCE_OUTPUT ?? '');
assert(originalRoot.startsWith(output + '/permission-live-'));
const priorBytes = await readFile(join(originalRoot, 'report.json'));
const prior = JSON.parse(priorBytes);
assert.equal(prior.scenarios.length, 4);
assert.equal(prior.native.length, 4);
assert.equal(prior.newRealModelInputsConfirmed, 4);
assert.equal(prior.rejectedPosts.length, 0);
assert.equal(prior.unresolvedOriginalCases.length, 0);
assert(
  prior.rejectedReads.length > 0 &&
    prior.rejectedReads.every(
      (x) => new URL(x.path, 'http://localhost').pathname === '/v1/threads/fork-options',
    ),
);
const serial = process.env.ANDROID_SERIAL;
assert.equal(serial, prior.serial);
assert.equal(process.env.ANDROID_ALLOW_PHYSICAL, '1');
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
const fixture = JSON.parse(
  await android('exec-out', 'run-as', pkg, 'cat', 'files/mobile-permission-config.json'),
);
assert.equal(fixture.nonce, prior.nonce);
assert.equal(fixture.hostId, prior.hostId);
assert.equal(fixture.threadId, prior.threadId);
assert(
  fixture.token === (await readFile(join(originalRoot, 'host/access-token'), 'utf8')).trim(),
  'Original private credential mismatch',
);
for (const name of ['endpoint', 'control']) {
  const u = new URL(fixture[name]);
  assert.equal(u.hostname, '127.0.0.1');
  assert.equal(u.protocol, 'http:');
  assert.equal(u.pathname, '/');
}
const root = join(output, 'permission-original-read-recovery-' + Date.now());
await mkdir(root, { mode: 0o700 });
const sha = (x) => createHash('sha256').update(x).digest('hex');
const sources = [
  'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePermissionLiveTest.kt',
  'scripts/mobile/android-permissions-live.mjs',
  'scripts/mobile/android-permissions-readonly.mjs',
];
const hashes = async () =>
  Object.fromEntries(await Promise.all(sources.map(async (p) => [p, sha(await readFile(p))])));
const report = {
  status: 'running',
  newRealModelInputs: 0,
  originalInputs: 4,
  priorReportSha256: sha(priorBytes),
  threadId: prior.threadId,
  posts: [],
  reads: [],
  errors: [],
  cleanupReads: [],
  sourceFiles: await hashes(),
  adapterSha256: preparedAdapterDigest(),
};
assert.equal(report.adapterSha256, prior.adapterSha256);
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  prior.hostId,
  'Android exact original permission read-only recovery',
);
let service, proxy, control;
let closing = false;
const reverses = [];
const respond = (res, status, data) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(data));
};
const listen = async (server, port) =>
  new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', done);
  });
try {
  await witness.connect();
  await witness.ipc.follow(prior.threadId, 5000, true);
  assert(witness.ipc.hasOwner(prior.threadId));
  const snapshot = witness.ipc.state(prior.threadId);
  assert.equal(snapshot.id, prior.threadId);
  assert.equal(snapshot.hostId, 'local');
  assert.equal(await realpath(snapshot.cwd), prior.projectRoot);
  const turns = desktopTurns(snapshot);
  const history = await witness.reader.read('thread/read', {
    threadId: prior.threadId,
    includeTurns: true,
  });
  assert.equal(
    history.thread.turns.flatMap((t) => t.items).filter((x) => x.type === 'userMessage').length,
    4,
  );
  for (const s of prior.scenarios) {
    const matches = turns.filter((t) =>
      t.items.some((i) => i.type === 'userMessage' && i.clientId === s.clientId),
    );
    assert.equal(matches.length, 1);
    const t = matches[0];
    assert.equal(t.turnId, s.turnId);
    assert.equal(t.status, 'completed');
    assert.equal(t.params.sandboxPolicy.type, s.sandboxPolicy.type);
    assert.equal(t.params.approvalPolicy, s.approvalPolicy);
    assert.equal(
      t.params.model ?? t.params.collaborationMode?.settings?.model,
      prior.settings.model,
    );
    assert.equal(
      t.params.effort ?? t.params.collaborationMode?.settings?.reasoning_effort,
      prior.settings.effort,
    );
    assert.equal(t.params.serviceTier, 'priority');
    const user = t.items.find((i) => i.type === 'userMessage');
    assert(user.content.some((i) => i.type === 'text' && sha(i.text) === s.textSha256));
    assert(t.items.some((i) => i.type === 'agentMessage' && i.text.trim() === s.marker));
  }
  report.originalOwnerReverified = true;
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(originalRoot, 'host/bridge.json'),
    HOST_AGENT_STATE_DIR: join(originalRoot, 'host'),
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Android original permission read-only recovery',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  service = await startRuntimeServer();
  const upstream = 'http://127.0.0.1:' + service.server.address().port;
  const gets = new Set([
    '/healthz',
    '/host',
    '/v1/workspace',
    '/v1/models',
    '/v1/history',
    '/v1/events',
    '/v1/receipts',
    '/v1/replies/status',
    '/v1/desktop/creation-receipts',
    '/v1/desktop/creation-status',
    '/v1/desktop/open-status',
    '/v1/threads/fork-options',
  ]);
  proxy = createServer((req, res) => {
    void (async () => {
      if (req.method !== 'GET') {
        report.posts.push({ method: req.method, path: req.url });
        throw Error('Read-only recovery refuses every business write');
      }
      assert(!req.headers.origin && req.headers.authorization === 'Bearer ' + fixture.token);
      const u = new URL(req.url, upstream);
      assert(gets.has(u.pathname));
      if (u.searchParams.has('hostId')) assert.equal(u.searchParams.get('hostId'), prior.hostId);
      if (u.searchParams.has('threadId'))
        assert.equal(u.searchParams.get('threadId'), prior.threadId);
      if (u.searchParams.has('id'))
        assert(prior.scenarios.some((x) => x.operationId === u.searchParams.get('id')));
      const response = await fetch(upstream + req.url, {
        headers: { Authorization: 'Bearer ' + fixture.token },
        signal: AbortSignal.timeout(30000),
      });
      const bytes = Buffer.from(await response.arrayBuffer());
      report.reads.push({ path: u.pathname, status: response.status });
      assert.equal(response.status, 200);
      res.writeHead(response.status, {
        'Content-Type': response.headers.get('Content-Type') ?? 'application/json',
      });
      res.end(bytes);
    })().catch((e) => {
      (closing ? report.cleanupReads : report.errors).push({ path: req.url, error: e.message });
      void save();
      if (!res.headersSent) respond(res, 500, { error: e.message });
      else res.destroy();
    });
  });
  control = createServer((req, res) => {
    void (async () => {
      assert(
        req.method === 'POST' &&
          req.url === '/original-read-complete' &&
          !req.headers.origin &&
          req.headers.authorization === 'Bearer ' + fixture.controlKey,
      );
      let bytes = '';
      for await (const c of req) {
        bytes += c;
        assert(bytes.length < 65536);
      }
      const value = JSON.parse(bytes);
      assert.equal(value.nonce, prior.nonce);
      assert.equal(value.threadId, prior.threadId);
      assert.equal(value.originals.length, 4);
      const warm = JSON.parse(
        await readFile(join(originalRoot, 'warm-three-originals.json'), 'utf8'),
      );
      assert.equal(warm.length, 3);
      for (const row of value.originals) {
        const s = prior.scenarios.find((s) => s.operationId === row.id);
        assert(s);
        assert.equal(sha(row.intent), row.intentSha256);
        const intent = JSON.parse(row.intent);
        assert.equal(sha(intent.text), s.textSha256);
        assert.equal(intent.hostId, prior.hostId);
        if (s.index > 0) assert.equal(intent.threadId, prior.threadId);
        const old = warm.find((x) => x.id === row.id);
        if (old)
          for (const k of ['target', 'sourceKey', 'intentSha256']) assert.equal(row[k], old[k]);
      }
      assert.equal(report.posts.length, 0);
      assert.equal(report.errors.length, 0);
      assert(report.reads.some((r) => r.path === '/v1/threads/fork-options' && r.status === 200));
      report.nativeOriginals = value.originals.map(({ intent: _intent, ...row }) => row);
      report.nativePid = value.pid;
      report.nativeOriginalsVerified = true;
      await save();
      respond(res, 200, { ok: true });
    })().catch((e) => {
      report.errors.push({ control: true, error: e.message });
      void save();
      respond(res, 500, { error: e.message });
    });
  });
  for (const [server, name] of [
    [proxy, 'endpoint'],
    [control, 'control'],
  ]) {
    const port = Number(new URL(fixture[name]).port);
    await listen(server, port);
    await android('reverse', '--no-rebind', 'tcp:' + port, 'tcp:' + port);
    reverses.push(port);
  }
  const app = '.build/native/android/outputs/apk/debug/android-debug.apk';
  assert.equal(sha(await readFile(app)), prior.appSha256);
  report.sameProductApkBytes = true;
  await installAndroidApkIfNeeded({
    adb,
    serial,
    packageName: pkg + '.test',
    apk: resolve(
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
    ),
    allowPhysical: true,
  });
  fixture.readOnlyRecovery = true;
  fixture.originalOperationIds = prior.scenarios.map((x) => x.operationId);
  execFileSync(
    adb,
    [
      '-s',
      serial,
      'shell',
      'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-permission-config.json'",
    ],
    { input: JSON.stringify(fixture), timeout: 10000 },
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
      'class',
      'com.agenthub.android.MobilePermissionLiveTest#originalOwnerPermissionReadOnlyRecovery',
      pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { encoding: 'utf8', timeout: 180000, maxBuffer: 16 * 1024 * 1024 },
  );
  const log = (result.stdout + result.stderr)
    .replaceAll(fixture.token, '[REDACTED]')
    .replaceAll(fixture.controlKey, '[REDACTED]');
  await writeFile(join(root, 'native-instrumentation.log'), log, { mode: 0o600 });
  assert(
    /OK \(1 test\)/.test(log) && !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(log),
  );
  assert(report.nativeOriginalsVerified && report.originalOwnerReverified);
  assert.equal(report.errors.length, 0);
  assert.equal(report.posts.length, 0);
  assert.deepEqual(await hashes(), report.sourceFiles);
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = String(e.stack)
    .replaceAll(fixture.token, '[REDACTED]')
    .replaceAll(fixture.controlKey, '[REDACTED]');
  process.exitCode = 1;
} finally {
  closing = true;
  try {
    const b = (
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
          'files/acceptance/P4-permission/' + prior.nonce,
        ],
        { encoding: 'buffer', timeout: 10000, maxBuffer: 32 * 1024 * 1024 },
      )
    ).stdout;
    await writeFile(join(root, 'native-evidence.tar'), b, { mode: 0o600 });
  } catch (e) {
    report.artifactError = e.message;
  }
  await android('shell', 'am', 'force-stop', pkg).catch(() => {});
  for (const p of reverses) await android('reverse', '--remove', 'tcp:' + p).catch(() => {});
  for (const s of [proxy, control])
    if (s?.listening) {
      s.closeAllConnections();
      await new Promise((done) => s.close(done));
    }
  witness.close();
  service?.close();
  assert.equal(sha(await readFile(join(originalRoot, 'report.json'))), report.priorReportSha256);
  if (report.status === 'passed' && report.errors.length > 0) {
    report.status = 'failed';
    report.error = 'Read errors before native completion';
    process.exitCode = 1;
  }
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      newInputs: 0,
      errors: report.errors.length,
      error: report.error,
    }),
  );
}
