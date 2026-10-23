import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { Worker, parentPort, workerData } from 'node:worker_threads';

// Runs as an ordinary subprocess or an owning isolate; never starts a Host/model.
const config = workerData ?? JSON.parse(readFileSync(process.argv[2], 'utf8'));
if (config.scenario === 'isolates') {
  // The real Host main loads the Adapter before starting its owning isolates.
  await import(config.moduleURL);
  const { getWindowsProcessGate, registerWindowsProcessWorker } = await import(
    new URL('./windows-process.js', config.moduleURL)
  );
  // Keep abrupt-owner failures inside a disposable process. Even a regression
  // leaving a nested Worker alive must not hang the parent test runner.
  const owners = [];
  const active = new Set();
  const alive = (pid) => {
    try {
      process.kill(pid, 0);
      return true;
    } catch (error) {
      if (error.code === 'ESRCH') return false;
      throw error;
    }
  };
  const untilGone = async (pid) => {
    const deadline = Date.now() + 3000;
    while (alive(pid)) {
      assert(Date.now() < deadline, 'Terminated owner left its child alive');
      await delay(15);
    }
    active.delete(pid);
  };
  let failure;
  try {
    const start = async () => {
      const owner = new Worker(new URL(import.meta.url), {
        execArgv: [],
        workerData: {
          moduleURL: config.moduleURL,
          entryURL: config.entryURL,
          path: config.path,
          windowsProcessGate: getWindowsProcessGate(),
        },
      });
      registerWindowsProcessWorker(owner);
      owners.push(owner);
      const result = await new Promise((resolve, reject) => {
        owner.once('error', reject);
        owner.once('message', resolve);
      });
      assert(result.ready && Number.isSafeInteger(result.pid) && result.pid > 0);
      active.add(result.pid);
      return result.pid;
    };
    const first = await start(),
      second = await start();
    assert.notEqual(first, second);
    assert(alive(first) && alive(second));
    await owners[0].terminate();
    await untilGone(first);
    assert(alive(second), 'Terminating one owner retired the other owner child');
    const exited = new Promise((resolve, reject) => {
      owners[1].once('error', reject);
      owners[1].once('exit', resolve);
    });
    owners[1].postMessage('close');
    await exited;
    await untilGone(second);
  } catch (error) {
    failure = error;
  } finally {
    for (const owner of owners) await owner.terminate();
    // Never signal an unverified numeric PID after its owner has terminated.
    // Synthetic children have a finite lifetime. Native EOF failures remain
    // failures; exiting this disposable supervisor closes only its own pipes.
  }
  // Explicit exit contains any nested-Worker leak on the failing path. It is
  // never used as evidence for the assertions above.
  process.stdout.write(
    JSON.stringify({
      passed: !failure,
      error: failure?.message,
      ...(active.size ? { unconfirmedChildren: active.size } : {}),
    }),
    () => process.exit(failure ? 1 : 0),
  );
  await new Promise(() => {});
}
const { createWindowsPrivateStateBackend } = await import(config.moduleURL);
const backend = createWindowsPrivateStateBackend(new URL(config.entryURL));
backend.call('check', config.path ?? 'synthetic-owner-path');
const result = { pid: backend.status.pid, ready: true };
if (config.result) writeFileSync(config.result, JSON.stringify(result));
if (parentPort) {
  parentPort.postMessage(result);
  parentPort.on('message', (message) => {
    assert.equal(message, 'close');
    backend.close();
    parentPort.close();
  });
} else if (config.explicitClose) backend.close();
// Without explicit close, the backend's beforeExit/exit lifecycle must retire it.
