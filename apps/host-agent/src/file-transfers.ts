import { createHash, randomUUID } from 'node:crypto';
import {
  mkdir,
  open,
  readFile,
  readdir,
  rename,
  rm,
  realpath,
  lstat,
  link,
} from 'node:fs/promises';
import { join, resolve, extname, basename } from 'node:path';
import {
  FILE_TRANSFER_MAX_BYTES,
  parseFileTransferManifest,
  type FileTransferManifest,
  type FileTransferReceipt,
  type FileTransferCatalog,
} from '@codex-manage/protocol';
import {
  RuntimeError,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  assertPrivateFile,
} from '@codex-manage/codex-adapter';

/** Host-owned, fixed destination. This service never submits an Agent command. */
export class FileTransfers {
  private readonly records: string;
  private readonly active = new Set<string>();
  constructor(
    private readonly hostId: string,
    stateDirectory: string,
    readonly destinationDirectory: string,
  ) {
    this.records = join(stateDirectory, 'file-transfers');
  }
  private async directory(path: string) {
    await mkdir(path, { recursive: true, mode: 0o700 });
    if (!(await lstat(path)).isDirectory() || (await realpath(path)) !== resolve(path))
      throw new RuntimeError(409, '文件接收目录已变化或包含符号链接，请在电脑上检查');
    protectWindowsStateDirectory(path);
  }
  private recordPath(id: string) {
    if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id))
      throw new RuntimeError(400, '无效传输 ID');
    return join(this.records, id + '.json');
  }
  private destination(manifest: FileTransferManifest) {
    const extension = extname(manifest.name);
    // Stable unique names avoid replacing existing files, including after a lost response.
    return join(
      this.destinationDirectory,
      `${basename(manifest.name, extension)} (${manifest.id})${extension}`,
    );
  }
  private async save(receipt: FileTransferReceipt) {
    await this.directory(this.records);
    const destination = this.recordPath(receipt.id),
      temporary = destination + '.' + randomUUID();
    const file = await open(temporary, 'wx', 0o600);
    try {
      await file.writeFile(JSON.stringify(receipt));
      await file.sync();
    } finally {
      await file.close();
    }
    protectWindowsStateFile(temporary);
    try {
      await rename(temporary, destination);
      await this.syncDirectory(this.records);
    } finally {
      await rm(temporary, { force: true });
    }
  }
  private async syncDirectory(path: string) {
    if (process.platform === 'win32') return;
    const directory = await open(path, 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  }
  private manifest(input: unknown) {
    let value;
    try {
      value = parseFileTransferManifest(input);
    } catch (error) {
      throw new RuntimeError(400, (error as Error).message);
    }
    if (value.hostId !== this.hostId) throw new RuntimeError(403, '电脑身份不一致');
    return value;
  }
  private async read(id: string): Promise<FileTransferReceipt> {
    const path = this.recordPath(id);
    try {
      assertPrivateFile(path);
      const receipt = JSON.parse(await readFile(path, 'utf8')) as FileTransferReceipt;
      const { phase, destinationPath, createdAt, ...manifest } = receipt;
      this.manifest(manifest);
      if (
        !['prepared', 'completed'].includes(phase) ||
        destinationPath !== this.destination(manifest) ||
        !Number.isFinite(Date.parse(createdAt))
      )
        throw new Error('Invalid transfer receipt');
      return receipt;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT')
        throw new RuntimeError(404, '传输记录不存在');
      if (error instanceof RuntimeError) throw error;
      throw new RuntimeError(409, '传输记录无法读取，请在电脑上检查');
    }
  }
  async catalog(): Promise<FileTransferCatalog> {
    await this.directory(this.records);
    const files: FileTransferReceipt[] = [];
    for (const name of await readdir(this.records)) {
      if (!name.endsWith('.json')) continue;
      files.push(await this.status(name.slice(0, -5)));
    }
    files.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    return {
      hostId: this.hostId,
      destinationDirectory: this.destinationDirectory,
      maxFileBytes: FILE_TRANSFER_MAX_BYTES,
      files,
    };
  }
  async prepare(input: unknown): Promise<FileTransferReceipt> {
    const manifest = this.manifest(input);
    return this.exclusive(manifest.id, async () => {
      try {
        const previous = await this.read(manifest.id);
        if (
          Object.entries(manifest).some(
            ([key, value]) => previous[key as keyof FileTransferManifest] !== value,
          )
        )
          throw new RuntimeError(409, '同一传输 ID 的文件内容不能改变');
        return await this.reconcile(previous);
      } catch (error) {
        if (!(error instanceof RuntimeError) || error.status !== 404) throw error;
      }
      await this.directory(this.destinationDirectory);
      const receipt: FileTransferReceipt = {
        ...manifest,
        phase: 'prepared',
        destinationPath: this.destination(manifest),
        createdAt: new Date().toISOString(),
      };
      await this.save(receipt);
      return receipt;
    });
  }
  private async verify(receipt: FileTransferReceipt): Promise<boolean> {
    await this.directory(this.destinationDirectory);
    let file;
    try {
      if (
        !(await lstat(receipt.destinationPath)).isFile() ||
        (await realpath(receipt.destinationPath)) !== receipt.destinationPath
      )
        throw new RuntimeError(409, '电脑上的接收文件已改变');
      file = await open(receipt.destinationPath, 'r');
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false;
      throw error;
    }
    try {
      const before = await file.stat({ bigint: true });
      if (before.size !== BigInt(receipt.sizeBytes))
        throw new RuntimeError(409, '电脑上的接收文件已改变');
      const hash = createHash('sha256');
      for await (const chunk of file.createReadStream({ autoClose: false })) hash.update(chunk);
      const after = await file.stat({ bigint: true });
      const path = await lstat(receipt.destinationPath, { bigint: true });
      if (
        hash.digest('hex') !== receipt.sha256 ||
        before.mtimeNs !== after.mtimeNs ||
        before.ctimeNs !== after.ctimeNs ||
        path.ino !== after.ino ||
        path.dev !== after.dev ||
        !path.isFile()
      )
        throw new RuntimeError(409, '电脑上的接收文件已改变');
      return true;
    } finally {
      await file.close();
    }
  }
  private async reconcile(receipt: FileTransferReceipt) {
    // Completion records delivery at that time. Moving/editing a received file must
    // not break the catalog or cause the original upload to run again.
    if (receipt.phase === 'completed') return receipt;
    if (await this.verify(receipt)) {
      receipt.phase = 'completed';
      await this.save(receipt);
    }
    return receipt;
  }
  async status(id: string) {
    // A GET does not wait on an in-flight upload or replay any bytes.
    const receipt = await this.read(id);
    return this.active.has(id) ? receipt : this.reconcile(receipt);
  }
  private async exclusive<T>(id: string, action: () => Promise<T>): Promise<T> {
    if (this.active.has(id)) throw new RuntimeError(409, '此文件正在上传，请查询原传输结果');
    if (this.active.size >= 2) throw new RuntimeError(429, '同时最多上传两个文件，请稍后重试');
    this.active.add(id);
    try {
      return await action();
    } finally {
      this.active.delete(id);
    }
  }
  async upload(id: string, source: AsyncIterable<Buffer>): Promise<FileTransferReceipt> {
    this.recordPath(id);
    return this.exclusive(id, async () => {
      const receipt = await this.reconcile(await this.read(id));
      if (receipt.phase === 'completed') return receipt;
      await this.directory(this.records);
      await this.directory(this.destinationDirectory);
      // Stage on the destination volume so publishing by exclusive hard link is atomic.
      const stagingDirectory = join(this.destinationDirectory, '.incoming');
      await this.directory(stagingDirectory);
      const staging = join(stagingDirectory, id + '.partial');
      await rm(staging, { force: true });
      const file = await open(staging, 'wx', 0o600);
      protectWindowsStateFile(staging);
      try {
        let size = 0;
        const hash = createHash('sha256');
        for await (const chunk of source) {
          size += chunk.length;
          if (size > receipt.sizeBytes) throw new RuntimeError(413, '上传大小超过文件清单');
          hash.update(chunk);
          await file.writeFile(chunk);
        }
        if (size !== receipt.sizeBytes || hash.digest('hex') !== receipt.sha256)
          throw new RuntimeError(400, '上传文件大小或完整性校验失败');
        await file.sync();
        await file.close();
        await this.directory(this.destinationDirectory);
        await this.directory(stagingDirectory);
        try {
          await link(staging, receipt.destinationPath);
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
          if (!(await this.verify(receipt))) throw new RuntimeError(409, '接收位置已被占用');
        }
        await this.syncDirectory(this.destinationDirectory);
        receipt.phase = 'completed';
        await this.save(receipt);
        return receipt;
      } finally {
        await file.close();
        await rm(staging, { force: true });
      }
    });
  }
}
