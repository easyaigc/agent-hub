import { Worker, isMainThread } from 'node:worker_threads';
import {
  machineRuntimeTargetKey,
  type DesktopNextTurnSuggestionResponse,
} from '@codex-manage/protocol';
import type { SuggestionWorkerScope } from './desktop-suggestion-read-worker.js';
import {
  registerWindowsTraceTransport,
  retireWindowsNativeOwner,
} from './desktop-private-state-native.js';
import { getWindowsProcessGate, registerWindowsProcessWorker } from './windows-process.js';
import { windowsSuggestionWorkerLimits as limits } from './desktop-suggestion-worker-limits.js';
import {
  createWindowsTraceDiagnostics,
  type WindowsTraceFailureReason,
  type WindowsTraceDiagnostics,
} from './windows-trace-diagnostics.js';

export type PreparedSuggestionRead = {
  commit(): Promise<DesktopNextTurnSuggestionResponse>;
  dispose(): void;
};
type FencedPending = {
  id: number;
  generation: number;
  threadId: string;
  sourceTurnId: string;
  startedAt: number;
  deadlineAt: number;
  phase: 'preparing' | 'prepared' | 'committing' | 'committed';
  nonce?: string;
  commitAt?: number;
  resolve: (value: PreparedSuggestionRead) => void;
  commitResolve?: (value: DesktopNextTurnSuggestionResponse) => void;
  reject: (error: Error & { status: number }) => void;
  timer: NodeJS.Timeout;
  detach: () => void;
  diagnostics: WindowsTraceDiagnostics | undefined;
  endPhase: (() => void) | undefined;
  deadlineMask: number;
};

export class DesktopSuggestionWorkerClient {
  private worker: Worker | undefined;
  private availableAt = 0;
  private nextId = 0;
  private generation = 0;
  private prepared: FencedPending | undefined;
  private pending:
    | {
        id: number;
        threadId: string;
        sourceTurnId: string;
        startedAt: number;
        deadlineAt: number;
        resolve: (value: DesktopNextTurnSuggestionResponse) => void;
        reject: (error: Error & { status: number }) => void;
        timer: NodeJS.Timeout;
        diagnostics: WindowsTraceDiagnostics | undefined;
        endPhase: (() => void) | undefined;
      }
    | undefined;
  private closed = false;
  private retry: NodeJS.Timeout | undefined;
  private readonly retiring = new Set<Worker>();
  private readonly exits = new WeakMap<Worker, Promise<void>>();
  private diagnostics = createWindowsTraceDiagnostics('client');
  constructor(
    private readonly scope: SuggestionWorkerScope,
    private readonly entry = new URL('./desktop-suggestion-read-worker.js', import.meta.url),
  ) {}
  get available(): boolean {
    const age = performance.now() - this.availableAt;
    return !this.closed && this.availableAt > 0 && age >= 0 && age < limits.capabilityTtlMs;
  }
  get targetKey(): string {
    return machineRuntimeTargetKey(this.scope.target);
  }
  start(): void {
    if (this.closed || this.worker || this.retiring.size || !this.scope.traceRoot) return;
    if (!isMainThread) throw new Error('Main reader supervisor required');
    registerWindowsTraceTransport();
    const generation = ++this.generation;
    this.diagnostics = createWindowsTraceDiagnostics('client', generation, {
      operation: 'retirement',
    });
    const worker = new Worker(this.entry, {
      workerData: {
        kind: 'windows-suggestion-reader',
        entryURL: this.entry.href,
        windowsProcessGate: getWindowsProcessGate(),
        scope: this.scope,
        generation,
      },
    });
    this.worker = worker;
    this.exits.set(worker, new Promise<void>((resolve) => worker.once('exit', () => resolve())));
    registerWindowsProcessWorker(worker);
    worker.on('message', (input: unknown) => {
      if (this.worker !== worker || this.closed) return;
      // Main's timer can be delayed by CPU/dispatch. No queued result or state
      // may satisfy or renew a generation after its absolute RPC deadline.
      if (this.pending && performance.now() >= this.pending.deadlineAt) {
        this.fail(worker, this.error(), 'phase_deadline');
        return;
      }
      if (
        this.prepared &&
        this.prepared.phase !== 'committed' &&
        performance.now() >= this.prepared.deadlineAt
      ) {
        this.fail(worker, this.error(), 'phase_deadline');
        return;
      }
      if (input === null || typeof input !== 'object' || Array.isArray(input)) {
        this.fail(worker);
        return;
      }
      const message = input as Record<string, unknown>;
      if (message.kind === 'prepared' || message.kind === 'fenced-result') {
        this.acceptFenced(worker, message);
        return;
      }
      if (this.prepared && message.kind !== 'state' && message.kind !== 'failed') {
        this.fail(worker);
        return;
      }
      if (message.kind === 'state') {
        if (typeof message.available !== 'boolean' || !this.validSampleTime(message.sampledAt)) {
          this.fail(worker);
          return;
        }
        this.availableAt = message.available ? message.sampledAt : 0;
      }
      if (message.kind === 'failed') this.fail(worker);
      if (message.kind === 'result' && this.pending && message.id === this.pending.id) {
        const pending = this.pending!;
        if (Object.hasOwn(message, 'error')) {
          clearTimeout(pending.timer);
          this.pending = undefined;
          this.availableAt = 0;
          const error = message.error;
          pending.endPhase?.();
          pending.diagnostics?.end('native_rejected', { request: pending.id });
          pending.reject(
            this.error(
              error !== null &&
                typeof error === 'object' &&
                !Array.isArray(error) &&
                'status' in error
                ? error.status
                : undefined,
            ),
          );
          return;
        }
        const value = message.value as DesktopNextTurnSuggestionResponse | undefined;
        let complete = false;
        try {
          complete =
            value?.schemaVersion === 1 &&
            value.readOnly === true &&
            value.source === 'native-trace' &&
            value.threadId === pending.threadId &&
            value.sourceTurnId === pending.sourceTurnId &&
            machineRuntimeTargetKey(value.target) === machineRuntimeTargetKey(this.scope.target) &&
            (value.suggestion === null ||
              (value.suggestion?.validity === 'native-version' &&
                typeof value.suggestion.id === 'string' &&
                typeof value.suggestion.text === 'string' &&
                typeof value.suggestion.contentSha256 === 'string' &&
                typeof value.suggestion.expiresAt === 'string'));
        } catch {
          // A malformed result cannot refresh readiness or satisfy this read.
        }
        const valid =
          complete &&
          typeof message.available === 'boolean' &&
          this.validSampleTime(message.sampledAt) &&
          message.sampledAt >= pending.startedAt &&
          (message.available || value?.suggestion === null);
        if (performance.now() >= pending.deadlineAt) {
          this.fail(worker, this.error(), 'phase_deadline');
          return;
        }
        clearTimeout(pending.timer);
        this.pending = undefined;
        pending.endPhase?.();
        if (!valid) {
          pending.diagnostics?.end('invalid_message', { request: pending.id });
          this.availableAt = 0;
          pending.reject(this.error());
        } else {
          // The reader sampled its existing final availability guard for this
          // exact result. Deliver it before resolving into the next HTTP read.
          this.availableAt = message.available ? (message.sampledAt as number) : 0;
          pending.diagnostics?.end('ok', {
            request: pending.id,
            remainingMs: pending.deadlineAt - performance.now(),
          });
          pending.resolve(value!);
        }
      }
    });
    worker.on('error', () => this.fail(worker));
    worker.on('exit', () => this.fail(worker));
  }
  private validSampleTime(value: unknown): value is number {
    return (
      typeof value === 'number' && Number.isFinite(value) && value > 0 && value <= performance.now()
    );
  }
  private error(status: unknown = 503): Error & { status: number } {
    return Object.assign(new Error('原桌面建议读取暂不可用'), {
      status: status === 400 || status === 409 || status === 503 ? status : 503,
    });
  }
  private fail(
    worker: Worker,
    error = this.error(),
    reason: WindowsTraceFailureReason = 'transport_lost',
  ): void {
    if (this.worker !== worker) return;
    this.availableAt = 0;
    this.worker = undefined;
    this.retiring.add(worker);
    const diagnostics = this.diagnostics;
    if (this.prepared) {
      const p = this.prepared;
      p.endPhase?.();
      if (p.diagnostics) {
        const boundary = this.deadlineFields(p);
        p.diagnostics.end(reason, {
          request: p.id,
          ...boundary,
          deadlineTriggered: reason === 'phase_deadline',
          ...(reason === 'phase_deadline' ? { deadlineKind: boundary.selectedDeadlineKind } : {}),
        });
      }
    } else if (this.pending) {
      this.pending.endPhase?.();
      this.pending.diagnostics?.end(reason, { operation: 'read', request: this.pending.id });
    } else {
      diagnostics.end(reason, { operation: 'retirement' });
    }
    if (this.prepared) {
      if (this.prepared.nonce && this.prepared.phase !== 'committed') {
        try {
          worker.postMessage({ kind: 'abort', ...this.fenceIdentity(this.prepared) });
        } catch {
          /* The following close retires an unreachable continuation. */
        }
      }
      clearTimeout(this.prepared.timer);
      this.prepared.detach();
      this.prepared.reject(error);
      this.prepared = undefined;
    }
    if (this.pending) {
      clearTimeout(this.pending.timer);
      this.pending.reject(this.error());
      this.pending = undefined;
    }
    try {
      worker.postMessage({ kind: 'close' });
    } catch {
      // A confirmed failed/closed generation cannot be used again.
    }
    const terminate = setTimeout(() => void worker.terminate(), limits.closeMs);
    terminate.unref();
    worker.once('exit', () => clearTimeout(terminate));
    const incomplete = setTimeout(
      () => diagnostics.end('shutdown_incomplete', { operation: 'retirement' }),
      limits.closeMs,
    );
    incomplete.unref();
    // Failure returns immediately. Only this Reader's actual exit and every
    // Main-owned native transport's gone+exit acknowledgement permit a retry.
    void Promise.all([this.exits.get(worker), retireWindowsNativeOwner(worker)]).then(
      ([, nativeComplete]) => {
        clearTimeout(incomplete);
        if (!nativeComplete) {
          diagnostics.end('shutdown_incomplete', {
            operation: 'retirement',
            readerExited: true,
            transportExited: false,
          });
          return;
        }
        this.retiring.delete(worker);
        diagnostics.end('ok', {
          operation: 'retirement',
          readerExited: true,
          transportExited: true,
        });
        if (!this.closed) {
          this.retry = setTimeout(() => {
            this.retry = undefined;
            this.start();
          }, 1000);
          this.retry.unref();
        }
      },
      () => diagnostics.end('shutdown_incomplete', { operation: 'retirement' }),
    );
  }
  read(threadId: string, sourceTurnId: string): Promise<DesktopNextTurnSuggestionResponse> {
    if (!this.available || !this.worker || this.pending || this.prepared)
      return Promise.reject(this.error());
    const worker = this.worker,
      id = ++this.nextId,
      startedAt = performance.now(),
      deadlineAt = startedAt + limits.rpcMs;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => this.fail(worker, this.error(), 'phase_deadline'),
        limits.rpcMs,
      );
      const diagnostics = this.diagnostics.enabled
        ? createWindowsTraceDiagnostics('client', this.generation, {
            operation: 'read',
            request: id,
          })
        : undefined;
      this.pending = {
        id,
        threadId,
        sourceTurnId,
        startedAt,
        deadlineAt,
        resolve,
        reject,
        timer,
        diagnostics,
        endPhase: diagnostics?.begin('prepare'),
      };
      worker.postMessage({
        kind: 'read',
        id,
        threadId,
        sourceTurnId,
        deadlineAt,
      });
    });
  }
  /** One reserved logical operation; no body leaves the Worker before commit. */
  prepare(
    threadId: string,
    sourceTurnId: string,
    httpDeadlineAt: number,
    signal?: AbortSignal,
  ): Promise<PreparedSuggestionRead> {
    if (
      !this.available ||
      !this.worker ||
      this.pending ||
      this.prepared ||
      signal?.aborted ||
      !Number.isFinite(httpDeadlineAt) ||
      httpDeadlineAt <= performance.now()
    )
      return Promise.reject(this.error());
    const worker = this.worker;
    const startedAt = performance.now();
    const deadlineAt = Math.min(startedAt + limits.fencedRpcMs, httpDeadlineAt);
    return new Promise((resolve, reject) => {
      const abort = () => this.fail(worker, this.error(), 'aborted');
      const pending: FencedPending = {
        id: ++this.nextId,
        generation: this.generation,
        threadId,
        sourceTurnId,
        startedAt,
        deadlineAt,
        phase: 'preparing',
        resolve,
        reject,
        timer: setTimeout(
          () => this.fail(worker, this.error(), 'phase_deadline'),
          Math.max(0, Math.min(startedAt + limits.fencedPhaseMs, deadlineAt) - performance.now()),
        ),
        detach: () => signal?.removeEventListener('abort', abort),
        diagnostics: this.diagnostics.enabled
          ? createWindowsTraceDiagnostics('client', this.generation, {
              operation: 'read',
              request: this.nextId,
            })
          : undefined,
        endPhase: undefined,
        deadlineMask:
          httpDeadlineAt < startedAt + limits.fencedRpcMs
            ? 4
            : httpDeadlineAt > startedAt + limits.fencedRpcMs
              ? 2
              : 6,
      };
      pending.endPhase = pending.diagnostics?.begin('prepare');
      this.prepared = pending;
      signal?.addEventListener('abort', abort, { once: true });
      worker.postMessage({
        kind: 'prepare',
        ...this.fenceIdentity(pending),
        deadlineAt,
      });
    });
  }
  private deadlineFields(p: FencedPending) {
    const phaseAt = p.commitAt ?? p.startedAt;
    const phaseDeadline = phaseAt + limits.fencedPhaseMs;
    const waiting = p.phase === 'prepared' || p.phase === 'committed';
    const mask = waiting
      ? p.deadlineMask
      : p.deadlineAt < phaseDeadline
        ? p.deadlineMask
        : p.deadlineAt > phaseDeadline
          ? 1
          : p.deadlineMask | 1;
    return {
      selectedDeadlineKind:
        mask === 1
          ? ('phase' as const)
          : mask === 2
            ? ('shared_rpc' as const)
            : mask === 4
              ? ('http' as const)
              : ('combined' as const),
      deadlineMask: mask,
      remainingMs: p.deadlineAt - performance.now(),
      phaseRemainingMs:
        (waiting ? p.deadlineAt : Math.min(p.deadlineAt, phaseDeadline)) - performance.now(),
    };
  }
  private fenceIdentity(pending: FencedPending) {
    return {
      id: pending.id,
      generation: pending.generation,
      target: this.scope.target,
      threadId: pending.threadId,
      sourceTurnId: pending.sourceTurnId,
      ...(pending.nonce ? { nonce: pending.nonce } : {}),
    };
  }
  private acceptFenced(worker: Worker, message: Record<string, unknown>): void {
    const p = this.prepared;
    const phaseDeadline = p
      ? Math.min(p.deadlineAt, (p.commitAt ?? p.startedAt) + limits.fencedPhaseMs)
      : 0;
    let identity = false;
    try {
      identity =
        !!p &&
        message.id === p.id &&
        message.generation === p.generation &&
        message.threadId === p.threadId &&
        message.sourceTurnId === p.sourceTurnId &&
        machineRuntimeTargetKey(message.target as typeof this.scope.target) ===
          machineRuntimeTargetKey(this.scope.target);
    } catch {
      /* A malformed fixed continuation is never accepted. */
    }
    if (
      !p ||
      !identity ||
      performance.now() >= phaseDeadline ||
      (message.kind === 'prepared' ? p.phase !== 'preparing' : p.phase !== 'committing') ||
      typeof message.nonce !== 'string' ||
      !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(message.nonce) ||
      (p.nonce !== undefined && p.nonce !== message.nonce)
    ) {
      this.fail(worker);
      return;
    }
    if (Object.hasOwn(message, 'error')) {
      const error = message.error;
      this.fail(
        worker,
        this.error(
          error && typeof error === 'object' && 'status' in error ? error.status : undefined,
        ),
      );
      return;
    }
    const keys = [
      'kind',
      'id',
      'generation',
      'target',
      'threadId',
      'sourceTurnId',
      'nonce',
      'available',
      'sampledAt',
    ];
    if (message.kind === 'fenced-result') keys.push('value');
    if (
      Object.keys(message).some((k) => !keys.includes(k)) ||
      message.available !== true ||
      !this.validSampleTime(message.sampledAt) ||
      message.sampledAt < (p.commitAt ?? p.startedAt)
    ) {
      this.fail(worker);
      return;
    }
    if (performance.now() >= phaseDeadline) {
      this.fail(worker, this.error(), 'phase_deadline');
      return;
    }
    this.availableAt = message.sampledAt;
    if (message.kind === 'prepared') {
      p.nonce = message.nonce;
      p.endPhase?.();
      p.diagnostics?.end('ok', { stage: 'prepared', ...this.deadlineFields(p) });
      p.phase = 'prepared';
      clearTimeout(p.timer);
      p.timer = setTimeout(
        () => this.fail(worker, this.error(), 'phase_deadline'),
        Math.max(0, p.deadlineAt - performance.now()),
      );
      p.resolve({
        commit: () => {
          if (
            this.prepared !== p ||
            this.worker !== worker ||
            p.phase !== 'prepared' ||
            performance.now() >= p.deadlineAt
          ) {
            if (this.prepared === p) this.fail(worker);
            return Promise.reject(this.error());
          }
          p.phase = 'committing';
          p.commitAt = performance.now();
          p.diagnostics = this.diagnostics.enabled
            ? createWindowsTraceDiagnostics('client', p.generation, {
                operation: 'read',
                request: p.id,
              })
            : undefined;
          p.endPhase = p.diagnostics?.begin('commit');
          clearTimeout(p.timer);
          p.timer = setTimeout(
            () => this.fail(worker, this.error(), 'phase_deadline'),
            Math.max(
              0,
              Math.min(p.deadlineAt, p.commitAt + limits.fencedPhaseMs) - performance.now(),
            ),
          );
          return new Promise((resolve, reject) => {
            p.commitResolve = resolve;
            p.reject = reject;
            worker.postMessage({ kind: 'commit', ...this.fenceIdentity(p) });
          });
        },
        dispose: () => {
          if (this.prepared !== p) return;
          if (p.phase !== 'committed') {
            this.fail(worker);
            return;
          }
          clearTimeout(p.timer);
          p.detach();
          this.prepared = undefined;
        },
      });
      return;
    }
    const value = message.value as DesktopNextTurnSuggestionResponse | undefined;
    let valid = false;
    try {
      valid =
        value?.schemaVersion === 1 &&
        value.readOnly === true &&
        value.source === 'native-trace' &&
        value.threadId === p.threadId &&
        value.sourceTurnId === p.sourceTurnId &&
        machineRuntimeTargetKey(value.target) === machineRuntimeTargetKey(this.scope.target) &&
        (value.suggestion === null ||
          (value.suggestion?.validity === 'native-version' &&
            typeof value.suggestion.id === 'string' &&
            typeof value.suggestion.text === 'string' &&
            typeof value.suggestion.contentSha256 === 'string' &&
            typeof value.suggestion.expiresAt === 'string'));
    } catch {
      /* Reject malformed response without a partial projection. */
    }
    if (!valid || performance.now() >= phaseDeadline) {
      this.fail(worker);
      return;
    }
    const committedDeadlineFields = p.diagnostics?.enabled ? this.deadlineFields(p) : undefined;
    p.phase = 'committed';
    clearTimeout(p.timer);
    // Keep the busy reservation until Host's final source guard has finished.
    p.endPhase?.();
    p.diagnostics?.end('ok', { stage: 'committed', ...committedDeadlineFields });
    p.commitResolve!(value!);
  }
  close(): void {
    this.closed = true;
    clearTimeout(this.retry);
    this.retry = undefined;
    if (this.worker) this.fail(this.worker, this.error(), 'aborted');
  }
}
