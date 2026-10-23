import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const variable = 'CODEX_ROLLOUT_TRACE_ROOT';
const label = 'com.codexmanage.suggestion-trace-environment';
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const xml = (value) =>
  value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');

function privatePath(path, directory) {
  for (let cursor = path; cursor !== dirname(cursor); cursor = dirname(cursor)) {
    if (lstatSync(cursor).isSymbolicLink()) throw Error('Private path contains a symlink');
  }
  const info = lstatSync(path);
  if (
    (directory ? !info.isDirectory() : !info.isFile()) ||
    info.uid !== process.getuid() ||
    (info.mode & 0o077) !== 0 ||
    (!directory && info.nlink !== 1)
  )
    throw Error('Invalid private trace environment state');
}

/** One named launchd environment key. Never launches, attaches to or quits Codex. */
export function traceEnvironment({
  state = join(homedir(), '.config/agent-hub/desktop-trace'),
  agents = join(homedir(), 'Library/LaunchAgents'),
  node = process.execPath,
  run = (args) =>
    execFileSync('/bin/launchctl', args, { encoding: 'utf8', stdio: 'pipe', timeout: 5000 }),
} = {}) {
  state = resolve(state);
  const traceRoot = join(state, 'traces');
  const manifestPath = join(state, 'environment.json');
  const runtimePath = join(state, 'trace-environment.mjs');
  const plist = join(agents, label + '.plist');
  const domain = 'gui/' + process.getuid();
  const service = domain + '/' + label;
  const source = readFileSync(fileURLToPath(import.meta.url));
  const getenv = () => {
    try {
      return run(['getenv', variable]).trimEnd();
    } catch {
      return '';
    }
  };
  const manifest = () => {
    privatePath(state, true);
    privatePath(manifestPath, false);
    privatePath(traceRoot, true);
    const value = JSON.parse(readFileSync(manifestPath, 'utf8'));
    if (
      value.schemaVersion !== 1 ||
      value.label !== label ||
      value.variable !== variable ||
      value.traceRoot !== traceRoot ||
      value.plist !== plist ||
      value.runtime !== runtimePath
    )
      throw Error('Trace environment identity changed');
    return value;
  };
  const status = () => {
    const value = manifest();
    let registered = false;
    try {
      run(['print', service]);
      registered = true;
    } catch {
      /* Absent job. */
    }
    return {
      registered,
      futureLaunchEnvironmentReady: getenv() === value.traceRoot,
      traceRoot,
      currentDesktopActivated: 'requires-independent-process-check',
      modelInputsSubmitted: 0,
    };
  };
  const apply = () => {
    const value = manifest();
    const current = getenv();
    if (current && current !== value.traceRoot)
      throw Error('Preserve a later or unrelated trace environment');
    run(['setenv', variable, value.traceRoot]);
    return { futureLaunchEnvironmentReady: getenv() === value.traceRoot, modelInputsSubmitted: 0 };
  };
  const install = () => {
    // Set exactly one non-secret environment key. The key is inherited by GUI
    // descendants; it is not an application-scoped macOS environment setting.
    // Reject conflicts before writing state or unloading an existing login job.
    const current = getenv();
    if (current && current !== traceRoot)
      throw Error(
        existsSync(manifestPath)
          ? 'Preserve a later or unrelated trace environment'
          : 'An existing trace environment belongs to another configuration',
      );
    mkdirSync(state, { recursive: true, mode: 0o700 });
    privatePath(state, true);
    mkdirSync(traceRoot, { mode: 0o700, recursive: true });
    privatePath(traceRoot, true);
    let previous = null;
    if (existsSync(manifestPath)) previous = manifest();
    if (!previous) {
      let registered = false;
      try {
        run(['print', service]);
        registered = true;
      } catch {
        /* Absent job. */
      }
      if (registered) throw Error('Preserve an unmanaged registered LaunchAgent');
    }
    if (existsSync(plist)) {
      privatePath(plist, false);
      if (!previous || sha256(readFileSync(plist)) !== previous.plistSha256)
        throw Error('Preserve an unmanaged or edited LaunchAgent');
    }
    if (existsSync(runtimePath)) {
      privatePath(runtimePath, false);
      if (!previous || sha256(readFileSync(runtimePath)) !== previous.runtimeSha256)
        throw Error('Preserve an unmanaged or edited runtime');
    }
    node = realpathSync(node);
    const body =
      '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n' +
      '<plist version="1.0"><dict><key>Label</key><string>' +
      label +
      '</string>' +
      '<key>ProgramArguments</key><array>' +
      [node, runtimePath, 'apply', '--state', state]
        .map((v) => '<string>' + xml(v) + '</string>')
        .join('') +
      '</array><key>RunAtLoad</key><true/></dict></plist>\n';
    const value = {
      schemaVersion: 1,
      label,
      variable,
      traceRoot,
      runtime: runtimePath,
      plist,
      previousValue: previous?.previousValue ?? current,
      runtimeSha256: sha256(source),
      plistSha256: sha256(body),
    };
    if (previous) {
      try {
        run(['bootout', service]);
      } catch {
        /* Absent job. */
      }
    }
    mkdirSync(agents, { recursive: true });
    writeFileSync(runtimePath, source, { mode: 0o600 });
    writeFileSync(manifestPath, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
    writeFileSync(plist, body, { mode: 0o600 });
    // Set it now as well as at the next login. This does not alter the current
    // desktop's environment and must not be reported as current trace activation.
    apply();
    run(['enable', service]);
    run(['bootstrap', domain, plist]);
    return status();
  };
  const disable = () => {
    const value = manifest();
    privatePath(plist, false);
    if (sha256(readFileSync(plist)) !== value.plistSha256)
      throw Error('Preserve edited LaunchAgent');
    run(['disable', service]);
    try {
      run(['bootout', service]);
    } catch {
      /* Absent job. */
    }
    // Do not overwrite a user's later environment change.
    if (getenv() === value.traceRoot) {
      if (value.previousValue) run(['setenv', variable, value.previousValue]);
      else run(['unsetenv', variable]);
    }
    return { disabled: true, loginFileRetainedForRecovery: plist, modelInputsSubmitted: 0 };
  };
  return { install, status, apply, disable };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.platform !== 'darwin')
    throw Error('Persistent desktop trace environment requires macOS');
  const [action, flag, state, ...extra] = process.argv.slice(2);
  if (
    !['install', 'status', 'apply', 'disable'].includes(action) ||
    (flag && flag !== '--state') ||
    (flag && !state) ||
    extra.length
  )
    throw Error('Use install|status|apply|disable [--state <private-directory>]');
  console.log(JSON.stringify(traceEnvironment(state ? { state } : {})[action]()));
}
