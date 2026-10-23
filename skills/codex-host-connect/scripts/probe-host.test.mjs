import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { chmod, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { probeHost } from './probe-host.mjs';

const token = 'fixture-secret-that-must-never-be-displayed';
const hostId = '11111111-1111-4111-8111-111111111111';
const health = {
  service: 'host-agent',
  status: 'ok',
  protocolVersion: '0.1',
};
const workspace = {
  hostId,
  connectionMode: 'desktop-runtime',
  capabilities: ['history:read', 'desktop:creations'],
  projects: [{ id: 'fixture-project', name: 'PRIVATE_PROJECT_NAME', roots: ['/private/work'] }],
  threads: [{ id: 'fixture-thread', title: 'PRIVATE_CHAT_TITLE' }],
};

async function fixture(t, handle) {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'codex-host-skill-'));
  t.after(() => rm(stateDirectory, { recursive: true, force: true }));
  await writeFile(join(stateDirectory, 'access-token'), token, { mode: 0o600 });
  await writeFile(join(stateDirectory, 'runtime.json'), JSON.stringify({ hostId }), {
    mode: 0o600,
  });
  const requests = [];
  const server = createServer((request, response) => {
    requests.push({
      method: request.method,
      path: request.url,
      authorization: request.headers.authorization,
    });
    if (handle?.(request, response)) return;
    response.setHeader('Content-Type', 'application/json');
    if (request.headers.authorization !== 'Bearer ' + token) {
      response.writeHead(401);
      response.end('{}');
    } else {
      response.end(JSON.stringify(request.url === '/healthz' ? health : workspace));
    }
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  t.after(() => new Promise((done) => server.close(done)));
  return { stateDirectory, endpoint: `http://127.0.0.1:${server.address().port}`, requests };
}

test('reports only connection evidence and private summaries, even when creation is advertised', async (t) => {
  const context = await fixture(t);
  const result = await probeHost(context);
  assert.equal(result.hostId, hostId);
  assert.equal(result.authenticationEnforced, true);
  assert.equal(result.indexedProjects, 1);
  assert.equal(result.indexedThreads, 1);
  assert.equal(result.acceptance.desktopCreationAndOpen, 'not-tested');
  for (const secret of [token, 'PRIVATE_PROJECT_NAME', 'PRIVATE_CHAT_TITLE', '/private/work'])
    assert.equal(JSON.stringify(result).includes(secret), false);
  assert.deepEqual(
    context.requests.map((value) => value.method),
    ['GET', 'GET', 'GET'],
  );
  assert.equal(context.requests[0].authorization, undefined);
});

test('rejects a healthy endpoint belonging to a different computer', async (t) => {
  const context = await fixture(t, (request, response) => {
    if (request.url !== '/v1/workspace') return false;
    response.end(JSON.stringify({ ...workspace, hostId: '22222222-2222-4222-8222-222222222222' }));
    return true;
  });
  await assert.rejects(probeHost(context), /WRONG_HOST/);
});

test('rejects a managed executor presented as a desktop connection', async (t) => {
  const context = await fixture(t, (request, response) => {
    if (request.url !== '/v1/workspace') return false;
    response.end(JSON.stringify({ ...workspace, connectionMode: 'managed-runtime' }));
    return true;
  });
  await assert.rejects(probeHost(context), /ORIGINAL_DESKTOP_REQUIRED/);
});

test('refuses an unauthenticated service before sending its token', async (t) => {
  const context = await fixture(t, (_request, response) => {
    response.end(JSON.stringify(health));
    return true;
  });
  await assert.rejects(probeHost(context), /AUTHENTICATION_NOT_ENFORCED/);
  assert.equal(context.requests.length, 1);
  assert.equal(context.requests[0].authorization, undefined);
});

test('refuses remote plaintext and credentials in an endpoint before reading a token', async () => {
  for (const endpoint of ['http://192.0.2.1:8794', 'https://user:password@example.invalid'])
    await assert.rejects(
      probeHost({ endpoint, stateDirectory: '/nonexistent-fixture-state' }),
      /HTTPS_REQUIRED_EXCEPT_LOOPBACK/,
    );
});

test('does not follow an authenticated redirect or forward credentials', async (t) => {
  const destination = await fixture(t);
  const source = await fixture(t, (request, response) => {
    if (!request.headers.authorization) return false;
    response.writeHead(302, { Location: destination.endpoint + '/healthz' });
    response.end();
    return true;
  });
  await assert.rejects(probeHost(source));
  assert.equal(destination.requests.length, 0);
});

test('refuses a readable-by-others token before making any request', async (t) => {
  const context = await fixture(t);
  await chmod(join(context.stateDirectory, 'access-token'), 0o644);
  await assert.rejects(probeHost(context), /UNSAFE_PRIVATE_FILE/);
  assert.equal(context.requests.length, 0);
});

test('CLI redacts failures and cannot overwrite an existing private file', async (t) => {
  const context = await fixture(t);
  const script = fileURLToPath(new URL('probe-host.mjs', import.meta.url));
  const sentinel = join(context.stateDirectory, 'do-not-overwrite.json');
  await writeFile(sentinel, 'unchanged', { mode: 0o600 });
  const child = spawn(process.execPath, [
    script,
    '--state-dir',
    context.stateDirectory,
    '--endpoint',
    context.endpoint,
    '--report',
    sentinel,
  ]);
  let output = '';
  child.stdout.on('data', (value) => {
    output += value;
  });
  child.stderr.on('data', (value) => {
    output += value;
  });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('exit', done);
  });
  assert.equal(code, 1);
  assert.equal(await readFile(sentinel, 'utf8'), 'unchanged');
  assert.equal(output.includes(token), false);
  assert.equal(output.includes(context.stateDirectory), false);
  assert.equal(JSON.parse(output).error, 'PROBE_FAILED');
});
