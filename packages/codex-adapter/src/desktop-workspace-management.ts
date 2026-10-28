import type { MetadataExecutable } from './desktop-thread-preparer.js';
import type { WorkspaceManagementRequest } from '@codex-manage/protocol';
import { MetadataConnection, MetadataRejection } from './desktop-thread-preparer.js';
import { CodexRuntime, CodexRejection, object, list, string } from './runtime.js';

type Action = WorkspaceManagementRequest['action'];
/** Fixed catalog mutations only; never resumes a thread or starts a model executor. */
export class DesktopWorkspaceManagement {
  constructor(
    private readonly executable: MetadataExecutable,
    private readonly home: string,
    private readonly sharedWebsocketUrl?: string,
  ) {}
  private async connection<T>(
    work: (client: Pick<MetadataConnection, 'rpc'>) => Promise<T>,
  ): Promise<T> {
    if (this.sharedWebsocketUrl) {
      const runtime = new CodexRuntime({
        mode: 'shared-runtime',
        sharedWebsocketUrl: this.sharedWebsocketUrl,
      });
      runtime.onRequest = (id) => runtime.rejectRequest(id);
      try {
        try {
          await runtime.connect();
        } catch {
          throw new CodexRejection('未能连接官方项目管理接口，操作尚未提交');
        }
        return await work({
          rpc: (method, params) => {
            if (
              !['project/list', 'project/delete', 'thread/list', 'thread/archive'].includes(method)
            )
              throw new Error('Fixed workspace metadata method required');
            return runtime.call(method, params);
          },
        });
      } finally {
        runtime.close();
      }
    }
    const client = new MetadataConnection(this.executable, this.home, this.home, 'management');
    let result: T | undefined;
    let failure: unknown;
    try {
      try {
        await client.initialize();
      } catch {
        throw new CodexRejection('未能连接官方项目管理接口，操作尚未提交');
      }
      result = await work(client);
    } catch (error) {
      failure = error;
    }
    try {
      if ((await client.close()) !== 0) failure ??= new Error('项目管理进程退出待确认');
    } catch (error) {
      failure ??= error;
    }
    if (failure) throw failure;
    return result as T;
  }

  async execute(action: Action, id: string, beforeWrite: () => Promise<void>): Promise<void> {
    return this.connection(async (client) => {
      await beforeWrite();
      try {
        await client.rpc(
          action === 'projects:delete' ? 'project/delete' : 'thread/archive',
          action === 'projects:delete' ? { projectId: id } : { threadId: id },
        );
      } catch (error) {
        if (error instanceof MetadataRejection && error.reason === 'active-writer')
          throw new CodexRejection(
            '电脑端拒绝了此次归档：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。',
          );
        if (error instanceof MetadataRejection || error instanceof CodexRejection)
          throw new CodexRejection('官方接口拒绝此操作，请刷新后核对目标');
        throw error;
      }
    });
  }
  async verify(action: Action, id: string): Promise<boolean> {
    return this.connection(async (client) => {
      const seen = new Set<string>();
      let cursor: string | undefined;
      do {
        const page = await client.rpc(
          action === 'projects:delete' ? 'project/list' : 'thread/list',
          {
            limit: 100,
            ...(cursor ? { cursor } : {}),
            ...(action === 'threads:archive'
              ? { archived: true, useStateDbOnly: true, modelProviders: [] }
              : {}),
          },
        );
        if (!Array.isArray(page.data)) throw new Error('项目管理查询结果无效');
        if (list(page.data).some((row) => object(row).id === id))
          return action === 'threads:archive';
        cursor = string(page.nextCursor) || undefined;
        if (cursor && seen.has(cursor)) throw new Error('项目管理分页游标重复');
        if (cursor) seen.add(cursor);
      } while (cursor);
      return action === 'projects:delete';
    });
  }
}
