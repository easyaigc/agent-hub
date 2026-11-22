import { PerformanceObserver } from 'node:perf_hooks';

/** Fixed labels only: never pass paths, targets, text or operation identifiers. */
export type CpuStage =
  | 'persistence.state.reuse'
  | 'persistence.history.reuse'
  | 'persistence.snapshot.declined'
  | 'persistence.serialize'
  | 'persistence.sha256'
  | 'persistence.save'
  | 'desktop.frame.parse'
  | 'desktop.projection'
  | 'desktop.attachments'
  | 'files.inspect'
  | 'http.read.serialize';
type Totals = { calls: number; wallMs: number; maxWallMs: number };
let active: { stages: Map<CpuStage, Totals> } | undefined;

/** Disabled hot path allocates nothing. Async spans include waiting and may overlap. */
export function beginCpuStage(label: CpuStage): (() => void) | undefined {
  const capture = active;
  if (!capture) return;
  const start = performance.now();
  return () => {
    const elapsed = performance.now() - start;
    const value = capture.stages.get(label) ?? { calls: 0, wallMs: 0, maxWallMs: 0 };
    value.calls++;
    value.wallMs += elapsed;
    value.maxWallMs = Math.max(value.maxWallMs, elapsed);
    capture.stages.set(label, value);
  };
}

export function startCpuStages() {
  if (active) throw new Error('CPU metrics already active');
  const capture = { stages: new Map<CpuStage, Totals>() };
  active = capture;
  const startedNs = process.hrtime.bigint();
  const cpu = process.cpuUsage();
  const thread = process.threadCpuUsage?.();
  const memory = process.memoryUsage();
  const gc = { events: 0, wallMs: 0, maxWallMs: 0 };
  const collect = (entries: { duration: number }[]) => {
    for (const entry of entries) {
      gc.events++;
      gc.wallMs += entry.duration;
      gc.maxWallMs = Math.max(gc.maxWallMs, entry.duration);
    }
  };
  const observer = new PerformanceObserver((list) => collect(list.getEntries()));
  observer.observe({ entryTypes: ['gc'] });
  return () => {
    if (active !== capture) throw new Error('CPU metrics ownership changed');
    active = undefined;
    collect(observer.takeRecords());
    observer.disconnect();
    return {
      startedNs: String(startedNs),
      stoppedNs: String(process.hrtime.bigint()),
      processCpuUs: process.cpuUsage(cpu),
      mainThreadCpuUs: thread ? process.threadCpuUsage(thread) : null,
      memoryBefore: memory,
      memoryAfter: process.memoryUsage(),
      gc,
      stages: Object.fromEntries(capture.stages),
      interpretation:
        'Stage and GC durations are wall time, can overlap, and are not additive CPU shares. Process CPU includes all threads; Main metrics boundaries differ from profiler boundaries.',
    };
  };
}
