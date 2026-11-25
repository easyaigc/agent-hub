import { isDeepStrictEqual } from 'node:util';
import type { RuntimeTarget } from '@codex-manage/protocol';
import { CodexRejection } from './runtime.js';

export interface ProjectCreationRecord {
  target: RuntimeTarget;
  operationId: string;
  name: string;
  root: string;
  state: 'prepared' | 'submitting' | 'outcome-unknown' | 'completed' | 'rejected';
  projectId?: string;
  error?: string;
}

/** Durable catalog-only mutations. A repeated request can query, never replay a write. */
export class DesktopProjectCreation {
  private readonly pending = new Map<string, Promise<string>>();
  constructor(
    private readonly target: RuntimeTarget,
    private readonly records: Record<string, ProjectCreationRecord>,
    private readonly save: () => void,
    private readonly create: (
      record: ProjectCreationRecord,
      beforeWrite: () => void,
    ) => Promise<string>,
    private readonly find: (record: ProjectCreationRecord) => Promise<string | undefined>,
  ) {}

  submit(name: string, root: string, operationId: string): Promise<string> {
    if (
      !/^[a-zA-Z0-9_-]{8,100}$/.test(operationId) ||
      ['__proto__', 'prototype', 'constructor'].includes(operationId)
    )
      throw new CodexRejection('项目创建操作 ID 无效');
    const previous = Object.hasOwn(this.records, operationId)
      ? this.records[operationId]
      : undefined;
    if (
      previous &&
      (!isDeepStrictEqual(previous.target, this.target) ||
        previous.name !== name ||
        previous.root !== root)
    )
      throw new CodexRejection('创建 ID 已用于不同的项目或电脑目标');
    const pending = this.pending.get(operationId);
    if (pending) return pending;
    if (
      !previous &&
      Object.values(this.records).some(
        (record) =>
          record.root === root &&
          isDeepStrictEqual(record.target, this.target) &&
          ['prepared', 'submitting', 'outcome-unknown'].includes(record.state),
      )
    )
      throw new CodexRejection('此目录有待确认的项目创建，请先查询原操作 ID');
    const work = this.run(previous, name, root, operationId);
    this.pending.set(operationId, work);
    void work.finally(() => this.pending.delete(operationId)).catch(() => {});
    return work;
  }

  private async recover(record: ProjectCreationRecord): Promise<string> {
    if (record.state === 'rejected')
      throw new CodexRejection(record.error ?? '官方接口已拒绝此次项目创建');
    const id = await this.find(record);
    if (id) {
      record.projectId = id;
      record.state = 'completed';
      delete record.error;
      this.save();
      return id;
    }
    record.state = 'outcome-unknown';
    this.save();
    throw new Error('项目创建结果待确认，请使用同一操作 ID 刷新查询；不会自动重新创建');
  }

  private async run(
    previous: ProjectCreationRecord | undefined,
    name: string,
    root: string,
    operationId: string,
  ): Promise<string> {
    if (previous) return this.recover(previous);
    const record: ProjectCreationRecord = {
      target: { ...this.target },
      operationId,
      name,
      root,
      state: 'prepared',
    };
    this.records[operationId] = record;
    // If persistence fails, no official write is attempted.
    this.save();
    try {
      record.projectId = await this.create(record, () => {
        record.state = 'submitting';
        this.save();
      });
      record.state = 'outcome-unknown';
      this.save();
    } catch (error) {
      record.state = error instanceof CodexRejection ? 'rejected' : 'outcome-unknown';
      record.error = error instanceof CodexRejection ? error.message : '官方创建接口返回结果待确认';
      this.save();
      if (record.state === 'rejected') throw error;
    }
    return this.recover(record);
  }
}
