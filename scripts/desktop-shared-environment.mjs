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
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const label = 'com.codexmanage.shared-desktop-environment';
const keys = ['CODEX_CLI_PATH', 'CODEX_APP_SERVER_FORCE_CLI'];
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const xml = (value) =>
  String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
function privatePath(path, directory = false) {
  for (let cursor = path; cursor !== dirname(cursor); cursor = dirname(cursor))
    if (lstatSync(cursor).isSymbolicLink())
      throw Error('Private shared environment path contains a symlink');
  const info = lstatSync(path);
  if (
    (directory ? !info.isDirectory() : !info.isFile()) ||
    info.uid !== process.getuid() ||
    info.mode & 0o077 ||
    (!directory && info.nlink !== 1)
  )
    throw Error('Shared environment state must be private');
}

/** Configure future launches of the original app. Never starts or quits a desktop. */
export function sharedDesktopEnvironment({
  state = join(homedir(), '.config/agent-hub/shared-desktop'),
  agents = join(homedir(), 'Library/LaunchAgents'),
  node = process.execPath,
  run = (args) =>
    execFileSync('/bin/launchctl', args, { encoding: 'utf8', stdio: 'pipe', timeout: 5000 }),
} = {}) {
  state = resolve(state);
  const manifestPath = join(state, 'environment.json');
  const runtime = join(state, 'shared-environment.mjs');
  const wrapper = join(state, 'codex-shared.mjs');
  const ownerDirectory = join(state, 'service');
  const plist = join(agents, label + '.plist');
  const domain = 'gui/' + process.getuid();
  const service = domain + '/' + label;
  const getenv = (key) => {
    try {
      return run(['getenv', key]).trimEnd();
    } catch {
      return '';
    }
  };
  const manifest = () => {
    privatePath(state, true);
    privatePath(manifestPath);
    privatePath(wrapper);
    privatePath(runtime);
    privatePath(ownerDirectory, true);
    const value = JSON.parse(readFileSync(manifestPath, 'utf8'));
    if (
      ![1, 2].includes(value.schemaVersion) ||
      value.label !== label ||
      value.state !== state ||
      value.plist !== plist ||
      value.runtime !== runtime ||
      value.wrapper !== wrapper ||
      value.environment.CODEX_CLI_PATH !== wrapper ||
      value.environment.CODEX_APP_SERVER_FORCE_CLI !== (value.schemaVersion === 1 ? '1' : '')
    )
      throw Error('Shared desktop environment identity changed');
    if (
      hash(readFileSync(wrapper)) !== value.wrapperSha256 ||
      hash(readFileSync(runtime)) !== value.runtimeSha256
    )
      throw Error('Preserve edited shared desktop environment');
    return value;
  };
  const apply = () => {
    const value = manifest();
    if (value.schemaVersion !== 2)
      throw Error('Upgrade the shared environment with install; preserve native cloud routing');
    for (const key of keys) {
      const current = getenv(key);
      if (current && current !== value.environment[key])
        throw Error('Preserve another desktop executable configuration');
    }
    for (const key of keys)
      run(value.environment[key] ? ['setenv', key, value.environment[key]] : ['unsetenv', key]);
    return {
      futureLaunchEnvironmentReady:
        value.schemaVersion === 2 && keys.every((key) => getenv(key) === value.environment[key]),
      migrationRequired: value.schemaVersion !== 2,
      desktopProcessesStarted: 0,
    };
  };
  const status = () => {
    if (!existsSync(manifestPath)) return { installed: false, desktopProcessesStarted: 0 };
    const value = manifest();
    let registered = false;
    try {
      run(['print', service]);
      registered = true;
    } catch {
      /* Not registered. */
    }
    return {
      installed: true,
      registered,
      futureLaunchEnvironmentReady:
        value.schemaVersion === 2 && keys.every((key) => getenv(key) === value.environment[key]),
      migrationRequired: value.schemaVersion !== 2,
      url: value.url,
      program: value.program,
      currentDesktopActivated: 'requires-independent-process-check',
      desktopProcessesStarted: 0,
    };
  };
  const install = ({ program, executable, url, nativeSuggestionsConfig }) => {
    program = realpathSync(resolve(program));
    executable = realpathSync(resolve(executable));
    node = realpathSync(node);
    const endpoint = new URL(url);
    if (
      endpoint.protocol !== 'ws:' ||
      !['127.0.0.1', '[::1]'].includes(endpoint.hostname) ||
      !endpoint.port ||
      endpoint.username ||
      endpoint.password ||
      endpoint.pathname !== '/' ||
      endpoint.search ||
      endpoint.hash
    )
      throw Error('Explicit loopback shared service required');
    if (!lstatSync(executable).isFile()) throw Error('Original executable required');
    const entry = join(program, 'packages/codex-adapter/dist/desktop-shared-launcher.js');
    if (!lstatSync(entry).isFile()) throw Error('Compiled shared launcher required');
    const previous = existsSync(manifestPath) ? manifest() : undefined;
    nativeSuggestionsConfig ??= previous?.nativeSuggestionsConfig;
    if (nativeSuggestionsConfig) {
      nativeSuggestionsConfig = realpathSync(resolve(nativeSuggestionsConfig));
      privatePath(nativeSuggestionsConfig);
    }
    // A global force flag also redirects the official durable/cloud backend.
    // The original local CLI override is sufficient; preserve all native routes.
    const environment = { CODEX_CLI_PATH: wrapper, CODEX_APP_SERVER_FORCE_CLI: '' };
    for (const key of keys)
      if (
        getenv(key) &&
        getenv(key) !== environment[key] &&
        getenv(key) !== previous?.environment[key]
      )
        throw Error('Preserve another desktop executable configuration');
    mkdirSync(state, { recursive: true, mode: 0o700 });
    privatePath(state, true);
    if (existsSync(plist)) {
      privatePath(plist);
      if (!previous || hash(readFileSync(plist)) !== previous.plistSha256)
        throw Error('Preserve an unmanaged or edited LaunchAgent');
    }
    if (!previous) {
      let registered = false;
      try {
        run(['print', service]);
        registered = true;
      } catch {
        /* Not registered. */
      }
      if (registered || existsSync(wrapper) || existsSync(runtime))
        throw Error('Preserve unmanaged shared environment files');
    }
    mkdirSync(ownerDirectory, { recursive: true, mode: 0o700 });
    privatePath(ownerDirectory, true);
    const wrapperSource =
      '#!' +
      node +
      '\n' +
      'process.env.AGENT_HUB_SHARED_APP_SERVER_URL = ' +
      JSON.stringify(endpoint.origin) +
      ';\n' +
      'process.env.AGENT_HUB_SHARED_APP_SERVER_DIRECTORY = ' +
      JSON.stringify(ownerDirectory) +
      ';\n' +
      (nativeSuggestionsConfig
        ? 'process.env.AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG = ' +
          JSON.stringify(nativeSuggestionsConfig) +
          ';\n'
        : '') +
      'const { runDesktopSharedLauncher } = await import(' +
      JSON.stringify(pathToFileURL(entry).href) +
      ');\n' +
      'await runDesktopSharedLauncher(' +
      JSON.stringify(executable) +
      ', process.argv.slice(2));\n';
    const source = readFileSync(fileURLToPath(import.meta.url));
    const body =
      '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict><key>Label</key><string>' +
      label +
      '</string><key>ProgramArguments</key><array>' +
      [node, runtime, 'apply', '--state', state]
        .map((v) => '<string>' + xml(v) + '</string>')
        .join('') +
      '</array><key>RunAtLoad</key><true/></dict></plist>\n';
    if (previous) {
      try {
        run(['bootout', service]);
      } catch {
        /* Already unloaded. */
      }
    }
    if (previous?.schemaVersion === 1 && getenv('CODEX_APP_SERVER_FORCE_CLI') === '1')
      run(['unsetenv', 'CODEX_APP_SERVER_FORCE_CLI']);
    mkdirSync(agents, { recursive: true });
    writeFileSync(wrapper, wrapperSource, { mode: 0o700 });
    writeFileSync(runtime, source, { mode: 0o600 });
    writeFileSync(plist, body, { mode: 0o600 });
    writeFileSync(
      manifestPath,
      JSON.stringify(
        {
          schemaVersion: 2,
          label,
          state,
          plist,
          runtime,
          wrapper,
          ownerDirectory,
          program,
          executable,
          url: endpoint.origin,
          environment,
          ...(nativeSuggestionsConfig ? { nativeSuggestionsConfig } : {}),
          previousEnvironment:
            previous?.previousEnvironment ??
            Object.fromEntries(keys.map((key) => [key, getenv(key)])),
          wrapperSha256: hash(wrapperSource),
          runtimeSha256: hash(source),
          plistSha256: hash(body),
        },
        null,
        2,
      ) + '\n',
      { mode: 0o600 },
    );
    apply();
    run(['enable', service]);
    run(['bootstrap', domain, plist]);
    return status();
  };
  const disable = () => {
    const value = manifest();
    privatePath(plist);
    if (hash(readFileSync(plist)) !== value.plistSha256) throw Error('Preserve edited LaunchAgent');
    run(['disable', service]);
    try {
      run(['bootout', service]);
    } catch {
      /* Already unloaded. */
    }
    for (const key of keys)
      if (getenv(key) === value.environment[key]) {
        if (value.previousEnvironment[key]) run(['setenv', key, value.previousEnvironment[key]]);
        else run(['unsetenv', key]);
      }
    return {
      disabled: true,
      desktopProcessesStarted: 0,
      restartRequiredToChangeCurrentDesktop: true,
    };
  };
  return { install, status, apply, disable };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.platform !== 'darwin') throw Error('Shared desktop environment requires macOS');
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: {
      state: { type: 'string' },
      program: { type: 'string' },
      executable: { type: 'string' },
      url: { type: 'string' },
      'native-suggestions-config': { type: 'string' },
    },
  });
  const [action] = positionals;
  if (positionals.length !== 1 || !['install', 'status', 'apply', 'disable'].includes(action))
    throw Error('Use install/status/apply/disable');
  const api = sharedDesktopEnvironment({ ...(values.state ? { state: values.state } : {}) });
  console.log(
    JSON.stringify(
      action === 'install'
        ? api.install({ ...values, nativeSuggestionsConfig: values['native-suggestions-config'] })
        : api[action](),
    ),
  );
}
