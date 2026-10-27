import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile, cp, readdir, stat, chmod } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { createServer, request as proxyRequest } from 'node:http';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';
import { DesktopIpc, desktopTurns } from '../../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { prepareIsolatedIOSRunner } from './isolated-ios-runner.mjs';
import { verifyIOSResultDevice } from './ios-result-device.mjs';

const device = process.env.IOS_PHYSICAL_DEVICE_ID;
assert(device, 'Select the paired physical iPhone explicitly');
const directory = resolve(process.env.FILE_PREVIEW_ACCEPTANCE_DIR);
await mkdir(directory, { recursive: true, mode: 0o700 });
const reportPath = join(directory, 'report.json');
let report;
try {
  report = JSON.parse(await readFile(reportPath, 'utf8'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
report ??= {
  nonce: randomUUID().slice(0, 8),
  operationId: randomUUID(),
  modelSubmitStarted: false,
  attempts: [],
};
report.status = 'running';
delete report.error; // Earlier failures remain in the immutable attempts list and logs.
const save = () => writeFile(reportPath, JSON.stringify(report, null, 2), { mode: 0o600 });
await save();
const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
const run = (command, args) =>
  execFileSync(command, args, { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
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
  assert.equal(code, 0, name + ' failed; original inputs remain queryable');
}
async function until(check, timeout = 240000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await new Promise((done) => setTimeout(done, 2000));
  }
  throw Error('Timed out; inspect the original operation rather than replaying');
}
const tailscale = '/Applications/Tailscale.app/Contents/MacOS/Tailscale';
const port = 19466;
const routes = JSON.parse(run(tailscale, ['serve', 'status', '--json']));
assert(!routes.TCP?.[port], 'Do not replace another route');
const hostname = JSON.parse(run(tailscale, ['status', '--json'])).Self.DNSName.replace(/\.$/, '');
const endpoint = `https://${hostname}:${port}`;
const bundleId = 'com.easyai.agenthub.filepreview.' + report.nonce;
let service,
  gateway,
  routed = false;
const observer = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
try {
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
  const paths = [
    ...new Set(
      run('git', [
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
        .split('\n'),
    ),
  ].sort();
  const inputs = [];
  const digest = createHash('sha256');
  for (const file of paths) {
    const bytes = await readFile(file);
    const mode = (await stat(file)).mode & 0o777;
    digest.update(file + '\0' + mode + '\0').update(bytes);
    inputs.push([file, bytes, mode]);
  }
  const fingerprint = digest.digest('hex');
  const source = join(directory, 'source-' + fingerprint.slice(0, 12));
  await mkdir(source, { recursive: true, mode: 0o700 });
  for (const [file, bytes, mode] of inputs) {
    const output = join(source, file);
    await mkdir(dirname(output), { recursive: true });
    await writeFile(output, bytes);
    await chmod(output, mode);
  }
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
    if (value.buildSettings?.PRODUCT_NAME === 'CodexManage')
      value.buildSettings.INFOPLIST_KEY_CFBundleDisplayName = 'Agent Hub Markdown DEV';
  }
  await writeFile(pbx, JSON.stringify(config));
  run('plutil', ['-convert', 'xml1', pbx]);
  const derived = join(directory, 'device-' + fingerprint.slice(0, 12));
  report.nativeSourceSha256 = fingerprint;
  report.bundleId = bundleId;
  report.device = device;
  report.adapterSha256 = preparedAdapterDigest();
  await save();
  await xcode(
    [
      '-project',
      project,
      '-scheme',
      'CodexManage',
      '-destination',
      'generic/platform=iOS',
      '-derivedDataPath',
      derived,
      '-jobs',
      '2',
      '-parallel-testing-enabled',
      'NO',
      '-allowProvisioningUpdates',
      'COMPILER_INDEX_STORE_ENABLE=NO',
      'NOTIFICATION_APP_BUNDLE_ID=' + bundleId,
      'build-for-testing',
    ],
    'build-' + fingerprint.slice(0, 12),
  );
  const products = join(derived, 'Build/Products');
  const template = (await readdir(products)).find((file) => file.endsWith('.xctestrun'));
  assert(template);
  const runner = await prepareIsolatedIOSRunner({
    template: join(products, template),
    directory: join(directory, 'runner-' + randomUUID()),
    expectedAppBundleId: bundleId,
  });
  const assets = join(directory, 'assets');
  await mkdir(assets, { recursive: true, mode: 0o700 });
  const markdownFile = join(assets, 'inventory.md'),
    plainFile = join(assets, 'plain.txt');
  const markdown =
    '# Markdown 验收\n\nBEGIN_' +
    report.nonce +
    '\n\n## 代码示例\n\n```swift\r\n  let value = "中文"  \r\n\r\n```\r\n\n## 工具清单\n\n| 工具 | 首次进入 | 后续变化 | 完整说明 |\n| --- | --- | --- | --- |\n' +
    Array.from(
      { length: 191 },
      (_, index) =>
        `| tool-${index + 1} | 仅本轮条件成立时 | 保留当前来源与版本 | 支持横向阅读的中文说明 ${index + 1} |\n`,
    ).join('') +
    '\n## 末尾章节\n\nEND_' +
    report.nonce +
    '\n\n[回到顶部](#markdown-验收)\n\n[未授权相对文件](../private.md)\n';
  if (!report.sourceSha256) {
    await writeFile(markdownFile, markdown, { mode: 0o600, flag: 'wx' });
    await writeFile(plainFile, 'PLAIN_' + report.nonce, { mode: 0o600, flag: 'wx' });
    report.sourceSha256 = hash(Buffer.from(markdown));
    await save();
  }
  assert.equal(
    hash(await readFile(markdownFile)),
    report.sourceSha256,
    'Keep the original file immutable',
  );
  const state = join(directory, 'host'),
    bridge = join(state, 'bridge.json');
  await mkdir(state, { recursive: true, mode: 0o700 });
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE:
      '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: directory,
    HOST_AGENT_NAME: 'Markdown DEV ' + report.nonce,
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
  });
  for (const key of [
    'HOST_AGENT_TOKEN',
    'HOST_AGENT_DIRECT_CONFIG',
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  ])
    delete process.env[key];
  service = await startRuntimeServer();
  await writeFile(bridge, JSON.stringify({ hostId: service.session.hostId }), { mode: 0o600 });
  await service.session.refreshDesktopCreationAvailability();
  await service.session.workspace();
  if (report.hostId) assert.equal(service.session.hostId, report.hostId);
  report.hostId = service.session.hostId;
  const markdownLabel = 'Markdown_' + report.nonce,
    plainLabel = 'Plain_' + report.nonce;
  if (!report.modelSubmitStarted) {
    const intent = {
      hostId: report.hostId,
      clientOperationId: report.operationId,
      projectId: null,
      text:
        'FILE_PREVIEW_' +
        report.nonce +
        ' 手机文件预览专用真实验收。不要调用工具、不要修改文件、不要发其他消息。只原样输出下面两个 Markdown 链接，每项独立一段：\n\n' +
        `[${markdownLabel}](${new URL('file://' + markdownFile).href})\n\n[${plainLabel}](${new URL('file://' + plainFile).href})`,
      attachments: [],
      effort: 'xhigh',
    };
    await writeFile(join(directory, 'intent.json'), JSON.stringify(intent), {
      mode: 0o600,
      flag: 'wx',
    });
    service.session.prepareDesktopCreation(intent);
    report.modelSubmitStarted = true;
    await save();
    await service.session.submitDesktopCreation(report.operationId);
  }
  const creation = await until(async () => {
    const value = await service.session.queryDesktopCreation(report.operationId);
    assert.notEqual(value.phase, 'rejected', value.error);
    return value.phase === 'confirmed' && value.threadId ? value : null;
  });
  report.threadId = creation.threadId;
  await save();
  await observer.connect();
  const original = await until(async () => {
    await observer.follow(report.threadId, 10000, true);
    const turns = desktopTurns(observer.state(report.threadId) ?? {});
    return turns.length === 1 &&
      turns[0].status === 'completed' &&
      turns[0].items.some(
        (item) => item.type === 'agentMessage' && item.text.includes(markdownLabel),
      )
      ? turns
      : null;
  });
  assert.equal(original[0].items.filter((item) => item.type === 'userMessage').length, 1);
  assert.equal(
    original[0].params.effort ?? original[0].params.collaborationMode?.settings?.reasoning_effort,
    'xhigh',
  );
  report.originalOwner = {
    turnId: original[0].turnId,
    model: original[0].params.model,
    effort: 'xhigh',
    inputs: 1,
  };
  const history = await service.session.open(report.threadId);
  const answer = history.messages.findLast(
    (message) => message.role === 'assistant' && message.text.includes(markdownLabel),
  );
  assert(answer?.attachments?.length === 2, 'Use actual registered original-message files');
  assert(answer.attachments.some((file) => file.sha256 === report.sourceSha256));
  const token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  report.requests = [];
  gateway = createServer((request, response) => {
    report.requests.push({ method: request.method, path: request.url.split('?')[0] });
    const forward = proxyRequest(
      `http://127.0.0.1:${service.server.address().port}` + request.url,
      { method: request.method, headers: request.headers },
      (upstream) => {
        response.writeHead(upstream.statusCode, upstream.headers);
        upstream.pipe(response);
      },
    );
    forward.on('error', () => response.writeHead(502).end());
    request.pipe(forward);
  });
  await new Promise((done) => gateway.listen(0, '127.0.0.1', done));
  assert(
    !JSON.parse(run(tailscale, ['serve', 'status', '--json'])).TCP?.[port],
    'Keep routes added during the build',
  );
  run(tailscale, [
    'serve',
    '--bg',
    '--https=' + port,
    `http://127.0.0.1:${gateway.address().port}`,
  ]);
  routed = true;
  const fixture = {
    bundleId,
    markdownLabel,
    plainLabel,
    sourceSha256: report.sourceSha256,
    connection: {
      endpoint,
      token,
      hostId: report.hostId,
      threadId: report.threadId,
      // A short unique query avoids whitespace normalization of a long desktop title.
      // The XCTest still selects the exact original thread ID.
      threadTitle: 'FILE_PREVIEW_' + report.nonce,
      nonce: report.nonce,
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    },
  };
  const xc = JSON.parse(run('plutil', ['-convert', 'json', '-o', '-', runner.template]));
  const target = Object.values(xc).find((value) => value?.IsUITestBundle);
  target.EnvironmentVariables = {
    ...target.EnvironmentVariables,
    FILE_PREVIEW_FIXTURE: JSON.stringify(fixture),
  };
  await writeFile(runner.template, JSON.stringify(xc), { mode: 0o600 });
  run('plutil', ['-convert', 'xml1', runner.template]);
  const app = target.UITargetAppPath.replaceAll('__TESTROOT__', dirname(runner.template));
  const info = JSON.parse(run('plutil', ['-convert', 'json', '-o', '-', join(app, 'Info.plist')]));
  assert.equal(info.CFBundleIdentifier, bundleId);
  report.appSha256 = hash(await readFile(join(app, info.CFBundleExecutable)));
  await save();
  await until(
    async () =>
      !run('ps', ['-axo', 'command'])
        .split('\n')
        .some((line) => /xcodebuild .*test/.test(line) && line.includes(device)),
    600000,
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
  const result = join(directory, 'native-' + Date.now() + '.xcresult');
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
      result,
      '-only-testing:CodexManageUITests/CodexManageUITests/testMarkdownFilePreviewPhysicalDEV',
    ],
    'native-' + Date.now(),
  );
  const summary = JSON.parse(
    run('xcrun', [
      'xcresulttool',
      'get',
      'test-results',
      'summary',
      '--path',
      result,
      '--format',
      'json',
    ]),
  );
  report.devices = verifyIOSResultDevice(summary, device);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.passedTests, 1);
  await observer.follow(report.threadId, 15000, true);
  const after = desktopTurns(observer.state(report.threadId) ?? {});
  assert.equal(after.length, 1);
  assert.equal(after[0].turnId, report.originalOwner.turnId);
  assert.equal(after[0].items.filter((item) => item.type === 'userMessage').length, 1);
  assert(
    !report.requests.some(
      (request) =>
        request.method === 'POST' &&
        /^\/v1\/(replies|desktop\/creations|stop|approvals)/.test(request.path),
    ),
  );
  report.result = result;
  report.runner = runner;
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  report.attempts.push({
    at: new Date().toISOString(),
    error: error.message,
    nativeSourceSha256: report.nativeSourceSha256,
  });
  throw error;
} finally {
  observer.close();
  if (gateway) await new Promise((done) => gateway.close(done));
  if (service) await service.close();
  if (routed) run(tailscale, ['serve', '--https=' + port, 'off']);
  report.routeRemoved = !JSON.parse(run(tailscale, ['serve', 'status', '--json'])).TCP?.[port];
  await save();
  console.log(
    JSON.stringify({
      status: report.status,
      directory,
      modelSubmissionStarted: report.modelSubmitStarted,
      verifiedOriginalInputs: report.originalOwner?.inputs ?? null,
    }),
  );
}
