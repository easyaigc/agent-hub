import { appendFileSync } from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import workerThreads from 'node:worker_threads';

const watched = new Map();
let original, wrapper;

// Observe the actual, fixed transport Workers without owning or stopping them.
export function monitorPrivateStateWorkers(entryURL, log, { onEvent } = {}) {
  const active = new Set();
  const controls = new WeakMap();
  watched.set(entryURL.href, { log, active, controls, onEvent });
  if (!wrapper) {
    original = workerThreads.Worker;
    wrapper = new Proxy(original, {
      construct(Type, args, newTarget) {
        const worker = Reflect.construct(Type, args, newTarget);
        const scope = args[0] instanceof URL && watched.get(args[0].href);
        if (scope) {
          const id = worker.threadId;
          scope.active.add(worker);
          scope.controls.set(worker, new Int32Array(args[1].workerData.shared));
          scope.onEvent?.({ event: 'transport-start', id });
          worker.on('message', (message) => {
            if (message?.kind === 'fixture-lifecycle') {
              const { kind: _kind, ...event } = message;
              scope.onEvent?.(event);
            }
          });
          worker.once('exit', (code) => {
            scope.active.delete(worker);
            scope.onEvent?.({ event: 'transport-exit', id, code });
            appendFileSync(scope.log, JSON.stringify({ event: 'transport-exit', id, code }) + '\n');
          });
        }
        return worker;
      },
    });
    workerThreads.Worker = wrapper;
    syncBuiltinESMExports();
  }
  return {
    active,
    controls,
    restore() {
      watched.delete(entryURL.href);
      if (!watched.size) {
        workerThreads.Worker = original;
        wrapper = undefined;
        syncBuiltinESMExports();
      }
    },
  };
}
