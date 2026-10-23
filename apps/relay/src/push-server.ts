import {
  businessPushHandler,
  type BusinessPushState,
  type BusinessPublisherConfiguration,
} from './business-push.js';
import { createServer, type IncomingMessage } from 'node:http';
import { connect } from 'node:http2';
import { createHash, createPrivateKey, randomUUID, sign, timingSafeEqual } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import { dirname } from 'node:path';
import {
  acquireNotificationState,
  writeNotificationState,
} from '@codex-manage/notification-runtime';
import {
  notificationObject,
  notificationTargetKey,
  parseNotificationEnvelope,
  parseNotificationTarget,
  type NotificationEnvelope,
  type RuntimeTarget,
} from '@codex-manage/protocol';

export interface APNsSigningKey {
  keyId: string;
  keyPath: string;
}
type APNsEnvironment = 'development' | 'production';
export interface PushConfiguration {
  statePath: string;
  topic: string;
  teamId: string;
  /** Legacy keys may support both environments. New Apple keys are environment-specific. */
  keyId?: string;
  keyPath?: string;
  keys?: Partial<Record<APNsEnvironment, APNsSigningKey>>;
  hosts: { hostId: string; token: string }[];
  businessPublishers?: BusinessPublisherConfiguration[];
}
interface Binding {
  id: string;
  hostId: string;
  deviceId: string;
  target: RuntimeTarget;
  deviceToken: string;
  environment: APNsEnvironment;
  managementHash: string;
  enabled: boolean;
  generation: number;
}
interface Delivery {
  fingerprint: string;
  envelope: NotificationEnvelope;
  state: 'pending' | 'submitting' | 'accepted' | 'uncertain' | 'rejected';
  reason?: string;
  cancelled?: boolean;
  createdAt?: string;
}
interface PushState {
  business?: BusinessPushState;
  version: 1;
  bindings: Record<string, Binding>;
  deliveries: Record<string, Delivery>;
  cancelled?: Record<string, string>;
  subscriptions?: Record<string, { generation: number; enabled: boolean }>;
}
export interface APNsResult {
  state: 'accepted' | 'pending' | 'uncertain' | 'rejected';
  reason?: string;
}
const hash = (value: string) => createHash('sha256').update(value).digest('hex');
const equal = (a: string, b: string) => {
  const left = Buffer.from(a),
    right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
};
function configuredAPNsKey(config: PushConfiguration, environment: APNsEnvironment) {
  return (
    config.keys?.[environment] ??
    (config.keyId && config.keyPath ? { keyId: config.keyId, keyPath: config.keyPath } : undefined)
  );
}
/** Each environment signs with its own key and token cache. */
export function createAPNsAuthorizer(config: PushConfiguration) {
  if (Boolean(config.keyId) !== Boolean(config.keyPath)) throw new Error('APNs 密钥配置不完整');
  const credentials = new Map<
    APNsEnvironment,
    { keyId: string; key: ReturnType<typeof createPrivateKey>; jwt: string; created: number }
  >();
  for (const environment of ['development', 'production'] as const) {
    const signing = configuredAPNsKey(config, environment);
    if (!signing) continue;
    if (!signing.keyId || !signing.keyPath) throw new Error('APNs 密钥配置不完整');
    assertPushPrivateFile(signing.keyPath);
    const key = createPrivateKey(readFileSync(signing.keyPath));
    if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1')
      throw new Error('APNs 需要 ES256 P-256 私钥');
    credentials.set(environment, { keyId: signing.keyId, key, jwt: '', created: 0 });
  }
  if (!credentials.size) throw new Error('尚未配置 APNs 环境密钥');
  return (environment: APNsEnvironment): string | undefined => {
    const signing = credentials.get(environment);
    if (!signing) return undefined;
    if (Date.now() - signing.created > 3_000_000 || !signing.jwt) {
      const header = Buffer.from(JSON.stringify({ alg: 'ES256', kid: signing.keyId })).toString(
        'base64url',
      );
      const payload = Buffer.from(
        JSON.stringify({ iss: config.teamId, iat: Math.floor(Date.now() / 1000) }),
      ).toString('base64url');
      const data = header + '.' + payload;
      signing.jwt =
        data +
        '.' +
        sign('sha256', Buffer.from(data), {
          key: signing.key,
          dsaEncoding: 'ieee-p1363',
        }).toString('base64url');
      signing.created = Date.now();
    }
    return signing.jwt;
  };
}
/** No conversation plaintext, Codex login or execution lives here. */
export function notificationAPNsPayload(
  envelope: NotificationEnvelope & { domain?: string },
  bindingId: string,
) {
  const business = envelope.domain === 'taskhub.business';
  const aps = envelope.badgeOnly
    ? { 'content-available': 1 }
    : {
        alert: { title: 'Agent Hub', body: '有新的任务提醒，请打开查看。' },
        'mutable-content': 1,
        category: business ? 'agenthub.business' : 'agenthub.task',
        ...(envelope.sound !== 'none' ? { sound: envelope.sound } : {}),
        ...(envelope.badge !== undefined ? { badge: envelope.badge } : {}),
        ...(envelope.timeSensitive ? { 'interruption-level': 'time-sensitive' } : {}),
        'thread-id': bindingId,
      };
  return business ? { aps, businessNotification: envelope } : { aps, notification: envelope };
}
export function createAPNsSender(config: PushConfiguration) {
  const authorize = createAPNsAuthorizer(config);
  return async (
    binding: Pick<Binding, 'id' | 'environment' | 'deviceToken'> & { topic?: string },
    envelope: NotificationEnvelope,
  ): Promise<APNsResult> => {
    const jwt = authorize(binding.environment);
    if (!jwt) return { state: 'rejected', reason: 'APNsEnvironmentNotConfigured' };
    const data = JSON.stringify(notificationAPNsPayload(envelope, binding.id));
    if (Buffer.byteLength(data) > 4096) return { state: 'rejected', reason: 'PayloadTooLarge' };
    return await new Promise<APNsResult>((resolve) => {
      let finished = false,
        written = false;
      const client = connect(
        binding.environment === 'production'
          ? 'https://api.push.apple.com'
          : 'https://api.sandbox.push.apple.com',
      );
      const finish = (result: APNsResult) => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        client.destroy();
        resolve(result);
      };
      const timer = setTimeout(
        () => finish({ state: written ? 'uncertain' : 'pending', reason: 'APNsTimeout' }),
        10_000,
      );
      client.on('error', () =>
        finish({ state: written ? 'uncertain' : 'pending', reason: 'APNsConnection' }),
      );
      client.once('connect', () => {
        const request = client.request({
          ':method': 'POST',
          ':path': '/3/device/' + binding.deviceToken,
          authorization: 'bearer ' + jwt,
          'apns-topic': binding.topic ?? config.topic,
          'apns-push-type': envelope.badgeOnly ? 'background' : 'alert',
          'apns-priority': envelope.badgeOnly ? '5' : '10',
          'apns-collapse-id': envelope.eventId,
          'apns-expiration': String(Math.floor(Date.parse(envelope.expiresAt) / 1000)),
        });
        let status = 0,
          body = '';
        request.on('response', (headers) => {
          status = Number(headers[':status']);
        });
        request.on('data', (chunk) => {
          body += String(chunk);
        });
        request.on('error', () =>
          finish({ state: written ? 'uncertain' : 'pending', reason: 'APNsConnection' }),
        );
        request.on('end', () => {
          if (status === 200) {
            finish({ state: 'accepted' });
            return;
          }
          let reason = 'APNsRejected';
          try {
            reason = String(JSON.parse(body).reason ?? reason);
          } catch {
            /* No sensitive payload is logged. */
          }
          finish({ state: status === 429 || status >= 500 ? 'pending' : 'rejected', reason });
        });
        written = true;
        request.end(data);
      });
    });
  };
}
export function assertPushPrivateFile(path: string): void {
  const file = statSync(path);
  if (!file.isFile() || (process.platform !== 'win32' && file.mode & 0o077))
    throw new Error('推送配置、密钥和状态文件必须仅服务账户可读写');
}
async function readJSON(request: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const value of request) {
    const chunk = value as Buffer;
    size += chunk.length;
    if (size > 16_384) throw new Error('请求过大');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
export async function startPushServer(
  config: PushConfiguration,
  options: { port?: number; address?: string; send?: ReturnType<typeof createAPNsSender> } = {},
) {
  if (
    !config.topic ||
    (!config.hosts.length && !config.businessPublishers?.length) ||
    config.hosts.some((host) => !host.hostId || host.token.length < 32) ||
    new Set(config.hosts.map((host) => host.hostId)).size !== config.hosts.length ||
    new Set(config.hosts.map((host) => host.token)).size !== config.hosts.length
  )
    throw new Error('无效推送服务配置');
  const publishers = config.businessPublishers ?? [];
  const publisherKeys = publishers.map((p) =>
    JSON.stringify([p.deploymentId, p.serverInstanceId, p.primaryHostId]),
  );
  const tokens = [...config.hosts.map((h) => h.token), ...publishers.map((p) => p.token)];
  if (
    publishers.some(
      (p) =>
        ![p.deploymentId, p.serverInstanceId, p.primaryHostId].every((id) =>
          /^[A-Za-z0-9._-]{1,128}$/.test(id),
        ) ||
        p.token.length < 32 ||
        (p.topic !== undefined && !/^[A-Za-z0-9][A-Za-z0-9.-]{2,199}$/.test(p.topic)),
    ) ||
    new Set(publisherKeys).size !== publisherKeys.length ||
    new Set(tokens).size !== tokens.length
  )
    throw new Error('Invalid business publisher configuration');
  const send = options.send ?? createAPNsSender(config);
  if (existsSync(config.statePath)) assertPushPrivateFile(config.statePath);
  mkdirSync(dirname(config.statePath), { recursive: true, mode: 0o700 });
  const releaseLease = acquireNotificationState(config.statePath);
  let state: PushState;
  try {
    state = existsSync(config.statePath)
      ? (JSON.parse(readFileSync(config.statePath, 'utf8')) as PushState)
      : { version: 1, bindings: {}, deliveries: {} };
    if (state.version !== 1 || !state.bindings || !state.deliveries)
      throw new Error('无效推送服务状态');
  } catch (error) {
    releaseLease();
    throw error;
  }
  if (state.version !== 1 || !state.bindings || !state.deliveries)
    throw new Error('无效推送服务状态');
  const subscriptionKey = (target: RuntimeTarget, deviceId: string) =>
    hash(JSON.stringify([notificationTargetKey(target), deviceId]));
  state.subscriptions ??= {};
  for (const binding of Object.values(state.bindings)) {
    binding.generation ??= 0;
    state.subscriptions[subscriptionKey(binding.target, binding.deviceId)] ??= {
      generation: binding.generation,
      enabled: binding.enabled,
    };
  }
  // A crash after the write boundary is not evidence of rejection; do not replay it.
  for (const delivery of Object.values(state.deliveries))
    if (delivery.state === 'submitting') delivery.state = 'uncertain';
  const save = () => {
    writeNotificationState(config.statePath, state);
  };
  state.business ??= { bindings: {}, deliveries: {}, subscriptions: {} };
  const business = businessPushHandler({
    publishers,
    defaultTopic: config.topic,
    state: state.business,
    save,
    environmentAvailable: (environment) => Boolean(configuredAPNsKey(config, environment)),
    send,
  });
  save();
  let busy = false,
    closed = false;
  const flush = async () => {
    if (busy || closed) return;
    busy = true;
    try {
      await business.flush();
      for (const [id, delivery] of Object.entries(state.deliveries))
        if (
          delivery.state !== 'pending' &&
          delivery.state !== 'submitting' &&
          Date.parse(delivery.envelope.expiresAt) < Date.now() - 7 * 86400_000
        )
          delete state.deliveries[id];
      for (const [id, timestamp] of Object.entries(state.cancelled ?? {}))
        if (Date.parse(timestamp) < Date.now() - 7 * 86400_000) delete state.cancelled?.[id];
      for (const delivery of Object.values(state.deliveries)) {
        if (closed) return;
        if (delivery.state !== 'pending') continue;
        const binding = state.bindings[delivery.envelope.bindingId];
        if (
          delivery.cancelled ||
          !binding?.enabled ||
          Date.parse(delivery.envelope.expiresAt) < Date.now()
        ) {
          delivery.state = 'rejected';
          delivery.reason = 'ExpiredOrRevoked';
          save();
          continue;
        }
        delivery.state = 'submitting';
        save();
        const result = await send(binding, delivery.envelope).catch((): APNsResult => ({
          state: 'uncertain',
          reason: 'APNsUnknown',
        }));
        if (closed) return;
        delivery.state = result.state;
        if (result.reason) delivery.reason = result.reason;
        if (
          ['BadDeviceToken', 'Unregistered', 'DeviceTokenNotForTopic'].includes(result.reason ?? '')
        )
          binding.enabled = false;
        save();
      }
    } finally {
      busy = false;
    }
  };
  const timer = setInterval(() => {
    void flush().catch(() => undefined);
  }, 2000);
  timer.unref();
  const server = createServer((request, response) => {
    const json = (status: number, value: unknown) => {
      response.writeHead(status, {
        'content-type': 'application/json',
        'cache-control': 'no-store',
      });
      response.end(JSON.stringify(value));
    };
    void (async () => {
      if (request.headers.origin) {
        json(403, { error: 'CrossOriginDenied' });
        return;
      }
      const path = new URL(request.url ?? '/', 'http://localhost').pathname;
      if (request.method === 'GET' && path === '/healthz') {
        json(200, {
          service: 'relay',
          status: 'ok',
          protocolVersion: '0.1',
          capabilities: [
            'health',
            'notifications:push',
            ...(publishers.length ? ['business-notifications:push'] : []),
          ],
        });
        return;
      }
      if (request.method !== 'POST') {
        json(404, { error: 'NotFound' });
        return;
      }
      const token = (request.headers.authorization ?? '').replace(/^Bearer /, '');
      if (!request.headers.authorization?.startsWith('Bearer ')) {
        json(401, { error: 'Unauthorized' });
        return;
      }
      const host = config.hosts.find((value) => equal(value.token, token));
      const data = await readJSON(request);
      if (closed) return;
      if (path === '/v1/push/revoke') {
        const row = notificationObject(data, ['bindingId']);
        const binding = state.bindings[String(row.bindingId)];
        if (!binding || !equal(hash(token), binding.managementHash)) {
          json(403, { error: 'BindingDenied' });
          return;
        }
        binding.enabled = false;
        state.subscriptions![subscriptionKey(binding.target, binding.deviceId)] = {
          generation: binding.generation,
          enabled: false,
        };
        save();
        json(200, { state: 'revoked' });
        return;
      }
      if (path.startsWith('/v1/business-push/')) {
        const result = business.handle(path, token, data);
        json(result.status, result.body);
        void flush().catch(() => undefined);
        return;
      }
      if (!host) {
        json(401, { error: 'Unauthorized' });
        return;
      }
      if (path === '/v1/push/register') {
        const row = notificationObject(data, [
          'hostId',
          'deviceId',
          'target',
          'deviceToken',
          'environment',
          'generation',
          'managementToken',
        ]);
        const target = parseNotificationTarget(row.target);
        if (
          row.hostId !== host.hostId ||
          target.hostId !== host.hostId ||
          typeof row.deviceId !== 'string' ||
          !/^[A-Za-z0-9._-]{1,128}$/.test(row.deviceId) ||
          ['__proto__', 'prototype', 'constructor'].includes(row.deviceId) ||
          !Number.isSafeInteger(row.generation) ||
          Number(row.generation) < 1 ||
          typeof row.managementToken !== 'string' ||
          !/^[A-Za-z0-9_-]{43}$/.test(row.managementToken) ||
          typeof row.deviceToken !== 'string' ||
          !/^[a-f0-9]{64,256}$/.test(row.deviceToken) ||
          !['development', 'production'].includes(String(row.environment))
        ) {
          json(403, { error: 'TargetDenied' });
          return;
        }
        if (!configuredAPNsKey(config, row.environment as APNsEnvironment)) {
          json(503, { error: 'APNsEnvironmentNotConfigured' });
          return;
        }
        let binding = Object.values(state.bindings).find(
          (value) =>
            value.hostId === host.hostId &&
            value.deviceId === row.deviceId &&
            notificationTargetKey(value.target) === notificationTargetKey(target),
        );
        const key = subscriptionKey(target, row.deviceId),
          prior = state.subscriptions![key];
        const generation = Number(row.generation),
          managementToken = row.managementToken;
        if (
          prior &&
          (generation < prior.generation || (generation === prior.generation && !prior.enabled))
        ) {
          json(409, { error: 'StaleSubscription' });
          return;
        }
        if (prior && generation === prior.generation) {
          if (
            !binding ||
            binding.deviceToken !== row.deviceToken ||
            binding.environment !== row.environment ||
            !equal(binding.managementHash, hash(managementToken))
          ) {
            json(409, { error: 'SubscriptionConflict' });
            return;
          }
          json(200, { bindingId: binding.id, managementToken });
          return;
        }
        binding = {
          id: binding?.id ?? randomUUID(),
          hostId: host.hostId,
          deviceId: row.deviceId,
          target,
          deviceToken: row.deviceToken,
          environment: row.environment as Binding['environment'],
          managementHash: hash(managementToken),
          enabled: true,
          generation,
        };
        state.bindings[binding.id] = binding;
        state.subscriptions![key] = { generation, enabled: true };
        save();
        json(200, { bindingId: binding.id, managementToken });
        return;
      }
      if (path === '/v1/push/unregister') {
        const row = notificationObject(data, ['hostId', 'target', 'deviceId', 'generation']);
        const target = parseNotificationTarget(row.target);
        if (
          row.hostId !== host.hostId ||
          target.hostId !== host.hostId ||
          typeof row.deviceId !== 'string' ||
          !/^[A-Za-z0-9._-]{1,128}$/.test(row.deviceId) ||
          ['__proto__', 'prototype', 'constructor'].includes(row.deviceId) ||
          !Number.isSafeInteger(row.generation) ||
          Number(row.generation) < 1
        ) {
          json(403, { error: 'TargetDenied' });
          return;
        }
        const key = subscriptionKey(target, row.deviceId),
          generation = Number(row.generation);
        if (generation < (state.subscriptions![key]?.generation ?? 0)) {
          json(409, { error: 'StaleSubscription' });
          return;
        }
        state.subscriptions![key] = { generation, enabled: false };
        for (const binding of Object.values(state.bindings))
          if (
            binding.hostId === host.hostId &&
            binding.deviceId === row.deviceId &&
            notificationTargetKey(binding.target) === notificationTargetKey(target)
          ) {
            binding.enabled = false;
            binding.generation = generation;
          }
        save();
        json(200, { state: 'revoked' });
        return;
      }
      if (path === '/v1/push/binding-status') {
        const row = notificationObject(data, ['bindingId']);
        const binding = state.bindings[String(row.bindingId)];
        if (!binding || binding.hostId !== host.hostId) {
          json(403, { error: 'BindingDenied' });
          return;
        }
        const last = Object.values(state.deliveries)
          .filter((value) => value.envelope.bindingId === binding.id)
          .at(-1);
        json(200, {
          enabled: binding.enabled,
          ...(last
            ? {
                lastDelivery: {
                  state: last.state,
                  occurredAt:
                    last.createdAt ??
                    new Date(Date.parse(last.envelope.expiresAt) - 600_000).toISOString(),
                  ...(last.reason ? { reason: last.reason } : {}),
                },
              }
            : {}),
        });
        return;
      }
      if (path === '/v1/push/cancel' || path === '/v1/push/status') {
        const row = notificationObject(data, ['bindingId', 'eventId']);
        const binding = state.bindings[String(row.bindingId)];
        if (
          !binding ||
          binding.hostId !== host.hostId ||
          typeof row.eventId !== 'string' ||
          !/^[a-f0-9]{64}$/.test(row.eventId)
        ) {
          json(403, { error: 'BindingDenied' });
          return;
        }
        const id = binding.id + ':' + row.eventId;
        const delivery = state.deliveries[id];
        if (path === '/v1/push/cancel') {
          state.cancelled ??= {};
          state.cancelled[id] = new Date().toISOString();
          if (delivery) {
            delivery.cancelled = true;
            if (delivery.state === 'pending') {
              delivery.state = 'rejected';
              delivery.reason = 'Resolved';
            }
          }
          save();
        }
        json(200, {
          eventId: row.eventId,
          state: state.cancelled?.[id] ? 'cancelled' : (delivery?.state ?? 'missing'),
          ...(delivery?.reason ? { reason: delivery.reason } : {}),
        });
        return;
      }
      if (path === '/v1/push/publish') {
        const envelope = parseNotificationEnvelope(data),
          binding = state.bindings[envelope.bindingId];
        if (!binding?.enabled || binding.hostId !== host.hostId) {
          json(403, { error: 'BindingDenied' });
          return;
        }
        if (
          Date.parse(envelope.expiresAt) < Date.now() ||
          Date.parse(envelope.expiresAt) > Date.now() + 660_000
        ) {
          json(400, { error: 'ExpirationDenied' });
          return;
        }
        const id = binding.id + ':' + envelope.eventId,
          fingerprint = hash(JSON.stringify(envelope));
        if (state.cancelled?.[id]) {
          json(200, { eventId: envelope.eventId, state: 'rejected', reason: 'Resolved' });
          return;
        }
        let delivery = state.deliveries[id];
        if (delivery && delivery.fingerprint !== fingerprint) {
          json(409, { error: 'EventConflict' });
          return;
        }
        if (!delivery) {
          if (Object.keys(state.deliveries).length >= 10000) {
            json(503, { error: 'QueueFull' });
            return;
          }
          delivery = {
            fingerprint,
            envelope,
            state: 'pending',
            createdAt: new Date().toISOString(),
          };
          state.deliveries[id] = delivery;
          save();
        }
        void flush().catch(() => undefined);
        json(200, { eventId: envelope.eventId, state: delivery.state });
        return;
      }
      json(404, { error: 'NotFound' });
    })().catch(() => {
      if (!response.headersSent) json(400, { error: 'InvalidPushRequest' });
    });
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 10_000;
  try {
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject);
      server.listen(
        options.port ?? Number(process.env.RELAY_PORT ?? 8787),
        options.address ?? process.env.RELAY_BIND_ADDRESS ?? '127.0.0.1',
        resolve,
      );
    });
  } catch (error) {
    clearInterval(timer);
    releaseLease();
    throw error;
  }
  const close = () => {
    if (closed) return;
    closed = true;
    business.close();
    clearInterval(timer);
    server.close();
    server.closeAllConnections();
    save();
    releaseLease();
  };
  return { server, close };
}
