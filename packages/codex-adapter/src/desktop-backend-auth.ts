import { withWindowsProcessCreation } from './windows-process.js';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

/** Official metadata-only auth provider. Fixed methods; no login, refresh, Turn or tool execution. */
export async function readDesktopBackendAuth(executable: string, codexHome: string) {
  let startupTimeout = 15000;
  const child = withWindowsProcessCreation(15000, (remaining) => {
    startupTimeout = remaining;
    return spawn(executable, ['app-server', '--stdio'], {
      windowsHide: true,
      stdio: 'pipe',
      env: { ...process.env, CODEX_HOME: codexHome },
    });
  });
  child.stderr.resume();
  let id = 0,
    bytes = 0;
  const pending = new Map<
    number,
    { resolve: (value: Record<string, unknown>) => void; reject: (error: Error) => void }
  >();
  const fail = () => {
    for (const request of pending.values())
      request.reject(new Error('Desktop auth metadata unavailable'));
    pending.clear();
  };
  const timer = setTimeout(() => {
    fail();
    child.kill();
  }, startupTimeout);
  const lines = createInterface({ input: child.stdout });
  lines.on('line', (line) => {
    bytes += Buffer.byteLength(line);
    if (bytes > 2_000_000) {
      fail();
      child.kill();
      return;
    }
    try {
      const message = JSON.parse(line) as {
        id?: number;
        error?: unknown;
        result?: Record<string, unknown>;
      };
      const request = message.id === undefined ? undefined : pending.get(message.id);
      if (!request || message.id === undefined) return;
      pending.delete(message.id);
      if (message.error || !message.result || typeof message.result !== 'object')
        request.reject(new Error('Desktop auth read rejected'));
      else request.resolve(message.result);
    } catch {
      fail();
      child.kill();
    }
  });
  child.on('error', fail);
  child.stdin.on('error', fail);
  child.on('exit', fail);
  const call = (method: string, params: Record<string, unknown>) => {
    if (
      ![
        'initialize',
        'getAuthStatus',
        'account/read',
        'config/read',
        'configRequirements/read',
      ].includes(method)
    )
      throw new Error('Auth metadata method required');
    return new Promise<Record<string, unknown>>((resolve, reject) => {
      const requestId = ++id;
      pending.set(requestId, { resolve, reject });
      child.stdin.write(JSON.stringify({ id: requestId, method, params }) + '\n', (error) => {
        if (error) {
          pending.delete(requestId);
          reject(new Error('Desktop auth transport unavailable'));
        }
      });
    });
  };
  let result: Record<'auth' | 'account' | 'config' | 'requirements', Record<string, unknown>>;
  let exited: boolean;
  try {
    await call('initialize', {
      clientInfo: { name: 'codex_manage_voice_auth', version: '0.1.0' },
      capabilities: { experimentalApi: true },
    });
    child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
    const auth = await call('getAuthStatus', { includeToken: true, refreshToken: false });
    const account = await call('account/read', { refreshToken: false });
    const config = await call('config/read', { includeLayers: false });
    const requirements = await call('configRequirements/read', {});
    result = { auth, account, config, requirements };
  } finally {
    clearTimeout(timer);
    lines.close();
    child.stdin.end();
    // Never leave an auth helper running alongside the original desktop.
    const waitForExit = (timeout: number) =>
      new Promise<boolean>((resolve) => {
        if (child.exitCode !== null || child.signalCode !== null) {
          resolve(true);
          return;
        }
        const exited = () => {
          clearTimeout(timer);
          resolve(true);
        };
        const timer = setTimeout(() => {
          child.off('exit', exited);
          resolve(false);
        }, timeout);
        child.once('exit', exited);
      });
    exited = await waitForExit(2000);
    if (!exited) {
      child.kill('SIGKILL');
      exited = await waitForExit(2000);
    }
    fail();
  }
  if (!exited) throw new Error('Desktop auth helper exit could not be confirmed');
  return result;
}
