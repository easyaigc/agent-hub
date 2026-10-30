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
  assert.equal(store.acknowledgeRuntimeResult(thread, turn, now - 8000, now - 4000), false);
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

test.after(() => rmSync(fixture, { recursive: true }));
