import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { CodexRuntime, CodexRejection } from '../packages/codex-adapter/dist/runtime.js';
import { sharedCatalogPage } from '../packages/codex-adapter/dist/shared-catalog.js';
import { DesktopChatActions } from '../packages/codex-adapter/dist/desktop-chat-actions.js';
import { DesktopWorkspaceManagement } from '../packages/codex-adapter/dist/desktop-workspace-management.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';

// A deliberately small WS peer verifies frame boundaries, connection isolation,
// immutable management receipts and loss after dispatch. This is auxiliary evidence.
const connections = new Set(),
  calls = [];
let historyMode = 'legacy';
let metadataArchived = false,
  metadataProjectExists = true;
const legacyTurns = () =>
  historyMode === 'legacy'
    ? [
        {
          id: 'active-turn',
          status: turnStatus,
          items: [
            {
              id: 'legacy-user',
              type: 'userMessage',
              content: [{ type: 'text', text: 'legacy history' }],
            },
          ],
        },
      ]
    : [];
let turnStatus = 'inProgress',
  injectLive = false;
const send = (socket, value) => {
  const body = Buffer.from(JSON.stringify(value));
  const header =
    body.length < 126
      ? Buffer.from([0x81, body.length])
      : Buffer.from([0x81, 126, body.length >> 8, body.length & 255]);
  socket.write(Buffer.concat([header, body]));
};
const server = createServer();
server.on('upgrade', (request, socket) => {
  const accept = createHash('sha1')
    .update(request.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' +
      accept +
      '\r\n\r\n',
  );
  connections.add(socket);
  socket.on('close', () => connections.delete(socket));
  let bytes = Buffer.alloc(0);
  socket.on('data', (chunk) => {
    bytes = Buffer.concat([bytes, chunk]);
    while (bytes.length >= 6) {
      const opcode = bytes[0] & 15;
      let size = bytes[1] & 127,
        offset = 2;
      if (size === 126) {
        if (bytes.length < 8) return;
        size = bytes.readUInt16BE(2);
        offset = 4;
      }
      assert.notEqual(size, 127);
      assert(bytes[1] & 128, 'Client WS frames must be masked');
      if (bytes.length < offset + 4 + size) return;
      const mask = bytes.subarray(offset, offset + 4),
        payload = Buffer.from(bytes.subarray(offset + 4, offset + 4 + size));
      bytes = bytes.subarray(offset + 4 + size);
      for (let index = 0; index < payload.length; index++) payload[index] ^= mask[index % 4];
      if (opcode === 8) {
        socket.end(Buffer.from([0x88, 0]));
        return;
      }
      assert.equal(opcode, 1);
      const message = JSON.parse(payload.toString());
      calls.push(message);
      if (message.id === undefined || !message.method) continue;
      if (message.method === 'lose-after-write') {
        socket.destroy();
        continue;
      }
      if (message.method === 'reject') {
        send(socket, { id: message.id, error: { code: -32600, message: 'definitive rejection' } });
        continue;
      }
      if (message.method === 'thread/delete') {
        socket.destroy();
        continue;
      }
      if (message.method === 'numeric-request') {
        send(socket, {
          id: 712,
          method: 'item/tool/requestUserInput',
          params: { threadId: 'test' },
        });
      }
      let result =
        message.method === 'initialize'
          ? { userAgent: 'fixture' }
          : message.method === 'thread/read'
            ? {
                thread: {
                  id: message.params.threadId,
                  cwd: directory,
                  historyMode,
                  status: { type: 'active' },
                  turns: legacyTurns(),
                },
              }
            : { method: message.method };
      if (message.method === 'thread/archive' && message.params.threadId === 'metadata-loaded')
        metadataArchived = true;
      if (message.method === 'thread/list')
        result = {
          data: metadataArchived && message.params.archived ? [{ id: 'metadata-loaded' }] : [],
          nextCursor: null,
        };
      if (message.method === 'project/delete') metadataProjectExists = false;
      if (message.method === 'project/list')
        result = {
          data: metadataProjectExists ? [{ id: 'metadata-project' }] : [],
          nextCursor: null,
        };
      if (message.method === 'thread/resume')
        result = { thread: { id: message.params.threadId, historyMode, turns: legacyTurns() } };
      if (message.method === 'thread/turns/list')
        result = { data: [{ id: 'active-turn', status: turnStatus, items: [] }], nextCursor: null };
      if (message.method === 'thread/items/list') {
        result = {
          data: [
            {
              turnId: 'active-turn',
              item: {
                id: 'active-tool',
                type: 'commandExecution',
                command: 'fixture',
                status: turnStatus,
                aggregatedOutput: 'TOOL_OUTPUT',
                exitCode: null,
              },
            },
          ],
          nextCursor: null,
        };
        if (injectLive) {
          injectLive = false;
          turnStatus = 'interrupted';
          send(socket, {
            method: 'item/commandExecution/outputDelta',
            params: {
              threadId: 'history-fixture',
              turnId: 'active-turn',
              itemId: 'active-tool',
              delta: '_LIVE_DELTA',
            },
          });
          send(socket, {
            method: 'turn/completed',
            params: {
              threadId: 'history-fixture',
              turn: { id: 'active-turn', status: 'interrupted', items: [] },
            },
          });
        }
      }
      send(socket, { id: message.id, result });
    }
  });
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
const url = 'ws://127.0.0.1:' + server.address().port;
const directory = await mkdtemp(join(tmpdir(), 'shared-runtime-'));
const first = new CodexRuntime({ mode: 'shared-runtime', sharedWebsocketUrl: url });
const second = new CodexRuntime({ mode: 'shared-runtime', sharedWebsocketUrl: url });
let broker, session;
try {
  for (const value of ['ws://example.invalid:1234', url + '?secret=x', 'wss://127.0.0.1:1234']) {
    await assert.rejects(
      new CodexRuntime({ mode: 'shared-runtime', sharedWebsocketUrl: value }).connect(),
      /explicit local/,
    );
  }
  await first.connect();
  await second.connect();
  let requestId;
  first.onRequest = (id) => {
    requestId = id;
    first.rejectRequest(id);
  };
  await first.call('numeric-request');
  assert.equal(requestId, 712);
  await assert.rejects(
    first.call('reject'),
    (error) => error instanceof CodexRejection && error.code === -32600,
  );
  first.close();
  assert.equal((await second.call('still-alive')).method, 'still-alive');
  const target = {
    agentId: 'codex',
    hostId: 'host-fixture',
    workspaceId: 'workspace-fixture',
    runtimeId: 'shared-runtime',
  };
  broker = new DesktopChatActions(
    directory,
    target.hostId,
    '/never-spawn',
    directory,
    async () => {},
    url,
  );
  const intent = {
    hostId: target.hostId,
    target,
    threadId: 'thread-fixture',
    clientOperationId: randomUUID(),
    action: 'archive',
  };
  assert.equal((await broker.submit(intent)).phase, 'confirmed');
  assert.equal((await broker.submit(intent)).phase, 'confirmed');
  assert.equal(calls.filter((call) => call.method === 'thread/archive').length, 1);
  const lost = { ...intent, clientOperationId: randomUUID(), action: 'delete' };
  assert.equal((await broker.submit(lost)).phase, 'outcome-unknown');
  assert.equal((await broker.submit(lost)).phase, 'outcome-unknown');
  assert.equal(
    calls.filter((call) => call.method === 'thread/delete').length,
    1,
    'Unknown lifecycle result is never replayed',
  );
  await assert.rejects(second.call('lose-after-write'), /connection closed|uncertain/i);
  const pages = [];
  const catalog = {
    async call(method, params) {
      assert.equal(method, 'thread/list');
      pages.push(params);
      return {
        data: [{ id: params.archived ? 'archived' : 'active' }],
        nextCursor: params.archived || params.cursor ? null : 'next-active',
      };
    },
  };
  const page = await sharedCatalogPage(catalog);
  assert.deepEqual(
    page.threads.map((thread) => thread.archived),
    [false, true],
  );
  await sharedCatalogPage(catalog, page.nextCursor);
  assert.equal(
    pages.length,
    3,
    'Exhausted archive page is not restarted while active catalog continues',
  );
  assert.equal(pages.at(-1).cursor, 'next-active');
  const metadata = new DesktopWorkspaceManagement('/never-spawn-metadata', directory, url);
  const beforeMetadata = calls.length;
  let preflight = 0;
  await metadata.execute('threads:archive', 'metadata-loaded', async () => {
    preflight++;
  });
  assert.equal(await metadata.verify('threads:archive', 'metadata-loaded'), true);
  await metadata.execute('projects:delete', 'metadata-project', async () => {
    preflight++;
  });
  assert.equal(await metadata.verify('projects:delete', 'metadata-project'), true);
  const written = calls.length;
  await assert.rejects(
    metadata.execute('threads:archive', 'must-not-write', async () => {
      throw new Error('target changed');
    }),
    /target changed/,
  );
  assert.equal(calls.slice(written).filter((v) => v.method === 'thread/archive').length, 0);
  assert.equal(preflight, 2);
  assert(
    !calls
      .slice(beforeMetadata)
      .some((v) => ['thread/start', 'thread/resume', 'turn/start'].includes(v.method)),
  );
  session = new RuntimeSession({
    mode: 'shared-runtime',
    sharedWebsocketUrl: url,
    directory: join(directory, 'session'),
    root: directory,
    name: 'Boundary fixture',
  });
  await session.connect();
  const legacy = await session.open('history-fixture');
  assert(legacy.messages.some((item) => item.text.includes('legacy history')));
  assert.equal(
    calls.filter((call) => call.method === 'thread/items/list').length,
    0,
    'Unmigrated legacy history does not support rich items',
  );
  historyMode = 'paginated';
  const live = await session.open('history-fixture');
  assert(
    live.messages.some(
      (item) => item.kind === 'commandExecution' && item.detail.includes('TOOL_OUTPUT'),
    ),
    'After desktop history migration, shared history must use official rich items',
  );
  injectLive = true;
  const refreshed = await session.open('history-fixture');
  assert.equal(refreshed.runtime.state, 'idle');
  assert.equal(
    refreshed.thread.lastTurnStatus,
    'interrupted',
    'An older in-flight read cannot resurrect a stopped turn',
  );
  assert(
    refreshed.messages.some(
      (item) => item.detail.includes('TOOL_OUTPUT_LIVE_DELTA') && item.status === 'interrupted',
    ),
    'History refresh preserves verified streaming output and the terminal status',
  );
  console.log(
    'Shared WS framing, connection isolation, immutable receipts, unknown no-replay, dual catalogs, legacy-to-paginated migration and stale-read/live-stop fencing passed.',
  );
} finally {
  first.close();
  second.close();
  broker?.close();
  await session?.close();
  for (const socket of connections) socket.destroy();
  await new Promise((done) => server.close(done));
  await rm(directory, { recursive: true, force: true });
}
