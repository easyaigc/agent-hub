import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:net';
import { chmod, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { test } from 'node:test';
import {
  desktopFixtureEndpoint,
  listenDesktopFixture,
  prepareDesktopFixtureRuntime,
} from './fixtures/desktop-ipc-transport.mjs';

const runtimeModule = process.env.ACCEPTED_RECOVERY_RUNTIME_MODULE
  ? pathToFileURL(resolve(process.env.ACCEPTED_RECOVERY_RUNTIME_MODULE)).href
  : new URL('../packages/codex-adapter/dist/runtime-session.js', import.meta.url).href;
const { RuntimeSession } = await import(runtimeModule);
const { CodexHistoryReader } = await import('../packages/codex-adapter/dist/history-reader.js');
const hash = (text) => createHash('sha256').update(text).digest('hex');
const until = async (predicate) => {
  for (let n = 0; n < 200; n++) {
    if (predicate()) return;
    await delay(10);
  }
  assert.fail('Timed out waiting for read-only reconciliation');
};

// The socket is a task-owned owner fixture. The real RuntimeSession, framing,
// persistence, disconnect and mobile history paths execute without model input.
async function withSession(body) {
  const dir = await mkdtemp(join(tmpdir(), 'accepted-recovery-'));
  await chmod(dir, 0o700);
  const endpoint = desktopFixtureEndpoint(join(dir, 'owner.sock'));
  const peers = new Set(),
    methods = [];
  let session,
    peer,
    revision = 1;
  const server = createServer((socket) => {
    peer = socket;
    peers.add(socket);
    socket.on('error', () => {});
    let bytes = Buffer.alloc(0);
    const send = (message) => {
      const payload = Buffer.from(JSON.stringify(message));
      const header = Buffer.alloc(4);
      header.writeUInt32LE(payload.length);
      socket.write(Buffer.concat([header, payload]));
    };
    socket.on('data', (chunk) => {
      bytes = Buffer.concat([bytes, chunk]);
      while (bytes.length >= 4 && bytes.length >= 4 + bytes.readUInt32LE(0)) {
        const length = bytes.readUInt32LE(0),
          request = JSON.parse(bytes.subarray(4, length + 4));
        bytes = bytes.subarray(length + 4);
        methods.push(request.method);
        if (request.method === 'thread-stream-following-changed') {
          send({
            type: 'broadcast',
            method: 'thread-stream-state-changed',
            version: 11,
            sourceClientId: 'owner',
            params: {
              hostId: 'local',
              conversationId: 'test-thread',
              change: {
                type: 'snapshot',
                revision: revision++,
                conversationState: {
                  id: 'test-thread',
                  hostId: 'local',
                  cwd: dir,
                  title: 'fixture',
                  requests: [],
                  threadRuntimeStatus: { type: 'active' },
                  turns: [
                    {
                      turnId: 'new-turn',
                      status: 'inProgress',
                      items: [{ id: 'new-message', type: 'agentMessage', text: 'new turn only' }],
                    },
                  ],
                },
              },
            },
          });
        } else if (request.type === 'request') {
          const result =
            request.method === 'initialize'
              ? { clientId: 'fixture-client' }
              : request.method === 'thread-owner-discovery'
                ? { supportsUntrustedAppInput: true }
                : request.method === 'thread-follower-read-model-settings'
                  ? { settings: { model: 'fixture', reasoningEffort: 'medium' } }
                  : null;
          assert.notEqual(result, null, `Unexpected owner method ${request.method}`);
          send({
            type: 'response',
            requestId: request.requestId,
            method: request.method,
            resultType: 'success',
            handledByClientId: 'owner',
            result,
          });
        }
      }
    });
  });
  const make = () => {
    const s = new RuntimeSession({
      root: dir,
      directory: join(dir, 'host'),
      name: 'fixture',
      mode: 'desktop-runtime',
      socket: endpoint,
    });
    prepareDesktopFixtureRuntime(s.runtime);
    s.runtime.reader.connect = async () =>
      Object.defineProperty(s.runtime.reader, 'ready', { value: true, configurable: true });
    return s;
  };
  try {
    await listenDesktopFixture(server, endpoint);
    session = make();
    session.state.threads = [
      {
        id: 'test-thread',
        hostId: session.hostId,
        projectId: null,
        title: 'fixture',
        state: 'unknown',
        turnId: null,
        messages: [],
        plan: [],
        error: null,
      },
    ];
    const receipts = Array.from({ length: 8 }, (_, i) => ({
      hostId: session.hostId,
      threadId: 'test-thread',
      clientMessageId: `operation-${i}`,
      state: 'submitted',
      turnId: 'old-turn',
      acceptedTurnId: 'old-turn',
      error: null,
      reply: {
        mode: i ? 'steer' : 'next-turn',
        text: `input ${i}`,
        attachmentIds: i === 7 ? ['attachment-1'] : [],
      },
    }));
    session.state.receipts.push(...receipts, {
      hostId: session.hostId,
      threadId: 'test-thread',
      clientMessageId: 'unknown',
      state: 'submitting',
      turnId: null,
      error: null,
      reply: { mode: 'steer', text: 'unknown input', attachmentIds: [] },
    });
    await session.connect();
    session.runtime.onDisconnect();
    assert.deepEqual(
      receipts.map((r) => [r.state, r.acceptedTurnId]),
      Array.from({ length: 8 }, () => ['submitted', 'old-turn']),
      'owner acceptance cannot be revoked by a disconnect',
    );
    assert.equal(session.state.receipts.at(-1).state, 'uncertain');
    // Emulate an already persisted older schema produced by the regression.
    receipts[0].state = 'uncertain';
    session.close();
    session = make();
    const restored = session.state.receipts;
    assert.equal(restored[0].state, 'submitted', 'restore prior owner acceptance');
    assert.equal(restored[0].acceptedTurnId, 'old-turn');
    assert.deepEqual(restored[7].reply.attachmentIds, ['attachment-1']);
    await session.connect();
    await body({ session, restored, methods, peer: () => peer });
  } finally {
    session?.close();
    for (const socket of peers) socket.destroy();
    await new Promise((done) => server.close(done));
    await rm(dir, { recursive: true, force: true });
  }
}

test('eight accepted inputs survive restart; latest window reconciles old turn without another input', async () =>
  withSession(async ({ session, restored, methods }) => {
    let release,
      calls = 0;
    const gate = new Promise((resolve) => {
      release = resolve;
    });
    session.runtime.reader.receiptTurnEvidence = async (threadId, turnIds) => {
      calls++;
      assert.equal(threadId, 'test-thread');
      assert(turnIds.includes('old-turn'));
      await gate;
      return [
        { id: 'old-turn', status: 'completed', acceptedMessageIds: [], acceptedMessageHashes: {} },
      ];
    };
    const first = session.refreshHistory('test-thread', undefined, true);
    const window = await Promise.race([
      first,
      delay(2000).then(() => {
        throw Error('latest window blocked on old turn');
      }),
    ]);
    assert(window.messages.some((m) => m.text === 'new turn only'));
    await until(() => calls === 1);
    await Promise.all([
      session.refreshHistory('test-thread', undefined, true),
      session.refreshHistory('test-thread', undefined, true),
    ]);
    assert.equal(calls, 1, 'concurrent reads must coalesce');
    assert(restored.slice(0, 8).every((r) => r.state === 'submitted'));
    release();
    await until(() => restored.slice(0, 8).every((r) => r.state === 'completed'));
    assert.equal(restored[8].state, 'uncertain');
    assert(restored.slice(0, 8).every((r) => r.acceptedTurnId === 'old-turn'));
    await session.refreshHistory('test-thread', undefined, true);
    assert.equal(calls, 1, 'recent summary reads are bounded');
    assert(
      !methods.some((m) => /start-turn|steer-turn|load-complete-history/.test(m)),
      'reads cannot send input or expand complete owner history',
    );
  }));

test('unknown inputs require matching id and text; attachments and foreign targets do not infer acceptance', async () =>
  withSession(async ({ session, restored, methods }) => {
    const make = (id, text, extra = {}) => ({
      hostId: session.hostId,
      threadId: 'test-thread',
      clientMessageId: id,
      state: 'uncertain',
      turnId: null,
      error: null,
      reply: { mode: 'steer', text, attachmentIds: [] },
      ...extra,
    });
    const match = make('match', 'correct'),
      mismatch = make('mismatch', 'original'),
      attachment = make('attachment', 'image', {
        reply: { mode: 'steer', text: 'image', attachmentIds: ['file'] },
      }),
      foreign = make('foreign', 'foreign', { threadId: 'another-thread' });
    session.state.receipts.push(match, mismatch, attachment, foreign);
    // Exercise production reconciliation with independent synthetic read evidence.
    session.reconcileReadReceiptEvidence(session.state.threads[0], [
      {
        id: 'old-turn',
        status: 'completed',
        acceptedMessageIds: ['match', 'mismatch', 'attachment', 'foreign'],
        acceptedMessageHashes: {
          match: hash('correct'),
          mismatch: hash('different'),
          attachment: hash('image'),
          foreign: hash('foreign'),
        },
      },
    ]);
    assert.equal(match.state, 'completed');
    for (const r of [mismatch, attachment, foreign, restored[8]])
      assert.equal(r.state, 'uncertain');
    assert(!methods.some((m) => /start-turn|steer-turn/.test(m)));
  }));

test('reader obtains bounded status summaries for paginated and legacy history', async () => {
  const reader = new CodexHistoryReader('host', 'fixture');
  reader.registerOwnerThread('test-thread');
  const calls = [];
  reader.rpc = async (method, params) => {
    calls.push({ method, params });
    if (method === 'thread/read')
      return { thread: { id: 'test-thread', historyMode: 'paginated' } };
    assert.equal(method, 'thread/turns/list');
    const page = calls.filter((c) => c.method === method).length;
    return {
      data: [{ id: page === 3 ? 'old-turn' : `other-${page}`, status: 'completed', items: [] }],
      nextCursor: `page-${page}`,
    };
  };
  const evidence = await reader.receiptTurnEvidence('test-thread', ['old-turn']);
  assert(evidence.some((turn) => turn.id === 'old-turn' && turn.status === 'completed'));
  assert(calls.filter((c) => c.method === 'thread/turns/list').length <= 4);
  for (const { params } of calls.filter((c) => c.method === 'thread/turns/list')) {
    assert.equal(params.itemsView, 'notLoaded');
    assert.equal(params.limit, 100);
  }
  calls.length = 0;
  await reader.receiptTurnEvidence('test-thread', ['missing-turn']);
  assert.equal(
    calls.filter((c) => c.method === 'thread/turns/list').length,
    4,
    'missing turns cannot trigger unbounded paging',
  );
  calls.length = 0;
  reader.rpc = async () => ({ thread: { id: 'foreign-thread', historyMode: 'paginated' } });
  await assert.rejects(reader.receiptTurnEvidence('test-thread', ['old-turn']), /target mismatch/i);
  calls.length = 0;
  reader.rpc = async (method, params) => {
    calls.push({ method, params });
    if (method === 'thread/read') return { thread: { id: 'test-thread', historyMode: 'legacy' } };
    return {
      data: [
        { id: 'old-turn', status: 'completed', items: null },
        { id: 'other-turn', status: 'failed' },
      ],
    };
  };
  assert.deepEqual(await reader.receiptTurnEvidence('test-thread', ['old-turn', 'other-turn']), [
    { id: 'other-turn', status: 'failed', acceptedMessageIds: [], acceptedMessageHashes: {} },
    { id: 'old-turn', status: 'completed', acceptedMessageIds: [], acceptedMessageHashes: {} },
  ]);
  assert(
    !calls.some((c) => c.method === 'thread/read' && c.params.includeTurns === true),
    'legacy must not expand full history',
  );
  assert(calls.some((c) => c.method === 'thread/turns/list'));
  reader.rpc = async (method) => {
    if (method === 'thread/read') return { thread: { id: 'test-thread', historyMode: 'legacy' } };
    throw Object.assign(new Error('method not found'), { code: -32601 });
  };
  assert.deepEqual(await reader.receiptTurnEvidence('test-thread', ['old-turn']), []);
  reader.rpc = async (method) => {
    if (method === 'thread/read') return { thread: { id: 'test-thread', historyMode: 'legacy' } };
    throw Object.assign(new Error('read failed'), { code: -32603 });
  };
  await assert.rejects(reader.receiptTurnEvidence('test-thread', ['old-turn']), /read failed/);
});

test('reader hashes accepted steering input and rejects optimistic steering', async () => {
  const reader = new CodexHistoryReader('host', 'fixture');
  reader.registerOwnerThread('test-thread');
  reader.rpc = async () => ({
    thread: {
      id: 'test-thread',
      turns: [
        {
          id: 'old-turn',
          status: 'completed',
          items: [
            {
              type: 'steeringUserMessage',
              status: 'accepted',
              clientUserMessageId: 'accepted',
              input: [
                { type: 'text', text: 'first' },
                { type: 'text', text: 'second' },
              ],
            },
            {
              type: 'steeringUserMessage',
              status: 'pending',
              clientUserMessageId: 'pending',
              input: [{ type: 'text', text: 'draft' }],
            },
          ],
        },
      ],
    },
  });
  const [turn] = await reader.receiptEvidence('test-thread');
  assert.equal(turn.acceptedMessageHashes.accepted, hash('first\n\nsecond'));
  assert(!turn.acceptedMessageIds.includes('pending'));
});

test('read-only summary pagination crosses the real JSONL RPC transport', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'receipt-rpc-'));
  const executable = join(dir, 'metadata-fixture.mjs'),
    log = join(dir, 'requests.jsonl');
  const script = `#!/usr/bin/env node
import { createInterface } from 'node:readline';
import { appendFileSync } from 'node:fs';
createInterface({ input: process.stdin }).on('line', (line) => {
  const request = JSON.parse(line);
  appendFileSync(${JSON.stringify(log)}, JSON.stringify(request) + '\\n');
  if (request.id === undefined) return;
  let result = {};
  if (request.method === 'thread/read') result = { thread: { id: request.params.threadId, historyMode: request.params.threadId === 'legacy' ? 'legacy' : 'paginated' } };
  if (request.method === 'thread/turns/list') {
    const page = request.params.cursor ? Number(request.params.cursor) : 0;
    result = { data: [{ id: page === 2 ? 'old-turn' : 'other-' + page, status: 'completed', items: [] }], nextCursor: String(page + 1) };
  }
  process.stdout.write(JSON.stringify({ id: request.id, result }) + '\\n');
});`;
  let reader;
  try {
    await writeFile(executable, script, { mode: 0o700 });
    reader = new CodexHistoryReader('host', 'fixture', executable);
    await reader.connect();
    reader.registerOwnerThread('paginated');
    reader.registerOwnerThread('legacy');
    const result = await reader.receiptTurnEvidence('paginated', ['old-turn']);
    assert(result.some((turn) => turn.id === 'old-turn' && turn.status === 'completed'));
    const legacy = await reader.receiptTurnEvidence('legacy', ['old-turn']);
    assert(legacy.some((turn) => turn.id === 'old-turn' && turn.status === 'completed'));
    const requests = (await readFile(log, 'utf8')).trim().split('\n').map(JSON.parse);
    const pages = requests.filter((r) => r.method === 'thread/turns/list');
    assert.equal(pages.length, 6);
    assert(
      pages.every(
        (r) =>
          ['paginated', 'legacy'].includes(r.params.threadId) &&
          r.params.limit === 100 &&
          r.params.itemsView === 'notLoaded',
      ),
    );
    assert(!requests.some((r) => r.method === 'thread/read' && r.params.includeTurns === true));
    assert(!requests.some((r) => /turn\/start|turn\/steer/.test(r.method)));
  } finally {
    if (reader) {
      reader.close();
      await reader.processClosed;
    }
    await rm(dir, { recursive: true, force: true });
  }
});

test('late ACK failure cannot undo owner-confirmed acceptance or terminal outcome', async () =>
  withSession(async ({ session, methods }) => {
    await session.refreshHistory('test-thread', undefined, true);
    const thread = session.state.threads[0];
    let writes = 0;
    for (const status of ['inProgress', 'completed']) {
      const id = `late-${status}`;
      session.callWithOwnerRecovery = async (
        _thread,
        messageId,
        _method,
        _params,
        _target,
        beforeWire,
      ) => {
        writes++;
        beforeWire();
        await session.desktopSnapshot(thread, {
          id: 'test-thread',
          hostId: 'local',
          cwd: '/fixture',
          title: 'fixture',
          requests: [],
          threadRuntimeStatus: { type: status === 'completed' ? 'idle' : 'active' },
          turns: [
            {
              turnId: 'accepted-turn',
              status,
              items: [
                {
                  id: 'owner-user',
                  type: 'userMessage',
                  clientId: messageId,
                  content: [{ type: 'text', text: id }],
                },
              ],
            },
          ],
        });
        throw Error('ACK lost after owner confirmation');
      };
      const reply = {
        hostId: session.hostId,
        threadId: 'test-thread',
        clientMessageId: id,
        text: id,
        attachmentIds: [],
        mode: 'steer',
        expectedTurnId: 'new-turn',
      };
      const result = await session.submit(reply);
      assert.equal(result.state, status === 'completed' ? 'completed' : 'submitted');
      assert.equal(result.turnId, 'accepted-turn');
      const persisted = session.state.receipts.find((r) => r.clientMessageId === id);
      assert.equal(persisted.acceptedTurnId, 'accepted-turn');
      assert.equal((await session.submit(reply)).state, result.state);
    }
    assert.equal(writes, 2, 'each immutable input executes exactly once despite lost ACK');
    assert(
      !methods.some((m) => /start-turn|steer-turn/.test(m)),
      'execution boundary is synthetic',
    );
  }));

test('native queue add ACK loss preserves owner acceptance and terminal state without re-adding', async () =>
  withSession(async ({ session, methods }) => {
    await session.refreshHistory('test-thread', undefined, true);
    const originalQueue = session.nativeQueue;
    const originalValidation = session.nativeQueueThread;
    let adds = 0;
    session.nativeQueueThread = () => true;
    session.nativeQueue = {
      available: true,
      list: async () => [],
      add: async (_threadId, id, _input, beforeWrite) => {
        adds++;
        beforeWrite();
        const status = id.endsWith('completed') ? 'completed' : 'inProgress';
        await session.desktopSnapshot(session.state.threads[0], {
          id: 'test-thread',
          hostId: 'local',
          cwd: '/fixture',
          title: 'fixture',
          requests: [],
          threadRuntimeStatus: { type: status === 'completed' ? 'idle' : 'active' },
          turns: [
            {
              turnId: 'queue-accepted-turn',
              status,
              items: [
                {
                  id: 'owner-queue-user',
                  type: 'userMessage',
                  clientId: id,
                  content: [{ type: 'text', text: id }],
                },
              ],
            },
          ],
        });
        throw Error('queue add ACK lost after owner confirmation');
      },
      close() {},
    };
    try {
      for (const status of ['inProgress', 'completed']) {
        const id = `queue-late-${status}`;
        const reply = {
          hostId: session.hostId,
          threadId: 'test-thread',
          clientMessageId: id,
          text: id,
          attachmentIds: [],
          mode: 'queue',
        };
        const result = await session.submit(reply);
        assert.equal(result.state, status === 'completed' ? 'completed' : 'submitted');
        assert.equal(result.turnId, 'queue-accepted-turn');
        const receipt = session.state.receipts.find((entry) => entry.clientMessageId === id);
        assert.equal(receipt.acceptedTurnId, 'queue-accepted-turn');
        assert.equal((await session.submit(reply)).state, result.state);
      }
      assert.equal(adds, 2, 'lost ACK cannot replay a queue add');
      assert(!methods.some((method) => /start-turn|steer-turn/.test(method)));
    } finally {
      session.nativeQueue = originalQueue;
      session.nativeQueueThread = originalValidation;
    }
  }));
