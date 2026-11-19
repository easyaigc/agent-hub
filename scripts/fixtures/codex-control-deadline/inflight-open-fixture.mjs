import assert from 'node:assert/strict';
import { createServer as createHTTPServer } from 'node:http';
import { randomBytes } from 'node:crypto';

// Dedicated synthetic loopback Host. No Codex, real Host, or model access.
const phase = process.env.INFLIGHT_PHASE;
assert(['baseline', 'fixed'].includes(phase), 'INFLIGHT_PHASE=baseline|fixed required');
const port = Number(process.env.INFLIGHT_PORT || 18802);
assert(Number.isInteger(port) && port > 1024 && port < 65536);
const bindAddress = process.env.FIXTURE_BIND || '127.0.0.1';
assert(
  /^(127\.0\.0\.1|192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3})$/.test(bindAddress),
  'private IPv4 bind required',
);

const controlPort = Number(process.env.FIXTURE_CONTROL_PORT || 0);
assert(!controlPort || (controlPort > 1024 && controlPort < 65536 && controlPort !== port));
const token = randomBytes(32).toString('base64url');
const endpoint = `http://${bindAddress}:${port}`;
const hostId = 'inflight-open-fixture-host',
  threadId = 'inflight-open-fixture-thread';
const thread = {
  id: threadId,
  hostId,
  projectId: 'fixture-project',
  title: 'In-flight open fixture',
  state: 'idle',
};
const capabilities = [
  'projects:read',
  'threads:read',
  'history:read',
  'events',
  'desktop:open',
  'reply',
];
let open,
  recovered = false,
  ownerConnected = false,
  reply,
  receipt,
  stream,
  timer;
const counters = {
  openPosts: 0,
  statusGets: 0,
  replies: 0,
  streamChunks: 0,
  streamClosed: false,
  openId: null,
  statusIds: [],
  replyIds: [],
  sequence: [],
  recovered: false,
  errors: [],
  startedAt: null,
  streamLastAt: null,
  streamClosedAt: null,
};
function json(res, status, value) {
  if (!res.destroyed && !res.headersSent)
    res
      .writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
      .end(JSON.stringify(value));
}
const handler = async (req, res) => {
  try {
    const url = new URL(req.url, endpoint);
    if (
      ![`${bindAddress}:${port}`, `127.0.0.1:${controlPort}`].includes(req.headers.host) ||
      req.headers.origin
    )
      return json(res, 403, { error: 'loopback only' });
    if (url.pathname === '/fixture' && req.method === 'GET')
      return json(res, 200, {
        endpoint,
        token,
        hostId,
        threadId,
        threadTitle: thread.title,
        nonce: phase,
        secondEndpoint: '',
        secondToken: '',
        secondHostId: '',
        delayedEndpoint: '',
      });
    if (url.pathname === '/results' && req.method === 'GET') return json(res, 200, counters);
    if (url.pathname === '/recover' && req.method === 'POST') {
      assert(open, 'open must be received before recovery');
      recovered = true;
      counters.recovered = true;
      return json(res, 200, { recovered: true, originalOpenId: open.clientOperationId });
    }
    if (req.headers.authorization !== 'Bearer ' + token)
      return json(res, 401, { error: 'authentication required' });
    if (url.pathname === '/v1/desktop/open' && req.method === 'POST') {
      let body = '';
      for await (const chunk of req) {
        body += chunk;
        assert(body.length < 65536);
      }
      const input = JSON.parse(body);
      counters.openPosts++;
      counters.sequence.push('open');
      assert.equal(input.hostId, hostId);
      assert.equal(input.threadId, threadId);
      assert.equal(counters.openPosts, 1, 'duplicate open forbidden');
      assert(input.clientOperationId);
      open = {
        hostId,
        threadId,
        clientOperationId: input.clientOperationId,
        phase: 'opened',
        error: null,
      };
      counters.openId = input.clientOperationId;
      counters.startedAt = Date.now();
      stream = res;
      res.writeHead(200, {
        'content-type': 'application/json',
        'cache-control': 'no-store',
        'transfer-encoding': 'chunked',
      });
      const tick = () => {
        if (!res.destroyed) {
          res.write(' ');
          counters.streamChunks++;
          counters.streamLastAt = Date.now();
        }
      };
      tick();
      timer = setInterval(tick, 50);
      res.once('close', () => {
        clearInterval(timer);
        timer = null;
        counters.streamClosed = true;
        counters.streamClosedAt = Date.now();
      });
      return;
    }
    if (req.method === 'POST' && url.pathname === '/v1/replies') {
      let body = '';
      for await (const chunk of req) {
        body += chunk;
        assert(body.length < 65536);
      }
      const input = JSON.parse(body);
      counters.replies++;
      counters.sequence.push('reply');
      counters.replyIds.push(input.clientMessageId);
      assert.equal(counters.replies, 1, 'duplicate reply forbidden');
      assert(ownerConnected && recovered);
      assert.equal(input.hostId, hostId);
      assert.equal(input.threadId, threadId);
      reply = input;
      receipt = {
        hostId,
        threadId,
        clientMessageId: input.clientMessageId,
        state: 'completed',
        turnId: 'fixture-turn',
        error: null,
      };
      return json(res, 200, receipt);
    }
    if (req.method !== 'GET') return json(res, 405, { error: 'unsupported' });
    if (url.pathname === '/v1/workspace')
      return json(res, 200, {
        hostId,
        name: 'In-flight fixture',
        clientType: { id: 'codex', name: 'Codex' },
        connectionMode: 'desktop-runtime',
        capabilities,
        projects: [{ id: 'fixture-project', hostId, name: 'Fixture project', roots: [] }],
        threads: [thread],
        nextProjectCursor: null,
        nextThreadCursor: null,
      });
    if (url.pathname === '/v1/usage' && !url.searchParams.has('hostId'))
      return json(res, 404, { error: 'unsupported' });
    assert.equal(url.searchParams.get('hostId'), hostId);
    if (url.pathname === '/v1/desktop/open-status') {
      assert(open);
      assert.equal(url.searchParams.get('threadId'), threadId);
      const id = url.searchParams.get('id');
      counters.statusGets++;
      counters.sequence.push('status');
      counters.statusIds.push(id);
      assert.equal(id, open.clientOperationId, 'must query original open ID');
      if (!recovered) return json(res, 200, { ...open, phase: 'pending' });
      ownerConnected = true;
      return json(res, 200, open);
    }
    if (['/v1/history', '/v1/events'].includes(url.pathname))
      assert.equal(url.searchParams.get('threadId'), threadId);
    if (url.pathname === '/v1/history')
      return json(res, 200, {
        hostId,
        threadId,
        thread,
        nextCursor: null,
        messages: [
          { id: 'ready', role: 'assistant', text: 'INFLIGHT_FIXTURE_READY', files: [] },
          ...(reply
            ? [
                { id: 'reply-user', role: 'user', text: reply.text, files: [] },
                {
                  id: 'reply-assistant',
                  role: 'assistant',
                  text: 'INFLIGHT_FIXTURE_REPLY',
                  files: [],
                },
              ]
            : []),
        ],
        runtime: {
          state: 'idle',
          turnId: null,
          plan: [],
          approvals: [],
          receipts: receipt ? [receipt] : [],
          canControl: ownerConnected,
        },
      });
    if (url.pathname === '/v1/events') {
      await new Promise((done) => setTimeout(done, 250));
      return json(res, 200, {
        hostId,
        epoch: 'inflight-fixture',
        cursor: 1,
        reset: false,
        ready: true,
        capabilities,
        events: [],
      });
    }
    if (
      url.pathname === '/v1/receipts' &&
      receipt &&
      url.searchParams.get('id') === receipt.clientMessageId
    )
      return json(res, 200, receipt);
    return json(res, 404, { error: 'unsupported fixture route' });
  } catch (error) {
    counters.errors.push(error.message);
    json(res, 500, { error: 'fixture assertion failed' });
  }
};
const server = createHTTPServer(handler);
const controlServer = controlPort ? createHTTPServer(handler) : null;
if (controlServer) controlServer.listen(controlPort, '127.0.0.1');
server.listen(port, bindAddress, () =>
  console.log(JSON.stringify({ fixture: 'inflight-open', phase, port, pid: process.pid })),
);
function shutdown() {
  if (timer) clearInterval(timer);
  stream?.destroy();
  controlServer?.closeAllConnections();
  controlServer?.close();
  server.closeAllConnections();
  server.close(() => process.exit(0));
}
process.once('SIGTERM', shutdown);
process.once('SIGINT', shutdown);
