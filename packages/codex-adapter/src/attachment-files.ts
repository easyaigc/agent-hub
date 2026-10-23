import { createHash } from 'node:crypto';
import { open, realpath, stat } from 'node:fs/promises';
import type { BigIntStats } from 'node:fs';
import { isAbsolute, relative } from 'node:path';
import { setImmediate } from 'node:timers/promises';

export class AttachmentFileError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

const limit = 20 * 1024 * 1024;
const changed = () => new AttachmentFileError(409, '文件已发生变化，请刷新对话');
const identity = (value: BigIntStats) =>
  [value.dev, value.ino, value.size, value.mtimeNs, value.ctimeNs, value.birthtimeNs].join(':');
type Metadata = { path: string; sizeBytes: number; sha256: string };

/** No file bytes are retained. Stat/hash entries and concurrent reads are bounded. */
export class AttachmentFiles {
  private cache = new Map<string, { identity: string; metadata: Metadata }>();
  private pending = new Map<string, Promise<Metadata>>();
  private active = 0;
  private waiting: (() => void)[] = [];

  constructor(private readonly capacity = 2048) {}

  private async authorized(path: string, roots: string[]): Promise<string> {
    const [actual, allowed] = await Promise.all([
      realpath(path),
      Promise.all(roots.map((root) => realpath(root))),
    ]);
    if (
      !allowed.some((root) => {
        const rel = relative(root, actual);
        return !rel.startsWith('..') && !isAbsolute(rel);
      })
    )
      throw new AttachmentFileError(403, '文件超出电脑授权目录');
    return actual;
  }

  private async checked(path: string, roots: string[]) {
    const actual = await this.authorized(path, roots);
    const value = await stat(actual, { bigint: true });
    if (!value.isFile() || value.size > BigInt(limit)) throw changed();
    return { actual, value, key: identity(value) };
  }

  private async slot<T>(work: () => Promise<T>): Promise<T> {
    if (this.waiting.length >= 128) throw new AttachmentFileError(503, '附件读取繁忙，请稍后重试');
    if (this.active >= 2) await new Promise<void>((done) => this.waiting.push(done));
    else this.active++;
    try {
      return await work();
    } finally {
      const next = this.waiting.shift();
      if (next) next();
      else this.active--;
    }
  }

  private async scan(path: string, roots: string[], bytes: boolean) {
    return this.slot(async () => {
      const before = await this.checked(path, roots);
      const file = await open(before.actual, 'r');
      try {
        if (identity(await file.stat({ bigint: true })) !== before.key) throw changed();
        const hash = createHash('sha256');
        const data = bytes ? Buffer.alloc(Number(before.value.size)) : undefined;
        const chunk = Buffer.alloc(Math.min(1024 * 1024, Number(before.value.size) + 1));
        let position = 0;
        for (;;) {
          const { bytesRead } = await file.read(chunk, 0, chunk.length, position);
          if (!bytesRead) break;
          if (position + bytesRead > Number(before.value.size)) throw changed();
          hash.update(chunk.subarray(0, bytesRead));
          data?.set(chunk.subarray(0, bytesRead), position);
          position += bytesRead;
          // Bound CPU work per event-loop turn, including a fully warm OS page cache.
          await setImmediate();
        }
        if (
          position !== Number(before.value.size) ||
          identity(await file.stat({ bigint: true })) !== before.key
        )
          throw changed();
        const after = await this.checked(path, roots);
        if (after.actual !== before.actual || after.key !== before.key) throw changed();
        return {
          key: before.key,
          metadata: { path: before.actual, sizeBytes: position, sha256: hash.digest('hex') },
          data,
        };
      } finally {
        await file.close();
      }
    });
  }

  async inspect(path: string, roots: string[]): Promise<Metadata> {
    const before = await this.checked(path, roots);
    const cached = this.cache.get(before.actual);
    if (cached?.identity === before.key) {
      this.cache.delete(before.actual);
      this.cache.set(before.actual, cached);
      return cached.metadata;
    }
    this.cache.delete(before.actual);
    const key = before.actual + '\0' + before.key;
    let work = this.pending.get(key);
    if (!work) {
      work = this.scan(path, roots, false).then(({ key, metadata }) => {
        this.cache.delete(metadata.path);
        this.cache.set(metadata.path, { identity: key, metadata });
        while (this.cache.size > this.capacity) this.cache.delete(this.cache.keys().next().value!);
        return metadata;
      });
      this.pending.set(key, work);
    }
    try {
      const metadata = await work;
      // A different caller may have different authorization roots or observe a replacement.
      const after = await this.checked(path, roots);
      if (after.actual !== before.actual || after.key !== before.key) throw changed();
      return metadata;
    } finally {
      if (this.pending.get(key) === work) this.pending.delete(key);
    }
  }

  async read(path: string, roots: string[], expectedHash: string) {
    try {
      // Downloads always read and hash fresh bytes, independently of the metadata cache.
      const { metadata, data } = await this.scan(path, roots, true);
      if (metadata.sha256 !== expectedHash) throw changed();
      return data!;
    } catch (error) {
      if (error instanceof AttachmentFileError) throw error;
      throw changed();
    }
  }
}
