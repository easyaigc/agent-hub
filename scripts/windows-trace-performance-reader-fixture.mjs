import { workerData } from 'node:worker_threads';
import { runSuggestionReadWorker } from '../packages/codex-adapter/dist/desktop-suggestion-read-worker.js';

// This test entry, never a production Worker option or HTTP/environment field,
// controls only instance readiness. All trace I/O goes through native handles.
const scope = workerData.scope;
await runSuggestionReadWorker(scope, (inspect) =>
  process.env.TRACE_PERF_NATIVE_PROBE === '1'
    ? (() => {
        const value = inspect();
        return {
          ...value,
          traceRoot: scope.traceRoot,
          executors: value.executors.map((e) => ({ ...e, traceRoot: scope.traceRoot })),
        };
      })()
    : {
        pid: 1,
        startedAtUnixMs: 1,
        traceRoot: scope.traceRoot,
        executors: [{ pid: 2, parentPid: 1, startedAtUnixMs: 1, traceRoot: scope.traceRoot }],
      },
);
