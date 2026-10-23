import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

const threadId = process.env.DESKTOP_ACCEPTANCE_THREAD_ID;
const device = process.env.IOS_SIMULATOR_ID;
assert(
  threadId && device,
  'Select an idle dedicated original desktop chat and local DEV simulator',
);
const source = resolve(process.env.UNREAD_SOURCE_ROOT ?? '.');
const { DesktopRuntime, desktopTurns } = await import(
  join(source, 'packages/codex-adapter/dist/desktop-ipc.js')
);
const { startRuntimeServer } = await import(join(source, 'apps/host-agent/dist/runtime-server.js'));
const output = resolve(
  process.env.UNREAD_ACCEPTANCE_OUTPUT ?? '.local/unread-desktop-' + Date.now(),
);
await mkdir(output, { recursive: true, mode: 0o700 });
const executable =
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
const resumedReceipt =
  process.env.UNREAD_RESUME_FIRST_TASK === '1'
    ? JSON.parse(await readFile(join(output, 'host-state/runtime.json'), 'utf8')).receipts.at(-1)
    : undefined;
if (resumedReceipt) {
  assert.equal(resumedReceipt.threadId, threadId);
  assert.equal(resumedReceipt.state, 'completed');
  assert.equal(resumedReceipt.effort, 'xhigh');
  assert(resumedReceipt.turnId && resumedReceipt.reply?.text?.match(/READ_DEV_[a-f0-9]+_1/));
}
const nonce = resumedReceipt
  ? resumedReceipt.reply.text.match(/READ_DEV_[a-f0-9]+(?=_1)/)[0]
  : 'READ_DEV_' + randomUUID().slice(0, 8);
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE: executable,
  HOST_AGENT_STATE_DIR: join(output, 'host-state'),
  HOST_AGENT_ROOT: process.cwd(),
  HOST_AGENT_NAME: 'Reading DEV',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
});
for (const key of [
  'CODEX_DESKTOP_CONTROLLER_CONFIG',
  'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
  'HOST_NOTIFICATION_RELAY_URL',
  'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
  'HOST_AGENT_TOKEN',
])
  delete process.env[key];
const verifier = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  'reading-independent-verifier',
  'Reading Verifier',
);
const report = {
  status: 'running',
  scenario: 'mobile-reading-original-owner-isolation',
  threadId,
  startedAt: new Date().toISOString(),
  tasks: [],
  submitted: [],
  acknowledgements: [],
};
const save = () =>
  writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const json = (res, status, value) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
let service,
  controlServer,
  child,
  secondSubmitted = false,
  token;
async function ownerSnapshot() {
  for (let attempt = 0; attempt < 3; attempt++) {
    const state = await verifier.ipc.follow(threadId, 15000, true);
    if (state && verifier.ipc.hasOwner(threadId)) return state;
    await new Promise((done) => setTimeout(done, 500));
  }
  throw new Error('Independent desktop owner snapshot unavailable');
}
async function task(number) {
  const marker = nonce + '_' + number;
  const receipt =
    number === 1 && resumedReceipt
      ? resumedReceipt
      : await service.session.submit({
          hostId: service.session.hostId,
          threadId,
          clientMessageId: randomUUID(),
          text:
            '专用已读隔离 DEV 验收：只原样回复 ' + marker + '。不要使用工具，不读取或修改文件。',
          attachmentIds: [],
          mode: 'next-turn',
          effort: 'xhigh',
        });
  assert(receipt.turnId && !['failed', 'uncertain'].includes(receipt.state));
  report.submitted.push({
    number,
    turnId: receipt.turnId,
    clientMessageId: receipt.clientMessageId,
    reusedCompletedReceipt: number === 1 && Boolean(resumedReceipt),
  });
  await save();
  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    const owner = await ownerSnapshot();
    const turn = desktopTurns(owner).find((turn) => turn.turnId === receipt.turnId);
    if (turn && turn.status !== 'inProgress') {
      assert.equal(turn.status, 'completed');
      const replies = (turn.items ?? []).filter((item) => item.type === 'agentMessage');
      assert(replies.some((item) => item.text?.includes(marker)));
      assert.equal(
        (turn.items ?? []).filter(
          (item) => item.type === 'userMessage' && JSON.stringify(item).includes(marker),
        ).length,
        1,
      );
      await service.session.open(threadId);
      report.tasks.push({
        turnId: turn.turnId,
        status: turn.status,
        marker,
        desktopUnread: owner.hasUnreadTurn,
        model: turn.params?.model ?? owner.latestModel,
        effort: turn.params?.effort ?? turn.params?.collaborationMode?.settings?.reasoning_effort,
      });
      await save();
      return marker;
    }
    await new Promise((done) => setTimeout(done, 1000));
  }
  throw new Error('Real desktop model task did not complete');
}
try {
  await verifier.connect();
  const initial = await ownerSnapshot();
  assert(
    verifier.ipc.hasOwner(threadId) &&
      !desktopTurns(initial).some((turn) => turn.status === 'inProgress'),
  );
  report.desktopBuild = verifier.version;
  const digest = createHash('sha256');
  for (const path of [
    'packages/codex-adapter/dist/runtime-session.js',
    'packages/codex-adapter/dist/attachment-files.js',
    'packages/protocol/dist/read-state.js',
    'apps/host-agent/dist/runtime-server.js',
    'apps/ios/Sources/ThreadReading.swift',
    'apps/ios/Sources/SessionStore.swift',
    'apps/ios/Sources/RootView.swift',
    'apps/ios/Sources/MobileUnreadBadge.swift',
    'apps/ios/Sources/TaskNotifications.swift',
    'apps/ios/NotificationService/NotificationService.swift',
  ])
    digest.update(await readFile(join(source, path)));
  report.implementationSha256 = digest.digest('hex');
  service = await startRuntimeServer();
  await service.session.open(threadId);
  token = (await readFile(join(output, 'host-state/access-token'), 'utf8')).trim();
  const endpoint = 'http://127.0.0.1:' + service.server.address().port;
  service.server.prependListener('request', (req, res) => {
    const url = new URL(req.url, endpoint),
      end = res.end;
    res.end = function (body, ...args) {
      if (res.statusCode === 200 && body) {
        if (url.pathname === '/v1/workspace') {
          const value = JSON.parse(body.toString());
          value.threads = value.threads.filter((thread) => thread.id === threadId);
          value.nextThreadCursor = null;
          const projects = new Set(
            value.threads.flatMap((thread) => [thread.projectId, ...(thread.projectIds ?? [])]),
          );
          value.projects = value.projects.filter((project) => projects.has(project.id));
          body = JSON.stringify(value);
        } else if (url.pathname === '/v1/notifications/events') {
          // The DEV workspace exposes one dedicated chat. Its foreground notices
          // must use the same scope while retaining the Host's global cursor.
          const value = JSON.parse(body.toString());
          const selected = value.events.filter((event) => event.threadId === threadId);
          report.filteredNotificationEvents =
            (report.filteredNotificationEvents ?? 0) + value.events.length - selected.length;
          value.events = selected;
          body = JSON.stringify(value);
        } else if (url.pathname === '/v1/threads/read')
          report.acknowledgements.push(JSON.parse(body.toString()));
      }
      return end.call(this, body, ...args);
    };
  });
  await task(1);
  const fixture = {
    endpoint,
    token,
    hostId: service.session.hostId,
    threadId,
    threadTitle: initial.title,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  controlServer = createServer(async (req, res) => {
    try {
      if (req.method !== 'GET' || req.headers.origin) return json(res, 403, {});
      if (req.url === '/') return json(res, 200, fixture);
      if (req.headers.authorization !== 'Bearer ' + token) return json(res, 401, {});
      if (req.url === '/next') {
        assert(!secondSubmitted);
        secondSubmitted = true;
        await task(2);
        return json(res, 200, { ready: true });
      }
      if (req.url === '/results') {
        const state = await ownerSnapshot();
        return json(res, 200, {
          acknowledgements: report.acknowledgements.length,
          desktopUnread: state.hasUnreadTurn,
        });
      }
      return json(res, 404, {});
    } catch (error) {
      report.controlError = error.message;
      await save();
      json(res, 500, { error: error.message });
    }
  });
  await new Promise((done) => controlServer.listen(8805, '127.0.0.1', done));
  const resultBundle = join(output, 'native.xcresult');
  child = spawn(
    'xcodebuild',
    [
      '-project',
      join(source, 'apps/ios/CodexManage.xcodeproj'),
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      process.env.IOS_DERIVED_DATA_PATH ?? join(output, 'build'),
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopMobileReadingIsolation',
      'test',
    ],
    { stdio: 'inherit' },
  );
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        resultBundle,
        '--format',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  );
  report.native = {
    passed: summary.passedTests,
    failed: summary.failedTests,
    skipped: summary.skippedTests,
  };
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  assert.equal(report.tasks.length, 2);
  assert(report.acknowledgements.length >= 2);
  const last = await ownerSnapshot();
  assert.equal(
    last.hasUnreadTurn,
    report.tasks.at(-1).desktopUnread,
    'Phone reading must preserve the original desktop state',
  );
  const latest = service.session.projectReadState(
    service.session.history(threadId),
    report.acknowledgements.at(-1).readerId,
  ).thread.readState;
  assert.deepEqual(latest.read, latest.latest);
  service.close();
  service = await startRuntimeServer();
  const recovered = service.session.projectReadState(
    await service.session.open(threadId),
    report.acknowledgements.at(-1).readerId,
  ).thread.readState;
  assert.deepEqual(recovered.read, latest.read);
  assert.deepEqual(recovered.latest, latest.latest);
  report.hostRestart = 'passed';
  report.desktopStatePreserved = true;
  report.status = 'passed';
  await save();
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  await save();
  throw error;
} finally {
  if (child?.exitCode === null) child.kill();
  controlServer?.closeAllConnections();
  controlServer?.close();
  service?.close();
  verifier.close();
}
