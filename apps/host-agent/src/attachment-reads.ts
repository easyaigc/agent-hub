import { createHash } from 'node:crypto';
import { lstat, mkdir, mkdtemp, open, realpath, rm, statfs } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import type { BigIntStats } from 'node:fs';
import {
  RuntimeError,
  RuntimeSession,
  openPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '@codex-manage/codex-adapter';
import {
  ATTACHMENT_READ_CHUNK_BYTES,
  AttachmentRangeError,
  attachmentReadRange,
  type AttachmentReadState,
} from '@codex-manage/protocol';

type Prepared = Awaited<ReturnType<RuntimeSession['prepareAttachmentRead']>>;
type Entry = {
  id: string;
  scope: string;
  threadId: string;
  sourceId: string;
  expires: number;
  controller: AbortController;
  work: Promise<void>;
  done: boolean;
  active: number;
  reads: Set<Promise<void>>;
  bytes: number;
  total: number | null;
  reserved: number;
  path?: string | undefined;
  stamp?: string;
  prepared?: Prepared;
  error?: unknown;
};
const stamp = (value: BigIntStats) =>
  [value.dev, value.ino, value.size, value.mtimeNs, value.ctimeNs].join(':');
const lifetime = 10 * 60_000;
const maximumBytes = 2 * 1024 ** 3;

/** Process-owned, verified snapshots. Mutable source bytes never enter a media response. */
export class AttachmentReads {
  private entries = new Map<string, Entry>();
  private directory: Promise<string> | undefined;
  private reserved = 0;
  private preparing = 0;
  private inFlight = new Set<Promise<void>>();
  private closed = false;
  private closing: Promise<void> | undefined;
  private timer: ReturnType<typeof setInterval>;
  constructor(
    private session: RuntimeSession,
    private stateDirectory: string,
  ) {
    this.timer = setInterval(() => {
      for (const entry of this.entries.values())
        if (entry.expires <= Date.now()) {
          entry.controller.abort(new RuntimeError(410, '视频读取已过期，请重新打开'));
          void this.retire(entry).catch(() => {
            /* close() retries owned cleanup. */
          });
        }
    }, 30_000);
    this.timer.unref();
  }
  private async root() {
    if (!this.directory)
      this.directory = (async () => {
        const parent = await realpath(this.stateDirectory);
        const base = join(parent, 'attachment-reads');
        try {
          await mkdir(base, { mode: 0o700 });
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
        }
        const info = await lstat(base);
        if (
          !info.isDirectory() ||
          info.isSymbolicLink() ||
          (await realpath(base)) !== resolve(base) ||
          (process.platform !== 'win32' && (info.mode & 0o077) !== 0)
        )
          throw new RuntimeError(403, '视频缓存目录不满足私有目录要求');
        protectWindowsStateDirectory(base);
        const run = await mkdtemp(join(base, 'run-'));
        protectWindowsStateDirectory(run);
        return run;
      })();
    return this.directory;
  }
  private entry(id: string, scope: string, threadId: string, sourceId: string) {
    const entry = this.entries.get(id.toLowerCase());
    if (!entry || this.closed || entry.expires <= Date.now())
      throw new RuntimeError(410, '视频读取已过期，请重新打开');
    if (entry.scope !== scope || entry.threadId !== threadId || entry.sourceId !== sourceId)
      throw new RuntimeError(403, '视频读取不属于当前目标或资源');
    if (entry.error) throw entry.error;
    entry.controller.signal.throwIfAborted();
    return entry;
  }
  async prepare(
    id: string,
    scope: string,
    threadId: string,
    sourceId: string,
  ): Promise<AttachmentReadState> {
    if (!/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id))
      throw new RuntimeError(400, '视频读取标识无效');
    id = id.toLowerCase();
    if (!this.entries.has(id)) {
      const live = [...this.entries.values()].filter(
        (value) => !value.error && !value.controller.signal.aborted,
      ).length;
      if (this.closed || live >= 16 || this.entries.size >= 256 || this.preparing >= 2)
        throw new RuntimeError(503, '视频准备繁忙，请稍后重试');
      const entry: Entry = {
        id,
        scope,
        threadId,
        sourceId,
        expires: Date.now() + lifetime,
        controller: new AbortController(),
        work: Promise.resolve(),
        done: false,
        active: 0,
        reads: new Set(),
        bytes: 0,
        total: null,
        reserved: 0,
      };
      this.entries.set(id, entry);
      this.preparing++;
      entry.work = this.build(entry).catch((error) => {
        entry.error = error;
      });
    }
    const entry = this.entry(id, scope, threadId, sourceId);
    if (entry.prepared) await entry.prepared.assertSource();
    return {
      version: 1,
      readId: id,
      expiresAt: new Date(entry.expires).toISOString(),
      remainingSeconds: Math.max(0, Math.floor((entry.expires - Date.now()) / 1000)),
      ...(entry.prepared
        ? {
            phase: 'ready',
            attachment: entry.prepared.file,
            etag: `"${entry.prepared.file.sha256}"`,
          }
        : { phase: 'preparing', preparedBytes: entry.bytes, totalBytes: entry.total }),
    };
  }
  private async build(entry: Entry) {
    let failure: unknown;
    const signal = AbortSignal.any([entry.controller.signal, AbortSignal.timeout(120_000)]);
    try {
      const root = await this.root();
      signal.throwIfAborted();
      const path = join(root, entry.id + '.snapshot');
      const output = await openPrivateFile(path, 'wx', 0o600);
      // Only a successfully created file belongs to this entry's cleanup.
      entry.path = path;
      let prepared: Prepared;
      try {
        prepared = await this.session.prepareAttachmentRead(
          entry.threadId,
          entry.sourceId,
          output,
          signal,
          async (size) => {
            if (!Number.isSafeInteger(size) || size < 0 || size > maximumBytes - this.reserved)
              throw new RuntimeError(507, '视频缓存预算不足，请关闭其他视频后重试');
            this.reserved += size;
            entry.reserved = size;
            const volume = await statfs(root);
            if (volume.bavail * volume.bsize < size + 256 * 1024 ** 2)
              throw new RuntimeError(507, '电脑可用空间不足以准备视频');
          },
          (bytes, total) => {
            entry.bytes = bytes;
            entry.total = total;
          },
        );
        signal.throwIfAborted();
        if (process.platform !== 'win32') await output.chmod(0o400);
        entry.stamp = stamp(await output.stat({ bigint: true }));
        protectWindowsStateFile(entry.path);
      } finally {
        await output.close();
      }
      signal.throwIfAborted();
      entry.prepared = prepared;
    } catch (error) {
      failure = entry.controller.signal.aborted
        ? entry.controller.signal.reason
        : signal.aborted
          ? new RuntimeError(504, '视频准备超时，请重新打开')
          : error;
    } finally {
      entry.done = true;
      this.preparing--;
      // Pollers may observe the original error only after the owned snapshot
      // and reservation are retired. Otherwise a terminal 409 races its unlink.
      if (failure || entry.controller.signal.aborted) await this.retire(entry);
      entry.error = failure;
    }
  }
  private async retire(entry: Entry) {
    if (!entry.done || entry.active) return;
    if (entry.path) {
      await rm(entry.path, { force: true });
      entry.path = undefined;
    }
    this.reserved -= entry.reserved;
    entry.reserved = 0;
    // Keep failed/cancelled IDs as tombstones until expiry; late polls cannot restart work.
    if (entry.expires <= Date.now() || this.closed) this.entries.delete(entry.id);
  }
  async cancel(id: string, scope: string, threadId: string, sourceId: string) {
    const entry = this.entries.get(id.toLowerCase());
    if (this.closed) throw new RuntimeError(410, '视频读取已过期，请重新打开');
    if (!entry) {
      if (!/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id))
        throw new RuntimeError(400, '视频读取标识无效');
      if (this.entries.size >= 256) throw new RuntimeError(503, '视频准备繁忙，请稍后重试');
      const controller = new AbortController();
      controller.abort(new RuntimeError(410, '视频读取已取消'));
      // A cancel can overtake preparation during authorization/dispatch.
      // The bounded scoped tombstone prevents a late poll from reopening it.
      this.entries.set(id.toLowerCase(), {
        id: id.toLowerCase(),
        scope,
        threadId,
        sourceId,
        expires: Date.now() + lifetime,
        controller,
        work: Promise.resolve(),
        done: true,
        active: 0,
        reads: new Set(),
        bytes: 0,
        total: null,
        reserved: 0,
      });
      throw new RuntimeError(410, '视频读取已过期，请重新打开');
    }
    if (entry.scope !== scope || entry.threadId !== threadId || entry.sourceId !== sourceId)
      throw new RuntimeError(403, '视频读取不属于当前目标或资源');
    entry.controller.abort(new RuntimeError(410, '视频读取已取消'));
    await entry.work;
    await Promise.allSettled([...entry.reads]);
    await this.retire(entry);
  }
  serve(
    request: IncomingMessage,
    response: ServerResponse,
    id: string,
    scope: string,
    threadId: string,
    sourceId: string,
    assertTarget: () => void,
  ) {
    const work = this.read(request, response, id, scope, threadId, sourceId, assertTarget);
    this.inFlight.add(work);
    const entry = this.entries.get(id.toLowerCase());
    entry?.reads.add(work);
    void work.finally(() => this.inFlight.delete(work)).catch(() => {});
    void work.finally(() => entry?.reads.delete(work)).catch(() => {});
    return work;
  }
  private async read(
    request: IncomingMessage,
    response: ServerResponse,
    id: string,
    scope: string,
    threadId: string,
    sourceId: string,
    assertTarget: () => void,
  ) {
    const entry = this.entry(id, scope, threadId, sourceId);
    if (!entry.prepared || !entry.path)
      throw new RuntimeError(409, '视频仍在准备，请查询同一读取标识');
    if ([...this.entries.values()].reduce((sum, value) => sum + value.active, 0) >= 8)
      throw new RuntimeError(503, '视频读取繁忙，请稍后重试');
    entry.active++;
    let descriptor: Awaited<ReturnType<typeof open>> | undefined;
    try {
      const file = entry.prepared.file;
      const etag = `"${file.sha256}"`;
      if (request.headers['if-range'] !== undefined)
        throw new RuntimeError(400, '固定版本视频读取只接受 If-Match');
      if (!request.headers['if-match']) throw new RuntimeError(428, '视频读取必须指定固定版本');
      if (request.headers['if-match'] !== etag)
        throw new RuntimeError(412, '视频版本不匹配，请重新打开');
      await entry.prepared.assertSource();
      entry.controller.signal.throwIfAborted();
      let range;
      try {
        range =
          request.method === 'HEAD'
            ? null
            : attachmentReadRange(request.headers.range, file.sizeBytes);
      } catch (error) {
        if (!(error instanceof AttachmentRangeError)) throw error;
        if (error.status === 416) response.setHeader('Content-Range', `bytes */${file.sizeBytes}`);
        throw new RuntimeError(error.status, error.message);
      }
      descriptor = await open(entry.path, 'r');
      if (stamp(await descriptor.stat({ bigint: true })) !== entry.stamp)
        throw new RuntimeError(409, '视频缓存已变化，请重新打开');
      entry.controller.signal.throwIfAborted();
      assertTarget();
      response.writeHead(range ? 206 : 200, {
        'Content-Type': file.mediaType,
        'Content-Length': range ? range.end - range.start + 1 : file.sizeBytes,
        ...(range
          ? { 'Content-Range': `bytes ${range.start}-${range.end}/${file.sizeBytes}` }
          : {}),
        'Accept-Ranges': 'bytes',
        ETag: etag,
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
      });
      if (!range) {
        response.end();
        return;
      }
      const targetGuard = new Transform({
        highWaterMark: ATTACHMENT_READ_CHUNK_BYTES,
        transform(bytes, _encoding, done) {
          try {
            assertTarget();
            done(null, bytes);
          } catch (error) {
            done(error as Error);
          }
        },
      });
      await pipeline(
        descriptor.createReadStream({
          start: range.start,
          end: range.end,
          autoClose: false,
          highWaterMark: ATTACHMENT_READ_CHUNK_BYTES,
        }),
        targetGuard,
        response,
        { signal: entry.controller.signal },
      );
    } finally {
      try {
        await descriptor?.close();
      } finally {
        entry.active--;
        if (entry.controller.signal.aborted || this.closed) await this.retire(entry);
      }
    }
  }
  close(): Promise<void> {
    if (this.closing) return this.closing;
    this.closed = true;
    clearInterval(this.timer);
    for (const entry of this.entries.values()) entry.controller.abort();
    this.closing = (async () => {
      await Promise.allSettled([...this.entries.values()].map((entry) => entry.work));
      await Promise.allSettled([...this.inFlight]);
      for (const entry of this.entries.values()) await this.retire(entry);
      if (this.directory) {
        const directory = await this.directory;
        if (!(await lstat(directory)).isSymbolicLink() && (await realpath(directory)) === directory)
          await rm(directory, { recursive: true });
      }
    })();
    return this.closing;
  }
}

/** Secret material never enters a URL, descriptor or diagnostic. */
export function attachmentReadScope(target: string, authorization: string) {
  return createHash('sha256').update(target).update('\0').update(authorization).digest('hex');
}
