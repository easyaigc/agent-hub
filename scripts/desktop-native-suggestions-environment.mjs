import { createHash, randomUUID } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  writeFileSync,
  chmodSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import {
  machineRuntimeTargetKey,
  parseNotificationTarget,
} from '../packages/protocol/dist/index.js';
import { DesktopNativeSuggestionChannel } from '../packages/codex-adapter/dist/desktop-native-suggestion-channel.js';
import { sharedDesktopEnvironment } from './desktop-shared-environment.mjs';

const sha = (b) => createHash('sha256').update(b).digest('hex');
function privatePath(path, directory = false) {
  for (let p = path; p !== dirname(p); p = dirname(p))
    if (lstatSync(p).isSymbolicLink()) throw new Error('Private native state alias');
  const s = lstatSync(path);
  if (
    (directory ? !s.isDirectory() : !s.isFile()) ||
    s.uid !== process.getuid() ||
    s.mode & 0o077 ||
    (!directory && s.nlink !== 1)
  )
    throw new Error('Private native state required');
}
function atomic(path, value) {
  const temporary = path + '.' + randomUUID() + '.tmp';
  writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  renameSync(temporary, path);
  chmodSync(path, 0o600);
}
/** Configure future normal original-app launches only; never quits or restarts it. */
export function nativeSuggestionsEnvironment({
  state = join(homedir(), '.config/agent-hub/native-suggestions'),
  sharedState,
  node = process.execPath,
  shared,
} = {}) {
  state = resolve(state);
  const manifestPath = join(state, 'environment.json'),
    configPath = join(state, 'config.json');
  const read = () => {
    privatePath(state, true);
    privatePath(manifestPath);
    privatePath(configPath);
    const m = JSON.parse(readFileSync(manifestPath, 'utf8'));
    if (
      m.version !== 1 ||
      m.configPath !== configPath ||
      m.configSha256 !== sha(readFileSync(configPath))
    )
      throw new Error('Preserve edited native suggestion configuration');
    return { manifest: m, config: JSON.parse(readFileSync(configPath, 'utf8')) };
  };
  const sharedApi = () =>
    shared ?? sharedDesktopEnvironment({ ...(sharedState ? { state: sharedState } : {}), node });
  const status = () => {
    if (!existsSync(manifestPath)) return { installed: false, desktopProcessesStarted: 0 };
    const { config, manifest } = read();
    const channel = new DesktopNativeSuggestionChannel({
      traceRoot: config.traceRoot,
      configPath,
      target: () => config.target,
    });
    const ready = channel.available;
    return {
      installed: true,
      configured: manifest.phase === 'configured',
      configPath,
      hostEnvironment: { CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG: configPath },
      currentProducerReady: ready,
      normalDesktopReopenRequired: !ready,
      originalModelRequests: 0,
      desktopProcessesStarted: 0,
    };
  };
  const install = ({
    program,
    executable,
    traceRoot,
    targetFile,
    applicationPath = '/Applications/ChatGPT.app',
    logDirectory = join(homedir(), 'Library/Logs/com.openai.codex'),
    url,
  }) => {
    program = realpathSync(resolve(program));
    executable = realpathSync(resolve(executable));
    node = realpathSync(node);
    traceRoot = realpathSync(resolve(traceRoot));
    privatePath(traceRoot, true);
    privatePath(resolve(targetFile));
    const target = parseNotificationTarget(JSON.parse(readFileSync(targetFile, 'utf8')));
    if (target.agentId !== 'codex' || target.runtimeId !== 'desktop-runtime')
      throw new Error('Original Codex target required');
    const previous = existsSync(manifestPath) ? read() : undefined;
    if (
      previous &&
      machineRuntimeTargetKey(previous.config.target) !== machineRuntimeTargetKey(target)
    )
      throw new Error('Preserve another machine target');
    if (!previous && existsSync(configPath)) throw new Error('Preserve unmanaged native config');
    mkdirSync(state, { recursive: true, mode: 0o700 });
    privatePath(state, true);
    const config = {
      version: 1,
      target,
      traceRoot,
      applicationPath: realpathSync(resolve(applicationPath)),
      executable,
      node,
      entry: join(program, 'scripts/desktop-native-suggestions-runtime.mjs'),
      logDirectory: resolve(logDirectory),
    };
    if (!existsSync(config.entry)) throw new Error('Compiled native suggestion program required');
    atomic(configPath, config);
    const manifest = {
      version: 1,
      configPath,
      configSha256: sha(readFileSync(configPath)),
      phase: 'prepared',
    };
    atomic(manifestPath, manifest);
    const current = sharedApi().status();
    sharedApi().install({
      program,
      executable,
      url: url ?? current.url,
      nativeSuggestionsConfig: configPath,
    });
    atomic(manifestPath, { ...manifest, phase: 'configured' });
    return status();
  };
  return { install, status };
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.platform !== 'darwin') throw new Error('Use the native Windows installer on Windows');
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: {
      state: { type: 'string' },
      'shared-state': { type: 'string' },
      program: { type: 'string' },
      executable: { type: 'string' },
      node: { type: 'string' },
      'trace-root': { type: 'string' },
      'target-file': { type: 'string' },
      'application-path': { type: 'string' },
      'log-directory': { type: 'string' },
      url: { type: 'string' },
    },
  });
  if (positionals.length !== 1 || !['install', 'status'].includes(positionals[0]))
    throw new Error('Use install/status');
  const api = nativeSuggestionsEnvironment({
    ...(values.state ? { state: values.state } : {}),
    ...(values.node ? { node: values.node } : {}),
    ...(values['shared-state'] ? { sharedState: values['shared-state'] } : {}),
  });
  console.log(
    JSON.stringify(
      positionals[0] === 'status'
        ? api.status()
        : api.install({
            program: values.program,
            executable: values.executable,
            traceRoot: values['trace-root'],
            targetFile: values['target-file'],
            ...(values['application-path'] ? { applicationPath: values['application-path'] } : {}),
            ...(values['log-directory'] ? { logDirectory: values['log-directory'] } : {}),
            ...(values.url ? { url: values.url } : {}),
          }),
    ),
  );
}
