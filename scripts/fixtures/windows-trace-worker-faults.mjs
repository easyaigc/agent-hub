import fs from 'node:fs';
import { join } from 'node:path';
import { parentPort, workerData } from 'node:worker_threads';
import assert from 'node:assert/strict';
import { getWindowsProcessGate } from '../../packages/codex-adapter/dist/windows-process.js';

// Controlled worker fault only. No native processes, model, real history or I/O outside fixture scope.
assert.equal(
  getWindowsProcessGate(),
  workerData.windowsProcessGate,
  'Reader must inherit the actual process gate',
);
const file = join(workerData.scope.directory, 'worker-generation.json');
const generation = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file)) + 1 : 1;
fs.writeFileSync(file, JSON.stringify(generation));
const state = () =>
  parentPort.postMessage({ kind: 'state', available: true, sampledAt: performance.now() });
state();
const heartbeat = setInterval(state, 200);
parentPort.on('message', (request) => {
  if (request.kind === 'close') {
    clearInterval(heartbeat);
    parentPort.close();
    return;
  }
  if (request.kind !== 'read' || generation === 1) return;
  parentPort.postMessage({
    kind: 'result',
    id: request.id,
    available: true,
    sampledAt: performance.now(),
    value: {
      schemaVersion: 1,
      target: workerData.scope.target,
      threadId: request.threadId,
      sourceTurnId: request.sourceTurnId,
      source: 'native-trace',
      readOnly: true,
      suggestion: null,
    },
  });
});
