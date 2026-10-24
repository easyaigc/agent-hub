import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { createInterface } from 'node:readline';
import { readFileSync, writeFileSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

// Native paging/refresh interaction only. This fixture never contacts Codex.
const endpoint = 'http://127.0.0.1:8814';
const hostId = 'history-pagination-fixture';
const threadId = 'paging-thread';
const token = randomBytes(32).toString('base64url');
const directory = process.env.IOS_HISTORY_PAGING_DIRECTORY ?? '.local/history-pagination-dev';
const device = process.env.IOS_SIMULATOR_ID;
assert(device, 'Use a dedicated history pagination simulator');
await mkdir(directory, { recursive: true });
let mode = 'remote';
let readEpoch = 0,
  activeHistoryReads = 0,
  maxHistoryReads = 0;
let pageReads = 0;
let latestReads = 0;
let failNext = false;
let seenCursors = [];
let holdLatest = false;
let heldLatest = 0;
let releaseLatest = () => {};
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
    releaseLatest();
    holdLatest = false;
    heldLatest = 0;
    mode = url.searchParams.get('mode') ?? 'remote';
    pageReads = latestReads = 0;
    readEpoch++;
    activeHistoryReads = maxHistoryReads = 0;
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
  if (url.pathname === '/hold-latest') {
    holdLatest = true;
    return json(response, 200, { armed: true });
  }
  if (url.pathname === '/release-latest') {
    holdLatest = false;
    releaseLatest();
    return json(response, 200, { released: true });
  }
  if (url.pathname === '/results')
    return json(response, 200, {
      pageReads,
      latestReads,
      seenCursors,
      heldLatest,
      maxHistoryReads,
    });
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
  const epoch = readEpoch;
  activeHistoryReads++;
  maxHistoryReads = Math.max(maxHistoryReads, activeHistoryReads);
  let finished = false;
  const finish = () => {
    if (!finished && epoch === readEpoch) activeHistoryReads--;
    finished = true;
  };
  response.once('finish', finish);
  response.once('close', finish);
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
    if (holdLatest) {
      heldLatest++;
      await new Promise((done) => {
        releaseLatest = done;
      });
    }
    return json(response, 200, {
      hostId,
      threadId,
      thread: thread(),
      messages:
        mode === 'local'
          ? messages(0, 72)
          : mode === 'local-multiple'
            ? messages(0, 132)
            : mode === 'remote-layout'
              ? messages(20, 40)
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
  'testHistoryPagingLocalDuringLatestRead',
  'testHistoryPagingRemoteDuringLatestRead',
  'testHistoryPagingRemoteSecondLayoutKeepsAnchor',
  'testHistoryPagingQueuedSwitchDiscardsRequest',
  'testHistoryPagingPullInMiddleDoesNotExpand',
  'testHistoryPagingPullFailureRetryAndExhaustion',
  'testHistoryPagingThreadSwitchDiscardsLatePage',
];
const resultBundle = directory + '/fixture-' + Date.now() + '.xcresult';
const fingerprint = async () => {
  const hash = createHash('sha256');
  for (const file of [
    'apps/ios/Sources/RootView.swift',
    'apps/ios/Sources/SessionStore.swift',
    'apps/ios/Sources/Models.swift',
    'apps/ios/Sources/SharedMobileRules.swift',
    'apps/ios/Sources/SharedReadingPolicy.swift',
    'apps/ios/Tests/CodexManageUITests.swift',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/MessageProjection.kt',
  ])
    hash
      .update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return hash.digest('hex');
};
const nativeSourceSha256 = await fingerprint();
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
  { stdio: ['ignore', 'pipe', 'inherit'], env: process.env },
);
const diagnosticCopies = [];
createInterface({ input: child.stdout }).on('line', (line) => {
  console.log(line);
  const completed = line.match(/^Test Case .* (testHistoryPaging\w+)\]' (passed|failed)/);
  if (!completed) return;
  try {
    const container = execFileSync(
      'xcrun',
      ['simctl', 'get_app_container', device, 'com.codexmanage.ios', 'data'],
      { encoding: 'utf8' },
    ).trim();
    const bytes = readFileSync(
      container + '/Library/Application Support/history-position-metrics.json',
    );
    JSON.parse(bytes.toString());
    writeFileSync(directory + '/' + completed[1] + '-position.json', bytes, { mode: 0o600 });
    diagnosticCopies.push({ test: completed[1], result: completed[2], captured: true });
  } catch {
    diagnosticCopies.push({ test: completed[1], result: completed[2], captured: false });
  }
});
try {
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  const sourcesUnchanged = (await fingerprint()) === nativeSourceSha256;
  let summary;
  try {
    summary = JSON.parse(
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
  } catch {
    /* An incomplete result is a failure, never a zero-test success. */
  }
  const passed =
    code === 0 &&
    sourcesUnchanged &&
    summary?.passedTests === tests.length &&
    summary.failedTests === 0 &&
    summary.skippedTests === 0;
  await writeFile(
    directory + '/fixture-result.json',
    JSON.stringify(
      {
        status: passed ? 'passed' : 'failed',
        nativeSourceSha256,
        harnessSha256: createHash('sha256')
          .update(await readFile('scripts/history-pagination-ios-fixture.mjs'))
          .digest('hex'),
        diagnosticCopies,
        sourcesUnchanged,
        passedTests: summary?.passedTests ?? null,
        failedTests: summary?.failedTests ?? null,
        skippedTests: summary?.skippedTests ?? null,
        resultBundle,
        tests,
        realCodexRuntime: false,
      },
      null,
      2,
    ),
  );
  assert.equal(code, 0, 'Native history pagination interaction must pass');
  assert.equal(summary?.passedTests, tests.length, 'Every selected paging regression must run');
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  assert.equal(
    await fingerprint(),
    nativeSourceSha256,
    'Paging source or assertions changed during acceptance',
  );
} finally {
  child.kill('SIGTERM');
  releaseLatest();
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
