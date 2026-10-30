import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import {
  closeSync,
  existsSync,
  fstatSync,
  fsyncSync,
  lstatSync,
  realpathSync,
  readdirSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  inspectWindowsTraceInstance,
  makePrivateDirectorySync,
  openPrivateFileSync,
  renamePrivateFileSync,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  windowsPowerShellEnvironment,
  windowsTraceInstanceReady,
  windowsTraceSnapshot,
} from '../packages/codex-adapter/dist/desktop-platform.js';

const variable = 'CODEX_ROLLOUT_TRACE_ROOT';
function registry(action, value, kind = 'String') {
  return JSON.parse(
    execFileSync(
      join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
      [
        '-NoProfile',
        '-NonInteractive',
        '-File',
        fileURLToPath(
          new URL('../packages/codex-adapter/windows/trace-environment.ps1', import.meta.url),
        ),
        '-Action',
        action,
        '-Kind',
        kind,
      ],
      {
        encoding: 'utf8',
        stdio: 'pipe',
        windowsHide: true,
        timeout: 10000,
        env: { ...windowsPowerShellEnvironment(), CODEX_MANAGE_TRACE_VALUE: value ?? '' },
      },
    ),
  );
}
function privateDirectory(path, create = false) {
  for (let at = path; at !== dirname(at); at = dirname(at))
    if (existsSync(at) && lstatSync(at).isSymbolicLink()) throw Error('Reparse path rejected');
  if (create) {
    makePrivateDirectorySync(path);
    protectWindowsStateDirectory(path);
  }
  windowsTraceSnapshot(path, path);
  if (realpathSync(path).toLowerCase() !== path.toLowerCase()) throw Error('Path identity changed');
}

/** One HKCU key only. No service changes, process injection, quit or restart. */
export function windowsTraceEnvironment({
  state,
  run = registry,
  inspect = inspectWindowsTraceInstance,
} = {}) {
  if (process.platform !== 'win32') throw Error('Native Windows required');
  if (state === undefined) {
    if (!process.env.USERPROFILE || !isAbsolute(process.env.USERPROFILE))
      throw Error('Native Windows user profile directory missing');
    // Store/MSIX apps may virtualize LOCALAPPDATA into their package cache.
    // Keep the persistent path native and shared with the original desktop.
    state = join(process.env.USERPROFILE, '.config', 'agent-hub', 'desktop-trace');
  }
  state = resolve(state);
  const traceRoot = join(state, 'traces'),
    manifest = join(state, 'environment.json');
  const load = () => {
    privateDirectory(state);
    const value = JSON.parse(windowsTraceSnapshot(state, manifest, 16384).bytes);
    const phase = value.phase === undefined ? 'ready' : value.phase;
    const previous = value.previous;
    if (
      value.version !== 1 ||
      value.traceRoot !== traceRoot ||
      typeof value.enabled !== 'boolean' ||
      !['preparing', 'ready'].includes(phase) ||
      (phase === 'preparing' && value.enabled) ||
      !previous ||
      !(
        (previous.value === null && previous.kind === null) ||
        (typeof previous.value === 'string' && ['String', 'ExpandString'].includes(previous.kind))
      )
    )
      throw Error('Trace environment identity changed');
    if (phase !== 'preparing' || existsSync(traceRoot)) privateDirectory(traceRoot);
    return { ...value, phase };
  };
  const save = (value) => {
    const temporary = manifest + '.' + process.pid + '.' + randomUUID() + '.tmp';
    let fd, identity;
    let created = false;
    try {
      fd = openPrivateFileSync(temporary, 'wx', 0o600);
      created = true;
      identity = fstatSync(fd, { bigint: true });
      protectWindowsStateFile(temporary);
      writeFileSync(fd, JSON.stringify(value, null, 2) + '\n');
      fsyncSync(fd);
      const opened = fd;
      fd = undefined;
      closeSync(opened);
      renamePrivateFileSync(temporary, manifest);
    } catch (cause) {
      try {
        if (fd !== undefined) closeSync(fd);
        if (created) {
          const current = lstatSync(temporary, { bigint: true });
          if (
            !identity ||
            !current.isFile() ||
            current.isSymbolicLink() ||
            current.nlink !== 1n ||
            current.dev !== identity.dev ||
            current.ino !== identity.ino ||
            current.birthtimeNs !== identity.birthtimeNs
          )
            throw Error('Temporary manifest identity changed', { cause });
          unlinkSync(temporary);
        }
      } catch (cleanup) {
        if (cleanup.code !== 'ENOENT')
          throw new AggregateError(
            [cause, cleanup],
            'Trace manifest save failed; temporary file retained for inspection: ' + temporary,
            { cause: cleanup },
          );
      }
      throw cause;
    }
  };
  const assertEnvironment = (value, current) => {
    const owned = value.enabled && current.value === traceRoot && current.kind === 'String';
    if (!owned && (current.value !== value.previous.value || current.kind !== value.previous.kind))
      throw Error('Preserve later user environment');
  };
  const status = () => {
    if (!existsSync(manifest)) return { configured: false, variable, currentInstanceReady: false };
    const value = load(),
      current = run('read');
    let instance;
    try {
      if (value.phase === 'ready') instance = inspect();
    } catch {
      /* unavailable is not enabled */
    }
    return {
      phase: value.phase,
      configured: value.enabled && current.value === traceRoot && current.kind === 'String',
      variable,
      traceRoot,
      registryValueMatches: current.value === traceRoot,
      currentInstanceReady: !!instance && windowsTraceInstanceReady(instance, traceRoot),
      currentDesktopPid: instance?.pid ?? null,
      requiresFreshLauncher: true,
      loginRecoveryVerified: false,
      modelInputsSubmitted: 0,
    };
  };
  return {
    status,
    install() {
      const current = run('read');
      let value;
      if (existsSync(manifest)) {
        value = load();
        assertEnvironment(value, current);
      } else {
        if (current.value !== null)
          throw Error('Existing trace environment requires explicit migration');
        if (existsSync(state) && readdirSync(state).length)
          throw Error('Preserve existing directory');
        privateDirectory(state, true);
        value = { version: 1, traceRoot, previous: current, phase: 'preparing', enabled: false };
        save(value);
      }
      if (value.phase === 'preparing') privateDirectory(traceRoot, !existsSync(traceRoot));
      value.phase = 'ready';
      value.enabled = true;
      save(value);
      // Filesystem preparation may take time; preserve a choice made during it.
      assertEnvironment(value, run('read'));
      run('write', traceRoot, 'String');
      return status();
    },
    disable() {
      const value = load(),
        current = run('read');
      if (!value.enabled) return status();
      if (current.value !== traceRoot || current.kind !== 'String')
        throw Error('Preserve later user environment');
      if (value.previous.value === null) run('remove');
      else run('write', value.previous.value, value.previous.kind);
      value.enabled = false;
      save(value);
      return status();
    },
  };
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [action = 'status', state] = process.argv.slice(2);
  if (!['install', 'status', 'disable'].includes(action))
    throw Error('Use install|status|disable [private-state-directory]');
  console.log(JSON.stringify(windowsTraceEnvironment(state ? { state } : {})[action]()));
}
