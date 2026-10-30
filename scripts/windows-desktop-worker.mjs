import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  assertPrivateFile,
  makePrivateDirectorySync,
  writePrivateFileSync,
  renamePrivateFileSync,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { refreshWindowsHostConfiguration } from './windows-desktop-recovery.mjs';
import { safeWorkerFailure, superviseWorker } from './host-worker-supervisor.mjs';

if (process.platform !== 'win32') throw new Error('Native Windows required');
if (process.argv[3] === '--host-child')
  process.on('uncaughtExceptionMonitor', (error) => {
    console.error(
      JSON.stringify({
        diagnostic: 'host-runtime-failure',
        scope: 'uncaught',
        ...safeWorkerFailure(error),
      }),
    );
  });
const configurationPath = process.argv[2];
if (!configurationPath) throw new Error('Windows Host configuration required');
assertPrivateFile(configurationPath);
let configuration;
try {
  configuration = JSON.parse(readFileSync(configurationPath, 'utf8'));
} catch {
  throw new Error('Invalid private Host configuration');
}
const allowed = new Set([
  'AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS',
  'CODEX_RUNTIME_MODE',
  'CODEX_DESKTOP_VERSION',
  'CODEX_EXECUTABLE',
  'CODEX_DESKTOP_WINDOWS_CONFIG',
  'CODEX_DESKTOP_CONTROLLER_CONFIG',
  'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
  'CODEX_DESKTOP_SUGGESTION_OBSERVER',
  'CODEX_DESKTOP_SUGGESTION_TRACE_ROOT',
  'CODEX_DESKTOP_TRACE_RETENTION',
  'CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG',
  'HOST_AGENT_ROOT',
  'HOST_AGENT_NAME',
  'HOST_AGENT_PORT',
  'HOST_AGENT_STATE_DIR',
  'HOST_AGENT_DISCOVERY',
  'HOST_AGENT_DISCOVERY_BIND',
  'HOST_AGENT_DISCOVERY_PORT',
  'HOST_AGENT_DISCOVERY_TLS_CERT',
  'HOST_AGENT_DISCOVERY_TLS_KEY',
  'HOST_AGENT_OPENSSL',
  'HOST_AGENT_HTTPS_ENDPOINTS',
  'HOST_NOTIFICATION_RELAY_URL',
  'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
]);
if (configuration.environment?.CODEX_RUNTIME_MODE !== 'desktop-runtime')
  throw new Error('Original desktop required');
for (const [key, value] of Object.entries(configuration.environment ?? {})) {
  if (!allowed.has(key) || typeof value !== 'string') throw new Error('Invalid Host environment');
  if (key === 'AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS' && value !== '0' && value !== '1')
    throw new Error('Invalid Windows Trace diagnostic mode');
}
if (process.argv[3] === '--host-child') {
  const refreshed = refreshWindowsHostConfiguration(configurationPath);
  configuration = refreshed.configuration;
  if (refreshed.changed)
    console.error(
      JSON.stringify({
        diagnostic: 'host-runtime-failure',
        scope: 'startup-recovery',
        code: 'WINDOWS_CONFIG_REFRESHED',
      }),
    );
}
for (const [key, value] of Object.entries(configuration.environment)) process.env[key] = value;
if (process.argv[3] === '--host-child') {
  // Monitoring records the crash; it never catches or suppresses it.
  const stop = () => {
    let status = 0;
    try {
      process.emit('SIGTERM');
    } catch (error) {
      status = 1;
      console.error(
        JSON.stringify({
          diagnostic: 'host-runtime-failure',
          scope: 'shutdown',
          ...safeWorkerFailure(error?.cause ?? error),
        }),
      );
    } finally {
      process.exit(status);
    }
  };
  process.once('disconnect', stop);
  process.on('message', (message) => {
    if (message?.command === 'host-worker-stop') stop();
  });
  await import('../apps/host-agent/dist/runtime-entry.js');
} else {
  const directory = join(dirname(configurationPath), 'worker-diagnostics');
  makePrivateDirectorySync(directory);
  protectWindowsStateDirectory(directory);
  const file = join(directory, 'worker.jsonl');
  const previous = join(directory, 'worker.previous.jsonl');
  const prepare = () => {
    if (!existsSync(file)) {
      writePrivateFileSync(file, '', { mode: 0o600, flag: 'wx' });
      protectWindowsStateFile(file);
    }
    assertPrivateFile(file);
  };
  prepare();
  const report = (entry) => {
    prepare();
    if (statSync(file).size >= 1024 * 1024) {
      if (existsSync(previous)) assertPrivateFile(previous);
      renamePrivateFileSync(file, previous);
      prepare();
    }
    writePrivateFileSync(
      file,
      JSON.stringify({ at: new Date().toISOString(), supervisorPid: process.pid, ...entry }) + '\n',
      { flag: 'a', mode: 0o600 },
    );
  };
  const supervisor = superviseWorker({
    executable: process.execPath,
    args: [fileURLToPath(import.meta.url), configurationPath, '--host-child'],
    report,
    probe: async () => {
      const tokenFile = join(configuration.environment.HOST_AGENT_STATE_DIR, 'access-token');
      assertPrivateFile(tokenFile);
      const response = await fetch(
        `http://127.0.0.1:${configuration.environment.HOST_AGENT_PORT ?? '8794'}/healthz`,
        {
          headers: { authorization: 'Bearer ' + readFileSync(tokenFile, 'utf8').trim() },
          signal: AbortSignal.timeout(5000),
          redirect: 'error',
        },
      );
      if (!response.ok) throw new Error('Host health unavailable');
      const health = await response.json();
      if (health.service !== 'host-agent' || !['ok', 'degraded'].includes(health.status))
        throw new Error('Host health invalid');
      if (health.status === 'degraded') return 'runtime-degraded';
      return health.persistence?.backgroundAttempt > 0 ||
        health.persistence?.historyRetrying > 0 ||
        health.persistence?.historyStalled > 0
        ? 'persistence-degraded'
        : 'healthy';
    },
  });
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => void supervisor.stop());
  await supervisor.done;
}
