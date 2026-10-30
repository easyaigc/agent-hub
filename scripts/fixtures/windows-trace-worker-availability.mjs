import assert from 'node:assert/strict';
import fs from 'node:fs';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { parentPort, workerData } from 'node:worker_threads';

// Fixed synthetic transport only: real Worker ordering/blocking, no native
// process, source history, model, network, or caller-controlled executable.
assert.equal(workerData.kind, 'windows-suggestion-reader');
const log = join(workerData.scope.directory, 'availability-timeline.jsonl');
const record = (event) =>
  fs.appendFileSync(log, JSON.stringify({ event, at: Date.now() }) + '\n', { mode: 0o600 });
const pause = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const config = join(workerData.scope.directory, 'availability-mode.json');
const mode = fs.existsSync(config) ? JSON.parse(fs.readFileSync(config)).mode : 'slow';
const invalidMessages = new Map([
  ['top-null', null],
  ['top-undefined', undefined],
  ['top-string', 'synthetic malformed message'],
  ['top-number', 42],
  ['top-array', []],
  ['state-missing', { kind: 'state' }],
  ['state-truthy', { kind: 'state', available: 'true' }],
  ['state-null', { kind: 'state', available: null }],
  ['state-no-time', { kind: 'state', available: true }],
  ['state-future-time', { kind: 'state', available: true, sampledAt: Number.MAX_VALUE }],
]);
const errors = new Map([
  ['error-false', false],
  ['error-zero', 0],
  ['error-null', null],
  ['error-undefined', undefined],
  ['error-empty-string', ''],
  ['error-true', true],
  ['error-string', 'synthetic error'],
  ['error-array', []],
  ['error-empty-object', {}],
  ['status-null', { status: null }],
  ['status-zero', { status: 0 }],
  ['status-success', { status: 200 }],
  ['status-string', { status: '503' }],
  ['status-fractional', { status: 503.5 }],
  ['status-nan', { status: NaN }],
  ['status-infinity', { status: Infinity }],
  ['status-overflow', { status: 1000 }],
  ['status-other-failure', { status: 500 }],
  ['status-400', { status: 400 }],
  ['status-409', { status: 409 }],
  ['status-503', { status: 503 }],
]);
assert(
  [
    'slow',
    'false',
    'false-text',
    'error',
    'malformed',
    'wrong-target',
    'empty',
    'missing',
    'truthy',
    'stale',
    'late',
    'closed',
    'deadline-stall',
    'queued-state',
    'old-final-sample',
  ].includes(mode) ||
    invalidMessages.has(mode) ||
    errors.has(mode),
);
const generationFile = join(workerData.scope.directory, 'availability-generation.json');
const generation = fs.existsSync(generationFile)
  ? JSON.parse(fs.readFileSync(generationFile)) + 1
  : 1;
// The parent polls while a replacement starts; publish a complete generation
// without exposing writeFileSync's truncate-to-empty interval to that reader.
const generationStage = generationFile + '.' + randomUUID() + '.tmp';
try {
  fs.writeFileSync(generationStage, JSON.stringify(generation), { flag: 'wx', mode: 0o600 });
  fs.renameSync(generationStage, generationFile);
} finally {
  if (fs.existsSync(generationStage)) fs.unlinkSync(generationStage);
}
let reads = 0;
let delayed;
const lifetime = setTimeout(() => parentPort.close(), 12000);
lifetime.unref();
function finish() {
  clearTimeout(lifetime);
  clearTimeout(delayed);
  parentPort.close();
}
function result(request, available = true) {
  return {
    kind: 'result',
    id: request.id,
    available,
    sampledAt: performance.now(),
    value: {
      schemaVersion: 1,
      target: workerData.scope.target,
      threadId: request.threadId,
      sourceTurnId: request.sourceTurnId,
      source: 'native-trace',
      readOnly: true,
      suggestion: null,
    },
  };
}
let pending;
const initialSampledAt = performance.now();
record('initial-state');
const state = (available = true) =>
  parentPort.postMessage({ kind: 'state', available, sampledAt: performance.now() });
state(mode !== 'late' || generation === 1);
parentPort.on('message', (request) => {
  if (request.kind === 'close') {
    record('close');
    if (mode === 'late' && generation === 1 && pending) return;
    if (mode === 'closed' && pending) {
      state();
      parentPort.postMessage(result(pending));
    }
    finish();
    return;
  }
  if (mode === 'queued-state' && request.kind === 'fixture-queue-state') {
    assert(request.gate instanceof SharedArrayBuffer && request.gate.byteLength === 4);
    const gate = new Int32Array(request.gate);
    const sampledAt = performance.now();
    Atomics.store(gate, 0, 1);
    Atomics.notify(gate, 0);
    Atomics.wait(gate, 0, 1, 10000);
    assert.equal(Atomics.load(gate, 0), 2, 'Main must release the already sampled state');
    parentPort.postMessage({ kind: 'state', available: true, sampledAt });
    parentPort.postMessage({ kind: 'fixture-state-posted' });
    return;
  }
  if (request.kind !== 'read') return;
  pending = request;
  if (invalidMessages.has(mode)) {
    parentPort.postMessage(invalidMessages.get(mode));
    parentPort.postMessage({ kind: 'fixture-malformed-sent' });
    return;
  }
  if (mode === 'closed' || mode === 'late' || mode === 'deadline-stall') {
    parentPort.postMessage({ kind: 'fixture-read-start' });
    delayed = setTimeout(
      () => {
        parentPort.postMessage(result(request));
        state();
        finish();
      },
      mode === 'late' ? 5500 : mode === 'deadline-stall' ? 100 : 1000,
    );
    return;
  }
  if (mode !== 'slow') {
    if (mode === 'stale') state(false);
    const response = result(request, !['false', 'false-text'].includes(mode));
    if (mode === 'false-text')
      response.value.suggestion = {
        id: 'synthetic-version',
        validity: 'native-version',
        text: 'contradictory fixture must not escape',
        contentSha256: 'a'.repeat(64),
        expiresAt: new Date(Date.now() + 6000).toISOString(),
      };
    if (mode === 'error') response.error = { status: 503 };
    if (errors.has(mode)) response.error = errors.get(mode);
    if (mode === 'malformed') response.value.sourceTurnId = request.threadId;
    if (mode === 'wrong-target')
      response.value.target = { ...workerData.scope.target, runtimeId: 'other-fixture' };
    if (mode === 'empty') response.value = {};
    if (mode === 'missing') delete response.available;
    if (mode === 'truthy') response.available = 'true';
    if (mode === 'old-final-sample') response.sampledAt = initialSampledAt;
    if (mode === 'stale') {
      parentPort.postMessage({ ...response, id: request.id + 100 });
      pause(150);
      response.available = false;
    }
    parentPort.postMessage(response);
    return;
  }
  if (++reads === 1) {
    record('read-start');
    parentPort.postMessage({ kind: 'fixture-read-start' });
    pause(2700); // Real synchronous Worker work across the old 1800/2000ms limits.
  }
  record('result');
  parentPort.postMessage(result(request)); // Explicit final guard, never inferred from text.
  if (reads === 1) pause(400); // The real reader does its fresh state work after result.
  record('following-state');
  state();
});
