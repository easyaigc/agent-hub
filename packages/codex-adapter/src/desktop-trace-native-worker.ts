import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { MessagePort, parentPort, workerData } from 'node:worker_threads';
import { win32 } from 'node:path';
import { windowsTraceNativeExecutable, type WindowsDesktopIdentity } from './desktop-platform.js';
import {
  privateStateProtocol as p,
  assertWindowsTraceProducerScope,
  type WindowsTraceProducerScope,
} from './desktop-private-state-native.js';
import { withWindowsProcessCreation } from './windows-process.js';
import { createWindowsTraceDiagnostics } from './windows-trace-diagnostics.js';
import {
  encodeTraceSnapshotBatch,
  decodeTraceSnapshotBatch,
  parseTraceSnapshotBatchJSON,
  traceSnapshotBatchLimits,
  encodeTraceProducerRequest,
  decodeTraceProducerIdentity,
  decodeTraceProducerSnapshot,
  traceProducerLimits,
  traceProducerPathWithin,
  parseTraceProducerJSON,
  encodeTraceLogRangeRequest,
  decodeTraceLogRange,
  traceLogRangeLimits,
  type TraceLogRangeRequest,
  type TraceProducerRequest,
  type TraceSnapshotBatch,
} from './desktop-trace-native.js';

if (workerData?.kind !== 'windows-trace-native' || workerData.entryURL !== import.meta.url)
  throw new Error('Trace transport worker required');
const { root, identity, shared, lifecycle, ownerPort, producer } = workerData as {
  root: string;
  identity: WindowsDesktopIdentity;
  shared: SharedArrayBuffer;
  lifecycle: SharedArrayBuffer;
  ownerPort: MessagePort;
  producer?: WindowsTraceProducerScope;
};
if (
  !(shared instanceof SharedArrayBuffer) ||
  shared.byteLength !== 48 * 1024 * 1024 + 8 ||
  !(lifecycle instanceof SharedArrayBuffer) ||
  lifecycle.byteLength !== p.words * 4 + p.responseBytes ||
  !(ownerPort instanceof MessagePort)
)
  throw new Error('Invalid trace transport');
if (Object.hasOwn(workerData, 'producer')) assertWindowsTraceProducerScope(producer, root);
const configRoot = producer ? win32.dirname(producer.configPath) : undefined;
const startupDeadline = performance.now() + p.startupMs;
const control = new Int32Array(shared, 0, 2);
const bytes = new Uint8Array(shared, 8);
const life = new Int32Array(lifecycle, 0, p.words);
let child: ChildProcessWithoutNullStreams | undefined;
let chunks: Buffer[] = [];
let buffered = 0;
let ready = false,
  pending = false,
  closed = false;
let timer: NodeJS.Timeout | undefined;
let batch: TraceSnapshotBatch | undefined;
let logRange: TraceLogRangeRequest | undefined;
let producerRequest: TraceProducerRequest | undefined;
let snapshotRequest: { path: string; maximum: number } | undefined;
let nativeDeadline = Infinity;
let lastBatch = 0;
let killTimer: NodeJS.Timeout | undefined;
let childExited = false,
  childClosed = false,
  streamsClosed = 0,
  retired = false;
const diagnostics = createWindowsTraceDiagnostics('native', 0, { operation: 'startup' });
let startupEnd: (() => void) | undefined;
let responseEnd: (() => void) | undefined, closeEnd: (() => void) | undefined;
function reply(value: Buffer, failed = false): void {
  responseEnd?.();
  responseEnd = undefined;
  clearTimeout(timer);
  timer = undefined;
  if (!pending) return;
  pending = false;
  if (value.length > bytes.length) failed = true;
  const expired = () =>
    producer !== undefined &&
    (performance.now() >= nativeDeadline ||
      Atomics.load(life, p.state) !== p.ready ||
      Atomics.load(control, 0) !== 0);
  if (expired()) failed = true;
  if ((batch || producer) && !failed) {
    try {
      const maximum = logRange
        ? traceLogRangeLimits.responseBytes
        : producerRequest
          ? traceProducerLimits.responseBytes
          : batch
            ? traceSnapshotBatchLimits.responseBytes
            : bytes.length;
      if (value.length > maximum) throw new Error('Native response too large');
      const parsed = producer
        ? parseTraceProducerJSON(value)
        : parseTraceSnapshotBatchJSON(value.toString('utf8'));
      if (batch) decodeTraceSnapshotBatch(parsed, batch);
      else if (logRange) decodeTraceLogRange(parsed, logRange);
      else if (producerRequest) decodeTraceProducerIdentity(parsed, producerRequest, identity);
      else if (snapshotRequest)
        decodeTraceProducerSnapshot(parsed, snapshotRequest.path, snapshotRequest.maximum);
      else if (parsed && typeof parsed === 'object' && Object.hasOwn(parsed, 'error'))
        throw new Error('Native producer operation rejected');
    } catch {
      failed = true;
    }
  }
  if (expired()) failed = true;
  if (!failed) {
    bytes.set(value);
    if (expired()) failed = true;
  }
  const retire = (batch !== undefined || producer !== undefined) && failed;
  batch = undefined;
  logRange = undefined;
  producerRequest = undefined;
  snapshotRequest = undefined;
  Atomics.store(control, 1, failed ? 0 : value.length);
  Atomics.store(control, 0, failed ? -1 : 1);
  Atomics.notify(control, 0);
  if (retire) close();
}
function gone(): void {
  if (retired || (child && (!childExited || !childClosed || streamsClosed !== 3))) return;
  retired = true;
  closeEnd?.();
  clearTimeout(timer);
  clearTimeout(killTimer);
  child?.stdin.destroy();
  child?.stdout.destroy();
  child?.stderr.destroy();
  Atomics.store(life, p.state, p.gone);
  Atomics.notify(life, p.state);
  ownerPort.close();
  parentPort?.close();
  diagnostics.end('ok', {
    operation: 'retirement',
    childExited: child === undefined || childExited,
    stdioClosed: child === undefined || streamsClosed === 3,
  });
}
function close(): void {
  if (closed) return;
  closed = true;
  reply(Buffer.alloc(0), true);
  startupEnd?.();
  startupEnd = undefined;
  closeEnd = diagnostics.begin('close');
  clearTimeout(timer);
  Atomics.store(life, p.state, p.failed);
  Atomics.store(control, 0, -1);
  Atomics.notify(life, p.state);
  Atomics.notify(control, 0);
  if (!child) {
    gone();
    return;
  }
  try {
    child.stdin.end('QUIT\n');
  } catch {
    child.kill();
  }
  if (!childExited) killTimer = setTimeout(() => child?.kill(), 250);
}
parentPort?.once('close', close);
parentPort?.on('message', close);
ownerPort.once('close', close);
ownerPort.on('message', (input: unknown) => {
  if (input === null || typeof input !== 'object' || Array.isArray(input)) {
    close();
    return;
  }
  const request = input as { kind: string; path?: string; maximum?: number };
  if (request.kind === 'close') {
    close();
    return;
  }
  if (!ready || pending || closed) {
    close();
    return;
  }
  let command: string;
  if (
    request.kind === 'snapshot' &&
    typeof request.path === 'string' &&
    Number.isSafeInteger(request.maximum) &&
    request.maximum! >= 0 &&
    request.maximum! <= 32 * 1024 * 1024
  ) {
    if (
      producer &&
      (Object.keys(request).length !== 3 ||
        !Object.hasOwn(request, 'maximum') ||
        !(
          traceProducerPathWithin(root, request.path) ||
          traceProducerPathWithin(configRoot!, request.path)
        ))
    ) {
      close();
      return;
    }
    if (producer) snapshotRequest = { path: request.path, maximum: request.maximum! };
    command = 'S\t' + Buffer.from(request.path).toString('base64') + '\t' + request.maximum;
  } else if (request.kind === 'snapshot-many') {
    try {
      command = encodeTraceSnapshotBatch(request);
      batch = request as unknown as TraceSnapshotBatch;
      if (producer && batch.items.some((item) => !traceProducerPathWithin(root, item.path)))
        throw new Error('Native producer batch scope changed');
      if (batch.id <= lastBatch) throw new Error('Stale native batch');
      lastBatch = batch.id;
    } catch {
      close();
      return;
    }
  } else if (request.kind === 'producer') {
    try {
      command = encodeTraceProducerRequest(request);
      if (producer) {
        const inspect = request as unknown as TraceProducerRequest;
        if (inspect.ProducerPid !== producer.producerPid || inspect.Config !== producer.configPath)
          throw new Error('Native producer identity scope changed');
        producerRequest = inspect;
      }
    } catch {
      close();
      return;
    }
  } else if (request.kind === 'log-range' && producer) {
    try {
      command = encodeTraceLogRangeRequest(request);
      logRange = request as unknown as TraceLogRangeRequest;
      if (!traceProducerPathWithin(producer.logRoot, logRange.path))
        throw new Error('Native producer log scope changed');
    } catch {
      close();
      return;
    }
  } else if (request.kind === 'probe' || request.kind === 'tokens') {
    if (producer && Object.keys(request).length !== 1) {
      close();
      return;
    }
    command = request.kind === 'probe' ? 'P' : 'T';
  } else {
    if (producer) {
      close();
      return;
    }
    Atomics.store(control, 0, -1);
    Atomics.notify(control, 0);
    return;
  }
  pending = true;
  if (producer) nativeDeadline = performance.now() + 750;
  responseEnd = diagnostics.begin('native_response');
  timer = setTimeout(() => {
    diagnostics.end('phase_deadline', { operation: 'read', deadlineKind: 'native_response' });
    reply(Buffer.alloc(0), true);
    close();
  }, 750);
  try {
    child!.stdin.write(command + '\n');
  } catch {
    close();
  }
});
process.once('exit', () => {
  if (Atomics.load(life, p.state) !== p.gone) child?.kill();
});
const starting = () =>
  Atomics.load(life, p.state) === p.starting && (!producer || performance.now() < startupDeadline);
try {
  if (diagnostics.enabled) startupEnd = diagnostics.begin('native_start');
  const executable = starting() ? windowsTraceNativeExecutable(starting) : undefined;
  if (!executable || !starting()) gone();
  else {
    Atomics.store(life, p.pid, -1);
    child = diagnostics.phase('gate_wait', () =>
      withWindowsProcessCreation(
        producer ? Math.max(1, Math.floor(startupDeadline - performance.now())) : p.startupMs,
        () => {
          if (!starting()) throw new Error('Trace owner closed');
          const create = () =>
            spawn(
              executable,
              [
                producer ? 'serve-producer' : 'serve',
                root,
                String(identity.pid),
                String(identity.startedAtUnixMs),
                identity.executable,
                ...(producer ? [String(producer.producerPid), producer.configPath] : []),
              ],
              { stdio: 'pipe', windowsHide: true },
            );
          return diagnostics.enabled ? diagnostics.phase('spawn', create) : create();
        },
        starting,
      ),
    );
    Atomics.store(life, p.pid, child.pid ?? 0);
    for (const stream of [child.stdin, child.stdout, child.stderr]) {
      stream.once('close', () => {
        streamsClosed++;
        gone();
      });
    }
    const closeStreams = () => {
      child!.stdin.destroy();
      child!.stdout.destroy();
      child!.stderr.destroy();
    };
    child.stdin.on('error', close);
    child.stdout.on('error', close);
    child.stdout.once('end', close);
    child.stderr.on('error', close);
    child.stderr.resume();
    child.once('error', () => {
      ownerPort.postMessage({ kind: 'failed' });
      close();
      if (!child?.pid) {
        childExited = true;
        closeStreams();
      }
    });
    child.once('exit', () => {
      childExited = true;
      if (!closed) ownerPort.postMessage({ kind: 'failed' });
      close();
      clearTimeout(killTimer);
      closeStreams();
    });
    child.once('close', () => {
      childClosed = true;
      gone();
    });
    child.stdout.on('data', (chunk: Buffer) => {
      if (closed) return;
      if (ready && !pending) {
        close();
        return;
      }
      for (let start = 0; start < chunk.length;) {
        const newline = chunk.indexOf(10, start);
        const part = chunk.subarray(start, newline < 0 ? chunk.length : newline);
        buffered += part.length;
        const maximum = logRange
          ? traceLogRangeLimits.responseBytes
          : producerRequest
            ? traceProducerLimits.responseBytes
            : bytes.length;
        if (buffered > maximum) {
          close();
          return;
        }
        chunks.push(part);
        if (newline < 0) break;
        const line = Buffer.concat(chunks, buffered);
        chunks = [];
        buffered = 0;
        start = newline + 1;
        if (!ready) {
          try {
            if (
              (producer
                ? (() => {
                    const value = parseTraceProducerJSON(line);
                    return (
                      !value ||
                      typeof value !== 'object' ||
                      Array.isArray(value) ||
                      Object.keys(value).length !== 1 ||
                      !Object.hasOwn(value, 'ready') ||
                      (value as { ready: unknown }).ready !== true
                    );
                  })()
                : JSON.parse(line.toString()).ready !== true) ||
              (producer && (!starting() || start !== chunk.length)) ||
              Atomics.compareExchange(life, p.state, p.starting, p.ready) !== p.starting
            ) {
              close();
              return;
            }
          } catch {
            close();
            return;
          }
          ready = true;
          startupEnd?.();
          startupEnd = undefined;
          diagnostics.end('ok', { operation: 'startup', phase: 'native_start' });
          ownerPort.postMessage({ kind: 'ready', pid: child?.pid });
        } else {
          // One pending operation owns exactly one frame. Never publish a
          // valid prefix of an extra/multi-frame response from the same chunk.
          if (!pending || start !== chunk.length) {
            close();
            return;
          }
          reply(line);
        }
      }
    });
  }
} catch {
  close();
}
