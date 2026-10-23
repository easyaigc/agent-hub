import assert from 'node:assert/strict';
import { fork, execFile, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { promisify } from 'node:util';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

// Reopen an existing acceptance binding and completed input. Never claim or submit another input.
const inputPath = resolve(process.env.PRIVATE_REOPEN_REPORT ?? '');
assert(inputPath.includes('/.local/android-development/upstream-migration/UM04/live-'));
const prior = JSON.parse(await readFile(inputPath, 'utf8'));
const serial = process.env.ANDROID_SERIAL;
assert(serial && (serial.startsWith('emulator-') || process.env.ANDROID_ALLOW_PHYSICAL === '1'));
const source = prior.cases.find((c) => c.device === serial);
assert(
  source &&
    prior.http.filter(
      (r) => r.caseId === source.id && r.path === '/v1/replies' && r.method === 'POST',
    ).length === 1,
);
const claims = prior.http.filter(
  (r) => r.caseId === source.id && r.path === '/connection/claim' && r.method === 'POST',
);
assert.equal(claims.length, 1);
assert.equal(claims[0].status, 200);
const root = resolve(
  '.local/android-development/upstream-migration/UM04/reopen-' +
    Date.now() +
    '-' +
    serial +
    '-' +
    randomUUID(),
);
await mkdir(root, { recursive: true, mode: 0o700 });
const state = join(dirname(inputPath), 'host');
const hostId = prior.hostId,
  threadId = prior.source.threadId;
const hash = (b) => createHash('sha256').update(b).digest('hex');
const keyHash = hash(await readFile(join(state, 'access-token')));
const issuerBefore = await readFile(join(state, 'private-connections-v1.json'));
const binding = JSON.parse(issuerBefore).bindings.find(
  (b) => b.publicKey === claims[0].body.publicKey,
);
assert(binding && !binding.revoked);
const expectedTokenSha256 = hash(binding.token);
const report = {
  status: 'running',
  priorReport: inputPath,
  serial,
  hostId,
  threadId,
  originalMarker: source.marker,
  reusedOriginalInput: true,
  newModelInputs: 0,
  newClaims: 0,
  http: [],
  hosts: [],
  appProcesses: [],
  examinerSha256: hash(await readFile(new URL(import.meta.url))),
  appSha256: hash(await readFile('.build/native/android/outputs/apk/debug/android-debug.apk')),
  testAppSha256: hash(
    await readFile(
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
    ),
  ),
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
await save();
const run = promisify(execFile);
const sdk = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')).androidSdk;
const adb = resolve(sdk, 'platform-tools/adb');
assert(
  !execFileSync('/bin/ps', ['-axo', 'args='], { encoding: 'utf8' })
    .split('\n')
    .some((line) => line.trimStart().startsWith(adb + ' -s ' + serial + ' shell am instrument ')),
  'Keep the active device acceptance run; no overlapping instrumentation',
);
const call = async (...args) =>
  (await run(adb, ['-s', serial, ...args], { timeout: 180000, maxBuffer: 8 * 1024 * 1024 })).stdout;
const executable = process.env.CODEX_EXECUTABLE;
assert(executable);
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  hostId,
  'UM04 existing-input reopen witness',
);
let host,
  control,
  controlPort,
  phase = 'capture',
  namespace;
const caseId = randomUUID();
const expectedDraft = 'UM04_PROCESS_REOPEN_DRAFT_' + caseId;
const settledOwner = async (expectedIds) => {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const value = witness.ipc.state(threadId);
    if (
      value &&
      witness.ipc.hasOwner(threadId) &&
      JSON.stringify(desktopTurns(value).map((t) => t.turnId)) === JSON.stringify(expectedIds)
    )
      return value;
    await new Promise((done) => setTimeout(done, 200));
  }
  throw Error('Current original owner did not settle to the completed prefix; no input submitted');
};
const stop = async () => {
  if (!host || host.child.exitCode !== null) return;
  const c = host.child;
  const exited = new Promise((done) => c.once('exit', done));
  c.kill('SIGTERM');
  await Promise.race([
    exited,
    new Promise((_, reject) =>
      setTimeout(() => reject(Error('Owned Host did not exit')), 15000).unref(),
    ),
  ]);
  assert.equal(c.exitCode, 0);
};
const launch = async () => {
  const c = fork(new URL('./private-connection-live-host.mjs', import.meta.url), [], {
    stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
  });
  const log = createWriteStream(join(root, 'host-' + c.pid + '.log'), { mode: 0o600 });
  c.stdout.pipe(log, { end: false });
  c.stderr.pipe(log, { end: false });
  c.once('exit', () => log.end());
  const ready = await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(Error('Owned Host startup timeout')), 30000);
    c.once('error', reject);
    c.once('exit', (code) => {
      clearTimeout(timer);
      reject(Error('Host exited before ready: ' + code));
    });
    c.on('message', (m) => {
      if (m.type === 'ready') {
        clearTimeout(timer);
        done(m);
      }
      if (m.type === 'http') {
        report.http.push({ phase, ...m.record });
        void save();
      }
    });
  });
  host = { ...ready, child: c };
  assert.equal(ready.descriptor.target.hostId, hostId);
  assert.equal(ready.descriptor.fingerprint, prior.hosts[0].fingerprint);
  report.hosts.push({
    pid: ready.pid,
    port: ready.port,
    fingerprint: ready.descriptor.fingerprint,
  });
  await save();
};
try {
  await witness.connect();
  await witness.ipc.follow(threadId, 5000, true);
  const persisted = await witness.reader.read('thread/read', { threadId, includeTurns: true });
  const before = await settledOwner(persisted.thread.turns.map((t) => t.id));
  const turns = desktopTurns(before);
  const matched = turns.filter(
    (t) =>
      t.status === 'completed' &&
      t.items?.some((i) => i.type === 'agentMessage' && i.text === source.marker),
  );
  assert.equal(
    matched.length,
    1,
    'Existing input must have exactly one current original completed Turn',
  );
  assert.deepEqual(
    turns.map((t) => t.turnId),
    persisted.thread.turns.map((t) => t.id),
  );
  report.originalTurnId = matched[0].turnId;
  await writeFile(join(root, 'owner-before.json'), JSON.stringify(before, null, 2), {
    mode: 0o600,
  });
  const repositoryBefore = execFileSync('git', [
    '-C',
    prior.source.root,
    'diff',
    '--binary',
    'HEAD',
  ]);
  const repositoryStatus = execFileSync('git', [
    '-C',
    prior.source.root,
    'status',
    '--porcelain=v1',
    '--untracked-files=all',
  ]);
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE: executable,
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: prior.source.root,
    HOST_AGENT_NAME: 'UM04 existing binding reopen',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '1',
    HOST_AGENT_DISCOVERY_BIND: '0.0.0.0',
    HOST_AGENT_DISCOVERY_PORT: String(new URL(prior.hosts.at(-1).endpoint).port),
  });
  delete process.env.HOST_AGENT_DISCOVERY_BONJOUR;
  await launch();
  control = createServer((req, res) => {
    assert(!req.headers.origin);
    if (req.method !== 'GET' || req.url !== '/um04/reopen') {
      res.writeHead(404);
      res.end();
      return;
    }
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(
      JSON.stringify({
        phase,
        hostId,
        threadId,
        marker: source.marker,
        expectedTokenSha256,
        expectedDraft,
        namespace: namespace ?? null,
        newClaims: report.http.filter((r) => r.path === '/connection/claim' && r.method === 'POST')
          .length,
      }),
    );
  });
  await new Promise((done) => control.listen(0, '127.0.0.1', done));
  controlPort = control.address().port;
  await call('reverse', '--no-rebind', 'tcp:' + controlPort, 'tcp:' + controlPort);
  for (const [packageName, apk] of [
    [
      'com.easyai.agenthub.android.debug',
      '.build/native/android/outputs/apk/debug/android-debug.apk',
    ],
    [
      'com.easyai.agenthub.android.debug.test',
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
    ],
  ])
    await installAndroidApkIfNeeded({
      adb,
      serial,
      packageName,
      apk,
      allowPhysical: process.env.ANDROID_ALLOW_PHYSICAL === '1',
    });
  for (phase of ['capture', 'verify']) {
    await call('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
    const policy = await call('shell', 'dumpsys', 'window', 'policy');
    if (/showing=true/.test(policy) && /secure=false/.test(policy)) {
      await call('shell', 'wm', 'dismiss-keyguard');
      const size = (await call('shell', 'wm', 'size')).match(/(\d+)x(\d+)/);
      assert(size);
      const x = String(Math.floor(Number(size[1]) / 2));
      await call(
        'shell',
        'input',
        'swipe',
        x,
        String(Math.floor(Number(size[2]) * 0.85)),
        x,
        String(Math.floor(Number(size[2]) * 0.2)),
        '400',
      );
    }
    const method =
      phase === 'capture'
        ? 'captureExistingBindingAndDraft'
        : 'reopenOriginalBindingAfterRealProcessRestart';
    const out = await call(
      'shell',
      'am',
      'instrument',
      '-w',
      '-r',
      '-e',
      'class',
      'com.agenthub.android.MobilePrivateConnectionProcessRestartTest#' + method,
      '-e',
      'privateReopenControl',
      'http://127.0.0.1:' + controlPort,
      '-e',
      'privateReopenRun',
      caseId,
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    );
    await writeFile(join(root, phase + '.log'), out, { mode: 0o600 });
    assert(
      out.includes('OK (1 test)') &&
        !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(out),
      'Native ' + phase + ' failed; preserve its original instrumentation log',
    );
    await call(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/UM04-reopen/' +
        caseId,
      join(root, phase),
    );
    const proof = JSON.parse(await readFile(join(root, phase, phase + '.json'), 'utf8'));
    assert.equal(proof.tokenSha256, expectedTokenSha256);
    assert.equal(proof.newClaims, 0);
    assert.equal(proof.originalUserInputs, 1);
    report.appProcesses.push(proof);
    if (phase === 'capture') {
      namespace = proof.namespace;
      await call('shell', 'am', 'force-stop', 'com.easyai.agenthub.android.debug');
      await stop();
      const priorPid = host.pid;
      await launch();
      assert.notEqual(host.pid, priorPid);
    }
    await save();
  }
  assert.notEqual(
    report.appProcesses[0].pid,
    report.appProcesses[1].pid,
    'A real application process restart is required',
  );
  assert.equal(report.appProcesses[0].namespace, report.appProcesses[1].namespace);
  assert(
    report.http.length && report.http.every((r) => r.method === 'GET'),
    'No new claim, input or other product POST is permitted',
  );
  await witness.ipc.follow(threadId, 5000, true);
  assert.deepEqual(
    desktopTurns(await settledOwner(turns.map((t) => t.turnId))),
    turns,
    'Keep the entire current original owner prefix, no new Turn',
  );
  assert.equal(hash(await readFile(join(state, 'access-token'))), keyHash);
  assert.deepEqual(await readFile(join(state, 'private-connections-v1.json')), issuerBefore);
  assert.deepEqual(
    execFileSync('git', ['-C', prior.source.root, 'diff', '--binary', 'HEAD']),
    repositoryBefore,
  );
  assert.deepEqual(
    execFileSync('git', [
      '-C',
      prior.source.root,
      'status',
      '--porcelain=v1',
      '--untracked-files=all',
    ]),
    repositoryStatus,
  );
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
} finally {
  await stop();
  witness.close();
  control?.close();
  control?.closeAllConnections();
  if (controlPort) await call('reverse', '--remove', 'tcp:' + controlPort).catch(() => {});
  report.finishedAtUTC = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({
      status: report.status,
      report: join(root, 'report.json'),
      error: report.error,
      newModelInputs: 0,
    }),
  );
}
