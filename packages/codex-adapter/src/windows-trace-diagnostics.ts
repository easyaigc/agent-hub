import { channel } from 'node:diagnostics_channel';
import { threadId } from 'node:worker_threads';

type Phase =
  | 'gate_wait'
  | 'native_start'
  | 'spawn'
  | 'native_response'
  | 'parse'
  | 'verify'
  | 'verify_inputs'
  | 'verify_inputs_final'
  | 'prepare'
  | 'source_fence'
  | 'commit'
  | 'close'
  | 'reap'
  | 'state';
export type WindowsTraceFailureReason =
  | 'ok'
  | 'aborted'
  | 'phase_deadline'
  | 'native_rejected'
  | 'transport_lost'
  | 'source_changed'
  | 'shutdown_incomplete'
  | 'invalid_message';
type Fields = {
  phase?: Phase;
  operation?: 'read' | 'startup' | 'retirement' | 'state' | 'parser';
  stage?: 'entered' | 'prepared' | 'committed' | 'interrupted' | 'snapshot';
  parserReadSnapshotCalls?: number;
  parserCatalogWalks?: number;
  parserGuardCount?: number;
  selectedDeadlineKind?: Fields['deadlineKind'];
  deadlineTriggered?: boolean;
  deadlineKind?:
    | 'phase'
    | 'shared_rpc'
    | 'http'
    | 'combined'
    | 'shared_or_http'
    | 'native_budget'
    | 'native_response'
    | 'startup'
    | 'unknown';
  deadlineMask?: number;
  phaseRemainingMs?: number;
  request?: number | undefined;
  remainingMs?: number;
  readerExited?: boolean;
  transportExited?: boolean;
  childExited?: boolean;
  stdioClosed?: boolean;
};
const summaries = channel('agent-hub.windows-trace.summary');
const reasons = new Set([
  'ok',
  'aborted',
  'phase_deadline',
  'native_rejected',
  'transport_lost',
  'source_changed',
  'shutdown_incomplete',
  'invalid_message',
]);
const phaseNames = new Set([
  'gate_wait',
  'native_start',
  'spawn',
  'native_response',
  'parse',
  'verify',
  'verify_inputs',
  'verify_inputs_final',
  'prepare',
  'source_fence',
  'commit',
  'close',
  'reap',
  'state',
]);

/** Fixed numeric metadata only. Never serialize a request, error, path or model body. */
export function createWindowsTraceDiagnostics(
  scope: 'reader' | 'client' | 'native' | 'host',
  generation = 0,
  context: Fields = {},
) {
  const startedAt = performance.now();
  const native: Partial<
    Record<
      'P' | 'S' | 'B' | 'T',
      {
        calls: number;
        completed: number;
        failed: number;
        pending: number;
        items: number;
        elapsedMs: number;
        waitMs: number;
        decodeMs: number;
      }
    >
  > = {};
  const phases: Partial<Record<Phase, { calls: number; elapsedMs: number }>> = {};
  let current: Phase | undefined;
  let last: Phase | undefined;
  const begin = (name: Phase) => {
    if (!phaseNames.has(name)) return () => {};
    const before = performance.now(),
      previous = current;
    current = last = name;
    let ended = false;
    return () => {
      if (ended) return;
      ended = true;
      const value = (phases[name] ??= { calls: 0, elapsedMs: 0 });
      value.calls++;
      value.elapsedMs += performance.now() - before;
      current = previous;
    };
  };
  return {
    get enabled() {
      return summaries.hasSubscribers || process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS === '1';
    },
    begin,
    nativeCall(kind: 'P' | 'S' | 'B' | 'T', items = 0) {
      if (!summaries.hasSubscribers && process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS !== '1')
        return undefined;
      if (!['P', 'S', 'B', 'T'].includes(kind))
        return {
          decoding() {},
          decoded() {},
          waited() {},
          decode<T>(body: () => T): T {
            return body();
          },
          end(_completed: boolean) {},
        };
      const value = (native[kind] ??= {
        calls: 0,
        completed: 0,
        failed: 0,
        pending: 0,
        items: 0,
        elapsedMs: 0,
        waitMs: 0,
        decodeMs: 0,
      });
      const started = performance.now();
      value.calls++;
      value.pending++;
      value.items += Number.isSafeInteger(items) && items >= 0 && items <= 8 ? items : 0;
      let decodeStarted: number | undefined;
      let waitEnded = false,
        ended = false;
      return {
        decoding() {
          decodeStarted = performance.now();
        },
        decoded() {
          if (decodeStarted !== undefined) {
            value.decodeMs += performance.now() - decodeStarted;
            decodeStarted = undefined;
          }
        },
        waited() {
          if (waitEnded) return;
          waitEnded = true;
          value.waitMs += performance.now() - started;
        },
        decode<T>(body: () => T): T {
          const before = performance.now();
          try {
            return body();
          } finally {
            value.decodeMs += performance.now() - before;
          }
        },
        end(completed: boolean) {
          if (ended) return;
          ended = true;
          if (decodeStarted !== undefined) value.decodeMs += performance.now() - decodeStarted;
          value.pending--;
          if (completed) value.completed++;
          else value.failed++;
          value.elapsedMs += performance.now() - started;
        },
      };
    },
    async asyncPhase<T>(name: Phase, body: () => Promise<T>): Promise<T> {
      if (!phaseNames.has(name)) return body();
      const before = performance.now(),
        previous = current;
      current = last = name;
      try {
        return await body();
      } finally {
        const value = (phases[name] ??= { calls: 0, elapsedMs: 0 });
        value.calls++;
        value.elapsedMs += performance.now() - before;
        current = previous;
      }
    },
    phase<T>(name: Phase, body: () => T): T {
      if (!phaseNames.has(name)) return body();
      const before = performance.now();
      const previous = current;
      current = name;
      last = name;
      try {
        return body();
      } finally {
        const value = (phases[name] ??= { calls: 0, elapsedMs: 0 });
        value.calls++;
        value.elapsedMs += performance.now() - before;
        current = previous;
      }
    },
    end(reason: WindowsTraceFailureReason, fields: Fields = {}): void {
      if (!summaries.hasSubscribers && process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS !== '1')
        return;
      const projected = Object.fromEntries(
        (
          [
            'phase',
            'operation',
            'stage',
            'deadlineKind',
            'selectedDeadlineKind',
            'deadlineTriggered',
            'parserReadSnapshotCalls',
            'parserCatalogWalks',
            'parserGuardCount',
            'deadlineMask',
            'phaseRemainingMs',
            'request',
            'remainingMs',
            'readerExited',
            'transportExited',
            'childExited',
            'stdioClosed',
          ] as const
        ).map((key) => [key, fields[key] ?? context[key]]),
      ) as Fields;
      const parser = ['parserReadSnapshotCalls', 'parserCatalogWalks', 'parserGuardCount'].some(
        (key) => Number.isSafeInteger(projected[key as keyof Fields]),
      );
      if (parser && projected.operation === undefined) projected.operation = 'parser';
      if (parser && projected.stage === undefined) projected.stage = 'snapshot';
      const event = {
        schemaVersion: 1,
        scope: scope === 'reader' || scope === 'native' || scope === 'host' ? scope : 'client',
        reason: reasons.has(reason) ? reason : 'invalid_message',
        generation: Number.isSafeInteger(generation) && generation >= 0 ? generation : 0,
        pid: process.pid,
        threadId,
        monotonicNs: String(process.hrtime.bigint()),
        utc: new Date().toISOString(),
        elapsedMs: performance.now() - startedAt,
        window:
          projected.operation === 'parser'
            ? 'snapshot'
            : Number.isSafeInteger(projected.request)
              ? 'request_phase'
              : 'lifetime',
        phase: phaseNames.has(fields.phase!)
          ? fields.phase
          : (current ?? last ?? (phaseNames.has(context.phase!) ? context.phase : undefined)),
        phases: structuredClone(phases),
        native: structuredClone(native),
        ...Object.fromEntries(
          (['operation', 'stage', 'deadlineKind', 'selectedDeadlineKind'] as const).flatMap(
            (key) => {
              const allowed =
                key === 'operation'
                  ? ['read', 'startup', 'retirement', 'state', 'parser']
                  : key === 'stage'
                    ? ['entered', 'prepared', 'committed', 'interrupted', 'snapshot']
                    : [
                        'phase',
                        'shared_rpc',
                        'http',
                        'combined',
                        'shared_or_http',
                        'native_budget',
                        'native_response',
                        'startup',
                        'unknown',
                      ];
              return allowed.includes(projected[key]!) ? [[key, projected[key]]] : [];
            },
          ),
        ),
        ...Object.fromEntries(
          (['parserReadSnapshotCalls', 'parserCatalogWalks', 'parserGuardCount'] as const)
            .filter((key) => Number.isSafeInteger(projected[key]) && projected[key]! >= 0)
            .map((key) => [key, projected[key]]),
        ),
        ...(typeof projected.deadlineTriggered === 'boolean'
          ? { deadlineTriggered: projected.deadlineTriggered }
          : {}),
        ...(Number.isSafeInteger(projected.deadlineMask) &&
        projected.deadlineMask! >= 0 &&
        projected.deadlineMask! <= 7
          ? { deadlineMask: projected.deadlineMask }
          : {}),
        ...(Number.isFinite(projected.phaseRemainingMs)
          ? { phaseRemainingMs: projected.phaseRemainingMs }
          : {}),
        ...(Number.isSafeInteger(projected.request) ? { request: projected.request } : {}),
        ...(Number.isFinite(projected.remainingMs) ? { remainingMs: projected.remainingMs } : {}),
        ...Object.fromEntries(
          (['readerExited', 'transportExited', 'childExited', 'stdioClosed'] as const)
            .filter((key) => typeof projected[key] === 'boolean')
            .map((key) => [key, projected[key]]),
        ),
      };
      // Observer failures must never turn a rejected read into success or interrupt cleanup.
      try {
        // Serialize our fixed projection before a subscriber can mutate its copy.
        const line =
          process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS === '1'
            ? JSON.stringify({ windowsTrace: event }) + '\n'
            : undefined;
        if (summaries.hasSubscribers) summaries.publish(event);
        if (line !== undefined) process.stderr.write(line);
      } catch {
        /* Bounded best-effort metadata; no fallback filesystem writes. */
      }
    },
  };
}

export type WindowsTraceDiagnostics = ReturnType<typeof createWindowsTraceDiagnostics>;
