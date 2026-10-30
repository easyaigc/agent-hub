// A real Node Worker with synthetic readiness/ACKs, not a native producer.
// It never spawns another Worker, Original, model, shell, or HTTP service.
import { createHash } from 'node:crypto';
import { isMainThread, parentPort, workerData } from 'node:worker_threads';
import { DesktopNativeSampleFrames } from '../../packages/codex-adapter/dist/desktop-native-sample-framing.js';

if (isMainThread || !parentPort || workerData?.kind !== 'forwarding-boundary-fixture')
  throw new Error('Dedicated forwarding fixture Worker required');
const control = new Int32Array(workerData.shared);
const gate = new Int32Array(workerData.gate);
// Diagnostics use a separate port: they must never masquerade as product ACKs.
const report = workerData.report;
const channels = {
  outbound: { bytes: 0, hash: createHash('sha256') },
  inbound: { bytes: 0, hash: createHash('sha256') },
};
const frames = [];
const framer = new DesktopNativeSampleFrames((direction, line) => {
  frames.push({ direction, bytes: Buffer.byteLength(line), sha256: digest(line) });
});
let started = false;
let begun;
let closed = false;
let sequence = 0;
let sequenceFault = false;
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const snapshot = () => ({
  started,
  begun,
  sequence,
  sequenceFault,
  frames,
  channels: Object.fromEntries(
    Object.entries(channels).map(([direction, value]) => [
      direction,
      { bytes: value.bytes, sha256: value.hash.copy().digest('hex') },
    ]),
  ),
});
function finish(reason) {
  if (closed) return;
  closed = true;
  framer.clear();
  Atomics.store(gate, 5, 1);
  report.postMessage({ kind: 'closed', reason, ...snapshot() });
  report.close();
  parentPort.close();
}
function ack(value) {
  const normal = { kind: 'ack', sequence: value.sequence, size: value.bytes.byteLength };
  switch (workerData.ack ?? 'valid') {
    case 'none':
      return;
    case 'gap':
      normal.sequence++;
      break;
    case 'zero-sequence':
      normal.sequence = 0;
      break;
    case 'string-sequence':
      normal.sequence = String(normal.sequence);
      break;
    case 'unsafe-sequence':
      normal.sequence = Number.MAX_SAFE_INTEGER + 1;
      break;
    case 'wrong-size':
      normal.size++;
      break;
    case 'zero-size':
      normal.size = 0;
      break;
    case 'fractional-size':
      normal.size += 0.5;
      break;
    case 'string-size':
      normal.size = String(normal.size);
      break;
    case 'extra':
      normal.extra = true;
      break;
    case 'missing-size':
      delete normal.size;
      break;
  }
  parentPort.postMessage(normal);
  if (workerData.ack === 'duplicate') parentPort.postMessage(normal);
}

report.on('message', (value) => {
  if (closed) return;
  if (value.kind === 'snapshot')
    report.postMessage({ kind: 'snapshot', id: value.id, ...snapshot() });
  else if (value.kind === 'emit') parentPort.postMessage(value.value);
  else if (value.kind === 'exit') finish('fixture-exit');
});
parentPort.on('message', (value) => {
  if (closed) return;
  if (value.kind === 'close') return finish('parent-close');
  if (value.kind === 'start' && !started) {
    started = true;
    Atomics.store(gate, 1, 1);
    report.postMessage({ kind: 'started', originalFirstData: Atomics.load(gate, 4) });
    Atomics.store(gate, 6, 1);
    report.postMessage({ kind: 'blocked' });
    // Hold this actual isolate before ready. Original stream I/O stays on main.
    // close() wakes the same control word, so a blocked fixture can retire too.
    while (!Atomics.load(gate, 0) && !Atomics.load(control, 0)) Atomics.wait(control, 0, 0, 25);
    if (Atomics.load(control, 0)) return finish('closed-before-ready');
    Atomics.store(gate, 2, 1);
    parentPort.postMessage({ kind: 'ready' });
    return;
  }
  if (value.kind === 'begin' && started && !begun) {
    begun = { outbound: value.outboundBoundary, inbound: value.inboundBoundary };
    if (workerData.frames) framer.begin(begun);
    Atomics.store(gate, 3, 1);
    report.postMessage({ kind: 'begun', boundary: begun });
    return;
  }
  if (value.kind === 'bytes' && begun) {
    if (value.sequence !== sequence + 1) sequenceFault = true;
    sequence = value.sequence;
    const channel = channels[value.direction];
    channel.bytes += value.bytes.byteLength;
    channel.hash.update(value.bytes);
    if (workerData.frames) framer.push(value.direction, value.bytes);
    report.postMessage({
      kind: 'bytes',
      sequence,
      direction: value.direction,
      size: value.bytes.byteLength,
      sha256: digest(value.bytes),
    });
    ack(value);
    return;
  }
  throw new Error('Unexpected forwarding fixture message');
});
