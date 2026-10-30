import type { MetadataExecutable } from './desktop-thread-preparer.js';
import { createHash, randomUUID } from 'node:crypto';
import { closeSync, existsSync, fsyncSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import type { ThreadForkOperation, ThreadForkRequest } from '@codex-manage/protocol';
import { BeforeDesktopSubmit, CreationError } from './desktop-creation.js';
import { DesktopThreadPreparer, MetadataRejection } from './desktop-thread-preparer.js';
import {
  makePrivateDirectorySync,
  openPrivateFileSync,
  renamePrivateFileSync,
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from './desktop-platform.js';
import { prepareForkWorktree } from './fork-worktree.js';

export interface ForkSource {
  cwd: string;
  projectId: string | null;
  title: string;
  model?: string;
  effort?: string | null;
}
interface ForkRecord extends ThreadForkOperation {
  fingerprint: string;
  sourceDirectory: string;
  directory: string;
  title: string;
  model?: string;
  effort?: string | null;
  checkpoint:
    | 'intent'
    | 'worktree-preparing'
    | 'worktree-prepared'
    | 'fork-written'
    | 'identified'
    | 'materialized'
    | 'released'
    | 'opening';
  preparationPid: number | null;
  preparationExitCode: number | null;
}
const digest = (value: string) => createHash('sha256').update(value).digest('hex');
const idOK = (id: string) =>
  /^[a-zA-Z0-9_-]{8,100}$/.test(id) && !['constructor', 'prototype', '__proto__'].includes(id);
const intent = (request: ThreadForkRequest) =>
  digest(
    JSON.stringify({
      hostId: request.hostId,
      threadId: request.threadId,
      turnId: request.turnId,
      clientOperationId: request.clientOperationId,
      environment: request.environment,
    }),
  );

/** Durable fork intent. Queries only observe; unknown submissions are never replayed. */
export class DesktopForkBroker {
  private readonly records = new Map<string, ForkRecord>();
  private readonly work = new Map<string, Promise<void>>();
  private readonly confirmations = new Map<string, Promise<void>>();
  private readonly preparer: DesktopThreadPreparer;
  private closed = false;
  constructor(
    private readonly directory: string,
    private readonly hostId: string,
    executable: MetadataExecutable,
    home: string,
    private readonly open: (threadId: string, operationId: string) => Promise<boolean>,
    private readonly verify: (record: ThreadForkOperation, cwd: string) => Promise<boolean>,
    private readonly worktreeDirectory = join(directory, 'worktrees'),
  ) {
    makePrivateDirectorySync(directory);
    protectWindowsStateDirectory(directory);
    this.preparer = new DesktopThreadPreparer(executable, home);
  }
  private path(id: string): string {
    if (!idOK(id)) throw new CreationError(400, '分支操作 ID 无效');
    return join(this.directory, id + '.json');
  }
  private save(record: ForkRecord): void {
    const file = this.path(record.clientOperationId);
    const temporary = file + '.' + randomUUID() + '.tmp';
    const fd = openPrivateFileSync(temporary, 'wx', 0o600);
    try {
      writeFileSync(fd, JSON.stringify(record));
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
    protectWindowsStateFile(temporary);
    renamePrivateFileSync(temporary, file);
    if (process.platform !== 'win32') {
      const fd = openSync(this.directory, 'r');
      try {
        fsyncSync(fd);
      } finally {
        closeSync(fd);
      }
    }
  }
  private read(id: string): ForkRecord | undefined {
    if (this.closed) throw new CreationError(503, '分支桥接已关闭');
    const file = this.path(id);
    const current = this.records.get(id);
    if (current) return current;
    if (!existsSync(file)) return undefined;
    assertPrivateFile(file);
    const record: ForkRecord = JSON.parse(readFileSync(file, 'utf8'));
    if (
      record.hostId !== this.hostId ||
      record.clientOperationId !== id ||
      record.fingerprint !== intent(record)
    )
      throw new CreationError(409, '分支回执与当前电脑或提交意图不一致');
    if (record.phase === 'submitted') {
      record.phase = 'outcome-unknown';
      record.error = '分支提交结果待确认，请查询原操作';
    }
    this.records.set(id, record);
    return record;
  }
  private view(record: ForkRecord): ThreadForkOperation {
    return {
      hostId: record.hostId,
      threadId: record.threadId,
      turnId: record.turnId,
      clientOperationId: record.clientOperationId,
      environment: record.environment,
      phase: record.phase,
      forkedThreadId: record.forkedThreadId,
      projectId: record.projectId,
      error: record.error,
    };
  }
  existing(request: ThreadForkRequest): ThreadForkOperation | undefined {
    const record = this.read(request.clientOperationId);
    if (!record) return undefined;
    if (record.fingerprint !== intent(request))
      throw new CreationError(409, '操作 ID 已用于其他分支意图');
    return this.view(record);
  }
  submit(request: ThreadForkRequest, source: ForkSource): ThreadForkOperation {
    if (request.hostId !== this.hostId) throw new CreationError(403, '分支电脑身份不一致');
    const old = this.existing(request);
    if (old) return old;
    const record: ForkRecord = {
      ...request,
      phase: 'submitted',
      forkedThreadId: null,
      projectId: source.projectId,
      error: null,
      fingerprint: intent(request),
      sourceDirectory: source.cwd,
      directory: source.cwd,
      title: source.title,
      ...(source.model ? { model: source.model } : {}),
      ...(source.effort !== undefined ? { effort: source.effort } : {}),
      checkpoint: 'intent',
      preparationPid: null,
      preparationExitCode: null,
    };
    // No filesystem or API side effect occurs before the immutable intent is durable.
    this.save(record);
    this.records.set(request.clientOperationId, record);
    const work = this.perform(record);
    this.work.set(request.clientOperationId, work);
    void work.finally(() => this.work.delete(request.clientOperationId)).catch(() => {});
    return this.view(record);
  }
  private async perform(record: ForkRecord): Promise<void> {
    try {
      if (record.environment === 'worktree') {
        const root = join(this.worktreeDirectory, digest(record.clientOperationId).slice(0, 24));
        makePrivateDirectorySync(root);
        protectWindowsStateDirectory(root);
        record.checkpoint = 'worktree-preparing';
        this.save(record);
        const result = await prepareForkWorktree(
          record.sourceDirectory,
          join(root, basename(record.sourceDirectory)),
        );
        record.directory = result.cwd;
        record.checkpoint = 'worktree-prepared';
        this.save(record);
      }
      await this.preparer.fork(
        {
          sourceThreadId: record.threadId,
          turnId: record.turnId,
          cwd: record.directory,
          title: record.title,
          ...(record.model ? { model: record.model } : {}),
          ...(record.effort !== undefined ? { effort: record.effort } : {}),
        },
        {
          starting: (pid) => {
            record.preparationPid = pid;
            record.checkpoint = 'fork-written';
            this.save(record);
          },
          identified: (id) => {
            record.forkedThreadId = id;
            record.checkpoint = 'identified';
            this.save(record);
          },
          materialized: () => {
            record.checkpoint = 'materialized';
            this.save(record);
          },
          exited: (code) => {
            record.preparationExitCode = code;
            this.save(record);
          },
        },
      );
      record.checkpoint = 'released';
      this.save(record);
      if (this.closed) throw new Error('分支服务已关闭');
      record.checkpoint = 'opening';
      this.save(record);
      if (!(await this.open(record.forkedThreadId!, record.clientOperationId)))
        throw new Error('原桌面接管结果待确认');
      await this.confirm(record);
    } catch (error) {
      const beforeFork = ['intent', 'worktree-preparing', 'worktree-prepared'].includes(
        record.checkpoint,
      );
      record.phase =
        (beforeFork ||
          error instanceof BeforeDesktopSubmit ||
          error instanceof MetadataRejection) &&
        !record.forkedThreadId
          ? 'rejected'
          : 'outcome-unknown';
      record.error =
        record.phase === 'rejected'
          ? beforeFork && record.environment === 'worktree'
            ? '工作树准备未完成，尚未创建对话；请检查源目录改动、Git 状态和可用空间后重试'
            : '分支未创建，请刷新后重试'
          : '分支结果待确认，请查询原操作；不会重复创建';
      try {
        this.save(record);
      } catch {
        /* The last durable checkpoint still forbids replay. */
      }
    }
  }
  private async confirm(record: ForkRecord): Promise<void> {
    if (
      !record.forkedThreadId ||
      record.preparationExitCode !== 0 ||
      !['materialized', 'released', 'opening'].includes(record.checkpoint) ||
      ['confirmed', 'rejected'].includes(record.phase)
    )
      return;
    const verified = await this.verify(record, record.directory);
    if (['confirmed', 'rejected'].includes(record.phase)) return;
    if (verified) {
      record.phase = 'confirmed';
      record.error = null;
      this.save(record);
    } else {
      record.phase = 'outcome-unknown';
      record.error = '新对话已创建，原桌面状态待确认';
      this.save(record);
    }
  }
  async query(threadId: string, id: string): Promise<ThreadForkOperation> {
    const record = this.read(id);
    if (!record || record.threadId !== threadId)
      throw new CreationError(404, '分支操作不存在或目标不一致');
    if (record.phase === 'outcome-unknown' && !this.work.has(id)) {
      let confirmation = this.confirmations.get(id);
      if (!confirmation) {
        confirmation = this.confirm(record);
        this.confirmations.set(id, confirmation);
        void confirmation.finally(() => this.confirmations.delete(id)).catch(() => {});
      }
      await confirmation;
    }
    return this.view(record);
  }
  close(): void {
    this.closed = true;
    this.preparer.close();
  }
}
