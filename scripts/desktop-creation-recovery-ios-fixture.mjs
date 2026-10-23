import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Native UI boundary fixture. No desktop execution or real user data is used.
const hostId = 'fixture-creation-recovery-host';
const token = randomBytes(32).toString('base64url');
const nonce = randomBytes(4).toString('hex');
const capabilities = [
  'projects:read',
  'threads:read',
  'history:read',
  'reply',
  'events',
  'desktop:creations',
  'desktop:creations:projectless',
];
const operations = new Map();
let originalId,
  submitCount = 0,
  oldSubmitCount = 0,
  detachCount = 0,
  replyCount = 0;
let mode = 'draft-recovery',
  prepareCount = 0,
  statusQueries = 0;
const completedRuns = [];
const json = (response, status, value) => {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const thread = (operation) => ({
  id: 'native-' + operation.clientOperationId,
  hostId,
  projectId: operation.projectId,
  title: 'Recovery ' + nonce,
  state: 'idle',
});
const result = (operation) => ({
  hostId,
  clientOperationId: operation.clientOperationId,
  projectId: operation.projectId,
  phase: operation.confirmed ? 'confirmed' : operation.submitted ? 'outcome-unknown' : 'prepared',
  threadId: operation.confirmed ? thread(operation).id : null,
  error: null,
  uploaded: [],
  ...(operation.detached ? { detached: true } : {}),
  ...(operation.confirmed ? { thread: thread(operation) } : {}),
});
const counters = () => ({
  mode,
  prepareCount,
  statusQueries,
  submitCount,
  oldSubmitCount,
  detachCount,
  replyCount,
  originalText: operations.get(originalId)?.text,
});
const host = createServer(async (request, response) => {
  try {
    if (request.headers.authorization !== 'Bearer ' + token)
      return json(response, 401, { error: 'auth required' });
    const url = new URL(request.url, 'http://localhost');
    if (url.pathname === '/v1/workspace')
      return json(response, 200, {
        hostId,
        name: 'Fixture creation recovery',
        connectionMode: 'desktop-runtime',
        capabilities,
        projects: [{ id: 'fixture-project', hostId, name: 'Fixture project', roots: [] }],
        threads: [...operations.values()].filter((o) => o.confirmed).map(thread),
        nextProjectCursor: null,
        nextThreadCursor: null,
      });
    if (request.method === 'GET' && url.searchParams.get('hostId') !== hostId)
      return json(response, 403, { error: 'wrong host' });
    if (url.pathname === '/v1/events') {
      await new Promise((resolve) => setTimeout(resolve, 400));
      return json(response, 200, {
        hostId,
        ready: true,
        capabilities,
        epoch: 'recovery-fixture',
        cursor: 0,
        reset: false,
        events: [],
      });
    }
    if (url.pathname === '/v1/desktop/creations' && request.method === 'GET') {
      const operation = operations.get(url.searchParams.get('id'));
      if (operation?.submitted && mode === 'timeout-recovery') {
        assert.equal(url.searchParams.get('id'), originalId);
        statusQueries++;
        if (statusQueries <= 2) return json(response, 503, { error: 'temporary status failure' });
        operation.confirmed = true;
      }
      return operation
        ? json(response, 200, result(operation))
        : json(response, 404, { error: 'unknown operation' });
    }
    if (url.pathname === '/v1/history') {
      const operation = [...operations.values()].find(
        (o) => thread(o).id === url.searchParams.get('threadId'),
      );
      if (!operation?.confirmed) return json(response, 404, { error: 'thread not confirmed' });
      return json(response, 200, {
        hostId,
        threadId: thread(operation).id,
        thread: thread(operation),
        nextCursor: null,
        messages: [
          { id: 'user', role: 'user', text: operation.text, files: [] },
          { id: 'answer', role: 'assistant', text: 'RECOVERY_NEW_CONFIRMED_' + nonce, files: [] },
        ],
        runtime: {
          canControl: true,
          state: 'idle',
          turnId: null,
          plan: [],
          approvals: [],
          receipts: [],
          error: null,
        },
      });
    }
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    assert.equal(data.hostId, hostId);
    if (url.pathname === '/v1/desktop/creations') {
      prepareCount++;
      assert.equal(data.projectId, 'fixture-project');
      assert.deepEqual(data.attachments, []);
      assert.equal(
        data.text,
        (mode === 'timeout-recovery'
          ? 'TIMEOUT_FIXTURE_'
          : mode === 'missing-operation'
            ? 'MISSING_FIXTURE_'
            : originalId
              ? 'NEW_FIXTURE_'
              : 'RECOVER_FIXTURE_') + nonce,
      );
      assert(
        !operations.has(data.clientOperationId),
        'New draft must use a different immutable operation ID',
      );
      if (!originalId) originalId = data.clientOperationId;
      if (mode === 'missing-operation')
        return json(response, 504, { error: 'prepare response timed out' });
      operations.set(data.clientOperationId, data);
      return json(response, 200, result(data));
    }
    const operation = operations.get(data.clientOperationId);
    if (url.pathname === '/v1/desktop/creations/submit') {
      assert(operation);
      submitCount++;
      if (operation.clientOperationId === originalId) oldSubmitCount++;
      operation.submitted = true;
      operation.confirmed = operation.clientOperationId !== originalId;
      if (mode === 'timeout-recovery')
        return json(response, 504, { error: 'submission confirmation timed out' });
      return json(response, 200, result(operation));
    }
    if (url.pathname === '/v1/desktop/creations/detach') {
      assert.equal(data.acknowledgeUncertain, true);
      assert.equal(data.clientOperationId, originalId);
      detachCount++;
      if (mode === 'missing-operation')
        return json(response, 404, { error: 'operation does not exist' });
      assert(operation);
      operation.detached = true;
      return json(response, 200, result(operation));
    }
    if (url.pathname === '/v1/replies') replyCount++;
    return json(response, 404, { error: 'unsupported fixture operation' });
  } catch {
    json(response, 500, { error: 'fixture contract failed' });
  }
});
await new Promise((resolve) => host.listen(0, '127.0.0.1', resolve));
const endpoint = 'http://127.0.0.1:' + host.address().port;
const fixture = createServer((request, response) => {
  const url = new URL(request.url, 'http://localhost');
  if (url.pathname === '/reset') {
    if (prepareCount) completedRuns.push(counters());
    mode = url.searchParams.get('mode');
    assert(['draft-recovery', 'timeout-recovery', 'missing-operation'].includes(mode));
    operations.clear();
    originalId = undefined;
    submitCount = 0;
    oldSubmitCount = 0;
    detachCount = 0;
    replyCount = 0;
    prepareCount = 0;
    statusQueries = 0;
  }
  if (request.url === '/confirm-old') {
    assert.equal(operations.get(originalId)?.detached, true);
    operations.get(originalId).confirmed = true;
  }
  return json(
    response,
    200,
    request.url === '/results'
      ? counters()
      : {
          nonce,
          endpoint,
          token,
          hostId,
          threadId: '',
          fileName: '',
          expectedMarker: '',
          secondEndpoint: endpoint,
          secondToken: token,
          secondHostId: hostId,
          delayedEndpoint: endpoint,
        },
  );
});
await new Promise((resolve) => fixture.listen(8806, '127.0.0.1', resolve));
await mkdir('.local', { recursive: true });
const device = process.env.IOS_SIMULATOR_ID;
const child = spawn(
  'xcodebuild',
  [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    device ? `platform=iOS Simulator,id=${device}` : 'platform=iOS Simulator,name=iPhone 17 Pro',
    '-derivedDataPath',
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/creation-recovery-ui',
    '-resultBundlePath',
    `.local/creation-recovery-ios-${Date.now()}.xcresult`,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    '-only-testing:CodexManageUITests/CodexManageUITests/testDesktopCreationUnknownDraftCanBeClearedAndDetached',
    '-only-testing:CodexManageUITests/CodexManageUITests/testDesktopCreationTimeoutObservesWithoutResubmit',
    '-only-testing:CodexManageUITests/CodexManageUITests/testDesktopCreationMissingOperationCanEndWait',
    'test',
  ],
  { stdio: 'inherit', env: process.env },
);
try {
  const code = await new Promise((resolve, reject) => {
    child.once('exit', resolve);
    child.once('error', reject);
  });
  assert.equal(code, 0);
  completedRuns.push(counters());
  const draftRun = completedRuns.find((run) => run.mode === 'draft-recovery');
  const timeoutRun = completedRuns.find((run) => run.mode === 'timeout-recovery');
  const missingRun = completedRuns.find((run) => run.mode === 'missing-operation');
  assert.equal(draftRun?.submitCount, 2);
  assert.equal(draftRun?.oldSubmitCount, 1);
  assert.equal(draftRun?.detachCount, 1);
  assert.equal(timeoutRun?.prepareCount, 1);
  assert.equal(timeoutRun?.submitCount, 1);
  assert(timeoutRun?.statusQueries >= 3);
  assert.equal(missingRun?.prepareCount, 1);
  assert.equal(missingRun?.submitCount, 0);
  assert.equal(missingRun?.detachCount, 1);
  assert(completedRuns.every((run) => run.replyCount === 0));
  console.log(
    JSON.stringify({
      suite: 'creation-recovery-ios-fixture',
      passed: 3,
      failed: 0,
      runs: completedRuns,
      realDesktopCreationVerified: false,
    }),
  );
} finally {
  child.kill('SIGTERM');
  host.closeAllConnections();
  fixture.closeAllConnections();
  await Promise.all([
    new Promise((resolve) => host.close(resolve)),
    new Promise((resolve) => fixture.close(resolve)),
  ]);
}
