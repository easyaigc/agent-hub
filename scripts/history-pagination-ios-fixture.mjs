import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

// Native paging/refresh interaction only. This fixture never contacts Codex.
const endpoint = 'http://127.0.0.1:8814';
const hostId = 'history-pagination-fixture';
const threadId = 'paging-thread';
const token = randomBytes(32).toString('base64url');
const directory = '.local/history-pagination-dev';
const device = process.env.IOS_SIMULATOR_ID;
assert(device, 'Use a dedicated history pagination simulator');
await mkdir(directory, { recursive: true });
let mode = 'remote';
let pageReads = 0;
let latestReads = 0;
let failNext = false;
let seenCursors = [];
const thread = (id = threadId) => ({
  id,
  hostId,
  projectId: 'paging-project',
  title: id,
  state: 'idle',
});
const messages = (start, end) =>
  Array.from({ length: end - start }, (_, offset) => ({
    id: 'paging-message-' + (start + offset),
    role: 'user',
    text: 'HISTORY_PAGING_' + String(start + offset).padStart(3, '0'),
    files: [],
  }));
const json = (response, status, value) => {
  if (response.destroyed) return;
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const server = createServer(async (request, response) => {
  const url = new URL(request.url, endpoint);
  if (url.pathname === '/reset') {
    mode = url.searchParams.get('mode') ?? 'remote';
    pageReads = latestReads = 0;
    seenCursors = [];
    failNext = mode === 'remote';
    return json(response, 200, {
      endpoint,
      hostId,
      threadId,
      token,
      nonce: 'history-paging',
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    });
  }
  if (url.pathname === '/results')
    return json(response, 200, { pageReads, latestReads, seenCursors });
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, 401, { error: 'Unauthorized' });
  if (url.pathname === '/v1/workspace')
    return json(response, 200, {
      hostId,
      name: 'History fixture',
      connectionMode: 'history-only',
      capabilities: ['projects:read', 'threads:read', 'history:read'],
      projects: [{ id: 'paging-project', hostId, name: 'Paging project', roots: [] }],
      threads: [thread(), thread('other-thread')],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  assert.equal(url.searchParams.get('hostId'), hostId);
  if (url.pathname !== '/v1/history') return json(response, 404, { error: 'Unsupported route' });
  const id = url.searchParams.get('threadId');
  if (id === 'other-thread')
    return json(response, 200, {
      hostId,
      threadId: id,
      thread: thread(id),
      messages: [{ id: 'other-message', role: 'user', text: 'OTHER_THREAD_HISTORY', files: [] }],
      nextCursor: null,
    });
  assert.equal(id, threadId);
  const cursor = url.searchParams.get('cursor');
  if (!cursor) {
    latestReads++;
    console.log('HISTORY_LATEST_STARTED', mode, latestReads);
    return json(response, 200, {
      hostId,
      threadId,
      thread: thread(),
      messages:
        mode === 'local'
          ? messages(0, 72)
          : mode === 'local-multiple'
            ? messages(0, 132)
            : messages(20, 24),
      nextCursor: mode.startsWith('local') ? null : 'earlier-page',
    });
  }
  assert.equal(cursor, 'earlier-page');
  pageReads++;
  seenCursors.push(cursor);
  console.log('HISTORY_PAGE_STARTED', mode, pageReads);
  response.once('close', () => console.log('HISTORY_PAGE_CLOSED', mode, response.writableFinished));
  // Capture both the native pull indicator and compact row while I/O is pending.
  const shotTimer = setTimeout(() => {
    try {
      execFileSync('xcrun', [
        'simctl',
        'io',
        device,
        'screenshot',
        directory + '/loading-' + mode + '.png',
      ]);
    } catch {
      /* UI assertions still verify the completed interaction. */
    }
  }, 700);
  await new Promise((done) => setTimeout(done, mode === 'switch' ? 6000 : 2500));
  clearTimeout(shotTimer);
  if (failNext) {
    failNext = false;
    console.log('HISTORY_PAGE_FAILED', response.destroyed);
    return json(response, 503, { error: 'Earlier history temporarily unavailable' });
  }
  // Deliberate overlap exercises deduplication at the native store boundary.
  return json(response, 200, {
    hostId,
    threadId,
    thread: thread(),
    messages: messages(0, 21),
    nextCursor: null,
  });
});
await new Promise((done, reject) => {
  server.once('error', reject);
  server.listen(8814, '127.0.0.1', done);
});
if (process.env.IOS_FIXTURE_ONLY === '1') {
  console.log('HISTORY_PAGING_FIXTURE_READY');
  await new Promise((done) => {
    process.once('SIGTERM', done);
    process.once('SIGINT', done);
  });
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
  process.exit(0);
}
const tests = process.env.IOS_TESTS?.split(',') ?? [
  'testHistoryPagingLocalWindow',
  'testHistoryPagingLocalPullWindow',
  'testHistoryPagingPullInMiddleDoesNotExpand',
  'testHistoryPagingPullFailureRetryAndExhaustion',
  'testHistoryPagingThreadSwitchDiscardsLatePage',
];
const resultBundle = directory + '/fixture-' + Date.now() + '.xcresult';
const hash = createHash('sha256');
for (const file of ['RootView.swift', 'SessionStore.swift', 'Models.swift'])
  hash.update(await readFile('apps/ios/Sources/' + file));
const child = spawn(
  'xcodebuild',
  [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    'platform=iOS Simulator,id=' + device,
    '-derivedDataPath',
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios-history-pagination',
    '-resultBundlePath',
    resultBundle,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'ONLY_ACTIVE_ARCH=YES',
    ...tests.map((test) => '-only-testing:CodexManageUITests/CodexManageUITests/' + test),
    'test',
  ],
  { stdio: 'inherit', env: process.env },
);
try {
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  await writeFile(
    directory + '/fixture-result.json',
    JSON.stringify(
      {
        status: code === 0 ? 'passed' : 'failed',
        nativeSourceSha256: hash.digest('hex'),
        resultBundle,
        tests,
        realCodexRuntime: false,
      },
      null,
      2,
    ),
  );
  assert.equal(code, 0, 'Native history pagination interaction must pass');
} finally {
  child.kill('SIGTERM');
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
