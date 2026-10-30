import { Worker } from 'node:worker_threads';
import { monitorEventLoopDelay } from 'node:perf_hooks';
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import {
  diagnosticEvent,
  runtimeDiagnosticChannel,
  safeDiagnostic,
  observeDiagnosticWorker,
  silenceDiagnosticWorker,
} from './diagnostic-events.js';
import { registerWindowsProcessWorker } from './windows-process.js';
import {
  privateStateExecArgv,
  privateStateNodeOptions,
  retireWindowsNativeOwner,
} from './desktop-private-state-native.js';

const stderrRecent = new Map<string, number>();
/** Fixed labels only; never publish raw errors or stderr. */
export function runtimeFailure(scope: string, error: unknown, attempt?: number): void {
  const value = error as {
    code?: unknown;
    stage?: unknown;
    rpcMethod?: unknown;
    rpcClassification?: unknown;
    cause?: { code?: unknown };
  };
  const row = safeDiagnostic({
    scope,
    stage: value?.stage,
    code: value?.code ?? value?.cause?.code,
    nativeCode: value?.code ?? value?.cause?.code,
    rpcMethod: value?.rpcMethod,
    rpcClassification: value?.rpcClassification,
    attempt,
  });
  diagnosticEvent(row);
  const key = JSON.stringify(row);
  const now = Date.now();
  if (now - (stderrRecent.get(key) ?? 0) < 30_000) return;
  if (stderrRecent.size >= 64) stderrRecent.delete(stderrRecent.keys().next().value!);
  stderrRecent.set(key, now);
  console.error(JSON.stringify({ diagnostic: 'host-runtime-failure', ...row }));
}

export function appendRuntimeDiagnostic(
  _directory: string,
  context: {
    scope: string;
    hostId: string;
    stage?: string | undefined;
    threadId?: string | undefined;
    messageId?: string | undefined;
    resourceId?: string | undefined;
    path?: string | undefined;
    requestId?: string | undefined;
    operationId?: string | undefined;
    route?: string | undefined;
    method?: string | undefined;
    status?: number | undefined;
    elapsedMs?: number | undefined;
    attempt?: number | undefined;
    code?: string | undefined;
    ready?: boolean | undefined;
    bridge?: boolean | undefined;
    reader?: boolean | undefined;
    commandCommitPending?: boolean | undefined;
    historyPending?: number | undefined;
    historyStalled?: number | undefined;
  },
  error: unknown,
): void {
  const value = error as {
    code?: unknown;
    stage?: unknown;
    rpcMethod?: unknown;
    rpcClassification?: unknown;
    cause?: { code?: unknown };
  };
  diagnosticEvent({
    ...context,
    stage: context.stage ?? value?.stage,
    code: context.code ?? value?.code ?? value?.cause?.code,
    nativeCode: value?.code ?? value?.cause?.code,
    rpcMethod: value?.rpcMethod,
    rpcClassification: value?.rpcClassification,
  });
}

/** One bounded asynchronous sink. Disk/DACL waits execute outside the HTTP isolate. */
export function startRuntimeDiagnostics(
  directory: string,
  hostId: string,
  runtime = 'desktop-runtime',
) {
  const bootId = randomUUID();
  let fingerprint: string;
  try {
    const files = Object.fromEntries(
      readdirSync(new URL('.', import.meta.url))
        .filter((name) => name.endsWith('.js'))
        .sort()
        .map((name) => [
          name,
          createHash('sha256')
            .update(readFileSync(new URL('./' + name, import.meta.url)))
            .digest('hex'),
        ]),
    );
    fingerprint = createHash('sha256').update(JSON.stringify(files)).digest('hex');
  } catch {
    return {
      bootId,
      fingerprint: 'unavailable',
      status: () => ({ pending: 0, dropped: 1, failed: true }),
      recent: () => [] as Record<string, unknown>[],
      close: async () => {},
    };
  }
  let worker: Worker;
  try {
    worker = new Worker(new URL('./runtime-diagnostic-writer.js', import.meta.url), {
      execArgv: privateStateExecArgv(process.execArgv),
      env: {
        ...process.env,
        NODE_OPTIONS: privateStateNodeOptions(process.env.NODE_OPTIONS, true),
      },
      workerData: { kind: 'runtime-diagnostic-writer', directory },
    });
  } catch {
    return {
      bootId,
      fingerprint,
      status: () => ({ pending: 0, dropped: 1, failed: true }),
      recent: () => [] as Record<string, unknown>[],
      close: async () => {},
    };
  }
  silenceDiagnosticWorker(worker);
  registerWindowsProcessWorker(worker);
  observeDiagnosticWorker(worker);
  worker.unref();
  let pending = false,
    failed = false,
    stopped = false,
    dropped = 0,
    windowAt = Date.now(),
    count = 0,
    retryAt = 0;
  const queue: Record<string, unknown>[] = [];
  const recent: Record<string, unknown>[] = [];
  const flush = () => {
    if (pending || failed || !queue.length) return;
    pending = true;
    worker.postMessage(queue.splice(0, 64));
  };
  const receive = (input: unknown) => {
    if (stopped) return;
    const fields = input as Record<string, unknown>;
    if (typeof fields.hostId === 'string' && fields.hostId !== hostId) return;
    if (failed) {
      if (Date.now() < retryAt) {
        dropped++;
        return;
      }
      failed = false;
    }
    const row = {
      ...safeDiagnostic(input as Record<string, unknown>),
      hostId,
      bootId,
      fingerprint,
    };
    recent.push(row);
    if (recent.length > 64) recent.shift();
    if (Date.now() - windowAt >= 1000) {
      windowAt = Date.now();
      count = 0;
    }
    if (++count > 200 || queue.length >= 64) {
      dropped++;
      return;
    }
    queue.push(row);
    flush();
  };
  runtimeDiagnosticChannel.subscribe(receive);
  worker.on('message', (value: { kind?: string; ok?: boolean }) => {
    if (value?.kind !== 'diagnostic-ack') return;
    pending = false;
    if (!value.ok) {
      dropped++;
      failed = true;
      retryAt = Date.now() + 30_000;
      queue.length = 0;
    }
    flush();
  });
  worker.on('error', () => {
    retryAt = Infinity;
    failed = true;
    queue.length = 0;
  });
  worker.on('exit', () => {
    retryAt = Infinity;
    failed = true;
    queue.length = 0;
  });
  const delay = monitorEventLoopDelay({ resolution: 20 });
  delay.enable();
  const timer = setInterval(() => {
    diagnosticEvent({
      scope: 'host-pressure',
      loopMaxMs: delay.max / 1e6,
      loopMeanMs: delay.mean / 1e6,
      pending: queue.length,
      dropped,
    });
    delay.reset();
  }, 10_000);
  timer.unref();
  diagnosticEvent({
    scope: 'host-start',
    runtime,
    node: process.versions.node,
    platform: process.platform,
    pid: process.pid,
  });
  let closeWork: Promise<void> | undefined;
  return {
    bootId,
    fingerprint,
    status: () => ({ pending: queue.length, dropped, failed }),
    recent: () => recent.map((row) => ({ ...row })),
    close(): Promise<void> {
      if (closeWork) return closeWork;
      stopped = true;
      closeWork = (async () => {
        clearInterval(timer);
        delay.disable();
        runtimeDiagnosticChannel.unsubscribe(receive);
        // Bounded best-effort flush; retain unconfirmed native owners for existing supervisor policy.
        for (let n = 0; n < 20 && !failed && (pending || queue.length); n++)
          await new Promise((resolve) => setTimeout(resolve, 25));
        if (await retireWindowsNativeOwner(worker)) await worker.terminate();
      })();
      return closeWork;
    },
  };
}
