import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Synthetic Host snapshots test the native button and its commands; no Codex is started.
const hostId = 'composer-state-fixture';
const threadId = 'composer-state-thread';
const token = randomBytes(32).toString('base64url');
const endpoint = 'http://127.0.0.1:8799';
const capabilities = [
  'reply',
  'stop',
  'queue',
  'steer',
  'history:read',
  'events',
  'desktop:suggestions:read:v1',
  'desktop:suggestions:displayed:v1',
];
const readTarget = {
  agentId: 'codex',
  hostId,
  workspaceId: hostId,
  runtimeId: 'desktop-runtime',
};
const suggestionText = '继续检查草稿清空后的建议恢复，并核对可用按钮的背景颜色。';
const suggestion = {
  id: 'composer-suggestion',
  text: suggestionText,
  contentSha256: createHash('sha256').update(suggestionText).digest('hex'),
  validity: 'desktop-display',
};
const states = new Set([
  'completed',
  'interrupted',
  'failed',
  'paused',
  'completed-with-old-interruption',
  'unknown',
  'running',
  'suggestion',
]);
let state = 'completed';
let cursor = 1;
let stopCount = 0;
let resumeCount = 0;
let queueCount = 0;
let steerCount = 0;
let cancelCount = 0;
let replyMode;
const receipts = [];
const queueIds = [];
const visited = new Set();
const thread = () => ({
  id: threadId,
  hostId,
  projectId: 'composer-project',
  title: 'Composer state fixture',
  state: state === 'running' ? 'running' : state === 'failed' ? 'failed' : 'idle',
});
const snapshot = () => ({
  readTarget,
  hostId,
  threadId,
  thread: thread(),
  nextCursor: null,
  messages: [
    { id: 'composer-user', role: 'user', text: 'Fixture task', files: [] },
    ...(state === 'completed-with-old-interruption'
      ? [
          {
            id: 'old-tool',
            role: 'tool',
            kind: 'commandExecution',
            text: 'Fixture tool',
            status: 'interrupted',
            files: [],
          },
        ]
      : []),
    {
      id: 'composer-answer',
      role: 'assistant',
      text: 'COMPOSER_STATE_' + state,
      status: ['completed', 'completed-with-old-interruption', 'suggestion'].includes(state)
        ? 'completed'
        : state === 'running'
          ? 'inProgress'
          : state,
      files: [],
    },
  ],
  runtime: {
    state: ['completed', 'completed-with-old-interruption', 'interrupted', 'suggestion'].includes(
      state,
    )
      ? 'idle'
      : state,
    // Terminal and unknown snapshots can retain a historical turn ID. Only an
    // explicitly active state may show progress or the stop/queue controls.
    turnId: 'fixture-turn',
    plan: [],
    approvals: [],
    receipts:
      state === 'completed-with-old-interruption'
        ? [
            {
              clientMessageId: 'old-receipt',
              hostId,
              threadId,
              state: 'interrupted',
              turnId: 'old-turn',
              error: null,
            },
          ]
        : receipts,
    canControl: true,
    ...(state === 'suggestion' ? { suggestionSourceTurnId: 'suggestion-source-turn' } : {}),
  },
});
const json = (response, value, status = 200) => {
  response.writeHead(status, {
    'Content-Type': 'application/json',
    'Cache-Control': 'no-store',
  });
  response.end(JSON.stringify(value));
};
const fixture = createServer(async (request, response) => {
  const url = new URL(request.url, endpoint);
  if (url.pathname === '/')
    return json(response, {
      endpoint,
      token,
      hostId,
      threadId,
      threadTitle: thread().title,
      nonce: 'composer-state',
      secondEndpoint: endpoint,
      secondToken: token,
      secondHostId: hostId,
      delayedEndpoint: endpoint,
      suggestionText,
    });
  if (url.pathname === '/results')
    return json(response, {
      stopCount,
      resumeCount,
      replyMode,
      queueCount,
      steerCount,
      cancelCount,
      queueIds,
    });
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, { error: 'Fixture auth required' }, 401);
  if (url.pathname === '/state') {
    const next = url.searchParams.get('value');
    assert.ok(states.has(next));
    state = next;
    visited.add(state);
    cursor++;
    return json(response, { state });
  }
  if (url.pathname === '/v1/workspace')
    return json(response, {
      hostId,
      name: 'Composer fixture',
      connectionMode: state === 'suggestion' ? 'desktop-runtime' : 'managed-runtime',
      readTarget,
      clientType: { id: 'codex', name: 'Codex' },
      capabilities,
      projects: [{ id: 'composer-project', hostId, name: 'Fixture project', roots: [] }],
      threads: [thread()],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  if (url.pathname === '/v1/models') return json(response, { models: [] });
  if (url.pathname === '/v1/threads/next-turn-suggestion') {
    assert.deepEqual(JSON.parse(url.searchParams.get('target')), readTarget);
    assert.equal(url.searchParams.get('threadId'), threadId);
    assert.equal(url.searchParams.get('sourceTurnId'), 'suggestion-source-turn');
    return json(response, {
      schemaVersion: 1,
      target: readTarget,
      threadId,
      sourceTurnId: 'suggestion-source-turn',
      readOnly: true,
      source: 'desktop-display',
      suggestion: {
        ...suggestion,
        expiresAt: new Date(Date.now() + 6000).toISOString(),
      },
    });
  }
  if (url.pathname === '/v1/history' || url.pathname === '/v1/events') {
    assert.equal(url.searchParams.get('hostId'), hostId);
    assert.equal(url.searchParams.get('threadId'), threadId);
    if (url.pathname === '/v1/history') return json(response, snapshot());
    await new Promise((done) => setTimeout(done, 300));
    return json(response, {
      hostId,
      epoch: 'composer-fixture',
      cursor,
      reset: false,
      ready: true,
      capabilities,
      events:
        cursor > Number(url.searchParams.get('after') ?? -1)
          ? [{ threadId, snapshot: snapshot() }]
          : [],
    });
  }
  if (request.method === 'POST') {
    let bytes = '';
    for await (const chunk of request) bytes += chunk;
    const body = JSON.parse(bytes);
    assert.equal(body.hostId, hostId);
    assert.equal(body.threadId, threadId);
    if (url.pathname === '/v1/stop') {
      assert.equal(body.turnId, 'fixture-turn');
      stopCount++;
      for (const receipt of receipts)
        if (receipt.state === 'submitted') receipt.state = 'interrupted';
      state = 'interrupted';
      cursor++;
      return json(response, { ok: true });
    }
    if (url.pathname === '/v1/replies') {
      const receipt = {
        ...body,
        state: 'submitted',
        turnId: 'fixture-turn',
        error: null,
      };
      if (body.mode === 'queue') {
        queueCount++;
        assert.equal(body.text, 'queued-fixture-' + queueCount);
        receipt.state = 'queued';
        receipt.turnId = null;
        queueIds.push(body.clientMessageId);
      } else if (body.mode === 'steer') {
        assert.equal(body.text, 'steer-fixture');
        assert.equal(body.expectedTurnId, 'fixture-turn');
        steerCount++;
      } else {
        assert.equal(body.text, '继续完成上一个任务。');
        assert.equal(body.mode, 'next-turn');
        resumeCount++;
        replyMode = body.mode;
        state = 'running';
      }
      receipts.push(receipt);
      cursor++;
      return json(response, receipt);
    }
    if (url.pathname === '/v1/queue/cancel') {
      const receipt = receipts.find((entry) => entry.clientMessageId === body.clientMessageId);
      assert.equal(receipt?.state, 'queued');
      receipt.state = 'cancelled';
      cancelCount++;
      cursor++;
      return json(response, { ok: true });
    }
  }
  return json(response, { error: 'Unsupported fixture route' }, 404);
});
await mkdir('.local', { recursive: true });
await new Promise((resolve, reject) => {
  fixture.once('error', reject);
  fixture.listen(8799, '127.0.0.1', resolve);
});
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
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios-composer',
    '-resultBundlePath',
    `.local/composer-state-${Date.now()}.xcresult`,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    '-only-testing:CodexManageUITests/CodexManageUITests/testUnifiedComposerStopResumeAndRestart',
    '-only-testing:CodexManageUITests/CodexManageUITests/testComposerRecoveryFromHostStates',
    '-only-testing:CodexManageUITests/CodexManageUITests/testDesktopSuggestionClearingRestoresAndShowsEnabledAction',
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
  assert.equal(stopCount, 1);
  assert.equal(resumeCount, 1);
  assert.equal(queueCount, 3);
  assert.equal(steerCount, 1);
  assert.equal(cancelCount, 1);
  assert.deepEqual(visited, states);
  console.log(
    JSON.stringify({
      suite: 'composer-state',
      passed: 3,
      failed: 0,
      realRuntimeVerified: false,
    }),
  );
} finally {
  child.kill('SIGTERM');
  fixture.closeAllConnections();
  await new Promise((done) => fixture.close(done));
}
