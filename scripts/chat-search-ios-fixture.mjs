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
  directory = process.env.CHAT_SEARCH_ACCEPTANCE_OUTPUT
    ? resolve(process.env.CHAT_SEARCH_ACCEPTANCE_OUTPUT)
    : resolve('.local/chat-search-fixture', nonce);
await mkdir(directory, { recursive: true, mode: 0o700 });
const hostId = 'search-fixture-host',
  threadId = 'search-fixture-thread';
const target = {
  agentId: 'codex',
  hostId,
  workspaceId: hostId,
  runtimeId:
    process.env.CHAT_MENU_FIXTURE === 'queue-controls'
      ? 'queue-controls-' + nonce
      : 'desktop-runtime',
};
const queueControlsMode = process.env.CHAT_MENU_FIXTURE === 'queue-controls';
const queueMode = queueControlsMode || process.env.CHAT_MENU_FIXTURE === 'queue-metadata';
const queueActions = [],
  queueRemoved = new Set();
let queueRunning = false,
  releaseQueueAction;
let queueControlFailure = null;
let queueSyncErrors = true;
const queueRawRequests = [];
const batchRecordsMode = process.env.CHAT_MENU_FIXTURE === 'batch-records';
const conflictMode = process.env.CHAT_MENU_FIXTURE === 'conflicts';
const batchMode = conflictMode || batchRecordsMode;
const batchOperations = new Map();
const batchQueries = [];
const countMode = process.env.CHAT_MENU_FIXTURE === 'counts';
const recordMode = conflictMode || process.env.CHAT_MENU_FIXTURE === 'records';
const recordOperations = new Map();
const recordQueries = [];
const threads = [threadId, 'search-second-thread'].map((id) => ({
  id,
  hostId,
  projectId: null,
  title: id === threadId ? 'Chat Search Fixture' : 'Other Search Target',
  state: 'unknown',
}));
if (batchRecordsMode)
  threads.push(
    ...Array.from({ length: 11 }, (_, index) => ({
      id: 'batch-record-' + String(index).padStart(2, '0'),
      hostId,
      projectId: null,
      title: 'BATCH_RECORD_' + String(index).padStart(2, '0') + '_' + nonce,
      state: 'idle',
    })),
  );
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
let holdNextCatalog = false,
  catalogHeld = false,
  oldReplyReleased = false,
  freshCatalogReadsWhileHeld = 0;
let historyReadsAfterRelease = 0;
let releaseCatalog;
let holdAllCatalog = false;
const heldCatalogReplies = [];
const catalogObservations = [];
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (queueMode) queueRawRequests.push({ method: req.method, path: url.pathname });
  const json = (status, result) => {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(result));
  };
  if (req.headers.origin) return json(403, {});
  if (queueControlsMode && url.pathname === '/fixture/queue-controls/stats')
    return json(200, { actions: queueActions });
  if (queueControlsMode && url.pathname === '/fixture/queue-controls/release') {
    releaseQueueAction?.();
    return json(200, {});
  }
  if (queueControlsMode && url.pathname === '/fixture/queue-controls/running') {
    queueRunning = true;
    return json(200, {});
  }
  if (queueControlsMode && req.method === 'POST') {
    try {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      queueActions.push({ path: url.pathname, body });
      assert(['/v1/queue/execute', '/v1/queue/cancel'].includes(url.pathname));
      assert.deepEqual(JSON.parse(url.searchParams.get('target')), target);
      assert.equal(body.hostId, hostId);
      assert.equal(body.threadId, threadId);
      assert(['native-original', 'old-compatible', 'file-only'].includes(body.clientMessageId));
      assert.deepEqual(
        Object.keys(body).sort(),
        [
          'hostId',
          'threadId',
          'clientMessageId',
          ...(queueRunning ? ['expectedTurnId'] : []),
        ].sort(),
      );
      if (queueRunning) assert.equal(body.expectedTurnId, 'queue-original-run');
      if (body.clientMessageId === 'native-original')
        await new Promise((done) => {
          releaseQueueAction = done;
        });
      queueRemoved.add(body.clientMessageId);
      return json(200, { ok: true });
    } catch (error) {
      queueControlFailure = String(error.stack);
      return json(500, { error: 'Owned queue fixture contract rejected the attempt' });
    }
  }
  if (queueMode && url.pathname === '/fixture/queue-clear') {
    queueSyncErrors = false;
    return json(200, {});
  }
  if (url.pathname === '/fixture/hold-catalog-all') {
    holdAllCatalog = true;
    return json(200, {});
  }
  if (url.pathname === '/fixture/hold-catalog') {
    holdNextCatalog = true;
    return json(200, {});
  }
  if (url.pathname === '/fixture/catalog-state')
    return json(200, {
      held: catalogHeld,
      freshCatalogReadsWhileHeld,
      oldReplyReleased,
      historyReadsAfterRelease,
    });
  if (url.pathname === '/fixture/release-catalog') {
    holdAllCatalog = false;
    for (const done of heldCatalogReplies.splice(0)) done();
    releaseCatalog?.();
    return json(200, {});
  }
  if (batchMode && url.pathname === '/fixture/conflict-stats')
    return json(200, {
      single: [...recordOperations.values()],
      batches: [...batchOperations.values()],
      batchQueries,
    });
  if (batchMode && url.pathname === '/v1/workspace-management') {
    assert.equal(req.headers.authorization, 'Bearer chat-search-fixture-key');
    let request;
    if (req.method === 'POST') {
      let body = '';
      for await (const part of req) body += part;
      request = JSON.parse(body);
      assert.equal(request.hostId, hostId);
      assert.deepEqual(request.target, target);
      assert.equal(request.action, 'threads:archive');
      if (conflictMode) assert.deepEqual(request.ids, ['search-second-thread']);
      else {
        assert.equal(request.ids.length, 1);
        assert(/^batch-record-(?:0[0-9]|10)$/.test(request.ids[0]));
        assert(
          ![...batchOperations.values()].some((old) => old.ids.includes(request.ids[0])),
          'No repeat target during eleven original batches',
        );
      }
      assert(!batchOperations.has(request.clientOperationId), 'No replay of original batch');
      batchOperations.set(request.clientOperationId, {
        ...request,
        items: request.ids.map((id) => {
          const state =
            batchRecordsMode && id !== 'batch-record-00' ? 'completed' : 'outcome-unknown';
          if (state === 'completed') threads.find((row) => row.id === id).archived = true;
          return { id, state, error: null };
        }),
      });
    } else {
      assert.equal(req.method, 'GET');
      request = { clientOperationId: url.searchParams.get('id') };
      assert.equal(url.searchParams.get('hostId'), hostId);
      if (batchRecordsMode) assert.deepEqual(JSON.parse(url.searchParams.get('target')), target);
      batchQueries.push(request.clientOperationId);
    }
    const original = batchOperations.get(request.clientOperationId);
    assert(original);
    return json(200, original);
  }
  if (recordMode && url.pathname === '/fixture/record-stats')
    return json(200, { posts: [...recordOperations.values()], queries: recordQueries });
  if (recordMode && req.method === 'GET' && url.pathname === '/v1/threads/actions') {
    assert.equal(req.headers.authorization, 'Bearer chat-search-fixture-key');
    const original = recordOperations.get(url.searchParams.get('id'));
    assert(original && original.threadId === url.searchParams.get('threadId'));
    assert.equal(url.searchParams.get('hostId'), hostId);
    if (url.searchParams.has('target'))
      assert.deepEqual(JSON.parse(url.searchParams.get('target')), target);
    recordQueries.push({ threadId: original.threadId, id: original.clientOperationId });
    return json(200, { ...original, phase: 'outcome-unknown', error: null });
  }
  if (req.method === 'POST' && url.pathname === '/v1/threads/actions') {
    if (req.headers.authorization !== 'Bearer chat-search-fixture-key') return json(401, {});
    let body = '';
    for await (const part of req) body += part;
    const action = JSON.parse(body);
    assert.equal(action.hostId, hostId);
    assert(
      recordMode ? threads.some((t) => t.id === action.threadId) : action.threadId === threadId,
    );
    assert.deepEqual(action.target, target);
    assert(['pin', 'unpin', 'archive', 'unarchive', 'delete'].includes(action.action));
    if (recordMode) {
      assert(!recordOperations.has(action.clientOperationId));
      recordOperations.set(action.clientOperationId, action);
      return json(200, { ...action, phase: 'outcome-unknown', error: null });
    }
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
    method: req.method,
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
  if (url.pathname === '/v1/workspace') {
    const catalog = structuredClone({
      hostId,
      name: 'Search fixture',
      connectionMode: 'desktop-runtime',
      clientType: { id: 'codex', name: 'Codex' },
      projects: [],
      threads,
      capabilities: [
        'health',
        'history',
        'threads:manage',
        ...(batchMode ? ['threads:archive:unloaded'] : []),
        ...(queueMode
          ? [
              'reply',
              'commands:target-bound:v1',
              'queue',
              ...(queueControlsMode ? ['queue:execute'] : []),
            ]
          : []),
      ],
      ...(queueMode
        ? {
            agentConnectionState: {
              target,
              phase: 'connected',
              transportId: 'fixture-http',
              processHealthy: true,
              runtimeConnected: true,
              historyReadable: true,
            },
          }
        : {}),
      pinnedThreadIds: [...pinned],
      readTarget: target,
      nextThreadCursor: null,
      nextProjectCursor: null,
    });
    if (holdNextCatalog || holdAllCatalog) {
      holdNextCatalog = false;
      catalogHeld = true;
      catalogObservations.push({
        kind: 'held-original',
        archived: catalog.threads[0]?.archived === true,
      });
      await new Promise((done) => {
        if (holdAllCatalog) heldCatalogReplies.push(done);
        else releaseCatalog = done;
      });
      catalogHeld = false;
      oldReplyReleased = true;
    } else if (catalogHeld) {
      freshCatalogReadsWhileHeld++;
      catalogObservations.push({
        kind: 'fresh-before-release',
        archived: catalog.threads[0]?.archived === true,
      });
    }
    if (!res.destroyed) return json(200, catalog);
    return;
  }
  if (['/v1/history', '/v1/threads/search-history'].includes(url.pathname)) {
    if (url.pathname === '/v1/history' && oldReplyReleased) historyReadsAfterRelease++;
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
        state: queueControlsMode
          ? queueRunning
            ? 'running'
            : 'idle'
          : queueMode
            ? 'idle'
            : threads[0].state,
        turnId: queueControlsMode
          ? queueRunning
            ? 'queue-original-run'
            : null
          : ['running', 'waiting-for-approval'].includes(menuState)
            ? 'active-fixture-turn'
            : null,
        plan: [],
        approvals: [],
        receipts: queueMode ? queueReceipts() : [],
        canControl: menuOwner,
      },
      messages: queueMode
        ? [
            {
              id: queueSyncErrors ? 'queue-errors' : 'queue-clear',
              role: 'assistant',
              text: queueSyncErrors ? 'QUEUE_PHASE_ERRORS' : 'QUEUE_PHASE_CLEAR',
              files: [],
            },
          ]
        : (more ? older : latest).map((message) => ({ files: [], ...message })),
      nextCursor: queueMode ? null : more ? null : 'older-page',
    });
  }
  json(404, {});
});
function queueReceipts() {
  if (queueControlsMode)
    return ['native-original', 'old-compatible', 'file-only']
      .filter((id) => !queueRemoved.has(id))
      .map((id) => ({
        hostId,
        threadId,
        clientMessageId: id,
        state: 'queued',
        turnId: null,
        error: null,
        text:
          id === 'file-only' ? '  ' : id === 'native-original' ? '  Original first input  ' : id,
        queueSource: 'native',
        queuePresent: true,
        attachmentIds: id === 'file-only' ? ['original-file'] : [],
        canExecuteNow: true,
      }));
  const receipt = (id, state = 'queued') => ({
    hostId,
    threadId,
    clientMessageId: id,
    state,
    turnId: state === 'queued' ? null : 'original-turn',
    error: null,
    text: id,
  });
  return [
    {
      ...receipt('native-original'),
      queueSource: 'native',
      queuePresent: true,
      canExecuteNow: false,
    },
    receipt('old-compatible'),
    { ...receipt('legacy-host'), queueSource: 'host' },
    { ...receipt('legacy-disposition'), queueSource: 'native', queueDisposition: 'legacy-review' },
    { ...receipt('removed-native'), queueSource: 'native', queuePresent: false },
    ...[
      ['accepted-sync', 'submitted'],
      ['failed-sync', 'failed'],
    ].map(([id, state]) => ({
      ...receipt(id, state),
      queueSource: 'native',
      supersededByTurnId: 'later-turn',
      ...(queueSyncErrors
        ? {
            queueSyncError:
              id === 'accepted-sync'
                ? 'accepted original client sync failure'
                : 'failed original client sync failure',
          }
        : {}),
    })),
    receipt('later-success', 'completed'),
  ];
}
const source = {};
for (const file of [
  'apps/ios/Sources/RootView.swift',
  'apps/ios/Sources/SharedMobileRules.swift',
  'apps/ios/Sources/NativeChatSearch.swift',
  'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatSearchRules.kt',
  'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatManagementRules.kt',
  'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceSidebarRules.kt',
  'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceArchiveRules.kt',
  'packages/protocol/schemas/codex-mobile-http-v1.schema.json',
  'apps/ios/Resources/en.lproj/Localizable.strings',
  'apps/ios/Resources/zh-Hans.lproj/Localizable.strings',
  'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/MessageProjection.kt',
  'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt',
  'packages/protocol/fixtures/mobile/state-v1.json',
  'scripts/chat-search-ios-fixture.mjs',
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
        (queueMode
          ? queueControlsMode
            ? 'testOriginalQueueActionsPreserveDraftAndRequireExplicitConsentFixture'
            : 'testOriginalClientQueueMetadataFixture'
          : batchRecordsMode
            ? 'testEveryOriginalBatchRecordRemainsReachableFixture'
            : conflictMode
              ? 'testChatManagementBatchConflictFixture'
              : countMode
                ? 'testClientRunningCountsFixture'
                : recordMode
                  ? 'testChatPendingRecordsMenuFixture'
                  : process.env.CHAT_MENU_FIXTURE === 'refresh'
                    ? 'testChatRefreshDuringCatalogFixture'
                    : process.env.CHAT_MENU_FIXTURE === 'catalog'
                      ? 'testChatCatalogManagementFenceFixture'
                      : process.env.CHAT_MENU_FIXTURE === '1'
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
  if (batchMode)
    await writeFile(
      join(directory, 'batch-observations.json'),
      JSON.stringify({ batches: [...batchOperations.values()], batchQueries }, null, 2),
      { mode: 0o600 },
    );
  assert.equal(code, 0, 'Native search fixture failed');
  if (queueControlsMode) {
    assert.equal(queueControlFailure, null);
    assert.equal(queueActions.length, 3);
    assert.deepEqual(
      queueActions.map((r) => r.path),
      ['/v1/queue/execute', '/v1/queue/cancel', '/v1/queue/execute'],
    );
    assert.deepEqual(
      queueActions.map((r) => r.body.clientMessageId),
      ['native-original', 'old-compatible', 'file-only'],
    );
    assert.equal(queueRawRequests.filter((r) => r.method !== 'GET').length, 3);
    await writeFile(
      join(directory, 'queue-control-observations.json'),
      JSON.stringify({ requests: queueRawRequests, actions: queueActions }, null, 2),
      { mode: 0o600 },
    );
  } else if (queueMode) {
    assert(queueRawRequests.length > 0);
    assert(
      queueRawRequests.every((r) => r.method === 'GET'),
      'Queue metadata/dismissal scene writes no commands',
    );
    assert.equal(queueSyncErrors, false);
    await writeFile(
      join(directory, 'queue-observations.json'),
      JSON.stringify(queueRawRequests, null, 2),
      { mode: 0o600 },
    );
  } else if (batchRecordsMode) {
    assert.equal(batchOperations.size, 11);
    const originals = [...batchOperations.values()];
    assert.equal(new Set(originals.map((row) => row.clientOperationId)).size, 11);
    assert.deepEqual(
      originals.map((row) => row.ids[0]),
      Array.from({ length: 11 }, (_, index) => 'batch-record-' + String(index).padStart(2, '0')),
    );
    assert(originals[0].items[0].state === 'outcome-unknown');
    assert(originals.slice(1).every((row) => row.items[0].state === 'completed'));
    assert(
      batchQueries.length >= 2 && batchQueries.every((id) => id === originals[0].clientOperationId),
    );
  } else if (conflictMode) {
    assert.equal(recordOperations.size, 1);
    assert.equal(batchOperations.size, 1);
    assert.deepEqual(
      [...recordOperations.values()].map((r) => [r.action, r.threadId]),
      [['pin', threadId]],
    );
    assert.deepEqual([...batchOperations.values()][0].ids, ['search-second-thread']);
    await writeFile(
      join(directory, 'conflicts.json'),
      JSON.stringify(
        {
          single: [...recordOperations.values()],
          batches: [...batchOperations.values()],
          batchQueries,
        },
        null,
        2,
      ),
      { mode: 0o600 },
    );
  } else if (countMode) {
    assert(requests.every((r) => r.method === 'GET'));
    assert.equal(requests.filter((r) => r.path === '/v1/threads/actions').length, 0);
  } else if (recordMode) {
    assert.equal(recordOperations.size, 2);
    assert.deepEqual(
      [...recordOperations.values()].map((r) => r.action),
      ['pin', 'archive'],
    );
    assert.equal(recordQueries.length, 1);
    assert.equal(recordQueries[0].threadId, threadId);
    assert.equal(recordQueries[0].id, [...recordOperations.values()][0].clientOperationId);
    await writeFile(
      join(directory, 'records.json'),
      JSON.stringify(
        { operations: [...recordOperations.values()], queries: recordQueries },
        null,
        2,
      ),
      { mode: 0o600 },
    );
  } else if (process.env.CHAT_MENU_FIXTURE === 'refresh') {
    assert.equal(requests.filter((r) => r.path === '/v1/threads/actions').length, 0);
    assert(oldReplyReleased);
  } else if (process.env.CHAT_MENU_FIXTURE === 'catalog') {
    assert.deepEqual(
      requests.filter((r) => r.path === '/v1/threads/actions').map((r) => r.action),
      ['archive'],
    );
    assert(oldReplyReleased && freshCatalogReadsWhileHeld > 0);
    assert(catalogObservations.some((r) => r.kind === 'held-original' && !r.archived));
    assert(catalogObservations.some((r) => r.kind === 'fresh-before-release' && r.archived));
  } else if (process.env.CHAT_MENU_FIXTURE === '1') {
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
  await writeFile(
    join(directory, 'catalog-observations.json'),
    JSON.stringify(catalogObservations, null, 2),
    { mode: 0o600 },
  );
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
  releaseQueueAction?.();
  if (queueControlsMode)
    await writeFile(
      join(directory, 'queue-control-attempts.json'),
      JSON.stringify(
        { requests: queueRawRequests, actions: queueActions, error: queueControlFailure },
        null,
        2,
      ),
      { mode: 0o600 },
    );
  for (const done of heldCatalogReplies.splice(0)) done();
  releaseCatalog?.();
  if (child?.exitCode === null) child.kill('SIGTERM');
  server.closeAllConnections();
  server.close();
}
