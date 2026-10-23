/** The history reader can reconstruct ordinal IDs before the original owner exposes stable IDs. */
export function historyIdentity(id: string, runId?: string): string {
  return runId && /^item-\d+$/.test(id) ? `history-fallback:${runId}:${id}` : id;
}

interface HistoryRow {
  id: string;
  role: string;
  text: string;
  kind?: string;
  runId?: string;
  replyPhase?: string;
  status?: string;
  files?: readonly string[];
  attachments?: readonly unknown[];
}

const provisional = (id: string) => /^item-\d+$/.test(id) || id.startsWith('history-fallback:');
const dialog = (row: HistoryRow) =>
  !!row.runId &&
  ['userMessage', 'agentMessage'].includes(row.kind ?? '') &&
  !row.files?.length &&
  !row.attachments?.length;
const identity = (row: HistoryRow) =>
  JSON.stringify([row.runId, row.role, row.kind, row.replyPhase ?? null, row.text]);

/** Preserve missing history while replacing only matched provisional dialog rows, one for one. */
export function retainedDesktopHistory<T extends HistoryRow>(
  previous: readonly T[],
  incoming: readonly T[],
  initializationIds: ReadonlySet<string>,
  completedTurns: ReadonlySet<string> = new Set(),
): T[] {
  const visible = new Set(incoming.map((row) => row.id));
  const priorStableIds = new Set(
    previous.filter((row) => !provisional(row.id)).map((row) => row.id),
  );
  const replacements = new Map<string, number>();
  for (const row of incoming) {
    if (!dialog(row) || provisional(row.id)) continue;
    // A partial snapshot repeating an already known real item is not evidence
    // that a distinct older provisional item disappeared.
    if (priorStableIds.has(row.id) && !completedTurns.has(row.runId!)) continue;
    const key = identity(row);
    replacements.set(key, (replacements.get(key) ?? 0) + 1);
  }
  return previous
    .filter((row) => {
      if (visible.has(row.id) || initializationIds.has(row.id) || row.kind === 'planImplementation')
        return false;
      if (!dialog(row) || !provisional(row.id)) return true;
      const key = identity(row);
      const remaining = replacements.get(key) ?? 0;
      if (!remaining) return true;
      replacements.set(key, remaining - 1);
      return false;
    })
    .map((row) =>
      row.kind === 'contextCompaction' && row.status === 'inProgress'
        ? { ...row, status: 'unknown' }
        : row,
    );
}
