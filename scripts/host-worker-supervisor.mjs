import { spawn } from 'node:child_process';

const identifier = (value) =>
  typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value) ? value : undefined;
export function safeWorkerFailure(error) {
  const frames = new Set([
    'runtime-persistence.js',
    'runtime-session.js',
    'runtime-server.js',
    'runtime-entry.js',
    'desktop-platform.js',
    'windows-desktop-worker.mjs',
    'windows-desktop-recovery.mjs',
    'host-worker-supervisor.mjs',
  ]);
  return {
    code: identifier(error?.code),
    syscall: identifier(error?.syscall),
    stage: identifier(error?.stage),
    nativeStatus: Number.isSafeInteger(error?.nativeStatus) ? error.nativeStatus : undefined,
    nativeType: [
      'UnauthorizedAccessException',
      'IOException',
      'SecurityException',
      'Win32Exception',
      'ArgumentException',
    ].includes(error?.nativeType)
      ? error.nativeType
      : undefined,
    stack: (Array.isArray(error?.stack) ? error.stack.join('\n') : String(error?.stack ?? ''))
      .split('\n')
      .flatMap((line) => {
        const frame = line.match(/([^/\\():\s]+\.(?:js|mjs)):(\d+):(\d+)\)?$/);
        return frame && frames.has(frame[1]) ? [frame.slice(1).join(':')] : [];
      })
      .slice(0, 8),
  };
}

export function workerRecoveryState(scope, code) {
  if (code === 'WINDOWS_CONFIG_REFRESHED') return 'configuration-refreshed';
  if (code === 'WINDOWS_DESKTOP_NOT_RUNNING') return 'desktop-not-running';
  if (/^WINDOWS_(CLI_|HOST_CONFIG_)/.test(code ?? '')) return 'configuration-refresh-required';
  if (code === 'WINDOWS_POWERSHELL_MODULE_UNAVAILABLE') return 'helper-module-unavailable';
  if (['background-save', 'history-save', 'history-close'].includes(scope))
    return 'persistence-degraded';
  return 'runtime-failure';
}

/** One child at a time; only an observed exit authorizes the next launch. */
export function superviseWorker({
  executable,
  args,
  report,
  delays = [1000, 5000, 15_000, 60_000],
  healthyMs = 60_000,
  stopTimeoutMs = 10_000,
  probe,
  probeIntervalMs = 30_000,
}) {
  let child;
  let timer;
  let probeTimer;
  let stopping = false;
  let failures = 0;
  let resolveDone;
  const done = new Promise((resolve) => (resolveDone = resolve));
  const diagnostic = (entry) => {
    try {
      report(entry);
    } catch {
      console.error('HOST_DIAGNOSTICS_UNAVAILABLE');
    }
  };
  const launch = () => {
    if (stopping) return resolveDone();
    const started = Date.now();
    child = spawn(executable, args, {
      windowsHide: true,
      stdio: ['ignore', 'ignore', 'pipe', 'ipc'],
    });
    const launched = child;
    let healthState;
    const checkHealth = async () => {
      probeTimer = undefined;
      if (stopping || child !== launched) return;
      let state;
      try {
        state = await probe();
      } catch {
        state = 'http-unhealthy';
      }
      if (stopping || child !== launched) return;
      if (!['healthy', 'http-unhealthy', 'persistence-degraded'].includes(state))
        state = 'http-unhealthy';
      if (state !== healthState) {
        healthState = state;
        diagnostic({ event: 'health', childPid: launched.pid, state, recovery: 'diagnostic-only' });
      }
      probeTimer = setTimeout(checkHealth, probeIntervalMs);
    };
    if (probe) probeTimer = setTimeout(checkHealth, probeIntervalMs);
    diagnostic({ event: 'started', childPid: child.pid });
    let buffer = '';
    child.stderr.on('data', (chunk) => {
      buffer += chunk.toString('utf8');
      if (buffer.length > 16_384) buffer = '';
      const lines = buffer.split('\n');
      buffer = lines.pop();
      for (const line of lines) {
        try {
          const value = JSON.parse(line);
          if (value.diagnostic !== 'host-runtime-failure') continue;
          diagnostic({
            event: 'runtime-failure',
            childPid: child.pid,
            scope: identifier(value.scope),
            recoveryState: workerRecoveryState(value.scope, value.code),
            ...safeWorkerFailure(value),
            attempt: Number.isSafeInteger(value.attempt) ? value.attempt : undefined,
          });
        } catch {
          // Raw stdout, stderr, exception messages and configuration never enter logs.
        }
      }
    });
    child.on('error', (error) =>
      diagnostic({ event: 'spawn-failure', ...safeWorkerFailure(error) }),
    );
    child.once('close', (code, signal) => {
      diagnostic({
        event: 'exited',
        childPid: child.pid,
        exitCode: code,
        signal: identifier(signal),
      });
      child = undefined;
      if (probeTimer) clearTimeout(probeTimer);
      if (stopping) return resolveDone();
      failures = Date.now() - started >= healthyMs ? 0 : failures + 1;
      const delayMs = delays[Math.min(Math.max(0, failures - 1), delays.length - 1)];
      diagnostic({ event: 'retry', attempt: failures, delayMs });
      timer = setTimeout(launch, delayMs);
    });
  };
  launch();
  return {
    done,
    stop() {
      if (stopping) return done;
      stopping = true;
      if (timer) clearTimeout(timer);
      if (probeTimer) clearTimeout(probeTimer);
      diagnostic({ event: 'stopping' });
      if (!child) resolveDone();
      else {
        const active = child;
        // IPC lets the child flush its own state on Windows, where SIGTERM
        // alone is not a graceful shutdown request.
        if (active.connected) active.send({ command: 'host-worker-stop' }, () => {});
        const kill = setTimeout(() => active.kill(), stopTimeoutMs);
        active.once('close', () => clearTimeout(kill));
      }
      return done;
    },
  };
}
