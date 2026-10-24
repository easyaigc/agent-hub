import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'node:net';
import { EventEmitter } from 'node:events';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { setImmediate as tick } from 'node:timers/promises';
import {
  desktopFixtureEndpoint,
  listenDesktopFixture,
  prepareDesktopFixtureIpc,
} from './fixtures/desktop-ipc-transport.mjs';
import { installWindowsPrivateStateFixture } from './fixtures/windows-private-state-boundary.mjs';

// Synthetic Windows permission transport only; never start its real native Worker.
const permissionRoot = resolve(process.env.OWNER_RECOVERY_FIXTURE_ROOT ?? tmpdir());
await mkdir(permissionRoot, { recursive: true, mode: 0o700 });
const permissions =
  process.platform === 'win32'
    ? installWindowsPrivateStateFixture(
        permissionRoot,
        new URL('../packages/codex-adapter/dist/desktop-platform.js', import.meta.url),
      )
    : undefined;
after(() => permissions?.restore());

// Fail before any real CLI, helper, model or native process can start.
const nativeAttempts = [];
for (const method of [
  'spawn',
  'spawnSync',
  'exec',
  'execSync',
  'execFile',
  'execFileSync',
  'fork',
]) {
  childProcess[method] = (...args) => {
    nativeAttempts.push({ method, executable: String(args[0]) });
    throw new Error('Native process forbidden by synthetic owner-recovery fixture');
  };
}
syncBuiltinESMExports();
const { RuntimeSession } = await import('../packages/codex-adapter/dist/runtime-session.js');
const { DesktopFrameDecoder } = await import('../packages/codex-adapter/dist/desktop-framing.js');
const traces = [];
after(() => {
  if (process.env.OWNER_RECOVERY_EVENTLOG)
    writeFileSync(
      process.env.OWNER_RECOVERY_EVENTLOG,
      JSON.stringify(
        { node: process.version, platform: process.platform, nativeAttempts, traces },
        null,
        2,
      ) + '\n',
      { mode: 0o600 },
    );
  assert.deepEqual(nativeAttempts, [], 'No native/CLI/model process attempted');
});

const state = (id, turn, status, text) => ({
  id,
  hostId: 'local',
  cwd: process.cwd(),
  title: 'Synthetic owner recovery',
  resumeState: 'resumed',
  threadRuntimeStatus: { type: status === 'inProgress' ? 'active' : 'idle' },
  turns: [
    {
      turnId: turn,
      status,
      items: [
        {
          id: turn + '-user',
          type: 'userMessage',
          content: [{ type: 'text', text: 'Synthetic input for ' + turn }],
        },
        { id: turn + '-assistant', type: 'agentMessage', text },
      ],
    },
  ],
  requests: [],
});

async function fixture(t, body) {
  const storage = resolve(process.env.OWNER_RECOVERY_FIXTURE_ROOT ?? tmpdir());
  await mkdir(storage, { recursive: true, mode: 0o700 });
  const directory = await mkdtemp(join(storage, 'case-'));
  // Relative Unix pathname keeps the socket entirely in the private cwd and below AF_UNIX limits.
  const address = desktopFixtureEndpoint('./owner-' + randomUUID() + '.sock');
  const bus = new EventEmitter();
  const trace = { test: t.name, requests: [], received: [], discoveries: [], readerAttempts: [] };
  traces.push(trace);
  let peer,
    revision = 10,
    ticket = 0;
  const registry = new Map(),
    holds = new Map();
  const responseSnapshots = new Map();
  const frame = (message) => {
    const bytes = Buffer.from(JSON.stringify(message)),
      header = Buffer.alloc(4);
    header.writeUInt32LE(bytes.length);
    // Exercise the real production decoder's fragmented header/body path.
    peer.write(header.subarray(0, 2));
    peer.write(Buffer.concat([header.subarray(2), bytes]));
  };
  const snapshot = (id, owner, value, overrides = {}) =>
    frame({
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: 11,
      sourceClientId: owner,
      params: {
        hostId: 'local',
        conversationId: id,
        change: { type: 'snapshot', revision: ++revision, conversationState: value },
      },
      ...overrides,
    });
  const server = createServer((socket) => {
    peer = socket;
    socket.on('error', () => {});
    const decoder = new DesktopFrameDecoder((bytes) => {
      const message = JSON.parse(bytes.toString());
      trace.requests.push(message);
      if (message.type === 'request' && message.method === 'initialize') {
        frame({
          type: 'response',
          requestId: message.requestId,
          resultType: 'success',
          method: message.method,
          result: { clientId: 'fixture-reader' },
        });
      } else if (message.type === 'request' && message.method === 'thread-owner-discovery') {
        const id = message.params.conversationId;
        trace.discoveries.push({ id, timeoutMs: message.timeoutMs });
        const reply = () => {
          const owner = registry.get(id);
          frame(
            owner
              ? {
                  type: 'response',
                  requestId: message.requestId,
                  resultType: 'success',
                  method: message.method,
                  handledByClientId: owner.clientId,
                  result: { supportsUntrustedAppInput: true },
                }
              : {
                  type: 'response',
                  requestId: message.requestId,
                  resultType: 'error',
                  error: 'no-client-found',
                },
          );
        };
        if (holds.has(id)) holds.get(id).push(reply);
        else reply();
      } else if (
        message.type === 'broadcast' &&
        message.method === 'thread-stream-following-changed'
      ) {
        const id = message.params.conversationId,
          owner = registry.get(id);
        assert(owner, 'Only an authenticated registry owner can supply the follow snapshot');
        assert.deepEqual(message.targetClientIds, [owner.clientId]);
        assert.equal(message.params.following, true);
        const supply = responseSnapshots.get(id);
        if (supply) supply();
        else snapshot(id, owner.clientId, owner.state);
      } else {
        assert.fail('Execution or non-read-only IPC method: ' + message.method);
      }
      bus.emit('change');
    });
    socket.on('data', (bytes) => decoder.push(bytes));
  });
  await listenDesktopFixture(server, address);
  const session = new RuntimeSession({
    directory,
    root: directory,
    name: 'Known owner recovery fixture',
    mode: 'desktop-runtime',
    socket: address,
    executable: join(directory, 'never-run-native'),
  });
  const ipc = prepareDesktopFixtureIpc(session.runtime.ipc);
  // Only the unrelated history/native availability boundary is synthetic.
  // follow/receive/owner checks and RuntimeSession.open/projection stay real.
  Object.defineProperty(session.runtime.reader, 'ready', { value: true });
  session.nativeQueueUnsupported = true;
  for (const method of ['connect', 'history', 'read', 'workspace', 'desktopWorkspace']) {
    session.runtime.reader[method] = (...args) => {
      trace.readerAttempts.push({ method, args });
      throw new Error('History/native reader forbidden in owner recovery fixture');
    };
  }
  const originalReceive = ipc.receive;
  ipc.receive = function (message) {
    trace.received.push(message);
    const result = Reflect.apply(originalReceive, this, [message]);
    bus.emit('change');
    return result;
  };
  const originalEmit = session.emit;
  session.emit = function (...args) {
    const result = Reflect.apply(originalEmit, this, args);
    bus.emit('change');
    return result;
  };
  const wait = (predicate, label, timeoutMs = 1200) => {
    if (predicate()) return Promise.resolve();
    return new Promise((done, fail) => {
      const cleanup = () => {
        clearTimeout(timer);
        bus.off('change', check);
      };
      const check = () => {
        if (predicate()) {
          cleanup();
          done();
        }
      };
      const timer = setTimeout(() => {
        cleanup();
        trace.timeout = {
          label,
          owners: [...ipc.owners],
          threads: session.state.threads.map((row) => ({
            id: row.id,
            state: row.state,
            turnId: row.turnId,
          })),
          discoveryCount: trace.discoveries.length,
        };
        fail(new Error(label));
      }, timeoutMs);
      bus.on('change', check);
      check();
    });
  };
  const flush = async () => {
    const marker = ++ticket;
    frame({ type: 'broadcast', method: 'fixture-barrier', marker, params: {} });
    await wait(
      () => trace.received.some((row) => row.method === 'fixture-barrier' && row.marker === marker),
      'Synthetic IPC barrier must arrive',
    );
    await tick();
  };
  const release = (id) => {
    const replies = holds.get(id) ?? [];
    holds.delete(id);
    replies.forEach((reply) => reply());
  };
  const rows = (id) => session.state.threads.find((row) => row.id === id);
  const hasText = (id, text) => rows(id)?.messages.some((message) => message.text === text);
  const count = (id) => trace.discoveries.filter((row) => row.id === id).length;
  const seed = async (id, clientId, value, loaded = true) => {
    registry.set(id, { clientId, state: value });
    if (loaded) await session.open(id);
    else {
      session.state.threads.push({
        id,
        hostId: session.hostId,
        projectId: null,
        title: 'Catalog summary only',
        state: 'unknown',
        turnId: null,
        messages: [],
        plan: [],
        error: null,
      });
      await ipc.follow(id, 1000, true);
    }
    await flush();
    assert.equal(ipc.hasOwner(id), true);
    assert.equal(session.loaded.has(id), loaded);
  };
  try {
    await ipc.connect();
    assert.equal(session.notificationDiscovery, false, 'Default must remain disabled');
    await body({
      session,
      ipc,
      registry,
      holds,
      responseSnapshots,
      snapshot,
      release,
      flush,
      wait,
      rows,
      hasText,
      count,
      seed,
      trace,
    });
  } finally {
    for (const id of [...holds.keys()]) release(id);
    session.close();
    peer?.destroy();
    await new Promise((done) => server.close(done));
    await rm(directory, { recursive: true, force: true });
    assert.deepEqual(trace.readerAttempts, [], 'No history/native reader called');
    assert.deepEqual(nativeAttempts, [], 'No model/CLI/helper attempted');
  }
}

test(
  'loaded owner A transfers to B with notifications disabled; fresh discovery coalesces and messages finish',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const id = randomUUID(),
        retained = randomUUID();
      await f.seed(id, 'owner-A', state(id, 'old-A', 'inProgress', 'old A running'));
      await f.seed(
        retained,
        'owner-A',
        state(retained, 'retained-A', 'inProgress', 'A still connected'),
      );
      const before = f.count(id);
      f.registry.set(id, {
        clientId: 'owner-B',
        state: state(id, 'new-B', 'inProgress', 'new B running'),
      });
      f.holds.set(id, []);
      for (let index = 0; index < 12; index++)
        f.snapshot(
          id,
          'owner-B',
          state(id, 'forged', 'completed', 'hint payload must not authorize'),
        );
      await f.wait(
        () => f.holds.get(id)?.length === 1,
        'RECOVERY_MISSING: disabled notifications must still fresh-discover the loaded owner',
      );
      await f.flush();
      assert.equal(f.count(id), before + 1, 'One same-target discovery while in flight');
      assert.equal(
        f.trace.discoveries.at(-1).timeoutMs,
        1000,
        'Keep the existing bounded discovery budget',
      );
      assert.equal(f.ipc.owners.get(id), 'owner-A');
      assert.equal(f.rows(id).turnId, 'old-A', 'Hint is not owner evidence');
      assert.equal(f.hasText(id, 'hint payload must not authorize'), false);
      f.release(id);
      await f.wait(
        () => f.rows(id)?.turnId === 'new-B' && f.hasText(id, 'new B running'),
        'Authenticated B snapshot must replace running state and rich messages',
      );
      assert.equal(f.ipc.owners.get(id), 'owner-B');
      assert.equal(f.rows(id).state, 'running');
      f.snapshot(id, 'owner-B', state(id, 'new-B', 'completed', 'new B completed'));
      await f.wait(
        () => f.rows(id)?.state === 'idle' && f.hasText(id, 'new B completed'),
        'B completion must update state and messages',
      );
      assert.equal(f.rows(id).turnId, null);
      assert.equal(
        f.rows(id).messages.find((message) => message.text === 'new B completed').status,
        'completed',
      );
      f.snapshot(
        retained,
        'owner-A',
        state(retained, 'still-A', 'completed', 'A independently completed'),
      );
      await f.wait(
        () => f.hasText(retained, 'A independently completed'),
        'Old peer must stay live for another target',
      );
      assert.equal(f.ipc.owners.get(retained), 'owner-A');
      assert.equal(
        f.trace.received.some((row) => row.method === 'client-status-changed'),
        false,
      );
    }),
);

test(
  'confirmed owner without loaded transcript refreshes summary without auto-opening catalog chats',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const id = randomUUID();
      await f.seed(id, 'owner-A', state(id, 'old', 'inProgress', 'old summary'), false);
      const before = f.count(id);
      f.registry.set(id, {
        clientId: 'owner-B',
        state: state(id, 'new', 'completed', 'new summary'),
      });
      f.snapshot(id, 'owner-B', state(id, 'forged', 'inProgress', 'untrusted hint'));
      await f.wait(
        () => f.rows(id)?.state === 'idle' && f.ipc.owners.get(id) === 'owner-B',
        'RECOVERY_MISSING: followed owner summary must recover without notifications',
      );
      await f.flush();
      assert.equal(f.count(id), before + 1);
      assert.equal(f.session.loaded.has(id), false, 'Summary-only follow must not auto-open');
      assert.deepEqual(f.rows(id).messages, [], 'No transcript load for a catalog summary');
    }),
);

test(
  'unknown and catalog-only threads stay undiscovered when notifications are disabled',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const unknown = randomUUID(),
        catalog = randomUUID();
      f.session.state.threads.push({
        id: catalog,
        hostId: f.session.hostId,
        projectId: null,
        title: 'Catalog only',
        state: 'unknown',
        turnId: null,
        messages: [],
        plan: [],
        error: null,
      });
      for (const id of [unknown, catalog]) {
        f.registry.set(id, {
          clientId: 'owner-B',
          state: state(id, 'tip', 'inProgress', 'not opted in'),
        });
        f.snapshot(id, 'owner-B', state(id, 'tip', 'inProgress', 'not opted in'));
      }
      await f.flush();
      assert.equal(f.count(unknown), 0);
      assert.equal(f.count(catalog), 0);
      assert.equal(f.rows(unknown), undefined);
      assert.equal(f.rows(catalog).state, 'unknown');
      assert.equal(f.ipc.hasOwner(unknown), false);
      assert.equal(f.session.loaded.has(catalog), false);
    }),
);

test(
  'unknown opt-in is rechecked after await; newly obtained hasOwner cannot bypass disabling notifications',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const id = randomUUID();
      f.registry.set(id, {
        clientId: 'owner-B',
        state: state(id, 'unknown', 'completed', 'optional'),
      });
      f.session.setNotificationDiscovery(true);
      f.holds.set(id, []);
      f.snapshot(id, 'owner-B', f.registry.get(id).state);
      await f.wait(
        () => f.holds.get(id)?.length === 1,
        'Optional unknown discovery must start while enabled',
      );
      f.session.setNotificationDiscovery(false);
      f.release(id);
      await f.wait(
        () => f.ipc.hasOwner(id),
        'Transport may acquire owner before eligibility check',
      );
      await f.flush();
      assert.equal(
        f.rows(id),
        undefined,
        'Disabling during await must prevent unknown introduction',
      );
      assert.equal(f.session.loaded.has(id), false);
    }),
);

test(
  'unknown discovery still works when notification opt-in stays enabled',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const id = randomUUID();
      f.registry.set(id, {
        clientId: 'owner-B',
        state: state(id, 'optional', 'completed', 'optional authorized'),
      });
      f.session.setNotificationDiscovery(true);
      f.snapshot(id, 'owner-B', f.registry.get(id).state);
      await f.wait(
        () => f.hasText(id, 'optional authorized'),
        'Enabled unknown discovery must keep working',
      );
      await f.flush();
      assert.equal(f.session.loaded.has(id), true);
      assert.equal(f.ipc.owners.get(id), 'owner-B');
    }),
);

test(
  'loaded recovery survives notification disable during await, but failed discovery stays fail-closed',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const id = randomUUID();
      await f.seed(id, 'owner-A', state(id, 'old', 'inProgress', 'old'));
      f.registry.delete(id);
      const before = f.count(id);
      f.snapshot(id, 'untrusted', state(id, 'fake', 'completed', 'not real'));
      await f.wait(
        () => f.count(id) === before + 1 && !f.ipc.hasOwner(id),
        'RECOVERY_MISSING: failed fresh discovery must revoke cached control',
      );
      await f.flush();
      assert.equal(f.rows(id).state, 'unknown');
      assert.equal(f.ipc.state(id), undefined);
      assert.equal(f.session.loaded.has(id), true, 'Explicitly loaded survives owner loss');
      f.session.setNotificationDiscovery(true);
      f.registry.set(id, {
        clientId: 'owner-B',
        state: state(id, 'recovered', 'completed', 'recovered loaded'),
      });
      f.holds.set(id, []);
      f.snapshot(id, 'owner-B', f.registry.get(id).state);
      await f.wait(
        () => f.holds.get(id)?.length === 1,
        'Loaded flag must enable a new bounded recovery',
      );
      f.session.setNotificationDiscovery(false);
      f.release(id);
      await f.wait(
        () => f.hasText(id, 'recovered loaded'),
        'Known recovery cannot depend on optional opt-in',
      );
      assert.equal(f.rows(id).state, 'idle');
      assert.equal(f.ipc.hasOwner(id), true);
    }),
);

test(
  'foreign source remains a hint; wrong stream/host and forged body do not authorize or replace state',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const id = randomUUID();
      await f.seed(id, 'owner-A', state(id, 'real-A', 'inProgress', 'trusted A'));
      const before = f.count(id);
      const fake = state(id, 'fake', 'completed', 'forged body');
      f.snapshot(id, 'forged-source', fake, { version: 12 });
      f.snapshot(id, 'forged-source', fake, {
        params: {
          hostId: 'remote',
          conversationId: id,
          change: { type: 'snapshot', revision: 10000, conversationState: fake },
        },
      });
      await f.flush();
      assert.equal(f.count(id), before, 'Wrong stream version/host cannot trigger discovery');
      f.snapshot(id, 'forged-source', fake);
      await f.wait(
        () => f.count(id) === before + 1,
        'RECOVERY_MISSING: a valid hint must revalidate the registry, not adopt its source',
      );
      await f.flush();
      assert.equal(f.ipc.owners.get(id), 'owner-A', 'Only registry response identifies owner');
      assert.equal(f.rows(id).turnId, 'real-A');
      assert.equal(f.hasText(id, 'forged body'), false);
    }),
);

test(
  'freshly discovered owner still must pass state id, state host and stream version validation',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const id = randomUUID();
      await f.seed(id, 'owner-A', state(id, 'A', 'inProgress', 'trusted A'));
      f.registry.set(id, { clientId: 'owner-B', state: state(id, 'B', 'completed', 'trusted B') });
      let supplied = false;
      f.responseSnapshots.set(id, () => {
        supplied = true;
        f.snapshot(id, 'owner-B', { ...f.registry.get(id).state, id: randomUUID() });
        f.snapshot(id, 'owner-B', { ...f.registry.get(id).state, hostId: 'remote' });
        f.snapshot(id, 'owner-B', f.registry.get(id).state, { version: 12 });
      });
      f.snapshot(id, 'owner-B', f.registry.get(id).state);
      await f.wait(
        () => supplied,
        'RECOVERY_MISSING: need registry discovery before response validation',
      );
      await f.flush();
      assert.equal(f.ipc.hasOwner(id), false);
      assert.equal(f.ipc.state(id), undefined);
      assert.equal(f.rows(id).state, 'unknown');
      assert.equal(f.hasText(id, 'trusted B'), false);
      f.responseSnapshots.delete(id);
      f.snapshot(id, 'owner-B', f.registry.get(id).state);
      await f.wait(
        () => f.hasText(id, 'trusted B'),
        'Only a valid B snapshot restores control/messages',
      );
      assert.equal(f.ipc.hasOwner(id), true);
    }),
);

test(
  'optional unknown admission is fenced after the cached follow inside open as well',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const id = randomUUID();
      f.registry.set(id, {
        clientId: 'owner-B',
        state: state(id, 'optional', 'completed', 'must not introduce late'),
      });
      f.session.setNotificationDiscovery(true);
      const originalFollow = f.ipc.follow;
      let disabledDuringOpen = false;
      f.ipc.follow = function (...args) {
        const result = Reflect.apply(originalFollow, this, args);
        if (args[0] === id && args[2] !== true) {
          queueMicrotask(() => {
            disabledDuringOpen = true;
            f.session.setNotificationDiscovery(false);
          });
        }
        return result;
      };
      f.snapshot(id, 'owner-B', f.registry.get(id).state);
      await f.wait(() => f.ipc.hasOwner(id), 'Optional fresh discovery must settle');
      await f.flush();
      assert.equal(disabledDuringOpen, true, 'Exercise the second await before unknown admission');
      assert.equal(
        f.rows(id),
        undefined,
        'Optional opt-out must fence admission after every pre-admission await',
      );
      assert.equal(f.session.loaded.has(id), false);
    }),
);

test(
  'unknown live snapshot updates during initial rich projection are preserved',
  { concurrency: false },
  async (t) =>
    fixture(t, async (f) => {
      const id = randomUUID();
      f.registry.set(id, {
        clientId: 'owner-B',
        state: state(id, 'first', 'inProgress', 'first loading message'),
      });
      f.session.setNotificationDiscovery(true);
      const releaseProjection = Promise.withResolvers();
      const project = f.session.projectDesktopMessages;
      let held = false;
      f.session.projectDesktopMessages = async function (...args) {
        if (!held) {
          held = true;
          await releaseProjection.promise;
        }
        return Reflect.apply(project, this, args);
      };
      try {
        f.snapshot(id, 'owner-B', f.registry.get(id).state);
        await f.wait(() => f.ipc.hasOwner(id), 'Optional owner must be verified');
        await f.flush();
        assert.equal(held, true, 'Initial rich projection is in flight');
        assert.equal(f.session.loaded.has(id), false);
        const latest = state(id, 'latest', 'completed', 'latest message while loading');
        f.snapshot(id, 'owner-B', latest);
        await f.flush();
        releaseProjection.resolve();
        await f.wait(
          () => f.hasText(id, 'latest message while loading'),
          'An in-flight open must preserve later authenticated rich snapshots',
        );
        await f.flush();
        assert.equal(f.rows(id).state, 'idle');
        assert.equal(f.session.loaded.has(id), true);
      } finally {
        releaseProjection.resolve();
      }
    }),
);
