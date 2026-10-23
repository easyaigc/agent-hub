import { withWindowsProcessCreation } from './windows-process.js';
import { execFile } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  closeSync,
  existsSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import {
  BeforeDesktopSubmit,
  CreationInputNotWritten,
  type CreationDiagnostic,
  type CreationInputBoundary,
  type CreationInputCheckpoint,
  type DesktopCreationBridge,
  type DesktopCreationPlan,
} from './desktop-creation.js';
import { DesktopRuntime } from './desktop-ipc.js';
import { DesktopThreadPreparer } from './desktop-thread-preparer.js';
import {
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  windowsPowerShellEnvironment,
} from './desktop-platform.js';
import { object } from './runtime.js';

interface PreparedBridgeConfig {
  hostId: string;
  /** Optional historical metadata; upgrades do not require rewriting this field. */
  desktopVersion?: string;
  validationOnly?: boolean;
  // Optional evidence reference; availability depends on target and runtime readiness.
  verification?: string;
}
interface PreparationRecord {
  hostId: string;
  clientOperationId: string;
  fingerprint: string;
  cwd: string;
  phase:
    | 'intent'
    | 'start-written'
    | 'identified'
    | 'materialized'
    | 'released'
    | 'input-preparing'
    | 'input-retryable'
    | 'input-submitting'
    | 'submitted';
  threadId: string | null;
  pid: number | null;
  exitCode: number | null;
  error?: string;
  diagnostic?: CreationDiagnostic;
  inputBoundary?: {
    state: 'not-written' | 'may-have-written';
    checkpoint: CreationInputCheckpoint;
  };
  cancelled?: boolean;
}
const validId = (id: string) => /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(id);
const hash = (value: string | Buffer) => createHash('sha256').update(value).digest('hex');
function validInputCheckpoint(
  checkpoint: CreationInputCheckpoint,
  hostId: string,
  id: string,
  operationId: string,
  fingerprint: string,
) {
  const target = object(checkpoint.target),
    account = object(target.account);
  return (
    checkpoint.version === 1 &&
    checkpoint.hostId === hostId &&
    checkpoint.threadId === id &&
    checkpoint.clientMessageId === 'first-' + operationId &&
    checkpoint.preparationFingerprint === fingerprint &&
    /^[a-f0-9]{64}$/.test(checkpoint.replyFingerprint) &&
    checkpoint.writerPid === process.pid &&
    validId(checkpoint.attemptId) &&
    target.agentId === 'codex' &&
    target.hostId === hostId &&
    target.workspaceId === hostId &&
    target.runtimeId === 'desktop-runtime' &&
    (target.account === undefined ||
      (typeof account.authority === 'string' &&
        !!account.authority &&
        typeof account.subject === 'string' &&
        !!account.subject))
  );
}
const loadedDigest = (() => {
  const digest = createHash('sha256');
  for (const name of [
    'desktop-prepared-bridge.js',
    'desktop-thread-preparer.js',
    'desktop-forks.js',
    'fork-worktree.js',
    'desktop-ipc.js',
    'desktop-framing.js',
    'desktop-creation.js',
    'runtime-session.js',
    'desktop-history-identity.js',
    'attachment-files.js',
    'desktop-questions.js',
    'runtime-persistence.js',
    'runtime-diagnostics.js',
    'generation-settings.js',
    'history-reader.js',
    'desktop-platform.js',
    'windows-process.js',
    'desktop-private-state-native.js',
    'desktop-private-state-native-worker.js',
    'desktop-trace-native.js',
    'desktop-trace-native-worker.js',
    'desktop-suggestion-read-worker.js',
    'desktop-suggestion-worker-client.js',
    'desktop-suggestion-worker-limits.js',
  ])
    digest.update(readFileSync(new URL(name, import.meta.url)));
  digest.update(readFileSync(new URL('../windows/desktop-open.ps1', import.meta.url)));
  digest.update(readFileSync(new URL('../windows/private-state.cs', import.meta.url)));
  digest.update(readFileSync(new URL('../windows/private-state.ps1', import.meta.url)));
  return digest.digest('hex');
})();
export const preparedAdapterDigest = () => loadedDigest;

function save(path: string, value: unknown): void {
  const temporary = path + '.' + randomUUID() + '.tmp';
  const fd = openSync(temporary, 'wx', 0o600);
  try {
    writeFileSync(fd, JSON.stringify(value));
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  protectWindowsStateFile(temporary);
  renameSync(temporary, path);
  if (process.platform !== 'win32') {
    const fd = openSync(dirname(path), 'r');
    try {
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
  }
}

/** Opens only the official existing-thread URI. No arbitrary command or UI automation. */
export async function openOriginalDesktopThread(threadId: string): Promise<void> {
  if (!validId(threadId)) throw new Error('准确会话 ID 无效');
  const uri = 'codex://threads/' + threadId;
  const executable =
    process.platform === 'darwin'
      ? '/usr/bin/open'
      : process.platform === 'win32'
        ? join(
            process.env.SystemRoot ?? 'C:\\Windows',
            'System32',
            'WindowsPowerShell',
            'v1.0',
            'powershell.exe',
          )
        : '';
  if (!executable) throw new BeforeDesktopSubmit('当前平台没有原桌面接管入口');
  const args =
    process.platform === 'darwin'
      ? ['-b', 'com.openai.codex', uri]
      : [
          '-NoProfile',
          '-NonInteractive',
          '-File',
          fileURLToPath(new URL('../windows/desktop-open.ps1', import.meta.url)),
          '-ThreadId',
          threadId,
        ];
  await new Promise<void>((resolve, reject) =>
    withWindowsProcessCreation(10_000, (timeout) =>
      execFile(
        executable,
        args,
        {
          timeout,
          maxBuffer: 4096,
          windowsHide: true,
          ...(process.platform === 'win32' ? { env: windowsPowerShellEnvironment() } : {}),
        },
        (error) => (error ? reject(new Error('原桌面打开结果待确认')) : resolve()),
      ),
    ),
  );
}

/** Service lifetime is independent of all ordinary desktop chats. */
export class DesktopPreparedBridge implements DesktopCreationBridge {
  readonly backgroundSubmission = true;
  readonly independentTargets = true;
  readonly inputTransport = 'desktop-ipc';
  readonly projectAssignment = 'app-server';
  readonly recoverCreationOwner = true;
  available = false;
  openAvailable = false;
  validationOnly = false;
  openValidationOnly = false;
  reason: string | null = '独立空会话桥接尚未配置';
  private closed = false;
  private refreshing: Promise<void> | undefined;
  private readonly cancelled = new Set<string>();
  private readonly work = new Map<string, { fingerprint: string; promise: Promise<string> }>();
  private readonly preparer: DesktopThreadPreparer;
  constructor(
    private readonly path: string,
    private readonly hostId: string,
    private readonly runtime: DesktopRuntime,
    private readonly directory: string,
    executable: string,
    home: string,
    private readonly firstInput: (
      plan: DesktopCreationPlan,
      id: string,
      boundary: CreationInputBoundary,
      preparationFingerprint: string,
    ) => Promise<void>,
  ) {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    protectWindowsStateDirectory(directory);
    this.preparer = new DesktopThreadPreparer(executable, home);
  }
  async refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing;
    const work = this.refreshNow();
    this.refreshing = work;
    try {
      await work;
    } finally {
      if (this.refreshing === work) this.refreshing = undefined;
    }
  }
  private async refreshNow(): Promise<void> {
    let available = false,
      validationOnly = false;
    let reason = '独立空会话桥接配置不可用';
    try {
      if (this.closed) throw new Error('closed');
      assertPrivateFile(this.path);
      const config: PreparedBridgeConfig = JSON.parse(readFileSync(this.path, 'utf8'));
      reason = '空会话桥接配置与当前 Host 不一致';
      if (config.hostId !== this.hostId) throw new Error('target');
      validationOnly = config.validationOnly === true;
      reason = '原 Codex 桌面连接不可用';
      if (!this.runtime.ready || !(await this.runtime.desktopAvailable()))
        throw new Error('desktop');
      available = true;
      reason = '';
    } catch {
      /* Atomically publish the completed probe; ordinary chat owners are irrelevant. */
    }
    this.validationOnly = validationOnly;
    this.openValidationOnly = validationOnly;
    this.available = this.openAvailable = available && !this.closed;
    this.reason = this.closed ? '独立空会话桥接已关闭' : reason || null;
  }
  private file(id: string): string {
    if (
      !/^[a-zA-Z0-9_-]{8,100}$/.test(id) ||
      ['constructor', 'prototype', '__proto__'].includes(id)
    )
      throw new Error('创建操作 ID 无效');
    return join(this.directory, id + '.json');
  }
  projectTarget(_name: string, roots: string[], projectId?: string) {
    if (!projectId || !roots[0]) throw new Error('准确项目 ID 与主要根目录未确认');
    return { directory: realpathSync(roots[0]), nativeProjectId: projectId };
  }
  private fingerprint(plan: DesktopCreationPlan): string {
    return hash(
      JSON.stringify({
        hostId: plan.hostId,
        clientOperationId: plan.clientOperationId,
        projectId: plan.projectId,
        directory: plan.directory,
        text: plan.text,
        attachments: plan.attachments,
        model: plan.model,
        effort: plan.effort,
        permissionMode: plan.permissionMode,
        ...(plan.speed ? { speed: plan.speed } : {}),
        collaborationMode: plan.collaborationMode,
      }),
    );
  }
  private record(plan: DesktopCreationPlan): PreparationRecord | undefined {
    const file = this.file(plan.clientOperationId);
    if (!existsSync(file)) return undefined;
    assertPrivateFile(file);
    const record: PreparationRecord = JSON.parse(readFileSync(file, 'utf8'));
    if (
      record.hostId !== this.hostId ||
      record.clientOperationId !== plan.clientOperationId ||
      record.fingerprint !== this.fingerprint(plan)
    )
      throw new Error('空会话准备回执目标不一致');
    return record;
  }
  async create(plan: DesktopCreationPlan): Promise<string> {
    plan = structuredClone(plan);
    const fingerprint = this.fingerprint(plan);
    const prior = this.work.get(plan.clientOperationId);
    if (prior) {
      if (prior.fingerprint !== fingerprint) throw new Error('创建操作已有不同的不可变输入');
      return prior.promise;
    }
    const work = Promise.resolve().then(() => this.createNow(plan));
    this.work.set(plan.clientOperationId, { fingerprint, promise: work });
    try {
      return await work;
    } finally {
      this.work.delete(plan.clientOperationId);
    }
  }
  private async createNow(plan: DesktopCreationPlan): Promise<string> {
    await this.refresh();
    if (this.closed || this.cancelled.has(plan.clientOperationId))
      throw new Error('创建桥接已关闭或操作已结束');
    if (!this.available || plan.hostId !== this.hostId)
      throw new BeforeDesktopSubmit(this.reason ?? '独立新建不可用');
    const existing = this.record(plan);
    if (existing) {
      if (existing.phase === 'submitted' && existing.threadId) return existing.threadId;
      // Only an explicit submit may consume the owned pre-write disposition.
      // Status polling must never turn a failed preflight into a model input.
      if (existing.phase === 'input-retryable' && existing.inputBoundary?.state === 'not-written')
        return this.continueReleased(plan, existing);
      // Querying an unknown receipt never starts a second thread or first turn.
      throw new Error('同一创建操作已有准备记录，请查询原结果');
    }
    const record: PreparationRecord = {
      hostId: this.hostId,
      clientOperationId: plan.clientOperationId,
      fingerprint: this.fingerprint(plan),
      cwd:
        plan.directory ??
        join(
          homedir(),
          'Documents',
          'Codex',
          new Date().toISOString().slice(0, 10),
          'codex-plus-' + randomUUID(),
        ),
      phase: 'intent',
      threadId: null,
      pid: null,
      exitCode: null,
    };
    const persist = () => {
      if (this.closed) throw new Error('创建桥接已关闭，禁止晚到写入');
      if (this.cancelled.has(plan.clientOperationId)) record.cancelled = true;
      save(this.file(plan.clientOperationId), record);
    };
    persist();
    if (!plan.directory) {
      mkdirSync(record.cwd, { recursive: true, mode: 0o700 });
      protectWindowsStateDirectory(record.cwd);
    }
    const cwd = record.cwd;
    try {
      await this.preparer.prepare(
        { cwd, projectId: plan.projectId, title: plan.text.trim().slice(0, 100) || '手机新聊天' },
        {
          starting: (pid) => {
            record.pid = pid;
            record.phase = 'start-written';
            persist();
          },
          identified: (threadId) => {
            record.threadId = threadId;
            record.phase = 'identified';
            persist();
          },
          materialized: () => {
            record.phase = 'materialized';
            persist();
          },
          exited: (code) => {
            record.exitCode = code;
            if (code === 0 && record.phase === 'materialized') record.phase = 'released';
            persist();
          },
        },
      );
    } catch (error) {
      if (!this.closed) {
        record.diagnostic = {
          stage: 'metadata',
          code: 'metadata-unconfirmed',
          disposition: 'observe-only',
        };
        record.error = '元数据准备尚未确认，原操作已保留';
        persist();
      }
      throw error;
    }
    return this.continueReleased(plan, record);
  }
  private async continueReleased(
    plan: DesktopCreationPlan,
    record: PreparationRecord,
  ): Promise<string> {
    const id = record.threadId;
    if (
      !id ||
      this.closed ||
      record.cancelled ||
      !['released', 'input-retryable'].includes(record.phase) ||
      record.exitCode !== 0 ||
      (record.phase === 'input-retryable' && record.inputBoundary?.state !== 'not-written')
    )
      throw new Error('空会话准备未释放，禁止桌面执行');
    const persist = () => {
      if (this.closed) throw new Error('创建桥接已关闭，禁止晚到写入');
      if (this.cancelled.has(plan.clientOperationId)) record.cancelled = true;
      save(this.file(plan.clientOperationId), record);
    };
    const assertOpen = () => {
      if (this.closed || record.cancelled || this.cancelled.has(plan.clientOperationId))
        throw new Error('创建操作已结束，禁止首条输入');
    };
    let stage: CreationDiagnostic['stage'] = 'metadata';
    try {
      const metadata = object(
        (
          await this.runtime.reader.read('thread/read', {
            threadId: id,
            includeTurns: true,
          })
        ).thread,
      );
      assertOpen();
      if (
        metadata.id !== id ||
        (metadata.projectId ?? null) !== plan.projectId ||
        !Array.isArray(metadata.turns) ||
        metadata.turns.length !== 0 ||
        typeof metadata.cwd !== 'string' ||
        realpathSync(metadata.cwd) !== realpathSync(record.cwd)
      )
        throw new Error('独立只读核对未确认空会话目标');
      stage = 'owner';
      const opened = await this.open(id, plan.clientOperationId);
      assertOpen();
      if (!opened) throw new Error('原桌面接管结果待确认');
      const owner = this.runtime.ipc.state(id);
      if (
        !owner ||
        owner.workspaceKind !== (plan.projectId ? 'project' : 'projectless') ||
        !Array.isArray(owner.turns) ||
        owner.turns.length !== 0
      )
        throw new Error('原桌面必须接管准确归属的空会话');
      record.phase = 'input-preparing';
      persist();
      await this.firstInput(
        plan,
        id,
        {
          ...(record.inputBoundary?.state === 'not-written'
            ? { originalCheckpoint: structuredClone(record.inputBoundary.checkpoint) }
            : {}),
          assertOpen: (next) => {
            stage = next;
            assertOpen();
          },
          beforeWrite: (checkpoint) => {
            assertOpen();
            if (
              !validInputCheckpoint(
                checkpoint,
                this.hostId,
                id,
                plan.clientOperationId,
                record.fingerprint,
              )
            )
              throw new Error('首条输入执行边界目标不一致');
            stage = 'execution';
            record.inputBoundary = {
              state: 'may-have-written',
              checkpoint: structuredClone(checkpoint),
            };
            record.phase = 'input-submitting';
            // File and directory fsync must complete before the execution hook returns.
            persist();
          },
        },
        record.fingerprint,
      );
      assertOpen();
      record.phase = 'submitted';
      delete record.diagnostic;
      delete record.error;
      persist();
      return id;
    } catch (error) {
      if (this.closed) throw error;
      const proved =
        error instanceof CreationInputNotWritten &&
        process.platform !== 'win32' &&
        validInputCheckpoint(
          error.checkpoint,
          this.hostId,
          id,
          plan.clientOperationId,
          record.fingerprint,
        ) &&
        record.phase === 'input-preparing' &&
        !record.cancelled;
      if (proved) {
        record.phase = 'input-retryable';
        record.inputBoundary = {
          state: 'not-written',
          checkpoint: structuredClone(error.checkpoint),
        };
        record.diagnostic = error.diagnostic;
      } else {
        record.diagnostic = {
          stage,
          code: record.cancelled ? 'creation-cancelled' : stage + '-unconfirmed',
          disposition: 'observe-only',
        };
      }
      record.error = '创建阶段尚未确认，原操作与输入已保留';
      persist();
      throw error;
    }
  }
  async reconcile(plan: DesktopCreationPlan): Promise<string | undefined> {
    plan = structuredClone(plan);
    const pending = this.work.get(plan.clientOperationId);
    if (pending) {
      if (pending.fingerprint !== this.fingerprint(plan))
        throw new Error('创建操作已有不同的不可变输入');
      return pending.promise;
    }
    const record = this.record(plan);
    // A durable released phase proves no input was attempted. Resume only this
    // known ID after checking the still-empty original owner; never thread/start.
    if (
      !this.closed &&
      !record?.cancelled &&
      record?.exitCode === 0 &&
      record.threadId &&
      record.phase === 'released'
    ) {
      const work = Promise.resolve().then(() => this.continueReleased(plan, record));
      this.work.set(plan.clientOperationId, { fingerprint: this.fingerprint(plan), promise: work });
      try {
        return await work;
      } finally {
        this.work.delete(plan.clientOperationId);
      }
    }
    return record?.threadId && validId(record.threadId) ? record.threadId : undefined;
  }
  observe(plan: DesktopCreationPlan) {
    const record = this.record(plan);
    return record
      ? {
          preparedThreadId: record.threadId,
          preparationPhase: record.phase,
          ...(record.diagnostic ? { diagnostic: structuredClone(record.diagnostic) } : {}),
        }
      : undefined;
  }
  cancel(id: string): void {
    const file = this.file(id);
    this.cancelled.add(id);
    if (!existsSync(file)) return;
    assertPrivateFile(file);
    const record: PreparationRecord = JSON.parse(readFileSync(file, 'utf8'));
    if (record.hostId !== this.hostId || record.clientOperationId !== id)
      throw new Error('创建取消目标不一致');
    // The in-flight continuation checks the durable cancellation as well.
    record.cancelled = true;
    save(file, record);
  }
  async open(threadId: string, _operationId: string): Promise<boolean> {
    await this.refresh();
    if (!this.openAvailable || !validId(threadId))
      throw new BeforeDesktopSubmit(this.reason ?? '原桌面打开不可用');
    const prior = await this.runtime.ipc.follow(threadId, 1000, true);
    if (prior && this.runtime.ipc.hasOwner(threadId)) return true;
    await openOriginalDesktopThread(threadId);
    // The URI handler navigates before the renderer has resumed the thread.
    // no-client-found is an immediate read result, not a 15-second wait.
    const deadline = Date.now() + 30_000;
    while (!this.closed && this.runtime.ready && Date.now() < deadline) {
      const state = await this.runtime.ipc.follow(threadId, 1000, true);
      if (state?.id === threadId && state.hostId === 'local' && this.runtime.ipc.hasOwner(threadId))
        return true;
      await new Promise<void>((done) => setTimeout(done, 350));
    }
    return false;
  }
  close(): void {
    this.closed = true;
    this.preparer.close();
    this.available = this.openAvailable = false;
  }
}
