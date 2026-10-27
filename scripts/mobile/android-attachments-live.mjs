import assert from 'node:assert/strict';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const systemPicker = process.env.ANDROID_ATTACHMENT_SYSTEM_PICKER === '1';
const preflightOnly = process.env.ANDROID_ATTACHMENT_PREFLIGHT_ONLY === '1';
const pickerEdges = process.env.ANDROID_ATTACHMENT_PICKER_EDGES === '1';
const coldHostReply = process.env.ANDROID_ATTACHMENT_COLD_HOST_REPLY === '1';
assert(!coldHostReply || (systemPicker && !pickerEdges));
assert(
  !pickerEdges || (systemPicker && preflightOnly),
  'Picker edge scenes never send model inputs',
);
assert(preflightOnly || process.env.ANDROID_ATTACHMENT_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(
  serial &&
    (serial.startsWith('emulator-')
      ? preflightOnly && systemPicker
      : process.env.ANDROID_ALLOW_PHYSICAL === '1'),
);
const projectId = process.env.ANDROID_ATTACHMENT_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_ATTACHMENT_PROJECT_ROOT ?? '');
const output = process.env.MOBILE_ATTACHMENT_ACCEPTANCE_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
await mkdir(output, { recursive: true, mode: 0o700 });
const root = join(output, 'attachment-live-' + Date.now() + '-' + randomBytes(6).toString('hex'));
await mkdir(root, { mode: 0o700 });
const priorPath = process.env.ANDROID_ATTACHMENT_PRIOR_EVIDENCE;
const priorOriginalPath = process.env.ANDROID_ATTACHMENT_PRIOR_ORIGINAL;
let prior, priorOriginal;
if (priorPath) {
  assert(!coldHostReply, 'A clean cold Host scene never restores an old submitted operation');
  assert(
    !preflightOnly &&
      priorOriginalPath &&
      resolve(priorPath).startsWith(resolve(output) + '/') &&
      resolve(priorOriginalPath).startsWith(resolve(output) + '/'),
  );
  prior = JSON.parse(await readFile(priorPath, 'utf8'));
  priorOriginal = JSON.parse(await readFile(priorOriginalPath, 'utf8'));
  assert.equal(prior.status, 'failed');
  assert.equal(prior.scenarios.length, 1);
  assert.equal(prior.scenarios[0].index, 0);
  assert.equal(prior.newRealModelInputsConfirmed, 1);
  assert.equal(prior.rejectedPosts.length, 1);
  assert.equal(prior.rejectedReads.length, 0);
  assert(prior.rejectedPosts[0].path.startsWith('/v1/replies?'));
  assert(prior.posts.every((p) => p.path !== '/v1/replies'));
  assert.equal(prior.projectId, projectId);
  assert.equal(await realpath(prior.projectRoot), projectRoot);
  assert.equal(priorOriginal.hostId, prior.hostId);
  assert.equal(priorOriginal.threadId, prior.firstThreadId);
}
const state = join(root, 'host');
await mkdir(state, { mode: 0o700 });
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(local.androidSdk, 'platform-tools/adb');
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
if (serial.startsWith('emulator-'))
  assert(
    ['AgentHub_Alignment_API26', 'AgentHub_Alignment_API37'].includes(
      (await android('emu', 'avd', 'name')).split('\n')[0].trim(),
    ),
  );
assert((await android('shell', 'dumpsys', 'power')).includes('mWakefulness=Awake'));
const pkg = 'com.easyai.agenthub.android.acceptance';
const hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
const sha = (value) => createHash('sha256').update(value).digest('hex');
const cases = [0, 1, 2].map((index) => {
  const code = 'TXT-' + randomBytes(6).toString('hex').toUpperCase();
  const imageCode = 'IMG-' + randomBytes(6).toString('hex').toUpperCase();
  const marker = `ANDROID_P4_ATTACHMENT_${nonce}_${index}`;
  const fileName = `owned-${nonce}-${index}.txt`,
    imageName = `owned-${nonce}-${index}.png`;
  const expected = index === 2 ? `${marker}|${code}|${imageCode}` : `${marker}|${code}`;
  const text =
    index === 2
      ? `安卓专用首条混合附件验收：实际读取 ${fileName} 的 code 值和 ${imageName} 中的字符，只回复 ${marker}|TXT的code值|图片字符，不加格式。不猜测，不修改文件，不联系其他聊天或Agent，不派生任务。随机码只在附件中。`
      : '';
  const fileText =
    index === 2
      ? `code=${code}\n中文附件原字节\n`
      : `这是用户显式发送的安卓专用纯附件验收任务。请实际读取本文件，只原样回复 ${expected}，不要加格式，不修改任何文件，不联系其他聊天或Agent，不派生任务。\ncode=${code}\n中文原字节\n`;
  assert(!text.includes(code) && !text.includes(imageCode));
  return { index, marker, code, imageCode, fileName, imageName, text, fileText, expected };
});
await writeFile(
  join(root, 'immutable-cases.json'),
  JSON.stringify({ nonce, projectId, projectRoot, cases }, null, 2),
  { mode: 0o600 },
);
const fingerprint = async () => {
  const paths = execFileSync(
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
      'scripts/mobile/android-attachments-live.mjs',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const hash = createHash('sha256');
  for (const path of new Set(paths))
    hash
      .update(path + '\0')
      .update(await readFile(path))
      .update('\0');
  return hash.digest('hex');
};
const report = {
  phase: 'P4-3j',
  scope: coldHostReply
    ? 'Owned child Host startup and opt-in physical pure-file first/reply across clean Host restart; exact original Electron owner, no mixed-input or network-path claim'
    : 'Physical native pure-file first/reply, mixed first, distinct-process original bytes and exact original Electron owner; USB loopback, not a new network path proof',
  status: 'running',
  preflightOnly,
  pickerEdgesEnabled: pickerEdges,
  coldHostReply,
  ownedHostProcesses: [],
  hostId,
  nonce,
  serial,
  projectId,
  projectRoot,
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  claims: [],
  posts: [],
  uploads: [],
  native: [],
  scenarios: [],
  rejectedReads: [],
  rejectedPosts: [],
  cleanupReadCancellations: [],
  newRealModelInputsConfirmed: 0,
  unresolvedOriginalCases: [],
};
if (prior) {
  assert.equal(
    prior.appSha256,
    sha(await readFile('.build/native/android/outputs/apk/debug/android-debug.apk')),
  );
  assert.equal(prior.adapterSha256, report.adapterSha256);
  assert.equal(sha(priorOriginal.record.intent), priorOriginal.record.intentSha256);
  assert.equal(priorOriginal.record.kind, 'reply');
  assert.equal(priorOriginal.record.phase, 'uncertain');
  assert.equal(JSON.parse(priorOriginal.record.intent).clientMessageId, priorOriginal.record.id);
  assert.equal(JSON.parse(priorOriginal.record.intent).threadId, prior.firstThreadId);
  assert.deepEqual(
    priorOriginal.source.files,
    prior.claims.find((c) => c.index === 1).files.map((p) => p.file),
  );
  report.firstThreadId = prior.firstThreadId;
  report.priorComponent = {
    reportSha256: sha(await readFile(priorPath)),
    sourceSha256: prior.sourceSha256,
    nativeSourceSha256: sha(await readFile(join(output, 'attachment-live-v1-native.kt'))),
    examinerSourceSha256: sha(await readFile(join(output, 'attachment-live-v1-examiner.mjs'))),
    completedFirstInput: prior.scenarios[0],
    blockedOriginalOperationId: priorOriginal.record.id,
    blockedOriginalIntentSha256: priorOriginal.record.intentSha256,
    originalNeverReplayed: true,
  };
}
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android attachment original owner read-only witness',
);
assert(process.env.CODEX_EXECUTABLE);
let service, proxy, control, token, endpoint, fixture, ownedHost;
const reverses = [],
  claims = new Map(),
  submitted = new Map(),
  submittedMethods = new Set(),
  upstreams = new Set();
const json = (res, value, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
const bytes = async (req, maximum = 1024 * 1024) => {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    assert(size <= maximum);
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
};
const listen = async (server) => {
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', done);
  });
  return server.address().port;
};
const wait = async (fn, label) => {
  const deadline = Date.now() + 25000;
  let last;
  do {
    try {
      const value = await fn();
      if (value) return value;
    } catch (e) {
      last = e;
    }
    await new Promise((done) => setTimeout(done, 500));
  } while (Date.now() < deadline);
  throw Error(label + ': ' + (last?.message ?? 'unavailable'));
};
const clean = (text) =>
  String(text)
    .replaceAll(token ?? '[NO_TOKEN]', '[REDACTED]')
    .replaceAll(controlKey, '[REDACTED]');
async function startOwnedHost() {
  assert(coldHostReply && !ownedHost);
  const env = { ...process.env };
  delete env.HOST_AGENT_REPLY_CHECKPOINT_FILE;
  const child = spawn(process.execPath, ['apps/host-agent/dist/runtime-entry.js'], {
    cwd: process.cwd(),
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const instance = { child, logs: [], index: report.ownedHostProcesses.length };
  ownedHost = instance;
  const lines = createInterface({ input: child.stdout });
  child.stderr.on('data', (value) => instance.logs.push(clean(value.toString())));
  const ready = await new Promise((done, reject) => {
    const timeout = setTimeout(
      () => reject(Error('Owned attachment Host readiness deadline')),
      30_000,
    );
    child.once('error', (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    child.once('exit', (code, signal) => {
      clearTimeout(timeout);
      reject(Error('Owned attachment Host early exit ' + code + '/' + signal));
    });
    lines.on('line', (line) => {
      instance.logs.push(clean(line));
      try {
        const value = JSON.parse(line);
        if (
          value.service === 'host-agent' &&
          value.ready === true &&
          value.mode === 'desktop-runtime'
        ) {
          clearTimeout(timeout);
          done(value);
        }
      } catch {
        /* Fixed readiness JSON only. */
      }
    });
  });
  assert.equal(ready.address.address, '127.0.0.1');
  assert.equal(ready.tokenFile, join(state, 'access-token'));
  const current = (await readFile(ready.tokenFile, 'utf8')).trim();
  assert(current.length >= 32);
  if (token) assert.equal(current, token);
  else token = current;
  endpoint = 'http://127.0.0.1:' + ready.address.port;
  instance.row = { index: instance.index, pid: child.pid, port: ready.address.port };
  report.ownedHostProcesses.push(instance.row);
}
async function stopOwnedHost(reason) {
  if (!ownedHost) return;
  const instance = ownedHost,
    child = instance.child;
  for (const entry of upstreams) {
    entry.cleanupAbort = true;
    entry.controller.abort(new Error(reason));
  }
  if (child.exitCode === null && child.signalCode === null)
    await new Promise((done, reject) => {
      const timeout = setTimeout(
        () => reject(Error('Owned attachment Host close deadline')),
        15_000,
      );
      child.once('exit', () => {
        clearTimeout(timeout);
        done();
      });
      child.kill('SIGTERM');
    });
  const expectedExit = child.exitCode === 0 || child.signalCode === 'SIGTERM';
  try {
    process.kill(child.pid, 0);
    assert.fail('Original owned Host still exists');
  } catch (error) {
    assert.equal(error.code, 'ESRCH');
  }
  if (instance.row)
    Object.assign(instance.row, {
      exitCode: child.exitCode,
      signal: child.signalCode,
      closed: true,
      reason,
    });
  await writeFile(join(root, 'owned-host-' + instance.index + '.log'), instance.logs.join('\n'), {
    mode: 0o600,
  });
  ownedHost = undefined;
  assert(expectedExit, 'Owned attachment Host exited unsuccessfully');
}
async function ownedHostJSON(path) {
  assert(coldHostReply && ['/v1/workspace', '/v1/models'].includes(path));
  const response = await fetch(endpoint + path + '?hostId=' + hostId, {
    headers: { Authorization: 'Bearer ' + token },
    signal: AbortSignal.timeout(30_000),
  });
  assert.equal(response.status, 200);
  return response.json();
}
async function restartOwnedHost(modelId, withFirstThread) {
  assert(coldHostReply && !claims.has(withFirstThread ? 1 : 0));
  assert.equal(submitted.size, withFirstThread ? 1 : 0);
  assert.equal(report.scenarios.length, withFirstThread ? 1 : 0);
  const oldPid = ownedHost.child.pid,
    oldTarget = report.readTarget;
  const persisted = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
  assert.equal(persisted.hostId, hostId);
  await stopOwnedHost('clean-owned-host-restart-before-new-file-input');
  await startOwnedHost();
  assert.notEqual(ownedHost.child.pid, oldPid);
  const restored = await ownedHostJSON('/v1/workspace');
  assert.equal(restored.hostId, hostId);
  assert.deepEqual(restored.readTarget, oldTarget);
  if (withFirstThread)
    assert(restored.threads.some((thread) => thread.id === report.firstThreadId));
  assert.equal(
    (await ownedHostJSON('/v1/models')).models.find((model) => model.isDefault)?.id,
    modelId,
  );
  const proof = {
    oldPid,
    newPid: ownedHost.child.pid,
    sameHostId: true,
    sameToken: true,
    sameReadTarget: true,
    sameDefaultModel: true,
    originalFirstThreadPreserved: withFirstThread,
    nextInputNotClaimedBeforeRestart: true,
  };
  if (withFirstThread) report.cleanColdHostReply = proof;
  else report.cleanColdHostPreflight = proof;
  await save();
}
const claimFiles = (value, row) => {
  assert.equal(value.text, row.text);
  assert.equal(value.files.length, row.index === 2 ? 2 : 1);
  for (const [index, part] of value.files.entries()) {
    const content = Buffer.from(part.bytes, 'base64'),
      file = part.file;
    assert.equal(sha(content), file.sha256);
    assert.equal(content.length, file.sizeBytes);
    assert.equal(file.name, index === 0 ? row.fileName : row.imageName);
    assert.equal(file.mediaType, index === 0 ? 'text/plain' : 'image/png');
    if (index === 0) assert(content.equals(Buffer.from(row.fileText)));
    else assert(content.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])));
  }
};
async function observe(index, value) {
  const row = cases[index],
    sent = submitted.get(index),
    claim = claims.get(index);
  assert(sent && claim && sent.operationId === value.record.id);
  const clientId = index === 1 ? sent.operationId : 'first-' + sent.operationId;
  const owner = await wait(async () => {
    await witness.ipc.follow(value.threadId, 5000, true);
    const owner = witness.ipc.state(value.threadId);
    if (!owner || !witness.ipc.hasOwner(value.threadId)) return null;
    return desktopTurns(owner).some(
      (t) =>
        t.status === 'completed' &&
        t.items?.some((item) => item.type === 'userMessage' && item.clientId === clientId),
    )
      ? owner
      : null;
  }, 'Exact original owner attachment completion');
  assert.equal(owner.id, value.threadId);
  assert.equal(owner.hostId, 'local');
  assert.equal(await realpath(owner.cwd), projectRoot);
  const turns = desktopTurns(owner).filter((t) =>
    t.items?.some((item) => item.type === 'userMessage' && item.clientId === clientId),
  );
  assert.equal(turns.length, 1);
  const turn = turns[0];
  assert.equal(turn.status, 'completed');
  assert(
    turn.items.some((item) => item.type === 'agentMessage' && item.text.trim() === row.expected),
  );
  assert.equal(turn.params.model ?? turn.params.collaborationMode?.settings?.model, fixture.model);
  assert.equal(
    turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort,
    'xhigh',
  );
  assert.equal(turn.params.serviceTier, 'priority');
  assert.equal(turn.params.sandboxPolicy.type, 'readOnly');
  assert.equal(turn.params.approvalPolicy, 'on-request');
  const history = await witness.reader.read('thread/read', {
    threadId: value.threadId,
    includeTurns: true,
  });
  assert.equal(history.thread.id, value.threadId);
  assert.equal(await realpath(history.thread.cwd), projectRoot);
  assert.equal(
    history.thread.turns.flatMap((t) => t.items ?? []).filter((item) => item.type === 'userMessage')
      .length,
    index === 1 ? 2 : 1,
  );
  assert.equal(value.record.intentSha256, sha(value.record.intent));
  assert.equal(value.source.text, row.text);
  assert.deepEqual(
    value.source.files,
    claim.files.map((p) => p.file),
  );
  const stored = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8')).attachments;
  const matched = stored.filter(
    (f) => f.threadId === value.threadId && claim.files.some((p) => p.file.name === f.name),
  );
  assert.equal(matched.length, claim.files.length);
  for (const file of matched) {
    const part = claim.files.find((p) => p.file.name === file.name);
    assert.equal(file.sha256, part.file.sha256);
    assert.equal(file.sizeBytes, part.file.sizeBytes);
    const path = await realpath(resolve(state, file.path));
    assert(path.startsWith(state + '/'));
    assert((await readFile(path)).equals(Buffer.from(part.bytes, 'base64')));
  }
  if (index === 0) report.firstThreadId = value.threadId;
  if (index === 1) assert.equal(value.threadId, report.firstThreadId);
  if (index === 2) assert.notEqual(value.threadId, report.firstThreadId);
  const result = {
    index,
    threadId: value.threadId,
    operationId: sent.operationId,
    clientId,
    turnId: turn.turnId,
    expected: row.expected,
    exactOriginalInputCount: 1,
    canonicalThreadInputCount: index === 1 ? 2 : 1,
    files: claim.files.map((p) => p.file),
    originalHostBytesVerified: true,
    originalSourceSnapshotVerified: true,
    nativePid: value.pid,
  };
  report.scenarios.push(result);
  report.newRealModelInputsConfirmed = report.scenarios.length;
  await save();
  return result;
}
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  if (prior) {
    await witness.ipc.follow(prior.firstThreadId, 15000, true);
    assert(witness.ipc.hasOwner(prior.firstThreadId));
    const canonical = await witness.reader.read('thread/read', {
      threadId: prior.firstThreadId,
      includeTurns: true,
    });
    assert.equal(await realpath(canonical.thread.cwd), projectRoot);
    const users = canonical.thread.turns
      .flatMap((t) => t.items ?? [])
      .filter((item) => item.type === 'userMessage');
    assert.equal(users.length, 1);
    assert.equal(users[0].clientId, prior.scenarios[0].clientId);
    assert(!users.some((item) => item.clientId === priorOriginal.record.id));
    report.priorComponent.canonicalInputsBeforeContinuation = 1;
    report.priorComponent.blockedOriginalAbsent = true;
    await save();
  }

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
    HOST_AGENT_NAME: 'Android attachment DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  if (coldHostReply) await startOwnedHost();
  else {
    service = await startRuntimeServer();
    token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
    endpoint = 'http://127.0.0.1:' + service.server.address().port;
  }
  const workspace = coldHostReply
      ? await ownedHostJSON('/v1/workspace')
      : await service.session.workspace(),
    project = workspace.projects.find((p) => p.id === projectId);
  assert(
    project &&
      (await Promise.all(project.roots.map((path) => realpath(path)))).includes(projectRoot),
  );
  assert(workspace.capabilities.includes('desktop:creations'));
  report.readTarget = workspace.readTarget;
  const model = (
    coldHostReply ? await ownedHostJSON('/v1/models') : await service.session.models()
  ).models.find((m) => m.isDefault);
  assert(
    model && model.supportedEfforts.includes('xhigh') && model.supportedSpeeds.includes('fast'),
  );
  const gets = new Set([
    '/healthz',
    '/v1/workspace',
    '/v1/models',
    '/v1/history',
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
    '/v1/attachments',
    '/v1/desktop/creation-attachments',
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
      let content;
      if (req.method === 'POST') {
        assert(!preflightOnly, 'Preflight must never post to the product Host');
        content = await bytes(req);
        if (
          url.pathname === '/v1/attachments' ||
          url.pathname === '/v1/desktop/creation-attachments'
        ) {
          const claim = claims.get(fixture.activeCase);
          assert(claim);
          assert.equal(url.searchParams.get('hostId'), hostId);
          const part = claim.files.find((p) =>
            url.pathname === '/v1/attachments'
              ? p.file.name === url.searchParams.get('name')
              : p.file.id === url.searchParams.get('attachmentId'),
          );
          assert(part);
          assert(content.equals(Buffer.from(part.bytes, 'base64')));
          assert.equal(req.headers['x-content-sha256'], part.file.sha256);
          assert.equal(req.headers['content-type'], part.file.mediaType);
          if (fixture.activeCase === 1)
            assert.equal(url.searchParams.get('threadId'), report.firstThreadId);
          else
            assert.equal(
              url.searchParams.get('id'),
              submitted.get(fixture.activeCase)?.operationId,
            );
          const key = url.pathname + ':' + fixture.activeCase + ':' + part.file.id;
          assert(!submittedMethods.has(key));
          submittedMethods.add(key);
          report.uploads.push({
            index: fixture.activeCase,
            path: url.pathname,
            file: part.file,
            sha256: sha(content),
          });
        } else {
          const value = JSON.parse(content.toString());
          assert.equal(value.hostId, hostId);
          if (prior)
            assert(
              ![priorOriginal.record.id, prior.scenarios[0].operationId].includes(
                value.clientMessageId ?? value.clientOperationId,
              ),
              'A prior original ID cannot be replayed',
            );
          const key = url.pathname + ':' + (value.clientMessageId ?? value.clientOperationId);
          assert(!submittedMethods.has(key), 'Original POST cannot be replayed');
          submittedMethods.add(key);
          if (Object.hasOwn(value, 'text')) {
            const index = fixture.activeCase,
              row = cases[index],
              claim = claims.get(index);
            assert(claim && !submitted.has(index));
            assert.equal(value.text, row.text);
            assert.equal(value.model, model.id);
            assert.equal(value.effort, 'xhigh');
            assert.equal(value.speed, 'fast');
            assert.equal(value.permissionMode, 'read-only');
            assert.equal(url.pathname, index === 1 ? '/v1/replies' : '/v1/desktop/creations');
            if (index === 1) assert.equal(value.threadId, report.firstThreadId);
            else {
              assert.equal(value.projectId, projectId);
              assert.deepEqual(
                value.attachments,
                claim.files.map((p) => p.file),
              );
            }
            submitted.set(index, {
              operationId: value.clientOperationId ?? value.clientMessageId,
              textSha256: sha(value.text),
            });
          }
          if (url.pathname === '/v1/desktop/creations/submit')
            assert.equal(value.clientOperationId, submitted.get(fixture.activeCase)?.operationId);
          if (url.pathname === '/v1/desktop/open')
            assert.equal(value.threadId, report.firstThreadId);
          report.posts.push({
            path: url.pathname,
            index: fixture.activeCase,
            bodySha256: sha(content),
            operationId: value.clientMessageId ?? value.clientOperationId,
          });
        }
        await save();
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
            ...(content
              ? {
                  'Content-Type': req.headers['content-type'],
                  ...(req.headers['x-content-sha256']
                    ? { 'X-Content-SHA256': req.headers['x-content-sha256'] }
                    : {}),
                }
              : {}),
          },
          ...(content ? { body: content } : {}),
          redirect: 'error',
          signal: entry.controller.signal,
        });
        const value = Buffer.from(await response.arrayBuffer());
        res.writeHead(response.status, {
          'Content-Type': response.headers.get('content-type') ?? 'application/json',
        });
        res.end(value);
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
      const value = JSON.parse((await bytes(req)).toString());
      assert.equal(value.nonce, nonce);
      if (req.url === '/picker-gate') {
        assert(pickerEdges && preflightOnly && fixture.activeCase === -1);
        assert(report.posts.length === 0 && report.uploads.length === 0);
        assert(['arm', 'status', 'release'].includes(value.action));
        const testPackage = pkg + '.test';
        if (value.action === 'arm') {
          execFileSync(
            adb,
            [
              '-s',
              serial,
              'shell',
              'run-as ' +
                testPackage +
                " sh -c 'rm -f files/owned-read-started files/owned-read-finished files/owned-read-failure files/owned-read-release; cat > files/owned-read-gate.json'",
            ],
            {
              input: JSON.stringify({ fileName: cases[0].fileName, nonce }),
              encoding: 'utf8',
              timeout: 10000,
            },
          );
        } else if (value.action === 'release') {
          execFileSync(
            adb,
            [
              '-s',
              serial,
              'shell',
              'run-as ' +
                testPackage +
                " sh -c 'cat > files/owned-read-release.tmp; mv files/owned-read-release.tmp files/owned-read-release; rm -f files/owned-read-gate.json'",
            ],
            { input: nonce, encoding: 'utf8', timeout: 10000 },
          );
        }
        const started = await android(
          'shell',
          'run-as',
          testPackage,
          'cat',
          'files/owned-read-started',
        ).catch(() => '');
        const finished = await android(
          'shell',
          'run-as',
          testPackage,
          'cat',
          'files/owned-read-finished',
        ).catch(() => '');
        return json(res, {
          started: started === cases[0].fileName,
          finished: finished === cases[0].fileName,
        });
      }
      if (req.url === '/warm-ready') {
        assert.equal(report.posts.length, 0);
        claimFiles(value, cases[0]);
        assert.equal(value.text, '');
        report.warm = value;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/warm-reply-ready') {
        assert(systemPicker && !preflightOnly && report.scenarios.length === 1);
        assert.equal(value.threadId, report.firstThreadId);
        claimFiles({ ...value, index: 1 }, cases[1]);
        report.warmReply = value;
        await save();
        return json(res, { ok: true });
      }
      if (req.url === '/cold-preflight') {
        assert(preflightOnly && report.posts.length === 0 && report.uploads.length === 0);
        assert.notEqual(value.pid, report.warm.pid);
        assert.equal(value.targetKey, report.warm.targetKey);
        assert.deepEqual(value.files, report.warm.files);
        report.coldPreflight = {
          pid: value.pid,
          targetKey: value.targetKey,
          files: value.files.map((p) => p.file),
        };
        await save();
        return json(res, { ok: true });
      }
      assert(!preflightOnly);
      if (req.url === '/claim-case') {
        const row = cases[value.index];
        assert(row && value.index === fixture.activeCase && !claims.has(row.index));
        claimFiles(value, row);
        if (row.index === 0) {
          assert.notEqual(value.pid, report.warm.pid);
          assert.equal(value.targetKey, report.warm.targetKey);
          assert.deepEqual(value.files, report.warm.files);
          assert.equal(report.posts.length, 0);
        }
        if (row.index === 1) {
          assert.equal(value.threadId, report.firstThreadId);
          if (systemPicker) {
            assert.notEqual(value.pid, report.warmReply.pid);
            assert.deepEqual(value.files, report.warmReply.files);
            assert.equal(value.targetKey, report.warmReply.targetKey);
          }
        } else assert.equal(value.threadId, '');
        claims.set(row.index, value);
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
    hostId,
    token,
    endpoint: 'http://127.0.0.1:' + proxyPort,
    control: 'http://127.0.0.1:' + controlPort,
    controlKey,
    projectId,
    model: model.id,
    preflightOnly,
    systemPicker,
    pickerEdges,
    pickerPackage: systemPicker
      ? (
          await android(
            'shell',
            'cmd',
            'package',
            'resolve-activity',
            '--brief',
            '-a',
            'android.intent.action.OPEN_DOCUMENT',
            '-c',
            'android.intent.category.OPENABLE',
            '-t',
            'text/plain',
          )
        )
          .trim()
          .split('\n')
          .at(-1)
          .split('/')[0]
      : undefined,
    resumeRemaining: Boolean(prior),
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
    await installAndroidApkIfNeeded({
      adb,
      serial,
      packageName,
      apk,
      allowPhysical: !serial.startsWith('emulator-'),
    });
  }
  if (systemPicker) {
    assert(!prior, 'New immutable picker scenes only');
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' +
          pkg +
          '.test' +
          " sh -c 'mkdir -p files; umask 077; cat > files/owned-documents.json'",
      ],
      { input: JSON.stringify({ cases }), encoding: 'utf8', timeout: 10000 },
    );
  }
  for (const index of preflightOnly
    ? [-1, 0]
    : prior
      ? [1, 2]
      : systemPicker
        ? coldHostReply
          ? [-1, 0, -2, 1]
          : [-1, 0, -2, 1, 2]
        : [-1, 0, 1, 2]) {
    if (index === 0 || (systemPicker && index === 1))
      await android('shell', 'am', 'force-stop', pkg);
    if (coldHostReply && ((preflightOnly && index === 0) || index === 1))
      await restartOwnedHost(model.id, index === 1);
    await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
    fixture.activeCase = index;
    fixture.firstThreadId = report.firstThreadId ?? '';
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-attachment-config.json'",
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
          'com.agenthub.android.MobileAttachmentLiveTest#originalAttachmentCase',
          '-e',
          'attachmentStage',
          String(index),
          '-e',
          'nativeForeground',
          '1',
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
      /OK \(1 test\)/.test(log) && !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(log),
    );
    report.native.push({ index, status: 'passed', logSha256: sha(log) });
    if (pickerEdges && index === -1) {
      const proof = JSON.parse(
        await android(
          'shell',
          'run-as',
          pkg,
          'cat',
          'files/acceptance/P4-attachment-live/' + nonce + '/picker-edges.json',
        ),
      );
      assert(
        proof.cancelPreservesDraft &&
          proof.lateReturnKeepsOriginalDraft &&
          proof.lateReadKeepsOriginalDraft,
      );
      assert.equal(proof.businessInputCount, 0);
      report.pickerEdges = proof;
    }
    await save();
  }
  assert.equal(await fingerprint(), report.sourceSha256);
  assert.equal(report.rejectedPosts.length, 0);
  assert.equal(report.rejectedReads.length, 0);
  if (preflightOnly) {
    assert(report.coldPreflight && report.posts.length === 0 && submitted.size === 0);
  } else {
    const count = coldHostReply || prior ? 2 : 3;
    assert.equal(report.scenarios.length, count);
    assert.equal(submitted.size, count);
    assert.equal(report.uploads.length, coldHostReply ? 2 : prior ? 3 : 4);
    assert.equal(report.newRealModelInputsConfirmed, count);
    if (coldHostReply) assert(report.cleanColdHostReply);
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = clean(e.stack);
  process.exitCode = 1;
} finally {
  for (const [index, sent] of submitted)
    if (!report.scenarios.some((s) => s.index === index))
      report.unresolvedOriginalCases.push({ index, ...sent });
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
          'files/acceptance/P4-attachment-live/' + nonce,
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
  try {
    await stopOwnedHost('owned-attachment-examiner-close');
  } catch (error) {
    report.ownedHostCleanupError = clean(error.stack);
    report.status = 'failed';
    process.exitCode = 1;
  }
  if ((report.rejectedPosts.length || report.rejectedReads.length) && report.status === 'passed') {
    report.status = 'failed';
    report.error = 'Unexpected original reads/writes remain failed; never replay inputs';
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
