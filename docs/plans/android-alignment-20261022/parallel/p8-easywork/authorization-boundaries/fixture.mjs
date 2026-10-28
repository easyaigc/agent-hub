import assert from 'node:assert/strict';
import {
  createCipheriv,
  createDecipheriv,
  createHash,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  randomBytes,
  randomUUID,
  verify,
} from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const KIND = 'p8-synthetic-ewdap-authorization';
export const CODES = ['0123456789ABCDEF', '123456789ABCDEF0'];
export const MODES = [
  'normal',
  'drop-claim-post',
  'drop-claim-poll',
  'claim-rejected',
  'claim-expired',
  'drop-refresh',
  'hold-refresh',
  'bad-refresh-target',
  'refresh-revoked',
  'drop-revoke',
];
export const MOBILE_PUBLIC = 'hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo';
const DESKTOP_PUBLIC = '3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08';
export const canonical = (value) => JSON.stringify(normalize(value));
function normalize(value) {
  if (Array.isArray(value)) return value.map(normalize);
  if (value && typeof value === 'object')
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((k) => [k, normalize(value[k])]),
    );
  return value;
}
export const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const b64hash = (bytes) => createHash('sha256').update(bytes).digest('base64url');
const uint32 = (n) => {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n);
  return b;
};
const deliveryKey = (secret) =>
  createHash('sha256')
    .update(
      Buffer.concat([
        uint32(1),
        secret,
        uint32(7),
        Buffer.from('A256GCM'),
        uint32(0),
        uint32(0),
        uint32(256),
      ]),
    )
    .digest();

/** Independent RFC7518 Concat KDF / compact JWE; recipient must be the public test key. */
export function sealDelivery(value, deliveryId, recipient = MOBILE_PUBLIC) {
  assert.equal(recipient, MOBILE_PUBLIC);
  const { privateKey, publicKey } = generateKeyPairSync('x25519');
  const peer = createPublicKey({ key: { kty: 'OKP', crv: 'X25519', x: recipient }, format: 'jwk' });
  const secret = diffieHellman({ privateKey, publicKey: peer }),
    key = deliveryKey(secret);
  secret.fill(0);
  try {
    const header = Buffer.from(
      canonical({
        alg: 'ECDH-ES',
        enc: 'A256GCM',
        typ: 'ewdap+jwe',
        version: 1,
        deliveryId,
        epk: publicKey.export({ format: 'jwk' }),
      }),
    ).toString('base64url');
    const nonce = randomBytes(12),
      cipher = createCipheriv('aes-256-gcm', key, nonce);
    cipher.setAAD(Buffer.from(header));
    const encrypted = Buffer.concat([
      cipher.update(canonical({ ...value, protocol: 'EWDAP/1', deliveryId })),
      cipher.final(),
    ]);
    return [
      header,
      '',
      nonce.toString('base64url'),
      encrypted.toString('base64url'),
      cipher.getAuthTag().toString('base64url'),
    ].join('.');
  } finally {
    key.fill(0);
  }
}
/** Node-only self-check with the already public RFC7748 private scalar. */
export function openPublicDelivery(compact, expectedId) {
  const parts = compact.split('.');
  assert.equal(parts.length, 5);
  assert.equal(parts[1], '');
  const header = JSON.parse(Buffer.from(parts[0], 'base64url'));
  assert.equal(header.deliveryId, expectedId);
  const privateKey = createPrivateKey({
    key: Buffer.concat([
      Buffer.from('302e020100300506032b656e04220420', 'hex'),
      Buffer.from('dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo', 'base64url'),
    ]),
    format: 'der',
    type: 'pkcs8',
  });
  const secret = diffieHellman({
      privateKey,
      publicKey: createPublicKey({ key: header.epk, format: 'jwk' }),
    }),
    key = deliveryKey(secret);
  secret.fill(0);
  try {
    const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(parts[2], 'base64url'));
    decipher.setAAD(Buffer.from(parts[0]));
    decipher.setAuthTag(Buffer.from(parts[4], 'base64url'));
    const value = JSON.parse(
      Buffer.concat([decipher.update(Buffer.from(parts[3], 'base64url')), decipher.final()]),
    );
    assert.equal(value.protocol, 'EWDAP/1');
    assert.equal(value.deliveryId, expectedId);
    return value;
  } finally {
    key.fill(0);
  }
}

export function verifyProof(
  proof,
  { method, url, token, body = '', jwk, seen = new Set(), now = Math.floor(Date.now() / 1000) },
) {
  const parts = proof.split('.');
  assert.equal(parts.length, 3);
  const header = JSON.parse(Buffer.from(parts[0], 'base64url')),
    payload = JSON.parse(Buffer.from(parts[1], 'base64url'));
  assert.equal(header.typ, 'dpop+jwt');
  assert.equal(header.alg, 'ES256');
  assert.equal(header.jwk.kty, 'EC');
  assert.equal(header.jwk.crv, 'P-256');
  assert(!('d' in header.jwk));
  if (jwk) assert.equal(canonical(header.jwk), canonical(jwk));
  assert(
    verify(
      'sha256',
      Buffer.from(parts[0] + '.' + parts[1]),
      { key: createPublicKey({ key: header.jwk, format: 'jwk' }), dsaEncoding: 'ieee-p1363' },
      Buffer.from(parts[2], 'base64url'),
    ),
  );
  assert.equal(payload.htm, method);
  const u = new URL(url);
  u.search = '';
  u.hash = '';
  assert.equal(payload.htu, u.href);
  assert(Number.isInteger(payload.iat) && Math.abs(now - payload.iat) <= 120);
  assert.match(payload.jti, /^[a-f0-9-]{36}$/i);
  assert(!seen.has(payload.jti), 'DPoP jti replay');
  if (token) assert.equal(payload.ath, b64hash(token));
  else assert(!('ath' in payload));
  if (body.length) assert.equal(payload.bht, b64hash(body));
  else assert(!('bht' in payload));
  seen.add(payload.jti);
  return { jti: payload.jti, thumbprint: b64hash(canonical(header.jwk)), jwk: header.jwk };
}

export async function startFixture(directory) {
  const folder = resolve(directory);
  await mkdir(folder, { mode: 0o700 });
  const asset = await readFile(
    new URL(
      '../../../../../../apps/android/src/androidTest/assets/easywork-crypto-vector.json',
      import.meta.url,
    ),
  );
  assert.equal(JSON.parse(asset).credential.binding.mobilePublicKey, MOBILE_PUBLIC);
  const instanceId = randomUUID(),
    sockets = new Set();
  let config;
  const fresh = (mode, caseId, testMethod = 'unassigned') => ({
    testMethod,
    mode,
    caseId,
    peers: new Map(),
    claims: new Map(),
    seen: new Set(),
    requests: [],
    errors: [],
    held: [],
    lateClosed: 0,
    lateReleased: 0,
    dropped: new Set(),
  });
  let active = fresh('normal', randomUUID());
  const stats = (s) => ({
    fixture: KIND,
    instanceId,
    caseId: s.caseId,
    testMethod: s.testMethod,
    mode: s.mode,
    requests: s.requests,
    errors: s.errors,
    held: s.held.length,
    heldClosed: s.held.filter((x) => x.res.destroyed).length,
    lateClosed: s.lateClosed,
    lateReleased: s.lateReleased,
    realDesktopRequests: 0,
    realModelInputs: 0,
  });
  const send = (res, value, status = 200) => {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(value));
  };
  const readBody = async (req) => {
    let size = 0;
    const parts = [];
    for await (const b of req) {
      size += b.length;
      assert(size <= 65536);
      parts.push(b);
    }
    return Buffer.concat(parts).toString('utf8');
  };
  const expiry = (seconds) => new Date(Date.now() + seconds * 1000).toISOString();
  function newPeer(s, device, index) {
    assert(s.peers.size < 2 && !s.peers.has(device.deviceId));
    const id = randomUUID(),
      target = {
        account: { authority: 'https://p8-synthetic.invalid', subject: 'public-test-user' },
        desktopDeviceId: 'p8-synthetic-desktop',
        workspaceId: 'p8-workspace-' + index,
        runtimeId: 'p8-runtime-' + index,
      };
    const thumbprint = b64hash(canonical(device.authPublicKey));
    const binding = {
      bindingId: randomUUID(),
      account: target.account,
      desktopDeviceId: target.desktopDeviceId,
      mobileDeviceId: device.deviceId,
      desktopPublicKey: DESKTOP_PUBLIC,
      mobilePublicKey: MOBILE_PUBLIC,
      target,
      status: 'active',
      deviceAuthorization: {
        protocol: 'EWDAP/1',
        grantId: id,
        authKeyThumbprint: thumbprint,
        keyVersion: 1,
      },
    };
    const p = {
      id,
      device,
      jwk: device.authPublicKey,
      binding,
      claimId: randomUUID(),
      offerId: randomUUID(),
      pendingToken: b64hash('public pending ' + device.deviceId),
      refreshes: new Map(),
      revocations: new Map(),
      accessTokens: new Set(),
      revoked: false,
      rotation: 0,
    };
    p.grant = grant(p);
    s.peers.set(device.deviceId, p);
    return p;
  }
  function grant(p) {
    const accessToken = b64hash('public access ' + p.id + ' ' + p.rotation);
    p.accessTokens.add(accessToken);
    return {
      protocol: 'EWDAP/1',
      grantId: p.id,
      deviceId: p.device.deviceId,
      binding: p.binding,
      target: p.binding.target,
      keyVersion: 1,
      scopes: ['relay:read', 'relay:write', 'runtime:read', 'runtime:control'],
      accessToken,
      refreshToken: b64hash('public refresh ' + p.id + ' ' + p.rotation),
      accessExpiresAt: expiry(600),
      refreshExpiresAt: expiry(3600),
    };
  }
  function once(s, key) {
    if (s.dropped.has(key)) return false;
    s.dropped.add(key);
    return true;
  }
  function proof(s, req, url, raw, token, jwk) {
    assert.equal(req.headers.authorization, token ? 'DPoP ' + token : undefined);
    return verifyProof(req.headers.dpop, {
      method: req.method,
      url,
      body: raw,
      token,
      jwk,
      seen: s.seen,
    });
  }
  const service = createServer(async (req, res) => {
    const s = active;
    try {
      const url = new URL(req.url, config.origin),
        path = url.pathname,
        raw = await readBody(req),
        body = raw ? JSON.parse(raw) : {};
      let p,
        verified,
        operationId = body.operationId ?? null,
        reply;
      assert(url.search === '');
      assert(s.requests.length < 100);
      if (operationId !== null) assert.match(operationId, /^[a-f0-9-]{36}$/i);
      if (path === '/v2/remote-access/pairing/claims' && req.method === 'POST') {
        assert(CODES.includes(body.connectionCode));
        assert.equal(body.device.platform, 'android');
        assert.equal(body.device.publicKey, MOBILE_PUBLIC);
        assert.match(body.device.deviceId, new RegExp('^p8-auth-' + s.caseId + '-[ab]$'));
        verified = proof(s, req, url.href, raw, null, body.device.authPublicKey);
        const prior = s.claims.get(operationId);
        if (prior) {
          assert.equal(prior.raw, raw);
          p = prior.peer;
        } else {
          p = newPeer(s, body.device, CODES.indexOf(body.connectionCode));
          s.claims.set(operationId, { raw, peer: p });
        }
        reply = {
          protocol: 'EWDAP/1',
          status: 'pending',
          claimId: p.claimId,
          offerId: p.offerId,
          expiresAt: expiry(300),
          verificationCode: 'PUBLIC-TEST',
          sealedDelivery: sealDelivery(
            { claimId: p.claimId, pendingToken: p.pendingToken, expiresAt: expiry(300) },
            p.claimId,
          ),
        };
      } else if (path.startsWith('/v2/remote-access/pairing/claims/') && req.method === 'GET') {
        const id = path.split('/').at(-1);
        p = [...s.peers.values()].find((x) => x.claimId === id);
        assert(p);
        verified = proof(s, req, url.href, raw, p.pendingToken, p.jwk);
        const status =
          s.mode === 'claim-rejected'
            ? 'rejected'
            : s.mode === 'claim-expired'
              ? 'expired'
              : 'approved';
        reply = {
          protocol: 'EWDAP/1',
          claimId: p.claimId,
          offerId: p.offerId,
          status,
          expiresAt: expiry(300),
          ...(status === 'approved' ? { sealedDelivery: sealDelivery(p.grant, p.claimId) } : {}),
        };
      } else if (path === '/v2/remote-access/device-grants/refresh' && req.method === 'POST') {
        const token = req.headers.authorization?.slice(5);
        p = [...s.peers.values()].find(
          (x) => x.grant.refreshToken === token || x.refreshes.get(operationId)?.token === token,
        );
        assert(p);
        verified = proof(s, req, url.href, raw, token, p.jwk);
        if (s.mode === 'refresh-revoked') reply = { error: { code: 'grant_revoked' } };
        else {
          const old = p.refreshes.get(operationId);
          if (old) {
            assert.equal(old.raw, raw);
            assert.equal(old.token, token);
            reply = old.reply;
          } else {
            p.rotation++;
            p.grant = grant(p);
            const value = structuredClone(p.grant);
            if (s.mode === 'bad-refresh-target') {
              value.target.runtimeId = 'wrong-synthetic-runtime';
            }
            reply = {
              protocol: 'EWDAP/1',
              grantId: p.id,
              sealedDelivery: sealDelivery(value, operationId),
            };
            p.refreshes.set(operationId, { raw, token, reply });
          }
        }
      } else if (path.startsWith('/v2/remote-access/device-grants/') && req.method === 'DELETE') {
        p = [...s.peers.values()].find((x) => x.id === path.split('/').at(-1));
        assert(p);
        const token = req.headers.authorization?.slice(5);
        assert(p.accessTokens.has(token));
        verified = proof(s, req, url.href, raw, token, p.jwk);
        if (p.revocations.has(operationId)) assert.equal(p.revocations.get(operationId), raw);
        else p.revocations.set(operationId, raw);
        p.revoked = true;
        reply = {
          protocol: 'EWDAP/1',
          grantId: p.id,
          bindingId: p.binding.bindingId,
          status: 'revoked',
        };
      } else if (path === '/v2/remote-access/bindings' && req.method === 'GET') {
        p = [...s.peers.values()].find(
          (x) => 'DPoP ' + x.grant.accessToken === req.headers.authorization,
        );
        assert(p);
        assert(!p.revoked);
        verified = proof(s, req, url.href, raw, p.grant.accessToken, p.jwk);
        reply = { bindings: [p.binding], synthetic: true };
      } else {
        return send(res, { error: { code: 'unregistered_fixture_path' } }, 404);
      }
      s.requests.push({
        method: req.method,
        path,
        operationId,
        bodyHash: hash(raw),
        tokenHash: hash(req.headers.authorization ?? ''),
        deviceId: p.device.deviceId,
        grantId: p.id,
        target: p.binding.target,
        jti: verified.jti,
        thumbprint: verified.thumbprint,
      });
      const drop =
        (s.mode === 'drop-claim-post' &&
          path.endsWith('/pairing/claims') &&
          req.method === 'POST') ||
        (s.mode === 'drop-claim-poll' &&
          req.method === 'GET' &&
          path.includes('/pairing/claims/')) ||
        (s.mode === 'drop-refresh' && path.endsWith('/refresh')) ||
        (s.mode === 'drop-revoke' && req.method === 'DELETE');
      if (drop && once(s, path + req.method)) {
        res.destroy();
        return;
      }
      if (s.mode === 'hold-refresh' && path.endsWith('/refresh') && once(s, 'held-refresh')) {
        s.held.push({ res, reply });
        return;
      }
      send(res, reply, s.mode === 'refresh-revoked' && path.endsWith('/refresh') ? 403 : 200);
    } catch (error) {
      s.errors.push(error.name);
      if (!res.headersSent) send(res, { error: { code: 'fixture_contract_refused' } }, 400);
      else res.destroy();
    }
  });
  const control = createServer(async (req, res) => {
    try {
      const path = new URL(req.url, 'http://fixture.invalid').pathname;
      if (path === '/health' && req.method === 'GET')
        return send(res, { ...config, onlyLoopback: true, realDesktop: false, realModel: false });
      assert.equal(req.headers['x-p8-fixture'], instanceId);
      if (path === '/begin-case' && req.method === 'POST') {
        const value = JSON.parse(await readBody(req));
        assert(MODES.includes(value.mode));
        assert.match(value.caseId, /^[a-f0-9-]{36}$/);
        for (const socket of sockets) socket.destroy();
        assert.match(value.testMethod, /^[A-Za-z0-9]{1,150}$/);
        active = fresh(value.mode, value.caseId, value.testMethod);
        return send(res, stats(active));
      }
      if (path === '/stats' && req.method === 'GET') return send(res, stats(active));
      if (path === '/release-held' && req.method === 'POST') {
        for (const held of active.held.splice(0)) {
          active.lateReleased++;
          if (held.res.destroyed) active.lateClosed++;
          else send(held.res, held.reply);
        }
        return send(res, stats(active));
      }
      if (path === '/end-case' && req.method === 'POST') {
        const value = JSON.parse(await readBody(req));
        assert.equal(value.caseId, active.caseId);
        await writeFile(
          join(folder, 'case-' + active.caseId + '.json'),
          JSON.stringify(stats(active), null, 2) + '\n',
          { mode: 0o600 },
        );
        return send(res, stats(active));
      }
      return send(res, {}, 404);
    } catch {
      if (!res.headersSent) send(res, {}, 400);
      else res.destroy();
    }
  });
  service.on('connection', (socket) => {
    sockets.add(socket);
    socket.on('error', () => {});
    socket.on('close', () => sockets.delete(socket));
  });
  const listen = (s) =>
    new Promise((done, fail) => {
      s.once('error', fail);
      s.listen(0, '127.0.0.1', done);
    });
  try {
    await listen(service);
    await listen(control);
  } catch (e) {
    service.close();
    control.close();
    throw e;
  }
  config = {
    version: 1,
    fixture: KIND,
    instanceId,
    sourceFixtureSha256: hash(asset),
    origin: `http://127.0.0.1:${service.address().port}`,
    controlOrigin: `http://127.0.0.1:${control.address().port}`,
  };
  await writeFile(join(folder, 'config.json'), JSON.stringify(config, null, 2) + '\n', {
    mode: 0o600,
  });
  return {
    config,
    async close() {
      for (const socket of sockets) socket.destroy();
      await Promise.all(
        [service, control].map(
          (s) =>
            new Promise((done) => {
              s.close(done);
              s.closeAllConnections?.();
            }),
        ),
      );
    },
  };
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  assert.equal(process.argv[2], '--start');
  assert(process.argv[3]);
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
