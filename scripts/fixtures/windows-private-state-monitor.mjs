import { appendFileSync } from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import workerThreads from 'node:worker_threads';

const watched = new Map();
let original, wrapper;

// Observe the actual, fixed transport Workers without owning or stopping them.
export function monitorPrivateStateWorkers(entryURL, log) {
  const active = new Set();
  watched.set(entryURL.href, { log, active });
  if (!wrapper) {
    original = workerThreads.Worker;
    wrapper = new Proxy(original, {
      construct(Type, args, newTarget) {
        const worker = Reflect.construct(Type, args, newTarget);
        const scope = args[0] instanceof URL && watched.get(args[0].href);
        if (scope) {
          const id = worker.threadId;
          scope.active.add(worker);
          worker.once('exit', (code) => {
            scope.active.delete(worker);
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
