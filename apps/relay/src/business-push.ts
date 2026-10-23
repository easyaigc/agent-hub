import { createHash, timingSafeEqual, randomUUID } from 'node:crypto';
import {
  businessIdentityKey,
  businessTargetKey,
  notificationObject,
  parseBusinessTarget,
  parseBusinessEnvelope,
  type BusinessServiceIdentity,
  type BusinessNoticeTarget,
  type BusinessNotificationEnvelope,
} from '@codex-manage/protocol';

export interface BusinessPublisherConfiguration extends BusinessServiceIdentity {
  token: string;
  topic?: string;
}
export interface BusinessPushBinding {
  id: string;
  target: BusinessNoticeTarget;
  projectId: string;
  deviceId: string;
  deviceToken: string;
  environment: 'development' | 'production';
  managementHash: string;
  enabled: boolean;
  generation: number;
  topic?: string;
}
type Result = { state: 'pending' | 'accepted' | 'uncertain' | 'rejected'; reason?: string };
interface Delivery {
  envelope: BusinessNotificationEnvelope;
  fingerprint: string;
  state: Result['state'] | 'submitting';
  cancelled?: boolean;
  reason?: string;
}
export interface BusinessPushState {
  bindings: Record<string, BusinessPushBinding>;
  deliveries: Record<string, Delivery>;
  subscriptions: Record<string, { generation: number; enabled: boolean }>;
}
const hash = (v: string) => createHash('sha256').update(v).digest('hex');
const same = (a: string, b: string) => {
  const x = Buffer.from(a),
    y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
};
const uuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);

/** Ciphertext transport only. Business principals never enter Host bindings. */
export function businessPushHandler(options: {
  publishers: BusinessPublisherConfiguration[];
  defaultTopic: string;
  state: BusinessPushState;
  save: () => void;
  environmentAvailable: (environment: 'development' | 'production') => boolean;
  send: (binding: BusinessPushBinding, envelope: BusinessNotificationEnvelope) => Promise<Result>;
}) {
  const { state, save } = options;
  let busy = false,
    closed = false;
  for (const delivery of Object.values(state.deliveries))
    if (delivery.state === 'submitting') delivery.state = 'uncertain';
  const flush = async () => {
    if (busy || closed) return;
    busy = true;
    try {
      for (const [id, delivery] of Object.entries(state.deliveries)) {
        if (closed) return;
        if (
          Date.parse(delivery.envelope.expiresAt) < Date.now() - 7 * 86400000 &&
          delivery.state !== 'submitting'
        ) {
          delete state.deliveries[id];
          save();
          continue;
        }
        if (delivery.state !== 'pending') continue;
        const binding = state.bindings[delivery.envelope.bindingId];
        if (
          !binding?.enabled ||
          !options.publishers.some(
            (p) =>
              businessIdentityKey(p) === businessIdentityKey(binding.target) &&
              (p.topic ?? options.defaultTopic) === binding.topic,
          ) ||
          delivery.cancelled ||
          Date.parse(delivery.envelope.expiresAt) <= Date.now()
        ) {
          delivery.state = 'rejected';
          delivery.reason = 'ExpiredOrRevoked';
          save();
          continue;
        }
        delivery.state = 'submitting';
        save();
        const result = await options
          .send(binding, delivery.envelope)
          .catch((): Result => ({ state: 'uncertain', reason: 'APNsUnknown' }));
        if (closed) return;
        delivery.state = result.state;
        if (result.reason) delivery.reason = result.reason;
        else delete delivery.reason;
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
  const revoke = (binding: BusinessPushBinding, generation: number) => {
    binding.enabled = false;
    binding.generation = generation;
    state.subscriptions[
      hash(businessTargetKey(binding.target) + ':' + binding.projectId + ':' + binding.deviceId)
    ] = { generation, enabled: false };
    for (const delivery of Object.values(state.deliveries))
      if (delivery.envelope.bindingId === binding.id) {
        delivery.cancelled = true;
        if (delivery.state === 'pending') {
          delivery.state = 'rejected';
          delivery.reason = 'Revoked';
        }
      }
    save();
  };
  return {
    flush,
    close() {
      closed = true;
    },
    handle(path: string, token: string, value: unknown): { status: number; body: unknown } {
      const result = (status: number, body: unknown) => ({ status, body });
      if (!path.startsWith('/v1/business-push/')) throw new Error('Invalid business push path');
      if (path === '/v1/business-push/revoke') {
        const row = notificationObject(value, ['bindingId']);
        const binding = state.bindings[String(row.bindingId)];
        if (!binding || !same(hash(token), binding.managementHash))
          return result(403, { error: 'BindingDenied' });
        revoke(binding, binding.generation);
        return result(200, { state: 'revoked' });
      }
      const publisher = options.publishers.find((p) => same(p.token, token));
      if (!publisher) return result(401, { error: 'BusinessPublisherDenied' });
      const own = (binding: BusinessPushBinding | undefined) =>
        binding && businessIdentityKey(binding.target) === businessIdentityKey(publisher);
      if (path === '/v1/business-push/register' || path === '/v1/business-push/unregister') {
        const registration = path.endsWith('/register');
        const row = notificationObject(
          value,
          registration
            ? [
                'target',
                'projectId',
                'deviceId',
                'deviceToken',
                'environment',
                'generation',
                'managementToken',
              ]
            : ['target', 'projectId', 'deviceId', 'generation'],
        );
        const target = parseBusinessTarget(row.target);
        if (
          businessIdentityKey(target) !== businessIdentityKey(publisher) ||
          !uuid(row.projectId) ||
          !uuid(row.deviceId) ||
          !Number.isSafeInteger(row.generation) ||
          Number(row.generation) < 1
        )
          return result(403, { error: 'BusinessTargetDenied' });
        const key = hash(businessTargetKey(target) + ':' + row.projectId + ':' + row.deviceId),
          prior = state.subscriptions[key],
          generation = Number(row.generation);
        if (
          prior &&
          (generation < prior.generation ||
            (registration && generation === prior.generation && !prior.enabled))
        )
          return result(409, { error: 'StaleSubscription' });
        let binding = Object.values(state.bindings).find(
          (b) =>
            businessTargetKey(b.target) === businessTargetKey(target) &&
            b.projectId === row.projectId &&
            b.deviceId === row.deviceId,
        );
        if (!registration) {
          if (binding) revoke(binding, generation);
          else {
            state.subscriptions[key] = { generation, enabled: false };
            save();
          }
          return result(200, { state: 'revoked' });
        }
        if (
          typeof row.deviceToken !== 'string' ||
          !/^[a-f0-9]{64,256}$/.test(row.deviceToken) ||
          !['development', 'production'].includes(String(row.environment)) ||
          typeof row.managementToken !== 'string' ||
          !/^[A-Za-z0-9_-]{43}$/.test(row.managementToken)
        )
          return result(400, { error: 'InvalidBusinessRegistration' });
        if (!options.environmentAvailable(row.environment as BusinessPushBinding['environment']))
          return result(503, { error: 'APNsEnvironmentNotConfigured' });
        if (prior && generation === prior.generation) {
          if (
            !binding ||
            binding.deviceToken !== row.deviceToken ||
            binding.environment !== row.environment ||
            binding.topic !== (publisher.topic ?? options.defaultTopic) ||
            !same(binding.managementHash, hash(row.managementToken))
          )
            return result(409, { error: 'SubscriptionConflict' });
        } else {
          if (!binding && Object.keys(state.bindings).length >= 10000)
            return result(503, { error: 'BindingLimit' });
          binding = {
            topic: publisher.topic ?? options.defaultTopic,
            id: binding?.id ?? randomUUID(),
            target,
            projectId: row.projectId,
            deviceId: row.deviceId,
            deviceToken: row.deviceToken,
            environment: row.environment as BusinessPushBinding['environment'],
            managementHash: hash(row.managementToken),
            generation,
            enabled: true,
          };
          state.bindings[binding.id] = binding;
          state.subscriptions[key] = { generation, enabled: true };
          save();
        }
        return result(200, { bindingId: binding!.id });
      }
      if (path === '/v1/business-push/publish') {
        const envelope = parseBusinessEnvelope(value),
          binding = state.bindings[envelope.bindingId];
        if (!own(binding) || !binding?.enabled)
          return result(403, { error: 'BusinessBindingDenied' });
        if (
          Date.parse(envelope.expiresAt) <= Date.now() ||
          Date.parse(envelope.expiresAt) > Date.now() + 660000
        )
          return result(400, { error: 'ExpirationDenied' });
        const id = binding.id + ':' + envelope.eventId,
          fingerprint = hash(
            JSON.stringify(
              Object.keys(envelope)
                .sort()
                .map((k) => [k, envelope[k as keyof BusinessNotificationEnvelope]]),
            ),
          ),
          prior = state.deliveries[id];
        if (prior && prior.fingerprint !== fingerprint)
          return result(409, { error: 'EventConflict' });
        if (!prior) {
          if (Object.keys(state.deliveries).length >= 10000)
            return result(503, { error: 'QueueFull' });
          state.deliveries[id] = { envelope, fingerprint, state: 'pending' };
          save();
        }
        return result(200, {
          eventId: envelope.eventId,
          state: state.deliveries[id]!.cancelled ? 'cancelled' : state.deliveries[id]!.state,
        });
      }
      if (path === '/v1/business-push/status' || path === '/v1/business-push/binding-status') {
        const row = notificationObject(
          value,
          path.endsWith('/binding-status') ? ['bindingId'] : ['bindingId', 'eventId'],
        );
        const binding = state.bindings[String(row.bindingId)];
        if (!own(binding)) return result(403, { error: 'BusinessBindingDenied' });
        if (path.endsWith('/binding-status')) return result(200, { enabled: binding!.enabled });
        if (typeof row.eventId !== 'string' || !/^[a-f0-9]{64}$/.test(row.eventId))
          return result(400, { error: 'InvalidEventId' });
        const delivery = state.deliveries[binding!.id + ':' + row.eventId];
        return result(200, {
          eventId: row.eventId,
          state: delivery?.cancelled ? 'cancelled' : (delivery?.state ?? 'missing'),
          ...(delivery?.reason ? { reason: delivery.reason } : {}),
        });
      }
      return result(404, { error: 'NotFound' });
    },
  };
}
