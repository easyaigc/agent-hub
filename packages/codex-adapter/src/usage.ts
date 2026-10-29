import { createHash, randomUUID } from 'node:crypto';
import {
  normalizeUsageDecimal,
  parseUsageMetric,
  parseUsageResponse,
  unavailableUsage,
  type UsageMetric,
  type UsageResponse,
  type UsageTarget,
  type UsageIssue,
  type UsageBucket,
} from '@codex-manage/protocol';

type Row = Record<string, unknown>;
const row = (v: unknown): Row =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Row) : {};
const unavailable = (reason: 'not-returned' | 'invalid-data') => ({
  state: 'unavailable' as const,
  reason,
});
/** No raw account identifiers, authentication tokens or provider wire escape this adapter. */
export function codexUsageScope(value: unknown): string | null {
  const account = row(row(value).account);
  if (!Object.keys(account).length) return null;
  const identity = [account.type, account.id ?? null, account.email ?? null];
  return createHash('sha256').update(JSON.stringify(identity)).digest('hex');
}
export function mapCodexUsage(
  value: unknown,
  target: UsageTarget,
  requestId: string,
  scopeRef: string,
  now = new Date(),
): UsageResponse {
  const wire = row(value),
    sampledAt = now.toISOString(),
    validUntil = new Date(+now + 60_000).toISOString();
  const issues: UsageIssue[] = [],
    buckets: UsageBucket[] = [];
  const mapped = wire.rateLimitsByLimitId;
  const entries =
    mapped !== undefined && mapped !== null
      ? Object.entries(row(mapped))
      : ([['codex', wire.rateLimits]] as [string, unknown][]);
  let plan: string | null = null;
  for (const [index, [key, raw]] of entries.entries()) {
    if (index >= 32) throw new Error('Invalid Codex usage');
    const bucket = row(raw),
      metrics: UsageMetric[] = [],
      bucketId = `pool-${index}`;
    if (typeof bucket.planType === 'string') plan ??= bucket.planType;
    const base = (id: string, label: string) => ({
      id,
      label,
      appliesTo: { kind: 'all' as const },
      observedAt: sampledAt,
      validUntil,
    });
    for (const slot of ['primary', 'secondary'] as const) {
      if (bucket[slot] === null || bucket[slot] === undefined) continue;
      const w = row(bucket[slot]);
      const durationSeconds =
        typeof w.windowDurationMins === 'number' &&
        Number.isSafeInteger(w.windowDurationMins * 60) &&
        w.windowDurationMins > 0
          ? w.windowDurationMins * 60
          : null;
      const label =
        durationSeconds === 18000
          ? '5h'
          : durationSeconds === 604800
            ? '7d'
            : durationSeconds === null
              ? slot
              : `${w.windowDurationMins}m`;
      const reading =
        typeof w.usedPercent === 'number' && Number.isFinite(w.usedPercent) && w.usedPercent >= 0
          ? {
              state: 'measured' as const,
              usage: { kind: 'percentage' as const, usedPercent: w.usedPercent },
            }
          : unavailable('invalid-data');
      let resetsAt: string | null = null;
      if (
        typeof w.resetsAt === 'number' &&
        Number.isSafeInteger(w.resetsAt) &&
        w.resetsAt >= 0 &&
        w.resetsAt <= 253402300799
      )
        resetsAt = new Date(w.resetsAt * 1000).toISOString();
      metrics.push(
        parseUsageMetric({
          ...base(slot, label),
          kind: 'window-limit',
          window: { durationSeconds, resetsAt, resetPolicy: 'provider-defined' },
          reading,
        }),
      );
      if (reading.state === 'unavailable')
        issues.push({ bucketId, metricId: slot, reason: reading.reason });
    }
    const c = row(bucket.credits);
    // Preserve provider buckets; equal balances do not establish a shared pool.
    if (Object.keys(c).length) {
      let reading: Extract<UsageMetric, { kind: 'balance' }>['reading'];
      if (typeof c.balance === 'string') {
        try {
          reading = {
            state: 'amount',
            amount: normalizeUsageDecimal(c.balance),
            entitlement:
              c.unlimited === true ? 'unlimited' : c.unlimited === false ? 'finite' : 'unknown',
          };
        } catch {
          reading = unavailable('invalid-data');
        }
      } else
        reading =
          c.unlimited === true
            ? { state: 'unlimited' }
            : unavailable(
                c.balance === null || c.balance === undefined ? 'not-returned' : 'invalid-data',
              );
      metrics.push(
        parseUsageMetric({
          ...base('credits', 'Credits'),
          kind: 'balance',
          unit: { kind: 'credits', code: 'codex-credit', label: 'credits' },
          reading,
          expiresAt: null,
        }),
      );
      if (reading.state === 'unavailable')
        issues.push({ bucketId, metricId: 'credits', reason: reading.reason });
    }
    if (!Object.keys(bucket).length)
      issues.push({ bucketId, metricId: null, reason: 'invalid-data' });
    buckets.push({
      id: bucketId,
      label: typeof bucket.limitName === 'string' ? bucket.limitName : key,
      sharedPoolRef: null,
      combination: { kind: 'provider-defined' },
      metrics,
    });
  }
  if (!buckets.length || !buckets.some((b) => b.metrics.length))
    return unavailableUsage(target, requestId, 'not-returned');
  return parseUsageResponse({
    schemaVersion: 1,
    target,
    requestId,
    status: 'ok',
    snapshot: {
      snapshotId: randomUUID(),
      sampledAt,
      source: { adapterId: 'codex', scopeRef, scopeLabel: null, planLabel: plan },
      coverage: issues.length ? 'partial' : 'complete',
      buckets,
      issues,
    },
  });
}
export async function readCodexUsage(
  call: (method: string, params: Row) => Promise<unknown>,
  target: UsageTarget,
  requestId: string,
): Promise<UsageResponse> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const operation = async () => {
    const before = codexUsageScope(await call('account/read', { refreshToken: false }));
    if (!before) return unavailableUsage(target, requestId, 'not-authenticated');
    const data = await call('account/rateLimits/read', {});
    const after = codexUsageScope(await call('account/read', { refreshToken: false }));
    if (before !== after) return unavailableUsage(target, requestId, 'not-authenticated');
    return mapCodexUsage(data, target, requestId, before);
  };
  try {
    return await Promise.race([
      operation(),
      new Promise<UsageResponse>((resolve) => {
        timer = setTimeout(() => resolve(unavailableUsage(target, requestId, 'timeout')), 5000);
      }),
    ]);
  } catch (error) {
    const code = row(error).code;
    return unavailableUsage(
      target,
      requestId,
      code === -32601
        ? 'unsupported'
        : code === 'CODEX_READER_TIMEOUT'
          ? 'timeout'
          : 'upstream-error',
    );
  } finally {
    if (timer) clearTimeout(timer);
  }
}
