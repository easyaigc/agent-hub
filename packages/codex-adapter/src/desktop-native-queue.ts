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
  available = false;
  constructor(
    private readonly executable: string,
    private readonly home: string,
  ) {}
  private async connection(): Promise<MetadataConnection> {
    if (this.closed) throw new Error('原生队列连接已关闭');
    if (this.client) return this.client;
    if (this.connecting) return this.connecting;
    const client = new MetadataConnection(this.executable, this.home, this.home, 'queue');
    this.connecting = client
      .initialize()
      .then(() => {
        if (this.closed) {
          client.abort();
          throw new Error('原生队列连接已关闭');
        }
        this.client = client;
        return client;
      })
      .catch((error) => {
        this.available = false;
        client.abort();
        throw error;
      })
      .finally(() => {
        this.connecting = undefined;
      });
    return this.connecting;
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
      if (this.client === client) this.client = undefined;
      client.abort();
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
    const client = this.client;
    this.client = undefined;
    if (client) void client.close().catch(() => client.abort());
  }
}
