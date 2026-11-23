import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { fsyncSync, renameSync, readFileSync, statSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const [modulePath, json] = process.argv.slice(2);
const opts = JSON.parse(json);
const { RuntimePersistence } = await import(pathToFileURL(modulePath).href);
// Both variants use the current compiled CPU-stage module, including a baseline
// transpiled outside dist with absolute imports to those same dependencies.
const stagesPath = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../packages/codex-adapter/dist/cpu-stages.js',
);
const { startCpuStages } = await import(pathToFileURL(stagesPath).href);
const directory = await mkdtemp(join(tmpdir(), 'runtime-persistence-benchmark-'));
const file = join(directory, 'runtime.json');
let store;
const counts = {
  statePublishes: 0,
  historyPublishes: 0,
  otherPublishes: 0,
  barriers: 0,
  schedules: 0,
  dirty: 0,
  backgroundCallbacks: 0,
  reports: [],
};
const snapshot = () => JSON.parse(JSON.stringify(counts));
const difference = (a, b) =>
  Object.fromEntries(
    Object.keys(a)
      .filter((k) => k !== 'reports')
      .map((k) => [k, a[k] - b[k]]),
  );
const hash = (value) => createHash('sha256').update(JSON.stringify(value)).digest('hex');
// Distinct records and multi-message histories, all deterministic and synthetic.
const threads = Array.from({ length: 605 }, (_, i) => ({
  id: `synthetic-thread-${i}`,
  codexId: `synthetic-codex-${i}`,
  hostId: 'synthetic-host',
  projectId: 'synthetic-project',
  projectIds: ['synthetic-project'],
  title: `Synthetic conversation ${i}`,
  state: 'idle',
  hasUnreadTurn: false,
  turnId: null,
  messages: [],
  plan: [],
  error: null,
  historyCursor: null,
  desktopDirectory: '/synthetic/workspace',
  historyDirectory: '/synthetic/history',
}));
for (let t = 0; t < 3; t++) {
  threads[t].messages = Array.from({ length: 128 }, (_, i) => ({
    id: `synthetic-message-${t}-${i}`,
    role: i % 2 ? 'assistant' : 'user',
    text: `synthetic ${t}/${i} ` + String.fromCharCode(97 + (i % 26)).repeat(90_000),
  }));
}
const component = (n) => `synthetic-segment-${n}-abcdefghijklmnopqrstuvwxyz0123456789`;
const syntheticPath = (i) =>
  `/synthetic/${component(i % 17)}/${component(i % 101)}/${component(i)}/file-${i}.dat`;
const reference = (i, j) =>
  `synthetic-reference://${component(i % 29)}/${component(j)}/${component(i)}/file-${i}.dat`;
const attachments = Array.from({ length: 22000 }, (_, i) => ({
  id: `synthetic-attachment-${i}`,
  resourceId: `synthetic-resource-${i}`,
  hostId: 'synthetic-host',
  threadId: threads[i % threads.length].id,
  name: `synthetic-${i}.dat`,
  mediaType: 'application/octet-stream',
  sizeBytes: 4096 + i,
  sha256: i.toString(16).padStart(64, '0'),
  path: syntheticPath(i),
  desktopMessageLinks: Array.from({ length: 3 }, (_, j) => ({
    messageId: `synthetic-message-${i}-${j}`,
    reference: reference(i, j),
  })),
}));
const target = {
  agentId: 'codex',
  hostId: 'synthetic-host',
  workspaceId: 'synthetic-host',
  runtimeId: 'desktop-runtime',
};
const state = {
  hostId: 'synthetic-host',
  projects: [
    {
      id: 'synthetic-project',
      hostId: 'synthetic-host',
      name: 'Synthetic project',
      roots: ['/synthetic/workspace'],
    },
  ],
  threads,
  attachments,
  receipts: Array.from({ length: 3500 }, (_, i) => {
    const threadId = threads[i % threads.length].id;
    const clientMessageId = `synthetic-receipt-${i}`;
    return {
      clientMessageId,
      hostId: 'synthetic-host',
      threadId,
      state: 'submitted',
      turnId: `synthetic-turn-${i}`,
      error: null,
      fingerprint: i.toString(16).padStart(64, '0'),
      fingerprintVersion: 2,
      target,
      reply: {
        clientMessageId,
        hostId: 'synthetic-host',
        threadId,
        text: `Synthetic reply ${i}`,
        attachmentIds: [attachments[i].id],
        mode: 'default',
      },
      attachmentSnapshot: [attachments[i]],
    };
  }),
  messageFileLinks: Array.from({ length: 4096 }, (_, i) => ({
    threadId: threads[i % threads.length].id,
    messageId: `synthetic-message-${i}`,
    reference: reference(i, 0),
    path: syntheticPath(i),
  })),
  creations: {},
  counter: 0,
};
// Materialized flat one-byte strings in strongly held JS objects, not Buffers
// retained as external memory. Explicit collections are outside measured phases.
assert.equal(typeof global.gc, 'function', 'child requires --expose-gc');
global.gc();
const beforeCold = process.memoryUsage();
const cold = [];
for (let i = 0; i < opts.coldMiB * 64; i++) {
  const text = Buffer.alloc(16 * 1024, 65 + (i % 26)).toString('latin1');
  cold.push({ index: i, text });
}
global.gc();
const afterCold = process.memoryUsage();
const coldHeapDelta = afterCold.heapUsed - beforeCold.heapUsed;
assert(coldHeapDelta >= opts.coldMiB * 1024 * 1024 * 0.9, 'cold strings did not reside in JS heap');
const metadataEstimate = { ...state, threads: state.threads.map((t) => ({ ...t, messages: [] })) };
const shape = {
  threads: threads.length,
  attachments: state.attachments.length,
  receipts: state.receipts.length,
  messageFileLinks: state.messageFileLinks.length,
  activeHistories: 3,
  messagesPerHistory: 128,
  historyJsonBytes: threads.slice(0, 3).map((t) => Buffer.byteLength(JSON.stringify(t.messages))),
  syntheticMetadataBytes: Buffer.byteLength(JSON.stringify(metadataEstimate)),
  coldMiB: opts.coldMiB,
  coldObjects: cold.length,
  beforeCold,
  afterCold,
  coldHeapDelta,
};
if (process.env.PERSISTENCE_FIXTURE_SHAPE_ONLY === '1') {
  process.stdout.write(JSON.stringify(shape));
  await rm(directory, { recursive: true, force: true });
  process.exit(0);
}
try {
  store = new RuntimePersistence(file, directory, {
    rename(from, to) {
      renameSync(from, to);
      if (to === file) counts.statePublishes++;
      else if (to.includes('runtime-history-')) counts.historyPublishes++;
      else counts.otherPublishes++;
    },
    fsync(fd) {
      fsyncSync(fd);
      counts.barriers++;
    },
    report(stage, error) {
      counts.reports.push({ stage, message: String(error?.message ?? error) });
    },
  });
  assert.equal(store.load(), undefined);
  store.acquireReplyWriter(target);
  store.save(state);
  // Flush initial histories and a real state barrier before measuring.
  for (const t of threads.slice(0, 3)) store.dirty(t);
  await delay(1600);
  assert.equal(store.status.historyPending, 0);
  shape.actualMetadataBytes = statSync(file).size;
  const phase = async (name, ticks, mutate, historyChanges = mutate) => {
    const before = snapshot();
    const stop = startCpuStages();
    const start = performance.now();
    for (let i = 0; i < ticks; i++) {
      if (mutate) {
        state.counter++;
      }
      if (historyChanges) {
        threads[0].messages[0].text =
          `changed synthetic tick ${state.counter} ` + 'z'.repeat(90000);
      }
      store.schedule(() => {
        counts.backgroundCallbacks++;
        return state;
      });
      counts.schedules++;
      const dirtyThreads = historyChanges
        ? [threads[0], threads[1 + (i % 2)]]
        : [threads[i % 3], threads[(i + 1) % 3]];
      for (const t of dirtyThreads) {
        store.dirty(t);
        counts.dirty++;
      }
      await delay(Math.max(0, start + (i + 1) * opts.intervalMs - performance.now()));
    }
    // Capture all scheduled work, rather than mistaking pending work for savings.
    const deadline = performance.now() + 15000;
    while (
      (store.status.backgroundSave !== 'idle' || store.status.historyPending) &&
      performance.now() < deadline
    )
      await delay(25);
    assert.equal(store.status.backgroundSave, 'idle', `${name} background did not settle`);
    assert.equal(store.status.historyPending, 0, `${name} histories did not settle`);
    const metrics = stop();
    return {
      name,
      ticks,
      elapsedMs: performance.now() - start,
      counts: difference(snapshot(), before),
      metrics,
    };
  };
  const warmup = await phase('warmup', opts.warmupTicks, false);
  const steady = await phase('steady', opts.ticks, false);
  const metadata = opts.metadataTicks
    ? await phase('metadata', opts.metadataTicks, true, false)
    : undefined;
  const changed = await phase('changed', opts.changeTicks, true);
  // Exercise synchronous read, command and close with real durability/fencing.
  const beforeFinal = snapshot();
  const stopFinal = startCpuStages();
  store.saveRead(state);
  store.save(state);
  // Force a final history update immediately before close, checking its flush.
  state.counter++;
  threads[0].messages[0].text = `final synthetic tick ${state.counter} ` + 'q'.repeat(90000);
  store.saveRead(state);
  store.dirty(threads[0]);
  store.close(true);
  store = undefined;
  const finalMetrics = stopFinal();
  const saved = new RuntimePersistence(file, directory).load();
  assert(saved);
  const { replyLedger: _replyLedger, historyCacheVersion: _historyCacheVersion, ...actual } = saved;
  assert.deepEqual(actual, state, 'saved state or history differs from input');
  assert.equal(counts.reports.length, 0, 'persistence reported an error');
  const raw = JSON.parse(readFileSync(file, 'utf8'));
  assert.equal(raw.counter, state.counter);
  assert.equal(raw.replyLedger.version, 2);
  assert.equal(cold.length, opts.coldMiB * 64);
  assert.equal(cold.at(-1)?.text.length ?? 0, opts.coldMiB ? 16 * 1024 : 0);
  process.stdout.write(
    JSON.stringify({
      schema: 1,
      node: process.version,
      platform: process.platform,
      options: opts,
      shape,
      warmup,
      steady,
      ...(metadata ? { metadata } : {}),
      changed,
      final: { counts: difference(snapshot(), beforeFinal), metrics: finalMetrics },
      maxRssKiB: process.resourceUsage().maxRSS,
      equivalence: {
        passed: true,
        stateSha256: hash(actual),
        historySha256: threads.slice(0, 3).map((t) => hash(t.messages)),
        finalCounter: state.counter,
      },
      totalCounts: counts,
    }),
  );
} finally {
  try {
    store?.close();
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}
