import type { RuntimeTarget } from '@codex-manage/protocol';

function privateConnectionObject(input: unknown, keys: readonly string[]): Record<string, unknown> {
  if (
    !input ||
    typeof input !== 'object' ||
    Array.isArray(input) ||
    Object.keys(input).length !== keys.length ||
    Object.keys(input).some((key) => !keys.includes(key))
  )
    throw Error('Invalid private connection administration input');
  return input as Record<string, unknown>;
}

/** Host administration uses machine identity only; historical account metadata remains stored. */
export type CodexPrivateMachineTarget = Pick<
  RuntimeTarget,
  'agentId' | 'hostId' | 'workspaceId' | 'runtimeId'
>;
export interface CodexPrivateConnectionStatus {
  target: CodexPrivateMachineTarget;
  publicKeySha256: string;
  revoked: boolean;
  hostKeyAuthorized: boolean;
}
export interface CodexPrivateConnectionSelector {
  target: CodexPrivateMachineTarget;
  publicKeySha256: string;
}
export interface CodexPrivateConnectionRevoke extends CodexPrivateConnectionSelector {
  clientOperationId: string;
}
export interface CodexPrivateConnectionOperation {
  target: CodexPrivateMachineTarget;
  clientOperationId: string;
}
export interface CodexPrivateConnectionRevokeReceipt {
  clientOperationId: string;
  inputSha256: string;
  state: 'confirmed';
  binding: CodexPrivateConnectionStatus;
}
export interface CodexPrivateConnectionUnknownReceipt {
  clientOperationId: string;
  inputSha256: string;
  state: 'unknown';
}
export function codexPrivateMachineTarget(target: RuntimeTarget): CodexPrivateMachineTarget {
  return {
    agentId: target.agentId,
    hostId: target.hostId,
    workspaceId: target.workspaceId,
    runtimeId: target.runtimeId,
  };
}
export function parseCodexPrivateMachineTarget(input: unknown): CodexPrivateMachineTarget {
  const row = privateConnectionObject(input, ['agentId', 'hostId', 'workspaceId', 'runtimeId']);
  if (
    row.agentId !== 'codex' ||
    !['hostId', 'workspaceId', 'runtimeId'].every(
      (key) =>
        typeof row[key] === 'string' &&
        row[key].length > 0 &&
        row[key].length <= 256 &&
        Array.from(row[key]).every(
          (character) => character.charCodeAt(0) >= 32 && character.charCodeAt(0) !== 127,
        ),
    )
  )
    throw Error('Invalid private connection machine');
  return row as unknown as CodexPrivateMachineTarget;
}
function privateOperationId(input: unknown): string {
  if (
    typeof input !== 'string' ||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(input)
  )
    throw Error('Invalid private connection operation');
  return input;
}
export function parseCodexPrivateConnectionSelector(
  input: unknown,
): CodexPrivateConnectionSelector {
  const row = privateConnectionObject(input, ['target', 'publicKeySha256']);
  if (typeof row.publicKeySha256 !== 'string' || !/^[a-f0-9]{64}$/.test(row.publicKeySha256))
    throw Error('Invalid private connection key digest');
  return {
    target: parseCodexPrivateMachineTarget(row.target),
    publicKeySha256: row.publicKeySha256,
  };
}
export function parseCodexPrivateConnectionRevoke(input: unknown): CodexPrivateConnectionRevoke {
  const row = privateConnectionObject(input, ['target', 'publicKeySha256', 'clientOperationId']);
  const selector = parseCodexPrivateConnectionSelector({
    target: row.target,
    publicKeySha256: row.publicKeySha256,
  });
  return {
    ...selector,
    clientOperationId: privateOperationId(row.clientOperationId),
  };
}
export function parseCodexPrivateConnectionOperation(
  input: unknown,
): CodexPrivateConnectionOperation {
  const row = privateConnectionObject(input, ['target', 'clientOperationId']);
  return {
    target: parseCodexPrivateMachineTarget(row.target),
    clientOperationId: privateOperationId(row.clientOperationId),
  };
}
/** Stable field order, independent of request JSON property order. No credential is part of this intent. */
export function codexPrivateConnectionRevokeIntent(input: CodexPrivateConnectionRevoke): string {
  return JSON.stringify({
    kind: 'codex-private-connection-revoke-v1',
    target: codexPrivateMachineTarget(input.target),
    publicKeySha256: input.publicKeySha256,
    clientOperationId: input.clientOperationId,
  });
}
