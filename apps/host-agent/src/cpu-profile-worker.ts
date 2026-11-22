import { createHash } from 'node:crypto';
import { existsSync, renameSync, writeFileSync } from 'node:fs';
import { Session } from 'node:inspector';
import { join } from 'node:path';
import { parentPort, workerData } from 'node:worker_threads';
import {
  MAX_PROFILE_BYTES,
  privateDirectory,
  readPrivateJSON,
  uuid,
  writePrivateJSON,
} from './cpu-profile-files.js';

const { directory, identity } = workerData as {
  directory: string;
  identity: { version: number; bootId: string; pid: number; expiresAt: number };
};
privateDirectory(directory);
const readyFile = join(directory, 'ready.json');
const requestFile = join(directory, `request-${identity.bootId}.json`);
const stopFile = join(directory, `stop-${identity.bootId}.json`);
const expiresNs =
  process.hrtime.bigint() + BigInt(Math.max(0, identity.expiresAt - Date.now())) * 1_000_000n;
let closed = false;
let captures = 0;
const ready = (state: string) => writePrivateJSON(readyFile, { ...identity, state, captures });
const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
const stopRequested = () => {
  if (!existsSync(stopFile)) return false;
  const value = readPrivateJSON(stopFile);
  return value.bootId === identity.bootId && value.pid === identity.pid;
};
async function metrics(kind: 'start' | 'stop', operationId: string): Promise<unknown> {
  return new Promise((resolve) => {
    const listener = (message: { kind: string; operationId: string; metrics?: unknown }) => {
      if (
        message.kind === `metrics-${kind === 'start' ? 'started' : 'stopped'}` &&
        message.operationId === operationId
      ) {
        clearTimeout(timer);
        parentPort!.off('message', listener);
        resolve(message.metrics ?? null);
      }
    };
    const timer = setTimeout(() => {
      parentPort!.off('message', listener);
      resolve(null);
    }, 5_000);
    parentPort!.on('message', listener);
    parentPort!.postMessage({ kind: `metrics-${kind}`, operationId });
  });
}
async function capture(request: Record<string, unknown>) {
  if (
    request.version !== 1 ||
    request.bootId !== identity.bootId ||
    request.pid !== identity.pid ||
    !uuid(request.operationId) ||
    !Number.isSafeInteger(request.durationMs) ||
    (request.durationMs as number) < 5_000 ||
    (request.durationMs as number) > 60_000
  )
    throw new Error('CPU_PROFILE_INVALID_REQUEST');
  const operationId = request.operationId;
  const receiptFile = join(directory, `receipt-${operationId}.json`);
  if (existsSync(receiptFile)) return;
  const base = {
    ...identity,
    operationId,
    durationMs: request.durationMs,
    samplingIntervalUs: 2_000,
  };
  writePrivateJSON(receiptFile, { ...base, state: 'starting' }, true);
  ready('active');
  const session = new Session();
  // This fixed in-process channel cannot receive operator-selected CDP methods or JS.
  const post = <T>(
    method: 'Profiler.enable' | 'Profiler.setSamplingInterval' | 'Profiler.start' | 'Profiler.stop',
    params = {},
  ): Promise<T> =>
    new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error('CPU_PROFILE_RESPONSE_DEADLINE_UNKNOWN')),
        10_000,
      );
      session.post(method, params, (error, result) => {
        clearTimeout(timer);
        if (error) reject(error);
        else resolve(result as T);
      });
    });
  let started = false;
  let stopRequestedNs: string | undefined;
  let startNs: string | undefined;
  let outcome: Record<string, unknown>;
  try {
    session.connectToMainThread();
    await post('Profiler.enable');
    await post('Profiler.setSamplingInterval', { interval: 2_000 });
    await metrics('start', operationId);
    // Once submitted a missing response is unknown, even if start was not acknowledged.
    started = true;
    await post('Profiler.start');
    startNs = String(process.hrtime.bigint());
    writePrivateJSON(receiptFile, { ...base, state: 'recording', startNs });
    const deadline = process.hrtime.bigint() + BigInt(request.durationMs as number) * 1_000_000n;
    while (
      process.hrtime.bigint() < deadline &&
      process.hrtime.bigint() < expiresNs &&
      !stopRequested()
    )
      await sleep(100);
    stopRequestedNs = String(process.hrtime.bigint());
    const result = await post<{
      profile: { nodes: unknown[]; samples?: unknown[]; startTime: number; endTime: number };
    }>('Profiler.stop');
    started = false;
    const stoppedNs = String(process.hrtime.bigint());
    const profile = result.profile;
    if (profile.nodes.length > 60_000 || (profile.samples?.length ?? 0) > 120_000)
      throw new Error('CPU_PROFILE_OUTPUT_BOUND_EXCEEDED');
    const bytes = Buffer.from(JSON.stringify(profile));
    if (bytes.length > MAX_PROFILE_BYTES) throw new Error('CPU_PROFILE_OUTPUT_BOUND_EXCEEDED');
    const name = `profile-${operationId}.cpuprofile`;
    writeFileSync(join(directory, name), bytes, { mode: 0o600, flag: 'wx' });
    outcome = {
      state: 'completed',
      profile: name,
      bytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
      startNs,
      stopRequestedNs,
      stoppedNs,
      actualProfileDurationUs: profile.endTime - profile.startTime,
      requestedStopDelayMs: Number(BigInt(stoppedNs) - BigInt(stopRequestedNs)) / 1e6,
    };
  } catch (error) {
    // A missing response does not prove collection stopped. Never kill Main or silently retry.
    outcome = {
      state: started ? 'unknown' : 'failed',
      startNs,
      stopRequestedNs,
      reason:
        error instanceof Error && error.message.startsWith('CPU_PROFILE_')
          ? error.message
          : 'CPU_PROFILE_INSPECTOR_FAILED',
    };
    closed = true;
  } finally {
    // Only our session is disconnected; no inspector.close(), network port or other debugger.
    session.disconnect();
  }
  const layerMetrics = await metrics('stop', operationId);
  writePrivateJSON(receiptFile, {
    ...base,
    ...outcome,
    layerMetrics,
    interpretation:
      'Main JS samples only. Worker/native thread CPU is not represented. Raw profiles contain code URLs and names; keep private. Stop deadlines bound requests, not Main response under stalls.',
  });
  captures++;
}

try {
  ready('idle');
  while (!closed && captures < 3 && process.hrtime.bigint() < expiresNs && !stopRequested()) {
    if (existsSync(requestFile)) {
      const request = readPrivateJSON(requestFile);
      if (!uuid(request.operationId)) throw new Error('CPU_PROFILE_INVALID_OPERATION');
      // Persist the claimed immutable intent before any profiling action.
      renameSync(requestFile, join(directory, `request-${request.operationId}.used.json`));
      await capture(request);
      if (!closed) ready('idle');
    }
    await sleep(250);
  }
} finally {
  ready('closed');
  parentPort!.close();
}
