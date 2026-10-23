import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { gunzipSync, gzipSync } from 'node:zlib';

// Acceptance-only adapter: real upstream JSON, bounded GETs, no upstream writes.
// This shapes aggregate HTTP response bytes, not radio/TCP packet loss or TLS.
export const weakNetworkProfiles = {
  normal: { kbps: 1024, rttMS: 120, jitterMS: 30 },
  constrained: { kbps: 128, rttMS: 600, jitterMS: 150 },
  severe: { kbps: 64, rttMS: 1000, jitterMS: 250 },
};

export function createWeakNetworkReadProxy({
  target,
  threadId,
  read,
  profile = 'constrained',
  selectThreadFirst = false,
}) {
  assert(target?.agentId === 'codex' && target.runtimeId === 'desktop-runtime');
  assert(target.hostId && target.workspaceId && threadId && weakNetworkProfiles[profile]);
  const token = randomBytes(32).toString('base64url');
  const state = {
    requests: [],
    rejected: 0,
    rejectedRequests: [],
    stalls: 0,
    drops: 0,
    upstreamWrites: 0,
    historyInFlight: 0,
    maxHistoryInFlight: 0,
    bytes: 0,
    offline: false,
  };
  let selected = profile,
    nextByteAt = 0,
    sequence = 0,
    stallNext = false,
    dropNext = false;
  const jobs = new Set();
  const metrics = () => ({
    ...state,
    profile: weakNetworkProfiles[selected],
    profileName: selected,
  });
  const server = createServer(async (req, res) => {
    if (!req.url?.startsWith('/') || req.url.startsWith('//')) {
      state.rejected++;
      res.writeHead(403).end();
      return;
    }
    const url = new URL(req.url, 'http://127.0.0.1');
    const rejectRequest = () => {
      state.rejected++;
      if (state.rejectedRequests.length < 64)
        state.rejectedRequests.push({
          method: req.method,
          path: url.pathname.slice(0, 128),
          keys: [...url.searchParams.keys()].map((key) => key.slice(0, 64)),
          hostMatches: url.searchParams.get('hostId') === target.hostId,
          threadMatches: url.searchParams.get('threadId') === threadId,
        });
    };
    const json = (status, body) =>
      res
        .writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
        .end(JSON.stringify(body));
    if (req.method !== 'GET' || req.headers.authorization !== 'Bearer ' + token) {
      rejectRequest();
      return json(403, { error: 'Read-only acceptance scope' });
    }
    const allowed = {
      '/healthz': [],
      '/v1/workspace': ['projectCursor', 'threadCursor', 'readerId'],
      '/v1/history': ['hostId', 'threadId', 'window', 'cursor', 'readerId'],
      '/v1/events': ['hostId', 'threadId', 'window', 'epoch', 'after', 'readerId'],
    };
    const keys = [...url.searchParams.keys()];
    if (
      !Object.hasOwn(allowed, url.pathname) ||
      new Set(keys).size !== keys.length ||
      keys.some((key) => !allowed[url.pathname].includes(key)) ||
      (url.pathname !== '/v1/workspace' &&
        url.pathname !== '/healthz' &&
        (url.searchParams.get('hostId') !== target.hostId ||
          url.searchParams.get('threadId') !== threadId))
    ) {
      rejectRequest();
      return json(403, { error: 'Target or operation rejected' });
    }
    const record = { path: url.pathname, startedAt: Date.now(), bytes: 0, outcome: 'pending' };
    state.requests.push(record);
    const abort = new AbortController();
    jobs.add(abort);
    const history = url.pathname === '/v1/history';
    if (history)
      state.maxHistoryInFlight = Math.max(state.maxHistoryInFlight, ++state.historyInFlight);
    res.once('close', () => {
      abort.abort();
      jobs.delete(abort);
      if (history) state.historyInFlight--;
      record.closedAt = Date.now();
      if (record.outcome === 'pending') record.outcome = 'client-closed';
    });
    try {
      const p = weakNetworkProfiles[selected];
      // A reproducible jitter sequence keeps runs comparable without fake time.
      const jitter = (((++sequence * 73) % 101) / 50 - 1) * p.jitterMS;
      record.injectedDelayMS = Math.max(0, p.rttMS + jitter);
      await delay(Math.max(0, p.rttMS + jitter), undefined, { signal: abort.signal });
      if (state.offline) {
        record.outcome = 'injected-outage';
        return json(503, { error: 'Injected link outage' });
      }
      if (history && stallNext) {
        stallNext = false;
        state.stalls++;
        record.injectedStall = true;
        record.stallStartedAt = Date.now();
        await delay(45000, undefined, { signal: abort.signal });
      }
      const upstreamStarted = performance.now();
      const upstream = await read(
        url.pathname + url.search,
        abort.signal,
        req.headers['accept-encoding'],
      );
      record.upstreamMS = performance.now() - upstreamStarted;
      if (res.destroyed) return;
      assert(upstream.status === 200, 'Upstream rejected read');
      let body = Buffer.from(upstream.body);
      assert(body.length <= 8 * 1024 * 1024, 'Upstream read exceeds acceptance bound');
      assert(!upstream.encoding || upstream.encoding === 'gzip', 'Unsupported upstream encoding');
      const decoded =
        upstream.encoding === 'gzip'
          ? gunzipSync(body, { maxOutputLength: 8 * 1024 * 1024 })
          : body;
      const value = JSON.parse(decoded);
      record.decodedBytes = decoded.length;
      record.contentEncoding = upstream.encoding ?? 'identity';
      if (url.pathname === '/healthz') assert(value.service === 'host-agent');
      else {
        assert(value.hostId === target.hostId, 'Upstream Host changed');
        if (value.threadId !== undefined)
          assert(value.threadId === threadId, 'Upstream thread changed');
        if (url.pathname === '/v1/workspace')
          assert(
            Object.entries(target).every(([k, v]) => value.readTarget?.[k] === v),
            'Upstream runtime changed',
          );
      }
      if (url.pathname === '/v1/workspace' && selectThreadFirst) {
        // Keep the complete real catalog. Only its initial selection order is
        // adapted so a new native client never requests an out-of-scope chat.
        assert(
          value.threads?.some((thread) => thread.id === threadId),
          'Scoped thread missing',
        );
        value.threads = [
          ...value.threads.filter((thread) => thread.id === threadId),
          ...value.threads.filter((thread) => thread.id !== threadId),
        ];
        const ordered = Buffer.from(JSON.stringify(value));
        body = upstream.encoding === 'gzip' ? gzipSync(ordered, { level: 1 }) : ordered;
        record.catalogSelectionReordered = true;
        record.decodedBytes = ordered.length;
      }
      const truncate = history && dropNext;
      if (truncate) {
        dropNext = false;
        state.drops++;
      }
      record.wireStartedAt = Date.now();
      res.writeHead(200, {
        'content-type': 'application/json',
        'content-length': body.length,
        'cache-control': 'no-store',
        ...(upstream.encoding ? { 'content-encoding': upstream.encoding } : {}),
      });
      for (let offset = 0; offset < body.length && !res.destroyed; offset += 512) {
        const part = body.subarray(offset, offset + (truncate ? 1 : 512));
        const slot = Math.max(performance.now(), nextByteAt);
        nextByteAt = slot + (part.length * 8) / p.kbps;
        await delay(Math.max(0, slot - performance.now()), undefined, { signal: abort.signal });
        if (res.destroyed) return;
        res.write(part);
        record.bytes += part.length;
        state.bytes += part.length;
        if (truncate) {
          record.outcome = 'injected-truncation';
          res.destroy();
          return;
        }
      }
      if (!res.destroyed) {
        record.outcome = 'complete';
        res.end();
      }
    } catch {
      if (res.destroyed || abort.signal.aborted) return;
      record.outcome = 'upstream-or-validation-failed';
      if (res.headersSent) res.destroy();
      else json(503, { error: 'Read unavailable' });
    }
  });
  return {
    server,
    token,
    metrics,
    configure(action) {
      if (Object.hasOwn(weakNetworkProfiles, action)) {
        selected = action;
        nextByteAt = performance.now();
      } else if (action === 'stall') stallNext = true;
      else if (action === 'drop') dropNext = true;
      else if (action === 'offline') state.offline = true;
      else if (action === 'recover') {
        state.offline = false;
        stallNext = false;
      } else throw Error('Unknown impairment');
    },
    async close() {
      for (const job of jobs) job.abort();
      server.closeAllConnections();
      await new Promise((done) => server.close(done));
    },
  };
}

// The upstream credential stays on the Mac. It is never returned to the test
// app, logged, passed on the command line, or accepted from a proxy request.
export function authenticatedRead(host) {
  const root = new URL(host.endpoint);
  assert(
    root.protocol === 'https:' &&
      root.pathname === '/' &&
      !root.username &&
      !root.password &&
      !root.search &&
      !root.hash,
  );
  return (path, signal, acceptEncoding) =>
    new Promise((done, reject) => {
      assert(path.startsWith('/'));
      const req = httpsRequest(
        new URL(path, root),
        {
          signal,
          headers: {
            Authorization: 'Bearer ' + host.clientKey,
            ...(typeof acceptEncoding === 'string' && acceptEncoding.length <= 256
              ? { 'Accept-Encoding': acceptEncoding }
              : {}),
          },
        },
        (res) => {
          const parts = [];
          let size = 0;
          res.on('data', (part) => {
            size += part.length;
            if (size > 8 * 1024 * 1024) req.destroy(Error('Read bound'));
            else parts.push(part);
          });
          res.on('end', () =>
            done({
              status: res.statusCode,
              body: Buffer.concat(parts),
              encoding: res.headers['content-encoding'],
            }),
          );
          res.on('error', reject);
        },
      );
      req.setTimeout(50000, () => req.destroy(Error('Upstream read deadline')));
      req.on('error', reject);
      req.end();
    });
}

async function main() {
  const hostId = process.env.WEAK_NETWORK_HOST_ID,
    threadId = process.env.WEAK_NETWORK_THREAD_ID;
  assert(hostId && threadId, 'Exact authorized Host and thread are required');
  const registry = JSON.parse(
    await readFile(join(homedir(), '.config/agent-hub/hosts.json'), 'utf8'),
  );
  const host = registry.hosts.find((item) => item.hostId === hostId);
  assert(host && host.hostId !== registry.ownerHostId, 'Remote authorized Host required');
  const target = { agentId: 'codex', hostId, workspaceId: hostId, runtimeId: 'desktop-runtime' };
  const read = authenticatedRead(host);
  const history = JSON.parse(
    (await read('/v1/history?' + new URLSearchParams({ hostId, threadId, window: 'latest-v1' })))
      .body,
  );
  assert(history.hostId === hostId && history.threadId === threadId && history.thread?.title);
  const proxy = createWeakNetworkReadProxy({ target, threadId, read, selectThreadFirst: true });
  await new Promise((done) => proxy.server.listen(0, '127.0.0.1', done));
  const control = createServer((req, res) => {
    const path = new URL(req.url, 'http://127.0.0.1').pathname;
    let value;
    if (req.method !== 'GET') return res.writeHead(405).end();
    if (path === '/fixture')
      value = {
        endpoint: 'http://127.0.0.1:' + proxy.server.address().port,
        token: proxy.token,
        hostId,
        threadId,
        threadTitle: history.thread.title,
        expectedText:
          process.env.WEAK_NETWORK_EXPECTED_TEXT ?? 'windows-cellular-reliability-20261020',
        nonce: 'weak-network-real-windows',
        secondEndpoint: '',
        secondToken: '',
        secondHostId: '',
        delayedEndpoint: '',
      };
    else if (path === '/stats') value = proxy.metrics();
    else if (path.startsWith('/fault/')) {
      try {
        proxy.configure(path.slice(7));
        value = {};
      } catch {
        return res.writeHead(400).end();
      }
    } else return res.writeHead(404).end();
    res
      .writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
      .end(JSON.stringify(value));
  });
  await new Promise((done) => control.listen(8897, '127.0.0.1', done));
  const directory = resolve(process.env.WEAK_NETWORK_OUTPUT ?? '.local/weak-network-real-windows');
  await mkdir(directory, { recursive: true, mode: 0o700 });
  console.log('Read-only real Windows impairment proxy ready on loopback control:8897');
  let closing = false;
  const close = async () => {
    if (closing) return;
    closing = true;
    await writeFile(
      join(directory, 'proxy-report.json'),
      JSON.stringify(
        {
          evidence:
            'real Windows HTTPS upstream; controlled aggregate HTTP response shaping; native client evidence recorded separately',
          target,
          threadId,
          ...proxy.metrics(),
        },
        null,
        2,
      ) + '\n',
      { mode: 0o600 },
    );
    control.closeAllConnections();
    await new Promise((done) => control.close(done));
    await proxy.close();
  };
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => void close());
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  await main();
