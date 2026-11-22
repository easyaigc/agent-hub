import { randomUUID } from 'node:crypto';
import { existsSync, renameSync, unlinkSync } from 'node:fs';
import { url as inspectorUrl } from 'node:inspector';
import { join } from 'node:path';
import { Worker } from 'node:worker_threads';
import { startCpuStages } from '@codex-manage/codex-adapter';
import {
  CPU_PROFILE_VERSION,
  MAX_ARM_MS,
  fileIdentity,
  profileDirectory,
  readPrivateJSON,
  sameIdentity,
  uuid,
  writePrivateJSON,
} from './cpu-profile-files.js';

/** Local operator only. No HTTP endpoint, environment change, debugger server or model input. */
export function armCpuProfile(state: string, hostId: string, entry: string) {
  if (process.platform !== 'darwin') throw new Error('CPU_PROFILE_MACOS_ONLY');
  if (!uuid(hostId)) throw new Error('CPU_PROFILE_HOST_ID_REQUIRED');
  const directory = profileDirectory(state, true);
  const arm = {
    version: CPU_PROFILE_VERSION,
    operationId: randomUUID(),
    hostId,
    expiresAt: Date.now() + MAX_ARM_MS,
    node: fileIdentity(process.execPath),
    entry: fileIdentity(entry),
  };
  writePrivateJSON(join(directory, 'arm.json'), arm, true);
  return arm;
}

export function cpuProfileStatus(state: string) {
  return readPrivateJSON(join(profileDirectory(state), 'ready.json'));
}

/** Withdraw only an unconsumed, exactly identified local arm; never stop a Host. */
export function disarmCpuProfile(state: string, operationId: string) {
  if (!uuid(operationId)) throw new Error('CPU_PROFILE_OPERATION_REQUIRED');
  const file = join(profileDirectory(state), 'arm.json');
  const arm = readPrivateJSON(file);
  if (arm.operationId !== operationId) throw new Error('CPU_PROFILE_ARM_OPERATION_MISMATCH');
  unlinkSync(file);
  return { operationId, state: 'disarmed' };
}

export function requestCpuProfile(
  state: string,
  pid: number,
  operationId: string,
  durationMs: number,
) {
  if (
    !uuid(operationId) ||
    !Number.isSafeInteger(durationMs) ||
    durationMs < 5_000 ||
    durationMs > 60_000
  )
    throw new Error('CPU_PROFILE_INVALID_REQUEST');
  const directory = profileDirectory(state);
  const ready = cpuProfileStatus(state);
  if (
    ready.version !== CPU_PROFILE_VERSION ||
    ready.pid !== pid ||
    ready.state !== 'idle' ||
    (typeof ready.captures === 'number' && ready.captures >= 3) ||
    !uuid(ready.bootId) ||
    typeof ready.expiresAt !== 'number' ||
    Date.now() >= ready.expiresAt
  )
    throw new Error('CPU_PROFILE_NOT_READY_FOR_EXACT_PID');
  process.kill(pid, 0); // Existence only; bootId is checked again inside the original Host.
  const receipt = join(directory, `receipt-${operationId}.json`);
  if (existsSync(receipt)) return readPrivateJSON(receipt); // Query; never replay an operation.
  const request = {
    version: CPU_PROFILE_VERSION,
    bootId: ready.bootId,
    pid,
    operationId,
    durationMs,
  };
  writePrivateJSON(join(directory, `request-${ready.bootId}.json`), request, true);
  return request;
}

export function stopCpuProfile(state: string, pid: number) {
  const directory = profileDirectory(state);
  const ready = cpuProfileStatus(state);
  if (ready.pid !== pid || !uuid(ready.bootId)) throw new Error('CPU_PROFILE_WRONG_PID');
  const file = join(directory, `stop-${ready.bootId}.json`);
  if (!existsSync(file))
    writePrivateJSON(file, { version: CPU_PROFILE_VERSION, bootId: ready.bootId, pid }, true);
  return cpuProfileStatus(state);
}

/** No worker, timer, GC observer or port when not explicitly armed before this Host start. */
export function startOptionalCpuProfile(state: string, hostId: string): () => void {
  if (process.platform !== 'darwin') return () => {};
  let finish: ReturnType<typeof startCpuStages> | undefined;
  let worker: Worker | undefined;
  try {
    // Do not create diagnostics directories during ordinary startup.
    const directory = profileDirectory(state);
    const armFile = join(directory, 'arm.json');
    const arm = readPrivateJSON(armFile);
    if (
      arm.version !== CPU_PROFILE_VERSION ||
      arm.hostId !== hostId ||
      !uuid(arm.operationId) ||
      typeof arm.expiresAt !== 'number' ||
      arm.expiresAt <= Date.now() ||
      arm.expiresAt > Date.now() + MAX_ARM_MS ||
      !sameIdentity(arm.node, fileIdentity(process.execPath)) ||
      !sameIdentity(arm.entry, fileIdentity(process.argv[1]!)) ||
      inspectorUrl()
    )
      throw new Error('CPU_PROFILE_ARM_MISMATCH_OR_DEBUGGER_PRESENT');
    // Consume once, including across Host restarts. No operation starts from an arm alone.
    renameSync(armFile, join(directory, `arm-${arm.operationId}.used.json`));
    const identity = {
      version: CPU_PROFILE_VERSION,
      bootId: randomUUID(),
      pid: process.pid,
      hostId,
      armOperationId: arm.operationId,
      expiresAt: arm.expiresAt,
      node: arm.node,
      entry: arm.entry,
    };
    worker = new Worker(new URL('./cpu-profile-worker.js', import.meta.url), {
      workerData: { directory, identity },
    });
    worker.on('message', (message: { kind: string; operationId: string }) => {
      try {
        if (message.kind === 'metrics-start' && !finish) {
          finish = startCpuStages();
          worker?.postMessage({ kind: 'metrics-started', operationId: message.operationId });
        } else if (message.kind === 'metrics-stop') {
          const metrics = finish?.();
          finish = undefined;
          worker?.postMessage({
            kind: 'metrics-stopped',
            operationId: message.operationId,
            metrics,
          });
        }
      } catch {
        // Optional metrics failure must not affect the Host or any command.
        finish = undefined;
      }
    });
    const reset = () => {
      try {
        finish?.();
      } catch {
        /* Diagnostic failure cannot become a Host failure. */
      }
      finish = undefined;
    };
    worker.once('exit', reset);
    // Diagnostics must never bring down or prevent the normal Host from starting.
    worker.once('error', reset);
    worker.unref();
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT')
      console.error(
        JSON.stringify({
          diagnostic: 'cpu-profile-unavailable',
          reason: 'private-arm-or-runtime-check-failed',
        }),
      );
  }
  return () => {
    finish?.();
    finish = undefined;
    // Normal Host close only; no termination of Main, desktop or model processes.
    void worker?.terminate();
  };
}
