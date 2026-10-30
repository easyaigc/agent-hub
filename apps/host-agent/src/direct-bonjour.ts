import { createHash, randomUUID } from 'node:crypto';
import { Worker } from 'node:worker_threads';

export type DirectBonjourState = {
  version: 1;
  hostId: string;
  hostPid: number;
  port: number;
  generation: number;
  phase:
    | 'starting'
    | 'probing'
    | 'announced-local'
    | 'retry-wait'
    | 'closing'
    | 'closed'
    | 'stop-unconfirmed';
  instanceName?: string;
  checkedAt: number;
  retryAt?: number | undefined;
  lastFailureCode?: string;
  localPublicationOnly: true;
};
type PublisherWorker = Pick<Worker, 'on' | 'postMessage' | 'terminate' | 'unref'>;
type PublicTarget = { name: string; hostId: string; port: number };
type WorkerInput = PublicTarget & { instanceName: string; dnsHost: string };

const failureCodes = new Set([
  'EACCES',
  'EADDRINUSE',
  'EADDRNOTAVAIL',
  'EMSGSIZE',
  'ENETUNREACH',
  'EHOSTUNREACH',
  'ENODEV',
  'EINVAL',
  'ECONNREFUSED',
  'ECONNRESET',
  'EPIPE',
  'ETIMEDOUT',
  'EBADF',
  'ENOTSOCK',
  'ERR_SOCKET_DGRAM_NOT_RUNNING',
  'MDNS_PUBLICATION_FAILED',
  'MDNS_ANNOUNCEMENT_TIMEOUT',
  'MDNS_SERVICE_INACTIVE',
]);
export const directBonjourFailureCode = (value: unknown): string =>
  typeof value === 'string' && failureCodes.has(value) ? value : 'MDNS_PUBLICATION_FAILED';

/** DNS names are discovery hints. The full Host ID and pinned HTTPS identity stay unchanged. */
export function directBonjourInstanceName(target: PublicTarget, nonce: string): string {
  if (
    !/^[a-f0-9]{8}$/.test(nonce) ||
    !target.hostId ||
    Buffer.byteLength(target.hostId) > 128 ||
    !Number.isInteger(target.port) ||
    target.port < 1 ||
    target.port > 65535
  )
    throw new Error('Invalid direct Bonjour target');
  // Reuse the existing display hint position; do not add another implementation
  // field to the native client's unpaired computer row.
  const suffix = `-${nonce}-${target.port}`;
  let prefix = '';
  for (const character of target.name.trim() || 'Codex') {
    const code = character.codePointAt(0)!;
    const label = character === '.' || code < 32 || code === 127 ? ' ' : character;
    if (Buffer.byteLength(prefix + label + suffix) > 63) break;
    prefix += label;
  }
  return prefix + suffix;
}

/** Owns only a DNS worker. A publication failure cannot retire HTTPS or the desktop runtime. */
export class DirectBonjourPublisher {
  private worker: PublisherWorker | undefined;
  private retiring = false;
  private closed = false;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private stopTimer: ReturnType<typeof setTimeout> | undefined;
  private closeWaiters: (() => void)[] = [];
  private state: DirectBonjourState;

  constructor(
    private readonly target: PublicTarget,
    private readonly report: (state: DirectBonjourState) => void = () => {},
    private readonly dependencies: {
      createWorker?: (input: WorkerInput) => PublisherWorker;
      retryDelayMs?: number;
      shutdownGraceMs?: number;
    } = {},
  ) {
    this.state = {
      version: 1,
      hostId: target.hostId,
      hostPid: process.pid,
      port: target.port,
      generation: 0,
      phase: 'starting',
      checkedAt: Date.now(),
      localPublicationOnly: true,
    };
  }

  snapshot(): DirectBonjourState {
    return structuredClone(this.state);
  }

  private update(change: Partial<DirectBonjourState>): void {
    this.state = { ...this.state, ...change, checkedAt: Date.now() };
    try {
      this.report(this.snapshot());
    } catch {
      /* A diagnostic sink cannot own recovery. */
    }
  }

  start(): void {
    if (this.closed || this.worker || this.retryTimer) return;
    const instanceName = directBonjourInstanceName(this.target, randomUUID().slice(0, 8));
    const host = /^[a-f0-9-]{36}$/i.test(this.target.hostId)
      ? this.target.hostId
      : createHash('sha256').update(this.target.hostId).digest('hex').slice(0, 32);
    const input = { ...this.target, instanceName, dnsHost: `codex-${host}.local` };
    this.retiring = false;
    this.update({
      generation: this.state.generation + 1,
      phase: 'starting',
      instanceName,
      retryAt: undefined,
    });
    let worker: PublisherWorker;
    try {
      worker =
        this.dependencies.createWorker?.(input) ??
        new Worker(new URL('./direct-bonjour-worker.js', import.meta.url), { workerData: input });
    } catch {
      this.update({ lastFailureCode: 'MDNS_WORKER_START_FAILED' });
      this.retry();
      return;
    }
    this.worker = worker;
    worker.on('message', (message: unknown) => {
      if (
        this.worker !== worker ||
        this.retiring ||
        this.closed ||
        !message ||
        typeof message !== 'object'
      )
        return;
      const value = message as Record<string, unknown>;
      if (value.instanceName !== instanceName) return;
      if (value.phase === 'probing' || value.phase === 'announced-local') {
        this.update({ phase: value.phase });
      } else if (value.phase === 'failed') {
        const code = directBonjourFailureCode(value.code);
        this.update({ lastFailureCode: code });
        this.retire(worker);
      }
    });
    worker.on('error', () => {
      if (this.worker !== worker) return;
      this.update({ lastFailureCode: 'MDNS_WORKER_FAILED' });
      this.retire(worker);
    });
    worker.on('exit', () => {
      if (this.worker !== worker) return;
      clearTimeout(this.stopTimer);
      this.worker = undefined;
      this.retiring = false;
      if (this.closed) {
        this.update({ phase: 'closed', retryAt: undefined });
        for (const resolve of this.closeWaiters.splice(0)) resolve();
      } else {
        this.retry();
      }
    });
    worker.unref();
  }

  private retry(): void {
    if (this.closed || this.worker || this.retryTimer) return;
    const delay =
      this.dependencies.retryDelayMs ??
      Math.min(60_000, 10_000 * 2 ** Math.min(this.state.generation - 1, 3));
    this.update({ phase: 'retry-wait', retryAt: Date.now() + delay });
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      this.start();
    }, delay);
    this.retryTimer.unref();
  }

  private retire(worker: PublisherWorker): void {
    if (this.worker !== worker || this.retiring) return;
    this.retiring = true;
    this.update({ phase: 'closing', retryAt: undefined });
    try {
      worker.postMessage({ kind: 'close' });
    } catch {
      /* Exit remains the retirement barrier. */
    }
    if (this.worker !== worker) return;
    this.stopTimer = setTimeout(() => {
      void worker.terminate().catch(() => {
        if (this.worker === worker)
          this.update({
            phase: 'stop-unconfirmed',
            lastFailureCode: 'MDNS_WORKER_STOP_UNCONFIRMED',
          });
      });
    }, this.dependencies.shutdownGraceMs ?? 2000);
    this.stopTimer.unref();
  }

  close(): Promise<void> {
    this.closed = true;
    clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    if (!this.worker) {
      this.update({ phase: 'closed', retryAt: undefined });
      return Promise.resolve();
    }
    const waiting = new Promise<void>((resolve) => this.closeWaiters.push(resolve));
    this.retire(this.worker);
    return waiting;
  }
}
