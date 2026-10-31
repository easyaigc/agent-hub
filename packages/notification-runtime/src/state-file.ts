import { randomUUID } from 'node:crypto';
import {
  closeSync,
  existsSync,
  fsyncSync,
  lstatSync,
  openSync,
  readFileSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { dirname } from 'node:path';

/** Persist the submission boundary before any network write. */
export function writeNotificationState(
  path: string,
  value: unknown,
  protect?: (path: string) => void,
  create?: (path: string) => number,
): void {
  const temporary = path + '.' + randomUUID() + '.tmp';
  const fd = create ? create(temporary) : openSync(temporary, 'wx', 0o600);
  try {
    writeFileSync(fd, JSON.stringify(value));
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  try {
    protect?.(temporary);
    renameSync(temporary, path);
    if (process.platform !== 'win32') {
      const directory = openSync(dirname(path), 'r');
      try {
        fsyncSync(directory);
      } finally {
        closeSync(directory);
      }
    }
  } finally {
    if (existsSync(temporary)) unlinkSync(temporary);
  }
}

/** Each service state directory has one writer. A dead process may be recovered. */
export function acquireNotificationState(
  path: string,
  protect?: (path: string) => void,
  create?: (path: string) => number,
): () => void {
  const lock = path + '.lock';
  if (existsSync(lock)) {
    const stat = lstatSync(lock);
    if (
      !stat.isFile() ||
      stat.isSymbolicLink() ||
      (process.platform !== 'win32' && (stat.uid !== process.getuid?.() || stat.mode & 0o077))
    )
      throw new Error('无效通知状态锁');
    const existing = JSON.parse(readFileSync(lock, 'utf8')) as { pid: number };
    if (!Number.isSafeInteger(existing.pid) || existing.pid < 1) throw new Error('无效通知状态锁');
    try {
      process.kill(existing.pid, 0);
      throw new Error('通知状态已有运行中的服务');
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error;
      const current = lstatSync(lock);
      if (current.ino !== stat.ino || current.dev !== stat.dev)
        throw new Error('通知服务正在恢复，请重试', { cause: error });
      unlinkSync(lock);
    }
  }
  const owner = JSON.stringify({ pid: process.pid, nonce: randomUUID() });
  const fd = create ? create(lock) : openSync(lock, 'wx', 0o600);
  try {
    writeFileSync(fd, owner);
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  protect?.(lock);
  return () => {
    if (existsSync(lock) && readFileSync(lock, 'utf8') === owner) unlinkSync(lock);
  };
}
