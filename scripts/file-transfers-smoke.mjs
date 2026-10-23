import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import {
  mkdtemp,
  mkdir,
  readFile,
  writeFile,
  readdir,
  realpath,
  rm,
  symlink,
  chmod,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { FileTransfers } from '../apps/host-agent/dist/file-transfers.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

const root = await realpath(await mkdtemp(join(tmpdir(), 'agent-hub-transfers-')));
const hostId = randomUUID(),
  destination = join(root, 'Downloads', 'Agent Hub');
await mkdir(join(root, 'state'), { mode: 0o700 });
const store = new FileTransfers(hostId, join(root, 'state'), destination);
const bytes = Buffer.from('真实文件字节 ' + randomUUID());
const manifest = () => ({
  hostId,
  id: randomUUID(),
  name: '报告.txt',
  mediaType: 'text/plain',
  sizeBytes: bytes.length,
  sha256: createHash('sha256').update(bytes).digest('hex'),
});
const source = async function* (data = bytes) {
  yield data.subarray(0, 5);
  yield data.subarray(5);
};
let server;
const originalSpawn = childProcess.spawn;
try {
  const first = manifest();
  const ready = await store.prepare(first);
  assert.equal(ready.phase, 'prepared');
  assert.deepEqual(await store.prepare(first), ready);
  await assert.rejects(store.prepare({ ...first, name: 'changed.txt' }), { status: 409 });
  await assert.rejects(store.prepare({ ...manifest(), hostId: 'another-host' }), { status: 403 });
  for (const name of ['../secret', 'a/b', 'a\\b', 'CON.txt', 'a:', 'trailing.'])
    await assert.rejects(store.prepare({ ...manifest(), name }), { status: 400 });
  await assert.rejects(store.prepare({ ...manifest(), path: '/arbitrary' }), { status: 400 });
  await assert.rejects(store.prepare({ ...manifest(), sizeBytes: 20 * 1024 * 1024 + 1 }), {
    status: 400,
  });
  await assert.rejects(store.upload(first.id, source(Buffer.from('corrupt'))), { status: 400 });
  await assert.rejects(store.upload(first.id, source(Buffer.alloc(bytes.length + 1))), {
    status: 413,
  });
  assert.equal((await store.status(first.id)).phase, 'prepared');
  assert.deepEqual(await readdir(join(destination, '.incoming')), []);
  const done = await store.upload(first.id, source());
  assert.equal(done.phase, 'completed');
  assert.deepEqual(await readFile(done.destinationPath), bytes);
  assert.deepEqual(await store.upload(first.id, source()), done);
  const second = manifest();
  await store.prepare(second);
  const done2 = await store.upload(second.id, source());
  assert.notEqual(done2.destinationPath, done.destinationPath);
  assert.deepEqual(await readFile(done.destinationPath), bytes);
  const broken = manifest();
  await store.prepare(broken);
  await assert.rejects(
    store.upload(
      broken.id,
      (async function* () {
        yield bytes.subarray(0, 5);
        throw Error('connection lost');
      })(),
    ),
  );
  assert.equal((await store.status(broken.id)).phase, 'prepared');
  assert.deepEqual(await readdir(join(destination, '.incoming')), []);
  assert.equal((await store.upload(broken.id, source())).phase, 'completed');
  // Crash after atomic publication but before saving completion: query repairs the receipt.
  const recordPath = join(root, 'state/file-transfers', first.id + '.json');
  await writeFile(recordPath, JSON.stringify({ ...done, phase: 'prepared' }), { mode: 0o600 });
  const restarted = new FileTransfers(hostId, join(root, 'state'), destination);
  assert.equal((await restarted.status(first.id)).phase, 'completed');
  assert.equal((await restarted.catalog()).files.length, 3);
  await rm(done2.destinationPath);
  assert.equal((await restarted.prepare(second)).phase, 'completed');
  assert.equal((await restarted.upload(second.id, source())).phase, 'completed');
  assert(
    !(await readdir(destination)).some((name) => name.includes(second.id)),
    'A moved/deleted received file must never be recreated by retry',
  );
  const reserved = manifest();
  const reservedReceipt = await store.prepare(reserved);
  await symlink(done.destinationPath, reservedReceipt.destinationPath);
  await assert.rejects(store.upload(reserved.id, source()), { status: 409 });
  assert.deepEqual(await readFile(done.destinationPath), bytes);
  const redirected = join(root, 'redirected');
  await symlink(destination, redirected);
  const unsafe = new FileTransfers(hostId, join(root, 'state2'), redirected);
  await assert.rejects(unsafe.prepare(manifest()), { status: 409 });

  const fixture = join(root, 'codex-fixture.cjs');
  await writeFile(
    fixture,
    `#!${process.execPath}\nconst r=require('node:readline');r.createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line);if(m.id!=null)process.stdout.write(JSON.stringify({id:m.id,result:m.method==='initialize'?{userAgent:'transfer-fixture'}:{data:[]}})+'\\n')});`,
  );
  await chmod(fixture, 0o700);
  // Windows cannot execute a .cjs shebang. Only the named fixture is redirected
  // through the actual Node binary; native ACL helpers and all other child
  // processes remain real and unmodified.
  childProcess.spawn = (executable, args, options) =>
    executable === fixture
      ? originalSpawn(process.execPath, [fixture, ...args], options)
      : originalSpawn(executable, args, options);
  syncBuiltinESMExports();
  const token = randomUUID() + randomUUID();
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'managed-runtime',
    CODEX_EXECUTABLE: fixture,
    HOST_AGENT_PORT: '0',
    HOST_AGENT_STATE_DIR: join(root, 'http-state'),
    HOST_AGENT_ROOT: root,
    HOST_AGENT_TOKEN: token,
    HOST_AGENT_TRANSFER_DIR: join(root, 'http-downloads'),
    HOST_AGENT_DISCOVERY_BONJOUR: '0',
  });
  server = await startRuntimeServer();
  const endpoint = `http://127.0.0.1:${server.server.address().port}`;
  const httpFile = { ...manifest(), hostId: server.session.hostId };
  const request = async (path, body, expectedStatus = 200, authorization = token) => {
    const response = await fetch(endpoint + path, {
      method: body ? 'POST' : 'GET',
      headers: {
        Authorization: 'Bearer ' + authorization,
        'Content-Type': Buffer.isBuffer(body) ? 'application/octet-stream' : 'application/json',
      },
      body: body ? (Buffer.isBuffer(body) ? body : JSON.stringify(body)) : undefined,
    });
    assert.equal(response.status, expectedStatus, path);
    return response.json();
  };
  await request('/v1/file-transfers?hostId=wrong', undefined, 403);
  await request('/v1/file-transfers?hostId=' + httpFile.hostId, undefined, 401, 'wrong');
  const health = await request('/healthz');
  assert(health.capabilities.includes('files:upload'));
  await request('/v1/file-transfers', httpFile);
  const route =
    '/v1/file-transfers/content?' +
    new URLSearchParams({ hostId: httpFile.hostId, id: httpFile.id });
  const httpDone = await request(route, bytes);
  assert.equal(httpDone.phase, 'completed');
  assert.deepEqual(await readFile(httpDone.destinationPath), bytes);
  assert.deepEqual(await request(route, bytes), httpDone);
  const listing = await request('/v1/file-transfers?hostId=' + httpFile.hostId);
  assert.equal(listing.files.length, 1);
  assert.equal(
    (await server.session.workspace()).threads.length,
    0,
    'File delivery must not create or run a chat',
  );
  console.log(
    JSON.stringify({
      suite: 'file-transfers',
      status: 'passed',
      platform: process.platform,
      nativeWindowsAcl: process.platform === 'win32',
      fixtureLaunch: 'actual-node-executable-no-shell',
      storageRecoveryIntegrityPathIsolationAndAuthenticatedHTTP: 'passed',
    }),
  );
} finally {
  childProcess.spawn = originalSpawn;
  syncBuiltinESMExports();
  await server?.close();
  await rm(root, { recursive: true, force: true });
}
