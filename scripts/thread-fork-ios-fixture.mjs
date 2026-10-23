import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';

// Isolated HTTP/UI fixture. It never operates a desktop, Git checkout, or model.
// --serve-only starts the fixture without taking a simulator from another run.
const hostId = 'fork-fixture-host';
const secondHostId = 'fork-fixture-other-host';
const token = randomBytes(32).toString('base64url');
const nonce = randomBytes(4).toString('hex');
const metadataPort = Number(process.env.THREAD_FORK_FIXTURE_PORT ?? 8817);
const modes = new Set([
  'same-directory',
  'worktree',
  'pending',
  'paginated',
  'non-git',
  'legacy',
  'history',
  'provider',
  'missing-capability',
  'rejected',
  'mismatched-receipt',
  'missing-receipt',
]);
let mode = 'same-directory';
let confirmed = false;
let heldOperationId = null;
let heldQuery = false;
let heldResolvers = [];
const confirmedIds = new Set();
let posts = [],
  queries = [],
  historyReads = [],
  optionReads = [],
  forbiddenInputs = [],
  violations = [];
const operations = new Map();
const runs = [];
const caps = () => [
  'projects:read',
  'threads:read',
  'history:read',
  'reply',
  ...(mode === 'missing-capability' ? [] : ['threads:fork', 'threads:fork:worktree']),
];
const target = (id) => ({
  agentId: mode === 'provider' ? 'easywork' : 'codex',
  hostId: id,
  workspaceId: 'fixture-workspace',
  runtimeId: 'fixture-desktop',
  account: { authority: 'fixture', subject: id },
});
const thread = (id, name = 'fork-source', projectId = 'fork-project') => ({
  id: name,
  hostId: id,
  projectId,
  title:
    name === 'fork-source'
      ? 'Fork source fixture'
      : name === 'fork-other'
        ? 'Other source fixture'
        : 'Confirmed fork fixture',
  state: 'idle',
});
const forkId = (operation) => 'fork-result-' + operation.clientOperationId;
const receipt = (operation) => ({
  ...operation,
  phase:
    mode === 'rejected'
      ? 'rejected'
      : confirmed || confirmedIds.has(operation.clientOperationId)
        ? 'confirmed'
        : 'outcome-unknown',
  forkedThreadId:
    confirmed || confirmedIds.has(operation.clientOperationId) ? forkId(operation) : null,
  projectId:
    confirmed || confirmedIds.has(operation.clientOperationId)
      ? operation.environment === 'worktree'
        ? 'fork-worktree-project'
        : 'fork-project'
      : null,
  error: mode === 'rejected' ? 'Fixture desktop rejected fork' : null,
});
const counters = () => ({
  mode,
  posts,
  queries,
  historyReads,
  optionReads,
  forbiddenInputs,
  violations,
  confirmed,
  heldQuery,
  records: [...operations.values()].map(receipt),
});
const json = (response, code, value) => {
  response.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const history = (id, name, cursor) => {
  const fork = [...operations.values()].find((op) => forkId(op) === name);
  const messages =
    name === 'fork-other'
      ? [
          {
            id: 'other-message',
            role: 'assistant',
            text: 'OTHER_TARGET',
            runId: 'actual-other-turn',
            status: 'completed',
          },
        ]
      : [
          {
            id: 'first-user',
            role: 'user',
            text: 'First source input',
            runId: 'actual-first-turn',
          },
          {
            id: 'reply-visible-earlier',
            role: 'assistant',
            text: 'FIRST_COMPLETED_REPLY',
            runId: 'actual-first-turn',
            replyPhase: 'final_answer',
            status: 'completed',
          },
          ...(fork
            ? []
            : [
                {
                  id: 'second-user',
                  role: 'user',
                  text: 'Second source input',
                  runId: 'actual-second-turn',
                },
                {
                  id: 'reply-visible-later',
                  role: 'assistant',
                  text: 'SECOND_COMPLETED_REPLY',
                  runId: 'actual-second-turn',
                  replyPhase: 'final_answer',
                  status: 'completed',
                },
              ]),
        ];
  if (fork)
    messages.push({
      id: 'fixture-fork-opened',
      role: 'assistant',
      text: 'FORK_RESULT_' + fork.environment,
      runId: 'actual-first-turn',
      replyPhase: 'commentary',
      status: 'completed',
    });
  if (fork)
    messages.push({
      id: 'fixture-fork-operation',
      role: 'assistant',
      text: 'FORK_OPERATION_' + fork.clientOperationId,
      runId: 'actual-first-turn',
      replyPhase: 'commentary',
      status: 'completed',
    });
  if (mode === 'legacy') for (const message of messages) delete message.runId;
  for (const message of messages) message.files = [];
  return {
    hostId: id,
    readTarget: target(id),
    threadId: name,
    thread: thread(
      id,
      name,
      fork?.environment === 'worktree' ? 'fork-worktree-project' : 'fork-project',
    ),
    messages:
      mode === 'paginated' && !fork && name === 'fork-source'
        ? cursor
          ? messages.slice(0, 2)
          : messages.slice(2)
        : messages,
    nextCursor:
      mode === 'paginated' && !fork && name === 'fork-source' && !cursor ? 'first-page' : null,
    runtime:
      mode === 'paginated' && cursor
        ? null
        : {
            state: 'idle',
            turnId: null,
            canControl: mode !== 'history',
            plan: [],
            approvals: [],
            receipts: [],
            error: null,
          },
    epoch: 'fork-fixture-epoch',
    cursor: 0,
  };
};
const createHost = (id) =>
  createServer(async (request, response) => {
    try {
      if (request.headers.authorization !== 'Bearer ' + token)
        return json(response, 401, { error: 'Fixture authentication required' });
      const url = new URL(request.url, 'http://localhost');
      if (url.pathname === '/v1/workspace')
        return json(response, 200, {
          hostId: id,
          name: 'Fork fixture computer',
          readTarget: target(id),
          connectionMode: mode === 'history' ? 'history-only' : 'desktop-runtime',
          clientType: { id: target(id).agentId, name: 'Fixture agent' },
          capabilities: caps(),
          projects: [
            { id: 'fork-project', hostId: id, name: 'Fork fixture project', roots: [] },
            ...(confirmed
              ? [
                  {
                    id: 'fork-worktree-project',
                    hostId: id,
                    name: 'Fork worktree fixture',
                    roots: [],
                  },
                ]
              : []),
          ],
          threads: [
            thread(id),
            thread(id, 'fork-other'),
            ...[...operations.values()]
              .filter(
                (op) => (confirmed || confirmedIds.has(op.clientOperationId)) && op.hostId === id,
              )
              .map((op) => thread(id, forkId(op), receipt(op).projectId)),
          ],
          nextProjectCursor: null,
          nextThreadCursor: null,
        });
      if (request.method === 'GET') assert.equal(url.searchParams.get('hostId'), id);
      if (url.pathname === '/v1/history') {
        historyReads.push({
          hostId: id,
          threadId: url.searchParams.get('threadId'),
          cursor: url.searchParams.get('cursor'),
        });
        return json(
          response,
          200,
          history(id, url.searchParams.get('threadId'), url.searchParams.get('cursor')),
        );
      }
      if (url.pathname === '/v1/events') {
        await new Promise((resolve) => setTimeout(resolve, 400));
        return json(response, 200, {
          hostId: id,
          epoch: 'fork-fixture-epoch',
          cursor: 0,
          reset: false,
          ready: true,
          capabilities: caps(),
          events: [],
        });
      }
      if (url.pathname === '/v1/threads/fork-options') {
        const source = url.searchParams.get('threadId');
        assert(
          ['fork-source', 'fork-other'].includes(source) ||
            [...operations.values()].some((operation) => forkId(operation) === source),
        );
        optionReads.push({ hostId: id, threadId: source });
        return json(response, 200, {
          hostId: id,
          threadId: source,
          sameDirectoryAvailable: true,
          worktreeAvailable: mode !== 'non-git',
          reason: null,
          worktreeReason: mode === 'non-git' ? 'Fixture source is outside a Git repository' : null,
        });
      }
      if (url.pathname === '/v1/threads/fork' && request.method === 'GET') {
        const operationId = url.searchParams.get('id');
        const operation = operations.get(operationId);
        queries.push({ hostId: id, threadId: url.searchParams.get('threadId'), id: operationId });
        assert(operation, 'Queries must retain the submitted operation ID');
        assert.equal(operation.hostId, id);
        assert.equal(url.searchParams.get('threadId'), operation.threadId);
        if (operationId === heldOperationId) {
          heldQuery = true;
          await new Promise((resolve) => {
            heldResolvers.push(resolve);
          });
        }
        if (mode === 'missing-receipt')
          return json(response, 404, { error: 'Fixture original receipt is unavailable' });
        return json(response, 200, {
          ...receipt(operation),
          ...(mode === 'mismatched-receipt' ? { turnId: 'wrong-turn' } : {}),
        });
      }
      if (request.method === 'POST') {
        const bytes = [];
        for await (const chunk of request) bytes.push(chunk);
        const payload = JSON.parse(Buffer.concat(bytes).toString('utf8'));
        if (url.pathname !== '/v1/threads/fork') {
          forbiddenInputs.push({ path: url.pathname });
          return json(response, 409, {
            error: 'Fork must never submit model input or create a new prompt',
          });
        }
        assert.deepEqual(Object.keys(payload).sort(), [
          'clientOperationId',
          'environment',
          'hostId',
          'threadId',
          'turnId',
        ]);
        assert.equal(payload.hostId, id);
        assert.equal(payload.threadId, 'fork-source');
        assert.equal(
          payload.turnId,
          'actual-first-turn',
          'Fork action must target the earlier reply, not the latest turn or presentation ID',
        );
        assert(['same-directory', 'worktree'].includes(payload.environment));
        assert.match(payload.clientOperationId, /^[a-zA-Z0-9_-]{8,100}$/);
        posts.push(payload);
        assert(!operations.has(payload.clientOperationId), 'A fork may only be POSTed once');
        operations.set(payload.clientOperationId, Object.freeze({ ...payload }));
        // Release the old in-flight query once the newer native operation owns navigation.
        // Holding it across slow keyboard animation waits would test HTTP timeout instead.
        if (heldOperationId && posts.length === 2) {
          confirmedIds.add(heldOperationId);
          heldOperationId = null;
          heldQuery = false;
          for (const resolve of heldResolvers) resolve();
          heldResolvers = [];
        }
        confirmed = ['same-directory', 'worktree', 'paginated', 'mismatched-receipt'].includes(
          mode,
        );
        return json(response, 200, {
          ...receipt(payload),
          ...(mode === 'mismatched-receipt' ? { turnId: 'wrong-turn' } : {}),
        });
      }
      return json(response, 404, { error: 'Unsupported fixture route' });
    } catch (error) {
      violations.push(error.message);
      json(response, 500, { error: 'Fixture contract failed: ' + error.message });
    }
  });
const host = createHost(hostId),
  second = createHost(secondHostId);
await Promise.all([
  new Promise((resolve) => host.listen(0, '127.0.0.1', resolve)),
  new Promise((resolve) => second.listen(0, '127.0.0.1', resolve)),
]);
const endpoint = 'http://127.0.0.1:' + host.address().port;
const secondEndpoint = 'http://127.0.0.1:' + second.address().port;
const fixture = createServer((request, response) => {
  const url = new URL(request.url, 'http://localhost');
  if (url.pathname === '/reset') {
    if (!modes.has(url.searchParams.get('mode')))
      return json(response, 400, { error: 'Unknown fixture mode' });
    if (historyReads.length) runs.push(counters());
    mode = url.searchParams.get('mode');
    confirmed = false;
    posts = [];
    queries = [];
    historyReads = [];
    optionReads = [];
    forbiddenInputs = [];
    violations = [];
    operations.clear();
    confirmedIds.clear();
    heldOperationId = null;
    heldQuery = false;
    for (const resolve of heldResolvers) resolve();
    heldResolvers = [];
  }
  if (url.pathname === '/hold-original-query') {
    heldOperationId = posts[0]?.clientOperationId;
    return json(response, 200, counters());
  }
  if (url.pathname === '/release-original-query') {
    if (posts[0]) confirmedIds.add(posts[0].clientOperationId);
    heldOperationId = null;
    heldQuery = false;
    for (const resolve of heldResolvers) resolve();
    heldResolvers = [];
    return json(response, 200, counters());
  }
  if (url.pathname === '/confirm') {
    confirmed = true;
    return json(response, 200, counters());
  }
  if (url.pathname === '/results') return json(response, 200, counters());
  if (url.pathname === '/runs') return json(response, 200, [...runs, counters()]);
  return json(response, 200, {
    nonce,
    endpoint,
    token,
    hostId,
    threadId: 'fork-source',
    secondEndpoint,
    secondToken: token,
    secondHostId,
    delayedEndpoint: endpoint,
  });
});
await new Promise((resolve) => fixture.listen(metadataPort, '127.0.0.1', resolve));
const close = async () => {
  for (const server of [host, second, fixture]) server.closeAllConnections();
  await Promise.all(
    [host, second, fixture].map((server) => new Promise((resolve) => server.close(resolve))),
  );
};
if (process.argv.includes('--serve-only')) {
  console.log(
    JSON.stringify({
      suite: 'thread-fork-ios-fixture',
      metadataPort,
      serveOnly: true,
      realDesktopForkVerified: false,
    }),
  );
  await new Promise((resolve) => {
    process.once('SIGINT', resolve);
    process.once('SIGTERM', resolve);
  });
  await close();
} else {
  // An explicitly reserved simulator is required; this harness never picks one implicitly.
  const device = process.env.IOS_SIMULATOR_ID;
  assert(
    device,
    'Coordinate/reserve IOS_SIMULATOR_ID before running native UI tests, or use --serve-only',
  );
  await mkdir('.local', { recursive: true });
  const allTests = [
    'RepeatAfterSuccess',
    'DetachAndNewOperationOwnsNavigation',
    'PaginatedTurnSelection',
    'SameDirectory',
    'Worktree',
    'PendingLateResultPreservesDraft',
    'RestartQueriesOriginalReceipt',
    'HostSwitchDoesNotNavigate',
    'UnavailableOptions',
    'ReceiptIdentityAndMissingReceipt',
    'RejectedOutcome',
  ];
  const tests = process.env.THREAD_FORK_IOS_TESTS
    ? process.env.THREAD_FORK_IOS_TESTS.split(',')
    : allTests;
  assert(
    tests.length > 0 && tests.every((name) => allTests.includes(name)),
    'Unknown fixture test',
  );
  const child = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS Simulator,id=${device}`,
      '-derivedDataPath',
      '.build/ios-thread-fork',
      '-resultBundlePath',
      `.local/thread-fork-ios-fixture-${Date.now()}.xcresult`,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      ...tests.map(
        (name) => `-only-testing:CodexManageUITests/CodexManageUITests/testThreadFork${name}`,
      ),
      'test',
    ],
    {
      stdio: 'inherit',
      env: {
        ...process.env,
        DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer',
      },
    },
  );
  try {
    const code = await new Promise((resolve, reject) => {
      child.once('exit', resolve);
      child.once('error', reject);
    });
    runs.push(counters());
    const evidence = {
      suite: 'thread-fork-ios-fixture',
      selectedTests: tests,
      exitCode: code,
      passed: code === 0 ? tests.length : null,
      runs,
      realDesktopForkVerified: false,
    };
    const reportFile = '.local/thread-fork-ios-fixture-' + Date.now() + '.json';
    await writeFile(reportFile, JSON.stringify(evidence, null, 2) + '\n', { mode: 0o600 });
    console.log(JSON.stringify({ reportFile, exitCode: code, passed: evidence.passed }));
    assert.equal(code, 0);
    assert(runs.every((run) => run.forbiddenInputs.length === 0 && run.violations.length === 0));
  } finally {
    child.kill('SIGTERM');
    await close();
  }
}
