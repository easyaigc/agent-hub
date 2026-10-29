import { execFileSync } from 'node:child_process';
import { mkdir, writeFile, readFile, chmod, rm } from 'node:fs/promises';
import { homedir } from 'node:os';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { existsSync } from 'node:fs';
import {
  readMacDesktopVersion,
  assertPrivateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

// Persist the user LaunchAgent so it also returns after the next login.
if (process.platform !== 'darwin') throw Error('Desktop service requires macOS launchd');
const action = process.argv[2] ?? 'status';
if (!['start', 'stop', 'status', 'restart'].includes(action))
  throw Error('Use start/stop/status/restart');
const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const directory = resolve(process.env.HOST_AGENT_STATE_DIR ?? join(root, '.local/desktop-runtime'));
const label = 'com.codexmanage.host.desktop';
const domain = `gui/${process.getuid()}`;
const target = `${domain}/${label}`;
const plist = join(directory, 'host-agent.plist');
const loginDirectory = join(homedir(), 'Library/LaunchAgents');
const loginPlist = join(loginDirectory, `${label}.plist`);
const launch = (args) =>
  execFileSync('/bin/launchctl', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
const status = () => {
  try {
    const value = launch(['print', target]);
    return {
      installed: true,
      running: /state = running/.test(value),
      pid: Number(value.match(/\bpid = (\d+)/)?.[1]) || null,
    };
  } catch {
    return { installed: false, running: false, pid: null };
  }
};
const xml = (value) =>
  String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
if (action === 'stop') {
  // An explicit stop must remain stopped after logout/reboot. Keep private state.
  if (existsSync(loginPlist)) {
    assertPrivateFile(loginPlist);
    await rm(loginPlist);
  }
  if (status().installed) launch(['bootout', target]);
} else if (action === 'start' || action === 'restart') {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  let previous = {};
  let previousArguments;
  let previousWorkingDirectory;
  if (existsSync(plist)) {
    assertPrivateFile(plist);
    const saved = JSON.parse(
      execFileSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-', plist], { encoding: 'utf8' }),
    );
    previous = saved.EnvironmentVariables ?? {};
    previousArguments = saved.ProgramArguments;
    previousWorkingDirectory = saved.WorkingDirectory;
  }
  const environment = {
    ...previous,
    HOME: homedir(),
    PATH: previous.PATH ?? '/usr/bin:/bin:/usr/sbin:/sbin',
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_VERSION: readMacDesktopVersion(),
    CODEX_EXECUTABLE:
      process.env.CODEX_EXECUTABLE ??
      previous.CODEX_EXECUTABLE ??
      '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex',
    HOST_AGENT_PORT: process.env.HOST_AGENT_PORT ?? previous.HOST_AGENT_PORT ?? '8794',
    HOST_AGENT_ROOT: process.env.HOST_AGENT_ROOT ?? previous.HOST_AGENT_ROOT ?? root,
    HOST_AGENT_STATE_DIR: directory,
    HOST_AGENT_NAME: process.env.HOST_AGENT_NAME ?? previous.HOST_AGENT_NAME ?? '本机 Mac',
  };
  if (process.env.HOST_NOTIFICATION_RELAY_URL !== undefined)
    environment.HOST_NOTIFICATION_RELAY_URL = process.env.HOST_NOTIFICATION_RELAY_URL;
  for (const key of [
    'HOST_AGENT_DISCOVERY',
    'HOST_AGENT_DISCOVERY_BIND',
    'HOST_AGENT_DISCOVERY_PORT',
    'HOST_AGENT_DISCOVERY_TLS_CERT',
    'HOST_AGENT_DISCOVERY_TLS_KEY',
    'HOST_AGENT_OPENSSL',
    'HOST_AGENT_HTTPS_ENDPOINTS',
    'HOST_RESPONSES_PROXY_ENABLED',
    'HOST_RESPONSES_PROXY_BIND',
    'HOST_RESPONSES_PROXY_PORT',
    'HOST_RESPONSES_PROXY_STATE_DIR',
    'HOST_RESPONSES_PROXY_TOKEN_FILE',
    'HOST_RESPONSES_PROXY_TLS_CERT',
    'HOST_RESPONSES_PROXY_TLS_KEY',
    'HOST_RESPONSES_PROXY_MODELS',
    'HOST_RESPONSES_PROXY_MAX_BYTES',
    'HOST_RESPONSES_PROXY_CONCURRENCY',
    'HOST_RESPONSES_PROXY_TIMEOUT_MS',
    'CODEX_DESKTOP_SUGGESTION_OBSERVER',
    'CODEX_DESKTOP_SUGGESTION_TRACE_ROOT',
    'CODEX_SHARED_SERVER_URL',
  ]) {
    if (process.env[key] !== undefined) environment[key] = process.env[key];
  }
  if (process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE !== undefined)
    environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE = process.env
      .HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE
      ? resolve(process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE)
      : '';
  const creationKeys = [
    'CODEX_DESKTOP_CREATION_HELPER',
    'CODEX_DESKTOP_CREATION_PROFILE',
    'CODEX_DESKTOP_CREATION_VERIFICATION',
  ];
  if (process.env.CODEX_DESKTOP_CONTROLLER_CONFIG)
    environment.CODEX_DESKTOP_CONTROLLER_CONFIG = resolve(
      process.env.CODEX_DESKTOP_CONTROLLER_CONFIG,
    );
  if (process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG)
    environment.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG = resolve(
      process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG,
    );
  if (
    !environment.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG &&
    existsSync(join(directory, 'prepared-bridge.json'))
  )
    environment.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG = join(directory, 'prepared-bridge.json');
  const nodePath =
    previousArguments?.[0] && existsSync(previousArguments[0])
      ? previousArguments[0]
      : process.execPath;
  const entryPath =
    previousArguments?.[1] && existsSync(previousArguments[1])
      ? previousArguments[1]
      : join(root, 'apps/host-agent/dist/runtime-entry.js');
  if (creationKeys.some((key) => process.env[key])) {
    if (!creationKeys.every((key) => process.env[key]))
      throw Error('Desktop creation requires all three local configuration paths');
    for (const key of creationKeys) environment[key] = resolve(process.env[key]);
  }
  const log = join(directory, 'service.log');
  await writeFile(log, '', { flag: 'a', mode: 0o600 });
  await chmod(log, 0o600);
  await writeFile(
    plist,
    `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>${label}</string>
<key>ProgramArguments</key><array><string>${xml(nodePath)}</string><string>${xml(entryPath)}</string></array>
<key>WorkingDirectory</key><string>${xml(previousWorkingDirectory ?? root)}</string>
<key>EnvironmentVariables</key><dict>${Object.entries(environment)
      .map(([key, value]) => `<key>${key}</key><string>${xml(value)}</string>`)
      .join('')}</dict>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>ThrottleInterval</key><integer>5</integer>
<key>StandardOutPath</key><string>${xml(log)}</string>
<key>StandardErrorPath</key><string>${xml(log)}</string>
</dict></plist>`,
    { mode: 0o600 },
  );
  await mkdir(loginDirectory, { recursive: true, mode: 0o700 });
  if (existsSync(loginPlist)) assertPrivateFile(loginPlist);
  await writeFile(loginPlist, await readFile(plist), { mode: 0o600 });
  await chmod(loginPlist, 0o600);
  // start/restart also clears a persistent launchctl disable override for this job.
  launch(['enable', target]);
  if (action === 'restart' && status().installed) {
    launch(['bootout', target]);
    const deadline = Date.now() + 5000;
    while (status().installed && Date.now() < deadline)
      await new Promise((done) => setTimeout(done, 100));
    if (status().installed) throw Error('Previous bridge did not finish unloading');
  }
  if (!status().installed) launch(['bootstrap', domain, loginPlist]);
}
console.log(
  JSON.stringify({ service: label, ...status(), loginAutostart: existsSync(loginPlist) }),
);
