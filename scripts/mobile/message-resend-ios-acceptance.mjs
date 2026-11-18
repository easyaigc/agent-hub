import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile, readdir, cp } from 'node:fs/promises';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { startDirectDiscovery } from '../../apps/host-agent/dist/direct-discovery.js';
import { DesktopIpc, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { DesktopThreadPreparer } from '../../packages/codex-adapter/dist/desktop-thread-preparer.js';
import {
  openOriginalDesktopThread,
  preparedAdapterDigest,
} from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { RuntimePersistenceError } from '../../packages/codex-adapter/dist/runtime-persistence.js';
import { prepareIsolatedIOSRunner } from './isolated-ios-runner.mjs';
import { verifyIOSResultDevice } from './ios-result-device.mjs';

// Native App + original Electron + one primer and one explicitly resent model input. The two initial
// failures are deliberate DEV persistence faults, never a claim of natural ENOSPC.
// Recovery is allowed only before any POST attempt, and reuses the exact empty thread.
const simulated = Boolean(process.env.IOS_SIMULATOR_ID);
assert(!(simulated && process.env.IOS_PHYSICAL_DEVICE_ID), 'Select exactly one native device');
const device = process.env.IOS_PHYSICAL_DEVICE_ID ?? process.env.IOS_SIMULATOR_ID;
assert(device, 'Select the paired physical iPhone or a dedicated auxiliary simulator');
const destination = (simulated ? 'platform=iOS Simulator,id=' : 'platform=iOS,id=') + device;
assert(process.env.MESSAGE_RESEND_DIRECTORY, 'Choose a new private evidence directory');
const directory = resolve(process.env.MESSAGE_RESEND_DIRECTORY);
await mkdir(directory, { recursive: true, mode: 0o700 });
const reportPath = join(directory, 'report.json');
const resumeEmpty = process.env.MESSAGE_RESEND_RESUME_EMPTY === '1';
const previousBytes = resumeEmpty ? await readFile(reportPath) : null;
const previous = previousBytes ? JSON.parse(previousBytes) : null;
if (previous) {
  assert.equal(previous.device, device);
  assert.equal(previous.status, 'failed');
  assert.equal(previous.attempts.length, 0, 'Never replay any earlier POST or model input');
  assert.equal(previous.faults.length, 0);
  await writeFile(join(directory, 'prior-report-' + Date.now() + '.json'), previousBytes, {
    mode: 0o600,
    flag: 'wx',
  });
}
const runSuffix = randomUUID().slice(0, 8);
const nonce = previous?.nonce ?? randomUUID().slice(0, 8);
const bundleId = previous?.bundleId ?? 'com.easyai.agenthub.resend.' + nonce;
const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
const run = (command, args) =>
  execFileSync(command, args, { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const report = {
  ...previous,
  status: 'running',
  reality: simulated ? 'simulated' : 'physical',
  nonce,
  bundleId,
  device,
  adapterSha256: preparedAdapterDigest(),
  attempts: [],
  faults: [],
};
delete report.error;
await writeFile(reportPath, JSON.stringify(report), { mode: 0o600, flag: previous ? 'w' : 'wx' });
const save = () => writeFile(reportPath, JSON.stringify(report, null, 2), { mode: 0o600 });
async function xcode(args, name) {
  const log = createWriteStream(join(directory, name + '.log'), { mode: 0o600 });
  const child = spawn('xcodebuild', args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('close', done);
  });
  await new Promise((done) => log.end(done));
  assert.equal(code, 0, name + ' failed; query the original IDs, never replay inputs');
}
async function until(check) {
  const deadline = Date.now() + 240000;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await new Promise((done) => setTimeout(done, 1000));
  }
  throw Error('Timed out; preserve the original operation IDs');
}
let endpoint;
const executable =
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const observer = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let service, gateway, direct;
try {
  if (simulated) {
    const listing = JSON.parse(run('xcrun', ['simctl', 'list', 'devices', 'available', '--json']));
    const details = Object.values(listing.devices)
      .flat()
      .find((value) => value.udid === device);
    assert(details?.isAvailable && details.state === 'Booted');
    report.connection = details;
  } else {
    const detailsPath = join(directory, 'device.json');
    run('xcrun', [
      'devicectl',
      'device',
      'info',
      'details',
      '--device',
      device,
      '--json-output',
      detailsPath,
    ]);
    const details = JSON.parse(await readFile(detailsPath, 'utf8')).result;
    assert.equal(details.hardwareProperties.reality, 'physical');
    assert.equal(details.hardwareProperties.marketingName, 'iPhone 15 Pro Max');
    assert.equal(details.connectionProperties.pairingState, 'paired');
    report.connection = details.connectionProperties;
  }
  const source = join(directory, 'source');
  await mkdir(source, { recursive: true, mode: 0o700 });
  const paths = run('git', [
    'ls-files',
    '-co',
    '--exclude-standard',
    'apps/ios',
    'apps/android',
    'packages/mobile-core',
    'packages/protocol',
    'scripts/mobile',
    'gradle',
    'gradlew',
    'gradle.properties',
    'settings.gradle.kts',
    'build.gradle.kts',
  ])
    .trim()
    .split('\n');
  const hash = createHash('sha256');
  for (const file of [...new Set(paths)].sort()) {
    const bytes = await readFile(file);
    hash.update(file + '\0').update(bytes);
    await mkdir(dirname(join(source, file)), { recursive: true });
    await cp(file, join(source, file));
  }
  report.nativeSourceSha256 = hash.digest('hex');
  await mkdir(join(source, '.local'), { recursive: true });
  await cp('.local/mobile-toolchain.json', join(source, '.local/mobile-toolchain.json'));
  const project = join(source, 'apps/ios/CodexManage.xcodeproj');
  const pbx = join(project, 'project.pbxproj');
  const config = JSON.parse(run('plutil', ['-convert', 'json', '-o', '-', pbx]));
  for (const value of Object.values(config.objects)) {
    for (const target of Object.values(value.attributes?.TargetAttributes ?? {}))
      target.SystemCapabilities = {};
    for (const key of Object.keys(value.buildSettings ?? {}))
      if (key.startsWith('CODE_SIGN_ENTITLEMENTS') || key.startsWith('PROVISIONING_PROFILE'))
        delete value.buildSettings[key];
  }
  await writeFile(pbx, JSON.stringify(config));
  run('plutil', ['-convert', 'xml1', pbx]);
  await xcode(
    [
      '-project',
      project,
      '-scheme',
      'CodexManage',
      '-destination',
      destination,
      '-derivedDataPath',
      join(directory, 'derived'),
      '-parallel-testing-enabled',
      'NO',
      'NOTIFICATION_APP_BUNDLE_ID=' + bundleId,
      ...(simulated
        ? [
            'CODE_SIGNING_ALLOWED=YES',
            'CODE_SIGN_IDENTITY=-',
            'ARCHS=arm64',
            'ONLY_ACTIVE_ARCH=YES',
          ]
        : []),
      'build-for-testing',
    ],
    'build-' + runSuffix,
  );
  const products = join(directory, 'derived/Build/Products');
  const template = (await readdir(products)).find((name) => name.endsWith('.xctestrun'));
  assert(template);
  const runner = simulated
    ? { template: join(products, template) }
    : await prepareIsolatedIOSRunner({
        template: join(products, template),
        directory: join(directory, 'runner-' + runSuffix),
        expectedAppBundleId: bundleId,
      });
  report.runner = runner;
  report.threadId ??= await new DesktopThreadPreparer(
    executable,
    join(homedir(), '.codex'),
  ).prepare(
    { cwd: process.cwd(), projectId: null, title: '消息失败重发 DEV ' + nonce },
    {
      starting() {},
      identified() {},
      materialized() {},
      exited(code) {
        report.preparerExit = code;
      },
    },
  );
  assert.equal(report.preparerExit, 0);
  await openOriginalDesktopThread(report.threadId);
  await observer.connect();
  await until(
    async () =>
      (await observer.follow(report.threadId, 2000, true)) && observer.hasOwner(report.threadId),
  );
  const initialTurns = desktopTurns(observer.state(report.threadId));
  assert(
    initialTurns.length === 0 ||
      (report.primer &&
        initialTurns.length === 1 &&
        initialTurns[0].params.clientUserMessageId === report.primer.id),
  );
  const state = join(directory, 'host');
  await mkdir(state, { recursive: true, mode: 0o700 });
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE: executable,
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: '消息重发 DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  for (const key of [
    'HOST_AGENT_DIRECT_CONFIG',
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
    'HOST_AGENT_TOKEN',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  ])
    delete process.env[key];
  service = await startRuntimeServer();
  await service.session.workspace();
  await service.session.open(report.threadId);
  const model = (await service.session.models()).models.find((m) => m.id === 'gpt-6.1-sol');
  assert(model?.supportedEfforts.includes('xhigh'));
  const settings = await observer.command(
    report.threadId,
    'thread-follower-update-thread-settings',
    {
      threadSettings: {
        model: model.id,
        effort: 'xhigh',
        collaborationMode: {
          mode: 'default',
          settings: { model: model.id, reasoning_effort: 'xhigh', developer_instructions: null },
        },
      },
    },
  );
  assert.equal(settings.applied, true);
  // Official sidebar ordering need not expose a never-submitted CLI preparation.
  // Materialize one real, separately identified primer before exercising replies.
  if (!report.primer) {
    report.primer = { id: randomUUID(), marker: 'RESEND_PRIMER_' + nonce };
    await save();
    await service.session.submit(
      {
        hostId: service.session.hostId,
        threadId: report.threadId,
        clientMessageId: report.primer.id,
        text: '专用发送恢复验收初始化，请只原样回复 ' + report.primer.marker + '，不要调用工具。',
        attachmentIds: [],
        mode: 'next-turn',
        model: model.id,
        effort: 'xhigh',
      },
      service.session.readingTarget(),
    );
  } else {
    const originalPrimer = service.session.receipt(report.primer.id);
    assert(
      ['submitted', 'completed'].includes(originalPrimer.state),
      'Reconcile the exact primer; never replay it',
    );
  }
  await until(async () => {
    await observer.follow(report.threadId, 1000, true);
    const values = desktopTurns(observer.state(report.threadId));
    assert(values.length <= 1);
    return (
      values.length === 1 &&
      values[0].params.clientUserMessageId === report.primer.id &&
      values[0].status === 'completed'
    );
  });
  report.primer.completed = true;
  await save();

  const token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  const local = 'http://127.0.0.1:' + service.server.address().port;
  gateway = createServer(async (req, res) => {
    try {
      if (req.headers.authorization !== 'Bearer ' + token) {
        res.writeHead(401).end();
        return;
      }
      let body = '';
      for await (const chunk of req) body += chunk;
      const reply =
        req.method === 'POST' && req.url.split('?')[0] === '/v1/replies' ? JSON.parse(body) : null;
      const persistence = service.session.persistence,
        originalSave = persistence.save.bind(persistence);
      let injected = false;
      if (reply) {
        assert.equal(reply.threadId, report.threadId);
        assert.equal(reply.hostId, service.session.hostId);
        assert(report.attempts.length < 3, 'Unexpected duplicate native send');
        assert(
          !report.attempts.some((v) => v.id === reply.clientMessageId),
          'Native resend must have a new ID',
        );
        report.attempts.push({
          id: reply.clientMessageId,
          text: reply.text,
          attachmentIds: reply.attachmentIds,
          mode: reply.mode,
          model: reply.model,
          effort: reply.effort,
        });
        await writeFile(join(directory, 'intent-' + report.attempts.length + '.json'), body, {
          mode: 0o600,
          flag: 'wx',
        });
        await save();
        if (report.attempts.length <= 2)
          persistence.save = (value) => {
            if (
              !injected &&
              value.receipts.some((r) => r.clientMessageId === reply.clientMessageId)
            ) {
              injected = true;
              throw new RuntimePersistenceError(
                'dev-resend-persistence',
                Object.assign(new Error('Deliberate DEV failure'), { code: 'ENOSPC' }),
              );
            }
            originalSave(value);
          };
      }
      try {
        const response = await fetch(local + req.url, {
          method: req.method,
          headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
          ...(body ? { body } : {}),
          signal: AbortSignal.timeout(180000),
        });
        const bytes = Buffer.from(await response.arrayBuffer());
        if (injected) {
          report.faults.push({ id: reply.clientMessageId, status: response.status });
          await save();
        }
        res.writeHead(response.status, { 'content-type': 'application/json' }).end(bytes);
      } finally {
        if (reply) persistence.save = originalSave;
      }
    } catch (error) {
      report.gatewayError = error.message;
      await save();
      res.writeHead(500).end(JSON.stringify({ error: error.message }));
    }
  });
  await new Promise((done) => gateway.listen(0, '127.0.0.1', done));
  direct = await startDirectDiscovery({
    directory: join(directory, 'tls'),
    name: '消息重发 DEV',
    token,
    target: () => service.session.readingTarget(),
    peers: async () => [],
    handler: async (req, res) => {
      gateway.emit('request', req, res);
    },
    port: 0,
  });
  const descriptor = direct.descriptor();
  endpoint = descriptor.endpointHints.find((hint) => hint.networkKind === 'lan')?.endpoint;
  assert(endpoint, 'Require the actual local LAN TLS listener');
  const offer = {
    ...descriptor,
    endpoints: [endpoint],
    endpointHints: descriptor.endpointHints.filter((hint) => hint.endpoint === endpoint),
    token,
  };
  const pairingUri =
    'codex-host://pair?descriptor=' + Buffer.from(JSON.stringify(offer)).toString('base64url');
  report.transport = 'pinned-LAN-HTTPS';
  const fixture = {
    pairingUri,
    bundleId,
    marker: 'RESEND_OK_' + nonce,
    connection: {
      endpoint,
      token,
      hostId: service.session.hostId,
      threadId: report.threadId,
      threadTitle: '消息失败重发 DEV ' + nonce,
      nonce,
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    },
  };
  const xc = JSON.parse(run('plutil', ['-convert', 'json', '-o', '-', runner.template]));
  const target = Object.values(xc).find((v) => v?.IsUITestBundle);
  target.EnvironmentVariables = {
    ...target.EnvironmentVariables,
    MESSAGE_RESEND_FIXTURE: JSON.stringify(fixture),
  };
  await writeFile(runner.template, JSON.stringify(xc), { mode: 0o600 });
  run('plutil', ['-convert', 'xml1', runner.template]);
  const app = target.UITargetAppPath.replaceAll('__TESTROOT__', dirname(runner.template));
  const info = JSON.parse(run('plutil', ['-convert', 'json', '-o', '-', join(app, 'Info.plist')]));
  assert.equal(info.CFBundleIdentifier, bundleId);
  report.appSha256 = createHash('sha256')
    .update(await readFile(join(app, info.CFBundleExecutable)))
    .digest('hex');
  await save();
  if (simulated) run('xcrun', ['simctl', 'install', device, app]);
  else {
    run('xcrun', [
      'devicectl',
      'device',
      'install',
      'app',
      '--device',
      device,
      '--json-output',
      join(directory, 'install.json'),
      app,
    ]);
  }
  await xcode(
    [
      'test-without-building',
      '-xctestrun',
      runner.template,
      '-destination',
      destination,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-resultBundlePath',
      join(directory, 'native-' + runSuffix + '.xcresult'),
      '-only-testing:CodexManageUITests/CodexManageUITests/testFailedMessageResendPhysicalDEV',
    ],
    'native-' + runSuffix,
  );
  const summary = JSON.parse(
    run('xcrun', [
      'xcresulttool',
      'get',
      'test-results',
      'summary',
      '--path',
      join(directory, 'native-' + runSuffix + '.xcresult'),
      '--format',
      'json',
    ]),
  );
  report.actualDevice = verifyIOSResultDevice(summary, device);
  report.resultBundle = join(directory, 'native-' + runSuffix + '.xcresult');
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(report.attempts.length, 3);
  assert.equal(report.faults.length, 2);
  assert.equal(report.attempts[1].text, report.attempts[2].text);
  assert.deepEqual(report.attempts[1].attachmentIds, report.attempts[2].attachmentIds);
  const originalWire = JSON.parse(await readFile(join(directory, 'intent-2.json'), 'utf8'));
  const replacementWire = JSON.parse(await readFile(join(directory, 'intent-3.json'), 'utf8'));
  assert.notEqual(originalWire.clientMessageId, replacementWire.clientMessageId);
  delete originalWire.clientMessageId;
  delete replacementWire.clientMessageId;
  assert.deepEqual(originalWire, replacementWire);
  assert.equal(report.gatewayError, undefined);
  await observer.follow(report.threadId, 15000, true);
  const turns = desktopTurns(observer.state(report.threadId));
  assert.equal(turns.length, 2);
  assert.equal(turns[0].params.clientUserMessageId, report.primer.id);
  assert.equal(turns[0].status, 'completed');
  assert.equal(turns[1].status, 'completed');
  const resultTurn = turns[1];
  const users = resultTurn.items.filter((v) => v.type === 'userMessage');
  assert.equal(users.length, 1);
  assert.equal(resultTurn.params.clientUserMessageId, report.attempts[2].id);
  assert(
    resultTurn.items.some((v) => v.type === 'agentMessage' && v.text.includes(fixture.marker)),
  );
  assert.equal(
    resultTurn.params.effort ?? resultTurn.params.collaborationMode?.settings?.reasoning_effort,
    'xhigh',
  );
  const executedModel =
    resultTurn.params.model ?? resultTurn.params.collaborationMode?.settings?.model;
  assert.equal(executedModel, model.id);
  const actual = await observer.command(report.threadId, 'thread-follower-read-model-settings', {});
  // Current settings are diagnostic; only the exact executed turn proves what
  // the model used. Some desktop versions return null after completion here.
  report.currentSettings = {
    model: actual.settings.model ?? null,
    effort: actual.settings.reasoningEffort ?? null,
  };
  report.owner = {
    turnId: resultTurn.id ?? resultTurn.turnId,
    userMessageId: resultTurn.params.clientUserMessageId,
    model: executedModel,
    effort:
      resultTurn.params.effort ?? resultTurn.params.collaborationMode?.settings?.reasoning_effort,
  };
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  observer.close();
  if (gateway) {
    gateway.closeAllConnections();
    await new Promise((done) => gateway.close(done));
  }
  if (service) await service.close();
  direct?.close();
  await save();
}
console.log(
  JSON.stringify({
    status: report.status,
    directory,
    bundleId,
    attempts: report.attempts.length,
    realModelInputs: 2,
  }),
);
