import type { RuntimeTarget } from './agent-management.js';

/** Existing Serve routes use system TLS trust, independently of the private certificate. */
export interface CodexConnectionRoutes {
  version: 1;
  target: RuntimeTarget;
  httpsEndpoints: string[];
}

export function codexSystemEndpoint(value: string): string {
  if (value.length > 512) throw Error('Invalid HTTPS route');
  const match =
    /^https:\/\/((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+ts\.net)(?::([0-9]{1,5}))?\/?$/i.exec(
      value,
    );
  if (!match) throw Error('Expected a Tailscale Serve HTTPS root');
  const port = match[2] === undefined ? 443 : Number(match[2]);
  if (port < 1 || port > 65535) throw Error('Invalid HTTPS port');
  return 'https://' + match[1]!.toLowerCase() + (port === 443 ? '' : ':' + port);
}

export function parseCodexSystemEndpoints(serialized: string | undefined): string[] {
  if (!serialized) return [];
  const value: unknown = JSON.parse(serialized);
  if (!Array.isArray(value) || value.length > 4 || value.some((v) => typeof v !== 'string'))
    throw Error('Expected at most four HTTPS endpoints');
  return [...new Set(value.map((v: string) => codexSystemEndpoint(v)))];
}
