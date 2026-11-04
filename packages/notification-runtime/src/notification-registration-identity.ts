import {
  notificationTargetKey,
  type NotificationRegistration,
  type NotificationPreferences,
} from '@codex-manage/protocol';

export function preferenceIdentity(preferences: NotificationPreferences): string {
  return JSON.stringify(
    Object.entries(preferences)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([name, value]) => [
        name,
        name === 'categorySounds'
          ? Object.entries(preferences.categorySounds ?? {}).sort(([left], [right]) =>
              left.localeCompare(right),
            )
          : value,
      ]),
  );
}
export function registrationIdentity(value: NotificationRegistration): string {
  return JSON.stringify([
    value.deviceId,
    notificationTargetKey(value.target),
    value.publicKey,
    value.deviceToken ?? null,
    value.deviceToken ? (value.pushProvider ?? 'apns') : null,
    value.environment ?? null,
    preferenceIdentity(value.preferences),
  ]);
}
