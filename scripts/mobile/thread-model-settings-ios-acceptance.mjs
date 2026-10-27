import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile, readdir, cp } from 'node:fs/promises';
import { createServer, request as proxyRequest } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { createInterface } from 'node:readline';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { DesktopIpc, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { prepareIsolatedIOSRunner } from './isolated-ios-runner.mjs';
import { verifyIOSResultDevice } from './ios-result-device.mjs';

const device = process.env.IOS_PHYSICAL_DEVICE_ID;
assert(device, 'Select the paired physical iPhone');
const directory = resolve(process.env.THREAD_MODEL_SETTINGS_DIRECTORY);
await mkdir(directory, { recursive: true, mode: 0o700 });
const prior = process.env.THREAD_MODEL_SETTINGS_REUSE
  ? JSON.parse(await readFile(join(process.env.THREAD_MODEL_SETTINGS_REUSE, 'report.json'), 'utf8'))
  : null;
const nonce = randomUUID().slice(0, 8);
const bundleId = 'com.easyai.agenthub.modelsettings.' + nonce;
const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
const run = (command, args) =>
  execFileSync(command, args, { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const tailscale = '/Applications/Tailscale.app/Contents/MacOS/Tailscale';
const port = 19447;
const originalRoutes = JSON.parse(run(tailscale, ['serve', 'status', '--json']));
assert(!originalRoutes.TCP?.[port], 'Keep existing listeners');
const hostname = JSON.parse(run(tailscale, ['status', '--json'])).Self.DNSName.replace(/\.$/, '');
const endpoint = `https://${hostname}:${port}`;
const report = {
  status: 'running',
  nonce,
  bundleId,
  device,
  directory,
  adapterSha256: preparedAdapterDigest(),
  realModelInputs: 0,
  phases: [],
  reusedModelsFrom: process.env.THREAD_MODEL_SETTINGS_REUSE ?? null,
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
await save();
let handleControl;
let controlQueue = Promise.resolve();
async function xcode(args, name, extra = {}) {
  const log = createWriteStream(join(directory, name + '.log'), { mode: 0o600 });
  const child = spawn('xcodebuild', args, {
    env: { ...env, ...extra },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const phase = line.match(/^MODEL_SETTINGS_CONTROL ([a-z-]+)$/)?.[1];
      if (phase) controlQueue = controlQueue.then(() => handleControl(phase));
    });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('close', done);
  });
  await new Promise((done) => log.end(done));
  await controlQueue;
  assert.equal(code, 0, `${name} failed; preserve original inputs and report`);
}
const until = async (fn, timeout = 180000) => {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const value = await fn();
    if (value) return value;
    await new Promise((done) => setTimeout(done, 1000));
  }
  throw Error('Timed out; inspect the original operation, never replay');
};
let service,
  gateway,
  routed = false,
  blocked;
const observer = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
try {
  const details = join(directory, 'device.json');
  run('xcrun', [
    'devicectl',
    'device',
    'info',
    'details',
    '--device',
    device,
    '--json-output',
    details,
  ]);
  const actual = JSON.parse(await readFile(details, 'utf8')).result;
  assert.equal(actual.hardwareProperties.reality, 'physical');
  assert.equal(actual.hardwareProperties.marketingName, 'iPhone 15 Pro Max');
  assert.equal(actual.connectionProperties.pairingState, 'paired');
  report.connection = actual.connectionProperties;
  const source = join(directory, 'source');
  await mkdir(source, { mode: 0o700 });
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
  await mkdir(join(source, '.local'), { recursive: true });
  await cp('.local/mobile-toolchain.json', join(source, '.local/mobile-toolchain.json'));
  report.nativeSourceSha256 = hash.digest('hex');
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
      'platform=iOS,id=' + device,
      '-derivedDataPath',
      join(directory, 'derived'),
      '-parallel-testing-enabled',
      'NO',
      'NOTIFICATION_APP_BUNDLE_ID=' + bundleId,
      'build-for-testing',
    ],
    'build',
  );
  const products = join(directory, 'derived/Build/Products');
  const template = (await readdir(products)).find((name) => name.endsWith('.xctestrun'));
  assert(template);
  const runner = await prepareIsolatedIOSRunner({
    template: join(products, template),
    directory: join(directory, 'runner'),
    expectedAppBundleId: bundleId,
  });
  report.runner = runner;
  const state = prior ? join(prior.directory, 'host') : join(directory, 'host');
  await mkdir(state, { recursive: true, mode: 0o700 });
  const hostId = prior ? prior.hostId : randomUUID();
  report.hostId = hostId;
  const bridge = join(state, 'bridge.json');
  if (!prior) {
    await writeFile(bridge, JSON.stringify({ hostId, validationOnly: true }), { mode: 0o600 });
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
  }
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE:
      '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Thread model settings DEV',
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  delete process.env.HOST_AGENT_DIRECT_CONFIG;
  delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
  service = await startRuntimeServer();
  const token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  const local = `http://127.0.0.1:${service.server.address().port}`;
  const request = async (path, body) => {
    const response = await fetch(local + path, {
      method: body ? 'POST' : 'GET',
      headers: {
        authorization: 'Bearer ' + token,
        ...(body ? { 'content-type': 'application/json' } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
      signal: AbortSignal.timeout(30000),
    });
    const value = await response.json();
    assert(response.ok, JSON.stringify(value));
    return value;
  };
  await observer.connect();
  const models = (await service.session.models()).models;
  const primary = models.find((m) => m.id === 'gpt-6.1-sol');
  assert(primary?.supportedEfforts.includes('xhigh'));
  const alternate = models.find((m) => m.id !== primary.id && m.supportedEfforts.includes('high'));
  assert(alternate);
  report.catalogueDefault = models.find((m) => m.isDefault);
  report.tasks = prior?.tasks ?? [];
  if (!prior)
    for (const [index, model, effort] of [
      [0, primary, 'xhigh'],
      [1, alternate, 'high'],
    ]) {
      const operationId = 'model-settings-' + nonce + '-' + index;
      const marker = 'MODEL_SETTINGS_' + nonce + '_' + index;
      const intent = {
        hostId,
        clientOperationId: operationId,
        projectId: null,
        text: '专用参数同步验收，只原样回复 ' + marker + '，不要调用工具。',
        attachments: [],
        model: model.id,
        effort,
      };
      await writeFile(join(directory, 'intent-' + index + '.json'), JSON.stringify(intent), {
        mode: 0o600,
        flag: 'wx',
      });
      report.realModelInputs++;
      await save();
      await request('/v1/desktop/creations', intent);
      await request('/v1/desktop/creations/submit', { hostId, clientOperationId: operationId });
      const creation = await until(async () => {
        const c = await request(
          '/v1/desktop/creations?' + new URLSearchParams({ hostId, id: operationId }),
        );
        assert(c.phase !== 'rejected', c.error);
        return c.phase === 'confirmed' ? c : null;
      });
      const task = {
        threadId: creation.threadId,
        model: model.id,
        modelName: model.name,
        effort,
        marker,
        operationId,
      };
      report.tasks.push(task);
      await save();
      await until(async () => {
        await observer.follow(task.threadId, 15000, true);
        const turns = desktopTurns(observer.state(task.threadId) ?? {});
        return turns.length === 1 && turns[0].status === 'completed';
      });
    }
  assert.equal(report.tasks.length, 2);
  const first = report.tasks[0],
    second = report.tasks[1];
  // Fault injection drops only full snapshots in this independent DEV connection.
  const ipc = service.session.runtime.ipc,
    receive = ipc.receive.bind(ipc);
  ipc.receive = (message) => {
    if (
      blocked &&
      message.method === 'thread-stream-state-changed' &&
      message.params?.conversationId === blocked &&
      message.params.change?.type === 'snapshot'
    )
      return;
    receive(message);
  };
  async function ownerSettings(model, effort) {
    const result = await observer.command(
      first.threadId,
      'thread-follower-update-thread-settings',
      {
        threadSettings: {
          model,
          effort,
          collaborationMode: {
            mode: 'default',
            settings: { model, reasoning_effort: effort, developer_instructions: null },
          },
        },
      },
    );
    assert.equal(result.applied, true);
    const actual = (
      await observer.command(first.threadId, 'thread-follower-read-model-settings', {})
    ).settings;
    assert.equal(actual.model, model);
    assert.equal(actual.reasoningEffort, effort);
    return actual;
  }
  await ownerSettings(first.model, 'xhigh');
  gateway = createServer(async (req, res) => {
    if (req.url === '/acceptance/model-settings') {
      if (req.headers.authorization !== 'Bearer ' + token) {
        res.writeHead(403).end();
        return;
      }
      try {
        let body = '';
        for await (const chunk of req) body += chunk;
        const { phase } = JSON.parse(body);
        let evidence;
        if (phase === 'desktop-low') evidence = await ownerSettings(first.model, 'low');
        else if (phase === 'desktop-alternate')
          evidence = await ownerSettings(second.model, 'high');
        else if (phase === 'desktop-xhigh') evidence = await ownerSettings(first.model, 'xhigh');
        else if (phase === 'drop-full-snapshot') {
          blocked = first.threadId;
          ipc.invalidate(first.threadId);
          evidence = { fault: 'only full owner snapshots dropped' };
        } else if (phase === 'restore-full-snapshot') {
          blocked = null;
          await ipc.follow(first.threadId, 15000, true);
          evidence = { hasOwner: ipc.hasOwner(first.threadId) };
        } else throw Error('Unknown acceptance phase');
        report.phases.push({ phase, evidence });
        await save();
        res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(evidence));
      } catch (error) {
        res
          .writeHead(500, { 'content-type': 'application/json' })
          .end(JSON.stringify({ error: error.message }));
      }
      return;
    }
    const forward = proxyRequest(
      local + req.url,
      { method: req.method, headers: req.headers },
      (response) => {
        res.writeHead(response.statusCode, response.headers);
        response.pipe(res);
      },
    );
    forward.on('error', () => res.writeHead(502).end());
    req.pipe(forward);
  });
  await new Promise((done) => gateway.listen(0, '127.0.0.1', done));
  handleControl = async (phase) => {
    const response = await fetch(
      `http://127.0.0.1:${gateway.address().port}/acceptance/model-settings`,
      {
        method: 'POST',
        headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
        body: JSON.stringify({ phase }),
        signal: AbortSignal.timeout(40000),
      },
    );
    assert.equal(response.status, 200, await response.text());
  };
  run(tailscale, [
    'serve',
    '--bg',
    '--https=' + port,
    `http://127.0.0.1:${gateway.address().port}`,
  ]);
  routed = true;
  const connection = (task) => ({
    endpoint,
    token,
    hostId,
    threadId: task.threadId,
    threadTitle: task.marker,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  });
  const fixture = {
    bundleId,
    connection: connection(first),
    second: connection(second),
    alternateModelName: second.modelName,
  };
  const xc = JSON.parse(run('plutil', ['-convert', 'json', '-o', '-', runner.template]));
  const target = Object.values(xc).find((v) => v?.IsUITestBundle);
  target.EnvironmentVariables = {
    ...target.EnvironmentVariables,
    THREAD_MODEL_SETTINGS_FIXTURE: JSON.stringify(fixture),
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
  const occupied = run('ps', ['-axo', 'command'])
    .split('\n')
    .some((line) => /xcodebuild .*test/.test(line) && line.includes(device));
  assert(
    !occupied,
    'Another native test is using this phone; preserve this run and reuse its model inputs',
  );
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
  await xcode(
    [
      'test-without-building',
      '-xctestrun',
      runner.template,
      '-destination',
      'platform=iOS,id=' + device,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-resultBundlePath',
      join(directory, 'native.xcresult'),
      '-only-testing:CodexManageUITests/CodexManageUITests/testThreadModelSettingsPhysicalDEV',
    ],
    'native',
  );
  const summary = JSON.parse(
    run('xcrun', [
      'xcresulttool',
      'get',
      'test-results',
      'summary',
      '--path',
      join(directory, 'native.xcresult'),
      '--format',
      'json',
    ]),
  );
  report.devices = verifyIOSResultDevice(summary, device);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.passedTests, 1);
  for (const task of report.tasks) {
    await observer.follow(task.threadId, 15000, true);
    const turns = desktopTurns(observer.state(task.threadId) ?? {});
    assert.equal(turns.length, 1);
    assert.equal(turns[0].status, 'completed');
    assert.equal(turns[0].items.filter((item) => item.type === 'userMessage').length, 1);
    assert(
      turns[0].items.some(
        (item) => item.type === 'agentMessage' && item.text.includes(task.marker),
      ),
    );
    const params = turns[0].params;
    assert.equal(
      params.effort ?? params.collaborationMode?.settings?.reasoning_effort,
      task.effort,
    );
    assert.equal(params.model ?? params.collaborationMode?.settings?.model, task.model);
    task.verifiedTurnId = turns[0].turnId;
  }
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  observer.close();
  if (gateway) await new Promise((done) => gateway.close(done));
  if (service) await service.close();
  if (routed) run(tailscale, ['serve', '--https=' + port, 'off']);
  assert.deepEqual(JSON.parse(run(tailscale, ['serve', 'status', '--json'])), originalRoutes);
  report.routesRestored = true;
  await save();
  console.log(
    JSON.stringify({ status: report.status, directory, realModelInputs: report.realModelInputs }),
  );
}
