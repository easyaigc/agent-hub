import { randomUUID } from 'node:crypto';
import { readFileSync, unlinkSync } from 'node:fs';
import {
  assertPrivateFile,
  inspectWindowsInstallation,
  writePrivateFileSync,
  renamePrivateFileSync,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

const failure = (code) =>
  Object.assign(new Error('Windows desktop configuration unavailable'), { code });
const readPrivate = (file) => {
  assertPrivateFile(file);
  return JSON.parse(readFileSync(file, 'utf8'));
};

// Startup and runtime reconnection use the same bounded native trust checks.
export {
  discoverWindowsDesktopCli,
  inspectWindowsInstallation,
} from '../packages/codex-adapter/dist/desktop-platform.js';

function publishPrivate(file, value) {
  const temporary = file + '.' + randomUUID() + '.tmp';
  let owned = false;
  try {
    writePrivateFileSync(temporary, JSON.stringify(value, null, 2) + '\n', {
      mode: 0o600,
      flag: 'wx',
    });
    owned = true;
    protectWindowsStateFile(temporary);
    renamePrivateFileSync(temporary, file);
    owned = false;
  } finally {
    if (owned) unlinkSync(temporary);
  }
}

/** Called only before a worker owns runtime state or accepts commands. */
export function refreshWindowsHostConfiguration(file) {
  const configuration = readPrivate(file);
  const environment = configuration.environment;
  if (
    environment?.CODEX_RUNTIME_MODE !== 'desktop-runtime' ||
    typeof environment.CODEX_DESKTOP_WINDOWS_CONFIG !== 'string'
  )
    throw failure('WINDOWS_HOST_CONFIG_INVALID');
  const desktopPath = environment.CODEX_DESKTOP_WINDOWS_CONFIG;
  const desktop = readPrivate(desktopPath);
  const installed = inspectWindowsInstallation(environment.CODEX_EXECUTABLE);
  const refreshedDesktop = {
    ...desktop,
    executable: installed.identity.executable,
    archive: installed.archivePath,
    desktopVersion: installed.archive.version,
  };
  const refreshed = {
    ...configuration,
    environment: {
      ...environment,
      CODEX_EXECUTABLE: installed.executable,
      CODEX_DESKTOP_VERSION: installed.archive.version,
    },
  };
  const desktopChanged = JSON.stringify(desktop) !== JSON.stringify(refreshedDesktop);
  const hostChanged = JSON.stringify(configuration) !== JSON.stringify(refreshed);
  // Publish the desktop first. An interrupted pair is repaired idempotently on
  // the next startup, before any model input or runtime state writer exists.
  if (desktopChanged) publishPrivate(desktopPath, refreshedDesktop);
  if (hostChanged) publishPrivate(file, refreshed);
  return { configuration: refreshed, changed: desktopChanged || hostChanged };
}
