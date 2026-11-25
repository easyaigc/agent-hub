import { parentPort, workerData } from 'node:worker_threads';
import {
  writeFileSync,
  renameSync,
  openSync,
  closeSync,
  unlinkSync,
  existsSync,
  type Dir,
} from 'node:fs';
import { opendir } from 'node:fs/promises';
import { machineRuntimeTargetKey, type RuntimeTarget } from '@codex-manage/protocol';
import { DesktopTraceRetention } from './desktop-trace-retention.js';
import { DesktopTraceStorageInventory } from './desktop-trace-storage-inventory.js';
import { DesktopNativeSuggestionChannel } from './desktop-native-suggestion-channel.js';
import { createHash, randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { createWindowsTraceBackend } from './desktop-trace-native.js';
import {
  closeWindowsPrivateStateBackend,
  inspectWindowsPipe,
  windowsTraceInstanceReady,
} from './desktop-platform.js';

const code = (error: unknown) =>
  error instanceof Error && /^[A-Z_]{1,80}$/.test(error.message)
    ? error.message
    : 'TRACE_RETENTION_REJECTED';

async function run(): Promise<void> {
  const scope = workerData as { traceRoot: string; target: RuntimeTarget; directory: string };
  if (!scope || typeof scope.traceRoot !== 'string' || !scope.target || !parentPort) return;
  const key = machineRuntimeTargetKey(scope.target);
  const statusPath = join(
    scope.directory,
    'trace-retention-' + createHash('sha256').update(key).digest('hex') + '.json',
  );
  let closed = false;
  const shutdown = new AbortController();
  const inventory = new DesktopTraceStorageInventory(scope.traceRoot);
  let native: Awaited<ReturnType<typeof createWindowsTraceBackend>> | undefined;
  let timer: NodeJS.Timeout | undefined;
  let catalog: Dir | undefined;
  let phase = 'initializing';
  let lastError: string | undefined;
  let lastSuccessfulTick = 0;
  let lastCompleteScan = 0;
  const totals = { retired: 0, releasedBytes: 0, retained: 0, failed: 0 };
  const queue: string[] = [];
  const queued = new Set<string>();
  const seen = new Map<string, { after: number; state: string; repeats: number }>();
  let current = new Set<string>();
  const recent: { bundle: string; state: string }[] = [];
  const publish = () => {
    const uniqueStates: Record<string, number> = {};
    for (const entry of seen.values())
      uniqueStates[entry.state] = (uniqueStates[entry.state] ?? 0) + 1;
    const storage = inventory.snapshot;
    const status = {
      version: 1,
      kind: 'retention',
      ...totals,
      phase,
      ...(lastError ? { lastError } : {}),
      pending: queue.length,
      recent,
      countersSince: 'process-start',
      checkedAt: Date.now(),
      lastSuccessfulTick,
      lastCompleteScan,
      uniqueStates,
      storage: {
        ...storage,
        accounting: 'logical-bytes-advisory',
        pressure:
          storage.bytes >= 1024 ** 3
            ? 'over-budget'
            : storage.bytes >= 512 * 1024 ** 2
              ? 'high'
              : storage.complete
                ? 'normal'
                : 'unknown',
        softBudgetBytes: 512 * 1024 ** 2,
        targetBudgetBytes: 1024 ** 3,
        activeWriterHardLimitEnforced: false,
      },
    };
    const temporary = statusPath + '.' + randomUUID() + '.tmp';
    let created = false;
    try {
      const file = openSync(temporary, 'wx', 0o600);
      created = true;
      try {
        writeFileSync(file, JSON.stringify(status));
      } finally {
        closeSync(file);
      }
      renameSync(temporary, statusPath);
    } catch {
      parentPort?.postMessage({
        kind: 'retention',
        state: 'unavailable',
        code: 'TRACE_STATUS_UNAVAILABLE',
      });
    } finally {
      if (created && existsSync(temporary)) unlinkSync(temporary);
    }
    parentPort?.postMessage(status);
  };
  const stop = () => {
    if (closed) return;
    closed = true;
    shutdown.abort();
    clearTimeout(timer);
    clearInterval(heartbeat);
    void (async () => {
      try {
        await catalog?.close();
      } catch {
        /* May already be closing. */
      }
    })();
    void inventory.close();
    native?.close();
    closeWindowsPrivateStateBackend();
    phase = 'stopped';
    publish();
    parentPort?.close();
  };
  parentPort.on('message', (message) => {
    if (message?.kind === 'close') stop();
  });
  parentPort.once('close', stop);
  publish();
  const heartbeat = setInterval(publish, 10_000);
  try {
    if (process.platform === 'win32') {
      native = await createWindowsTraceBackend(
        inspectWindowsPipe(),
        scope.traceRoot,
        shutdown.signal,
      );
      if (closed) {
        native.close();
        return;
      }
    }
    const retention = new DesktopTraceRetention(scope.traceRoot, key, shutdown.signal, () => {
      const configPath = process.env.CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG;
      if (process.platform !== 'darwin' || closed || !configPath) return false;
      try {
        return new DesktopNativeSuggestionChannel({
          traceRoot: scope.traceRoot,
          configPath,
          target: () => scope.target,
        }).available;
      } catch {
        return false;
      }
    });
    let nextScan = 0;
    let nextNativeCheck = 0;
    const enqueue = (name: string) => {
      if (!queued.has(name)) {
        queued.add(name);
        queue.push(name);
      }
    };
    const record = (name: string, state: string) => {
      recent.push({ bundle: name, state });
      if (recent.length > 16) recent.shift();
      const old = seen.get(name);
      const repeats = old?.state === state ? Math.min(old.repeats + 1, 5) : 1;
      const delay =
        state === 'active-or-unsealed' ? 5000 : Math.min(900000, 60000 * 2 ** (repeats - 1));
      seen.set(name, { state, repeats, after: Date.now() + delay });
    };
    const tick = async () => {
      if (closed) return;
      try {
        const firstTick = lastSuccessfulTick === 0;
        const retiredBefore = totals.retired;
        const now = Date.now();
        if (native && now >= nextNativeCheck) {
          if (!windowsTraceInstanceReady(native.backend.inspect(), scope.traceRoot))
            throw new Error('TRACE_NATIVE_UNAVAILABLE');
          nextNativeCheck = now + 5000;
        }
        // Bound discovery and waiting work without disabling large backlogs.
        // FIFO does not let continuous new sessions starve older candidates.
        if (!catalog && now >= nextScan) {
          const opened = await opendir(scope.traceRoot);
          if (closed) {
            await opened.close();
            return;
          }
          catalog = opened;
          current = new Set();
        }
        if (catalog)
          for (let count = 0; count < 512 && queue.length < 4096 && !closed; count++) {
            const entry = await catalog.read();
            if (!entry) {
              await catalog.close();
              catalog = undefined;
              for (const name of seen.keys()) if (!current.has(name)) seen.delete(name);
              lastCompleteScan = Date.now();
              nextScan = Date.now() + 5000;
              break;
            }
            if (!/^trace-[0-9a-f-]{36}-[0-9a-f-]{36}$/i.test(entry.name)) continue;
            current.add(entry.name);
            const previous = seen.get(entry.name);
            if (!previous || now >= previous.after) enqueue(entry.name);
          }
        for (let count = 0; count < 4 && queue.length && !closed; count++) {
          const name = queue.shift()!;
          queued.delete(name);
          try {
            const result = await retention.process(name, Date.now());
            if (result.state === 'retired') {
              seen.delete(name);
              totals.retired++;
              totals.releasedBytes += result.bytes;
            } else {
              totals.retained++;
              record(name, result.state);
            }
          } catch (error) {
            totals.failed++;
            record(name, code(error));
          }
        }
        if (closed) return;
        await inventory.slice();
        phase = 'running';
        lastError = undefined;
        lastSuccessfulTick = Date.now();
        if (firstTick || totals.retired !== retiredBefore) publish();
      } catch (error) {
        if (closed) return;
        phase = 'degraded';
        lastError = code(error);
        publish();
      }
      if (!closed) timer = setTimeout(() => void tick(), 1000);
    };
    await tick();
  } catch (error) {
    phase = 'degraded';
    lastError = code(error);
    publish();
    stop();
  }
}
void run();
