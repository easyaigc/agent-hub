import assert from 'node:assert/strict';
import test from 'node:test';
import { gzipSync } from 'node:zlib';
import { createWeakNetworkReadProxy } from './weak-network-read-proxy.mjs';

const target = {
  agentId: 'codex',
  hostId: 'test-host',
  workspaceId: 'test-host',
  runtimeId: 'desktop-runtime',
};
const threadId = 'test-thread';
const path = '/v1/history?hostId=test-host&threadId=test-thread&window=latest-v1';
async function start(read, options = {}) {
  const proxy = createWeakNetworkReadProxy({ target, threadId, read, ...options });
  await new Promise((done) => proxy.server.listen(0, '127.0.0.1', done));
  const root = 'http://127.0.0.1:' + proxy.server.address().port;
  const get = (path, options = {}) =>
    fetch(root + path, {
      ...options,
      headers: { Authorization: 'Bearer ' + proxy.token, ...options.headers },
    });
  return { proxy, get };
}

test('native initial selection keeps every authentic catalog entry and the exact scoped thread', async () => {
  const original = {
    hostId: target.hostId,
    readTarget: target,
    threads: [
      { id: 'other', title: 'untouched' },
      { id: threadId, title: 'actual scoped chat' },
    ],
    projects: [{ id: 'actual-project' }],
    nextThreadCursor: 'actual-cursor',
  };
  const { proxy, get } = await start(
    async () => ({ status: 200, body: gzipSync(JSON.stringify(original)), encoding: 'gzip' }),
    { selectThreadFirst: true },
  );
  try {
    const result = await (await get('/v1/workspace')).json();
    assert.deepEqual(result, { ...original, threads: [original.threads[1], original.threads[0]] });
    assert.equal(proxy.metrics().requests[0].catalogSelectionReordered, true);
    assert.equal(proxy.metrics().upstreamWrites, 0);
  } finally {
    await proxy.close();
  }
});

test('content negotiation is forwarded and shaped bytes retain real gzip encoding', async () => {
  const original = {
    hostId: target.hostId,
    threadId,
    messages: [{ text: 'actual upstream output '.repeat(5000) }],
  };
  const bytes = gzipSync(JSON.stringify(original), { level: 1 });
  let requested;
  const { proxy, get } = await start(async (_path, _signal, acceptEncoding) => {
    requested = acceptEncoding;
    return { status: 200, body: bytes, encoding: 'gzip' };
  });
  try {
    const response = await get(path, { headers: { 'Accept-Encoding': 'gzip' } });
    assert.equal(response.headers.get('content-encoding'), 'gzip');
    assert.deepEqual(await response.json(), original);
    assert.equal(requested, 'gzip');
    assert.equal(proxy.metrics().bytes, bytes.length);
    assert.equal(
      proxy.metrics().requests[0].decodedBytes,
      Buffer.byteLength(JSON.stringify(original)),
    );
    assert(bytes.length < Buffer.byteLength(JSON.stringify(original)) / 10);
  } finally {
    await proxy.close();
  }
});

test('aggregate bandwidth applies across concurrent real HTTP bodies', async () => {
  const body = JSON.stringify({
    hostId: target.hostId,
    threadId,
    messages: [{ text: 'x'.repeat(8192) }],
  });
  const { proxy, get } = await start(async () => ({ status: 200, body }));
  try {
    const start = performance.now();
    const result = await Promise.all([
      get(path).then((r) => r.text()),
      get(path).then((r) => r.text()),
    ]);
    const elapsed = performance.now() - start;
    assert.deepEqual(result, [body, body]);
    assert(
      elapsed >= 600 - 150 + ((Buffer.byteLength(body) * 2 - 1024) * 8) / 128,
      'Concurrent responses must share a 128 kbit/s budget after jittered RTT',
    );
    assert.equal(proxy.metrics().bytes, Buffer.byteLength(body) * 2);
  } finally {
    await proxy.close();
  }
});

test('wrong target, duplicate query, redirect-like paths and writes never reach upstream', async () => {
  let calls = 0;
  const { proxy, get } = await start(async () => {
    calls++;
    throw Error('must not forward');
  });
  try {
    for (const bad of [
      '/v1/replies',
      path.replace('test-thread', 'other'),
      path + '&hostId=test-host',
      '//other.invalid/v1/history',
      '/v1/history?hostId=test-host',
    ]) {
      assert.equal((await get(bad)).status, 403);
    }
    assert.equal((await get(path, { method: 'POST', body: '{}' })).status, 403);
    assert.equal((await get(path, { headers: { Authorization: 'wrong' } })).status, 403);
    assert.equal(calls, 0);
  } finally {
    await proxy.close();
  }
});

test('wrong upstream runtime fails closed; outage and actual truncated body recover', async () => {
  const body = JSON.stringify({
    hostId: target.hostId,
    threadId,
    messages: [{ text: 'a'.repeat(1024) }],
  });
  const { proxy, get } = await start(async (path) => ({
    status: 200,
    body: path.startsWith('/v1/workspace')
      ? JSON.stringify({ hostId: target.hostId, readTarget: { ...target, runtimeId: 'other' } })
      : body,
  }));
  try {
    proxy.configure('normal');
    assert.equal((await get('/v1/workspace')).status, 503);
    proxy.configure('offline');
    assert.equal((await get(path)).status, 503);
    proxy.configure('recover');
    proxy.configure('drop');
    await assert.rejects(async () => (await get(path)).text());
    assert.equal(await (await get(path)).text(), body);
    assert.equal(proxy.metrics().drops, 1);
    assert.equal(proxy.metrics().upstreamWrites, 0);
  } finally {
    await proxy.close();
  }
});

test('native event target is preserved and historical account metadata does not change the machine', async () => {
  const original = { hostId: target.hostId, threadId, events: [] };
  const paths = [];
  const { proxy, get } = await start(
    async (route) => {
      paths.push(route);
      return { status: 200, body: JSON.stringify(original) };
    },
    { profile: 'normal' },
  );
  try {
    for (const scoped of [
      target,
      { ...target, account: { authority: 'historical', subject: 'changed-account' } },
    ]) {
      const route =
        '/v1/events?' +
        new URLSearchParams({
          hostId: target.hostId,
          threadId,
          window: 'latest-v1',
          epoch: 'synthetic-epoch',
          after: '7',
          target: JSON.stringify(scoped),
        });
      const response = await get(route);
      assert.equal(response.status, 200);
      assert.deepEqual(await response.json(), original);
      assert.equal(paths.at(-1), route);
    }
    assert.equal(proxy.metrics().upstreamWrites, 0);
  } finally {
    await proxy.close();
  }
});

test('event target guards reject malformed or different machines before any upstream read', async () => {
  let calls = 0;
  const { proxy, get } = await start(
    async () => {
      calls++;
      throw Error('must not forward');
    },
    { profile: 'normal' },
  );
  try {
    const bad = [
      '{',
      'null',
      '[]',
      ...['agentId', 'hostId', 'workspaceId', 'runtimeId'].map((key) =>
        JSON.stringify({ ...target, [key]: 'wrong' }),
      ),
      JSON.stringify({ ...target, unknown: 'forbidden' }),
      JSON.stringify({ ...target, account: { authority: '', subject: 'invalid' } }),
    ];
    for (const value of bad) {
      const route =
        '/v1/events?' +
        new URLSearchParams({
          hostId: target.hostId,
          threadId,
          window: 'latest-v1',
          epoch: 'synthetic-epoch',
          after: '7',
          target: value,
        });
      assert.equal((await get(route)).status, 403);
    }
    assert.equal(calls, 0);
  } finally {
    await proxy.close();
  }
});

test('workspace account metadata is shape-checked but never becomes a Codex machine gate', async () => {
  let readTarget = { ...target, account: { authority: 'new-provider', subject: 'new-account' } };
  const { proxy, get } = await start(
    async () => ({ status: 200, body: JSON.stringify({ hostId: target.hostId, readTarget }) }),
    {
      target: { ...target, account: { authority: 'historical', subject: 'old-account' } },
      profile: 'normal',
    },
  );
  try {
    assert.equal((await get('/v1/workspace')).status, 200);
    readTarget = { ...target };
    assert.equal((await get('/v1/workspace')).status, 200);
    readTarget = { ...target, account: { authority: '', subject: 'bad' } };
    assert.equal((await get('/v1/workspace')).status, 503);
    readTarget = { ...target, workspaceId: 'other-machine-workspace' };
    assert.equal((await get('/v1/workspace')).status, 503);
    assert.equal(proxy.metrics().upstreamWrites, 0);
  } finally {
    await proxy.close();
  }
});
