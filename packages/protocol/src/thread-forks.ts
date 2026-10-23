/** Product fork commands; upstream Codex and Git details belong to the adapter. */
export type ThreadForkEnvironment = 'same-directory' | 'worktree';
export interface ThreadForkRequest {
  hostId: string;
  threadId: string;
  turnId: string;
  clientOperationId: string;
  environment: ThreadForkEnvironment;
}
export interface ThreadForkOptions {
  hostId: string;
  threadId: string;
  sameDirectoryAvailable: boolean;
  worktreeAvailable: boolean;
  reason: string | null;
  worktreeReason: string | null;
}
export interface ThreadForkOperation extends ThreadForkRequest {
  phase: 'submitted' | 'confirmed' | 'outcome-unknown' | 'rejected';
  forkedThreadId: string | null;
  projectId: string | null;
  error: string | null;
}
export function parseThreadForkRequest(value: unknown): ThreadForkRequest {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('无效分支请求');
  const row = value as Record<string, unknown>;
  const keys = ['hostId', 'threadId', 'turnId', 'clientOperationId', 'environment'];
  if (
    Object.keys(row).some((key) => !keys.includes(key)) ||
    keys.some((key) => typeof row[key] !== 'string' || !(row[key] as string).trim()) ||
    !['same-directory', 'worktree'].includes(row.environment as string) ||
    !/^[a-zA-Z0-9_-]{8,100}$/.test(row.clientOperationId as string) ||
    ['__proto__', 'constructor', 'prototype'].includes(row.clientOperationId as string)
  )
    throw new Error('分支目标、环境或操作 ID 无效');
  return row as unknown as ThreadForkRequest;
}
