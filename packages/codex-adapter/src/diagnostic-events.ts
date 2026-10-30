import { channel } from 'node:diagnostics_channel';
import {
  isMainThread,
  postMessageToThread,
  workerData,
  threadId,
  type Worker,
} from 'node:worker_threads';

export const runtimeDiagnosticChannel = channel('agent-hub.runtime-diagnostic.v1');
const routes = new Set([
  '/healthz',
  '/v1/approvals',
  '/v1/attachments',
  '/v1/attachments/resolve',
  '/v1/attachments/resolve-link',
  '/v1/connection-routes',
  '/v1/desktop/composer-suggestion',
  '/v1/desktop/creation-attachments',
  '/v1/desktop/creation-receipts',
  '/v1/desktop/creation-status',
  '/v1/desktop/creations',
  '/v1/desktop/creations/detach',
  '/v1/desktop/creations/submit',
  '/v1/desktop/open',
  '/v1/desktop/open-status',
  '/v1/desktop/suggestion-observations',
  '/v1/discovery/peers',
  '/v1/events',
  '/v1/file-transfers',
  '/v1/file-transfers/content',
  '/v1/history',
  '/v1/models',
  '/v1/notifications/',
  '/v1/notifications/events',
  '/v1/notifications/preferences',
  '/v1/notifications/register',
  '/v1/notifications/status',
  '/v1/notifications/unregister',
  '/v1/projects',
  '/v1/queue/cancel',
  '/v1/queue/execute',
  '/v1/receipts',
  '/v1/replies',
  '/v1/replies/status',
  '/v1/stop',
  '/v1/threads',
  '/v1/threads/actions',
  '/v1/threads/fork',
  '/v1/threads/fork-options',
  '/v1/threads/next-turn-suggestion',
  '/v1/threads/read',
  '/v1/threads/search-history',
  '/v1/voice/',
  '/v1/voice/readiness',
  '/v1/voice/transcriptions',
  '/v1/voice/transcriptions/audio',
  '/v1/workspace',
  '/v1/workspace-management',
]);
const labels = new Set([
  'scope',
  'stage',
  'code',
  'action',
  'initiator',
  'method',
  'runtime',
  'platform',
  'node',
  'nativeType',
  'waitKind',
]);
const ids = new Set([
  'threadId',
  'messageId',
  'resourceId',
  'requestId',
  'operationId',
  'generation',
  'hostId',
  'bootId',
  'fingerprint',
]);
const numbers = new Set([
  'atUnixMs',
  'monotonicMs',
  'ownerId',
  'elapsedMs',
  'remainingMs',
  'queueMs',
  'waitMs',
  'state',
  'request',
  'pid',
  'workerId',
  'exitCode',
  'attempt',
  'delayMs',
  'status',
  'pending',
  'dropped',
  'loopMaxMs',
  'loopMeanMs',
  'historyPending',
  'historyStalled',
]);
const booleans = new Set(['ready', 'bridge', 'reader', 'closed', 'gone', 'commandCommitPending']);
const metadataRpcMethods = new Set([
  'initialize',
  'project/list',
  'thread/list',
  'thread/read',
  'thread/items/list',
  'thread/turns/list',
  'model/list',
  'config/read',
  'account/read',
  'account/rateLimits/read',
]);
/** Copy only schema fields; never stringify an error, path, header, body or stderr. */
export function safeDiagnostic(input: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(input)) {
    if (labels.has(key) && typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,80}$/.test(value))
      out[key] = value;
    else if (
      ids.has(key) &&
      typeof value === 'string' &&
      /^(?:[a-f0-9-]{16,64}|\d{1,12})$/i.test(value)
    )
      out[key] = value;
    else if (numbers.has(key) && typeof value === 'number' && Number.isFinite(value))
      out[key] = Math.round(value * 100) / 100;
    else if (booleans.has(key) && typeof value === 'boolean') out[key] = value;
    else if (
      key === 'nativeCode' &&
      typeof value === 'number' &&
      Number.isInteger(value) &&
      value >= -0x80000000 &&
      value <= 0x7fffffff
    )
      out[key] = value;
    else if (key === 'rpcMethod' && typeof value === 'string' && metadataRpcMethods.has(value))
      out[key] = value;
    else if (
      key === 'rpcClassification' &&
      typeof value === 'string' &&
      ['metadata-read-rejected', 'metadata-read-timeout'].includes(value)
    )
      out[key] = value;
    else if (key === 'route' && typeof value === 'string') {
      // Current API uses fixed pathnames. IDs, encoded input and unknown routes are redacted.
      out.route = routes.has(value) ? value : '/other';
    }
  }
  return out;
}
export function diagnosticEvent(input: Record<string, unknown>): void {
  if (workerData?.kind === 'runtime-diagnostic-writer') return;
  try {
    const event = safeDiagnostic({
      atUnixMs: Date.now(),
      monotonicMs: performance.now(),
      ...(!isMainThread ? { workerId: threadId } : {}),
      ...input,
    });
    if (isMainThread) runtimeDiagnosticChannel.publish(event);
    else
      void postMessageToThread(0, { kind: 'runtime-diagnostic', event }, [], 1000).catch(() => {});
  } catch {
    /* Diagnostics never alter an operation. */
  }
}
// Multiple real ESM copies may coexist during deployment verification. Keep one
// process dispatcher rather than adding a listener for every loaded copy.
const dispatchKey = Symbol.for('agent-hub.runtime-diagnostic-dispatch.v1');
type Dispatcher = { silentWorkers: WeakSet<Worker>; silentIds: Set<number>; installed: boolean };
const globals = globalThis as typeof globalThis & { [dispatchKey]?: Dispatcher };
const dispatcher = (globals[dispatchKey] ??= {
  silentWorkers: new WeakSet(),
  silentIds: new Set(),
  installed: false,
});
if (isMainThread && !dispatcher.installed) {
  dispatcher.installed = true;
  process.on('workerMessage', (message: unknown, source: number) => {
    const value = message as { kind?: unknown; event?: unknown };
    if (
      source > 0 &&
      !dispatcher.silentIds.has(source) &&
      value?.kind === 'runtime-diagnostic' &&
      value.event &&
      typeof value.event === 'object'
    )
      diagnosticEvent(value.event as Record<string, unknown>);
  });
}
export function silenceDiagnosticWorker(worker: Worker): void {
  dispatcher.silentWorkers.add(worker);
  const id = worker.threadId;
  dispatcher.silentIds.add(id);
  worker.once('exit', () => dispatcher.silentIds.delete(id));
}
export function observeDiagnosticWorker(worker: Worker, owner?: Worker): void {
  if (owner && dispatcher.silentWorkers.has(owner)) silenceDiagnosticWorker(worker);
}
