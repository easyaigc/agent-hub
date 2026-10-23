import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { execFile, spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { promisify } from 'node:util';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';

// Local HTTP fixture: exercises the native refresh gesture without a Codex runtime.
const hostId = 'sidebar-refresh-host';
const token = randomBytes(32).toString('base64url');
const endpoint = 'http://127.0.0.1:8797';
const exec = promisify(execFile);
const devices = JSON.parse(
  (await exec('xcrun', ['simctl', 'list', 'devices', 'available', '-j'])).stdout,
);
const device =
  process.env.IOS_SIMULATOR_ID ??
  Object.values(devices.devices)
    .flat()
    .find((entry) => entry.name === 'iPhone 17 Pro')?.udid;
assert.ok(device, 'An iPhone 17 Pro simulator or IOS_SIMULATOR_ID is required');
await mkdir('.local', { recursive: true });
const captureDirectory =
  (process.env.UNREAD_ACCEPTANCE_OUTPUT ?? `.local/sidebar-refresh-${Date.now()}`) +
  '/refresh-captures';
await mkdir(captureDirectory, { recursive: true });
const captures = [];
const captureJobs = new Set();
const requests = [];
let armed,
  unreadState,
  pinnedIds,
  runtime = false,
  revision = 0,
  workspaceCount = 0,
  historyCount = 0,
  refreshCount = 0,
  historyRuntimePublished = false;
const counters = () => ({ workspaceCount, historyCount, refreshCount });
const anchor = {
  id: 'refresh-anchor',
  hostId,
  projectId: 'refresh-project',
  state: 'idle',
  title: 'Fixture reading anchor',
  hasUnreadTurn: false,
};
const thread = () => ({
  id: 'refresh-thread',
  hostId,
  projectId: 'refresh-project',
  state:
    unreadState === 'async-unknown'
      ? 'unknown'
      : ['running', 'async-running'].includes(unreadState)
        ? 'running'
        : ['approval', 'approval-and-async'].includes(unreadState)
          ? 'waiting-for-approval'
          : runtime
            ? historyRuntimePublished
              ? 'running'
              : 'idle'
            : 'unknown',
  ...(unreadState && unreadState !== 'legacy' ? { hasUnreadTurn: unreadState !== 'read' } : {}),
  ...(unreadState && unreadState !== 'legacy'
    ? {
        pendingAsyncQuestionCount:
          unreadState === 'async-unknown'
            ? 0
            : unreadState === 'approval-and-async'
              ? 1
              : unreadState.startsWith('async-')
                ? 2
                : 0,
      }
    : {}),
  title: revision ? 'Fixture refreshed conversation' : 'Fixture original conversation',
});
const json = (response, status, value) => {
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-store',
  });
  response.end(JSON.stringify(value));
};
const fixture = createServer(async (request, response) => {
  const url = new URL(request.url, endpoint);
  // Keep only synthetic route/state diagnostics, never headers or credentials.
  const trace = {
    path: url.pathname,
    startedAt: Date.now(),
    runtime,
    unreadState,
    armed,
  };
  requests.push(trace);
  response.once('finish', () =>
    Object.assign(trace, {
      finishedAt: Date.now(),
      status: response.statusCode,
      runtime,
      unreadState,
      armed,
    }),
  );
  if (
    ['/reset', '/runtime-reset', '/unread-reset', '/async-status-reset', '/pinned-reset'].includes(
      url.pathname,
    )
  ) {
    pinnedIds = url.pathname === '/pinned-reset' ? ['pinned-older', 'refresh-thread'] : undefined;
    runtime = url.pathname !== '/reset';
    unreadState =
      url.pathname === '/unread-reset'
        ? 'unread'
        : url.pathname === '/async-status-reset'
          ? 'read'
          : undefined;
    armed = undefined;
    historyRuntimePublished = false;
    revision = workspaceCount = historyCount = refreshCount = 0;
    return json(response, 200, {
      endpoint,
      token,
      hostId,
      threadId: 'refresh-thread',
      nonce: 'sidebar-refresh',
      secondEndpoint: endpoint,
      secondToken: token,
      secondHostId: hostId,
      delayedEndpoint: endpoint,
    });
  }
  if (url.pathname === '/arm') {
    armed = url.searchParams.get('mode');
    return json(response, 200, counters());
  }
  if (url.pathname === '/results') return json(response, 200, counters());
  if (url.pathname === '/pinned-state') {
    assert.equal(request.headers.authorization, 'Bearer ' + token);
    pinnedIds = url.searchParams.get('empty') === '1' ? [] : ['refresh-thread', 'pinned-older'];
    return json(response, 200, counters());
  }
  if (url.pathname === '/unread-state') {
    assert.equal(request.headers.authorization, 'Bearer ' + token);
    const value = url.searchParams.get('value');
    assert(
      [
        'read',
        'unread',
        'running',
        'approval',
        'legacy',
        'async-running',
        'async-idle',
        'approval-and-async',
        'async-unknown',
      ].includes(value),
    );
    unreadState = value;
    return json(response, 200, counters());
  }
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, 401, { error: 'Fixture auth required' });
  if (url.pathname === '/v1/workspace') {
    workspaceCount++;
    if (armed) {
      const mode = armed;
      // Background unread/sidebar reads can race the user's pull gesture.
      // An unavailable service remains unavailable for this scenario; only a
      // success update is consumed once. Do not let a background read heal it.
      if (mode !== 'error') armed = undefined;
      refreshCount++;
      const capture = {
        id: captures.length + 1,
        mode,
        requestStartedAt: new Date().toISOString(),
        file: '',
      };
      capture.file = `${captureDirectory}/${mode}-${capture.id}-loading.png`;
      captures.push(capture);
      assert.equal(url.searchParams.has('projectCursor'), false);
      assert.equal(url.searchParams.has('threadCursor'), false);
      const captureJob = Promise.all([
        new Promise((done) => setTimeout(done, 5000)),
        (async () => {
          await new Promise((done) => setTimeout(done, 1000));
          await exec('xcrun', ['simctl', 'io', device, 'screenshot', capture.file]);
        })(),
      ]);
      captureJobs.add(captureJob);
      try {
        await captureJob;
      } finally {
        captureJobs.delete(captureJob);
      }
      if (mode === 'error') return json(response, 503, { error: 'Fixture refresh unavailable' });
      revision++;
    }
    return json(response, 200, {
      hostId,
      name: 'Refresh fixture',
      connectionMode: runtime ? 'shared-runtime' : 'history-only',
      capabilities: ['projects:read', 'threads:read', 'history:read'],
      projects: [{ id: 'refresh-project', hostId, name: 'Fixture project', roots: [] }],
      threads: [
        ...(unreadState ? [anchor] : []),
        thread(),
        ...(pinnedIds
          ? [
              {
                ...thread(),
                id: 'pinned-older',
                title: 'Older pinned conversation',
              },
              {
                ...thread(),
                id: 'unpinned-recent',
                title: 'Unpinned recent conversation',
              },
            ]
          : []),
        ...(revision
          ? [
              {
                ...thread(),
                id: 'refresh-added',
                title: 'Fixture new conversation',
              },
            ]
          : []),
      ],
      nextProjectCursor: null,
      nextThreadCursor: null,
      ...(pinnedIds ? { pinnedThreadIds: [...pinnedIds, 'missing-thread'] } : {}),
    });
  }
  if (url.pathname === '/v1/history') {
    assert.equal(url.searchParams.get('hostId'), hostId);
    const isAnchor = url.searchParams.get('threadId') === anchor.id;
    assert(isAnchor || url.searchParams.get('threadId') === 'refresh-thread');
    historyCount++;
    const snapshot = {
      hostId,
      threadId: isAnchor ? anchor.id : 'refresh-thread',
      thread: isAnchor ? anchor : thread(),
      nextCursor: null,
      messages: [
        {
          id: 'refresh-message',
          role: 'assistant',
          text: isAnchor ? 'REFRESH_FIXTURE_ANCHOR' : 'REFRESH_FIXTURE_HISTORY',
          files: [],
        },
      ],
      ...(runtime
        ? {
            runtime: {
              state: isAnchor ? 'idle' : unreadState ? thread().state : 'running',
              turnId: unreadState ? null : 'history-running-turn',
              plan: [],
              approvals: [],
              receipts: [],
              canControl: false,
            },
          }
        : {}),
    };
    // First history deliberately carries an idle summary plus running runtime.
    // Later workspace reads reflect that published runtime, rather than
    // contradicting it and turning this into a refresh-timing lottery.
    if (runtime && !unreadState && !isAnchor) historyRuntimePublished = true;
    return json(response, 200, snapshot);
  }
  if (runtime && url.pathname === '/v1/events') {
    await new Promise((done) => setTimeout(done, 15000));
    if (response.destroyed) return;
    return json(response, 200, {
      hostId,
      epoch: 'history-only-update',
      cursor: 0,
      reset: false,
      ready: true,
      events: [],
    });
  }
  return json(response, 404, { error: 'Unsupported fixture route' });
});
await new Promise((done) => fixture.listen(8797, '127.0.0.1', done));
const availableTests = [
  'testSidebarPinnedConversations',
  'testSidebarPullRefreshUpdatesListAndRetainsDraft',
  'testSidebarPullRefreshFailurePreservesCachedList',
  'testSidebarHistoryUpdatesStateBeforeEvents',
  'testSidebarStatePriorityPreservesPhoneUnread',
  'testSidebarAsyncQuestionStatus',
  'testSidebarSearchAndProjectSelection',
  'testSidebarRecentAndProjectExpansion',
  'testCloseDemoReturnsToOnboardingAndPersists',
  'testCloseDemoPreservesConnectedClientAndDraft',
];
const tests = process.env.SIDEBAR_REFRESH_TESTS?.split(',') ?? availableTests;
assert(tests.length && tests.every((test) => availableTests.includes(test)));
// Each case owns a fresh installation. Relaunch/persistence assertions inside a
// case retain that same installation; unrelated cases never share system state.
const resultBundle =
  process.env.IOS_RESULT_BUNDLE_PATH ?? `.local/sidebar-refresh-${Date.now()}.xcresult`;
const caseDirectory = resultBundle.replace(/\.xcresult$/, '-cases');
await mkdir(caseDirectory, { recursive: true });
const results = [];
let child;
try {
  for (const test of tests) {
    // Finish request-bound screenshots before changing the Simulator's state.
    await Promise.allSettled([...captureJobs]);
    await exec('xcrun', ['simctl', 'shutdown', device]);
    await exec('xcrun', ['simctl', 'boot', device]);
    await exec('xcrun', ['simctl', 'bootstatus', device, '-b']);
    armed = unreadState = undefined;
    runtime = false;
    historyRuntimePublished = false;
    revision = workspaceCount = historyCount = refreshCount = 0;
    await exec('xcrun', ['simctl', 'uninstall', device, 'com.codexmanage.ios']);
    const path = tests.length === 1 ? resultBundle : caseDirectory + '/' + test + '.xcresult';
    const log = createWriteStream(caseDirectory + '/' + test + '.log');
    child = spawn(
      'xcodebuild',
      [
        '-project',
        process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj',
        '-scheme',
        'CodexManage',
        '-destination',
        `platform=iOS Simulator,id=${device}`,
        '-derivedDataPath',
        process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios',
        '-resultBundlePath',
        path,
        '-parallel-testing-enabled',
        'NO',
        '-collect-test-diagnostics',
        'never',
        'CODE_SIGNING_ALLOWED=YES',
        'CODE_SIGN_IDENTITY=-',
        '-only-testing:CodexManageUITests/CodexManageUITests/' + test,
        'test',
      ],
      { stdio: ['ignore', 'pipe', 'pipe'], env: process.env },
    );
    for (const stream of [child.stdout, child.stderr])
      createInterface({ input: stream }).on('line', (line) => {
        log.write(line + '\n');
        // The parent gate must inspect the merged result, not the first case.
        // Preserve the exact original output in the per-case log.
        console.log(line.replace('Writing result bundle at path:', 'Isolated result bundle path:'));
      });
    const code = await new Promise((resolve, reject) => {
      child.once('close', resolve);
      child.once('error', reject);
    });
    await new Promise((resolve) => log.end(resolve));
    const summary = JSON.parse(
      (
        await exec('xcrun', [
          'xcresulttool',
          'get',
          'test-results',
          'summary',
          '--path',
          path,
          '--format',
          'json',
        ])
      ).stdout,
    );
    results.push({ test, resultBundle: path, exitCode: code, summary });
    await writeFile(caseDirectory + '/index.json', JSON.stringify(results, null, 2) + '\n');
    assert.equal(code, 0);
    assert.equal(summary.totalTestCount, 1);
    assert.equal(summary.passedTests, 1);
    assert.equal(summary.failedTests, 0);
    assert.equal(summary.skippedTests, 0);
    assert.equal(summary.expectedFailures, 0);
    console.log('Passed isolated sidebar case:', test);
  }
  if (results.length > 1)
    await exec('xcrun', [
      'xcresulttool',
      'merge',
      '--output-path',
      resultBundle,
      ...results.map((result) => result.resultBundle),
    ]);
  const summary = JSON.parse(
    (
      await exec('xcrun', [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        resultBundle,
        '--format',
        'json',
      ])
    ).stdout,
  );
  assert.equal(summary.totalTestCount, tests.length);
  assert.equal(summary.passedTests, tests.length);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  assert.equal(summary.expectedFailures, 0);
  console.log('Writing result bundle at path: ' + resultBundle);
  console.log(
    JSON.stringify({
      suite: 'sidebar-refresh',
      passed: summary.passedTests,
      failed: summary.failedTests,
      skipped: summary.skippedTests,
      resultBundle,
      isolation: 'fresh-simulator-boot-and-install-per-case',
      realRuntimeVerified: false,
    }),
  );
} finally {
  await writeFile(captureDirectory + '/index.json', JSON.stringify(captures, null, 2) + '\n');
  await writeFile(captureDirectory + '/requests.json', JSON.stringify(requests, null, 2) + '\n');
  child?.kill('SIGTERM');
  fixture.closeAllConnections();
  await new Promise((done) => fixture.close(done));
}
