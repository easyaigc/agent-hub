import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { dirname, join, resolve } from 'node:path';
import { DesktopRuntime, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { controllerAdapterDigest } from '../packages/codex-adapter/dist/desktop-controller.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// A dedicated, already-loaded acceptance chat receives one real model task.
// The native app only refreshes its sidebar; an independent owner checks results.
const threadId = process.env.DESKTOP_ACCEPTANCE_THREAD_ID;
const device = process.env.IOS_SIMULATOR_ID;
const verifyUnread = process.env.SIDEBAR_VERIFY_UNREAD === '1';
assert(threadId && device, 'Select a dedicated idle desktop acceptance chat and simulator');
const nonce = crypto.randomUUID().slice(0, 8);
const directory = resolve('.local/sidebar-state-acceptance/' + nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
const executable =
  process.env.CODEX_EXECUTABLE ??
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE: executable,
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_NAME: 'Sidebar State Acceptance',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
});
delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
const verifier = new DesktopRuntime(
  { mode: 'desktop-runtime', executable },
  'sidebar-verifier',
  'Sidebar Verifier',
);
let service, fixtureServer, child, startedTurn;
let startRequested = false;
let historyRequests = 0,
  openRequests = 0,
  workspaceRequests = 0;
const workspaceDurationsMs = [];
const workspaceStates = [];
const nativeProject = process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj';
const nativeFingerprint = async () => {
  const hash = createHash('sha256');
  for (const file of ['Models.swift', 'HostService.swift', 'SessionStore.swift', 'RootView.swift'])
    hash.update(await readFile(join(dirname(nativeProject), 'Sources', file)));
  return hash.digest('hex');
};
const marker = 'SIDEBAR_STATE_' + nonce;
const evidence = {
  startedAt: new Date().toISOString(),
  status: 'running',
  appMode: 'ordinary',
  scenario: verifyUnread
    ? 'unopened-chat-completed-unread'
    : 'unopened-chat-refresh-running-completed',
  threadId,
  nonce,
  adapterSha256: controllerAdapterDigest(),
  nativeSourceSha256: await nativeFingerprint(),
  nativeProjectIsolated: !!process.env.IOS_PROJECT_PATH,
};
const save = () =>
  writeFile(join(directory, 'run.json'), JSON.stringify(evidence, null, 2), { mode: 0o600 });
const originalProcesses = () => {
  const rows = execFileSync('ps', ['-axo', 'pid,ppid,command'], { encoding: 'utf8' })
    .split('\n')
    .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/))
    .filter(Boolean);
  const main = rows.filter((row) =>
    /^\/Applications\/ChatGPT.app\/Contents\/MacOS\/ChatGPT(?: |$)/.test(row[3]),
  );
  return [
    ...main,
    ...rows.filter(
      (row) =>
        main.some((parent) => parent[1] === row[2]) &&
        row[3].includes('/codex-cli/') &&
        row[3].includes('app-server'),
    ),
  ].map((row) => [row[1], row[2]]);
};
try {
  await verifier.connect();
  const initial = await verifier.ipc.follow(threadId);
  assert(verifier.ipc.hasOwner(threadId), 'Dedicated chat needs a verified original owner');
  assert.notEqual(initial.threadRuntimeStatus?.type, 'active');
  assert(!desktopTurns(initial).some((turn) => turn.status === 'inProgress'));
  evidence.desktopBuild = verifier.version;
  evidence.initialUnread = initial.hasUnreadTurn;
  evidence.beforeProcesses = originalProcesses();
  service = await startRuntimeServer();
  const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
  const endpoint = `http://127.0.0.1:${service.server.address().port}`;
  service.server.prependListener('request', (request, response) => {
    const url = new URL(request.url, endpoint);
    if (url.pathname === '/v1/workspace') {
      workspaceRequests++;
      const started = Date.now();
      response.once('finish', () => workspaceDurationsMs.push(Date.now() - started));
      const end = response.end;
      response.end = function (body, ...args) {
        const workspace = JSON.parse(body.toString());
        const thread = workspace.threads?.find((thread) => thread.id === threadId);
        workspaceStates.push({
          state: thread?.state,
          turnId: thread?.turnId,
          hasUnreadTurn: thread?.hasUnreadTurn,
        });
        return end.call(this, body, ...args);
      };
    }
    if (url.searchParams.get('threadId') === threadId && url.pathname === '/v1/history')
      historyRequests++;
    if (url.pathname === '/v1/desktop/open') openRequests++;
  });
  const fixture = {
    endpoint,
    token,
    hostId: service.session.hostId,
    threadId,
    threadTitle: initial.title,
    verifyUnread,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  const json = (response, status, value) => {
    response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(value));
  };
  fixtureServer = createServer(async (request, response) => {
    try {
      if (
        request.method !== 'GET' ||
        request.headers.origin ||
        !['127.0.0.1:8799', 'localhost:8799'].includes(request.headers.host)
      )
        return json(response, 403, {});
      if (request.url === '/') return json(response, 200, fixture);
      if (request.url === '/refresh-count') return json(response, 200, { workspaceRequests });
      if (request.url === '/start') {
        assert(!startRequested, 'Acceptance task must only be submitted once');
        startRequested = true;
        const result = verifyUnread
          ? await service.session.submit({
              hostId: service.session.hostId,
              threadId,
              clientMessageId: crypto.randomUUID(),
              text: `专用未读状态验收：仅原样回复 ${marker}，不要使用工具。`,
              attachmentIds: [],
              mode: 'next-turn',
            })
          : await verifier.call('turn/start', {
              threadId,
              input: [
                {
                  type: 'text',
                  text: `专用侧栏状态验收：使用 exec_command 执行 sleep 45，等待自然结束，最后仅回复 ${marker}。只执行这一个命令，不读取或修改文件，不创建会话，不调用其他工具。`,
                  text_elements: [],
                },
              ],
            });
        startedTurn = verifyUnread ? result.turnId : result.turn.id;
        if (verifyUnread)
          evidence.submission = {
            state: result.state,
            turnId: result.turnId,
            error: result.error,
          };
        await save();
        assert(startedTurn);
        evidence.turnId = startedTurn;
        evidence.independentRunning =
          verifier.ipc.state(threadId).threadRuntimeStatus?.type === 'active';
        if (!verifyUnread) assert(evidence.independentRunning);
        await save();
        return json(response, 200, { running: true });
      }
      if (request.url === '/status') {
        const state = verifier.ipc.state(threadId);
        const turn = desktopTurns(state).find((turn) => turn.turnId === startedTurn);
        if (turn?.status === 'completed' && !evidence.completionObservation) {
          evidence.completionObservation = {
            verifierUnread: state.hasUnreadTurn,
            hostUnread: service.session.history(threadId).thread.hasUnreadTurn,
          };
          await save();
        }
        return json(response, 200, {
          completed: turn?.status === 'completed' && state.threadRuntimeStatus?.type !== 'active',
        });
      }
      return json(response, 404, {});
    } catch (error) {
      evidence.controlError = error.message;
      await save();
      json(response, 500, { error: error.message });
    }
  });
  await new Promise((done, reject) => {
    fixtureServer.once('error', reject);
    fixtureServer.listen(8799, '127.0.0.1', done);
  });
  const resultBundle = join(directory, 'result.xcresult');
  evidence.resultBundle = resultBundle;
  await save();
  child = spawn(
    'xcodebuild',
    [
      '-project',
      nativeProject,
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS Simulator,id=${device}`,
      '-derivedDataPath',
      '.build/sidebar-state-ios',
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopSidebarRefreshShowsUnopenedRunningChat',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      const safe = line
        .replaceAll(token, '[REDACTED]')
        .replaceAll(token.slice(0, 16), '[REDACTED]');
      log.write(safe + '\n');
      if (/^Test Case|: error:|^\s*Executed |^\*\* TEST (SUCCEEDED|FAILED)/.test(safe))
        console.log(safe);
    });
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  await new Promise((done) => log.end(done));
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
  evidence.nativeBinarySha256 = createHash('sha256')
    .update(
      await readFile(
        '.build/sidebar-state-ios/Build/Products/Debug-iphonesimulator/CodexManage.app/CodexManage.debug.dylib',
      ),
    )
    .digest('hex');
  Object.assign(evidence, {
    nativePassed: summary.passedTests,
    nativeFailed: summary.failedTests,
    nativeSkipped: summary.skippedTests,
    historyRequests,
    openRequests,
    workspaceDurationsMs,
    workspaceStates,
  });
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  assert.equal(
    await nativeFingerprint(),
    evidence.nativeSourceSha256,
    'Native source changed during acceptance',
  );
  assert.equal(historyRequests, 0, 'The phone must never load the target chat');
  assert.equal(openRequests, 0, 'Sidebar refresh must never open desktop chats');
  if (!verifyUnread)
    assert(
      workspaceStates.some((thread) => thread.state === 'running' && thread.turnId === startedTurn),
    );
  assert.equal(workspaceStates.at(-1).state, 'idle');
  assert(
    workspaceDurationsMs.every((duration) => duration < 35000),
    'Workspace refresh must fit the native request timeout',
  );
  const state = verifier.ipc.state(threadId);
  const turn = desktopTurns(state).find((turn) => turn.turnId === startedTurn);
  assert.equal(turn.status, 'completed');
  if (verifyUnread) {
    assert.equal(state.hasUnreadTurn, true, 'Original desktop must independently report unread');
    assert.equal(workspaceStates.at(-1).hasUnreadTurn, true);
    evidence.independentUnread = true;
    evidence.desktopVisualCheck = 'not-verified-computer-use-denied';
  }
  assert(turn.items.some((item) => item.type === 'agentMessage' && item.text.trim() === marker));
  const tools = turn.items.filter((item) => item.type === 'commandExecution');
  assert.equal(tools.length, verifyUnread ? 0 : 1);
  if (!verifyUnread) assert.equal(tools[0].exitCode, 0);
  evidence.afterProcesses = originalProcesses();
  assert.deepEqual(evidence.afterProcesses, evidence.beforeProcesses);
  Object.assign(evidence, {
    status: 'passed',
    independentCompleted: true,
    originalExecutionUnchanged: true,
    finishedAt: new Date().toISOString(),
  });
  await save();
  console.log(JSON.stringify(evidence));
} catch (error) {
  Object.assign(evidence, { status: 'failed', error: error.message });
  await save();
  throw error;
} finally {
  child?.kill('SIGTERM');
  if (startedTurn && verifier.ipc.state(threadId)?.threadRuntimeStatus?.type === 'active')
    await verifier.call('turn/interrupt', { threadId, turnId: startedTurn }).catch(() => {});
  fixtureServer?.closeAllConnections();
  if (fixtureServer) await new Promise((done) => fixtureServer.close(done));
  await service?.close();
  verifier.close();
}
