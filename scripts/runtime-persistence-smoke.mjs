import assert from 'node:assert/strict';
import { chmod, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { fsyncSync, renameSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { RuntimePersistence } from '../packages/codex-adapter/dist/runtime-persistence.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { privateStateProtocol } from '../packages/codex-adapter/dist/desktop-private-state-native.js';

// Background correctness waits include the existing native helper cold-start
// budget. They are not HTTP latency assertions and do not change production limits.
const backgroundSettleMs =
  process.platform === 'win32'
    ? privateStateProtocol.startupMs + privateStateProtocol.operationMs + 5000
    : 5000;

const directory = await mkdtemp(join(tmpdir(), 'codex-runtime-persistence-'));
const file = join(directory, 'runtime.json');
let store;
try {
  const legacy = {
    hostId: 'host-a',
    threads: [{ id: 'thread-a', messages: [{ id: 'message-a', text: 'preserved history' }] }],
    receipts: [{ id: 'stable-operation', state: 'uncertain' }],
    attachments: [{ id: 'attachment-a', threadId: 'thread-a' }],
  };
  await writeFile(file, JSON.stringify(legacy), { mode: 0o600 });
  store = new RuntimePersistence(file, directory);
  const migrated = store.load();
  assert.deepEqual(migrated, legacy);
  store.save(migrated);
  const core = JSON.parse(await readFile(file, 'utf8'));
  assert.deepEqual(core.receipts, legacy.receipts);
  assert.deepEqual(core.attachments, legacy.attachments);
  assert.deepEqual(core.threads[0].messages, []);
  assert.equal(core.historyCacheVersion, 1);
  assert.deepEqual(new RuntimePersistence(file, directory).load().threads, legacy.threads);

  // A 100 MiB cold transcript must not be serialized during a receipt commit.
  let historySerializations = 0;
  const largeMessage = {
    id: 'large-message',
    text: 'x'.repeat(100 * 1024 * 1024),
    toJSON() {
      historySerializations++;
      return { id: this.id, text: this.text };
    },
  };
  migrated.threads.push({ id: '../untrusted-id', messages: [largeMessage] });
  for (let i = 0; i < 5; i++) {
    migrated.receipts[0].state = 'submitted';
    store.save(migrated);
  }
  assert.equal(historySerializations, 0);
  assert((await readFile(file)).length < 1024);
  // Latest updates coalesce; graceful shutdown flushes history before return.
  largeMessage.text = 'latest history';
  store.dirty(migrated.threads[1]);
  store.close();
  assert.equal(historySerializations, 1);
  const restored = new RuntimePersistence(file, directory).load();
  assert.equal(restored.threads[1].messages[0].text, 'latest history');
  assert.equal(restored.receipts[0].state, 'submitted');
  const cacheDirectory = join(directory, 'runtime-history-v1');
  const files = await readdir(cacheDirectory);
  assert(files.every((name) => /^[a-f0-9]{64}\.json$/.test(name)));

  // Foreign/corrupt replaceable caches cannot change the durable identity or receipts.
  for (const name of files)
    await writeFile(
      join(cacheDirectory, name),
      JSON.stringify({
        version: 1,
        hostId: 'other-host',
        threadId: '../untrusted-id',
        messages: [{ text: 'foreign' }],
      }),
    );
  const isolated = new RuntimePersistence(file, directory).load();
  assert.equal(isolated.hostId, legacy.hostId);
  assert(isolated.threads.every((thread) => thread.messages.length === 0));
  assert.equal(isolated.receipts[0].state, 'submitted');
  await writeFile(join(cacheDirectory, files[0]), '{broken');
  assert.equal(new RuntimePersistence(file, directory).load().hostId, legacy.hostId);
  console.log(
    'Runtime persistence passed: legacy migration, immediate durable receipts, 100 MiB cold-history isolation, latest cache coalescing, shutdown flush and host isolation.',
  );
} finally {
  store?.close();
  await rm(directory, { recursive: true, force: true });
}

// A read-only save can coalesce only an already durable, byte-identical head.
// Real temporary files/renames/fsyncs exercise the command durability boundary.
const coalescingDirectory = await mkdtemp(join(tmpdir(), 'codex-persistence-coalescing-'));
const coalescingFile = join(coalescingDirectory, 'runtime.json');
let coalescing;
try {
  let publishes = 0;
  let barriers = 0;
  let failBarrier = false;
  const reports = [];
  coalescing = new RuntimePersistence(coalescingFile, coalescingDirectory, {
    retryDelays: [5],
    report: (...args) => reports.push(args),
    rename: (from, to) => {
      renameSync(from, to);
      if (to === coalescingFile) publishes++;
    },
    fsync: (fd) => {
      barriers++;
      if (failBarrier) throw new Error('injected barrier failure');
      fsyncSync(fd);
    },
  });
  assert.equal(coalescing.load(), undefined);
  coalescing.acquireReplyWriter({
    agentId: 'codex',
    hostId: 'host-a',
    workspaceId: 'host-a',
    runtimeId: 'desktop-runtime',
  });
  const state = { hostId: 'host-a', threads: [], receipts: [{ id: 'op-a', state: 'uncertain' }] };
  coalescing.save(state);
  const first = { publishes, barriers };
  coalescing.schedule(() => state);
  const settleDeadline = performance.now() + backgroundSettleMs;
  while (coalescing.status.backgroundSave !== 'idle' && performance.now() < settleDeadline)
    await delay(5);
  assert.equal(coalescing.status.backgroundSave, 'idle');
  assert.deepEqual({ publishes, barriers }, first, 'unchanged reads must not rewrite the head');
  state.receipts[0].state = 'completed';
  coalescing.saveRead(state);
  assert.equal(publishes, first.publishes + 1, 'changed receipts must publish');
  assert(barriers > first.barriers, 'changed receipts must flush');
  const beforeCommand = publishes;
  coalescing.save(state);
  assert.equal(publishes, beforeCommand + 1, 'explicit commands always publish and flush');
  failBarrier = true;
  assert.throws(() => coalescing.save(state), /could not be persisted/);
  failBarrier = false;
  const beforeRecovery = publishes;
  coalescing.saveRead(state);
  assert.equal(
    publishes,
    beforeRecovery + 1,
    'a failed identical command invalidates no-op durability',
  );
  if (process.platform !== 'win32') {
    await chmod(coalescingFile, 0o666);
    assert.throws(() => coalescing.saveRead(state), /could not be persisted/);
    await chmod(coalescingFile, 0o600);
  }
  const original = await readFile(coalescingFile);
  await writeFile(coalescingFile, Buffer.concat([original, Buffer.from(' ')]));
  assert.throws(
    () => coalescing.saveRead(state),
    (e) => e.stage === 'writer-continuity',
  );
  await writeFile(coalescingFile, original);
  const leaseFile = join(coalescingDirectory, 'reply-writer-v2.json');
  const lease = await readFile(leaseFile);
  await writeFile(leaseFile, JSON.stringify({ ...JSON.parse(lease), nonce: 'foreign' }));
  assert.throws(
    () => coalescing.saveRead(state),
    (e) => e.stage === 'writer-continuity',
  );
  await writeFile(leaseFile, lease);
  assert.deepEqual(JSON.parse(await readFile(coalescingFile)).receipts, state.receipts);
  assert.equal(reports.length, 0);
  console.log(
    'Persistence coalescing passed: real no-op reads, changed receipts, mandatory command barriers, failed barrier recovery, file privacy and writer/head continuity.',
  );
} finally {
  coalescing?.close();
  await rm(coalescingDirectory, { recursive: true, force: true });
}

// Exercise actual desktop projection without connecting or submitting model input.
const projectionDirectory = await mkdtemp(join(tmpdir(), 'codex-projection-coalescing-'));
let session;
try {
  session = new RuntimeSession({
    directory: projectionDirectory,
    root: projectionDirectory,
    name: 'fixture',
    mode: 'desktop-runtime',
  });
  session.runtime.ipc.hasOwner = () => true;
  const thread = {
    id: 'projection-thread',
    projectId: null,
    title: 'fixture',
    messages: [],
    state: 'idle',
    turnId: null,
    approvals: [],
  };
  session.state.threads.push(thread);
  let dirty = 0;
  const realDirty = session.persistence.dirty.bind(session.persistence);
  session.persistence.dirty = (value) => {
    dirty++;
    realDirty(value);
  };
  const snapshot = {
    id: thread.id,
    hostId: 'local',
    turns: [
      {
        turnId: 'turn-a',
        status: 'completed',
        items: [{ id: 'item-a', type: 'agentMessage', text: 'same' }],
      },
    ],
  };
  await session.desktopSnapshot(thread, snapshot);
  assert.equal(dirty, 1);
  const messages = thread.messages;
  const eventCount = session.events.length;
  await session.desktopSnapshot(thread, structuredClone(snapshot));
  assert.equal(dirty, 1, 'equivalent authoritative snapshots must not dirty history');
  assert.equal(thread.messages, messages, 'unchanged history retains its cache reference');
  assert.equal(
    session.events.length,
    eventCount + 1,
    'fresh owner snapshots still signal listeners',
  );
  snapshot.turns[0].items[0].text = 'diff';
  await session.desktopSnapshot(thread, snapshot);
  assert.equal(dirty, 2, 'same-length changed body must invalidate history');
  assert.equal(thread.messages[0].text, 'diff');
  snapshot.turns[0].status = 'interrupted';
  await session.desktopSnapshot(thread, snapshot);
  assert.equal(dirty, 3, 'changed status must invalidate history');
  assert.equal(thread.messages[0].status, 'interrupted');
  Object.defineProperty(session.runtime, 'ready', { value: true });
  session.runtime.ipc.follow = async () => structuredClone(snapshot);
  const originalQueue = session.nativeQueue;
  session.nativeQueue = undefined;
  let headPublishes = 0;
  session.persistence.options.rename = (from, to) => {
    renameSync(from, to);
    if (to === join(projectionDirectory, 'runtime.json')) headPublishes++;
  };
  await session.open(thread.id, undefined, undefined, true);
  const beforeRead = headPublishes;
  await session.open(thread.id, undefined, undefined, true);
  await session.open(thread.id, undefined, undefined, true);
  assert.equal(
    headPublishes,
    beforeRead,
    'actual desktop open must reuse an unchanged durable head',
  );
  snapshot.title = 'changed title';
  await session.open(thread.id, undefined, undefined, true);
  assert.equal(headPublishes, beforeRead + 1, 'changed read metadata must synchronously publish');
  assert.equal(
    JSON.parse(await readFile(join(projectionDirectory, 'runtime.json'))).threads[0].title,
    snapshot.title,
  );
  session.needsCommit = true;
  await session.open(thread.id, undefined, undefined, true);
  assert.equal(
    headPublishes,
    beforeRead + 2,
    'pending command durability cannot be skipped by a read',
  );
  assert.equal(session.needsCommit, false);
  session.nativeQueue = originalQueue;
  console.log(
    'Desktop projection coalescing passed: exact equivalent snapshots avoid cache rewrites; same-length body and status changes persist, with owner events preserved and no model input.',
  );
} finally {
  session?.close();
  await rm(projectionDirectory, { recursive: true, force: true });
}

// A readonly deadline must not compete with scheduled cache writes on Main.
// Explicit command/read barriers and the real writer/head guards remain active.
const deferredDirectory = await mkdtemp(join(tmpdir(), 'codex-deferred-cache-'));
let deferredStore;
try {
  const deferredFile = join(deferredDirectory, 'runtime.json');
  const publications = [];
  const failures = [];
  let failNext = false;
  deferredStore = new RuntimePersistence(deferredFile, deferredDirectory, {
    retryDelays: [5, 60],
    report: (...args) => failures.push(args),
    rename: (from, to) => {
      if (failNext && to === deferredFile) {
        failNext = false;
        throw new Error('owned deferred-cache publication failure');
      }
      renameSync(from, to);
      publications.push(to);
    },
  });
  deferredStore.load();
  deferredStore.acquireReplyWriter({
    agentId: 'codex',
    hostId: 'deferred-host',
    workspaceId: 'deferred-host',
    runtimeId: 'desktop-runtime',
  });
  const state = {
    hostId: 'deferred-host',
    threads: [{ id: 'source', messages: [{ id: 'one', text: 'before' }] }],
    receipts: [{ id: 'original', state: 'uncertain' }],
  };
  deferredStore.save(state);
  const waitFor = async (check) => {
    const end = performance.now() + backgroundSettleMs;
    while (!check()) {
      assert(performance.now() < end, 'Deferred cache did not resume');
      await delay(5);
    }
  };
  const first = deferredStore.deferBackground();
  const second = deferredStore.deferBackground();
  const count = publications.length;
  state.receipts[0].state = 'completed';
  state.threads[0].messages[0].text = 'latest held history';
  deferredStore.dirty(state.threads[0]);
  deferredStore.schedule(() => state);
  await delay(1100); // Both the old state timer and the real history timer are overdue.
  assert.equal(publications.length, count);
  assert.equal(deferredStore.status.backgroundSave, 'pending');
  assert.equal(deferredStore.status.historyPending, 1);
  first();
  first();
  await delay(20);
  assert.equal(publications.length, count, 'One nested release cannot drain another hold');
  second();
  await waitFor(
    () =>
      deferredStore.status.backgroundSave === 'idle' && deferredStore.status.historyPending === 0,
  );
  const resumed = new RuntimePersistence(deferredFile, deferredDirectory).load();
  assert.equal(resumed.receipts[0].state, 'completed');
  assert.equal(resumed.threads[0].messages[0].text, 'latest held history');

  const releaseCommand = deferredStore.deferBackground();
  state.receipts.push({ id: 'new-command', state: 'received' });
  const beforeCommand = publications.length;
  deferredStore.save(state);
  assert.equal(
    publications.length,
    beforeCommand + 1,
    'An explicit command still publishes immediately',
  );
  assert.equal(JSON.parse(await readFile(deferredFile)).receipts.at(-1).id, 'new-command');
  state.receipts.at(-1).state = 'submitted';
  deferredStore.saveRead(state);
  assert.equal(JSON.parse(await readFile(deferredFile)).receipts.at(-1).state, 'submitted');
  const leaseFile = join(deferredDirectory, 'reply-writer-v2.json');
  const lease = await readFile(leaseFile);
  await writeFile(
    leaseFile,
    JSON.stringify({ ...JSON.parse(lease), nonce: 'owned changed nonce' }),
    { mode: 0o600 },
  );
  assert.throws(
    () => deferredStore.save(state),
    (error) => error.stage === 'writer-continuity',
  );
  assert.throws(
    () => deferredStore.saveRead(state),
    (error) => error.stage === 'writer-continuity',
  );
  await writeFile(leaseFile, lease, { mode: 0o600 });
  const head = await readFile(deferredFile);
  await writeFile(deferredFile, Buffer.concat([head, Buffer.from('\n')]), { mode: 0o600 });
  assert.throws(
    () => deferredStore.saveRead(state),
    (error) => error.stage === 'writer-continuity',
  );
  await writeFile(deferredFile, head, { mode: 0o600 });
  releaseCommand();

  const receiver = Object.create(RuntimeSession.prototype);
  receiver.persistence = deferredStore;
  const cancelled = new AbortController();
  const releaseAbort = receiver.deferBackgroundPersistence(cancelled.signal);
  state.receipts.push({ id: 'after-abort', state: 'completed' });
  deferredStore.schedule(() => state);
  const beforeAbort = publications.length;
  await delay(20);
  assert.equal(publications.length, beforeAbort);
  cancelled.abort();
  await waitFor(() => deferredStore.status.backgroundSave === 'idle');
  assert.equal(JSON.parse(await readFile(deferredFile)).receipts.at(-1).id, 'after-abort');
  releaseAbort();

  failNext = true;
  state.receipts.push({ id: 'retry-after-hold', state: 'completed' });
  deferredStore.schedule(() => state);
  await waitFor(() => failures.length === 1);
  const releaseRetry = deferredStore.deferBackground();
  await delay(100);
  assert.equal(failures.length, 1, 'A deferred retry cannot spin or lose its pending state');
  releaseRetry();
  await waitFor(() => deferredStore.status.backgroundSave === 'idle');
  assert.equal(JSON.parse(await readFile(deferredFile)).receipts.at(-1).id, 'retry-after-hold');

  const releaseClose = deferredStore.deferBackground();
  state.threads[0].messages[0].text = 'latest before close';
  deferredStore.dirty(state.threads[0]);
  deferredStore.save(state);
  deferredStore.close();
  releaseClose();
  releaseClose();
  assert.equal(
    new RuntimePersistence(deferredFile, deferredDirectory).load().threads[0].messages[0].text,
    'latest before close',
  );
  console.log(
    'Readonly cache scheduling passed: overdue state/history deferral, nested release, latest bytes, explicit barriers, real writer/head fences, abort, retry and close.',
  );
} finally {
  deferredStore?.close();
  await rm(deferredDirectory, { recursive: true, force: true });
}

// Real asynchronous temp-file I/O, with only the native ACL response held by
// this fixture. A Windows cache completion must never overwrite a newer command.
const asyncDirectory = await mkdtemp(join(tmpdir(), 'codex-windows-background-'));
const gates = [];
let asyncStore;
try {
  const asyncFile = join(asyncDirectory, 'runtime.json');
  const reports = [];
  let holding = true;
  let failNext = false;
  asyncStore = new RuntimePersistence(asyncFile, asyncDirectory, {
    platform: 'win32',
    retryDelays: [5, 80],
    cooldownMs: 120,
    report: (...args) => reports.push(args),
    protectAsync: async () => {
      if (holding) await new Promise((resolve, reject) => gates.push({ resolve, reject }));
      if (failNext) {
        failNext = false;
        throw new Error('owned async ACL failure');
      }
    },
  });
  const until = async (check) => {
    const end = performance.now() + backgroundSettleMs;
    while (!check()) {
      assert(performance.now() < end, 'Async persistence did not settle');
      await delay(5);
    }
  };
  const state = {
    hostId: 'async-host',
    threads: [{ id: 'source', messages: [] }],
    receipts: [{ id: 'original', state: 'uncertain' }],
  };
  asyncStore.load();
  asyncStore.acquireReplyWriter({
    agentId: 'codex',
    hostId: 'async-host',
    workspaceId: 'async-host',
    runtimeId: 'desktop-runtime',
  });
  asyncStore.save(state);
  state.receipts[0].state = 'background-old';
  asyncStore.schedule(() => state);
  await until(() => gates.length === 1);
  state.receipts[0].state = 'command-new';
  asyncStore.save(state);
  state.receipts[0].state = 'read-new';
  asyncStore.saveRead(state);
  assert.equal(
    JSON.parse(await readFile(asyncFile)).receipts[0].state,
    'read-new',
    'Command publication remains synchronous during async I/O',
  );
  holding = false;
  gates.shift().resolve();
  await asyncStore.settleBackground();
  assert.equal(
    JSON.parse(await readFile(asyncFile)).receipts[0].state,
    'read-new',
    'Late background completion cannot overwrite the command',
  );

  holding = true;
  state.receipts[0].state = 'coalesced-old';
  asyncStore.schedule(() => state);
  await until(() => gates.length === 1);
  for (let i = 0; i < 20; i++) {
    state.receipts[0].state = 'latest-' + i;
    asyncStore.schedule(() => state);
  }
  const release = asyncStore.deferBackground();
  holding = false;
  gates.shift().resolve();
  await delay(30);
  assert.equal(
    JSON.parse(await readFile(asyncFile)).receipts[0].state,
    'read-new',
    'An in-flight write also respects the readonly publication hold',
  );
  release();
  await until(() => asyncStore.status.backgroundSave === 'idle');
  assert.equal(JSON.parse(await readFile(asyncFile)).receipts[0].state, 'latest-19');

  // Adversarial microtask boundary: a valid permission/hold result has resolved,
  // then an explicit durable read save wins before the async publisher resumes.
  const allowed = asyncStore.publicationAllowed.bind(asyncStore);
  let interleave = true;
  asyncStore.publicationAllowed = async (current) => {
    const result = await allowed(current);
    if (interleave) {
      interleave = false;
      state.receipts[0].state = 'microtask-command';
      asyncStore.saveRead(state);
    }
    return result;
  };
  state.receipts[0].state = 'obsolete-before-microtask';
  asyncStore.schedule(() => state);
  await until(() => asyncStore.status.backgroundSave === 'idle');
  await asyncStore.settleBackground();
  assert.equal(JSON.parse(await readFile(asyncFile)).receipts[0].state, 'microtask-command');
  asyncStore.publicationAllowed = allowed;

  for (const conflict of ['complete-head', 'writer-nonce']) {
    holding = true;
    state.receipts[0].state = conflict;
    asyncStore.schedule(() => state);
    await until(() => gates.length === 1);
    const conflictFile =
      conflict === 'complete-head' ? asyncFile : join(asyncDirectory, 'reply-writer-v2.json');
    const original = await readFile(conflictFile);
    const tampered =
      conflict === 'complete-head'
        ? Buffer.concat([original, Buffer.from(' ')])
        : Buffer.from(JSON.stringify({ ...JSON.parse(original), nonce: 'foreign' }));
    await writeFile(conflictFile, tampered);
    const prior = reports.length;
    holding = false;
    gates.shift().resolve();
    await until(() => reports.length > prior);
    const pause = asyncStore.deferBackground();
    assert.equal(reports.at(-1)[1].cause.stage, 'writer-continuity');
    assert.deepEqual(
      await readFile(conflictFile),
      tampered,
      'Async publication must not replace a foreign writer/head',
    );
    await writeFile(conflictFile, original);
    pause();
    await until(() => asyncStore.status.backgroundSave === 'idle');
  }
  failNext = true;
  state.receipts[0].state = 'retry-latest';
  asyncStore.schedule(() => state);
  const failures = reports.length;
  await until(() => reports.length > failures);
  const pauseRetry = asyncStore.deferBackground();
  await delay(100);
  assert.equal(reports.length, failures + 1, 'A paused async failure cannot spin');
  pauseRetry();
  await until(() => asyncStore.status.backgroundSave === 'idle');
  assert.equal(JSON.parse(await readFile(asyncFile)).receipts[0].state, 'retry-latest');

  holding = true;
  state.threads[0].messages = [{ text: 'obsolete failed history' }];
  asyncStore.dirty(state.threads[0]);
  await until(() => gates.length === 1);
  state.threads[0].messages = [{ text: 'latest successful history' }];
  asyncStore.dirty(state.threads[0]);
  holding = false;
  gates.shift().reject(new Error('owned obsolete ACL failure'));
  await until(() => asyncStore.status.historyPending === 0);
  assert.equal(
    new RuntimePersistence(asyncFile, asyncDirectory).load().threads[0].messages[0].text,
    'latest successful history',
    'Failure of an obsolete snapshot must preserve the newer pending object',
  );

  // The first history snapshot is frozen before awaiting ACLs. Closing writes
  // the latest empty history synchronously; late work cannot resurrect old text.
  holding = true;
  state.threads[0].messages = [{ text: 'old in-flight history' }];
  asyncStore.dirty(state.threads[0]);
  await until(() => gates.length === 1);
  const releaseClose = asyncStore.deferBackground();
  holding = false;
  gates.shift().resolve();
  await delay(30);
  state.threads[0].messages = [];
  asyncStore.dirty(state.threads[0]);
  asyncStore.close();
  await asyncStore.settleBackground();
  releaseClose();
  assert.deepEqual(
    new RuntimePersistence(asyncFile, asyncDirectory).load().threads[0].messages,
    [],
  );
  for (const directory of [asyncDirectory, join(asyncDirectory, 'runtime-history-v1')])
    assert(
      (await readdir(directory)).every((name) => !name.endsWith('.tmp')),
      'All owned obsolete temporary files must be removed',
    );
  console.log(
    'Windows async persistence passed: late command protection, latest coalescing, in-flight holds, actual writer/head fences, backoff, empty-history close and owned temp cleanup.',
  );
} finally {
  asyncStore?.close();
  for (const gate of gates) gate.resolve();
  await asyncStore?.settleBackground();
  await rm(asyncDirectory, { recursive: true, force: true });
}
