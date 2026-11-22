import { Session, url } from 'node:inspector';
import { startOptionalCpuProfile } from '../../apps/host-agent/dist/cpu-profile-control.js';
import { RuntimePersistence } from '../../packages/codex-adapter/dist/runtime-persistence.js';
import { beginCpuStage } from '../../packages/codex-adapter/dist/cpu-stages.js';
import { join } from 'node:path';

const [state, hostId] = process.argv.slice(2);
const observer = new Session();
observer.connect();
observer.post('Runtime.enable');
const close = startOptionalCpuProfile(state, hostId);
const store = new RuntimePersistence(join(state, 'synthetic-state.json'), state);
let tick = 0;
function namedCpuWork(durationMs = 12) {
  const end = beginCpuStage('desktop.projection');
  try {
    const until = performance.now() + durationMs;
    while (performance.now() < until) {
      tick++;
      store.save({
        hostId,
        threads: [],
        synthetic: Array.from({ length: 100 }, (_, i) => `${tick}-${i}-synthetic`),
      });
    }
  } finally {
    end?.();
  }
}
const timer = setInterval(namedCpuWork, 50);
process.on('message', (message) => {
  if (message.blockMs) namedCpuWork(message.blockMs);
  if (message.check) {
    observer.post('Runtime.getIsolateId', (error, result) =>
      process.send({
        check: true,
        error: !!error,
        isolate: !!result?.id,
        inspectorUrl: url() ?? null,
        tick,
      }),
    );
  }
  if (message.close) {
    clearInterval(timer);
    close();
    observer.disconnect();
    store.close();
    process.exit(0);
  }
});
process.send({ started: true, pid: process.pid, inspectorUrl: url() ?? null });
