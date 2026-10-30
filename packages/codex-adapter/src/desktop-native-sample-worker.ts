import { closeSync, fstatSync, lstatSync, openSync, readSync, realpathSync } from 'node:fs';
import { isAbsolute } from 'node:path';
import { parentPort, workerData, isMainThread } from 'node:worker_threads';
import { TextDecoder } from 'node:util';
import {
  createDesktopNativeSampleWorkerBridge,
  type DesktopNativeSampleBridge,
} from './desktop-native-sample-bridge.js';
import { nativeSamplerProtocol } from './desktop-native-sample-forwarding.js';
import { DesktopNativeSampleFrames } from './desktop-native-sample-framing.js';
import { closeWindowsPrivateStateBackend, inspectWindowsPipe } from './desktop-platform.js';
import { createWindowsTraceProducerBackend } from './desktop-trace-native.js';
import { nativeSuggestionImplementationFingerprint } from './desktop-native-sample-platform.js';

if (
  isMainThread ||
  process.platform !== 'win32' ||
  !parentPort ||
  workerData?.kind !== 'windows-native-sample' ||
  workerData.entryURL !== import.meta.url ||
  typeof workerData.configPath !== 'string' ||
  !isAbsolute(workerData.configPath) ||
  !(workerData.control instanceof SharedArrayBuffer) ||
  workerData.control.byteLength !== 4 ||
  typeof workerData.startupDeadline !== 'bigint'
)
  throw new Error('Fixed Windows sampler isolate required');
// Freeze the loaded implementation before any asynchronous native startup wait.
nativeSuggestionImplementationFingerprint();
const port = parentPort;
const control = new Int32Array(workerData.control);
const configPath: string = workerData.configPath;
const startupDeadline: bigint = workerData.startupDeadline;
const active = () => !closed && Atomics.load(control, 0) === 0;
const remaining = () => Number((startupDeadline - process.hrtime.bigint()) / 1_000_000n);
const abort = new AbortController();
let closed = false,
  started = false,
  ready = false,
  begun = false,
  sequence = 0;
let bridge: DesktopNativeSampleBridge | undefined;
let native: Awaited<ReturnType<typeof createWindowsTraceProducerBackend>> | undefined;
const decoder = new TextDecoder('utf-8', { fatal: true });
const frames = new DesktopNativeSampleFrames((direction, line) => {
  if (!active()) throw new Error('Sampler closed');
  if (direction === 'outbound') bridge!.observeOutbound(line);
  else bridge!.observeInbound(line);
  if (!bridge?.available) throw new Error('Sampler unavailable');
});
const timer = setTimeout(() => finish(), Math.max(1, Math.min(remaining(), 30_000)));

function fields(value: unknown, keys: string[]): value is Record<string, unknown> {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key))
  );
}
function finish(): void {
  if (closed) return;
  closed = true;
  clearTimeout(timer);
  Atomics.store(control, 0, 1);
  try {
    bridge?.close();
  } finally {
    abort.abort();
    native?.close();
    closeWindowsPrivateStateBackend();
    frames.clear();
    try {
      port.postMessage({ kind: 'unavailable' });
    } catch {
      /* Parent exit is not a reason to retain private frames or restart. */
    }
    port.close();
  }
}

/** Hints only. The native ProducerScope independently rereads and authenticates
 * the private configuration, real process chain and both roots before ready. */
function bootstrapPaths() {
  const before = lstatSync(configPath);
  if (
    !before.isFile() ||
    before.isSymbolicLink() ||
    before.nlink !== 1 ||
    before.size < 1 ||
    before.size > 16_384 ||
    realpathSync(configPath) !== configPath
  )
    throw new Error('Sampler configuration unavailable');
  const fd = openSync(configPath, 'r');
  try {
    const stat = fstatSync(fd);
    if (
      !stat.isFile() ||
      stat.nlink !== 1 ||
      stat.dev !== before.dev ||
      stat.ino !== before.ino ||
      stat.size !== before.size
    )
      throw new Error('Sampler configuration changed');
    const storage = Buffer.alloc(16_385);
    let length = 0;
    while (length < storage.length) {
      const count = readSync(fd, storage, length, storage.length - length, length);
      if (!count) break;
      length += count;
    }
    if (length > 16_384) throw new Error('Sampler configuration limit');
    const bytes = storage.subarray(0, length);
    const after = fstatSync(fd),
      current = lstatSync(configPath);
    if (
      bytes.length !== stat.size ||
      after.dev !== stat.dev ||
      after.ino !== stat.ino ||
      after.size !== stat.size ||
      after.mtimeMs !== stat.mtimeMs ||
      current.dev !== stat.dev ||
      current.ino !== stat.ino ||
      current.nlink !== 1 ||
      current.isSymbolicLink() ||
      realpathSync(configPath) !== configPath
    )
      throw new Error('Sampler configuration changed');
    const config = JSON.parse(decoder.decode(bytes)) as {
      traceRoot?: unknown;
      logDirectory?: unknown;
    };
    if (
      typeof config.traceRoot !== 'string' ||
      typeof config.logDirectory !== 'string' ||
      !isAbsolute(config.traceRoot) ||
      !isAbsolute(config.logDirectory)
    )
      throw new Error('Sampler roots unavailable');
    return { traceRoot: config.traceRoot, logRoot: config.logDirectory };
  } finally {
    closeSync(fd);
  }
}

async function start() {
  if (!active() || remaining() <= 0) throw new Error('Sampler startup closed');
  const paths = bootstrapPaths();
  if (!active() || remaining() <= 0) throw new Error('Sampler startup closed');
  // Start is sent only after original child bytes have already been forwarded.
  // One real inspection shares the original, unextended startup deadline.
  const identity = inspectWindowsPipe(Math.max(1, Math.floor(remaining())));
  if (!active() || remaining() <= 0) throw new Error('Sampler startup closed');
  native = await createWindowsTraceProducerBackend(
    identity,
    paths.traceRoot,
    configPath,
    paths.logRoot,
    abort.signal,
  );
  if (!active() || remaining() <= 0) throw new Error('Sampler startup closed');
  bridge = createDesktopNativeSampleWorkerBridge(
    configPath,
    {
      active,
      startupRemaining: remaining,
      begin: (milliseconds) => {
        // Bridge phases check active before data access. Its separate bounded
        // retirement may still mark this exact epoch unavailable after close.
        native!.budget(milliseconds);
      },
      end: () => native?.resetBudget(),
    },
    (code) => {
      port.postMessage({ kind: 'status', code });
      queueMicrotask(() => {
        if (!bridge?.available) finish();
      });
    },
  );
  if (!active() || !bridge?.available) throw new Error('Sampler unavailable');
  clearTimeout(timer);
  ready = true;
  port.postMessage({ kind: 'ready' });
}

port.on('message', (value: unknown) => {
  if (closed) return;
  try {
    if (fields(value, ['kind']) && value.kind === 'close') finish();
    else if (fields(value, ['kind']) && value.kind === 'start' && !started) {
      started = true;
      void start().catch(() => finish());
    } else if (
      fields(value, ['kind', 'outboundBoundary', 'inboundBoundary']) &&
      value.kind === 'begin' &&
      ready &&
      !begun &&
      typeof value.outboundBoundary === 'boolean' &&
      typeof value.inboundBoundary === 'boolean'
    ) {
      frames.begin({ outbound: value.outboundBoundary, inbound: value.inboundBoundary });
      begun = true;
    } else if (
      fields(value, ['kind', 'direction', 'sequence', 'bytes']) &&
      value.kind === 'bytes' &&
      ready &&
      begun &&
      (value.direction === 'outbound' || value.direction === 'inbound') &&
      value.sequence === sequence + 1 &&
      value.bytes instanceof Uint8Array &&
      value.bytes.byteLength > 0 &&
      value.bytes.byteLength <= nativeSamplerProtocol.queuedBytes
    ) {
      sequence++;
      if (!active()) throw new Error('Sampler closed');
      frames.push(value.direction, value.bytes);
      if (!active()) throw new Error('Sampler closed');
      port.postMessage({ kind: 'ack', sequence, size: value.bytes.byteLength });
    } else finish();
  } catch {
    finish();
  }
});
port.once('close', finish);
