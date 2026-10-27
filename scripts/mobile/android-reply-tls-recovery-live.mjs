import assert from 'node:assert/strict';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { createHash, randomBytes, randomUUID, X509Certificate } from 'node:crypto';
import { createServer as httpsServer } from 'node:https';
import { createServer as httpServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath, chmod } from 'node:fs/promises';
import { join, resolve, isAbsolute } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { replyIntentFingerprint } from '../../packages/codex-adapter/dist/runtime-session.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const preflightOnly = process.env.ANDROID_REPLY_TLS_PREFLIGHT_ONLY === '1';
assert(preflightOnly || process.env.ANDROID_REPLY_TLS_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL,
  pkg = 'com.easyai.agenthub.android.acceptance';
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const output = process.env.MOBILE_REPLY_TLS_RECOVERY_OUTPUT,
  projectId = process.env.ANDROID_REPLY_TLS_PROJECT_ID;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_REPLY_TLS_PROJECT_ROOT ?? ''),
  hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
const root = join(output, 'reply-tls-recovery-live-' + Date.now() + '-' + nonce),
  state = join(root, 'host');
await mkdir(state, { recursive: true, mode: 0o700 });
const execute = promisify(execFile),
  sha = (x) => createHash('sha256').update(x).digest('hex'),
  read = async (p) => JSON.parse(await readFile(p, 'utf8'));
const adb = resolve((await read('.local/mobile-toolchain.json')).androidSdk, 'platform-tools/adb');
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
const sourcePaths = [
  'apps/android',
  'apps/host-agent',
  'packages/codex-adapter',
  'packages/mobile-core',
  'packages/protocol',
  'scripts/mobile/android-reply-tls-recovery-live.mjs',
  'scripts/mobile/android-reply-tls-handoff-host.mjs',
];
const fingerprint = async () => {
  const files = execFileSync('git', ['ls-files', '-co', '--exclude-standard', ...sourcePaths], {
    encoding: 'utf8',
  })
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const h = createHash('sha256');
  for (const f of new Set(files))
    h.update(f + '\0')
      .update(await readFile(f))
      .update('\0');
  return h.digest('hex');
};
const machineTarget = (v) =>
  Object.fromEntries(['agentId', 'hostId', 'workspaceId', 'runtimeId'].map((k) => [k, v[k]]));
const cases = ['SEED', 'ORIGINAL_REPLY'].map((name) => {
  const marker = 'ANDROID_P4_REPLY_TLS_' + nonce + '_' + name;
  return {
    marker,
    text:
      'Android native paired TLS original reply recovery acceptance. Reply only with ' +
      marker +
      '. Do not call tools, read or write files, access the network, or contact other chats or agents.',
  };
});
await writeFile(
  join(root, 'immutable-cases.json'),
  JSON.stringify({ nonce, hostId, projectId, projectRoot, cases }, null, 2),
  { mode: 0o600, flag: 'wx', flush: true },
);
const report = {
  phase: 'P4-4h.2',
  status: 'running',
  preflightOnly,
  serial,
  nonce,
  hostId,
  projectId,
  projectRoot,
  scope:
    'Fresh owned native paired-TLS seed and original reply only. Initial reply is never forwarded. Scoped operator-audited original writer handoff is an explicit private attestation; ordinary404/restart/unavailable is not absence authority. Own app default encrypted cold restoration precedes explicit same-ID retry. Actual maximum two new accepted inputs; all prior62 remain untouched. Read-only/no tools; no project CRUD, debug package, global input/screenshot, system picker, archived owner or network-path proof.',
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  claims: [],
  requests: [],
  rejectedPosts: [],
  rejectedReads: [],
  readCancellations: [],
  ownedPorts: [],
  hostProcessExits: [],
  native: [],
  nativePhase: 0,
  newRealModelInputsConfirmed: 0,
  unknownSubmittedInputIds: [],
  observations: [],
};
let service,
  proxy,
  control,
  token,
  endpoint,
  fixture,
  target,
  proxyPort,
  certFingerprint,
  seed,
  originalReply,
  phase0Ready,
  replyForwarded = false,
  retryClaimed = false,
  seedClaimed = false,
  replyClaimed = false;
const active = new Set(),
  reverses = [];
const clean = (x) =>
  String(x)
    .replaceAll(token ?? '[NO_TOKEN]', '[REDACTED]')
    .replaceAll(controlKey, '[REDACTED]');
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const json = (res, v, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(v));
};
const listen = async (s) => {
  await new Promise((r, j) => {
    s.once('error', j);
    s.listen(0, '127.0.0.1', r);
  });
  return s.address().port;
};
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android paired TLS original reply independent owner witness',
);
assert(process.env.CODEX_EXECUTABLE);
async function startHost(handoffBase) {
  const env = {
    ...process.env,
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Android original reply TLS DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  };
  delete env.HOST_AGENT_TOKEN;
  delete env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete env.HOST_AGENT_REPLY_CHECKPOINT_FILE;
  if (handoffBase)
    Object.assign(env, {
      ANDROID_REPLY_TLS_CASE_ROOT: root,
      ANDROID_REPLY_TLS_CHECKPOINT_BASE: handoffBase,
      ANDROID_REPLY_TLS_HOST_ID: hostId,
      ANDROID_REPLY_TLS_NONCE: nonce,
      HOST_AGENT_REPLY_CHECKPOINT_FILE: join(root, 'reply-successor-checkpoint.json'),
    });
  const child = spawn(
    process.execPath,
    [
      handoffBase
        ? 'scripts/mobile/android-reply-tls-handoff-host.mjs'
        : 'apps/host-agent/dist/runtime-entry.js',
    ],
    { cwd: process.cwd(), env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  service = { child, logs: [], index: report.ownedPorts.filter((p) => p.label === 'host').length };
  const instance = service;
  const lines = createInterface({ input: child.stdout });
  const ready = await new Promise((done, reject) => {
    const t = setTimeout(() => reject(Error('Owned Host readiness deadline')), 30000);
    child.once('error', reject);
    child.once('exit', (c, s) => {
      clearTimeout(t);
      reject(Error('Owned Host early exit ' + c + '/' + s));
    });
    child.stderr.on('data', (b) => instance.logs.push(clean(b.toString())));
    lines.on('line', (l) => {
      instance.logs.push(clean(l));
      try {
        const v = JSON.parse(l);
        if (v.service === 'host-agent' && v.ready === true && v.mode === 'desktop-runtime') {
          clearTimeout(t);
          done(v);
        }
      } catch {
        /* Fixed readiness JSON only. */
      }
    });
  });
  assert.equal(ready.address.address, '127.0.0.1');
  assert.equal(ready.tokenFile, join(state, 'access-token'));
  const current = (await readFile(ready.tokenFile, 'utf8')).trim();
  if (token) assert.equal(current, token);
  else token = current;
  assert(token.length >= 32);
  endpoint = 'http://127.0.0.1:' + ready.address.port;
  instance.port = ready.address.port;
  report.ownedPorts.push({
    label: 'host',
    port: ready.address.port,
    pid: child.pid,
    index: instance.index,
  });
  await save();
}
async function stopHost(reason) {
  if (!service) return;
  const instance = service,
    child = instance.child;
  for (const e of active)
    if (e.hostPid === child.pid) {
      e.cancelReason = reason;
      e.controller.abort();
    }
  if (child.exitCode === null && child.signalCode === null)
    await new Promise((done, reject) => {
      const t = setTimeout(() => reject(Error('Owned Host close deadline')), 15000);
      child.once('exit', () => {
        clearTimeout(t);
        done();
      });
      child.kill('SIGTERM');
    });
  assert(child.exitCode === 0 || child.signalCode === 'SIGTERM');
  report.hostProcessExits.push({
    pid: child.pid,
    port: instance.port,
    code: child.exitCode,
    signal: child.signalCode,
    index: instance.index,
  });
  await writeFile(join(root, 'host-process-' + instance.index + '.log'), instance.logs.join('\n'), {
    mode: 0o600,
  });
  service = undefined;
  await save();
}
async function hostRead(path, query = {}) {
  assert(['/v1/workspace', '/v1/models', '/v1/replies/status'].includes(path));
  const url = new URL(path, endpoint);
  url.searchParams.set('hostId', hostId);
  for (const [k, v] of Object.entries(query)) url.searchParams.set(k, v);
  const response = await fetch(url, {
    headers: { Authorization: 'Bearer ' + token },
    signal: AbortSignal.timeout(30000),
    redirect: 'error',
  });
  assert.equal(response.status, 200);
  return response.json();
}
function settings(v) {
  assert.equal(v.model, fixture.model);
  assert.equal(v.effort, 'xhigh');
  assert.equal(v.speed, 'fast');
  assert.equal(v.permissionMode, 'read-only');
  assert.equal(v.collaborationMode, 'default');
}
async function bindSeed() {
  assert(seed);
  const prep = await read(join(state, 'desktop-prepared-bridge', seed.id + '.json'));
  assert.equal(prep.hostId, hostId);
  assert.equal(prep.clientOperationId, seed.id);
  assert.equal(prep.exitCode, 0);
  assert.equal(await realpath(prep.cwd), projectRoot);
  assert.match(prep.threadId, /^[a-f0-9-]{36}$/);
  const broker = (await read(join(state, 'desktop-creations/creations.json')))[seed.id];
  assert(broker && !broker.existingThreadIds.includes(prep.threadId));
  if (report.threadId) assert.equal(prep.threadId, report.threadId);
  report.threadId = prep.threadId;
  return prep.threadId;
}
async function probe(expected, completed, label) {
  const id = await bindSeed();
  await witness.ipc.follow(id, 5000, true);
  const owner = witness.ipc.state(id);
  if (!owner || !witness.ipc.hasOwner(id)) return { ready: false };
  assert.equal(owner.id, id);
  assert.equal(owner.hostId, 'local');
  assert.equal(await realpath(owner.cwd), projectRoot);
  assert.equal(owner.requests.length, 0);
  const turns = desktopTurns(owner),
    users = turns.flatMap((t) => t.items ?? []).filter((i) => i.type === 'userMessage');
  assert(users.length <= expected, 'Unexpected original owner input; do not replay');
  const expectedIds = ['first-' + seed.id, ...(originalReply ? [originalReply.id] : [])];
  for (let index = 0; index < users.length; index++) {
    assert.equal(users[index].clientId, expectedIds[index]);
    assert.deepEqual(
      users[index].content.filter((i) => i.type === 'text').map((i) => i.text),
      [cases[index].text],
    );
    assert(users[index].content.every((i) => i.type === 'text'));
  }
  const canonical = await witness.reader.read('thread/read', { threadId: id, includeTurns: true });
  const canonicalCount = canonical.thread.turns
    .flatMap((t) => t.items ?? [])
    .filter((i) => i.type === 'userMessage').length;
  assert(canonicalCount <= expected);
  report.newRealModelInputsConfirmed = Math.max(
    report.newRealModelInputsConfirmed ?? 0,
    Math.min(users.length, canonicalCount),
  );
  report.unknownSubmittedInputIds = report.unknownSubmittedInputIds.filter(
    (op) =>
      !expectedIds
        .slice(0, Math.min(users.length, canonicalCount))
        .some((clientId) => clientId === op || clientId === 'first-' + op),
  );
  const ordinal = report.observations.length;
  await writeFile(join(root, 'owner-' + ordinal + '.json'), JSON.stringify(owner, null, 2), {
    mode: 0o600,
  });
  await writeFile(
    join(root, 'canonical-' + ordinal + '.json'),
    JSON.stringify(canonical, null, 2),
    { mode: 0o600 },
  );
  const ready =
    users.length === expected &&
    canonicalCount === expected &&
    turns.length === expected &&
    (!completed || turns.every((t) => t.status === 'completed'));
  if (ready)
    for (let index = 0; index < turns.length; index++) {
      const t = turns[index];
      assert.equal(t.params.collaborationMode.mode, 'default');
      assert.equal(t.params.model ?? t.params.collaborationMode.settings.model, fixture.model);
      assert.equal(
        t.params.effort ?? t.params.collaborationMode.settings.reasoning_effort,
        'xhigh',
      );
      assert.equal(t.params.serviceTier, 'priority');
      assert.equal(t.params.sandboxPolicy.type, 'readOnly');
      assert.equal(t.params.approvalPolicy, 'on-request');
      assert(
        !t.items.some((i) =>
          ['commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall'].includes(i.type),
        ),
      );
      if (completed)
        assert(
          t.items.some(
            (i) =>
              i.type === 'agentMessage' &&
              i.phase === 'final_answer' &&
              i.text.trim() === cases[index].marker,
          ),
        );
    }
  report.observations.push({
    label,
    ownerInputs: users.length,
    canonicalInputs: canonicalCount,
    completed: turns.map((t) => t.status),
    ready,
  });
  await save();
  return { ready };
}
async function inspection(label) {
  assert(originalReply);
  const value = await hostRead('/v1/replies/status', {
    threadId: report.threadId,
    id: originalReply.id,
    target: JSON.stringify(originalReply.target),
  });
  assert.deepEqual(machineTarget(value.target), machineTarget(target));
  assert.equal(value.hostId, hostId);
  assert.equal(value.threadId, report.threadId);
  assert.equal(value.clientMessageId, originalReply.id);
  await writeFile(join(root, label + '-inspection.json'), JSON.stringify(value, null, 2), {
    mode: 0o600,
  });
  return value;
}
function verifyRecord(record) {
  assert.equal(record.id, originalReply.id);
  assert.equal(record.intentSha256, sha(record.intent));
  assert.deepEqual(JSON.parse(record.intent), originalReply.body);
  assert.equal(record.replyRecovery.trust, 'codex:' + certFingerprint);
  assert.equal(record.replyRecovery.fingerprint, originalReply.fingerprint);
}
async function makeHandoff() {
  assert(!preflightOnly && originalReply && phase0Ready && report.hostProcessExits.length === 1);
  const oldPid = report.hostProcessExits[0].pid;
  assert.throws(() => process.kill(oldPid, 0));
  const bytes = await readFile(join(state, 'runtime.json')),
    runtime = JSON.parse(bytes),
    ledger = runtime.replyLedger;
  assert.equal(ledger.writerPid, oldPid);
  assert.equal(ledger.version, 2);
  assert.equal(ledger.contract, 'fsync-before-model-v2');
  assert.deepEqual(machineTarget(ledger.target), machineTarget(originalReply.target));
  assert(!runtime.receipts.some((r) => r.clientMessageId === originalReply.id));
  const closedBytes = await readFile(join(state, 'reply-writer-closed-v2.json')),
    closed = JSON.parse(closedBytes);
  assert.equal(closed.oldPid, oldPid);
  assert.equal(closed.ledgerSha256, sha(bytes));
  assert.equal(closed.writerNonce, ledger.writerNonce);
  assert.equal(closed.ledgerId, ledger.ledgerId);
  await writeFile(join(root, 'original-closed-ledger.json'), bytes, {
    mode: 0o600,
    flag: 'wx',
    flush: true,
  });
  const closedSnapshot = join(root, 'original-writer-closed.json');
  await writeFile(closedSnapshot, closedBytes, { mode: 0o600, flag: 'wx', flush: true });
  const auditFile = join(root, 'non-forwarded-request-operator-audit.json');
  await writeFile(
    auditFile,
    JSON.stringify({
      nonce,
      hostId,
      oldPid,
      originalReplyId: originalReply.id,
      exactOriginalReplyForwards: 0,
      originalOwnerInputs: 1,
      canonicalInputs: 1,
      requests: report.requests,
      oldWriterClosed: true,
      closedSnapshotSha256: sha(closedBytes),
    }),
    { mode: 0o600, flag: 'wx', flush: true },
  );
  const sourceFiles = [];
  for (const repositorySource of [
    'packages/codex-adapter/dist/runtime-session.js',
    'packages/codex-adapter/dist/runtime-persistence.js',
    'apps/host-agent/dist/runtime-server.js',
    'scripts/mobile/android-reply-tls-recovery-live.mjs',
    'scripts/mobile/android-reply-tls-handoff-host.mjs',
  ]) {
    const source = await readFile(repositorySource),
      file = join(root, 'audited-' + repositorySource.replaceAll('/', '__'));
    await writeFile(file, source, { mode: 0o600, flag: 'wx', flush: true });
    sourceFiles.push({ file, sha256: sha(source), repositorySource });
  }
  const operations = [
      {
        threadId: report.threadId,
        clientMessageId: originalReply.id,
        fingerprint: originalReply.fingerprint,
      },
    ],
    fenceFile = join(root, 'operator-audited-fence.json');
  const fence = {
    version: 2,
    kind: 'operator-audited-legacy-fence-v2',
    oldPid,
    target: originalReply.target,
    ledgerSha256: sha(bytes),
    writerNonce: ledger.writerNonce,
    ledgerId: ledger.ledgerId,
    boundary: 'receipt-before-model-write-no-deletion',
    singleWriter: true,
    operations,
    sourceFiles,
    logFiles: [
      { file: auditFile, sha256: sha(await readFile(auditFile)) },
      { file: closedSnapshot, sha256: sha(closedBytes) },
    ],
  };
  await writeFile(fenceFile, JSON.stringify(fence), { mode: 0o600, flag: 'wx', flush: true });
  const checkpoint = {
    version: 2,
    kind: 'reply-ledger-fenced-handoff-v2',
    target: originalReply.target,
    oldPid,
    ledgerSha256: sha(bytes),
    fenceFile,
    fenceSha256: sha(await readFile(fenceFile)),
    operations,
  };
  const base = join(root, 'checkpoint-base.json');
  await writeFile(
    base,
    JSON.stringify({
      nonce,
      hostId,
      adapterSha256: preparedAdapterDigest(),
      originalReplyId: originalReply.id,
      originalReplyFingerprint: originalReply.fingerprint,
      checkpoint,
    }),
    { mode: 0o600, flag: 'wx', flush: true },
  );
  report.handoff = {
    oldPid,
    ledgerSha256: sha(bytes),
    oldClosedSnapshotSha256: sha(closedBytes),
    fenceSha256: checkpoint.fenceSha256,
    operatorScopedNonForwardedAudit: true,
  };
  return base;
}
async function nativePhase(phase) {
  report.nativePhase = phase;
  fixture.nativePhase = phase;
  await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
  execFileSync(
    adb,
    [
      '-s',
      serial,
      'shell',
      `run-as ${pkg} sh -c 'umask 077; cat > files/mobile-reply-tls-recovery-config.json'`,
    ],
    { input: JSON.stringify(fixture), encoding: 'utf8', timeout: 10000 },
  );
  const method =
    phase === 0
      ? 'prepareOriginalReplyAndSaveLaterDraft'
      : 'restoreOriginalReplyAndRetryOnlyExplicitly';
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
        'com.agenthub.android.MobileReplyTlsRecoveryModelLiveTest#' + method,
        pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
      ],
      { encoding: 'utf8', timeout: 650000, maxBuffer: 16 * 1024 * 1024 },
    );
  } catch (e) {
    await writeFile(
      join(root, 'phase-' + phase + '-command-failure.json'),
      JSON.stringify({ code: e.code, stdout: clean(e.stdout), stderr: clean(e.stderr) }),
      { mode: 0o600 },
    );
    throw e;
  }
  const log = clean(result.stdout + result.stderr);
  await writeFile(join(root, 'phase-' + phase + '-instrumentation.log'), log, { mode: 0o600 });
  assert(
    /OK \(1 test\)/.test(log) &&
      !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED/.test(log),
  );
  report.native.push({ phase, status: 'passed', logSha256: sha(log) });
  await save();
}
try {
  if (!preflightOnly) {
    const gatePath = process.env.ANDROID_REPLY_TLS_PREFLIGHT_REPORT,
      checkPath = process.env.ANDROID_REPLY_TLS_ROOT_CHECK;
    assert(gatePath && checkPath && isAbsolute(gatePath) && isAbsolute(checkPath));
    const gate = await read(gatePath),
      check = await read(checkPath);
    assert.equal(gate.phase, 'P4-4h.2');
    assert(
      gate.preflightOnly &&
        gate.status === 'passed' &&
        gate.newRealModelInputsConfirmed === 0 &&
        gate.native.length === 2,
    );
    assert.equal(gate.sourceSha256, report.sourceSha256);
    assert.equal(gate.adapterSha256, report.adapterSha256);
    assert.equal(gate.serial, serial);
    assert.equal(gate.projectId, projectId);
    assert.equal(await realpath(gate.projectRoot), projectRoot);
    assert.notEqual(gate.hostId, hostId);
    assert.equal(check.exitCode, 0);
    assert(check.sourceStable && check.commands === 59);
    for (const [f, h] of Object.entries(check.files)) assert.equal(sha(await readFile(f)), h);
    for (const [k, p] of [
      ['app', '.build/native/android/outputs/apk/debug/android-debug.apk'],
      ['test', '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk'],
    ])
      assert.equal(sha(await readFile(p)), gate[k + 'Sha256']);
    report.preflightGate = {
      reportSha256: sha(await readFile(gatePath)),
      rootCheckSha256: sha(await readFile(checkPath)),
      desktopVersion: gate.desktopVersion,
    };
  }
  await witness.connect();
  report.desktopVersion = witness.version;
  if (report.preflightGate) assert.equal(witness.version, report.preflightGate.desktopVersion);
  await writeFile(
    join(state, 'bridge.json'),
    JSON.stringify({ hostId, desktopVersion: witness.version }),
    { mode: 0o600 },
  );
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
  await startHost();
  const workspace = await hostRead('/v1/workspace');
  target = workspace.readTarget;
  assert.equal(workspace.hostId, hostId);
  assert.equal(workspace.connectionMode, 'desktop-runtime');
  assert.equal(target.agentId, 'codex');
  const project = workspace.projects.find((p) => p.id === projectId);
  assert(
    project && (await Promise.all(project.roots.map((p) => realpath(p)))).includes(projectRoot),
  );
  const model = (await hostRead('/v1/models')).models.find((m) => m.isDefault);
  assert(
    model && model.supportedEfforts.includes('xhigh') && model.supportedSpeeds.includes('fast'),
  );
  report.model = model.id;
  report.target = target;
  const keyPath = join(root, 'key.pem'),
    certPath = join(root, 'certificate.pem');
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-days',
      '1',
      '-subj',
      '/CN=localhost',
      '-addext',
      'subjectAltName=IP:127.0.0.1,DNS:localhost',
      '-keyout',
      keyPath,
      '-out',
      certPath,
    ],
    { stdio: 'ignore' },
  );
  await chmod(keyPath, 0o600);
  await chmod(certPath, 0o600);
  const cert = await readFile(certPath);
  certFingerprint = sha(new X509Certificate(cert).raw);
  report.certificateSha256 = certFingerprint;
  const descriptor = () => ({
    version: 1,
    agentId: 'codex',
    name: 'Android original reply TLS DEV',
    target,
    fingerprint: certFingerprint,
    endpoints: ['https://127.0.0.1:' + proxyPort],
    endpointHints: [{ endpoint: 'https://127.0.0.1:' + proxyPort, networkKind: 'lan' }],
  });
  const getRoutes = new Set([
    '/v1/workspace',
    '/v1/models',
    '/v1/history',
    '/v1/events',
    '/v1/receipts',
    '/v1/replies/status',
    '/v1/threads/fork-options',
    '/v1/desktop/creation-receipts',
    '/v1/desktop/creations',
  ]);
  const postRoutes = new Set([
    '/v1/desktop/creations',
    '/v1/desktop/creations/submit',
    '/v1/replies',
  ]);
  proxy = httpsServer({ key: await readFile(keyPath), cert }, (req, res) => {
    const entry = { controller: new AbortController(), hostPid: service?.child.pid };
    active.add(entry);
    res.on('close', () => {
      if (!res.writableEnded && !entry.controller.signal.aborted) {
        entry.cancelReason = 'native-reader-close';
        entry.controller.abort();
      }
    });
    void (async () => {
      assert(!req.headers.origin);
      const url = new URL(req.url, 'https://127.0.0.1');
      if (req.method === 'GET' && url.pathname === '/host') return json(res, descriptor());
      assert.equal(req.headers.authorization, 'Bearer ' + token);
      assert([null, '', hostId].includes(url.searchParams.get('hostId')));
      if (url.searchParams.has('target'))
        assert.deepEqual(
          machineTarget(JSON.parse(url.searchParams.get('target'))),
          machineTarget(target),
        );
      let bytes;
      if (req.method === 'POST') {
        assert(!preflightOnly && postRoutes.has(url.pathname), 'No unleased write');
        const chunks = [];
        let size = 0;
        for await (const b of req) {
          size += b.length;
          assert(size < 200000);
          chunks.push(b);
        }
        bytes = Buffer.concat(chunks);
        const value = JSON.parse(bytes);
        assert.equal(value.hostId, hostId);
        const item = {
          method: 'POST',
          path: url.pathname,
          nativePhase: report.nativePhase,
          bodySha256: sha(bytes),
          forwarded: false,
        };
        report.requests.push(item);
        if (url.pathname === '/v1/desktop/creations') {
          assert(seedClaimed && !seed && report.nativePhase === 0);
          settings(value);
          assert.equal(value.text, cases[0].text);
          assert.equal(value.projectId, projectId);
          assert.deepEqual(value.attachments, []);
          assert.match(value.clientOperationId, /^[a-f0-9-]{36}$/);
          seed = { id: value.clientOperationId, body: value };
          item.operationId = seed.id;
          await writeFile(
            join(root, 'seed-original-request.json'),
            JSON.stringify({ nonce, hostId, body: value }),
            { mode: 0o600, flag: 'wx', flush: true },
          );
        } else if (url.pathname === '/v1/desktop/creations/submit') {
          assert(seed && !seed.submitted && report.nativePhase === 0);
          assert.equal(value.clientOperationId, seed.id);
          seed.submitted = true;
          item.operationId = seed.id;
          report.unknownSubmittedInputIds.push(seed.id);
          report.newRealModelInputsConfirmed = null;
        } else {
          assert(replyClaimed && seed && report.seedCompleted);
          settings(value);
          assert.equal(value.threadId, report.threadId);
          assert.equal(value.text, cases[1].text);
          assert.deepEqual(value.attachmentIds, []);
          assert.equal(value.mode, 'next-turn');
          assert.match(value.clientMessageId, /^[a-f0-9-]{36}$/);
          assert.notEqual(value.clientMessageId, seed.id);
          if (!originalReply) {
            assert.equal(report.nativePhase, 0);
            originalReply = {
              id: value.clientMessageId,
              body: value,
              target: JSON.parse(url.searchParams.get('target')),
              fingerprint: replyIntentFingerprint(
                value,
                JSON.parse(url.searchParams.get('target')),
              ),
            };
            item.operationId = originalReply.id;
            item.controlledNeverForwarded = true;
            await writeFile(
              join(root, 'reply-original-request.json'),
              JSON.stringify({ nonce, hostId, ...originalReply }),
              { mode: 0o600, flag: 'wx', flush: true },
            );
            await save();
            return json(
              res,
              { error: 'Owned original reply transport interception ' + nonce },
              503,
            );
          }
          assert(report.nativePhase === 1 && retryClaimed && report.handoff && !replyForwarded);
          assert.equal(value.clientMessageId, originalReply.id);
          assert.deepEqual(value, originalReply.body);
          assert.equal(
            replyIntentFingerprint(value, JSON.parse(url.searchParams.get('target'))),
            originalReply.fingerprint,
          );
          replyForwarded = true;
          item.operationId = originalReply.id;
          report.unknownSubmittedInputIds.push(originalReply.id);
          report.newRealModelInputsConfirmed = null;
        }
        item.forwarded = true;
        await save();
      } else {
        assert.equal(req.method, 'GET');
        assert(getRoutes.has(url.pathname), 'Unknown read route ' + url.pathname);
        if (
          [
            '/v1/history',
            '/v1/events',
            '/v1/receipts',
            '/v1/replies/status',
            '/v1/threads/fork-options',
          ].includes(url.pathname)
        )
          assert.equal(url.searchParams.get('threadId'), report.threadId);
        if (['/v1/desktop/creation-receipts', '/v1/desktop/creations'].includes(url.pathname))
          assert.equal(url.searchParams.get('id'), seed?.id);
        if (['/v1/receipts', '/v1/replies/status'].includes(url.pathname))
          assert.equal(url.searchParams.get('id'), originalReply?.id);
      }
      const response = await fetch(new URL(url.pathname + url.search, endpoint), {
        method: req.method,
        headers: {
          Authorization: 'Bearer ' + token,
          ...(bytes ? { 'Content-Type': 'application/json' } : {}),
        },
        body: bytes,
        signal: entry.controller.signal,
        redirect: 'error',
      });
      const data = Buffer.from(await response.arrayBuffer());
      if (
        seed &&
        response.status === 200 &&
        [
          '/v1/desktop/creations',
          '/v1/desktop/creations/submit',
          '/v1/desktop/creation-receipts',
        ].includes(url.pathname)
      ) {
        const value = JSON.parse(data);
        assert.equal(value.clientOperationId, seed.id);
        assert.equal(value.hostId, hostId);
        const returnedThread = value.threadId ?? value.thread?.id;
        // The first prepare only reserves intent. Publish actual metadata before any response may navigate.
        if (returnedThread) {
          assert.equal(returnedThread, await bindSeed());
          await save();
        }
      }
      report.requests.push({
        method: req.method,
        path: url.pathname,
        status: response.status,
        nativePhase: report.nativePhase,
        hostPid: entry.hostPid,
        responseOnly: true,
      });
      if (req.method === 'GET' && ![200, 404, 409].includes(response.status))
        report.rejectedReads.push(
          'Unexpected owned read HTTP ' + response.status + ' ' + url.pathname,
        );
      res.writeHead(response.status, { 'Content-Type': 'application/json' });
      res.end(data);
    })()
      .catch((e) => {
        if (entry.controller.signal.aborted) {
          report.readCancellations.push({
            kind: entry.cancelReason ?? 'owned-examiner-close',
            hostPid: entry.hostPid,
          });
          if (!res.headersSent && !res.destroyed)
            json(res, { error: 'Owned reader lifecycle cancellation' }, 503);
        } else {
          (req.method === 'POST' ? report.rejectedPosts : report.rejectedReads).push(
            clean(e.message),
          );
          if (!res.headersSent)
            json(res, { error: 'Owned original reply scope guard rejected request' }, 403);
          else res.destroy();
        }
      })
      .finally(() => active.delete(entry));
  });
  proxyPort = await listen(proxy);
  report.ownedPorts.push({ label: 'tls-edge', port: proxyPort, pid: process.pid });
  control = httpServer((req, res) => {
    void (async () => {
      assert.equal(req.method, 'POST');
      assert.equal(req.headers.authorization, 'Bearer ' + controlKey);
      const chunks = [];
      let size = 0;
      for await (const b of req) {
        size += b.length;
        assert(size < 200000);
        chunks.push(b);
      }
      const v = JSON.parse(Buffer.concat(chunks));
      assert.equal(v.nonce, nonce);
      const route = new URL(req.url, 'http://127.0.0.1').pathname;
      let result = { ok: true };
      if (route === '/claim-seed') {
        assert(!preflightOnly && !seedClaimed && report.nativePhase === 0);
        assert.equal(v.text, cases[0].text);
        seedClaimed = true;
        report.claims.push({ kind: 'seed', sourceKey: v.sourceKey, textSha256: sha(v.text) });
      } else if (route === '/seed-complete') {
        assert(!preflightOnly && seed);
        assert.equal(v.record.id, seed.id);
        assert.equal(v.record.intentSha256, sha(v.record.intent));
        assert.deepEqual(JSON.parse(v.record.intent), seed.body);
        assert.equal(v.threadId, await bindSeed());
        result = await probe(1, true, 'seed-complete');
        if (result.ready) report.seedCompleted = true;
      } else if (route === '/claim-reply') {
        assert(!preflightOnly && report.seedCompleted && !replyClaimed && report.nativePhase === 0);
        assert.equal(v.text, cases[1].text);
        assert.equal(v.threadId, report.threadId);
        replyClaimed = true;
        report.claims.push({ kind: 'reply', threadId: v.threadId, textSha256: sha(v.text) });
      } else if (route === '/reply-intercepted') {
        assert(originalReply && !replyForwarded && report.nativePhase === 0);
        verifyRecord(v.record);
        assert((await probe(1, true, 'before-handoff-absence')).ready);
        const proof = await inspection('before-handoff');
        assert.equal(proof.status, 'unavailable');
        report.originalReplyBeforeHandoff = v.record;
      } else if (route === '/restart-ready') {
        assert(!phase0Ready && report.nativePhase === 0);
        assert.equal(v.preflightOnly, preflightOnly);
        assert.equal(v.localText, fixture.independentDraft);
        assert.equal(v.localFile.sha256, sha(fixture.localFileText));
        assert.equal(v.fingerprint, certFingerprint);
        if (!preflightOnly) {
          verifyRecord(v.record);
          assert.equal(v.threadId, report.threadId);
        }
        phase0Ready = v;
        report.restartReady = v;
      } else if (route === '/preflight-complete') {
        assert(preflightOnly && report.nativePhase === 1 && phase0Ready);
        assert.equal(v.newModelInputs, 0);
        assert.equal(v.pid === phase0Ready.pid, false);
        assert.equal(v.sourceKey, phase0Ready.sourceKey);
        assert.equal(v.localFile.id, phase0Ready.localFile.id);
        assert.equal(v.localFile.sha256, phase0Ready.localFile.sha256);
        assert.equal(v.localText, fixture.independentDraft);
        assert.equal(v.fingerprint, certFingerprint);
        report.preflightCompleted = v;
      } else if (route === '/claim-retry') {
        assert(!preflightOnly && report.nativePhase === 1 && report.handoff && !retryClaimed);
        verifyRecord(v.record);
        assert.equal(v.record.replyRecovery.phase, 'strong-not-accepted');
        assert.equal(v.localFile.id, phase0Ready.localFile.id);
        assert((await probe(1, true, 'before-explicit-retry')).ready);
        const proof = await inspection('strong-before-explicit-retry');
        assert.equal(proof.status, 'not-accepted');
        assert.equal(proof.fingerprintVersion, 2);
        assert.equal(proof.fingerprint, originalReply.fingerprint);
        assert.equal(proof.evidence.kind, 'operator-fenced-checkpoint-v2');
        assert.equal(proof.evidence.ledgerSha256, report.handoff.ledgerSha256);
        assert.equal(proof.evidence.fenceSha256, report.handoff.fenceSha256);
        assert.equal(proof.evidence.oldPid, report.handoff.oldPid);
        assert.equal(proof.evidence.newPid, service.child.pid);
        retryClaimed = true;
        report.claims.push({
          kind: 'explicit-original-retry',
          operationId: originalReply.id,
          fingerprint: originalReply.fingerprint,
        });
      } else if (route === '/case-complete') {
        assert(!preflightOnly && report.nativePhase === 1 && replyForwarded);
        verifyRecord(v.record);
        assert(v.record.replyRecovery.consumed);
        assert.equal(v.localFile.id, phase0Ready.localFile.id);
        assert.equal(v.localFile.sha256, phase0Ready.localFile.sha256);
        assert.equal(v.localText, fixture.independentDraft);
        result = await probe(2, true, 'case-complete');
        if (result.ready) report.caseCompleted = v;
      } else throw Error('Unsupported native control route');
      await save();
      json(res, result);
    })().catch((e) => {
      report.controlFailure = clean(e.stack);
      json(res, { error: 'Owned original reply witness rejected result' }, 500);
    });
  });
  const controlPort = await listen(control);
  report.ownedPorts.push({ label: 'control', port: controlPort, pid: process.pid });
  fixture = {
    nonce,
    evidenceRun: randomUUID(),
    nativePhase: 0,
    hostId,
    target,
    fingerprint: certFingerprint,
    preflightOnly,
    pairing:
      'codex-host://pair?descriptor=' +
      Buffer.from(JSON.stringify({ ...descriptor(), token })).toString('base64url'),
    projectId,
    model: model.id,
    control: 'http://127.0.0.1:' + controlPort,
    controlKey,
    firstText: cases[0].text,
    replyText: cases[1].text,
    independentDraft: 'Independent later reply draft ' + nonce,
    localFileName: 'later-reply-' + nonce + '.txt',
    localFileText: 'Local later reply bytes ' + nonce + '\n',
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
  await nativePhase(0);
  assert(phase0Ready);
  await android('shell', 'am', 'force-stop', pkg);
  await stopHost('owned-host-restart');
  let base;
  if (preflightOnly) {
    const runtime = await read(join(state, 'runtime.json'));
    assert.equal(runtime.receipts.length, 0);
    assert(report.requests.every((r) => r.method !== 'POST'));
  } else {
    assert((await probe(1, true, 'closed-writer-absence')).ready);
    base = await makeHandoff();
  }
  await startHost(base);
  const restored = await hostRead('/v1/workspace');
  assert.deepEqual(machineTarget(restored.readTarget), machineTarget(target));
  if (!preflightOnly) {
    const producer = await read(join(root, 'successor-checkpoint-producer.json'));
    assert.equal(producer.pid, service.child.pid);
    assert.equal(producer.oldPid, report.handoff.oldPid);
    assert.equal(producer.historicalArtifactsRewritten, false);
    report.handoff.newPid = service.child.pid;
    report.handoff.producerSha256 = sha(
      await readFile(join(root, 'successor-checkpoint-producer.json')),
    );
    const proof = await inspection('successor-initial');
    assert.equal(proof.status, 'not-accepted');
    assert.equal(proof.fingerprint, originalReply.fingerprint);
  }
  Object.assign(fixture, {
    sourceKey: phase0Ready.sourceKey,
    localFileId: phase0Ready.localFile.id,
    priorAppPid: phase0Ready.pid,
    threadId: phase0Ready.threadId,
    ...(!preflightOnly
      ? { originalReplyId: originalReply.id, originalReply: phase0Ready.record }
      : {}),
  });
  await nativePhase(1);
  assert.equal(await fingerprint(), report.sourceSha256);
  assert.equal(report.rejectedPosts.length, 0);
  assert.equal(report.rejectedReads.length, 0);
  assert(!report.controlFailure);
  if (preflightOnly) {
    assert(report.preflightCompleted);
    assert(report.requests.every((r) => r.method !== 'POST'));
    assert.equal(report.newRealModelInputsConfirmed, 0);
  } else {
    assert(report.caseCompleted && replyForwarded && retryClaimed);
    assert.equal(report.requests.filter((r) => r.method === 'POST' && !r.responseOnly).length, 4);
    assert.equal(report.newRealModelInputsConfirmed, 2);
    assert.equal(report.unknownSubmittedInputIds.length, 0);
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  if (seed && report.threadId && !preflightOnly)
    try {
      await probe(replyForwarded ? 2 : 1, false, 'final-readonly-accounting');
    } catch (e) {
      report.finalReadonlyAuditError = clean(e.message);
      if (report.status === 'passed') {
        report.status = 'failed';
        process.exitCode = 1;
      }
    }
  if (fixture)
    try {
      const bytes = (
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
            'files/acceptance/P4-reply-tls-recovery/' + nonce,
          ],
          { encoding: 'buffer', timeout: 10000, maxBuffer: 64 * 1024 * 1024 },
        )
      ).stdout;
      await writeFile(join(root, 'native-evidence.tar'), bytes, { mode: 0o600 });
    } catch (e) {
      report.artifactError = clean(e.message);
    }
  await android('shell', 'am', 'force-stop', pkg).catch(() => {});
  for (const port of reverses) await android('reverse', '--remove', 'tcp:' + port).catch(() => {});
  for (const e of active) {
    e.cancelReason = 'owned-examiner-close';
    e.controller.abort();
  }
  for (const s of [proxy, control])
    if (s) {
      s.closeAllConnections();
      await new Promise((r) => s.close(r));
    }
  await witness.close();
  try {
    await stopHost('owned-examiner-close');
  } catch (e) {
    report.cleanupError = clean(e.message);
    report.status = 'failed';
    process.exitCode = 1;
  }
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      preflightOnly,
      nativeMethods: report.native.length,
      newModelInputs: report.newRealModelInputsConfirmed,
      unknownSubmittedInputIds: report.unknownSubmittedInputIds,
      error: report.error,
    }),
  );
}
