import { createHash } from 'node:crypto';
import type {
  NotificationEvent,
  NotificationKind,
  RuntimeTarget,
  ReadPosition,
} from '@codex-manage/protocol';
import { notificationTargetKey, normalizeNotificationTaskSummary } from '@codex-manage/protocol';

export interface NotificationObservation {
  threadId: string;
  title: string;
  live: boolean;
  runId?: string;
  outcome?: string;
  updatedAt?: string;
  taskSummary?: string;
  requests: { id: string; runId: string; kind: 'approval' | 'question'; taskSummary?: string }[];
  planId?: string;
  planSummary?: string;
  uncertainOperations: string[];
  /** Exact submitted intent, independent of the latest live run's input. */
  uncertainOperationDetails?: {
    id: string;
    runId?: string;
    threadTitle?: string;
    taskSummary?: string;
  }[];
  readTarget?: RuntimeTarget;
  readPosition?: ReadPosition;
}
/** Live observations only. Persisted history and missing owners are never terminal evidence. */
export class NotificationObserver {
  private previous = new Map<string, NotificationObservation>();
  private stopped = new Set<string>();
  private readonly startedAt = Date.now();
  constructor(
    private target: RuntimeTarget,
    previous: readonly NotificationObservation[] = [],
    stopped: readonly string[] = [],
  ) {
    for (const value of previous) if (value.live) this.previous.set(value.threadId, value);
    for (const id of stopped) this.stopped.add(id);
  }
  markStopped(runId: string) {
    this.stopped.add(runId);
  }
  observe(value: NotificationObservation): { events: NotificationEvent[]; activeIds: string[] } {
    const previous = this.previous.get(value.threadId);
    const old =
      previous?.readTarget &&
      value.readTarget &&
      notificationTargetKey(previous.readTarget) !== notificationTargetKey(value.readTarget)
        ? undefined
        : previous;
    if (!value.live) return { events: [], activeIds: [] };
    this.previous.set(value.threadId, value);
    const make = (
      kind: NotificationKind,
      identity: string,
      runId?: string,
      requestId?: string,
      operationId?: string,
      summary?: string,
      threadTitle = value.title,
    ): NotificationEvent => {
      const taskSummary =
        kind === 'uncertain' || (runId && value.outcome !== 'unknown')
          ? normalizeNotificationTaskSummary(summary)
          : undefined;
      return {
        version: 1,
        id: createHash('sha256')
          .update(
            JSON.stringify([
              notificationTargetKey(this.target),
              ...(value.readTarget ? [notificationTargetKey(value.readTarget)] : []),
              value.threadId,
              kind,
              ['approval', 'question', 'plan'].includes(kind)
                ? [runId ?? null, identity]
                : identity,
            ]),
          )
          .digest('hex'),
        target: this.target,
        kind,
        occurredAt: new Date().toISOString(),
        ...(value.threadId ? { threadId: value.threadId } : {}),
        threadTitle: threadTitle.slice(0, 100).replace(/[\uD800-\uDBFF]$/, ''),
        ...(runId ? { runId } : {}),
        ...(requestId ? { requestId } : {}),
        ...(operationId ? { operationId } : {}),
        ...(taskSummary ? { taskSummary } : {}),
        ...(value.readTarget ? { readTarget: value.readTarget } : {}),
        ...(kind === 'completed' && value.readPosition && value.readTarget
          ? { readPosition: value.readPosition }
          : {}),
      };
    };
    const active = value.requests.map((request) =>
      make(
        request.kind,
        request.id,
        request.runId,
        request.id,
        undefined,
        request.runId === value.runId ? (request.taskSummary ?? value.taskSummary) : undefined,
      ),
    );
    if (value.planId)
      active.push(
        make(
          'plan',
          value.planId,
          value.runId,
          value.planId,
          undefined,
          value.planSummary ?? value.taskSummary,
        ),
      );
    for (const id of value.uncertainOperations) {
      const detail = value.uncertainOperationDetails?.find((entry) => entry.id === id);
      active.push(
        make(
          'uncertain',
          id,
          detail ? detail.runId : value.runId,
          undefined,
          id,
          detail?.taskSummary,
          detail?.threadTitle,
        ),
      );
    }
    if (value.outcome === 'paused' && value.runId)
      active.push(
        make('paused', value.runId, value.runId, undefined, undefined, value.taskSummary),
      );
    const events = [...active];
    // First live snapshots establish a baseline; outstanding requests are actionable now.
    const recentLiveOutcome =
      !old && value.updatedAt && Date.parse(value.updatedAt) > this.startedAt;
    if (
      (old || recentLiveOutcome) &&
      value.runId &&
      (!old || old.runId !== value.runId || old.outcome !== value.outcome)
    ) {
      const outcome = value.outcome;
      let kind: NotificationKind | undefined =
        outcome === 'completed'
          ? 'completed'
          : outcome === 'failed'
            ? 'failed'
            : outcome === 'paused'
              ? 'paused'
              : ['interrupted', 'cancelled'].includes(outcome ?? '')
                ? 'interrupted'
                : undefined;
      if (kind === 'interrupted' && this.stopped.has(value.runId)) kind = 'stopped';
      if (kind && !(kind === 'completed' && value.planId))
        events.push(make(kind, value.runId, value.runId, undefined, undefined, value.taskSummary));
    }
    return { events, activeIds: active.map((event) => event.id) };
  }
}
