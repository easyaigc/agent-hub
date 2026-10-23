import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Native UI and HTTP contracts only; this fixture never starts Codex.
const hostId = 'plan-mode-fixture';
const threadId = 'plan-mode-thread';
const endpoint = 'http://127.0.0.1:8796';
const token = randomBytes(32).toString('base64url');
const capabilities = ['history:read', 'reply', 'plan-mode', 'events'];
let cursor = 1,
  mode = 'plan',
  revision = 1,
  writable = true;
let proposedPlan = {
  id: 'plan-1',
  turnId: 'turn-1',
  text: '# Mobile plan\n\nInspect input, then implement.',
};
const replies = [],
  receipts = [];
const thread = () => ({
  id: threadId,
  hostId,
  projectId: null,
  title: 'Plan mode fixture',
  state: 'idle',
});
const snapshot = () => ({
  hostId,
  threadId,
  thread: thread(),
  nextCursor: null,
  messages: [
    { id: 'fixture-user', role: 'user', text: 'Plan this task', files: [] },
    {
      id: 'fixture-tool',
      role: 'tool',
      kind: 'commandExecution',
      text: 'Read fixture input',
      status: 'completed',
      files: [],
    },
    ...(proposedPlan
      ? [
          {
            id: 'plan-body-' + revision,
            role: 'tool',
            kind: 'plan',
            text: proposedPlan.text,
            status: 'completed',
            files: [],
          },
        ]
      : [
          {
            id: 'done-' + revision,
            role: 'assistant',
            text: 'PLAN_ACTION_COMPLETED',
            status: 'completed',
            files: [],
          },
        ]),
  ],
  runtime: {
    state: 'idle',
    turnId: null,
    plan: [],
    approvals: [],
    receipts,
    canControl: writable,
    collaborationMode: mode,
    proposedPlan,
  },
});
const json = (response, value, status = 200) => {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
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
      nonce: 'plan-mode',
      secondEndpoint: endpoint,
      secondToken: token,
      secondHostId: hostId,
      delayedEndpoint: endpoint,
    });
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, { error: 'Fixture auth required' }, 401);
  if (url.pathname === '/readonly') {
    writable = false;
    mode = 'plan';
    revision++;
    proposedPlan = {
      id: 'readonly-plan',
      turnId: 'readonly-turn',
      text: '# Cached plan\n\nRead only plan.',
    };
    cursor++;
    return json(response, { ok: true });
  }
  if (url.pathname === '/v1/workspace')
    return json(response, {
      hostId,
      name: 'Plan fixture',
      clientType: { id: 'codex', name: 'Codex' },
      connectionMode: 'desktop-runtime',
      capabilities,
      projects: [],
      threads: [thread()],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  assert.equal(url.searchParams.get('hostId') ?? hostId, hostId);
  if (url.pathname === '/v1/history') return json(response, snapshot());
  if (url.pathname === '/v1/events') {
    assert.equal(url.searchParams.get('threadId'), threadId);
    // Match the Host's long-poll contract: release immediately on a changed
    // cursor, otherwise hold an idle request instead of publishing at 4 Hz.
    const after = Number(url.searchParams.get('after'));
    const deadline = Date.now() + 5_000;
    while (after === cursor && Date.now() < deadline && !response.destroyed)
      await new Promise((done) => setTimeout(done, 100));
    if (response.destroyed) return;
    return json(response, {
      hostId,
      epoch: 'plan-epoch',
      cursor,
      reset: false,
      ready: true,
      capabilities,
      events: after !== cursor ? [{ threadId, snapshot: snapshot() }] : [],
    });
  }
  if (url.pathname === '/v1/replies' && request.method === 'POST') {
    let bytes = '';
    for await (const chunk of request) bytes += chunk;
    const body = JSON.parse(bytes);
    assert.equal(body.hostId, hostId);
    assert.equal(body.threadId, threadId);
    assert.equal(body.mode, 'next-turn');
    assert.equal(writable, true);
    assert(['plan', 'default'].includes(body.collaborationMode));
    if (body.expectedPlanId) assert.equal(body.expectedPlanId, proposedPlan?.id);
    if (body.text.startsWith('PLEASE IMPLEMENT THIS PLAN:')) {
      assert.equal(body.text, 'PLEASE IMPLEMENT THIS PLAN:\n' + proposedPlan.text);
      assert.equal(body.collaborationMode, 'default');
      proposedPlan = null;
    } else if (body.collaborationMode === 'plan') {
      revision++;
      proposedPlan = {
        id: 'plan-' + revision,
        turnId: 'turn-' + revision,
        text: '# Revised mobile plan\n\n' + body.text,
      };
    } else {
      proposedPlan = null;
    }
    mode = body.collaborationMode;
    replies.push(body);
    const receipt = { ...body, state: 'completed', turnId: 'turn-' + ++cursor, error: null };
    receipts.push(receipt);
    return json(response, receipt);
  }
  return json(response, { error: 'Unsupported fixture route' }, 404);
});
await mkdir('.local', { recursive: true });
await new Promise((resolve, reject) => {
  fixture.once('error', reject);
  fixture.listen(8796, '127.0.0.1', resolve);
});
const child = spawn(
  process.execPath,
  [
    'scripts/mobile/native-ios-cases.mjs',
    'testMobilePlanModeAndPlanActions',
    'testUnifiedComposerStopResumeAndRestart',
  ],
  {
    stdio: 'inherit',
    env: {
      ...process.env,
      IOS_DERIVED_DATA_PATH: process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios-plan',
      IOS_RESULT_BUNDLE_PATH:
        process.env.IOS_RESULT_BUNDLE_PATH ?? `.local/plan-mode-${Date.now()}.xcresult`,
    },
  },
);
try {
  const code = await new Promise((resolve, reject) => {
    child.once('close', resolve);
    child.once('error', reject);
  });
  assert.equal(code, 0);
  assert.equal(replies.length, 4);
  assert.equal(replies[0].text, 'Focus on the mobile UI first.');
  assert.equal(replies[0].collaborationMode, 'plan');
  assert.equal(replies[1].collaborationMode, 'default');
  assert.equal(replies[2].collaborationMode, 'plan');
  assert.equal(replies[3].collaborationMode, 'default');
  console.log(
    JSON.stringify({ suite: 'plan-mode-ios', passed: 2, failed: 0, realRuntimeVerified: false }),
  );
} finally {
  child.kill('SIGTERM');
  fixture.closeAllConnections();
  await new Promise((done) => fixture.close(done));
}
