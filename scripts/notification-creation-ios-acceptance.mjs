import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { NotificationObserver } from '../packages/notification-runtime/dist/index.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';

const executable = process.env.CODEX_EXECUTABLE,
  device = process.env.IOS_SIMULATOR_ID;
assert(executable && device, 'Select the official CLI and a dedicated DEV simulator');
const output = resolve(
  process.env.NOTIFICATION_CREATION_OUTPUT ?? '.local/notification-creation-' + randomUUID(),
);
let previous;
try {
  previous = JSON.parse(await readFile(join(output, 'report.json'), 'utf8'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
if (previous) {
  assert(
    previous.originalOwnerVerified,
    'Reconcile the original creation first; do not replay an unknown submission',
  );
  assert.equal(previous.adapterSha256, preparedAdapterDigest());
  await writeFile(
    join(output, 'previous-attempt-' + randomUUID() + '.json'),
    JSON.stringify(previous, null, 2) + '\n',
    { mode: 0o600 },
  );
}
const stateDirectory = join(output, 'host-state'),
  hostId = previous?.hostId ?? randomUUID();
await mkdir(stateDirectory, { recursive: true, mode: 0o700 });
const witness = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  hostId,
  'Notification creation independent witness',
);
const report = {
  startedAt: new Date().toISOString(),
  status: 'running',
  hostId,
  adapterSha256: preparedAdapterDigest(),
  samples: 0,
  transientUnknownSamples: 0,
  falseNotices: [],
  creationQueries: 0,
  writesFromPhone: 0,
  reusedCompletedCreation: Boolean(previous),
  ...(previous
    ? {
        samples: previous.samples,
        transientUnknownSamples: previous.transientUnknownSamples,
        falseNotices: previous.falseNotices,
      }
    : {}),
};
const save = () =>
  writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const delay = (ms) => new Promise((done) => setTimeout(done, ms));
const wait = async (probe, label) => {
  const until = Date.now() + 180000;
  while (Date.now() < until) {
    const value = await probe();
    if (value) return value;
    await delay(250);
  }
  throw Error('Timed out: ' + label);
};
let service, proxy, controls, child, sampler;
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  const config = join(output, 'bridge.json');
  await writeFile(
    config,
    JSON.stringify({ hostId, desktopVersion: witness.version, validationOnly: true }),
    { mode: 0o600 },
  );
  if (!previous)
    await writeFile(
      join(stateDirectory, 'runtime.json'),
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
    HOST_AGENT_STATE_DIR: stateDirectory,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Notification Creation DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: config,
  });
  for (const key of [
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
    'HOST_AGENT_TOKEN',
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
  ])
    delete process.env[key];
  service = await startRuntimeServer();
  assert.equal(service.session.hostId, hostId);
  await service.session.workspace();
  const token = (await readFile(join(stateDirectory, 'access-token'), 'utf8')).trim();
  const realEndpoint = 'http://127.0.0.1:' + service.server.address().port;
  const operationId = previous?.operationId ?? randomUUID(),
    marker = previous?.marker ?? 'NOTICE_CREATION_' + randomUUID().slice(0, 8);
  report.operationId = operationId;
  report.marker = marker;
  await save();
  const observer = new NotificationObserver(service.session.notificationTarget());
  // Hold the real owner verification briefly to deterministically cross a monitor tick.
  const broker = service.session.desktopCreation,
    originalVerify = broker.verify.bind(broker);
  broker.verify = async (...args) => {
    await delay(1200);
    return originalVerify(...args);
  };
  sampler = setInterval(() => {
    const observation = service.session
      .notificationObservations()
      .find((value) => value.threadId === '');
    report.samples++;
    report.falseNotices.push(...observer.observe(observation).events);
    try {
      if (JSON.parse(readFileSyncJournal())[operationId]?.phase === 'outcome-unknown')
        report.transientUnknownSamples++;
    } catch {
      /* Journal may not yet exist. */
    }
  }, 100);
  // Keep file reads out of the main notification sampling callback.
  let journalBytes = '{}';
  function readFileSyncJournal() {
    return journalBytes;
  }
  const journalReader = setInterval(() => {
    void readFile(join(stateDirectory, 'desktop-creations/creations.json'), 'utf8')
      .then((bytes) => {
        journalBytes = bytes;
      })
      .catch(() => {});
  }, 100);
  let operation;
  try {
    if (!previous)
      service.session.prepareDesktopCreation({
        hostId,
        clientOperationId: operationId,
        projectId: null,
        text: '专用通知创建 DEV：仅原样回复 ' + marker + '。不使用工具，不读取或修改文件。',
        attachments: [],
        effort: 'xhigh',
      });
    if (!previous) await service.session.submitDesktopCreation(operationId);
    operation = await wait(async () => {
      const value = await service.session.queryDesktopCreation(operationId);
      return value.phase === 'confirmed' ? value : undefined;
    }, 'confirmed original desktop creation');
  } finally {
    clearInterval(journalReader);
    clearInterval(sampler);
    sampler = undefined;
  }
  report.threadId = operation.threadId;
  assert(report.transientUnknownSamples > 0, 'Exercise the actual intermediate journal phase');
  assert.deepEqual(
    report.falseNotices,
    [],
    'Live creation must not produce an unknown-operation notice',
  );
  const owner = await wait(async () => {
    const value = await witness.ipc.follow(operation.threadId, 1000, true);
    const turn = value && desktopTurns(value).at(-1);
    return witness.ipc.hasOwner(operation.threadId) && turn?.status === 'completed'
      ? value
      : undefined;
  }, 'independently verified model completion');
  const turns = desktopTurns(owner);
  assert.equal(turns.length, 1, 'No initialization or duplicate model turn');
  assert.equal(turns[0].items.filter((item) => item.type === 'userMessage').length, 1);
  assert(
    turns[0].items.some((item) => item.type === 'agentMessage' && item.text === marker),
    'Verify the actual model answer separately from the user input',
  );
  report.runId = turns[0].turnId;
  report.originalOwnerVerified = true;
  await save();
  const event = {
    version: 1,
    id: createHash('sha256')
      .update(operationId + ':' + randomUUID())
      .digest('hex'),
    target: service.session.notificationTarget(),
    kind: 'uncertain',
    occurredAt: new Date().toISOString(),
    operationId,
    threadTitle: marker,
  };
  let noticeEnabled = false,
    noticeRead = false;
  const json = (res, value) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(value));
  };
  // Inject only the old notice. Receipts, model output and history stay on the real isolated Host.
  proxy = createServer(async (req, res) => {
    try {
      assert.equal(req.headers.authorization, 'Bearer ' + token);
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname.startsWith('/v1/notifications/')) {
        const status = {
          version: 1,
          target: event.target,
          delivery: 'foreground-only',
          state: 'ready',
        };
        if (url.pathname.endsWith('/events')) {
          const events = noticeEnabled && !noticeRead ? [event] : [];
          if (events.length) noticeRead = true;
          return json(res, {
            events,
            cursor: noticeRead ? event.id : '',
            activeIds: noticeEnabled ? [event.id] : [],
            status,
          });
        }
        return json(res, status);
      }
      if (req.method !== 'GET' && url.pathname !== '/v1/threads/read') report.writesFromPhone++;
      if (url.pathname === '/v1/desktop/creation-receipts' && req.method === 'GET')
        report.creationQueries++;
      const bytes = [];
      for await (const chunk of req) bytes.push(chunk);
      const response = await fetch(realEndpoint + req.url, {
        method: req.method,
        headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
        ...(bytes.length ? { body: Buffer.concat(bytes) } : {}),
        signal: AbortSignal.timeout(40000),
      });
      const value = await response.json();
      if (url.pathname === '/v1/workspace' && response.ok) {
        value.threads = value.threads.filter((thread) => thread.id === operation.threadId);
        value.projects = [];
        value.nextProjectCursor = null;
        value.nextThreadCursor = null;
      }
      res.writeHead(response.status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(value));
    } catch (error) {
      res.writeHead(500);
      res.end(JSON.stringify({ error: error.message }));
    }
  });
  await new Promise((done) => proxy.listen(0, '127.0.0.1', done));
  const endpoint = 'http://127.0.0.1:' + proxy.address().port;
  controls = createServer((req, res) => {
    if (req.url === '/')
      return json(res, {
        endpoint,
        token,
        hostId,
        threadId: operation.threadId,
        nonce: marker,
        secondEndpoint: '',
        secondToken: '',
        secondHostId: '',
        delayedEndpoint: '',
      });
    if (req.headers.authorization !== 'Bearer ' + token) {
      res.writeHead(401);
      return res.end();
    }
    if (req.url === '/notice') noticeEnabled = true;
    json(res, { noticeEnabled, noticeRead });
  });
  await new Promise((done) => controls.listen(8816, '127.0.0.1', done));
  const nativeProject = resolve(process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj');
  const nativeFiles = [
    'SessionStore.swift',
    'AgentClient.swift',
    'HostService.swift',
    'TaskNotifications.swift',
  ];
  const nativeFingerprints = async () =>
    Object.fromEntries(
      await Promise.all(
        nativeFiles.map(async (name) => [
          name,
          createHash('sha256')
            .update(await readFile(join(dirname(nativeProject), 'Sources', name)))
            .digest('hex'),
        ]),
      ),
    );
  report.nativeSource = await nativeFingerprints();
  await save();
  child = spawn(
    'xcodebuild',
    [
      '-project',
      nativeProject,
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      resolve(process.env.NOTIFICATION_NATIVE_BUILD_DIRECTORY ?? join(output, 'native-build')),
      '-resultBundlePath',
      join(output, 'native-' + Date.now() + '.xcresult'),
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      '-only-testing:CodexManageUITests/CodexManageUITests/testCompletedCreationNotificationOpensDurableReceipt',
      'test',
    ],
    { stdio: 'inherit', env: process.env },
  );
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  assert.equal(code, 0);
  assert.deepEqual(
    await nativeFingerprints(),
    report.nativeSource,
    'Keep the native acceptance source stable',
  );
  assert(noticeRead && report.creationQueries > 0);
  assert.equal(report.writesFromPhone, 0, 'Tapping does not execute or replay input');
  const finalOwner = await witness.ipc.follow(operation.threadId, 1000, true);
  assert.equal(desktopTurns(finalOwner).length, 1);
  report.status = 'passed';
  report.noticeSource = 'injected old local notice for a real confirmed creation';
  report.physicalIPhoneAPNsVerified = false;
  await save();
  console.log(JSON.stringify({ status: report.status, report: join(output, 'report.json') }));
} catch (error) {
  report.status = 'failed';
  report.error = error.stack;
  await save();
  throw error;
} finally {
  clearInterval(sampler);
  child?.kill('SIGTERM');
  service?.close();
  witness.close();
  for (const server of [proxy, controls])
    if (server) {
      server.closeAllConnections();
      await new Promise((done) => server.close(done));
    }
}
