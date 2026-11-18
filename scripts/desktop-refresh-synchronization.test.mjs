import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { mkdtemp, chmod, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { test } from 'node:test';
import {
  desktopFixtureEndpoint,
  listenDesktopFixture,
  prepareDesktopFixtureRuntime,
} from './fixtures/desktop-ipc-transport.mjs';

const runtimeModule = process.env.SYNC_TEST_RUNTIME_MODULE
  ? pathToFileURL(resolve(process.env.SYNC_TEST_RUNTIME_MODULE)).href
  : new URL('../packages/codex-adapter/dist/runtime-session.js', import.meta.url).href;
const { RuntimeSession } = await import(runtimeModule);

// A framed owner drives the production projection and poll. Only the duration
// of that asynchronous projection is controlled; no HTTP snapshots are forged.
async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'ah-sync-'));
  await chmod(directory, 0o700);
  const socket = desktopFixtureEndpoint(join(directory, 'desktop.sock'));
  const peers = new Set(),
    methods = [];
  let peer,
    session,
    revision = 1,
    text = 'verified original';
  const state = () => ({
    id: 'refresh-thread',
    hostId: 'local',
    cwd: directory,
    title: 'Refresh boundary',
    requests: [],
    threadRuntimeStatus: { type: 'idle' },
    turns: [
      {
        turnId: 'original-turn',
        status: 'completed',
        items: [{ id: 'original-answer', type: 'agentMessage', text }],
      },
    ],
  });
  const send = (value) => {
    const bytes = Buffer.from(JSON.stringify(value)),
      header = Buffer.alloc(4);
    header.writeUInt32LE(bytes.length);
    peer.write(header);
    peer.write(bytes);
  };
  const snapshot = () =>
    send({
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: 11,
      sourceClientId: 'original-owner',
      params: {
        hostId: 'local',
        conversationId: 'refresh-thread',
        change: { type: 'snapshot', revision, conversationState: state() },
      },
    });
  const server = createServer((connected) => {
    peer = connected;
    peers.add(peer);
    peer.on('error', () => {});
    let buffer = Buffer.alloc(0);
    peer.on('data', (bytes) => {
      buffer = Buffer.concat([buffer, bytes]);
      while (buffer.length >= 4 && buffer.length >= buffer.readUInt32LE(0) + 4) {
        const length = buffer.readUInt32LE(0);
        const request = JSON.parse(buffer.subarray(4, length + 4));
        buffer = buffer.subarray(length + 4);
        methods.push(request.method);
        if (request.method === 'initialize')
          send({
            type: 'response',
            requestId: request.requestId,
            resultType: 'success',
            result: { clientId: 'read-only-refresh' },
          });
        else if (request.method === 'thread-owner-discovery')
          send({
            type: 'response',
            requestId: request.requestId,
            resultType: 'success',
            handledByClientId: 'original-owner',
            result: { supportsUntrustedAppInput: true },
          });
        else if (request.method === 'thread-stream-following-changed') snapshot();
        else if (request.method === 'thread-follower-read-model-settings')
          send({
            type: 'response',
            requestId: request.requestId,
            resultType: 'success',
            handledByClientId: 'original-owner',
            method: request.method,
            result: { settings: { model: 'fixture', reasoningEffort: 'xhigh' } },
          });
        else assert.fail('Unexpected execution method: ' + request.method);
      }
    });
  });
  let release;
  try {
    await listenDesktopFixture(server, socket);
    session = new RuntimeSession({
      root: directory,
      directory: join(directory, 'state'),
      name: 'Refresh boundary',
      mode: 'desktop-runtime',
      socket,
    });
    prepareDesktopFixtureRuntime(session.runtime);
    session.runtime.reader.connect = async () =>
      Object.defineProperty(session.runtime.reader, 'ready', { value: true, configurable: true });
    session.state.threads = [
      {
        id: 'refresh-thread',
        hostId: session.hostId,
        projectId: null,
        title: 'Refresh boundary',
        state: 'unknown',
        turnId: null,
        messages: [],
        plan: [],
        error: null,
      },
    ];
    await session.connect();
    const initial = await session.refreshHistory('refresh-thread', undefined, true);
    assert.equal(initial.synchronization.state, 'live');
    const unknown = {
      hostId: session.hostId,
      threadId: 'refresh-thread',
      clientMessageId: 'original-unknown-operation',
      state: 'uncertain',
      turnId: null,
      error: null,
      reply: { mode: 'next-turn', text: 'Never replay', attachmentIds: [] },
    };
    session.state.receipts.push(unknown);
    const originalUnknown = JSON.stringify(unknown);
    const originalProjection = session.projectDesktopMessages.bind(session);
    let failProjection = false;
    const gate = new Promise((done) => {
      release = done;
    });
    session.projectDesktopMessages = async (...args) => {
      await gate;
      if (failProjection) throw Error('Actual projection failure');
      return originalProjection(...args);
    };
    text = 'new authoritative answer';
    revision++;
    snapshot();
    for (let i = 0; i < 100 && !session.desktopProjections.has('refresh-thread'); i++)
      await delay(5);
    assert(session.desktopProjections.has('refresh-thread'));
    await run({
      session,
      initial,
      release,
      originalProjection,
      snapshot,
      fail: () => {
        failProjection = true;
      },
      delay,
    });
    assert.equal(
      JSON.stringify(unknown),
      originalUnknown,
      'Reads cannot settle or replay old input',
    );
    assert(
      methods.every((method) =>
        [
          'initialize',
          'thread-owner-discovery',
          'thread-stream-following-changed',
          'thread-follower-read-model-settings',
        ].includes(method),
      ),
    );
  } finally {
    release?.();
    session?.close();
    for (const connection of peers) connection.destroy();
    await new Promise((done) => server.close(done));
    await rm(directory, { recursive: true, force: true });
  }
}

test('refresh holds incomplete snapshots without losing epoch/cursor or declaring stale data live', () =>
  fixture(async ({ session, release }) => {
    const started = performance.now();
    const pending = await session.poll(0, '', new AbortController().signal, 'refresh-thread', true);
    assert(
      performance.now() - started < 2500,
      'Projection cannot strand the long poll indefinitely',
    );
    assert.deepEqual(
      pending.events,
      [],
      'A pending projection is not a synchronization failure snapshot',
    );
    assert.equal(pending.epoch, '');
    assert.equal(pending.cursor, 0);
    assert.equal(pending.reset, false);
    assert.equal(
      session.history('refresh-thread', true).synchronization.state,
      'unavailable',
      'An unfinished projection must not be mislabeled live',
    );
    const job = session.desktopProjections.get('refresh-thread');
    release();
    await job.work;
    const complete = await session.poll(
      pending.cursor,
      pending.epoch,
      new AbortController().signal,
      'refresh-thread',
      true,
    );
    assert.equal(complete.reset, true);
    assert.equal(complete.events[0].snapshot.synchronization.state, 'live');
    assert.equal(complete.events[0].snapshot.messages.at(-1).text, 'new authoritative answer');
  }));

test('short refresh publishes only the completed authoritative projection', () =>
  fixture(async ({ session, release, initial, delay }) => {
    const pending = session.poll(
      initial.cursor,
      initial.epoch,
      new AbortController().signal,
      'refresh-thread',
      true,
    );
    await delay(150);
    release();
    const result = await pending;
    assert.equal(result.events[0].snapshot.synchronization.state, 'live');
    assert.equal(result.events[0].snapshot.messages.at(-1).text, 'new authoritative answer');
  }));

test('real owner loss during a refresh still returns an unavailable snapshot', () =>
  fixture(async ({ session, initial, delay }) => {
    const pending = session.poll(
      initial.cursor,
      initial.epoch,
      new AbortController().signal,
      'refresh-thread',
      true,
    );
    await delay(150);
    session.runtime.ipc.invalidate('refresh-thread');
    const result = await pending;
    assert.equal(result.events[0].snapshot.synchronization.state, 'unavailable');
    assert.equal(result.events[0].snapshot.runtime.canControl, false);
  }));

test('a failed new projection cannot reuse an old live proof', () =>
  fixture(async ({ session, initial, release, fail, delay }) => {
    fail();
    const projection = session.desktopProjections.get('refresh-thread');
    release();
    await assert.rejects(projection.work, /Actual projection failure/);
    await delay(0);
    const result = await session.poll(
      initial.cursor,
      initial.epoch,
      new AbortController().signal,
      'refresh-thread',
      true,
    );
    assert.equal(result.events[0].snapshot.synchronization.state, 'unavailable');
  }));

test('navigation cancellation releases the pending projection wait promptly', () =>
  fixture(async ({ session, delay }) => {
    const controller = new AbortController(),
      started = performance.now();
    const pending = session.poll(0, '', controller.signal, 'refresh-thread', true);
    await delay(100);
    controller.abort();
    await pending;
    assert(
      performance.now() - started < 700,
      'Cancelled navigation cannot keep a projection waiter',
    );
  }));

test('history joins a newer projection arriving during its final metadata read', () =>
  fixture(async ({ session, release, originalProjection, snapshot, delay }) => {
    const first = session.desktopProjections.get('refresh-thread');
    release();
    await first.work;
    let releaseLate;
    const late = new Promise((done) => {
      releaseLate = done;
    });
    session.projectDesktopMessages = async (...args) => {
      await late;
      return originalProjection(...args);
    };
    const originalQueue = session.refreshNativeQueue;
    let injected = false;
    session.refreshNativeQueue = async (...args) => {
      if (!injected) {
        injected = true;
        snapshot();
        await delay(30);
      }
      return originalQueue.apply(session, args);
    };
    let finished = false;
    const read = session.refreshHistory('refresh-thread', undefined, true).then((value) => {
      finished = true;
      return value;
    });
    try {
      await delay(100);
      assert(session.desktopProjections.has('refresh-thread'));
      assert.equal(finished, false, 'Final metadata reads cannot bypass the new projection');
    } finally {
      releaseLate();
    }
    assert.equal((await read).synchronization.state, 'live');
  }));
