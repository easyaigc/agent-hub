import type { RuntimeTarget } from './agent-management.js';
import { parseReadPosition, type ReadPosition } from './read-state.js';

export const NOTIFICATION_VERSION = 1 as const;
/** Matches Swift String.utf16.count; the pattern's quantifier counts UTF-16 code units. */
export const TASK_SUMMARY_MAX_LENGTH = 160;
export const notificationTaskSummaryPattern =
  '^(?! )(?!.* $)(?!.* {2})(?!.*[^\\S ])(?!.*[\\u0000-\\u001f\\u007f-\\u009f\\u00ad\\u061c\\u180e\\u200b-\\u200f\\u202a-\\u202e\\u2060-\\u206f\\ufeff])[\\s\\S]{1,160}$';
export function notificationTaskSummary(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length <= TASK_SUMMARY_MAX_LENGTH &&
    new RegExp(notificationTaskSummaryPattern).test(value)
  );
}
/** First text paragraph only; no model call and no split surrogate pair at the limit. */
export function normalizeNotificationTaskSummary(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const paragraph =
    value
      .replace(/\r\n?/g, '\n')
      .trim()
      .split(/\n[^\S\n]*\n/)[0] ?? '';
  const text = paragraph
    .replace(/\s+/g, ' ')
    .replace(/[\p{Cc}\p{Cf}]/gu, '')
    .replace(/ +/g, ' ')
    .trim()
    .replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '\ufffd');
  if (!text) return undefined;
  if (text.length <= TASK_SUMMARY_MAX_LENGTH) return text;
  let prefix = '';
  for (const character of text) {
    if (prefix.length + character.length >= TASK_SUMMARY_MAX_LENGTH) break;
    prefix += character;
  }
  return prefix.trimEnd() + '…';
}
export const notificationKinds = [
  'approval',
  'question',
  'plan',
  'paused',
  'interrupted',
  'failed',
  'uncertain',
  'completed',
  'connection',
  'stopped',
] as const;
export type NotificationKind = (typeof notificationKinds)[number];
export interface NotificationPreferences {
  version: 1;
  revision: string;
  generation: number;
  enabled: boolean;
  kinds: NotificationKind[];
  excludedHosts: string[];
  foregroundBanner: boolean;
  soundEnabled: boolean;
  badgeEnabled: boolean;
  showThreadTitle: boolean;
  timeSensitive: boolean;
  sound: string;
  categorySounds: Partial<Record<NotificationKind, string>>;
}
export interface NotificationEvent {
  version: 1;
  id: string;
  target: RuntimeTarget;
  kind: NotificationKind;
  occurredAt: string;
  threadId?: string;
  runId?: string;
  requestId?: string;
  operationId?: string;
  threadTitle?: string;
  taskSummary?: string;
  /** Canonical mobile scope and reply version, inside the encrypted event. */
  readTarget?: RuntimeTarget;
  readPosition?: ReadPosition;
}
export interface NotificationRegistration {
  version: 1;
  deviceId: string;
  target: RuntimeTarget;
  publicKey: string;
  deviceToken?: string;
  environment?: 'development' | 'production';
  preferences: NotificationPreferences;
}
export interface NotificationStatus {
  version: 1;
  target: RuntimeTarget;
  delivery: 'foreground-only' | 'apns';
  state: 'disabled' | 'ready' | 'pending' | 'unavailable';
  reason?: string;
  revision?: string;
  bindingId?: string;
  publicKey?: string;
  relayURL?: string;
  managementToken?: string;
  lastDelivery?: {
    state: 'pending' | 'submitting' | 'accepted' | 'uncertain' | 'rejected';
    occurredAt: string;
    reason?: string;
  };
}
export interface NotificationEnvelope {
  version: 1;
  bindingId: string;
  eventId: string;
  nonce: string;
  ciphertext: string;
  tag: string;
  expiresAt: string;
  sound: string;
  timeSensitive: boolean;
  badge?: number;
  /** A background refresh, with no alert or sound and no host-local badge number. */
  badgeOnly?: boolean;
}
export function defaultNotificationPreferences(): NotificationPreferences {
  return {
    version: 1,
    revision: 'initial',
    generation: 0,
    enabled: false,
    kinds: ['approval', 'question', 'plan', 'paused', 'interrupted', 'failed', 'uncertain'],
    excludedHosts: [],
    foregroundBanner: true,
    soundEnabled: true,
    badgeEnabled: true,
    showThreadTitle: true,
    timeSensitive: false,
    sound: 'default',
    categorySounds: {},
  };
}
export function notificationTargetKey(target: RuntimeTarget): string {
  // Preserve the persisted six-slot accountless Codex notification layout,
  // including disabled subscription tombstones without a remaining binding.
  return JSON.stringify([
    target.agentId,
    target.hostId,
    target.workspaceId,
    target.runtimeId,
    target.agentId === 'codex' ? null : (target.account?.authority ?? null),
    target.agentId === 'codex' ? null : (target.account?.subject ?? null),
  ]);
}
export function notificationSound(value: unknown): value is string {
  return typeof value === 'string' && /^(default|none|[a-zA-Z0-9_-]{1,72}\.caf)$/.test(value);
}
export function notificationObject(
  value: unknown,
  keys: readonly string[],
): Record<string, unknown> {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some((key) => !keys.includes(key))
  )
    throw new Error('无效通知契约');
  return value as Record<string, unknown>;
}
function identifier(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= 256 &&
    Array.from(value).every((character) => character.charCodeAt(0) >= 32)
  );
}
export function parseNotificationTarget(value: unknown): RuntimeTarget {
  const row = notificationObject(value, [
    'agentId',
    'hostId',
    'workspaceId',
    'runtimeId',
    'account',
  ]);
  if (!['agentId', 'hostId', 'workspaceId', 'runtimeId'].every((key) => identifier(row[key])))
    throw new Error('无效通知目标');
  if (row.account !== undefined) {
    const account = notificationObject(row.account, ['authority', 'subject']);
    if (!identifier(account.authority) || !identifier(account.subject))
      throw new Error('无效通知账户');
  }
  return row as unknown as RuntimeTarget;
}
export function parseNotificationEvent(value: unknown): NotificationEvent {
  const row = notificationObject(value, [
    'version',
    'id',
    'target',
    'kind',
    'occurredAt',
    'threadId',
    'runId',
    'requestId',
    'operationId',
    'threadTitle',
    'taskSummary',
    'readTarget',
    'readPosition',
  ]);
  if (
    row.version !== 1 ||
    typeof row.id !== 'string' ||
    !/^[a-f0-9]{64}$/.test(row.id) ||
    !notificationKinds.includes(row.kind as NotificationKind) ||
    typeof row.occurredAt !== 'string' ||
    !Number.isFinite(Date.parse(row.occurredAt)) ||
    ['threadId', 'runId', 'requestId', 'operationId'].some(
      (key) => row[key] !== undefined && !identifier(row[key]),
    ) ||
    (row.threadTitle !== undefined &&
      (typeof row.threadTitle !== 'string' || row.threadTitle.length > 100)) ||
    (row.taskSummary !== undefined && !notificationTaskSummary(row.taskSummary))
  )
    throw new Error('无效通知事件');
  const target = parseNotificationTarget(row.target);
  if (row.readTarget !== undefined) {
    const reading = parseNotificationTarget(row.readTarget);
    if (
      ['agentId', 'hostId', 'workspaceId', 'runtimeId'].some(
        (key) => reading[key as keyof RuntimeTarget] !== target[key as keyof RuntimeTarget],
      ) ||
      (target.account && notificationTargetKey(reading) !== notificationTargetKey(target))
    )
      throw new Error('通知阅读目标不一致');
  }
  if (row.readPosition !== undefined) {
    if (!row.threadId || !row.readTarget || row.kind !== 'completed')
      throw new Error('通知阅读版本缺少目标');
    parseReadPosition(row.readPosition);
  }
  return row as unknown as NotificationEvent;
}
export function parseNotificationPreferences(value: unknown): NotificationPreferences {
  const row = notificationObject(value, [
    'version',
    'revision',
    'generation',
    'enabled',
    'kinds',
    'excludedHosts',
    'foregroundBanner',
    'soundEnabled',
    'badgeEnabled',
    'showThreadTitle',
    'timeSensitive',
    'sound',
    'categorySounds',
  ]);
  if (
    row.version !== 1 ||
    !identifier(row.revision) ||
    !Number.isSafeInteger(row.generation) ||
    Number(row.generation) < 0 ||
    ![
      'enabled',
      'foregroundBanner',
      'soundEnabled',
      'badgeEnabled',
      'showThreadTitle',
      'timeSensitive',
    ].every((key) => typeof row[key] === 'boolean') ||
    !Array.isArray(row.kinds) ||
    row.kinds.length > notificationKinds.length ||
    row.kinds.some((kind) => !notificationKinds.includes(kind)) ||
    new Set(row.kinds).size !== row.kinds.length ||
    !Array.isArray(row.excludedHosts) ||
    row.excludedHosts.length > 256 ||
    row.excludedHosts.some((host) => !identifier(host)) ||
    !notificationSound(row.sound)
  )
    throw new Error('无效通知偏好');
  const sounds = notificationObject(row.categorySounds, notificationKinds);
  if (Object.values(sounds).some((sound) => !notificationSound(sound)))
    throw new Error('无效通知声音');
  return row as unknown as NotificationPreferences;
}
export function parseNotificationRegistration(value: unknown): NotificationRegistration {
  const row = notificationObject(value, [
    'version',
    'deviceId',
    'target',
    'publicKey',
    'deviceToken',
    'environment',
    'preferences',
  ]);
  if (
    row.version !== 1 ||
    typeof row.deviceId !== 'string' ||
    !/^[A-Za-z0-9._-]{1,128}$/.test(row.deviceId) ||
    ['__proto__', 'constructor', 'prototype'].includes(row.deviceId) ||
    typeof row.publicKey !== 'string' ||
    !/^[A-Za-z0-9+/]{43}=$/.test(row.publicKey) ||
    (row.deviceToken !== undefined &&
      (typeof row.deviceToken !== 'string' || !/^[a-f0-9]{64,256}$/.test(row.deviceToken))) ||
    (row.environment !== undefined &&
      !['development', 'production'].includes(String(row.environment))) ||
    !!row.deviceToken !== !!row.environment
  )
    throw new Error('无效通知注册');
  parseNotificationTarget(row.target);
  parseNotificationPreferences(row.preferences);
  return row as unknown as NotificationRegistration;
}
export function parseNotificationEnvelope(value: unknown): NotificationEnvelope {
  const row = notificationObject(value, [
    'version',
    'bindingId',
    'eventId',
    'nonce',
    'ciphertext',
    'tag',
    'expiresAt',
    'sound',
    'timeSensitive',
    'badge',
    'badgeOnly',
  ]);
  if (
    row.version !== 1 ||
    !identifier(row.bindingId) ||
    typeof row.eventId !== 'string' ||
    !/^[a-f0-9]{64}$/.test(row.eventId) ||
    typeof row.nonce !== 'string' ||
    !/^[A-Za-z0-9+/]{16}$/.test(row.nonce) ||
    typeof row.tag !== 'string' ||
    !/^[A-Za-z0-9+/]{22}==$/.test(row.tag) ||
    typeof row.ciphertext !== 'string' ||
    row.ciphertext.length > 2800 ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(row.ciphertext) ||
    typeof row.expiresAt !== 'string' ||
    !Number.isFinite(Date.parse(row.expiresAt)) ||
    !notificationSound(row.sound) ||
    typeof row.timeSensitive !== 'boolean' ||
    (row.badge !== undefined && (!Number.isSafeInteger(row.badge) || Number(row.badge) < 0)) ||
    (row.badgeOnly !== undefined && typeof row.badgeOnly !== 'boolean') ||
    (row.badgeOnly === true &&
      (row.badge !== undefined || row.sound !== 'none' || row.timeSensitive !== false))
  )
    throw new Error('无效通知信封');
  return row as unknown as NotificationEnvelope;
}
