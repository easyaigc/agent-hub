import { createHash, randomUUID } from 'node:crypto';
import {
  normalizeUsageDecimal,
  parseUsageResponse,
  unavailableUsage,
  type UsageTarget,
  type UsageResponse,
} from '@codex-manage/protocol';
export interface EasyWorkUsageAccess {
  platformBaseUrl: string;
  accessToken: string;
  userId?: string;
  tenantId?: string;
}
export interface EasyWorkUsageAuth {
  ensurePlatformAccessContext(): Promise<EasyWorkUsageAccess>;
}
const object = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
/** JSON numeric lexemes are quoted before parsing, so balances cannot silently lose precision. */
export function parseExactUsageJSON(input: string): unknown {
  if (Buffer.byteLength(input) > 262144) throw new Error('Invalid usage data');
  // Validate grammar first; this result is deliberately discarded (numbers may be imprecise).
  JSON.parse(input);
  let output = '',
    quoted = false,
    escaped = false;
  for (let i = 0; i < input.length;) {
    const c = input[i]!;
    if (quoted) {
      output += c;
      i++;
      if (escaped) escaped = false;
      else if (c === '\\') escaped = true;
      else if (c === '"') quoted = false;
    } else if (c === '"') {
      quoted = true;
      output += c;
      i++;
    } else if (c === '-' || /[0-9]/.test(c)) {
      const match = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(input.slice(i));
      if (!match) throw new Error('Invalid usage data');
      output += JSON.stringify(match[0]);
      i += match[0].length;
    } else {
      output += c;
      i++;
    }
  }
  return JSON.parse(output);
}
function scope(access: EasyWorkUsageAccess): string {
  if (!access.userId) throw new Error('Usage identity unavailable');
  return createHash('sha256')
    .update(
      JSON.stringify([
        new URL(access.platformBaseUrl).origin,
        access.userId,
        access.tenantId ?? null,
      ]),
    )
    .digest('hex');
}
export function mapEasyWorkUsage(
  value: unknown,
  target: UsageTarget,
  requestId: string,
  scopeRef: string,
  now = new Date(),
): UsageResponse {
  const data = object(value),
    balance = object(data.balance),
    membership = object(data.membership);
  const raw = balance.availableAmount ?? balance.amount;
  if (raw === undefined || raw === null) return unavailableUsage(target, requestId, 'not-returned');
  if (
    typeof raw !== 'string' ||
    (balance.currency !== undefined && typeof balance.currency !== 'string')
  )
    return unavailableUsage(target, requestId, 'invalid-data');
  // The registered account-summary API defines omitted currency as the resource wallet.
  const currency = typeof balance.currency === 'string' ? balance.currency : 'resource';
  const unit =
    currency === 'resource'
      ? { kind: 'compute', code: 'easyai-resource', label: '算力' }
      : /^[A-Z]{3}$/.test(currency)
        ? { kind: 'currency', code: currency, label: currency }
        : { kind: 'custom', code: currency, label: currency };
  const sampledAt = now.toISOString();
  return parseUsageResponse({
    schemaVersion: 1,
    target,
    requestId,
    status: 'ok',
    snapshot: {
      snapshotId: randomUUID(),
      sampledAt,
      source: {
        adapterId: 'easywork',
        scopeRef,
        scopeLabel: null,
        planLabel: typeof membership.name === 'string' ? membership.name : null,
      },
      coverage: 'complete',
      issues: [],
      buckets: [
        {
          id: 'wallet',
          label: null,
          sharedPoolRef: null,
          combination: { kind: 'provider-defined' },
          metrics: [
            {
              id: 'available',
              kind: 'balance',
              label: balance.availableAmount !== undefined ? '可用余额' : '余额',
              unit,
              reading: {
                state: 'amount',
                amount: normalizeUsageDecimal(raw),
                entitlement: 'unknown',
              },
              expiresAt: null,
              appliesTo: { kind: 'all' },
              observedAt: sampledAt,
              validUntil: new Date(+now + 60000).toISOString(),
            },
          ],
        },
      ],
    },
  });
}
/** Fixed read-only upstream operation. Credentials stay on the desktop and redirects are refused. */
export async function readEasyWorkUsage(
  auth: EasyWorkUsageAuth,
  target: UsageTarget,
  requestId: string,
  fetchImpl: typeof fetch = fetch,
): Promise<UsageResponse> {
  try {
    const access = await auth.ensurePlatformAccessContext(),
      before = scope(access);
    const base = new URL(
      access.platformBaseUrl.endsWith('/') ? access.platformBaseUrl : access.platformBaseUrl + '/',
    );
    if (
      base.protocol !== 'https:' &&
      !(base.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname))
    )
      return unavailableUsage(target, requestId, 'invalid-data');
    if (base.username || base.password || base.search || base.hash)
      return unavailableUsage(target, requestId, 'invalid-data');
    const response = await fetchImpl(new URL('workbench-platform/v1/account-summary', base), {
      headers: { authorization: `Bearer ${access.accessToken}` },
      redirect: 'error',
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok)
      return unavailableUsage(
        target,
        requestId,
        response.status === 401
          ? 'not-authenticated'
          : response.status === 403
            ? 'forbidden'
            : 'upstream-error',
      );
    const reader = response.body?.getReader();
    if (!reader) return unavailableUsage(target, requestId, 'invalid-data');
    const chunks: Uint8Array[] = [];
    let size = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        if (size > 262144) throw new Error('Invalid usage data');
        chunks.push(value);
      }
    } finally {
      await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
    const value = parseExactUsageJSON(Buffer.concat(chunks).toString('utf8'));
    if (before !== scope(await auth.ensurePlatformAccessContext()))
      return unavailableUsage(target, requestId, 'not-authenticated');
    return mapEasyWorkUsage(value, target, requestId, before);
  } catch (error) {
    const e = object(error);
    return unavailableUsage(
      target,
      requestId,
      e.name === 'TimeoutError'
        ? 'timeout'
        : e.code === 'EASYAI_AUTH_REQUIRED'
          ? 'not-authenticated'
          : e instanceof SyntaxError
            ? 'invalid-data'
            : 'upstream-error',
    );
  }
}
