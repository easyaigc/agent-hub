import assert from 'node:assert/strict';
import {
  createCipheriv,
  createDecipheriv,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  hkdfSync,
  randomBytes,
  randomUUID,
} from 'node:crypto';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile, chmod } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { PairingCase } from '../../pairing-ui/fixture.mjs';
import { canonical, hash, verifyProof } from '../../authorization-boundaries/fixture.mjs';

export const KIND = 'p8-synthetic-positive-commit-v1';
const exact = (value, keys) => assert.deepEqual(Object.keys(value).sort(), [...keys].sort());
const uuid = (value) =>
  assert.match(value, /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/);
const aad = (v) =>
  Buffer.from(
    canonical([
      1,
      v.bindingId,
      v.senderDeviceId,
      v.senderSessionId,
      v.recipientDeviceId,
      v.sequence,
      v.sentAt,
    ]),
  );
/** Independent RFC7748 synthetic desktop; no installed identity or external service. */
export function codec(binding) {
  const privateKey = createPrivateKey({
    key: Buffer.concat([
      Buffer.from('302e020100300506032b656e04220420', 'hex'),
      Buffer.from('5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb', 'hex'),
    ]),
    format: 'der',
    type: 'pkcs8',
  });
  assert.equal(createPublicKey(privateKey).export({ format: 'jwk' }).x, binding.desktopPublicKey);
  const secret = diffieHellman({
    privateKey,
    publicKey: createPublicKey({
      key: { kty: 'OKP', crv: 'X25519', x: binding.mobilePublicKey },
      format: 'jwk',
    }),
  });
  const key = (direction) =>
    Buffer.from(
      hkdfSync('sha256', secret, `easyai-remote:${binding.bindingId}`, `v1:${direction}`, 32),
    );
  const inbound = key('mobile-to-desktop'),
    outbound = key('desktop-to-mobile');
  const confirmation = Buffer.from(
    hkdfSync('sha256', secret, binding.bindingId, 'easyai-remote:key-confirmation:v1', 16),
  ).toString('base64url');
  secret.fill(0);
  return {
    confirmation,
    open(v, direction = 'mobile-to-desktop') {
      exact(v, [
        'version',
        'bindingId',
        'senderDeviceId',
        'senderSessionId',
        'recipientDeviceId',
        'sequence',
        'sentAt',
        'nonce',
        'ciphertext',
      ]);
      assert.equal(v.version, 1);
      assert.equal(v.bindingId, binding.bindingId);
      uuid(v.senderSessionId);
      assert.equal(
        v.senderDeviceId,
        direction === 'mobile-to-desktop' ? binding.mobileDeviceId : binding.desktopDeviceId,
      );
      assert.equal(
        v.recipientDeviceId,
        direction === 'mobile-to-desktop' ? binding.desktopDeviceId : binding.mobileDeviceId,
      );
      assert(Number.isSafeInteger(v.sequence) && v.sequence >= 1);
      assert(Number.isFinite(Date.parse(v.sentAt)));
      assert.equal(typeof v.sentAt, 'string');
      assert(v.sentAt.length <= 64);
      assert.match(v.nonce, /^[A-Za-z0-9_-]{16}$/);
      assert.match(v.ciphertext, /^[A-Za-z0-9_-]+$/);
      const nonce = Buffer.from(v.nonce, 'base64url'),
        bytes = Buffer.from(v.ciphertext, 'base64url');
      assert.equal(nonce.length, 12);
      assert(bytes.length >= 16 && bytes.length <= 65536);
      const c = createDecipheriv(
        'aes-256-gcm',
        direction === 'mobile-to-desktop' ? inbound : outbound,
        nonce,
      );
      c.setAAD(aad(v));
      c.setAuthTag(bytes.subarray(-16));
      return JSON.parse(Buffer.concat([c.update(bytes.subarray(0, -16)), c.final()]));
    },
    seal(payload, session, sequence, direction = 'desktop-to-mobile') {
      const v = {
        version: 1,
        bindingId: binding.bindingId,
        senderDeviceId:
          direction === 'desktop-to-mobile' ? binding.desktopDeviceId : binding.mobileDeviceId,
        senderSessionId: session,
        recipientDeviceId:
          direction === 'desktop-to-mobile' ? binding.mobileDeviceId : binding.desktopDeviceId,
        sequence,
        sentAt: new Date().toISOString(),
      };
      const nonce = randomBytes(12),
        c = createCipheriv(
          'aes-256-gcm',
          direction === 'desktop-to-mobile' ? outbound : inbound,
          nonce,
        );
      c.setAAD(aad(v));
      return {
        ...v,
        nonce: nonce.toString('base64url'),
        ciphertext: Buffer.concat([
          c.update(canonical(payload)),
          c.final(),
          c.getAuthTag(),
        ]).toString('base64url'),
      };
    },
    dispose() {
      inbound.fill(0);
      outbound.fill(0);
    },
  };
}
/** Only three fixed read methods. The last first-workspace read is explicitly held. */
export class CommitCase {
  constructor(id, vector) {
    uuid(id);
    this.id = id;
    this.pairing = new PairingCase(id, 'approve-block', vector);
    this.channels = new Map();
    this.pending = new Map();
    this.seen = new Set();
    this.transport = [];
    this.reads = [];
    this.errors = [];
    this.held = null;
    this.released = false;
    this.closed = false;
  }
  get peer() {
    return this.pairing.issuer.peers[0];
  }
  resolve(status) {
    assert.equal(status, 'approved');
    this.pairing.resolve(status);
  }
  send(channel, payload) {
    assert(this.pending.size < 128);
    const frame = this.crypto.seal(payload, channel.id, channel.next++);
    this.pending.set(canonical([frame.senderSessionId, frame.sequence]), frame);
  }
  response(channel, request, outcome = 'ok') {
    let result;
    if (request.method === 'runtime/context')
      result = {
        target: this.peer.binding.target,
        state: { runtimeConnected: true },
        capabilities: ['workspace:read'],
        connectionCandidates: [],
      };
    else if (request.method === 'project/list') result = { projects: [] };
    else result = { threads: [], nextCursor: null };
    this.send(channel, {
      kind: 'protocol',
      envelope: {
        protocolVersion: 2,
        kind: 'response',
        requestId: request.requestId,
        target: this.peer.binding.target,
        ok: outcome === 'ok',
        ...(outcome === 'ok'
          ? { result }
          : {
              error: {
                code: 'synthetic_final_read_refused',
                message: 'Synthetic final workspace read refused',
              },
            }),
      },
    });
  }
  release(outcome) {
    assert(['ok', 'error'].includes(outcome));
    assert(this.held && !this.released);
    this.released = true;
    this.response(this.held.channel, this.held.request, outcome);
    this.held = null;
  }
  auth(request) {
    const u = new URL(request.url);
    assert.equal(u.hostname, '127.0.0.1');
    assert.equal(u.protocol, 'http:');
    assert.equal(u.search, '');
    assert.equal(u.hash, '');
    assert(!u.username && !u.password);
    if (!u.pathname.startsWith('/v2/remote-access/relay/')) return this.pairing.auth(request);
    assert(
      [
        '/v2/remote-access/relay/envelopes',
        '/v2/remote-access/relay/pending',
        '/v2/remote-access/relay/acknowledgements',
      ].includes(u.pathname),
    );
    assert(this.transport.length < 1000);
    const p = this.peer;
    assert(p && this.pairing.resolution === 'approved' && !p.revoked);
    // Retired-case pumps receive an authentication rejection, never a new grant/operation.
    const token = request.authorization?.slice(5);
    if (!p.accesses.has(token))
      return { status: 403, reply: { error: { code: 'synthetic_foreign_grant' } } };
    assert.equal(request.authorization, 'DPoP ' + token);
    const proof = verifyProof(request.dpop, {
      method: request.method,
      url: request.url,
      token,
      body: request.raw,
      jwk: p.device.authPublicKey,
      seen: this.pairing.issuer.seen,
    });
    this.transport.push({
      method: request.method,
      path: u.pathname,
      bodyHash: hash(request.raw),
      tokenHash: hash(request.authorization),
      jti: proof.jti,
      thumbprint: proof.thumbprint,
      grantId: p.id,
    });
    this.crypto ??= codec(p.binding);
    if (u.pathname.endsWith('/pending')) {
      assert.equal(request.method, 'GET');
      assert.equal(request.raw, '');
      return { status: 200, reply: { envelopes: [...this.pending.values()] } };
    }
    assert.equal(request.method, 'POST');
    const v = JSON.parse(request.raw);
    if (u.pathname.endsWith('/acknowledgements')) {
      exact(v, ['envelopes']);
      assert(Array.isArray(v.envelopes) && v.envelopes.length <= 128);
      for (const ack of v.envelopes) {
        exact(ack, ['bindingId', 'senderDeviceId', 'senderSessionId', 'sequence']);
        assert.equal(ack.bindingId, p.binding.bindingId);
        assert.equal(ack.senderDeviceId, p.binding.desktopDeviceId);
        uuid(ack.senderSessionId);
        assert(Number.isSafeInteger(ack.sequence) && ack.sequence >= 1);
        assert(
          [...this.channels.values()].some(
            (c) => c.id === ack.senderSessionId && ack.sequence < c.next,
          ),
        );
        this.pending.delete(canonical([ack.senderSessionId, ack.sequence]));
      }
      return { status: 200, reply: { accepted: true } };
    }
    const payload = this.crypto.open(v);
    const key = canonical([v.senderSessionId, v.sequence]);
    assert(!this.seen.has(key));
    this.seen.add(key);
    let c = this.channels.get(v.senderSessionId);
    if (!c) {
      assert(this.channels.size < 12);
      c = { id: randomUUID(), next: 1, confirmed: false };
      this.channels.set(v.senderSessionId, c);
    }
    if (payload.kind === 'key-confirmation') {
      exact(payload, ['kind', 'value']);
      assert.equal(payload.value, this.crypto.confirmation);
      assert(!c.confirmed);
      c.confirmed = true;
      this.send(c, { kind: 'key-confirmation', value: this.crypto.confirmation });
    } else {
      exact(payload, ['kind', 'envelope']);
      assert.equal(payload.kind, 'protocol');
      assert(c.confirmed);
      const q = payload.envelope;
      exact(q, ['protocolVersion', 'kind', 'requestId', 'target', 'method', 'params']);
      assert.equal(q.protocolVersion, 2);
      assert.equal(q.kind, 'request');
      uuid(q.requestId);
      assert.equal(canonical(q.target), canonical(p.binding.target));
      assert(
        ['runtime/context', 'project/list', 'thread/list'].includes(q.method),
        'No command, model, shell, generic proxy or arbitrary read',
      );
      if (q.method === 'thread/list') assert.equal(canonical(q.params), canonical({ limit: 100 }));
      else exact(q.params, []);
      this.reads.push({
        method: q.method,
        requestId: q.requestId,
        targetHash: hash(canonical(q.target)),
        paramsHash: hash(canonical(q.params)),
        grantId: p.id,
      });
      if (q.method === 'thread/list' && !this.released) {
        assert(!this.held);
        this.held = { channel: c, request: q };
      } else this.response(c, q);
    }
    return { status: 200, reply: { accepted: true } };
  }
  stats() {
    return {
      caseId: this.id,
      resolution: this.pairing.resolution,
      held: this.held ? 1 : 0,
      released: this.released,
      requests: this.pairing.issuer.requests,
      transport: this.transport,
      reads: this.reads,
      errors: this.errors,
      realDesktopRequests: 0,
      realModelInputs: 0,
    };
  }
  close() {
    this.closed = true;
    this.crypto?.dispose();
  }
}
export async function startFixture(directory) {
  const folder = resolve(directory);
  await mkdir(folder, { mode: 0o700 });
  const sources = [
    new URL(import.meta.url),
    new URL('../../pairing-ui/fixture.mjs', import.meta.url),
    new URL('../../authorization-fence/phase2/fixture.mjs', import.meta.url),
    new URL('../../authorization-boundaries/fixture.mjs', import.meta.url),
  ];
  const graph = Object.fromEntries(
    await Promise.all(
      sources.map(async (u) => [
        new URL(u).pathname.split('/p8-easywork/')[1],
        hash(await readFile(u)),
      ]),
    ),
  );
  const asset = await readFile(
    new URL(
      '../../../../../../../apps/android/src/androidTest/assets/easywork-crypto-vector.json',
      import.meta.url,
    ),
  );
  const vector = JSON.parse(asset),
    instanceId = randomUUID(),
    sockets = new Set();
  let active = null,
    config;
  const send = (r, v, status = 200) => {
    r.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    r.end(JSON.stringify(v));
  };
  const body = async (r) => {
    let size = 0;
    const parts = [];
    for await (const p of r) {
      size += p.length;
      assert(size <= 65536);
      parts.push(p);
    }
    return Buffer.concat(parts).toString('utf8');
  };
  const auth = createServer(async (req, res) => {
    try {
      if (!active) return send(res, { error: { code: 'no_active_owned_case' } }, 403);
      const result = active.auth({
        method: req.method,
        url: new URL(req.url, config.authOrigin).href,
        raw: await body(req),
        authorization: req.headers.authorization,
        dpop: req.headers.dpop,
      });
      send(res, result.reply, result.status);
    } catch (e) {
      active?.errors.push(e.name);
      send(res, { error: { code: 'positive_fixture_refused' } }, 400);
    }
  });
  const control = createServer(async (req, res) => {
    try {
      const u = new URL(req.url, 'http://fixture.invalid');
      assert.equal(u.search, '');
      if (u.pathname === '/health' && req.method === 'GET')
        return send(res, { ...config, onlyLoopback: true, realDesktop: false, realModel: false });
      assert.equal(req.headers['x-p8-fixture'], instanceId);
      if (u.pathname === '/begin-case' && req.method === 'POST') {
        assert(!active);
        const v = JSON.parse(await body(req));
        exact(v, ['caseId']);
        active = new CommitCase(v.caseId, vector);
        return send(res, active.stats());
      }
      assert(active);
      if (u.pathname === '/stats' && req.method === 'GET') return send(res, active.stats());
      const v = JSON.parse(await body(req));
      assert.equal(v.caseId, active.id);
      if (u.pathname === '/resolve' && req.method === 'POST') {
        exact(v, ['caseId', 'status']);
        active.resolve(v.status);
        return send(res, active.stats());
      }
      if (u.pathname === '/release-read' && req.method === 'POST') {
        exact(v, ['caseId', 'outcome']);
        active.release(v.outcome);
        return send(res, active.stats());
      }
      if (u.pathname === '/end-case' && req.method === 'POST') {
        exact(v, ['caseId']);
        const value = active.stats();
        await writeFile(join(folder, active.id + '.json'), JSON.stringify(value, null, 2) + '\n', {
          mode: 0o600,
          flag: 'wx',
        });
        active.close();
        active = null;
        return send(res, value);
      }
      send(res, { error: 'No named operation' }, 404);
    } catch {
      send(res, { error: 'positive_control_refused' }, 400);
    }
  });
  for (const server of [auth, control])
    server.on('connection', (s) => {
      sockets.add(s);
      s.on('error', () => {});
      s.on('close', () => sockets.delete(s));
    });
  for (const s of [auth, control])
    await new Promise((ok, fail) => {
      s.once('error', fail);
      s.listen(0, '127.0.0.1', ok);
    });
  config = {
    version: 1,
    fixture: KIND,
    instanceId,
    sidecarPid: process.pid,
    startedAt: new Date().toISOString(),
    sourceFixtureSha256: hash(asset),
    sourceGraph: graph,
    sourceGraphSha256: hash(canonical(graph)),
    authOrigin: 'http://127.0.0.1:' + auth.address().port,
    controlOrigin: 'http://127.0.0.1:' + control.address().port,
  };
  await writeFile(join(folder, 'config.json'), JSON.stringify(config, null, 2) + '\n', {
    mode: 0o600,
  });
  await chmod(folder, 0o700);
  return {
    config,
    async close() {
      active?.close();
      for (const s of sockets) s.destroy();
      await Promise.all([auth, control].map((s) => new Promise((ok) => s.close(ok))));
    },
  };
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  assert.equal(process.argv[2], '--start');
  assert(process.argv[3]);
  const f = await startFixture(process.argv[3]);
  console.log(JSON.stringify(f.config));
  for (const sig of ['SIGINT', 'SIGTERM'])
    process.once(sig, async () => {
      await f.close();
      process.exit(0);
    });
}
