import { createWindowsTraceTransport } from './desktop-private-state-native.js';
import { win32 } from 'node:path';
import { createHash } from 'node:crypto';
import {
  installWindowsTraceBackend,
  type WindowsDesktopIdentity,
  type WindowsTraceInstance,
} from './desktop-platform.js';

export type TraceSnapshotItem = { path: string; maximum: number };
export type TraceSnapshotValue = { identity: string; chain: string[]; bytes: Buffer };
export type TraceSnapshotBatch = {
  schemaVersion: 1;
  kind: 'snapshot-many';
  id: number;
  items: (TraceSnapshotItem & { index: number })[];
};
export const traceSnapshotBatchLimits = Object.freeze({
  items: 2,
  maximum: 16_384,
  requestBytes: 48 * 1024,
  responseBytes: 256 * 1024,
  lineCharacters: 100_000,
});
function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function fields(value: Record<string, unknown>, names: string[]): boolean {
  return (
    Object.keys(value).length === names.length && names.every((key) => Object.hasOwn(value, key))
  );
}
function pathDepth(path: string): number {
  let depth = 1;
  for (let at = path; at !== win32.parse(at).root; at = win32.dirname(at)) depth++;
  return depth;
}
function batchPath(path: unknown): path is string {
  return (
    typeof path === 'string' &&
    path.length <= 32767 &&
    /^[a-z]:\\/i.test(path) &&
    !/[/:]/.test(path.slice(2)) &&
    !Array.from(path).some((character) => character.charCodeAt(0) < 32) &&
    path === win32.normalize(path) &&
    !path
      .slice(3)
      .split('\\')
      .some((part) => /[. ]$/.test(part))
  );
}
/** Planning only: an oversized original single read is never retried as a batch. */
export function traceSnapshotBatchFits(items: readonly TraceSnapshotItem[]): boolean {
  if (
    items.length === 0 ||
    items.length > traceSnapshotBatchLimits.items ||
    items.some(
      (item) =>
        !batchPath(item.path) ||
        !Number.isSafeInteger(item.maximum) ||
        item.maximum < 0 ||
        item.maximum > traceSnapshotBatchLimits.maximum,
    )
  )
    return false;
  const request = {
    schemaVersion: 1,
    kind: 'snapshot-many',
    id: 2147483647,
    items: items.map((item, index) => ({ index, path: item.path, maximum: item.maximum })),
  };
  const size = Buffer.byteLength(JSON.stringify(request));
  // Each native chain component is <=101 ASCII bytes; reserve128 plus envelope.
  const response =
    256 +
    items.reduce(
      (sum, item) => sum + 256 + pathDepth(item.path) * 128 + 4 * Math.ceil(item.maximum / 3),
      0,
    );
  return (
    size <= traceSnapshotBatchLimits.requestBytes &&
    2 + 4 * Math.ceil(size / 3) <= traceSnapshotBatchLimits.lineCharacters &&
    response <= traceSnapshotBatchLimits.responseBytes
  );
}
export function encodeTraceSnapshotBatch(value: unknown): string {
  if (
    !record(value) ||
    !fields(value, ['schemaVersion', 'kind', 'id', 'items']) ||
    value.schemaVersion !== 1 ||
    value.kind !== 'snapshot-many' ||
    !Number.isSafeInteger(value.id) ||
    Number(value.id) < 1 ||
    Number(value.id) > 2147483647 ||
    !Array.isArray(value.items) ||
    value.items.some(
      (item, index) =>
        !record(item) || !fields(item, ['index', 'path', 'maximum']) || item.index !== index,
    ) ||
    !traceSnapshotBatchFits(value.items as TraceSnapshotItem[])
  )
    throw new Error('Invalid trace snapshot batch');
  const bytes = Buffer.from(JSON.stringify(value));
  if (bytes.length > traceSnapshotBatchLimits.requestBytes)
    throw new Error('Oversized trace snapshot batch');
  return 'B\t' + bytes.toString('base64');
}
function base64(value: unknown, maximum: number): Buffer {
  if (typeof value !== 'string' || value.length > 4 * Math.ceil(maximum / 3))
    throw new Error('Invalid native batch bytes');
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length > maximum || bytes.toString('base64') !== value)
    throw new Error('Invalid native batch bytes');
  return bytes;
}
/** JSON.parse alone silently overwrites duplicate keys in a native frame. */
export function parseTraceSnapshotBatchJSON(text: string): unknown {
  const value: unknown = JSON.parse(text);
  const objects: Set<string>[] = [];
  for (let at = 0; at < text.length; at++) {
    if (text[at] === '{') objects.push(new Set());
    else if (text[at] === '}') objects.pop();
    else if (text[at] === '"') {
      const start = at++;
      for (; at < text.length && text[at] !== '"'; at++) if (text[at] === '\\') at++;
      let next = at + 1;
      while (/\s/.test(text[next] ?? '') && next < text.length) next++;
      if (text[next] === ':') {
        const key = JSON.parse(text.slice(start, at + 1)) as string;
        const keys = objects.at(-1)!;
        if (keys.has(key)) throw new Error('Duplicate native batch field');
        keys.add(key);
      }
    }
  }
  return value;
}
export function decodeTraceSnapshotBatch(
  value: unknown,
  request: TraceSnapshotBatch,
): TraceSnapshotValue[] {
  if (
    !record(value) ||
    !fields(value, ['schemaVersion', 'kind', 'id', 'items']) ||
    value.schemaVersion !== 1 ||
    value.kind !== 'snapshot-many' ||
    value.id !== request.id ||
    !Array.isArray(value.items) ||
    value.items.length !== request.items.length
  )
    throw new Error('Invalid native batch response');
  return value.items.map((item, index) => {
    if (
      !record(item) ||
      !fields(item, ['index', 'snapshot']) ||
      item.index !== index ||
      !record(item.snapshot) ||
      !fields(item.snapshot, ['identity', 'chain', 'bytes'])
    )
      throw new Error('Invalid native batch item');
    const snapshot = item.snapshot;
    if (
      base64(snapshot.identity, 32).length !== 32 ||
      !Array.isArray(snapshot.chain) ||
      snapshot.chain.length !== pathDepth(request.items[index]!.path) ||
      snapshot.chain.some((part) => {
        if (typeof part !== 'string' || part.length > 128) return true;
        const values = part.split(':');
        if (values.length !== 6) return true;
        for (let at = 0; at < 5; at++) {
          const number = Number(values[at]);
          if (
            !Number.isInteger(number) ||
            String(number) !== values[at] ||
            (at < 3
              ? number < 0 || number > 4294967295
              : number < -2147483648 || number > 2147483647)
          )
            return true;
        }
        return base64(values[5], 32).length !== 32;
      })
    )
      throw new Error('Invalid native batch identity');
    if (
      createHash('sha256').update(snapshot.chain.join('|')).digest('base64') !== snapshot.identity
    )
      throw new Error('Inconsistent native batch identity');
    return {
      identity: snapshot.identity as string,
      chain: snapshot.chain as string[],
      bytes: base64(snapshot.bytes, request.items[index]!.maximum),
    };
  });
}

/** Persistent fixed native operations, synchronously consumed only in a reader thread. */
export async function createWindowsTraceBackend(
  identity: WindowsDesktopIdentity,
  root: string,
  signal?: AbortSignal,
) {
  if (signal?.aborted) throw new Error('Trace native startup closed');
  const shared = new SharedArrayBuffer(48 * 1024 * 1024 + 8);
  const control = new Int32Array(shared, 0, 2),
    bytes = new Uint8Array(shared, 8);
  const transport = createWindowsTraceTransport(identity, root, shared);
  const worker = transport.port;
  let alive = true,
    deadline = Infinity;
  let rejectStartup: ((error: Error) => void) | undefined;
  function close(): void {
    alive = false;
    Atomics.store(control, 0, -1);
    Atomics.notify(control, 0);
    signal?.removeEventListener('abort', close);
    transport.close();
    rejectStartup?.(new Error('Trace native startup closed'));
  }
  signal?.addEventListener('abort', close, { once: true });
  worker.on('error', close);
  worker.once('close', close);
  await new Promise<void>((resolve, reject) => {
    rejectStartup = (error) => {
      clearTimeout(timer);
      rejectStartup = undefined;
      reject(error);
    };
    const timer = setTimeout(() => {
      close();
    }, 30000);
    worker.once('message', (value) => {
      clearTimeout(timer);
      if (alive && !signal?.aborted && value.kind === 'ready') {
        rejectStartup = undefined;
        resolve();
      } else close();
    });
    if (signal?.aborted) close();
  });
  worker.unref();
  if (!alive || signal?.aborted) {
    close();
    throw new Error('Trace native startup closed');
  }
  function call(
    kind: 'snapshot' | 'probe' | 'tokens' | 'snapshot-many',
    path?: string,
    maximum?: number,
    batch?: TraceSnapshotBatch,
  ) {
    if (!alive || performance.now() >= deadline) throw new Error('Trace read budget exhausted');
    Atomics.store(control, 0, 0);
    worker.postMessage(batch ?? { kind, path, maximum });
    const status = Atomics.wait(control, 0, 0, Math.min(1000, deadline - performance.now()));
    if (status === 'timed-out' || Atomics.load(control, 0) !== 1) {
      close();
      throw new Error('Trace native operation unavailable');
    }
    const text = Buffer.from(bytes.subarray(0, Atomics.load(control, 1))).toString('utf8');
    const value = kind === 'snapshot-many' ? parseTraceSnapshotBatchJSON(text) : JSON.parse(text);
    if (record(value) && value.error) throw new Error('Trace native operation rejected');
    return value;
  }
  let nextBatch = 0;
  const backend = {
    snapshotMany(requestedRoot: string, items: readonly TraceSnapshotItem[]): TraceSnapshotValue[] {
      if (requestedRoot !== root || nextBatch === 2147483647)
        throw new Error('Trace batch scope changed');
      const request: TraceSnapshotBatch = {
        schemaVersion: 1,
        kind: 'snapshot-many',
        id: ++nextBatch,
        items: items.map((item, index) => ({ index, path: item.path, maximum: item.maximum })),
      };
      encodeTraceSnapshotBatch(request);
      try {
        return decodeTraceSnapshotBatch(
          call('snapshot-many', undefined, undefined, request),
          request,
        );
      } catch (error) {
        close();
        throw error;
      }
    },
    snapshot(requestedRoot: string, path: string, maximum = 0) {
      if (requestedRoot !== root) throw new Error('Trace root changed');
      const value = call('snapshot', path, maximum);
      if (
        typeof value.identity !== 'string' ||
        !Array.isArray(value.chain) ||
        value.chain.some((v: unknown) => typeof v !== 'string') ||
        typeof value.bytes !== 'string'
      )
        throw new Error('Invalid native trace response');
      return {
        identity: value.identity,
        chain: value.chain as string[],
        bytes: Buffer.from(value.bytes, 'base64'),
      };
    },
    inspect: () => call('probe') as WindowsTraceInstance,
  };
  installWindowsTraceBackend(backend);
  return {
    backend,
    budget: (milliseconds: number) => {
      deadline = performance.now() + milliseconds;
    },
    resetBudget: () => {
      deadline = Infinity;
    },
    tokens: () => call('tokens'),
    close,
  };
}
