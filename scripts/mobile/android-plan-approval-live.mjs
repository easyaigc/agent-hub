import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, readdir, copyFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import {
  DesktopRuntime,
  desktopTurns,
  desktopProposedPlan,
} from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// The native UI issues every command; an independent original-owner witness only reads.
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
const root = resolve('.local/mobile-acceptance/A4/plan-approval-live-' + serial + '-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const state = join(root, 'host');
await mkdir(state, { mode: 0o700 });
const pendingPath = process.env.ANDROID_PLAN_APPROVAL_PENDING_REPORT;
assert(!pendingPath || pendingPath.startsWith('.local/mobile-acceptance/A4/plan-approval-live-'));
const pending = pendingPath ? JSON.parse(await readFile(pendingPath, 'utf8')) : null;
const nonce = pending?.nonce ?? randomUUID().slice(0, 8),
  hostId = randomUUID(),
  controlKey = randomBytes(32).toString('hex');
const report = {
  phase: 'A4',
  scope:
    'Real original desktop, native plan revision and full implementation, actual tool approval deny/allow subset; not full stage completion',
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
report.toolSha256 = await hash('scripts/mobile/android-plan-approval-live.mjs');
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
  'Android plan approval independent witness',
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
  const priorPath = process.env.ANDROID_PLAN_APPROVAL_PRIOR_REPORT;
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
  if (pending) {
    assert.equal(pending.threadId, report.threadId);
    assert.equal(pending.status, 'failed');
    assert(
      pending.gates.actualPlanMode &&
        pending.gates.nativeRevisionExactPlan &&
        pending.gates.fullRevisedPlanImplemented,
    );
    report.pendingResume = true;
    report.priorPlanEvidenceSha256 = await hash(pendingPath);
  }

  const history = await witness.reader.read('thread/read', {
    threadId: report.threadId,
    includeTurns: true,
  });
  report.baselineTurnCount = history.thread.turns.length;
  const baselineTurnIds = new Set(history.thread.turns.map((t) => t.id));
  assert.equal(baselineTurnIds.size, report.baselineTurnCount);
  if (pending) {
    const value = await owner(),
      latest = desktopTurns(value).at(-1);
    assert.equal(latest.status, 'inProgress');
    assert.equal(
      value.requests.filter((r) => r.method === 'item/commandExecution/requestApproval').length,
      1,
    );
    const saved = JSON.parse(
      await readFile(join(resolve(pendingPath, '..'), 'host/runtime.json'), 'utf8'),
    );
    const intent = saved.receipts.at(-1);
    assert(intent.reply.text.includes('PLAN_APPROVAL_' + nonce + '_DENY_TOOL'));
    assert.equal(
      latest.items.filter((i) => i.type === 'userMessage' && i.clientId === intent.clientMessageId)
        .length,
      1,
    );
    assert(baselineTurnIds.delete(latest.turnId));
    report.pendingIntentSha256 = intent.fingerprint;
    report.pendingTurnId = latest.turnId;
    report.gates.pendingOriginalIntentObserved = true;
  }

  assert([...baselineTurnIds].every((id) => typeof id === 'string' && id.length > 0));
  // A newly opened desktop can expose a shorter historical prefix. Stable IDs
  // distinguish this run's turns without treating an array index as identity.
  const newTurns = (value) =>
    desktopTurns(value).filter((turn) => !baselineTurnIds.has(turn.turnId));
  assert(
    report.baselineTurnCount >= 1 &&
      history.thread.turns.every(
        (t) =>
          (pending && t.id === report.pendingTurnId) ||
          ['completed', 'interrupted', 'failed'].includes(t.status),
      ),
    'Owned prior thread must be idle before this run',
  );
  report.gates.examinerOwnedHistoricalThread = true;
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
    HOST_AGENT_NAME: 'Android plan approval DEV',
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
        const marker = 'PLAN_APPROVAL_' + nonce + '_';
        const value = await owner(),
          turns = newTurns(value);
        const all = (await service.session.open(report.threadId)).runtime.receipts;
        const match = (receipt) =>
          turns.filter((turn) =>
            turn.items.some(
              (item) => item.type === 'userMessage' && item.clientId === receipt.clientMessageId,
            ),
          );
        if (route === '/initial-plan' || route === '/revised-plan') {
          const revised = route === '/revised-plan';
          const plan = desktopProposedPlan(value);
          assert(plan && plan.text.includes(marker + (revised ? 'REVISED' : 'INITIAL')));
          assert.equal(turns.length, revised ? 2 : 1);
          assert.equal(turns.at(-1).status, 'completed');
          assert.equal(turns.at(-1).params.collaborationMode.mode, 'plan');
          assert.equal(all.length, revised ? 2 : 1);
          assert.equal(match(all.at(-1)).length, 1);
          if (revised) {
            assert.notEqual(plan.id, report.initialPlan.id);
            assert.equal(all.at(-1).expectedPlanId, report.initialPlan.id);
          }
          report[revised ? 'revisedPlan' : 'initialPlan'] = plan;
          report.gates[revised ? 'nativeRevisionExactPlan' : 'actualPlanMode'] = true;
        } else if (route === '/implemented') {
          assert.equal(turns.length, 3);
          assert.equal(all.length, 3);
          const receipt = all.at(-1);
          assert.equal(receipt.text, 'PLEASE IMPLEMENT THIS PLAN:\n' + report.revisedPlan.text);
          assert.equal(receipt.expectedPlanId, report.revisedPlan.id);
          assert.equal(match(receipt).length, 1);
          assert.equal(turns.at(-1).params.collaborationMode.mode, 'default');
          assert.equal(turns.at(-1).status, 'completed');
          assert(
            turns
              .at(-1)
              .items.some(
                (item) =>
                  item.type === 'agentMessage' && item.text.trim() === marker + 'IMPLEMENTED',
              ),
          );
          assert.equal(desktopProposedPlan(value), undefined);
          report.gates.fullRevisedPlanImplemented = true;
        } else if (route === '/denial-ready' || route === '/allow-ready') {
          const allow = route === '/allow-ready',
            request = value.requests.filter((r) => r.method.includes('Approval'));
          assert.equal(request.length, 1);
          assert.equal(turns.length, pending ? (allow ? 2 : 1) : allow ? 5 : 4);
          assert.equal(turns.at(-1).status, 'inProgress');
          assert.equal(turns.at(-1).params.approvalPolicy, 'on-request');
          assert.equal(turns.at(-1).params.permissions, ':read-only');
          const actual = (await service.session.open(report.threadId)).runtime.approvals;
          assert.equal(actual.length, 1);
          assert.equal(actual[0].kind, 'approval');
          assert.equal(actual[0].turnId, turns.at(-1).turnId);
          report[allow ? 'allowRequest' : 'denyRequest'] = {
            id: actual[0].id,
            turnId: actual[0].turnId,
          };
          report.gates[allow ? 'actualAllowRequest' : 'actualDenialRequest'] = true;
        } else if (route === '/denied' || route === '/allowed') {
          const allow = route === '/allowed',
            prefix = allow ? 'ALLOW' : 'DENY',
            request = report[allow ? 'allowRequest' : 'denyRequest'];
          assert.equal(turns.length, pending ? (allow ? 2 : 1) : allow ? 5 : 4);
          const turn = turns.at(-1);
          assert.equal(turn.turnId, request.turnId);
          assert.equal(value.requests.length, 0);
          const actions = requests.filter((r) => r.path === '/v1/approvals');
          assert.equal(actions.length, allow ? 2 : 1);
          assert.equal(actions.at(-1).input.approvalId, request.id);
          assert.equal(actions.at(-1).input.turnId, request.turnId);
          assert(
            (allow ? ['accept', 'approve'] : ['decline', 'deny', 'cancel']).includes(
              actions.at(-1).input.decision,
            ),
          );

          const choice = actions.at(-1).input.decision;
          assert.equal(turn.status, choice === 'cancel' ? 'interrupted' : 'completed');
          if (choice !== 'cancel')
            assert(
              turn.items.some(
                (i) => i.type === 'agentMessage' && i.text.trim() === marker + prefix + '_DONE',
              ),
            );
          if (choice === 'cancel')
            report.denialLimitation =
              'Original owner offers accept/cancel only; cancellation interrupts the task. Decline-and-continue is unavailable.';
          const commands = turn.items.filter(
            (item) =>
              item.type === 'commandExecution' && item.command.includes(marker + prefix + '_TOOL'),
          );
          if (allow)
            assert(
              commands.some(
                (item) =>
                  item.status === 'completed' &&
                  item.exitCode === 0 &&
                  item.aggregatedOutput.includes(marker + 'ALLOW_TOOL'),
              ),
              'Actual allowed tool must execute and print its marker',
            );
          else
            assert(
              !commands.some((item) => item.status === 'completed' && item.exitCode === 0),
              'Denied command must not succeed',
            );
          report[allow ? 'allowedCommands' : 'deniedCommands'] = commands.map((item) => ({
            status: item.status,
            exitCode: item.exitCode,
          }));
          report.gates[allow ? 'actualAllowedExecution' : 'deniedNoSuccessfulExecution'] = true;
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
      pendingOnly: !!pending,
    }),
    { mode: 0o600 },
  );
  const temp = '/data/local/tmp/a4-controls-' + nonce + '.json';
  android('push', config, temp);
  android(
    'shell',
    'run-as',
    'com.easyai.agenthub.android.debug',
    'cp',
    temp,
    'files/mobile-plan-approval-config.json',
  );
  android(
    'shell',
    'run-as',
    'com.easyai.agenthub.android.debug',
    'chmod',
    '600',
    'files/mobile-plan-approval-config.json',
  );
  android('shell', 'rm', temp);
  android(
    'shell',
    'rm',
    '-rf',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-plan-approval-live',
  );
  await save();
  console.log('Real native plan approval acceptance:', root);
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
      'com.agenthub.android.MobileLivePlanApprovalTest',
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  );
  let log = '';
  for (const stream of [examiner.stdout, examiner.stderr]) stream.on('data', (v) => (log += v));
  assert.equal(await new Promise((done) => examiner.once('close', done)), 0);
  log = log.replaceAll(token, '[REDACTED]').replaceAll(controlKey, '[REDACTED]');
  await writeFile(join(root, 'instrumentation.log'), log, { mode: 0o600 });
  assert(
    log.includes('OK (1 test)'),
    'Native instrumentation did not pass; see private instrumentation.log',
  );
  assert(!/FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/.test(log));
  const value = await owner();
  await writeFile(join(root, 'independent-owner.json'), JSON.stringify(value, null, 2), {
    mode: 0o600,
  });
  const turns = newTurns(value);
  assert.equal(turns.length, pending ? 2 : 5);
  assert.equal(turns.at(-1).status, 'completed');
  assert.equal(value.requests.length, 0);
  assert.equal(requests.filter((r) => r.path === '/v1/replies').length, pending ? 1 : 5);
  assert.equal(
    new Set(requests.filter((r) => r.path === '/v1/replies').map((r) => r.input.clientMessageId))
      .size,
    pending ? 1 : 5,
  );
  assert.equal(requests.filter((r) => r.path === '/v1/approvals').length, 2);
  assert(
    requests
      .filter((r) => r.path === '/v1/replies')
      .slice(pending ? 0 : -2)
      .every((r) => r.input.permissionMode === 'read-only'),
    'Each tool approval task explicitly selects read-only through the native UI',
  );
  report.gates.independentOwner = true;
  assert(Object.values(report.gates).every(Boolean));
  report.turnCount = pending ? 2 : 5;
  report.nativeReplyCount = pending ? 1 : 5;
  report.approvalActions = 2;
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
    assert(examiner, 'Instrumentation never started; no current screenshot capture');
    await mkdir(join(root, 'screenshots'));
    android(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-plan-approval-live/.',
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
  if (report.status === 'passed' && report.screenshots.length !== (pending ? 3 : 6)) {
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
  console.log('Real native plan approval result:', report.status, 'Evidence:', root);
}
