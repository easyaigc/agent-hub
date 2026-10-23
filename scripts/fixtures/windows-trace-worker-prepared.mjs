import assert from 'node:assert/strict';
import fs from 'node:fs';
import { join } from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { parentPort, workerData } from 'node:worker_threads';

// Actual Node Worker transport only. Fixed synthetic phases, never native evidence.
const { mode } = JSON.parse(
  fs.readFileSync(join(workerData.scope.directory, 'prepared-mode.json')),
);
const pause = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const log = (event) =>
  fs.appendFileSync(
    join(workerData.scope.directory, 'prepared-events.jsonl'),
    JSON.stringify({ event, at: performance.now() }) + '\n',
    { mode: 0o600 },
  );
let pending;
let commits = 0;
let delayed;
let synchronization;
const signalPhase = () => {
  if (!synchronization) return;
  Atomics.store(synchronization, 0, 1);
  Atomics.notify(synchronization, 0);
};
const synchronizeSample = () => {
  assert(synchronization);
  signalPhase();
  Atomics.wait(synchronization, 1, 0, 1000);
  assert.equal(Atomics.load(synchronization, 1), 1);
};
const state = () =>
  parentPort.postMessage({ kind: 'state', available: true, sampledAt: performance.now() });
const heartbeat = setInterval(state, 1000);
state();
parentPort.on('message', (request) => {
  // Test-only dispatch/sample barrier; it changes no product deadline or message.
  if (request.kind === 'fixture-sync') {
    assert(request.shared instanceof SharedArrayBuffer);
    assert.equal(request.shared.byteLength, 8);
    synchronization = new Int32Array(request.shared);
    return;
  }
  if (request.kind === 'close') {
    clearInterval(heartbeat);
    clearTimeout(delayed);
    parentPort.close();
    return;
  }
  if (request.kind === 'abort') {
    log('abort');
    pending = undefined;
    return;
  }
  if (request.kind === 'prepare') {
    assert.equal(pending, undefined);
    log('prepare');
    pending = {
      id: request.id,
      generation: request.generation,
      target: request.target,
      threadId: request.threadId,
      sourceTurnId: request.sourceTurnId,
      nonce: randomUUID(),
    };
    if (mode === 'prepare-hang') return;
    if (mode !== 'queued-prepare') signalPhase();
    if (mode === 'slow') pause(3400);
    if (mode === 'slow-commit') pause(2200);
    if (mode === 'prepare-late') pause(4200);
    const message = { kind: 'prepared', ...pending, available: true, sampledAt: performance.now() };
    if (mode === 'prepare-body') message.value = { secret: 'uncommitted fixture bytes' };
    if (mode === 'prepare-false') message.available = false;
    if (mode === 'prepare-generation') message.generation++;
    if (mode === 'prepare-source') message.sourceTurnId = randomUUID();
    if (mode === 'prepare-target') message.target = { ...message.target, hostId: 'wrong' };
    if (mode === 'prepare-old-sample') message.sampledAt = 1;
    // The exact sample exists before Main stalls; release never renews it.
    if (mode === 'queued-prepare') synchronizeSample();
    parentPort.postMessage(message);
    if (mode === 'prepare-duplicate') parentPort.postMessage(message);
    return;
  }
  if (request.kind !== 'commit') return;
  assert(pending);
  assert.equal(request.nonce, pending.nonce);
  assert.equal(++commits, 1);
  log('commit');
  if (mode === 'commit-hang') return;
  if (mode !== 'queued-result') signalPhase();
  if (mode === 'slow') pause(2200);
  if (mode === 'slow-commit') pause(3400);
  if (mode === 'commit-late') pause(4200);
  const text = 'committed synthetic fixture only';
  const message = {
    kind: 'fenced-result',
    ...pending,
    available: true,
    sampledAt: performance.now(),
    value: {
      schemaVersion: 1,
      target: pending.target,
      threadId: pending.threadId,
      sourceTurnId: pending.sourceTurnId,
      readOnly: true,
      source: 'native-trace',
      suggestion: {
        id: 'synthetic-prepared',
        validity: 'native-version',
        text,
        contentSha256: createHash('sha256').update(text).digest('hex'),
        expiresAt: new Date(Date.now() + 6000).toISOString(),
      },
    },
  };
  if (mode === 'commit-nonce') message.nonce = randomUUID();
  if (mode === 'commit-id') message.id++;
  if (mode === 'commit-generation') message.generation++;
  if (mode === 'commit-source') message.sourceTurnId = randomUUID();
  if (mode === 'commit-target') message.target = { ...message.target, runtimeId: 'wrong' };
  if (mode === 'commit-false') message.available = false;
  if (mode === 'commit-old-sample') message.sampledAt = 1;
  if (mode === 'commit-value-source') message.value.sourceTurnId = randomUUID();
  const send = () => {
    parentPort.postMessage(message);
    pending = undefined;
  };
  if (mode === 'queued-result') {
    if (synchronization) synchronizeSample();
    delayed = setTimeout(send, 50);
  } else send();
});
