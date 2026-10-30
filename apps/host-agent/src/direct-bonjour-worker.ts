import { parentPort, workerData } from 'node:worker_threads';
import { Bonjour } from 'bonjour-service';
import { directBonjourFailureCode } from './direct-bonjour.js';

// Only public discovery metadata enters this worker. No token, desktop RPC or HTTPS listener.
const input = workerData as { instanceName: string; dnsHost: string; hostId: string; port: number };
if (
  !parentPort ||
  !input ||
  typeof input.instanceName !== 'string' ||
  Buffer.byteLength(input.instanceName) > 63 ||
  typeof input.hostId !== 'string' ||
  !Number.isInteger(input.port)
)
  throw new Error('Invalid direct Bonjour worker input');
let closed = false;
let announced = false;
const publishState = (phase: 'probing' | 'announced-local' | 'failed', code?: string) =>
  parentPort?.postMessage({ phase, instanceName: input.instanceName, ...(code ? { code } : {}) });
const close = () => {
  if (closed) return;
  closed = true;
  clearInterval(timer);
  if (bonjour) bonjour.unpublishAll(() => bonjour?.destroy(() => parentPort?.close()));
  else parentPort?.close();
};
parentPort.on('message', (message: unknown) => {
  if (message && typeof message === 'object' && (message as { kind?: unknown }).kind === 'close')
    close();
});
const bonjour = new Bonjour(undefined, (error: { code?: unknown }) => {
  if (closed) return;
  const code = directBonjourFailureCode(error?.code);
  publishState('failed', code);
  close();
});
const service = bonjour.publish({
  name: input.instanceName,
  host: input.dnsHost,
  type: 'codex-host',
  port: input.port,
  probe: true,
  txt: { hostId: input.hostId, version: '1', port: String(input.port) },
});
const startedAt = performance.now();
service.on('up', () => {
  if (closed) return;
  announced = true;
  publishState('announced-local');
});
publishState('probing');
const timer = setInterval(() => {
  if (!service.activated || (!announced && performance.now() - startedAt > 10_000)) {
    publishState(
      'failed',
      service.activated ? 'MDNS_ANNOUNCEMENT_TIMEOUT' : 'MDNS_SERVICE_INACTIVE',
    );
    close();
  }
}, 1000);
