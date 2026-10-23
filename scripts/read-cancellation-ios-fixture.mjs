import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Native URLSession cancellation and Send connection boundaries, with local HTTP fixtures.
// No Codex runtime or production client is contacted.
const endpoint = 'http://127.0.0.1:8802';
const hostId = 'read-cancellation-host';
const threadId = 'read-cancellation-thread';
const token = randomBytes(32).toString('base64url');
let modelsEnabled = true;
let historyArmed = false;
let historyFailure = false;
let resetPending = true;
let cursor = 0;
let counters = {};
let desktopOpenFixture = false;
let ownerConnected = false;
let openingSucceeds = false;
let lateOwner = false;
let statusFailurePending = false;
let openOperation;
let reply;
let receipt;
let silentCompletion = false;
const reset = (desktop = false) => {
  desktopOpenFixture = desktop;
  ownerConnected = openingSucceeds = false;
  lateOwner = false;
  statusFailurePending = desktop;
  openOperation = reply = receipt = undefined;
  silentCompletion = false;
  modelsEnabled = true;
  historyArmed = historyFailure = false;
  resetPending = true;
  cursor = 0;
  counters = {
    modelStarts: 0,
    modelCancelled: 0,
    modelPending: 0,
    modelResponses: 0,
    historyDelayed: 0,
    historyCancelled: 0,
    historyFailures: 0,
    historyReads: 0,
    desktopOpens: 0,
    desktopOpenQueries: 0,
    replies: 0,
  };
};
reset();
const capabilities = () => [
  'projects:read',
  'threads:read',
  'history:read',
  'events',
  ...(desktopOpenFixture ? ['desktop:open', 'reply'] : []),
  ...(modelsEnabled ? ['models:read', 'generation-settings'] : []),
];
const thread = () => ({
  id: threadId,
  hostId,
  projectId: 'read-project',
  title: 'Read lifecycle fixture',
  state: 'idle',
});
const json = (response, status, value) => {
  if (response.destroyed) return;
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const delayed = async (response, key) => {
  const requestCounters = counters;
  requestCounters[key === 'model' ? 'modelStarts' : 'historyDelayed']++;
  if (key === 'model') requestCounters.modelPending++;
  await new Promise((done) => {
    const timer = setTimeout(() => {
      if (key === 'model') requestCounters.modelResponses++;
      done();
    }, 25000);
    response.once('close', () => {
      clearTimeout(timer);
      if (key === 'model') requestCounters.modelPending--;
      if (!response.writableFinished)
        requestCounters[key === 'model' ? 'modelCancelled' : 'historyCancelled']++;
      done();
    });
  });
};
const server = createServer(async (request, response) => {
  const url = new URL(request.url, endpoint);
  if (url.pathname === '/reset') {
    reset(url.searchParams.get('desktop') === '1');
    lateOwner = url.searchParams.get('late') === '1';
    if (lateOwner) statusFailurePending = false;
    return json(response, 200, {
      endpoint,
      token,
      hostId,
      threadId,
      nonce: 'read-cancellation',
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    });
  }
  if (url.pathname === '/results') return json(response, 200, counters);
  if (url.pathname === '/state') {
    switch (url.searchParams.get('mode')) {
      case 'disable-models':
        modelsEnabled = false;
        break;
      case 'enable-models':
        modelsEnabled = true;
        break;
      case 'delay-history':
        historyArmed = true;
        resetPending = true;
        break;
      case 'fail-history':
        historyFailure = true;
        resetPending = true;
        break;
      case 'silent-completion':
        silentCompletion = true;
        // Intentionally omit both a reset and an event: the long poll stays
        // healthy while its body is stale. Recovery must only read history.
        return json(response, 200, counters);
      case 'open-success':
        openingSucceeds = true;
        if (openOperation) {
          ownerConnected = true;
          openOperation.phase = 'opened';
          openOperation.error = null;
        }
        break;
      default:
        return json(response, 400, { error: 'Unknown fixture state' });
    }
    cursor++;
    return json(response, 200, counters);
  }
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, 401, { error: 'Read fixture auth required' });
  if (desktopOpenFixture && request.method === 'POST' && url.pathname === '/v1/desktop/open') {
    let body = '';
    for await (const chunk of request) body += chunk;
    const input = JSON.parse(body);
    assert.equal(input.hostId, hostId);
    assert.equal(input.threadId, threadId);
    assert(
      !openOperation,
      'Send must query the saved connection operation instead of opening again',
    );
    counters.desktopOpens++;
    ownerConnected = openingSucceeds;
    openOperation = {
      hostId,
      threadId,
      clientOperationId: input.clientOperationId,
      phase: openingSucceeds ? 'opened' : 'outcome-unknown',
      error: openingSucceeds ? null : 'Fixture desktop opening unavailable',
    };
    return json(response, 200, openOperation);
  }
  if (desktopOpenFixture && request.method === 'POST' && url.pathname === '/v1/replies') {
    let body = '';
    for await (const chunk of request) body += chunk;
    const input = JSON.parse(body);
    assert.equal(input.hostId, hostId);
    assert.equal(input.threadId, threadId);
    assert(ownerConnected, 'No model submission before a verified desktop connection');
    assert(!reply, 'The original input must be submitted exactly once');
    counters.replies++;
    reply = input;
    receipt = {
      hostId,
      threadId,
      clientMessageId: reply.clientMessageId,
      state: 'completed',
      turnId: 'fixture-turn',
      error: null,
    };
    return json(response, 200, receipt);
  }
  if (request.method !== 'GET') return json(response, 405, { error: 'Unsupported method' });
  if (url.pathname === '/v1/workspace')
    return json(response, 200, {
      hostId,
      name: 'Windows fixture',
      clientType: { id: 'codex', name: 'Codex' },
      connectionMode: 'desktop-runtime',
      capabilities: capabilities(),
      projects: [{ id: 'read-project', hostId, name: 'Fixture project', roots: [] }],
      threads: [thread()],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  assert.equal(url.searchParams.get('hostId'), hostId);
  if (desktopOpenFixture && url.pathname === '/v1/desktop/open-status') {
    assert.equal(url.searchParams.get('threadId'), threadId);
    assert.equal(url.searchParams.get('id'), openOperation?.clientOperationId);
    counters.desktopOpenQueries++;
    if (statusFailurePending) {
      statusFailurePending = false;
      return json(response, 503, { error: 'Fixture desktop status temporarily unavailable' });
    }
    if (lateOwner && counters.desktopOpenQueries >= 2) {
      ownerConnected = true;
      openOperation.phase = 'opened';
      openOperation.error = null;
    }
    return json(response, 200, openOperation);
  }
  if (url.pathname === '/v1/models') {
    await delayed(response, 'model');
    return json(response, 200, {
      models: [
        {
          id: 'read-model',
          name: 'Read fixture model',
          isDefault: true,
          defaultEffort: 'medium',
          supportedEfforts: ['low', 'medium', 'high'],
        },
      ],
    });
  }
  // Optional computer-level probes must reach the unsupported-route response.
  if (['/v1/history', '/v1/events'].includes(url.pathname))
    assert.equal(url.searchParams.get('threadId'), threadId);
  if (url.pathname === '/v1/history') {
    counters.historyReads++;
    if (historyFailure) {
      historyFailure = false;
      counters.historyFailures++;
      return json(response, 500, { error: 'Fixture genuine history failure' });
    }
    if (historyArmed) {
      historyArmed = false;
      await delayed(response, 'history');
    }
    return json(response, 200, {
      hostId,
      threadId,
      thread: thread(),
      nextCursor: null,
      messages: [
        { id: 'read-message', role: 'assistant', text: 'READ_LIFECYCLE_HISTORY', files: [] },
        ...(silentCompletion
          ? [
              {
                id: 'silent-completion',
                role: 'assistant',
                text: 'SILENT_EVENT_RECOVERED',
                files: [],
              },
            ]
          : []),
        ...(reply
          ? [
              { id: 'reply-user', role: 'user', text: reply.text, files: [] },
              {
                id: 'reply-assistant',
                role: 'assistant',
                text: 'AUTOMATIC_CONNECTION_REPLY',
                files: [],
              },
            ]
          : []),
      ],
      runtime: {
        state: 'idle',
        turnId: null,
        plan: [],
        approvals: [],
        receipts: receipt ? [receipt] : [],
        canControl: ownerConnected,
      },
    });
  }
  if (url.pathname === '/v1/events') {
    await new Promise((done) => setTimeout(done, 250));
    const reset = resetPending;
    resetPending = false;
    return json(response, 200, {
      hostId,
      epoch: 'read-lifecycle',
      cursor,
      reset,
      ready: true,
      capabilities: capabilities(),
      events: [],
    });
  }
  return json(response, 404, { error: 'Unsupported fixture route' });
});
await mkdir('.local', { recursive: true });
await new Promise((done) => server.listen(8802, '127.0.0.1', done));
assert(process.env.IOS_SIMULATOR_ID, 'Use a dedicated read lifecycle simulator');
const tests = (
  process.env.IOS_TESTS ??
  'testReadCancellationOnCapabilityChangeAndClientSwitch,testReadCancellationOnBackgroundAndRealFailure'
).split(',');
const resultBundle = `.local/read-cancellation-${Date.now()}.xcresult`;
const child = spawn(
  'xcodebuild',
  [
    '-project',
    process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    `platform=iOS Simulator,id=${process.env.IOS_SIMULATOR_ID}`,
    '-derivedDataPath',
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios-read-cancellation',
    '-resultBundlePath',
    resultBundle,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    ...tests.map((name) => '-only-testing:CodexManageUITests/CodexManageUITests/' + name),
    'test',
  ],
  { stdio: 'inherit', env: process.env },
);
try {
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  assert.equal(code, 0, 'Native read lifecycle assertions must pass');
  console.log(
    JSON.stringify({
      suite: 'read-cancellation',
      selectedTests: tests,
      resultBundle,
      counters,
      realCodexRuntime: false,
    }),
  );
} finally {
  child.kill('SIGTERM');
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
