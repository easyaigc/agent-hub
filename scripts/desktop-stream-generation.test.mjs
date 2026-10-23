import assert from 'node:assert/strict';
import { chmod, mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  desktopFixtureEndpoint,
  listenDesktopFixture,
  prepareDesktopFixtureIpc,
} from './fixtures/desktop-ipc-transport.mjs';
import { DesktopFrameDecoder } from '../packages/codex-adapter/dist/desktop-framing.js';

const { DesktopIpc, desktopTurns } = await import('../packages/codex-adapter/dist/desktop-ipc.js');

const id = '11111111-1111-4111-8111-111111111111';
const head = (state) => desktopTurns(state).at(-1)?.turnId;
const state = (input, turns) => ({
  id,
  hostId: 'local',
  latestTurnStartMessageId: input,
  turnHistory: {
    kind: 'canonical',
    history: {
      generation: 0,
      isComplete: true,
      entitiesByKey: Object.fromEntries(
        turns.map(([turnId, status]) => [turnId, { turnId, status, items: [] }]),
      ),
      islands: [{ entries: turns.map(([turnId]) => ({ value: turnId })) }],
    },
  },
});
const older = state('old-input', [
  ['a', 'completed'],
  ['b', 'inProgress'],
]);
// The owner may evict an older prefix while preserving the exact previous tip.
const current = state('new-input', [
  ['b', 'failed'],
  ['c', 'completed'],
  ['d', 'inProgress'],
]);
const tick = () => new Promise((resolve) => setTimeout(resolve, 25));
async function fixture(run) {
  const root = await mkdtemp(join(tmpdir(), 'desktop-generation-'));
  await chmod(root, 0o700);
  let peer,
    owner = 'owner-one',
    following = () => {};
  const executions = [],
    send = (value) => {
      const body = Buffer.from(JSON.stringify(value)),
        header = Buffer.alloc(4);
      header.writeUInt32LE(body.length);
      peer.write(Buffer.concat([header, body]));
    };
  const snapshot = (revision, conversationState, from = owner) =>
    send({
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: 11,
      sourceClientId: from,
      params: {
        hostId: 'local',
        conversationId: id,
        change: { type: 'snapshot', revision, conversationState },
      },
    });
  const patches = (baseRevision, revision, values) =>
    send({
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: 11,
      sourceClientId: owner,
      params: {
        hostId: 'local',
        conversationId: id,
        change: { type: 'patches', baseRevision, revision, patches: values },
      },
    });
  const server = createServer((socket) => {
    peer = socket;
    const decoder = new DesktopFrameDecoder(
      (body) => {
        const message = JSON.parse(body.toString());
        if (message.type === 'request') {
          if (message.method === 'initialize')
            send({
              type: 'response',
              requestId: message.requestId,
              resultType: 'success',
              result: { clientId: 'reader' },
            });
          else if (message.method === 'thread-owner-discovery')
            send({
              type: 'response',
              requestId: message.requestId,
              resultType: 'success',
              handledByClientId: owner,
              result: { supportsUntrustedAppInput: true },
            });
          else if (message.method === 'thread-follower-read-model-settings')
            send({
              type: 'response',
              requestId: message.requestId,
              resultType: 'success',
              handledByClientId: owner,
              method: message.method,
              result: { settings: { model: 'fixture', reasoningEffort: 'xhigh' } },
            });
          else executions.push(message.method);
        } else if (message.method === 'thread-stream-following-changed') following();
      },
      {
        onError(error) {
          socket.destroy(error);
        },
      },
    );
    socket.on('data', (bytes) => decoder.push(bytes));
  });
  const address = desktopFixtureEndpoint(join(root, 'ipc.sock'));
  await listenDesktopFixture(server, address);
  const ipc = prepareDesktopFixtureIpc(new DesktopIpc(address));
  try {
    await ipc.connect();
    await run({
      ipc,
      snapshot,
      patches,
      following: (fn) => {
        following = fn;
      },
      owner: (value) => {
        owner = value;
      },
    });
    assert.deepEqual(executions, [], 'Reading generations must never send execution commands');
  } finally {
    ipc.close();
    peer?.destroy();
    await new Promise((resolve) => server.close(resolve));
    await rm(root, { recursive: true, force: true });
  }
}

test('missing full snapshots still deliver bounded original-owner item updates without granting control', () =>
  fixture(async ({ ipc, patches, following, owner }) => {
    following(() => {});
    assert.equal(await ipc.follow(id), undefined);
    const path = ['turnHistory', 'history', 'entitiesByKey', 'tail:0:local:opaque', 'items', 405];
    patches(40, 41, [
      { op: 'add', path, value: { id: 'live-item', type: 'agentMessage', text: 'A' } },
    ]);
    await tick();
    ipc.bindObservation(id, [{ id: 'live-item', runId: 'exact-current-turn' }]);
    await ipc.refreshObservation(id);
    assert.equal(ipc.observation(id).items[0].item.text, 'A');
    patches(41, 42, [{ op: 'replace', path: [...path, 'text'], value: 'AB' }]);
    await tick();
    assert.equal(ipc.observation(id).items[0].item.text, 'AB');
    assert.equal(ipc.state(id), undefined);
    assert.equal(ipc.hasOwner(id), false, 'A bounded observation cannot authorize any command');
    owner('owner-two');
    const realNow = Date.now;
    try {
      Date.now = () => realNow() + 6000;
      await ipc.refreshObservation(id);
      assert.equal(ipc.observation(id), undefined, 'Owner changes revoke all observed ancestors');
    } finally {
      Date.now = realNow;
    }
  }));

test('a verified compact baseline survives legacy history aliases, but a revision gap revokes its ancestors', () =>
  fixture(async ({ ipc, snapshot, patches, following }) => {
    const initial = state('input', [['current-turn', 'inProgress']]);
    initial.turnHistory.history.entitiesByKey['current-turn'].items = [
      { id: 'original-item-id', type: 'agentMessage', text: 'A' },
    ];
    following(() => snapshot(10, initial));
    await ipc.follow(id);
    ipc.states.delete(id); // Model an upstream snapshot loss in our receiver only.
    following(() => {});
    patches(10, 11, [
      {
        op: 'replace',
        path: ['turnHistory', 'history', 'entitiesByKey', 'current-turn', 'items', 0, 'text'],
        value: 'AB',
      },
    ]);
    await tick();
    ipc.bindObservation(id, [{ id: 'legacy-alias-1', runId: 'current-turn' }]);
    await ipc.refreshObservation(id);
    assert.equal(ipc.observation(id).items[0].item.text, 'AB');
    assert.equal(ipc.observation(id).seeded, true);
    assert.equal(ipc.hasOwner(id), false);
    patches(12, 13, [
      {
        op: 'replace',
        path: ['turnHistory', 'history', 'entitiesByKey', 'current-turn', 'items', 0, 'text'],
        value: 'missing ancestor',
      },
    ]);
    await tick();
    assert.deepEqual(ipc.observation(id).items, []);
    assert.equal(ipc.observation(id).seeded, false);
  }));

test('a proven newer source survives revision reset, retired snapshots/patches and repeated following', () =>
  fixture(async ({ ipc, snapshot, patches, following }) => {
    following(() => snapshot(90, older));
    await ipc.follow(id);
    snapshot(12, current);
    await tick();
    assert.equal(
      head(ipc.state(id)),
      'd',
      'A lower revision with exact forward turn ancestry must catch up',
    );
    snapshot(91, older);
    patches(91, 92, [{ op: 'add', path: ['title'], value: 'retired stream' }]);
    patches(12, 13, [
      {
        op: 'replace',
        path: ['turnHistory', 'history', 'entitiesByKey', 'd', 'status'],
        value: 'completed',
      },
    ]);
    await tick();
    assert.equal(head(ipc.state(id)), 'd');
    assert.equal(desktopTurns(ipc.state(id)).at(-1).status, 'completed');
    assert.equal(ipc.state(id).title, undefined);
    following(() => {
      snapshot(92, older);
      snapshot(13, { ...ipc.state(id), title: 'fresh owner read' });
    });
    const refreshed = await ipc.follow(id, 1000, true);
    assert.equal(refreshed.title, 'fresh owner read');
    assert.equal(head(refreshed), 'd');
    assert(ipc.hasOwner(id));
  }));

test('revision reset requires exact ancestry and a verified owner; ordinary stale revisions remain ignored', () =>
  fixture(async ({ ipc, snapshot, following }) => {
    following(() => snapshot(90, older));
    await ipc.follow(id);
    snapshot(12, current, 'unverified-client');
    snapshot(12, state('unrelated-input', [['unrelated', 'completed']]));
    snapshot(
      12,
      state('old-input', [
        ['a', 'completed'],
        ['b', 'completed'],
      ]),
    );
    snapshot(
      12,
      state('new-input', [
        ['b', 'inProgress'],
        ['d', 'inProgress'],
      ]),
    );
    await tick();
    assert.deepEqual(ipc.state(id), older);
    snapshot(91, { ...older, title: 'same stream advances' });
    await tick();
    assert.equal(ipc.state(id).title, 'same stream advances');
  }));

test('a new turn delivered by patches also fences late snapshots of the previous input', () =>
  fixture(async ({ ipc, snapshot, patches, following }) => {
    following(() => snapshot(90, older));
    await ipc.follow(id);
    patches(90, 91, [
      {
        op: 'replace',
        path: ['latestTurnStartMessageId'],
        value: current.latestTurnStartMessageId,
      },
      { op: 'replace', path: ['turnHistory'], value: current.turnHistory },
    ]);
    await tick();
    assert.equal(head(ipc.state(id)), 'd');
    snapshot(200, older);
    patches(200, 201, [{ op: 'add', path: ['title'], value: 'retired after patched start' }]);
    await tick();
    assert.equal(head(ipc.state(id)), 'd');
    assert.equal(ipc.state(id).title, undefined);
    assert(ipc.hasOwner(id));
  }));

test('owner handoff clears retired generations and real revision gaps still revoke availability', () =>
  fixture(async ({ ipc, snapshot, patches, following, owner }) => {
    following(() => snapshot(90, older));
    await ipc.follow(id);
    snapshot(12, current);
    await tick();
    assert.equal(head(ipc.state(id)), 'd');
    following(() => {});
    patches(999, 1000, []);
    await tick();
    assert.equal(ipc.hasOwner(id), false, 'An unknown gap cannot be treated as a retired stream');
    owner('owner-two');
    following(() => snapshot(1, older));
    const handed = await ipc.follow(id, 1000, true);
    assert.equal(head(handed), 'b');
    assert(ipc.hasOwner(id));
  }));
