import { createHash, randomUUID } from 'node:crypto';
import {
  notificationObject,
  notificationTargetKey,
  notificationRecoveryLimits as limits,
  notificationV2Canonical,
  notificationV2IntentBytes,
  notificationV2Uuid,
  notificationV2Device,
  parseNotificationIntentV2,
  parseNotificationEvent,
  parseNotificationFeedQueryV2,
  type NotificationIntentV2,
  type NotificationReceiptV2,
  type NotificationFeedQueryV2,
  type NotificationFeedV2,
  type NotificationEvent,
  type NotificationRegistration,
  type RuntimeTarget,
  type NotificationDispositionV2,
} from '@codex-manage/protocol';

export const recoveryHash = (value: unknown): string =>
  createHash('sha256').update(notificationV2Canonical(value)).digest('hex');
export const intentHash = (value: NotificationIntentV2): string =>
  createHash('sha256').update(notificationV2IntentBytes(value)).digest('hex');
/** Ignore account changes in observations; retain the first original wire payload without rewriting it. */
export function originalObservationHashV2(event: NotificationEvent): string {
  const target = (value: RuntimeTarget) => {
    if (value.agentId !== 'codex') return value;
    const { account: _metadata, ...machine } = value;
    void _metadata;
    return machine;
  };
  return recoveryHash({
    ...event,
    target: target(event.target),
    ...(event.readTarget ? { readTarget: target(event.readTarget) } : {}),
  });
}
export interface RecoverySourceV2 {
  event: NotificationEvent;
  sequenceV2?: number;
}
interface Slot {
  deviceId: string;
  highest: number;
  applied: number;
  revoked: number;
  ownerId?: string;
}
interface Original {
  sequence: number;
  event: NotificationEvent;
  fingerprint: string;
}
interface Snapshot {
  id: string;
  deviceId: string;
  generation: number;
  hash: string;
  baseline: number;
  expiresAt: number;
  events: NotificationEvent[];
}
interface Operation {
  receipt: NotificationReceiptV2;
  ownerId?: string;
}
export interface RecoveryStateV2 {
  version: 2;
  epoch: string;
  head: number;
  floor: number;
  log: Original[];
  slots: Slot[];
  operations: Operation[];
  snapshots: Snapshot[];
}
export class NotificationRecoveryError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}
export function createRecoveryV2(): RecoveryStateV2 {
  return {
    version: 2,
    epoch: randomUUID(),
    head: 0,
    floor: 1,
    log: [],
    slots: [],
    operations: [],
    snapshots: [],
  };
}
function capacity(state: RecoveryStateV2): void {
  if (
    state.slots.length > limits.slots ||
    state.operations.length > limits.operations ||
    state.snapshots.length > limits.snapshots ||
    Buffer.byteLength(notificationV2Canonical(state)) > limits.totalBytes
  )
    throw new NotificationRecoveryError(507, '通知恢复资料已达上限');
}
/** Assign once in the same persisted file as the original v1 entry; active entries never get resequenced. */
export function appendRecoveryV2(state: RecoveryStateV2, sources: RecoverySourceV2[]): void {
  const known = new Map(state.log.map((row) => [row.event.id, row]));
  let logBytes = Buffer.byteLength(notificationV2Canonical(state.log));
  for (const source of sources) {
    parseNotificationEvent(source.event);
    const existing = known.get(source.event.id);
    if (existing && existing.fingerprint !== recoveryHash(source.event))
      throw new Error('通知原事件内容冲突');
    if (source.sequenceV2 !== undefined) {
      if (
        !Number.isSafeInteger(source.sequenceV2) ||
        source.sequenceV2 < 1 ||
        source.sequenceV2 > state.head ||
        (existing && existing.sequence !== source.sequenceV2)
      )
        throw new Error('通知原事件序号冲突');
      continue;
    }
    if (existing) {
      source.sequenceV2 = existing.sequence;
      continue;
    }
    if (state.head >= Number.MAX_SAFE_INTEGER)
      throw new NotificationRecoveryError(507, '通知序号已达上限');
    source.sequenceV2 = ++state.head;
    const original = {
      sequence: source.sequenceV2,
      event: structuredClone(source.event),
      fingerprint: recoveryHash(source.event),
    };
    state.log.push(original);
    known.set(source.event.id, original);
    logBytes +=
      Buffer.byteLength(notificationV2Canonical(original)) + (state.log.length > 1 ? 1 : 0);
    while (state.log.length > limits.logEvents || logBytes > limits.logBytes) {
      const previousLength = state.log.length,
        removed = state.log.shift();
      if (!removed) break;
      logBytes -=
        Buffer.byteLength(notificationV2Canonical(removed)) + (previousLength > 1 ? 1 : 0);
      known.delete(removed.event.id);
      state.floor = removed.sequence + 1;
    }
  }
  capacity(state);
}
export function validateRecoveryV2(
  value: unknown,
  target: RuntimeTarget,
  sources: RecoverySourceV2[],
): asserts value is RecoveryStateV2 {
  const raw = notificationObject(value, [
    'version',
    'epoch',
    'head',
    'floor',
    'log',
    'slots',
    'operations',
    'snapshots',
  ]);
  if (
    raw.version !== 2 ||
    !notificationV2Uuid(raw.epoch) ||
    !Number.isSafeInteger(raw.head) ||
    Number(raw.head) < 0 ||
    !Number.isSafeInteger(raw.floor) ||
    Number(raw.floor) < 1 ||
    Number(raw.floor) > Number(raw.head) + 1 ||
    !['log', 'slots', 'operations', 'snapshots'].every((k) => Array.isArray(raw[k]))
  )
    throw new Error('无效通知恢复资料');
  const state = raw as unknown as RecoveryStateV2;
  capacity(state);
  const unique = (values: unknown[]) => {
    if (new Set(values).size !== values.length) throw new Error('重复通知恢复标识');
  };
  unique(state.slots.map((s) => s.deviceId));
  unique(state.operations.map((o) => o.receipt.intent.operationId));
  unique(state.snapshots.map((s) => s.id));
  unique(state.log.map((s) => s.event.id));
  if (
    state.log.length > limits.logEvents ||
    Buffer.byteLength(notificationV2Canonical(state.log)) > limits.logBytes ||
    state.log.length !== state.head - state.floor + 1
  )
    throw new Error('无效通知保留范围');
  state.log.forEach((entry, index) => {
    notificationObject(entry, ['sequence', 'event', 'fingerprint']);
    parseNotificationEvent(entry.event);
    if (
      entry.sequence !== state.floor + index ||
      entry.fingerprint !== recoveryHash(entry.event) ||
      notificationTargetKey(entry.event.target) !== notificationTargetKey(target)
    )
      throw new Error('无效通知原事件');
  });
  for (const slot of state.slots) {
    notificationObject(slot, ['deviceId', 'highest', 'applied', 'revoked', 'ownerId']);
    if (
      !notificationV2Device(slot.deviceId) ||
      ![slot.highest, slot.applied, slot.revoked].every((n) => Number.isSafeInteger(n) && n >= 0) ||
      slot.applied > slot.highest ||
      slot.revoked > slot.highest ||
      (slot.ownerId !== undefined && typeof slot.ownerId !== 'string')
    )
      throw new Error('无效通知代次墓碑');
  }
  for (const operation of state.operations) {
    notificationObject(operation, ['receipt', 'ownerId']);
    const r = notificationObject(operation.receipt, [
      'version',
      'epoch',
      'intent',
      'stage',
      'reason',
      'status',
      'snapshot',
    ]) as unknown as NotificationReceiptV2;
    parseNotificationIntentV2(r.intent);
    if (
      r.version !== 2 ||
      r.epoch !== state.epoch ||
      r.intent.epoch !== state.epoch ||
      r.intent.fingerprint !== intentHash(r.intent) ||
      notificationTargetKey(r.intent.target) !== notificationTargetKey(target) ||
      !['reserved', 'applied', 'rejected'].includes(r.stage) ||
      (operation.ownerId !== undefined && typeof operation.ownerId !== 'string') ||
      (r.reason !== undefined && typeof r.reason !== 'string')
    )
      throw new Error('无效通知原收据');
    if (
      r.status &&
      (r.status.version !== 1 ||
        notificationTargetKey(r.status.target) !== notificationTargetKey(target))
    )
      throw new Error('无效通知收据目标');
    if (
      r.snapshot &&
      (!notificationV2Uuid(r.snapshot.id) ||
        r.snapshot.id !== r.intent.operationId ||
        !Number.isSafeInteger(r.snapshot.count) ||
        r.snapshot.count < 0 ||
        r.snapshot.count > limits.snapshotEvents)
    )
      throw new Error('无效通知复位收据');
  }
  for (const snapshot of state.snapshots) {
    notificationObject(snapshot, [
      'id',
      'deviceId',
      'generation',
      'hash',
      'baseline',
      'expiresAt',
      'events',
    ]);
    if (
      !notificationV2Uuid(snapshot.id) ||
      !notificationV2Device(snapshot.deviceId) ||
      !Number.isSafeInteger(snapshot.generation) ||
      snapshot.generation < 1 ||
      !Number.isSafeInteger(snapshot.baseline) ||
      snapshot.baseline < 0 ||
      snapshot.baseline > state.head ||
      !Number.isSafeInteger(snapshot.expiresAt) ||
      snapshot.expiresAt < 0 ||
      !Array.isArray(snapshot.events) ||
      snapshot.events.length > limits.snapshotEvents ||
      Buffer.byteLength(notificationV2Canonical(snapshot.events)) > limits.snapshotBytes ||
      snapshot.hash !== recoveryHash(snapshot.events)
    )
      throw new Error('无效通知复位快照');
    unique(snapshot.events.map((e) => e.id));
    snapshot.events.forEach((e) => {
      parseNotificationEvent(e);
      if (notificationTargetKey(e.target) !== notificationTargetKey(target))
        throw new Error('通知快照目标不一致');
    });
  }
  const sequences = new Set<number>();
  for (const source of sources) {
    if (
      !Number.isSafeInteger(source.sequenceV2) ||
      Number(source.sequenceV2) < 1 ||
      Number(source.sequenceV2) > state.head ||
      sequences.has(source.sequenceV2!)
    )
      throw new Error('通知来源缺少稳定序号');
    sequences.add(source.sequenceV2!);
    const known = state.log.find((r) => r.sequence === source.sequenceV2);
    if (known && recoveryHash(source.event) !== known.fingerprint)
      throw new Error('通知来源正文漂移');
  }
}
export interface RecoveryHooksV2 {
  target: RuntimeTarget;
  read(): RecoveryStateV2;
  /** Must durably publish receipt + device mutation atomically; only then replace live memory. */
  commit(next: RecoveryStateV2, apply?: NotificationIntentV2): void;
  device(id: string):
    | {
        registration: NotificationRegistration;
        bindingId?: string;
        remoteRevocationPending?: boolean;
        revokedPreferencesGeneration?: number;
        ownerId?: string;
      }
    | undefined;
  sources(): RecoverySourceV2[];
  disposition(deviceId: string, event: NotificationEvent): NotificationDispositionV2;
  activeIds(deviceId: string): string[];
  validateKey(publicKey: string): void;
}
/** No network or model execution. Reserve/apply are separate durable phases for crash recovery. */
export class NotificationRecoveryV2 {
  constructor(
    private hooks: RecoveryHooksV2,
    private now: () => number = Date.now,
  ) {}
  private owner(deviceId: string, ownerId?: string): void {
    const slot = this.hooks.read().slots.find((s) => s.deviceId === deviceId);
    const original = slot?.ownerId ?? this.hooks.device(deviceId)?.ownerId;
    if (original && original !== ownerId)
      throw new NotificationRecoveryError(403, '通知设备不属于当前授权');
  }
  adopted(deviceId: string): boolean {
    return this.hooks.read().slots.some((s) => s.deviceId === deviceId);
  }
  capabilities() {
    return {
      version: 2 as const,
      epoch: this.hooks.read().epoch,
      target: this.hooks.target,
      operations: ['register', 'preferences', 'revoke', 'feed-reset'],
      limits,
    };
  }
  query(deviceId: string, operationId: string, epoch: string, ownerId?: string) {
    if (
      !notificationV2Device(deviceId) ||
      !notificationV2Uuid(operationId) ||
      !notificationV2Uuid(epoch)
    )
      throw new NotificationRecoveryError(400, '无效通知收据查询');
    this.owner(deviceId, ownerId);
    const state = this.hooks.read(),
      row = state.operations.find((o) => o.receipt.intent.operationId === operationId);
    if (row && (row.receipt.intent.deviceId !== deviceId || row.ownerId !== ownerId))
      throw new NotificationRecoveryError(403, '通知原操作不属于当前目标');
    return {
      version: 2 as const,
      epoch: state.epoch,
      target: this.hooks.target,
      deviceId,
      operationId,
      ...(epoch !== state.epoch
        ? { found: false as const, reason: 'epoch-mismatch' }
        : row
          ? { found: true as const, receipt: structuredClone(row.receipt) }
          : { found: false as const, reason: 'absent-unknown' }),
    };
  }
  reserve(value: unknown, ownerId?: string): NotificationReceiptV2 {
    let intent: NotificationIntentV2;
    try {
      intent = structuredClone(parseNotificationIntentV2(value));
    } catch {
      throw new NotificationRecoveryError(400, '无效通知恢复意图');
    }
    if (
      intent.fingerprint !== intentHash(intent) ||
      notificationTargetKey(intent.target) !== notificationTargetKey(this.hooks.target)
    )
      throw new NotificationRecoveryError(400, '通知意图指纹或目标不一致');
    const state = this.hooks.read();
    if (intent.epoch !== state.epoch)
      throw new NotificationRecoveryError(409, '通知存储代次已变化');
    this.owner(intent.deviceId, ownerId);
    try {
      this.hooks.validateKey(intent.publicKey);
    } catch {
      throw new NotificationRecoveryError(400, '无效通知 X25519 公钥');
    }
    const existing = state.operations.find(
      (o) => o.receipt.intent.operationId === intent.operationId,
    );
    if (existing) {
      if (
        existing.ownerId !== ownerId ||
        notificationV2Canonical(existing.receipt.intent) !== notificationV2Canonical(intent)
      )
        throw new NotificationRecoveryError(409, '通知操作标识已绑定其他意图');
      return structuredClone(existing.receipt);
    }
    const next = structuredClone(state);
    let slot = next.slots.find((s) => s.deviceId === intent.deviceId);
    const device = this.hooks.device(intent.deviceId);
    const usedGeneration = state.operations.reduce(
      (highest, row) =>
        row.receipt.intent.deviceId === intent.deviceId && row.receipt.intent.kind !== 'feed-reset'
          ? Math.max(highest, row.receipt.intent.generation)
          : highest,
      0,
    );
    let reason: string | undefined;
    if (intent.kind === 'feed-reset') {
      if (
        !slot ||
        slot.applied !== intent.generation ||
        slot.highest !== slot.applied ||
        slot.revoked >= intent.generation ||
        !device ||
        recoveryHash(device.registration.preferences) !== recoveryHash(intent.preferences) ||
        device.registration.publicKey !== intent.publicKey
      )
        reason = 'subscription-mismatch';
    } else if (intent.generation <= Math.max(usedGeneration, slot?.highest ?? 0))
      reason = 'stale-generation';
    else if (
      intent.kind !== 'revoke' &&
      device &&
      ((intent.preferences.enabled &&
        intent.preferences.generation <= (device.revokedPreferencesGeneration ?? -1)) ||
        intent.preferences.generation < device.registration.preferences.generation ||
        (intent.preferences.generation === device.registration.preferences.generation &&
          recoveryHash(intent.preferences) !== recoveryHash(device.registration.preferences)))
    )
      reason = 'preferences-generation-conflict';
    else if (
      intent.kind === 'preferences' &&
      (!slot ||
        !device ||
        slot.applied === 0 ||
        slot.revoked >= slot.applied ||
        device.registration.publicKey !== intent.publicKey)
    )
      reason = 'not-registered';
    else if (
      intent.kind !== 'revoke' &&
      (device?.bindingId || device?.remoteRevocationPending || device?.registration.deviceToken)
    )
      reason = 'foreground-binding-conflict';
    if (!reason && intent.kind !== 'feed-reset') {
      if (!slot) {
        slot = {
          deviceId: intent.deviceId,
          highest: 0,
          applied: 0,
          revoked: 0,
          ...(ownerId ? { ownerId } : {}),
        };
        next.slots.push(slot);
      }
      slot.highest = intent.generation;
      if (intent.kind === 'revoke') slot.revoked = intent.generation;
    }
    const receipt: NotificationReceiptV2 = {
      version: 2,
      epoch: state.epoch,
      intent,
      stage: reason ? 'rejected' : 'reserved',
      ...(reason ? { reason } : {}),
    };
    next.operations.push({ receipt, ...(ownerId ? { ownerId } : {}) });
    capacity(next);
    this.hooks.commit(next);
    return structuredClone(receipt);
  }
  settle(operationId: string): NotificationReceiptV2 {
    const state = this.hooks.read(),
      next = structuredClone(state);
    const record = next.operations.find((o) => o.receipt.intent.operationId === operationId);
    if (!record) throw new NotificationRecoveryError(404, '通知原操作不存在');
    const receipt = record.receipt,
      intent = receipt.intent;
    if (receipt.stage !== 'reserved') return structuredClone(receipt);
    const slot = next.slots.find((s) => s.deviceId === intent.deviceId);
    if (
      !slot ||
      slot.highest !== intent.generation ||
      (intent.kind !== 'revoke' && slot.revoked >= intent.generation)
    ) {
      receipt.stage = 'rejected';
      receipt.reason = 'superseded-generation';
      this.hooks.commit(next);
      return structuredClone(receipt);
    }
    if (intent.kind === 'feed-reset') {
      const gap = intent.gap!;
      next.snapshots = next.snapshots.filter((s) => s.expiresAt > this.now());
      const originals = new Map<string, { event: NotificationEvent; sequence: number }>();
      for (const source of this.hooks.sources())
        originals.set(source.event.id, { event: source.event, sequence: source.sequenceV2! });
      for (const source of next.log) {
        const previous = originals.get(source.event.id);
        if (previous && recoveryHash(previous.event) !== source.fingerprint)
          throw new Error('通知复位原事件冲突');
        originals.set(source.event.id, { event: source.event, sequence: source.sequence });
      }
      const events = [...originals.values()]
        .sort((a, b) => a.sequence - b.sequence)
        .map((v) => structuredClone(v.event));
      if (
        gap.floor !== state.floor ||
        gap.head > state.head ||
        (gap.epoch === state.epoch && gap.after >= state.floor - 1)
      ) {
        receipt.stage = 'rejected';
        receipt.reason = 'gap-changed-or-unaccepted';
      } else if (
        events.length > limits.snapshotEvents ||
        Buffer.byteLength(notificationV2Canonical(events)) > limits.snapshotBytes ||
        next.snapshots.length >= limits.snapshots
      ) {
        receipt.stage = 'rejected';
        receipt.reason = 'snapshot-capacity';
      } else {
        const snapshot = {
          id: intent.operationId,
          deviceId: intent.deviceId,
          generation: intent.generation,
          events,
          hash: recoveryHash(events),
          baseline: state.head,
          expiresAt: this.now() + limits.snapshotLifetimeMs,
        };
        next.snapshots.push(snapshot);
        receipt.snapshot = {
          id: snapshot.id,
          hash: snapshot.hash,
          count: events.length,
          baseline: snapshot.baseline,
          expiresAt: snapshot.expiresAt,
        };
        receipt.stage = 'applied';
      }
      capacity(next);
      this.hooks.commit(next);
    } else {
      slot.applied = intent.generation;
      receipt.stage = 'applied';
      this.hooks.commit(next, intent);
    }
    return structuredClone(
      this.hooks.read().operations.find((o) => o.receipt.intent.operationId === operationId)!
        .receipt,
    );
  }
  submit(value: unknown, ownerId?: string) {
    const receipt = this.reserve(value, ownerId);
    return this.settle(receipt.intent.operationId);
  }
  recover(): void {
    for (const o of [...this.hooks.read().operations])
      if (o.receipt.stage === 'reserved') this.settle(o.receipt.intent.operationId);
  }
  feed(value: NotificationFeedQueryV2, ownerId?: string): NotificationFeedV2 {
    let query: NotificationFeedQueryV2;
    try {
      query = parseNotificationFeedQueryV2(value);
    } catch {
      throw new NotificationRecoveryError(400, '无效通知分页请求');
    }
    this.owner(query.deviceId, ownerId);
    const state = this.hooks.read(),
      slot = state.slots.find((s) => s.deviceId === query.deviceId),
      device = this.hooks.device(query.deviceId);
    const common = {
      version: 2 as const,
      epoch: state.epoch,
      deviceId: query.deviceId,
      target: this.hooks.target,
      generation: query.generation,
    };
    const reset = (reason: string): NotificationFeedV2 => ({
      ...common,
      kind: 'reset-required',
      reason,
      floor: state.floor,
      head: state.head,
    });
    if (query.epoch !== state.epoch) return reset('epoch-mismatch');
    if (
      !slot ||
      slot.applied !== query.generation ||
      slot.highest !== slot.applied ||
      slot.revoked >= query.generation ||
      !device
    )
      return reset('subscription-mismatch');
    const snapshot = query.resetId
      ? state.snapshots.find(
          (s) =>
            s.id === query.resetId &&
            s.deviceId === query.deviceId &&
            s.generation === query.generation,
        )
      : undefined;
    if (query.resetId && (!snapshot || snapshot.expiresAt <= this.now()))
      return reset('snapshot-expired-or-missing');
    if (!snapshot && (query.after < state.floor - 1 || query.after > state.head))
      return reset('retention-gap');
    const through = query.through ?? (snapshot ? snapshot.events.length : state.head);
    if (
      through > (snapshot ? snapshot.events.length : state.head) ||
      query.after > through ||
      (snapshot && through !== snapshot.events.length)
    )
      return reset('invalid-page-range');
    const activeIds = this.hooks.activeIds(query.deviceId);
    if (activeIds.length > limits.activeIds)
      return { ...common, kind: 'capacity', reason: 'active-ids' };
    const all = snapshot
      ? snapshot.events.map((event, index) => ({
          sequence: index + 1,
          event,
          fingerprint: recoveryHash(event),
        }))
      : state.log;
    const candidates = all.filter((e) => e.sequence > query.after && e.sequence <= through);
    const page: Extract<NotificationFeedV2, { kind: 'page' }> = {
      ...common,
      kind: 'page',
      mode: snapshot ? 'reset' : 'live',
      ...(snapshot ? { resetId: snapshot.id, snapshotHash: snapshot.hash } : {}),
      baseline: snapshot?.baseline ?? through,
      after: query.after,
      next: query.after,
      through,
      hasMore: candidates.length > 0,
      items: [],
      activeIds,
      publicKey: device.registration.publicKey,
      preferencesFingerprint: recoveryHash(device.registration.preferences),
    };
    for (const original of candidates) {
      if (page.items.length >= limits.pageEvents) break;
      if (original.sequence !== page.next + 1) return reset('non-contiguous-source');
      const item = {
        ...structuredClone(original),
        disposition: this.hooks.disposition(query.deviceId, original.event),
      };
      page.items.push(item);
      const previous = page.next;
      page.next = original.sequence;
      page.hasMore = page.next < through;
      if (Buffer.byteLength(notificationV2Canonical(page)) > limits.pageBytes) {
        page.items.pop();
        page.next = previous;
        page.hasMore = true;
        break;
      }
    }
    if (
      (candidates.length && !page.items.length) ||
      Buffer.byteLength(notificationV2Canonical(page)) > limits.pageBytes
    )
      return { ...common, kind: 'capacity', reason: 'page-bytes' };
    page.hasMore = page.next < through;
    return page;
  }
}
