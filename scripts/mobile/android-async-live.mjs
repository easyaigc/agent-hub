import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, readdir, copyFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { DesktopRuntime, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import {
  desktopQuestions,
  parseDesktopQuestionReply,
} from '../../packages/codex-adapter/dist/desktop-questions.js';

// Only this examiner's fresh Host is restarted. Original desktop owns every model turn.
const executable = process.env.CODEX_EXECUTABLE,
  serial = process.env.ANDROID_SERIAL;
assert(
  executable && /^emulator-\d+$/.test(serial ?? ''),
  'Select installed CLI and a dedicated emulator',
);
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(process.env.ANDROID_HOME ?? local.androidSdk, 'platform-tools/adb');
const android = (...args) =>
  execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' }).trim();
assert.equal(android('get-state'), 'device');
const root = resolve('.local/mobile-acceptance/A4/async-live-' + serial + '-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const state = join(root, 'host');
await mkdir(state, { mode: 0o700 });
const nonce = randomUUID().slice(0, 8),
  hostId = randomUUID(),
  controlKey = randomBytes(32).toString('hex');
const report = {
  phase: 'A4',
  scope:
    'Real original desktop, native unloaded open, generation settings, async questions and Host restart subset; not full stage completion',
  status: 'running',
  nonce,
  hostId,
  serial,
  api: Number(android('shell', 'getprop', 'ro.build.version.sdk')),
  adapterSha256: preparedAdapterDigest(),
  gates: {},
  commands: [],
};
const save = () =>
  writeFile(join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
const hash = async (path) =>
  createHash('sha256')
    .update(await readFile(path))
    .digest('hex');
const fingerprint = async () => {
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
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const h = createHash('sha256');
  for (const file of new Set(files))
    h.update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return h.digest('hex');
};
report.sourceSha256 = await fingerprint();
report.toolSha256 = await hash('scripts/mobile/android-async-live.mjs');
const wait = async (probe, name, timeout = 180000) => {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const value = await probe();
    if (value) return value;
    await new Promise((done) => setTimeout(done, 250));
  }
  throw Error('Timed out: ' + name);
};
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
  'Android async independent witness',
);

let service, proxy, control, examiner, proxyPort, controlPort, token, endpoint;
const requests = [];
const owner = async () => {
  await witness.ipc.follow(report.threadId, 15000, true);
  const value = witness.ipc.state(report.threadId);
  assert(value && witness.ipc.hasOwner(report.threadId), 'Exact original owner');
  return value;
};
try {
  await witness.connect();
  report.desktopVersion = witness.version;
  const priorPath = process.env.ANDROID_ASYNC_PRIOR_REPORT;
  assert(
    priorPath && priorPath.startsWith('.local/mobile-acceptance/A4/live-'),
    'Only a prior examiner-owned thread',
  );
  const prior = JSON.parse(await readFile(priorPath, 'utf8'));
  assert.equal(prior.status, 'passed');
  const observation = prior.observations.find(
    (o) => o.marker === 'ANDROID_A4_EMPTY_' + prior.nonce,
  );
  assert(observation);
  report.threadId = observation.threadId;
  report.priorOwnedEvidenceSha256 = await hash(priorPath);
  const history = await witness.reader.read('thread/read', {
    threadId: report.threadId,
    includeTurns: true,
  });
  report.baselineTurnCount = history.thread.turns.length;
  const baselineTurnIds = new Set(history.thread.turns.map((t) => t.id));
  assert.equal(baselineTurnIds.size, report.baselineTurnCount);
  assert([...baselineTurnIds].every((id) => typeof id === 'string' && id.length > 0));
  // A newly opened desktop can expose a shorter historical prefix. Stable IDs
  // distinguish this run's turns without treating an array index as identity.
  const newTurns = (value) =>
    desktopTurns(value).filter((turn) => !baselineTurnIds.has(turn.turnId));
  assert(
    report.baselineTurnCount >= 1 && history.thread.turns.every((t) => t.status === 'completed'),
  );
  report.gates.examinerOwnedHistoricalThread = true;
  await witness.ipc.follow(report.threadId, 1000, true);
  assert(
    !witness.ipc.hasOwner(report.threadId),
    'Native input must explicitly open the unloaded owner',
  );
  report.gates.ownerInitiallyUnloaded = true;
  const bridge = join(state, 'bridge.json');
  await writeFile(bridge, JSON.stringify({ hostId, desktopVersion: witness.version }), {
    mode: 0o600,
  });
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE: executable,
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Android async DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  delete process.env.HOST_AGENT_TOKEN;
  const launch = async () => {
    service = await startRuntimeServer();
    report.hostPort = service.server.address().port;
    endpoint = 'http://127.0.0.1:' + report.hostPort;
    token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  };
  await launch();
  const catalog = (await service.session.models()).models;
  const model = catalog.find((m) => m.isDefault);
  assert(model);
  const effort = model.supportedEfforts.includes('xhigh') ? 'xhigh' : model.defaultEffort;
  const speed = model.supportedSpeeds.includes('fast') ? 'fast' : 'standard';
  report.settings = { model: model.id, effort, speed, permissionMode: 'full-access' };
  report.effortLimitation =
    effort === 'xhigh' ? null : 'Current default model does not offer xhigh';
  const getRoutes = new Set([
    '/v1/workspace',
    '/v1/history',
    '/v1/models',
    '/v1/events',
    '/v1/receipts',
    '/v1/desktop/open-status',
  ]);
  const postRoutes = new Set(['/v1/replies', '/v1/desktop/open', '/v1/approvals']);
  proxy = createServer((req, res) => {
    void (async () => {
      try {
        assert(!req.headers.origin && req.headers.authorization === 'Bearer ' + token);
        const url = new URL(req.url, 'http://127.0.0.1');
        assert(
          req.method === 'GET'
            ? getRoutes.has(url.pathname)
            : req.method === 'POST' && postRoutes.has(url.pathname),
          'Named native routes only',
        );
        const chunks = [];
        let size = 0;
        for await (const chunk of req) {
          size += chunk.length;
          assert(size < 150000);
          chunks.push(chunk);
        }
        const body = Buffer.concat(chunks);
        if (req.method === 'POST') {
          const input = JSON.parse(body);
          assert.equal(input.hostId, hostId);
          assert.equal(input.threadId, report.threadId);
          requests.push({ path: url.pathname, input });
          report.commands.push({
            path: url.pathname,
            bodySha256: createHash('sha256').update(body).digest('hex'),
          });
          await save();
        }
        const response = await fetch(endpoint + url.pathname + url.search, {
          method: req.method,
          headers: {
            Authorization: 'Bearer ' + token,
            ...(req.headers['content-type'] ? { 'Content-Type': req.headers['content-type'] } : {}),
          },
          ...(req.method === 'POST' ? { body } : {}),
          redirect: 'error',
          signal: AbortSignal.timeout(45000),
        });
        if (['/v1/desktop/open', '/v1/desktop/open-status'].includes(url.pathname) && response.ok) {
          const receipt = await response.clone().json();
          const observed = { phase: receipt.phase, error: receipt.error ?? null };
          const previous = report.openObservations?.at(-1);
          if (!previous || previous.phase !== observed.phase || previous.error !== observed.error) {
            (report.openObservations ??= []).push({
              ...observed,
              method: req.method,
              observedAt: new Date().toISOString(),
            });
            await save();
          }
        }
        if (req.method === 'POST' && url.pathname === '/v1/approvals' && response.ok) {
          // Observe each accepted action independently. This read does not send
          // input or change the result returned by the native command route.
          try {
            const turns = newTurns(await owner());
            const replies = turns
              .flatMap((turn) => turn.items)
              .filter(
                (item) =>
                  item.type === 'userMessage' ||
                  (item.type === 'steeringUserMessage' && item.status === 'accepted'),
              );
            const answers = (item) =>
              parseDesktopQuestionReply((item.content ?? item.input ?? [])[0]?.text ?? '');
            (report.answerObservations ??= []).push({
              nativeQuestionActionCount: requests.filter((entry) => entry.path === '/v1/approvals')
                .length,
              acceptedReplyCount: replies.flatMap(answers).length,
              desktopAggregateAnswers: replies
                .filter(
                  (item) =>
                    item.restoreMessage?.context?.turnTrigger ===
                    'send_user_message_async_question',
                )
                .flatMap(answers).length,
              observedAt: new Date().toISOString(),
            });
            await save();
          } catch (error) {
            report.answerObservationError = error.message;
          }
        }
        res
          .writeHead(response.status, {
            'content-type': response.headers.get('content-type') ?? 'application/json',
          })
          .end(Buffer.from(await response.arrayBuffer()));
      } catch (error) {
        report.proxyError = error.message;
        if (!res.headersSent) res.writeHead(502);
        res.end('{"error":"Acceptance route unavailable"}');
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
        let bytes = '';
        for await (const part of req) {
          bytes += part;
          assert(bytes.length < 10000);
        }
        const body = JSON.parse(bytes);
        assert.equal(body.nonce, nonce);
        const route = new URL(req.url, 'http://127.0.0.1').pathname;
        if (route === '/settings') {
          const value = await owner(),
            turns = newTurns(value);
          assert.equal(turns.length, 1);
          const turn = turns[0];
          assert.equal(turn.status, 'completed');
          assert(
            turn.items.some(
              (i) => i.type === 'agentMessage' && i.text.trim() === 'SETTINGS_' + nonce,
            ),
          );
          assert.equal(
            turn.params.model ?? turn.params.collaborationMode?.settings?.model,
            model.id,
          );
          assert.equal(
            turn.params.effort ?? turn.params.collaborationMode?.settings?.reasoning_effort,
            effort,
          );
          assert.equal(turn.params.serviceTier, speed === 'fast' ? 'priority' : 'default');
          assert.equal(turn.params.approvalPolicy, 'never');
          assert.equal(turn.params.permissions, ':danger-full-access');
          assert.equal(requests.filter((r) => r.path === '/v1/desktop/open').length, 1);
          report.gates.nativeUnloadedOpen = true;
          report.gates.actualGenerationSettings = true;
        } else if (route === '/continued') {
          await wait(
            async () => {
              const turns = desktopTurns(await owner()),
                latest = turns.at(-1),
                items = latest.items ?? [];
              const questions = desktopQuestions(items),
                answers = items.flatMap((i) =>
                  parseDesktopQuestionReply((i.content ?? i.input ?? [])[0]?.text ?? ''),
                );
              const command = items.find(
                (i) =>
                  i.type === 'commandExecution' && i.command.includes('ASYNC_CONTINUE_' + nonce),
              );
              return (
                questions.length === 3 &&
                answers.length === 0 &&
                command &&
                latest.status === 'inProgress'
              );
            },
            'Real owner tool continues before any native answer',
            45000,
          );
          report.gates.continuedWhileUnanswered = true;
        } else if (route === '/restart') {
          const value = await owner();
          assert(
            desktopTurns(value)
              .at(-1)
              .items.some((i) => i.type === 'agentMessage' && i.text === 'ASYNC_IDLE_' + nonce),
          );
          const persisted = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
          assert.equal(Object.keys(persisted.asyncQuestionActions).length, 3);
          await new Promise((done) => {
            service.server.once('close', done);
            service.close();
          });
          process.env.HOST_AGENT_PORT = String(report.hostPort);
          await launch();
          report.gates.isolatedHostRestart = true;
        } else throw Error('Unknown acceptance control');
        await save();
        res.writeHead(200, { 'content-type': 'application/json' }).end('{"ok":true}');
      } catch (error) {
        report.controlError = error.message;
        await save();
        res.writeHead(500).end('{"error":"Independent owner check failed"}');
      }
    })();
  });
  await new Promise((done) => control.listen(0, '127.0.0.1', done));
  controlPort = control.address().port;
  for (const port of [proxyPort, controlPort]) android('reverse', 'tcp:' + port, 'tcp:' + port);
  const app = '.build/native/android/outputs/apk/debug/android-debug.apk',
    test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
  report.apkSha256 = await hash(app);
  report.testApkSha256 = await hash(test);
  await mkdir(join(root, 'artifacts'));
  await copyFile(app, join(root, 'artifacts/app.apk'));
  await copyFile(test, join(root, 'artifacts/test.apk'));
  android('install', '-r', app);
  android('install', '-r', test);
  const config = join(root, 'config.json');
  await writeFile(
    config,
    JSON.stringify({
      nonce,
      hostId,
      threadId: report.threadId,
      endpoint: 'http://127.0.0.1:' + proxyPort,
      token,
      control: 'http://127.0.0.1:' + controlPort,
      controlKey,
      ...report.settings,
    }),
    { mode: 0o600 },
  );
  const temp = '/data/local/tmp/a4-async-' + nonce + '.json';
  android('push', config, temp);
  android(
    'shell',
    'run-as',
    'com.easyai.agenthub.android.debug',
    'cp',
    temp,
    'files/mobile-async-live-config.json',
  );
  android(
    'shell',
    'run-as',
    'com.easyai.agenthub.android.debug',
    'chmod',
    '600',
    'files/mobile-async-live-config.json',
  );
  android('shell', 'rm', temp);
  android(
    'shell',
    'rm',
    '-rf',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-async-live',
  );
  await save();
  console.log('Real native async acceptance:', root);
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
      'com.agenthub.android.MobileLiveQuestionsTest',
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  );
  let log = '';
  for (const stream of [examiner.stdout, examiner.stderr]) stream.on('data', (v) => (log += v));
  assert.equal(await new Promise((done) => examiner.once('close', done)), 0);
  log = log.replaceAll(token, '[REDACTED]').replaceAll(controlKey, '[REDACTED]');
  await writeFile(join(root, 'instrumentation.log'), log, { mode: 0o600 });
  assert(log.includes('OK (1 test)'));
  assert(!/FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/.test(log));
  const value = await owner();
  await writeFile(join(root, 'independent-owner.json'), JSON.stringify(value, null, 2), {
    mode: 0o600,
  });
  const turns = newTurns(value),
    items = turns.flatMap((t) => t.items ?? []),
    questions = desktopQuestions(items);
  const replies = items
    .filter(
      (i) =>
        i.type === 'userMessage' || (i.type === 'steeringUserMessage' && i.status === 'accepted'),
    )
    .flatMap((i) => parseDesktopQuestionReply((i.content ?? i.input)?.[0]?.text ?? ''));
  assert.equal(questions.length, 4);
  assert.equal(replies.length, 3);
  assert.equal(new Set(replies.map((r) => r.questionItemId)).size, 3);
  for (const answer of ['简洁版', 'PHONE_' + nonce, '继续验收'])
    assert(replies.some((r) => r.answer === answer));
  assert(!replies.some((r) => r.question === '可选的发布渠道'));
  for (const marker of ['ASYNC_DONE_', 'ASYNC_IDLE_', 'ASYNC_FOLLOWUP_'])
    assert(items.some((i) => i.type === 'agentMessage' && i.text === marker + nonce));
  assert.equal(turns.length, 4);
  assert.equal(turns.at(-1).status, 'completed');
  assert.equal(value.requests.length, 0);
  const persisted = JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8'));
  assert.equal(Object.keys(persisted.asyncQuestionActions).length, 4);
  assert.equal(
    Object.values(persisted.asyncQuestionActions).filter((a) => a.decision === 'skip').length,
    1,
  );
  Object.assign(report.gates, {
    sameTurnAnswers: true,
    idleFollowup: true,
    optionsFreeformSkip: true,
    independentOwner: true,
  });
  assert(Object.values(report.gates).every(Boolean));
  report.questionCount = questions.length;
  report.acceptedReplyCount = replies.length;
  report.turnCount = turns.length;
  report.completedTests = 1;
  report.failures = 0;
  report.skips = 0;
  assert.equal(await fingerprint(), report.sourceSha256, 'Acceptance inputs changed');
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
} finally {
  if (examiner && examiner.exitCode === null) examiner.kill('SIGTERM');
  try {
    await mkdir(join(root, 'screenshots'));
    android(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-async-live/.',
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
  if (report.status === 'passed' && report.screenshots.length !== 7) {
    report.status = 'failed';
    report.error = 'Required native screenshots missing';
    process.exitCode = 1;
  }
  for (const port of [proxyPort, controlPort].filter(Boolean))
    try {
      android('reverse', '--remove', 'tcp:' + port);
    } catch {
      // An already removed reverse mapping has no remaining resource to release.
    }
  for (const server of [proxy, control].filter(Boolean)) {
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
  }
  service?.close();
  witness.close();
  report.completedAt = new Date().toISOString();
  await save();
  console.log('Real native async result:', report.status, 'Evidence:', root);
}
