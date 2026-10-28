import type { RuntimeTarget } from './agent-management.js';
import {
  notificationObject,
  parseNotificationEvent,
  parseNotificationPreferences,
  parseNotificationRegistration,
  parseNotificationTarget,
  type NotificationEvent,
  type NotificationPreferences,
  type NotificationStatus,
} from './notifications.js';

export const NOTIFICATION_RECOVERY_VERSION = 2 as const;
export const notificationRecoveryLimits = {
  slots: 128,
  operations: 1024,
  logEvents: 2048,
  logBytes: 4 * 1024 * 1024,
  snapshots: 4,
  snapshotEvents: 2048,
  snapshotBytes: 2 * 1024 * 1024,
  totalBytes: 16 * 1024 * 1024,
  pageEvents: 32,
  pageBytes: 256 * 1024,
  activeIds: 2048,
  snapshotLifetimeMs: 300_000,
} as const;
export interface NotificationGapV2 {
  epoch: string;
  after: number;
  floor: number;
  head: number;
  acceptanceId: string;
}
export interface NotificationIntentV2 {
  version: 2;
  operationId: string;
  epoch: string;
  deviceId: string;
  target: RuntimeTarget;
  publicKey: string;
  preferences: NotificationPreferences;
  generation: number;
  kind: 'register' | 'preferences' | 'revoke' | 'feed-reset';
  gap?: NotificationGapV2;
  fingerprint: string;
}
export interface NotificationReceiptV2 {
  version: 2;
  epoch: string;
  intent: NotificationIntentV2;
  stage: 'reserved' | 'applied' | 'rejected';
  reason?: string;
  status?: NotificationStatus;
  snapshot?: { id: string; hash: string; count: number; baseline: number; expiresAt: number };
}
export interface NotificationFeedQueryV2 {
  version: 2;
  deviceId: string;
  epoch: string;
  generation: number;
  after: number;
  through?: number;
  resetId?: string;
}
export type NotificationDispositionV2 = 'eligible' | 'inactive' | 'stale' | 'filtered';
export interface NotificationFeedItemV2 {
  sequence: number;
  event: NotificationEvent;
  fingerprint: string;
  disposition: NotificationDispositionV2;
}
export type NotificationFeedV2 = {
  version: 2;
  epoch: string;
  deviceId: string;
  target: RuntimeTarget;
  generation: number;
} & (
  | {
      kind: 'page';
      mode: 'live' | 'reset';
      resetId?: string;
      snapshotHash?: string;
      baseline: number;
      after: number;
      next: number;
      through: number;
      hasMore: boolean;
      items: NotificationFeedItemV2[];
      activeIds: string[];
      publicKey: string;
      preferencesFingerprint: string;
    }
  | { kind: 'reset-required'; reason: string; floor: number; head: number }
  | { kind: 'capacity'; reason: string }
);
export function notificationV2Uuid(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length === 36 &&
    /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
  );
}
export function notificationV2Hash(value: unknown): value is string {
  return typeof value === 'string' && value.length === 64 && /^[a-f0-9]{64}$/.test(value);
}
export function notificationV2Device(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[A-Za-z0-9._-]{1,128}$/.exec(value)?.[0] === value &&
    !['__proto__', 'prototype', 'constructor'].includes(value)
  );
}
/** Portable canonical subset: valid Unicode, sorted UTF-16 keys, safe integers, minimal JSON escaping. */
export function notificationV2Canonical(value: unknown): string {
  if (value === null || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) throw new Error('无效通知整数');
    return String(value === 0 ? 0 : value);
  }
  if (typeof value === 'string') {
    if (/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value))
      throw new Error('无效通知 Unicode');
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return '[' + value.map(notificationV2Canonical).join(',') + ']';
  if (
    !value ||
    typeof value !== 'object' ||
    (Object.getPrototypeOf(value) !== Object.prototype && Object.getPrototypeOf(value) !== null)
  )
    throw new Error('无效通知规范值');
  return (
    '{' +
    Object.keys(value)
      .filter((key) => (value as Record<string, unknown>)[key] !== undefined)
      .sort()
      .map(
        (key) =>
          notificationV2Canonical(key) +
          ':' +
          notificationV2Canonical((value as Record<string, unknown>)[key]),
      )
      .join(',') +
    '}'
  );
}
export function notificationV2IntentBytes(intent: NotificationIntentV2): string {
  const { fingerprint: _fingerprint, ...original } = intent;
  void _fingerprint;
  return notificationV2Canonical(original);
}
export function parseNotificationIntentV2(value: unknown): NotificationIntentV2 {
  const row = notificationObject(value, [
    'version',
    'operationId',
    'epoch',
    'deviceId',
    'target',
    'publicKey',
    'preferences',
    'generation',
    'kind',
    'gap',
    'fingerprint',
  ]);
  if (
    row.version !== 2 ||
    !notificationV2Uuid(row.operationId) ||
    !notificationV2Uuid(row.epoch) ||
    !notificationV2Device(row.deviceId) ||
    !Number.isSafeInteger(row.generation) ||
    Number(row.generation) < 1 ||
    !notificationV2Hash(row.fingerprint) ||
    typeof row.kind !== 'string' ||
    !['register', 'preferences', 'revoke', 'feed-reset'].includes(row.kind)
  )
    throw new Error('无效通知恢复意图');
  parseNotificationRegistration({
    version: 1,
    deviceId: row.deviceId,
    target: row.target,
    publicKey: row.publicKey,
    preferences: row.preferences,
  });
  if (
    typeof row.publicKey !== 'string' ||
    row.publicKey.length !== 44 ||
    !/^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$/.test(row.publicKey)
  )
    throw new Error('通知公钥必须使用规范 Base64');
  if (row.kind === 'revoke' && (row.preferences as NotificationPreferences).enabled)
    throw new Error('通知撤销必须关闭偏好');
  if ((row.kind === 'feed-reset') !== (row.gap !== undefined))
    throw new Error('通知复位缺少原缺口');
  if (row.gap !== undefined) {
    const gap = notificationObject(row.gap, ['epoch', 'after', 'floor', 'head', 'acceptanceId']);
    if (
      !notificationV2Uuid(gap.epoch) ||
      !notificationV2Uuid(gap.acceptanceId) ||
      !['after', 'floor', 'head'].every(
        (k) => Number.isSafeInteger(gap[k]) && Number(gap[k]) >= 0,
      ) ||
      Number(gap.floor) < 1 ||
      Number(gap.head) < Number(gap.floor) - 1
    )
      throw new Error('无效通知复位缺口');
  }
  notificationV2IntentBytes(row as unknown as NotificationIntentV2);
  return row as unknown as NotificationIntentV2;
}
export function parseNotificationFeedQueryV2(value: unknown): NotificationFeedQueryV2 {
  const row = notificationObject(value, [
    'version',
    'deviceId',
    'epoch',
    'generation',
    'after',
    'through',
    'resetId',
  ]);
  if (
    row.version !== 2 ||
    !notificationV2Device(row.deviceId) ||
    !notificationV2Uuid(row.epoch) ||
    !Number.isSafeInteger(row.generation) ||
    Number(row.generation) < 1 ||
    !Number.isSafeInteger(row.after) ||
    Number(row.after) < 0 ||
    (row.through !== undefined &&
      (!Number.isSafeInteger(row.through) || Number(row.through) < Number(row.after))) ||
    (row.resetId !== undefined && !notificationV2Uuid(row.resetId))
  )
    throw new Error('无效通知分页请求');
  return row as unknown as NotificationFeedQueryV2;
}
/** Validate common payloads before a platform-specific closed response decoder handles its envelope. */
export function validateNotificationV2Original(
  event: unknown,
  preferences: unknown,
  target: unknown,
): void {
  parseNotificationEvent(event);
  parseNotificationPreferences(preferences);
  parseNotificationTarget(target);
}
