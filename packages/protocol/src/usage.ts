import type { RuntimeTarget } from './agent-management.js';

export const USAGE_VERSION = 1 as const;
export const USAGE_CAPABILITY = 'usage:read:v1' as const;
export const USAGE_MAX_BYTES = 256 * 1024;
export type UsageTarget = Readonly<
  Pick<RuntimeTarget, 'agentId' | 'hostId' | 'workspaceId' | 'runtimeId'>
>;
export type UsageReason =
  | 'not-returned'
  | 'unsupported'
  | 'not-authenticated'
  | 'forbidden'
  | 'timeout'
  | 'offline'
  | 'upstream-error'
  | 'invalid-data'
  | 'unsupported-version';
export type UsageUnit = {
  kind: 'credits' | 'compute' | 'tokens' | 'requests' | 'currency' | 'custom';
  code: string;
  label: string;
};
export type UsageAppliesTo =
  { kind: 'all' } | { kind: 'models' | 'features'; ids: readonly string[] };
export interface UsageMetricBase {
  id: string;
  label: string;
  appliesTo: UsageAppliesTo;
  observedAt: string;
  validUntil: string;
}
export type UsageMeasure =
  | { kind: 'percentage'; usedPercent: number }
  | { kind: 'quantity'; used: string; limit: string; unit: UsageUnit };
export type UsageLimitReading =
  | { state: 'measured'; usage: UsageMeasure }
  | { state: 'unlimited' }
  | { state: 'unavailable'; reason: UsageReason };
export type UsageBalanceReading =
  | { state: 'amount'; amount: string; entitlement: 'finite' | 'unlimited' | 'unknown' }
  | { state: 'unlimited' }
  | { state: 'unavailable'; reason: UsageReason };
export type UsageMetric = UsageMetricBase &
  (
    | { kind: 'balance'; unit: UsageUnit; reading: UsageBalanceReading; expiresAt: string | null }
    | {
        kind: 'window-limit';
        window: {
          durationSeconds: number | null;
          resetsAt: string | null;
          resetPolicy: 'fixed' | 'rolling' | 'provider-defined' | 'unknown';
        };
        reading: UsageLimitReading;
      }
    | { kind: 'capacity-limit'; reading: UsageLimitReading }
    | {
        kind: 'extension';
        namespace: string;
        extensionVersion: number;
        valueText: string | null;
        note: string | null;
      }
  );
export interface UsageIssue {
  bucketId: string | null;
  metricId: string | null;
  reason: UsageReason;
}
export interface UsageBucket {
  id: string;
  label: string | null;
  sharedPoolRef: string | null;
  combination:
    { kind: 'all-of' | 'any-of'; metricIds: readonly string[] } | { kind: 'provider-defined' };
  metrics: readonly UsageMetric[];
}
export interface UsageSnapshot {
  snapshotId: string;
  sampledAt: string;
  source: {
    adapterId: string;
    scopeRef: string | null;
    scopeLabel: string | null;
    planLabel: string | null;
  };
  coverage: 'complete' | 'partial';
  buckets: readonly UsageBucket[];
  issues: readonly UsageIssue[];
}
export type UsageResponse = { schemaVersion: 1; target: UsageTarget; requestId: string } & (
  { status: 'ok'; snapshot: UsageSnapshot } | { status: 'unavailable'; reason: UsageReason }
);
export interface UsageProvider {
  readonly descriptor: {
    id: typeof USAGE_CAPABILITY;
    version: 1;
    metricKinds: readonly UsageMetric['kind'][];
    updates: 'push' | 'poll';
  };
  read(input: {
    target: UsageTarget;
    requestId: string;
    signal: { readonly aborted: boolean };
  }): Promise<UsageResponse>;
}

export const usageReasons: readonly UsageReason[] = [
  'not-returned',
  'unsupported',
  'not-authenticated',
  'forbidden',
  'timeout',
  'offline',
  'upstream-error',
  'invalid-data',
  'unsupported-version',
];
const utf8Length = (value: string): number =>
  [...value].reduce(
    (n, c) =>
      n +
      (c.codePointAt(0)! <= 0x7f
        ? 1
        : c.codePointAt(0)! <= 0x7ff
          ? 2
          : c.codePointAt(0)! <= 0xffff
            ? 3
            : 4),
    0,
  );
function invalid(): never {
  throw new Error('Invalid usage contract');
}
function row(value: unknown, keys: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid();
  const result = value as Record<string, unknown>;
  if (Object.keys(result).length !== keys.length || keys.some((key) => !Object.hasOwn(result, key)))
    invalid();
  return result;
}
function text(value: unknown, max = 80): asserts value is string {
  if (
    typeof value !== 'string' ||
    !value.trim() ||
    [...value].length > max ||
    [...value].some((c) => c.codePointAt(0)! < 32 || c.codePointAt(0) === 127)
  )
    invalid();
}
function id(value: unknown): asserts value is string {
  text(value, 128);
  if (utf8Length(value) > 128) invalid();
}
function nullable(value: unknown, check: (value: unknown) => void): void {
  if (value !== null) check(value);
}
function choice(value: unknown, values: readonly string[]): void {
  if (typeof value !== 'string' || !values.includes(value)) invalid();
}
function list(value: unknown, max: number): asserts value is unknown[] {
  if (!Array.isArray(value) || value.length > max) invalid();
}
function instant(value: unknown): asserts value is string {
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?Z$/.test(value) ||
    !Number.isFinite(Date.parse(value)) ||
    new Date(value).toISOString().slice(0, 19) !== value.slice(0, 19)
  )
    invalid();
}
/** Source decimals can be normalized losslessly. Wire decimals must already be canonical. */
export function normalizeUsageDecimal(value: string): string {
  if (typeof value !== 'string' || value.length > 256) invalid();
  const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value);
  if (!match) invalid();
  const exponent = Number(match[4] ?? 0);
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 84) invalid();
  let digits = match[2]! + (match[3] ?? ''),
    point = match[2]!.length + exponent;
  if (point < 0) {
    digits = '0'.repeat(-point) + digits;
    point = 0;
  }
  if (point > digits.length) digits += '0'.repeat(point - digits.length);
  const whole = (digits.slice(0, point) || '0').replace(/^0+(?=\d)/, '');
  const fraction = digits.slice(point).replace(/0+$/, '');
  if (whole.length > 60 || fraction.length > 24) invalid();
  return (
    (match[1] && (whole !== '0' || fraction) ? '-' : '') + whole + (fraction ? '.' + fraction : '')
  );
}
function decimal(value: unknown, nonnegative = false): void {
  if (
    typeof value !== 'string' ||
    normalizeUsageDecimal(value) !== value ||
    (nonnegative && value.startsWith('-'))
  )
    invalid();
}
export function usageTarget(target: RuntimeTarget): UsageTarget {
  return parseUsageTarget({
    agentId: target.agentId,
    hostId: target.hostId,
    workspaceId: target.workspaceId,
    runtimeId: target.runtimeId,
  });
}
export function parseUsageTarget(value: unknown): UsageTarget {
  const result = row(value, ['agentId', 'hostId', 'workspaceId', 'runtimeId']);
  Object.values(result).forEach(id);
  return result as unknown as UsageTarget;
}
export function usageTargetKey(target: UsageTarget): string {
  return JSON.stringify([target.agentId, target.hostId, target.workspaceId, target.runtimeId]);
}
function unit(value: unknown): void {
  const v = row(value, ['kind', 'code', 'label']);
  choice(v.kind, ['credits', 'compute', 'tokens', 'requests', 'currency', 'custom']);
  id(v.code);
  text(v.label);
  if (utf8Length(v.code) > 64 || (v.kind === 'currency' && !/^[A-Z]{3}$/.test(v.code))) invalid();
}
function applies(value: unknown): void {
  const kind = (value as Record<string, unknown> | null)?.kind;
  if (kind === 'all') {
    row(value, ['kind']);
    return;
  }
  const v = row(value, ['kind', 'ids']);
  choice(v.kind, ['models', 'features']);
  list(v.ids, 64);
  if (!v.ids.length || new Set(v.ids).size !== v.ids.length) invalid();
  v.ids.forEach(id);
}
function reading(value: unknown, balance: boolean): boolean {
  const state = (value as Record<string, unknown> | null)?.state;
  if (state === 'unlimited') {
    row(value, ['state']);
    return true;
  }
  if (state === 'unavailable') {
    const v = row(value, ['state', 'reason']);
    choice(v.reason, usageReasons);
    return false;
  }
  if (balance) {
    const v = row(value, ['state', 'amount', 'entitlement']);
    if (state !== 'amount') invalid();
    decimal(v.amount);
    choice(v.entitlement, ['finite', 'unlimited', 'unknown']);
  } else {
    const v = row(value, ['state', 'usage']);
    if (state !== 'measured') invalid();
    const kind = (v.usage as Record<string, unknown> | null)?.kind;
    if (kind === 'percentage') {
      const u = row(v.usage, ['kind', 'usedPercent']);
      if (typeof u.usedPercent !== 'number' || !Number.isFinite(u.usedPercent) || u.usedPercent < 0)
        invalid();
    } else {
      const u = row(v.usage, ['kind', 'used', 'limit', 'unit']);
      if (kind !== 'quantity') invalid();
      decimal(u.used, true);
      decimal(u.limit, true);
      unit(u.unit);
    }
  }
  return true;
}
/** Validate a single known metric at its element boundary. */
export function parseUsageMetric(value: unknown): UsageMetric {
  const base = ['id', 'kind', 'label', 'appliesTo', 'observedAt', 'validUntil'];
  const kind = (value as Record<string, unknown> | null)?.kind;
  const extra =
    kind === 'balance'
      ? ['unit', 'reading', 'expiresAt']
      : kind === 'window-limit'
        ? ['window', 'reading']
        : kind === 'capacity-limit'
          ? ['reading']
          : kind === 'extension'
            ? ['namespace', 'extensionVersion', 'valueText', 'note']
            : invalid();
  const v = row(value, [...base, ...extra]);
  id(v.id);
  text(v.label);
  applies(v.appliesTo);
  instant(v.observedAt);
  instant(v.validUntil);
  if (Date.parse(v.validUntil) < Date.parse(v.observedAt)) invalid();
  if (kind === 'balance') {
    unit(v.unit);
    reading(v.reading, true);
    nullable(v.expiresAt, instant);
  } else if (kind === 'extension') {
    id(v.namespace);
    if (!Number.isSafeInteger(v.extensionVersion) || (v.extensionVersion as number) < 1) invalid();
    nullable(v.valueText, (x) => text(x, 256));
    nullable(v.note, (x) => text(x, 256));
  } else {
    reading(v.reading, false);
    if (kind === 'window-limit') {
      const w = row(v.window, ['durationSeconds', 'resetsAt', 'resetPolicy']);
      if (
        w.durationSeconds !== null &&
        (!Number.isSafeInteger(w.durationSeconds) || (w.durationSeconds as number) < 1)
      )
        invalid();
      nullable(w.resetsAt, instant);
      choice(w.resetPolicy, ['fixed', 'rolling', 'provider-defined', 'unknown']);
    }
  }
  return value as UsageMetric;
}
/** Unknown v1 metrics may be skipped by a forward-compatible reader; known malformed data never passes. */
export function parseUsageResponse(value: unknown, tolerateUnknownMetrics = false): UsageResponse {
  if (utf8Length(JSON.stringify(value)) > USAGE_MAX_BYTES) invalid();
  const ok = (value as Record<string, unknown> | null)?.status === 'ok';
  const v = row(value, [
    'schemaVersion',
    'target',
    'requestId',
    'status',
    ok ? 'snapshot' : 'reason',
  ]);
  if (v.schemaVersion !== 1) invalid();
  parseUsageTarget(v.target);
  id(v.requestId);
  if (!ok) {
    if (v.status !== 'unavailable') invalid();
    choice(v.reason, usageReasons);
    return value as UsageResponse;
  }
  const s = row(v.snapshot, ['snapshotId', 'sampledAt', 'source', 'coverage', 'buckets', 'issues']);
  id(s.snapshotId);
  instant(s.sampledAt);
  choice(s.coverage, ['complete', 'partial']);
  const source = row(s.source, ['adapterId', 'scopeRef', 'scopeLabel', 'planLabel']);
  id(source.adapterId);
  nullable(source.scopeRef, id);
  nullable(source.scopeLabel, text);
  nullable(source.planLabel, text);
  list(s.buckets, 32);
  list(s.issues, 64);
  for (const issue of s.issues) {
    const i = row(issue, ['bucketId', 'metricId', 'reason']);
    nullable(i.bucketId, id);
    nullable(i.metricId, id);
    choice(i.reason, usageReasons);
  }
  const bucketIds = new Set<string>();
  let count = 0,
    unavailable = s.issues.length > 0;
  let sourceIncomplete = unavailable;
  const issues = [...s.issues] as UsageIssue[];
  const buckets = s.buckets.map((value) => {
    const b = row(value, ['id', 'label', 'sharedPoolRef', 'combination', 'metrics']);
    id(b.id);
    nullable(b.label, text);
    nullable(b.sharedPoolRef, id);
    if (bucketIds.has(b.id)) invalid();
    bucketIds.add(b.id);
    list(b.metrics, 64);
    count += b.metrics.length;
    if (count > 64) invalid();
    const metricIds = new Set<string>();
    const metrics = b.metrics.flatMap((value) => {
      const raw = value as Record<string, unknown> | null;
      id(raw?.id);
      text(raw?.kind);
      if (metricIds.has(raw.id)) invalid();
      metricIds.add(raw.id);
      if (
        !['balance', 'window-limit', 'capacity-limit', 'extension'].includes(raw.kind as string) &&
        tolerateUnknownMetrics
      ) {
        text(raw.label);
        applies(raw.appliesTo);
        instant(raw.observedAt);
        instant(raw.validUntil);
        if (Date.parse(raw.validUntil) < Date.parse(raw.observedAt)) invalid();
        issues.push({ bucketId: b.id as string, metricId: raw.id, reason: 'unsupported-version' });
        unavailable = true;
        return [];
      }
      const metric = parseUsageMetric(value);
      if ('reading' in metric && metric.reading.state === 'unavailable') {
        unavailable = true;
        sourceIncomplete = true;
      }
      return [metric];
    });
    const c = b.combination as Record<string, unknown> | null;
    if (c?.kind === 'provider-defined') row(c, ['kind']);
    else {
      const c = row(b.combination, ['kind', 'metricIds']);
      choice(c.kind, ['all-of', 'any-of']);
      list(c.metricIds, 64);
      if (
        c.metricIds.length < 2 ||
        new Set(c.metricIds).size !== c.metricIds.length ||
        c.metricIds.some((x) => !metricIds.has(x as string))
      )
        invalid();
    }
    // Unknown children cannot leave a locally incomplete combination masquerading as complete.
    const combination =
      metrics.length === b.metrics.length ? b.combination : { kind: 'provider-defined' };
    return { ...b, combination, metrics } as unknown as UsageBucket;
  });
  if (issues.length > 64 || (s.coverage === 'partial') !== sourceIncomplete) invalid();
  return {
    ...v,
    snapshot: { ...s, coverage: unavailable ? 'partial' : s.coverage, buckets, issues },
  } as unknown as UsageResponse;
}

export function unavailableUsage(
  target: UsageTarget,
  requestId: string,
  reason: UsageReason,
): UsageResponse {
  return parseUsageResponse({ schemaVersion: 1, target, requestId, status: 'unavailable', reason });
}
