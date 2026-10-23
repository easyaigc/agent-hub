import { withWindowsProcessCreation } from './windows-process.js';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createHash, randomUUID } from 'node:crypto';
import {
  mkdirSync,
  existsSync,
  readFileSync,
  readdirSync,
  openSync,
  writeFileSync,
  fsyncSync,
  closeSync,
  renameSync,
} from 'node:fs';
import { join } from 'node:path';
import {
  machineRuntimeTargetKey,
  type ChatActionReceipt,
  type ChatActionRequest,
} from '@codex-manage/protocol';
import {
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from './desktop-platform.js';
import { CodexRuntime, CodexRejection, object, type WireObject } from './runtime.js';
import { CreationError } from './desktop-creation.js';

/** Inspected built-in server section in the current desktop profile. Never a phone section ID. */
export { desktopPinnedSection } from './desktop-sidebar.js';
import { desktopPinnedSection } from './desktop-sidebar.js';
const methods = {
  pin: 'thread/section/move',
  unpin: 'thread/section/move',
  archive: 'thread/archive',
  unarchive: 'thread/unarchive',
  delete: 'thread/delete',
} as const;
class MetadataRejected extends Error {
  constructor(
    readonly definitive: boolean,
    message: string,
  ) {
    super(message);
  }
}
interface ChatMetadataConnection {
  initialize(): Promise<void>;
  inspect(threadId: string): Promise<WireObject>;
  apply(request: ChatActionRequest, beforeWrite: () => void): Promise<void>;
  close(): void;
}
/** A second connection to the selected service; never resumes or executes a thread. */
class SharedChatMetadata implements ChatMetadataConnection {
  private runtime: CodexRuntime;
  constructor(url: string) {
    this.runtime = new CodexRuntime({ mode: 'shared-runtime', sharedWebsocketUrl: url });
    this.runtime.onRequest = (id) => this.runtime.rejectRequest(id);
  }
  async initialize() {
    await this.runtime.connect();
  }
  private async call(
    method: 'thread/read' | (typeof methods)[keyof typeof methods],
    params: WireObject,
  ) {
    try {
      return await this.runtime.call(method, params);
    } catch (error) {
      if (error instanceof CodexRejection)
        throw new MetadataRejected(
          [-32600, -32601, -32602].includes(error.code ?? 0),
          '官方共享服务拒绝此聊天操作',
        );
      throw error;
    }
  }
  async inspect(threadId: string) {
    return object((await this.call('thread/read', { threadId, includeTurns: false })).thread);
  }
  async apply(request: ChatActionRequest, beforeWrite: () => void) {
    beforeWrite();
    await this.call(methods[request.action], {
      threadId: request.threadId,
      ...(['pin', 'unpin'].includes(request.action)
        ? {
            sectionId: request.action === 'pin' ? desktopPinnedSection : null,
            beforeThreadId: null,
          }
        : {}),
    });
  }
  close() {
    this.runtime.close();
  }
}
/** Metadata-only process. No resume/start/turn/shell, no reader privilege expansion. */
class ChatMetadata {
  private child: ChildProcessWithoutNullStreams;
  private next = 0;
  private startupTimeout = 15000;
  private pending = new Map<
    number,
    { resolve: (v: WireObject) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }
  >();
  constructor(executable: string, home: string) {
    this.child = withWindowsProcessCreation(15000, (remaining) => {
      this.startupTimeout = remaining;
      return spawn(executable, ['app-server', '--stdio'], {
        windowsHide: true,
        env: { ...process.env, CODEX_HOME: home },
        stdio: 'pipe',
      });
    });
    this.child.stderr.resume();
    this.child.on('error', () => this.fail());
    this.child.on('exit', () => this.fail());
    this.child.stdin.on('error', () => this.fail());
    createInterface({ input: this.child.stdout }).on('line', (line) => {
      try {
        const row = object(JSON.parse(line));
        const task = this.pending.get(Number(row.id));
        if (!task) return;
        this.pending.delete(Number(row.id));
        clearTimeout(task.timer);
        if (row.error) {
          const error = object(row.error);
          task.reject(
            new MetadataRejected(
              [-32600, -32601, -32602].includes(Number(error.code)),
              typeof error.message === 'string' &&
                error.message.includes('already has an active writer')
                ? '电脑端拒绝了此次操作：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。'
                : '原桌面 API 拒绝此操作',
            ),
          );
        } else task.resolve(object(row.result));
      } catch {
        this.fail();
      }
    });
  }
  private fail() {
    for (const task of this.pending.values()) {
      clearTimeout(task.timer);
      task.reject(new Error('聊天管理连接已关闭'));
    }
    this.pending.clear();
  }
  private rpc(method: string, params: WireObject, beforeWrite?: () => void): Promise<WireObject> {
    if (!['initialize', 'thread/read', ...Object.values(methods)].includes(method))
      throw new Error('不支持此元数据方法');
    if (this.child.killed || this.child.exitCode !== null) throw new Error('聊天管理连接不可用');
    beforeWrite?.();
    const id = ++this.next;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => {
          this.pending.delete(id);
          reject(new Error('聊天操作结果待确认'));
        },
        method === 'initialize' ? this.startupTimeout : 15000,
      );
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(JSON.stringify({ id, method, params }) + '\n', (error) => {
        if (error) this.fail();
      });
    });
  }
  async initialize() {
    await this.rpc('initialize', {
      clientInfo: { name: 'agent_hub_chat_metadata', version: '0.1.0' },
      capabilities: { experimentalApi: true },
    });
    this.child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
  }
  async inspect(threadId: string) {
    return object((await this.rpc('thread/read', { threadId, includeTurns: false })).thread);
  }
  async apply(request: ChatActionRequest, beforeWrite: () => void) {
    const { threadId, action } = request;
    await this.rpc(
      methods[action],
      {
        threadId,
        ...(['pin', 'unpin'].includes(action)
          ? { sectionId: action === 'pin' ? desktopPinnedSection : null, beforeThreadId: null }
          : {}),
      },
      beforeWrite,
    );
  }
  close() {
    this.child.stdin.end();
    this.child.kill('SIGTERM');
    this.fail();
  }
}
const fingerprint = (request: ChatActionRequest) =>
  createHash('sha256')
    .update(
      JSON.stringify([
        machineRuntimeTargetKey(request.target),
        request.hostId,
        request.threadId,
        request.clientOperationId,
        request.action,
      ]),
    )
    .digest('hex');
type Record = ChatActionReceipt & { fingerprint: string };
export class DesktopChatActions {
  private records = new Map<string, Record>();
  private clients = new Set<ChatMetadataConnection>();
  private busy = new Set<string>();
  private operationBusy = new Set<string>();
  private closed = false;
  constructor(
    private directory: string,
    private hostId: string,
    private executable: string,
    private home: string,
    private guard: (request: ChatActionRequest) => Promise<void>,
    private sharedWebsocketUrl?: string,
  ) {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    protectWindowsStateDirectory(directory);
  }
  deletedThreadIds(): Set<string> {
    const ids = new Set<string>();
    for (const name of readdirSync(this.directory)) {
      if (!name.endsWith('.json')) continue;
      const file = join(this.directory, name);
      assertPrivateFile(file);
      const record = JSON.parse(readFileSync(file, 'utf8')) as Record;
      if (
        record.hostId === this.hostId &&
        record.fingerprint === fingerprint(record) &&
        record.action === 'delete' &&
        record.phase === 'confirmed'
      )
        ids.add(record.threadId);
    }
    return ids;
  }
  private file(id: string) {
    if (!/^[a-zA-Z0-9_-]{8,100}$/.test(id)) throw new CreationError(400, '操作 ID 无效');
    return join(this.directory, id + '.json');
  }
  private save(record: Record) {
    const file = this.file(record.clientOperationId),
      temporary = file + '.' + randomUUID() + '.tmp';
    const fd = openSync(temporary, 'wx', 0o600);
    try {
      writeFileSync(fd, JSON.stringify(record));
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
    protectWindowsStateFile(temporary);
    renameSync(temporary, file);
    if (process.platform !== 'win32') {
      const dir = openSync(this.directory, 'r');
      try {
        fsyncSync(dir);
      } finally {
        closeSync(dir);
      }
    }
  }
  status(threadId: string, id: string): ChatActionReceipt {
    let record = this.records.get(id);
    if (!record && existsSync(this.file(id))) {
      assertPrivateFile(this.file(id));
      record = JSON.parse(readFileSync(this.file(id), 'utf8')) as Record;
      if (record.phase === 'submitted') {
        record.phase = 'outcome-unknown';
        record.error = '上次操作结果待确认，未重新提交';
      }
      this.records.set(id, record);
    }
    if (!record) throw new CreationError(404, '没有此聊天操作');
    if (
      record.hostId !== this.hostId ||
      record.threadId !== threadId ||
      record.fingerprint !== fingerprint(record)
    )
      throw new CreationError(409, '操作回执目标不一致');
    return { ...record };
  }
  async submit(request: ChatActionRequest): Promise<ChatActionReceipt> {
    if (this.closed) throw new CreationError(503, '聊天管理已关闭');
    if (request.hostId !== this.hostId) throw new CreationError(403, '电脑身份不一致');
    try {
      const old = this.status(request.threadId, request.clientOperationId);
      if (fingerprint(old) !== fingerprint(request))
        throw new CreationError(409, '操作 ID 已用于其他意图');
      return old;
    } catch (error) {
      if (!(error instanceof CreationError) || error.status !== 404) throw error;
    }
    for (const name of readdirSync(this.directory)) {
      if (!name.endsWith('.json')) continue;
      const priorId = name.slice(0, -5);
      const file = join(this.directory, name);
      assertPrivateFile(file);
      const prior = JSON.parse(readFileSync(file, 'utf8')) as Record;
      if (prior.hostId !== this.hostId || prior.threadId !== request.threadId) continue;
      const status = this.status(request.threadId, priorId);
      if (['submitted', 'outcome-unknown'].includes(status.phase))
        throw new CreationError(409, '此对话仍有待确认的管理操作，请查询原记录');
    }
    if (this.operationBusy.has(request.clientOperationId))
      throw new CreationError(409, '此操作正在提交，请查询原记录');
    if (this.busy.has(request.threadId)) throw new CreationError(409, '此对话的管理操作尚未结束');
    this.busy.add(request.threadId);
    this.operationBusy.add(request.clientOperationId);
    let client: ChatMetadataConnection | undefined;
    let written = false;
    const record: Record = {
      ...request,
      fingerprint: fingerprint(request),
      phase: 'submitted',
      error: null,
    };
    try {
      client = this.sharedWebsocketUrl
        ? new SharedChatMetadata(this.sharedWebsocketUrl)
        : new ChatMetadata(this.executable, this.home);
      this.clients.add(client);
      await this.guard(request);
      await client.initialize();
      const metadata = await client.inspect(request.threadId);
      if (metadata.id !== request.threadId) throw new CreationError(409, '原对话身份不一致');
      await this.guard(request);
      await client.apply(request, () => {
        this.save(record);
        this.records.set(request.clientOperationId, record);
        written = true;
      });
      record.phase = 'confirmed';
      this.save(record);
    } catch (error) {
      if (!written) {
        if (error instanceof MetadataRejected && error.definitive)
          throw new CreationError(409, error.message);
        throw error;
      }
      record.phase =
        error instanceof MetadataRejected && error.definitive ? 'rejected' : 'outcome-unknown';
      record.error =
        error instanceof MetadataRejected && error.definitive
          ? error.message
          : '操作结果待确认，请查询原操作；不会自动重试';
      this.save(record);
    } finally {
      try {
        client?.close();
      } finally {
        if (client) this.clients.delete(client);
        this.busy.delete(request.threadId);
        this.operationBusy.delete(request.clientOperationId);
      }
    }
    return { ...record };
  }
  close() {
    this.closed = true;
    for (const client of this.clients) client.close();
  }
}
