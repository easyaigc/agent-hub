import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';
import { chmod } from 'node:fs/promises';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopWorkspaceView } from '../packages/codex-adapter/dist/desktop-workspace-view.js';
import { CodexHistoryReader } from '../packages/codex-adapter/dist/history-reader.js';
import { DesktopInstallation } from '../packages/codex-adapter/dist/desktop-installation.js';

// Real app-server JSONL subprocess and real close/stdio barrier, no user state.
const dir = await mkdtemp(join(tmpdir(), 'desktop-update-boundary-'));
const originalSpawn = childProcess.spawn;
const originalTimeout = globalThis.setTimeout;
const children = new Set();
const calls = join(dir, 'calls');
const handshake = join(dir, 'initialized-pid');
const old = join(dir, 'old.mjs');
const replacement = join(dir, 'new.mjs');
const source = `#!/usr/bin/env node
import {createInterface} from 'node:readline';
import {appendFileSync,existsSync,writeFileSync} from 'node:fs';
process.on('SIGTERM',()=>process.exit(0));
createInterface({input:process.stdin}).on('line',line=>{const r=JSON.parse(line);appendFileSync(${JSON.stringify(calls)},r.method+'\\n');if(r.method==='initialized')writeFileSync(${JSON.stringify(handshake)},String(process.pid));if(r.id!==undefined){if(r.method==='project/list' && existsSync(${JSON.stringify(join(dir, 'drop'))}))process.exit(4);if(r.method==='model/list' && existsSync(${JSON.stringify(join(dir, 'invalid'))}))process.stdout.write('not-json\\n');else process.stdout.write(JSON.stringify({id:r.id,result:r.method==='project/list'||r.method==='thread/list'?{data:[],nextCursor:null}:{}})+'\\n')}});
`;
let reader;
try {
  await writeFile(old, source, { mode: 0o700 });
  await writeFile(replacement, source, { mode: 0o700 });
  childProcess.spawn = (command, args, options) => {
    const child =
      command === old || command === replacement
        ? originalSpawn(process.execPath, [command, ...args], options)
        : originalSpawn(command, args, options);
    children.add(child);
    child.once('close', () => children.delete(child));
    return child;
  };
  syncBuiltinESMExports();
  reader = new CodexHistoryReader('test-host', 'test', old);
  await reader.configureExecutable(old, 'generation-1');
  await reader.connect();
  // connect has written initialized, but the child may not have consumed it.
  // Freeze the no-RPC baseline only after this exact fixture received it.
  const handshakeDeadline = performance.now() + 1000;
  while (
    (await readFile(handshake, 'utf8').catch((error) => {
      if (error.code === 'ENOENT') return null;
      throw error;
    })) !== String(reader.process.pid)
  ) {
    assert(performance.now() < handshakeDeadline, 'Fixture did not consume initialized');
    await new Promise((resolve) => originalTimeout(resolve, 5));
  }
  // Reserved Host cursors must never reach a native metadata method, even
  // before the target is registered in the reader's catalog.
  const beforeCursor = await readFile(calls, 'utf8');
  for (const cursor of ['desktop-page-v1:invalid', 'desktop-page-v1:' + 'x'.repeat(9000)]) {
    await assert.rejects(
      reader.history('cold-thread', cursor),
      (e) => e.code === 'CODEX_READER_HOST_CURSOR',
    );
    await assert.rejects(
      reader.read('thread/items/list', { threadId: 'cold-thread', cursor }),
      (e) => e.code === 'CODEX_READER_HOST_CURSOR',
    );
  }
  assert.equal(await readFile(calls, 'utf8'), beforeCursor, 'Host cursor rejection writes no RPC');
  const sameGeneration = reader.process;
  await reader.configureExecutable(old, 'generation-1');
  assert.strictEqual(reader.process, sameGeneration, 'unchanged generation keeps reader');
  let samePathClosed = false;
  sameGeneration.once('close', () => {
    samePathClosed = true;
  });
  await reader.configureExecutable(old, 'generation-2');
  assert(samePathClosed, 'same-path changed generation waits for old close');
  await reader.connect();
  assert.notStrictEqual(
    reader.process,
    sameGeneration,
    'same-path changed generation starts a new reader',
  );
  const prior = reader.process;
  let exited = false;
  prior.once('close', () => {
    exited = true;
  });
  await reader.configureExecutable(replacement);
  assert(exited, 'old reader close must precede replacement');
  await reader.connect();
  assert(reader.ready);
  await assert.rejects(reader.read('turn/start', {}), /Read-only method/);
  assert(!(await readFile(calls, 'utf8')).includes('turn/start'));
  await writeFile(join(dir, 'invalid'), '1');
  await assert.rejects(reader.read('model/list', {}), (e) => e.code === 'CODEX_READER_PROTOCOL');
  await rm(join(dir, 'invalid'));
  reader.close();
  await reader.processClosed;

  // An uncooperative process must not be replaced before close is confirmed.
  reader = new CodexHistoryReader('test-host', 'test', old);
  await reader.connect();
  const held = reader.process;
  const originalKill = held.kill;
  try {
    held.kill = () => true;
    globalThis.setTimeout = (fn, ms, ...args) =>
      originalTimeout(fn, ms === 10000 ? 120 : ms, ...args);
    await assert.rejects(
      reader.configureExecutable(replacement),
      (e) => e.code === 'CODEX_READER_EXIT_UNCONFIRMED',
    );
    assert.equal(reader.executable, old);
    assert.equal(reader.process, held);
    assert.equal(held.exitCode, null);
  } finally {
    held.kill = originalKill;
    globalThis.setTimeout = originalTimeout;
    held.kill('SIGKILL');
    await reader.processClosed;
  }
  await reader.configureExecutable(replacement);
  await reader.connect();
  assert(reader.ready);
  reader.close();
  await reader.processClosed;

  // Windows installation discovery needs its native identity fixture; report it
  // as skipped here instead of constructing a coordinator without config.
  if (process.platform !== 'win32') {
    const options = { executable: old };
    const install = new DesktopInstallation(options, 'test-host');
    const [a, b, c] = await Promise.all([install.resolve(), install.resolve(), install.resolve()]);
    assert.strictEqual(a, b);
    assert.strictEqual(b, c);
    assert.equal(a.executable, old);
    const sock = join(dir, 'ipc.sock');
    const ipcMethods = [];
    const peers = new Set();
    const server = createServer((peer) => {
      peers.add(peer);
      peer.on('close', () => peers.delete(peer));
      let buffer = Buffer.alloc(0);
      peer.on('data', (data) => {
        buffer = Buffer.concat([buffer, data]);
        while (buffer.length >= 4 && buffer.length >= buffer.readUInt32LE(0) + 4) {
          const len = buffer.readUInt32LE(0);
          const request = JSON.parse(buffer.subarray(4, len + 4));
          buffer = buffer.subarray(len + 4);
          ipcMethods.push(request.method);
          if (request.type === 'request' && request.method === 'initialize') {
            const body = Buffer.from(
              JSON.stringify({
                type: 'response',
                requestId: request.requestId,
                resultType: 'success',
                method: request.method,
                result: { clientId: 'test-client' },
              }),
            );
            const head = Buffer.alloc(4);
            head.writeUInt32LE(body.length);
            peer.write(Buffer.concat([head, body]));
          }
        }
      });
    });
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(sock, resolve);
    });
    await chmod(sock, 0o600);
    const session = new RuntimeSession({
      directory: join(dir, 'session'),
      root: dir,
      name: 'test',
      mode: 'desktop-runtime',
      socket: sock,
      executable: replacement,
    });
    let view;
    try {
      await session.runtime.connect();
      assert(session.runtime.ipc.ready);
      view = new DesktopWorkspaceView(session, 'test', old, join(dir, 'state.json'));
      await view.workspace();
      assert.equal(view.status.state, 'ready');
      const before = view.reader.process;
      assert(before && before.exitCode === null);
      before.kill('SIGKILL');
      await view.reader.processClosed;
      assert.equal(
        view.status.state,
        'unavailable',
        'reader loss immediately degrades the catalog until the full background read succeeds',
      );
      assert(session.runtime.ipc.ready, 'auxiliary reader loss must not disconnect primary IPC');
      const recoveryDeadline = Date.now() + 5000;
      while (view.status.state !== 'ready' && Date.now() < recoveryDeadline)
        await new Promise((resolve) => setTimeout(resolve, 25));
      assert.equal(
        view.status.state,
        'ready',
        'background recovery must succeed without an explicit read',
      );
      assert(view.reader.ready);
      assert.notStrictEqual(view.reader.process, before, 'recovery must start a new reader');
      assert(session.runtime.ipc.ready);
      assert(
        ipcMethods.every((method) => method === 'initialize'),
        'catalog recovery must not send desktop write commands',
      );
      view.close();
      await view.reader.processClosed;
      await assert.rejects(view.workspace(), /closed/);
      const closing = new DesktopWorkspaceView(session, 'test', old, join(dir, 'state.json'));
      const count = children.size;
      const pending = closing.workspace();
      closing.close();
      await assert.rejects(pending);
      assert(
        !closing.reader.ready && !closing.reader.process,
        'closing while connecting must not spawn a reader',
      );
      assert.equal(children.size, count);
      const failing = new DesktopWorkspaceView(session, 'test', old, join(dir, 'state.json'));
      await writeFile(join(dir, 'drop'), '1');
      const beforeCalls = (await readFile(calls, 'utf8'))
        .split('\n')
        .filter((v) => v === 'project/list').length;
      await assert.rejects(failing.workspace(), (e) => e.status === 503 || e.statusCode === 503);
      const afterCalls = (await readFile(calls, 'utf8'))
        .split('\n')
        .filter((v) => v === 'project/list').length;
      assert.equal(afterCalls - beforeCalls, 2, 'read is retried at most once');
      assert.equal(failing.status.state, 'unavailable');
      assert(failing.status.retryAfterMs > 0);
      await assert.rejects(failing.workspace());
      assert.equal(
        (await readFile(calls, 'utf8')).split('\n').filter((v) => v === 'project/list').length,
        afterCalls,
        'backoff must suppress new reads',
      );
      assert(session.runtime.ipc.ready);
      failing.close();
      await failing.reader.processClosed;
      const closedCalls = (await readFile(calls, 'utf8')).split('\n').length;
      await new Promise((resolve) => setTimeout(resolve, 1300));
      assert.equal(
        (await readFile(calls, 'utf8')).split('\n').length,
        closedCalls,
        'closed failure must not schedule or spawn another read',
      );
      assert.equal(failing.recoveryTimer, undefined, 'close clears the recovery timer');
      await rm(join(dir, 'drop'));
    } finally {
      view?.close();
      session.close();
      for (const peer of peers) peer.destroy();
      await new Promise((resolve) => server.close(resolve));
    }
  }
  console.log(
    JSON.stringify({
      result: 'passed',
      coverage: [
        'real-reader-exit-barrier',
        'exit-unconfirmed-no-replacement',
        'metadata-read-only',
        'malformed-protocol-not-disconnect',
        'same-path-generation-replacement',
        ...(process.platform === 'win32'
          ? []
          : [
              'coalesced-discovery',
              'explicit-cli-platform',
              'ready-ipc-reader-background-restart',
              'closed-failure-no-background-retry',
              'close-fence',
              'close-during-connect',
              'one-read-retry-and-backoff',
            ]),
      ],
      skipped:
        process.platform === 'win32'
          ? [
              'unix-ipc-view-recovery-and-close',
              'windows-installation-coordinator-discovery-migration-coalescing-backoff',
            ]
          : ['windows-installation-coordinator-discovery-migration-coalescing-backoff'],
      platform: process.platform,
      evidence: 'isolated subprocess fixture; not real desktop or Windows acceptance',
    }),
  );
} finally {
  globalThis.setTimeout = originalTimeout;
  reader?.close();
  for (const child of children) child.kill('SIGKILL');
  await Promise.all(
    [...children].map((child) => new Promise((resolve) => child.once('close', resolve))),
  );
  childProcess.spawn = originalSpawn;
  syncBuiltinESMExports();
  await rm(dir, { recursive: true, force: true });
}
