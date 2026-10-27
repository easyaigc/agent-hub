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

/** Preserve missing history while reconciling matched provisional dialog identities one for one. */
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
  const restartedOwnerReplacements = new Map<string, number>();
  for (const row of incoming) {
    if (!dialog(row)) continue;
    if (provisional(row.id)) {
      // After a desktop restart the completed owner may reconstruct ordinal
      // IDs for previously stable dialog. Require positive completed-Turn
      // evidence and consume each replacement only once, preserving true
      // same-text multiplicity and all unmatched history/tools/attachments.
      if (completedTurns.has(row.runId!)) {
        const key = identity(row);
        restartedOwnerReplacements.set(key, (restartedOwnerReplacements.get(key) ?? 0) + 1);
      }
      continue;
    }
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
      if (!dialog(row)) return true;
      const key = identity(row);
      const candidates = provisional(row.id) ? replacements : restartedOwnerReplacements;
      const remaining = candidates.get(key) ?? 0;
      if (!remaining) return true;
      candidates.set(key, remaining - 1);
      return false;
    })
    .map((row) =>
      row.kind === 'contextCompaction' && row.status === 'inProgress'
        ? { ...row, status: 'unknown' }
        : row,
    );
}
