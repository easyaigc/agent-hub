import { createHash, randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { mkdir, lstat, open, readFile, readdir, rename, rm, statfs } from 'node:fs/promises';
import { join } from 'node:path';
import {
  parseUploadManifest,
  UPLOAD_CHUNK_BYTES,
  type UploadManifest,
  type UploadReceipt,
} from '@codex-manage/protocol';
import {
  RuntimeError,
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '@codex-manage/codex-adapter';

/** Owns bytes only. Publication callbacks are idempotent; never execute model input. */
export class ResumableUploads {
  private readonly root: string;
  private readonly active = new Set<string>();
  constructor(
    private readonly hostId: string,
    stateDirectory: string,
    private readonly destination: {
      validate(manifest: UploadManifest): Promise<void>;
      complete(manifest: UploadManifest, path: string): Promise<Record<string, unknown>>;
    },
  ) {
    this.root = join(stateDirectory, 'resumable-uploads');
  }
  private paths(id: string) {
    if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id))
      throw new RuntimeError(400, '无效上传 ID');
    return { record: join(this.root, id + '.json'), bytes: join(this.root, id + '.partial') };
  }
  private async directory() {
    await mkdir(this.root, { recursive: true, mode: 0o700 });
    const value = await lstat(this.root);
    if (!value.isDirectory() || value.isSymbolicLink())
      throw new RuntimeError(409, '上传暂存目录无效');
    protectWindowsStateDirectory(this.root);
  }
  private async syncDirectory() {
    if (process.platform === 'win32') return;
    const file = await open(this.root, 'r');
    try {
      await file.sync();
    } finally {
      await file.close();
    }
  }
  private async save(receipt: UploadReceipt) {
    const path = this.paths(receipt.manifest.id).record;
    const temporary = path + '.' + randomUUID();
    const file = await open(temporary, 'wx', 0o600);
    try {
      await file.writeFile(JSON.stringify(receipt));
      await file.sync();
    } finally {
      await file.close();
    }
    protectWindowsStateFile(temporary);
    try {
      await rename(temporary, path);
      await this.syncDirectory();
    } finally {
      await rm(temporary, { force: true });
    }
  }
  private async read(id: string): Promise<UploadReceipt> {
    const path = this.paths(id).record;
    try {
      assertPrivateFile(path);
      const value = JSON.parse(await readFile(path, 'utf8')) as UploadReceipt;
      const manifest = parseUploadManifest(value.manifest);
      if (
        manifest.id !== id ||
        manifest.hostId !== this.hostId ||
        value.version !== 1 ||
        !['uploading', 'verifying', 'completed', 'cancelled'].includes(value.phase) ||
        !Number.isSafeInteger(value.committedBytes) ||
        value.committedBytes < 0 ||
        value.committedBytes > manifest.sizeBytes ||
        !Number.isFinite(Date.parse(value.createdAt)) ||
        !Number.isFinite(Date.parse(value.updatedAt)) ||
        value.maxChunkBytes !== UPLOAD_CHUNK_BYTES
      )
        throw new Error('invalid-upload-record');
      return value;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT')
        throw new RuntimeError(404, '上传记录不存在');
      if (error instanceof RuntimeError) throw error;
      throw new RuntimeError(409, '上传记录无法读取，请保留原文件');
    }
  }
  private async exclusive<T>(id: string, action: () => Promise<T>) {
    this.paths(id);
    if (this.active.has(id)) throw new RuntimeError(409, '原上传正在处理中，请先查询进度');
    if (this.active.size >= 2) throw new RuntimeError(429, '同时最多上传两个文件');
    this.active.add(id);
    try {
      return await action();
    } finally {
      this.active.delete(id);
    }
  }
  /** Only our inactive, unpublished byte snapshots expire; receipts are retained. */
  async prune() {
    await this.directory();
    for (const name of await readdir(this.root)) {
      if (!/^[a-f0-9-]{36}\.json$/.test(name)) continue;
      const id = name.slice(0, -5);
      if (this.active.has(id) || this.active.size >= 2) continue;
      await this.exclusive(id, async () => {
        const receipt = await this.read(id);
        if (['completed', 'cancelled'].includes(receipt.phase)) {
          await rm(this.paths(id).bytes, { force: true });
          return;
        }
        if (
          !['uploading', 'verifying'].includes(receipt.phase) ||
          receipt.publicationStarted ||
          Date.now() - Date.parse(receipt.updatedAt) <= 7 * 86400_000
        )
          return;
        receipt.phase = 'cancelled';
        receipt.reason = 'expired';
        await this.save(receipt);
        await rm(this.paths(id).bytes, { force: true });
      });
    }
  }
  async prepare(input: unknown) {
    let manifest: UploadManifest;
    try {
      manifest = parseUploadManifest(input);
    } catch (error) {
      throw new RuntimeError(400, (error as Error).message);
    }
    if (manifest.hostId !== this.hostId) throw new RuntimeError(403, '电脑身份不一致');
    await this.prune();
    return this.exclusive(manifest.id, async () => {
      await this.directory();
      try {
        const previous = await this.read(manifest.id);
        if (
          Object.keys(manifest).some(
            (key) =>
              manifest[key as keyof UploadManifest] !==
              previous.manifest[key as keyof UploadManifest],
          )
        )
          throw new RuntimeError(409, '同一上传 ID 的文件和目标不能改变');
        return previous;
      } catch (error) {
        if (!(error instanceof RuntimeError) || error.status !== 404) throw error;
      }
      await this.destination.validate(manifest);
      let pending = 0,
        reserved = 0n;
      for (const name of await readdir(this.root)) {
        if (!/^[a-f0-9-]{36}\.json$/.test(name)) continue;
        const previous = await this.read(name.slice(0, -5));
        if (['uploading', 'verifying'].includes(previous.phase)) {
          pending++;
          reserved += BigInt(previous.manifest.sizeBytes - previous.committedBytes);
          if (previous.manifest.purpose === 'file-transfer')
            reserved += BigInt(previous.manifest.sizeBytes);
        }
      }
      if (pending >= 64) throw new RuntimeError(429, '待完成上传过多，请先继续或取消已有上传');
      const space = await statfs(this.root, { bigint: true });
      const required =
        BigInt(manifest.sizeBytes) * (manifest.purpose === 'file-transfer' ? 2n : 1n);
      if (required + reserved + 32n * 1024n * 1024n > space.bavail * space.bsize)
        throw new RuntimeError(507, '电脑可用空间不足，请释放空间后继续上传');
      try {
        await lstat(this.paths(manifest.id).bytes);
        throw new RuntimeError(409, '存在未登记上传文件，请保留原记录');
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
      }
      const now = new Date().toISOString();
      const receipt: UploadReceipt = {
        version: 1,
        manifest,
        phase: 'uploading',
        committedBytes: 0,
        maxChunkBytes: UPLOAD_CHUNK_BYTES,
        createdAt: now,
        updatedAt: now,
        result: null,
      };
      // Persist identity before creating bytes; a zero-offset record can recover a missing empty file.
      await this.save(receipt);
      const file = await open(this.paths(manifest.id).bytes, 'wx', 0o600);
      try {
        await file.sync();
      } finally {
        await file.close();
      }
      protectWindowsStateFile(this.paths(manifest.id).bytes);
      await this.syncDirectory();
      return receipt;
    });
  }
  async status(id: string) {
    return this.read(id);
  }
  async chunk(id: string, offset: number, sha256: string, bytes: Buffer) {
    if (
      !Number.isSafeInteger(offset) ||
      offset < 0 ||
      !/^[a-f0-9]{64}$/.test(sha256) ||
      !bytes.length ||
      bytes.length > UPLOAD_CHUNK_BYTES
    )
      throw new RuntimeError(400, '上传分块无效');
    if (createHash('sha256').update(bytes).digest('hex') !== sha256)
      throw new RuntimeError(400, '上传分块完整性校验失败');
    return this.exclusive(id, async () => {
      const receipt = await this.read(id);
      if (receipt.phase !== 'uploading') throw new RuntimeError(409, '上传已结束，请查询原回执');
      await this.destination.validate(receipt.manifest);
      if (offset > receipt.committedBytes || offset + bytes.length > receipt.manifest.sizeBytes)
        throw new RuntimeError(409, '上传位置不一致，请查询电脑已保存的位置');
      const path = this.paths(id).bytes;
      try {
        assertPrivateFile(path);
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT' || receipt.committedBytes !== 0)
          throw error;
        const empty = await open(path, 'wx', 0o600);
        try {
          await empty.sync();
        } finally {
          await empty.close();
        }
        protectWindowsStateFile(path);
      }
      const file = await open(path, constants.O_RDWR | constants.O_NOFOLLOW);
      try {
        const stat = await file.stat();
        if (!stat.isFile() || stat.size < receipt.committedBytes)
          throw new RuntimeError(409, '上传暂存文件已变化');
        if (offset < receipt.committedBytes) {
          if (offset + bytes.length > receipt.committedBytes)
            throw new RuntimeError(409, '上传分块与已保存位置重叠');
          const existing = Buffer.alloc(bytes.length);
          const result = await file.read(existing, 0, existing.length, offset);
          if (result.bytesRead !== bytes.length || !existing.equals(bytes))
            throw new RuntimeError(409, '同一上传位置的内容不能改变');
          return receipt;
        }
        // A crash may leave an unacknowledged tail. Only the durable ledger is authoritative.
        await file.truncate(receipt.committedBytes);
        let written = 0;
        while (written < bytes.length) {
          const next = await file.write(bytes, written, bytes.length - written, offset + written);
          if (!next.bytesWritten) throw new RuntimeError(502, '无法保存上传分块');
          written += next.bytesWritten;
        }
        await file.sync();
        receipt.committedBytes += bytes.length;
        receipt.updatedAt = new Date().toISOString();
        await this.save(receipt);
        return receipt;
      } finally {
        await file.close();
      }
    });
  }
  async complete(id: string) {
    return this.exclusive(id, async () => {
      const receipt = await this.read(id);
      if (receipt.phase === 'completed') return receipt;
      if (receipt.phase === 'cancelled') throw new RuntimeError(409, '上传已取消');
      if (receipt.committedBytes !== receipt.manifest.sizeBytes)
        throw new RuntimeError(409, '文件尚未上传完整');
      await this.destination.validate(receipt.manifest);
      receipt.phase = 'verifying';
      await this.save(receipt);
      const path = this.paths(id).bytes;
      assertPrivateFile(path);
      const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
      try {
        if ((await file.stat()).size !== receipt.committedBytes)
          throw new RuntimeError(409, '上传暂存文件长度已变化');
        const hash = createHash('sha256');
        for await (const chunk of file.createReadStream({ autoClose: false })) hash.update(chunk);
        if (hash.digest('hex') !== receipt.manifest.sha256) {
          if (!receipt.publicationStarted) {
            receipt.phase = 'uploading';
            await this.save(receipt);
          }
          throw new RuntimeError(400, '文件完整性校验失败，请取消后重新选择');
        }
      } finally {
        await file.close();
      }
      // This callback must reconcile a crash after publication, before saving this receipt.
      receipt.publicationStarted = true;
      await this.save(receipt);
      receipt.result = await this.destination.complete(receipt.manifest, path);
      receipt.phase = 'completed';
      receipt.updatedAt = new Date().toISOString();
      await this.save(receipt);
      await rm(path, { force: true });
      return receipt;
    });
  }
  async cancel(id: string) {
    return this.exclusive(id, async () => {
      const receipt = await this.read(id);
      if (receipt.phase === 'completed' || receipt.phase === 'cancelled') return receipt;
      if (receipt.phase === 'verifying' && receipt.publicationStarted)
        throw new RuntimeError(409, '正在确认接收结果，请先查询原上传');
      receipt.phase = 'cancelled';
      receipt.updatedAt = new Date().toISOString();
      await this.save(receipt);
      await rm(this.paths(id).bytes, { force: true });
      return receipt;
    });
  }
}
