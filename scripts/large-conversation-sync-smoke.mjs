import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { mkdtemp, chmod, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createHash } from 'node:crypto';
import {
  desktopFixtureEndpoint,
  listenDesktopFixture,
  prepareDesktopFixtureRuntime,
} from './fixtures/desktop-ipc-transport.mjs';

const { RuntimeSession } = await import('../packages/codex-adapter/dist/runtime-session.js');

// Real framed socket and production projection; synthetic owner, no model execution.
const directory = await mkdtemp(join(tmpdir(), 'agent-hub-large-sync-'));
await chmod(directory, 0o700);
const socket = desktopFixtureEndpoint(join(directory, 'desktop.sock'));
const previousCodexHome = process.env.CODEX_HOME;
if (process.platform === 'win32') process.env.CODEX_HOME = directory;
const peers = new Set(),
  methods = [];
let revision = 1,
  peer,
  session;
const messages = Array.from({ length: 240 }, (_, index) => ({
  id: 'message-' + index,
  type: 'agentMessage',
  text: '进度 ' + index,
}));
const state = (id) => ({
  id,
  hostId: 'local',
  cwd: directory,
  title: 'Large fixture',
  requests: [],
  threadRuntimeStatus: { type: 'active' },
  turns: [
    {
      turnId: id + '-protected-turn',
      status: 'inProgress',
      items: [
        {
          id: 'historical-command',
          type: 'commandExecution',
          status: 'completed',
          command: 'read-only fixture',
          aggregatedOutput: '',
        },
        ...messages,
      ],
    },
  ],
});
const snapshot = (id, large = false) => ({
  type: 'broadcast',
  method: 'thread-stream-state-changed',
  version: 11,
  sourceClientId: 'original-owner',
  params: {
    hostId: 'local',
    conversationId: id,
    change: {
      type: 'snapshot',
      revision,
      conversationState: {
        ...state(id),
        ...(large
          ? {
              turns: [
                {
                  ...state(id).turns[0],
                  items: [
                    {
                      id: 'historical-command',
                      type: 'commandExecution',
                      status: 'completed',
                      command: 'read-only fixture',
                      aggregatedOutput: 'x'.repeat(70 * 1024 * 1024),
                    },
                    ...messages,
                  ],
                },
              ],
            }
          : {}),
      },
    },
  },
});
// The synthetic owner's payload construction is fixture setup, not part of the
// follower's one-second read budget. Send the same complete 70 MiB frame through
// the real socket, without blocking the shared event loop on JSON encoding after
// the production read timer has started.
const snapshotEncodingStarted = performance.now();
const initialLargeBody = Buffer.from(JSON.stringify(snapshot('large', true)));
const snapshotEncodingMS = performance.now() - snapshotEncodingStarted;
assert(initialLargeBody.length > 70 * 1024 * 1024);
function send(value) {
  return sendBody(Buffer.from(JSON.stringify(value)));
}
function sendBody(body) {
  const header = Buffer.alloc(4);
  header.writeUInt32LE(body.length);
  peer.write(header);
  peer.write(body);
  return body.length;
}
const server = createServer((connected) => {
  peer = connected;
  peers.add(peer);
  peer.on('error', () => {});
  let buffer = Buffer.alloc(0);
  peer.on('data', (bytes) => {
    buffer = Buffer.concat([buffer, bytes]);
    while (buffer.length >= 4 && buffer.length >= buffer.readUInt32LE(0) + 4) {
      const length = buffer.readUInt32LE(0),
        message = JSON.parse(buffer.subarray(4, length + 4));
      buffer = buffer.subarray(length + 4);
      methods.push(message.method);
      if (message.method === 'initialize')
        send({
          type: 'response',
          requestId: message.requestId,
          resultType: 'success',
          result: { clientId: 'read-only-fixture' },
        });
      else if (message.method === 'thread-owner-discovery')
        send({
          type: 'response',
          requestId: message.requestId,
          resultType: 'success',
          handledByClientId: 'original-owner',
          result: { supportsUntrustedAppInput: true },
        });
      else if (message.method === 'thread-follower-read-model-settings')
        send({
          type: 'response',
          requestId: message.requestId,
          resultType: 'success',
          handledByClientId: 'original-owner',
          method: message.method,
          result: { settings: { model: 'fixture', reasoningEffort: 'xhigh' } },
        });
      else if (message.method === 'thread-stream-following-changed') {
        // The upstream desktop can silently drop an over-limit snapshot while
        // continuing to publish patches. A follower cannot invent their base.
        if (message.params.conversationId === 'large') sendBody(initialLargeBody);
        else if (message.params.conversationId !== 'fallback')
          send(snapshot(message.params.conversationId));
      } else assert.fail('Unexpected execution method: ' + message.method);
    }
  });
});
await listenDesktopFixture(server, socket);
try {
  session = new RuntimeSession({
    root: directory,
    directory: join(directory, 'state'),
    name: 'Large fixture',
    mode: 'desktop-runtime',
    socket,
  });
  prepareDesktopFixtureRuntime(session.runtime);
  session.runtime.reader.connect = async () =>
    Object.defineProperty(session.runtime.reader, 'ready', { value: true, configurable: true });
  session.state.threads = ['large', 'protected'].map((id) => ({
    id,
    hostId: session.hostId,
    projectId: null,
    title: id,
    state: 'unknown',
    turnId: null,
    messages: [],
    plan: [],
    error: null,
  }));
  await session.connect();
  await session.open('protected');
  const protectedTurn = session.history('protected').runtime.turnId;
  const unknown = {
    hostId: session.hostId,
    threadId: 'large',
    clientMessageId: 'keep-unknown',
    state: 'uncertain',
    turnId: null,
    error: null,
    reply: { mode: 'next-turn', text: 'Never replay this input', attachmentIds: [] },
  };
  session.state.receipts.push(unknown);
  const unknownBefore = JSON.stringify(unknown);
  const latestReadStarted = performance.now();
  const latest = await session.refreshHistory('large', undefined, true);
  console.log(
    JSON.stringify({
      evidence: 'synthetic-large-snapshot',
      wireBytes: initialLargeBody.length,
      snapshotEncodingMS,
      latestReadMS: performance.now() - latestReadStarted,
    }),
  );
  assert.equal(latest.synchronization.state, 'live');
  assert.equal(latest.runtime.state, 'running');
  assert.equal(latest.runtime.canControl, true);
  assert.equal(latest.messages.length, 100);
  assert.equal(latest.messages.at(-1).text, '进度 239');
  assert.equal(JSON.stringify(unknown), unknownBefore);
  assert(Buffer.byteLength(JSON.stringify(latest)) < 100_000);
  const collected = [...latest.messages];
  let cursor = latest.nextCursor;
  while (cursor) {
    const page = await session.refreshHistory('large', cursor, true);
    assert.equal(page.historyWindow.kind, 'earlier');
    collected.unshift(...page.messages);
    cursor = page.nextCursor;
  }
  assert.deepEqual(
    collected.map((m) => m.id),
    ['historical-command', ...messages.map((m) => m.id)],
  );
  await assert.rejects(
    session.refreshHistory('protected', latest.nextCursor, true),
    (e) => e.status === 409,
  );
  const before = methods.filter((m) => m === 'thread-stream-following-changed').length;
  for (let i = 0; i < 3; i++) await session.refreshHistory('large', undefined, true);
  assert.equal(
    methods.filter((m) => m === 'thread-stream-following-changed').length,
    before,
    'Fresh stream must not resend a 70 MiB snapshot on every HTTP read',
  );
  session.runtime.reader.workspace = async () => ({
    projects: [],
    threads: session.state.threads.map(({ id, hostId, projectId, title }) => ({
      id,
      hostId,
      projectId,
      title,
    })),
    nextProjectCursor: null,
    nextThreadCursor: null,
  });
  await session.workspace();
  await delay(50);
  assert.equal(
    methods.filter((m) => m === 'thread-stream-following-changed').length,
    before,
    'Refreshing the computer catalog must not resend fresh large transcripts',
  );
  messages.push({ id: 'latest-progress', type: 'agentMessage', text: '新的真实格式进度🙂' });
  revision++;
  send(snapshot('large'));
  for (
    let i = 0;
    i < 100 && session.history('large').messages.at(-1)?.id !== 'latest-progress';
    i++
  )
    await delay(20);
  const events = await session.poll(0, '', new AbortController().signal, 'large', true);
  assert.equal(events.events[0].snapshot.messages.at(-1).id, 'latest-progress');
  assert.equal(session.history('protected').runtime.turnId, protectedTurn);
  assert.equal(session.history('protected').runtime.state, 'running');
  assert(
    methods.every((m) =>
      ['initialize', 'thread-owner-discovery', 'thread-stream-following-changed'].includes(m),
    ),
  );
  session.runtime.ipc.invalidate('large');
  const stale = await session.poll(0, '', new AbortController().signal, 'large', true);
  assert.equal(stale.events[0].snapshot.synchronization.state, 'unavailable');
  assert.equal(stale.events[0].snapshot.runtime.canControl, false);
  session.state.threads.push({
    ...session.state.threads[0],
    id: 'fallback',
    messages: [
      { ...latest.messages.at(-1), id: 'fallback-progress', text: 'stale progress' },
      {
        ...latest.messages.at(-1),
        id: 'old-rich-only-item',
        role: 'tool',
        text: 'older tool output',
      },
    ],
    state: 'unknown',
    turnId: null,
  });
  let fallbackText = '历史最新进度 A',
    failHistory = false;
  let latestTurn;
  const acceptedOld = {
    ...unknown,
    threadId: 'fallback',
    clientMessageId: 'accepted-old',
    turnId: 'old-turn',
    reply: { mode: 'steer', text: 'Exact original answer', attachmentIds: [] },
  };
  const mismatchedOld = { ...acceptedOld, clientMessageId: 'wrong-body' };
  session.state.receipts.push(acceptedOld, mismatchedOld);
  let receiptEvidenceReads = 0;
  const receiptTurns = [
    {
      id: 'old-turn',
      status: 'completed',
      acceptedMessageIds: ['accepted-old', 'wrong-body'],
      acceptedMessageHashes: {
        'accepted-old': createHash('sha256').update('Exact original answer').digest('hex'),
        'wrong-body': createHash('sha256').update('Different input').digest('hex'),
      },
    },
    { id: 'current-turn', status: 'inProgress', acceptedMessageIds: [], acceptedMessageHashes: {} },
  ];
  session.runtime.reader.receiptEvidence = async (id) => {
    assert.equal(id, 'fallback');
    receiptEvidenceReads++;
    return receiptTurns;
  };
  session.runtime.reader.history = async (id) => {
    assert.equal(id, 'fallback');
    if (failHistory) throw new Error('Selected history read failed');
    return {
      hostId: session.hostId,
      threadId: id,
      nextCursor: null,
      messages: [
        {
          ...latest.messages.at(-1),
          id: 'fallback-progress',
          text: fallbackText,
          runId: 'current-turn',
        },
      ],
      turns: [],
      historyMode: 'paginated',
      ...(latestTurn ? { latestTurn } : {}),
    };
  };
  const beforeFallback = methods.filter((m) => m === 'thread-stream-following-changed').length;
  const fallback = await session.refreshHistory('fallback', undefined, true);
  assert.equal(fallback.synchronization.state, 'history');
  assert(fallback.synchronization.checkedAt);
  assert.equal(fallback.runtime.canControl, false);
  assert.equal(fallback.runtime.state, 'unknown');
  assert.equal(fallback.messages.at(-1).text, fallbackText);
  assert.equal(
    acceptedOld.state,
    'completed',
    'An exact official operation ID and body hash settles its old receipt',
  );
  assert.equal(
    mismatchedOld.state,
    'uncertain',
    'A reused ID with different content cannot settle an operation',
  );
  assert.equal(
    fallback.runtime.receipts.find((v) => v.clientMessageId === 'wrong-body').noticeContext,
    'historical',
  );
  for (let i = 0; i < 20; i++)
    send({
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: 11,
      sourceClientId: 'original-owner',
      params: {
        hostId: 'local',
        conversationId: 'fallback',
        change: { type: 'patches', baseRevision: 100 + i, revision: 101 + i, patches: [] },
      },
    });
  await delay(50);
  fallbackText = '历史最新进度 B';
  const updated = await session.refreshHistory('fallback', undefined, true);
  assert.equal(updated.messages.at(-1).text, fallbackText);
  assert.equal(updated.synchronization.state, 'history');
  assert.equal(
    receiptEvidenceReads,
    1,
    'Paginated receipt recovery is read-only and coalesced/backed off',
  );
  assert.notEqual(updated.synchronization.checkedAt, fallback.synchronization.checkedAt);
  const actualNow = Date.now;
  Date.now = () => actualNow() + 11_000;
  try {
    assert.equal(session.history('fallback', true).synchronization.state, 'unavailable');
  } finally {
    Date.now = actualNow;
  }
  const file = join(directory, '最新记录 (验收).md');
  await writeFile(file, 'latest-file-v1');
  fallbackText = `[完整检查记录](<${file}:12>)`;
  latestTurn = { id: 'current-turn', status: 'completed' };
  const completed = await session.refreshHistory('fallback', undefined, true);
  assert.equal(completed.thread.lastTurnStatus, 'completed');
  assert.equal(completed.runtime.state, 'unknown');
  assert.equal(completed.runtime.canControl, false, 'A terminal history read grants no control');
  const attachment = completed.messages.at(-1).attachments[0];
  assert(attachment, 'New history links must be registered even without a live snapshot');
  assert(completed.messages.at(-1).text.includes('codex-attachment://' + attachment.id));
  assert.equal(
    (await session.download('fallback', attachment.id)).data.toString(),
    'latest-file-v1',
  );
  await writeFile(file, 'latest-file-v2');
  const changed = await session.refreshHistory('fallback', undefined, true);
  assert.equal(
    changed.messages.at(-1).attachments.length,
    1,
    'Never retain a stale inline version',
  );
  const next = changed.messages.at(-1).attachments[0];
  assert.notEqual(next.id, attachment.id);
  assert.equal(next.resourceId, attachment.resourceId);
  assert.equal((await session.download('fallback', next.id)).data.toString(), 'latest-file-v2');
  latestTurn = { id: 'different-turn', status: 'completed' };
  assert.equal(
    (await session.refreshHistory('fallback', undefined, true)).thread.lastTurnStatus,
    undefined,
    'A different terminal turn cannot settle the currently displayed run',
  );
  await rm(file);
  assert.equal(
    (await session.refreshHistory('fallback', undefined, true)).messages.at(-1).attachments.length,
    0,
    'A missing inline resource must not inherit a stale download',
  );
  fallbackText = '历史最新进度 B';
  latestTurn = undefined;
  assert.equal(
    methods.filter((m) => m === 'thread-stream-following-changed').length,
    beforeFallback + 1,
    'Missing-base patches and mobile retries must not create a snapshot storm',
  );
  // A passive mobile read must not inherit a concurrent command's long owner
  // discovery wait. The command continues; fresh history remains read-only.
  const originalFollow = session.runtime.ipc.follow;
  const originalObservation = session.runtime.ipc.refreshObservation;
  const discoveryBudgets = [];
  let pendingDiscovery;
  session.runtime.ipc.follow = (_id, budget) => {
    discoveryBudgets.push(budget);
    return new Promise((resolve) => {
      pendingDiscovery = resolve;
    });
  };
  session.runtime.ipc.refreshObservation = async () => {};
  try {
    const readStarted = performance.now();
    const passive = await session.open('fallback', undefined, undefined, true);
    const passiveReadMS = performance.now() - readStarted;
    assert(passiveReadMS < 1800, 'A pending command must not strand passive mobile history');
    assert.equal(passive.messages.at(-1).text, fallbackText);
    assert.equal(passive.runtime.canControl, false, 'Fresh history cannot grant owner control');
    assert.equal(discoveryBudgets[0], 1000);
    pendingDiscovery(undefined);
    let finished = false;
    const explicit = session.open('fallback').then(() => {
      finished = true;
    });
    await delay(1100);
    assert.equal(finished, false, 'Non-mobile reads retain full discovery');
    assert.equal(discoveryBudgets[1], 15000);
    pendingDiscovery(undefined);
    await explicit;
    console.log(
      JSON.stringify({
        evidence: 'synthetic-passive-read-deadline',
        passiveReadMS,
        discoveryBudgets,
      }),
    );
  } finally {
    session.runtime.ipc.follow = originalFollow;
    session.runtime.ipc.refreshObservation = originalObservation;
  }
  const readHistory = session.runtime.reader.history;
  session.runtime.reader.history = async (...args) => {
    const page = await readHistory(...args);
    send(snapshot('fallback'));
    for (let index = 0; index < 100 && !session.runtime.ipc.hasOwner('fallback'); index++)
      await delay(10);
    assert(session.runtime.ipc.hasOwner('fallback'));
    return page;
  };
  try {
    const recovered = await session.refreshHistory('fallback', undefined, true);
    assert.equal(recovered.synchronization.state, 'live');
    assert.equal(
      recovered.runtime.state,
      'running',
      'An in-flight fallback cannot erase a live snapshot',
    );
    assert.equal(recovered.runtime.turnId, 'fallback-protected-turn');
  } finally {
    session.runtime.reader.history = readHistory;
    session.runtime.ipc.invalidate('fallback');
  }
  failHistory = true;
  await assert.rejects(
    session.refreshHistory('fallback', undefined, true),
    /Selected history read failed/,
  );
  assert.equal(session.history('fallback', true).synchronization.state, 'unavailable');
  assert.equal(JSON.stringify(unknown), unknownBefore);
  assert.equal(session.history('protected').runtime.turnId, protectedTurn);
  assert(
    methods.every((m) =>
      [
        'initialize',
        'thread-owner-discovery',
        'thread-stream-following-changed',
        'thread-follower-read-model-settings',
      ].includes(m),
    ),
  );
  const legacyId = '22222222-2222-4222-8222-222222222222';
  const legacyQueue = {
    ...unknown,
    threadId: legacyId,
    clientMessageId: 'legacy-host-only',
    state: 'queued',
  };
  const originalOwner = session.runtime.ipc.hasOwner,
    originalExecute = session.execute;
  const originalQueue = session.nativeQueue,
    originalUnsupported = session.nativeQueueUnsupported;
  let automaticExecutions = 0;
  session.state.threads.push({
    ...session.state.threads[0],
    id: legacyId,
    state: 'idle',
    turnId: null,
  });
  session.state.receipts.push(legacyQueue);
  try {
    session.runtime.ipc.hasOwner = () => true;
    session.execute = async () => {
      automaticExecutions++;
    };
    session.nativeQueue = { available: false };
    session.nativeQueueUnsupported = false;
    assert.equal(session.startQueued, undefined, 'Host execution scheduler was removed');
    for (const unsupported of [false, true]) {
      session.nativeQueueUnsupported = unsupported;
      const receipt = session.receipt(legacyQueue.clientMessageId);
      assert.equal(receipt.queueDisposition, 'legacy-review');
      assert.equal(receipt.queuePresent, false);
      assert.equal(receipt.canExecuteNow, false);
      await assert.rejects(
        session.executeQueue(legacyId, legacyQueue.clientMessageId),
        /不能自动执行或重发/,
      );
      await delay(0);
      assert.equal(
        automaticExecutions,
        0,
        'Legacy Host records never execute, including unsupported native clients',
      );
    }
  } finally {
    session.runtime.ipc.hasOwner = originalOwner;
    session.execute = originalExecute;
    session.nativeQueue = originalQueue;
    session.nativeQueueUnsupported = originalUnsupported;
    session.state.threads = session.state.threads.filter((v) => v.id !== legacyId);
    session.state.receipts = session.state.receipts.filter((v) => v !== legacyQueue);
  }
  console.log(
    'Large sync passed: 70 MiB valid owner snapshot, newest progress, bounded HTTP/events, all 241 historical messages paged without duplicates, target-scoped cursors, no repeated catalog/full snapshots, upstream missing-base recovery through fresh history, expired/failed history unavailable, bounded patch recovery, unknown receipt retained without complete-history load, protected running turn unchanged; no execution requests. Synthetic boundary evidence.',
  );
} finally {
  session?.close();
  for (const peer of peers) peer.destroy();
  await new Promise((done) => server.close(done));
  await rm(directory, { recursive: true, force: true });
  if (process.platform === 'win32') {
    if (previousCodexHome === undefined) delete process.env.CODEX_HOME;
    else process.env.CODEX_HOME = previousCodexHome;
  }
}
