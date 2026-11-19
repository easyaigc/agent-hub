import assert from 'node:assert/strict';
import { request } from 'node:http';
import { mkdir, readFile, writeFile, lstat } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { startDirectDiscovery } from '../../../apps/host-agent/dist/direct-discovery.js';
import { DirectEndpointRegistry } from '../../../apps/host-agent/dist/direct-endpoints.js';

// TLS and pairing use the real transport. Only the two synthetic backends are
// accepted: this adapter cannot carry a production Host or its credentials.
const required = (key) => {
  assert(process.env[key], `${key} required`);
  return process.env[key];
};
const backend = new URL(required('CODEX_DEADLINE_BACKEND'));
assert(backend.protocol === 'http:' && backend.hostname === '127.0.0.1');
assert(!backend.username && !backend.password && !backend.search && backend.pathname === '/');
const fixture = JSON.parse(await readFile(required('CODEX_DEADLINE_FIXTURE_JSON'), 'utf8'));
assert(['inflight-open-fixture-host', 'reply-wait-fixture'].includes(fixture.hostId));
assert(typeof fixture.token === 'string' && /^[A-Za-z0-9_-]{43}$/.test(fixture.token));
const metadataPath = fixture.hostId === 'inflight-open-fixture-host' ? '/fixture' : '/';
const probe = await fetch(new URL(metadataPath, backend), { signal: AbortSignal.timeout(5000) });
assert.equal(probe.status, 200);
const actualFixture = await probe.json();
assert.equal(actualFixture.hostId, fixture.hostId);
assert.equal(actualFixture.token, fixture.token, 'Require the actual synthetic backend');
const bind = required('FIXTURE_BIND');
assert(/^(192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3})$/.test(bind));
const port = Number(required('CODEX_DEADLINE_TLS_PORT'));
assert(Number.isInteger(port) && port > 1024 && port < 65536);
assert(
  !process.env.HOST_AGENT_DISCOVERY_TLS_CERT && !process.env.HOST_AGENT_DISCOVERY_TLS_KEY,
  'Synthetic TLS must use its own certificate',
);
const directory = resolve(required('CODEX_DEADLINE_TLS_STATE'));
const marker = join(directory, 'synthetic-owner.json');
const target = {
  agentId: 'codex',
  hostId: fixture.hostId,
  workspaceId: fixture.hostId,
  runtimeId: 'desktop-runtime',
};
try {
  await mkdir(directory, { mode: 0o700 });
  await writeFile(marker, JSON.stringify(target), { mode: 0o600, flag: 'wx' });
} catch (error) {
  assert.equal(error.code, 'EEXIST');
  const info = await lstat(directory);
  assert(info.isDirectory() && !info.isSymbolicLink() && info.uid === process.getuid());
  assert.equal(info.mode & 0o077, 0);
  assert.deepEqual(JSON.parse(await readFile(marker, 'utf8')), target);
}
process.env.HOST_AGENT_DISCOVERY_BONJOUR = '0';
const registry = new DirectEndpointRegistry([
  { id: 'synthetic-test-interface', addresses: async () => [{ host: bind, networkKind: 'lan' }] },
]);
const service = await startDirectDiscovery({
  directory,
  name: 'Codex deadline synthetic TLS',
  token: fixture.token,
  target: () => target,
  registry,
  bind,
  port,
  handler: (incoming, response) =>
    new Promise((done) => {
      const url = new URL(incoming.url, backend);
      if (
        !incoming.url.startsWith('/') ||
        incoming.url.startsWith('//') ||
        url.origin !== backend.origin ||
        !url.pathname.startsWith('/v1/')
      ) {
        response.writeHead(403);
        response.end();
        done();
        return;
      }
      const upstream = request(
        url,
        {
          method: incoming.method,
          headers: { ...incoming.headers, host: backend.host, 'accept-encoding': 'identity' },
        },
        (result) => {
          const pathname = url.pathname;
          if (result.statusCode === 200 && ['/v1/workspace', '/v1/history'].includes(pathname)) {
            let size = 0;
            const chunks = [];
            result.on('data', (chunk) => {
              size += chunk.length;
              if (size > 2 * 1024 * 1024)
                upstream.destroy(new Error('Synthetic response too large'));
              else chunks.push(chunk);
            });
            result.on('end', () => {
              try {
                const value = JSON.parse(Buffer.concat(chunks).toString());
                assert.equal(value.hostId, target.hostId);
                const data = JSON.stringify({ ...value, readTarget: target });
                response.writeHead(200, {
                  'content-type': 'application/json',
                  'cache-control': 'no-store',
                });
                response.end(data);
              } catch {
                response.writeHead(502);
                response.end();
              }
            });
          } else {
            // Never buffer the deliberately incomplete open/reply response.
            response.writeHead(result.statusCode ?? 502, result.headers);
            result.pipe(response);
          }
        },
      );
      upstream.on('error', () => {
        if (!response.headersSent) response.writeHead(502);
        response.end();
      });
      response.once('close', () => {
        if (!response.writableEnded) upstream.destroy();
        done();
      });
      incoming.pipe(upstream);
    }),
});
const descriptor = service.descriptor();
const endpoint = `https://${bind}:${port}`;
assert(descriptor.endpoints.includes(endpoint));
const pairingUri =
  'codex-host://pair?descriptor=' +
  Buffer.from(JSON.stringify({ ...descriptor, token: fixture.token })).toString('base64url');
await writeFile(
  required('CODEX_DEADLINE_TLS_METADATA'),
  JSON.stringify({
    ...fixture,
    endpoint,
    pairingUri,
    readTarget: target,
  }),
  { mode: 0o600, flag: 'wx' },
);
console.log(JSON.stringify({ syntheticTLSReady: true, port }));
let closing = false;
const close = async () => {
  if (closing) return;
  closing = true;
  await service.close();
};
process.once('SIGTERM', () => {
  void close();
});
process.once('SIGINT', () => {
  void close();
});
