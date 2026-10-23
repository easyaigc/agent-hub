import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Isolated HTTP fixture for the real iOS app. Does not operate or claim a desktop runtime.
const hostId = 'fixture-creation-host',
  token = randomBytes(32).toString('base64url');
const nonce = randomBytes(4).toString('hex');
const caps = [
  'projects:read',
  'threads:read',
  'history:read',
  'reply',
  'events',
  'desktop:creations',
  'desktop:creations:projectless',
];
let operation,
  submitCount = 0,
  replyCount = 0,
  queryCount = 0,
  confirmed = false;
const thread = () => ({
  id: 'fixture-native-thread',
  hostId,
  projectId: operation.projectId,
  title: 'Fixture ' + nonce,
  state: 'idle',
});
const result = () => ({
  hostId,
  clientOperationId: operation.clientOperationId,
  projectId: operation.projectId,
  phase: confirmed ? 'confirmed' : operation.submitted ? 'outcome-unknown' : 'prepared',
  threadId: confirmed ? thread().id : null,
  error: null,
  uploaded: [],
  ...(confirmed ? { thread: thread() } : {}),
});
const json = (response, status, value) => {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const host = createServer(async (request, response) => {
  try {
    if (request.headers.authorization !== 'Bearer ' + token)
      return json(response, 401, { error: 'fixture auth required' });
    const url = new URL(request.url, 'http://localhost');
    if (url.pathname === '/v1/workspace')
      return json(response, 200, {
        hostId,
        name: 'Fixture desktop creation',
        connectionMode: 'desktop-runtime',
        capabilities: caps,
        projects: [{ id: 'fixture-project', hostId, name: 'Fixture project', roots: [] }],
        threads: confirmed ? [thread()] : [],
        nextProjectCursor: null,
        nextThreadCursor: null,
      });
    if (url.searchParams.get('hostId') !== hostId && request.method === 'GET')
      return json(response, 403, { error: 'fixture host mismatch' });
    if (url.pathname === '/v1/events') {
      await new Promise((done) => setTimeout(done, 400));
      return json(response, 200, {
        hostId,
        ready: true,
        capabilities: caps,
        epoch: 'fixture-epoch',
        cursor: 0,
        reset: false,
        events: [],
      });
    }
    if (url.pathname === '/v1/history')
      return json(response, 200, {
        hostId,
        threadId: thread().id,
        thread: thread(),
        nextCursor: null,
        messages: [
          { id: 'fixture-user', role: 'user', text: operation.text, files: [] },
          {
            id: 'fixture-answer',
            role: 'assistant',
            text: 'FIXTURE_CONFIRMED_' + nonce,
            files: [],
          },
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
    if (url.pathname === '/v1/desktop/creations' && request.method === 'GET') {
      if (url.searchParams.get('id') !== operation?.clientOperationId)
        return json(response, 404, { error: 'fixture operation not found' });
      queryCount++;
      return json(response, 200, result());
    }
    const bytes = [];
    for await (const chunk of request) bytes.push(chunk);
    const data = JSON.parse(Buffer.concat(bytes).toString('utf8'));
    assert.equal(data.hostId, hostId);
    if (url.pathname === '/v1/desktop/creations') {
      assert.equal(data.projectId, 'fixture-project');
      assert.equal(data.text, 'CREATE_FIXTURE_' + nonce);
      assert.deepEqual(data.attachments, []);
      if (operation) assert.equal(operation.clientOperationId, data.clientOperationId);
      else operation = data;
      return json(response, 200, result());
    }
    if (url.pathname === '/v1/desktop/creations/submit') {
      assert.equal(data.clientOperationId, operation.clientOperationId);
      submitCount++;
      operation.submitted = true;
      return json(response, 200, result());
    }
    if (url.pathname === '/v1/replies') replyCount++;
    return json(response, 404, { error: 'fixture unsupported route' });
  } catch {
    json(response, 500, { error: 'fixture contract failed' });
  }
});
await new Promise((done) => host.listen(0, '127.0.0.1', done));
const endpoint = 'http://127.0.0.1:' + host.address().port;
const fixture = createServer((request, response) => {
  if (request.url === '/confirm' && request.method === 'POST') {
    assert(operation?.submitted, 'Only confirm the operation already submitted by the app');
    confirmed = true;
    return json(response, 200, { confirmed });
  }
  return json(
    response,
    200,
    request.url === '/results'
      ? { submitCount, replyCount, queryCount, confirmed, projectId: operation?.projectId }
      : {
          nonce,
          endpoint,
          token,
          hostId,
          threadId: 'fixture-native-thread',
          fileName: '',
          expectedMarker: '',
          secondEndpoint: endpoint,
          secondToken: token,
          secondHostId: hostId,
          delayedEndpoint: endpoint,
        },
  );
});
await new Promise((done) => fixture.listen(8796, '127.0.0.1', done));
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
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios',
    '-resultBundlePath',
    `.local/desktop-creation-ios-fixture-${Date.now()}.xcresult`,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    '-only-testing:CodexManageUITests/CodexManageUITests/testDesktopCreationPendingSurvivesRestartWithoutReplay',
    'test',
  ],
  { stdio: 'inherit', env: process.env },
);
try {
  const code = await new Promise((done, reject) => {
    child.once('exit', done);
    child.once('error', reject);
  });
  assert.equal(code, 0);
  assert.equal(submitCount, 1);
  assert.equal(replyCount, 0);
  assert.equal(confirmed, true);
  assert.ok(queryCount >= 1);
  console.log(
    JSON.stringify({
      suite: 'desktop-creation-ios-fixture',
      passed: 1,
      failed: 0,
      submitCount,
      replyCount,
      realDesktopCreationVerified: false,
    }),
  );
} finally {
  child.kill('SIGTERM');
  host.closeAllConnections();
  fixture.closeAllConnections();
  await Promise.all([new Promise((r) => host.close(r)), new Promise((r) => fixture.close(r))]);
}
