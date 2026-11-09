import type { MetadataExecutable } from './desktop-thread-preparer.js';
import { isDeepStrictEqual } from 'node:util';
import { MetadataConnection } from './desktop-thread-preparer.js';
import { object, list, string, type WireObject } from './runtime.js';

export interface NativeQueuedInput {
  id: string;
  clientUserMessageId: string;
  input: WireObject[];
}
/** Queue metadata only; eventual desktop synchronization. Never starts a model Turn. */
export class DesktopNativeQueue {
  private client: MetadataConnection | undefined;
  private connecting: Promise<MetadataConnection> | undefined;
  private closed = false;
  private initializing: MetadataConnection | undefined;
  private retiring = new Set<MetadataConnection>();
  available = false;
  constructor(
    private readonly executable: MetadataExecutable,
    private readonly home: string,
  ) {}
  private async connection(): Promise<MetadataConnection> {
    if (this.closed) throw new Error('原生队列连接已关闭');
    if (this.client?.ready) return this.client;
    if (this.connecting) return this.connecting;
    const work = this.open();
    this.connecting = work;
    try {
      return await work;
    } finally {
      if (this.connecting === work) this.connecting = undefined;
    }
  }
  private retire(client: MetadataConnection): Promise<void> {
    if (this.client === client) this.client = undefined;
    // Keep the actual connection after an unconfirmed deadline, so a later
    // real close can unblock explicit recovery without losing the old owner.
    this.retiring.add(client);
    const closed = client.retire();
    void closed.then(
      () => this.retiring.delete(client),
      () => {},
    );
    return closed;
  }
  private async open(): Promise<MetadataConnection> {
    if (this.client) await this.retire(this.client);
    await Promise.all([...this.retiring].map((client) => this.retire(client)));
    if (this.closed) throw new Error('原生队列连接已关闭');
    const client = new MetadataConnection(this.executable, this.home, this.home, 'queue');
    this.initializing = client;
    try {
      await client.initialize();
      if (this.closed || !client.ready) throw new Error('原生队列连接已关闭');
      this.client = client;
      return client;
    } catch (error) {
      this.available = false;
      // Settle the closed initialization promptly; replacement still waits on
      // the retained close barrier, even if retirement later fails.
      void this.retire(client).catch(() => {});
      throw error;
    } finally {
      if (this.initializing === client) this.initializing = undefined;
    }
  }
  private item(value: unknown): NativeQueuedInput {
    const item = object(value);
    if (!string(item.id) || !string(item.clientUserMessageId) || !Array.isArray(item.input))
      throw new Error('原生队列响应无效');
    return {
      id: string(item.id),
      clientUserMessageId: string(item.clientUserMessageId),
      input: list(item.input).map(object),
    };
  }
  async list(threadId: string): Promise<NativeQueuedInput[]> {
    const client = await this.connection(),
      result: NativeQueuedInput[] = [],
      cursors = new Set<string>();
    try {
      let cursor: string | undefined;
      do {
        const page = await client.rpc('thread/queue/list', {
          threadId,
          limit: 100,
          ...(cursor ? { cursor } : {}),
        });
        if (!Array.isArray(page.data)) throw new Error('原生队列列表无效');
        result.push(...page.data.map((item) => this.item(item)));
        cursor = string(page.nextCursor) || undefined;
        if (cursor && cursors.has(cursor)) throw new Error('原生队列分页重复');
        if (cursor) cursors.add(cursor);
      } while (cursor);
      this.available = true;
      return result;
    } catch (error) {
      this.available = false;
      await this.retire(client);
      throw error;
    }
  }

  async add(
    threadId: string,
    clientUserMessageId: string,
    input: unknown[],
    beforeWrite: () => void,
  ): Promise<NativeQueuedInput> {
    const client = await this.connection();
    const result = await client.rpc(
      'thread/queue/add',
      { threadId, clientUserMessageId, input },
      beforeWrite,
    );
    const queued = this.item(result.queuedSubmission);
    if (
      queued.clientUserMessageId !== clientUserMessageId ||
      !isDeepStrictEqual(queued.input, input)
    )
      throw new Error('原生队列输入确认不符，结果待核对');
    return queued;
  }
  async remove(threadId: string, id: string, beforeWrite: () => void): Promise<boolean> {
    const client = await this.connection();
    const result = await client.rpc(
      'thread/queue/delete',
      { threadId, queuedSubmissionId: id },
      beforeWrite,
    );
    if (typeof result.deleted !== 'boolean') throw new Error('原生队列删除结果待核对');
    return result.deleted;
  }
  close(): void {
    this.closed = true;
    this.available = false;
    const client = this.client;
    this.client = undefined;
    if (client) void this.retire(client).catch(() => {});
    if (this.initializing) void this.retire(this.initializing).catch(() => {});
  }
}
