import { execFile } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { windowsPowerShellEnvironment } from './desktop-platform.js';
import { withWindowsProcessCreation } from './windows-process.js';

export interface ResponseDesktopIdentity {
  pid: number;
  startedAtUnixMs: number;
  executableSha256: string;
}
export interface ResponsePeerIdentity extends ResponseDesktopIdentity {
  executorPid: number;
  executorStartedAtUnixMs: number;
  executorSha256: string;
}
export function responseDesktopKey(value: ResponseDesktopIdentity): string {
  return JSON.stringify([value.pid, value.startedAtUnixMs, value.executableSha256]);
}
/** Fixed native operation; never interpolate request data into executable code. */
export async function inspectResponseDesktop(peer?: {
  clientPort: number;
  listenerPort: number;
}): Promise<ResponsePeerIdentity> {
  if (process.platform !== 'win32') throw new Error('Native Windows required');
  const args = [
    '-NoProfile',
    '-NonInteractive',
    '-File',
    fileURLToPath(new URL('../windows/desktop-pipe.ps1', import.meta.url)),
    '-Action',
    peer ? 'response-peer' : 'inspect',
  ];
  if (peer) {
    if (
      ![peer.clientPort, peer.listenerPort].every((n) => Number.isInteger(n) && n > 0 && n <= 65535)
    )
      throw new Error('Invalid response peer');
    args.push(
      '-ClientPort',
      String(peer.clientPort),
      '-ListenerPort',
      String(peer.listenerPort),
      '-ForwarderPid',
      String(process.pid),
    );
  }
  const output = await new Promise<string>((resolve, reject) => {
    withWindowsProcessCreation(6000, (timeout) =>
      execFile(
        join(
          process.env.SystemRoot ?? 'C:\\Windows',
          'System32/WindowsPowerShell/v1.0/powershell.exe',
        ),
        args,
        {
          windowsHide: true,
          encoding: 'utf8',
          maxBuffer: 16384,
          timeout,
          env: windowsPowerShellEnvironment(),
        },
        (error, stdout) =>
          error ? reject(new Error('Original response peer unavailable')) : resolve(stdout),
      ),
    );
  });
  const value = JSON.parse(output) as ResponsePeerIdentity & {
    sameUser: boolean;
    sameSession: boolean;
  };
  const integer = (n: number) => Number.isSafeInteger(n) && n > 0;
  const digest = (s: string) => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s);
  if (
    !value.sameUser ||
    !value.sameSession ||
    !integer(value.pid) ||
    !integer(value.startedAtUnixMs) ||
    value.startedAtUnixMs > Date.now() ||
    !digest(value.executableSha256) ||
    (peer &&
      (!integer(value.executorPid) ||
        !integer(value.executorStartedAtUnixMs) ||
        value.executorStartedAtUnixMs < value.startedAtUnixMs ||
        !digest(value.executorSha256)))
  )
    throw new Error('Original response peer rejected');
  return value;
}
