import { parentPort, workerData } from 'node:worker_threads';
import {
  readdirSync,
  writeFileSync,
  renameSync,
  lstatSync,
  openSync,
  closeSync,
  unlinkSync,
  existsSync,
} from 'node:fs';
import { machineRuntimeTargetKey, type RuntimeTarget } from '@codex-manage/protocol';
import { DesktopTraceRetention } from './desktop-trace-retention.js';
import { createHash, randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { createWindowsTraceBackend } from './desktop-trace-native.js';
import {
  closeWindowsPrivateStateBackend,
  inspectWindowsPipe,
  windowsTraceInstanceReady,
} from './desktop-platform.js';

async function run(): Promise<void> {
  const scope = workerData as { traceRoot: string; target: RuntimeTarget; directory: string };
  if (!scope || typeof scope.traceRoot !== 'string' || !scope.target || !parentPort) return;
  let closed = false;
  const shutdown = new AbortController();
  let native: Awaited<ReturnType<typeof createWindowsTraceBackend>> | undefined;
  let timer: NodeJS.Timeout | undefined;
  const stop = () => {
    if (closed) return;
    closed = true;
    shutdown.abort();
    clearTimeout(timer);
    native?.close();
    closeWindowsPrivateStateBackend();
    parentPort?.close();
  };
  parentPort.on('message', (message) => {
    if (message?.kind === 'close') stop();
  });
  parentPort.once('close', stop);
  try {
    if (process.platform === 'win32') {
      const backend = await createWindowsTraceBackend(
        inspectWindowsPipe(),
        scope.traceRoot,
        shutdown.signal,
      );
      native = backend;
      if (closed) {
        native.close();
        return;
      }
    }
    const retention = new DesktopTraceRetention(
      scope.traceRoot,
      machineRuntimeTargetKey(scope.target),
      shutdown.signal,
    );
    const statusPath = join(
      scope.directory,
      'trace-retention-' +
        createHash('sha256').update(machineRuntimeTargetKey(scope.target)).digest('hex') +
        '.json',
    );
    let lastStatus = 0;
    let queue: string[] = [];
    const seen = new Map<string, number>();
    let nextScan = 0;
    let nextNativeCheck = 0;
    let known = new Set<string>();
    const totals = { retired: 0, releasedBytes: 0, retained: 0, failed: 0 };
    const recent: { bundle: string; state: string }[] = [];
    const record = (bundle: string, state: string) => {
      recent.push({ bundle, state });
      if (recent.length > 16) recent.shift();
    };
    const tick = async () => {
      if (closed) return;
      const now = Date.now();
      if (native && now >= nextNativeCheck) {
        try {
          if (!windowsTraceInstanceReady(native.backend.inspect(), scope.traceRoot)) {
            stop();
            return;
          }
          nextNativeCheck = now + 5000;
        } catch {
          stop();
          return;
        }
      }
      try {
        if (now >= nextScan) {
          const names = readdirSync(scope.traceRoot).filter((n) =>
            /^trace-[0-9a-f-]{36}-[0-9a-f-]{36}$/i.test(n),
          );
          if (names.length > 20000) throw new Error('TRACE_DIRECTORY_BUDGET');
          const current = new Set(names);
          for (const key of seen.keys()) if (!current.has(key)) seen.delete(key);
          const fresh = names
            .filter((n) => !known.has(n))
            .map((name) => ({ name, time: lstatSync(join(scope.traceRoot, name)).mtimeMs }))
            .sort((a, b) => b.time - a.time)
            .map((row) => row.name);
          const due = names.filter((n) => seen.has(n) && now >= seen.get(n)!);
          queue = [...new Set([...fresh, ...queue.filter((n) => current.has(n)), ...due])];
          known = current;
          nextScan = now + 5000;
        }
        for (let count = 0; count < 4 && queue.length && !closed; count++) {
          const name = queue.shift()!;
          seen.set(name, now + 60000);
          try {
            const result = await retention.process(name, Date.now());
            if (result.state === 'retired') {
              totals.retired++;
              totals.releasedBytes += result.bytes;
            } else {
              totals.retained++;
              record(name, result.state);
              if (result.state === 'active-or-unsealed') seen.set(name, now + 5000);
            }
          } catch (error) {
            totals.failed++;
            const code =
              error instanceof Error && /^[A-Z_]{1,80}$/.test(error.message)
                ? error.message
                : 'TRACE_RETENTION_REJECTED';
            record(name, code);
          }
        }
        const status = {
          version: 1,
          kind: 'retention',
          ...totals,
          pending: queue.length,
          recent,
          countersSince: 'process-start',
          checkedAt: Date.now(),
        };
        if (Date.now() - lastStatus >= 10000) {
          const path = statusPath;
          const temporary = path + '.' + randomUUID() + '.tmp';
          let created = false;
          try {
            const file = openSync(temporary, 'wx', 0o600);
            created = true;
            try {
              writeFileSync(file, JSON.stringify(status));
            } finally {
              closeSync(file);
            }
            renameSync(temporary, path);
          } finally {
            if (created && existsSync(temporary)) unlinkSync(temporary);
          }
          lastStatus = Date.now();
        }
        if (!closed) parentPort?.postMessage(status);
      } catch {
        parentPort?.postMessage({ kind: 'retention', ...totals, state: 'unavailable' });
      }
      if (!closed) timer = setTimeout(() => void tick(), 1000);
    };
    await tick();
  } catch {
    stop();
  }
}
void run();
