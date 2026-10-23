import {
  notificationObject,
  notificationSound,
  parseNotificationEnvelope,
  type NotificationEnvelope,
} from './notifications.js';

export const BUSINESS_NOTIFICATION_DOMAIN = 'taskhub.business' as const;
export const businessNoticeKinds = [
  'response_required',
  'clarification_required',
  'acceptance_required',
  'acceptance_failed',
  'completed',
  'blocked',
  'operation_uncertain',
] as const;
export type BusinessNoticeKind = (typeof businessNoticeKinds)[number];
export interface BusinessServiceIdentity {
  deploymentId: string;
  serverInstanceId: string;
  primaryHostId: string;
}
export interface BusinessNoticeTarget extends BusinessServiceIdentity {
  actorId: string;
}
export interface BusinessNotice extends BusinessNoticeTarget {
  version: 1;
  domain: typeof BUSINESS_NOTIFICATION_DOMAIN;
  eventId: string;
  projectId: string;
  taskId: string;
  roundId: string;
  revision: number;
  kind: BusinessNoticeKind;
  occurredAt: string;
  title?: string;
  summary?: string;
}
export interface BusinessNotificationPreferences {
  enabled: boolean;
  generation: number;
  kinds: BusinessNoticeKind[];
  showPreview: boolean;
  sound: string;
  foregroundBanner: boolean;
}
export interface BusinessNotificationEnvelope extends NotificationEnvelope {
  domain: typeof BUSINESS_NOTIFICATION_DOMAIN;
}
export const businessIdentityKey = (target: BusinessServiceIdentity): string =>
  JSON.stringify([target.deploymentId, target.serverInstanceId, target.primaryHostId]);
export const businessTargetKey = (target: BusinessNoticeTarget): string =>
  JSON.stringify([
    target.deploymentId,
    target.serverInstanceId,
    target.primaryHostId,
    target.actorId,
  ]);
const uuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const identifier = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[A-Za-z0-9._-]{1,128}$/.test(v) &&
  !['__proto__', 'constructor', 'prototype'].includes(v);
export function parseBusinessTarget(value: unknown): BusinessNoticeTarget {
  const row = notificationObject(value, [
    'deploymentId',
    'serverInstanceId',
    'primaryHostId',
    'actorId',
  ]);
  if (
    ![row.deploymentId, row.serverInstanceId, row.primaryHostId].every(identifier) ||
    !uuid(row.actorId)
  )
    throw new Error('Invalid business notification target');
  return row as unknown as BusinessNoticeTarget;
}
export function parseBusinessNotice(value: unknown): BusinessNotice {
  const row = notificationObject(value, [
    'version',
    'domain',
    'eventId',
    'deploymentId',
    'serverInstanceId',
    'primaryHostId',
    'actorId',
    'projectId',
    'taskId',
    'roundId',
    'revision',
    'kind',
    'occurredAt',
    'title',
    'summary',
  ]);
  parseBusinessTarget(
    Object.fromEntries(
      ['deploymentId', 'serverInstanceId', 'primaryHostId', 'actorId'].map((k) => [k, row[k]]),
    ),
  );
  if (
    row.version !== 1 ||
    row.domain !== BUSINESS_NOTIFICATION_DOMAIN ||
    typeof row.eventId !== 'string' ||
    !/^[a-f0-9]{64}$/.test(row.eventId) ||
    ![row.projectId, row.taskId, row.roundId].every(uuid) ||
    !Number.isSafeInteger(row.revision) ||
    Number(row.revision) < 1 ||
    !businessNoticeKinds.includes(row.kind as BusinessNoticeKind) ||
    typeof row.occurredAt !== 'string' ||
    !Number.isFinite(Date.parse(row.occurredAt)) ||
    [row.title, row.summary].some(
      (v) =>
        v !== undefined &&
        (typeof v !== 'string' ||
          v.length > 160 ||
          Array.from(v).some((c) => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127)),
    )
  )
    throw new Error('Invalid business notice');
  return row as unknown as BusinessNotice;
}
export function parseBusinessPreferences(value: unknown): BusinessNotificationPreferences {
  const row = notificationObject(value, [
    'enabled',
    'generation',
    'kinds',
    'showPreview',
    'sound',
    'foregroundBanner',
  ]);
  if (
    ![row.enabled, row.showPreview, row.foregroundBanner].every((v) => typeof v === 'boolean') ||
    !Number.isSafeInteger(row.generation) ||
    Number(row.generation) < 1 ||
    !Array.isArray(row.kinds) ||
    new Set(row.kinds).size !== row.kinds.length ||
    row.kinds.some((k) => !businessNoticeKinds.includes(k)) ||
    !notificationSound(row.sound)
  )
    throw new Error('Invalid business preferences');
  return row as unknown as BusinessNotificationPreferences;
}
export function parseBusinessEnvelope(value: unknown): BusinessNotificationEnvelope {
  const row = notificationObject(value, [
    'domain',
    'version',
    'bindingId',
    'eventId',
    'nonce',
    'ciphertext',
    'tag',
    'expiresAt',
    'sound',
    'timeSensitive',
  ]);
  if (row.domain !== BUSINESS_NOTIFICATION_DOMAIN)
    throw new Error('Invalid business envelope domain');
  const { domain: _, ...encrypted } = row;
  return { ...parseNotificationEnvelope(encrypted), domain: BUSINESS_NOTIFICATION_DOMAIN };
}
