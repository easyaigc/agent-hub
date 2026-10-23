import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

// Exercises native settings and their HTTP intent without launching Codex.
const hostId = 'generation-fixture';
const threadId = 'generation-primary';
const secondaryId = 'generation-secondary';
const endpoint = 'http://127.0.0.1:8797';
const token = randomBytes(32).toString('base64url');
const capabilities = [
  'reply',
  'history:read',
  'events',
  'plan-mode',
  'models:read',
  'generation-settings',
  'generation-speed',
  'threads:create',
  'threads:create:projectless',
  'desktop:creations',
  'desktop:creations:projectless',
];
const models = [
  {
    id: 'fixture-quality',
    name: 'Fixture Quality',
    isDefault: true,
    defaultEffort: 'xhigh',
    supportedEfforts: ['low', 'medium', 'high', 'xhigh'],
    defaultSpeed: 'standard',
    supportedSpeeds: ['standard'],
  },
  {
    id: 'fixture-fast',
    name: 'Fixture Fast',
    isDefault: false,
    defaultEffort: 'medium',
    supportedEfforts: ['low', 'medium'],
    defaultSpeed: 'standard',
    supportedSpeeds: ['standard', 'fast'],
  },
];
const threads = [threadId, secondaryId].map((id) => ({
  id,
  hostId,
  projectId: null,
  title: id === threadId ? 'Generation primary' : 'Generation secondary',
  state: 'idle',
}));
const settings = new Map(
  threads.map((t) => [
    t.id,
    { model: 'fixture-quality', effort: 'xhigh', permissionMode: 'read-only', speed: 'standard' },
  ]),
);
let cursor = 1,
  writable = true;
const replies = [];
const creations = new Map();
const snapshot = (id) => ({
  hostId,
  threadId: id,
  thread: threads.find((t) => t.id === id),
  nextCursor: null,
  messages: [
    {
      id: 'fixture-answer',
      role: 'assistant',
      text: replies.some((r) => r.threadId === id)
        ? id === threadId
          ? 'SETTINGS_APPLIED'
          : 'CREATED_' + id
        : 'Choose settings for this chat.',
      files: [],
    },
  ],
  runtime: {
    state: 'idle',
    turnId: null,
    plan: [],
    approvals: [],
    receipts: [],
    canControl: writable,
    collaborationMode: 'plan',
    generationSettings: writable ? settings.get(id) : null,
  },
});
const json = (res, value, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(value));
};
const fixture = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, endpoint);
    if (url.pathname === '/')
      return json(res, {
        endpoint,
        token,
        hostId,
        threadId,
        threadTitle: threads[0].title,
        nonce: 'settings',
        secondEndpoint: endpoint,
        secondToken: token,
        secondHostId: hostId,
        delayedEndpoint: endpoint,
      });
    assert.equal(req.headers.authorization, 'Bearer ' + token);
    assert.equal(url.searchParams.get('hostId') ?? hostId, hostId);
    if (url.pathname === '/readonly') {
      writable = false;
      cursor++;
      return json(res, { ok: true });
    }
    if (url.pathname === '/v1/models') return json(res, { models });
    if (url.pathname === '/v1/workspace')
      return json(res, {
        hostId,
        name: 'Generation fixture',
        connectionMode: 'desktop-runtime',
        clientType: { id: 'codex', name: 'Codex' },
        capabilities,
        projects: [{ id: 'generation-project', hostId, name: 'Generation project', roots: [] }],
        threads,
        nextProjectCursor: null,
        nextThreadCursor: null,
      });
    const id = url.searchParams.get('threadId');
    if (url.pathname === '/v1/history') {
      assert(settings.has(id));
      return json(res, snapshot(id));
    }
    if (url.pathname === '/v1/events') {
      assert(settings.has(id));
      await new Promise((done) => setTimeout(done, 1500));
      return json(res, {
        hostId,
        epoch: 'generation-epoch',
        cursor,
        reset: false,
        ready: true,
        capabilities,
        events:
          Number(url.searchParams.get('after')) !== cursor
            ? [{ threadId: id, snapshot: snapshot(id) }]
            : [],
      });
    }
    if (url.pathname === '/v1/desktop/creations' && req.method === 'POST') {
      let bytes = '';
      for await (const chunk of req) bytes += chunk;
      const body = JSON.parse(bytes);
      assert.equal(body.hostId, hostId);
      assert.equal(body.model, 'fixture-fast');
      assert.equal(body.effort, 'low');
      assert.equal(body.permissionMode, 'full-access');
      assert.equal(body.speed, body.text === 'Remembered standard' ? 'standard' : 'fast');
      const operation = {
        ...body,
        phase: 'prepared',
        threadId: null,
        thread: null,
        error: null,
        uploaded: [],
      };
      creations.set(body.clientOperationId, operation);
      return json(res, operation);
    }
    if (url.pathname === '/v1/desktop/creations/submit' && req.method === 'POST') {
      let bytes = '';
      for await (const chunk of req) bytes += chunk;
      const body = JSON.parse(bytes);
      const operation = creations.get(body.clientOperationId);
      assert(operation);
      const id = 'generation-created-' + (threads.length - 1);
      const thread = { id, hostId, projectId: operation.projectId, title: id, state: 'idle' };
      threads.unshift(thread);
      settings.set(id, {
        model: operation.model,
        effort: operation.effort,
        permissionMode: operation.permissionMode,
        speed: operation.speed,
      });
      replies.push({ ...operation, threadId: id });
      Object.assign(operation, { threadId: id, thread, phase: 'confirmed' });
      cursor++;
      return json(res, operation);
    }
    if (url.pathname === '/v1/desktop/creations')
      return json(res, creations.get(url.searchParams.get('id')));
    if (url.pathname === '/v1/replies' && req.method === 'POST') {
      let bytes = '';
      for await (const chunk of req) bytes += chunk;
      const body = JSON.parse(bytes);
      assert.equal(body.hostId, hostId);
      assert(settings.has(body.threadId));
      assert.equal(body.mode, 'next-turn');
      if (body.threadId === threadId) assert.equal(body.collaborationMode, 'plan');
      assert(writable);
      assert.equal(body.model, 'fixture-fast');
      assert.equal(body.effort, 'low');
      assert.equal(body.permissionMode, 'full-access');
      assert.equal(body.speed, body.text === 'Remembered standard' ? 'standard' : 'fast');
      replies.push(body);
      settings.set(body.threadId, {
        model: body.model,
        effort: body.effort,
        permissionMode: body.permissionMode,
        speed: body.speed,
      });
      cursor++;
      return json(res, { ...body, state: 'completed', turnId: 'settings-turn', error: null });
    }
    return json(res, { error: 'Unknown fixture route' }, 404);
  } catch (error) {
    json(res, { error: error.message }, 500);
  }
});
await mkdir('.local', { recursive: true });
await new Promise((resolve, reject) => {
  fixture.once('error', reject);
  fixture.listen(8797, '127.0.0.1', resolve);
});
const child = spawn(
  'xcodebuild',
  [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    process.env.IOS_SIMULATOR_ID
      ? `platform=iOS Simulator,id=${process.env.IOS_SIMULATOR_ID}`
      : 'platform=iOS Simulator,name=iPhone 17 Pro',
    '-derivedDataPath',
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios-generation',
    '-resultBundlePath',
    `.local/generation-settings-${Date.now()}.xcresult`,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    '-only-testing:CodexManageUITests/CodexManageUITests/testMobileGenerationSettings',
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
  assert.equal(replies.length, 3);
  console.log(
    JSON.stringify({
      suite: 'generation-settings-ios',
      passed: 1,
      failed: 0,
      realDesktopTurnVerified: false,
    }),
  );
} finally {
  child.kill('SIGTERM');
  fixture.closeAllConnections();
  await new Promise((resolve) => fixture.close(resolve));
}
