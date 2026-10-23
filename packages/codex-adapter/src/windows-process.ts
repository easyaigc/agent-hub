import { Worker, isMainThread, threadId, workerData } from 'node:worker_threads';

const key = Symbol.for('codex-manage.windows-process-gate.v1');
type Registry = {
  shared: SharedArrayBuffer;
  workers: WeakSet<Worker>;
  owners: Map<number, Worker>;
};
const globals = globalThis as typeof globalThis & { [key]?: Registry };
const unavailable = (timeout = false, spawnStarted: false | 'unknown' = false, cause?: unknown) =>
  Object.assign(new Error('Windows process creation unavailable', { cause }), {
    code: timeout ? 'ETIMEDOUT' : 'WINDOWS_PROCESS_GATE_UNAVAILABLE',
    syscall: 'windows-process-creation',
    spawnStarted,
  });

/** Internal workerData only. A worker must never create an isolate-local fallback. */
export function getWindowsProcessGate(): SharedArrayBuffer {
  if (isMainThread)
    return (globals[key] ??= {
      shared: new SharedArrayBuffer(4),
      workers: new WeakSet(),
      owners: new Map(),
    }).shared;
  const shared: unknown = workerData?.windowsProcessGate;
  if (!(shared instanceof SharedArrayBuffer) || shared.byteLength !== 4) throw unavailable();
  return shared;
}

/** Register the actual main-owned Worker before it can be terminated. */
export function registerWindowsProcessWorker(worker: Worker): void {
  if (
    !isMainThread ||
    !(worker instanceof Worker) ||
    !Number.isSafeInteger(worker.threadId) ||
    worker.threadId <= 0 ||
    worker.threadId >= 0x7fffffff
  )
    throw unavailable();
  const shared = getWindowsProcessGate(),
    registry = globals[key]!;
  if (registry.workers.has(worker)) return;
  registry.workers.add(worker);
  const id = worker.threadId;
  const token = id + 1;
  registry.owners.set(id, worker);
  worker.once('exit', () => {
    if (registry.owners.get(id) === worker) registry.owners.delete(id);
    const owner = new Int32Array(shared);
    // Only a confirmed exit releases this exact owner, never a later acquirer.
    if (Atomics.compareExchange(owner, 0, token, 0) === token) Atomics.notify(owner, 0);
  });
}

/** Resolve only an actual main-registered, still-live owner; never historical IDs. */
export function getWindowsProcessWorker(id: number): Worker | undefined {
  if (!isMainThread) throw unavailable();
  const worker = globals[key]?.owners.get(id);
  return worker?.threadId === id ? worker : undefined;
}

/**
 * Local process-creation critical section, not an executable/argv transport.
 * Async producers return their original ChildProcess/Promise immediately: this
 * function intentionally does not await it. Sync producers hold until return.
 */
export function withWindowsProcessCreation<T>(
  timeoutMs: number,
  create: (remainingTimeoutMs: number) => T,
  active?: () => boolean,
): T {
  if (process.platform !== 'win32') {
    if (active && !active()) throw unavailable();
    return create(timeoutMs);
  }
  if (
    !Number.isSafeInteger(timeoutMs) ||
    timeoutMs <= 0 ||
    timeoutMs > 0x7fffffff ||
    threadId >= 0x7fffffff
  )
    throw unavailable();
  const owner = new Int32Array(getWindowsProcessGate()),
    token = threadId + 1;
  const deadline = performance.now() + timeoutMs;
  for (;;) {
    if (active && !active()) throw unavailable();
    const current = Atomics.load(owner, 0);
    if (current === token) throw unavailable(); // Never nest a leaf creation lock.
    const remaining = deadline - performance.now();
    if (remaining <= 0) throw unavailable(true);
    if (current === 0 && Atomics.compareExchange(owner, 0, 0, token) === 0) break;
    // Cancellation can change a different shared buffer while this isolate's
    // event loop is blocked. Poll it without restarting the original deadline.
    Atomics.wait(owner, 0, current, Math.min(50, remaining));
  }
  let invoked = false;
  let value: T | undefined;
  let failure: { error: unknown } | undefined;
  try {
    if (active && !active()) throw unavailable();
    const remaining = Math.min(timeoutMs, Math.floor(deadline - performance.now()));
    if (remaining <= 0) throw unavailable(true);
    invoked = true;
    value = create(remaining);
  } catch (error) {
    failure = { error };
  }
  // Release on both outcomes before returning the original value/exception.
  // Forced isolate termination skips JS cleanup; only its real Worker exit
  // handler may then CAS away this token.
  if (Atomics.compareExchange(owner, 0, token, 0) !== token)
    throw unavailable(false, invoked ? 'unknown' : false, failure?.error);
  Atomics.notify(owner, 0);
  if (failure) throw failure.error;
  return value as T;
}
