import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { spawn } from 'node:child_process';
import { resolve, join } from 'node:path';
const device = process.env.IOS_SIMULATOR_ID;
assert(device, 'Select the dedicated chat-actions simulator');
const nonce = randomUUID().slice(0, 8),
  directory = resolve('.local/chat-search-fixture', nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
const hostId = 'search-fixture-host',
  threadId = 'search-fixture-thread';
const target = { agentId: 'codex', hostId, workspaceId: hostId, runtimeId: 'desktop-runtime' };
const threads = [threadId, 'search-second-thread'].map((id) => ({
  id,
  hostId,
  projectId: null,
  title: id === threadId ? 'Chat Search Fixture' : 'Other Search Target',
  state: 'unknown',
}));
const latest = Array.from({ length: 100 }, (_, i) => ({
  id: 'latest-' + i,
  role: 'assistant',
  text: 'Latest readable message ' + i,
}));
const older = [
  {
    id: 'older-compaction',
    role: 'tool',
    kind: 'contextCompaction',
    text: 'COMPACTION_SEARCH_' + nonce,
    status: 'completed',
  },
  {
    id: 'older-tool',
    role: 'tool',
    text: 'printf test',
    kind: 'commandExecution',
    detail:
      'PREVIOUS_PAGE_TOOL_MARKER_' + nonce + '\n' + 'large readable tool output\n'.repeat(10000),
  },
  {
    id: 'older-attachment',
    role: 'user',
    text: 'Earlier attachment',
    files: ['附件资料-' + nonce + '.pdf'],
  },
  ...Array.from({ length: 60 }, (_, i) => ({
    id: 'older-' + i,
    role: 'assistant',
    text: 'Earlier readable message ' + i,
  })),
];
let failure = false,
  delay = false,
  menuState = 'idle',
  menuOwner = true;
const pinned = new Set();
const requests = [];
let rejectLifecycle = false;
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const json = (status, result) => {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(result));
  };
  if (req.headers.origin) return json(403, {});
  if (req.method === 'POST' && url.pathname === '/v1/threads/actions') {
    if (req.headers.authorization !== 'Bearer chat-search-fixture-key') return json(401, {});
    let body = '';
    for await (const part of req) body += part;
    const action = JSON.parse(body);
    assert.equal(action.hostId, hostId);
    assert.equal(action.threadId, threadId);
    assert.deepEqual(action.target, target);
    assert(['pin', 'unpin', 'archive', 'unarchive', 'delete'].includes(action.action));
    if (action.action === 'pin') pinned.add(threadId);
    else if (action.action === 'unpin') pinned.delete(threadId);
    requests.push({ path: url.pathname, action: action.action, menuState });
    if (rejectLifecycle && action.action === 'archive') {
      rejectLifecycle = false;
      return json(200, {
        ...action,
        phase: 'rejected',
        error:
          '电脑端拒绝了此次操作：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。',
      });
    }
    if (action.action === 'archive') threads[0].archived = true;
    if (action.action === 'unarchive') threads[0].archived = false;
    if (action.action === 'delete') threads.splice(0, 1);
    return json(200, { ...action, phase: 'confirmed' });
  }
  if (req.method !== 'GET') return json(403, {});
  if (url.pathname === '/fixture/menu-state') {
    menuState = url.searchParams.get('state');
    menuOwner = menuState !== 'unloaded';
    if (!menuOwner) menuState = 'idle';
    threads[0].lastTurnStatus = ['completed', 'interrupted'].includes(menuState)
      ? menuState
      : undefined;
    threads[0].state = ['completed', 'interrupted'].includes(menuState) ? 'idle' : menuState;
    return json(200, {});
  }
  if (url.pathname === '/fixture/reject-lifecycle') {
    rejectLifecycle = true;
    return json(200, {});
  }
  if (url.pathname === '/')
    return json(200, {
      endpoint: 'http://127.0.0.1:' + server.address().port,
      token: 'chat-search-fixture-key',
      hostId,
      threadId,
      threadTitle: 'Chat Search Fixture',
      nonce,
      secondEndpoint: '',
      secondToken: '',
      secondHostId: '',
      delayedEndpoint: '',
    });
  if (url.pathname === '/fixture/fail') {
    failure = true;
    return json(200, {});
  }
  if (url.pathname === '/fixture/delay') {
    failure = false;
    delay = true;
    return json(200, {});
  }
  if (req.headers.authorization !== 'Bearer chat-search-fixture-key') return json(401, {});
  requests.push({
    path: url.pathname,
    threadId: url.searchParams.get('threadId'),
    cursor: url.searchParams.get('cursor'),
  });
  if (url.pathname === '/healthz')
    return json(200, {
      service: 'host-agent',
      status: 'ok',
      protocolVersion: '0.1',
      capabilities: ['health', 'history'],
    });
  if (url.pathname === '/v1/workspace')
    return json(200, {
      hostId,
      name: 'Search fixture',
      connectionMode: 'desktop-runtime',
      clientType: { id: 'codex', name: 'Codex' },
      projects: [],
      threads,
      capabilities: ['health', 'history', 'threads:manage'],
      pinnedThreadIds: [...pinned],
      readTarget: target,
      nextThreadCursor: null,
      nextProjectCursor: null,
    });
  if (['/v1/history', '/v1/threads/search-history'].includes(url.pathname)) {
    const id = url.searchParams.get('threadId');
    if (id === 'search-second-thread')
      return json(200, {
        hostId,
        threadId: id,
        readTarget: target,
        thread: threads[1],
        messages: [
          { id: 'second-message', role: 'assistant', text: 'SECOND_TARGET_ONLY', files: [] },
        ],
        nextCursor: null,
      });
    if (id !== threadId) return json(404, {});
    if (url.pathname.includes('search-history')) {
      if (failure) return json(503, { error: 'Synthetic history read failure' });
      if (delay) await new Promise((done) => setTimeout(done, 8000));
    }
    const more = url.searchParams.get('cursor') === 'older-page';
    return json(200, {
      hostId,
      threadId,
      readTarget: target,
      thread: threads[0],
      runtime: {
        state: threads[0].state,
        turnId: ['running', 'waiting-for-approval'].includes(menuState)
          ? 'active-fixture-turn'
          : null,
        plan: [],
        approvals: [],
        receipts: [],
        canControl: menuOwner,
      },
      messages: (more ? older : latest).map((message) => ({ files: [], ...message })),
      nextCursor: more ? null : 'older-page',
    });
  }
  json(404, {});
});
const source = {};
for (const file of [
  'apps/ios/Sources/RootView.swift',
  'apps/ios/Sources/SessionStore.swift',
  'apps/ios/Sources/Models.swift',
  'apps/ios/Tests/CodexManageUITests.swift',
])
  source[file] = createHash('sha256')
    .update(await readFile(file))
    .digest('hex');
let child;
try {
  await new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(8820, '127.0.0.1', done);
  });
  const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
  child = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      'platform=iOS Simulator,id=' + device,
      '-derivedDataPath',
      '.build/ios',
      '-resultBundlePath',
      join(directory, 'native.xcresult'),
      '-collect-test-diagnostics',
      'never',
      '-parallel-testing-enabled',
      'NO',
      '-only-testing:CodexManageUITests/CodexManageUITests/' +
        (process.env.CHAT_MENU_FIXTURE === '1'
          ? 'testChatMenuRunningPinFixture'
          : 'testChatSearchPagedHistoryFixture'),
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'test',
    ],
    { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
  );
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  const code = await new Promise((done, reject) => {
    child.on('error', reject);
    child.on('exit', done);
  });
  await writeFile(join(directory, 'requests.json'), JSON.stringify(requests, null, 2), {
    mode: 0o600,
  });
  assert.equal(code, 0, 'Native search fixture failed');
  if (process.env.CHAT_MENU_FIXTURE === '1') {
    assert.deepEqual(
      requests
        .filter((r) => r.path === '/v1/threads/actions' && ['pin', 'unpin'].includes(r.action))
        .map((r) => [r.action, r.menuState]),
      [
        ['pin', 'running'],
        ['unpin', 'running'],
        ['pin', 'waiting-for-approval'],
        ['unpin', 'waiting-for-approval'],
      ],
    );
    const lifecycle = requests.filter(
      (r) => r.path === '/v1/threads/actions' && !['pin', 'unpin'].includes(r.action),
    );
    assert.deepEqual(
      lifecycle.map((r) => [r.action, r.menuState]),
      [
        ...[
          'running',
          'waiting-for-approval',
          'idle',
          'unloaded',
          'failed',
          'completed',
          'interrupted',
          'unknown',
        ].flatMap((state) => [
          ['archive', state === 'unloaded' ? 'idle' : state],
          ['unarchive', state === 'unloaded' ? 'idle' : state],
        ]),
        ['archive', 'unknown'],
        ['delete', 'unknown'],
      ],
    );
  } else {
    assert(requests.some((r) => r.path.includes('search-history') && r.cursor === 'older-page'));
    assert(requests.some((r) => r.path === '/v1/history' && r.threadId === 'search-second-thread'));
  }
  for (const [file, hash] of Object.entries(source))
    assert.equal(
      createHash('sha256')
        .update(await readFile(file))
        .digest('hex'),
      hash,
      'Source changed during fixture',
    );
  await writeFile(
    join(directory, 'report.json'),
    JSON.stringify({ kind: 'native-synthetic-fixture', passed: true, source }, null, 2),
    { mode: 0o600 },
  );
  console.log(JSON.stringify({ kind: 'native-synthetic-fixture', directory, passed: true }));
} finally {
  if (child?.exitCode === null) child.kill('SIGTERM');
  server.closeAllConnections();
  server.close();
}
