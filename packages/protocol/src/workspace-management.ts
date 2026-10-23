import { parseNotificationTarget } from './notifications.js';
import type { RuntimeTarget } from './agent-management.js';

export interface WorkspaceManagementRequest {
  hostId: string;
  target: RuntimeTarget;
  clientOperationId: string;
  action: 'projects:delete' | 'threads:archive';
  ids: string[];
}
export interface WorkspaceManagementOperation extends WorkspaceManagementRequest {
  items: {
    id: string;
    state: 'pending' | 'submitting' | 'completed' | 'rejected' | 'outcome-unknown';
    error: string | null;
  }[];
}
export function parseWorkspaceManagementRequest(value: unknown): WorkspaceManagementRequest {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('无效批量管理请求');
  const row = value as Record<string, unknown>;
  if (
    Object.keys(row).some(
      (key) => !['hostId', 'target', 'clientOperationId', 'action', 'ids'].includes(key),
    ) ||
    typeof row.hostId !== 'string' ||
    !row.hostId.trim() ||
    typeof row.clientOperationId !== 'string' ||
    !/^[a-zA-Z0-9_-]{8,100}$/.test(row.clientOperationId) ||
    ['__proto__', 'constructor', 'prototype'].includes(row.clientOperationId) ||
    !['projects:delete', 'threads:archive'].includes(String(row.action)) ||
    !Array.isArray(row.ids) ||
    !row.ids.length ||
    row.ids.length > 100 ||
    row.ids.some((id) => typeof id !== 'string' || !id.trim() || id.length > 200) ||
    new Set(row.ids).size !== row.ids.length
  )
    throw new Error('批量目标、操作或 ID 无效');
  const target = parseNotificationTarget(row.target);
  if (target.hostId !== row.hostId) throw new Error('批量管理目标电脑不一致');
  return {
    hostId: row.hostId,
    target,
    clientOperationId: row.clientOperationId,
    action: row.action as WorkspaceManagementRequest['action'],
    ids: [...row.ids] as string[],
  };
}
