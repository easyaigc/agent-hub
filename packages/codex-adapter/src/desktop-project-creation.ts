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
  request?: { name: string; directory: string };
}

/** Durable catalog-only mutations. A repeated request can query, never replay a write. */
export class DesktopProjectCreation {
  private readonly pending = new Map<string, Promise<string>>();
  isPending(operationId: string): boolean {
    return this.pending.has(operationId);
  }
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

  private rejectInactivePrepared(): void {
    let changed = false;
    for (const [id, record] of Object.entries(this.records)) {
      if (
        record.state !== 'prepared' ||
        record.projectId ||
        this.pending.has(id) ||
        !isDeepStrictEqual(record.target, this.target)
      )
        continue;
      // submitting is durable before the official write. A restored prepared
      // entry with no active writer therefore proves this operation was unsent.
      record.state = 'rejected';
      record.error = '项目创建尚未提交，请重新创建';
      changed = true;
    }
    if (changed) this.save();
  }

  submit(
    name: string,
    root: string,
    operationId: string,
    request?: { name: string; directory: string },
  ): Promise<string> {
    if (
      !/^[a-zA-Z0-9_-]{8,100}$/.test(operationId) ||
      ['__proto__', 'prototype', 'constructor'].includes(operationId)
    )
      throw new CodexRejection('项目创建操作 ID 无效');
    this.rejectInactivePrepared();
    const previous = Object.hasOwn(this.records, operationId)
      ? this.records[operationId]
      : undefined;
    if (
      previous &&
      (!isDeepStrictEqual(previous.target, this.target) ||
        previous.name !== name ||
        previous.root !== root ||
        (previous.request && !isDeepStrictEqual(previous.request, request)))
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
    ) {
      this.records[operationId] = {
        target: { ...this.target },
        operationId,
        name,
        root,
        state: 'rejected',
        error: '此目录有待确认的项目创建，请先查询原操作 ID',
        ...(request ? { request: { ...request } } : {}),
      };
      this.save();
      throw new CodexRejection('此目录有待确认的项目创建，请先查询原操作 ID');
    }
    const work = this.run(previous, name, root, operationId, request);
    this.pending.set(operationId, work);
    void work.finally(() => this.pending.delete(operationId)).catch(() => {});
    return work;
  }

  private async recover(record: ProjectCreationRecord): Promise<string> {
    // A later rename/removal does not undo the original confirmed creation.
    if (record.state === 'completed' && record.projectId) return record.projectId;
    if (record.state === 'rejected')
      throw new CodexRejection(record.error ?? '官方接口已拒绝此次项目创建');
    const id = await this.find(record);
    const confirmed = this.records[record.operationId];
    if (confirmed === record && confirmed.state === 'completed' && confirmed.projectId)
      return confirmed.projectId;
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
    request?: { name: string; directory: string },
  ): Promise<string> {
    if (previous) return this.recover(previous);
    const record: ProjectCreationRecord = {
      target: { ...this.target },
      operationId,
      name,
      root,
      state: 'prepared',
      ...(request ? { request: { ...request } } : {}),
    };
    this.records[operationId] = record;
    let writeStarted = false;
    try {
      // A failed initial save also leaves a known-unsent, terminal operation.
      this.save();
      record.projectId = await this.create(record, () => {
        record.state = 'submitting';
        this.save();
        writeStarted = true;
      });
      record.state = 'outcome-unknown';
      this.save();
    } catch (error) {
      record.state =
        !writeStarted || error instanceof CodexRejection ? 'rejected' : 'outcome-unknown';
      record.error =
        error instanceof CodexRejection
          ? error.message
          : !writeStarted
            ? '项目创建尚未提交，请检查电脑状态及目录后重新创建'
            : '官方创建接口返回结果待确认';
      this.save();
      if (record.state === 'rejected') throw error;
    }
    return this.recover(record);
  }
}
