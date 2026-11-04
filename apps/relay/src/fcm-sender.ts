import { createPrivateKey, sign, type KeyObject } from 'node:crypto';
import { openSync, fstatSync, readFileSync, closeSync, constants } from 'node:fs';
import {
  notificationPushToken,
  parseNotificationEnvelope,
  type NotificationEnvelope,
} from '@codex-manage/protocol';

export interface FCMConfiguration {
  projectId: string;
  serviceAccountPath: string;
}
export interface FCMResult {
  state: 'accepted' | 'pending' | 'uncertain' | 'rejected';
  reason?: string;
  retryAfterMs?: number;
}
const tokenURL = 'https://oauth2.googleapis.com/token';
const oauthScope = 'https://www.googleapis.com/auth/firebase.messaging';
type Transport = typeof fetch;

function credentials(config: FCMConfiguration): { email: string; key: KeyObject } {
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(config.projectId)) throw new Error('FCM 项目标识无效');
  const descriptor = openSync(
    config.serviceAccountPath,
    constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0),
  );
  try {
    const stat = fstatSync(descriptor);
    if (
      !stat.isFile() ||
      stat.size > 65536 ||
      (process.platform !== 'win32' &&
        ((stat.mode & 0o077) !== 0 || stat.uid !== process.getuid!()))
    )
      throw new Error('FCM 服务凭据必须是当前用户私有文件');
    let value: Record<string, unknown>;
    try {
      value = JSON.parse(readFileSync(descriptor, 'utf8')) as Record<string, unknown>;
    } catch {
      throw new Error('FCM 服务凭据格式无效');
    }
    if (
      value.type !== 'service_account' ||
      value.project_id !== config.projectId ||
      typeof value.private_key !== 'string' ||
      typeof value.client_email !== 'string' ||
      !/^[A-Za-z0-9._-]+@[a-z0-9-]+\.iam\.gserviceaccount\.com$/.test(value.client_email)
    )
      throw new Error('FCM 服务凭据与项目不匹配');
    const key = createPrivateKey(value.private_key);
    if (key.asymmetricKeyType !== 'rsa' || (key.asymmetricKeyDetails?.modulusLength ?? 0) < 2048)
      throw new Error('FCM 需要 RSA 服务密钥');
    return { email: value.client_email, key };
  } finally {
    closeSync(descriptor);
  }
}
async function jsonBody(response: Response): Promise<Record<string, unknown>> {
  const reader = response.body?.getReader();
  if (!reader) throw new Error('FCM 响应为空');
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      length += next.value.byteLength;
      if (length > 65536) throw new Error('FCM 响应超限');
      chunks.push(next.value);
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
  let value: unknown;
  try {
    value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new Error('FCM 响应无效');
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('FCM 响应无效');
  return value as Record<string, unknown>;
}

/** The account file supplies a key, never an outbound URL. Token acquisition is single-flight. */
export function createFCMAuthorizer(config: FCMConfiguration, transport: Transport = fetch) {
  const account = credentials(config);
  let cached: { token: string; expires: number } | undefined;
  let pending: Promise<string> | undefined;
  const authorize = async (): Promise<string> => {
    if (cached && cached.expires > Date.now() + 60000) return cached.token;
    if (pending) return await pending;
    pending = (async () => {
      const now = Math.floor(Date.now() / 1000);
      const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString(
        'base64url',
      );
      const claims = Buffer.from(
        JSON.stringify({
          iss: account.email,
          scope: oauthScope,
          aud: tokenURL,
          iat: now,
          exp: now + 3600,
        }),
      ).toString('base64url');
      const input = header + '.' + claims;
      const jwt =
        input + '.' + sign('RSA-SHA256', Buffer.from(input), account.key).toString('base64url');
      const response = await transport(tokenURL, {
        method: 'POST',
        redirect: 'error',
        signal: AbortSignal.timeout(10000),
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
          assertion: jwt,
        }),
      });
      const value = await jsonBody(response);
      if (
        !response.ok ||
        value.token_type !== 'Bearer' ||
        typeof value.access_token !== 'string' ||
        !/^[\x21-\x7e]{1,8192}$/.test(value.access_token) ||
        !Number.isInteger(value.expires_in) ||
        Number(value.expires_in) < 60 ||
        Number(value.expires_in) > 3600
      )
        throw new Error('FCM 服务授权暂不可用');
      cached = { token: value.access_token, expires: Date.now() + Number(value.expires_in) * 1000 };
      return cached.token;
    })();
    try {
      return await pending;
    } finally {
      pending = undefined;
    }
  };
  return Object.assign(authorize, {
    invalidate: (observedToken?: string) => {
      if (observedToken === undefined || cached?.token === observedToken) cached = undefined;
    },
  });
}

/** Data-only delivery leaves authenticated decryption and local presentation to the native App. */
export function notificationFCMPayload(deviceToken: string, envelope: NotificationEnvelope) {
  if (!notificationPushToken('fcm', deviceToken)) throw new Error('FCM 设备令牌无效');
  parseNotificationEnvelope(envelope);
  const ttl = Math.min(2419200, Math.floor((Date.parse(envelope.expiresAt) - Date.now()) / 1000));
  if (ttl < 1) throw new Error('FCM 通知已过期');
  const data = { agent_hub_notification: JSON.stringify(envelope) };
  if (Buffer.byteLength(JSON.stringify(data), 'utf8') > 4096) throw new Error('FCM 密文超限');
  return {
    message: {
      token: deviceToken,
      data,
      android: { priority: envelope.badgeOnly ? 'NORMAL' : 'HIGH', ttl: ttl + 's' },
    },
  };
}
function retryAfter(response: Response): number {
  const value = response.headers.get('retry-after');
  if (!value) return 30000;
  const milliseconds = /^\d+$/.test(value) ? Number(value) * 1000 : Date.parse(value) - Date.now();
  return Number.isFinite(milliseconds) ? Math.min(3600000, Math.max(1000, milliseconds)) : 30000;
}
export function createFCMSender(config: FCMConfiguration, transport: Transport = fetch) {
  const authorize = createFCMAuthorizer(config, transport);
  return async (
    binding: { deviceToken: string },
    envelope: NotificationEnvelope,
  ): Promise<FCMResult> => {
    let payload: ReturnType<typeof notificationFCMPayload>;
    try {
      payload = notificationFCMPayload(binding.deviceToken, envelope);
    } catch {
      return { state: 'rejected', reason: 'FCMInvalidOrExpiredEnvelope' };
    }
    let token: string;
    try {
      token = await authorize();
    } catch {
      return { state: 'pending', reason: 'FCMAuthorizationUnavailable', retryAfterMs: 30000 };
    }
    try {
      const response = await transport(
        'https://fcm.googleapis.com/v1/projects/' + config.projectId + '/messages:send',
        {
          method: 'POST',
          redirect: 'error',
          signal: AbortSignal.timeout(10000),
          headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' },
          body: JSON.stringify(payload),
        },
      );
      if (response.status === 401) {
        authorize.invalidate(token);
        await response.body?.cancel();
        return { state: 'pending', reason: 'FCMAuthorizationRejected', retryAfterMs: 30000 };
      }
      if (response.status === 429 || response.status >= 500) {
        await response.body?.cancel();
        return {
          state: 'pending',
          reason: 'FCMRetryableRejection',
          retryAfterMs: retryAfter(response),
        };
      }
      let value: Record<string, unknown>;
      try {
        value = await jsonBody(response);
      } catch {
        return response.ok
          ? { state: 'uncertain', reason: 'FCMInvalidAcceptance' }
          : { state: 'rejected', reason: 'FCMRejected' };
      }
      if (response.ok) {
        return typeof value.name === 'string' &&
          value.name.startsWith('projects/' + config.projectId + '/messages/') &&
          value.name.length <= 512 &&
          !/\s/.test(value.name)
          ? { state: 'accepted' }
          : { state: 'uncertain', reason: 'FCMInvalidAcceptance' };
      }
      const error = value.error as { details?: { errorCode?: unknown }[] } | undefined;
      if (
        Array.isArray(error?.details) &&
        error.details.some((detail) => detail?.errorCode === 'UNREGISTERED')
      )
        return { state: 'rejected', reason: 'Unregistered' };
      return { state: 'rejected', reason: 'FCMRejected' };
    } catch {
      return { state: 'uncertain', reason: 'FCMUnknown' };
    }
  };
}
