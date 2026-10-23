import assert from 'node:assert/strict';
import { createHash, X509Certificate } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { chmod, mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:https';
import { resolve } from 'node:path';
import { homedir } from 'node:os';

const serials = process.argv.slice(2);
assert(serials.length && serials.every((s) => /^[a-zA-Z0-9_-]{1,80}$/.test(s)));
const sdk = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')).androidSdk;
const adb = resolve(sdk, 'platform-tools/adb');
const root = resolve(
  process.env.MOBILE_EVIDENCE_ROOT ??
    resolve(homedir(), '.codex/acceptance-archives/agent-hub/UM06'),
  'https-' + Date.now(),
);
await mkdir(root, { recursive: true, mode: 0o700 });
execFileSync(
  '/usr/bin/openssl',
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
    'subjectAltName=IP:127.0.0.1',
    '-keyout',
    root + '/key.pem',
    '-out',
    root + '/cert.pem',
  ],
  { stdio: 'ignore' },
);
await chmod(root + '/key.pem', 0o600);
const cert = await readFile(root + '/cert.pem');
const pin = new X509Certificate(cert).fingerprint256.replaceAll(':', '').toLowerCase();
const reports = [];
for (const serial of serials) {
  const counts = { publicCredential: 0, unknownPosts: 0, patches: 0, redirected: 0, uploads: 0 };
  const wire = [];
  const server = createServer(
    { cert, key: await readFile(root + '/key.pem') },
    async (request, response) => {
      try {
        const chunks = [];
        let size = 0;
        for await (const chunk of request) {
          size += chunk.length;
          assert(size <= 1024 * 1024);
          chunks.push(chunk);
        }
        const body = Buffer.concat(chunks);
        const url = new URL(request.url, 'https://127.0.0.1');
        wire.push({
          method: request.method,
          path: url.pathname,
          bodySha256: createHash('sha256').update(body).digest('hex'),
          bodyBytes: body.length,
          hasAuthorization: !!request.headers.authorization,
          operationId: request.headers['x-taskhub-operation-id'] ?? null,
        });
        if (url.pathname === '/api/v1/service') {
          if (request.headers.authorization) counts.publicCredential++;
          response.setHeader('Content-Type', 'application/json');
          response.end(
            JSON.stringify({
              version: 1,
              contractVersion: '1',
              deploymentId: 'fixture-deployment',
              serverInstanceId: 'fixture-instance',
              primaryHostId: 'fixture-primary',
              name: 'Task Hub fixture',
              capabilities: [],
            }),
          );
          return;
        }
        assert.equal(request.headers.authorization, 'Bearer fixture-human-token');
        assert.match(request.headers['x-taskhub-device-id'], /^[a-f0-9-]{36}$/);
        if (url.pathname === '/api/v1/auth/me') {
          response.end('{}');
          return;
        }
        if (url.pathname === '/api/v1/boundary/patch') {
          assert.equal(request.method, 'PATCH');
          counts.patches++;
          response.end(body);
          return;
        }
        if (url.pathname === '/api/v1/boundary/redirect') {
          response.writeHead(302, { Location: '/api/v1/boundary/redirected' }).end();
          return;
        }
        if (url.pathname === '/api/v1/boundary/redirected') {
          counts.redirected++;
          response.end('{}');
          return;
        }
        if (url.pathname === '/api/v1/boundary/unknown') {
          assert.equal(request.method, 'POST');
          counts.unknownPosts++;
          request.socket.destroy();
          return;
        }
        if (url.pathname === '/api/v1/attachments') {
          assert.equal(request.method, 'POST');
          counts.uploads++;
          response.end('uploaded');
          return;
        }
        if (url.pathname === '/api/v1/events') {
          assert.equal(request.headers['last-event-id'], '41');
          response.setHeader('Content-Type', 'text/event-stream');
          const bytes = Buffer.from(
            'id: 42\r\ndata: {"taskId":"00000000-0000-4000-8000-000000000011","revision":8,"text":"中文😀"}\r\n\r\nid: 43\ndata: partial',
          );
          for (let i = 0; i < bytes.length; i += 3) response.write(bytes.subarray(i, i + 3));
          response.end();
          return;
        }
        if (url.pathname === '/api/v1/boundary/counts') {
          response.end(JSON.stringify(counts));
          return;
        }
        response.writeHead(404).end();
      } catch (error) {
        wire.push({ fixtureError: error.message });
        response.writeHead(500).end();
      }
    },
  );
  server.on('tlsClientError', () => {});
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  const log = root + '/' + serial + '.log';
  let result;
  try {
    execFileSync(adb, ['-s', serial, 'reverse', 'tcp:' + port, 'tcp:' + port]);
    const child = spawn(adb, [
      '-s',
      serial,
      'shell',
      'am',
      'instrument',
      '-w',
      '-r',
      '-e',
      'class',
      'com.agenthub.android.TaskHubTransportTest',
      '-e',
      'taskHubEndpoint',
      'https://127.0.0.1:' + port,
      '-e',
      'taskHubFingerprint',
      pin,
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    ]);
    const chunks = [];
    child.stdout.on('data', (b) => chunks.push(b));
    child.stderr.on('data', (b) => chunks.push(b));
    const exit = await new Promise((r, j) => {
      child.once('error', j);
      child.once('close', r);
    });
    const text = Buffer.concat(chunks).toString('utf8');
    await writeFile(log, text, { mode: 0o600 });
    assert.equal(exit, 0);
    assert(text.includes('OK (1 test)') && !text.includes('FAILURES'));
    assert.deepEqual(counts, {
      publicCredential: 0,
      unknownPosts: 1,
      patches: 1,
      redirected: 0,
      uploads: 1,
    });
    assert(!wire.some((x) => x.fixtureError));
    result = { serial, status: 'passed', counts, wire, log };
  } finally {
    try {
      execFileSync(adb, ['-s', serial, 'reverse', '--remove', 'tcp:' + port]);
    } finally {
      server.closeAllConnections();
      await new Promise((r) => server.close(r));
    }
    reports.push(result ?? { serial, status: 'failed', counts, wire, log });
    await writeFile(
      root + '/report.json',
      JSON.stringify({ kind: 'synthetic-HTTPS-boundary-only', reports }, null, 2) + '\n',
      { mode: 0o600 },
    );
  }
}
console.log(root + '/report.json');
