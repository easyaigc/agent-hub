import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'node:net';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import {
  desktopFixtureEndpoint,
  listenDesktopFixture,
  prepareDesktopFixtureRuntime,
} from './fixtures/desktop-ipc-transport.mjs';

const { RuntimeSession } = await import('../packages/codex-adapter/dist/runtime-session.js');
async function until(predicate, label) {
  const deadline = Date.now() + 5000;
  while (!predicate()) {
    assert(Date.now() < deadline, `Timed out: ${label}`);
    await delay(10);
  }
}

async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'metadata-recovery-'));
  const executable = join(dir, 'reader.mjs');
  await writeFile(
    executable,
    `import {createInterface} from 'node:readline';
createInterface({input:process.stdin}).on('line',line=>{
 const r=JSON.parse(line); if(r.id===undefined)return;
 if(r.method==='model/list')return;
 if(r.method==='config/read'){process.stdout.end();return;}
 process.stdout.write(JSON.stringify({id:r.id,result:{}})+'\\n');
});\n`,
  );
  const spawn = childProcess.spawn;
  childProcess.spawn = (cmd, args, options) =>
    cmd === executable
      ? spawn(process.execPath, [executable, ...args], options)
      : spawn(cmd, args, options);
  syncBuiltinESMExports();
  const endpoint = desktopFixtureEndpoint(join(dir, 'ipc.sock'));
  const peers = new Set();
  const server = createServer((socket) => {
    peers.add(socket);
    socket.on('close', () => peers.delete(socket));
    socket.on('error', () => {});
    let buffer = Buffer.alloc(0);
    socket.on('data', (bytes) => {
      buffer = Buffer.concat([buffer, bytes]);
      while (buffer.length >= 4 && buffer.length >= 4 + buffer.readUInt32LE(0)) {
        const n = buffer.readUInt32LE(0),
          msg = JSON.parse(buffer.subarray(4, 4 + n));
        buffer = buffer.subarray(4 + n);
        if (msg.type === 'request' && msg.method === 'initialize') {
          const body = Buffer.from(
            JSON.stringify({
              type: 'response',
              requestId: msg.requestId,
              resultType: 'success',
              method: msg.method,
              result: { clientId: 'fixture' },
            }),
          );
          const header = Buffer.alloc(4);
          header.writeUInt32LE(body.length);
          socket.write(Buffer.concat([header, body]));
        }
      }
    });
  });
  await listenDesktopFixture(server, endpoint);
  const session = new RuntimeSession({
    mode: 'desktop-runtime',
    directory: join(dir, 'state'),
    root: dir,
    name: 'fixture',
    executable,
    socket: endpoint,
  });
  const runtime = session.runtime;
  prepareDesktopFixtureRuntime(runtime);
  runtime.installation.resolve = async () => ({
    executable,
    generation: 'fixture',
    version: 'fixture',
  });
  session.reconnectDelay = 30;
  t.after(async () => {
    session.close();
    await runtime.reader.closeAndWait();
    for (const peer of peers) peer.destroy();
    await new Promise((resolve) => server.close(resolve));
    childProcess.spawn = spawn;
    syncBuiltinESMExports();
    await rm(dir, { recursive: true, force: true });
  });
  await session.connect();
  const snapshot = { id: 'owner-thread', hostId: 'local', title: 'synthetic', turns: [] };
  runtime.ipc.owners.set('owner-thread', 'fixture-owner');
  runtime.ipc.states.set('owner-thread', { revision: 7, state: snapshot });
  session.state.threads.push(
    ...['owner-thread', 'cached-thread'].map((id) => ({
      id,
      hostId: session.hostId,
      projectId: null,
      title: id,
      state: 'running',
      turnId: 'turn',
      messages: [],
      plan: [],
      error: null,
    })),
  );
  return { session, runtime, peers, snapshot };
}

for (const failure of ['timeout', 'pipe'])
  test(
    `metadata ${failure} preserves IPC and recovers without replay`,
    { timeout: 15000, concurrency: false },
    async (t) => {
      const { session, runtime, snapshot } = await fixture(t);
      const epoch = session.epoch,
        seq = session.seq;
      const dirty = [],
        events = [];
      const originalDirty = session.persistence.dirty.bind(session.persistence);
      session.persistence.dirty = (...args) => {
        dirty.push(args);
        return originalDirty(...args);
      };
      const originalEmit = session.emit.bind(session);
      session.emit = (...args) => {
        events.push(args);
        return originalEmit(...args);
      };
      let metadata = 0,
        disconnected = 0,
        wakes = 0,
        release;
      session.listeners.add(() => wakes++);
      const onMetadata = runtime.onMetadataDisconnect,
        onDisconnect = runtime.onDisconnect;
      runtime.onMetadataDisconnect = () => {
        metadata++;
        onMetadata();
      };
      runtime.onDisconnect = () => {
        disconnected++;
        onDisconnect();
      };
      const connect = runtime.reader.connect.bind(runtime.reader);
      let recoveryStarted = false;
      runtime.reader.connect = async () => {
        recoveryStarted = true;
        await new Promise((resolve) => {
          release = resolve;
        });
        return connect();
      };
      const timeout = globalThis.setTimeout;
      try {
        if (failure === 'timeout')
          globalThis.setTimeout = (fn, ms, ...args) => timeout(fn, ms === 15000 ? 80 : ms, ...args);
        await assert.rejects(
          runtime.reader.read(failure === 'timeout' ? 'model/list' : 'config/read', {}),
          (error) =>
            failure === 'timeout'
              ? error.code === 'CODEX_READER_TIMEOUT'
              : /transport closed|disconnected/i.test(error.message),
        );
      } finally {
        globalThis.setTimeout = timeout;
      }
      assert.equal(metadata, 1);
      assert.equal(disconnected, 0);
      assert.equal(runtime.ipc.ready, true);
      assert.equal(runtime.ipc.hasOwner('owner-thread'), true);
      assert.strictEqual(runtime.ipc.state('owner-thread'), snapshot);
      assert.equal(session.ready, false);
      assert.deepEqual(session.capabilities, []);
      assert.throws(
        () => session.requireReady(),
        (error) => error.code === 'CODEX_RUNTIME_DISCONNECTED',
      );
      assert.equal(session.state.threads[0].state, 'running');
      await until(() => recoveryStarted, 'automatic reader recovery');
      assert.equal(session.ready, false);
      release();
      await until(() => session.ready && !session.reconnecting, 'reader ready');
      assert.equal(session.epoch, epoch);
      assert.equal(session.seq, seq);
      assert.equal(runtime.ipc.hasOwner('owner-thread'), true);
      assert.strictEqual(runtime.ipc.state('owner-thread'), snapshot);
      assert.deepEqual(events, []);
      assert.deepEqual(dirty, []);
      assert(wakes >= 2);
    },
  );

test(
  'real IPC loss during reader recovery retains full disconnect semantics',
  { timeout: 15000, concurrency: false },
  async (t) => {
    const { session, runtime, peers } = await fixture(t);
    const epoch = session.epoch;
    let release,
      started = false;
    const connect = runtime.reader.connect.bind(runtime.reader);
    runtime.reader.connect = async () => {
      started = true;
      await new Promise((resolve) => {
        release = resolve;
      });
      return connect();
    };
    await assert.rejects(runtime.reader.read('config/read', {}));
    await until(() => started, 'reader recovery starts');
    for (const peer of peers) peer.destroy();
    await until(() => !runtime.ipc.ready, 'real IPC disconnect');
    assert.equal(session.state.threads[0].state, 'unknown');
    release();
    await until(() => session.ready && !session.reconnecting, 'full reconnect');
    assert.notEqual(session.epoch, epoch);
    assert.equal(runtime.ipc.hasOwner('owner-thread'), false);
    assert(
      session.events.some(
        (event) => event.kind === 'runtime-reconnected' && event.threadId === 'cached-thread',
      ),
    );
  },
);

test(
  'explicit close cancels scheduled metadata recovery',
  { timeout: 15000, concurrency: false },
  async (t) => {
    const { session, runtime } = await fixture(t);
    session.reconnectDelay = 500;
    let attempts = 0;
    const connect = runtime.reader.connect.bind(runtime.reader);
    runtime.reader.connect = (...args) => {
      attempts++;
      return connect(...args);
    };
    await assert.rejects(runtime.reader.read('config/read', {}));
    session.close();
    await delay(750);
    assert.equal(attempts, 0);
    assert.equal(runtime.reader.ready, false);
    assert.equal(session.ready, false);
  },
);
