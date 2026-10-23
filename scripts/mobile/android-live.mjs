import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomUUID, randomBytes } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, readdir, copyFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { createInterface } from 'node:readline';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { CodexRuntime } from '../../packages/codex-adapter/dist/runtime.js';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

// This examiner owns only its isolated Host. It never terminates the original desktop.
const executable = process.env.CODEX_EXECUTABLE;
const serial = process.env.ANDROID_SERIAL;
assert(
  executable &&
    (/^emulator-\d+$/.test(serial ?? '') ||
      (process.env.ANDROID_ALLOW_PHYSICAL === '1' && /^[a-zA-Z0-9]+$/.test(serial ?? ''))),
  'Select installed CLI and a dedicated emulator or explicitly authorized physical device',
);
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(process.env.ANDROID_HOME ?? local.androidSdk, 'platform-tools/adb');
const android = (...args) =>
  execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' }).trim();
assert.equal(android('get-state'), 'device');
const root = resolve('.local/mobile-acceptance/A4/live-' + serial + '-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const state = join(root, 'host');
await mkdir(state, { mode: 0o700 });
const hostId = randomUUID(),
  nonce = randomUUID().slice(0, 8),
  controlKey = randomBytes(32).toString('hex');
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
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  hostId,
  'Android independent native witness',
);
const report = {
  phase: 'A4',
  scope:
    'Isolated real Electron/model/native creation, reply and Host recovery subset; not full stage completion',
  status: 'running',
  nonce,
  hostId,
  serial,
  api: Number(android('shell', 'getprop', 'ro.build.version.sdk')),
  adapterSha256: preparedAdapterDigest(),
  hostPids: [],
  commands: [],
  observations: [],
  independent: [],
};
const hash = async (path) =>
  createHash('sha256')
    .update(await readFile(path))
    .digest('hex');
const fingerprint = async () => {
  const names = execFileSync(
    'git',
    [
      'ls-files',
      '-co',
      '--exclude-standard',
      'apps/android',
      'apps/host-agent',
      'packages/mobile-core',
      'packages/protocol',
      'packages/codex-adapter',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const h = createHash('sha256');
  for (const name of new Set(names))
    h.update(name + '\0')
      .update(await readFile(name))
      .update('\0');
  return h.digest('hex');
};
report.sourceSha256 = await fingerprint();
report.toolSha256 = await hash('scripts/mobile/android-live.mjs');
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const wait = async (probe, name, timeout = 180000) => {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const r = await probe();
    if (r) return r;
    await new Promise((done) => setTimeout(done, 250));
  }
  throw Error('Timed out: ' + name);
};
let host, endpoint, token, proxy, control, proxyPort, controlPort, examiner;
let dropNextSubmit = false,
  queryBlocked = false;
const request = async (route) => {
  const r = await fetch(endpoint + route, {
    headers: { Authorization: 'Bearer ' + token },
    redirect: 'error',
    signal: AbortSignal.timeout(45000),
  });
  assert.equal(r.status, 200, 'Isolated Host query');
  return r.json();
};
const terminate = async () => {
  const current = host;
  host = undefined;
  if (!current || current.exitCode !== null) return;
  await new Promise((done) => {
    current.once('close', done);
    current.kill('SIGTERM');
  });
};
const launch = async () => {
  host = spawn(process.execPath, [resolve('apps/host-agent/dist/index.js')], {
    env: {
      ...process.env,
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_EXECUTABLE: executable,
      CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: join(state, 'bridge.json'),
      HOST_AGENT_STATE_DIR: state,
      HOST_AGENT_ROOT: process.cwd(),
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: report.hostPort ? String(report.hostPort) : '0',
      HOST_AGENT_DISCOVERY_BONJOUR: '0',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  report.hostPids.push(host.pid);
  host.stderr.resume();
  let address;
  createInterface({ input: host.stdout }).on('line', (line) => {
    try {
      const v = JSON.parse(line);
      if (v.service === 'host-agent') address = v.address;
    } catch {
      // Host diagnostics can contain non-JSON lines; only the startup record is used.
    }
  });
  await wait(
    () => {
      if (host.exitCode !== null) throw Error('Dedicated Host failed');
      return address;
    },
    'Host startup',
    30000,
  );
  report.hostPort = address.port;
  endpoint = 'http://127.0.0.1:' + address.port;
  token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  await save();
};
const creations = async () =>
  Object.values(
    JSON.parse(await readFile(join(state, 'desktop-creations/creations.json'), 'utf8')),
  );
const inputs = new Map();
const replyInputs = new Map();
const postRoutes = new Set([
  '/v1/replies',
  '/v1/desktop/open',
  '/v1/desktop/creations',
  '/v1/desktop/creations/submit',
  '/v1/desktop/creations/detach',
  '/v1/desktop/creation-attachments',
  '/v1/attachments',
  '/v1/stop',
  '/v1/queue/cancel',
  '/v1/approvals',
]);
let originalScreenTimeout;
try {
  if (!serial.startsWith('emulator-')) {
    originalScreenTimeout = android('shell', 'settings', 'get', 'system', 'screen_off_timeout');
    android('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
    android('shell', 'settings', 'put', 'system', 'screen_off_timeout', '1800000');
  }
  await witness.connect();
  report.desktopVersion = witness.version;
  const archivePath = process.env.ANDROID_ARCHIVE_EVIDENCE;
  assert(
    archivePath?.startsWith('.local/android-development/a4/'),
    'Select exact owned archive evidence',
  );
  const archive = JSON.parse(await readFile(archivePath, 'utf8'));
  const prior = JSON.parse(await readFile(archive.ownedPriorReport, 'utf8'));
  assert.equal(prior.status, 'passed');
  assert.equal(await hash(archive.ownedPriorReport), archive.priorOwnedReportSha256);
  assert(
    prior.observations.some(
      (o) => o.threadId === archive.threadId && o.marker === 'RECOVERY_' + prior.nonce,
    ),
  );
  assert(
    archive.archived && archive.desktopWorkspaceExcluded && archive.ownerBeforeArchiveCompleted,
  );
  const originalWorkspace = await witness.reader.desktopWorkspace();
  assert(
    !originalWorkspace.threads.some((t) => t.id === archive.threadId),
    'Owned historical test chat stays archived',
  );
  report.archiveEvidenceSha256 = await hash(archivePath);
  report.archiveScope =
    'Examiner-owned ordinary historical chat; prepared bridge has no installation/control-chat dependency';
  report.archivedBeforeHostLaunch = true;
  await writeFile(
    join(state, 'bridge.json'),
    JSON.stringify({ hostId, desktopVersion: witness.version }),
    { mode: 0o600 },
  );
  await launch();
  const workspace = await request('/v1/workspace');
  assert.equal(workspace.hostId, hostId);
  assert(
    workspace.capabilities.includes('commands:target-bound:v1') &&
      workspace.capabilities.includes('desktop:creations'),
  );
  const managed = new CodexRuntime({ mode: 'managed-runtime', executable });
  const projects = [];
  try {
    await managed.connect();
    for (const kind of ['project', 'empty']) {
      const cwd = join(root, kind);
      await mkdir(cwd, { mode: 0o700 });
      const v = await managed.call('project/create', {
        name: 'Android DEV ' + kind + ' ' + nonce,
        roots: [{ path: cwd }],
        idempotencyKey: randomUUID(),
      });
      projects.push({ kind, id: v.project.id, name: v.project.name });
    }
  } finally {
    managed.close();
  }
  report.projectIds = projects.map((p) => p.id);
  const startWorkspace = await witness.reader.desktopWorkspace();
  assert.equal(
    startWorkspace.threads.filter((t) =>
      projects.some((p) => t.projectId === p.id || t.projectIds?.includes(p.id)),
    ).length,
    0,
  );
  proxy = createServer((req, res) => {
    void (async () => {
      try {
        const url = new URL(req.url, 'http://127.0.0.1');
        assert(!req.headers.origin && ['GET', 'POST'].includes(req.method));
        if (req.method === 'POST')
          assert(postRoutes.has(url.pathname), 'Named native command only');
        else
          assert(
            [
              '/v1/workspace',
              '/v1/history',
              '/v1/models',
              '/v1/events',
              '/v1/receipts',
              '/v1/desktop/creation-receipts',
              '/v1/desktop/open-receipts',
            ].includes(url.pathname),
            'Named native query only',
          );
        assert.equal(req.headers.authorization, 'Bearer ' + token);
        const chunks = [];
        let length = 0;
        for await (const chunk of req) {
          length += chunk.length;
          assert(length <= 25 * 1024 * 1024);
          chunks.push(chunk);
        }
        const body = Buffer.concat(chunks);
        if (req.method === 'POST') {
          report.commands.push({
            path: url.pathname,
            bodySha256: createHash('sha256').update(body).digest('hex'),
            hostGeneration: report.hostPids.length,
          });
          if (url.pathname === '/v1/desktop/creations') {
            const input = JSON.parse(body);
            assert.equal(input.hostId, hostId);
            inputs.set(input.clientOperationId, input);
          }
          if (url.pathname === '/v1/replies') {
            const input = JSON.parse(body);
            assert.equal(input.hostId, hostId);
            assert(!replyInputs.has(input.clientMessageId), 'No duplicate reply POST');
            replyInputs.set(input.clientMessageId, input);
          }
        }
        if (queryBlocked && url.pathname === '/v1/desktop/creation-receipts') {
          res.writeHead(503).end('{"error":"Controlled connection outage"}');
          return;
        }
        const response = await fetch(endpoint + url.pathname + url.search, {
          method: req.method,
          headers: {
            Authorization: 'Bearer ' + token,
            ...(req.headers['content-type'] ? { 'Content-Type': req.headers['content-type'] } : {}),
            ...(req.headers['x-content-sha256']
              ? { 'X-Content-SHA256': req.headers['x-content-sha256'] }
              : {}),
          },
          ...(req.method === 'POST' ? { body } : {}),
          redirect: 'error',
          signal: AbortSignal.timeout(45000),
        });
        const bytes = Buffer.from(await response.arrayBuffer());
        if (dropNextSubmit && url.pathname === '/v1/desktop/creations/submit') {
          dropNextSubmit = false;
          report.droppedSubmitResponse = true;
          await save();
          res.destroy();
          return;
        }
        res
          .writeHead(response.status, {
            'content-type': response.headers.get('content-type') ?? 'application/json',
          })
          .end(bytes);
      } catch (error) {
        if (!res.headersSent) res.writeHead(502);
        res.end(JSON.stringify({ error: 'Isolated acceptance route unavailable' }));
        report.proxyError = error.message;
      }
    })();
  });
  await new Promise((done) => proxy.listen(0, '127.0.0.1', done));
  proxyPort = proxy.address().port;
  control = createServer((req, res) => {
    void (async () => {
      try {
        assert.equal(req.method, 'POST');
        assert.equal(req.headers.authorization, 'Bearer ' + controlKey);
        assert(!req.headers.origin);
        const url = new URL(req.url, 'http://127.0.0.1');
        const chunks = [];
        let size = 0;
        for await (const chunk of req) {
          size += chunk.length;
          assert(size <= 150000);
          chunks.push(chunk);
        }
        const body = JSON.parse(Buffer.concat(chunks));
        assert.equal(body.nonce, nonce);
        if (url.pathname === '/arm-submit-loss') {
          dropNextSubmit = true;
          queryBlocked = true;
        } else if (url.pathname === '/await-first-input') {
          const record = await wait(async () => {
            const records = await creations();
            return records.find((v) => v.text.includes('RECOVERY_' + nonce) && v.threadId);
          }, 'Original recovery input submitted before native detach');
          const journal = JSON.parse(
            await readFile(
              join(state, 'desktop-prepared-bridge', record.clientOperationId + '.json'),
              'utf8',
            ),
          );
          assert.equal(journal.phase, 'submitted');
          assert.equal(journal.exitCode, 0);
          assert.equal(journal.threadId, record.threadId);
          report.submittedBeforeDetach = true;
          report.submittedBeforeDetachOperation = record.clientOperationId;
        } else if (url.pathname === '/restart-host') {
          const record = await wait(async () => {
            const records = await creations();
            return records.find((v) => v.text.includes('RECOVERY_' + nonce) && v.threadId);
          }, 'Recovery creation thread');
          const journal = await wait(async () => {
            try {
              const v = JSON.parse(
                await readFile(
                  join(state, 'desktop-prepared-bridge', record.clientOperationId + '.json'),
                  'utf8',
                ),
              );
              return v.phase === 'submitted' && v.exitCode === 0 ? v : null;
            } catch {
              return null;
            }
          }, 'First input durable and preparation exited');
          assert.throws(() => process.kill(journal.pid, 0));
          report.restartedOperation = record.clientOperationId;
          await terminate();
          await launch();
          queryBlocked = false;
          report.hostRestarted = true;
          await witness.ipc.follow(record.threadId, 15000, true);
          await wait(
            () =>
              desktopTurns(witness.ipc.state(record.threadId) ?? {}).some(
                (turn) =>
                  turn.status === 'completed' &&
                  turn.items?.some(
                    (item) => item.type === 'agentMessage' && item.text === 'RECOVERY_' + nonce,
                  ),
              ),
            'Original owner completed recovery input before native manual query',
          );
        } else if (url.pathname === '/observation') {
          assert.equal(body.hostId, hostId);
          assert(body.threadId && body.marker && body.operationId);
          report.observations.push(body);
        } else throw Error('Unknown control route');
        await save();
        res.writeHead(200, { 'content-type': 'application/json' }).end('{"ok":true}');
      } catch (error) {
        report.controlError = error.message;
        await save();
        res
          .writeHead(500, { 'content-type': 'application/json' })
          .end('{"error":"Acceptance control failed"}');
      }
    })();
  });
  await new Promise((done) => control.listen(0, '127.0.0.1', done));
  controlPort = control.address().port;
  android('reverse', `tcp:${proxyPort}`, `tcp:${proxyPort}`);
  android('reverse', `tcp:${controlPort}`, `tcp:${controlPort}`);
  const app = '.build/native/android/outputs/apk/debug/android-debug.apk',
    test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
  report.apkSha256 = await hash(app);
  report.testApkSha256 = await hash(test);
  await mkdir(join(root, 'artifacts'));
  await copyFile(app, join(root, 'artifacts/android-debug.apk'));
  await copyFile(test, join(root, 'artifacts/android-debug-androidTest.apk'));
  report.installation = [];
  for (const [packageName, apk] of [
    ['com.easyai.agenthub.android.debug', app],
    ['com.easyai.agenthub.android.debug.test', test],
  ])
    report.installation.push(
      await installAndroidApkIfNeeded({
        adb,
        serial,
        packageName,
        apk,
        allowPhysical: process.env.ANDROID_ALLOW_PHYSICAL === '1',
      }),
    );
  const configPath = join(root, 'native-config.json');
  await writeFile(
    configPath,
    JSON.stringify({
      nonce,
      minimumModelGapMillis: serial.startsWith('emulator-') ? 0 : 30000,
      hostId,
      endpoint: 'http://127.0.0.1:' + proxyPort,
      token,
      control: 'http://127.0.0.1:' + controlPort,
      controlKey,
      projects,
    }),
    { mode: 0o600 },
  );
  const temp = '/data/local/tmp/android-a4-' + nonce + '.json';
  android('push', configPath, temp);
  android(
    'shell',
    'run-as',
    'com.easyai.agenthub.android.debug',
    'cp',
    temp,
    'files/mobile-live-config.json',
  );
  android(
    'shell',
    'run-as',
    'com.easyai.agenthub.android.debug',
    'chmod',
    '600',
    'files/mobile-live-config.json',
  );
  android('shell', 'rm', temp);
  android(
    'shell',
    'rm',
    '-rf',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-live',
  );
  await save();
  console.log('Isolated real native acceptance started on API', report.api, 'Evidence:', root);
  if (!serial.startsWith('emulator-')) {
    android(
      'shell',
      'am',
      'start',
      '-W',
      '-n',
      'com.easyai.agenthub.android.debug/com.agenthub.android.MainActivity',
    );
  }
  examiner = spawn(
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
      'com.agenthub.android.MobileLiveCodexTest',
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  );
  let log = '';
  examiner.stdout.on('data', (v) => (log += v));
  examiner.stderr.on('data', (v) => (log += v));
  const code = await new Promise((done) => examiner.once('close', done));
  log = log.replaceAll(token, '[REDACTED]').replaceAll(controlKey, '[REDACTED]');
  await writeFile(join(root, 'instrumentation.log'), log, { mode: 0o600 });
  assert.equal(code, 0);
  assert(log.includes('OK (1 test)'));
  assert(!/FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/.test(log));
  assert.equal(report.observations.length, 5);
  assert.equal(report.submittedBeforeDetach, true);
  for (const observation of report.observations) {
    await witness.ipc.follow(observation.threadId, 15000, true);
    const completed = await wait(
      () =>
        desktopTurns(witness.ipc.state(observation.threadId) ?? {}).find(
          (t) =>
            t.status === 'completed' &&
            t.items?.some(
              (item) => item.type === 'agentMessage' && item.text === observation.marker,
            ),
        ),
      'Independent original owner ' + observation.marker,
    );
    assert(witness.ipc.hasOwner(observation.threadId));
    const input = inputs.get(observation.operationId);
    const markerParts = observation.marker.split('|');
    if (markerParts.length === 3) {
      const [marker, textCode, imageCode] = markerParts;
      const suffix = marker.startsWith('ANDROID_A4_PROJECT_') ? 'PROJECT' : 'REPLY';
      assert.match(textCode, /^TXT-[A-F0-9]{12}$/);
      assert.match(imageCode, /^IMG-[A-F0-9]{12}$/);
      const original = input ?? replyInputs.get(observation.operationId);
      assert(original && !original.text.includes(textCode) && !original.text.includes(imageCode));
      const expected = Buffer.from(textCode + '\n' + imageCode + '\n');
      const artifact = join(root, 'project', `native-result-${suffix}-${nonce}.txt`);
      assert.deepEqual(
        await readFile(artifact),
        expected,
        'Actual model generated exact attachment content',
      );
      const changes = completed.items.filter(
        (item) => item.type === 'fileChange' && item.status === 'completed',
      );
      assert(changes.length > 0, 'Actual original owner apply_patch, not a fabricated diff');
      const stored = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8')).attachments;
      const files = stored.filter(
        (a) =>
          a.threadId === observation.threadId &&
          [`native-${suffix}-${nonce}.txt`, `native-${suffix}-${nonce}.png`].includes(a.name),
      );
      assert.equal(files.length, 2);
      for (const file of files) {
        const path = resolve(state, file.path);
        assert(path.startsWith(state + '/'), 'Attachment stays in the isolated Host');
        const bytes = await readFile(path);
        assert.equal(bytes.length, file.sizeBytes);
        assert.equal(await hash(path), file.sha256);
      }
      report.independent.push({
        kind: 'mixed-attachment-model-content-and-native-final-copy',
        originalInputContainsCodes: false,
        exactArtifactSha256: createHash('sha256').update(expected).digest('hex'),
        actualFileChanges: changes.length,
        attachmentBytes: files.map((a) => ({
          mediaType: a.mediaType,
          sizeBytes: a.sizeBytes,
          sha256: a.sha256,
        })),
      });
    }
    if (input) {
      const record = (await creations()).find(
        (v) => v.clientOperationId === observation.operationId,
      );
      assert(record && record.threadId === observation.threadId);
      const turn = desktopTurns(witness.ipc.state(observation.threadId));
      const messages = turn
        .flatMap((t) => t.items ?? [])
        .filter(
          (item) =>
            item.type === 'userMessage' && item.clientId === 'first-' + observation.operationId,
        );
      assert.equal(messages.length, 1);
      assert(messages[0].content.some((c) => c.type === 'text' && c.text.startsWith(input.text)));
      const journal = JSON.parse(
        await readFile(
          join(state, 'desktop-prepared-bridge', observation.operationId + '.json'),
          'utf8',
        ),
      );
      assert.equal(journal.exitCode, 0);
      assert.throws(() => process.kill(journal.pid, 0));
      report.independent.push({
        kind: 'creation',
        operationId: observation.operationId,
        threadId: observation.threadId,
        owner: true,
        firstInputs: 1,
        preparationExited: true,
        projectId: record.projectId,
        originalTextSha256: createHash('sha256').update(input.text).digest('hex'),
        completedTurnId: completed.id,
      });
    } else {
      const original = replyInputs.get(observation.operationId);
      assert(original && original.threadId === observation.threadId);
      const exactInputs = completed.items.filter(
        (item) => item.type === 'userMessage' && item.clientId === observation.operationId,
      );
      assert.equal(exactInputs.length, 1, 'One original user input for the exact reply ID');
      report.independent.push({
        kind: 'reply',
        operationId: observation.operationId,
        threadId: observation.threadId,
        owner: true,
        completedTurnId: completed.id,
      });
    }
  }
  assert.equal(report.commands.filter((c) => c.path === '/v1/desktop/creations/submit').length, 4);
  assert.equal(report.commands.filter((c) => c.path === '/v1/replies').length, 1);
  const afterRestart = report.observations.find(
    (o) => o.marker === 'ANDROID_A4_AFTER_RESTART_' + nonce,
  );
  assert(afterRestart && inputs.has(afterRestart.operationId));
  assert.equal(
    report.commands.filter((c) => c.path === '/v1/desktop/creations/submit').at(-1).hostGeneration,
    2,
  );
  report.creationAfterHostRestart = true;
  assert(report.hostRestarted && report.droppedSubmitResponse);
  assert.equal(await fingerprint(), report.sourceSha256, 'Inputs changed during native acceptance');
  assert.equal(
    await hash('scripts/mobile/android-live.mjs'),
    report.toolSha256,
    'Examiner changed during native acceptance',
  );
  report.status = 'passed';
  report.freshModelExecution = true;
  report.completedTests = 1;
  report.failures = 0;
  report.skips = 0;
  report.remaining = [
    'Full stop/queue/steer/approval/question/plan native real scenarios',
    'Archived-controller lifecycle gate',
    'Full affected iOS and Android UI regression',
    'Phase closure and remote SHA verification',
  ];
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
} finally {
  if (originalScreenTimeout !== undefined) {
    if (originalScreenTimeout === 'null')
      android('shell', 'settings', 'delete', 'system', 'screen_off_timeout');
    else android('shell', 'settings', 'put', 'system', 'screen_off_timeout', originalScreenTimeout);
    report.screenTimeoutRestored = true;
  }
  try {
    assert(examiner, 'Instrumentation never started; no current screenshots');
    await mkdir(join(root, 'screenshots'));
    android(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-live/.',
      join(root, 'screenshots'),
    );
    report.screenshots = await Promise.all(
      (await readdir(join(root, 'screenshots')))
        .filter((n) => n.endsWith('.png'))
        .sort()
        .map(async (name) => ({
          name,
          sha256: await hash(join(root, 'screenshots', name)),
          reviewed: false,
        })),
    );
  } catch (error) {
    report.captureError = error.message;
  }
  if (report.status === 'passed' && report.screenshots.length !== 8) {
    report.status = 'failed';
    report.error = 'Required native first/reply attachment and Diff screenshots missing';
    process.exitCode = 1;
  }
  try {
    android(
      'shell',
      'run-as',
      'com.easyai.agenthub.android.debug',
      'rm',
      '-f',
      'files/mobile-live-config.json',
    );
  } catch {
    // The emulator may have disconnected; the private config stays inside its app sandbox.
  }
  await terminate();
  for (const [server, port] of [
    [proxy, proxyPort],
    [control, controlPort],
  ]) {
    if (server) {
      server.closeAllConnections();
      await new Promise((done) => server.close(done));
    }
    if (port)
      try {
        android('reverse', '--remove', `tcp:${port}`);
      } catch {
        // Removing an already-absent owned reverse mapping is harmless.
      }
  }
  witness.close();
  report.completedAt = new Date().toISOString();
  await save();
  console.log('Real native subset:', report.status, 'Evidence:', root);
}
