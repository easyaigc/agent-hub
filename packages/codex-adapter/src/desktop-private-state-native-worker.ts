import { diagnosticEvent } from './diagnostic-events.js';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { MessagePort, parentPort, workerData } from 'node:worker_threads';
import { windowsPrivateStateNativeExecutable } from './desktop-platform.js';
import { privateStatePath, privateStateProtocol as p } from './desktop-private-state-native.js';
import { withWindowsProcessCreation } from './windows-process.js';

if (workerData?.kind !== 'windows-private-state' || workerData.entryURL !== import.meta.url)
  throw new Error('Private state worker required');
const shared: unknown = workerData.shared;
if (!(shared instanceof SharedArrayBuffer) || shared.byteLength !== p.words * 4 + p.responseBytes)
  throw new Error('Invalid private state shared response');
const control = new Int32Array(shared, 0, p.words);
const bytes = new Uint8Array(shared, p.words * 4);
const ownerPort: MessagePort | null =
  workerData.ownerPort instanceof MessagePort ? workerData.ownerPort : parentPort;
let child: ChildProcessWithoutNullStreams | undefined;
let pending = 0,
  ready = false,
  closing = false,
  buffered = Buffer.alloc(0);
const started = performance.now();
let dispatchedAt = 0;
const record = (stage: string, extra: Record<string, unknown> = {}) =>
  diagnosticEvent({
    scope: 'private-native',
    stage,
    request: pending,
    pid: child?.pid,
    elapsedMs: performance.now() - started,
    ...extra,
  });
let timer: NodeJS.Timeout | undefined;
let killTimer: NodeJS.Timeout | undefined;
function gone(): void {
  record('cleanup-confirmed', { gone: true });
  clearTimeout(timer);
  clearTimeout(killTimer);
  // The direct child has exited (or was never spawned). Foreign inherited
  // writers must not keep this transport Worker alive waiting for pipe EOF.
  child?.stdin.destroy();
  child?.stdout.destroy();
  child?.stderr.destroy();
  Atomics.store(control, p.state, p.gone);
  Atomics.notify(control, p.state);
  ownerPort?.close();
  parentPort?.close();
}
function close(): void {
  if (closing) return;
  record('closing');
  closing = true;
  clearTimeout(timer);
  Atomics.store(control, p.state, p.failed);
  Atomics.store(control, p.result, p.failed);
  Atomics.notify(control, p.state);
  Atomics.notify(control, p.result);
  if (!child) {
    gone();
    return;
  }
  try {
    child.stdin.end('QUIT\n');
  } catch {
    child.kill();
  }
  killTimer = setTimeout(() => child?.kill(), 250);
}
process.once('exit', () => {
  // Worker termination closes stdin too; the native reader exits even during Execute.
  if (Atomics.load(control, p.state) !== p.gone) child?.kill();
});
parentPort?.once('close', () => {
  record('cancel', { initiator: 'parent-port' });
  close();
});
if (ownerPort !== parentPort) parentPort?.on('message', close);
ownerPort?.once('close', () => {
  record('cancel', { initiator: 'owner-port' });
  close();
});
ownerPort?.on('message', (request: Record<string, unknown>) => {
  if (request?.kind === 'close') {
    record('cancel', { initiator: 'owner' });
    close();
    return;
  }
  if (
    closing ||
    !ready ||
    Atomics.load(control, p.state) !== p.ready ||
    request?.kind !== 'call' ||
    pending ||
    !Number.isSafeInteger(request.id) ||
    request.id !== Atomics.load(control, p.request) ||
    (request.id as number) <= 0 ||
    !['protect', 'protect-file', 'check'].includes(request.action as string) ||
    !privateStatePath(request.path) ||
    !privateStatePath(request.cwd) ||
    typeof request.deadline !== 'number' ||
    !Number.isFinite(request.deadline) ||
    request.deadline <= performance.now()
  ) {
    record('request-rejected');
    close();
    return;
  }
  const frame = [
    request.id,
    request.action,
    Buffer.from(request.path).toString('base64'),
    Buffer.from(request.cwd).toString('base64'),
  ].join('\t');
  if (Buffer.byteLength(frame) + 1 > p.frameBytes) {
    close();
    return;
  }
  pending = request.id as number;
  dispatchedAt = performance.now();
  record('native-dispatch', {
    remainingMs: request.deadline - performance.now(),
    action: request.action,
    queueMs: typeof request.sentAt === 'number' ? performance.now() - request.sentAt : undefined,
  });
  timer = setTimeout(
    () => {
      record('execution-deadline');
      close();
    },
    Math.min(p.operationMs, request.deadline - performance.now()),
  );
  child!.stdin.write(frame + '\n');
});
const starting = () => Atomics.load(control, p.state) === p.starting;
try {
  // Compile/protect/check on this Worker too, never on the caller's event loop.
  record('prepare');
  const executable = starting() ? windowsPrivateStateNativeExecutable(starting) : undefined;
  if (!executable || !starting()) gone();
  else {
    // -1 means spawn may have happened; an abrupt Worker exit is not proof of child exit.
    Atomics.store(control, p.pid, -1);
    let startupMs = p.startupMs as number;
    record('spawn-gate');
    child = withWindowsProcessCreation(
      p.startupMs,
      (remaining) => {
        if (!starting()) throw new Error('Private state owner closed');
        startupMs = remaining;
        return spawn(executable, ['serve'], { stdio: 'pipe', windowsHide: true });
      },
      starting,
    );
    Atomics.store(control, p.pid, child.pid ?? 0);
    record('spawned');
    timer = setTimeout(() => {
      record('startup-deadline');
      close();
    }, startupMs);
    child.stdin.on('error', () => {
      record('stdin-error');
      close();
    });
    child.stdout.on('error', () => {
      record('stdout-error');
      close();
    });
    child.stdout.once('end', () => {
      record('stdout-eof');
      close();
    });
    child.stderr.on('error', () => {
      record('stderr-error');
      close();
    });
    // Native stderr contains only fixed failure types; never accumulate arbitrary output.
    child.stderr.on('data', () => {
      record('stderr-present');
      close();
    });
    child.once('error', () => {
      record('spawn-error');
      close();
      if (!child?.pid) gone();
    });
    child.once('exit', (exitCode) => {
      record('native-exit', { exitCode });
      close();
      gone();
    });
    child.stdout.on('data', (chunk: Buffer) => {
      if (closing) {
        record('late-response');
        return;
      }
      if (buffered.length + chunk.length > p.responseBytes) {
        record('oversized-frame');
        close();
        return;
      }
      buffered = Buffer.concat([buffered, chunk]);
      const newline = buffered.indexOf(10);
      if (newline < 0) return;
      // One complete response only. No unsolicited, trailing or combined frames.
      if (newline !== buffered.length - 1) {
        record('trailing-frame');
        close();
        return;
      }
      const line = buffered.subarray(0, newline).toString('ascii');
      if (buffered.some((byte) => byte > 127)) {
        record('non-ascii-frame');
        close();
        return;
      }
      buffered = Buffer.alloc(0);
      if (!ready) {
        if (
          line !== 'PRIVATE_STATE_READY\t1' ||
          Atomics.compareExchange(control, p.state, p.starting, p.ready) !== p.starting
        ) {
          close();
          return;
        }
        clearTimeout(timer);
        ready = true;
        record('ready');
        Atomics.notify(control, p.state);
        return;
      }
      const match =
        /^([1-9][0-9]*)\t(PRIVATE_STATE_OK|UNSAFE_WINDOWS_PRIVATE_STATE:[A-Za-z][A-Za-z0-9]{0,127})$/.exec(
          line,
        );
      if (
        !pending ||
        !match ||
        match[0] !== line ||
        Number(match[1]) !== pending ||
        pending !== Atomics.load(control, p.request) ||
        Atomics.load(control, p.state) !== p.ready ||
        Atomics.load(control, p.result) !== p.waiting
      ) {
        record('uncorrelated-response');
        close();
        return;
      }
      clearTimeout(timer);
      const response = Buffer.from(match[2]!);
      bytes.set(response);
      Atomics.store(control, p.length, response.length);
      Atomics.store(control, p.response, pending);
      record('native-response', {
        elapsedMs: performance.now() - dispatchedAt,
        code: match[2] === 'PRIVATE_STATE_OK' ? 'OK' : 'UNSAFE_WINDOWS_PRIVATE_STATE',
        nativeType: match[2]?.split(':')[1],
      });
      pending = 0;
      Atomics.store(control, p.result, match[2] === 'PRIVATE_STATE_OK' ? p.ok : p.rejected);
      Atomics.notify(control, p.result);
    });
  }
} catch {
  record('prepare-or-spawn-failed');
  close();
}
