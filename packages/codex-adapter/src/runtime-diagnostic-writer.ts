import { parentPort, workerData } from 'node:worker_threads';
import { appendFileSync, existsSync, lstatSync, mkdirSync, renameSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import {
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from './desktop-platform.js';
import { safeDiagnostic } from './diagnostic-events.js';

if (workerData?.kind !== 'runtime-diagnostic-writer' || !parentPort)
  throw new Error('Diagnostic writer required');
const root = join(workerData.directory, 'diagnostics');
const maxBytes = 2 * 1024 * 1024;
function checked(path: string) {
  assertPrivateFile(path);
  const info = lstatSync(path);
  if (
    !info.isFile() ||
    info.isSymbolicLink() ||
    (process.platform !== 'win32' && (info.uid !== process.getuid?.() || info.mode & 0o077))
  )
    throw new Error('Private diagnostic file required');
  return info;
}
parentPort.on('message', (batch: unknown) => {
  let ok = false;
  try {
    if (!Array.isArray(batch) || batch.length > 64) throw new Error('Invalid diagnostic batch');
    mkdirSync(root, { recursive: true, mode: 0o700 });
    protectWindowsStateDirectory(root);
    // No permission cache; recheck on each batch and fail closed.
    const info = lstatSync(root);
    if (
      !info.isDirectory() ||
      info.isSymbolicLink() ||
      (process.platform !== 'win32' && (info.uid !== process.getuid?.() || info.mode & 0o077))
    )
      throw new Error('Private diagnostic directory required');
    const file = join(root, 'errors.jsonl');
    for (const suffix of ['', '.1', '.2']) {
      const path = file + suffix;
      if (existsSync(path) && Date.now() - checked(path).mtimeMs > 7 * 86400_000) rmSync(path);
    }
    const lines = batch
      .map(
        (value) =>
          JSON.stringify({ at: new Date().toISOString(), ...safeDiagnostic(value) }) + '\n',
      )
      .join('');
    if (existsSync(file) && checked(file).size + Buffer.byteLength(lines) > maxBytes) {
      if (existsSync(file + '.2')) {
        checked(file + '.2');
        rmSync(file + '.2');
      }
      if (existsSync(file + '.1')) {
        checked(file + '.1');
        renameSync(file + '.1', file + '.2');
      }
      renameSync(file, file + '.1');
    }
    // Create/protect before writing any diagnostic bytes.
    if (!existsSync(file)) {
      appendFileSync(file, '', { mode: 0o600 });
      protectWindowsStateFile(file);
    }
    checked(file);
    appendFileSync(file, lines, { mode: 0o600 });
    ok = true;
  } catch {
    /* No recursive logger or raw writer errors. */
  }
  parentPort!.postMessage({ kind: 'diagnostic-ack', ok });
});
