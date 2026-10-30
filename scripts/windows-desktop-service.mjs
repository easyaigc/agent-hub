import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  assertPrivateFile,
  windowsPowerShellEnvironment,
  makePrivateDirectorySync,
  writePrivateFileSync,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  windowsHostLauncherConfiguration,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { withWindowsProcessCreation } from '../packages/codex-adapter/dist/windows-process.js';

import { inspectWindowsInstallation } from './windows-desktop-recovery.mjs';

if (process.platform !== 'win32') throw new Error('Use this service on native Windows');
const action = process.argv[2] ?? 'status';
if (!['preflight', 'start', 'stop', 'status', 'restart'].includes(action))
  throw new Error('Use preflight/start/stop/status/restart');
const repository = resolve(fileURLToPath(new URL('..', import.meta.url)));
const directory = join(repository, '.local/desktop-runtime');
const jobConfig = join(directory, 'windows-host.json');
const desktopConfig = join(directory, 'windows-desktop.json');
const powershell = join(
  process.env.SystemRoot ?? 'C:\\Windows',
  'System32/WindowsPowerShell/v1.0/powershell.exe',
);
if (['preflight', 'start', 'restart'].includes(action)) {
  let configuredCli = process.env.CODEX_EXECUTABLE;
  if (!configuredCli && existsSync(jobConfig)) {
    assertPrivateFile(jobConfig);
    configuredCli = JSON.parse(readFileSync(jobConfig, 'utf8')).environment?.CODEX_EXECUTABLE;
  }
  const { identity, archivePath, archive, executable } = inspectWindowsInstallation(configuredCli);
  if (action === 'preflight') {
    console.log(
      JSON.stringify(
        {
          status: 'passed',
          platform: 'win32',
          desktopVersion: archive.version,
          versionPolicy: 'metadata-only',
          ...identity,
          cli: executable,
          productionVerified: false,
          acceptancePolicy: 'report-only',
        },
        null,
        2,
      ),
    );
    process.exit(0);
  }
  makePrivateDirectorySync(directory);
  protectWindowsStateDirectory(directory);
  let desktop;
  if (existsSync(desktopConfig)) {
    assertPrivateFile(desktopConfig);
    desktop = JSON.parse(readFileSync(desktopConfig, 'utf8'));
    if (desktop.executable.toLowerCase() !== identity.executable.toLowerCase())
      console.warn('桌面安装已变化，使用已核对身份的实际安装继续；旧验收结果需复验。');
  } else {
    desktop = {
      executable: identity.executable,
      archive: archivePath,
      desktopVersion: archive.version,
    };
  }
  if (desktop.validationOnly)
    console.warn('已解除旧版 Windows 专用验收聊天限制，已有聊天按实际 owner 开放控制。');
  desktop.executable = identity.executable;
  desktop.archive = archivePath;
  desktop.validationOnly = false;
  // A test target is optional and only used by the acceptance script.
  if (process.env.DESKTOP_WINDOWS_TEST_THREAD_ID)
    desktop.validationThreadId = process.env.DESKTOP_WINDOWS_TEST_THREAD_ID;
  let previousConfiguration = {};
  let previous = {};
  if (existsSync(jobConfig)) {
    assertPrivateFile(jobConfig);
    previousConfiguration = JSON.parse(readFileSync(jobConfig, 'utf8'));
    previous = previousConfiguration.environment;
  }
  // Compatibility default cwd only; original desktop projects own their paths.
  const root = process.env.HOST_AGENT_ROOT ?? previous.HOST_AGENT_ROOT ?? process.cwd();
  const environment = {
    ...previous,
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    CODEX_DESKTOP_VERSION: archive.version,
    CODEX_EXECUTABLE: executable,
    CODEX_DESKTOP_WINDOWS_CONFIG: desktopConfig,
    HOST_AGENT_ROOT: resolve(root),
    HOST_AGENT_STATE_DIR: directory,
    HOST_AGENT_NAME:
      process.env.HOST_AGENT_NAME ??
      (previous.HOST_AGENT_NAME === 'Windows Codex · 原桌面'
        ? '本机 Windows'
        : previous.HOST_AGENT_NAME) ??
      '本机 Windows',
    HOST_AGENT_PORT: process.env.HOST_AGENT_PORT ?? previous.HOST_AGENT_PORT ?? '8794',
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
    'HOST_RESPONSES_PROXY_IMAGE_MODELS',
    'HOST_RESPONSES_PROXY_IMAGE_MAX_BYTES',
    'HOST_RESPONSES_PROXY_IMAGE_CONCURRENCY',
    'HOST_RESPONSES_PROXY_IMAGE_TIMEOUT_MS',
    'CODEX_DESKTOP_SUGGESTION_OBSERVER',
    'CODEX_DESKTOP_SUGGESTION_TRACE_ROOT',
  ]) {
    if (process.env[key] !== undefined) environment[key] = process.env[key];
  }
  if (process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE !== undefined)
    environment.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE = process.env
      .HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE
      ? resolve(process.env.HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE)
      : '';
  if (process.env.CODEX_DESKTOP_CONTROLLER_CONFIG)
    environment.CODEX_DESKTOP_CONTROLLER_CONFIG = resolve(
      process.env.CODEX_DESKTOP_CONTROLLER_CONFIG,
    );
  if (process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG)
    environment.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG = resolve(
      process.env.CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG,
    );
  writePrivateFileSync(desktopConfig, JSON.stringify(desktop, null, 2) + '\n', { mode: 0o600 });
  writePrivateFileSync(
    jobConfig,
    JSON.stringify({ ...previousConfiguration, environment }, null, 2) + '\n',
    { mode: 0o600 },
  );
  protectWindowsStateFile(desktopConfig);
  protectWindowsStateFile(jobConfig);
}
// The task enforces a total deadline; reserve time for PowerShell startup,
// one in-flight native operation and reporting an explicit failure.
const commandBudgetMs = ['stop', 'restart'].includes(action) ? 45_000 : 20_000;
const launcher = windowsHostLauncherConfiguration();
const result = withWindowsProcessCreation(commandBudgetMs + 15_000, (timeout) =>
  execFileSync(
    powershell,
    [
      '-NoProfile',
      '-NonInteractive',
      '-File',
      join(repository, 'scripts/windows-host-task.ps1'),
      '-Action',
      action,
      '-NodePath',
      process.execPath,
      '-LauncherPath',
      launcher.executable,
      '-LauncherRootsBase64',
      Buffer.from(JSON.stringify(launcher.roots)).toString('base64'),
      '-WorkerPath',
      join(repository, 'scripts/windows-desktop-worker.mjs'),
      '-ConfigPath',
      jobConfig,
      '-CommandBudgetMs',
      String(commandBudgetMs),
    ],
    {
      encoding: 'utf8',
      stdio: 'pipe',
      timeout,
      windowsHide: true,
      env: windowsPowerShellEnvironment(),
    },
  ),
);
console.log(result.trim());
