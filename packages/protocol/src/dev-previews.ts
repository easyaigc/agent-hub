import { parseUsageTarget, type UsageTarget } from './usage.js';

export const DEV_PREVIEW_CAPABILITIES = [
  'preview:http:v1',
  'preview:websocket:v1',
  'preview:streaming:v1',
] as const;
export interface DevPreviewIntent {
  id: string;
  target: UsageTarget;
  threadId: string;
  url: string;
  browserPort: number;
  browserSecret: string;
}
export interface DevPreviewReceipt {
  id: string;
  target: UsageTarget;
  threadId: string;
  expiresAt: string;
}
export function parseDevPreviewIntent(value: unknown): DevPreviewIntent {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid preview');
  const v = value as DevPreviewIntent;
  const keys = ['id', 'target', 'threadId', 'url', 'browserPort', 'browserSecret'];
  if (
    Object.keys(v).length !== keys.length ||
    Object.keys(v).some((k) => !keys.includes(k)) ||
    typeof v.id !== 'string' ||
    !/^[a-f0-9-]{36}$/i.test(v.id) ||
    typeof v.threadId !== 'string' ||
    !/^[\w.-]{1,256}$/.test(v.threadId) ||
    typeof v.url !== 'string' ||
    v.url.length > 8192 ||
    !Number.isInteger(v.browserPort) ||
    v.browserPort < 1024 ||
    v.browserPort > 65535 ||
    typeof v.browserSecret !== 'string' ||
    !/^[a-f0-9]{64}$/.test(v.browserSecret)
  )
    throw new Error('Invalid preview');
  parseUsageTarget(v.target);
  parseDevServiceURL(v.url);
  return v;
}
/** IPv4/IPv6 literals and exact localhost only; no DNS or arbitrary network destinations. */
export function parseDevServiceURL(value: string): {
  hostname: string;
  port: string;
  host: string;
  origin: string;
} {
  const m =
    /^http:\/\/(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]|\[::\]):([1-9][0-9]{3,4})(?:[/?#]|$)/.exec(
      value,
    );
  if (!m || +m[2]! < 1024 || +m[2]! > 65535 || /[\s\\]/.test(value))
    throw new Error('Only explicit local HTTP DEV ports are supported');
  return {
    hostname: m[1]!,
    port: m[2]!,
    host: `${m[1]}:${m[2]}`,
    origin: `http://${m[1]}:${m[2]}`,
  };
}
