import assert from 'node:assert/strict';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { test } from 'node:test';
import { DesktopSuggestionStore } from '../packages/codex-adapter/dist/desktop-suggestion-store.js';
import { machineRuntimeTargetKey } from '../packages/protocol/dist/index.js';
import { protectWindowsStateDirectory } from '../packages/codex-adapter/dist/desktop-platform.js';

const digest = (value) => createHash('sha256').update(value).digest('hex');
const target = {
  agentId: 'codex',
  hostId: 'host-a',
  workspaceId: 'workspace-a',
  runtimeId: 'desktop-runtime',
};
const fixture = mkdtempSync(join(tmpdir(), 'native-channel-boundary-'));
const platform = join(fixture, 'platform.mjs');
// Synthetic kernel proof only; the storage and channel implementation are real.
writeFileSync(
  platform,
  `export let identity; export function setIdentity(v){identity=v;}
export function inspectNativeSuggestionProducer(){if(!identity)throw Error('dead');return identity;}
export function nativeSuggestionImplementationFingerprint(){return '${digest('implementation')}';}`,
);
let source = readFileSync(
  new URL('../packages/codex-adapter/dist/desktop-native-suggestion-channel.js', import.meta.url),
  'utf8',
);
source = source
  .replaceAll(
    "'@codex-manage/protocol'",
    JSON.stringify(new URL('../packages/protocol/dist/index.js', import.meta.url).href),
  )
  .replaceAll(
    "'./desktop-suggestion-store.js'",
    JSON.stringify(
      new URL('../packages/codex-adapter/dist/desktop-suggestion-store.js', import.meta.url).href,
    ),
  )
  .replaceAll(
    "'./desktop-native-sample-platform.js'",
    JSON.stringify(pathToFileURL(platform).href),
  );
writeFileSync(join(fixture, 'channel.mjs'), source);
const { DesktopNativeSuggestionChannel } = await import(
  pathToFileURL(join(fixture, 'channel.mjs'))
);
const { setIdentity } = await import(pathToFileURL(platform));

test('native channel fences source, producer, implementation, lease and two-stage reads', async (t) => {
  const traceRoot = realpathSync(mkdtempSync(join(tmpdir(), 'native-channel-store-')));
  t.after(() => rmSync(traceRoot, { recursive: true }));
  protectWindowsStateDirectory(traceRoot);
  const key = machineRuntimeTargetKey(target);
  const store = new DesktopSuggestionStore(traceRoot, key);
  const now = Date.now();
  const producer = {
    epoch: randomUUID(),
    pid: process.pid,
    desktopPid: process.ppid,
    desktopStartedAt: now - 20000,
  };
  const state = {
    version: 1,
    kind: 'native-sample-producer',
    targetKey: key,
    producer,
    wrapperStartedAt: now - 15000,
    rootIdentity: store.rootIdentity(),
    implementationSha256: digest('implementation'),
    checkedAt: now,
    phase: 'listening',
  };
  const thread = randomUUID(),
    turn = randomUUID(),
    request = `thread/sample:${randomUUID()}`;
  const text = 'Exact Unicode suggestion\n保留完整正文';
  const record = {
    version: 2,
    kind: 'native-sample',
    targetKey: key,
    sourceThreadId: thread,
    sourceTurnId: turn,
    requestId: request,
    requestSequence: 1,
    requestedAt: now - 3000,
    recordedAt: now - 1000,
    producer,
    state: 'completed',
    result: {
      requestId: request,
      sourceTurnId: turn,
      text,
      contentSha256: digest(text),
      resultSha256: digest('result'),
      responseId: null,
      completedAt: now - 2000,
      safety: { decidedAt: now - 1000, evidenceSha256: digest('safety') },
    },
  };
  setIdentity({
    pid: producer.pid,
    desktopPid: producer.desktopPid,
    desktopStartedAt: producer.desktopStartedAt,
    wrapperStartedAt: state.wrapperStartedAt,
  });
  const originalRename = fs.renameSync;
  let failures = 2;
  fs.renameSync = (...args) => {
    if (failures-- > 0)
      throw Object.assign(new Error('synthetic sharing overlap'), { code: 'EPERM' });
    return originalRename(...args);
  };
  syncBuiltinESMExports();
  try {
    store.saveProducer(state);
  } finally {
    fs.renameSync = originalRename;
    syncBuiltinESMExports();
  }
  assert(failures < 0, 'same atomic publication eventually succeeded');
  const previousProducer = store.readProducer();
  fs.renameSync = () => {
    throw Object.assign(new Error('synthetic persistent denial'), { code: 'EACCES' });
  };
  syncBuiltinESMExports();
  try {
    assert.throws(
      () => store.saveProducer({ ...state, phase: 'unavailable' }),
      /synthetic persistent denial/,
    );
  } finally {
    fs.renameSync = originalRename;
    syncBuiltinESMExports();
  }
  assert.deepEqual(
    store.readProducer(),
    previousProducer,
    'permanent failure cannot replace the old durable result',
  );
  store.prepare();
  const lock = join(store.directory, thread + '.json.write-lock');
  writeFileSync(lock, JSON.stringify({ version: 1, pid: process.pid, token: 'live-owner' }), {
    mode: 0o600,
  });
  assert.equal(store.saveRuntime(record), false, 'live writer must retain the body slot');
  rmSync(lock);
  writeFileSync(lock, JSON.stringify({ version: 1, pid: 2147483647, token: 'dead-owner' }), {
    mode: 0o600,
  });
  assert.equal(store.saveRuntime(record), true, 'confirmed dead process lock is recoverable');
  const channel = new DesktopNativeSuggestionChannel({
    traceRoot,
    configPath: join(traceRoot, 'config.json'),
    target: () => target,
  });
  assert.equal(channel.available, true);
  assert.equal(store.acknowledgeRuntimeResult(thread, turn, now - 4000, now - 2000), true);
  assert.equal(store.acknowledgeRuntimeResult(thread, randomUUID(), now - 4000, now - 2000), false);
  assert.equal(store.acknowledgeRuntimeResult(thread, turn, now, now + 1), false);
  // Latest-only supersession is strict; equality still needs the original same-turn overlap.
  assert.equal(store.acknowledgeRuntimeResult(thread, turn, now - 8000, now - 4000), true);
  assert.equal(store.acknowledgeRuntimeResult(thread, randomUUID(), now - 8000, now - 4000), true);
  assert.equal(store.acknowledgeRuntimeResult(thread, randomUUID(), now - 4000, now - 3000), false);
  assert.equal(store.acknowledgeRuntimeResult(thread, turn, now - 4000, now - 3000), true);
  const response = channel.read(target, thread, turn);
  assert.equal(response.source, 'native-trace');
  assert.equal(response.suggestion.text, text);
  assert.equal(response.suggestion.validity, 'native-version');
  assert.equal(channel.read(target, thread, randomUUID()).suggestion, null);
  assert.throws(() => channel.read({ ...target, hostId: 'other' }, thread, turn), { status: 400 });
  const pending = channel.prepare(target, thread, turn);
  assert.equal(
    store.saveRuntime({
      ...record,
      requestId: `thread/sample:${randomUUID()}`,
      requestSequence: 2,
      requestedAt: now,
      recordedAt: now,
      state: 'pending',
      result: null,
    }),
    true,
  );
  assert.throws(() => pending.commit(), { status: 409 });
  assert.equal(channel.read(target, thread, turn).suggestion, null);
  assert.equal(store.readRuntime(thread).latestExtracted.result.text, text);
  store.saveProducer({ ...state, phase: 'unavailable' });
  assert.equal(channel.available, false);
  store.saveProducer({ ...state, checkedAt: now - 7000 });
  assert.equal(channel.available, false);
  store.saveProducer({ ...state, implementationSha256: digest('old') });
  assert.equal(channel.available, false);
  store.saveProducer(state);
  setIdentity({
    pid: producer.pid,
    desktopPid: producer.desktopPid + 1,
    desktopStartedAt: producer.desktopStartedAt,
    wrapperStartedAt: state.wrapperStartedAt,
  });
  assert.equal(channel.available, false);
  setIdentity(undefined);
  assert.equal(channel.available, false);
});

function runtimeStore(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'native-ack-store-')));
  t.after(() => rmSync(root, { recursive: true }));
  protectWindowsStateDirectory(root);
  return new DesktopSuggestionStore(root, machineRuntimeTargetKey(target));
}

function completedSample(requestedAt = 100) {
  const requestId = `thread/sample:${randomUUID()}`;
  const sourceTurnId = randomUUID();
  const text = 'Synthetic retained native body';
  return {
    version: 2,
    kind: 'native-sample',
    targetKey: machineRuntimeTargetKey(target),
    sourceThreadId: randomUUID(),
    sourceTurnId,
    requestId,
    requestSequence: 1,
    requestedAt,
    recordedAt: requestedAt + 3,
    producer: {
      epoch: randomUUID(),
      pid: process.pid,
      desktopPid: process.ppid,
      desktopStartedAt: 1,
    },
    state: 'completed',
    result: {
      requestId,
      sourceTurnId,
      text,
      contentSha256: digest(text),
      resultSha256: digest('result'),
      responseId: null,
      completedAt: requestedAt + 1,
      safety: { decidedAt: requestedAt + 2, evidenceSha256: digest('safety') },
    },
  };
}

test('native acknowledgement rejects invalid raw ranges and cross-target/thread bodies', async (t) => {
  const store = runtimeStore(t);
  const record = completedSample();
  assert.equal(store.saveRuntime(record), true);
  const thread = record.sourceThreadId,
    turn = record.sourceTurnId;
  const slot = join(store.directory, thread + '.json');
  const bytes = readFileSync(slot);
  for (const [name, start, end] of [
    ['negative start', -1, 101],
    ['negative end', 0, -1],
    ['fractional start', 99.5, 101],
    ['fractional end', 99, 100.5],
    ['unsafe start', Number.MAX_SAFE_INTEGER + 1, 101],
    ['unsafe end', 99, Number.MAX_SAFE_INTEGER + 1],
    ['NaN start', NaN, 101],
    ['NaN end', 99, NaN],
    ['infinite start', -Infinity, 101],
    ['infinite end', 99, Infinity],
    ['missing start', undefined, 101],
    ['missing end', 99, undefined],
    ['coerced start', '99', 101],
    ['coerced end', 99, '101'],
    ['inverted overlapping range', 101, 100],
  ])
    await t.test(name, () => {
      assert.equal(store.acknowledgeRuntimeResult(thread, turn, start, end), false);
      assert.deepEqual(readFileSync(slot), bytes);
    });
  assert.equal(store.acknowledgeRuntimeResult(thread, 'not-a-turn', 1, 99), false);
  assert.equal(store.acknowledgeRuntimeResult(thread, thread, 1, 99), false);
  const otherTarget = new DesktopSuggestionStore(
    store.traceRoot,
    machineRuntimeTargetKey({ ...target, hostId: 'host-b' }),
  );
  assert.equal(otherTarget.acknowledgeRuntimeResult(thread, turn, 1, 99), false);
  assert.equal(store.acknowledgeRuntimeResult(randomUUID(), turn, 1, 99), false);
  for (const mismatch of ['target', 'thread'])
    await t.test('a valid foreign ' + mismatch + ' envelope in the slot is rejected', () => {
      const foreignStore = mismatch === 'target' ? otherTarget : store;
      const foreign = {
        ...record,
        targetKey:
          mismatch === 'target'
            ? machineRuntimeTargetKey({ ...target, hostId: 'host-b' })
            : record.targetKey,
        sourceThreadId: mismatch === 'thread' ? randomUUID() : thread,
      };
      assert.equal(foreignStore.saveRuntime(foreign), true);
      const foreignBytes = readFileSync(
        join(foreignStore.directory, foreign.sourceThreadId + '.json'),
      );
      try {
        writeFileSync(slot, foreignBytes);
        assert.throws(
          () => store.acknowledgeRuntimeResult(thread, turn, 1, 99),
          /Invalid runtime suggestion source/,
        );
        assert.deepEqual(readFileSync(slot), foreignBytes);
      } finally {
        writeFileSync(slot, bytes);
      }
    });
});

test('native acknowledgement uses the retained body time, never a pending request time', async (t) => {
  for (const kind of ['no-body', 'older-body', 'later-body'])
    await t.test(kind, (t) => {
      const store = runtimeStore(t);
      const body = completedSample(kind === 'later-body' ? 201 : 100);
      if (kind !== 'no-body') assert.equal(store.saveRuntime(body), true);
      const rawTurn = randomUUID();
      const pending = {
        ...body,
        sourceTurnId: rawTurn,
        requestId: `thread/sample:${randomUUID()}`,
        requestSequence: 2,
        requestedAt: 300,
        recordedAt: 300,
        state: 'pending',
        result: null,
      };
      assert.equal(store.saveRuntime(pending), true);
      const saved = store.readRuntime(body.sourceThreadId);
      assert.equal(saved.state, 'pending');
      assert.equal(saved.result, null);
      assert.equal(
        saved.latestExtracted?.result.text,
        kind === 'no-body' ? undefined : body.result.text,
      );
      const slot = join(store.directory, body.sourceThreadId + '.json');
      const bytes = readFileSync(slot);
      assert.equal(
        store.acknowledgeRuntimeResult(body.sourceThreadId, rawTurn, 150, 200),
        kind === 'later-body',
      );
      assert.deepEqual(readFileSync(slot), bytes);
    });
});

test('native acknowledgement rejects invalid retained-body ranges even in a valid pending envelope', async (t) => {
  for (const kind of ['completed-before-request', 'safety-after-record'])
    await t.test(kind, (t) => {
      const store = runtimeStore(t);
      const body = completedSample();
      const latestExtracted = {
        kind: 'native-sample',
        requestedAt: kind === 'completed-before-request' ? 102 : body.requestedAt,
        result: {
          ...body.result,
          safety: {
            ...body.result.safety,
            decidedAt: kind === 'safety-after-record' ? 301 : body.result.safety.decidedAt,
          },
        },
      };
      assert.equal(
        store.saveRuntime({
          ...body,
          sourceTurnId: randomUUID(),
          requestId: `thread/sample:${randomUUID()}`,
          requestSequence: 2,
          requestedAt: 300,
          recordedAt: 300,
          state: 'pending',
          result: null,
          latestExtracted,
        }),
        true,
        'the public store accepts this pending envelope; ack must validate its retained time proof',
      );
      assert.deepEqual(store.readRuntime(body.sourceThreadId).latestExtracted, latestExtracted);
      const slot = join(store.directory, body.sourceThreadId + '.json');
      const bytes = readFileSync(slot);
      assert.equal(
        store.acknowledgeRuntimeResult(body.sourceThreadId, body.sourceTurnId, 100, 200),
        false,
      );
      assert.deepEqual(readFileSync(slot), bytes);
    });
});

test.after(() => rmSync(fixture, { recursive: true }));
