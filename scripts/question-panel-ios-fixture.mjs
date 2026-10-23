import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Native layout and draft routing only; this does not execute a desktop model.
const endpoint = 'http://127.0.0.1:8803';
const hostId = 'question-ui-fixture';
const threadId = 'question-ui-thread';
const token = randomBytes(32).toString('base64url');
const answers = new Map();
let cursor = 1;
let activated = false;
const questions = [
  {
    id: 'short',
    question: '请选择交付格式',
    options: [
      { label: '简洁版', description: '保留必要信息。' },
      { label: '详细版', description: '补充背景与说明。' },
    ],
  },
  {
    id: 'long',
    question:
      '请补充长问题的回复。' +
      '这是一段用于验证长问题、屏幕边界和键盘避让的合成说明，回复框和提交按钮应始终可见。'.repeat(
        24,
      ),
  },
  { id: 'other', question: '此问题用于检查切题时的听写隔离。' },
];
const thread = {
  id: threadId,
  hostId,
  projectId: 'question-ui-project',
  title: threadId,
  state: 'idle',
};
const snapshot = () => ({
  hostId,
  threadId,
  thread,
  nextCursor: null,
  messages: Array.from({ length: 75 }, (_, index) => ({
    id: 'history-' + index,
    role: 'assistant',
    text: '合成历史 ' + index + '：滚动阅读时，待回答问题入口应保持在输入栏上方。',
    files: [],
    status: 'completed',
  })),
  runtime: {
    state: 'idle',
    turnId: null,
    plan: [],
    receipts: [],
    canControl: true,
    approvals: questions
      .filter((question) => activated && !answers.has(question.id))
      .map((question) => ({
        id: question.id,
        threadId,
        turnId: 'question-ui-turn',
        kind: 'question',
        interactionMode: 'async',
        title: question.question,
        detail: '',
        decisions: ['skip'],
        questions: [question],
      })),
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
      nonce: 'question-ui',
      secondEndpoint: endpoint,
      secondHostId: hostId,
      secondToken: token,
      delayedEndpoint: endpoint,
    });
  if (url.pathname === '/status') return json(response, { answers: answers.size });
  if (request.headers.authorization !== 'Bearer ' + token)
    return json(response, { error: 'Fixture auth required' }, 401);
  if (url.pathname === '/activate') {
    activated = true;
    cursor++;
    return json(response, { ok: true });
  }
  if (url.pathname === '/v1/workspace')
    return json(response, {
      hostId,
      name: 'Question UI fixture',
      connectionMode: 'desktop-runtime',
      capabilities: ['history:read', 'events', 'approvals:respond'],
      projects: [{ id: thread.projectId, hostId, name: 'Question UI fixture', roots: [] }],
      threads: [thread],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
  if (url.pathname === '/v1/approvals') {
    let bytes = '';
    for await (const part of request) bytes += part;
    const body = JSON.parse(bytes);
    assert.equal(body.hostId, hostId);
    assert.equal(body.threadId, threadId);
    assert.equal(body.turnId, 'question-ui-turn');
    assert(questions.some((question) => question.id === body.approvalId));
    assert(!answers.has(body.approvalId), 'Never submit an answer twice');
    if (body.approvalId === 'short') assert.deepEqual(body.answers, { short: ['简洁版'] });
    if (body.approvalId === 'long') assert(body.answers.long[0].includes('语音输入测试'));
    if (body.approvalId === 'other') assert.equal(body.decision, 'skip');
    answers.set(body.approvalId, body);
    cursor++;
    return json(response, { ok: true });
  }
  assert.equal(url.searchParams.get('hostId'), hostId);
  assert.equal(url.searchParams.get('threadId'), threadId);
  if (url.pathname === '/v1/history') return json(response, snapshot());
  if (url.pathname === '/v1/events') {
    await new Promise((done) => setTimeout(done, 200));
    const reset = url.searchParams.get('epoch') !== 'question-ui-epoch';
    return json(response, {
      hostId,
      epoch: 'question-ui-epoch',
      cursor,
      ready: true,
      reset,
      events:
        reset || Number(url.searchParams.get('after')) !== cursor
          ? [{ threadId, kind: 'approval', snapshot: snapshot() }]
          : [],
    });
  }
  return json(response, { error: 'Unsupported fixture route' }, 404);
});
assert(process.env.IOS_SIMULATOR_ID, 'Use a dedicated simulator');
await mkdir('.local', { recursive: true });
await new Promise((done) => server.listen(8803, '127.0.0.1', done));
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
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios-question-panel',
    '-resultBundlePath',
    process.env.IOS_RESULT_BUNDLE_PATH ?? `.local/question-panel-${Date.now()}.xcresult`,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    '-only-testing:CodexManageUITests/CodexManageUITests/testQuestionPanelLayoutAndDictation',
    '-only-testing:CodexManageUITests/CodexManageUITests/testDictationCancelFinishAndSend',
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
  assert.equal(answers.size, 3);
  console.log(
    'Native question visibility, dictation draft isolation and single submissions passed (fixture).',
  );
} finally {
  child.kill('SIGTERM');
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
