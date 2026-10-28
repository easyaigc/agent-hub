import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  CODES,
  MOBILE_PUBLIC,
  canonical,
  hash,
  sealDelivery,
  verifyProof,
} from '../../authorization-boundaries/fixture.mjs';

export const KIND = 'p8-synthetic-authorization-fence';
export const MODES = ['normal', 'hold-refresh', 'hold-refresh-drop-revoke', 'drop-revoke'];
const token = (value) => createHash('sha256').update(value).digest('base64url');
const expiry = (n) => new Date(Date.now() + n * 1000).toISOString();

/** Pure synthetic issuer; dispatching never opens sockets or contacts a real service. */
export class Issuer {
  constructor(caseId, mode, testMethod = 'node-helper-check') {
    assert.match(caseId, /^[a-f0-9-]{36}$/);
    assert(MODES.includes(mode));
    Object.assign(this, {
      caseId,
      mode,
      testMethod,
      peers: [],
      claims: new Map(),
      seen: new Set(),
      requests: [],
      errors: [],
      heldOnce: false,
      dropped: false,
    });
  }
  newGrant(p) {
    const accessToken = token('public fence access ' + p.id + ' ' + p.rotation);
    p.accesses.add(accessToken);
    return {
      protocol: 'EWDAP/1',
      grantId: p.id,
      deviceId: p.device.deviceId,
      binding: p.binding,
      target: p.binding.target,
      keyVersion: 1,
      scopes: ['relay:read', 'relay:write', 'runtime:read', 'runtime:control'],
      accessToken,
      refreshToken: token('public fence refresh ' + p.id + ' ' + p.rotation),
      accessExpiresAt: expiry(600),
      refreshExpiresAt: expiry(3600),
    };
  }
  newPeer(device, code) {
    assert(this.peers.length < 2);
    const index = CODES.indexOf(code),
      id = randomUUID();
    const target = {
      account: { authority: 'https://p8-synthetic.invalid', subject: 'public-test-user' },
      desktopDeviceId: 'p8-fence-desktop-' + index,
      workspaceId: 'p8-fence-workspace-' + index,
      runtimeId: 'p8-fence-runtime-' + index,
    };
    const binding = {
      bindingId: randomUUID(),
      account: target.account,
      desktopDeviceId: target.desktopDeviceId,
      mobileDeviceId: device.deviceId,
      desktopPublicKey: '3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08',
      mobilePublicKey: MOBILE_PUBLIC,
      target,
      status: 'active',
      deviceAuthorization: {
        protocol: 'EWDAP/1',
        grantId: id,
        keyVersion: 1,
        authKeyThumbprint: token(canonical(device.authPublicKey)),
      },
    };
    const p = {
      id,
      device,
      binding,
      claimId: randomUUID(),
      offerId: randomUUID(),
      pending: token('public fence pending ' + id),
      accesses: new Set(),
      refreshes: new Map(),
      revokes: new Map(),
      rotation: 0,
      revoked: false,
    };
    p.grant = this.newGrant(p);
    this.peers.push(p);
    return p;
  }
  dispatch({ method, url, raw = '', authorization, dpop }) {
    const path = new URL(url).pathname,
      body = raw ? JSON.parse(raw) : {},
      operationId = body.operationId ?? null;
    assert.equal(new URL(url).hostname, '127.0.0.1');
    assert.equal(new URL(url).protocol, 'http:');
    assert.equal(new URL(url).search, '');
    assert(this.requests.length < 100);
    if (operationId !== null) assert.match(operationId, /^[a-f0-9-]{36}$/i);
    let p,
      proofToken,
      reply,
      verified,
      status = 200,
      drop = false,
      hold = false;
    const authenticate = (jwk, expectedToken) => {
      assert.equal(authorization, expectedToken ? 'DPoP ' + expectedToken : undefined);
      return verifyProof(dpop, {
        method,
        url,
        token: expectedToken,
        body: raw,
        jwk,
        seen: this.seen,
      });
    };
    if (path === '/v2/remote-access/pairing/claims' && method === 'POST') {
      assert(CODES.includes(body.connectionCode));
      assert.equal(body.device.platform, 'android');
      assert.equal(body.device.publicKey, MOBILE_PUBLIC);
      assert.match(body.device.deviceId, new RegExp('^p8-fence-' + this.caseId + '-[ab]$'));
      verified = authenticate(body.device.authPublicKey);
      const previous = this.claims.get(operationId);
      if (previous) {
        assert.equal(previous.raw, raw);
        p = previous.peer;
      } else {
        p = this.newPeer(body.device, body.connectionCode);
        this.claims.set(operationId, { raw, peer: p });
      }
      reply = {
        protocol: 'EWDAP/1',
        status: 'pending',
        claimId: p.claimId,
        offerId: p.offerId,
        expiresAt: expiry(300),
        sealedDelivery: sealDelivery(
          { claimId: p.claimId, pendingToken: p.pending, expiresAt: expiry(300) },
          p.claimId,
        ),
      };
    } else if (path.startsWith('/v2/remote-access/pairing/claims/') && method === 'GET') {
      p = this.peers.find((x) => x.claimId === path.split('/').at(-1));
      assert(p);
      proofToken = p.pending;
      verified = authenticate(p.device.authPublicKey, proofToken);
      // A previously approved delivery remains readable; local revocation must fence its save.
      reply = {
        protocol: 'EWDAP/1',
        claimId: p.claimId,
        offerId: p.offerId,
        status: 'approved',
        sealedDelivery: sealDelivery(p.grant, p.claimId),
      };
    } else if (path === '/v2/remote-access/device-grants/refresh' && method === 'POST') {
      proofToken = authorization?.slice(5);
      p = this.peers.find(
        (x) =>
          x.grant.refreshToken === proofToken || x.refreshes.get(operationId)?.token === proofToken,
      );
      assert(p);
      verified = authenticate(p.device.authPublicKey, proofToken);
      const previous = p.refreshes.get(operationId);
      if (previous) {
        assert.equal(previous.raw, raw);
        assert.equal(previous.token, proofToken);
        reply = previous.reply;
      } else if (p.revoked) {
        reply = { error: { code: 'grant_revoked' } };
        status = 403;
      } else {
        p.rotation++;
        p.grant = this.newGrant(p);
        reply = {
          protocol: 'EWDAP/1',
          grantId: p.id,
          sealedDelivery: sealDelivery(p.grant, operationId),
        };
        p.refreshes.set(operationId, { raw, token: proofToken, reply });
      }
      if (status === 200 && this.mode.startsWith('hold-refresh') && !this.heldOnce) {
        this.heldOnce = true;
        hold = true;
      }
    } else if (path.startsWith('/v2/remote-access/device-grants/') && method === 'DELETE') {
      p = this.peers.find((x) => x.id === path.split('/').at(-1));
      assert(p);
      proofToken = authorization?.slice(5);
      assert(p.accesses.has(proofToken));
      verified = authenticate(p.device.authPublicKey, proofToken);
      const previous = p.revokes.get(operationId);
      if (previous) assert.equal(previous, raw);
      else p.revokes.set(operationId, raw);
      p.revoked = true;
      reply = {
        protocol: 'EWDAP/1',
        grantId: p.id,
        bindingId: p.binding.bindingId,
        status: 'revoked',
      };
      if (this.mode.endsWith('drop-revoke') && !this.dropped) {
        this.dropped = true;
        drop = true;
      }
    } else if (path === '/v2/remote-access/bindings' && method === 'GET') {
      proofToken = authorization?.slice(5);
      p = this.peers.find((x) => x.accesses.has(proofToken));
      assert(p);
      verified = authenticate(p.device.authPublicKey, proofToken);
      if (p.revoked) {
        reply = { error: { code: 'grant_revoked' } };
        status = 403;
      } else reply = { bindings: [p.binding], synthetic: true };
    } else throw new Error('Unregistered synthetic path');
    assert(verified);
    this.requests.push({
      method,
      path,
      operationId,
      bodyHash: hash(raw),
      tokenHash: hash(authorization ?? ''),
      deviceId: p.device.deviceId,
      grantId: p.id,
      target: p.binding.target,
      jti: verified.jti,
      thumbprint: verified.thumbprint,
    });
    return { reply, status, hold, drop };
  }
  stats() {
    return {
      fixture: KIND,
      caseId: this.caseId,
      testMethod: this.testMethod,
      mode: this.mode,
      requests: this.requests,
      errors: this.errors,
      realDesktopRequests: 0,
      realModelInputs: 0,
    };
  }
}

export async function startFixture(directory) {
  const folder = resolve(directory);
  await mkdir(folder, { mode: 0o700 });
  const instanceId = randomUUID();
  let config,
    issuer = new Issuer(randomUUID(), 'normal'),
    held = [];
  const sockets = new Set();
  let released = 0,
    closed = 0;
  const asset = await readFile(
    new URL(
      '../../../../../../../apps/android/src/androidTest/assets/easywork-crypto-vector.json',
      import.meta.url,
    ),
  );
  assert.equal(JSON.parse(asset).credential.binding.mobilePublicKey, MOBILE_PUBLIC);
  const stats = () => ({
    ...issuer.stats(),
    instanceId,
    held: held.length,
    heldClosed: held.filter((x) => x.res.destroyed).length,
    lateReleased: released,
    lateClosed: closed,
  });
  const send = (res, value, status = 200) => {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(value));
  };
  const readBody = async (req) => {
    let size = 0;
    const chunks = [];
    for await (const b of req) {
      size += b.length;
      assert(size <= 65536);
      chunks.push(b);
    }
    return Buffer.concat(chunks).toString('utf8');
  };
  const service = createServer(async (req, res) => {
    const current = issuer;
    try {
      const result = current.dispatch({
        method: req.method,
        url: new URL(req.url, config.origin).href,
        raw: await readBody(req),
        authorization: req.headers.authorization,
        dpop: req.headers.dpop,
      });
      if (result.drop) {
        res.destroy();
        return;
      }
      if (result.hold) {
        held.push({ res, reply: result.reply, status: result.status });
        return;
      }
      send(res, result.reply, result.status);
    } catch (error) {
      current.errors.push(error.name);
      if (!res.headersSent) send(res, { error: { code: 'fixture_contract_refused' } }, 400);
      else res.destroy();
    }
  });
  service.on('connection', (socket) => {
    sockets.add(socket);
    socket.on('error', () => {});
    socket.on('close', () => sockets.delete(socket));
  });
  const control = createServer(async (req, res) => {
    try {
      const path = new URL(req.url, 'http://fixture.invalid').pathname;
      if (path === '/health' && req.method === 'GET')
        return send(res, { ...config, onlyLoopback: true, realDesktop: false, realModel: false });
      assert.equal(req.headers['x-p8-fixture'], instanceId);
      if (path === '/begin-case' && req.method === 'POST') {
        const value = JSON.parse(await readBody(req));
        assert.match(value.testMethod, /^[A-Za-z0-9]{1,150}$/);
        const next = new Issuer(value.caseId, value.mode, value.testMethod);
        for (const socket of sockets) socket.destroy();
        held = [];
        released = 0;
        closed = 0;
        issuer = next;
        return send(res, stats());
      }
      if (path === '/stats' && req.method === 'GET') return send(res, stats());
      if (path === '/release-held' && req.method === 'POST') {
        for (const h of held.splice(0)) {
          released++;
          if (h.res.destroyed) closed++;
          else send(h.res, h.reply, h.status);
        }
        return send(res, stats());
      }
      if (path === '/end-case' && req.method === 'POST') {
        const value = JSON.parse(await readBody(req));
        assert.equal(value.caseId, issuer.caseId);
        await writeFile(
          join(folder, 'case-' + issuer.caseId + '.json'),
          JSON.stringify(stats(), null, 2) + '\n',
          { mode: 0o600 },
        );
        return send(res, stats());
      }
      send(res, {}, 404);
    } catch {
      if (!res.headersSent) send(res, {}, 400);
      else res.destroy();
    }
  });
  const listen = (s) =>
    new Promise((done, fail) => {
      s.once('error', fail);
      s.listen(0, '127.0.0.1', done);
    });
  try {
    await listen(service);
    await listen(control);
  } catch (error) {
    service.close();
    control.close();
    throw error;
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
