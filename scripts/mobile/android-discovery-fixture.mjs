import { createServer } from 'node:https';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { createHash, X509Certificate } from 'node:crypto';
import { join } from 'node:path';

// An ephemeral synthetic certificate and token. No installed Host identity is used.
export async function startAndroidDiscoveryFixture(adb, serial) {
  const directory = await mkdtemp('.local/android-pairing-fixture-');
  const keyPath = join(directory, 'key.pem');
  const certificatePath = join(directory, 'certificate.pem');
  // Match the product Host's RSA certificate generator; LibreSSL's default EC
  // parameter encoding is rejected by Android's BoringSSL before trust checks.
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-days',
      '1',
      '-subj',
      '/CN=localhost',
      '-addext',
      'subjectAltName=IP:127.0.0.1,DNS:localhost',
      '-keyout',
      keyPath,
      '-out',
      certificatePath,
    ],
    { stdio: 'ignore' },
  );
  const certificate = await readFile(certificatePath);
  const fingerprint = createHash('sha256')
    .update(new X509Certificate(certificate).raw)
    .digest('hex');
  const token = 'synthetic-pairing-token-for-native-acceptance';
  const target = {
    agentId: 'codex',
    hostId: 'fixture-paired-codex',
    workspaceId: 'fixture-desktop-workspace',
    runtimeId: 'fixture-desktop-runtime',
  };
  const requests = [];
  const tlsErrors = [];
  let descriptor;
  const server = createServer(
    { key: await readFile(keyPath), cert: certificate },
    (request, response) => {
      const url = new URL(request.url, 'https://fixture.invalid');
      const authorized = request.headers.authorization === 'Bearer ' + token;
      requests.push({ method: request.method, path: url.pathname, authorized });
      const send = (status, data, headers = {}) => {
        response.writeHead(status, { 'Content-Type': 'application/json', ...headers });
        response.end(JSON.stringify(data));
      };
      if (request.method !== 'GET') return send(405, { error: 'readonly_only' });
      if (url.pathname === '/host') return send(200, descriptor);
      if (!authorized) return send(401, { error: 'unauthorized' });
      if (url.pathname === '/v1/workspace')
        return send(200, {
          hostId: target.hostId,
          name: descriptor.name,
          connectionMode: 'desktop-runtime',
          readTarget: target,
          projects: [],
          threads: [
            {
              id: 'paired-read-thread',
              hostId: target.hostId,
              title: '合成配对历史',
              state: 'idle',
            },
          ],
          nextProjectCursor: null,
          nextThreadCursor: null,
          capabilities: ['history:read', 'threads:read'],
        });
      if (url.pathname === '/v1/history') {
        if (url.searchParams.get('threadId') === 'redirect')
          return send(307, {}, { Location: 'http://127.0.0.1:1/never-follow' });
        return send(200, {
          hostId: target.hostId,
          threadId: 'paired-read-thread',
          readTarget: target,
          messages: [
            {
              id: 'paired-reply',
              role: 'assistant',
              text: '已验证合成配对与只读历史。',
              files: [],
              status: 'completed',
            },
          ],
          nextCursor: null,
        });
      }
      return send(404, { error: 'unknown_read' });
    },
  );
  server.on('tlsClientError', (error) => {
    tlsErrors.push({ code: error.code, reason: error.reason });
  });
  try {
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(0, '127.0.0.1', resolve);
    });
    const port = server.address().port;
    const endpoint = 'https://127.0.0.1:' + port;
    descriptor = {
      version: 1,
      agentId: 'codex',
      name: '合成配对电脑',
      target,
      fingerprint,
      endpoints: [endpoint],
      endpointHints: [{ endpoint, networkKind: 'lan' }],
    };
    const uri = (value) =>
      'codex-host://pair?descriptor=' + Buffer.from(JSON.stringify(value)).toString('base64url');
    execFileSync(adb, ['-s', serial, 'reverse', 'tcp:' + port, 'tcp:' + port]);
    return {
      endpoint,
      pairing: uri({ ...descriptor, token }),
      wrongPin: uri({ ...descriptor, fingerprint: '0'.repeat(64), token }),
      requests,
      tlsErrors,
      async close() {
        execFileSync(adb, ['-s', serial, 'reverse', '--remove', 'tcp:' + port]);
        server.closeAllConnections();
        await new Promise((resolve) => server.close(resolve));
        await rm(directory, { recursive: true, force: true });
      },
    };
  } catch (error) {
    server.close();
    await rm(directory, { recursive: true, force: true });
    throw error;
  }
}
