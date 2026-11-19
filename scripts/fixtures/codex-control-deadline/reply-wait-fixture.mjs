import assert from 'node:assert/strict';
import { createServer as createHTTPServer } from 'node:http';
import { randomBytes } from 'node:crypto';

// Native regression with real HTTP responses deliberately held across two sends.
// Synthetic snapshots are auxiliary evidence, never a real desktop/model proof.
// Standalone task-only server; no simulator or desktop process access.
const hostId = 'reply-wait-fixture',
  threadId = 'reply-wait-thread';
const token = randomBytes(32).toString('base64url');
const scheduling = process.env.REPLY_WAIT_SCHEDULING === '1';
const port = Number(process.env.REPLY_WAIT_PORT || (scheduling ? 18816 : 8816));
const bindAddress = process.env.FIXTURE_BIND || '127.0.0.1';
assert(/^(127\.0\.0\.1|192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3})$/.test(bindAddress));
const endpoint = 'http://' + bindAddress + ':' + port;
const controlPort = Number(process.env.FIXTURE_CONTROL_PORT || 0);
assert(!controlPort || (controlPort > 1024 && controlPort < 65536 && controlPort !== port));
const connectionMode = process.env.FIXTURE_CONNECTION_MODE || 'managed-runtime';
assert(['managed-runtime', 'desktop-runtime'].includes(connectionMode));
const automatic = scheduling || process.env.REPLY_WAIT_AUTOMATIC === '1';
const stalledHistory = process.env.REPLY_WAIT_STALLED_HISTORY === '1';
const confirmed = new Set();
const heldHistory = new Set();
let historyReleased = false;
let boundary = '';
const capabilities = [
  'reply',
  'stop',
  'queue',
  'steer',
  ...(!scheduling ? ['events'] : []),
  'history:read',
];
const sends = [],
  receipts = [],
  held = new Map();
const cursor = 1;
const report = {
  realRuntimeVerified: false,
  queries: [],
  statusQueries: [],
  releases: [],
  sends,
  queriesWhileHistoryHeld: 0,
  boundaryQueries: {},
  eventRequests: 0,
};
const thread = {
  id: threadId,
  hostId,
  title: 'Reply wait fixture',
  projectId: 'wait-project',
  state: 'idle',
};
const snapshot = () => ({
  hostId,
  threadId,
  thread,
  nextCursor: null,
  messages: [
    { id: 'ready', role: 'assistant', text: 'REPLY_WAIT_READY', status: 'completed', files: [] },
  ],
  runtime: {
    state: 'idle',
    turnId: 'old-completed-turn',
    plan: [],
    approvals: [],
    receipts: [],
    canControl: true,
  },
});
const json = (response, value, status = 200) => {
  if (!response.destroyed)
    response
      .writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
      .end(JSON.stringify(value));
};
const handler = async (request, response) => {
  try {
    if (
      request.headers.origin ||
      ![bindAddress + ':' + port, '127.0.0.1:' + controlPort].includes(request.headers.host)
    )
      return json(response, { error: 'Local fixture only' }, 403);
    const url = new URL(request.url, endpoint);
    if (url.pathname === '/')
      return json(response, {
        endpoint,
        token,
        hostId,
        threadId,
        threadTitle: thread.title,
        nonce: stalledHistory ? 'fixture-stalled-history' : 'fixture',
        secondEndpoint: '',
        secondToken: '',
        secondHostId: '',
        delayedEndpoint: '',
      });
    assert.equal(request.headers.authorization, 'Bearer ' + token);
    if (url.pathname === '/wait/status')
      return json(response, { ...report, heldHistory: heldHistory.size });
    if (url.pathname === '/wait/checkpoint' && scheduling) {
      boundary = url.searchParams.get('phase');
      assert(['cold', 'foreground', 'manual'].includes(boundary));
      historyReleased = false;
      return json(response, { ok: true });
    }
    if (url.pathname === '/wait/release-history' && scheduling) {
      historyReleased = true;
      for (const pending of heldHistory) json(pending, snapshot());
      heldHistory.clear();
      return json(response, { ok: true });
    }
    if (url.pathname === '/wait/settled') return json(response, { ok: true });
    if (url.pathname === '/wait/confirm') {
      confirmed.add(Number(url.searchParams.get('index')));
      return json(response, { ok: true });
    }
    if (url.pathname === '/wait/upgrade') return json(response, { error: 'disabled' }, 403);
    if (url.pathname === '/wait/release') {
      const index = Number(url.searchParams.get('index'));
      assert(held.has(index));
      if (automatic && !scheduling && index === 0)
        json(held.get(index), { error: 'Delayed transport timeout' }, 504);
      else json(held.get(index), receipts[index]);
      held.delete(index);
      report.releases.push(index);
      if (stalledHistory && index === 1) {
        historyReleased = true;
        for (const pending of heldHistory) json(pending, snapshot());
        heldHistory.clear();
      }
      return json(response, { ok: true });
    }
    if (url.pathname === '/v1/workspace')
      return json(response, {
        hostId,
        name: 'Reply wait fixture',
        connectionMode,
        capabilities,
        clientType: { id: 'codex', name: 'Codex' },
        projects: [{ id: 'wait-project', hostId, name: 'Reply wait', roots: [] }],
        threads: [thread],
        nextProjectCursor: null,
        nextThreadCursor: null,
      });
    if (url.pathname === '/v1/models') return json(response, { models: [] });
    if (url.pathname === '/v1/history') {
      if (((stalledHistory && sends.length > 0) || (scheduling && boundary)) && !historyReleased) {
        heldHistory.add(response);
        response.once('close', () => heldHistory.delete(response));
        return;
      }
      return json(response, snapshot());
    }
    if (url.pathname === '/v1/events') {
      report.eventRequests++;
      assert(!scheduling, 'Receipt scheduling must not require events');
      await new Promise((done) => setTimeout(done, 1000));
      return json(response, {
        hostId,
        epoch: 'wait',
        cursor,
        ready: true,
        reset: false,
        capabilities,
        events: [],
      });
    }
    if (url.pathname === '/v1/receipts') {
      const id = url.searchParams.get('id');
      report.queries.push(id);
      if (heldHistory.size) {
        report.queriesWhileHistoryHeld++;
        if (scheduling)
          report.boundaryQueries[boundary] = (report.boundaryQueries[boundary] ?? 0) + 1;
      }
      const index = receipts.findIndex((r) => r.clientMessageId === id);
      const receipt = receipts[index];
      if (held.has(index) && (!confirmed.has(index) || (scheduling && !heldHistory.size)))
        return json(response, { ...receipt, state: 'uncertain' });
      if (automatic && !scheduling && index === 0)
        return json(response, { error: 'Primary receipt missing' }, 404);
      if (automatic && !scheduling && index === 2)
        return json(response, { error: 'No receipt' }, 404);
      return json(response, receipt ?? { error: 'No receipt' }, receipt ? 200 : 404);
    }
    if (url.pathname === '/v1/replies/status' && automatic) {
      const id = url.searchParams.get('id');
      report.statusQueries.push(id);
      const index = receipts.findIndex((r) => r.clientMessageId === id);
      return json(response, {
        version: 2,
        hostId,
        threadId,
        clientMessageId: id,
        target: JSON.parse(url.searchParams.get('target')),
        status: index === 0 && confirmed.has(0) ? 'accepted' : 'unavailable',
        capturedTarget: JSON.parse(url.searchParams.get('target')),
        evidence: null,
        fingerprintVersion: null,
        fingerprint: null,
        observedAt: new Date().toISOString(),
        receipt: index === 0 && confirmed.has(0) ? receipts[index] : null,
      });
    }
    if (url.pathname === '/v1/replies' && request.method === 'POST') {
      let body = '';
      for await (const bytes of request) body += bytes;
      const reply = JSON.parse(body);
      assert.equal(reply.hostId, hostId);
      assert.equal(reply.threadId, threadId);
      assert.equal(reply.mode, 'next-turn');
      assert(!sends.some((s) => s.clientMessageId === reply.clientMessageId));
      const index = sends.length;
      sends.push(reply);
      receipts.push({
        ...reply,
        state: automatic ? 'completed' : index === 0 || index === 2 ? 'failed' : 'completed',
        turnId: null,
        error: !automatic && (index === 0 || index === 2) ? 'Submission failed fixture' : null,
      });
      if (index < 2 || scheduling) {
        held.set(index, response);
        if (process.env.REPLY_WAIT_TRICKLE === '1') {
          response.writeHead(200, {
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
          });
          report.trickleStart = Date.now();
          report.trickleChunks = 0;
          const tick = setInterval(() => {
            if (!response.destroyed) {
              response.write(' ');
              report.trickleChunks++;
            }
          }, 50);
          response.once('close', () => {
            clearInterval(tick);
            report.trickleClosed = Date.now();
          });
        }
        return;
      }
      if (automatic && index === 2) return json(response, { error: 'Unknown submission' }, 504);
      return json(response, receipts[index]);
    }
    return json(response, { error: 'Unsupported fixture route' }, 404);
  } catch (error) {
    report.error = error.message;
    json(response, { error: error.message }, 500);
  }
};
const server = createHTTPServer(handler);
const controlServer = controlPort ? createHTTPServer(handler) : null;
if (controlServer)
  await new Promise((done, reject) => {
    controlServer.once('error', reject);
    controlServer.listen(controlPort, '127.0.0.1', done);
  });
await new Promise((done, reject) => {
  server.once('error', reject);
  server.listen(port, bindAddress, done);
});

console.log(JSON.stringify({ fixture: 'reply-wait', endpoint, pid: process.pid }));
function shutdown() {
  controlServer?.closeAllConnections();
  controlServer?.close();
  server.closeAllConnections();
  server.close(() => process.exit(0));
}
process.once('SIGTERM', shutdown);
process.once('SIGINT', shutdown);
