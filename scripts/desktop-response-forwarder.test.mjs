import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { deflateRawSync, createDeflateRaw, constants } from 'node:zlib';
import { Worker } from 'node:worker_threads';
import { startDesktopResponseForwarder } from '../packages/codex-adapter/dist/desktop-response-forwarder.js';
const sourceThread = randomUUID(),
  sourceTurn = randomUUID(),
  secret = 'fake-header-never-valid-' + randomUUID();
const bindingId = randomUUID(),
  basePath = '/_agent_hub/' + bindingId + '/backend-api';
const suggestion = '原生候选 中文𠮷 e\u0301\n完整第二行';
const payload = JSON.stringify({
  model: 'fixture',
  client_metadata: {
    'x-codex-turn-metadata': JSON.stringify({
      prediction_source_thread_id: sourceThread,
      prediction_source_turn_id: sourceTurn,
    }),
  },
});
const events = (id) => [
  { type: 'response.created', response: { id } },
  {
    type: 'response.output_item.done',
    item: {
      id: 'msg',
      type: 'message',
      role: 'assistant',
      content: [
        { type: 'output_text', text: JSON.stringify({ suggestion: ' ' + suggestion + ' ' }) },
      ],
    },
  },
  { type: 'response.completed', response: { id, status: 'completed' } },
];
const encode = (text, masked = false, flags = 0x81) => {
  const bytes = Buffer.from(text),
    mask = Buffer.from([1, 2, 3, 4]);
  const head = Buffer.alloc(bytes.length < 126 ? 2 : 4);
  head[0] = flags;
  head[1] = (masked ? 128 : 0) | (bytes.length < 126 ? bytes.length : 126);
  if (bytes.length >= 126) head.writeUInt16BE(bytes.length, 2);
  if (masked) for (let i = 0; i < bytes.length; i++) bytes[i] ^= mask[i % 4];
  return Buffer.concat([head, ...(masked ? [mask] : []), bytes]);
};
const compressedFrame = (value, masked = false) =>
  encode(
    deflateRawSync(Buffer.from(JSON.stringify(value)), {
      finishFlush: constants.Z_SYNC_FLUSH,
    }).subarray(0, -4),
    masked,
    0xc1,
  );
async function compressor() {
  const z = createDeflateRaw();
  return {
    async next(value) {
      const chunks = [];
      const onData = (b) => chunks.push(b);
      z.on('data', onData);
      z.write(Buffer.from(JSON.stringify(value)));
      await new Promise((resolve) => z.flush(constants.Z_SYNC_FLUSH, resolve));
      z.removeListener('data', onData);
      return Buffer.concat(chunks).subarray(0, -4);
    },
    close() {
      z.close();
    },
  };
}
let mode = 'normal',
  upstreamClosed = false,
  providerCalls = 0,
  bridge;
const captured = [],
  audit = [],
  peers = new Set();
const provider = http.createServer((req, res) => {
  providerCalls++;
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    captured.push({
      body: Buffer.concat(chunks),
      authMatches: req.headers.authorization === 'Bearer ' + secret,
    });
    if (mode === 'passthrough') {
      captured.push({
        route: req.url,
        body: Buffer.concat(chunks),
        auth: req.headers.authorization,
        marker: req.headers['x-fixture-marker'],
      });
      res.writeHead(207, {
        'content-type': 'application/octet-stream',
        'x-fixture-response': 'retained',
      });
      res.end(Buffer.from([0, 255, 17, 128]));
      return;
    }
    if (mode === 'cancel') {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.write(': waiting\n\n');
      res.once('close', () => {
        upstreamClosed = true;
      });
      return;
    }
    if (mode === 'extended') {
      const value = JSON.parse(Buffer.concat(chunks).toString());
      const id = value.fixtureId;
      let responseEvents = events(id);
      if (id === 'empty')
        responseEvents[1].item.content[0].text = JSON.stringify({ suggestion: null });
      if (id === 'failed')
        responseEvents = [
          { type: 'response.created', response: { id } },
          { type: 'response.failed', response: { id } },
        ];
      const responseBody =
        id === 'budget'
          ? Buffer.alloc(4 * 1024 * 1024 + 1, 120)
          : Buffer.from(responseEvents.map((x) => `data: ${JSON.stringify(x)}\n\n`).join(''));
      setTimeout(
        () => {
          res.writeHead(200, { 'content-type': 'text/event-stream' });
          res.end(responseBody);
        },
        id === 'slow' ? 150 : 0,
      );
      return;
    }
    const body = Buffer.from(
      events('sse-response')
        .map((x) => `data: ${JSON.stringify(x)}\n\n`)
        .join(''),
    );
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    const split = body.indexOf(Buffer.from('原')) + 1;
    res.write(body.subarray(0, split));
    res.end(body.subarray(split));
  });
});
provider.on('upgrade', (req, socket) => {
  providerCalls++;
  peers.add(socket);
  socket.on('close', () => peers.delete(socket));
  socket.on('error', () => {});
  assert.equal(req.headers.authorization, 'Bearer ' + secret);
  if (mode === 'reject-ws') {
    socket.end('HTTP/1.1 401 Unauthorized\r\nContent-Length: 6\r\nConnection: close\r\n\r\ndenied');
    return;
  }
  const key = createHash('sha1')
    .update(req.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' +
      key +
      '\r\n' +
      (mode === 'compressed'
        ? 'Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover; client_no_context_takeover\r\n'
        : mode === 'context-network'
          ? 'Sec-WebSocket-Extensions: permessage-deflate\r\n'
          : '') +
      '\r\n',
  );
  if (mode === 'realtime-network') {
    captured.push({
      realtimeRoute: req.url,
      auth: req.headers.authorization,
      marker: req.headers['x-fixture-marker'],
    });
    socket.once('data', (bytes) => {
      captured.push({ realtimeFrame: bytes });
      socket.write(encode('realtime\0response'));
    });
    return;
  }
  if (mode === 'context-network') {
    const z = createDeflateRaw();
    let round = 0;
    const next = async (value) => {
      const parts = [];
      const onData = (b) => parts.push(b);
      z.on('data', onData);
      z.write(Buffer.from(JSON.stringify(value)));
      await new Promise((resolve) => z.flush(constants.Z_SYNC_FLUSH, resolve));
      z.removeListener('data', onData);
      return encode(Buffer.concat(parts).subarray(0, -4), false, 0xc1);
    };
    socket.on('close', () => z.close());
    socket.on('data', async (bytes) => {
      captured.push({ contextFrame: bytes });
      if (round++ === 0)
        socket.write(await next({ type: 'response.completed', response: { id: 'warmup' } }));
      else for (const event of events('network-context')) socket.write(await next(event));
    });
    return;
  }
  socket.once('data', (bytes) => {
    captured.push({ wsFrame: bytes });
    if (mode === 'reserved-frame') socket.write(encode('opaque-compressed', false, 0xc1));
    else
      for (const event of events(mode === 'compressed' ? 'compressed-response' : 'ws-response'))
        socket.write(
          mode === 'compressed' ? compressedFrame(event) : encode(JSON.stringify(event)),
        );
  });
});
const result = {
  startedAt: new Date().toISOString(),
  assertions: {},
  success: false,
  fixtureOnly: true,
};
try {
  await new Promise((resolve) => provider.listen(0, '127.0.0.1', resolve));
  bridge = await startDesktopResponseForwarder({
    port: 0,
    basePath,
    fixture: { upstream: `http://127.0.0.1:${provider.address().port}/backend` },
    onEvent: (e) => audit.push(e),
  });
  for (let n = 0; n < 50 && !bridge.stats().observerAlive; n++) await delay(10);
  for (const path of ['/health', basePath + '/health']) {
    const health = await (await fetch(`http://127.0.0.1:${bridge.port}${path}`)).json();
    assert.equal(health.ready, true);
    assert.equal(health.bindingId, bindingId);
    assert.equal(health.port, bridge.port);
  }
  result.assertions.healthMatchesListenerAndObserver = true;
  const post = await fetch(`http://127.0.0.1:${bridge.port}${basePath}/responses`, {
    method: 'POST',
    headers: { authorization: 'Bearer ' + secret },
    body: payload,
  });
  assert.equal(post.status, 200);
  const text = await post.text();
  await delay(150);
  assert.equal(
    text,
    events('sse-response')
      .map((x) => `data: ${JSON.stringify(x)}\n\n`)
      .join(''),
  );
  assert.equal(captured[0].body.toString(), payload);
  assert.equal(captured[0].authMatches, true);
  assert.equal(
    audit.find((x) => x.kind === 'prediction-result' && x.responseId === 'sse-response')?.text,
    suggestion,
  );
  result.assertions.httpForwardingAndObservation = true;
  async function ws() {
    return await new Promise((resolve, reject) => {
      const req = http.request({
        host: '127.0.0.1',
        port: bridge.port,
        path: basePath + '/responses',
        headers: {
          authorization: 'Bearer ' + secret,
          connection: 'Upgrade',
          upgrade: 'websocket',
          'sec-websocket-key': Buffer.from('0123456789abcdef').toString('base64'),
          'sec-websocket-version': '13',
        },
      });
      req.on('error', reject);
      req.on('response', (response) => {
        const parts = [];
        response.on('data', (c) => parts.push(c));
        response.on('end', () =>
          resolve({ status: response.statusCode, text: Buffer.concat(parts).toString() }),
        );
      });
      req.on('upgrade', (_response, socket, head) => {
        const parts = [head];
        socket.on('error', reject);
        socket.on('data', (c) => parts.push(c));
        const requestValue = { ...JSON.parse(payload), type: 'response.create' };
        const request =
          mode === 'compressed'
            ? compressedFrame(requestValue, true)
            : encode(JSON.stringify(requestValue), true);
        socket.write(request);
        setTimeout(() => {
          socket.destroy();
          resolve({ status: 101, bytes: Buffer.concat(parts), request });
        }, 200);
      });
      req.end();
    });
  }
  const received = await ws();
  await delay(150);
  assert.equal(received.status, 101);
  assert.deepEqual(
    received.bytes,
    Buffer.concat(events('ws-response').map((e) => encode(JSON.stringify(e)))),
  );
  assert.deepEqual(captured.find((x) => x.wsFrame)?.wsFrame, received.request);
  assert.equal(
    audit.find((x) => x.kind === 'prediction-result' && x.responseId === 'ws-response')?.text,
    suggestion,
  );
  result.assertions.wsFramesAndObservation = true;
  mode = 'reject-ws';
  const rejection = await ws();
  assert.equal(rejection.status, 401);
  assert.equal(rejection.text, 'denied');
  result.assertions.wsRejectionPreserved = true;
  mode = 'reserved-frame';
  const reserved = await ws();
  await delay(150);
  assert.deepEqual(reserved.bytes, encode('opaque-compressed', false, 0xc1));
  assert.ok(audit.some((e) => e.kind === 'observation-unavailable'));
  result.assertions.unsupportedObservationDoesNotChangeWire = true;
  mode = 'compressed';
  const compressed = await ws();
  await delay(150);
  assert.deepEqual(
    compressed.bytes,
    Buffer.concat(events('compressed-response').map((e) => compressedFrame(e))),
  );
  assert.equal(
    audit.find((x) => x.kind === 'prediction-result' && x.responseId === 'compressed-response')
      ?.text,
    suggestion,
  );
  result.assertions.negotiatedDeflateNetworkRoundTrip = true;
  mode = 'cancel';
  await new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port: bridge.port, path: basePath + '/responses', method: 'POST' },
      (res) =>
        res.once('data', () => {
          res.destroy();
          req.destroy();
          resolve();
        }),
    );
    req.on('error', (error) => {
      if (error.code !== 'ECONNRESET') reject(error);
    });
    req.end(payload);
  });
  for (let i = 0; i < 30 && !upstreamClosed; i++) await delay(50);
  assert.equal(upstreamClosed, true);
  result.assertions.clientCancelClosesUpstream = true;
  assert.equal(JSON.stringify(audit).includes(secret), false);
  result.assertions.noCredentialInAudit = true;
  assert.equal(providerCalls, 6);
  result.assertions.noRetryOrDuplicate = true;
  mode = 'extended';
  const freshSource = () => ({ thread: randomUUID(), turn: randomUUID() });
  const first = freshSource(),
    second = freshSource();
  const bodyFor = (id, source) =>
    JSON.stringify({
      fixtureId: id,
      ...(source
        ? {
            client_metadata: {
              'x-codex-turn-metadata': JSON.stringify({
                prediction_source_thread_id: source.thread,
                prediction_source_turn_id: source.turn,
              }),
            },
          }
        : {}),
    });
  const postFixture = (id, source) =>
    fetch(`http://127.0.0.1:${bridge.port}${basePath}/responses`, {
      method: 'POST',
      body: bodyFor(id, source),
    }).then((r) => r.arrayBuffer());
  const startIndex = audit.length;
  await Promise.all([postFixture('slow', first), postFixture('fast', second)]);
  await delay(200);
  const paired = audit.slice(startIndex).filter((e) => e.kind === 'prediction-result');
  assert.equal(paired.length, 2);
  for (const [id, source] of [
    ['slow', first],
    ['fast', second],
  ]) {
    const item = paired.find((e) => e.responseId === id);
    assert.equal(item?.sourceThreadId, source.thread);
    assert.equal(item?.sourceTurnId, source.turn);
    assert.equal(item?.text, suggestion);
    assert.equal(item?.state, 'completed');
    assert.ok(
      audit.some(
        (e) =>
          e.kind === 'prediction-start' &&
          e.requestId === item.requestId &&
          e.connectionId === item.connectionId,
      ),
    );
  }
  assert.equal(paired[0].responseId, 'fast');
  result.assertions.concurrentOutOfOrderSourceAssociation = true;
  const ordinaryIndex = audit.length;
  await postFixture('ordinary', null);
  await delay(100);
  assert.equal(
    audit
      .slice(ordinaryIndex)
      .some((e) => e.kind === 'prediction-start' || e.kind === 'prediction-result'),
    false,
  );
  result.assertions.ordinaryRequestNotPublished = true;
  for (const [id, expected] of [
    ['empty', 'empty'],
    ['failed', 'failed'],
  ]) {
    const index = audit.length;
    await postFixture(id, freshSource());
    await delay(100);
    const item = audit.slice(index).find((e) => e.kind === 'prediction-result');
    assert.equal(item?.state, expected);
    assert.equal(item?.text, null);
  }
  result.assertions.emptyAndFailureInvalidate = true;
  const budgetIndex = audit.length;
  const budgetBytes = await postFixture('budget', freshSource());
  await delay(100);
  assert.equal(budgetBytes.byteLength, 4 * 1024 * 1024 + 1);
  assert.ok(audit.slice(budgetIndex).some((e) => e.kind === 'observation-unavailable'));
  assert.equal(
    audit.slice(budgetIndex).some((e) => e.kind === 'prediction-result' && e.state === 'completed'),
    false,
  );
  result.assertions.budgetDisablesOnlyObservation = true;
  mode = 'passthrough';
  const passthroughIndex = audit.length;
  const routes = [
    '/images/generations',
    '/images/edits',
    '/memories/trace_summarize',
    '/alpha/search',
    '/realtime/calls',
    '/live',
  ];
  for (const route of routes) {
    const bytes =
      route === '/images/edits'
        ? Buffer.alloc(4 * 1024 * 1024 + 1, 173)
        : Buffer.from([0, 255, 1, 128]);
    const response = await fetch(`http://127.0.0.1:${bridge.port}${basePath}${route}?fixture=1`, {
      method: 'POST',
      headers: { authorization: 'Bearer ' + secret, 'x-fixture-marker': 'retained' },
      body: bytes,
    });
    assert.equal(response.status, 207);
    assert.equal(response.headers.get('x-fixture-response'), 'retained');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), Buffer.from([0, 255, 17, 128]));
    const received = captured.find((x) => x.route === '/backend' + route + '?fixture=1');
    assert.deepEqual(received?.body, bytes);
    assert.equal(received?.auth, 'Bearer ' + secret);
    assert.equal(received?.marker, 'retained');
  }
  assert.equal(
    (await fetch(`http://127.0.0.1:${bridge.port}${basePath}/not-allowed`, { method: 'POST' }))
      .status,
    404,
  );
  await delay(100);
  assert.equal(
    audit
      .slice(passthroughIndex)
      .some((e) => e.kind === 'prediction-start' || e.kind === 'prediction-result'),
    false,
  );
  result.assertions.additionalRoutesPassBytesStatusHeadersWithoutObservation = true;
  mode = 'realtime-network';
  for (const suffix of ['', '/realtime', '/live']) {
    const index = audit.length,
      frame = encode('realtime\0request', true);
    const received = await new Promise((resolve, reject) => {
      const req = http.request({
        host: '127.0.0.1',
        port: bridge.port,
        path: basePath + suffix + '?intent=fixture',
        headers: {
          authorization: 'Bearer ' + secret,
          'x-fixture-marker': 'realtime',
          connection: 'Upgrade',
          upgrade: 'websocket',
          'sec-websocket-key': Buffer.from('0123456789abcdef').toString('base64'),
          'sec-websocket-version': '13',
        },
      });
      req.on('error', reject);
      req.on('upgrade', (_response, socket, head) => {
        const chunks = [head];
        socket.on('error', reject);
        socket.on('data', (chunk) => chunks.push(chunk));
        socket.write(frame);
        setTimeout(() => {
          socket.destroy();
          resolve(Buffer.concat(chunks));
        }, 120);
      });
      req.end();
    });
    assert.deepEqual(received, encode('realtime\0response'));
    const route = captured.find((x) => x.realtimeRoute === '/backend' + suffix + '?intent=fixture');
    assert.equal(route?.auth, 'Bearer ' + secret);
    assert.equal(route?.marker, 'realtime');
    assert.deepEqual(captured.filter((x) => x.realtimeFrame).at(-1).realtimeFrame, frame);
    await delay(50);
    assert.equal(
      audit
        .slice(index)
        .some(
          (e) =>
            e.kind === 'prediction-start' ||
            e.kind === 'prediction-result' ||
            e.kind === 'observation-unavailable',
        ),
      false,
    );
  }
  result.assertions.realtimeWebsocketPathsTransparent = true;
  mode = 'context-network';
  const networkCompressor = await compressor();
  try {
    const warm = encode(
      await networkCompressor.next({ type: 'response.create', generate: false }),
      true,
      0xc1,
    );
    const compressedInput = await networkCompressor.next({
      ...JSON.parse(payload),
      type: 'response.create',
    });
    const fragmented = Buffer.concat([
      encode(compressedInput.subarray(0, 10), true, 0x41),
      encode(compressedInput.subarray(10), true, 0x80),
    ]);
    const index = audit.length;
    const clientBytes = await new Promise((resolve, reject) => {
      const req = http.request({
        host: '127.0.0.1',
        port: bridge.port,
        path: basePath + '/responses',
        headers: {
          authorization: 'Bearer ' + secret,
          connection: 'Upgrade',
          upgrade: 'websocket',
          'sec-websocket-key': Buffer.from('0123456789abcdef').toString('base64'),
          'sec-websocket-version': '13',
          'sec-websocket-extensions': 'permessage-deflate',
        },
      });
      req.on('error', reject);
      req.on('upgrade', (_response, socket, head) => {
        const chunks = [head];
        let sent = false;
        socket.on('error', reject);
        socket.on('data', (chunk) => {
          chunks.push(chunk);
          if (!sent) {
            sent = true;
            socket.write(fragmented);
          }
        });
        socket.write(warm);
        setTimeout(() => {
          socket.destroy();
          resolve(Buffer.concat(chunks));
        }, 400);
      });
      req.end();
    });
    await delay(100);
    assert.deepEqual(
      Buffer.concat(captured.filter((x) => x.contextFrame).map((x) => x.contextFrame)),
      Buffer.concat([warm, fragmented]),
    );
    assert.ok(clientBytes.length > 0);
    const item = audit.slice(index).find((e) => e.kind === 'prediction-result');
    assert.equal(item?.responseId, 'network-context');
    assert.equal(item?.text, suggestion);
    assert.equal(
      audit.slice(index).some((e) => e.kind === 'observation-unavailable'),
      false,
    );
    result.assertions.contextTakeoverWarmupAndFragmentationOverNetwork = true;
  } finally {
    networkCompressor.close();
  }
  // A throwaway certificate exercises the explicit CA fixture and real TLS verification.
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'agent-hub-forwarder-test-'));
  let tlsProvider, tlsBridge;
  try {
    const certPath = path.join(temp, 'fixture.crt'),
      keyPath = path.join(temp, 'fixture.key');
    const configPath = path.join(temp, 'openssl.cnf');
    fs.writeFileSync(
      configPath,
      '[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n[dn]\nCN=127.0.0.1\n[ext]\nsubjectAltName=IP:127.0.0.1\n',
    );
    execFileSync(
      'openssl',
      [
        'req',
        '-config',
        configPath,
        '-x509',
        '-newkey',
        'rsa:2048',
        '-nodes',
        '-keyout',
        keyPath,
        '-out',
        certPath,
        '-days',
        '1',
      ],
      { stdio: 'ignore' },
    );
    const ca = fs.readFileSync(certPath);
    tlsProvider = https.createServer({ key: fs.readFileSync(keyPath), cert: ca }, (_req, res) =>
      res.end('tls-fixture'),
    );
    await new Promise((resolve) => tlsProvider.listen(0, '127.0.0.1', resolve));
    tlsBridge = await startDesktopResponseForwarder({
      port: 0,
      basePath,
      fixture: { upstream: `https://127.0.0.1:${tlsProvider.address().port}/backend`, ca },
      onEvent: (e) => audit.push(e),
    });
    const response = await fetch(`http://127.0.0.1:${tlsBridge.port}${basePath}/models`);
    assert.equal(response.status, 200);
    assert.equal(await response.text(), 'tls-fixture');
    result.assertions.trustedTlsFixture = true;
    await tlsBridge.close();
    tlsBridge = await startDesktopResponseForwarder({
      port: 0,
      basePath,
      fixture: { upstream: `https://127.0.0.1:${tlsProvider.address().port}/backend` },
      onEvent: (e) => audit.push(e),
    });
    const rejectedTls = await fetch(`http://127.0.0.1:${tlsBridge.port}${basePath}/models`);
    assert.equal(rejectedTls.status, 502);
    result.assertions.untrustedTlsRejected = true;
  } finally {
    if (tlsBridge) await tlsBridge.close();
    if (tlsProvider) {
      tlsProvider.closeAllConnections();
      await new Promise((resolve) => tlsProvider.close(resolve));
    }
    fs.rmSync(temp, { recursive: true, force: true });
  }
  // Exercise the real observer with persistent dictionaries, warmup and masked
  // fragmented compressed input, independently from the fixture server.
  const observer = new Worker(
    new URL('../packages/codex-adapter/dist/desktop-response-observer.js', import.meta.url),
  );
  const observed = [];
  observer.on('message', (event) => observed.push(event));
  const clientCompressor = await compressor(),
    serverCompressor = await compressor();
  try {
    observer.postMessage({
      kind: 'ws-config',
      id: 'context',
      transport: 'ws',
      compression: {
        request: { noContextTakeover: false, windowBits: 15 },
        response: { noContextTakeover: false, windowBits: 15 },
      },
    });
    const send = (direction, chunk) =>
      observer.postMessage({ kind: 'frames', id: 'context', transport: 'ws', direction, chunk });
    send(
      'request',
      encode(await clientCompressor.next({ type: 'response.create', generate: false }), true, 0xc1),
    );
    send(
      'response',
      encode(
        await serverCompressor.next({ type: 'response.completed', response: { id: 'warmup' } }),
        false,
        0xc1,
      ),
    );
    const input = await clientCompressor.next({ ...JSON.parse(payload), type: 'response.create' });
    send('request', encode(input.subarray(0, 10), true, 0x41));
    send('request', encode(input.subarray(10), true, 0x80));
    for (const event of events('context-response'))
      send('response', encode(await serverCompressor.next(event), false, 0xc1));
    for (let i = 0; i < 40 && !observed.some((x) => x.kind === 'prediction-result'); i++)
      await delay(50);
    assert.equal(observed.find((x) => x.kind === 'prediction-result')?.text, suggestion);
    assert.equal(
      observed.some((x) => x.kind === 'observation-unavailable'),
      false,
    );
    result.assertions.contextTakeoverWarmupAndFragmentation = true;
  } finally {
    clientCompressor.close();
    serverCompressor.close();
    await observer.terminate();
  }
  assert.equal(JSON.stringify(audit).includes(secret), false);
  result.candidateCount = audit.filter((x) => x.kind === 'prediction-result').length;
  result.stats = bridge.stats();
  assert.equal(result.stats.bufferedBytes, 0);
  assert.equal(result.stats.pendingBytes, 0);
  result.success = true;
} catch (error) {
  result.failure = { name: error.name, message: error.message };
} finally {
  if (bridge) await bridge.close();
  for (const p of peers) p.destroy();
  provider.closeAllConnections();
  await new Promise((resolve) => provider.close(resolve));
}
result.finishedAt = new Date().toISOString();
result.scriptSha256 = createHash('sha256')
  .update(fs.readFileSync(fileURLToPath(import.meta.url)))
  .digest('hex');
console.log(JSON.stringify(result));
if (!result.success) process.exitCode = 1;
