import fs from 'node:fs';
import { join } from 'node:path';
import { parentPort, workerData } from 'node:worker_threads';
const file = join(workerData.scope.directory, 'retirement-generations.json');
const generation = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file)) + 1 : 1;
fs.writeFileSync(file, JSON.stringify(generation), { mode: 0o600 });
const heartbeat = setInterval(
  () => parentPort.postMessage({ kind: 'state', available: true, sampledAt: performance.now() }),
  100,
);
parentPort.postMessage({ kind: 'state', available: true, sampledAt: performance.now() });
parentPort.on('message', (value) => {
  if (value.kind === 'read' && generation === 1) parentPort.postMessage(null);
  // First generation deliberately ignores close. The actual client must wait
  // for its own 2500ms termination grace before creating another Reader.
  if (value.kind === 'close' && generation > 1) {
    clearInterval(heartbeat);
    parentPort.close();
  }
});
