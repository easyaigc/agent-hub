import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { createInterface } from 'node:readline';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';

// Uses an existing Host and idle original desktop QA chat. The localhost server
// supplies connection metadata only; every app request goes to the actual Host.
const threadId = process.env.DESKTOP_ACCEPTANCE_THREAD_ID;
const device = process.env.IOS_SIMULATOR_ID;
assert(threadId && device, 'Select an idle dedicated Codex QA chat and simulator');
const stateDirectory = resolve(process.env.HOST_AGENT_STATE_DIR ?? '.local/desktop-runtime');
const endpoint = process.env.DESKTOP_ACCEPTANCE_ENDPOINT ?? 'http://127.0.0.1:8794';
const token = (await readFile(join(stateDirectory, 'access-token'), 'utf8')).trim();
const directory = resolve('.local/read-lifecycle-desktop/' + Date.now());
await mkdir(directory, { recursive: true, mode: 0o700 });
const nonce = crypto.randomUUID().slice(0, 8);
const get = async (path) => {
  const response = await fetch(endpoint + path, {
    headers: { Authorization: 'Bearer ' + token },
  });
  assert.equal(response.status, 200);
  return response.json();
};
const processIds = () => {
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
const project = process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj';
const sources = ['SessionStore.swift', 'RootView.swift', 'AgentClient.swift'];
const fingerprint = async () => {
  const hash = createHash('sha256');
  for (const name of sources) hash.update(await readFile(join(project, '..', 'Sources', name)));
  return hash.digest('hex');
};
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let server, child;
const evidence = {
  status: 'running',
  nonce,
  threadId,
  appMode: 'ordinary',
  beforeProcesses: processIds(),
  nativeSourceSha256: await fingerprint(),
};
const save = () =>
  writeFile(join(directory, 'run.json'), JSON.stringify(evidence, null, 2), { mode: 0o600 });
try {
  await ipc.connect();
  const before = await ipc.follow(threadId);
  assert(ipc.hasOwner(threadId), 'QA chat must be loaded by the existing desktop');
  assert(!desktopTurns(before).some((turn) => turn.status === 'inProgress'));
  const workspace = await get('/v1/workspace');
  assert.equal(workspace.connectionMode, 'desktop-runtime');
  const history = await get('/v1/history?hostId=' + workspace.hostId + '&threadId=' + threadId);
  assert.equal(history.runtime.canControl, true);
  const metadata = {
    endpoint,
    token,
    hostId: workspace.hostId,
    threadId,
    threadTitle: history.thread.title,
    nonce,
    secondEndpoint: '',
    secondToken: '',
    secondHostId: '',
    delayedEndpoint: '',
  };
  server = createServer((request, response) => {
    if (
      request.method !== 'GET' ||
      request.url !== '/' ||
      request.headers.origin ||
      !['127.0.0.1:8803', 'localhost:8803'].includes(request.headers.host)
    ) {
      response.writeHead(403);
      response.end();
      return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(metadata));
  });
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(8803, '127.0.0.1', done);
  });
  const resultBundle = join(directory, 'result.xcresult');
  evidence.resultBundle = resultBundle;
  await save();
  child = spawn(
    'xcodebuild',
    [
      '-project',
      project,
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS Simulator,id=${device}`,
      '-derivedDataPath',
      '.build/ios-read-lifecycle-live',
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopReadLifecycle',
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
  Object.assign(evidence, {
    passedTests: summary.passedTests,
    failedTests: summary.failedTests,
    skippedTests: summary.skippedTests,
  });
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  const after = await ipc.follow(threadId);
  assert(ipc.hasOwner(threadId));
  const marker = 'READ_LIFECYCLE_REAL_' + nonce;
  const turns = desktopTurns(after).filter((turn) =>
    (turn.items ?? []).some((item) => item.type === 'agentMessage' && item.text === marker),
  );
  assert.equal(turns.length, 1, 'Original desktop must complete the native app request once');
  assert.equal(turns[0].status, 'completed');
  evidence.afterProcesses = processIds();
  assert.deepEqual(evidence.afterProcesses, evidence.beforeProcesses);
  assert.equal(await fingerprint(), evidence.nativeSourceSha256);
  Object.assign(evidence, {
    status: 'passed',
    originalOwner: true,
    sameDesktopProcesses: true,
    nativeClientSwitchAndBackground: true,
    verifiedAt: new Date().toISOString(),
  });
  await save();
  console.log(
    JSON.stringify({ status: evidence.status, passedTests: 1, resultBundle, originalOwner: true }),
  );
} catch (error) {
  evidence.status = 'failed';
  evidence.error = error.message;
  await save();
  throw error;
} finally {
  child?.kill('SIGTERM');
  if (server) {
    server.closeAllConnections();
    await new Promise((done) => server.close(done));
  }
  ipc.close();
}
