import { diagnosticEvent } from './diagnostic-events.js';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  closeSync,
  constants,
  existsSync,
  fstatSync,
  lstatSync,
  mkdirSync,
  openSync,
  readSync,
  readFileSync,
  readdirSync,
  realpathSync,
  renameSync,
  rmSync,
  statSync,
} from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { lstat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { Duplex } from 'node:stream';
import { isMainThread, threadId } from 'node:worker_threads';
import { createWindowsPrivateStateBackend } from './desktop-private-state-native.js';
import { withWindowsProcessCreation } from './windows-process.js';
import type { TraceSnapshotItem, TraceSnapshotValue } from './desktop-trace-native.js';

// Observed builds are a validation record, not an allowlist for starting the Host.
export const desktopBuilds = ['26.930.61225', '26.1002.52244'];

/** Optional acceptance metadata. Build labels never decide runtime availability. */
export function readMacDesktopVersion(): string {
  try {
    return execFileSync(
      '/usr/libexec/PlistBuddy',
      ['-c', 'Print :CFBundleShortVersionString', '/Applications/ChatGPT.app/Contents/Info.plist'],
      { encoding: 'utf8', stdio: 'pipe', timeout: 3000 },
    ).trim();
  } catch {
    return '';
  }
}

export const windowsDesktopPipe = String.raw`\\.\pipe\codex-ipc`;
const windowsHelper = (name: string) =>
  fileURLToPath(new URL('../windows/' + name, import.meta.url));
const powershell = () =>
  join(process.env.SystemRoot ?? 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe');

/** Remove PS7 module paths only from the PS5 child environment.
 * https://learn.microsoft.com/powershell/module/microsoft.powershell.core/about/about_psmodulepath
 */
export function windowsPowerShellEnvironment(environment = process.env): NodeJS.ProcessEnv {
  return Object.fromEntries(
    Object.entries(environment).filter(([key]) => key.toUpperCase() !== 'PSMODULEPATH'),
  );
}

function windowsPowerShellSync(args: string[], active?: () => boolean): string {
  try {
    return withWindowsProcessCreation(
      30_000,
      (timeout) => {
        if (active && !active()) throw new Error('Native owner closed before bootstrap');
        return execFileSync(powershell(), args, {
          encoding: 'utf8',
          stdio: 'pipe',
          timeout,
          windowsHide: true,
          env: windowsPowerShellEnvironment(),
        });
      },
      active,
    );
  } catch (cause) {
    const stderr = String((cause as { stderr?: unknown }).stderr ?? '');
    const code =
      stderr.match(
        /^(WINDOWS_POWERSHELL_MODULE_UNAVAILABLE|WINDOWS_DESKTOP_NOT_RUNNING|WINDOWS_DESKTOP_PIPE_REJECTED|WRONG_DESKTOP_USER|WRONG_DESKTOP_SESSION|WINDOWS_CLI_REJECTED|UNSAFE_WINDOWS_PRIVATE_STATE)\s*$/m,
      )?.[1] ?? 'WINDOWS_HELPER_FAILED';
    throw Object.assign(new Error('Windows helper failed', { cause }), { code });
  }
}

/** Verify the discovered CLI without launching it or submitting model input. */
export function inspectWindowsCli(path: string): { sha256: string } {
  const value = JSON.parse(
    windowsPowerShellSync([
      '-NoProfile',
      '-NonInteractive',
      '-File',
      windowsHelper('desktop-cli.ps1'),
      '-Path',
      path,
    ]),
  );
  if (!/^[a-f0-9]{64}$/.test(value.sha256)) throw new Error('Invalid Windows CLI identity');
  return value;
}

const nativeExecutables = new Map<string, string>();
function windowsPrivateStateExecutable(
  name: 'private-state' | 'trace-state' | 'host-launcher' = 'private-state',
  active?: () => boolean,
): string {
  if (active && !active()) throw new Error('Native owner closed before bootstrap');
  if (!process.env.LOCALAPPDATA) throw new Error('Windows local user data directory missing');
  const cacheKey = name + '\n' + process.env.LOCALAPPDATA;
  const cached = nativeExecutables.get(cacheKey);
  if (cached) return cached;
  const source = windowsHelper(name + '.cs');
  const sources = name === 'trace-state' ? [source, windowsHelper('trace-process.cs')] : [source];
  const hash = createHash('sha256');
  for (const path of sources) hash.update(readFileSync(path));
  const digest = hash.digest('hex');
  const directory = join(process.env.LOCALAPPDATA, 'CodexManage/native-helpers', digest);
  mkdirSync(directory, { recursive: true });
  // Bootstrap is performed once per helper isolate. Per-snapshot writes then
  // use the same fixed native ACL implementation without starting PowerShell.
  const bootstrap = (action: string, path: string) =>
    windowsPowerShellSync(
      [
        '-NoProfile',
        '-NonInteractive',
        '-File',
        windowsHelper('private-state.ps1'),
        '-Action',
        action,
        '-Path',
        path,
      ],
      active,
    );
  const executable = join(directory, name + '.exe');
  if (!existsSync(executable)) {
    bootstrap('protect', directory);
    const system = process.env.SystemRoot ?? 'C:\\Windows';
    const compiler = ['Framework64', 'Framework']
      .map((name) => join(system, 'Microsoft.NET', name, 'v4.0.30319/csc.exe'))
      .find((path) => existsSync(path));
    if (!compiler) throw new Error('Installed Windows .NET Framework compiler missing');
    const temporary = executable + '.' + process.pid + '.' + threadId + '.tmp.exe';
    withWindowsProcessCreation(
      30_000,
      (timeout) => {
        if (active && !active()) throw new Error('Native owner closed before compilation');
        return execFileSync(
          compiler,
          [
            '/nologo',
            '/target:' + (name === 'host-launcher' ? 'winexe' : 'exe'),
            '/optimize+',
            ...(name === 'host-launcher' ? [] : ['/r:System.Web.Extensions.dll']),
            '/out:' + temporary,
            ...sources,
          ],
          {
            stdio: 'pipe',
            timeout,
            windowsHide: true,
          },
        );
      },
      active,
    );
    bootstrap('protect-file', temporary);
    try {
      renameSync(temporary, executable);
    } catch (error) {
      if (!existsSync(executable)) throw error;
      // Another service may have compiled the same immutable source first.
      // Validate the winning private binary below; remove only our own temp.
      rmSync(temporary);
    }
  }
  // Resolve the actual executable for callers outside an MSIX virtual view.
  // The logical and physical files both retain the original private ACL check.
  const physical = realpathSync.native(executable);
  if (basename(physical).toLowerCase() !== name + '.exe' || basename(dirname(physical)) !== digest)
    throw new Error('Unsafe Windows native helper path');
  // Every isolate still protects/checks the directory, logical binary and
  // physical binary freshly. Batch the fixed operations to avoid paying three
  // PowerShell process startups for a previously compiled native helper.
  windowsPowerShellSync(
    [
      '-NoProfile',
      '-NonInteractive',
      '-File',
      windowsHelper('private-state.ps1'),
      '-Action',
      'bootstrap-native',
      '-Path',
      directory,
      '-BinaryPath',
      executable,
      '-PhysicalPath',
      physical,
    ],
    active,
  );
  if (realpathSync.native(executable) !== physical)
    throw new Error('Windows native helper namespace changed during bootstrap');
  nativeExecutables.set(cacheKey, physical);
  return physical;
}
export function windowsHostLauncherExecutable(): string {
  return windowsPrivateStateExecutable('host-launcher');
}
/** Only the private-state transport Worker bootstraps this fixed local binary. */
export function windowsPrivateStateNativeExecutable(active?: () => boolean): string {
  if (isMainThread || process.platform !== 'win32')
    throw new Error('Private state worker required');
  return windowsPrivateStateExecutable('private-state', active);
}
let privateStateBackend: ReturnType<typeof createWindowsPrivateStateBackend> | undefined;
let privateStateAsyncBackend: ReturnType<typeof createWindowsPrivateStateBackend> | undefined;
let privateStateAsyncQueue: Promise<void> = Promise.resolve();
let privateStateAsyncGeneration = 0;
let privateStateQueued = 0;
function privateState(action: 'protect' | 'protect-file' | 'check', path: string): void {
  // A later independent call may create a new channel only after confirmed child exit.
  // Never retry this call, including when its operation may already have executed.
  if (privateStateBackend?.status.gone) privateStateBackend = undefined;
  privateStateBackend ??= createWindowsPrivateStateBackend();
  privateStateBackend.call(action, path);
}
export function closeWindowsPrivateStateBackend(): void {
  privateStateAsyncGeneration++;
  try {
    privateStateBackend?.close();
  } finally {
    privateStateAsyncBackend?.close();
  }
}

/** Explicit, verified aliases for the same product's helper storage only. */
export function windowsHostLauncherConfiguration(): { executable: string; roots: string[] } {
  const executable = windowsHostLauncherExecutable();
  const digest = basename(dirname(executable));
  const local = process.env.LOCALAPPDATA!;
  const roots = new Set([dirname(dirname(executable))]);
  // This is the fixed Store package family, not a version/account gate. Never
  // enumerate other packages or accept a root supplied by an existing task.
  const candidates = [
    join(local, 'CodexManage/native-helpers'),
    join(local, 'Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/CodexManage/native-helpers'),
  ];
  for (const root of candidates) {
    const file = join(root, digest, 'host-launcher.exe');
    if (!existsSync(file)) continue;
    const physical = realpathSync.native(file);
    if (
      basename(physical).toLowerCase() !== 'host-launcher.exe' ||
      basename(dirname(physical)) !== digest
    )
      throw new Error('Unsafe Windows native helper alias');
    // Check both views before allowing the alias. Existing digest directories
    // keep their DACLs; recognizing a legacy path never repairs unsafe ACLs.
    for (const path of new Set([dirname(file), file, dirname(physical), physical]))
      privateState('check', path);
    roots.add(root);
    roots.add(dirname(dirname(physical)));
  }
  return { executable, roots: [...roots] };
}

export function assertPrivateFile(path: string): void {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Unsafe private file');
  if (process.platform === 'win32') {
    privateState('check', path);
  } else if (stat.uid !== process.getuid?.() || stat.mode & 0o022)
    throw new Error('Unsafe private file');
}

export function protectWindowsStateDirectory(path: string): void {
  if (process.platform === 'win32') privateState('protect', path);
}

export function protectWindowsStateFile(path: string): void {
  if (process.platform === 'win32') privateState('protect-file', path);
}

/** Independent channel: explicit synchronous barriers never share its in-flight slot. */
export function protectWindowsStateFileAsync(path: string): Promise<void> {
  return privateStateAsync('protect-file', path);
}

function privateStateAsync(action: 'protect-file' | 'check', path: string): Promise<void> {
  if (process.platform !== 'win32') return Promise.resolve();
  const generation = privateStateAsyncGeneration;
  const queuedAt = performance.now();
  privateStateQueued++;
  const call = privateStateAsyncQueue.then(async () => {
    diagnosticEvent({
      scope: 'private-queue',
      stage: 'dispatch',
      pending: privateStateQueued,
      queueMs: performance.now() - queuedAt,
    });
    if (generation !== privateStateAsyncGeneration)
      throw Object.assign(new Error('Windows private state unavailable'), {
        code: 'WINDOWS_PRIVATE_STATE_UNAVAILABLE',
      });
    if (privateStateAsyncBackend?.status.gone) privateStateAsyncBackend = undefined;
    privateStateAsyncBackend ??= createWindowsPrivateStateBackend();
    await privateStateAsyncBackend.callAsync(action, path);
  });
  privateStateAsyncQueue = call
    .finally(() => {
      privateStateQueued--;
    })
    .catch(() => {
      // A later independent operation may proceed; this caller still receives its rejection.
    });
  return call;
}

export async function assertPrivateFileAsync(path: string): Promise<void> {
  const stat = await lstat(path);
  if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Unsafe private file');
  if (process.platform === 'win32') await privateStateAsync('check', path);
  else if (stat.uid !== process.getuid?.() || stat.mode & 0o022)
    throw new Error('Unsafe private file');
}

/** Handle-based Windows trace read. Never uses POSIX mode bits as an ACL check. */
export function windowsTraceSnapshot(
  root: string,
  path: string,
  maximum = 0,
): {
  identity: string;
  chain: string[];
  bytes: Buffer;
} {
  if (process.platform !== 'win32') throw new Error('Native Windows required');
  if (traceBackend) return traceBackend.snapshot(root, path, maximum);
  // Bootstrap may itself create processes; resolve it before taking the leaf lock.
  const executable = windowsPrivateStateExecutable('trace-state');
  const result = JSON.parse(
    withWindowsProcessCreation(10_000, (timeout) =>
      execFileSync(executable, ['snapshot', root, path, String(maximum)], {
        encoding: 'utf8',
        stdio: 'pipe',
        timeout,
        windowsHide: true,
        maxBuffer: 48 * 1024 * 1024,
      }),
    ),
  );
  if (
    typeof result.identity !== 'string' ||
    typeof result.bytes !== 'string' ||
    !Array.isArray(result.chain) ||
    result.chain.some((v: unknown) => typeof v !== 'string')
  )
    throw new Error('Invalid Windows trace snapshot');
  return {
    identity: result.identity,
    chain: result.chain,
    bytes: Buffer.from(result.bytes, 'base64'),
  };
}

export interface WindowsDesktopIdentity {
  pid: number;
  startedAtUnixMs: number;
  executable: string;
  executableSha256: string;
  userSid: string;
  sameUser: boolean;
  sameSession: boolean;
}

export function inspectWindowsPipe(): WindowsDesktopIdentity {
  if (process.platform !== 'win32') throw new Error('Native Windows required');
  const value: WindowsDesktopIdentity = JSON.parse(
    windowsPowerShellSync([
      '-NoProfile',
      '-NonInteractive',
      '-File',
      windowsHelper('desktop-pipe.ps1'),
      '-Action',
      'inspect',
    ]),
  );
  if (
    !value.sameUser ||
    !value.sameSession ||
    !Number.isSafeInteger(value.pid) ||
    value.pid <= 0 ||
    !Number.isSafeInteger(value.startedAtUnixMs) ||
    value.startedAtUnixMs <= 0 ||
    value.startedAtUnixMs > Date.now() ||
    !/^[a-f0-9]{64}$/.test(value.executableSha256)
  )
    throw new Error('Invalid Windows desktop identity');
  return value;
}

export interface WindowsTraceInstance {
  pid: number;
  startedAtUnixMs: number;
  traceRoot: string | null;
  executors: {
    pid: number;
    parentPid: number;
    startedAtUnixMs: number;
    traceRoot: string | null;
  }[];
}

/** Tie trace readiness to the authenticated current pipe owner and its executors. */
export function inspectWindowsTraceInstance(): WindowsTraceInstance {
  if (traceBackend) return traceBackend.inspect();
  const value: WindowsTraceInstance = JSON.parse(
    windowsPowerShellSync([
      '-NoProfile',
      '-NonInteractive',
      '-File',
      windowsHelper('desktop-pipe.ps1'),
      '-Action',
      'trace',
    ]),
  );
  if (
    !Number.isSafeInteger(value.pid) ||
    value.pid <= 0 ||
    !Number.isSafeInteger(value.startedAtUnixMs) ||
    value.startedAtUnixMs <= 0 ||
    value.startedAtUnixMs > Date.now() ||
    !Array.isArray(value.executors) ||
    value.executors.length === 0 ||
    value.executors.some(
      (e) =>
        e.parentPid !== value.pid ||
        !Number.isSafeInteger(e.pid) ||
        !Number.isSafeInteger(e.startedAtUnixMs) ||
        e.startedAtUnixMs < value.startedAtUnixMs ||
        e.startedAtUnixMs > Date.now(),
    )
  )
    throw new Error('Windows trace instance changed');
  return value;
}

export type WindowsTraceBackend = {
  snapshot: typeof windowsTraceSnapshot;
  snapshotMany?: (root: string, items: readonly TraceSnapshotItem[]) => TraceSnapshotValue[];
  inspect: () => WindowsTraceInstance;
};
let traceBackend: WindowsTraceBackend | undefined;
export function windowsTraceSnapshotBatchAvailable(): boolean {
  return process.platform === 'win32' && traceBackend?.snapshotMany !== undefined;
}
export function windowsTraceSnapshotMany(
  root: string,
  items: readonly TraceSnapshotItem[],
): TraceSnapshotValue[] {
  if (!windowsTraceSnapshotBatchAvailable()) throw new Error('Native trace batch required');
  return traceBackend!.snapshotMany!(root, items);
}
/** Internal worker boundary; never configurable through HTTP, env or RPC. */
export function installWindowsTraceBackend(backend: WindowsTraceBackend): void {
  if (isMainThread || process.platform !== 'win32') throw new Error('Trace worker required');
  traceBackend = backend;
}
export function windowsTraceNativeExecutable(active?: () => boolean): string {
  if (isMainThread || process.platform !== 'win32') throw new Error('Trace worker required');
  return windowsPrivateStateExecutable('trace-state', active);
}

export function windowsTraceInstanceReady(value: WindowsTraceInstance, root: string): boolean {
  return (
    typeof value.traceRoot === 'string' &&
    value.traceRoot.toLowerCase() === root.toLowerCase() &&
    value.executors.length > 0 &&
    value.executors.every(
      (e) => typeof e.traceRoot === 'string' && e.traceRoot.toLowerCase() === root.toLowerCase(),
    )
  );
}

/** Inspect a user's installed archive, never modify or execute its contents. */
export function inspectDesktopArchive(path: string): { version: string; asarSha256: string } {
  const physical = realpathSync.native(path);
  const before = statSync(physical, { bigint: true });
  if (!before.isFile() || before.size < 16n || before.size > BigInt(Number.MAX_SAFE_INTEGER))
    throw new Error('Invalid desktop archive');
  const same = (value: typeof before) =>
    value.dev === before.dev &&
    value.ino === before.ino &&
    value.mode === before.mode &&
    value.nlink === before.nlink &&
    value.uid === before.uid &&
    value.gid === before.gid &&
    value.size === before.size &&
    value.mtimeNs === before.mtimeNs &&
    value.ctimeNs === before.ctimeNs &&
    value.birthtimeNs === before.birthtimeNs;
  const fd = openSync(physical, constants.O_RDONLY | (constants.O_NONBLOCK ?? 0));
  try {
    if (!same(fstatSync(fd, { bigint: true })))
      throw new Error('Desktop archive changed during inspection');
    const length = Number(before.size);
    const exact = (offset: number, size: number) => {
      const bytes = Buffer.alloc(size);
      let done = 0;
      while (done < size) {
        const count = readSync(fd, bytes, done, size - done, offset + done);
        if (!count) throw new Error('Desktop archive changed or truncated during inspection');
        done += count;
      }
      return bytes;
    };
    const prefix = exact(0, 16);
    const headerSize = prefix.readUInt32LE(4),
      jsonSize = prefix.readUInt32LE(12);
    if (headerSize > 32 * 1024 * 1024 || jsonSize > headerSize - 8 || 8 + headerSize > length)
      throw new Error('Invalid desktop archive header');
    interface Entry {
      files?: Record<string, Entry>;
      offset?: string;
      size?: number;
      unpacked?: boolean;
      link?: string;
    }
    const tree: Entry = JSON.parse(exact(16, jsonSize).toString('utf8'));
    const read = (entry: Entry | undefined) => {
      if (
        !entry ||
        entry.unpacked ||
        entry.link ||
        entry.offset === undefined ||
        !Number.isSafeInteger(entry.size)
      )
        throw new Error('Unsupported archive entry');
      const offset = 8 + headerSize + Number(entry.offset),
        size = entry.size!;
      if (
        !Number.isSafeInteger(offset) ||
        offset < 8 + headerSize ||
        size < 0 ||
        !Number.isSafeInteger(offset + size) ||
        offset + size > length
      )
        throw new Error('Invalid archive entry');
      if (size > 8 * 1024 * 1024) throw new Error('Unsupported archive entry size');
      return exact(offset, size).toString('utf8');
    };
    const version: unknown = JSON.parse(read(tree.files?.['package.json'])).version;
    const entries = tree.files?.['.vite']?.files?.build?.files ?? {};
    let transport = false,
      profile = false;
    for (const [name, entry] of Object.entries(entries)) {
      if (!name.endsWith('.js') || (entry.size ?? 0) >= 8 * 1024 * 1024) continue;
      const value = read(entry);
      transport ||=
        value.includes('codex-ipc') && value.includes('win32') && value.includes('ipc.sock');
      profile ||=
        /"thread-stream-state-changed":11/.test(value) &&
        /"thread-follower-start-turn":2/.test(value) &&
        /"thread-follower-interrupt-turn":4/.test(value);
      // Keep validating later selected entries even after finding the profile.
    }
    if (!transport || !profile) throw new Error('Desktop protocol profile not recognized');
    const hash = createHash('sha256');
    const chunk = Buffer.alloc(64 * 1024);
    for (let offset = 0; offset < length;) {
      const count = readSync(fd, chunk, 0, Math.min(chunk.length, length - offset), offset);
      if (!count) throw new Error('Desktop archive changed or truncated during inspection');
      hash.update(chunk.subarray(0, count));
      offset += count;
    }
    if (
      !same(fstatSync(fd, { bigint: true })) ||
      realpathSync.native(path) !== physical ||
      !same(statSync(physical, { bigint: true }))
    )
      throw new Error('Desktop archive changed during inspection');
    return {
      version: typeof version === 'string' ? version : '',
      asarSha256: hash.digest('hex'),
    };
  } finally {
    closeSync(fd);
  }
}

const windowsInstallationFailure = (code: string) =>
  Object.assign(new Error('Windows desktop installation unavailable'), { code });

/** Bounded discovery, never PATH search, shell execution, or desktop restart. */
export function discoverWindowsDesktopCli(
  identity: WindowsDesktopIdentity,
  configured?: string,
  localData = process.env.LOCALAPPDATA,
) {
  const ownerDirectory = dirname(identity.executable);
  const adjacent = [
    join(ownerDirectory, 'resources/codex.exe'),
    join(ownerDirectory, 'bin/codex.exe'),
  ];
  const bin = localData && join(localData, 'OpenAI/Codex/bin');
  const candidates = [...adjacent, configured, bin && join(bin, 'codex.exe')];
  if (bin && existsSync(bin)) {
    const entries = readdirSync(bin, { withFileTypes: true });
    if (entries.length > 128) throw windowsInstallationFailure('WINDOWS_CLI_DISCOVERY_LIMIT');
    for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name)))
      if (entry.isDirectory() && /^[a-f0-9]{8,64}$/i.test(entry.name))
        candidates.push(join(bin, entry.name, 'codex.exe'));
  }
  const seen = new Set();
  const trusted = [];
  for (const candidate of candidates) {
    if (!candidate || !existsSync(candidate)) continue;
    const path = resolve(candidate);
    if (seen.has(path.toLowerCase())) continue;
    seen.add(path.toLowerCase());
    if (!lstatSync(path).isFile() || lstatSync(path).isSymbolicLink()) continue;
    try {
      trusted.push({ path, ...inspectWindowsCli(path) });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'WINDOWS_CLI_REJECTED') throw error;
    }
  }
  // Prefer a CLI physically shipped with this verified owner. Multiple different
  // loose payloads are ambiguous; don't guess by modification time or version.
  const bundled = trusted.filter((v) =>
    adjacent.some((p) => resolve(p).toLowerCase() === v.path.toLowerCase()),
  );
  const choices = bundled.length ? bundled : trusted;
  if (!choices.length) throw windowsInstallationFailure('WINDOWS_CLI_NOT_FOUND');
  const configuredChoice =
    configured &&
    choices.find((value) => value.path.toLowerCase() === resolve(configured).toLowerCase());
  if (configuredChoice) return configuredChoice.path;
  if (new Set(choices.map((v) => v.sha256)).size !== 1)
    throw windowsInstallationFailure('WINDOWS_CLI_AMBIGUOUS');
  return choices[0]!.path;
}

export function inspectWindowsInstallation(configured?: string, identity = inspectWindowsPipe()) {
  const archivePath = join(dirname(identity.executable), 'resources/app.asar');
  const archive = inspectDesktopArchive(archivePath);
  const executable = discoverWindowsDesktopCli(identity, configured);
  return { identity, archivePath, archive, executable };
}

export interface WindowsDesktopConfiguration {
  executable: string;
  archive?: string;
  /** Optional historical metadata, never an installation requirement. */
  desktopVersion?: string;
  /** Legacy deployment fields are retained as metadata, never a reply allowlist. */
  validationOnly?: boolean;
  validationThreadId?: string;
  verification?: string;
}

export function loadWindowsDesktop(
  path: string,
  hostId: string,
  refresh?: { executable?: string | undefined },
): {
  config: WindowsDesktopConfiguration;
  executable?: string;
  identity: WindowsDesktopIdentity;
  archive: { version: string; asarSha256: string };
  verificationStatus: 'not-run' | 'passed' | 'stale' | 'unavailable';
} {
  assertPrivateFile(path);
  const config: WindowsDesktopConfiguration = JSON.parse(readFileSync(path, 'utf8'));
  if (typeof config.executable !== 'string' || !config.executable)
    throw new Error('Windows desktop configuration required');
  const identity = inspectWindowsPipe();
  if (!refresh && identity.executable.toLowerCase() !== config.executable.toLowerCase())
    throw new Error('Wrong Windows desktop executable');
  // A private stored path is a startup hint. During reconnection resolve the
  // actual same-user/session pipe owner, its adjacent protocol archive
  // and a trusted metadata CLI again. No configuration or task input is written.
  const installed = refresh ? inspectWindowsInstallation(refresh.executable, identity) : undefined;
  const archive =
    installed?.archive ??
    inspectDesktopArchive(config.archive ?? join(dirname(config.executable), 'resources/app.asar'));
  // Acceptance is evidence for reporting, not permission to use an actual owner.
  let verificationStatus: 'not-run' | 'passed' | 'stale' | 'unavailable' = 'not-run';
  if (config.verification) {
    try {
      assertPrivateFile(config.verification);
      const proof = JSON.parse(readFileSync(config.verification, 'utf8'));
      verificationStatus =
        proof.kind === 'real-windows-desktop' &&
        proof.hostId === hostId &&
        proof.desktopVersion === archive.version &&
        proof.executableSha256 === identity.executableSha256 &&
        proof.asarSha256 === archive.asarSha256 &&
        proof.adapterSha256 === windowsAdapterDigest() &&
        Number.isFinite(Date.parse(proof.verifiedAt)) &&
        /^[a-f0-9-]{36}$/i.test(proof.threadId ?? '') &&
        ['reply', 'stop', 'approval'].every((gate) => proof.gates?.[gate] === true)
          ? 'passed'
          : 'stale';
    } catch {
      verificationStatus = 'unavailable';
    }
  }
  if (verificationStatus !== 'passed')
    console.warn(`Windows 实机验收记录：${verificationStatus}；已有聊天仍按实际 owner 接入。`);
  return {
    config,
    identity,
    archive,
    verificationStatus,
    ...(installed ? { executable: installed.executable } : {}),
  };
}

export function windowsAdapterDigest(): string {
  const hash = createHash('sha256');
  for (const name of [
    'desktop-platform.js',
    'windows-process.js',
    'desktop-private-state-native.js',
    'desktop-private-state-native-worker.js',
    'desktop-trace-native.js',
    'desktop-trace-native-worker.js',
    'desktop-suggestion-read-worker.js',
    'desktop-suggestion-worker-client.js',
    'desktop-suggestion-worker-limits.js',
    'windows-trace-diagnostics.js',
    'desktop-ipc.js',
    'history-reader.js',
    'desktop-framing.js',
    'runtime-session.js',
    'approval-requests.js',
    'runtime-persistence.js',
    'runtime-diagnostics.js',
    'diagnostic-events.js',
    'runtime-diagnostic-writer.js',
    'attachment-files.js',
    'desktop-questions.js',
  ])
    hash.update(readFileSync(new URL(name, import.meta.url)));
  for (const name of [
    'desktop-pipe.ps1',
    'desktop-cli.ps1',
    'private-state.ps1',
    'private-state.cs',
    'trace-state.cs',
    'trace-process.cs',
    'desktop-trace.ps1',
  ])
    hash.update(readFileSync(windowsHelper(name)));
  return hash.digest('hex');
}

let bridgeRetirement: Promise<void> | undefined;
/** Consume errors immediately, including Duplex.from cancellation before IPC adopts it. */
export function windowsDesktopBridgeStream(child: ReturnType<typeof spawn>): Duplex {
  const started = performance.now();
  const stream = Duplex.from({ readable: child.stdout!, writable: child.stdin! });
  stream.on('error', (error: NodeJS.ErrnoException) => {
    diagnosticEvent({
      scope: 'windows-bridge',
      stage: 'stream-error',
      code: error.code,
      pid: child.pid,
    });
  });
  stream.on('close', () => child.kill());
  child.once('exit', (exitCode) => {
    diagnosticEvent({
      scope: 'windows-bridge',
      stage: 'exit',
      pid: child.pid,
      exitCode,
      elapsedMs: performance.now() - started,
    });
    stream.destroy();
  });
  child.on('error', () => stream.destroy());
  return stream;
}
export async function openWindowsDesktopPipe(
  identity: WindowsDesktopIdentity,
  signal?: AbortSignal,
): Promise<Duplex> {
  if (bridgeRetirement) {
    let timer: NodeJS.Timeout | undefined;
    try {
      await Promise.race([
        bridgeRetirement,
        new Promise<never>((_, reject) => {
          timer = setTimeout(
            () =>
              reject(
                Object.assign(new Error('Previous bridge exit unconfirmed'), {
                  code: 'WINDOWS_BRIDGE_EXIT_UNCONFIRMED',
                }),
              ),
            1000,
          );
        }),
      ]);
    } finally {
      clearTimeout(timer);
    }
  }
  if (signal?.aborted)
    throw Object.assign(new Error('Bridge startup cancelled'), { code: 'ABORT_ERR' });
  let startupMs = 30_000;
  const child = withWindowsProcessCreation(startupMs, (remaining) => {
    startupMs = remaining;
    return spawn(
      powershell(),
      [
        '-NoProfile',
        '-NonInteractive',
        '-File',
        windowsHelper('desktop-pipe.ps1'),
        '-Action',
        'bridge',
        '-ExpectedDesktop',
        identity.executable,
        '-ExpectedSha256',
        identity.executableSha256,
      ],
      { stdio: 'pipe', windowsHide: true, env: windowsPowerShellEnvironment() },
    );
  });
  bridgeRetirement = new Promise<void>((resolve) => child.once('close', () => resolve()));
  const stream = windowsDesktopBridgeStream(child);
  const abort = () => {
    diagnosticEvent({
      scope: 'windows-bridge',
      stage: 'cancel',
      initiator: 'owner',
      pid: child.pid,
    });
    stream.destroy();
  };
  signal?.addEventListener('abort', abort, { once: true });
  child.once('close', () => signal?.removeEventListener('abort', abort));
  if (signal?.aborted) abort();
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      stream.destroy();
      reject(
        Object.assign(new Error('Windows desktop pipe timed out'), {
          code: 'WINDOWS_BRIDGE_STARTUP_TIMEOUT',
        }),
      );
    }, startupMs);
    let stderr = '';
    const fail = () => {
      clearTimeout(timer);
      const code =
        stderr.match(
          /^(WINDOWS_POWERSHELL_MODULE_UNAVAILABLE|WINDOWS_DESKTOP_NOT_RUNNING)\s*$/m,
        )?.[1] ?? 'WINDOWS_DESKTOP_PIPE_REJECTED';
      stream.destroy();
      diagnosticEvent({ scope: 'windows-bridge', stage: 'startup-failed', code, pid: child.pid });
      reject(Object.assign(new Error('Windows desktop pipe identity rejected'), { code }));
    };
    child.once('error', fail);
    child.once('exit', fail);
    child.stderr.on('data', (chunk: Buffer) => {
      stderr = (stderr + chunk.toString('utf8')).slice(-4096);
      if (stderr.split(/\r?\n/).includes('CODEX_MANAGE_PIPE_READY')) {
        clearTimeout(timer);
        child.removeListener('error', fail);
        child.removeListener('exit', fail);
        diagnosticEvent({ scope: 'windows-bridge', stage: 'ready', pid: child.pid });
        resolve();
      }
    });
  });
  return stream;
}
