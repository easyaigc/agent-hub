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
  detail?: string;
  replyMessageId?: string;
  files?: readonly string[];
  attachments?: readonly unknown[];
}

const provisional = (id: string) => /^item-\d+$/.test(id) || id.startsWith('history-fallback:');
const dialog = (row: HistoryRow) =>
  !!row.runId &&
  ['userMessage', 'agentMessage'].includes(row.kind ?? '') &&
  !row.files?.length &&
  !row.attachments?.length;
const identityFields = (row: HistoryRow) => [
  row.runId,
  row.role,
  row.kind,
  row.replyPhase ?? null,
  row.text,
];
const identity = (row: HistoryRow) => JSON.stringify(identityFields(row));

// Resource-bearing replies and user inputs need positive completed-Turn evidence and full
// registered claims. Display-only file names are redundant only when every one
// is covered by those claims; names or a shared path never establish identity.
const resourceFields = [
  'id',
  'hostId',
  'threadId',
  'name',
  'mediaType',
  'sizeBytes',
  'sha256',
  'resourceId',
];
function completedResourceIdentity(
  row: HistoryRow,
  completedTurns: ReadonlySet<string>,
): string | null {
  if (
    !row.runId ||
    !completedTurns.has(row.runId) ||
    !(
      (row.role === 'assistant' &&
        row.kind === 'agentMessage' &&
        row.replyPhase === 'final_answer') ||
      (row.role === 'user' && row.kind === 'userMessage' && row.replyPhase == null)
    ) ||
    row.status !== 'completed' ||
    !row.attachments?.length
  )
    return null;
  const knownRowFields = [
    'id',
    'role',
    'text',
    'kind',
    'runId',
    'replyPhase',
    'status',
    'detail',
    'replyMessageId',
    'files',
    'attachments',
  ];
  if (
    Object.keys(row).some((key) => !knownRowFields.includes(key)) ||
    (row.detail != null && typeof row.detail !== 'string') ||
    (row.replyMessageId != null && typeof row.replyMessageId !== 'string')
  )
    return null;
  const claims: unknown[][] = [];
  const names = new Set<string>();
  for (const value of row.attachments) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    const file = value as Record<string, unknown>;
    if (
      Object.keys(file).some((key) => !resourceFields.includes(key)) ||
      resourceFields.slice(0, 7).some((key) => !Object.hasOwn(file, key)) ||
      ['id', 'hostId', 'threadId', 'name', 'mediaType'].some(
        (key) => typeof file[key] !== 'string' || !file[key],
      ) ||
      typeof file.sizeBytes !== 'number' ||
      !Number.isSafeInteger(file.sizeBytes) ||
      file.sizeBytes < 0 ||
      typeof file.sha256 !== 'string' ||
      !/^[a-f0-9]{64}$/.test(file.sha256) ||
      (file.resourceId != null && (typeof file.resourceId !== 'string' || !file.resourceId))
    )
      return null;
    names.add(file.name as string);
    claims.push(resourceFields.map((key) => file[key] ?? null));
  }
  if (row.files?.some((name) => !names.has(name))) return null;
  return JSON.stringify([
    ...identityFields(row),
    row.detail ?? null,
    row.replyMessageId ?? null,
    claims,
  ]);
}
function reconciliationIdentity(
  row: HistoryRow,
  completedTurns: ReadonlySet<string>,
): string | null {
  if (
    (row.files !== undefined && !Array.isArray(row.files)) ||
    (row.attachments !== undefined && !Array.isArray(row.attachments))
  )
    return null;
  if (dialog(row)) return 'dialog:' + identity(row);
  const key = completedResourceIdentity(row, completedTurns);
  return key === null ? null : 'resource:' + key;
}

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
    const key = reconciliationIdentity(row, completedTurns);
    if (key === null) continue;
    if (provisional(row.id)) {
      // After a desktop restart the completed owner may reconstruct ordinal
      // IDs for previously stable dialog. Require positive completed-Turn
      // evidence and consume each replacement only once, preserving true
      // same-text multiplicity and all unmatched history/tools/attachments.
      if (completedTurns.has(row.runId!)) {
        restartedOwnerReplacements.set(key, (restartedOwnerReplacements.get(key) ?? 0) + 1);
      }
      continue;
    }
    // A partial snapshot repeating an already known real item is not evidence
    // that a distinct older provisional item disappeared.
    if (priorStableIds.has(row.id) && !completedTurns.has(row.runId!)) continue;
    replacements.set(key, (replacements.get(key) ?? 0) + 1);
  }
  return previous
    .filter((row) => {
      if (visible.has(row.id) || initializationIds.has(row.id) || row.kind === 'planImplementation')
        return false;
      const key = reconciliationIdentity(row, completedTurns);
      if (key === null) return true;
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
