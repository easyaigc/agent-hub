// Opt-in isolated-DEV preload. Starts no service, model, native reader or RPC.
// Pass a file: URL to Node --import on Windows; the fixed Trace broker Worker
// receives this diagnostic preload explicitly after production strips preloads.
import { AsyncLocalStorage } from 'node:async_hooks';
import childProcess from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import { syncBuiltinESMExports } from 'node:module';
import { join, win32 } from 'node:path';
import { monitorEventLoopDelay, performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import { types } from 'node:util';
import workerThreads, { isMainThread, parentPort, threadId, workerData } from 'node:worker_threads';

if (process.env.AGENT_HUB_TRACE_TIMING_DEV !== '1')
  throw new Error('Timing import requires an explicitly isolated DEV process');

let active = true;
const NativeWorker = workerThreads.Worker;
const nativeWorkerThreadId = Object.getOwnPropertyDescriptor(
  NativeWorker.prototype,
  'threadId',
)?.get;
const nativePromise = Promise;
const nativeThen = Promise.prototype.then;
const nativeSpecies = Object.getOwnPropertyDescriptor(Promise, Symbol.species)?.get;
const debugInteger = (value) => (Number.isSafeInteger(value) && value >= 0 ? value : undefined);
const processId = debugInteger(process.pid);
const isolateThreadId = debugInteger(threadId);
if (processId === undefined || isolateThreadId === undefined)
  throw new Error('Invalid OS timing identity');

// Resolve the Host's actual import condition, including packaged/nested copies.
// The resolver executes no Host or Adapter module and inherits no CLI --import.
// This fixed leaf module imports only node:worker_threads. Its Symbol registry
// (Main) / original workerData buffer (Workers) also serves nested Adapter copies.
const resolverGateURL = new URL(
  '../../packages/codex-adapter/dist/windows-process.js',
  import.meta.url,
);
const unsupportedGate = () =>
  Object.assign(new Error('DEV timing requires the shared 5b+ Windows process gate'), {
    code: 'WINDOWS_TIMING_GATE_UNSUPPORTED',
  });
let resolverGate, resolverShared;
try {
  resolverGate = await import(resolverGateURL.href);
  if (
    typeof resolverGate.withWindowsProcessCreation !== 'function' ||
    typeof resolverGate.getWindowsProcessGate !== 'function'
  )
    throw unsupportedGate();
  if (process.platform === 'win32') {
    resolverShared = resolverGate.getWindowsProcessGate();
    if (!(resolverShared instanceof SharedArrayBuffer) || resolverShared.byteLength !== 4)
      throw unsupportedGate();
  }
} catch {
  throw unsupportedGate();
}
const resolverEnvironment = { ...process.env };
delete resolverEnvironment.NODE_OPTIONS;
const actualAdapterURL = new URL(
  JSON.parse(
    resolverGate.withWindowsProcessCreation(5000, (timeout) =>
      childProcess.execFileSync(
        process.execPath,
        [
          '--experimental-import-meta-resolve',
          '--input-type=module',
          '--eval',
          "import {readFileSync} from 'node:fs';const v=JSON.parse(readFileSync(0,'utf8'));console.log(JSON.stringify(import.meta.resolve(v.name,v.parent)));",
        ],
        {
          input: JSON.stringify({
            name: '@codex-manage/codex-adapter',
            parent: new URL('../../apps/host-agent/dist/runtime-server.js', import.meta.url).href,
          }),
          encoding: 'utf8',
          timeout,
          stdio: ['pipe', 'pipe', 'pipe'],
          env: resolverEnvironment,
        },
      ),
    ),
  ),
);
if (actualAdapterURL.protocol !== 'file:' || !actualAdapterURL.pathname.endsWith('/dist/index.js'))
  throw Error('Expected actual compiled Host Adapter');
try {
  const actualGate = await import(new URL('./windows-process.js', actualAdapterURL).href);
  if (
    typeof actualGate.withWindowsProcessCreation !== 'function' ||
    typeof actualGate.getWindowsProcessGate !== 'function' ||
    (process.platform === 'win32' && actualGate.getWindowsProcessGate() !== resolverShared)
  )
    throw unsupportedGate();
} catch {
  throw unsupportedGate();
}
if (isMainThread) {
  const traceEntry = new URL('./desktop-trace-native-worker.js', actualAdapterURL).href;
  workerThreads.Worker = new Proxy(workerThreads.Worker, {
    construct(original, args, newTarget) {
      if (!active) return Reflect.construct(original, args, newTarget);
      const [entry, options] = args;
      if (entry instanceof URL && entry.href === traceEntry && options) {
        const descriptors = Object.getOwnPropertyDescriptors(options);
        const flags = descriptors.execArgv?.value;
        // The fixed broker supplies a plain options object and an already
        // filtered array under --import. Never copy process-wide Node defaults
        // or evaluate options getters merely to attach diagnostic metadata.
        if (
          Object.getPrototypeOf(options) === Object.prototype &&
          Reflect.ownKeys(descriptors).every((key) => 'value' in descriptors[key]) &&
          Array.isArray(flags) &&
          !flags.some(
            (flag, index) =>
              flag === '--import=' + import.meta.url ||
              (flag === '--import' && flags[index + 1] === import.meta.url),
          )
        ) {
          descriptors.execArgv.value = [...flags, '--import', import.meta.url];
          const augmented = Object.create(Object.getPrototypeOf(options), descriptors);
          if (!Object.isExtensible(options)) Object.preventExtensions(augmented);
          args = [entry, augmented, ...args.slice(2)];
        }
      }
      return Reflect.construct(original, args, newTarget);
    },
  });
  syncBuiltinESMExports();
}
const load = (name) => import(new URL('./' + name + '.js', actualAdapterURL).href);
const [
  { RuntimeSession },
  { RuntimePersistence },
  { DesktopIpc },
  { DesktopFrameDecoder },
  { DesktopWorkspaceView },
  { DesktopPreparedBridge },
  { CodexHistoryReader },
  { DesktopSuggestionObserver },
  { DesktopSuggestionWorkerClient },
  { DesktopPredictionTraceReader },
  { protectWindowsStateDirectory, protectWindowsStateFile },
] = await Promise.all(
  [
    'runtime-session',
    'runtime-persistence',
    'desktop-ipc',
    'desktop-framing',
    'desktop-workspace-view',
    'desktop-prepared-bridge',
    'history-reader',
    'desktop-suggestion-observer',
    'desktop-suggestion-worker-client',
    'desktop-prediction-trace',
    'desktop-platform',
  ].map(load),
);

const context = new AsyncLocalStorage();
const predictionContext = new AsyncLocalStorage();
const predictionScans = new Map();
const traceChildren = [];
let predictionScansObserved = 0;
const predictionScanPhases = { legacy: 0, prepare: 0, commit: 0 };
const preparedObservation = { prepareCalls: 0, commitCalls: 0, receiptHookFailures: 0 };
const receiptHooks = new WeakMap();
const pendingContinuations = new Set();
const rows = [];
const open = new Map();
const started = process.hrtime.bigint();
const startedAt = new Date().toISOString();
const cpuStart = process.cpuUsage();
const utilizationStart = performance.eventLoopUtilization();
const histogram = monitorEventLoopDelay({ resolution: 10 });
const role = isMainThread
  ? 'main'
  : workerData?.kind === 'windows-suggestion-reader'
    ? 'suggestion-reader'
    : workerData?.shared instanceof SharedArrayBuffer
      ? 'native-channel'
      : 'other-worker';
const traceCommandCoverage = {
  expected: role === 'native-channel',
  spawnAttempts: 0,
  hookedChildren: 0,
  hookFailures: 0,
  incomplete: 0,
  unclassified: 0,
  observedCounts: { P: 0, S: 0, T: 0, B: 0 },
};
const moduleSHA256 = Object.fromEntries(
  [
    'runtime-session',
    'runtime-persistence',
    'desktop-ipc',
    'desktop-framing',
    'desktop-platform',
    'desktop-suggestion-observer',
    'desktop-suggestion-worker-client',
    'desktop-suggestion-read-worker',
    'desktop-suggestion-worker-limits',
    'desktop-trace-native',
    'desktop-trace-native-worker',
    'desktop-private-state-native',
    'desktop-private-state-native-worker',
    'desktop-prediction-trace',
    'desktop-workspace-view',
    'windows-process',
  ].map((name) => [
    name,
    createHash('sha256')
      .update(fs.readFileSync(new URL(`./${name}.js`, actualAdapterURL)))
      .digest('hex'),
  ]),
);
moduleSHA256['host/runtime-server'] = createHash('sha256')
  .update(fs.readFileSync(new URL('../../apps/host-agent/dist/runtime-server.js', import.meta.url)))
  .digest('hex');
moduleSHA256['timing/resolver-windows-process'] = createHash('sha256')
  .update(fs.readFileSync(resolverGateURL))
  .digest('hex');
const originalWriteFileSync = fs.writeFileSync;
let sequence = 0;
let dropped = 0;
let output;
let workerReadOrdinal;
const now = () => Number(process.hrtime.bigint() - started) / 1e6;
function append(row) {
  if (!active) return;
  if (rows.length < 20_000) rows.push(row);
  else dropped++;
}
function span(name, metadata = {}) {
  const entry = {
    sequence: ++sequence,
    parentSequence: context.getStore()?.sequence ?? null,
    name,
    startMs: now(),
    ...metadata,
  };
  open.set(entry.sequence, entry.name);
  return entry;
}
// Inspect only own data properties. Never invoke result/error getters or Proxy traps.
function data(value, key) {
  if (!value || typeof value !== 'object' || types.isProxy(value)) return undefined;
  const descriptor = Object.getOwnPropertyDescriptor(value, key);
  return descriptor && 'value' in descriptor ? descriptor.value : undefined;
}
function resultSummary(kind, value) {
  if (kind === 'wait')
    return { waitTimedOut: value === 'timed-out', waitNotEqual: value === 'not-equal' };
  if (kind === 'parser-prepare') return { resultNull: value === null };
  if (kind === 'availability') return typeof value === 'boolean' ? { availability: value } : {};
  if (kind === 'predictions')
    return {
      resultNull: value === null,
      ...(value === null
        ? { resultCount: null }
        : Array.isArray(value) && !types.isProxy(value)
          ? { resultCount: data(value, 'length') }
          : {}),
    };
  if (kind === 'suggestion') {
    const suggestion = data(value, 'suggestion');
    if (suggestion === undefined) return {};
    // For bound reads resultNull describes the suggestion, not its envelope.
    const id = data(suggestion, 'id');
    return {
      resultNull: suggestion === null,
      ...(suggestion === null
        ? { suggestionIdHash: null }
        : typeof id === 'string' && /^[a-f0-9]{64}$/i.test(id)
          ? { suggestionIdHash: createHash('sha256').update(id).digest('hex') }
          : {}),
    };
  }
  if (kind === 'source') {
    const thread = data(value, 'threadId'),
      turn = data(value, 'sourceTurnId');
    const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
    return typeof thread === 'string' &&
      uuid.test(thread) &&
      typeof turn === 'string' &&
      uuid.test(turn)
      ? {
          sourceTupleHash: createHash('sha256')
            .update(JSON.stringify([thread, turn]))
            .digest('hex'),
        }
      : {};
  }
  return {};
}
function settle(entry, failed, error, kind, value) {
  if (!active || !open.delete(entry.sequence)) return;
  finishPredictionScan(entry.sequence, false);
  let status;
  let summary;
  try {
    const candidate = data(error, 'status');
    if (Number.isInteger(candidate) && candidate >= 400 && candidate <= 599) status = candidate;
    if (!failed)
      summary =
        kind === 'prepare-receipt'
          ? observePreparedReceipt(value, entry)
          : resultSummary(kind, value);
  } catch {
    // Omit inaccessible diagnostic fields; preserve the actual outcome.
  }
  append({
    ...entry,
    endMs: now(),
    wallMs: now() - entry.startMs,
    outcome: failed ? 'error' : 'ok',
    status,
    ...summary,
  });
}
function timed(original, receiver, args, name, metadata = {}, kind) {
  if (!active) return Reflect.apply(original, receiver, args);
  const entry = span(name, metadata);
  const scanPhase =
    name === 'DesktopPredictionTraceReader.readSourcePredictions'
      ? 'legacy'
      : name === 'DesktopSuggestionObserver.prepareBoundSource'
        ? 'prepare'
        : name === 'DesktopSuggestionObserver.prepareBoundSource.commit'
          ? 'commit'
          : undefined;
  const scan =
    role === 'suggestion-reader' && scanPhase
      ? {
          sequence: entry.sequence,
          phase: scanPhase,
          workerReadOrdinal: context.getStore()?.workerReadOrdinal ?? workerReadOrdinal,
          startMs: entry.startMs,
          methods: {},
          depth: 0,
          topLevelWallMs: 0,
        }
      : undefined;
  if (scan) {
    predictionScansObserved++;
    predictionScanPhases[scanPhase]++;
    predictionScans.set(entry.sequence, scan);
  }
  let value;
  try {
    value = context.run(entry, () =>
      scan
        ? predictionContext.run(scan, () => Reflect.apply(original, receiver, args))
        : Reflect.apply(original, receiver, args),
    );
  } catch (error) {
    entry.syncMs = now() - entry.startMs;
    settle(entry, true, error);
    throw error;
  }
  entry.syncMs = now() - entry.startMs;
  if (types.isPromise(value)) {
    try {
      // Use the intrinsic then, never a value.then getter. Exotic constructors
      // or species cannot be observed safely; leave that span explicitly pending.
      if (
        Object.getPrototypeOf(value) === nativePromise.prototype &&
        !Object.hasOwn(value, 'constructor') &&
        Object.getOwnPropertyDescriptor(nativePromise.prototype, 'constructor')?.value ===
          nativePromise &&
        Object.getOwnPropertyDescriptor(nativePromise, Symbol.species)?.get === nativeSpecies
      )
        Reflect.apply(nativeThen, value, [
          (result) => settle(entry, false, undefined, kind, result),
          (error) => settle(entry, true, error),
        ]);
    } catch {
      // Diagnostics must neither replace this Promise nor throw on its behalf.
    }
  } else settle(entry, false, undefined, kind, value);
  // Preserve receiver, arguments, the original return/Promise, and thrown errors.
  return value;
}
// Only mutable fixed continuation methods are decorated. The original receipt,
// Promise, descriptor flags, receiver, arguments and errors remain intact.
// The parser's frozen receipt is intentionally opaque: observe its enclosing
// worker-local observer.commit plus parser.prepare, never proxy/clone it.
function observePreparedReceipt(value, preparedBy) {
  if (!value || typeof value !== 'object') return { preparedReceiptPresent: false };
  if (types.isProxy(value)) {
    preparedObservation.receiptHookFailures++;
    return { receiptObservationComplete: false };
  }
  let names = receiptHooks.get(value);
  if (!names) receiptHooks.set(value, (names = new Set()));
  if (names.has(preparedBy.name)) {
    preparedObservation.receiptHookFailures++;
    return { preparedReceiptPresent: true, receiptObservationComplete: false, receiptReused: true };
  }
  names.add(preparedBy.name);
  const continuation = {
    name: preparedBy.name,
    preparedBySequence: preparedBy.sequence,
    phase: 'prepared',
  };
  if (pendingContinuations.size < 20_000) pendingContinuations.add(continuation);
  else preparedObservation.receiptHookFailures++;
  let complete = true;
  for (const method of ['commit', 'dispose']) {
    const descriptor = Object.getOwnPropertyDescriptor(value, method);
    const safe =
      descriptor &&
      'value' in descriptor &&
      typeof descriptor.value === 'function' &&
      !types.isProxy(descriptor.value) &&
      (descriptor.writable || descriptor.configurable);
    const installed =
      safe &&
      replaceObservedMethod(
        value,
        method,
        (original) =>
          function (...args) {
            if (!active) return Reflect.apply(original, this, args);
            if (method === 'commit') {
              preparedObservation.commitCalls++;
              continuation.phase = 'commit-started';
            }
            try {
              const returned = timed(original, this, args, `${preparedBy.name}.${method}`, {
                preparedBySequence: preparedBy.sequence,
                workerReadOrdinal: preparedBy.workerReadOrdinal,
                workerThreadId: preparedBy.workerThreadId,
                nextWorkerReadOrdinal: preparedBy.nextWorkerReadOrdinal,
                workerGeneration: preparedBy.workerGeneration,
              });
              if (method === 'dispose') pendingContinuations.delete(continuation);
              return returned;
            } catch (error) {
              if (method === 'dispose') continuation.phase = 'dispose-threw';
              throw error;
            }
          },
      );
    if (!installed) complete = false;
  }
  if (!complete) preparedObservation.receiptHookFailures++;
  return { preparedReceiptPresent: true, receiptObservationComplete: complete };
}
const fencedPhases = new Set(['preparing', 'prepared', 'committing', 'committed']);
function fencedMetadata(client) {
  const pending = data(client, 'prepared');
  const phase = data(pending, 'phase');
  const start = data(pending, phase === 'committing' ? 'commitAt' : 'startedAt');
  const deadline = data(pending, 'deadlineAt');
  const at = performance.now();
  return {
    pendingPresent: !!pending && typeof pending === 'object' && !types.isProxy(pending),
    pendingPhase: fencedPhases.has(phase) ? phase : undefined,
    pendingWorkerReadOrdinal: debugInteger(data(pending, 'id')),
    workerGeneration: debugInteger(data(pending, 'generation') ?? data(client, 'generation')),
    phaseElapsedMs:
      typeof start === 'number' && Number.isFinite(start) && at >= start ? at - start : undefined,
    // This is the total fenced deadline, not an inferred 3000ms phase limit.
    requestDeadlineExpired:
      typeof deadline === 'number' && Number.isFinite(deadline) ? at >= deadline : undefined,
  };
}
const wireReads = new Set([
  'initialize',
  'thread-owner-discovery',
  'thread-stream-following-changed',
  'thread-stream-state-changed',
  'thread-follower-read-model-settings',
]);
function wrap(Type, methods) {
  for (const method of methods) {
    const original = Type.prototype[method];
    if (typeof original !== 'function')
      throw new Error(`Timing method missing: ${Type.name}.${method}`);
    const prepareReceipt =
      (Type === DesktopSuggestionWorkerClient && method === 'prepare') ||
      (Type === DesktopSuggestionObserver &&
        ['prepareBoundSourceAsync', 'prepareBoundSource'].includes(method));
    const kind = prepareReceipt
      ? 'prepare-receipt'
      : Type === DesktopPredictionTraceReader && method === 'prepareSourcePredictions'
        ? 'parser-prepare'
        : Type === RuntimeSession && method === 'desktopSuggestionSource'
          ? 'source'
          : Type === DesktopPredictionTraceReader && method === 'readSourcePredictions'
            ? 'predictions'
            : (Type === DesktopSuggestionWorkerClient && method === 'read') ||
                (Type === DesktopSuggestionObserver &&
                  ['readBoundSource', 'readBoundSourceAsync'].includes(method))
              ? 'suggestion'
              : (Type === DesktopSuggestionObserver &&
                    [
                      'boundReadAvailable',
                      'windowsInstanceStable',
                      'workerReadiness',
                      'rootStable',
                    ].includes(method)) ||
                  (Type === DesktopPredictionTraceReader && method === 'isAvailable')
                ? 'availability'
                : undefined;
    Type.prototype[method] = function (...args) {
      if (!active) return Reflect.apply(original, this, args);
      let metadata;
      try {
        if (Type === DesktopIpc && method === 'follow') {
          const follows = data(this, 'follows');
          const pending = types.isMap(follows)
            ? Reflect.apply(Map.prototype.get, follows, [args[0]])
            : undefined;
          metadata = {
            fresh: args[2] === true,
            pending: Boolean(pending),
            pendingFresh: data(pending, 'revalidateOwner') === true,
            discoveryTimeoutMs: Number.isFinite(args[1]) ? args[1] : 15_000,
          };
        }
        if (Type === DesktopIpc && method === 'request')
          metadata = { wire: wireReads.has(args[0]) ? args[0] : 'other' };
        if (Type === DesktopIpc && method === 'receive')
          metadata = {
            wire: wireReads.has(data(args[0], 'method'))
              ? data(args[0], 'method')
              : data(args[0], 'type') === 'response'
                ? 'response'
                : 'other',
          };
        if (Type === RuntimeSession && method === 'event')
          metadata = {
            event: ['desktop/snapshot', 'desktop/unavailable', 'desktop/observation'].includes(
              args[0],
            )
              ? args[0]
              : 'other',
          };
        if (Type === RuntimePersistence && method === 'atomicWrite')
          metadata = {
            storage: args[0] === data(this, 'file') ? 'ledger' : 'private-cache',
            durable: args[2] === true,
          };
        if (
          Type === DesktopSuggestionWorkerClient &&
          ['read', 'prepare', 'acceptFenced', 'fail', 'close'].includes(method)
        ) {
          metadata = method === 'read' ? {} : fencedMetadata(this);
          // Internal counter/Worker only; never derive correlation from API args.
          // Diagnostic getters must not replace the original return or exception.
          try {
            if (['read', 'prepare'].includes(method)) {
              const counter = debugInteger(data(this, 'nextId'));
              metadata.nextWorkerReadOrdinal =
                counter === undefined ? undefined : debugInteger(counter + 1);
            }
          } catch {
            // Omit unavailable diagnostic data.
          }
          try {
            const worker = data(this, 'worker');
            const ownId = data(worker, 'threadId');
            // Retain the OS identity from a genuine Node Worker's fixed builtin
            // getter, never a caller-supplied accessor or a derived/proxied object.
            metadata.workerThreadId = debugInteger(
              ownId !== undefined
                ? ownId
                : worker &&
                    !types.isProxy(worker) &&
                    Object.getPrototypeOf(worker) === NativeWorker.prototype &&
                    !Object.hasOwn(worker, 'threadId') &&
                    nativeWorkerThreadId
                  ? Reflect.apply(nativeWorkerThreadId, worker, [])
                  : undefined,
            );
          } catch {
            // Omit unavailable diagnostic data.
          }
        }
        if (Type === DesktopSuggestionWorkerClient && method === 'fail')
          metadata = { ...metadata, currentWorkerMatches: args[0] === data(this, 'worker') };
        if (Type === DesktopSuggestionWorkerClient && method === 'acceptFenced') {
          const message = args[1];
          const messageKind = data(message, 'kind');
          metadata = {
            ...metadata,
            messageKind: ['prepared', 'fenced-result'].includes(messageKind)
              ? messageKind
              : 'other',
            currentWorkerMatches: args[0] === data(this, 'worker'),
            availabilityPresent: typeof data(message, 'available') === 'boolean',
            availability:
              typeof data(message, 'available') === 'boolean'
                ? data(message, 'available')
                : undefined,
            errorPresent:
              !!message &&
              typeof message === 'object' &&
              !types.isProxy(message) &&
              Object.hasOwn(message, 'error'),
          };
        }
        if (
          role === 'suggestion-reader' &&
          ['readBoundSource', 'prepareBoundSource'].includes(method)
        )
          metadata = { workerReadOrdinal };
      } catch {
        // Reading optional timing metadata cannot prevent the original call.
      }
      if (prepareReceipt) preparedObservation.prepareCalls++;
      const invoke =
        Type === DesktopSuggestionWorkerClient && method === 'acceptFenced'
          ? function (...received) {
              try {
                return Reflect.apply(original, this, received);
              } finally {
                append({
                  name: 'fenced-acceptance-after',
                  parentSequence: context.getStore()?.sequence,
                  atMs: now(),
                  ...fencedMetadata(this),
                });
              }
            }
          : original;
      return timed(invoke, this, args, `${Type.name}.${method}`, metadata, kind);
    };
  }
}
wrap(RuntimeSession, [
  'desktopSuggestionSource',
  'event',
  'desktopSummary',
  'desktopControls',
  'desktopSnapshot',
  'projectDesktopMessages',
  'emit',
  'save',
  'saveBackground',
  'workspace',
  'refreshDesktopCreationAvailability',
]);
wrap(DesktopIpc, ['follow', 'followNow', 'request', 'receive', 'following']);
wrap(DesktopFrameDecoder, ['push']);
wrap(RuntimePersistence, ['schedule', 'dirty', 'save', 'atomicWrite', 'writeHistory', 'drainOne']);
wrap(DesktopWorkspaceView, ['workspace', 'refresh']);
wrap(DesktopPreparedBridge, ['refresh', 'refreshNow']);
wrap(CodexHistoryReader, ['workspace', 'desktopWorkspace']);
wrap(DesktopSuggestionObserver, [
  'assertBoundReadAvailable',
  'readBoundSourceAsync',
  'readBoundSource',
  'prepareBoundSourceAsync',
  'prepareBoundSource',
  'workerReadiness',
  'boundReadAvailable',
  'windowsInstanceStable',
  'rootStable',
]);
wrap(DesktopSuggestionWorkerClient, ['read', 'prepare', 'acceptFenced', 'fail', 'close']);
wrap(DesktopPredictionTraceReader, [
  'readSourcePredictions',
  'prepareSourcePredictions',
  'isAvailable',
]);

// Observe the existing persistent child without a data/error listener, read(),
// JSON decoding, or changing stream flow. Only fixed headers and frame lengths
// survive; no command arguments, paths, payloads, or arbitrary error text do.
const byteLength = Object.getOwnPropertyDescriptor(
  Object.getPrototypeOf(Uint8Array.prototype),
  'length',
).get;
const byteIndexOf = Uint8Array.prototype.indexOf;
const stringIndexOf = String.prototype.indexOf;
const stringSlice = String.prototype.slice;
const staticNativeError = '{"error":"WINDOWS_TRACE_NATIVE_REJECTED"}';
function observedChunk(value, encoding) {
  if (typeof value === 'string') {
    if (typeof encoding === 'string' && !['utf8', 'utf-8', 'ascii', 'latin1'].includes(encoding))
      return undefined;
    return {
      length: value.length,
      at: (at) => value.charCodeAt(at),
      newline: (at) => Reflect.apply(stringIndexOf, value, ['\n', at]),
      bytes: (start, end) =>
        Buffer.byteLength(Reflect.apply(stringSlice, value, [start, end]), encoding),
    };
  }
  if (!value || types.isProxy(value) || !types.isUint8Array(value)) return undefined;
  return {
    length: Reflect.apply(byteLength, value, []),
    at: (at) => value[at],
    newline: (at) => Reflect.apply(byteIndexOf, value, [10, at]),
    bytes: (start, end) => end - start,
  };
}
function replaceObservedMethod(object, key, decorate) {
  if (!object || types.isProxy(object)) return false;
  try {
    let descriptor;
    for (let at = object; at && !descriptor; at = Object.getPrototypeOf(at)) {
      if (types.isProxy(at)) return false;
      descriptor = Object.getOwnPropertyDescriptor(at, key);
    }
    if (!descriptor || !('value' in descriptor) || typeof descriptor.value !== 'function')
      return false;
    const own = Object.getOwnPropertyDescriptor(object, key);
    Object.defineProperty(object, key, {
      ...(own ?? { configurable: true, enumerable: false, writable: true }),
      value: decorate(descriptor.value),
    });
    return true;
  } catch {
    return false;
  }
}
function observeTraceChild(child, childOrdinal) {
  if (traceChildren.length >= 20_000) {
    traceCommandCoverage.hookFailures++;
    traceCommandCoverage.incomplete++;
    return;
  }
  const queue = [];
  let commandOrdinal = 0,
    requestLength = 0,
    prefix = '',
    quit = true,
    requestStart;
  let responseLength = 0,
    errorMatches = true,
    firstFrame = true,
    terminal = false;
  const pid = () => debugInteger(data(child, 'pid'));
  traceChildren.push(() => ({
    childOrdinal,
    childProcessId: pid(),
    pendingCommands: queue.length,
    partialRequestBytes: requestLength,
    partialResponseBytes: responseLength,
    terminal,
  }));
  const incomplete = (flags = {}) => {
    if (terminal) return;
    terminal = true;
    traceCommandCoverage.incomplete++;
    for (const entry of queue.splice(0)) {
      Object.assign(entry, { commandCompleted: false, incomplete: true, ...flags });
      settle(entry, true);
    }
    append({
      name: 'trace-native-incomplete',
      childOrdinal,
      childProcessId: pid(),
      partialResponseBytes: responseLength,
      partialRequestBytes: requestLength,
      ...flags,
    });
  };
  const observeWrite = (value, encoding) => {
    const chunk = observedChunk(value, encoding);
    if (!chunk) {
      traceCommandCoverage.unclassified++;
      incomplete({ opaqueWrite: true });
      return;
    }
    for (let start = 0; start < chunk.length;) {
      if (!requestLength) requestStart = now();
      const newline = chunk.newline(start),
        end = newline < 0 ? chunk.length : newline;
      for (let at = start; at < end && requestLength + at - start < 5; at++) {
        const index = requestLength + at - start,
          byte = chunk.at(at);
        if (index < 2) prefix += String.fromCharCode(byte);
        if (index >= 4 || byte !== 'QUIT'.charCodeAt(index)) quit = false;
      }
      requestLength += chunk.bytes(start, end);
      if (newline < 0) break;
      const kind =
        (requestLength === 1 && ['P', 'T'].includes(prefix)) || ['S\t', 'B\t'].includes(prefix)
          ? prefix[0]
          : undefined;
      if (kind && !terminal && queue.length < 20_000) {
        traceCommandCoverage.observedCounts[kind]++;
        queue.push({
          ...span('trace-native-command', {
            childOrdinal,
            childProcessId: pid(),
            commandOrdinal: ++commandOrdinal,
            commandKind: kind,
          }),
          startMs: requestStart,
        });
      } else if (!(quit && requestLength === 4)) {
        traceCommandCoverage.unclassified++;
        incomplete({ unknownCommand: true });
      }
      requestLength = 0;
      prefix = '';
      quit = true;
      requestStart = undefined;
      start = newline + 1;
    }
  };
  const observeOutput = (value) => {
    const chunk = observedChunk(value);
    if (!chunk) {
      incomplete({ opaqueFrame: true });
      return;
    }
    for (let start = 0; start < chunk.length;) {
      const newline = chunk.newline(start),
        end = newline < 0 ? chunk.length : newline;
      const length = chunk.bytes(start, end);
      if (responseLength + length > staticNativeError.length + 1) errorMatches = false;
      if (errorMatches)
        for (let at = start; at < end; at++) {
          const index = responseLength + at - start;
          if (
            chunk.at(at) !==
            (index === staticNativeError.length ? 13 : staticNativeError.charCodeAt(index))
          )
            errorMatches = false;
        }
      responseLength += length;
      if (newline < 0) break;
      const entry = terminal ? undefined : queue.shift();
      const metadata = {
        responseBytes: responseLength + 1,
        nativeErrorFrame: errorMatches && responseLength >= staticNativeError.length,
      };
      if (entry) {
        Object.assign(entry, metadata, { commandCompleted: true });
        settle(entry, false);
      } else {
        append({
          name: 'trace-native-unmatched-frame',
          childOrdinal,
          childProcessId: pid(),
          atMs: now(),
          startupCandidate: firstFrame,
          ...metadata,
        });
        if (!firstFrame) incomplete({ extraFrame: true });
      }
      firstFrame = false;
      responseLength = 0;
      errorMatches = true;
      start = newline + 1;
    }
  };
  const safe = (action) => {
    try {
      action();
    } catch {
      try {
        incomplete({ observationFailed: true });
      } catch {
        /* Preserve the original stream operation. */
      }
    }
  };
  const writeHook = replaceObservedMethod(
    data(child, 'stdin'),
    'write',
    (original) =>
      function (...args) {
        if (active)
          safe(() => observeWrite(args[0], typeof args[1] === 'string' ? args[1] : undefined));
        try {
          return Reflect.apply(original, this, args);
        } catch (error) {
          if (active) safe(() => incomplete({ writeThrew: true }));
          throw error;
        }
      },
  );
  const outputHook = replaceObservedMethod(
    data(child, 'stdout'),
    'emit',
    (original) =>
      function (...args) {
        if (active)
          safe(() => {
            if (args[0] === 'data') observeOutput(args[1]);
            if (
              ['end', 'close', 'error'].includes(args[0]) &&
              (queue.length || responseLength || requestLength)
            )
              incomplete({ outputClosed: true });
          });
        return Reflect.apply(original, this, args);
      },
  );
  const lifecycleHook = replaceObservedMethod(
    child,
    'emit',
    (original) =>
      function (...args) {
        if (active)
          safe(() => {
            if (!['spawn', 'error', 'exit', 'close'].includes(args[0])) return;
            append({
              name: 'trace-native-lifecycle',
              childOrdinal,
              childProcessId: pid(),
              atMs: now(),
              spawned: args[0] === 'spawn',
              errored: args[0] === 'error',
              exited: args[0] === 'exit',
              closed: args[0] === 'close',
            });
            if (
              args[0] === 'error' ||
              (['exit', 'close'].includes(args[0]) &&
                (queue.length || responseLength || requestLength))
            )
              incomplete({ childClosed: true });
          });
        return Reflect.apply(original, this, args);
      },
  );
  if (writeHook && outputHook && lifecycleHook) traceCommandCoverage.hookedChildren++;
  else {
    traceCommandCoverage.hookFailures++;
    incomplete({ hookMissing: true });
  }
}
const nativeSpawn = childProcess.spawn;
childProcess.spawn = function (...args) {
  if (
    !active ||
    role !== 'native-channel' ||
    typeof args[0] !== 'string' ||
    win32.basename(args[0]).toLowerCase() !== 'trace-state.exe'
  )
    return Reflect.apply(nativeSpawn, this, args);
  const childOrdinal = ++traceCommandCoverage.spawnAttempts;
  const entry = span('trace-native-spawn', { childOrdinal });
  let child;
  try {
    child = Reflect.apply(nativeSpawn, this, args);
  } catch (error) {
    traceCommandCoverage.incomplete++;
    settle(entry, true);
    throw error;
  }
  entry.childProcessId = debugInteger(data(child, 'pid'));
  settle(entry, false);
  try {
    observeTraceChild(child, childOrdinal);
  } catch {
    traceCommandCoverage.hookFailures++;
    traceCommandCoverage.incomplete++;
  }
  return child;
};

const predictionFSMethods = [
  'lstatSync',
  'realpathSync',
  'fstatSync',
  'openSync',
  'readSync',
  'closeSync',
  'opendirSync',
];
const predictionFSHooks = {};
function finishPredictionScan(sequence, pending) {
  const scan = predictionScans.get(sequence);
  if (!scan) return;
  predictionScans.delete(sequence);
  append({
    name: 'prediction-scan-fs',
    parentSequence: scan.sequence,
    workerReadOrdinal: scan.workerReadOrdinal,
    phase: scan.phase,
    startMs: scan.startMs,
    endMs: now(),
    pending,
    topLevelWallMs: scan.topLevelWallMs,
    methods: scan.methods,
  });
}
if (role === 'suggestion-reader')
  for (const method of predictionFSMethods) {
    predictionFSHooks[method] = replaceObservedMethod(fs, method, (original) => {
      if (types.isProxy(original)) throw new Error('Opaque FS method');
      const wrapped = function (...args) {
        const scan = active ? predictionContext.getStore() : undefined;
        if (!scan) return Reflect.apply(original, this, args);
        const start = now(),
          outermost = scan.depth++ === 0;
        const aggregate = (scan.methods[method] ??= { count: 0, errors: 0, wallMs: 0 });
        aggregate.count++;
        try {
          return Reflect.apply(original, this, args);
        } catch (error) {
          aggregate.errors++;
          throw error;
        } finally {
          const elapsed = now() - start;
          aggregate.wallMs += elapsed;
          scan.depth--;
          if (outermost) scan.topLevelWallMs += elapsed;
        }
      };
      // In particular realpathSync.native stays the exact original data property.
      // Copy descriptors, never evaluate an original function's getters.
      Object.defineProperties(wrapped, Object.getOwnPropertyDescriptors(original));
      return wrapped;
    });
  }

const execFileSync = childProcess.execFileSync;
childProcess.execFileSync = function (...args) {
  if (!active) return Reflect.apply(execFileSync, this, args);
  const executable = typeof args[0] === 'string' ? win32.basename(args[0]).toLowerCase() : '';
  if (!['private-state.exe', 'trace-state.exe', 'powershell.exe', 'csc.exe'].includes(executable))
    return Reflect.apply(execFileSync, this, args);
  const action =
    executable === 'private-state.exe' &&
    ['check', 'protect', 'protect-file'].includes(args[1]?.[0])
      ? args[1][0]
      : 'native';
  return timed(execFileSync, this, args, `native.${executable}.${action}`);
};
for (const method of ['writeFileSync', 'readFileSync', 'renameSync', 'fsyncSync']) {
  const original = fs[method];
  fs[method] = function (...args) {
    if (!active) return Reflect.apply(original, this, args);
    const parent = context.getStore();
    if (!parent || !parent.name.startsWith('RuntimePersistence.'))
      return Reflect.apply(original, this, args);
    return timed(original, this, args, `fs.${method}`);
  };
}
syncBuiltinESMExports();
if (!isMainThread) {
  const wait = Atomics.wait;
  Atomics.wait = function (...args) {
    return timed(wait, this, args, 'Atomics.wait', {}, 'wait');
  };
}
if (role === 'suggestion-reader') {
  let ordinal = 0;
  const requests = new Map();
  // This preload runs before the entry registers its listener. MessagePort is
  // a NodeEventTarget: on() preserves this order without EventEmitter-only APIs.
  parentPort.on('message', (message) => {
    if (!active) return;
    const kind = data(message, 'kind'),
      id = data(message, 'id');
    if (
      !['read', 'prepare', 'commit', 'abort'].includes(kind) ||
      !Number.isSafeInteger(id) ||
      id <= 0
    )
      return;
    const generation = debugInteger(data(message, 'generation'));
    const existing = requests.get(id);
    if ((kind === 'read' || kind === 'prepare') && !existing && requests.size < 20_000)
      requests.set(id, { ordinal: ++ordinal, generation });
    const record = requests.get(id);
    const matched = !!record && (kind === 'read' || record.generation === generation);
    workerReadOrdinal = matched ? record.ordinal : undefined;
    append({
      name: kind === 'read' ? 'worker-read-dispatch' : 'worker-phase-dispatch',
      phase: kind,
      workerReadOrdinal,
      matchedRequest: matched,
      atMs: now(),
    });
    queueMicrotask(() => {
      workerReadOrdinal = undefined;
    });
    if (kind === 'abort' && matched) requests.delete(id);
  });
  const post = parentPort.postMessage;
  parentPort.postMessage = function (...args) {
    const message = args[0],
      kind = data(message, 'kind'),
      id = data(message, 'id');
    if (
      !active ||
      !['result', 'prepared', 'fenced-result'].includes(kind) ||
      !Number.isSafeInteger(id)
    )
      return Reflect.apply(post, this, args);
    const record = requests.get(id);
    const generation = debugInteger(data(message, 'generation'));
    const matched = !!record && (kind === 'result' || record.generation === generation);
    const availability = data(message, 'available');
    try {
      return timed(
        post,
        this,
        args,
        kind === 'result' ? 'worker-result-post' : 'worker-phase-post',
        {
          workerReadOrdinal: matched ? record.ordinal : undefined,
          ...(kind === 'result'
            ? {
                finalAvailabilityPresent: typeof availability === 'boolean',
                ...(typeof availability === 'boolean' ? { finalAvailability: availability } : {}),
              }
            : {
                phase: kind,
                matchedRequest: matched,
                availabilityPresent: typeof availability === 'boolean',
                ...(typeof availability === 'boolean' ? { availability } : {}),
                errorPresent:
                  !!message &&
                  typeof message === 'object' &&
                  !types.isProxy(message) &&
                  Object.hasOwn(message, 'error'),
              }),
        },
      );
    } finally {
      if (matched && kind !== 'prepared') requests.delete(id);
    }
  };
}

const routes = new Set(['/healthz', '/v1/workspace', '/v1/threads/next-turn-suggestion']);
for (const Type of [http.Server, https.Server]) {
  const emit = Type.prototype.emit;
  Type.prototype.emit = function (event, ...args) {
    if (!active || event !== 'request') return Reflect.apply(emit, this, [event, ...args]);
    const [request, response] = args;
    const url = data(request, 'url');
    const route = typeof url === 'string' ? url.split('?')[0] : '';
    if (data(request, 'method') !== 'GET' || !routes.has(route))
      return Reflect.apply(emit, this, [event, ...args]);
    const entry = span(`HTTP ${route}`);
    let finished = false;
    const finish = (completion) => {
      if (!active || finished) return;
      finished = true;
      const status =
        data(response, 'statusCode') ??
        (!types.isProxy(response) && !Object.hasOwn(response, 'statusCode')
          ? data(http.ServerResponse.prototype, 'statusCode')
          : undefined);
      entry.httpStatus =
        Number.isInteger(status) && status >= 100 && status <= 599 ? status : undefined;
      entry.completion = completion;
      settle(entry, false);
    };
    response.once('finish', () => finish('finish'));
    response.once('close', () => finish('close'));
    try {
      return context.run(entry, () => Reflect.apply(emit, this, [event, ...args]));
    } finally {
      entry.syncMs = now() - entry.startMs;
    }
  };
}
histogram.enable();
let lastTick = now();
const tick = setInterval(() => {
  const actual = now();
  const lagMs = Math.max(0, actual - lastTick - 20);
  if (lagMs >= 40) append({ name: 'event-loop-lag', startMs: lastTick, endMs: actual, lagMs });
  lastTick = actual;
}, 20);
tick.unref();

export function writeTimingEvidence() {
  if (!active) return output;
  const endMs = now();
  for (const sequence of predictionScans.keys()) finishPredictionScan(sequence, true);
  const childrenAtStop = traceChildren.map((snapshot) => snapshot());
  const nativeComplete =
    traceCommandCoverage.hookedChildren > 0 &&
    traceCommandCoverage.hookFailures === 0 &&
    traceCommandCoverage.incomplete === 0 &&
    traceCommandCoverage.unclassified === 0 &&
    childrenAtStop.every(
      (child) =>
        !child.pendingCommands && !child.partialRequestBytes && !child.partialResponseBytes,
    ) &&
    ![...open.values()].includes('trace-native-command');
  const fsComplete =
    role === 'suggestion-reader' &&
    predictionFSMethods.every((method) => predictionFSHooks[method]) &&
    ![...open.values()].some((name) =>
      [
        'DesktopPredictionTraceReader.readSourcePredictions',
        'DesktopSuggestionObserver.prepareBoundSource',
        'DesktopSuggestionObserver.prepareBoundSource.commit',
      ].includes(name),
    );
  active = false;
  histogram.disable();
  clearInterval(tick);
  clearTimeout(deadline);
  const summary = {
    startedAt,
    monotonicStartNs: started.toString(),
    processId,
    isolateThreadId,
    role,
    endMs,
    dropped,
    complete:
      dropped === 0 &&
      open.size === 0 &&
      preparedObservation.receiptHookFailures === 0 &&
      pendingContinuations.size === 0 &&
      (!traceCommandCoverage.expected || nativeComplete) &&
      (role !== 'suggestion-reader' || fsComplete),
    // Join expected roles only within the same processId. This local flag cannot
    // certify a whole profile; a missing role never means zero work or complete.
    completenessScope: 'isolate',
    pendingAtStop: [
      ...open.values(),
      ...[...pendingContinuations].map((value) => value.name + ':prepared-continuation'),
    ],
    node: process.version,
    platform: process.platform,
    moduleSHA256,
    persistentTraceCommands: {
      ...traceCommandCoverage,
      complete: nativeComplete && dropped === 0,
      childrenAtStop,
    },
    preparedReadObservation: {
      ...preparedObservation,
      pendingContinuations: [...pendingContinuations],
      activity: preparedObservation.prepareCalls ? 'observed' : 'not-observed',
      receiptObservationComplete: preparedObservation.receiptHookFailures === 0,
      scope:
        'Fixed mutable client/observer continuations only; frozen parser receipt unchanged; counts are per isolate, not end-to-end success',
    },
    predictionScanFS: {
      expected: role === 'suggestion-reader',
      observedScans: predictionScansObserved,
      observedPhases: predictionScanPhases,
      hooks: predictionFSHooks,
      complete: fsComplete && dropped === 0,
      scope:
        'Legacy parser.read or worker observer.prepare/commit subtrees; phase-tagged, inclusive method wall time, topLevelWallMs excludes nested double counting; native alias unchanged',
    },
    processCPUWindow: process.cpuUsage(cpuStart),
    cpuScope: 'Process-wide concurrent work; not exclusive CPU attributed to a span/thread',
    eventLoopUtilization: performance.eventLoopUtilization(utilizationStart),
    eventLoopDelayMs: { max: histogram.max / 1e6, p95: histogram.percentile(95) / 1e6 },
    rows,
  };
  // Serialization, native DACL protection and writes follow recording. Only OS
  // process/isolate/Worker integers and local counters identify timing contexts;
  // Business IDs appear only as fixed hashes; arguments, paths and bodies stay out.
  const root = fileURLToPath(new URL('../../.local/windows-suggestion-timing/', import.meta.url));
  fs.mkdirSync(root, { recursive: true, mode: 0o700 });
  if (!fs.lstatSync(root).isDirectory() || fs.lstatSync(root).isSymbolicLink())
    throw new Error('Unsafe timing evidence directory');
  fs.chmodSync(root, 0o700);
  protectWindowsStateDirectory(root);
  const directory = join(root, randomUUID());
  fs.mkdirSync(directory, { mode: 0o700 });
  protectWindowsStateDirectory(directory);
  output = join(directory, 'timing.json');
  originalWriteFileSync(output, JSON.stringify(summary), { flag: 'wx', mode: 0o600 });
  protectWindowsStateFile(output);
  return output;
}
const deadline = setTimeout(writeTimingEvidence, 90_000);
deadline.unref();
process.once('exit', () => writeTimingEvidence());
