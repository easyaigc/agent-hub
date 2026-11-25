/** Raw Trace completion is distinct from the root session's business status. */
export interface DesktopTraceTerminalEvent {
  schema_version: number;
  seq: number;
  rollout_id: string;
  wall_time_unix_ms: number;
  payload: { type: string; status?: string };
}

export type DesktopTraceLifecycle =
  | { state: 'sealed'; terminal: DesktopTraceTerminalEvent; recordingCompleted: boolean }
  | { state: 'active-or-unsealed' | 'writer-completion-unverified' | 'sealed-rollout-unverified' };

/**
 * A bounded tail is only a completion hint. Callers still validate the entire
 * selected log, original identities, saved result and fresh writer occupancy.
 * A root end somewhere in a shared writer must never authorize retirement.
 */
export function desktopTraceLifecycle(
  tail: Buffer,
  schema: number,
  rolloutId: string,
  now: number,
): DesktopTraceLifecycle {
  if (!tail.length || tail.at(-1) !== 10) return { state: 'active-or-unsealed' };
  const lines = tail.toString('utf8').trimEnd().split('\n');
  const events: DesktopTraceTerminalEvent[] = [];
  for (let i = 0; i < lines.length; i++) {
    try {
      events.push(JSON.parse(lines[i]!) as DesktopTraceTerminalEvent);
    } catch {
      // The first line of a bounded tail may start in the middle of an event.
      if (i !== 0) throw new Error('TRACE_INVALID_TAIL');
    }
  }
  const last = events.at(-1);
  const valid = (
    event: DesktopTraceTerminalEvent | undefined,
  ): event is DesktopTraceTerminalEvent =>
    !!event &&
    event.schema_version === schema &&
    event.rollout_id === rolloutId &&
    Number.isSafeInteger(event.seq) &&
    event.seq > 0 &&
    Number.isSafeInteger(event.wall_time_unix_ms) &&
    event.wall_time_unix_ms >= 0 &&
    event.wall_time_unix_ms <= now;
  if (!valid(last)) return { state: 'active-or-unsealed' };
  if (last.payload?.type === 'rollout_ended' && last.payload.status === 'completed')
    return { state: 'sealed', terminal: last, recordingCompleted: false };
  const terminal = events.findLast(
    (event) => valid(event) && event.payload?.type === 'rollout_ended',
  );
  if (schema === 2 && last.payload?.type === 'recording_completed') {
    if (
      !valid(terminal) ||
      terminal.payload.status !== 'completed' ||
      terminal.seq >= last.seq ||
      terminal.wall_time_unix_ms > last.wall_time_unix_ms
    )
      return { state: 'sealed-rollout-unverified' };
    return { state: 'sealed', terminal, recordingCompleted: true };
  }
  return {
    state: terminal ? 'writer-completion-unverified' : 'active-or-unsealed',
  };
}
