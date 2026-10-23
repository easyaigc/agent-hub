import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// A fresh Host cache exercises the actual reader/owner boundary. No fake tools,
// history edits, thread/resume calls or replacement execution service are used.
const threadId = process.env.DESKTOP_ACCEPTANCE_THREAD_ID;
const phase = process.env.DESKTOP_TOOL_PHASE ?? 'history';
const device = process.env.IOS_SIMULATOR_ID;
assert(threadId && device, 'Select a dedicated Codex acceptance chat and simulator');
assert(['history', 'live'].includes(phase));
const directory = resolve('.local/desktop-tools-acceptance/' + phase);
await mkdir(directory, { recursive: true, mode: 0o700 });
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE:
    '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
  HOST_AGENT_STATE_DIR: directory,
  HOST_AGENT_NAME: 'Codex Tool Acceptance',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
});
delete process.env.CODEX_DESKTOP_CONTROLLER_CONFIG;
const service = await startRuntimeServer();
const token = (await readFile(join(directory, 'access-token'), 'utf8')).trim();
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
const nonce = crypto.randomUUID().slice(0, 8);
const evidence = {
  phase,
  appMode: phase === 'live' ? 'ordinary' : 'ui-testing',
  threadId,
  nonce,
  status: 'running',
};
const save = async () => {
  const bytes = JSON.stringify(evidence, null, 2);
  await writeFile(join(directory, `run-${nonce}.json`), bytes, { mode: 0o600 });
  await writeFile(join(directory, 'latest-run.json'), bytes, { mode: 0o600 });
};
let child, fixtureServer;
try {
  await ipc.connect();
  let projectCursor, threadCursor, workspace;
  do {
    workspace = await service.session.workspace(projectCursor, threadCursor);
    projectCursor = workspace.nextProjectCursor ?? undefined;
    threadCursor = workspace.nextThreadCursor ?? undefined;
  } while (projectCursor || threadCursor);
  const initial = await service.session.open(threadId);
  assert.equal(initial.runtime.canControl, phase === 'live');
  assert.equal(initial.runtime.turnId, null);
  assert.deepEqual(workspace.clientType, { id: 'codex', name: 'Codex' });
  const beforeIds = new Set(initial.messages.map((m) => m.id));
  if (phase === 'history') {
    const raw = await service.session.runtime.reader.read('thread/items/list', {
      threadId,
      limit: 100,
      sortDirection: 'desc',
    });
    const expected = raw.data.filter((e) => e.item.type === 'commandExecution');
    assert(expected.length > 0, 'Actual Codex history must contain command tools');
    for (const { item } of expected) {
      const mapped = initial.messages.find((m) => m.id === item.id);
      assert.equal(mapped?.role, 'tool');
      assert.equal(mapped?.kind, 'commandExecution');
      assert.equal(mapped?.text, item.command);
      if (typeof item.aggregatedOutput === 'string')
        assert(mapped?.detail.includes(item.aggregatedOutput));
    }
    evidence.rawCommands = expected.length;
    evidence.mappedCommands = initial.messages.filter((m) => m.kind === 'commandExecution').length;
    evidence.canControl = initial.runtime.canControl;
  }
  const fixture = {
    endpoint: `http://127.0.0.1:${service.server.address().port}`,
    token,
    hostId: service.session.hostId,
    threadId,
    threadTitle: initial.thread.title,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  fixtureServer = createServer((req, res) => {
    if (
      req.method !== 'GET' ||
      req.url !== '/' ||
      req.headers.origin ||
      !['127.0.0.1:8795', 'localhost:8795'].includes(req.headers.host)
    ) {
      res.writeHead(403);
      res.end();
      return;
    }
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(fixture));
  });
  await new Promise((done, reject) => {
    fixtureServer.once('error', reject);
    fixtureServer.listen(8795, '127.0.0.1', done);
  });
  const resultBundle = join(directory, `result-${nonce}.xcresult`);
  evidence.resultBundle = resultBundle;
  await save();
  const test = phase === 'history' ? 'testCodexHistoricalTools' : 'testCodexLiveTools';
  child = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS Simulator,id=${device}`,
      '-derivedDataPath',
      '.build/ios',
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-only-testing:CodexManageUITests/CodexManageUITests/' + test,
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  const log = createWriteStream(join(directory, `xcode-${nonce}.log`), { mode: 0o600 });
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
    child.once('error', reject);
    child.once('exit', done);
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
  Object.assign(evidence, {
    passedTests: summary.passedTests,
    failedTests: summary.failedTests,
    skippedTests: summary.skippedTests,
  });
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  if (phase === 'live') {
    const state = await ipc.follow(threadId);
    assert(ipc.hasOwner(threadId));
    const items = desktopTurns(state).flatMap((t) => t.items ?? []);
    const history = await service.session.open(threadId);
    for (const count of [1, 2]) {
      const marker = `CODEX_TOOLS_${count}_${nonce}`;
      const commands = items.filter(
        (i) => i.type === 'commandExecution' && i.command.includes(marker) && !beforeIds.has(i.id),
      );
      assert.equal(commands.length, count);
      assert(
        commands.every(
          (i) =>
            i.status === 'completed' &&
            i.exitCode === 0 &&
            i.aggregatedOutput.includes(marker + '_输出'),
        ),
      );
      assert(items.some((i) => i.type === 'agentMessage' && i.text === marker));
      for (const i of commands)
        assert(
          history.messages.some(
            (m) => m.id === i.id && m.role === 'tool' && m.detail.includes(marker + '_输出'),
          ),
        );
    }
    evidence.ownerVerified = true;
    evidence.realCommands = 3;
  }
  evidence.status = 'passed';
  await save();
  console.log(JSON.stringify(evidence));
} catch (error) {
  evidence.status = 'failed';
  evidence.error = error.message;
  await save();
  throw error;
} finally {
  child?.kill('SIGTERM');
  fixtureServer?.close();
  ipc.close();
  service.session.close();
  service.server.closeAllConnections();
  service.server.close();
}
