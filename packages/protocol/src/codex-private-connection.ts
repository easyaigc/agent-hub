import type { RuntimeTarget } from './agent-management.js';
import { notificationObject, parseNotificationTarget } from './notifications.js';

export interface CodexPrivateConnectionClaim {
  version: 1;
  target: RuntimeTarget;
  publicKey: string;
}
export interface CodexPrivateConnectionReply extends CodexPrivateConnectionClaim {
  ephemeralPublicKey: string;
  nonce: string;
  ciphertext: string;
}
export function parseCodexPrivateConnectionClaim(value: unknown): CodexPrivateConnectionClaim {
  const row = notificationObject(value, ['version', 'target', 'publicKey']);
  const target = parseNotificationTarget(row.target);
  if (
    row.version !== 1 ||
    target.agentId !== 'codex' ||
    typeof row.publicKey !== 'string' ||
    !/^[A-Za-z0-9_-]{43}$/.test(row.publicKey)
  )
    throw Error('Invalid private connection claim');
  return { version: 1, target, publicKey: row.publicKey };
}
/** Length-prefixed UTF-16 fields, encoded as UTF-8 for HKDF and AEAD AAD. */
export function codexPrivateConnectionContext(target: RuntimeTarget, publicKey: string): string {
  return [
    'agent-hub-codex-private-connection-v1',
    target.agentId,
    target.hostId,
    target.workspaceId,
    target.runtimeId,
    target.account?.authority ?? '',
    target.account?.subject ?? '',
    publicKey,
  ]
    .map((field) => String(field.length) + ':' + field)
    .join('');
}
