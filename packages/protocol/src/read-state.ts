/** Reading belongs to a reader and a complete runtime target, never to a transport. */
export interface ReadPosition {
  revision: number;
  messageId: string;
  contentVersion: string;
}
export interface ThreadReadState {
  version: 1;
  latest: ReadPosition;
  readerId?: string;
  read?: ReadPosition;
}

export function parseReadPosition(value: unknown): ReadPosition {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid read position');
  const row = value as Record<string, unknown>;
  if (
    Object.keys(row).some((key) => !['revision', 'messageId', 'contentVersion'].includes(key)) ||
    !Number.isSafeInteger(row.revision) ||
    (row.revision as number) < 1 ||
    typeof row.messageId !== 'string' ||
    !row.messageId ||
    row.messageId.length > 512 ||
    typeof row.contentVersion !== 'string' ||
    !/^[a-f0-9]{64}$/.test(row.contentVersion)
  )
    throw new Error('Invalid read position');
  return row as unknown as ReadPosition;
}
