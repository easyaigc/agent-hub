import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { execFile, execFileSync, spawn, fork } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { networkInterfaces } from 'node:os';
import { promisify } from 'node:util';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { openOriginalDesktopThread } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

// Reuse an explicitly identified completed acceptance primer. Never prepare/execute another Codex instance.
const executable = process.env.CODEX_EXECUTABLE;
const sourcePath = process.env.PRIVATE_LIVE_SOURCE_REPORT,
  sourceRoot = process.env.PRIVATE_LIVE_SOURCE_ROOT;
assert(executable && sourcePath && sourceRoot);
assert(
  resolve(sourcePath).includes('/.local/') ||
    resolve(sourcePath).includes('/.codex/acceptance-archives/'),
  'Require a private acceptance record',
);
const prior = JSON.parse(await readFile(sourcePath, 'utf8'));
const threadId = prior.source.threadId,
  repo = resolve(sourceRoot);
const serials = (process.env.ANDROID_SERIALS ?? '').split(',').filter(Boolean);
const ios = process.env.IOS_SIMULATOR_ID;
assert(serials.length || ios);
assert(
  serials.every((s) => s.startsWith('emulator-') || process.env.ANDROID_ALLOW_PHYSICAL === '1'),
);
const root = resolve('.local/android-development/upstream-migration/UM04/live-' + Date.now());
const state = join(root, 'host');
await mkdir(state, { recursive: true, mode: 0o700 });
const sha = (v) => createHash('sha256').update(v).digest('hex');
const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' });
const repositoryProof = async () => {
  const untracked = git('ls-files', '--others', '--exclude-standard')
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  return {
    head: git('rev-parse', 'HEAD').trim(),
    status: git('status', '--porcelain=v1', '--untracked-files=all'),
    diffSha256: sha(git('diff', '--binary', 'HEAD')),
    untracked: await Promise.all(
      untracked.map(async (p) => ({ path: p, sha256: sha(await readFile(join(repo, p))) })),
    ),
  };
};
const hostId = randomUUID();
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  hostId,
  'UM04 independent original owner witness',
);
const run = promisify(execFile);
const sdk = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')).androidSdk;
const adb = resolve(sdk, 'platform-tools/adb');
const android = async (serial, ...args) =>
  (
    await run(adb, ['-s', serial, ...args], {
      timeout: 600_000,
      maxBuffer: 32 * 1024 * 1024,
      encoding: 'utf8',
    })
  ).stdout;
const address =
  process.env.PRIVATE_LIVE_LAN_ADDRESS ??
  Object.values(networkInterfaces())
    .flat()
    .find((a) => a && !a.internal && a.family === 'IPv4' && /^192\.168\./.test(a.address))?.address;
assert(
  address && /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(address),
  'Require the actual private LAN interface, never ADB reverse for product traffic',
);
const report = {
  step: 'UM04',
  status: 'running',
  hostId,
  source: { threadId, root: repo, report: sourcePath },
  cases: [],
  http: [],
  normalCodexConfigWritten: false,
  realProductHTTPS: true,
  controlOnlyADBReverse: true,
  physicalVPNConfigured: false,
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const fingerprint = async () => {
  const paths = [
    ...new Set(
      execFileSync(
        'git',
        [
          'ls-files',
          '-co',
          '--exclude-standard',
          'apps/android',
          'apps/ios',
          'apps/host-agent',
          'packages/mobile-core',
          'packages/codex-adapter',
          'packages/protocol',
          'scripts/mobile/private-connection-live.mjs',
          'scripts/mobile/private-connection-live-host.mjs',
        ],
        { encoding: 'utf8' },
      )
        .trim()
        .split('\n'),
    ),
  ].sort();
  const hash = createHash('sha256');
  for (const p of paths)
    hash
      .update(p + '\0')
      .update(await readFile(p))
      .update('\0');
  return hash.digest('hex');
};
report.sourceSha256 = await fingerprint();
let host, active, control, controlPort, originalHostKeySha;
const wait = async (probe, label, ms = 60_000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const value = await probe();
    if (value) return value;
    await new Promise((r) => setTimeout(r, 250));
  }
  throw Error('Timed out: ' + label);
};
const observe = async () => {
  const value = witness.ipc.state(threadId);
  assert(
    value && value.id === threadId && value.hostId === 'local' && witness.ipc.hasOwner(threadId),
    'Current original owner is required, persisted history is insufficient',
  );
  return value;
};
const endpoint = () => `https://${address}:${host.port}`;
const stopHost = async () => {
  if (!host || host.child.exitCode !== null) return;
  const current = host;
  const exited = new Promise((done) => current.child.once('exit', done));
  current.child.kill('SIGTERM');
  await Promise.race([
    exited,
    new Promise((_, reject) =>
      setTimeout(() => reject(Error('Owned Host did not exit')), 15000).unref(),
    ),
  ]);
  assert.equal(current.child.exitCode, 0, 'Owned Host must shut down cleanly');
  current.exited = true;
};
const launch = async () => {
  const child = fork(new URL('./private-connection-live-host.mjs', import.meta.url), [], {
    stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
  });
  const log = createWriteStream(join(root, 'host-' + child.pid + '.log'), { mode: 0o600 });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  child.once('exit', () => log.end());
  const ready = await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(Error('Owned Host startup timeout')), 30000);
    child.once('error', reject);
    child.once('exit', (code) => {
      clearTimeout(timer);
      reject(Error('Owned Host exited before ready: ' + code));
    });
    child.on('message', (message) => {
      if (message.type === 'ready') {
        clearTimeout(timer);
        done(message);
      }
      if (message.type === 'http') {
        report.http.push({ caseId: active?.id, ...message.record });
        void save();
      }
      if (message.type === 'tls-error') {
        report.tlsErrors ??= [];
        report.tlsErrors.push({ caseId: active?.id, ...message });
      }
    });
  });
  host = { ...ready, child };
  const current = sha(await readFile(join(state, 'access-token')));
  if (originalHostKeySha)
    assert.equal(current, originalHostKeySha, 'The original Host key must not rotate');
  else originalHostKeySha = current;
  report.hosts ??= [];
  report.hosts.push({
    pid: host.pid,
    endpoint: endpoint(),
    fingerprint: host.descriptor.fingerprint,
  });
  await save();
};
const claims = () =>
  report.http.filter(
    (r) => r.caseId === active.id && r.path === '/connection/claim' && r.method === 'POST',
  );
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  const existing = await witness.ipc.follow(threadId, 3000, true);
  if (!existing || !witness.ipc.hasOwner(threadId)) {
    report.namedProductOpen = {
      threadId,
      attemptedAtUTC: new Date().toISOString(),
      modelInputs: 0,
    };
    await save();
    await openOriginalDesktopThread(threadId);
  }
  let initial = await wait(
    async () => {
      const value = witness.ipc.hasOwner(threadId)
        ? witness.ipc.state(threadId)
        : await witness.ipc.follow(threadId, 3000, true);
      if (!value || !witness.ipc.hasOwner(threadId)) return null;
      return observe();
    },
    'original owner after one named product open',
    45_000,
  );
  assert(
    initial.cwd && resolve(initial.cwd) === repo,
    'Primer must still belong to the captured original repository',
  );
  const persisted = await witness.reader.read('thread/read', { threadId, includeTurns: true });
  assert(
    Array.isArray(persisted.thread?.turns),
    'Require the official completed prefix, not an omitted history',
  );
  const storedTurnIds = persisted.thread.turns.map((t) => t.id);
  initial = await wait(
    async () => {
      const value = await observe();
      return JSON.stringify(desktopTurns(value).map((t) => t.turnId)) ===
        JSON.stringify(storedTurnIds)
        ? value
        : null;
    },
    'current owner canonical history after one discovery, without repeatedly resetting its follow',
    30000,
  );
  const ownerTurnIds = desktopTurns(initial).map((t) => t.turnId);
  report.ownerHistoryPreflight = { ownerTurnIds, storedTurnIds, currentOriginalOwner: true };
  await save();
  assert.deepEqual(
    ownerTurnIds,
    storedTurnIds,
    'The current original owner must include the actual persisted completed prefix before another model input',
  );
  report.original = await repositoryProof();
  await writeFile(join(root, 'original-owner.json'), JSON.stringify(initial, null, 2), {
    mode: 0o600,
  });
  const priorTurn = desktopTurns(initial).at(-1);
  const model =
    initial.latestThreadSettings?.model ?? initial.latestModel ?? priorTurn?.params?.model;
  assert(typeof model === 'string' && model.length, 'Capture the actual existing model');
  report.model = model;
  report.effort =
    initial.latestThreadSettings?.effort ??
    initial.latestReasoningEffort ??
    priorTurn?.params?.effort;
  await writeFile(
    join(state, 'runtime.json'),
    JSON.stringify({
      hostId,
      projects: [],
      threads: [],
      receipts: [],
      attachments: [],
      creations: {},
    }),
    { mode: 0o600 },
  );
  await writeFile(
    join(state, 'bridge.json'),
    JSON.stringify({ hostId, desktopVersion: witness.version }),
    { mode: 0o600 },
  );
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE: executable,
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: repo,
    HOST_AGENT_NAME: 'UM04 DEV ' + hostId.slice(0, 8),
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '1',
    HOST_AGENT_DISCOVERY_BIND: '0.0.0.0',
    HOST_AGENT_DISCOVERY_PORT: '0',
  });
  delete process.env.HOST_AGENT_DISCOVERY_BONJOUR;
  await launch();
  control = createServer(
    (req, res) =>
      void (async () => {
        const reply = (status, value) => {
          res.writeHead(status, {
            'content-type': 'application/json',
            'cache-control': 'no-store',
          });
          res.end(JSON.stringify(value));
        };
        try {
          assert(active);
          assert(!req.headers.origin);
          if (req.method === 'GET' && req.url === '/um04/fixture')
            return reply(200, {
              hostId,
              threadId,
              title: initial.title,
              name: process.env.HOST_AGENT_NAME,
              endpoint: active.device.startsWith('emulator-')
                ? `https://10.0.2.2:${host.port}`
                : endpoint(),
              marker: active.marker,
              model,
            });
          if (req.method === 'GET' && req.url === '/um04/stats')
            return reply(200, { claimPosts: claims().length });
          if (req.method === 'POST' && req.url === '/um04/restart') {
            assert(!active.restarted);
            assert.equal(claims().length, 1);
            for await (const _ of req) {
              /* Only the owned examiner's empty control request. */
            }
            const pin = host.descriptor.fingerprint;
            const priorPid = host.pid;
            await stopHost();
            // The same verified private route is preserved across a real owned-process restart.
            process.env.HOST_AGENT_DISCOVERY_PORT = String(host.port);
            await launch();
            assert.notEqual(host.pid, priorPid);
            assert.equal(host.descriptor.fingerprint, pin);
            active.hostRestart = { beforePid: priorPid, afterPid: host.pid, originalExited: true };
            active.restarted = true;
            await save();
            return reply(200, { ok: true });
          }
          reply(404, {});
        } catch (error) {
          reply(500, { error: error.message });
        }
      })(),
  );
  await new Promise((done, reject) => {
    control.once('error', reject);
    control.listen(ios ? 8817 : 0, '127.0.0.1', done);
  });
  controlPort = control.address().port;
  for (const device of [...serials, ...(ios ? ['ios:' + ios] : [])]) {
    const before = await observe();
    const turnsBefore = desktopTurns(before);
    assert.equal(turnsBefore.at(-1)?.status, 'completed');
    active = {
      id: randomUUID(),
      device,
      marker: 'UM04_PRIVATE_' + randomUUID().slice(0, 8),
      status: 'running',
      restarted: false,
      productNetworkPath: device.startsWith('emulator-')
        ? 'Android emulator NAT gateway to the owned actual Host; no product ADB reverse'
        : 'Actual private LAN; native Bonjour required',
    };
    report.cases.push(active);
    await save();
    if (device.startsWith('ios:')) {
      const bundle = join(root, 'ios-' + active.id + '.xcresult');
      active.xcresult = bundle;
      const child = spawn(
        'xcodebuild',
        [
          '-project',
          'apps/ios/CodexManage.xcodeproj',
          '-scheme',
          'CodexManage',
          '-destination',
          'platform=iOS Simulator,id=' + ios,
          '-derivedDataPath',
          process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios',
          '-resultBundlePath',
          bundle,
          '-only-testing:CodexManageUITests/CodexManageUITests/testUM04DiscoveryExplicitPrivateConnectionRealDEV',
          'test',
        ],
        { stdio: ['ignore', 'pipe', 'pipe'] },
      );
      const chunks = [];
      child.stdout.on('data', (c) => chunks.push(c));
      child.stderr.on('data', (c) => chunks.push(c));
      const code = await new Promise((done, reject) => {
        child.once('error', reject);
        child.once('close', done);
      });
      await writeFile(join(root, 'ios-' + active.id + '.log'), Buffer.concat(chunks), {
        mode: 0o600,
      });
      assert.equal(code, 0, 'Actual iOS UI acceptance failed');
    } else {
      const processes = execFileSync('/bin/ps', ['-axo', 'args='], { encoding: 'utf8' });
      assert(
        !processes
          .split('\n')
          .some((line) =>
            line.trimStart().startsWith(adb + ' -s ' + device + ' shell am instrument '),
          ),
        'This device already has an instrumentation run; preserve it and retry only after it finishes',
      );
      assert.equal((await android(device, 'get-state')).trim(), 'device');
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
          serial: device,
          packageName,
          apk,
          allowPhysical: process.env.ANDROID_ALLOW_PHYSICAL === '1',
        });
      await android(device, 'reverse', 'tcp:' + controlPort, 'tcp:' + controlPort);
      try {
        const out = await android(
          device,
          'shell',
          'am',
          'instrument',
          '-w',
          '-r',
          '-e',
          'class',
          'com.agenthub.android.MobilePrivateConnectionLiveTest',
          '-e',
          'privateLiveControl',
          'http://127.0.0.1:' + controlPort,
          '-e',
          'privateLiveRun',
          active.id,
          '-e',
          'privateLiveAllowHint',
          device.startsWith('emulator-') ? '1' : '0',
          'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
        );
        await writeFile(join(root, 'native-' + active.id + '.log'), out, { mode: 0o600 });
        await android(
          device,
          'pull',
          '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/UM04-live/' +
            active.id,
          join(root, 'screens-' + device),
        ).catch(() => {});
        assert(
          out.includes('OK (1 test)') &&
            !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(out),
          'Actual Android UI acceptance failed',
        );
        active.nativeProof = JSON.parse(
          await readFile(join(root, 'screens-' + device, 'proof.json'), 'utf8'),
        );
      } finally {
        await android(device, 'reverse', '--remove', 'tcp:' + controlPort).catch(() => {});
      }
    }
    const after = await wait(
      async () => {
        const v = await observe();
        const t = desktopTurns(v);
        return t.length === turnsBefore.length + 1 && t.at(-1)?.status === 'completed' ? v : null;
      },
      'one original completed Turn',
      45_000,
    );
    const turns = desktopTurns(after);
    assert.deepEqual(turns.slice(0, -1), turnsBefore, 'Preserve the original completed prefix');
    const turn = turns.at(-1);
    const user = turn.items.filter((i) => i.type === 'userMessage');
    const output = turn.items
      .filter((i) => i.type === 'agentMessage')
      .map((i) => i.text ?? '')
      .join('\n')
      .trim();
    assert.equal(output, active.marker);
    assert.equal(user.length, 1);
    assert(JSON.stringify(user).includes('Reply only ' + active.marker + '. Do not use tools.'));
    assert.equal(claims().length, 1);
    assert(active.restarted, 'Host/app restart must execute');
    const posts = report.http.filter((r) => r.caseId === active.id && r.method === 'POST');
    assert.equal(
      posts.filter((r) => r.path === '/v1/replies').length,
      1,
      'Exactly one authenticated phone input',
    );
    for (const r of report.http.filter((r) => r.caseId === active.id && r.path === '/host'))
      assert(!r.authorizationPresent);
    const claim = claims()[0];
    assert.deepEqual(Object.keys(claim.body).sort(), ['publicKey', 'target', 'version']);
    assert(!claim.authorizationPresent);
    const bindingState = JSON.parse(
      await readFile(join(state, 'private-connections-v1.json'), 'utf8'),
    );
    const binding = bindingState.bindings.find((b) => b.publicKey === claim.body.publicKey);
    assert(binding && !binding.revoked);
    const credentialSha = sha(binding.token);
    assert.notEqual(
      credentialSha,
      sha((await readFile(join(state, 'access-token'), 'utf8')).trim()),
    );
    const actualAuth = report.http.filter((r) => r.caseId === active.id && r.authorizationPresent);
    assert(actualAuth.length && actualAuth.every((r) => r.authorizationSha256 === credentialSha));
    if (active.nativeProof) assert.equal(active.nativeProof.tokenSha256, credentialSha);
    assert.deepEqual(await repositoryProof(), report.original);
    active.turnId = turn.turnId;
    active.model = turn.params?.model ?? after.latestThreadSettings?.model ?? after.latestModel;
    active.effort =
      turn.params?.effort ?? after.latestThreadSettings?.effort ?? after.latestReasoningEffort;
    active.credentialSha256 = credentialSha;
    active.publicKey = claim.body.publicKey;
    active.inputPosts = 1;
    active.originalNewTurns = 1;
    active.status = 'passed';
    await writeFile(join(root, 'owner-' + active.id + '.json'), JSON.stringify(after, null, 2), {
      mode: 0o600,
    });
    await save();
  }
  assert.equal(
    new Set(report.cases.map((c) => c.publicKey)).size,
    report.cases.length,
    'Each independent phone identity has its own binding',
  );
  assert.equal(
    await fingerprint(),
    report.sourceSha256,
    'Frozen production/examiner inputs cannot change during real acceptance',
  );
  report.status = 'passed';
  report.passed = report.cases.length;
  report.originalHostKeyPreserved = true;
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
} finally {
  await stopHost();
  witness.close();
  control?.closeAllConnections();
  control?.close();
  report.finishedAtUTC = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({
      status: report.status,
      passed: report.passed,
      report: join(root, 'report.json'),
      error: report.error,
    }),
  );
}
