import assert from 'node:assert/strict';
import { realpathSync } from 'node:fs';
import { dirname } from 'node:path';
import { DesktopForkBroker } from '../packages/codex-adapter/dist/desktop-forks.js';
import { DesktopRuntime } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { CodexHistoryReader } from '../packages/codex-adapter/dist/history-reader.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';

// Memory/transport stubs exercise the built implementation. No constructors
// that persist state, subprocesses, desktop IPC, model calls or filesystem writes.
// Build in the main task before running: pnpm exec node scripts/thread-fork-recovery-smoke.mjs.
const cwd = realpathSync(process.cwd());
const sourceId = '11111111-1111-4111-8111-111111111111';
const childId = '22222222-2222-4222-8222-222222222222';
const turnId = 'selected-completed-turn';
const operation = () => ({
  hostId: 'fixture-host',
  threadId: sourceId,
  turnId,
  clientOperationId: 'recovery-operation-001',
  environment: 'same-directory',
  phase: 'outcome-unknown',
  forkedThreadId: childId,
  projectId: 'fixture-project',
  error: 'Awaiting owner',
});
const deferred = () => {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
const desktopRuntime = (reader, ipc) =>
  Object.assign(Object.create(DesktopRuntime.prototype), { reader, ipc });
const session = (runtime, fields) =>
  Object.assign(Object.create(RuntimeSession.prototype), { runtime, ...fields });

function broker(record, verify) {
  const saved = [];
  const instance = Object.assign(Object.create(DesktopForkBroker.prototype), {
    work: new Map(),
    confirmations: new Map(),
    read: (id) => {
      assert.equal(id, record.clientOperationId);
      return record;
    },
    verify,
    save: (value) => saved.push(structuredClone(value)),
    open: () => assert.fail('Receipt queries must not open a desktop chat'),
    preparer: { fork: () => assert.fail('Receipt queries must not replay a fork') },
  });
  return { instance, saved, query: () => instance.query(sourceId, record.clientOperationId) };
}
let passed = 0;
const test = async (name, run) => {
  await run();
  passed++;
  console.log(JSON.stringify({ test: name, status: 'passed', scope: 'memory-stub-boundary' }));
};

await test('paginated fork source reaches the actual reader RPC whitelist; writing methods remain blocked', async () => {
  const calls = [];
  const reader = new CodexHistoryReader('fixture-host', 'Recovery fixture');
  // Stub only stdin and the response delivery. read() and rpc() remain real,
  // including the whitelist, pending map, request IDs and timeout handling.
  reader.initialized = true;
  reader.process = {
    exitCode: null,
    killed: false,
    stdin: {
      write: (line, callback) => {
        const request = JSON.parse(line);
        calls.push(request);
        queueMicrotask(() => {
          const pending = reader.pending.get(request.id);
          assert.ok(pending);
          clearTimeout(pending.timer);
          reader.pending.delete(request.id);
          try {
            let result;
            if (request.method === 'thread/read') {
              assert.deepEqual(request.params, { threadId: sourceId, includeTurns: false });
              result = { thread: { id: sourceId, cwd, historyMode: 'paginated' } };
            } else {
              assert.equal(request.method, 'thread/turns/list');
              assert.equal(request.params.threadId, sourceId);
              assert.equal(request.params.sortDirection, 'desc');
              assert.equal(request.params.itemsView, 'notLoaded');
              assert.equal(request.params.limit, 100);
              result =
                request.params.cursor === undefined
                  ? { data: [{ id: 'newer-turn', status: 'completed' }], nextCursor: 'older-page' }
                  : { data: [{ id: turnId, status: 'completed' }], nextCursor: null };
              if (request.params.cursor !== undefined)
                assert.equal(request.params.cursor, 'older-page');
            }
            pending.resolve(result);
          } catch (error) {
            pending.reject(error);
          }
          callback?.();
        });
        return true;
      },
    },
  };
  const runtime = desktopRuntime(reader, { ready: true });
  const current = session(runtime, {
    thread: (id) => {
      assert.equal(id, sourceId);
      return { projectId: 'fixture-project', title: 'Source' };
    },
  });
  assert.deepEqual(await current.forkSource(sourceId, turnId), {
    cwd,
    projectId: 'fixture-project',
    title: 'Source',
  });
  assert.deepEqual(
    calls.map((row) => row.method),
    ['thread/read', 'thread/turns/list', 'thread/turns/list'],
  );
  assert.equal(new Set(calls.map((row) => row.id)).size, 3);
  const sent = calls.length;
  for (const method of [
    'thread/start',
    'thread/fork',
    'thread/resume',
    'thread/name/set',
    'turn/start',
    'turn/interrupt',
    'project/create',
  ])
    await assert.rejects(reader.read(method, { threadId: sourceId }), /Read-only method required/);
  assert.equal(calls.length, sent, 'rejected writes must never reach transport');
  assert.equal(reader.pending.size, 0);
});

await test('late owner with legitimate continuation confirms the original fork; target checks still reject mismatches', async () => {
  let owner = {
    id: childId,
    hostId: 'local',
    cwd,
    turns: [{ turnId, status: 'completed', items: [] }],
  };
  let hasOwner = true;
  const opened = [];
  const events = [];
  const fork = {};
  const runtime = desktopRuntime(
    { ready: true },
    {
      ready: true,
      follow: async (id, timeout, revalidate) => {
        assert.equal(id, childId);
        assert.equal(timeout, 1000);
        assert.equal(revalidate, true);
        return owner;
      },
      hasOwner: (id) => id === childId && hasOwner,
    },
  );
  const current = session(runtime, {
    open: async (id) => opened.push(id),
    thread: (id) => {
      assert.equal(id, childId);
      return fork;
    },
    save: () => {},
    emit: (id, event) => events.push([id, event]),
  });
  const record = operation();
  assert.equal(await current.verifyDesktopFork(record, cwd), true);
  owner = {
    ...owner,
    turns: [...owner.turns, { turnId: 'legitimate-next-turn', status: 'completed', items: [] }],
  };
  const recovering = broker(
    { ...record, checkpoint: 'opening', preparationExitCode: 0, directory: cwd },
    (value, directory) => current.verifyDesktopFork(value, directory),
  );
  assert.equal((await recovering.query()).phase, 'confirmed');
  assert.deepEqual(opened, [childId, childId]);
  assert.deepEqual(events, [
    [childId, 'desktop-forked'],
    [childId, 'desktop-forked'],
  ]);
  assert.equal(fork.projectId, record.projectId);
  const correct = owner;
  for (const invalid of [
    { ...correct, id: sourceId },
    { ...correct, hostId: 'foreign-host' },
    { ...correct, cwd: dirname(cwd) },
    { ...correct, turns: [{ turnId: 'unrelated-turn', items: [] }] },
  ]) {
    owner = invalid;
    assert.equal(await current.verifyDesktopFork(record, cwd), false);
  }
  owner = correct;
  hasOwner = false;
  assert.equal(await current.verifyDesktopFork(record, cwd), false);
  assert.equal(opened.length, 2, 'mismatched targets must not be opened or adopted');
});

await test('materialized + exit 0 crash checkpoint can recover without opening or replay; incomplete preparation cannot', async () => {
  const record = {
    ...operation(),
    checkpoint: 'materialized',
    preparationExitCode: 0,
    directory: cwd,
  };
  let checks = 0;
  const recovered = broker(record, async (value, directory) => {
    checks++;
    assert.equal(value.forkedThreadId, childId);
    assert.equal(directory, cwd);
    return true;
  });
  assert.equal((await recovered.query()).phase, 'confirmed');
  assert.equal(checks, 1);
  assert.equal(recovered.saved.length, 1);
  assert.equal(recovered.saved[0].phase, 'confirmed');
  assert.equal((await recovered.query()).phase, 'confirmed');
  assert.equal(checks, 1, 'settled receipt queries must not revalidate or execute');
  for (const patch of [
    { preparationExitCode: null },
    { preparationExitCode: 1 },
    { checkpoint: 'identified' },
    { checkpoint: 'fork-written' },
    { forkedThreadId: null },
  ]) {
    const pending = broker(
      {
        ...operation(),
        checkpoint: 'materialized',
        preparationExitCode: 0,
        directory: cwd,
        ...patch,
      },
      async () => assert.fail('Unreleased/incomplete preparation must not confirm an owner'),
    );
    assert.equal((await pending.query()).phase, 'outcome-unknown');
    assert.equal(pending.saved.length, 0);
  }
});

await test('concurrent receipt queries share one verification and stale failure cannot downgrade a terminal result', async () => {
  const gates = [];
  const record = { ...operation(), checkpoint: 'opening', preparationExitCode: 0, directory: cwd };
  const shared = broker(record, () => {
    const gate = deferred();
    gates.push(gate);
    return gate.promise;
  });
  const queries = [shared.query(), shared.query()];
  try {
    assert.equal(gates.length, 1, 'overlapping queries must share the same verification');
    gates[0].resolve(true);
    const results = await Promise.all(queries);
    assert.ok(results.every((value) => value.phase === 'confirmed'));
    assert.equal(shared.saved.length, 1);
    assert.equal(shared.instance.confirmations.size, 0);
    assert.equal((await shared.query()).phase, 'confirmed');
    assert.equal(gates.length, 1);
  } finally {
    for (const gate of gates) gate.resolve(false);
    await Promise.allSettled(queries);
  }
  // Cover the monotonic guard independently of query coalescing: another
  // completion can settle the receipt while an older owner check is pending.
  for (const phase of ['confirmed', 'rejected']) {
    const gate = deferred();
    const value = { ...operation(), checkpoint: 'opening', preparationExitCode: 0, directory: cwd };
    const stale = broker(value, () => gate.promise);
    const pending = stale.instance.confirm(value);
    value.phase = phase;
    gate.resolve(false);
    await pending;
    assert.equal(value.phase, phase);
    assert.equal(stale.saved.length, 0, 'stale checks must not rewrite terminal receipts');
  }
});

console.log(
  JSON.stringify({
    kind: 'thread-fork-recovery-smoke',
    status: 'passed',
    passed,
    scope: 'memory-stub-boundary',
    node: process.version,
  }),
);
