import { withWindowsProcessCreation } from './windows-process.js';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createInterface } from 'node:readline';
import { isAbsolute } from 'node:path';
import { realpathSync } from 'node:fs';
import { BeforeDesktopSubmit } from './desktop-creation.js';
import { object, list, string, type WireObject } from './runtime.js';

// This transport deliberately has no generic public RPC or execution methods.
const systemEnvironment = [
  'PATH',
  'HOME',
  'USER',
  'LOGNAME',
  'SHELL',
  'LANG',
  'LC_ALL',
  'TMPDIR',
  'TEMP',
  'TMP',
  'SystemRoot',
  'SYSTEMROOT',
  'WINDIR',
  'ComSpec',
  'COMSPEC',
  'SystemDrive',
  'USERPROFILE',
  'HOMEDRIVE',
  'HOMEPATH',
  'APPDATA',
  'LOCALAPPDATA',
  'PROGRAMFILES',
];
const allowed = new Set(['initialize', 'thread/start', 'thread/name/set', 'thread/read']);
const managementAllowed = new Set([
  'initialize',
  'project/list',
  'thread/list',
  'project/delete',
  'thread/archive',
]);
const forkAllowed = new Set(['initialize', 'thread/fork', 'thread/name/set', 'thread/read']);
const queueAllowed = new Set([
  'initialize',
  'thread/queue/add',
  'thread/queue/list',
  'thread/queue/delete',
]);
const validThreadId = (id: string) => /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(id);
export interface EmptyThreadTarget {
  cwd: string;
  projectId: string | null;
  title: string;
}
export interface PreparationObserver {
  starting(pid: number): void;
  identified(threadId: string): void;
  materialized(): void;
  exited(code: number | null): void;
}

export class MetadataConnection {
  private readonly child: ChildProcessWithoutNullStreams;
  private readonly pending = new Map<
    number,
    {
      resolve: (value: WireObject) => void;
      reject: (error: Error) => void;
      timer: NodeJS.Timeout;
    }
  >();
  private nextId = 0;
  private startupTimeout = 20_000;
  private readonly exited: Promise<number | null>;
  constructor(
    executable: string,
    home: string,
    cwd: string,
    private readonly purpose: 'empty' | 'fork' | 'management' | 'queue' = 'empty',
  ) {
    const env = Object.fromEntries(
      systemEnvironment.flatMap((key) =>
        process.env[key] === undefined ? [] : [[key, process.env[key]!]],
      ),
    );
    env.CODEX_HOME = home;
    this.child = withWindowsProcessCreation(20000, (remaining) => {
      this.startupTimeout = remaining;
      return spawn(executable, ['app-server', '--stdio'], {
        windowsHide: true,
        cwd,
        env,
        stdio: 'pipe',
      });
    });
    this.child.stderr.resume();
    this.exited = new Promise((done) => this.child.once('close', (code) => done(code)));
    this.child.once('error', () => this.fail(new Error('官方元数据准备进程未能启动')));
    this.child.once('exit', () => this.fail(new Error('官方元数据准备连接已退出')));
    this.child.stdin.on('error', () => this.fail(new Error('官方元数据准备传输已关闭')));
    createInterface({ input: this.child.stdout }).on('line', (line) => {
      try {
        const response = object(JSON.parse(line));
        const request = this.pending.get(Number(response.id));
        if (!request) return;
        clearTimeout(request.timer);
        this.pending.delete(Number(response.id));
        if (response.error)
          request.reject(
            new MetadataRejection(
              '官方 API 拒绝元数据准备请求',
              string(object(response.error).message).includes('already has an active writer')
                ? 'active-writer'
                : object(response.error).code === -32601
                  ? 'unsupported-method'
                  : undefined,
            ),
          );
        else request.resolve(object(response.result));
      } catch {
        this.fail(new Error('官方元数据响应无效'));
      }
    });
  }
  get pid(): number | undefined {
    return this.child.pid;
  }
  private fail(error: Error): void {
    for (const request of this.pending.values()) {
      clearTimeout(request.timer);
      request.reject(error);
    }
    this.pending.clear();
  }
  async initialize(): Promise<void> {
    await this.rpc('initialize', {
      clientInfo: { name: 'codex_manage_empty_metadata', version: '0.1.0' },
      capabilities: { experimentalApi: true },
    });
    this.child.stdin.write(JSON.stringify({ method: 'initialized' }) + '\n');
  }
  rpc(method: string, params: WireObject, beforeWrite?: () => void): Promise<WireObject> {
    if (
      !(
        this.purpose === 'queue'
          ? queueAllowed
          : this.purpose === 'management'
            ? managementAllowed
            : this.purpose === 'fork'
              ? forkAllowed
              : allowed
      ).has(method)
    )
      throw new Error('只允许对应的会话元数据操作');
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => {
          this.pending.delete(id);
          reject(new Error('空会话元数据准备结果待确认'));
        },
        method === 'initialize' ? this.startupTimeout : 20_000,
      );
      this.pending.set(id, { resolve, reject, timer });
      try {
        beforeWrite?.();
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error);
        return;
      }
      this.child.stdin.write(JSON.stringify({ id, method, params }) + '\n', (error) => {
        if (error) this.fail(new Error('空会话元数据准备写入失败'));
      });
    });
  }
  async close(): Promise<number | null> {
    this.child.stdin.end();
    if (this.child.exitCode !== null) return this.child.exitCode;
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        this.exited,
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => {
            this.child.kill('SIGKILL');
            reject(new Error('未能确认准备进程正常退出，禁止桌面接管'));
          }, 12_000);
        }),
      ]);
    } finally {
      clearTimeout(timer);
    }
  }
  abort(): void {
    this.child.kill('SIGTERM');
  }
}

export class MetadataRejection extends Error {
  constructor(
    message: string,
    readonly reason: 'active-writer' | 'unsupported-method' | undefined = undefined,
  ) {
    super(message);
  }
}
export interface ForkMetadataTarget {
  sourceThreadId: string;
  turnId: string;
  cwd: string;
  title: string;
  model?: string;
  effort?: string | null;
}

/** Official metadata preparation only. No turn, resume, tool or history-file write. */
export class DesktopThreadPreparer {
  private closed = false;
  private readonly connections = new Set<MetadataConnection>();
  constructor(
    private readonly executable: string,
    private readonly home: string,
  ) {
    if (!isAbsolute(executable) || !isAbsolute(home))
      throw new Error('空会话准备需要本机固定的官方 CLI 与 Codex 目录');
  }
  async prepare(target: EmptyThreadTarget, observer: PreparationObserver): Promise<string> {
    if (this.closed) throw new BeforeDesktopSubmit('空会话准备器已关闭');
    const cwd = realpathSync(target.cwd);
    const client = new MetadataConnection(this.executable, this.home, cwd);
    this.connections.add(client);
    let startWritten = false;
    let resultId: string | undefined;
    let failure: unknown;
    try {
      try {
        await client.initialize();
      } catch {
        throw new BeforeDesktopSubmit('官方空会话准备未开始');
      }
      if (!client.pid) throw new BeforeDesktopSubmit('官方空会话准备未开始');
      // Persist before the non-idempotent request can leave this process.
      observer.starting(client.pid);
      startWritten = true;
      const response = await client.rpc('thread/start', {
        cwd,
        ...(target.projectId ? { projectId: target.projectId } : {}),
        ephemeral: false,
        historyMode: 'legacy',
      });
      const thread = object(response.thread);
      resultId = string(thread.id);
      if (!validThreadId(resultId)) throw new Error('官方创建结果没有准确会话 ID');
      observer.identified(resultId);
      await client.rpc('thread/name/set', { threadId: resultId, name: target.title });
      const persisted = object(
        (
          await client.rpc('thread/read', {
            threadId: resultId,
            includeTurns: true,
          })
        ).thread,
      );
      if (
        persisted.id !== resultId ||
        (persisted.projectId ?? null) !== target.projectId ||
        typeof persisted.cwd !== 'string' ||
        realpathSync(persisted.cwd) !== cwd ||
        !Array.isArray(persisted.turns) ||
        list(persisted.turns).length !== 0
      )
        throw new Error('准备结果必须为空会话且与目标一致');
      observer.materialized();
    } catch (error) {
      failure =
        !startWritten && !(error instanceof BeforeDesktopSubmit)
          ? new BeforeDesktopSubmit('官方空会话准备未开始')
          : error;
    }
    let code: number | null;
    try {
      code = await client.close();
    } finally {
      this.connections.delete(client);
    }
    observer.exited(code);
    if (!startWritten && failure instanceof BeforeDesktopSubmit) throw failure;
    if (code !== 0) throw new Error('准备进程未正常退出，禁止桌面接管');
    if (failure) throw failure;
    return resultId!;
  }
  /** Copies an exact completed prefix through the official API, without a turn or resume. */
  async fork(target: ForkMetadataTarget, observer: PreparationObserver): Promise<string> {
    if (this.closed) throw new BeforeDesktopSubmit('会话准备器已关闭');
    if (!validThreadId(target.sourceThreadId) || !target.turnId.trim())
      throw new BeforeDesktopSubmit('分支源对话或任务轮次无效');
    const cwd = realpathSync(target.cwd);
    const client = new MetadataConnection(this.executable, this.home, cwd, 'fork');
    this.connections.add(client);
    let written = false;
    let resultId: string | undefined;
    let failure: unknown;
    try {
      await client.initialize();
      if (!client.pid) throw new BeforeDesktopSubmit('分支准备进程未开始');
      observer.starting(client.pid);
      written = true;
      const response = await client.rpc('thread/fork', {
        threadId: target.sourceThreadId,
        lastTurnId: target.turnId,
        cwd,
        ephemeral: false,
        excludeTurns: true,
        threadSource: 'user',
        ...(target.model ? { model: target.model } : {}),
        ...(target.effort ? { config: { model_reasoning_effort: target.effort } } : {}),
      });
      const thread = object(response.thread);
      resultId = string(thread.id);
      if (!validThreadId(resultId) || resultId === target.sourceThreadId)
        throw new Error('分支结果没有准确的新对话 ID');
      observer.identified(resultId);
      await client.rpc('thread/name/set', { threadId: resultId, name: target.title });
      const persisted = object(
        (await client.rpc('thread/read', { threadId: resultId, includeTurns: false })).thread,
      );
      if (
        persisted.id !== resultId ||
        persisted.forkedFromId !== target.sourceThreadId ||
        typeof persisted.cwd !== 'string' ||
        realpathSync(persisted.cwd) !== cwd ||
        persisted.ephemeral === true
      )
        throw new Error('分支元数据与源对话或目录不一致');
      observer.materialized();
    } catch (error) {
      failure =
        !written && !(error instanceof BeforeDesktopSubmit)
          ? new BeforeDesktopSubmit('分支尚未提交到官方 API')
          : error;
    }
    let code: number | null;
    try {
      code = await client.close();
    } finally {
      this.connections.delete(client);
    }
    observer.exited(code);
    if (code !== 0) throw new Error('准备进程未正常退出，禁止桌面接管');
    if (failure) throw failure;
    return resultId!;
  }
  close(): void {
    this.closed = true;
    for (const client of this.connections) client.abort();
  }
}
