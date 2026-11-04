import type { RuntimeTarget } from './agent-management.js';
import { parseNotificationTarget } from './notifications.js';

export const managementActions = [
  'pin',
  'unpin',
  'archive',
  'unarchive',
  'delete',
  'rename',
] as const;
export type ManagementAction = (typeof managementActions)[number];
/** Local hostId is a connection key. Providers independently verify the complete wire target. */
export type ChatManagementIntent = {
  hostId: string;
  target: RuntimeTarget;
  threadId: string;
  clientOperationId: string;
} & (
  | { action: 'rename'; title: string }
  | { action: Exclude<ManagementAction, 'rename'>; title?: never }
);
export type ChatManagementOutcome = ChatManagementIntent & {
  phase: 'submitted' | 'confirmed' | 'rejected' | 'outcome-unknown';
  error: string | null;
};

function object(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('无效聊天管理记录');
  const row = value as Record<string, unknown>;
  if (Object.keys(row).some((key) => !keys.includes(key))) throw new Error('未知聊天管理字段');
  return row;
}
export function parseChatManagementIntent(value: unknown): ChatManagementIntent {
  const row = object(value, [
    'hostId',
    'target',
    'threadId',
    'clientOperationId',
    'action',
    'title',
  ]);
  if (
    ['hostId', 'threadId', 'clientOperationId'].some(
      (key) => typeof row[key] !== 'string' || !/^[a-zA-Z0-9_-]{8,100}$/.test(row[key] as string),
    ) ||
    !managementActions.includes(row.action as ManagementAction)
  )
    throw new Error('聊天管理目标或动作无效');
  const target = parseNotificationTarget(row.target);
  if (row.action === 'rename') {
    if (typeof row.title !== 'string' || !row.title.trim() || row.title.length > 500)
      throw new Error('对话标题须为1至500个UTF-16字符');
    return {
      hostId: row.hostId as string,
      target,
      threadId: row.threadId as string,
      clientOperationId: row.clientOperationId as string,
      action: 'rename',
      title: row.title,
    };
  }
  if ('title' in row) throw new Error('此聊天动作不接受标题');
  return {
    hostId: row.hostId as string,
    target,
    threadId: row.threadId as string,
    clientOperationId: row.clientOperationId as string,
    action: row.action as Exclude<ManagementAction, 'rename'>,
  };
}
export function parseChatManagementOutcome(value: unknown): ChatManagementOutcome {
  const row = object(value, [
    'hostId',
    'target',
    'threadId',
    'clientOperationId',
    'action',
    'title',
    'phase',
    'error',
  ]);
  const { phase, error, ...original } = row;
  const intent = parseChatManagementIntent(original);
  if (
    !['submitted', 'confirmed', 'rejected', 'outcome-unknown'].includes(phase as string) ||
    (error !== null && typeof error !== 'string')
  )
    throw new Error('聊天管理结果无效');
  return {
    ...intent,
    phase: phase as ChatManagementOutcome['phase'],
    error: error as string | null,
  };
}
