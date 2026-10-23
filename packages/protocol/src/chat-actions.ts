import { parseNotificationTarget } from './notifications.js';
import type { RuntimeTarget } from './agent-management.js';
export const chatActions = ['pin', 'unpin', 'archive', 'unarchive', 'delete'] as const;
export type ChatAction = (typeof chatActions)[number];
export interface ChatActionRequest {
  hostId: string;
  target: RuntimeTarget;
  threadId: string;
  clientOperationId: string;
  action: ChatAction;
}
export interface ChatActionReceipt extends ChatActionRequest {
  phase: 'submitted' | 'confirmed' | 'rejected' | 'outcome-unknown';
  error: string | null;
}
export function parseChatActionRequest(value: unknown): ChatActionRequest {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('无效聊天操作');
  const row = value as Record<string, unknown>;
  const keys = ['hostId', 'target', 'threadId', 'clientOperationId', 'action'];
  if (
    Object.keys(row).some((key) => !keys.includes(key)) ||
    ['hostId', 'threadId', 'clientOperationId'].some(
      (key) => typeof row[key] !== 'string' || !/^[a-zA-Z0-9_-]{8,100}$/.test(row[key] as string),
    ) ||
    !chatActions.includes(row.action as ChatAction)
  )
    throw new Error('无效聊天操作');
  const target = parseNotificationTarget(row.target);
  if (target.hostId !== row.hostId) throw new Error('聊天操作目标不一致');
  return {
    hostId: row.hostId as string,
    target,
    threadId: row.threadId as string,
    clientOperationId: row.clientOperationId as string,
    action: row.action as ChatAction,
  };
}
