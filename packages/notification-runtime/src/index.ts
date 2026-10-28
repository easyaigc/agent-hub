export { businessNotificationCipher } from './business-crypto.js';
export { NotificationObserver, type NotificationObservation } from './observer.js';
export { writeNotificationState, acquireNotificationState } from './state-file.js';
export type {
  NotificationRegistration,
  NotificationPreferences,
  RuntimeTarget,
} from '@codex-manage/protocol';
import {
  createCipheriv,
  createHash,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  hkdfSync,
  randomBytes,
  randomUUID,
} from 'node:crypto';
import { mkdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { dirname } from 'node:path';
import {
  notificationTargetKey,
  type NotificationEvent,
  type NotificationRegistration,
  type NotificationStatus,
  type NotificationEnvelope,
  type NotificationPreferences,
  type RuntimeTarget,
  parseNotificationEvent,
  type NotificationIntentV2,
  type NotificationFeedQueryV2,
} from '@codex-manage/protocol';
import type { NotificationObservation } from './observer.js';
import { acquireNotificationState, writeNotificationState } from './state-file.js';
import {
  NotificationRecoveryV2,
  NotificationRecoveryError,
  createRecoveryV2,
  appendRecoveryV2,
  validateRecoveryV2,
  recoveryHash,
  intentHash,
  originalObservationHashV2,
  type RecoveryStateV2,
} from './recovery-v2.js';
export { NotificationRecoveryError } from './recovery-v2.js';

export function notificationPublicKey(raw: string) {
  return createPublicKey({
    key: Buffer.concat([
      Buffer.from('302a300506032b656e032100', 'hex'),
      Buffer.from(raw, 'base64'),
    ]),
    format: 'der',
    type: 'spki',
  });
}
export function sealNotification(
  event: NotificationEvent,
  bindingId: string,
  privateKey: string,
  recipient: string,
): Pick<NotificationEnvelope, 'nonce' | 'ciphertext' | 'tag'> {
  const shared = diffieHellman({
    privateKey: createPrivateKey(privateKey),
    publicKey: notificationPublicKey(recipient),
  });
  const key = Buffer.from(
    hkdfSync(
      'sha256',
      shared,
      Buffer.from(bindingId),
      Buffer.from('codex-manage/notifications/v1'),
      32,
    ),
  );
  const nonce = randomBytes(12),
    cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(Buffer.from(event.id));
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(event), 'utf8'), cipher.final()]);
  return {
    nonce: nonce.toString('base64'),
    ciphertext: ciphertext.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'),
  };
}
interface Device {
  remoteRevocationPending?: boolean;
  registration: NotificationRegistration;
  ownerId?: string;
  subscriptionGeneration?: number;
  revokedPreferencesGeneration?: number;
  bindingId?: string;
  managementToken?: string;
  error?: string;
  syncedRevision?: string;
  retryAt?: number;
  checkedAt?: number;
  invalidToken?: string;
  lastDelivery?: NotificationStatus['lastDelivery'];
}
interface Entry {
  sequenceV2?: number;
  event: NotificationEvent;
  active: boolean;
  deliveries: Record<string, 'pending' | 'accepted' | 'uncertain' | 'rejected'>;
  envelopes?: Record<string, NotificationEnvelope>;
  cancellations?: Record<string, boolean>;
}
interface State {
  version: 1 | 2;
  recoveryV2?: RecoveryStateV2;
  target?: RuntimeTarget;
  privateKey: string;
  devices: Record<string, Device>;
  entries: Entry[];
  observations?: Record<string, NotificationObservation>;
  stoppedRuns?: string[];
  connection?: { connected: boolean; generation: number };
}
// JSON encoders may reorder object keys between requests or app launches.
// Compare preference values without treating wire property order as a revision.
function preferenceIdentity(preferences: NotificationPreferences): string {
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
export interface NotificationPublisherOptions {
  target: RuntimeTarget;
  statePath: string;
  relayURL?: string;
  publisherToken?: string;
  protectFile?: (path: string) => void;
}
/** Agent-neutral delivery boundary. It accepts domain events, never upstream RPC. */
export class NotificationPublisher {
  private state: State;
  private timer: NodeJS.Timeout;
  private busy = false;
  private closed = false;
  private verified = new Map<string, number>();
  private releaseLease: () => void;
  private recoveryFault = false;
  private recovery?: NotificationRecoveryV2;
  constructor(private options: NotificationPublisherOptions) {
    if (options.relayURL) {
      const url = new URL(options.relayURL);
      if (
        (url.protocol !== 'https:' &&
          !(
            url.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
          )) ||
        url.username ||
        url.password ||
        url.search ||
        url.hash
      )
        throw new Error('推送服务必须使用 HTTPS');
    }
    mkdirSync(dirname(options.statePath), { recursive: true, mode: 0o700 });
    this.releaseLease = acquireNotificationState(options.statePath, options.protectFile);
    try {
      if (existsSync(options.statePath)) {
        if (process.platform !== 'win32' && statSync(options.statePath).mode & 0o077)
          throw new Error('通知状态文件必须仅当前用户可读写');
        this.state = JSON.parse(readFileSync(options.statePath, 'utf8')) as State;
        if (
          ![1, 2].includes(this.state.version) ||
          !this.state.privateKey ||
          !this.state.devices ||
          !Array.isArray(this.state.entries)
        )
          throw new Error('无效通知状态文件');
        const expected = notificationTargetKey(options.target);
        if (
          (this.state.target && notificationTargetKey(this.state.target) !== expected) ||
          Object.values(this.state.devices).some(
            (device) => notificationTargetKey(device.registration.target) !== expected,
          ) ||
          this.state.entries.some((entry) => notificationTargetKey(entry.event.target) !== expected)
        )
          throw new Error('通知状态目标不一致');
        this.state.target = options.target;
        if (this.state.version === 2)
          validateRecoveryV2(this.state.recoveryV2, options.target, this.state.entries);
        else if (this.state.recoveryV2 !== undefined) throw new Error('通知恢复磁盘版本不一致');
      } else {
        const { privateKey } = generateKeyPairSync('x25519');
        this.state = {
          version: 1,
          target: options.target,
          privateKey: privateKey.export({ format: 'pem', type: 'pkcs8' }).toString(),
          devices: {},
          entries: [],
        };
        this.save();
      }
      if (this.state.version === 2) this.recoveryEngine().recover();
    } catch (error) {
      this.releaseLease();
      throw error;
    }
    this.timer = setInterval(() => {
      void this.flush().catch(() => undefined);
    }, 2000);
    this.timer.unref();
  }
  private save() {
    if (this.closed) return;
    if (this.recoveryFault) throw new NotificationRecoveryError(503, '通知持久化待恢复');
    try {
      if (this.state.version === 2) {
        appendRecoveryV2(this.state.recoveryV2!, this.state.entries);
        validateRecoveryV2(this.state.recoveryV2, this.options.target, this.state.entries);
      }
      writeNotificationState(this.options.statePath, this.state, this.options.protectFile);
    } catch (failure) {
      if (this.state.version === 2) this.recoveryFault = true;
      throw failure;
    }
  }
  private publishRecovery(next: State): void {
    if (this.closed || this.recoveryFault)
      throw new NotificationRecoveryError(503, '通知恢复服务不可用');
    try {
      validateRecoveryV2(next.recoveryV2, this.options.target, next.entries);
      writeNotificationState(this.options.statePath, next, this.options.protectFile);
      this.state = next;
    } catch (failure) {
      this.recoveryFault = true;
      throw failure;
    }
  }
  private assertLegacyWrite(deviceId: string): void {
    if (this.recoveryFault) throw new NotificationRecoveryError(503, '通知持久化待恢复');
    if (this.state.recoveryV2?.slots.some((s) => s.deviceId === deviceId))
      throw new NotificationRecoveryError(409, '此设备通知需要原 v2 操作收据');
  }
  private recoveryEngine(): NotificationRecoveryV2 {
    if (this.closed || this.recoveryFault)
      throw new NotificationRecoveryError(503, '通知恢复服务不可用');
    if (!this.state.recoveryV2) {
      const next = structuredClone(this.state);
      next.version = 2;
      next.recoveryV2 = createRecoveryV2();
      appendRecoveryV2(next.recoveryV2, next.entries);
      this.publishRecovery(next);
    }
    this.recovery ??= new NotificationRecoveryV2({
      target: this.options.target,
      read: () => {
        if (this.recoveryFault) throw new NotificationRecoveryError(503, '通知持久化待恢复');
        return this.state.recoveryV2!;
      },
      device: (id) => this.state.devices[id],
      sources: () => this.state.entries,
      validateKey: (publicKey) => {
        diffieHellman({
          privateKey: createPrivateKey(this.state.privateKey),
          publicKey: notificationPublicKey(publicKey),
        });
      },
      disposition: (id, event) => {
        const device = this.state.devices[id],
          entry = this.state.entries.find((e) => e.event.id === event.id);
        if (!entry?.active) return 'inactive';
        if (!this.current(entry)) return 'stale';
        return device && this.allowed(device, event) ? 'eligible' : 'filtered';
      },
      activeIds: (id) =>
        this.state.entries
          .filter(
            (e) =>
              e.active &&
              this.state.devices[id] &&
              ['approval', 'question', 'plan', 'uncertain', 'paused'].includes(e.event.kind),
          )
          .map((e) => e.event.id),
      commit: (recovery, apply) => {
        const next = structuredClone(this.state);
        next.version = 2;
        next.recoveryV2 = recovery;
        if (apply) {
          const old = next.devices[apply.deviceId];
          const registration: NotificationRegistration = {
            version: 1,
            deviceId: apply.deviceId,
            target: structuredClone(apply.target),
            publicKey: apply.publicKey,
            preferences: structuredClone(apply.preferences),
          };
          const device: Device =
            apply.kind === 'revoke'
              ? {
                  ...old,
                  registration: old
                    ? { ...old.registration, preferences: structuredClone(apply.preferences) }
                    : registration,
                }
              : {
                  registration,
                  ...(old?.ownerId ? { ownerId: old.ownerId } : {}),
                  error: '手机尚未取得 APNs 令牌',
                };
          device.subscriptionGeneration = (old?.subscriptionGeneration ?? 0) + 1;
          const owner = recovery.slots.find((s) => s.deviceId === apply.deviceId)?.ownerId;
          if (owner) device.ownerId = owner;
          if (apply.kind === 'revoke') {
            device.revokedPreferencesGeneration = Math.max(
              old?.revokedPreferencesGeneration ?? -1,
              old?.registration.preferences.generation ?? -1,
              apply.preferences.generation,
            );
            device.remoteRevocationPending = Boolean(
              old?.bindingId || (this.options.relayURL && this.options.publisherToken),
            );
            if (device.remoteRevocationPending) device.error = '远端订阅撤销待同步';
          }
          next.devices[apply.deviceId] = device;
          const receipt = recovery.operations.find(
            (o) => o.receipt.intent.operationId === apply.operationId,
          )!.receipt;
          receipt.status = {
            version: 1,
            target: this.options.target,
            delivery: device.bindingId ? 'apns' : 'foreground-only',
            state: !device.registration.preferences.enabled
              ? 'disabled'
              : device.error
                ? 'unavailable'
                : 'pending',
            revision: apply.preferences.revision,
          };
        }
        this.publishRecovery(next);
        if (apply?.kind === 'revoke')
          void Promise.resolve()
            .then(() =>
              this.unregisterDevice(
                apply.deviceId,
                next.devices[apply.deviceId]?.ownerId,
                apply.generation,
              ),
            )
            .catch(() => undefined);
      },
    });
    return this.recovery;
  }
  recoveryCapabilities() {
    return this.recoveryEngine().capabilities();
  }
  recoverySubmit(value: unknown, ownerId?: string) {
    return this.recoveryEngine().submit(value, ownerId);
  }
  recoveryReceipt(deviceId: string, operationId: string, epoch: string, ownerId?: string) {
    return this.recoveryEngine().query(deviceId, operationId, epoch, ownerId);
  }
  recoveryFeed(query: NotificationFeedQueryV2, ownerId?: string) {
    return this.recoveryEngine().feed(query, ownerId);
  }
  get hasSubscribers(): boolean {
    return Object.values(this.state.devices).some(
      (device) => device.registration.preferences.enabled,
    );
  }
  get publicKey(): string {
    return createPublicKey(createPrivateKey(this.state.privateKey))
      .export({ format: 'der', type: 'spki' })
      .subarray(-32)
      .toString('base64');
  }
  get observations(): NotificationObservation[] {
    return Object.values(this.state.observations ?? {});
  }
  get stoppedRuns(): string[] {
    return this.state.stoppedRuns ?? [];
  }
  markStopped(runId: string): void {
    this.state.stoppedRuns = [...new Set([...this.stoppedRuns, runId])].slice(-512);
    this.save();
  }
  observeConnection(connected: boolean): void {
    if (this.recoveryFault) throw new NotificationRecoveryError(503, '通知持久化待恢复');
    if (this.closed || this.state.connection?.connected === connected) return;
    const old = this.state.connection;
    const generation = (old?.generation ?? 0) + 1;
    this.state.connection = { connected, generation };
    if (old?.connected && !connected && this.hasSubscribers) {
      const event: NotificationEvent = {
        version: 1,
        target: this.options.target,
        kind: 'connection',
        occurredAt: new Date().toISOString(),
        id: createHash('sha256')
          .update(
            JSON.stringify([notificationTargetKey(this.options.target), 'connection', generation]),
          )
          .digest('hex'),
      };
      this.state.entries.push({ event, active: true, deliveries: {} });
    }
    this.save();
    void this.flush().catch(() => undefined);
  }
  async retainOwners(owners: readonly string[]): Promise<void> {
    for (const [id, device] of Object.entries(this.state.devices)) {
      if (
        device.ownerId &&
        device.registration.preferences.enabled &&
        !owners.includes(device.ownerId)
      )
        if (this.state.recoveryV2?.slots.some((s) => s.deviceId === id)) {
          const slot = this.state.recoveryV2.slots.find((s) => s.deviceId === id)!;
          const intent: NotificationIntentV2 = {
            version: 2,
            operationId: randomUUID(),
            epoch: this.state.recoveryV2.epoch,
            deviceId: id,
            target: this.options.target,
            publicKey: device.registration.publicKey,
            preferences: { ...device.registration.preferences, enabled: false },
            generation: slot.highest + 1,
            kind: 'revoke',
            fingerprint: '',
          };
          intent.fingerprint = intentHash(intent);
          this.recoverySubmit(intent, device.ownerId);
        } else await this.unregister(id, device.ownerId);
    }
  }
  async updatePreferences(
    deviceId: string,
    target: RuntimeTarget,
    preferences: NotificationPreferences,
    ownerId?: string,
  ): Promise<NotificationStatus> {
    const device = this.state.devices[deviceId];
    if (
      !device ||
      notificationTargetKey(target) !== notificationTargetKey(this.options.target) ||
      (device.ownerId && device.ownerId !== ownerId)
    )
      throw new Error('通知设备或目标未注册');
    return await this.register({ ...device.registration, preferences }, ownerId);
  }
  private async relay(path: string, data: unknown): Promise<Record<string, unknown>> {
    if (!this.options.relayURL || !this.options.publisherToken)
      throw new Error('电脑尚未配置推送服务');
    const base = new URL(this.options.relayURL);
    if (!base.pathname.endsWith('/')) base.pathname += '/';
    const response = await fetch(new URL(path.replace(/^\//, ''), base), {
      method: 'POST',
      redirect: 'error',
      signal: AbortSignal.timeout(10_000),
      headers: {
        authorization: 'Bearer ' + this.options.publisherToken,
        'content-type': 'application/json',
      },
      body: JSON.stringify(data),
    });
    if (!response.ok) throw new Error('推送服务暂不可用（' + response.status + '）');
    return (await response.json()) as Record<string, unknown>;
  }
  async register(
    registration: NotificationRegistration,
    ownerId?: string,
  ): Promise<NotificationStatus> {
    if (this.closed) throw new Error('通知服务已关闭');
    this.assertLegacyWrite(registration.deviceId);
    if (notificationTargetKey(registration.target) !== notificationTargetKey(this.options.target))
      throw new Error('通知目标不一致');
    // Reject low-order keys here, before they can break asynchronous delivery.
    diffieHellman({
      privateKey: createPrivateKey(this.state.privateKey),
      publicKey: notificationPublicKey(registration.publicKey),
    });
    const previous = this.state.devices[registration.deviceId];
    if (previous?.ownerId && previous.ownerId !== ownerId)
      throw new Error('通知设备不属于当前授权');
    if (
      previous &&
      ((registration.preferences.enabled &&
        registration.preferences.generation <= (previous.revokedPreferencesGeneration ?? -1)) ||
        registration.preferences.generation < previous.registration.preferences.generation ||
        (registration.preferences.generation === previous.registration.preferences.generation &&
          preferenceIdentity(registration.preferences) !==
            preferenceIdentity(previous.registration.preferences)))
    )
      throw new Error('通知设置已变化');
    const device: Device = {
      ...previous,
      registration: structuredClone(registration),
      subscriptionGeneration: (previous?.subscriptionGeneration ?? 0) + 1,
      managementToken: previous?.managementToken ?? randomBytes(32).toString('base64url'),
      ...(ownerId ? { ownerId } : {}),
    };
    this.state.devices[registration.deviceId] = device;
    this.save();
    if (
      !registration.preferences.enabled ||
      registration.preferences.excludedHosts.includes(this.options.target.hostId)
    ) {
      await this.unregister(registration.deviceId, ownerId);
      return this.status(registration.deviceId, ownerId);
    }
    if (registration.deviceToken && this.options.relayURL && this.options.publisherToken) {
      try {
        const result = await this.relay('/v1/push/register', {
          hostId: this.options.target.hostId,
          target: this.options.target,
          deviceId: registration.deviceId,
          deviceToken: registration.deviceToken,
          environment: registration.environment,
          generation: device.subscriptionGeneration,
          managementToken: device.managementToken,
        });
        if (typeof result.bindingId !== 'string' || typeof result.managementToken !== 'string')
          throw new Error('无效推送注册响应');
        if (this.closed) return this.status(registration.deviceId, ownerId);
        if (
          this.state.devices[registration.deviceId] !== device ||
          !device.registration.preferences.enabled
        ) {
          // A newer registration owns this binding. Its durable generation also
          // prevents an old request arriving at the relay from restoring a token.
          return this.status(registration.deviceId, ownerId);
        }
        if (
          device.bindingId !== result.bindingId ||
          previous?.registration.publicKey !== registration.publicKey
        ) {
          for (const entry of this.state.entries) {
            delete entry.envelopes?.[registration.deviceId];
          }
        }
        device.bindingId = result.bindingId;
        device.managementToken = result.managementToken;
        device.syncedRevision = registration.preferences.revision;
        delete device.error;
        delete device.invalidToken;
        delete device.retryAt;
      } catch (error) {
        if (this.closed || this.state.devices[registration.deviceId] !== device)
          return this.status(registration.deviceId, ownerId);
        device.error = error instanceof Error ? error.message : '推送注册待重试';
        device.retryAt = Date.now() + 30_000;
      }
    } else {
      device.error = registration.deviceToken ? '电脑尚未配置推送服务' : '手机尚未取得 APNs 令牌';
    }
    this.save();
    void this.flush().catch(() => undefined);
    return this.status(registration.deviceId, ownerId);
  }
  async unregister(deviceId: string, ownerId?: string): Promise<void> {
    this.assertLegacyWrite(deviceId);
    return await this.unregisterDevice(deviceId, ownerId);
  }
  private async unregisterDevice(
    deviceId: string,
    ownerId?: string,
    expectedGeneration?: number,
  ): Promise<void> {
    if (this.closed || this.recoveryFault) return;
    if (expectedGeneration !== undefined) {
      const slot = this.state.recoveryV2?.slots.find((s) => s.deviceId === deviceId);
      if (slot?.applied !== expectedGeneration || slot.revoked !== expectedGeneration) return;
    }
    const previous = this.state.devices[deviceId];
    if (!previous) return;
    if (previous.ownerId && previous.ownerId !== ownerId) throw new Error('通知设备不属于当前授权');
    const device: Device = {
      ...previous,
      registration: structuredClone(previous.registration),
      subscriptionGeneration: (previous.subscriptionGeneration ?? 0) + 1,
    };
    this.state.devices[deviceId] = device;
    device.revokedPreferencesGeneration = Math.max(
      device.revokedPreferencesGeneration ?? -1,
      device.registration.preferences.generation,
    );
    device.registration.preferences.enabled = false;
    this.save();
    if (device.bindingId || (this.options.relayURL && this.options.publisherToken)) {
      try {
        // Revoke by target even when an in-flight registration has not returned
        // its binding yet. The relay keeps this generation as a tombstone.
        await this.relay('/v1/push/unregister', {
          hostId: this.options.target.hostId,
          target: this.options.target,
          deviceId,
          generation: device.subscriptionGeneration,
        });
        if (this.closed || this.state.devices[deviceId] !== device) return;
        delete device.bindingId;
        delete device.managementToken;
        delete device.remoteRevocationPending;
        delete device.error;
      } catch {
        if (this.closed || this.state.devices[deviceId] !== device) return;
        device.error = '远端订阅撤销待同步';
        device.retryAt = Date.now() + 30_000;
      }
    }
    this.save();
  }
  status(deviceId: string, ownerId?: string): NotificationStatus {
    const device = this.state.devices[deviceId];
    if (device?.ownerId && device.ownerId !== ownerId) throw new Error('通知设备不属于当前授权');
    return {
      version: 1,
      target: this.options.target,
      delivery: device?.bindingId ? 'apns' : 'foreground-only',
      state: !device?.registration.preferences.enabled
        ? 'disabled'
        : device.error
          ? 'unavailable'
          : device.bindingId
            ? 'ready'
            : 'pending',
      ...(device?.error ? { reason: device.error } : {}),
      ...(device ? { revision: device.registration.preferences.revision } : {}),
      ...(device?.bindingId
        ? {
            bindingId: device.bindingId,
            publicKey: this.publicKey,
            relayURL: this.options.relayURL,
            managementToken: device.managementToken,
          }
        : {}),
      ...(device?.lastDelivery ? { lastDelivery: device.lastDelivery } : {}),
    };
  }
  observe(
    events: NotificationEvent[],
    threadId: string,
    activeIds: string[],
    live: boolean,
    observation?: NotificationObservation,
  ): void {
    if (!live || this.closed) return; // Loss of an owner or a reader cannot resolve a pending request.
    if (this.recoveryFault) throw new NotificationRecoveryError(503, '通知持久化待恢复');
    if (this.state.recoveryV2)
      for (const event of events) {
        parseNotificationEvent(event);
        recoveryHash(event);
        if (notificationTargetKey(event.target) !== notificationTargetKey(this.options.target))
          throw new Error('通知事件目标不一致');
        const old =
          this.state.entries.find((e) => e.event.id === event.id) ??
          this.state.recoveryV2.log.find((e) => e.event.id === event.id);
        if (old && originalObservationHashV2(old.event) !== originalObservationHashV2(event))
          throw new Error('通知原事件内容冲突');
      }
    this.verified.set(threadId, Date.now());
    const activeKinds = ['approval', 'question', 'plan', 'uncertain', 'paused'];
    let changed = false;
    if (live && observation && !['__proto__', 'prototype', 'constructor'].includes(threadId)) {
      this.state.observations ??= {};
      if (JSON.stringify(this.state.observations[threadId]) !== JSON.stringify(observation)) {
        this.state.observations[threadId] = observation;
        changed = true;
      }
    }
    for (const entry of this.state.entries)
      if ((entry.event.threadId ?? '') === threadId && activeKinds.includes(entry.event.kind)) {
        const active = live && activeIds.includes(entry.event.id);
        if (active !== entry.active) {
          entry.active = active;
          changed = true;
        }
      }
    for (const event of events) {
      if (notificationTargetKey(event.target) !== notificationTargetKey(this.options.target))
        throw new Error('通知事件目标不一致');
      const old = this.state.entries.find((entry) => entry.event.id === event.id);
      if (old) {
        if (activeIds.includes(event.id) && !old.active) {
          old.active = true;
          changed = true;
        }
        continue;
      }
      this.state.entries.push({ event, active: true, deliveries: {} });
      changed = true;
    }
    if (changed) {
      // Journal every observed original before v1's independent last-512 pruning.
      if (this.state.recoveryV2) {
        try {
          appendRecoveryV2(this.state.recoveryV2, this.state.entries);
        } catch (failure) {
          this.recoveryFault = true;
          throw failure;
        }
      }
      this.state.entries = this.state.entries.filter(
        (entry, index, entries) =>
          (entry.active && activeKinds.includes(entry.event.kind)) ||
          (entry.envelopes &&
            !entry.active &&
            Object.keys(entry.envelopes).some((id) => !entry.cancellations?.[id])) ||
          index >= entries.length - 512,
      );
      this.save();
      void this.flush().catch(() => undefined);
    }
  }
  foreground(
    deviceId: string,
    after: string,
    ownerId?: string,
  ): {
    events: NotificationEvent[];
    cursor: string;
    activeIds: string[];
    status: NotificationStatus;
  } {
    const device = this.state.devices[deviceId];
    if (!device)
      return { events: [], cursor: after, activeIds: [], status: this.status(deviceId, ownerId) };
    if (device.ownerId && device.ownerId !== ownerId) throw new Error('通知设备不属于当前授权');
    const index = after ? this.state.entries.findIndex((entry) => entry.event.id === after) : -1;
    const entries = this.state.entries.slice(index + 1);
    const deliverable = entries.filter(
      (entry) => entry.active && this.allowed(device, entry.event) && this.current(entry),
    );
    return {
      events:
        this.status(deviceId, ownerId).delivery === 'apns'
          ? []
          : deliverable.map((entry) => entry.event),
      cursor: deliverable.at(-1)?.event.id ?? after,
      activeIds: this.state.entries
        .filter(
          (entry) =>
            entry.active &&
            this.allowed(device, entry.event) &&
            ['approval', 'question', 'plan', 'uncertain', 'paused'].includes(entry.event.kind),
        )
        .map((entry) => entry.event.id),
      status: this.status(deviceId, ownerId),
    };
  }
  private allowed(device: Device, event: NotificationEvent): boolean {
    const prefs = device.registration.preferences;
    return (
      prefs.enabled &&
      !prefs.excludedHosts.includes(event.target.hostId) &&
      (prefs.kinds.includes(event.kind) || (prefs.badgeEnabled && Boolean(event.threadId)))
    );
  }
  private current(entry: Entry): boolean {
    if (['approval', 'question', 'plan', 'uncertain', 'paused'].includes(entry.event.kind))
      return Date.now() - (this.verified.get(entry.event.threadId ?? '') ?? 0) < 30_000;
    return Date.now() - Date.parse(entry.event.occurredAt) < 600_000;
  }
  private async flush(): Promise<void> {
    if (this.busy || this.closed || this.recoveryFault) return;
    this.busy = true;
    try {
      for (const [id, device] of Object.entries(this.state.devices)) {
        if (this.closed) return;
        if (
          !device.registration.preferences.enabled &&
          (device.bindingId || device.remoteRevocationPending)
        ) {
          if (!device.retryAt || Date.now() >= device.retryAt)
            await this.unregisterDevice(
              id,
              device.ownerId,
              this.state.recoveryV2?.slots.find((s) => s.deviceId === id)?.applied,
            );
          continue;
        }
        if (
          device.registration.preferences.enabled &&
          device.error &&
          device.registration.deviceToken &&
          device.invalidToken !== device.registration.deviceToken &&
          this.options.relayURL &&
          this.options.publisherToken &&
          (!device.retryAt || Date.now() >= device.retryAt)
        ) {
          await this.register(device.registration, device.ownerId);
          continue;
        }
        if (!device.bindingId || device.error) continue;
        if (Date.now() - (device.checkedAt ?? 0) > 30_000) {
          device.checkedAt = Date.now();
          try {
            const result = await this.relay('/v1/push/binding-status', {
              bindingId: device.bindingId,
            });
            if (result.lastDelivery)
              device.lastDelivery = result.lastDelivery as NotificationStatus['lastDelivery'];
            if (result.enabled === false) {
              device.error = '推送设备令牌已失效或订阅已撤销，请重新开启通知';
              if (device.registration.deviceToken)
                device.invalidToken = device.registration.deviceToken;
            }
            this.save();
          } catch {
            /* Publishing has its own durable retry queue. */
          }
        }
        for (const entry of this.state.entries) {
          if (this.closed) return;
          const prior = entry.envelopes?.[id];
          if (
            (!entry.active || !this.allowed(device, entry.event)) &&
            prior &&
            !entry.cancellations?.[id]
          ) {
            try {
              await this.relay('/v1/push/cancel', {
                bindingId: prior.bindingId,
                eventId: prior.eventId,
              });
              entry.cancellations ??= {};
              entry.cancellations[id] = true;
              this.save();
            } catch {
              /* Preserve cancellation until the relay can acknowledge it. */
            }
            continue;
          }
          if (
            !entry.active ||
            !this.allowed(device, entry.event) ||
            !this.current(entry) ||
            entry.deliveries[id]
          )
            continue;
          const prefs = device.registration.preferences;
          const badgeOnly = !prefs.kinds.includes(entry.event.kind);
          if (!entry.envelopes) entry.envelopes = {};
          if (prior && Date.parse(prior.expiresAt) <= Date.now()) {
            try {
              const status = await this.relay('/v1/push/status', {
                bindingId: prior.bindingId,
                eventId: prior.eventId,
              });
              if (status.state !== 'missing') {
                entry.deliveries[id] =
                  status.state === 'uncertain'
                    ? 'uncertain'
                    : ['cancelled', 'rejected'].includes(String(status.state))
                      ? 'rejected'
                      : 'accepted';
                this.save();
                continue;
              }
              delete entry.envelopes[id];
            } catch {
              continue;
            }
          }
          let sealed = entry.envelopes[id]
            ? undefined
            : sealNotification(
                entry.event,
                device.bindingId,
                this.state.privateKey,
                device.registration.publicKey,
              );
          if (sealed && sealed.ciphertext.length > 2800 && entry.event.threadTitle) {
            const summary = { ...entry.event };
            delete summary.threadTitle;
            sealed = sealNotification(
              summary,
              device.bindingId,
              this.state.privateKey,
              device.registration.publicKey,
            );
          }
          if (sealed && sealed.ciphertext.length > 2800) {
            entry.deliveries[id] = 'rejected';
            device.lastDelivery = {
              state: 'rejected',
              occurredAt: new Date().toISOString(),
              reason: 'NotificationPayloadTooLarge',
            };
            this.save();
            continue;
          }
          const envelope: NotificationEnvelope = entry.envelopes[id] ?? {
            version: 1,
            bindingId: device.bindingId,
            eventId: entry.event.id,
            ...sealed!,
            expiresAt: new Date(Date.now() + 600_000).toISOString(),
            sound:
              !badgeOnly && prefs.soundEnabled
                ? (prefs.categorySounds[entry.event.kind] ?? prefs.sound)
                : 'none',
            timeSensitive:
              !badgeOnly &&
              prefs.timeSensitive &&
              ['approval', 'question', 'plan', 'paused', 'failed', 'uncertain'].includes(
                entry.event.kind,
              ),
            ...(badgeOnly ? { badgeOnly: true } : {}),
          };
          entry.envelopes[id] = envelope;
          this.save();
          try {
            const response = await this.relay('/v1/push/publish', envelope);
            if (this.closed) return;
            entry.deliveries[id] =
              response.state === 'uncertain'
                ? 'uncertain'
                : response.state === 'rejected'
                  ? 'rejected'
                  : 'accepted';
            this.save();
          } catch {
            /* Relay has a durable idempotency ledger; retrying this envelope never executes a task. */
          }
        }
      }
    } finally {
      this.busy = false;
    }
  }
  close(): void {
    try {
      this.save();
    } finally {
      this.closed = true;
      clearInterval(this.timer);
      this.releaseLease();
    }
  }
}
