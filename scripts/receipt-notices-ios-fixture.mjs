import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Synthetic presentation/regression evidence, never original-desktop execution evidence.
const endpoint = 'http://127.0.0.1:8802';
const hostId = 'receipt-fixture';
const threadId = 'receipt-primary';
const token = randomBytes(32).toString('base64url');
const states = new Set([
  'failed',
  'submitted',
  'task-failed',
  'desktop-continued',
  'uncertain-desktop-continued',
  'uncertain-with-success',
  'new-failed',
  'question',
]);
const visited = new Set();
let state = 'failed',
  cursor = 1,
  answered = false;
const thread = (id) => ({ id, hostId, projectId: 'receipt-project', title: id, state: 'idle' });
const receipt = (id, status, target = threadId, turnId = null) => ({
  clientMessageId: id,
  hostId,
  threadId: target,
  state: status,
  turnId,
  error:
    status === 'failed'
      ? turnId
        ? 'Remote compaction connection failed'
        : '本次提交被拒绝'
      : null,
});
const snapshot = (target) => ({
  hostId,
  threadId: target,
  thread: thread(target),
  nextCursor: null,
  messages: [
    ...(['desktop-continued', 'uncertain-desktop-continued'].includes(state)
      ? [
          {
            id: 'old-task-failure',
            role: 'tool',
            kind: 'turnFailure',
            status: 'failed',
            text: 'Remote compaction connection failed',
            files: [],
          },
        ]
      : []),
    {
      id: 'status',
      role: 'assistant',
      text: answered ? 'QUESTION_ANSWERED' : 'RECEIPT_STATE_' + state,
      files: [],
      status: 'completed',
    },
  ],
  runtime: {
    state: state === 'question' && !answered ? 'waiting-for-approval' : 'idle',
    turnId: state === 'question' && !answered ? 'question-turn' : null,
    plan: [],
    canControl: true,
    receipts:
      target !== threadId
        ? [receipt('failed', 'failed', target)]
        : state === 'question'
          ? []
          : ['desktop-continued', 'uncertain-desktop-continued'].includes(state)
            ? [
                {
                  ...receipt('old-task', 'failed', target, 'failed-turn'),
                  supersededByTurnId: 'desktop-turn',
                },
                ...(state === 'uncertain-desktop-continued'
                  ? [{ ...receipt('unknown', 'uncertain'), supersededByTurnId: 'desktop-turn' }]
                  : []),
              ]
            : state === 'new-failed'
              ? [
                  receipt('success', 'completed', target, 'success-turn'),
                  receipt('new-failure', 'failed'),
                ]
              : state === 'task-failed'
                ? [receipt('failed', 'failed', target, 'failed-turn')]
                : [
                    receipt('failed', 'failed'),
                    ...(state === 'uncertain-with-success'
                      ? [receipt('unknown', 'uncertain')]
                      : []),
                    ...(['submitted', 'uncertain-with-success'].includes(state)
                      ? [receipt('success', 'submitted', target, 'success-turn')]
                      : []),
                  ],
    approvals:
      state === 'question' && !answered
        ? [
            {
              id: 'question',
              threadId: target,
              turnId: 'question-turn',
              kind: 'question',
              title: '需要你的回复',
              detail: '',
              decisions: [],
              questions: [
                {
                  id: 'format',
                  question: '请选择交付格式',
                  options: [
                    { label: '简洁版', description: '保留必要信息。' },
                    { label: '详细版', description: '补充背景与说明。' },
                  ],
                },
              ],
            },
          ]
        : [],
  },
});
const json = (response, value, code = 200) => {
  response.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
};
const server = createServer(async (request, response) => {
  const url = new URL(request.url, endpoint);
  if (url.pathname === '/')
    return json(response, {
      endpoint,
      hostId,
      threadId,
      threadTitle: threadId,
      token,
      nonce: 'receipts',
      secondEndpoint: endpoint,
      secondHostId: hostId,
      secondToken: token,
      delayedEndpoint: endpoint,
    });
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, { error: 'Fixture auth required' }, 401);
  if (url.pathname === '/state') {
    state = url.searchParams.get('value');
    assert(states.has(state));
    visited.add(state);
    cursor++;
    return json(response, { state });
  }
  if (url.pathname === '/v1/workspace')
    return json(response, {
      hostId,
      name: 'Receipt fixture',
      connectionMode: 'desktop-runtime',
      capabilities: ['history:read', 'events', 'approvals:respond'],
      projects: [{ id: 'receipt-project', hostId, name: 'Receipt fixture', roots: [] }],
      threads: [thread(threadId), thread('receipt-secondary')],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  if (url.pathname === '/v1/approvals') {
    let bytes = '';
    for await (const part of request) bytes += part;
    const body = JSON.parse(bytes);
    assert.equal(body.hostId, hostId);
    assert.equal(body.threadId, threadId);
    assert.equal(body.approvalId, 'question');
    assert.equal(body.turnId, 'question-turn');
    assert.deepEqual(body.answers, { format: ['简洁版', '补充说明'] });
    answered = true;
    cursor++;
    return json(response, { ok: true });
  }
  assert.equal(url.searchParams.get('hostId'), hostId);
  const target = url.searchParams.get('threadId');
  if (url.pathname === '/v1/history') return json(response, snapshot(target));
  if (url.pathname === '/v1/events') {
    const reset = url.searchParams.get('epoch') !== 'receipt-epoch';
    const after = Number(url.searchParams.get('after'));
    if (!reset && after === cursor) {
      // Match the Host's event wait: return changed state immediately, and
      // avoid continuously publishing empty batches into the native UI.
      await new Promise((done) => {
        const deadline = Date.now() + 5000;
        let timer;
        const finish = () => {
          clearTimeout(timer);
          response.off('close', finish);
          done();
        };
        const probe = () => {
          if (response.destroyed || after !== cursor || Date.now() >= deadline) finish();
          else timer = setTimeout(probe, 50);
        };
        response.once('close', finish);
        probe();
      });
    }
    if (response.destroyed) return;
    return json(response, {
      hostId,
      epoch: 'receipt-epoch',
      cursor,
      ready: true,
      reset,
      events:
        reset || after !== cursor
          ? [{ threadId: target, kind: 'receipt', snapshot: snapshot(target) }]
          : [],
    });
  }
  return json(response, { error: 'Unsupported fixture route' }, 404);
});
await mkdir('.local', { recursive: true });
await new Promise((done) => server.listen(8802, '127.0.0.1', done));
const destination = process.env.IOS_SIMULATOR_ID
  ? `platform=iOS Simulator,id=${process.env.IOS_SIMULATOR_ID}`
  : 'platform=iOS Simulator,name=iPhone 17 Pro';
const child = spawn(
  'xcodebuild',
  [
    '-project',
    process.env.IOS_PROJECT_PATH ?? 'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    destination,
    '-derivedDataPath',
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios-receipts',
    '-resultBundlePath',
    process.env.IOS_RESULT_BUNDLE_PATH ?? `.local/receipt-notices-${Date.now()}.xcresult`,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    '-only-testing:CodexManageUITests/CodexManageUITests/testReceiptNoticesAndQuestionAppearance',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
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
  assert.equal(answered, true);
  assert.equal(visited.size, states.size - 1);
  console.log(
    'Native receipt notices, target isolation, persistence and question presentation passed (fixture).',
  );
} finally {
  child.kill('SIGTERM');
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
