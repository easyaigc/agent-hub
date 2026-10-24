import { createHash } from 'node:crypto';
import { open, realpath, stat } from 'node:fs/promises';
import type { BigIntStats } from 'node:fs';
import { isAbsolute, relative, resolve } from 'node:path';
import { setImmediate } from 'node:timers/promises';

export class AttachmentFileError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
  ) {
    super(message);
    this.name = 'AttachmentFileError';
  }
}

const limit = 20 * 1024 * 1024;
const changed = () => new AttachmentFileError(409, '文件已发生变化，请刷新对话', 'FILE_CHANGED');
export function attachmentFileError(error: unknown): AttachmentFileError {
  if (error instanceof AttachmentFileError) return error;
  const code = (error as NodeJS.ErrnoException)?.code;
  const known: Record<string, [number, string]> = {
    ENOENT: [410, '电脑上的文件不存在或已被移除'],
    ENOTDIR: [410, '文件路径中的目录不存在或不是目录'],
    EACCES: [403, '电脑系统拒绝读取文件，请检查文件权限'],
    EPERM: [403, '电脑系统拒绝读取文件，请检查系统隐私与文件权限'],
    ELOOP: [422, '文件路径存在循环符号链接'],
    EISDIR: [422, '此链接指向目录，无法作为文件预览'],
    EMFILE: [503, '电脑打开的文件过多，请稍后重试'],
    ENFILE: [503, '电脑系统文件句柄不足，请稍后重试'],
    EIO: [502, '电脑读取文件时发生磁盘输入输出错误'],
  };
  const detail = code && known[code];
  const failure = detail
    ? new AttachmentFileError(detail[0], detail[1] + `（${code}）`, code)
    : new AttachmentFileError(502, '电脑读取文件失败', 'FILE_READ_FAILED');
  failure.cause = error;
  return failure;
}
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

  private async authorized(
    path: string,
    roots: string[],
    exactPaths: readonly string[],
    fixedPath: boolean,
  ): Promise<string> {
    const [actual, allowed] = await Promise.all([
      realpath(path),
      Promise.all(roots.map((root) => realpath(root))),
    ]);
    if (fixedPath && actual !== resolve(path))
      throw new AttachmentFileError(
        403,
        '文件链接的真实目标已变化，请在电脑上核对原消息',
        'RESOURCE_TARGET_CHANGED',
      );
    if (
      !exactPaths.some((path) => isAbsolute(path) && actual === resolve(path)) &&
      !allowed.some((root) => {
        const rel = relative(root, actual);
        return !rel.startsWith('..') && !isAbsolute(rel);
      })
    )
      throw new AttachmentFileError(403, '文件没有当前聊天的读取授权', 'RESOURCE_NOT_AUTHORIZED');
    return actual;
  }

  private async checked(
    path: string,
    roots: string[],
    exactPaths: readonly string[],
    fixedPath = false,
  ) {
    try {
      const actual = await this.authorized(path, roots, exactPaths, fixedPath);
      const value = await stat(actual, { bigint: true });
      if (!value.isFile())
        throw new AttachmentFileError(
          422,
          '此链接指向目录或非普通文件，无法预览',
          'NOT_REGULAR_FILE',
        );
      if (value.size > BigInt(limit))
        throw new AttachmentFileError(413, '文件超过 20 MB，无法下载预览', 'FILE_TOO_LARGE');
      return { actual, value, key: identity(value) };
    } catch (error) {
      throw attachmentFileError(error);
    }
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

  private async scan(
    path: string,
    roots: string[],
    bytes: boolean,
    exactPaths: readonly string[] = [],
    fixedPath = false,
  ) {
    return this.slot(async () => {
      const before = await this.checked(path, roots, exactPaths, fixedPath);
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
        const after = await this.checked(path, roots, exactPaths, fixedPath);
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

  async inspect(
    path: string,
    roots: string[],
    exactPaths: readonly string[] = [],
    fixedPath = false,
  ): Promise<Metadata> {
    const before = await this.checked(path, roots, exactPaths, fixedPath);
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
      work = this.scan(path, roots, false, exactPaths, fixedPath).then(({ key, metadata }) => {
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
      const after = await this.checked(path, roots, exactPaths, fixedPath);
      if (after.actual !== before.actual || after.key !== before.key) throw changed();
      return metadata;
    } catch (error) {
      throw attachmentFileError(error);
    } finally {
      if (this.pending.get(key) === work) this.pending.delete(key);
    }
  }

  async read(
    path: string,
    roots: string[],
    expectedHash: string,
    exactPaths: readonly string[] = [],
    fixedPath = false,
  ) {
    try {
      // Downloads always read and hash fresh bytes, independently of the metadata cache.
      const { metadata, data } = await this.scan(path, roots, true, exactPaths, fixedPath);
      if (metadata.sha256 !== expectedHash) throw changed();
      return data!;
    } catch (error) {
      if (error instanceof AttachmentFileError) throw error;
      throw attachmentFileError(error);
    }
  }
}
