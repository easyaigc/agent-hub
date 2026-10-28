import assert from 'node:assert/strict';
import { randomUUID, X509Certificate } from 'node:crypto';
import { createServer } from 'node:http';
import { createServer as httpsServer } from 'node:https';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { Issuer } from '../authorization-fence/phase2/fixture.mjs';
import { canonical, hash, sealDelivery } from '../authorization-boundaries/fixture.mjs';
export const KIND = 'p8-synthetic-pairing-ui-v1';
export const MODES = [
  'form',
  'reject',
  'expire',
  'mismatch',
  'drop',
  'approve-block',
  'wrong-pin',
  'wrong-host-target',
  'wrong-binding',
  'expired-local',
];
export class PairingCase {
  constructor(id, mode, vector) {
    assert.match(id, /^[a-f0-9-]{36}$/);
    assert(MODES.includes(mode));
    Object.assign(this, {
      id,
      mode,
      vector,
      issuer: new Issuer(id, 'normal', 'pairing-ui'),
      resolution: null,
      dropped: false,
      hostReads: 0,
      lanClaims: 0,
      blockedRelay: 0,
      errors: [],
    });
  }
  resolve(status) {
    assert(['rejected', 'expired', 'approved'].includes(status));
    assert(this.resolution === null || this.resolution === status);
    this.resolution = status;
  }
  auth(request) {
    const path = new URL(request.url).pathname;
    if (path.startsWith('/v2/remote-access/relay/')) {
      this.blockedRelay++;
      return { status: 503, reply: { error: { code: 'synthetic_runtime_unavailable' } } };
    }
    assert(
      path === '/v2/remote-access/pairing/claims' ||
        /^\/v2\/remote-access\/pairing\/claims\/[a-f0-9-]{36}$/.test(path),
    );
    const result = this.issuer.dispatch(request);
    if (request.method === 'POST' && this.mode === 'drop' && !this.dropped) {
      this.dropped = true;
      return { ...result, drop: true };
    }
    if (request.method === 'GET') {
      const p = this.issuer.peers[0];
      if (!this.resolution)
        return {
          status: 200,
          reply: {
            protocol: 'EWDAP/1',
            claimId: p.claimId,
            offerId: p.offerId,
            status: 'pending',
            expiresAt: new Date(Date.now() + 60000).toISOString(),
          },
        };
      if (this.resolution !== 'approved')
        return {
          status: 200,
          reply: {
            protocol: 'EWDAP/1',
            claimId: p.claimId,
            offerId: p.offerId,
            status: this.resolution,
          },
        };
      if (this.mode === 'mismatch') {
        const grant = structuredClone(p.grant);
        grant.binding.mobileDeviceId = 'foreign-mobile';
        return {
          status: 200,
          reply: { ...result.reply, sealedDelivery: sealDelivery(grant, p.claimId) },
        };
      }
    }
    return result;
  }
  descriptor(origin, pin, offer = false) {
    const b = this.vector.credential.binding,
      target = structuredClone(b.target);
    if (!offer && this.mode === 'wrong-host-target') target.runtimeId = 'foreign-runtime';
    return {
      version: 1,
      target,
      publicKey: b.desktopPublicKey,
      fingerprint: offer && this.mode === 'wrong-pin' ? '0'.repeat(64) : pin,
      endpoints: [origin],
      offerId: 'public-ui-offer',
      pairingCode: '123456',
      expiresAt: new Date(
        Date.now() + (this.mode === 'expired-local' ? -60000 : 60000),
      ).toISOString(),
    };
  }
  lan(path, method, body, origin, pin) {
    if (path === '/host' && method === 'GET') {
      this.hostReads++;
      return { status: 200, reply: this.descriptor(origin, pin) };
    }
    assert.equal(path, '/pairing/claim');
    assert.equal(method, 'POST');
    assert.equal(body.code, '123456');
    assert.equal(body.publicKey, this.vector.credential.binding.mobilePublicKey);
    this.lanClaims++;
    const binding = structuredClone(this.vector.credential.binding);
    binding.mobileDeviceId = body.mobileDeviceId;
    if (this.mode === 'wrong-binding') binding.target.runtimeId = 'foreign-binding-runtime';
    return { status: 200, reply: { binding } };
  }
  stats() {
    return {
      caseId: this.id,
      mode: this.mode,
      resolution: this.resolution,
      hostReads: this.hostReads,
      lanClaims: this.lanClaims,
      blockedRelay: this.blockedRelay,
      requests: this.issuer.requests,
      errors: this.errors,
      realDesktopRequests: 0,
      realModelInputs: 0,
    };
  }
}
export async function startFixture(directory) {
  const folder = resolve(directory);
  await mkdir(folder, { mode: 0o700 });
  const asset = await readFile(
      new URL(
        '../../../../../../apps/android/src/androidTest/assets/easywork-crypto-vector.json',
        import.meta.url,
      ),
    ),
    vector = JSON.parse(asset);
  const key = join(folder, 'key.pem'),
    cert = join(folder, 'cert.pem');
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-keyout',
      key,
      '-out',
      cert,
      '-days',
      '1',
      '-subj',
      '/CN=localhost',
      '-addext',
      'subjectAltName=IP:127.0.0.1,DNS:localhost',
    ],
    { stdio: 'ignore' },
  );
  const certBytes = await readFile(cert),
    pin = hash(new X509Certificate(certBytes).raw),
    instanceId = randomUUID(),
    sockets = new Set();
  let active = null,
    config;
  const send = (res, value, status = 200) => {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(value));
  };
  const body = async (req) => {
    let size = 0;
    const parts = [];
    for await (const b of req) {
      size += b.length;
      assert(size <= 65536);
      parts.push(b);
    }
    return Buffer.concat(parts).toString('utf8');
  };
  const handler = (tls) => async (req, res) => {
    try {
      assert(active);
      const raw = await body(req),
        path = new URL(req.url, 'http://fixture.invalid').pathname;
      const result = tls
        ? active.lan(path, req.method, raw ? JSON.parse(raw) : {}, config.tlsOrigin, pin)
        : active.auth({
            method: req.method,
            url: new URL(req.url, config.authOrigin).href,
            raw,
            authorization: req.headers.authorization,
            dpop: req.headers.dpop,
          });
      if (result.drop) {
        res.destroy();
        return;
      }
      send(res, result.reply, result.status);
    } catch (error) {
      active?.errors.push(error.name);
      send(res, { error: { code: 'pairing_fixture_refused' } }, 400);
    }
  };
  const auth = createServer(handler(false)),
    tls = httpsServer({ key: await readFile(key), cert: certBytes }, handler(true));
  const control = createServer(async (req, res) => {
    try {
      const path = new URL(req.url, 'http://fixture.invalid').pathname;
      if (path === '/health' && req.method === 'GET')
        return send(res, { ...config, onlyLoopback: true, realDesktop: false, realModel: false });
      assert.equal(req.headers['x-p8-fixture'], instanceId);
      if (path === '/begin-case' && req.method === 'POST') {
        assert(!active);
        const input = JSON.parse(await body(req));
        active = new PairingCase(input.caseId, input.mode, vector);
        return send(res, active.stats());
      }
      assert(active);
      if (path === '/stats' && req.method === 'GET') return send(res, active.stats());
      if (path === '/offer' && req.method === 'GET')
        return send(res, {
          text:
            'easywork-host://pair?descriptor=' +
            Buffer.from(canonical(active.descriptor(config.tlsOrigin, pin, true))).toString(
              'base64url',
            ),
        });
      if (path === '/resolve' && req.method === 'POST') {
        const v = JSON.parse(await body(req));
        assert.equal(v.caseId, active.id);
        active.resolve(v.status);
        return send(res, active.stats());
      }
      if (path === '/end-case' && req.method === 'POST') {
        const v = JSON.parse(await body(req));
        assert.equal(v.caseId, active.id);
        const snapshot = active.stats();
        await writeFile(
          join(folder, active.id + '.json'),
          JSON.stringify(snapshot, null, 2) + '\n',
          { mode: 0o600, flag: 'wx' },
        );
        active = null;
        return send(res, snapshot);
      }
      send(res, { error: 'No such operation' }, 404);
    } catch {
      send(res, { error: 'pairing_control_refused' }, 400);
    }
  });
  for (const server of [auth, tls, control])
    server.on('connection', (socket) => {
      sockets.add(socket);
      socket.on('error', () => {});
      socket.on('close', () => sockets.delete(socket));
    });
  const listen = (s) =>
    new Promise((ok, fail) => {
      s.once('error', fail);
      s.listen(0, '127.0.0.1', ok);
    });
  await listen(auth);
  await listen(tls);
  await listen(control);
  config = {
    version: 1,
    fixture: KIND,
    instanceId,
    sidecarPid: process.pid,
    startedAt: new Date().toISOString(),
    sourceFixtureSha256: hash(asset),
    sourceSha256: hash(await readFile(new URL(import.meta.url))),
    certificateSha256: pin,
    authOrigin: 'http://127.0.0.1:' + auth.address().port,
    tlsOrigin: 'https://127.0.0.1:' + tls.address().port,
    controlOrigin: 'http://127.0.0.1:' + control.address().port,
  };
  await writeFile(join(folder, 'config.json'), JSON.stringify(config, null, 2) + '\n', {
    mode: 0o600,
  });
  return {
    config,
    async close() {
      for (const socket of sockets) socket.destroy();
      await Promise.all([auth, tls, control].map((s) => new Promise((ok) => s.close(ok))));
    },
  };
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  assert.equal(process.argv[2], '--start');
  assert(process.argv[3]);
  const f = await startFixture(process.argv[3]);
  console.log(JSON.stringify(f.config));
  let closing = false;
  const close = async () => {
    if (closing) return;
    closing = true;
    await f.close();
    process.exit(0);
  };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
}
