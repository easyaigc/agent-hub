import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { isMainThread, parentPort, threadId, workerData } from 'node:worker_threads';

// Only disposable fixture files and real Node children; no native/Host/model API.
if (isMainThread) {
  const [mode, marker, duration] = process.argv.slice(2);
  assert(['short', 'timeout'].includes(mode));
  appendFileSync(marker, 'effect\n');
  process.stdout.write('CLI_OK\n');
  process.stderr.write('CLI_STDERR\n');
  setTimeout(() => process.exit(0), mode === 'timeout' ? 8000 : Number(duration ?? 0));
} else {
  Object.defineProperty(process, 'platform', { value: 'win32' });
  const { getWindowsProcessGate, withWindowsProcessCreation } = await import(workerData.gateURL);
  const gate = getWindowsProcessGate();
  const control = new Int32Array(workerData.control);
  if (workerData.mode === 'hold') {
    withWindowsProcessCreation(5000, () => {
      assert.equal(Atomics.load(new Int32Array(gate), 0), threadId + 1);
      parentPort.postMessage({ kind: 'held', threadId });
      // Main acknowledges before our bounded hold starts, making budget checks deterministic.
      Atomics.wait(control, 0, 0, 4000);
      Atomics.wait(control, 1, 0, workerData.holdMs);
    });
    parentPort.postMessage({ kind: 'released' });
  } else {
    assert.equal(workerData.mode, 'contend');
    parentPort.postMessage({ kind: 'ready' });
    Atomics.wait(control, 0, 0, 4000);
    const started = performance.now();
    const result = withWindowsProcessCreation(3000, (remaining) => {
      assert.equal(Atomics.load(new Int32Array(gate), 0), threadId + 1);
      assert.equal(Atomics.compareExchange(control, 1, 0, threadId + 1), 0);
      try {
        const output = execFileSync(
          process.execPath,
          [fileURLToPath(import.meta.url), 'short', workerData.marker, '40'],
          { encoding: 'utf8', stdio: 'pipe', timeout: remaining },
        );
        assert.equal(output, 'CLI_OK\n');
        return { remaining, elapsedMs: performance.now() - started };
      } finally {
        assert.equal(Atomics.exchange(control, 1, 0), threadId + 1);
      }
    });
    parentPort.postMessage({ kind: 'done', ...result });
  }
  if (workerData.keepAlive)
    parentPort.once('message', (message) => {
      assert.equal(message, 'close');
      parentPort.close();
    });
  else parentPort.close();
}
