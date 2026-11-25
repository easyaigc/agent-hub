import { lstat, opendir } from 'node:fs/promises';
import type { Dir } from 'node:fs';
import { join, relative } from 'node:path';

interface Usage {
  bytes: number;
  files: number;
  directories: number;
}
export interface DesktopTraceStorageUsage extends Usage {
  complete: boolean;
  checkedAt: number;
  skipped: number;
  categories: Record<'raw' | 'quarantine' | 'suggestions' | 'other', Usage>;
}
const empty = (): Usage => ({ bytes: 0, files: 0, directories: 0 });
const initial = (): DesktopTraceStorageUsage => ({
  ...empty(),
  complete: false,
  checkedAt: Date.now(),
  skipped: 0,
  categories: { raw: empty(), quarantine: empty(), suggestions: empty(), other: empty() },
});

/** Advisory accounting only. No path from this inventory authorizes deletion. */
export class DesktopTraceStorageInventory {
  private stack: { path: string; directory: Dir }[] = [];
  private current = initial();
  private previous: DesktopTraceStorageUsage | undefined;
  private rootIdentity: string | undefined;
  private nextScan = 0;
  private closed = false;
  constructor(private readonly root: string) {}
  get snapshot(): DesktopTraceStorageUsage & { scanning: boolean; previousCompleteAt?: number } {
    return {
      ...structuredClone(this.current),
      scanning: this.stack.length > 0,
      ...(this.previous ? { previousCompleteAt: this.previous.checkedAt } : {}),
    };
  }
  async slice(entries = 512, milliseconds = 40): Promise<void> {
    if (this.closed || (!this.stack.length && Date.now() < this.nextScan)) return;
    const root = await lstat(this.root, { bigint: true });
    if (this.closed) return;
    const identity = `${root.dev}:${root.ino}`;
    if (
      !root.isDirectory() ||
      root.isSymbolicLink() ||
      (this.rootIdentity !== undefined && identity !== this.rootIdentity)
    )
      throw new Error('TRACE_STORAGE_ROOT_CHANGED');
    this.rootIdentity = identity;
    if (!this.stack.length) {
      this.current = initial();
      const opened = await opendir(this.root);
      if (this.closed) {
        await opened.close();
        return;
      }
      this.stack.push({ path: this.root, directory: opened });
    }
    const deadline = performance.now() + milliseconds;
    for (
      let count = 0;
      count < entries && this.stack.length && performance.now() < deadline;
      count++
    ) {
      if (this.closed) return;
      const cursor = this.stack.at(-1)!;
      const entry = await cursor.directory.read();
      if (this.closed) return;
      if (!entry) {
        await cursor.directory.close();
        this.stack.pop();
        continue;
      }
      const path = join(cursor.path, entry.name);
      let value;
      try {
        value = await lstat(path, { bigint: true });
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
        this.current.skipped++;
        continue;
      }
      if (this.closed) return;
      if (
        value.isSymbolicLink() ||
        value.dev !== root.dev ||
        (process.platform !== 'win32' && value.uid !== root.uid)
      ) {
        this.current.skipped++;
        continue;
      }
      const first = relative(this.root, path).split(/[\\/]/)[0]!;
      const category = first.startsWith('trace-')
        ? 'raw'
        : first.startsWith('.agent-hub-retired-')
          ? 'quarantine'
          : first === '.agent-hub-suggestions'
            ? 'suggestions'
            : 'other';
      const usage = this.current.categories[category];
      if (value.isDirectory()) {
        this.current.directories++;
        usage.directories++;
        if (this.stack.length >= 8 || (process.platform !== 'win32' && value.mode & 0o022n))
          this.current.skipped++;
        else {
          const opened = await opendir(path);
          if (this.closed) {
            await opened.close();
            return;
          }
          this.stack.push({ path, directory: opened });
        }
      } else if (value.isFile()) {
        const bytes = Number(value.size);
        this.current.files++;
        this.current.bytes += bytes;
        usage.files++;
        usage.bytes += bytes;
      } else this.current.skipped++;
    }
    this.current.checkedAt = Date.now();
    if (!this.stack.length) {
      this.current.complete = this.current.skipped === 0;
      this.previous = structuredClone(this.current);
      this.nextScan = Date.now() + 60_000;
    }
  }
  async close(): Promise<void> {
    this.closed = true;
    const stack = this.stack.splice(0);
    await Promise.allSettled(stack.map(async (entry) => entry.directory.close()));
  }
}
