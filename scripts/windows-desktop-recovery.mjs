import { randomUUID } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  readFileSync,
  readdirSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import {
  assertPrivateFile,
  inspectDesktopArchive,
  inspectWindowsPipe,
  inspectWindowsCli,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

const failure = (code) =>
  Object.assign(new Error('Windows desktop configuration unavailable'), { code });
const readPrivate = (file) => {
  assertPrivateFile(file);
  return JSON.parse(readFileSync(file, 'utf8'));
};

/** Bounded discovery, never PATH search, shell execution, or desktop restart. */
export function discoverWindowsDesktopCli(
  identity,
  configured,
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
    if (entries.length > 128) throw failure('WINDOWS_CLI_DISCOVERY_LIMIT');
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
      if (error.code !== 'WINDOWS_CLI_REJECTED') throw error;
    }
  }
  // Prefer a CLI physically shipped with this verified owner. Multiple different
  // loose payloads are ambiguous; don't guess by modification time or version.
  const bundled = trusted.filter((v) =>
    adjacent.some((p) => resolve(p).toLowerCase() === v.path.toLowerCase()),
  );
  const choices = bundled.length ? bundled : trusted;
  if (!choices.length) throw failure('WINDOWS_CLI_NOT_FOUND');
  const configuredChoice =
    configured &&
    choices.find((value) => value.path.toLowerCase() === resolve(configured).toLowerCase());
  if (configuredChoice) return configuredChoice.path;
  if (new Set(choices.map((v) => v.sha256)).size !== 1) throw failure('WINDOWS_CLI_AMBIGUOUS');
  return choices[0].path;
}

export function inspectWindowsInstallation(configured) {
  const identity = inspectWindowsPipe();
  const archivePath = join(dirname(identity.executable), 'resources/app.asar');
  const archive = inspectDesktopArchive(archivePath);
  const executable = discoverWindowsDesktopCli(identity, configured);
  return { identity, archivePath, archive, executable };
}

function publishPrivate(file, value) {
  const temporary = file + '.' + randomUUID() + '.tmp';
  let owned = false;
  try {
    writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
    owned = true;
    protectWindowsStateFile(temporary);
    renameSync(temporary, file);
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
