import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, copyFile, readFile, writeFile, chmod, realpath } from 'node:fs/promises';
import { join, resolve, isAbsolute } from 'node:path';
import { randomBytes, createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { desktopQuestions } from '../../packages/codex-adapter/dist/desktop-questions.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';
const priorPath = process.env.ANDROID_QUESTION_PENDING_REPORT,
  output = process.env.MOBILE_PENDING_QUESTION_OUTPUT,
  serial = process.env.ANDROID_SERIAL;
assert(
  priorPath &&
    isAbsolute(priorPath) &&
    output &&
    isAbsolute(output) &&
    !resolve(output).startsWith(process.cwd() + '/'),
);
assert(
  serial &&
    ((serial.startsWith('emulator-') && process.env.ANDROID_PENDING_ALLOW_EMULATOR === '1') ||
      (!serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1')),
);
const prior = JSON.parse(await readFile(priorPath, 'utf8')),
  priorRoot = resolve(priorPath, '..');
assert.equal(prior.newRealModelInputsConfirmed, 4);
assert.equal(prior.status, 'failed');
assert.equal(prior.questionPosts.length, 3);
assert(prior.questionPosts.every((p) => p.index === 0));
const nonce = randomBytes(6).toString('hex'),
  root = join(output, 'pending-question-' + Date.now() + '-' + nonce),
  state = join(root, 'host'),
  sha = (v) => createHash('sha256').update(v).digest('hex');
await mkdir(output, { recursive: true, mode: 0o700 });
await mkdir(root, { mode: 0o700 });
await mkdir(state, { mode: 0o700 });
for (const name of ['runtime.json', 'access-token', 'bridge.json']) {
  await copyFile(join(priorRoot, 'host', name), join(state, name));
  await chmod(join(state, name), 0o600);
}
const fingerprint = async () => {
  const files = [
    ...new Set(
      execFileSync(
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
          'scripts/mobile/android-question-recovery-live.mjs',
        ],
        { encoding: 'utf8' },
      )
        .trim()
        .split('\n'),
    ),
  ].sort();
  const h = createHash('sha256');
  for (const f of files)
    h.update(f + '\0')
      .update(await readFile(f))
      .update('\0');
  return h.digest('hex');
};
const report = {
  phase: 'P4-4o.7',
  status: 'running',
  serial,
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  newRealModelInputs: 0,
  rejected: [],
  native: null,
  requireLargeSheet: process.env.ANDROID_PENDING_REQUIRE_LARGE === '1',
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')),
  adb = resolve(local.androidSdk, 'platform-tools/adb'),
  pkg = 'com.easyai.agenthub.android.acceptance',
  execute = promisify(execFile),
  android = async (...args) =>
    (
      await execute(adb, ['-s', serial, ...args], {
        encoding: 'utf8',
        timeout: 30000,
        maxBuffer: 32 * 1024 * 1024,
      })
    ).stdout.trim();
assert.equal(await android('get-state'), 'device');
if (serial.startsWith('emulator'))
  assert.equal(
    (await android('emu', 'avd', 'name')).split('\n')[0].trim(),
    'AgentHub_Alignment_API37',
  );
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  prior.hostId,
  'Read only original pending question',
);
let service, proxy, control, fixture;
const ports = [],
  upstreams = new Set();
const controlKey = randomBytes(32).toString('base64url');
let token;
const clean = (v) =>
  String(v)
    .replaceAll(token ?? '[NO_TOKEN]', '[REDACTED]')
    .replaceAll(controlKey, '[REDACTED]');
const json = (res, value, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(value));
};
const listen = async (s) => {
  await new Promise((done, reject) => {
    s.once('error', reject);
    s.listen(0, '127.0.0.1', done);
  });
  return s.address().port;
};
const canonical = async () => {
  const v = await witness.reader.read('thread/read', {
    threadId: prior.threadId,
    includeTurns: true,
  });
  assert.equal(
    v.thread.turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'userMessage').length,
    4,
  );
  return v;
};
try {
  await witness.connect();
  await witness.ipc.follow(prior.threadId, 5000, true);
  const owner = witness.ipc.state(prior.threadId);
  assert(
    witness.ipc.hasOwner(prior.threadId) && owner.id === prior.threadId && owner.hostId === 'local',
  );
  assert.equal(await realpath(owner.cwd), await realpath(prior.projectRoot));
  const turns = desktopTurns(owner);
  assert.equal(turns.length, 2);
  assert(turns.every((t) => t.status === 'completed'));
  const question = desktopQuestions(turns.at(-1).items).at(-1);
  assert(question);
  const approvalId = prior.threadId + ':async:' + question.id;
  await writeFile(join(root, 'canonical-before.json'), JSON.stringify(await canonical()), {
    mode: 0o600,
  });
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Original pending question read only',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  service = await startRuntimeServer();
  assert.equal((await service.session.workspace()).hostId, prior.hostId);
  token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  const endpoint = 'http://127.0.0.1:' + service.server.address().port;
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
  proxy = createServer((req, res) => {
    const abort = new AbortController();
    upstreams.add(abort);
    void (async () => {
      assert(
        req.method === 'GET' &&
          !req.headers.origin &&
          req.headers.authorization === 'Bearer ' + token &&
          gets.has(new URL(req.url, endpoint).pathname),
      );
      const timer = setTimeout(() => abort.abort(), 30000);
      try {
        const r = await fetch(endpoint + req.url, {
          headers: { Authorization: 'Bearer ' + token },
          signal: abort.signal,
          redirect: 'error',
        });
        res.writeHead(r.status, {
          'Content-Type': r.headers.get('content-type') ?? 'application/json',
        });
        res.end(Buffer.from(await r.arrayBuffer()));
      } finally {
        clearTimeout(timer);
      }
    })()
      .catch((e) => {
        report.rejected.push(clean(e.message));
        if (!res.headersSent) json(res, { error: clean(e.message) }, 500);
        else res.destroy();
      })
      .finally(() => upstreams.delete(abort));
  });
  const proxyPort = await listen(proxy);
  control = createServer((req, res) => {
    void (async () => {
      assert(
        req.method === 'POST' &&
          req.url === '/pending-recovery-complete' &&
          req.headers.authorization === 'Bearer ' + controlKey &&
          !req.headers.origin,
      );
      const chunks = [];
      let size = 0;
      for await (const c of req) {
        size += c.length;
        assert(size < 10000);
        chunks.push(c);
      }
      const v = JSON.parse(Buffer.concat(chunks).toString());
      assert.equal(v.nonce, nonce);
      assert.equal(v.threadId, prior.threadId);
      assert.equal(v.approvalId, approvalId);
      assert.equal(v.turnId, turns.at(-1).turnId);
      assert(v.localAnswerRetained);
      const current = await service.session.open(prior.threadId);
      assert(
        current.runtime.canControl &&
          current.runtime.approvals.some((a) => a.id === approvalId && a.turnId === v.turnId),
      );
      report.native = v;
      await save();
      json(res, { ok: true });
    })().catch((e) => {
      report.controlError = clean(e.stack);
      json(res, { error: clean(e.message) }, 500);
    });
  });
  const controlPort = await listen(control);
  report.ownedPorts = {
    host: service.server.address().port,
    proxy: proxyPort,
    control: controlPort,
  };
  fixture = {
    nonce,
    evidenceRun: root.split('/').at(-1),
    activeCase: -2,
    hostId: prior.hostId,
    endpoint: 'http://127.0.0.1:' + proxyPort,
    token,
    control: 'http://127.0.0.1:' + controlPort,
    controlKey,
    threadId: prior.threadId,
    approvalId,
    originalTurnId: turns.at(-1).turnId,
    questionTitle: question.title,
    isolatedSession: true,
    requireLargeSheet: report.requireLargeSheet,
  };
  for (const port of [proxyPort, controlPort]) {
    await android('reverse', 'tcp:' + port, 'tcp:' + port);
    ports.push(port);
  }
  for (const [name, apk, packageName] of [
    ['app', '.build/native/android/outputs/apk/debug/android-debug.apk', pkg],
    [
      'runner',
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
      pkg + '.test',
    ],
  ]) {
    report[name + 'Sha256'] = sha(await readFile(apk));
    await installAndroidApkIfNeeded({
      adb,
      serial,
      packageName,
      apk,
      allowPhysical: !serial.startsWith('emulator'),
    });
  }
  execFileSync(
    adb,
    [
      '-s',
      serial,
      'shell',
      'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-question-model-config.json'",
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
      'nativeForeground',
      '1',
      '-e',
      'class',
      'com.agenthub.android.MobileQuestionModelLiveTest#readOnlyPendingOriginalQuestion',
      pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { encoding: 'utf8', timeout: 180000, maxBuffer: 16 * 1024 * 1024 },
  );
  const log = clean(result.stdout + result.stderr);
  await writeFile(join(root, 'native.log'), log, { mode: 0o600 });
  assert(
    /OK \(1 test\)/.test(log) && !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(log),
  );
  assert(report.native && report.rejected.length === 0);
  await writeFile(join(root, 'canonical-after.json'), JSON.stringify(await canonical()), {
    mode: 0o600,
  });
  assert.equal(await fingerprint(), report.sourceSha256);
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  if (fixture)
    try {
      const a = (
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
            'files/acceptance/P4-question-model/' + nonce + '/' + fixture.evidenceRun,
          ],
          { encoding: 'buffer', timeout: 10000, maxBuffer: 32 * 1024 * 1024 },
        )
      ).stdout;
      await writeFile(join(root, 'native-evidence.tar'), a, { mode: 0o600 });
    } catch (e) {
      report.artifactError = clean(e.message);
    }
  await android('shell', 'am', 'force-stop', pkg).catch(() => {});
  for (const p of ports) await android('reverse', '--remove', 'tcp:' + p).catch(() => {});
  for (const a of upstreams) a.abort();
  for (const s of [proxy, control])
    if (s) {
      s.closeAllConnections();
      await new Promise((done) => s.close(done));
    }
  if (service)
    await new Promise((done) => {
      service.server.once('close', done);
      service.close();
    });
  witness.close();
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      native: report.native,
      newRealModelInputs: 0,
      error: report.error,
    }),
  );
}
