import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Native presentation of normalized Host events. Adapter lifecycle tests run in pnpm check.
// No real task is submitted or compacted by this fixture.
const hostId = 'compaction-fixture-host';
const threadId = 'compaction-fixture-thread';
const endpoint = 'http://127.0.0.1:8800';
const token = randomBytes(32).toString('base64url');
const capabilities = ['history:read', 'reply', 'events', 'stop'];
const states = new Set(['inProgress', 'completed', 'interrupted', 'failed', 'unknown', 'offline']);
const visited = new Set();
let status = 'inProgress';
let cursor = 1;
const thread = () => ({
  id: threadId,
  hostId,
  projectId: 'compaction-project',
  title: 'Context compaction fixture',
  state: status === 'inProgress' ? 'running' : status === 'failed' ? 'failed' : 'idle',
});
const snapshot = () => ({
  hostId,
  threadId,
  thread: thread(),
  nextCursor: null,
  messages: [
    { id: 'fixture-user', role: 'user', text: 'Fixture task', files: [] },
    {
      id: 'fixture-answer',
      role: 'assistant',
      text: 'Fixture response before compaction.',
      status: 'completed',
      files: [],
    },
    ...['a', 'b'].map((id) => ({
      id: 'fixture-command-' + id,
      role: 'tool',
      kind: 'commandExecution',
      text: 'fixture command ' + id,
      status: 'completed',
      detail: 'Fixture output',
      files: [],
    })),
    {
      id: 'fixture-compaction',
      role: 'tool',
      kind: 'contextCompaction',
      text: '压缩上下文',
      status,
      detail: '',
      files: [],
    },
  ],
  runtime: {
    state: thread().state,
    turnId: status === 'inProgress' ? 'fixture-turn' : null,
    plan: [],
    approvals: [],
    receipts: [],
    canControl: true,
  },
});
const json = (response, code, value) => {
  response.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const server = createServer(async (request, response) => {
  const url = new URL(request.url, endpoint);
  if (url.pathname === '/')
    return json(response, 200, {
      endpoint,
      token,
      hostId,
      threadId,
      nonce: 'compaction',
      secondEndpoint: endpoint,
      secondToken: token,
      secondHostId: hostId,
      delayedEndpoint: endpoint,
    });
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, 401, { error: 'Fixture auth required' });
  if (url.pathname === '/state') {
    assert(states.has(url.searchParams.get('value')));
    status = url.searchParams.get('value');
    visited.add(status);
    cursor++;
    return json(response, 200, { status });
  }
  if (status === 'offline') return json(response, 503, { error: 'Fixture connection unavailable' });
  if (url.pathname === '/v1/workspace')
    return json(response, 200, {
      hostId,
      name: 'Compaction fixture',
      connectionMode: 'desktop-runtime',
      capabilities,
      projects: [{ id: 'compaction-project', hostId, name: 'Fixture project', roots: [] }],
      threads: [thread()],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  assert.equal(url.searchParams.get('hostId'), hostId);
  assert.equal(url.searchParams.get('threadId'), threadId);
  if (url.pathname === '/v1/history') return json(response, 200, snapshot());
  if (url.pathname === '/v1/events') {
    await new Promise((done) => setTimeout(done, 250));
    const reset = url.searchParams.get('epoch') !== 'compaction-epoch';
    return json(response, 200, {
      hostId,
      ready: true,
      capabilities,
      epoch: 'compaction-epoch',
      cursor,
      reset,
      events:
        reset || Number(url.searchParams.get('after')) !== cursor
          ? [{ threadId, kind: 'fixture-state', snapshot: snapshot() }]
          : [],
    });
  }
  return json(response, 404, { error: 'Unsupported fixture route' });
});
await mkdir('.local', { recursive: true });
await new Promise((done) => server.listen(8800, '127.0.0.1', done));
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
    `.local/context-compaction-${Date.now()}.xcresult`,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    '-only-testing:CodexManageUITests/CodexManageUITests/testContextCompactionLifecycleAndClientIsolation',
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
  assert.equal(visited.size, states.size);
  console.log(
    JSON.stringify({
      suite: 'context-compaction',
      passed: 1,
      failed: 0,
      realRuntimeVerified: false,
    }),
  );
} finally {
  child.kill('SIGTERM');
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
