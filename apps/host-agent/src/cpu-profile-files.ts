import { createHash } from 'node:crypto';
import {
  closeSync,
  constants,
  existsSync,
  fstatSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmdirSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';

export const CPU_PROFILE_VERSION = 1;
export const MAX_ARM_MS = 15 * 60_000;
export const MAX_PROFILE_BYTES = 16 * 1024 * 1024;
export const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value);
export function privateDirectory(directory: string, create = false): void {
  if (create) {
    try {
      mkdirSync(directory, { mode: 0o700 });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
    }
  }
  const stat = lstatSync(directory);
  if (
    !stat.isDirectory() ||
    stat.isSymbolicLink() ||
    stat.uid !== process.getuid?.() ||
    stat.mode & 0o077
  )
    throw new Error('CPU_PROFILE_PRIVATE_DIRECTORY_REQUIRED');
}
export function profileDirectory(state: string, create = false): string {
  // Resolve the operator's existing state root, but never follow a diagnostics symlink.
  const root = realpathSync(state);
  privateDirectory(root);
  const directory = join(root, '.cpu-profile');
  privateDirectory(directory, create);
  return directory;
}
export function readPrivateJSON(file: string): Record<string, unknown> {
  const fd = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = fstatSync(fd);
    if (
      !stat.isFile() ||
      stat.nlink !== 1 ||
      stat.uid !== process.getuid?.() ||
      stat.mode & 0o077 ||
      stat.size > 16_384
    )
      throw new Error('CPU_PROFILE_PRIVATE_FILE_REQUIRED');
    const value: unknown = JSON.parse(readFileSync(fd, 'utf8'));
    if (!value || typeof value !== 'object' || Array.isArray(value))
      throw new Error('CPU_PROFILE_INVALID_JSON');
    return value as Record<string, unknown>;
  } finally {
    closeSync(fd);
  }
}
export function writePrivateJSON(file: string, value: unknown, exclusive = false): void {
  const temporary = `${file}.${process.pid}.tmp`;
  const reservation = `${file}.reservation`;
  let reserved = false;
  const cleanup = () => {
    try {
      unlinkSync(temporary);
    } catch {
      /* Preserve publication outcome. */
    }
    if (reserved) {
      try {
        rmdirSync(reservation);
      } catch {
        /* A retained reservation prevents replay. */
      }
    }
  };
  try {
    if (exclusive) {
      // Reserve a separate name: a reader must never see an empty intent file.
      mkdirSync(reservation, { mode: 0o700 });
      reserved = true;
      if (existsSync(file))
        throw Object.assign(new Error('CPU_PROFILE_INTENT_EXISTS'), { code: 'EEXIST' });
    }
    writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
    renameSync(temporary, file);
  } catch (failure) {
    cleanup();
    throw failure;
  }
  cleanup();
}
export function fileIdentity(file: string) {
  const path = realpathSync(file);
  const stat = lstatSync(path);
  if (!stat.isFile()) throw new Error('CPU_PROFILE_REGULAR_PROGRAM_REQUIRED');
  return { path, sha256: createHash('sha256').update(readFileSync(path)).digest('hex') };
}
export function sameIdentity(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
