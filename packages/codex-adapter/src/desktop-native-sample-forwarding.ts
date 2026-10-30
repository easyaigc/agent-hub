import type { Readable, Writable } from 'node:stream';
import { Worker } from 'node:worker_threads';
import { retireWindowsNativeOwner } from './desktop-private-state-native.js';
import { registerWindowsProcessWorker } from './windows-process.js';

export const nativeSamplerProtocol = Object.freeze({
  queuedBytes: 32 * 1024 * 1024,
  queuedMessages: 2048,
  startupMs: 30_000,
  closeMs: 32_000,
});

type OriginalStreams = {
  input: Readable;
  childInput: Writable;
  childOutput: Readable;
  output: Writable;
};

function fields(value: unknown, keys: string[]): value is Record<string, unknown> {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key))
  );
}

/** Original pipes never wait for sampler startup, native I/O, credits or retirement. */
export function forwardOriginalDesktopWithNativeSampler(
  streams: OriginalStreams,
  startSampler: () => { worker: Worker; shared: SharedArrayBuffer },
) {
  streams.input.pipe(streams.childInput);
  streams.childOutput.pipe(streams.output);
  let sampler: { worker: Worker; shared: SharedArrayBuffer } | undefined;
  try {
    sampler = startSampler();
    if (
      !(sampler.worker instanceof Worker) ||
      !(sampler.shared instanceof SharedArrayBuffer) ||
      sampler.shared.byteLength !== 4
    )
      throw new Error('Fixed native sampler required');
    registerWindowsProcessWorker(sampler.worker);
  } catch {
    process.stderr.write('native_sampler_startup_failed\n');
    // Startup failure never retracts or duplicates the already-established pipes.
    if (sampler?.worker instanceof Worker) {
      sampler.worker.on('error', () => {});
      try {
        sampler.worker.postMessage({ kind: 'close' });
      } catch {
        /* The fixed worker may already have exited. No replacement is started. */
      }
    }
    return { close: () => Promise.resolve(false) };
  }
  const { worker, shared } = sampler;
  const control = new Int32Array(shared);
  let closed = false,
    started = false,
    ready = false,
    exited = false,
    sequence = 0,
    acknowledged = 0,
    queuedBytes = 0;
  const boundary = { outbound: true, inbound: true };
  const pending = new Map<number, number>();
  let retirement: Promise<boolean> | undefined;
  let resolveExit: () => void;
  const exit = new Promise<void>((resolve) => {
    resolveExit = resolve;
  });
  const close = (): Promise<boolean> => {
    if (retirement) return retirement;
    closed = true;
    ready = false;
    Atomics.store(control, 0, 1);
    Atomics.notify(control, 0);
    streams.input.off('data', outbound);
    streams.childOutput.off('data', inbound);
    pending.clear();
    queuedBytes = 0;
    try {
      worker.postMessage({ kind: 'close' });
    } catch {
      // Actual exit and native-child retirement remain authoritative.
    }
    retirement = (async () => {
      let timer: NodeJS.Timeout | undefined;
      await Promise.race([
        exit,
        new Promise<void>((resolve) => {
          timer = setTimeout(resolve, nativeSamplerProtocol.closeMs);
        }),
      ]);
      clearTimeout(timer);
      const childrenGone = await retireWindowsNativeOwner(worker);
      // Unknown exit is retained. Never start a replacement or replay bytes.
      return exited && childrenGone;
    })();
    return retirement;
  };
  const send = (direction: 'outbound' | 'inbound', chunk: unknown) => {
    if (closed) return;
    if (!(chunk instanceof Uint8Array)) {
      void close();
      return;
    }
    if (!chunk.byteLength) return;
    boundary[direction] = chunk[chunk.byteLength - 1] === 10;
    if (direction === 'inbound' && !started) {
      // The original initialize exchange has already reached the desktop.
      // Native inspection must not become a prerequisite for that exchange.
      started = true;
      try {
        worker.postMessage({ kind: 'start' });
      } catch {
        void close();
      }
    }
    if (!ready || closed) return;
    if (
      sequence >= Number.MAX_SAFE_INTEGER ||
      pending.size >= nativeSamplerProtocol.queuedMessages ||
      chunk.byteLength > nativeSamplerProtocol.queuedBytes - queuedBytes
    ) {
      void close();
      return;
    }
    const id = ++sequence;
    pending.set(id, chunk.byteLength);
    queuedBytes += chunk.byteLength;
    try {
      // Transfer only our own copy. The original pipe buffer remains untouched,
      // including when an internal caller supplies a shared-backed byte view.
      const bytes = new Uint8Array(chunk.byteLength);
      bytes.set(chunk);
      worker.postMessage({ kind: 'bytes', direction, sequence: id, bytes }, [bytes.buffer]);
    } catch {
      void close();
    }
  };
  const outbound = (chunk: unknown) => send('outbound', chunk);
  const inbound = (chunk: unknown) => send('inbound', chunk);
  streams.input.on('data', outbound);
  streams.childOutput.on('data', inbound);
  worker.on('message', (value: unknown) => {
    if (closed) return;
    if (fields(value, ['kind']) && value.kind === 'ready' && started && !ready) {
      try {
        worker.postMessage({
          kind: 'begin',
          outboundBoundary: boundary.outbound,
          inboundBoundary: boundary.inbound,
        });
        ready = true;
      } catch {
        void close();
      }
    } else if (
      fields(value, ['kind', 'sequence', 'size']) &&
      value.kind === 'ack' &&
      value.sequence === acknowledged + 1 &&
      pending.has(value.sequence as number) &&
      Number.isSafeInteger(value.size) &&
      (value.size as number) > 0 &&
      pending.get(value.sequence as number) === value.size
    ) {
      acknowledged++;
      queuedBytes -= pending.get(acknowledged)!;
      pending.delete(acknowledged);
    } else if (
      fields(value, ['kind', 'code']) &&
      value.kind === 'status' &&
      typeof value.code === 'string' &&
      /^native_bridge_[a-z_]{1,64}$/.test(value.code)
    ) {
      process.stderr.write(value.code + '\n');
    } else if (fields(value, ['kind']) && value.kind === 'unavailable') {
      void close();
    } else {
      void close();
    }
  });
  worker.on('error', () => {
    process.stderr.write('native_sampler_unavailable\n');
    void close();
  });
  worker.once('exit', () => {
    exited = true;
    resolveExit();
    void close();
  });
  return { close };
}
