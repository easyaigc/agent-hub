import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Isolated native UI checks; this fixture never connects to a Codex runtime.
const hostId = 'client-edit-fixture-host';
const endpoint = 'http://127.0.0.1:8798';
const secondEndpoint = 'http://localhost:8798';
let token = randomBytes(32).toString('base64url');
let offline = false;
let wrongHost = false;
let identityMismatchResponses = 0;
const metadata = () => ({
  endpoint,
  secondEndpoint,
  token,
  secondToken: token,
  hostId,
  secondHostId: hostId + '-other',
  threadId: 'client-edit-thread',
  nonce: 'client-edit',
  delayedEndpoint: endpoint,
});
const json = (response, status, value) => {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const fixture = createServer((request, response) => {
  const url = new URL(request.url, endpoint);
  if (url.pathname === '/reset') {
    offline = wrongHost = false;
    identityMismatchResponses = 0;
    token = randomBytes(32).toString('base64url');
    return json(response, 200, metadata());
  }
  if (url.pathname === '/offline') {
    offline = true;
    return json(response, 200, metadata());
  }
  if (url.pathname === '/wrong-host') {
    wrongHost = true;
    return json(response, 200, metadata());
  }
  if (url.pathname === '/rotate-key') {
    token = randomBytes(32).toString('base64url');
    return json(response, 200, metadata());
  }
  if (offline) return json(response, 503, { error: 'Fixture computer offline' });
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, 401, { error: 'Fixture auth required' });
  if (url.pathname === '/v1/workspace') {
    // Refreshes on the original connection can race the edit validation. Only
    // the candidate address under test may consume the one-shot identity fault.
    const mismatch = wrongHost && request.headers.host === new URL(secondEndpoint).host;
    const id = mismatch ? hostId + '-other' : hostId;
    if (mismatch) {
      wrongHost = false;
      identityMismatchResponses++;
    }
    return json(response, 200, {
      hostId: id,
      name: 'Client edit fixture',
      connectionMode: 'history-only',
      capabilities: ['projects:read', 'threads:read', 'history:read'],
      projects: [{ id: 'client-edit-project', hostId: id, name: 'Fixture project', roots: [] }],
      threads: [
        {
          id: 'client-edit-thread',
          hostId: id,
          projectId: 'client-edit-project',
          title: 'Fixture conversation',
          state: 'unknown',
        },
      ],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  }
  if (url.pathname === '/v1/history') {
    assert.equal(url.searchParams.get('hostId'), hostId);
    assert.equal(url.searchParams.get('threadId'), 'client-edit-thread');
    return json(response, 200, {
      hostId,
      threadId: 'client-edit-thread',
      nextCursor: null,
      messages: [
        {
          id: 'client-edit-message',
          role: 'assistant',
          text: 'CLIENT_EDIT_FIXTURE_HISTORY',
          files: [],
        },
      ],
    });
  }
  return json(response, 404, { error: 'Unsupported fixture route' });
});
await new Promise((done) => fixture.listen(8798, '127.0.0.1', done));
await mkdir('.local', { recursive: true });
const destination = process.env.IOS_SIMULATOR_ID
  ? `platform=iOS Simulator,id=${process.env.IOS_SIMULATOR_ID}`
  : 'platform=iOS Simulator,name=iPhone 17 Pro';
const child = spawn(
  'xcodebuild',
  [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    destination,
    '-derivedDataPath',
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios',
    '-resultBundlePath',
    `.local/client-edit-${Date.now()}.xcresult`,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    '-only-testing:CodexManageUITests/CodexManageUITests/testClientEditCancelAndNicknamePreserveSelectionAndDraft',
    '-only-testing:CodexManageUITests/CodexManageUITests/testClientEditConnectionValidationAndPersistence',
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
  assert.equal(identityMismatchResponses, 1, 'Candidate identity rejection must be exercised');
  console.log(
    JSON.stringify({ suite: 'client-edit', passed: 2, failed: 0, realRuntimeVerified: false }),
  );
} finally {
  child.kill('SIGTERM');
  fixture.closeAllConnections();
  await new Promise((done) => fixture.close(done));
}
