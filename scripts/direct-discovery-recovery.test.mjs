import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, mkdir, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';
import https from 'node:https';
import { X509Certificate } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { DirectDiscoveryRecovery } from '../apps/host-agent/dist/direct-discovery-recovery.js';
import { startDirectDiscovery } from '../apps/host-agent/dist/direct-discovery.js';
import { DirectEndpointRegistry } from '../apps/host-agent/dist/direct-endpoints.js';

process.env.HOST_AGENT_DISCOVERY_BONJOUR = '0';
const listen = (server, port = 0) =>
  new Promise((done, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', done);
  });
const stop = (server) => new Promise((done) => server.close(done));
const until = async (check) => {
  for (let i = 0; i < 200; i++) {
    if (check()) return;
    await delay(25);
  }
  assert.fail('Recovery did not finish');
};

test('occupied TLS port recovers once with the original identity and strict TLS', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'host-direct-recovery-'));
  const blocker = createServer();
  let service;
  let recovery;
  try {
    const target = {
      agentId: 'codex',
      hostId: 'fixture',
      workspaceId: 'workspace',
      runtimeId: 'desktop-runtime',
    };
    const options = {
      directory,
      name: 'Recovery fixture',
      token: 'a'.repeat(48),
      target: () => target,
      bind: '127.0.0.1',
      port: 0,
      registry: new DirectEndpointRegistry([{ id: 'fixture', addresses: async () => [] }]),
      handler: async (_request, response) => {
        response.writeHead(401);
        response.end();
      },
    };
    service = await startDirectDiscovery(options);
    const original = service.descriptor();
    const cert = await readFile(join(directory, 'direct-discovery/cert.pem'));
    const key = await readFile(join(directory, 'direct-discovery/key.pem'));
    const port = service.server.address().port;
    const closed = new Promise((done) => service.server.once('close', done));
    service.close();
    await closed;
    await listen(blocker, port);
    let attempts = 0;
    const errors = [];
    let recovered;
    recovery = new DirectDiscoveryRecovery(
      async () => {
        attempts++;
        return startDirectDiscovery({ ...options, port });
      },
      (value) => {
        recovered = value;
      },
      (error, attempt) => errors.push({ code: error.code, attempt }),
    );
    await recovery.start();
    await recovery.start();
    assert.equal(attempts, 1);
    assert.deepEqual(errors, [{ code: 'EADDRINUSE', attempt: 1 }]);
    await stop(blocker);
    await until(() => recovered);
    assert.equal(attempts, 2);
    assert.equal(recovered.server.address().port, port);
    assert.deepEqual(recovered.descriptor().target, original.target);
    assert.equal(recovered.descriptor().fingerprint, original.fingerprint);
    assert.deepEqual(await readFile(join(directory, 'direct-discovery/cert.pem')), cert);
    assert.deepEqual(await readFile(join(directory, 'direct-discovery/key.pem')), key);
    const read = (path) =>
      new Promise((done, reject) => {
        https
          .get(
            {
              hostname: '127.0.0.1',
              port,
              path,
              ca: cert,
              rejectUnauthorized: true,
              checkServerIdentity: (_host, peer) => {
                assert.deepEqual(peer.raw, new X509Certificate(cert).raw);
              },
            },
            (response) => {
              const chunks = [];
              response.on('data', (chunk) => chunks.push(chunk));
              response.on('end', () =>
                done({ status: response.statusCode, bytes: Buffer.concat(chunks) }),
              );
            },
          )
          .on('error', reject);
      });
    const descriptor = await read('/host');
    assert.equal(descriptor.status, 200);
    assert.equal(JSON.parse(descriptor.bytes).target.hostId, target.hostId);
    assert.equal(descriptor.bytes.includes(options.token), false);
    assert.equal((await read('/v1/workspace')).status, 401);
    const recoveredClosed = new Promise((done) => recovered.server.once('close', done));
    recovery.close();
    recovery.close();
    await recoveredClosed;

    // Fail after the listener has bound: the failed startup must relinquish its
    // port before it rejects, rather than racing the next recovery generation.
    const page = join(directory, 'direct-discovery/connect.html');
    await rm(page);
    await mkdir(page);
    await assert.rejects(startDirectDiscovery({ ...options, port }));
    await listen(blocker, port);
    await stop(blocker);
    await rm(page, { recursive: true });
    service = await startDirectDiscovery({ ...options, port });
    assert.equal(service.descriptor().fingerprint, original.fingerprint);
  } finally {
    recovery?.close();
    service?.close();
    if (blocker.listening) await stop(blocker);
    await rm(directory, { recursive: true, force: true });
  }
});

test('shutdown cancels a pending retry even if failure reporting throws', async () => {
  let calls = 0;
  const recovery = new DirectDiscoveryRecovery(
    async () => {
      calls++;
      throw Object.assign(Error('private text'), { code: 'EADDRINUSE' });
    },
    () => assert.fail('Must not become ready'),
    () => {
      throw Error('unavailable diagnostic sink');
    },
  );
  await recovery.start();
  recovery.close();
  await delay(1200);
  await recovery.start();
  assert.equal(calls, 1);
});

test('shutdown during startup retires a late result without publishing or retrying', async () => {
  let finish;
  let closed = 0;
  let published = 0;
  const recovery = new DirectDiscoveryRecovery(
    () =>
      new Promise((done) => {
        finish = done;
      }),
    () => {
      published++;
    },
    () => assert.fail('No failure expected'),
  );
  const initial = recovery.start();
  recovery.close();
  finish({
    close: () => {
      closed++;
    },
  });
  await initial;
  await recovery.start();
  recovery.close();
  assert.equal(closed, 1);
  assert.equal(published, 0);
});
