import {
  NotificationPublisher,
  NotificationRecoveryError,
} from '@codex-manage/notification-runtime';
import {
  notificationTargetKey,
  parseNotificationTarget,
  notificationV2Device,
  type RuntimeTarget,
} from '@codex-manage/protocol';

/** Called only after the Host's normal authentication; closed fixed notification operations, no method forwarding. */
export function notificationRecoveryRoute(
  method: string | undefined,
  url: URL,
  data: Record<string, unknown>,
  notifications: NotificationPublisher,
  currentTarget: RuntimeTarget,
): unknown | undefined {
  if (!url.pathname.startsWith('/v1/notifications/recovery-v2/')) return undefined;
  const invalid = (message: string): never => {
    throw new NotificationRecoveryError(400, message);
  };
  const route = url.pathname.slice('/v1/notifications/recovery-v2/'.length);
  const allowed = [
    'hostId',
    'target',
    'deviceId',
    ...(route === 'operations' && method === 'GET'
      ? ['operationId', 'epoch']
      : route === 'feed'
        ? ['epoch', 'generation', 'after', 'through', 'resetId']
        : []),
  ];
  const original = url.searchParams.get('target');
  if (
    !original ||
    original.length > 8192 ||
    url.searchParams.get('hostId') !== currentTarget.hostId ||
    [...url.searchParams.keys()].some(
      (key) => !allowed.includes(key) || url.searchParams.getAll(key).length !== 1,
    )
  )
    invalid('通知恢复必须指定准确目标及具名字段');
  let target: RuntimeTarget;
  try {
    target = parseNotificationTarget(JSON.parse(original!));
  } catch {
    return invalid('无效通知恢复目标');
  }
  if (notificationTargetKey(target) !== notificationTargetKey(currentTarget))
    throw new NotificationRecoveryError(409, '通知运行目标已变化');
  const deviceId = url.searchParams.get('deviceId') ?? '';
  if (!notificationV2Device(deviceId)) invalid('无效通知设备');
  if (route === 'capabilities' && method === 'GET') return notifications.recoveryCapabilities();
  if (route === 'operations' && method === 'POST') {
    if (
      Object.keys(data).some((key) => !['hostId', 'intent'].includes(key)) ||
      data.hostId !== currentTarget.hostId ||
      !data.intent ||
      typeof data.intent !== 'object' ||
      Array.isArray(data.intent) ||
      (data.intent as { deviceId?: unknown }).deviceId !== deviceId
    )
      invalid('无效通知恢复提交');
    return notifications.recoverySubmit(data.intent);
  }
  if (route === 'operations' && method === 'GET')
    return notifications.recoveryReceipt(
      deviceId,
      url.searchParams.get('operationId') ?? '',
      url.searchParams.get('epoch') ?? '',
    );
  if (route === 'feed' && method === 'GET') {
    const integer = (key: string, optional = false) => {
      const raw = url.searchParams.get(key);
      if (optional && raw === null) return undefined;
      if (!raw || !/^(0|[1-9][0-9]{0,15})$/.test(raw) || !Number.isSafeInteger(Number(raw)))
        return invalid('无效通知分页序号');
      return Number(raw);
    };
    const through = integer('through', true),
      resetId = url.searchParams.get('resetId');
    return notifications.recoveryFeed({
      version: 2,
      deviceId,
      epoch: url.searchParams.get('epoch') ?? '',
      generation: integer('generation')!,
      after: integer('after')!,
      ...(through !== undefined ? { through } : {}),
      ...(resetId !== null ? { resetId } : {}),
    });
  }
  throw new NotificationRecoveryError(404, '未知通知恢复操作');
}
