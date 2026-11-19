import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

// XCTest's allowance starts after launch, so it cannot bound a stalled
// xcodebuild/Simulator handshake. Own the spawned group and its wall deadline.
export async function runNativeProcess(command, args, { timeoutMs, killGraceMs = 5000, onLine }) {
  const child = spawn(command, args, {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: process.env,
    detached: process.platform !== 'win32',
  });
  const signalChild = (signal) => {
    if (!child.pid) return;
    try {
      if (process.platform === 'win32') child.kill(signal);
      else process.kill(-child.pid, signal);
    } catch (error) {
      if (error.code !== 'ESRCH') throw error;
    }
  };
  let timedOut = false;
  let interruptedBy;
  let escalation;
  const terminate = () => {
    signalChild('SIGTERM');
    escalation ??= setTimeout(() => signalChild('SIGKILL'), killGraceMs);
  };
  const deadline = setTimeout(() => {
    timedOut = true;
    onLine(`Native wall deadline exceeded: ${timeoutMs}ms`);
    terminate();
  }, timeoutMs);
  const handlers = ['SIGINT', 'SIGTERM'].map((signal) => {
    const handler = () => {
      interruptedBy = signal;
      terminate();
    };
    process.once(signal, handler);
    return [signal, handler];
  });
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', onLine);
  try {
    const result = await new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('close', (exitCode, signal) => resolve({ exitCode, signal }));
    });
    return { ...result, timedOut, interruptedBy };
  } finally {
    clearTimeout(deadline);
    clearTimeout(escalation);
    if (timedOut || interruptedBy) signalChild('SIGKILL');
    for (const [signal, handler] of handlers) process.removeListener(signal, handler);
  }
}
