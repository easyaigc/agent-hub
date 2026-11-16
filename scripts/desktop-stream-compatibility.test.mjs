import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'node:net';
import { chmod, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DesktopFrameDecoder } from '../packages/codex-adapter/dist/desktop-framing.js';
import {
  DesktopIpc,
  DesktopStreamCompatibilityError,
  desktopTurns,
} from '../packages/codex-adapter/dist/desktop-ipc.js';
import {
  desktopFixtureEndpoint,
  listenDesktopFixture,
  prepareDesktopFixtureIpc,
} from './fixtures/desktop-ipc-transport.mjs';

const id = '11111111-1111-4111-8111-111111111111';
const state = () => ({
  id,
  hostId: 'local',
  turns: [],
  requests: [],
  threadRuntimeStatus: { type: 'active', activeFlags: [] },
  turnHistory: {
    kind: 'canonical',
    history: {
      entitiesByKey: { tip: { turnId: 'turn', status: 'inProgress', items: [] } },
      islands: [{ entries: [{ value: 'tip' }] }],
      generation: 0,
      isComplete: true,
    },
  },
  addedByNewDesktop: { retained: true },
});

async function fixture(run) {
  const root = await mkdtemp(join(tmpdir(), 'desktop-stream-compatibility-'));
  await chmod(root, 0o700);
  let peer, ipc;
  let version = 13,
    revision = 1,
    native = state(),
    following = 0;
  const commands = [];
  const send = (message) => {
    const bytes = Buffer.from(JSON.stringify(message)),
      header = Buffer.alloc(4);
    header.writeUInt32LE(bytes.length);
    peer.write(Buffer.concat([header, bytes]));
  };
  const broadcast = (change, wireVersion = version, source = 'owner') =>
    send({
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: wireVersion,
      sourceClientId: source,
      params: { hostId: 'local', conversationId: id, change },
    });
  const snapshot = () => broadcast({ type: 'snapshot', revision, conversationState: native });
  const server = createServer((socket) => {
    peer = socket;
    socket.on('error', () => {});
    const decoder = new DesktopFrameDecoder((bytes) => {
      const message = JSON.parse(bytes);
      if (message.method === 'thread-stream-following-changed') {
        following++;
        snapshot();
      } else if (message.type === 'request') {
        if (!['initialize', 'thread-owner-discovery'].includes(message.method))
          commands.push(message);
        send({
          type: 'response',
          requestId: message.requestId,
          resultType: 'success',
          method: message.method,
          handledByClientId: 'owner',
          result:
            message.method === 'initialize'
              ? { clientId: 'observer' }
              : message.method === 'thread-owner-discovery'
                ? { supportsUntrustedAppInput: true }
                : { method: message.method, result: { accepted: true } },
        });
      }
    });
    socket.on('data', (bytes) => decoder.push(bytes));
  });
  try {
    const path = desktopFixtureEndpoint(join(root, 'ipc.sock'));
    await listenDesktopFixture(server, path);
    ipc = new DesktopIpc(path);
    await prepareDesktopFixtureIpc(ipc, path);
    await ipc.connect();
    await run({
      ipc,
      commands,
      broadcast,
      snapshot,
      get following() {
        return following;
      },
      set version(value) {
        version = value;
      },
      set revision(value) {
        revision = value;
      },
      set native(value) {
        native = value;
      },
    });
  } finally {
    ipc?.close();
    peer?.destroy();
    await new Promise((done) => server.close(done));
    await rm(root, { recursive: true, force: true });
  }
}

for (const version of [11, 13, 14]) {
  test(`v${version} common contract reads canonical history, streams completion and targets one send`, () =>
    fixture(async (f) => {
      f.version = version;
      const snapshot = await f.ipc.follow(id, 1000, true);
      assert.equal(f.ipc.hasOwner(id), true);
      assert.equal(desktopTurns(snapshot)[0].status, 'inProgress');
      assert.deepEqual(snapshot.addedByNewDesktop, { retained: true });
      assert.equal(f.commands.length, 0, 'Following must never submit model input');
      const completed = new Promise((done) => {
        f.ipc.onSnapshot = (_id, value) => {
          if (value.threadRuntimeStatus.type === 'idle') done(value);
        };
      });
      f.broadcast({
        type: 'patches',
        baseRevision: 1,
        revision: 2,
        patches: [
          { op: 'replace', path: ['threadRuntimeStatus'], value: { type: 'idle' } },
          {
            op: 'replace',
            path: ['turnHistory', 'history', 'entitiesByKey', 'tip', 'status'],
            value: 'completed',
          },
        ],
      });
      assert.equal(desktopTurns(await completed)[0].status, 'completed');
      f.revision = 2;
      f.native = f.ipc.state(id);
      await f.ipc.command(id, 'thread-follower-start-turn', {
        turnStart: { request: { threadId: id, clientUserMessageId: 'one-input' } },
      });
      assert.equal(f.commands.length, 1);
      assert.equal(f.commands[0].targetClientId, 'owner');
      assert.equal(
        f.commands[0].version,
        2,
        'Execution wire versions are independent of the stream',
      );
      assert.equal(f.commands[0].params.turnStart.request.clientUserMessageId, 'one-input');
    }));
}

test('schema failure releases connect/send preflight promptly; a later valid snapshot recovers without input', () =>
  fixture(async (f) => {
    f.native = {
      ...state(),
      turnHistory: { kind: 'canonical', history: { islands: 'changed-contract' } },
    };
    const started = performance.now();
    await assert.rejects(f.ipc.follow(id, 1000, true), DesktopStreamCompatibilityError);
    assert(
      performance.now() - started < 1000,
      'Do not spend the 5-second snapshot wait or 180-second phone wait',
    );
    assert.equal(f.ipc.hasOwner(id), false);
    await assert.rejects(f.ipc.command(id, 'thread-follower-start-turn', {}), /桌面实时状态数据/);
    assert.equal(f.commands.length, 0);
    f.native = state();
    await f.ipc.follow(id, 1000, true);
    assert.equal(f.ipc.hasOwner(id), true);
    assert.equal(f.commands.length, 0);
  }));

test('wire version change requires a fresh snapshot before new patches can change runtime state', () =>
  fixture(async (f) => {
    f.version = 11;
    await f.ipc.follow(id, 1000, true);
    const following = f.following;
    f.version = 13;
    f.revision = 2;
    f.native = { ...state(), threadRuntimeStatus: { type: 'idle' } };
    const refreshed = new Promise((done) => {
      f.ipc.onSnapshot = (_id, value) => done(value);
    });
    f.broadcast({
      type: 'patches',
      baseRevision: 1,
      revision: 2,
      patches: [{ op: 'replace', path: ['threadRuntimeStatus'], value: { type: 'systemError' } }],
    });
    assert.equal((await refreshed).threadRuntimeStatus.type, 'idle');
    assert.equal(f.following, following + 1);
    assert.equal(f.commands.length, 0);
  }));

test('wrong-target snapshots and malformed patches never grant control or execute a message', () =>
  fixture(async (f) => {
    f.native = { ...state(), id: 'another-thread' };
    await assert.rejects(f.ipc.follow(id, 1000, true), DesktopStreamCompatibilityError);
    assert.equal(f.ipc.hasOwner(id), false);
    f.native = state();
    await f.ipc.follow(id, 1000, true);
    const invalidated = new Promise((done) => {
      f.ipc.onUnavailable = done;
    });
    f.broadcast({ type: 'patches', baseRevision: 1, revision: 2, patches: null });
    await invalidated;
    assert.equal(f.ipc.hasOwner(id), false);
    assert.equal(f.commands.length, 0);
  }));
