import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { verifyIOSResultDevice } from './mobile/ios-result-device.mjs';
import { prepareIsolatedIOSRunner } from './mobile/isolated-ios-runner.mjs';

// One fresh, benign model input. No replay or production Host/App changes.
const device = process.env.IOS_PHYSICAL_DEVICE_ID;
assert(device, 'Select the paired physical iPhone explicitly');
const tailscale = process.env.TAILSCALE_EXECUTABLE;
assert(tailscale, 'Provide the installed local Tailscale CLI');
const port = Number(process.env.COMPUTER_USE_APPROVAL_HTTPS_PORT ?? 18443);
assert(Number.isSafeInteger(port) && port > 1024 && port < 65536);
const recoveryDirectory = process.env.COMPUTER_USE_APPROVAL_RECOVER_RUN
  ? resolve(process.env.COMPUTER_USE_APPROVAL_RECOVER_RUN)
  : undefined;
const prior = recoveryDirectory
  ? JSON.parse(await readFile(join(recoveryDirectory, 'report.json'), 'utf8'))
  : undefined;
if (prior)
  assert(
    prior.threadId && prior.requestId !== undefined && prior.realModelInputs === 1,
    'Recovery requires an existing original request; never submit a new input',
  );
const attempt = randomUUID().slice(0, 8);
const bundleId = 'com.easyai.agenthub.computeruseapproval.' + attempt;
const nonce = prior?.nonce ?? attempt;
const directory = resolve(
  '.local/computer-use-approval/runs',
  prior ? nonce + '-recovery-' + attempt : nonce,
);
const source = join(directory, 'source');
await mkdir(source, { recursive: true, mode: 0o700 });
const env = {
  ...process.env,
  DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer',
  GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? resolve('.local/gradle-home'),
};
const run = (cmd, args) =>
  execFileSync(cmd, args, { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const cli = (...args) => run(tailscale, args);
const originalRoutes = JSON.parse(cli('serve', 'status', '--json'));
assert(!originalRoutes.TCP?.[port], 'Refuse to replace an existing HTTPS listener');
const hostname = JSON.parse(cli('status', '--json')).Self.DNSName.replace(/\.$/, '');
const endpoint = `https://${hostname}:${port}`;
const files = [
  ...new Set(
    run('git', [
      'ls-files',
      '-co',
      '--exclude-standard',
      'apps/ios',
      'apps/android',
      'packages/protocol',
      'packages/mobile-core',
      'scripts/mobile',
      'gradle',
      'gradlew',
      'gradlew.bat',
      'gradle.properties',
      'build.gradle.kts',
      'settings.gradle.kts',
    ])
      .trim()
      .split('\n'),
  ),
].sort();
const digest = createHash('sha256');
for (const file of files) {
  const bytes = await readFile(file),
    mode = (await stat(file)).mode & 0o777;
  digest
    .update(file + '\0')
    .update(bytes)
    .update('\0');
  await mkdir(dirname(join(source, file)), { recursive: true });
  await writeFile(join(source, file), bytes, { mode });
}
await mkdir(join(source, '.local'), { recursive: true });
await writeFile(
  join(source, '.local/mobile-toolchain.json'),
  await readFile('.local/mobile-toolchain.json'),
  { mode: 0o600 },
);
const project = join(source, 'apps/ios/CodexManage.xcodeproj');
// This independent development app uses a wildcard profile without push entitlements.
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
const report = {
  status: 'running',
  nonce,
  bundleId,
  device,
  sourceSha256: digest.digest('hex'),
  adapterSha256: preparedAdapterDigest(),
  realModelInputs: 0,
  originalRun: recoveryDirectory ?? null,
  directory,
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
await save();
async function xcode(args, extra = {}) {
  const log = createWriteStream(join(directory, extra.name ?? 'native.log'), { mode: 0o600 });
  const child = spawn('xcodebuild', args, {
    env: { ...env, ...extra.env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('exit', done);
  });
  await new Promise((done) => log.end(done));
  assert.equal(code, 0, 'Xcode failed; preserve this run and do not replay its input');
}
let probeProcess;
let service,
  routeCreated = false;
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
  await xcode(
    [
      '-project',
      project,
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS,id=${device}`,
      '-derivedDataPath',
      join(directory, 'derived'),
      '-parallel-testing-enabled',
      'NO',
      'NOTIFICATION_APP_BUNDLE_ID=' + bundleId,
      'build-for-testing',
    ],
    { name: 'build.log' },
  );
  const products = join(directory, 'derived/Build/Products');
  const names = (await import('node:fs/promises')).readdir;
  const template = (await names(products)).find((name) => name.endsWith('.xctestrun'));
  assert(template);
  const runner = await prepareIsolatedIOSRunner({
    template: join(products, template),
    directory: join(directory, 'runner'),
    expectedAppBundleId: bundleId,
  });
  report.runner = runner;
  // Two native test runners must not compete for the paired phone's foreground.
  let quietSince = Date.now();
  for (let attempts = 0; ; attempts++) {
    const occupied = run('ps', ['-axo', 'command'])
      .split('\n')
      .some((line) => /xcodebuild .*test/.test(line) && line.includes(device));
    if (occupied) quietSince = Date.now();
    if (!occupied && Date.now() - quietSince >= 30000) break;
    assert(
      attempts < 120,
      'Paired phone remains in another native test; no model input was submitted',
    );
    await new Promise((done) => setTimeout(done, 5000));
  }
  const state = join(recoveryDirectory ?? directory, 'host');
  if (!prior) await mkdir(state, { mode: 0o700 });
  const hostId = prior
    ? JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8')).hostId
    : randomUUID();
  const bridge = join(state, 'prepared-bridge.json');
  const desktopVersion = run('/usr/libexec/PlistBuddy', [
    '-c',
    'Print :CFBundleShortVersionString',
    '/Applications/ChatGPT.app/Contents/Info.plist',
  ]).trim();
  if (!prior)
    await writeFile(bridge, JSON.stringify({ hostId, desktopVersion, validationOnly: true }), {
      mode: 0o600,
    });
  if (!prior)
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
    CODEX_EXECUTABLE:
      '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: process.cwd(),
    HOST_AGENT_NAME: 'Computer Use Approval DEV',
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
  cli('serve', '--bg', '--https=' + port, local);
  routeCreated = true;
  const workspace = await request('/v1/workspace');
  assert.equal(workspace.connectionMode, 'desktop-runtime');
  assert(workspace.capabilities.includes('desktop:creations:testing'));
  const models = await request('/v1/models?hostId=' + hostId);
  await writeFile(join(directory, 'models.json'), JSON.stringify(models), { mode: 0o600 });
  const originalIntent = prior
    ? JSON.parse(await readFile(join(recoveryDirectory, 'immutable-intent.json'), 'utf8'))
    : undefined;
  const model = prior
    ? models.models.find((model) => model.id === originalIntent.model)
    : (models.models.find((model) => model.isDefault) ?? models.models[0]);
  assert(
    model && model.supportedEfforts?.includes('xhigh'),
    'Existing default model must support xhigh',
  );
  const marker = 'COMPUTER_USE_APPROVED_' + nonce;
  const probeId = prior?.probe.bundleId ?? 'com.easyai.agenthub.approvalprobe.' + nonce;
  const probeApp = join(recoveryDirectory ?? directory, 'Approval Probe.app');
  if (!prior) await mkdir(join(probeApp, 'Contents/MacOS'), { recursive: true });
  if (!prior)
    await writeFile(
      join(probeApp, 'Contents/Info.plist'),
      `<?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>${probeId}</string><key>CFBundleExecutable</key><string>ApprovalProbe</string><key>CFBundleName</key><string>Agent Hub Approval Probe</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>`,
    );
  const swift = join(directory, 'ApprovalProbe.swift');
  if (!prior)
    await writeFile(
      swift,
      `import Cocoa
final class ProbeDelegate: NSObject, NSApplicationDelegate {
  var window: NSWindow?
  func applicationDidFinishLaunching(_ notification: Notification) {
    let window = NSWindow(contentRect: NSRect(x: 100, y: 100, width: 420, height: 180), styleMask: [.titled, .closable], backing: .buffered, defer: false)
    window.title = "Agent Hub Approval Probe ${nonce}"
    let label = NSTextField(labelWithString: "PROBE_VIEW_${nonce}")
    label.frame = NSRect(x: 20, y: 70, width: 380, height: 40)
    window.contentView?.addSubview(label)
    self.window = window
    window.makeKeyAndOrderFront(nil)
  }
}
let app = NSApplication.shared
let delegate = ProbeDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
`,
    );
  if (!prior) {
    run('swiftc', [swift, '-o', join(probeApp, 'Contents/MacOS/ApprovalProbe')]);
    run('codesign', ['--force', '--sign', '-', probeApp]);
  }
  probeProcess = spawn(join(probeApp, 'Contents/MacOS/ApprovalProbe'), [], {
    env,
    stdio: 'ignore',
  });
  report.probe = { bundleId: probeId, pid: probeProcess.pid };
  const intent = originalIntent ?? {
    hostId,
    clientOperationId: 'computer-use-approval-' + nonce,
    projectId: null,
    text: `专用手机新版 Computer Use 审批验收 ${nonce}。本次只授权获取本任务新建的空白验收应用（bundle ID: ${probeId}）的应用快照，不操作其他应用、不读取文件、不调用 shell、不创建或联系其他 Agent。请使用 node_repl 与本机已安装的官方 @oai/sky Computer Use API，执行 const { sky } = await import("@oai/sky"); nodeRepl.write((await sky.get_app_state({app:"${probeId}"})).text); 。该 API 文档随插件安装在 @oai/sky/docs/sky-window-api.md。必须走此新版带应用审批的 API；不要改用旧 cua_repl 路径，若接口不可用如实报告。出现应用审批时等待手机处理，拒绝后不要重试。成功返回真实快照后，仅回复 ${marker}。`,
    attachments: [],
    model: model.id,
    effort: 'xhigh',
    permissionMode: 'read-only',
  };
  await writeFile(join(directory, 'immutable-intent.json'), JSON.stringify(intent), {
    mode: 0o600,
    flag: 'wx',
  });
  report.intentSha256 = createHash('sha256').update(JSON.stringify(intent)).digest('hex');
  report.realModelInputs = prior ? 0 : 1;
  await save();
  if (!prior) await request('/v1/desktop/creations', intent);
  if (!prior)
    await request('/v1/desktop/creations/submit', {
      hostId,
      clientOperationId: intent.clientOperationId,
    });
  const until = async (fn, timeout = 180000) => {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const result = await fn();
      if (result) return result;
      await new Promise((done) => setTimeout(done, 1000));
    }
    throw Error('Timed out; reconcile this original operation, never replay');
  };
  const creation = prior
    ? { threadId: prior.threadId }
    : await until(async () => {
        const value = await request(
          '/v1/desktop/creations?' + new URLSearchParams({ hostId, id: intent.clientOperationId }),
        );
        assert(value.phase !== 'rejected', value.error);
        return value.phase === 'confirmed' ? value : null;
      });
  report.threadId = creation.threadId;
  await save();
  await observer.connect();
  const pending = await until(async () => {
    await observer.follow(creation.threadId, 15000, true);
    const state = observer.state(creation.threadId);
    const pending = state?.requests.find(
      (request) => request.method === 'mcpServer/elicitation/request' && !request.completed,
    );
    assert(
      pending || !desktopTurns(state ?? {}).some((turn) => turn.status === 'completed'),
      'Model finished without requesting app approval; this is not an approval pass',
    );
    return pending;
  });
  assert.equal(pending.params._meta.codex_approval_kind, 'mcp_tool_call');
  if (prior) {
    assert.equal(pending.id, prior.requestId);
    const original = JSON.parse(
      await readFile(join(recoveryDirectory, 'original-request.json'), 'utf8'),
    );
    assert.deepEqual(
      pending.params,
      original.params,
      'Only the unchanged original request can be recovered',
    );
  }
  assert.match(pending.params._meta.connector_id, /computer.use/i);
  report.requestId = pending.id;
  report.turnId = pending.params.turnId;
  await writeFile(join(directory, 'original-request.json'), JSON.stringify(pending, null, 2), {
    mode: 0o600,
  });
  const history = await request(
    '/v1/history?' + new URLSearchParams({ hostId, threadId: creation.threadId }),
  );
  const fixture = {
    endpoint,
    token,
    hostId,
    threadId: creation.threadId,
    threadTitle: history.thread.title,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
    bundleId,
    supportsAlways: [pending.params._meta.persist].flat().includes('always'),
  };
  const runnerPath = runner.template;
  assert(runnerPath, 'Missing isolated Runner path');
  const resultBundle = join(directory, 'native.xcresult');
  await xcode(
    [
      'test-without-building',
      '-xctestrun',
      runnerPath,
      '-destination',
      `platform=iOS,id=${device}`,
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testComputerUseApprovalOriginalDesktopDEV',
    ],
    { env: { TEST_RUNNER_COMPUTER_USE_APPROVAL_FIXTURE_JSON: JSON.stringify(fixture) } },
  );
  const summary = JSON.parse(
    run('xcrun', [
      'xcresulttool',
      'get',
      'test-results',
      'summary',
      '--path',
      resultBundle,
      '--format',
      'json',
    ]),
  );
  report.native = summary;
  verifyIOSResultDevice(summary, device);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  await observer.follow(creation.threadId, 15000, true);
  const original = observer.state(creation.threadId);
  await writeFile(join(directory, 'original-after.json'), JSON.stringify(original), {
    mode: 0o600,
  });
  const turns = desktopTurns(original);
  assert.equal(turns.length, 1);
  assert.equal(turns[0].status, 'completed');
  const settings = turns[0].params;
  assert.equal(
    settings.effort ??
      settings.reasoningEffort ??
      settings.collaborationMode?.settings?.reasoning_effort,
    'xhigh',
  );
  assert.equal(settings.model ?? settings.collaborationMode?.settings?.model, model.id);
  assert.equal(settings.approvalPolicy, 'on-request');
  assert.equal(settings.sandboxPolicy.type, 'readOnly');
  assert.equal(turns[0].items.filter((item) => item.type === 'userMessage').length, 1);
  assert(turns[0].items.some((item) => item.type === 'agentMessage' && item.text.includes(marker)));
  assert(
    turns[0].items.some(
      (item) =>
        item.type === 'mcpToolCall' &&
        item.status === 'completed' &&
        item.result?.content?.some(
          (part) => part.type === 'text' && part.text?.includes('PROBE_VIEW_' + nonce),
        ),
    ),
    'Real Computer Use snapshot must contain this new probe app marker',
  );
  assert(!original.requests.some((request) => request.id === pending.id && !request.completed));
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  if (probeProcess && probeProcess.exitCode === null) probeProcess.kill('SIGTERM');
  observer.close();
  if (service) await service.close();
  if (routeCreated) cli('serve', '--https=' + port, 'off');
  const after = JSON.parse(cli('serve', 'status', '--json'));
  assert.deepEqual(after, originalRoutes, 'Keep every pre-existing route');
  report.routesRestored = true;
  await save();
  console.log(
    JSON.stringify({ status: report.status, directory, realModelInputs: report.realModelInputs }),
  );
}
