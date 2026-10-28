import assert from 'node:assert/strict';
import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  hkdfSync,
  randomBytes,
  randomUUID,
  X509Certificate,
} from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createServer as createHTTP } from 'node:http';
import { createServer as createHTTPS } from 'node:https';
import { chmod, mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

export const FIXTURE_KIND = 'p8-synthetic-loopback-transport';
export const PUBLIC_TOKEN = 'public-p8-loopback-transport-token';
export const MODES = [
  'normal',
  'wrong-descriptor-target',
  'wrong-response-target',
  'drop-read',
  'drop-write',
  'drop-write-unknown',
  'relay-foreign-binding',
  'relay-redirect',
  'relay-drop-write',
];
const root = new URL('../../../../../../', import.meta.url);
const digest = (value) => createHash('sha256').update(value).digest('hex');
export const canonical = (value) => JSON.stringify(normalize(value));
function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (value && typeof value === 'object')
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, normalize(value[key])]),
    );
  return value;
}

/** RFC 7748 public synthetic peer; never reads an installed identity or service. */
export function makeCodec(vector) {
  const binding = vector.credential.binding;
  assert.equal(binding.bindingId, 'binding-vector');
  assert.equal(binding.desktopDeviceId, 'desktop-vector');
  assert.equal(binding.mobileDeviceId, 'mobile-vector');
  const desktop = createPrivateKey({
    key: Buffer.concat([
      Buffer.from('302e020100300506032b656e04220420', 'hex'),
      Buffer.from('5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb', 'hex'),
    ]),
    format: 'der',
    type: 'pkcs8',
  });
  assert.equal(createPublicKey(desktop).export({ format: 'jwk' }).x, binding.desktopPublicKey);
  const mobile = createPublicKey({
    key: { kty: 'OKP', crv: 'X25519', x: binding.mobilePublicKey },
    format: 'jwk',
  });
  const secret = diffieHellman({ privateKey: desktop, publicKey: mobile });
  const key = (direction) =>
    Buffer.from(
      hkdfSync('sha256', secret, `easyai-remote:${binding.bindingId}`, `v1:${direction}`, 32),
    );
  const inbound = key('mobile-to-desktop'),
    outbound = key('desktop-to-mobile');
  const confirmation = Buffer.from(
    hkdfSync('sha256', secret, binding.bindingId, 'easyai-remote:key-confirmation:v1', 16),
  ).toString('base64url');
  assert.equal(confirmation, vector.confirmation);
  secret.fill(0);
  const aad = (value) =>
    Buffer.from(
      canonical([
        1,
        value.bindingId,
        value.senderDeviceId,
        ...(value.senderSessionId ? [value.senderSessionId] : []),
        value.recipientDeviceId,
        value.sequence,
        value.sentAt,
      ]),
    );
  function open(value, direction = 'mobile-to-desktop') {
    assert.equal(value.version, 1);
    assert.equal(value.bindingId, binding.bindingId);
    assert(Number.isSafeInteger(value.sequence) && value.sequence >= 1);
    assert.equal(
      value.senderDeviceId,
      direction === 'mobile-to-desktop' ? binding.mobileDeviceId : binding.desktopDeviceId,
    );
    assert.equal(
      value.recipientDeviceId,
      direction === 'mobile-to-desktop' ? binding.desktopDeviceId : binding.mobileDeviceId,
    );
    const bytes = Buffer.from(value.ciphertext, 'base64url'),
      nonce = Buffer.from(value.nonce, 'base64url');
    assert.equal(nonce.length, 12);
    assert(bytes.length >= 16);
    const cipher = createDecipheriv(
      'aes-256-gcm',
      direction === 'mobile-to-desktop' ? inbound : outbound,
      nonce,
    );
    cipher.setAAD(aad(value));
    cipher.setAuthTag(bytes.subarray(-16));
    return JSON.parse(Buffer.concat([cipher.update(bytes.subarray(0, -16)), cipher.final()]));
  }
  function seal(payload, session, sequence, direction = 'desktop-to-mobile') {
    const value = {
      version: 1,
      bindingId: binding.bindingId,
      senderDeviceId:
        direction === 'desktop-to-mobile' ? binding.desktopDeviceId : binding.mobileDeviceId,
      senderSessionId: session,
      recipientDeviceId:
        direction === 'desktop-to-mobile' ? binding.mobileDeviceId : binding.desktopDeviceId,
      sequence,
      sentAt: '2026-10-28T00:00:00.000Z',
    };
    const nonce = randomBytes(12),
      cipher = createCipheriv(
        'aes-256-gcm',
        direction === 'desktop-to-mobile' ? outbound : inbound,
        nonce,
      );
    cipher.setAAD(aad(value));
    return {
      ...value,
      nonce: nonce.toString('base64url'),
      ciphertext: Buffer.concat([
        cipher.update(canonical(payload)),
        cipher.final(),
        cipher.getAuthTag(),
      ]).toString('base64url'),
    };
  }
  return {
    binding,
    confirmation,
    open,
    seal,
    dispose() {
      inbound.fill(0);
      outbound.fill(0);
    },
  };
}

/** Small bounded RFC6455 fixture framing. No compression/fragmentation negotiation. */
export function serverFrame(payload, opcode = 1) {
  const bytes = Buffer.from(payload);
  assert(bytes.length <= 65535);
  if (bytes.length < 126) return Buffer.concat([Buffer.from([0x80 | opcode, bytes.length]), bytes]);
  const header = Buffer.alloc(4);
  header[0] = 0x80 | opcode;
  header[1] = 126;
  header.writeUInt16BE(bytes.length, 2);
  assert(bytes.length <= 65535);
  return Buffer.concat([header, bytes]);
}
export function parseClientFrames(input) {
  assert(input.length <= 131072);
  const frames = [];
  let offset = 0;
  while (input.length - offset >= 2) {
    const first = input[offset],
      second = input[offset + 1];
    assert((first & 0xf0) === 0x80, 'Only final uncompressed frames are allowed');
    assert(second & 0x80, 'Client WebSocket frames must be masked');
    const opcode = first & 15;
    assert([1, 2, 8, 9, 10].includes(opcode));
    let size = second & 127,
      prefix = 2;
    if (size === 126) {
      if (input.length - offset < 4) break;
      size = input.readUInt16BE(offset + 2);
      prefix = 4;
    }
    if (size === 127) {
      if (input.length - offset < 10) break;
      const long = input.readBigUInt64BE(offset + 2);
      assert(long <= 65535n);
      size = Number(long);
      prefix = 10;
    }
    assert(size <= 65535);
    if (opcode >= 8) assert(size <= 125);
    if (input.length - offset < prefix + 4 + size) break;
    const mask = input.subarray(offset + prefix, offset + prefix + 4),
      payload = Buffer.from(input.subarray(offset + prefix + 4, offset + prefix + 4 + size));
    for (let i = 0; i < size; i++) payload[i] ^= mask[i % 4];
    frames.push({ opcode, payload });
    offset += prefix + 4 + size;
  }
  return { frames, remaining: Buffer.from(input.subarray(offset)) };
}

/** Starts only when the coordinator explicitly calls this after allocating the loopback lease. */
export async function startFixture(directory) {
  const folder = resolve(directory);
  await mkdir(folder, { mode: 0o700 });
  // Reuse the RSA-2048/SAN loopback generator from scripts/mobile/android-discovery-fixture.mjs.
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-days',
      '2',
      '-subj',
      '/CN=localhost',
      '-addext',
      'subjectAltName=IP:127.0.0.1,DNS:localhost',
      '-keyout',
      join(folder, 'key.pem'),
      '-out',
      join(folder, 'certificate.pem'),
    ],
    { stdio: 'ignore' },
  );
  for (const name of ['key.pem', 'certificate.pem']) await chmod(join(folder, name), 0o600);
  const cert = await readFile(join(folder, 'certificate.pem')),
    key = await readFile(join(folder, 'key.pem'));
  const vectorBytes = await readFile(
    new URL('apps/android/src/androidTest/assets/easywork-crypto-vector.json', root),
  );
  const vector = JSON.parse(vectorBytes),
    codec = makeCodec(vector),
    instanceId = randomUUID();
  const fingerprint = digest(new X509Certificate(cert).raw),
    sourceFixtureSha256 = digest(vectorBytes);
  const sockets = new Set();
  let active, config;
  const fresh = (mode, caseId) => ({
    mode,
    caseId,
    hostReads: 0,
    upgrades: 0,
    upgradeRejections: 0,
    relayResponseDrops: 0,
    initialWssRejected: false,
    tlsFailures: 0,
    relayPosts: 0,
    relayPolls: 0,
    acknowledgements: [],
    foreignDelivered: 0,
    foreignAcknowledged: 0,
    redirectedRequests: 0,
    authorizationRequests: 0,
    protocol: [],
    dropped: false,
    pending: [],
    peers: new Map(),
    errors: [],
    original: null,
    realDesktopRequests: 0,
    realModelInputs: 0,
  });
  active = fresh('normal', randomUUID());
  function stats(state) {
    return {
      fixture: FIXTURE_KIND,
      instanceId,
      caseId: state.caseId,
      mode: state.mode,
      hostReads: state.hostReads,
      upgrades: state.upgrades,
      upgradeRejections: state.upgradeRejections,
      relayResponseDrops: state.relayResponseDrops,
      tlsFailures: state.tlsFailures,
      relayPosts: state.relayPosts,
      relayPolls: state.relayPolls,
      acknowledgements: state.acknowledgements,
      foreignDelivered: state.foreignDelivered,
      foreignAcknowledged: state.foreignAcknowledged,
      redirectedRequests: state.redirectedRequests,
      authorizationRequests: state.authorizationRequests,
      protocol: state.protocol,
      pendingCount: state.pending.length,
      errors: state.errors,
      realDesktopRequests: 0,
      realModelInputs: 0,
    };
  }
  function peer(state, mobileSession) {
    if (!state.peers.has(mobileSession))
      state.peers.set(mobileSession, { session: randomUUID(), next: 1 });
    return state.peers.get(mobileSession);
  }
  function processEnvelope(state, value, path) {
    const payload = codec.open(value),
      sender = peer(state, value.senderSessionId);
    const seal = (body) => codec.seal(body, sender.session, sender.next++);
    if (payload.kind === 'key-confirmation') {
      assert.equal(payload.value, codec.confirmation);
      return seal({ kind: 'key-confirmation', value: codec.confirmation });
    }
    assert.equal(payload.kind, 'protocol');
    const request = payload.envelope;
    assert.equal(request.protocolVersion, 2);
    assert.equal(request.kind, 'request');
    assert.equal(canonical(request.target), canonical(codec.binding.target));
    assert(
      ['runtime/context', 'thread/read', 'turn/start', 'command/status'].includes(request.method),
      'Fixture rejects arbitrary methods',
    );
    const intentHash = digest(
      canonical({ target: request.target, method: request.method, params: request.params }),
    );
    state.protocol.push({
      path,
      method: request.method,
      requestId: request.requestId,
      operationId: request.params.clientRequestId ?? null,
      commandId: request.params.commandId ?? null,
      wireRequestHash: digest(canonical(request)),
      intentHash,
    });
    let result = { proof: 'synthetic-loopback-only', path };
    if (request.method === 'turn/start') state.original = { request, intentHash };
    const shouldDrop =
      !state.dropped &&
      ((path === 'lan-wss' &&
        ((state.mode === 'drop-read' && request.method === 'thread/read') ||
          (['drop-write', 'drop-write-unknown'].includes(state.mode) &&
            request.method === 'turn/start'))) ||
        (path === 'relay-http' &&
          state.mode === 'relay-drop-write' &&
          request.method === 'turn/start'));
    if (shouldDrop) {
      state.dropped = true;
      return null;
    }
    if (request.method === 'command/status') {
      assert(state.original, 'No synthetic original operation');
      assert.equal(request.params.commandId, state.original.request.params.clientRequestId);
      result =
        state.mode === 'drop-write-unknown'
          ? { state: 'uncertain' }
          : {
              state: 'accepted',
              operationId: state.original.request.params.clientRequestId,
              method: state.original.request.method,
              requestHash: state.original.intentHash,
              target: state.original.request.target,
              response: { ok: true, result: { receipt: 'original-synthetic-once' } },
            };
    }
    const target =
      state.mode === 'wrong-response-target'
        ? { ...codec.binding.target, runtimeId: 'wrong-synthetic-runtime' }
        : codec.binding.target;
    return seal({
      kind: 'protocol',
      envelope: {
        protocolVersion: 2,
        kind: 'response',
        requestId: request.requestId,
        target,
        ok: true,
        result,
      },
    });
  }
  const send = (response, value, status = 200, headers = {}) => {
    response.writeHead(status, {
      'content-type': 'application/json',
      'cache-control': 'no-store',
      ...headers,
    });
    response.end(JSON.stringify(value));
  };
  async function body(request) {
    const chunks = [];
    let size = 0;
    for await (const part of request) {
      size += part.length;
      assert(size <= 65536);
      chunks.push(part);
    }
    return chunks.length ? JSON.parse(Buffer.concat(chunks)) : {};
  }
  function track(server) {
    server.on('connection', (socket) => {
      sockets.add(socket);
      socket.on('error', () => {});
      socket.on('close', () => sockets.delete(socket));
    });
  }
  async function relayRequest(state, request, response, pathname) {
    state.authorizationRequests++;
    if (request.headers.authorization !== 'Bearer ' + PUBLIC_TOKEN)
      return send(response, { error: 'synthetic-token-only' }, 401);
    if (state.mode === 'relay-redirect')
      return send(response, {}, 302, {
        location: config.controlOrigin + '/forbidden-redirect-target',
      });
    if (pathname === '/v1/remote-access/relay/envelopes' && request.method === 'POST') {
      state.relayPosts++;
      const value = await body(request);
      const reply = processEnvelope(state, value, 'relay-http');
      if (reply === null) {
        state.relayResponseDrops++;
        response.destroy();
        return;
      }
      state.pending.push(reply);
      return send(response, {});
    }
    if (pathname === '/v1/remote-access/relay/pending' && request.method === 'GET') {
      state.relayPolls++;
      const envelopes = state.pending.splice(0);
      if (state.mode === 'relay-foreign-binding' && !state.foreignDelivered && envelopes.length) {
        state.foreignDelivered++;
        envelopes.unshift({ ...envelopes[0], bindingId: 'foreign-synthetic-binding' });
      }
      return send(response, { envelopes });
    }
    if (pathname === '/v1/remote-access/relay/acknowledgements' && request.method === 'POST') {
      for (const entry of (await body(request)).envelopes) {
        if (entry.bindingId !== codec.binding.bindingId) state.foreignAcknowledged++;
        assert.equal(entry.bindingId, codec.binding.bindingId);
        assert.equal(entry.senderDeviceId, codec.binding.desktopDeviceId);
        assert([...state.peers.values()].some((p) => p.session === entry.senderSessionId));
        assert(Number.isSafeInteger(entry.sequence) && entry.sequence >= 1);
        state.acknowledgements.push(entry);
      }
      return send(response, {});
    }
    if (pathname === '/v1/remote-access/bindings' && request.method === 'GET')
      return send(response, { bindings: [codec.binding] });
    return send(response, { error: 'unregistered-fixture-path' }, 404);
  }
  async function dataRequest(request, response, tls) {
    const state = active;
    try {
      const url = new URL(request.url, 'https://fixture.invalid');
      if (tls && url.pathname === '/host' && request.method === 'GET') {
        state.hostReads++;
        const target =
          state.mode === 'wrong-descriptor-target'
            ? { ...codec.binding.target, runtimeId: 'wrong-synthetic-runtime' }
            : codec.binding.target;
        return send(response, {
          version: 1,
          fingerprint,
          publicKey: codec.binding.desktopPublicKey,
          target,
          endpoints: [config.tlsOrigin],
          endpointHints: [{ endpoint: config.tlsOrigin, networkKind: 'lan' }],
          connectionMode: 'automatic',
        });
      }
      return await relayRequest(state, request, response, url.pathname);
    } catch (error) {
      state.errors.push(error.name);
      if (!response.headersSent) send(response, { error: 'fixture-contract-refused' }, 400);
      else response.destroy();
    }
  }
  const tls = createHTTPS({ key, cert, minVersion: 'TLSv1.2' }, (request, response) => {
    void dataRequest(request, response, true);
  });
  const relay = createHTTP((request, response) => {
    void dataRequest(request, response, false);
  });
  const control = createHTTP(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://fixture.invalid');
      if (url.pathname === '/health' && request.method === 'GET')
        return send(response, {
          ...config,
          fixture: FIXTURE_KIND,
          onlyLoopback: true,
          realDesktop: false,
          realModel: false,
        });
      if (url.pathname === '/forbidden-redirect-target') {
        active.redirectedRequests++;
        return send(response, {});
      }
      assert.equal(request.headers['x-p8-fixture'], instanceId);
      if (url.pathname === '/begin-case' && request.method === 'POST') {
        const value = await body(request);
        assert(MODES.includes(value.mode));
        assert.match(value.caseId, /^[a-f0-9-]{36}$/);
        // Each invocation owns one fixture; snapshot old case stats before releasing its sockets.
        for (const socket of sockets) socket.destroy();
        active = fresh(value.mode, value.caseId);
        return send(response, stats(active));
      }
      if (url.pathname === '/stats' && request.method === 'GET')
        return send(response, stats(active));
      if (url.pathname === '/end-case' && request.method === 'POST') {
        const value = await body(request);
        assert.equal(value.caseId, active.caseId);
        await writeFile(
          join(folder, 'case-' + active.caseId + '.json'),
          JSON.stringify(stats(active), null, 2) + '\n',
          { mode: 0o600 },
        );
        return send(response, stats(active));
      }
      return send(response, { error: 'unregistered-control' }, 404);
    } catch {
      if (!response.headersSent) send(response, { error: 'fixture-control-refused' }, 400);
      else response.destroy();
    }
  });
  // Do not track control connections: resetting a case must not destroy its own response.
  track(tls);
  track(relay);
  tls.on('tlsClientError', () => {
    active.tlsFailures++;
  });
  tls.on('upgrade', (request, socket, head) => {
    const state = active;
    try {
      const url = new URL(request.url, 'https://fixture.invalid');
      assert.equal(url.pathname, '/channel');
      assert.equal(url.searchParams.get('bindingId'), codec.binding.bindingId);
      assert.equal(request.headers.upgrade?.toLowerCase(), 'websocket');
      if (state.mode === 'relay-drop-write' && !state.initialWssRejected) {
        state.initialWssRejected = true;
        state.upgradeRejections++;
        socket.end(
          'HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n',
        );
        return;
      }
      const accept = createHash('sha1')
        .update(request.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
        .digest('base64');
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' +
          accept +
          '\r\n\r\n',
      );
      state.upgrades++;
      let buffer = Buffer.alloc(0);
      const receive = (bytes) => {
        try {
          buffer = Buffer.concat([buffer, bytes]);
          const parsed = parseClientFrames(buffer);
          buffer = parsed.remaining;
          for (const frame of parsed.frames) {
            if (frame.opcode === 8) {
              socket.end(serverFrame(frame.payload, 8));
              return;
            }
            if (frame.opcode === 9) {
              socket.write(serverFrame(frame.payload, 10));
              continue;
            }
            if (frame.opcode === 10) continue;
            const reply = processEnvelope(
              state,
              JSON.parse(frame.payload.toString('utf8')),
              'lan-wss',
            );
            if (reply === null) {
              socket.destroy();
              return;
            }
            socket.write(serverFrame(Buffer.from(canonical(reply))));
          }
        } catch (error) {
          state.errors.push(error.name);
          socket.destroy();
        }
      };
      socket.on('data', receive);
      if (head.length) receive(head);
    } catch (error) {
      state.errors.push(error.name);
      socket.destroy();
    }
  });
  const listen = (server) =>
    new Promise((done, fail) => {
      server.once('error', fail);
      server.listen(0, '127.0.0.1', done);
    });
  try {
    await listen(tls);
    await listen(relay);
    await listen(control);
  } catch (error) {
    for (const socket of sockets) socket.destroy();
    for (const s of [tls, relay, control]) s.close();
    codec.dispose();
    throw error;
  }
  config = {
    version: 1,
    fixture: FIXTURE_KIND,
    instanceId,
    sourceFixtureSha256,
    tlsOrigin: `https://127.0.0.1:${tls.address().port}`,
    relayOrigin: `http://127.0.0.1:${relay.address().port}`,
    controlOrigin: `http://127.0.0.1:${control.address().port}`,
    certificateSha256: fingerprint,
    syntheticBearer: PUBLIC_TOKEN,
  };
  await writeFile(join(folder, 'config.json'), JSON.stringify(config, null, 2) + '\n', {
    mode: 0o600,
  });
  return {
    config,
    async close() {
      for (const socket of sockets) socket.destroy();
      await Promise.all(
        [tls, relay, control].map(
          (s) =>
            new Promise((done) => {
              s.close(done);
              s.closeAllConnections?.();
            }),
        ),
      );
      codec.dispose();
    },
  };
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  assert.equal(process.argv[2], '--start');
  assert(process.argv[3], 'Provide a new private fixture directory; coordinator lease required');
  const fixture = await startFixture(process.argv[3]);
  console.log(JSON.stringify({ status: 'synthetic-loopback-ready', ...fixture.config }));
  let closing = false;
  const close = async () => {
    if (closing) return;
    closing = true;
    await fixture.close();
    process.exit(0);
  };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
}
