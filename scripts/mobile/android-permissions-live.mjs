import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile, realpath } from 'node:fs/promises';
import { homedir } from 'node:os';
import { isAbsolute, join, resolve } from 'node:path';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import {
  persistedToolEvidence,
  isPermissionDeniedResult,
} from '../host-permission-takeover-evidence.mjs';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

// Explicit opt-in: real original-desktop inputs, one native one-shot lease for each immutable case.
const preflightOnly = process.env.ANDROID_PERMISSION_PREFLIGHT_ONLY === '1';
assert(preflightOnly || process.env.ANDROID_PERMISSION_ALLOW_NEW_INPUTS === '1');
assert.equal(process.platform, 'darwin');
const serial = process.env.ANDROID_SERIAL;
assert(serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1');
const projectId = process.env.ANDROID_PERMISSION_PROJECT_ID;
assert.match(projectId ?? '', /^[a-f0-9-]{36}$/);
const projectRoot = await realpath(process.env.ANDROID_PERMISSION_PROJECT_ROOT ?? '');
const output = process.env.MOBILE_PERMISSION_ACCEPTANCE_OUTPUT;
assert(output && isAbsolute(output) && !resolve(output).startsWith(process.cwd() + '/'));
const root = join(output, 'permission-live-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
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
assert((await android('shell', 'dumpsys', 'power')).includes('mWakefulness=Awake'));
const pkg = 'com.easyai.agenthub.android.acceptance';
const hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('base64url');
const sha = (value) => createHash('sha256').update(value).digest('hex');
const probeDirectory = join(
  homedir(),
  '.local/share/agent-hub-android-permission-acceptance',
  nonce,
);
await mkdir(probeDirectory, { recursive: true, mode: 0o700 });
const probePath = join(probeDirectory, 'probe.txt');
await writeFile(probePath, 'UNTOUCHED_' + nonce, { mode: 0o600, flag: 'wx' });
const quote = (s) => "'" + s.replaceAll("'", "'\\''") + "'";
const cases = ['default', 'read-only', 'full-access', 'default'].map((choice, index) => {
  const marker = `ANDROID_P4_PERMISSION_${nonce}_${index}`;
  const code = `from pathlib import Path; p=Path(${JSON.stringify(probePath)}); p.write_text(${JSON.stringify(marker)}); print(p.read_text())`;
  const text = `专用 Android 权限验收 ${marker}。只操作指定的验收文件，不修改项目、其他文件或聊天，不派生子任务、不联系其他 Agent。请使用 exec_command 按当前默认权限实际执行一次这条命令：python3 -c ${quote(code)}。不要设置 sandbox_permissions，不申请提升权限。如果当前权限拒绝，保留拒绝结果，不重试、不换工具绕过。无论成功或拒绝，最终只原样回复 ${marker}。`;
  return { index, choice, marker, text, textSha256: sha(text) };
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
      'scripts/mobile/android-permissions-live.mjs',
      'scripts/host-permission-takeover-evidence.mjs',
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
  phase: 'P4',
  scope:
    'Physical Android/native original desktop permissions and process-cold draft/original restoration; USB loopback transport, not LAN/TLS/VPN proof',
  status: 'running',
  actualOwnerPermissionsVerified: false,
  actualProcessColdRestoreVerified: false,
  preflightOnly,
  hostId,
  nonce,
  serial,
  projectId,
  projectRoot,
  sourceSha256: await fingerprint(),
  adapterSha256: preparedAdapterDigest(),
  api: Number(await android('shell', 'getprop', 'ro.build.version.sdk')),
  claims: [],
  posts: [],
  rejectedPosts: [],
  rejectedReads: [],
  native: [],
  scenarios: [],
  newRealModelInputsConfirmed: 0,
  unresolvedOriginalCases: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable: process.env.CODEX_EXECUTABLE },
  hostId,
  'Android permissions independent read-only witness',
);
assert(process.env.CODEX_EXECUTABLE);
let service, proxy, control, token, fixture, endpoint;
const reverses = [],
  submitted = new Map(),
  claims = new Set(),
  submittedMethods = new Set();
const json = (res, value, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
const body = async (req) => {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    assert(size < 65536);
    chunks.push(chunk);
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
async function observe(index, threadId, operationId, nativePid) {
  assert(Number.isSafeInteger(nativePid) && nativePid > 0);
  if (index === 3)
    assert.notEqual(nativePid, report.scenarios.find((s) => s.index === 2).nativePid);
  const row = cases[index],
    sent = submitted.get(index);
  assert(sent && sent.operationId === operationId);
  const clientId = index === 0 ? 'first-' + operationId : operationId;
  const value = await wait(async () => {
    await witness.ipc.follow(threadId, 5000, true);
    const value = witness.ipc.state(threadId);
    if (!value || !witness.ipc.hasOwner(threadId)) return null;
    return desktopTurns(value).some(
      (t) =>
        t.status === 'completed' &&
        t.items?.some((item) => item.type === 'userMessage' && item.clientId === clientId),
    )
      ? value
      : null;
  }, 'Exact original owner completion');
  assert.equal(value.id, threadId);
  assert.equal(value.hostId, 'local');
  assert.equal(await realpath(value.cwd), projectRoot);
  const turns = desktopTurns(value),
    matching = turns.filter((t) =>
      t.items?.some((item) => item.type === 'userMessage' && item.clientId === clientId),
    );
  assert.equal(matching.length, 1);
  const turn = matching[0];
  assert.equal(turn.status, 'completed');
  assert(
    turn.items.some((item) => item.type === 'agentMessage' && item.text.trim() === row.marker),
  );
  assert.equal(turn.params.model ?? turn.params.collaborationMode?.settings?.model, fixture.model);
  assert.equal(
    turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort,
    fixture.effort,
  );
  assert.equal(turn.params.serviceTier, 'priority');
  assert.equal(turn.params.sandboxPolicy.type, index === 1 ? 'readOnly' : 'dangerFullAccess');
  assert.equal(turn.params.approvalPolicy, index === 1 ? 'on-request' : 'never');
  assert.equal(turn.params.approvalsReviewer, 'user');
  const history = await witness.reader.read('thread/read', { threadId, includeTurns: true });
  assert.equal(history.thread.id, threadId);
  assert.equal(
    history.thread.turns.flatMap((t) => t.items ?? []).filter((item) => item.type === 'userMessage')
      .length,
    index + 1,
    'Full canonical history, not a bounded owner tail, proves total input count',
  );
  assert.equal(await realpath(history.thread.cwd), projectRoot);
  const path = history.thread.path;
  assert(typeof path === 'string' && path.includes(threadId));
  const rows = await wait(
    async () =>
      (await readFile(path, 'utf8'))
        .trim()
        .split('\n')
        .map((line) => JSON.parse(line)),
    'Actual persisted tool spans',
  );
  const evidence = persistedToolEvidence(rows, turn.turnId);
  assert(
    evidence.calls.some((call) => JSON.stringify(call).includes(probePath)),
    'An actual tool call must contain the owned probe path',
  );
  if (index === 1) {
    assert(
      evidence.results.some(isPermissionDeniedResult),
      'Actual nonzero permission denial, not model narrative',
    );
    assert.equal(await readFile(probePath, 'utf8'), cases[0].marker);
  } else {
    assert(
      evidence.results.some(
        (result) => result.exit_code === 0 && result.output.includes(row.marker),
      ),
      'Actual successful process result',
    );
    assert.equal(await readFile(probePath, 'utf8'), row.marker);
  }
  await writeFile(
    join(root, `case-${index}-tool-evidence.json`),
    JSON.stringify(evidence, null, 2),
    { mode: 0o600 },
  );
  const result = {
    index,
    marker: row.marker,
    threadId,
    operationId,
    clientId,
    turnId: turn.turnId,
    sandboxPolicy: turn.params.sandboxPolicy,
    approvalPolicy: turn.params.approvalPolicy,
    actualToolVerified: true,
    nativePid,
    exactInputCount: 1,
    textSha256: row.textSha256,
  };
  report.scenarios.push(result);
  report.newRealModelInputsConfirmed = report.scenarios.length;
  report.threadId = threadId;
  await save();
  return result;
}
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  report.defaults = await witness.defaultPermissions(projectRoot);
  assert.equal(report.defaults.sandboxPolicy.type, 'dangerFullAccess');
  assert.equal(report.defaults.approvalPolicy, 'never');
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
    HOST_AGENT_NAME: 'Android permissions DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  service = await startRuntimeServer();
  token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  endpoint = 'http://127.0.0.1:' + service.server.address().port;
  const workspace = await service.session.workspace();
  const project = workspace.projects.find((p) => p.id === projectId);
  report.workspaceGate = {
    capabilities: workspace.capabilities,
    readTarget: workspace.readTarget,
    projectId: project?.id ?? null,
    projectRoots: project?.roots ?? [],
  };
  assert(
    project && (await Promise.all(project.roots.map((p) => realpath(p)))).includes(projectRoot),
  );
  assert(
    workspace.capabilities.includes('desktop:creations'),
    'Raw Host creation capability; the native shared layer derives staged client capabilities',
  );
  const models = (await service.session.models()).models;
  const model = models.find((m) => m.isDefault);
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
  ]);
  proxy = createServer((req, res) => {
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
        const value = await body(req);
        assert.equal(value.hostId, hostId);
        const key = url.pathname + ':' + (value.clientMessageId ?? value.clientOperationId);
        assert(!submittedMethods.has(key), 'No replay of an original write');
        submittedMethods.add(key);
        if (url.pathname === '/v1/desktop/open') {
          assert(report.threadId);
          assert.equal(value.threadId, report.threadId, 'Open only this new acceptance owner');
        }
        if (value.text !== undefined) {
          const row = cases.find((row) => row.text === value.text);
          assert(row && claims.has(row.index));
          assert(!submitted.has(row.index));
          assert.equal(value.model, model.id);
          assert.equal(value.effort, 'xhigh');
          assert.equal(value.speed, 'fast');
          assert.equal(value.permissionMode ?? null, row.choice === 'default' ? null : row.choice);
          if (row.index === 0) {
            assert.equal(url.pathname, '/v1/desktop/creations');
            assert.equal(value.projectId, projectId);
          } else {
            assert.equal(url.pathname, '/v1/replies');
            assert.equal(value.threadId, report.threadId);
          }
          submitted.set(row.index, {
            operationId: value.clientOperationId ?? value.clientMessageId,
            textSha256: sha(value.text),
          });
        }
        report.posts.push({
          path: url.pathname,
          bodySha256: sha(JSON.stringify(value)),
          operationId: value.clientMessageId ?? value.clientOperationId,
        });
        await save();
        bytes = JSON.stringify(value);
      }
      const upstream = await fetch(endpoint + req.url, {
        method: req.method,
        headers: {
          Authorization: 'Bearer ' + token,
          ...(bytes ? { 'Content-Type': 'application/json' } : {}),
        },
        ...(bytes ? { body: bytes } : {}),
        redirect: 'error',
        signal: AbortSignal.timeout(30000),
      });
      const responseBytes = Buffer.from(await upstream.arrayBuffer());
      res.writeHead(upstream.status, {
        'Content-Type': upstream.headers.get('content-type') ?? 'application/json',
      });
      res.end(responseBytes);
    })().catch((e) => {
      report.proxyError = e.message;
      if (req.method === 'POST') report.rejectedPosts.push({ path: req.url, error: e.message });
      else report.rejectedReads.push({ path: req.url, error: e.message });
      void save();
      if (!res.destroyed && !res.headersSent) json(res, { error: e.message }, 500);
      else res.destroy();
    });
  });
  const proxyPort = await listen(proxy);
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
        assert.equal(value.projectId, projectId);
        report.nativePreflight = true;
        report.nativePreflightPid = value.pid;
        return json(res, { ok: true });
      }
      assert(!preflightOnly);
      if (req.url === '/claim-case') {
        const row = cases[value.index];
        assert(
          row &&
            value.index === fixture.activeCase &&
            value.text === row.text &&
            !claims.has(row.index),
        );
        assert.deepEqual(await witness.defaultPermissions(projectRoot), report.defaults);
        if (row.index === 0) {
          assert.equal(report.posts.length, 0, 'Sharing confirmation must not submit content');
          assert.equal(submitted.size, 0);
        }
        claims.add(row.index);
        report.claims.push({ index: row.index, target: value.target, textSha256: sha(value.text) });
        await save();
        return json(res, { ready: true });
      }
      assert.equal(req.url, '/case-complete');
      assert.equal(value.index, fixture.activeCase);
      json(res, await observe(value.index, value.threadId, value.operationId, value.pid));
    })().catch((e) => {
      report.witnessError = e.stack;
      void save();
      json(res, { error: e.message }, 500);
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
    effort: 'xhigh',
    speed: 'fast',
    coldDraft: 'Android 冷恢复未发送草稿 ' + nonce,
    cases,
  };
  report.settings = { model: model.id, effort: 'xhigh', speed: 'fast' };
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
  for (const index of preflightOnly ? [-1] : [0, 1, 2, 3]) {
    if (index === 3) await android('shell', 'am', 'force-stop', pkg);
    await android('shell', 'am', 'start', '-W', '-n', pkg + '/com.agenthub.android.MainActivity');
    fixture.activeCase = index;
    fixture.threadId = report.threadId ?? '';
    if (index === 3) fixture.warmProcessId = report.scenarios.find((s) => s.index === 2).nativePid;
    execFileSync(
      adb,
      [
        '-s',
        serial,
        'shell',
        'run-as ' + pkg + " sh -c 'umask 077; cat > files/mobile-permission-config.json'",
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
          'com.agenthub.android.MobilePermissionLiveTest#originalOwnerPermissionCase',
          '-e',
          'permissionCase',
          String(index),
          pkg + '.test/androidx.test.runner.AndroidJUnitRunner',
        ],
        { encoding: 'utf8', timeout: 350000, maxBuffer: 16 * 1024 * 1024 },
      );
    } catch (e) {
      await writeFile(
        join(root, `case-${index}-command-failure.json`),
        JSON.stringify(
          {
            code: e.code,
            killed: e.killed,
            stdout: e.stdout?.replaceAll(token, '[REDACTED]').replaceAll(controlKey, '[REDACTED]'),
            stderr: e.stderr?.replaceAll(token, '[REDACTED]').replaceAll(controlKey, '[REDACTED]'),
          },
          null,
          2,
        ),
        { mode: 0o600 },
      );
      throw e;
    }
    const log = (result.stdout + result.stderr)
      .replaceAll(token, '[REDACTED]')
      .replaceAll(controlKey, '[REDACTED]');
    await writeFile(join(root, `case-${index}-instrumentation.log`), log, { mode: 0o600 });
    assert(
      /OK \(1 test\)/.test(log) && !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(log),
    );
    report.native.push({ index, status: 'passed', logSha256: sha(log) });
    await save();
  }
  assert.equal(await fingerprint(), report.sourceSha256, 'Source changed during actual acceptance');
  assert.equal(
    report.rejectedPosts.length,
    0,
    'No rejected or repeated native write may be hidden by this examiner proxy',
  );
  assert.equal(report.rejectedReads.length, 0, 'No blocked original read may be hidden');
  if (preflightOnly) {
    assert(report.nativePreflight && report.posts.length === 0);
  } else {
    assert.equal(report.scenarios.length, 4);
    assert.equal(submitted.size, 4);
    assert.equal(report.newRealModelInputsConfirmed, 4);
    report.actualOwnerPermissionsVerified = true;
    report.actualProcessColdRestoreVerified = true;
  }
  report.status = 'passed';
} catch (e) {
  report.status = 'failed';
  report.error = e.stack
    ?.replaceAll(token ?? '\0', '[REDACTED]')
    .replaceAll(controlKey, '[REDACTED]');
  process.exitCode = 1;
} finally {
  // Preserve unknown originals and the isolated Host journal. Closing transports does not cancel a model Turn.
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
          'files/acceptance/P4-permission/' + nonce,
        ],
        { encoding: 'buffer', timeout: 10000, maxBuffer: 32 * 1024 * 1024 },
      )
    ).stdout;
    await writeFile(join(root, 'native-evidence.tar'), archive, { mode: 0o600 });
  } catch (e) {
    report.artifactError = e.message;
  }
  await android('shell', 'am', 'force-stop', pkg).catch(() => {});
  for (const port of reverses) await android('reverse', '--remove', 'tcp:' + port).catch(() => {});
  for (const server of [proxy, control])
    if (server) {
      server.closeAllConnections();
      await new Promise((done) => server.close(done));
    }
  witness.close();
  service?.close();
  if (
    (report.rejectedPosts.length > 0 || report.rejectedReads.length > 0) &&
    report.status === 'passed'
  ) {
    report.status = 'failed';
    report.error =
      'Rejected native reads or writes were observed; original inputs must not be replayed';
    process.exitCode = 1;
  }
  await save();
  console.log(
    JSON.stringify({
      root,
      status: report.status,
      nativeCases: report.native.length,
      confirmedNewModelInputs: report.newRealModelInputsConfirmed,
      unresolvedOriginalCases: report.unresolvedOriginalCases.length,
      error: report.error,
    }),
  );
}
