import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Isolated native transport regression; never contacts a real Host or model.
assert(process.env.IOS_SIMULATOR_ID, 'Use a dedicated suggestion stability simulator');
const endpoint = 'http://127.0.0.1:8826';
const hostId = 'suggestion-stability-host';
const threadId = 'suggestion-stability-thread';
const sourceTurnId = 'completed-source';
const target = { agentId: 'codex', hostId, workspaceId: hostId, runtimeId: 'desktop-runtime' };
const token = randomBytes(32).toString('base64url');
const text = '同一展示建议按租期续租，来源不可用后到期清除。';
const suggestion = {
  id: 'original-suggestion',
  text,
  contentSha256: createHash('sha256').update(text).digest('hex'),
  validity: 'desktop-display',
};
const capabilities = [
  'projects:read',
  'threads:read',
  'history:read',
  'events',
  'reply',
  'runtime:control-required',
  'desktop:suggestions:read:v1',
  'desktop:suggestions:displayed:v1',
];
let mode = 'online';
let sequence = 0;
let counters = { transientFailures: 0, suggestionReads: 0, writes: 0 };
const thread = () => ({
  id: threadId,
  hostId,
  projectId: 'project',
  title: 'Suggestion stability',
  state: 'idle',
});
const snapshot = () => ({
  hostId,
  readTarget: target,
  threadId,
  thread: thread(),
  messages: [{ id: 'answer', role: 'assistant', text: 'STABILITY_SOURCE_ANSWER', files: [] }],
  nextCursor: null,
  runtime: {
    state: mode === 'new-turn' ? 'running' : 'idle',
    turnId: mode === 'new-turn' ? 'new-turn' : null,
    plan: [],
    error: null,
    approvals: [],
    receipts: [],
    canControl: true,
    suggestionSourceTurnId: mode === 'new-turn' ? null : sourceTurnId,
  },
});
const json = (response, status, value) => {
  if (response.destroyed) return;
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const server = createServer(async (request, response) => {
  const url = new URL(request.url, endpoint);
  if (request.method !== 'GET') {
    counters.writes++;
    return json(response, 405, { error: 'Read-only fixture' });
  }
  if (url.pathname === '/reset') {
    mode = 'online';
    counters = { transientFailures: 0, suggestionReads: 0, writes: 0 };
    return json(response, 200, {
      endpoint,
      token,
      hostId,
      threadId,
      nonce: 'suggestion-stability',
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    });
  }
  if (url.pathname === '/state') {
    mode = url.searchParams.get('mode');
    assert(['online', 'offline', 'recovering', 'new-turn'].includes(mode));
    return json(response, 200, { mode });
  }
  if (url.pathname === '/results') return json(response, 200, { ...counters, mode });
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, 401, { error: 'Unauthorized' });
  if (mode === 'offline') {
    counters.transientFailures++;
    return json(response, 503, { error: 'Temporary transport interruption' });
  }
  if (url.pathname === '/v1/workspace')
    return json(response, 200, {
      hostId,
      name: 'Suggestion stability',
      clientType: { id: 'codex', name: 'Codex' },
      readTarget: target,
      connectionMode: 'desktop-runtime',
      capabilities,
      projects: [{ id: 'project', hostId, name: 'Stability project', roots: [] }],
      threads: [thread()],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  if (url.pathname === '/v1/history') {
    if (mode === 'recovering')
      return json(response, 503, { error: 'Owner snapshot not recovered yet' });
    return json(response, 200, snapshot());
  }
  if (url.pathname === '/v1/threads/next-turn-suggestion') {
    counters.suggestionReads++;
    return json(response, 200, {
      schemaVersion: 1,
      target,
      threadId,
      sourceTurnId,
      readOnly: true,
      source: 'desktop-display',
      suggestion: { ...suggestion, expiresAt: new Date(Date.now() + 6000).toISOString() },
    });
  }
  if (url.pathname === '/v1/events') {
    await new Promise((done) => setTimeout(done, 300));
    if (mode === 'offline') {
      counters.transientFailures++;
      return json(response, 503, { error: 'Temporary long-poll interruption' });
    }
    return json(response, 200, {
      hostId,
      epoch: 'stability',
      cursor: ++sequence,
      reset: false,
      ready: true,
      capabilities,
      events: mode === 'recovering' ? [] : [{ sequence, threadId, snapshot: snapshot() }],
    });
  }
  return json(response, 404, { error: 'Unsupported fixture route' });
});
await mkdir('.local', { recursive: true });
await new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(8826, '127.0.0.1', resolve);
});
const resultBundle = `.local/suggestion-stability-${Date.now()}.xcresult`;
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
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios-suggestion-stability',
    '-resultBundlePath',
    resultBundle,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    '-only-testing:CodexManageUITests/CodexManageUITests/testSuggestionDisplayLeaseExpiresAndRecoversWithoutSending',
    'test',
  ],
  { stdio: 'inherit', env: process.env },
);
try {
  const code = await new Promise((resolve, reject) => {
    child.once('exit', resolve);
    child.once('error', reject);
  });
  assert.equal(code, 0, 'New native suggestion continuity regression must pass');
  assert.equal(counters.writes, 0);
  assert(counters.transientFailures > 0);
  console.log(
    JSON.stringify({ suite: 'suggestion-stability', resultBundle, counters, realHost: false }),
  );
} finally {
  child.kill('SIGTERM');
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
}
