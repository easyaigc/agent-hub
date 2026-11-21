import { createHash } from 'node:crypto';
import { SubagentHistoryError } from './subagents.js';

export const parentLineageAssociation = 'parent-thread-lineage' as const;
export const parentLineageCapability = 'subagents:history:parent-lineage:v1';
const prefix = 'subagent-lineage-page-v1:';
const record = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
const id = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0 && value.length <= 512;
function reject(message: string): never {
  throw new SubagentHistoryError(409, message);
}

/** Structured official source only. Catalog candidates confer no body authorization. */
export function officialDirectParent(value: unknown): string | undefined {
  const source = record(record(value).source),
    agent = record(source.subAgent);
  if (
    Object.keys(source).length !== 1 ||
    Object.keys(agent).length !== 1 ||
    !Object.hasOwn(source, 'subAgent') ||
    !Object.hasOwn(agent, 'thread_spawn')
  )
    return undefined;
  const spawn = agent.thread_spawn;
  const parent = record(spawn).parent_thread_id;
  return id(parent) ? parent : undefined;
}
export function verifyOfficialChild(value: unknown, child: string, parent: string): void {
  if (
    !id(child) ||
    !id(parent) ||
    child === parent ||
    record(value).id !== child ||
    officialDirectParent(value) !== parent
  )
    reject('Official direct-parent metadata unavailable or changed');
}
export function directParentCatalog(value: unknown, parent: string): string[] {
  const page = record(value);
  if (!id(parent) || !Array.isArray(page.data)) reject('Official child catalog unavailable');
  const rows = page.data as unknown[];
  if (page.nextCursor != null || rows.length > 32)
    reject('Official child catalog is incomplete; bounded reading unavailable');
  const ids: string[] = [];
  for (const row of rows) {
    if (
      record(row).turns !== undefined &&
      (!Array.isArray(record(row).turns) || (record(row).turns as unknown[]).length)
    )
      reject('Official catalog unexpectedly included turns');
    const child = record(row).id;
    if (!id(child) || ids.includes(child)) reject('Official child catalog is ambiguous');
    verifyOfficialChild(row, child, parent);
    ids.push(child);
  }
  if (!ids.length)
    throw new SubagentHistoryError(404, 'No direct subtasks in this official catalog');
  return ids;
}

export type LineagePageCursor = {
  target: string;
  parent: string;
  anchor: string;
  anchorHash: string;
  child: string;
  source: 'saved-history';
  historyMode: string;
  backend: string;
};
export function encodeLineageCursor(value: LineagePageCursor): string {
  const token = prefix + Buffer.from(JSON.stringify(value)).toString('base64url');
  if (token.length > 4096) reject('Official history cursor exceeds the bounded window');
  return token;
}
export function decodeLineageCursor(
  token: string,
  expected: Pick<LineagePageCursor, 'target' | 'parent' | 'anchor' | 'anchorHash'>,
  child?: string,
): LineagePageCursor {
  if (!token.startsWith(prefix) || token.length > 4096) reject('Invalid lineage cursor mode');
  let raw: unknown;
  try {
    const encoded = token.slice(prefix.length);
    const bytes = Buffer.from(encoded, 'base64url');
    if (bytes.toString('base64url') !== encoded) reject('Malformed lineage cursor');
    raw = JSON.parse(bytes.toString('utf8'));
  } catch {
    return reject('Malformed lineage cursor');
  }
  const value = record(raw);
  const keys = [
    'target',
    'parent',
    'anchor',
    'anchorHash',
    'child',
    'source',
    'historyMode',
    'backend',
  ];
  if (
    Object.keys(value).length !== keys.length ||
    keys.some((key) => !Object.hasOwn(value, key)) ||
    keys.some((key) => typeof value[key] !== 'string') ||
    !id(value.child) ||
    value.source !== 'saved-history' ||
    !['paginated', 'snapshot'].includes(String(value.historyMode)) ||
    !value.backend ||
    (child !== undefined && child !== value.child) ||
    Object.entries(expected).some(([key, field]) => value[key] !== field)
  )
    reject('Lineage cursor target, anchor or source changed');
  return value as LineagePageCursor;
}

/** A local continuation for a complete nonpaginated response; never forwarded as an RPC cursor. */
export function snapshotLineagePage<T extends { id: string }>(messages: T[], token?: string) {
  const digest = (end: number) =>
    createHash('sha256')
      .update(JSON.stringify(messages.slice(0, end)))
      .digest('hex');
  let end = messages.length;
  if (token !== undefined) {
    let value: unknown;
    try {
      value = JSON.parse(token);
    } catch {
      reject('Invalid snapshot continuation');
    }
    if (
      !Array.isArray(value) ||
      value.length !== 2 ||
      !Number.isSafeInteger(value[1]) ||
      value[1] < 1 ||
      value[1] >= messages.length
    )
      reject('Saved snapshot changed; refresh subtasks');
    end = value[1] as number;
    if (value[0] !== digest(end)) reject('Saved snapshot changed; refresh subtasks');
  }
  let start = end,
    bytes = 0;
  while (start > 0 && end - start < 80) {
    const size = Buffer.byteLength(JSON.stringify(messages[start - 1]));
    if (size > 1024 * 1024) reject('A saved item exceeds the history page budget');
    if (bytes + size > 1024 * 1024) break;
    bytes += size;
    start--;
  }
  return {
    messages: messages.slice(start, end),
    nextCursor: start ? JSON.stringify([digest(start), start]) : null,
  };
}
