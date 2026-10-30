import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { MessagePort, parentPort, workerData } from 'node:worker_threads';
import { windowsTraceNativeExecutable, type WindowsDesktopIdentity } from './desktop-platform.js';
import { privateStateProtocol as p } from './desktop-private-state-native.js';
import { withWindowsProcessCreation } from './windows-process.js';
import { createWindowsTraceDiagnostics } from './windows-trace-diagnostics.js';
import {
  encodeTraceSnapshotBatch,
  decodeTraceSnapshotBatch,
  parseTraceSnapshotBatchJSON,
  traceSnapshotBatchLimits,
  encodeTraceProducerRequest,
  type TraceSnapshotBatch,
} from './desktop-trace-native.js';

if (workerData?.kind !== 'windows-trace-native' || workerData.entryURL !== import.meta.url)
  throw new Error('Trace transport worker required');
const { root, identity, shared, lifecycle, ownerPort } = workerData as {
  root: string;
  identity: WindowsDesktopIdentity;
  shared: SharedArrayBuffer;
  lifecycle: SharedArrayBuffer;
  ownerPort: MessagePort;
};
if (
  !(shared instanceof SharedArrayBuffer) ||
  shared.byteLength !== 48 * 1024 * 1024 + 8 ||
  !(lifecycle instanceof SharedArrayBuffer) ||
  lifecycle.byteLength !== p.words * 4 + p.responseBytes ||
  !(ownerPort instanceof MessagePort)
)
  throw new Error('Invalid trace transport');
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
  if (batch && !failed) {
    try {
      if (value.length > traceSnapshotBatchLimits.responseBytes) throw new Error('Batch too large');
      decodeTraceSnapshotBatch(parseTraceSnapshotBatchJSON(value.toString('utf8')), batch);
    } catch {
      failed = true;
    }
  }
  const invalidBatch = batch !== undefined && failed;
  batch = undefined;
  if (!failed) bytes.set(value);
  Atomics.store(control, 1, failed ? 0 : value.length);
  Atomics.store(control, 0, failed ? -1 : 1);
  Atomics.notify(control, 0);
  if (invalidBatch) close();
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
    command = 'S\t' + Buffer.from(request.path).toString('base64') + '\t' + request.maximum;
  } else if (request.kind === 'snapshot-many') {
    try {
      command = encodeTraceSnapshotBatch(request);
      batch = request as unknown as TraceSnapshotBatch;
      if (batch.id <= lastBatch) throw new Error('Stale native batch');
      lastBatch = batch.id;
    } catch {
      close();
      return;
    }
  } else if (request.kind === 'producer') {
    try {
      command = encodeTraceProducerRequest(request);
    } catch {
      close();
      return;
    }
  } else if (request.kind === 'probe') command = 'P';
  else if (request.kind === 'tokens') command = 'T';
  else {
    Atomics.store(control, 0, -1);
    Atomics.notify(control, 0);
    return;
  }
  pending = true;
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
const starting = () => Atomics.load(life, p.state) === p.starting;
try {
  if (diagnostics.enabled) startupEnd = diagnostics.begin('native_start');
  const executable = starting() ? windowsTraceNativeExecutable(starting) : undefined;
  if (!executable || !starting()) gone();
  else {
    Atomics.store(life, p.pid, -1);
    child = diagnostics.phase('gate_wait', () =>
      withWindowsProcessCreation(
        p.startupMs,
        () => {
          if (!starting()) throw new Error('Trace owner closed');
          const create = () =>
            spawn(
              executable,
              [
                'serve',
                root,
                String(identity.pid),
                String(identity.startedAtUnixMs),
                identity.executable,
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
        if (buffered > bytes.length) {
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
              JSON.parse(line.toString()).ready !== true ||
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
