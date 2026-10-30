import type { WindowsTraceDiagnostics } from './windows-trace-diagnostics.js';
import {
  createWindowsTraceTransport,
  assertWindowsTraceProducerScope,
  windowsTraceProducerPath,
  type WindowsTraceProducerScope,
} from './desktop-private-state-native.js';
import { win32 } from 'node:path';
import { createHash } from 'node:crypto';
import {
  installWindowsTraceBackend,
  type WindowsDesktopIdentity,
  type WindowsTraceInstance,
} from './desktop-platform.js';

export type TraceSnapshotItem = { path: string; maximum: number };
export type { WindowsTraceProducerScope } from './desktop-private-state-native.js';
export type TraceLogRangeRequest = {
  kind: 'log-range';
  path: string;
  offset: number;
  count: number;
};
export type TraceLogRangeValue = TraceSnapshotValue & { size: number };
export const traceLogRangeLimits = Object.freeze({
  maximum: 1024 * 1024,
  responseBytes: 2 * 1024 * 1024,
});
export type TraceProducerRequest = { kind: 'producer'; ProducerPid: number; Config: string };
export type TraceProducerIdentity = {
  pid: number;
  desktopPid: number;
  desktopStartedAt: number;
  wrapperStartedAt: number;
};
export const traceProducerLimits = Object.freeze({
  requestBytes: 48 * 1024,
  responseBytes: 256,
  lineCharacters: 100_000,
});
export type TraceSnapshotValue = { identity: string; chain: string[]; bytes: Buffer };
export type TraceSnapshotBatch = {
  schemaVersion: 1;
  kind: 'snapshot-many';
  id: number;
  items: (TraceSnapshotItem & { index: number })[];
};
export const traceSnapshotBatchLimits = Object.freeze({
  items: 64,
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

export function traceProducerPathWithin(root: string, path: string): boolean {
  if (!windowsTraceProducerPath(root) || !windowsTraceProducerPath(path)) return false;
  const base = root.toLowerCase(),
    child = path.toLowerCase();
  return child === base || child.startsWith(base + '\\');
}

/** The native producer fixes the log root and desktop PID at startup. */
export function encodeTraceLogRangeRequest(value: unknown): string {
  if (
    !record(value) ||
    !fields(value, ['kind', 'path', 'offset', 'count']) ||
    value.kind !== 'log-range' ||
    !windowsTraceProducerPath(value.path) ||
    !Number.isSafeInteger(value.offset) ||
    Number(value.offset) < 0 ||
    Object.is(value.offset, -0) ||
    !Number.isSafeInteger(value.count) ||
    Number(value.count) < 0 ||
    Object.is(value.count, -0) ||
    Number(value.count) > traceLogRangeLimits.maximum ||
    !Number.isSafeInteger(Number(value.offset) + Number(value.count))
  )
    throw new Error('Invalid native log range request');
  return (
    'L\t' + Buffer.from(value.path).toString('base64') + '\t' + value.offset + '\t' + value.count
  );
}
/** Fixed internal action. No method names, command lines or extra fields cross this boundary. */
export function encodeTraceProducerRequest(value: unknown): string {
  if (
    !record(value) ||
    !fields(value, ['kind', 'ProducerPid', 'Config']) ||
    value.kind !== 'producer' ||
    !Number.isSafeInteger(value.ProducerPid) ||
    Number(value.ProducerPid) < 1 ||
    Number(value.ProducerPid) > 2147483647 ||
    !batchPath(value.Config) ||
    value.Config.endsWith('\\') ||
    value.Config.slice(3)
      .split('\\')
      .some(
        (part) =>
          !part ||
          /[<>"|?*]/.test(part) ||
          /~[0-9]+(?:\.|$)/.test(part) ||
          /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part),
      ) ||
    win32.basename(value.Config) !== 'config.json'
  )
    throw new Error('Invalid native producer request');
  const bytes = Buffer.from(JSON.stringify(value));
  const command = 'I\t' + bytes.toString('base64');
  if (
    bytes.length > traceProducerLimits.requestBytes ||
    command.length > traceProducerLimits.lineCharacters
  )
    throw new Error('Oversized native producer request');
  return command;
}
export function decodeTraceProducerIdentity(
  value: unknown,
  request: TraceProducerRequest,
  desktop: WindowsDesktopIdentity,
): TraceProducerIdentity {
  if (
    !record(value) ||
    !fields(value, ['pid', 'desktopPid', 'desktopStartedAt', 'wrapperStartedAt']) ||
    value.pid !== request.ProducerPid ||
    value.desktopPid !== desktop.pid ||
    value.pid === value.desktopPid ||
    value.desktopStartedAt !== desktop.startedAtUnixMs ||
    !Number.isSafeInteger(value.pid) ||
    Number(value.pid) < 1 ||
    Number(value.pid) > 2147483647 ||
    !Number.isSafeInteger(value.desktopPid) ||
    Number(value.desktopPid) < 1 ||
    Number(value.desktopPid) > 2147483647 ||
    !Number.isSafeInteger(value.desktopStartedAt) ||
    Number(value.desktopStartedAt) < 1 ||
    !Number.isSafeInteger(value.wrapperStartedAt) ||
    Number(value.wrapperStartedAt) < Number(value.desktopStartedAt) ||
    Number(value.wrapperStartedAt) > Date.now()
  )
    throw new Error('Invalid native producer identity');
  return value as TraceProducerIdentity;
}
/** Request-local planning only; every dispatched frame is validated again. */
export function createTraceSnapshotBatchPlanner(): { add(item: TraceSnapshotItem): boolean } {
  let count = 0;
  let requestBytes = Buffer.byteLength(
    JSON.stringify({ schemaVersion: 1, kind: 'snapshot-many', id: 2147483647, items: [] }),
  );
  let responseBytes = 256;
  return {
    add(item) {
      if (
        count === traceSnapshotBatchLimits.items ||
        !batchPath(item.path) ||
        !Number.isSafeInteger(item.maximum) ||
        item.maximum < 0 ||
        item.maximum > traceSnapshotBatchLimits.maximum
      )
        return false;
      // Serialize only this row. Rebuilding all previous rows for every append
      // made file/directory/commit planning quadratic in the batch width.
      const nextRequestBytes =
        requestBytes +
        Buffer.byteLength(
          JSON.stringify({ index: count, path: item.path, maximum: item.maximum }),
        ) +
        (count > 0 ? 1 : 0);
      // Identical conservative chain/body bound to the native protocol.
      const nextResponseBytes =
        responseBytes + 256 + pathDepth(item.path) * 128 + 4 * Math.ceil(item.maximum / 3);
      if (
        nextRequestBytes > traceSnapshotBatchLimits.requestBytes ||
        2 + 4 * Math.ceil(nextRequestBytes / 3) > traceSnapshotBatchLimits.lineCharacters ||
        nextResponseBytes > traceSnapshotBatchLimits.responseBytes
      )
        return false;
      count++;
      requestBytes = nextRequestBytes;
      responseBytes = nextResponseBytes;
      return true;
    },
  };
}

/** Planning only: an oversized original single read is never retried as a batch. */
export function traceSnapshotBatchFits(items: readonly TraceSnapshotItem[]): boolean {
  const planner = createTraceSnapshotBatchPlanner();
  return items.length > 0 && items.every((item) => planner.add(item));
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

/** Strict UTF-8 plus the existing duplicate-key parser for private producer frames. */
export function parseTraceProducerJSON(bytes: Buffer): unknown {
  const text = bytes.toString('utf8');
  if (!Buffer.from(text).equals(bytes)) throw new Error('Invalid native producer UTF-8');
  return parseTraceSnapshotBatchJSON(text);
}

function decodeTraceIdentity(value: Record<string, unknown>, path: string, log = false): void {
  const width = log ? 8 : 6;
  if (
    base64(value.identity, 32).length !== 32 ||
    !Array.isArray(value.chain) ||
    value.chain.length !== pathDepth(path) ||
    value.chain.some((part) => {
      if (typeof part !== 'string' || part.length > 128) return true;
      const values = part.split(':');
      if (values.length !== width) return true;
      for (let at = 0; at < width - 1; at++) {
        const number = Number(values[at]);
        if (
          !Number.isInteger(number) ||
          String(number) !== values[at] ||
          (at === 3 || at === 4
            ? number < -2147483648 || number > 2147483647
            : number < 0 || number > 4294967295) ||
          (log && at === 5 && number < 1)
        )
          return true;
      }
      return base64(values[width - 1], 32).length !== 32;
    })
  )
    throw new Error('Invalid native batch identity');
  if (createHash('sha256').update(value.chain.join('|')).digest('base64') !== value.identity)
    throw new Error('Inconsistent native batch identity');
}

export function decodeTraceProducerSnapshot(
  value: unknown,
  path: string,
  maximum: number,
): TraceSnapshotValue {
  if (!record(value) || !fields(value, ['identity', 'chain', 'bytes']))
    throw new Error('Invalid native producer snapshot');
  decodeTraceIdentity(value, path);
  return {
    identity: value.identity as string,
    chain: value.chain as string[],
    bytes: base64(value.bytes, maximum),
  };
}

export function decodeTraceLogRange(
  value: unknown,
  request: TraceLogRangeRequest,
): TraceLogRangeValue {
  encodeTraceLogRangeRequest(request);
  if (
    !record(value) ||
    !fields(value, ['identity', 'chain', 'bytes', 'size']) ||
    !Number.isSafeInteger(value.size) ||
    Number(value.size) < request.offset ||
    Object.is(value.size, -0)
  )
    throw new Error('Invalid native log range response');
  decodeTraceIdentity(value, request.path, true);
  const bytes = base64(value.bytes, request.count);
  if (bytes.length !== Math.min(request.count, Number(value.size) - request.offset))
    throw new Error('Incomplete native log range');
  return {
    identity: value.identity as string,
    chain: value.chain as string[],
    bytes,
    size: value.size as number,
  };
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
    return decodeTraceProducerSnapshot(
      item.snapshot,
      request.items[index]!.path,
      request.items[index]!.maximum,
    );
  });
}

// The original snapshot projection, shared only with the diagnostic timing boundary.
function decodeSnapshotValue(raw: unknown): TraceSnapshotValue {
  const value = raw as { identity: unknown; chain: unknown; bytes: unknown };
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
}

/** Persistent fixed native operations, synchronously consumed only in a reader thread. */
/** Start each bounded phase with one native parallel wave, without retrying a frame. */
export function createTraceSnapshotBatchSender(
  root: string,
  send: (request: TraceSnapshotBatch) => TraceSnapshotValue[],
  retire: () => void,
) {
  let nextId = 0;
  let parallelReady = false;
  let retired = false;
  return {
    beginPhase() {
      parallelReady = false;
    },
    read(requestedRoot: string, items: readonly TraceSnapshotItem[]): TraceSnapshotValue[] {
      if (retired || requestedRoot !== root || nextId === 2147483647)
        throw new Error('Trace batch scope changed');
      const original: TraceSnapshotBatch = {
        schemaVersion: 1,
        kind: 'snapshot-many',
        id: nextId + 1,
        items: items.map((item, index) => ({ index, path: item.path, maximum: item.maximum })),
      };
      // Validate every item before even a cold prefix can reach the native child.
      encodeTraceSnapshotBatch(original);
      const chunks =
        !parallelReady && original.items.length > 8
          ? [original.items.slice(0, 8), original.items.slice(8)]
          : [original.items];
      if (nextId > 2147483647 - chunks.length) throw new Error('Trace batch scope changed');
      const values: TraceSnapshotValue[] = [];
      try {
        for (const chunk of chunks) {
          const request: TraceSnapshotBatch = {
            ...original,
            id: ++nextId,
            items: chunk.map((item, index) => ({ ...item, index })),
          };
          encodeTraceSnapshotBatch(request);
          const result = send(request);
          if (result.length !== request.items.length) throw new Error('Incomplete native batch');
          values.push(...result);
          if (request.items.length > 1) parallelReady = true;
        }
        return values;
      } catch (error) {
        retired = true;
        retire();
        throw error;
      }
    },
  };
}

export async function createWindowsTraceBackend(
  identity: WindowsDesktopIdentity,
  root: string,
  signal?: AbortSignal,
  diagnostics?: () => WindowsTraceDiagnostics,
  producerScope?: WindowsTraceProducerScope,
) {
  if (signal?.aborted) throw new Error('Trace native startup closed');
  if (producerScope !== undefined) assertWindowsTraceProducerScope(producerScope, root);
  const scope = producerScope === undefined ? undefined : Object.freeze({ ...producerScope });
  const desktop = scope ? Object.freeze({ ...identity }) : identity;
  const configRoot = scope ? win32.dirname(scope.configPath) : undefined;
  const shared = new SharedArrayBuffer(48 * 1024 * 1024 + 8);
  const control = new Int32Array(shared, 0, 2),
    bytes = new Uint8Array(shared, 8);
  const transport = createWindowsTraceTransport(desktop, root, shared, scope);
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
    kind: 'snapshot' | 'probe' | 'tokens' | 'snapshot-many' | 'producer' | 'log-range',
    path?: string,
    maximum?: number,
    batch?: TraceSnapshotBatch,
    producer?: TraceProducerRequest,
    logRange?: TraceLogRangeRequest,
  ) {
    const diagnostic = diagnostics?.();
    if (signal?.aborted) close();
    if (!alive || performance.now() >= deadline) {
      diagnostic?.end(!alive ? 'transport_lost' : 'phase_deadline', {
        deadlineKind: !alive ? 'unknown' : 'native_budget',
      });
      if (scope) close();
      throw new Error('Trace read budget exhausted');
    }
    const sample =
      kind === 'log-range'
        ? undefined
        : diagnostic?.nativeCall(
            kind === 'probe' || kind === 'producer'
              ? 'P'
              : kind === 'snapshot'
                ? 'S'
                : kind === 'tokens'
                  ? 'T'
                  : 'B',
            batch?.items.length ?? 0,
          );
    let completed = false;
    try {
      Atomics.store(control, 0, 0);
      worker.postMessage(
        logRange ??
          producer ??
          batch ??
          (scope && (kind === 'probe' || kind === 'tokens') ? { kind } : { kind, path, maximum }),
      );
      const status = Atomics.wait(control, 0, 0, Math.min(1000, deadline - performance.now()));
      sample?.waited();
      if (
        status === 'timed-out' ||
        Atomics.load(control, 0) !== 1 ||
        (scope && performance.now() >= deadline)
      ) {
        diagnostic?.end(status === 'timed-out' ? 'phase_deadline' : 'transport_lost', {
          deadlineKind: performance.now() >= deadline ? 'native_budget' : 'native_response',
        });
        close();
        throw new Error('Trace native operation unavailable');
      }
      sample?.decoding();
      const length = Atomics.load(control, 1);
      if (
        length < 1 ||
        length > bytes.length ||
        (producer && length > traceProducerLimits.responseBytes) ||
        (logRange && length > traceLogRangeLimits.responseBytes)
      ) {
        close();
        throw new Error('Invalid native response length');
      }
      const raw = Buffer.from(bytes.subarray(0, length));
      const value = scope
        ? parseTraceProducerJSON(raw)
        : kind === 'snapshot-many' || producer
          ? parseTraceSnapshotBatchJSON(raw.toString('utf8'))
          : JSON.parse(raw.toString('utf8'));
      if (record(value) && value.error) throw new Error('Trace native operation rejected');
      const result = logRange
        ? decodeTraceLogRange(value, logRange)
        : producer
          ? decodeTraceProducerIdentity(value, producer, desktop)
          : kind === 'snapshot-many'
            ? decodeTraceSnapshotBatch(value, batch!)
            : kind === 'snapshot'
              ? scope
                ? decodeTraceProducerSnapshot(value, path!, maximum!)
                : decodeSnapshotValue(value)
              : value;
      if (scope && performance.now() >= deadline) throw new Error('Trace read budget exhausted');
      sample?.decoded();
      completed = true;
      return result;
    } catch (error) {
      if (scope || producer) close();
      throw error;
    } finally {
      sample?.end(completed);
    }
  }
  const batches = createTraceSnapshotBatchSender(
    root,
    (request) => call('snapshot-many', undefined, undefined, request) as TraceSnapshotValue[],
    close,
  );
  const backend = {
    snapshotMany(requestedRoot: string, items: readonly TraceSnapshotItem[]): TraceSnapshotValue[] {
      if (
        scope &&
        (requestedRoot !== root || items.some((item) => !traceProducerPathWithin(root, item.path)))
      ) {
        close();
        throw new Error('Trace batch scope changed');
      }
      return batches.read(requestedRoot, items);
    },
    snapshot(requestedRoot: string, path: string, maximum = 0) {
      if (scope) {
        if (
          (requestedRoot !== root && requestedRoot !== configRoot) ||
          !traceProducerPathWithin(requestedRoot, path) ||
          !Number.isSafeInteger(maximum) ||
          maximum < 0 ||
          maximum > 32 * 1024 * 1024
        ) {
          close();
          throw new Error('Trace producer snapshot scope changed');
        }
      } else if (requestedRoot !== root) throw new Error('Trace root changed');
      return call('snapshot', path, maximum) as TraceSnapshotValue;
    },
    inspect: () => call('probe') as WindowsTraceInstance,
    inspectProducer(pid: number, configPath: string): TraceProducerIdentity {
      if (scope && (pid !== scope.producerPid || configPath !== scope.configPath)) {
        close();
        throw new Error('Trace producer identity scope changed');
      }
      const request: TraceProducerRequest = {
        kind: 'producer',
        ProducerPid: pid,
        Config: configPath,
      };
      encodeTraceProducerRequest(request);
      return call('producer', undefined, undefined, undefined, request) as TraceProducerIdentity;
    },
    ...(scope
      ? {
          logRange(
            requestedRoot: string,
            path: string,
            offset: number,
            count: number,
            desktopPid: number,
          ) {
            const request: TraceLogRangeRequest = { kind: 'log-range', path, offset, count };
            try {
              if (
                requestedRoot !== scope.logRoot ||
                desktopPid !== desktop.pid ||
                !traceProducerPathWithin(scope.logRoot, path)
              )
                throw new Error('Trace producer log scope changed');
              encodeTraceLogRangeRequest(request);
              return call(
                'log-range',
                undefined,
                undefined,
                undefined,
                undefined,
                request,
              ) as TraceLogRangeValue;
            } catch (error) {
              close();
              throw error;
            }
          },
        }
      : {}),
  };
  installWindowsTraceBackend(backend);
  return {
    backend,
    budget: (milliseconds: number) => {
      deadline = performance.now() + milliseconds;
      batches.beginPhase();
    },
    resetBudget: () => {
      deadline = Infinity;
    },
    tokens: () => call('tokens'),
    close,
  };
}

/** Private sampler only; caller must be the actual main-registered owner Worker. */
export function createWindowsTraceProducerBackend(
  identity: WindowsDesktopIdentity,
  root: string,
  configPath: string,
  logRoot: string,
  signal?: AbortSignal,
) {
  return createWindowsTraceBackend(identity, root, signal, undefined, {
    producerPid: process.pid,
    configPath,
    logRoot,
  });
}
