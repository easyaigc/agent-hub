import { execFileSync } from 'node:child_process';
import { existsSync, lstatSync, readFileSync, realpathSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMainThread } from 'node:worker_threads';
import {
  windowsPowerShellEnvironment,
  windowsSuggestionProducerIdentity,
  inspectWindowsInstallation,
  type WindowsDesktopIdentity,
} from './desktop-platform.js';
import { withWindowsProcessCreation } from './windows-process.js';

export interface NativeSuggestionProducerIdentity {
  pid: number;
  desktopPid: number;
  desktopStartedAt: number;
  wrapperStartedAt: number;
}
let implementationSha256: string | undefined;
let selfIdentity:
  | {
      configPath: string;
      configSha256: string;
      parentPid: number;
      identity: NativeSuggestionProducerIdentity;
    }
  | undefined;
const configHash = (path: string) => createHash('sha256').update(readFileSync(path)).digest('hex');
/** Capture once per loaded producer, so replacing files never blesses old in-memory code. */
export function nativeSuggestionImplementationFingerprint(): string {
  if (implementationSha256) return implementationSha256;
  const directory = dirname(fileURLToPath(import.meta.url));
  const files: Record<string, string> = {};
  for (const name of readdirSync(directory)
    .filter((n) => n.endsWith('.js'))
    .sort())
    files[name] = createHash('sha256')
      .update(readFileSync(join(directory, name)))
      .digest('hex');
  implementationSha256 = createHash('sha256').update(JSON.stringify(files)).digest('hex');
  return implementationSha256;
}
/** Original launcher startup only; no dependency on an as-yet uninitialized IPC pipe. */
export function resolveNativeSuggestionExecutable(configPath: string, configured?: string): string {
  if (process.platform !== 'win32' || !isMainThread)
    throw new Error('Original Windows launcher required');
  const helper = fileURLToPath(
    new URL('../windows/desktop-native-sample-process.ps1', import.meta.url),
  );
  const before = configHash(configPath);
  const raw = withWindowsProcessCreation(15000, (timeout) =>
    execFileSync(
      join(
        process.env.SystemRoot ?? 'C:\\Windows',
        'System32/WindowsPowerShell/v1.0/powershell.exe',
      ),
      [
        '-NoProfile',
        '-NonInteractive',
        '-File',
        helper,
        '-ProducerPid',
        String(process.pid),
        '-Config',
        configPath,
        '-Action',
        'installation',
      ],
      {
        encoding: 'utf8',
        timeout,
        maxBuffer: 8192,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe'],
        env: windowsPowerShellEnvironment(),
      },
    ),
  );
  const value = JSON.parse(raw) as {
    producer: NativeSuggestionProducerIdentity;
    desktop: WindowsDesktopIdentity & { trustedPublisher?: boolean };
  };
  if (
    value.producer?.pid !== process.pid ||
    value.desktop?.pid !== value.producer.desktopPid ||
    value.desktop.startedAtUnixMs !== value.producer.desktopStartedAt ||
    !value.desktop.sameUser ||
    !value.desktop.sameSession ||
    !value.desktop.trustedPublisher ||
    !/^[a-f0-9]{64}$/.test(value.desktop.executableSha256)
  )
    throw new Error('Original desktop installation changed');
  if (configHash(configPath) !== before) throw new Error('Original launcher configuration changed');
  selfIdentity = {
    configPath,
    configSha256: before,
    parentPid: process.ppid,
    identity: value.producer,
  };
  return inspectWindowsInstallation(configured, value.desktop).executable;
}

/** Fixed native process reads only. No RPC, model input, process control or environment reads. */
export function inspectNativeSuggestionProducer(
  pid: number,
  configPath: string,
): NativeSuggestionProducerIdentity {
  if (!Number.isSafeInteger(pid) || pid <= 1 || !isAbsolute(configPath))
    throw new Error('Invalid native producer');
  const stat = lstatSync(configPath);
  if (
    !stat.isFile() ||
    stat.isSymbolicLink() ||
    stat.nlink !== 1 ||
    stat.size > 16384 ||
    realpathSync(configPath) !== configPath
  )
    throw new Error('Invalid native producer config');
  let raw: string;
  if (process.platform === 'darwin') {
    if (stat.uid !== process.getuid?.() || stat.mode & 0o077)
      throw new Error('Private native config required');
    const config = JSON.parse(readFileSync(configPath, 'utf8'));
    if (typeof config.applicationPath !== 'string' || !isAbsolute(config.applicationPath))
      throw new Error('Original application required');
    const root = lstatSync(config.traceRoot);
    if (
      !root.isDirectory() ||
      root.isSymbolicLink() ||
      root.uid !== process.getuid?.() ||
      root.mode & 0o077 ||
      realpathSync(config.traceRoot) !== config.traceRoot
    )
      throw new Error('Private native root required');
    const app = config.applicationPath.endsWith('.app')
      ? config.applicationPath
      : dirname(dirname(dirname(config.applicationPath)));
    const packaged = fileURLToPath(
      new URL('../resources/desktop-completion-notify-observer.py', import.meta.url),
    );
    const helper = existsSync(packaged)
      ? packaged
      : fileURLToPath(
          new URL('../../../scripts/desktop-completion-notify-observer.py', import.meta.url),
        );
    raw = execFileSync(
      '/usr/bin/python3',
      [
        '-I',
        helper,
        '--output-dir',
        config.traceRoot,
        '--application-path',
        app,
        '--inspect-producer',
        String(pid),
      ],
      {
        encoding: 'utf8',
        timeout: 3000,
        maxBuffer: 8192,
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    );
  } else if (process.platform === 'win32') {
    // Reader workers reuse their registered, retained native owner. Only the
    // actual desktop's main-thread launcher uses the fixed bootstrap probe.
    if (!isMainThread) return windowsSuggestionProducerIdentity(pid, configPath);
    // The producer's own process cannot reuse its PID. Bootstrap was checked
    // against the native parent; consumers still recheck retained kernel
    // identities on every read. Avoid a PS/CIM process on each heartbeat.
    const fingerprint = configHash(configPath);
    if (
      pid === process.pid &&
      selfIdentity?.configPath === configPath &&
      selfIdentity.configSha256 === fingerprint &&
      selfIdentity.parentPid === process.ppid
    )
      return selfIdentity.identity;
    const helper = fileURLToPath(
      new URL('../windows/desktop-native-sample-process.ps1', import.meta.url),
    );
    raw = withWindowsProcessCreation(10000, (timeout) =>
      execFileSync(
        join(
          process.env.SystemRoot ?? 'C:\\Windows',
          'System32/WindowsPowerShell/v1.0/powershell.exe',
        ),
        [
          '-NoProfile',
          '-NonInteractive',
          '-File',
          helper,
          '-ProducerPid',
          String(pid),
          '-Config',
          configPath,
        ],
        {
          encoding: 'utf8',
          timeout,
          maxBuffer: 8192,
          windowsHide: true,
          stdio: ['ignore', 'pipe', 'pipe'],
          env: windowsPowerShellEnvironment(),
        },
      ),
    );
  } else throw new Error('Native suggestions unavailable on this platform');
  const value = JSON.parse(raw) as NativeSuggestionProducerIdentity;
  if (
    value.pid !== pid ||
    !Number.isSafeInteger(value.desktopPid) ||
    value.desktopPid <= 1 ||
    !Number.isSafeInteger(value.desktopStartedAt) ||
    value.desktopStartedAt <= 0 ||
    !Number.isSafeInteger(value.wrapperStartedAt) ||
    value.wrapperStartedAt < value.desktopStartedAt ||
    value.wrapperStartedAt > Date.now()
  )
    throw new Error('Native producer identity changed');
  if (process.platform === 'win32' && isMainThread && pid === process.pid)
    selfIdentity = {
      configPath,
      configSha256: configHash(configPath),
      parentPid: process.ppid,
      identity: value,
    };
  return value;
}
