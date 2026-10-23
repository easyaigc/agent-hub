import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopIpc, desktopTurns } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';

// Read-only regression against a real historical receipt and its original owner.
// The isolated local Host copies only this machine's named target; no input is submitted.
const threadId = process.env.DESKTOP_ACCEPTANCE_THREAD_ID;
const messageId = process.env.DESKTOP_FAILED_MESSAGE_ID;
const device = process.env.IOS_SIMULATOR_ID;
assert(
  threadId && messageId && device,
  'Select the exact failed receipt and a dedicated simulator',
);
const original = JSON.parse(
  await readFile(
    join(process.env.DESKTOP_SOURCE_STATE_DIR ?? '.local/desktop-runtime', 'runtime.json'),
    'utf8',
  ),
);
const receipt = original.receipts.find(
  (value) => value.threadId === threadId && value.clientMessageId === messageId,
);
assert(receipt?.state === 'failed' && receipt.turnId, 'Use an actual confirmed task failure');
const directory = resolve(
  process.env.RECEIPT_ACCEPTANCE_OUTPUT ?? '.local/receipt-root-live-' + Date.now(),
);
await mkdir(directory, { recursive: true, mode: 0o700 });
const stateDirectory = join(directory, 'host-state');
await mkdir(stateDirectory, { mode: 0o700 });
await writeFile(
  join(stateDirectory, 'runtime.json'),
  JSON.stringify({
    hostId: original.hostId,
    projects: original.projects,
    threads: original.threads.filter((value) => value.id === threadId),
    receipts: original.receipts.filter((value) => value.threadId === threadId),
    attachments: original.attachments.filter((value) => value.threadId === threadId),
    creations: {},
  }),
  { mode: 0o600 },
);
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  CODEX_EXECUTABLE:
    '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
  HOST_AGENT_STATE_DIR: stateDirectory,
  HOST_AGENT_NAME: 'Historical Receipt DEV',
  HOST_AGENT_ROOT: process.cwd(),
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
});
for (const key of [
  'CODEX_DESKTOP_CONTROLLER_CONFIG',
  'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
  'HOST_NOTIFICATION_RELAY_URL',
  'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
])
  delete process.env[key];
const project = process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj';
const sourceDigest = createHash('sha256');
for (const file of ['Models.swift', 'SessionStore.swift', 'RootView.swift'])
  sourceDigest.update(await readFile(join(dirname(project), 'Sources', file)));
const report = {
  kind: 'real-desktop-historical-failure-native-read-only',
  threadId,
  messageId,
  adapterSha256: preparedAdapterDigest(),
  nativeSourceSha256: sourceDigest.digest('hex'),
  startedAt: new Date().toISOString(),
  modelInputSubmitted: false,
};
const save = () =>
  writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
const ipc = new DesktopIpc(join(homedir(), '.codex/ipc/ipc.sock'));
let service, metadataServer, child;
const desktopProcesses = () => {
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
        main.some((parent) => parent[1] === row[2]) && /\/codex-cli\/.*app-server/.test(row[3]),
    ),
  ].map((row) => row.slice(1, 3));
};
try {
  await ipc.connect();
  const before = await ipc.follow(threadId, 15000, true, true);
  assert(ipc.hasOwner(threadId) && before.id === threadId);
  const turns = desktopTurns(before);
  const failedIndex = turns.findIndex((turn) => turn.turnId === receipt.turnId);
  assert(failedIndex >= 0 && failedIndex < turns.length - 1);
  assert.equal(turns[failedIndex].status, 'failed');
  report.originalError = turns[failedIndex].error?.message;
  report.ownerVerifiedBefore = true;
  report.beforeLatest = { turnId: turns.at(-1).turnId, status: turns.at(-1).status };
  report.originalDesktopProcesses = desktopProcesses();
  service = await startRuntimeServer();
  const token = (await readFile(join(stateDirectory, 'access-token'), 'utf8')).trim();
  const endpoint = 'http://127.0.0.1:' + service.server.address().port;
  let nativeMutationRequests = 0;
  service.server.prependListener('request', (request) => {
    if (request.method !== 'GET') nativeMutationRequests++;
  });
  const history = await service.session.open(threadId);
  const updated = history.runtime.receipts.find((value) => value.clientMessageId === messageId);
  assert.equal(updated.state, 'failed');
  assert.equal(updated.error, report.originalError);
  assert(updated.supersededByTurnId);
  assert(
    history.messages.some(
      (value) =>
        value.id === 'turn-failure-' + receipt.turnId && value.text === report.originalError,
    ),
  );
  assert.deepEqual(service.session.receipt(messageId), updated);
  report.receipt = {
    state: updated.state,
    turnId: updated.turnId,
    error: updated.error,
    supersededByTurnId: updated.supersededByTurnId,
  };
  const metadata = {
    endpoint,
    token,
    hostId: service.session.hostId,
    threadId,
    threadTitle: history.thread.title,
    nonce: messageId,
    failureTurnId: receipt.turnId,
    secondEndpoint: '',
    secondHostId: '',
    secondToken: '',
    delayedEndpoint: '',
  };
  metadataServer = createServer((request, response) => {
    if (request.method !== 'GET' || request.url !== '/' || request.headers.origin) {
      response.writeHead(403);
      response.end();
      return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(metadata));
  });
  await new Promise((done, reject) => {
    metadataServer.once('error', reject);
    metadataServer.listen(8804, '127.0.0.1', done);
  });
  report.resultBundle = join(directory, 'result.xcresult');
  await save();
  child = spawn(
    'xcodebuild',
    [
      '-project',
      project,
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      join(directory, 'build'),
      '-resultBundlePath',
      report.resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopHistoricalFailureNotice',
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
      if (/^Test Case|: error:|^\*\* TEST/.test(safe)) console.log(safe);
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
        report.resultBundle,
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
  report.nativeMutationRequests = nativeMutationRequests;
  assert.equal(code, 0);
  assert.equal(summary.passedTests, 1);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  assert.equal(
    nativeMutationRequests,
    0,
    'Reading history and editing local drafts cannot submit or change remote tasks',
  );
  const after = await ipc.follow(threadId, 15000, true, true);
  report.ownerVerifiedAfter = ipc.hasOwner(threadId);
  report.afterLatest = {
    turnId: desktopTurns(after).at(-1).turnId,
    status: desktopTurns(after).at(-1).status,
  };
  assert(report.ownerVerifiedAfter);
  assert.deepEqual(
    desktopProcesses(),
    report.originalDesktopProcesses,
    'Original desktop execution processes must stay unchanged',
  );
  report.passed = true;
  console.log(
    'Real original-desktop failure, later tasks, native history/draft restart and zero remote writes passed.',
  );
} catch (error) {
  report.error = error.message;
  process.exitCode = 1;
  console.error(error.message);
} finally {
  child?.kill('SIGTERM');
  metadataServer?.closeAllConnections();
  if (metadataServer) await new Promise((done) => metadataServer.close(done));
  await service?.close();
  ipc.close();
  report.finishedAt = new Date().toISOString();
  await save();
}
