import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { cp, mkdir, readFile, readdir, writeFile, symlink, rename } from 'node:fs/promises';
import { networkInterfaces, homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline';
import { setTimeout as delay } from 'node:timers/promises';

// One dedicated original-desktop task, a frozen independent Host, and a dedicated native simulator.
// Reruns reuse the original immutable operation and never replay its model input.
const directory = resolve(
  process.env.MESSAGE_LINKS_ACCEPTANCE_DIR ?? join('.local/message-links-dev', randomUUID()),
);
await mkdir(directory, { recursive: true, mode: 0o700 });
let report;
try {
  report = JSON.parse(await readFile(join(directory, 'report.json'), 'utf8'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
report ??= { nonce: randomUUID().slice(0, 8), operationId: randomUUID(), status: 'running' };
if (report.status !== 'running') {
  (report.attempts ??= []).push({
    status: report.status,
    error: report.error,
    result: report.result,
    adapterSha256: report.adapterSha256,
    hostSha256: report.hostSha256,
    nativeSha256: report.nativeSha256,
  });
  report.status = 'running';
  delete report.error;
}
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const frozen = join(directory, 'frozen');
const native = process.env.MESSAGE_LINKS_NATIVE_PATH
  ? resolve(process.env.MESSAGE_LINKS_NATIVE_PATH)
  : join(directory, 'native');
const historyFallback = process.env.MESSAGE_LINKS_HISTORY_FALLBACK === '1';
if (!report.frozen) {
  await mkdir(frozen, { recursive: true });
  await cp('packages', join(frozen, 'packages'), { recursive: true, verbatimSymlinks: true });
  await cp('apps/host-agent', join(frozen, 'apps/host-agent'), {
    recursive: true,
    verbatimSymlinks: true,
  });
  if (!process.env.MESSAGE_LINKS_NATIVE_PATH) await cp('apps/ios', native, { recursive: true });
  await symlink(resolve('node_modules'), join(frozen, 'node_modules'), 'dir');
  const digest = createHash('sha256');
  for (const file of (await readdir(join(frozen, 'packages/codex-adapter/dist')))
    .filter((v) => v.endsWith('.js'))
    .sort()) {
    digest.update(file).update(await readFile(join(frozen, 'packages/codex-adapter/dist', file)));
  }
  report.adapterSha256 = digest.digest('hex');
  report.frozen = true;
  await save();
}
async function nativeFingerprint() {
  const nativeDigest = createHash('sha256');
  for (const file of [
    'CodexManage.xcodeproj/project.pbxproj',
    'Tests/CodexManageUITests.swift',
    ...(await readdir(join(native, 'Sources')))
      .filter((v) => v.endsWith('.swift'))
      .sort()
      .map((v) => 'Sources/' + v),
    'Resources/en.lproj/Localizable.strings',
    'Resources/zh-Hans.lproj/Localizable.strings',
  ])
    nativeDigest.update(file).update(await readFile(join(native, file)));
  return nativeDigest.digest('hex');
}
report.nativeSha256 = await nativeFingerprint();
const hostDigest = createHash('sha256');
for (const file of (await readdir(join(frozen, 'apps/host-agent/dist')))
  .filter((v) => v.endsWith('.js'))
  .sort())
  hostDigest.update(file).update(await readFile(join(frozen, 'apps/host-agent/dist', file)));
report.hostSha256 = hostDigest.digest('hex');
await save();
const { startRuntimeServer } = await import(
  pathToFileURL(join(frozen, 'apps/host-agent/dist/runtime-server.js'))
);
const { DesktopIpc, desktopTurns } = await import(
  pathToFileURL(join(frozen, 'packages/codex-adapter/dist/desktop-ipc.js'))
);
const witness = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
const executable =
  process.env.CODEX_EXECUTABLE ??
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const run = (command, args) =>
  execFileSync(command, args, {
    encoding: 'utf8',
    env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
  });
const wait = async (probe, label) => {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const value = await probe();
    if (value) return value;
    await delay(500);
  }
  throw Error('Timed out: ' + label);
};
let host, metadata, web, token;
const state = join(directory, 'host');
try {
  const address = Object.entries(networkInterfaces())
    .filter(([name]) => name.startsWith('en'))
    .flatMap(([, entries]) => entries)
    .find((value) => value.family === 'IPv4' && !value.internal)?.address;
  assert(address, 'Select a reachable LAN address for real Safari preview');
  web = createServer((request, response) => {
    assert.equal(
      request.headers.authorization,
      undefined,
      'Host credentials must never reach the webpage',
    );
    if (request.url !== '/preview/' + report.nonce) return response.writeHead(404).end();
    report.webRequests = (report.webRequests ?? 0) + 1;
    response.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    response.end(
      '<!doctype html><html><meta name="viewport" content="width=device-width"><title>Link preview</title><h1>LINK_WEB_' +
        report.nonce +
        '</h1></html>',
    );
  });
  // A rerun must keep the same URL embedded in the original model response.
  await new Promise((done, reject) => {
    web.once('error', reject);
    web.listen(report.webPort ?? 0, '0.0.0.0', done);
  });
  report.webPort ??= web.address().port;
  report.webAddress ??= address;
  const webURL = `http://${report.webAddress}:${report.webPort}/preview/${report.nonce}`;
  const file = join(directory, '验证 (记录).txt');
  const contents = 'LINK_FILE_' + report.nonce;
  await writeFile(file, contents, { mode: 0o600 });
  let labels = ['文件网址', '带空格路径', '带行号路径', '相对路径'];
  const markdown = [
    `[${labels[0]}](${pathToFileURL(file).href})`,
    `[${labels[1]}](<${file}>)`,
    `[${labels[2]}](${encodeURI(file)}:12:3)`,
    `[${labels[3]}](${encodeURI('验证 (记录).txt')}#L2)`,
    `[网页预览](${webURL})`,
    `[缺失文件](${join(directory, 'missing.txt')})`,
    '[电脑本机网页](http://localhost:8788/report)',
    '[不支持的链接](codex://review?pr=example)',
  ].join('\n\n');
  report.text ??=
    'LINK_DEV_' +
    report.nonce +
    ' 手机链接专项真实验收。请先只读文件 ' +
    file +
    ' 核对内容。不要修改文件或调用任何发消息工具。最终原样输出以下 Markdown，每项独占段落，末尾另加读到的完整校验码：\n\n' +
    markdown;
  await save();
  const bridge = join(directory, 'bridge.json');
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_EXECUTABLE: executable,
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: directory,
    HOST_AGENT_NAME: 'Link DEV ' + report.nonce,
    HOST_AGENT_BIND: '127.0.0.1',
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
    CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG: bridge,
    HOST_AGENT_TRANSFER_DIR: join(directory, 'received'),
  });
  for (const key of [
    'HOST_AGENT_TOKEN',
    'CODEX_DESKTOP_CONTROLLER_CONFIG',
    'HOST_NOTIFICATION_RELAY_URL',
    'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  ])
    delete process.env[key];
  host = await startRuntimeServer();
  await writeFile(bridge, JSON.stringify({ hostId: host.session.hostId }), { mode: 0o600 });
  await host.session.refreshDesktopCreationAvailability();
  await host.session.workspace();
  if (!report.submitted) {
    host.session.prepareDesktopCreation({
      hostId: host.session.hostId,
      clientOperationId: report.operationId,
      projectId: null,
      text: report.text,
      attachments: [],
      effort: 'xhigh',
    });
    report.submitted = true;
    await save();
    await host.session.submitDesktopCreation(report.operationId);
  }
  const creation = await wait(async () => {
    const value = await host.session.queryDesktopCreation(report.operationId);
    if (value.phase === 'rejected') throw Error('Dedicated creation rejected');
    return value.phase === 'confirmed' && value.threadId ? value : undefined;
  }, 'dedicated original desktop creation');
  report.threadId = creation.threadId;
  await save();
  await witness.connect();
  const original = await wait(async () => {
    await witness.follow(report.threadId, 3000, true);
    const turns = desktopTurns(witness.state(report.threadId) ?? {});
    if (turns.length !== 1 || turns[0].status !== 'completed') return;
    if (
      !turns[0].items.some((item) => item.type === 'agentMessage' && item.text.includes(contents))
    )
      return;
    return turns;
  }, 'real model response');
  assert.equal(original[0].items.filter((v) => v.type === 'userMessage').length, 1);
  assert.equal(
    original[0].params.effort ?? original[0].params.collaborationMode?.settings?.reasoning_effort,
    'xhigh',
  );
  assert(witness.hasOwner(report.threadId));
  report.originalOwner = {
    turns: 1,
    inputs: 1,
    effort: 'xhigh',
    model: original[0].params.model,
    connected: true,
  };
  let historyState = 'completed';
  if (historyFallback) {
    // Deliberately drop complete snapshots at the receiver boundary. The
    // independent witness still verifies the real owner and single model turn.
    const ipc = host.session.runtime.ipc;
    const receive = ipc.receive.bind(ipc);
    ipc.receive = (message) => {
      if (
        message.method === 'thread-stream-state-changed' &&
        message.params?.conversationId === report.threadId &&
        message.params?.change?.type === 'snapshot'
      ) {
        report.droppedSnapshots = (report.droppedSnapshots ?? 0) + 1;
        return;
      }
      receive(message);
    };
    ipc.invalidate(report.threadId);
    const read = host.session.runtime.reader.history.bind(host.session.runtime.reader);
    host.session.runtime.reader.history = async (...args) => {
      if (args[0] === report.threadId && historyState === 'read-failure')
        throw Error('Injected latest history read failure');
      const page = await read(...args);
      if (
        args[0] !== report.threadId ||
        args[1] ||
        ['completed', 'pending-reply'].includes(historyState)
      )
        return page;
      return {
        ...page,
        latestTurn:
          historyState === 'unknown'
            ? undefined
            : { id: page.latestTurn?.id, status: 'inProgress' },
      };
    };
    const snapshot = host.session.history.bind(host.session);
    host.session.history = (...args) => {
      const result = snapshot(...args);
      if (args[0] === report.threadId && historyState === 'pending-reply') {
        result.runtime.receipts.push({
          clientMessageId: 'fixture-pending-only',
          hostId: host.session.hostId,
          threadId: report.threadId,
          state: 'submitted',
          turnId: null,
          error: null,
          noticeContext: 'current',
        });
      }
      return result;
    };
  }
  const history = await host.session.open(report.threadId);
  if (historyFallback) {
    const window = host.session.history(report.threadId, true);
    assert.equal(window.synchronization.state, 'history');
    assert.equal(window.thread.lastTurnStatus, 'completed');
    assert.equal(window.runtime.canControl, false);
    report.historyFallback = {
      synchronization: window.synchronization,
      latestTurnStatus: window.thread.lastTurnStatus,
      canControl: window.runtime.canControl,
    };
  }
  const answer = history.messages.findLast(
    (value) => value.role === 'assistant' && value.text.includes(contents),
  );
  assert(answer && answer.attachments.length === 1);
  // Projectless desktop cwd may differ from this test's authorized file root.
  // Keep the original task intact; exercise the paths actually resolved by its owner.
  report.unresolvedLabels = labels.filter(
    (label) => !answer.text.includes(`[${label}](codex-attachment://`),
  );
  labels = labels.filter((label) => answer.text.includes(`[${label}](codex-attachment://`));
  assert(labels.length >= 3 && labels.includes('文件网址'));
  report.resolvedLabels = labels;
  token = (await readFile(join(state, 'access-token'), 'utf8')).trim();
  const connection = {
    endpoint: 'http://127.0.0.1:' + host.server.address().port,
    token,
    hostId: host.session.hostId,
    threadId: report.threadId,
    threadTitle: 'LINK_DEV_' + report.nonce,
    nonce: report.nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  metadata = createServer(async (request, response) => {
    if (request.method !== 'GET' || request.headers.origin) return response.writeHead(403).end();
    const url = new URL(request.url, 'http://127.0.0.1:8896');
    if (url.pathname === '/history-state' && historyFallback) {
      const value = url.searchParams.get('value');
      assert(['running', 'unknown', 'completed', 'read-failure', 'pending-reply'].includes(value));
      historyState = value;
      try {
        await host.session.refreshHistory(report.threadId, undefined, true);
      } catch {
        assert.equal(value, 'read-failure');
        report.injectedHistoryReadFailures = (report.injectedHistoryReadFailures ?? 0) + 1;
      }
      host.session.emit(report.threadId, 'fixture/history-state');
      return response.writeHead(200, { 'Content-Type': 'application/json' }).end('{}');
    }
    if (url.pathname === '/update') {
      try {
        const stage = Number(url.searchParams.get('stage'));
        assert([1, 2, 3].includes(stage));
        const marker = contents + '_UPDATED_' + stage;
        if (stage === 2) {
          await writeFile(file + '.new', marker, { mode: 0o600 });
          await rename(file + '.new', file);
        } else await writeFile(file, marker, { mode: 0o600 });
        await assert.rejects(
          host.session.download(report.threadId, answer.attachments[0].id),
          (error) => {
            report.staleVersionError = { status: error.status, message: error.message };
            return error.status === 409 && error.message === '文件已发生变化，请刷新对话';
          },
        );
        const current = await host.session.resolveAttachment(
          report.threadId,
          answer.attachments[0].id,
        );
        (report.currentVersions ??= []).push({
          stage,
          id: current.attachment.id,
          sha256: current.attachment.sha256,
        });
        await save();
        return response
          .writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
          .end(JSON.stringify({ marker }));
      } catch (error) {
        return response.writeHead(500).end(JSON.stringify({ error: error.message }));
      }
    }
    if (url.pathname !== '/') return response.writeHead(404).end();
    response
      .writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
      .end(
        JSON.stringify({
          connection,
          labels,
          webMarker: 'LINK_WEB_' + report.nonce,
          historyFallback,
        }),
      );
  });
  await new Promise((done, reject) => {
    metadata.once('error', reject);
    metadata.listen(8896, '127.0.0.1', done);
  });
  report.device ??=
    process.env.IOS_SIMULATOR_ID ??
    run('xcrun', [
      'simctl',
      'create',
      'Agent Hub Links DEV ' + report.nonce,
      'com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro',
      'com.apple.CoreSimulator.SimRuntime.iOS-26-0',
    ]).trim();
  await save();
  const devices = Object.values(
    JSON.parse(run('xcrun', ['simctl', 'list', 'devices', '--json'])).devices,
  ).flat();
  if (devices.find((v) => v.udid === report.device)?.state !== 'Booted')
    run('xcrun', ['simctl', 'boot', report.device]);
  run('xcrun', ['simctl', 'bootstatus', report.device, '-b']);
  const attempt = Date.now();
  const log = createWriteStream(join(directory, 'native-' + attempt + '.log'), { mode: 0o600 });
  const result = join(directory, 'native-' + attempt + '.xcresult');
  report.result = result;
  await save();
  console.log('Original desktop/model verified; native link acceptance starting. ' + directory);
  const child = spawn(
    'xcodebuild',
    [
      '-project',
      join(native, 'CodexManage.xcodeproj'),
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + report.device,
      '-derivedDataPath',
      join(directory, 'build'),
      '-resultBundlePath',
      result,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopMessageLinksDEV',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    {
      env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = line.replaceAll(token, '[REDACTED]');
      log.write(safe + '\n');
      if (/^Test Case|: error:|^\*\* TEST/.test(safe)) console.log(safe);
    });
  const exit = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  await new Promise((done) => log.end(done));
  report.native = JSON.parse(
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
  await save();
  assert.equal(exit, 0, 'native link test');
  assert.equal(
    await nativeFingerprint(),
    report.nativeSha256,
    'Native source must remain stable during acceptance',
  );
  report.nativeSourceStable = true;
  assert.equal(report.native.passedTests, 1);
  assert.equal(report.native.failedTests + report.native.skippedTests, 0);
  assert(report.webRequests > 0);
  const container = run('xcrun', [
    'simctl',
    'get_app_container',
    report.device,
    'com.codexmanage.ios',
    'data',
  ]).trim();
  const attachment = (
    await host.session.resolveAttachment(report.threadId, answer.attachments[0].id)
  ).attachment;
  const downloaded = await readFile(
    join(container, 'Library/Caches/Downloads', attachment.id, attachment.name),
  );
  assert.equal(downloaded.toString(), contents + '_UPDATED_3');
  assert.equal(createHash('sha256').update(downloaded).digest('hex'), attachment.sha256);
  await writeFile(join(directory, 'downloaded-evidence.txt'), downloaded, { mode: 0o600 });
  report.downloadSha256 = attachment.sha256;
  await witness.follow(report.threadId, 3000, true);
  assert.equal(
    desktopTurns(witness.state(report.threadId)).length,
    1,
    'preview/restart must not submit another model turn',
  );
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  throw error;
} finally {
  witness.close();
  for (const server of [metadata, web])
    if (server) {
      server.closeAllConnections();
      await new Promise((done) => server.close(done));
    }
  await host?.close();
  if (report.device && report.status === 'passed' && !process.env.IOS_SIMULATOR_ID) {
    run('xcrun', ['simctl', 'shutdown', report.device]);
    run('xcrun', ['simctl', 'delete', report.device]);
    report.simulatorCleaned = true;
  }
  await save();
  console.log(JSON.stringify({ directory, status: report.status }));
}
