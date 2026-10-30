/// <reference lib="es2024.sharedmemory" />
import { randomUUID } from 'node:crypto';
import { diagnosticEvent, observeDiagnosticWorker } from './diagnostic-events.js';
import {
  Worker,
  MessageChannel,
  MessagePort,
  isMainThread,
  parentPort,
  postMessageToThread,
  type WorkerOptions,
} from 'node:worker_threads';
import type { WindowsDesktopIdentity } from './desktop-platform.js';
import {
  getWindowsProcessGate,
  getWindowsProcessWorker,
  registerWindowsProcessWorker,
} from './windows-process.js';

export type WindowsPrivateStateAction = 'protect' | 'protect-file' | 'check';
// Separate from the permission response: diagnostic data never authorizes a call.
export const privateStateNativeDiagnostics = Object.freeze({
  words: 8,
  stages: [
    'environment',
    'source',
    'directory',
    'protect-directory',
    'compiler',
    'compile',
    'protect-binary',
    'publish-binary',
    'resolve-binary',
    'bootstrap-native',
    'recheck-binary',
    'prepare',
    'spawn-gate',
    'spawn',
    'handshake',
    'operation',
  ] as const,
  failures: [
    'prepare-failed',
    'spawn-error',
    'native-exit',
    'stderr-present',
    'stdin-error',
    'stdout-error',
    'stdout-eof',
    'stderr-error',
    'startup-deadline',
    'execution-deadline',
    'invalid-ready',
    'oversized-frame',
    'trailing-frame',
    'non-ascii-frame',
    'uncorrelated-response',
    'request-rejected',
    'isolate-error',
    'isolate-exit',
  ] as const,
  codes: [
    'ENOENT',
    'EACCES',
    'EPERM',
    'ETIMEDOUT',
    'UNSAFE_WINDOWS_PRIVATE_STATE',
    'WINDOWS_POWERSHELL_MODULE_UNAVAILABLE',
    'WINDOWS_HELPER_FAILED',
    'WINDOWS_PROCESS_GATE_UNAVAILABLE',
    'ERR_MODULE_NOT_FOUND',
    'ERR_WORKER_INVALID_EXEC_ARGV',
    'ERR_INPUT_TYPE_NOT_ALLOWED',
    'ERR_ACCESS_DENIED',
  ] as const,
  helperStages: ['add-type', 'execute'] as const,
  nativeTypes: [
    'Win32Exception',
    'UnauthorizedAccessException',
    'IOException',
    'ArgumentException',
    'TypeInitializationException',
    'InvalidOperationException',
    'PSInvalidOperationException',
    'PSInvalidCastException',
    'PathTooLongException',
    'FileNotFoundException',
    'DirectoryNotFoundException',
    'DllNotFoundException',
    'EntryPointNotFoundException',
    'BadImageFormatException',
    'SecurityException',
    'NotSupportedException',
    'Exception',
  ] as const,
  rejections: [
    'WRONG_FILE_OWNER',
    'NULL_PRIVATE_DACL',
    'UNKNOWN_PRIVATE_ACE',
    'SHARED_PRIVATE_FILE',
    'UNPROTECTED_PRIVATE_DACL',
  ] as const,
});
export type PrivateStateNativeStage = (typeof privateStateNativeDiagnostics.stages)[number];
type PrivateStateNativeFailure = (typeof privateStateNativeDiagnostics.failures)[number];
export function privateStateNativeStage(shared: Int32Array, stage: PrivateStateNativeStage): void {
  Atomics.store(shared, 0, privateStateNativeDiagnostics.stages.indexOf(stage) + 1);
}
export function privateStateNativeFailure(
  shared: Int32Array,
  failure: PrivateStateNativeFailure,
  error?: unknown,
): void {
  if (
    Atomics.compareExchange(
      shared,
      1,
      0,
      privateStateNativeDiagnostics.failures.indexOf(failure) + 1,
    )
  )
    return;
  try {
    const value = error as
      | {
          code?: unknown;
          status?: unknown;
          cause?: { status?: unknown };
          helperStage?: unknown;
          nativeType?: unknown;
          nativeWin32?: unknown;
          nativeRejection?: unknown;
        }
      | undefined;
    Atomics.store(
      shared,
      2,
      privateStateNativeDiagnostics.codes.findIndex((code) => code === value?.code) + 1,
    );
    const status = value?.status ?? value?.cause?.status;
    if (
      typeof status === 'number' &&
      Number.isInteger(status) &&
      status >= -0x80000000 &&
      status <= 0x7fffffff
    ) {
      Atomics.store(shared, 3, status);
      // Low byte is the first failure ID; this bit distinguishes exit 0 from unset.
      Atomics.or(shared, 1, 0x100);
    }
    Atomics.store(
      shared,
      4,
      privateStateNativeDiagnostics.helperStages.findIndex((v) => v === value?.helperStage) + 1,
    );
    Atomics.store(
      shared,
      5,
      privateStateNativeDiagnostics.nativeTypes.findIndex((v) => v === value?.nativeType) + 1,
    );
    if (
      typeof value?.nativeWin32 === 'number' &&
      Number.isInteger(value.nativeWin32) &&
      value.nativeWin32 > 0 &&
      value.nativeWin32 <= 0x7fffffff
    )
      Atomics.store(shared, 6, value.nativeWin32);
    Atomics.store(
      shared,
      7,
      privateStateNativeDiagnostics.rejections.findIndex((v) => v === value?.nativeRejection) + 1,
    );
  } catch {
    // A diagnostic-only property must never interrupt channel retirement.
  }
}
/** Internal transport contract; no paths or permission decisions are cached. */
export const privateStateProtocol = Object.freeze({
  words: 6,
  responseBytes: 256,
  pathBytes: 128 * 1024,
  pathCharacters: 32767,
  frameBytes: 2 * Math.ceil((128 * 1024) / 3) * 4 + 64,
  startupMs: 30000,
  operationMs: 5000,
  closeMs: 1000,
  state: 0,
  result: 1,
  request: 2,
  response: 3,
  length: 4,
  pid: 5,
  starting: 0,
  ready: 1,
  failed: -1,
  closing: 2,
  gone: 3,
  waiting: 0,
  ok: 1,
  rejected: 2,
} as const);

const bootstrapArguments = new Set([
  '--import',
  '--require',
  '-r',
  '--loader',
  '--experimental-loader',
  '--eval',
  '-e',
  '--print',
  '-p',
  '--input-type',
]);
const preloadArguments = new Set([
  ...bootstrapArguments,
  '--test-concurrency',
  '--test-reporter',
  '--test-reporter-destination',
  '--test-name-pattern',
  '--test-skip-pattern',
  '--test-shard',
  '--test-timeout',
  '--test-isolation',
  '--watch-path',
]);
// Node already inherits these process-wide/V8 settings. Repeating them in
// Worker.execArgv is rejected (including --disable-proto and code-generation).
const inheritedValueArguments = new Set([
  '--disable-proto',
  '--title',
  '--max-old-space-size',
  '--max-semi-space-size',
  '--stack-size',
  '--stack-trace-limit',
  '--tls-cipher-list',
  '--use-largepages',
  '--secure-heap-min',
  '--secure-heap',
  '--v8-pool-size',
  '--trace-event-file-pattern',
]);
const inheritedSwitches = new Set([
  '--disallow-code-generation-from-strings',
  '--abort-on-uncaught-exception',
  '--jitless',
  '--expose-gc',
  '--use-strict',
  '--node-snapshot',
]);
/** Keep ordinary Node/security flags, but never recursively execute a caller's preload. */
export function privateStateExecArgv(args: readonly string[], inherited = true): string[] {
  const kept: string[] = [];
  for (let index = 0; index < args.length; index++) {
    const argument = args[index]!;
    const name = argument.split('=')[0]!.replaceAll('_', '-');
    if (inherited && inheritedValueArguments.has(name)) {
      if (!argument.includes('=')) index++;
      continue;
    }
    if (inherited && inheritedSwitches.has(name)) continue;
    if (preloadArguments.has(argument)) {
      index++;
      continue;
    }
    if (
      preloadArguments.has(argument.split('=')[0]!) ||
      /^-[rep].+/.test(argument) ||
      /^--(?:test(?:-|=|$)|watch(?:-|=|$)|check$)/.test(argument) ||
      argument === '-c'
    ) {
      if (/^--test-/.test(argument) && !argument.includes('=') && args[index + 1]?.[0] !== '-')
        index++;
      continue;
    }
    kept.push(argument);
  }
  return kept;
}

export function privateStateNodeOptions(value = '', inherited = false): string {
  const args: string[] = [];
  let word = '',
    quoted = false,
    escaped = false;
  for (const character of value) {
    if (escaped) {
      word += character;
      escaped = false;
    } else if (quoted && character === '\\') escaped = true;
    else if (character === '"') quoted = !quoted;
    else if (/\s/.test(character) && !quoted) {
      if (word) args.push(word);
      word = '';
    } else word += character;
  }
  if (quoted || escaped) throw new Error('Invalid private state Node options');
  if (word) args.push(word);
  return privateStateExecArgv(args, inherited)
    .map((argument) => JSON.stringify(argument))
    .join(' ');
}

export function privateStatePath(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= privateStateProtocol.pathCharacters &&
    !value.includes('\0') &&
    Buffer.byteLength(value) <= privateStateProtocol.pathBytes &&
    Buffer.from(value).toString('utf8') === value
  );
}

function privateStateWorkerOptions(): Pick<WorkerOptions, 'execArgv' | 'env'> {
  return {
    // Let Node inherit its supported isolate options rather than re-submit all
    // reflected process defaults. Override only to prevent recursive bootstrap.
    execArgv: process.execArgv.some(
      (argument) => bootstrapArguments.has(argument.split('=')[0]!) || /^-[rep].+/.test(argument),
    )
      ? privateStateExecArgv(process.execArgv)
      : undefined,
    env: { ...process.env, NODE_OPTIONS: privateStateNodeOptions(process.env.NODE_OPTIONS, true) },
  };
}

function failPrivateState(control: Int32Array): void {
  const p = privateStateProtocol;
  for (;;) {
    const state = Atomics.load(control, p.state);
    if (state === p.gone) break;
    if (Atomics.compareExchange(control, p.state, state, p.failed) === state) break;
  }
  Atomics.store(control, p.result, p.failed);
  Atomics.notify(control, p.state);
  Atomics.notify(control, p.result);
}

// A nested Worker is forcibly stopped with its owning isolate. Its JS exit
// hooks cannot reap a native child. Only the main isolate creates transport
// Workers; an owner-held port binds each one's lifetime to its actual caller.
type PrivateStateStart = {
  kind: 'windows-private-state-start' | 'windows-trace-start';
  module: string;
  shared: SharedArrayBuffer;
  ownerPort: MessagePort;
  diagnostics?: SharedArrayBuffer;
  trace?: {
    identity: Pick<WindowsDesktopIdentity, 'pid' | 'startedAtUnixMs' | 'executable'>;
    root: string;
    response: SharedArrayBuffer;
  };
};
type NativeTransportRecord = {
  worker: Worker;
  owner: Worker;
  control: Int32Array;
  exited: Promise<boolean>;
};
type PrivateStateSupervisor = {
  starts: Map<string, (request: PrivateStateStart, source: number) => void>;
  workers: Set<NativeTransportRecord>;
  retiring: WeakSet<Worker>;
};
const supervisorKey = Symbol.for('codex-manage.windows-private-state-supervisor.v1');
function registerFixedNativeTransport(kind: PrivateStateStart['kind']): void {
  if (!isMainThread) return;
  const globals = globalThis as typeof globalThis & {
    [supervisorKey]?: PrivateStateSupervisor;
  };
  const supervisor = (globals[supervisorKey] ??= {
    starts: new Map(),
    workers: new Set(),
    retiring: new WeakSet(),
  });
  if (!supervisor.starts.size) {
    process.on('workerMessage', (request: PrivateStateStart, source: number) => {
      if (source <= 0) return;
      if (request?.kind === 'windows-private-state-start')
        supervisor.starts.get(request.module)?.(request, source);
      else if (request?.kind === 'windows-trace-start')
        supervisor.starts.get('trace:' + request.module)?.(request, source);
    });
    process.on('beforeExit', () => {
      const p = privateStateProtocol;
      const workers = [...supervisor.workers];
      const deadline = performance.now() + p.closeMs;
      for (const { worker, control } of workers) {
        failPrivateState(control);
        worker.postMessage({ kind: 'close' });
      }
      for (const { control } of workers) {
        for (;;) {
          const state = Atomics.load(control, p.state);
          if (state === p.gone || performance.now() >= deadline) break;
          Atomics.wait(control, p.state, state, deadline - performance.now());
        }
      }
    });
  }
  const key = kind === 'windows-trace-start' ? 'trace:' + import.meta.url : import.meta.url;
  if (supervisor.starts.has(key)) return;
  supervisor.starts.set(key, (request, source) => {
    const p = privateStateProtocol;
    if (
      !(request.ownerPort instanceof MessagePort) ||
      !(request.shared instanceof SharedArrayBuffer) ||
      request.shared.byteLength !== p.words * 4 + p.responseBytes
    )
      return;
    const control = new Int32Array(request.shared, 0, p.words);
    const trace = kind === 'windows-trace-start' ? request.trace : undefined;
    const fields = [
      'kind',
      'module',
      'shared',
      'ownerPort',
      ...(trace ? ['trace'] : ['diagnostics']),
    ];
    if (
      request.kind !== kind ||
      (!trace &&
        (!(request.diagnostics instanceof SharedArrayBuffer) ||
          request.diagnostics.byteLength !== privateStateNativeDiagnostics.words * 4)) ||
      Object.keys(request).some((key) => !fields.includes(key)) ||
      (kind === 'windows-trace-start' &&
        (!trace ||
          Object.keys(trace).some((key) => !['identity', 'root', 'response'].includes(key)) ||
          !privateStatePath(trace.root) ||
          !trace.identity ||
          Object.keys(trace.identity).some(
            (key) => !['pid', 'startedAtUnixMs', 'executable'].includes(key),
          ) ||
          !Number.isSafeInteger(trace.identity.pid) ||
          trace.identity.pid <= 0 ||
          !Number.isSafeInteger(trace.identity.startedAtUnixMs) ||
          trace.identity.startedAtUnixMs <= 0 ||
          !privateStatePath(trace.identity.executable) ||
          !(trace.response instanceof SharedArrayBuffer) ||
          trace.response.byteLength !== 48 * 1024 * 1024 + 8)) ||
      Atomics.load(control, p.state) !== p.starting ||
      Atomics.compareExchange(control, p.request, 0, -1) !== 0
    ) {
      request.ownerPort.close();
      return;
    }
    try {
      // The dispatcher supplies Node's actual sender ID. Only its currently
      // registered Worker object may own this transport, never a remembered ID.
      const owner = getWindowsProcessWorker(source);
      if (!owner) throw new Error('Registered native transport owner required');
      if (supervisor.retiring.has(owner)) throw new Error('Native owner is retiring');
      // Entry is fixed by this already-loaded module, never supplied by the sender.
      const entry = new URL(
        trace ? './desktop-trace-native-worker.js' : './desktop-private-state-native-worker.js',
        import.meta.url,
      );
      const options = privateStateWorkerOptions();
      if (Atomics.load(control, p.state) !== p.starting)
        throw new Error('Private state owner closed before startup');
      const worker = new Worker(entry, {
        ...options,
        workerData: {
          kind: trace ? 'windows-trace-native' : 'windows-private-state',
          entryURL: entry.href,
          windowsProcessGate: getWindowsProcessGate(),
          shared: trace?.response ?? request.shared,
          ...(!trace ? { diagnostics: request.diagnostics } : {}),
          ownerPort: request.ownerPort,
          ...(trace
            ? { identity: trace.identity, root: trace.root, lifecycle: request.shared }
            : {}),
        },
        transferList: [request.ownerPort],
      });
      let resolveExit!: (complete: boolean) => void;
      const exited = new Promise<boolean>((resolve) => {
        resolveExit = resolve;
      });
      const record = { worker, owner, control, exited };
      registerWindowsProcessWorker(worker);
      observeDiagnosticWorker(worker, owner);
      supervisor.workers.add(record);
      const ownerExited = () => {
        // The transport can be blocked in an atomic gate wait and cannot yet
        // dispatch ownerPort.close. Its shared startup predicate must fail now.
        failPrivateState(control);
        worker.postMessage({ kind: 'close' });
      };
      // Fail startup before the registry's exit CAS releases/notifies a gate
      // that this owner held, so a waiting transport cannot wake still active.
      owner.prependOnceListener('exit', ownerExited);
      worker.on('error', (error) => {
        if (!trace)
          privateStateNativeFailure(new Int32Array(request.diagnostics!), 'isolate-error', error);
        failPrivateState(control);
      });
      worker.once('exit', (exitCode) => {
        owner.removeListener('exit', ownerExited);
        const reaped = Atomics.load(control, p.state) === p.gone;
        if (!trace && !reaped)
          privateStateNativeFailure(new Int32Array(request.diagnostics!), 'isolate-exit', {
            status: exitCode,
          });
        failPrivateState(control);
        if (!Atomics.load(control, p.pid)) {
          Atomics.store(control, p.state, p.gone);
          Atomics.notify(control, p.state);
        }
        // A stopped isolate with an owned child but no gone acknowledgement is
        // not proof of recovery. Retain that record and block replacement.
        const complete = reaped || !Atomics.load(control, p.pid);
        if (complete) supervisor.workers.delete(record);
        resolveExit(complete);
      });
      worker.unref();
    } catch {
      failPrivateState(control);
      Atomics.store(control, p.state, p.gone);
      Atomics.notify(control, p.state);
      request.ownerPort.close();
    }
  });
}
registerFixedNativeTransport('windows-private-state-start');

/** Main bootstrap before any reader isolate starts; both executable entries stay fixed. */
export function registerWindowsTraceTransport(): void {
  registerFixedNativeTransport('windows-trace-start');
}

/** Main owns retirement; no numeric PID can authorize a replacement generation. */
export async function retireWindowsNativeOwner(owner: Worker): Promise<boolean> {
  if (!isMainThread) throw new Error('Main native retirement supervisor required');
  const globals = globalThis as typeof globalThis & {
    [supervisorKey]?: PrivateStateSupervisor;
  };
  const supervisor = globals[supervisorKey];
  if (!supervisor) return true;
  supervisor.retiring.add(owner);
  const records = [...supervisor.workers].filter((record) => record.owner === owner);
  for (const record of records) {
    record.worker.ref();
    failPrivateState(record.control);
    try {
      record.worker.postMessage({ kind: 'close' });
    } catch {
      // Its retained exit result, rather than a send error, decides retirement.
    }
  }
  const results = await Promise.all(records.map((record) => record.exited));
  return results.every(Boolean);
}

export function createWindowsTraceTransport(
  identity: WindowsDesktopIdentity,
  root: string,
  response: SharedArrayBuffer,
) {
  if (isMainThread) throw new Error('Trace reader isolate required');
  const p = privateStateProtocol;
  const shared = new SharedArrayBuffer(p.words * 4 + p.responseBytes);
  const control = new Int32Array(shared, 0, p.words);
  const { port1: port, port2: ownerPort } = new MessageChannel();
  const request: PrivateStateStart = {
    kind: 'windows-trace-start',
    module: import.meta.url,
    shared,
    ownerPort,
    trace: {
      identity: {
        pid: identity.pid,
        startedAtUnixMs: identity.startedAtUnixMs,
        executable: identity.executable,
      },
      root,
      response,
    },
  };
  void postMessageToThread(0, request, [ownerPort], p.startupMs).catch(() => {
    failPrivateState(control);
    port.close();
  });
  return {
    port,
    lifecycle: shared,
    close() {
      failPrivateState(control);
      port.postMessage({ kind: 'close' });
      port.close();
    },
  };
}

/** One synchronous caller per isolate. Optional entry is an internal fault-fixture seam. */
export function createWindowsPrivateStateBackend(
  entry = new URL('./desktop-private-state-native-worker.js', import.meta.url),
  limits: Partial<{ startupMs: number; operationMs: number; closeMs: number }> = {},
) {
  const p = privateStateProtocol;
  const timeouts = {
    startupMs: p.startupMs,
    operationMs: p.operationMs,
    closeMs: p.closeMs,
    ...limits,
  };
  for (const name of ['startupMs', 'operationMs', 'closeMs'] as const)
    if (!Number.isSafeInteger(timeouts[name]) || timeouts[name] <= 0 || timeouts[name] > p[name])
      throw new Error('Invalid private state timeout');
  const shared = new SharedArrayBuffer(p.words * 4 + p.responseBytes);
  const control = new Int32Array(shared, 0, p.words);
  const bytes = new Uint8Array(shared, p.words * 4);
  const diagnostics = new SharedArrayBuffer(privateStateNativeDiagnostics.words * 4);
  const native = new Int32Array(diagnostics);
  let worker: Worker | MessagePort;
  if (isMainThread) {
    worker = new Worker(entry, {
      ...privateStateWorkerOptions(),
      workerData: {
        kind: 'windows-private-state',
        entryURL: entry.href,
        shared,
        diagnostics,
        windowsProcessGate: getWindowsProcessGate(),
      },
    });
    registerWindowsProcessWorker(worker);
    observeDiagnosticWorker(worker);
  } else {
    if (entry.href !== new URL('./desktop-private-state-native-worker.js', import.meta.url).href)
      throw new Error('Private state supervisor requires its fixed worker entry');
    const channel = new MessageChannel();
    worker = channel.port1;
    const request: PrivateStateStart = {
      kind: 'windows-private-state-start',
      module: import.meta.url,
      shared,
      diagnostics,
      ownerPort: channel.port2,
    };
    void postMessageToThread(0, request, [channel.port2], timeouts.startupMs).catch(() => {
      failPrivateState(control);
      channel.port1.close();
    });
  }
  const generation = randomUUID();
  const started = performance.now();
  let phase = 'startup',
    waitKind = 'sync',
    waitMs = 0;
  let closed = false,
    busy = false,
    nextId = 0;
  const record = (stage: string, extra: Record<string, unknown> = {}) =>
    diagnosticEvent({
      scope: 'private-state',
      generation,
      stage,
      elapsedMs: performance.now() - started,
      state: Atomics.load(control, p.state),
      pid: Atomics.load(control, p.pid),
      workerId: worker instanceof Worker ? worker.threadId : undefined,
      request: nextId,
      waitKind,
      ...extra,
    });
  record('created');
  function unavailable(): Error {
    return Object.assign(new Error('Windows private state unavailable'), {
      code: 'WINDOWS_PRIVATE_STATE_UNAVAILABLE',
      stage: phase,
      waitMs,
      nativeStage: privateStateNativeDiagnostics.stages[Atomics.load(native, 0) - 1],
      nativeFailure: privateStateNativeDiagnostics.failures[(Atomics.load(native, 1) & 0xff) - 1],
      nativeCode: privateStateNativeDiagnostics.codes[Atomics.load(native, 2) - 1],
      nativeStatus: Atomics.load(native, 1) & 0x100 ? Atomics.load(native, 3) : undefined,
      helperStage: privateStateNativeDiagnostics.helperStages[Atomics.load(native, 4) - 1],
      nativeType: privateStateNativeDiagnostics.nativeTypes[Atomics.load(native, 5) - 1],
      nativeWin32: Atomics.load(native, 6) || undefined,
      nativeRejection: privateStateNativeDiagnostics.rejections[Atomics.load(native, 7) - 1],
    });
  }
  function fail(): void {
    failPrivateState(control);
    worker.postMessage({ kind: 'close' });
  }
  worker.on('error', (error) => {
    privateStateNativeFailure(native, 'isolate-error', error);
    record('isolate-error');
    fail();
  });
  const exited = () => {
    fail();
    // Without a child PID there is no native operation or child left to retire.
    if (!Atomics.load(control, p.pid)) {
      Atomics.store(control, p.state, p.gone);
      Atomics.notify(control, p.state);
    }
  };
  if (worker instanceof Worker)
    worker.once('exit', (exitCode) => {
      if (!closed && Atomics.load(control, p.state) !== p.gone)
        privateStateNativeFailure(native, 'isolate-exit', { status: exitCode });
      record('isolate-exit', { exitCode });
      exited();
    });
  else worker.once('close', fail);
  worker.unref();
  function close(): void {
    record('close', { initiator: 'owner' });
    closed = true;
    process.removeListener('beforeExit', cleanup);
    process.removeListener('exit', cleanup);
    parentPort?.removeListener('close', cleanup);
    if (Atomics.load(control, p.state) === p.gone) return;
    fail();
    const deadline = performance.now() + timeouts.closeMs;
    for (;;) {
      const state = Atomics.load(control, p.state);
      if (state === p.gone) return;
      const remaining = deadline - performance.now();
      if (remaining <= 0) throw unavailable();
      Atomics.wait(control, p.state, state, remaining);
    }
  }
  function cleanup(): void {
    try {
      close();
    } catch {
      // Shared state is already failed; never publish or retry an unknown operation.
    }
  }
  process.once('beforeExit', cleanup);
  process.once('exit', cleanup);
  parentPort?.once('close', cleanup);
  function* operation(
    action: WindowsPrivateStateAction,
    path: string,
  ): Generator<{ index: number; expected: number; timeout: number }, void, void> {
    if (
      closed ||
      busy ||
      !['protect', 'protect-file', 'check'].includes(action) ||
      !privateStatePath(path) ||
      !privateStatePath(process.cwd())
    )
      throw unavailable();
    busy = true;
    const operationAt = performance.now();
    waitMs = 0;
    phase = 'startup';
    record('call', { action });
    try {
      const startup = performance.now() + timeouts.startupMs;
      while (Atomics.load(control, p.state) === p.starting) {
        const remaining = startup - performance.now();
        if (remaining <= 0) {
          phase = 'startup-deadline';
          throw unavailable();
        }
        record('startup-wait', { remainingMs: remaining });
        yield { index: p.state, expected: p.starting, timeout: remaining };
      }
      if (
        Atomics.load(control, p.state) !== p.ready ||
        performance.now() >= startup ||
        nextId === 0x7fffffff
      )
        throw unavailable();
      phase = 'response';
      record('startup-ready', { elapsedMs: performance.now() - operationAt });
      const id = ++nextId;
      const deadline = performance.now() + timeouts.operationMs;
      Atomics.store(control, p.request, id);
      Atomics.store(control, p.result, p.waiting);
      worker.postMessage({
        kind: 'call',
        id,
        action,
        path,
        cwd: process.cwd(),
        deadline,
        sentAt: performance.now(),
      });
      while (Atomics.load(control, p.result) === p.waiting) {
        const remaining = deadline - performance.now();
        if (remaining <= 0) {
          phase = 'response-deadline';
          throw unavailable();
        }
        record('response-wait', { remainingMs: remaining });
        yield { index: p.result, expected: p.waiting, timeout: remaining };
      }
      const length = Atomics.load(control, p.length);
      if (
        Atomics.load(control, p.state) !== p.ready ||
        performance.now() >= deadline ||
        Atomics.load(control, p.response) !== id ||
        length <= 0 ||
        length > bytes.length
      )
        throw unavailable();
      const response = Buffer.from(bytes.subarray(0, length)).toString('utf8');
      if (Atomics.load(control, p.result) === p.ok && response === 'PRIVATE_STATE_OK') return;
      if (
        Atomics.load(control, p.result) === p.rejected &&
        /^UNSAFE_WINDOWS_PRIVATE_STATE:[A-Za-z][A-Za-z0-9]{0,127}$/.exec(response)?.[0] === response
      )
        throw Object.assign(new Error('Windows private state rejected'), {
          code: 'UNSAFE_WINDOWS_PRIVATE_STATE',
          nativeError: response,
        });
      throw unavailable();
    } catch (error) {
      record(phase, { code: (error as { code?: string }).code, waitMs });
      if ((error as { code?: string }).code !== 'UNSAFE_WINDOWS_PRIVATE_STATE') fail();
      throw error;
    } finally {
      record('call-finished', { action, elapsedMs: performance.now() - operationAt, waitMs });
      busy = false;
    }
  }
  return {
    get status() {
      return {
        closed,
        state: Atomics.load(control, p.state),
        gone: Atomics.load(control, p.state) === p.gone,
        pid: Atomics.load(control, p.pid),
      };
    },
    call(action: WindowsPrivateStateAction, path: string): void {
      waitKind = 'sync';
      for (const wait of operation(action, path)) {
        const before = performance.now();
        Atomics.wait(control, wait.index, wait.expected, wait.timeout);
        waitMs += performance.now() - before;
      }
    },
    async callAsync(action: WindowsPrivateStateAction, path: string): Promise<void> {
      waitKind = 'async';
      const iterator = operation(action, path);
      let next = iterator.next();
      if (next.done) return;
      worker.ref();
      try {
        while (!next.done) {
          const before = performance.now();
          await Atomics.waitAsync(
            control,
            next.value.index,
            next.value.expected,
            next.value.timeout,
          ).value;
          waitMs += performance.now() - before;
          next = iterator.next();
        }
      } finally {
        iterator.return();
        worker.unref();
      }
    },
    close,
  };
}
