import {
  machineRuntimeTargetKey,
  type WorkspaceManagementRequest,
  type WorkspaceManagementOperation,
  type RuntimeTarget,
} from '@codex-manage/protocol';
import { CodexRejection } from './runtime.js';

/** Durable intent before each write. Reads may reconcile uncertainty; they never replay it. */
export class WorkspaceManagementBroker {
  private readonly active = new Map<string, Promise<void>>();
  constructor(
    private readonly target: RuntimeTarget,
    private readonly records: Record<string, WorkspaceManagementOperation>,
    private readonly save: () => void,
    private readonly execute: (
      action: WorkspaceManagementRequest['action'],
      id: string,
    ) => Promise<void>,
    private readonly verify: (
      action: WorkspaceManagementRequest['action'],
      id: string,
    ) => Promise<boolean>,
    private readonly completed: (action: WorkspaceManagementRequest['action'], id: string) => void,
  ) {
    for (const record of Object.values(records))
      for (const item of record.items) {
        if (item.state === 'submitting') {
          item.state = 'outcome-unknown';
          item.error = '连接中断，请核对原操作结果';
        }
        if (item.state === 'pending') {
          item.state = 'rejected';
          item.error = '尚未提交，原等待已结束';
        }
      }
  }
  private assertTarget(request: WorkspaceManagementRequest) {
    if (
      request.hostId !== this.target.hostId ||
      machineRuntimeTargetKey(request.target) !== machineRuntimeTargetKey(this.target)
    )
      throw new CodexRejection('批量管理运行目标不一致');
  }
  submit(request: WorkspaceManagementRequest): WorkspaceManagementOperation {
    this.assertTarget(request);
    const old = Object.hasOwn(this.records, request.clientOperationId)
      ? this.records[request.clientOperationId]
      : undefined;
    if (old) {
      if (
        old.action !== request.action ||
        JSON.stringify(old.ids) !== JSON.stringify(request.ids) ||
        machineRuntimeTargetKey(old.target) !== machineRuntimeTargetKey(request.target)
      )
        throw new CodexRejection('操作 ID 已用于不同的批量请求');
      return structuredClone(old);
    }
    for (const record of Object.values(this.records)) {
      if (
        record.action === request.action &&
        machineRuntimeTargetKey(record.target) === machineRuntimeTargetKey(request.target) &&
        record.items.some(
          (item) =>
            request.ids.includes(item.id) &&
            ['pending', 'submitting', 'outcome-unknown'].includes(item.state),
        )
      )
        throw new CodexRejection('所选内容仍有待核对的原操作，请先查询结果');
    }
    const record: WorkspaceManagementOperation = {
      ...structuredClone(request),
      items: request.ids.map((id) => ({ id, state: 'pending', error: null })),
    };
    this.records[request.clientOperationId] = record;
    try {
      this.save();
    } catch (error) {
      delete this.records[request.clientOperationId];
      throw error;
    }
    const work = this.run(record).finally(() => this.active.delete(record.clientOperationId));
    this.active.set(record.clientOperationId, work);
    // A failed durability write stops this batch. Never continue to another item.
    void work.catch(() => {});
    return structuredClone(record);
  }
  private async run(record: WorkspaceManagementOperation) {
    for (const item of record.items) {
      item.state = 'submitting';
      this.save();
      try {
        await this.execute(record.action, item.id);
        item.state = 'completed';
        item.error = null;
        this.completed(record.action, item.id);
      } catch (error) {
        item.state = error instanceof CodexRejection ? 'rejected' : 'outcome-unknown';
        item.error = error instanceof CodexRejection ? error.message : '结果待确认，请核对原操作';
      }
      this.save();
    }
  }
  async status(id: string, target: RuntimeTarget): Promise<WorkspaceManagementOperation> {
    const record = Object.hasOwn(this.records, id) ? this.records[id] : undefined;
    if (!record) throw new CodexRejection('未找到原批量操作，请保留操作记录');
    this.assertTarget(record);
    this.assertTarget({ ...record, target });
    if (!this.active.has(id))
      for (const item of record.items) {
        if (item.state === 'pending') {
          item.state = 'rejected';
          item.error = '尚未提交，原等待已结束';
          this.save();
        }
        if (item.state === 'submitting') {
          item.state = 'outcome-unknown';
          item.error = '结果待确认，请核对原操作';
          this.save();
        }
        if (item.state !== 'outcome-unknown') continue;
        if (await this.verify(record.action, item.id)) {
          item.state = 'completed';
          item.error = null;
          this.completed(record.action, item.id);
          this.save();
        }
      }
    return structuredClone(record);
  }
}
