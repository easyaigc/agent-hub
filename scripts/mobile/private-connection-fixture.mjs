import assert from 'node:assert/strict';
import { createHash, X509Certificate, generateKeyPairSync } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createServer as createHTTP } from 'node:http';
import { createServer as createHTTPS } from 'node:https';
import { mkdir, readFile, chmod } from 'node:fs/promises';
import { resolve } from 'node:path';
import { PrivateConnections } from '../../apps/host-agent/dist/private-connections.js';

/** Native UI/HTTP fixture. It uses the real Host credential issuer, never a formal Host's identity/state. */
export async function startPrivateConnectionFixture(directory) {
  const root = resolve(directory);
  await mkdir(root, { recursive: true, mode: 0o700 });
  execFileSync(
    '/usr/bin/openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-keyout',
      root + '/key.pem',
      '-out',
      root + '/cert.pem',
      '-days',
      '2',
      '-subj',
      '/CN=UM04 isolated fixture',
      '-addext',
      'subjectAltName=IP:127.0.0.1',
    ],
    { stdio: 'ignore' },
  );
  for (const file of ['key.pem', 'cert.pem']) await chmod(root + '/' + file, 0o600);
  const key = await readFile(root + '/key.pem'),
    cert = await readFile(root + '/cert.pem');
  const fingerprint = new X509Certificate(cert).fingerprint256.replaceAll(':', '').toLowerCase();
  const issuer = new PrivateConnections(root);
  const easyKey = generateKeyPairSync('x25519')
    .publicKey.export({ type: 'spki', format: 'der' })
    .subarray(-32)
    .toString('base64url');
  const easyTarget = {
    account: { authority: 'https://fixture.invalid', subject: 'owner' },
    desktopDeviceId: 'um04-easywork-desktop',
    workspaceId: 'easy-workspace',
    runtimeId: 'easy-runtime',
  };
  const easyBindings = new Map();
  const target = {
    agentId: 'codex',
    hostId: 'um04-private-fixture',
    workspaceId: 'um04-workspace',
    runtimeId: 'um04-runtime',
    account: { authority: 'fixture😀', subject: 'owner' },
  };
  let mode = 'normal',
    requests = [],
    dropped = false,
    endpoint;
  const runs = [{ mode, requests, startedAtUTC: new Date().toISOString() }];
  const allowed = [
    'normal',
    'slow-host',
    'over-budget-host',
    'slow-peers',
    'over-budget-peers',
    'drip-host',
    'bad-fingerprint',
    'wrong-host',
    'bad-tag',
    'wrong-reply-target',
    'wrong-workspace',
    'drop-claim-response',
    'legacy-host',
    'hold-claim',
    'denied-claim',
    'redirect-host',
    'easywork-automatic',
    'easywork-bad-pin',
    'easywork-legacy',
    'easywork-wrong-binding-target',
    'easywork-drop-claim-response',
  ];
  const body = async (request) => {
    let count = 0;
    const chunks = [];
    for await (const chunk of request) {
      count += chunk.length;
      assert(count <= 8192);
      chunks.push(chunk);
    }
    return Buffer.concat(chunks);
  };
  const send = (response, value, status = 200) => {
    response.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    response.end(JSON.stringify(value));
  };
  const server = createHTTPS({ key, cert }, async (request, response) => {
    const url = new URL(request.url ?? '/', 'https://localhost');
    const record = {
      method: request.method,
      path: url.pathname,
      authorizationPresent: Boolean(request.headers.authorization),
    };
    response.on('close', () => {
      record.closedBeforeFinish = !response.writableFinished;
    });
    requests.push(record);
    try {
      if (request.method === 'GET' && url.pathname === '/host') {
        if (mode === 'slow-host' || mode === 'over-budget-host')
          await new Promise((done) => setTimeout(done, mode === 'slow-host' ? 3500 : 9000));
        if (mode.startsWith('easywork-')) {
          send(response, {
            version: 1,
            name: 'UM04 EasyWork fixture',
            fingerprint: mode === 'easywork-bad-pin' ? '0'.repeat(64) : fingerprint,
            publicKey: easyKey,
            target: easyTarget,
            endpoints: [endpoint, 'https://127.0.0.1:1'],
            ...(mode === 'easywork-legacy' ? {} : { connectionMode: 'automatic' }),
          });
          return;
        }
        if (mode === 'redirect-host') {
          response.writeHead(307, { location: endpoint + '/never-follow' });
          response.end();
          return;
        }
        const descriptor = {
          version: 1,
          agentId: 'codex',
          name: 'UM04 isolated computer',
          target: mode === 'wrong-host' ? { ...target, hostId: 'wrong-host' } : target,
          fingerprint: mode === 'bad-fingerprint' ? '0'.repeat(64) : fingerprint,
          endpoints: [endpoint],
          endpointHints: [{ endpoint, networkKind: 'lan' }],
          ...(mode === 'legacy-host' ? {} : { connectionMode: 'automatic' }),
        };
        if (mode === 'drip-host') {
          const wire = JSON.stringify(descriptor),
            width = Math.ceil(wire.length / 4);
          response.writeHead(200, {
            'content-type': 'application/json',
            'cache-control': 'no-store',
          });
          for (let offset = 0; offset < wire.length; offset += width) {
            if (response.destroyed) return;
            response.write(wire.slice(offset, offset + width));
            if (offset + width < wire.length) await new Promise((done) => setTimeout(done, 1700));
          }
          response.end();
          return;
        }
        send(response, descriptor);
        return;
      }
      if (request.method === 'GET' && url.pathname === '/discovery/peers') {
        if (mode === 'slow-peers' || mode === 'over-budget-peers')
          await new Promise((done) => setTimeout(done, mode === 'slow-peers' ? 3500 : 7000));
        assert.equal(url.searchParams.get('hostId'), target.hostId);
        send(response, { version: 1, target, peers: [] });
        return;
      }
      if (request.method === 'POST' && url.pathname === '/connection/claim') {
        const bytes = await body(request),
          input = JSON.parse(bytes.toString());
        record.fields = Object.keys(input).sort();
        record.publicKey = input.publicKey;
        record.bodySHA256 = createHash('sha256').update(bytes).digest('hex');
        record.bodyBase64 = bytes.toString('base64');
        record.target = input.target;
        if (mode === 'denied-claim') {
          send(response, {}, 403);
          return;
        }
        const reply = issuer.claim(input, target);
        record.issuanceCommitted = true;
        if (mode === 'drop-claim-response' && !dropped) {
          dropped = true;
          record.outcome = 'connection-destroyed-after-issuance';
          request.socket.destroy();
          return;
        }
        if (mode === 'hold-claim') await new Promise((done) => setTimeout(done, 1800));
        if (mode === 'bad-tag') {
          const bytes = Buffer.from(reply.ciphertext, 'base64url');
          bytes[0] ^= 1;
          reply.ciphertext = bytes.toString('base64url');
        }
        if (mode === 'wrong-reply-target')
          reply.target = { ...reply.target, runtimeId: 'wrong-runtime' };
        send(response, reply);
        return;
      }
      if (
        request.method === 'POST' &&
        url.pathname === '/connections' &&
        mode.startsWith('easywork-')
      ) {
        const bytes = await body(request),
          input = JSON.parse(bytes.toString());
        record.fields = Object.keys(input).sort();
        record.bodySHA256 = createHash('sha256').update(bytes).digest('hex');
        const binding = easyBindings.get(input.publicKey) ?? {
          bindingId: 'fixture-' + easyBindings.size,
          account: easyTarget.account,
          desktopDeviceId: easyTarget.desktopDeviceId,
          mobileDeviceId: input.mobileDeviceId,
          desktopPublicKey: easyKey,
          mobilePublicKey: input.publicKey,
          target: easyTarget,
          status: 'active',
          createdAt: new Date().toISOString(),
        };
        easyBindings.set(input.publicKey, binding);
        record.issuanceCommitted = true;
        if (mode === 'easywork-drop-claim-response') {
          request.socket.destroy();
          return;
        }
        send(response, {
          binding:
            mode === 'easywork-wrong-binding-target'
              ? { ...binding, target: { ...easyTarget, runtimeId: 'wrong' } }
              : binding,
        });
        return;
      }
      const auth = request.headers.authorization?.replace(/^Bearer /, '') ?? '';
      if (
        !issuer.accepts(Buffer.from(auth), target) &&
        auth !== 'fixture-legacy-token-'.repeat(2)
      ) {
        send(response, {}, 401);
        return;
      }
      if (url.pathname === '/v1/workspace') {
        send(response, {
          hostId: mode === 'wrong-workspace' ? 'wrong-host' : target.hostId,
          name: 'UM04 isolated computer',
          connectionMode: 'desktop-runtime',
          readTarget: target,
          capabilities: ['projects:read', 'threads:read', 'history:read'],
          projects: [{ id: 'p', hostId: target.hostId, name: 'UM04 fixture project', roots: [] }],
          threads: [
            {
              id: 't',
              hostId: target.hostId,
              title: 'UM04 fixture conversation',
              state: 'idle',
              projectId: 'p',
            },
          ],
        });
        return;
      }
      if (url.pathname === '/v1/history') {
        send(response, {
          hostId: target.hostId,
          threadId: 't',
          readTarget: target,
          messages: [
            { id: 'm', role: 'assistant', text: 'UM04_READ_ONLY_FIXTURE', status: 'completed' },
          ],
        });
        return;
      }
      if (url.pathname === '/v1/discovery/peers') {
        if (mode === 'slow-peers' || mode === 'over-budget-peers')
          await new Promise((done) => setTimeout(done, mode === 'slow-peers' ? 3500 : 7000));
        send(response, { version: 1, target, peers: [] });
        return;
      }
      send(response, {}, 404);
    } catch {
      if (!response.headersSent) send(response, {}, 400);
      else response.destroy();
    }
  });
  await new Promise((done) => server.listen(0, '0.0.0.0', done));
  endpoint = 'https://127.0.0.1:' + server.address().port;
  const control = createHTTP(async (request, response) => {
    try {
      if (request.method === 'POST' && request.url === '/mode') {
        const input = JSON.parse((await body(request)).toString());
        assert(allowed.includes(input.mode));
        mode = input.mode;
        requests = [];
        runs.push({ mode, requests, startedAtUTC: new Date().toISOString() });
        dropped = false;
        send(response, { mode });
        return;
      }
      if (request.method === 'GET' && request.url === '/stats') {
        let bindingCount = 0;
        try {
          bindingCount = JSON.parse(await readFile(root + '/private-connections-v1.json', 'utf8'))
            .bindings.length;
        } catch (error) {
          if (error.code !== 'ENOENT') throw error;
        }
        send(response, { mode, requests, bindingCount });
        return;
      }
      send(response, {}, 404);
    } catch {
      send(response, {}, 400);
    }
  });
  await new Promise((done) => control.listen(0, '127.0.0.1', done));
  return {
    endpoint,
    port: server.address().port,
    controlPort: control.address().port,
    fingerprint,
    target,
    stats: () => ({ mode, requests: structuredClone(requests) }),
    history: () => structuredClone(runs),
    close: async () => {
      server.closeAllConnections();
      control.closeAllConnections();
      await Promise.all([
        new Promise((done) => server.close(done)),
        new Promise((done) => control.close(done)),
      ]);
    },
  };
}
