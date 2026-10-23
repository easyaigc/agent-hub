import { parentPort, workerData } from 'node:worker_threads';
import { randomUUID } from 'node:crypto';
import { DesktopSuggestionObserver } from './desktop-suggestion-observer.js';
import { inspectWindowsPipe, type WindowsTraceInstance } from './desktop-platform.js';
import { createWindowsTraceBackend } from './desktop-trace-native.js';
import { machineRuntimeTargetKey, type RuntimeTarget } from '@codex-manage/protocol';
import { windowsSuggestionWorkerLimits as limits } from './desktop-suggestion-worker-limits.js';

export type SuggestionWorkerScope = {
  directory: string;
  root: string;
  traceRoot?: string | undefined;
  target: RuntimeTarget;
};
/** Fixture callers can replace only instance evidence in a separate test entry. */
export async function runSuggestionReadWorker(
  scope: SuggestionWorkerScope,
  fixtureInstance?: (inspect: () => WindowsTraceInstance) => WindowsTraceInstance,
): Promise<void> {
  if (!scope.traceRoot) {
    parentPort?.postMessage({ kind: 'state', available: false, sampledAt: performance.now() });
    return;
  }
  const startup = new AbortController();
  let native: Awaited<ReturnType<typeof createWindowsTraceBackend>> | undefined;
  let observer: DesktopSuggestionObserver | undefined;
  let heartbeat: NodeJS.Timeout | undefined;
  let prepared:
    | {
        id: number;
        generation: number;
        nonce: string;
        target: RuntimeTarget;
        threadId: string;
        sourceTurnId: string;
        deadlineAt: number;
        receipt: ReturnType<DesktopSuggestionObserver['prepareBoundSource']>;
        timer: NodeJS.Timeout;
      }
    | undefined;
  let lastId = 0;
  const disposePrepared = () => {
    const old = prepared;
    prepared = undefined;
    if (old) {
      clearTimeout(old.timer);
      old.receipt.dispose();
    }
  };
  let ending = false;
  function close(): void {
    if (ending) return;
    ending = true;
    startup.abort();
    clearInterval(heartbeat);
    try {
      disposePrepared();
    } finally {
      try {
        observer?.close();
      } finally {
        try {
          native?.close();
        } finally {
          parentPort?.close();
        }
      }
    }
  }
  const startupMessage = (request: { kind?: string }) => {
    if (request?.kind === 'close') close();
  };
  // This must precede native startup: the client can close or terminate us
  // before the native helper's bounded ready handshake completes.
  parentPort?.on('message', startupMessage);
  parentPort?.once('close', close);
  try {
    const backend = await createWindowsTraceBackend(
      inspectWindowsPipe(),
      scope.traceRoot,
      startup.signal,
    );
    native = backend;
    if (ending) {
      backend.close();
      return;
    }
    if (fixtureInstance) {
      const inspect = backend.backend.inspect;
      backend.backend.inspect = () => fixtureInstance(inspect);
    }
    const collector = new DesktopSuggestionObserver({
      ...scope,
      traceRoot: scope.traceRoot,
      target: () => scope.target,
      enabled: true,
      nativeWorker: true,
    });
    observer = collector;
    const state = () => {
      try {
        const instance = backend.backend.inspect();
        const available = collector.workerReadiness(instance);
        parentPort?.postMessage({ kind: 'state', available, sampledAt: performance.now() });
        if (prepared && (!available || performance.now() >= prepared.deadlineAt)) {
          parentPort?.postMessage({ kind: 'failed' });
          close();
        }
      } catch {
        parentPort?.postMessage({ kind: 'failed' });
        close();
      }
    };
    heartbeat = setInterval(state, 1000);
    collector.start();
    state();
    if (ending) return;
    parentPort?.removeListener('message', startupMessage);
    parentPort?.on('message', (request: Record<string, unknown>) => {
      if (!request || typeof request !== 'object' || Array.isArray(request)) {
        close();
        return;
      }
      if (request.kind === 'close') {
        close();
        return;
      }
      if (request.kind === 'commit' || request.kind === 'abort') {
        const p = prepared;
        let same = false;
        try {
          same =
            !!p &&
            request.id === p.id &&
            request.generation === p.generation &&
            request.nonce === p.nonce &&
            request.threadId === p.threadId &&
            request.sourceTurnId === p.sourceTurnId &&
            machineRuntimeTargetKey(request.target as RuntimeTarget) ===
              machineRuntimeTargetKey(p.target) &&
            Object.keys(request).length === 7;
        } catch {
          /* No arbitrary continuations. */
        }
        if (!p || !same || performance.now() >= p.deadlineAt || ending) {
          parentPort?.postMessage({ kind: 'failed' });
          close();
          return;
        }
        const identity = {
          id: p.id,
          generation: p.generation,
          nonce: p.nonce,
          target: p.target,
          threadId: p.threadId,
          sourceTurnId: p.sourceTurnId,
        };
        if (request.kind === 'abort') {
          disposePrepared();
          return;
        }
        prepared = undefined;
        clearTimeout(p.timer);
        const phaseDeadline = Math.min(p.deadlineAt, performance.now() + limits.fencedPhaseMs);
        try {
          backend.budget(Math.max(0, phaseDeadline - performance.now()));
          let available = false,
            sampledAt = 0;
          const value = p.receipt.commit((sample) => {
            available = sample;
            sampledAt = performance.now();
          });
          if (performance.now() >= phaseDeadline || !available)
            throw new Error('Commit phase deadline or final readiness failed');
          parentPort?.postMessage({
            kind: 'fenced-result',
            ...identity,
            value,
            available,
            sampledAt,
          });
        } catch (error) {
          parentPort?.postMessage({
            kind: 'fenced-result',
            ...identity,
            error: { status: (error as { status?: number }).status ?? 503 },
          });
        } finally {
          p.receipt.dispose();
          backend.resetBudget();
        }
        return;
      }
      if (
        ending ||
        prepared ||
        (request.kind !== 'read' && request.kind !== 'prepare') ||
        !Number.isSafeInteger(request.id) ||
        (request.id as number) <= lastId ||
        typeof request.threadId !== 'string' ||
        typeof request.sourceTurnId !== 'string' ||
        typeof request.deadlineAt !== 'number' ||
        !Number.isFinite(request.deadlineAt)
      ) {
        parentPort?.postMessage({ kind: 'failed' });
        close();
        return;
      }
      lastId = request.id as number;
      const preparing = request.kind === 'prepare';
      const nonce = randomUUID();
      const identity = {
        id: request.id,
        generation: workerData.generation,
        nonce,
        target: scope.target,
        threadId: request.threadId,
        sourceTurnId: request.sourceTurnId,
      };
      const deadlineAt = request.deadlineAt;
      const phaseMs = preparing ? limits.fencedPhaseMs : limits.readerMs;
      const phaseDeadline = Math.min(deadlineAt, performance.now() + phaseMs);
      try {
        if (
          preparing &&
          (request.generation !== workerData.generation ||
            !Number.isSafeInteger(request.generation) ||
            (request.generation as number) <= 0 ||
            machineRuntimeTargetKey(request.target as RuntimeTarget) !==
              machineRuntimeTargetKey(scope.target) ||
            Object.keys(request).length !== 7 ||
            deadlineAt > performance.now() + limits.fencedRpcMs)
        )
          throw new Error('Invalid prepared request identity');
        const remaining = Math.floor(phaseDeadline - performance.now());
        if (remaining <= 0) throw new Error('Reader request deadline elapsed');
        backend.budget(Math.min(phaseMs, remaining));
        let available = false;
        let sampledAt = 0;
        if (preparing) {
          const receipt = collector.prepareBoundSource(
            scope.target,
            request.threadId,
            request.sourceTurnId,
            (sample) => {
              available = sample;
              sampledAt = performance.now();
            },
          );
          prepared = {
            ...identity,
            id: request.id as number,
            generation: workerData.generation,
            receipt,
            deadlineAt,
            timer: setTimeout(
              () => {
                parentPort?.postMessage({ kind: 'failed' });
                close();
              },
              Math.max(0, deadlineAt - performance.now()),
            ),
          };
          if (!available || performance.now() >= phaseDeadline)
            throw new Error('Prepare phase unavailable');
          parentPort?.postMessage({ kind: 'prepared', ...identity, available, sampledAt });
          return;
        }
        const value = collector.readBoundSource(
          scope.target,
          request.threadId,
          request.sourceTurnId,
          (sample) => {
            available = sample;
            sampledAt = performance.now();
          },
        );
        parentPort?.postMessage({ kind: 'result', id: request.id, value, available, sampledAt });
      } catch (error) {
        if (preparing) disposePrepared();
        parentPort?.postMessage({
          kind: preparing ? 'prepared' : 'result',
          ...(preparing ? identity : {}),
          id: request.id,
          error: { status: (error as { status?: number }).status ?? 503 },
        });
      } finally {
        backend.resetBudget();
        if (!preparing) state();
      }
    });
  } catch (error) {
    const expected = ending;
    close();
    if (!expected) throw error;
  }
}

if (workerData?.kind === 'windows-suggestion-reader' && workerData.entryURL === import.meta.url)
  runSuggestionReadWorker(workerData.scope).catch(() => {
    parentPort?.postMessage({ kind: 'failed' });
    parentPort?.close();
  });
