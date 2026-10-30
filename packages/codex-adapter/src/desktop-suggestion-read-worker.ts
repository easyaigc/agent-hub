import { parentPort, workerData } from 'node:worker_threads';
import { randomUUID } from 'node:crypto';
import { DesktopSuggestionObserver } from './desktop-suggestion-observer.js';
import { inspectWindowsPipe, type WindowsTraceInstance } from './desktop-platform.js';
import { createWindowsTraceBackend } from './desktop-trace-native.js';
import { machineRuntimeTargetKey, type RuntimeTarget } from '@codex-manage/protocol';
import { windowsSuggestionWorkerLimits as limits } from './desktop-suggestion-worker-limits.js';
import {
  createWindowsTraceDiagnostics,
  type WindowsTraceDiagnostics,
} from './windows-trace-diagnostics.js';

type DiagnosticReason = Parameters<ReturnType<typeof createWindowsTraceDiagnostics>['end']>[0];

export type SuggestionWorkerScope = {
  directory: string;
  traceRoot?: string | undefined;
  nativeSuggestionsConfig?: string;
  target: RuntimeTarget;
};
/** Fixture callers can replace only instance evidence in a separate test entry. */
export async function runSuggestionReadWorker(
  scope: SuggestionWorkerScope,
  fixtureInstance?: (inspect: () => WindowsTraceInstance) => WindowsTraceInstance,
): Promise<void> {
  const diagnostics = createWindowsTraceDiagnostics('reader', workerData?.generation, {
    operation: 'startup',
  });
  let activeDiagnostics = diagnostics;
  if (!scope.traceRoot) {
    parentPort?.postMessage({ kind: 'state', available: false, sampledAt: performance.now() });
    diagnostics.end('native_rejected');
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
        diagnostics: WindowsTraceDiagnostics;
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
  function close(reason: DiagnosticReason = 'aborted'): void {
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
          try {
            parentPort?.close();
          } finally {
            diagnostics.end(reason, { operation: 'retirement' });
          }
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
    const startBackend = () =>
      createWindowsTraceBackend(
        inspectWindowsPipe(),
        scope.traceRoot!,
        startup.signal,
        () => activeDiagnostics,
      );
    const backend = await (diagnostics.enabled
      ? diagnostics.asyncPhase('native_start', startBackend)
      : startBackend());
    native = backend;
    if (ending) {
      backend.close();
      return;
    }
    diagnostics.end('ok', { operation: 'startup', phase: 'native_start' });
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
    const state = () =>
      diagnostics.phase('state', () => {
        try {
          const instance = backend.backend.inspect();
          const available = collector.workerReadiness(instance);
          parentPort?.postMessage({ kind: 'state', available, sampledAt: performance.now() });
          if (prepared && (!available || performance.now() >= prepared.deadlineAt)) {
            parentPort?.postMessage({ kind: 'failed' });
            close(!available ? 'native_rejected' : 'phase_deadline');
          }
        } catch {
          parentPort?.postMessage({ kind: 'failed' });
          close('native_rejected');
        }
      });
    const pauseHeartbeat = () => {
      clearInterval(heartbeat);
      heartbeat = undefined;
    };
    const resumeHeartbeat = () => {
      if (ending || prepared || heartbeat) return;
      heartbeat = setInterval(() => {
        // A queued interval must not add a P/root sample before commit.
        if (!ending && !prepared) state();
      }, 1000);
    };
    collector.start();
    state();
    if (ending) return;
    resumeHeartbeat();
    parentPort?.removeListener('message', startupMessage);
    parentPort?.on('message', (request: Record<string, unknown>) => {
      if (!request || typeof request !== 'object' || Array.isArray(request)) {
        close('invalid_message');
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
          close(
            p && same && performance.now() >= p.deadlineAt ? 'phase_deadline' : 'invalid_message',
          );
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
          try {
            disposePrepared();
            p.diagnostics.end('aborted', { request: p.id, stage: 'interrupted' });
          } catch {
            parentPort?.postMessage({ kind: 'failed' });
            close('native_rejected');
          } finally {
            resumeHeartbeat();
          }
          return;
        }
        pauseHeartbeat();
        prepared = undefined;
        clearTimeout(p.timer);
        const phaseDeadline = Math.min(p.deadlineAt, performance.now() + limits.fencedPhaseMs);
        const requestDiagnostics = diagnostics.enabled
          ? createWindowsTraceDiagnostics('reader', workerData?.generation, {
              operation: 'read',
              phase: 'commit',
              request: p.id,
            })
          : diagnostics;
        activeDiagnostics = requestDiagnostics;
        requestDiagnostics.end('ok', { stage: 'entered' });
        try {
          backend.budget(Math.max(0, phaseDeadline - performance.now()));
          let available = false,
            sampledAt = 0;
          const value = requestDiagnostics.phase('commit', () =>
            p.receipt.commit((sample) => {
              available = sample;
              sampledAt = performance.now();
            }),
          );
          if (performance.now() >= phaseDeadline || !available)
            throw new Error('Commit phase deadline or final readiness failed');
          parentPort?.postMessage({
            kind: 'fenced-result',
            ...identity,
            value,
            available,
            sampledAt,
          });
          requestDiagnostics.end('ok', {
            request: p.id,
            stage: 'committed',
            remainingMs: p.deadlineAt - performance.now(),
          });
        } catch (error) {
          requestDiagnostics.end(
            performance.now() >= phaseDeadline
              ? 'phase_deadline'
              : (error as { status?: number }).status === 409
                ? 'source_changed'
                : 'native_rejected',
            {
              request: p.id,
              remainingMs: p.deadlineAt - performance.now(),
              phaseRemainingMs: phaseDeadline - performance.now(),
              selectedDeadlineKind: phaseDeadline === p.deadlineAt ? 'shared_or_http' : 'phase',
            },
          );
          parentPort?.postMessage({
            kind: 'fenced-result',
            ...identity,
            error: { status: (error as { status?: number }).status ?? 503 },
          });
        } finally {
          try {
            try {
              p.receipt.dispose();
            } finally {
              backend.resetBudget();
            }
          } catch {
            parentPort?.postMessage({ kind: 'failed' });
            close('native_rejected');
          } finally {
            activeDiagnostics = diagnostics;
            resumeHeartbeat();
          }
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
        close('invalid_message');
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
      const requestDiagnostics = diagnostics.enabled
        ? createWindowsTraceDiagnostics('reader', workerData?.generation, {
            operation: 'read',
            phase: 'prepare',
            request: request.id as number,
          })
        : diagnostics;
      activeDiagnostics = requestDiagnostics;
      requestDiagnostics.end('ok', { stage: 'entered' });
      let failureReason: DiagnosticReason = 'native_rejected';
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
        ) {
          failureReason = 'invalid_message';
          throw new Error('Invalid prepared request identity');
        }
        const remaining = Math.floor(phaseDeadline - performance.now());
        if (remaining <= 0) {
          failureReason = 'phase_deadline';
          throw new Error('Reader request deadline elapsed');
        }
        if (preparing) pauseHeartbeat();
        backend.budget(Math.min(phaseMs, remaining));
        let available = false;
        let sampledAt = 0;
        if (preparing) {
          const receipt = requestDiagnostics.phase('prepare', () =>
            collector.prepareBoundSource(
              scope.target,
              identity.threadId,
              identity.sourceTurnId,
              (sample) => {
                available = sample;
                sampledAt = performance.now();
              },
            ),
          );
          prepared = {
            ...identity,
            id: request.id as number,
            generation: workerData.generation,
            receipt,
            diagnostics: requestDiagnostics,
            deadlineAt,
            timer: setTimeout(
              () => {
                parentPort?.postMessage({ kind: 'failed' });
                requestDiagnostics.end('phase_deadline', {
                  deadlineKind: 'shared_or_http',
                  remainingMs: deadlineAt - performance.now(),
                });
                close('phase_deadline');
              },
              Math.max(0, deadlineAt - performance.now()),
            ),
          };
          if (!available || performance.now() >= phaseDeadline)
            throw new Error('Prepare phase unavailable');
          parentPort?.postMessage({ kind: 'prepared', ...identity, available, sampledAt });
          requestDiagnostics.end('ok', {
            stage: 'prepared',
            remainingMs: deadlineAt - performance.now(),
          });
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
        requestDiagnostics.end('ok', {
          request: request.id as number,
          remainingMs: deadlineAt - performance.now(),
        });
      } catch (error) {
        if (preparing) {
          try {
            disposePrepared();
          } catch {
            close('native_rejected');
          }
        }
        requestDiagnostics.end(
          performance.now() >= phaseDeadline ? 'phase_deadline' : failureReason,
          {
            request: request.id as number,
            remainingMs: deadlineAt - performance.now(),
            phaseRemainingMs: phaseDeadline - performance.now(),
            selectedDeadlineKind: phaseDeadline === deadlineAt ? 'shared_or_http' : 'phase',
          },
        );
        parentPort?.postMessage({
          kind: preparing ? 'prepared' : 'result',
          ...(preparing ? identity : {}),
          id: request.id,
          error: { status: (error as { status?: number }).status ?? 503 },
        });
      } finally {
        try {
          backend.resetBudget();
        } finally {
          activeDiagnostics = diagnostics;
          if (!preparing) state();
          else resumeHeartbeat();
        }
      }
    });
  } catch (error) {
    const expected = ending;
    close('transport_lost');
    if (!expected) throw error;
  }
}

if (workerData?.kind === 'windows-suggestion-reader' && workerData.entryURL === import.meta.url)
  runSuggestionReadWorker(workerData.scope).catch(() => {
    parentPort?.postMessage({ kind: 'failed' });
    parentPort?.close();
  });
