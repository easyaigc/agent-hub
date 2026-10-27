import { createHash, randomUUID } from 'node:crypto';
import {
  closeSync,
  existsSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  renameSync,
  writeFileSync,
  lstatSync,
  unlinkSync,
} from 'node:fs';
import { basename, extname, join } from 'node:path';
import {
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from './desktop-platform.js';

export class CreationError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}
export class BeforeDesktopSubmit extends Error {}
export type CreationInputStage = 'owner' | 'attachments' | 'generation' | 'receipt' | 'execution';
export interface CreationDiagnostic {
  stage: CreationInputStage | 'metadata' | 'confirmation';
  code: string;
  disposition: 'retry-original-preflight' | 'observe-only';
}
/** Produced only by the original, fenced first-input path, never from receipt absence. */
export interface CreationInputCheckpoint {
  version: 1;
  hostId: string;
  threadId: string;
  clientMessageId: string;
  preparationFingerprint: string;
  replyFingerprint: string;
  target: unknown;
  writerPid: number;
  attemptId: string;
}
export interface CreationInputBoundary {
  readonly originalCheckpoint?: CreationInputCheckpoint;
  assertOpen(stage: CreationInputStage): void;
  beforeWrite(checkpoint: CreationInputCheckpoint): void;
}
export class CreationInputNotWritten extends Error {
  constructor(
    readonly checkpoint: CreationInputCheckpoint,
    readonly diagnostic: CreationDiagnostic,
  ) {
    super('首条输入前置校验未通过，已保留原操作');
  }
}
export interface CreationObservation {
  preparedThreadId: string | null;
  preparationPhase: string;
  diagnostic?: CreationDiagnostic;
}
export interface CreationFile {
  id: string;
  name: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
}
export interface CreationTarget {
  hostId: string;
  projectId: string | null;
  directory: string | null;
  nativeProjectId?: string;
  existingThreadIds?: string[];
}
export interface CreationInput {
  hostId: string;
  clientOperationId: string;
  projectId: string | null;
  text: string;
  attachments: CreationFile[];
  collaborationMode?: string;
  model?: string;
  effort?: string;
  permissionMode?: string;
  speed?: string;
}
export interface DesktopCreationPlan extends CreationInput {
  directory: string | null;
  nativeProjectId?: string;
  receiptPath: string;
  files: (CreationFile & { path: string })[];
}
/** Read-only metadata backed by this Host's released, unexecuted preparation. */
export interface PreparedThreadMetadata {
  id: string;
  hostId: string;
  projectId: string | null;
  title: string;
}
export interface DesktopCreationDriver {
  readonly available: boolean;
  readonly reason: string | null;
  /** Each operation owns a fresh, durably bound thread; no shared desktop composer. */
  readonly independentTargets?: boolean;
  /** Returns the exact ID copied from the active desktop chat, never the latest list entry. */
  create(plan: DesktopCreationPlan): Promise<string>;
  reconcile?(plan: DesktopCreationPlan): Promise<string | undefined>;
  preparedThreadMetadata?(
    plan: DesktopCreationPlan,
    threadId: string,
  ): Promise<PreparedThreadMetadata | undefined>;
  observe?(plan: DesktopCreationPlan): CreationObservation | undefined;
  cancel?(id: string): void;
}
/** Local bridge features, independent of a particular creation implementation. */
export interface DesktopCreationBridge extends DesktopCreationDriver {
  refresh(): Promise<void>;
  close(): void;
  readonly backgroundSubmission?: boolean;
  readonly validationOnly?: boolean;
  readonly openAvailable?: boolean;
  readonly openValidationOnly?: boolean;
  /** Reacquire the original owner of a known created thread before a new reply. */
  readonly recoverCreationOwner?: boolean;
  readonly inputTransport?: 'desktop-ipc' | 'native-ui';
  readonly projectAssignment?: 'desktop-sidebar' | 'app-server';
  projectTarget?(
    name: string,
    roots: string[],
    projectId?: string,
  ): { directory: string; nativeProjectId: string };
  open?(threadId: string, operationId: string): Promise<boolean>;
}
export interface CreationRecord extends CreationInput {
  directory: string | null;
  nativeProjectId?: string;
  phase: 'prepared' | 'submitted' | 'confirmed' | 'outcome-unknown' | 'rejected';
  threadId: string | null;
  error: string | null;
  fingerprint: string;
  uploaded: string[];
  existingThreadIds: string[];
  /** User ended the wait; uncertainty remains and this operation is never replayed. */
  detached?: boolean;
  preparation?: CreationObservation;
}
const idPattern = /^[a-zA-Z0-9_-]{8,100}$/;
const validId = (id: unknown): id is string =>
  typeof id === 'string' &&
  idPattern.test(id) &&
  !['__proto__', 'constructor', 'prototype'].includes(id);
const hash = (bytes: string | Buffer) => createHash('sha256').update(bytes).digest('hex');

export function creationAttachmentPath(
  directory: string,
  record: Pick<CreationInput, 'clientOperationId'>,
  file: CreationFile,
): string {
  // Match reply uploads' character limit while keeping filesystem components
  // bounded in UTF-8. Preserve existing journal paths and the original name.
  const name =
    Buffer.byteLength(file.name) > 200 ? 'attachment' + extname(file.name).slice(0, 12) : file.name;
  return join(directory, record.clientOperationId, file.id, name);
}

/** Durable intent journal. Creating a record is a draft, not a Codex thread. */
export class DesktopCreationBroker {
  private readonly file: string;
  private records: Record<string, CreationRecord>;
  private inFlight = new Map<string, Promise<CreationRecord>>();
  private notificationUncertainSince = new Map<string, number>();
  private tail: Promise<unknown> = Promise.resolve();
  private lease: string | undefined;
  private closed = false;
  constructor(
    private readonly directory: string,
    private readonly hostId: string,
    readonly driver: DesktopCreationDriver,
    private readonly verify: (record: CreationRecord, threadId: string) => Promise<boolean>,
  ) {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    protectWindowsStateDirectory(directory);
    {
      const lock = join(directory, 'writer.lock');
      if (existsSync(lock)) {
        const pid = Number(readFileSync(lock, 'utf8'));
        let alive = true;
        if (!Number.isSafeInteger(pid) || pid <= 0)
          throw new Error('Invalid creation writer lease');
        try {
          process.kill(pid, 0);
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code === 'ESRCH') alive = false;
        }
        if (alive) throw new Error('Creation journal already has an active writer');
        unlinkSync(lock);
      }
      const fd = openSync(lock, 'wx', 0o600);
      try {
        writeFileSync(fd, String(process.pid));
        fsyncSync(fd);
      } finally {
        closeSync(fd);
      }
      this.lease = lock;
      protectWindowsStateFile(lock);
    }
    this.file = join(directory, 'creations.json');
    this.records = existsSync(this.file) ? JSON.parse(readFileSync(this.file, 'utf8')) : {};
    for (const record of Object.values(this.records)) {
      if (record.hostId !== hostId) throw new Error('Creation journal host mismatch');
      // A process may have died after the desktop accepted Send. Never replay it.
      if (record.phase === 'submitted') {
        record.phase = 'outcome-unknown';
        record.error = '桌面提交结果待确认，请查询创建状态';
      }
    }
    this.save();
  }
  private save(): void {
    const temporary = this.file + '.' + randomUUID() + '.tmp';
    const fd = openSync(temporary, 'wx', 0o600);
    try {
      writeFileSync(fd, JSON.stringify(this.records));
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
    protectWindowsStateFile(temporary);
    renameSync(temporary, this.file);
    // Node cannot flush a directory handle on Windows. Keep the file fsync and
    // atomic rename there, while retaining directory durability on macOS.
    if (process.platform !== 'win32') {
      const dir = openSync(this.directory, 'r');
      try {
        fsyncSync(dir);
      } finally {
        closeSync(dir);
      }
    }
  }
  private record(id: string): CreationRecord {
    if (this.closed) throw new CreationError(503, '创建桥接已关闭');
    if (!validId(id)) throw new CreationError(400, '创建操作 ID 无效');
    const record = Object.hasOwn(this.records, id) ? this.records[id] : undefined;
    if (!record) throw new CreationError(404, '创建操作不存在');
    return record;
  }
  private copy(record: CreationRecord): CreationRecord {
    return structuredClone(record);
  }
  private plan(record: CreationRecord): DesktopCreationPlan {
    return {
      ...this.copy(record),
      receiptPath: join(this.directory, record.clientOperationId, 'native-result.json'),
      files: record.attachments.map((file) => ({ ...file, path: this.filePath(record, file) })),
    };
  }
  private observe(record: CreationRecord): void {
    const observation = this.driver.observe?.(this.plan(record));
    if (observation) record.preparation = structuredClone(observation);
  }
  status() {
    return { available: this.driver.available, reason: this.driver.reason };
  }
  /** An explicit open may recover an owned empty target; it never resumes its first input. */
  async preparedThreadMetadata(threadId: string): Promise<PreparedThreadMetadata | undefined> {
    if (
      this.closed ||
      !this.driver.preparedThreadMetadata ||
      !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(threadId)
    )
      return undefined;
    for (const record of Object.values(this.records)) {
      if (
        record.hostId !== this.hostId ||
        record.phase !== 'rejected' ||
        !Array.isArray(record.existingThreadIds) ||
        record.existingThreadIds.includes(threadId)
      )
        continue;
      try {
        const plan = this.plan(record);
        if (this.driver.observe?.(plan)?.preparedThreadId !== threadId) continue;
        const metadata = await this.driver.preparedThreadMetadata(plan, threadId);
        if (
          metadata?.id === threadId &&
          metadata.hostId === this.hostId &&
          metadata.projectId === record.projectId &&
          typeof metadata.title === 'string'
        )
          return structuredClone(metadata);
      } catch {
        // An unreadable or changed proof gives no new thread authority.
      }
    }
    return undefined;
  }

  async refreshNotificationOperations(): Promise<void> {
    // Notification observation must never call driver.reconcile: some drivers
    // resume an unattempted first input there. Only verify an already-known ID.
    for (const record of Object.values(this.records)) {
      if (
        record.phase === 'outcome-unknown' &&
        record.threadId &&
        !this.inFlight.has(record.clientOperationId)
      ) {
        await this.confirm(record);
      }
    }
  }
  notificationOperations(now = Date.now()): string[] {
    const operations: string[] = [];
    for (const record of Object.values(this.records)) {
      const id = record.clientOperationId;
      // Persisted intermediate phases and a briefly lagging owner snapshot are
      // normal creation progress. Restart conservatively begins the grace again.
      if (record.phase !== 'outcome-unknown' || this.inFlight.has(id)) {
        this.notificationUncertainSince.delete(id);
        continue;
      }
      const since = this.notificationUncertainSince.get(id) ?? now;
      this.notificationUncertainSince.set(id, since);
      if (now - since >= 30_000) operations.push(id);
    }
    return operations;
  }
  notificationOperationDetails(
    now = Date.now(),
  ): Pick<CreationRecord, 'clientOperationId' | 'threadId' | 'text' | 'attachments'>[] {
    return this.notificationOperations(now).map((id) => {
      const record = this.record(id);
      return {
        clientOperationId: id,
        threadId: record.threadId,
        text: record.text,
        attachments: structuredClone(record.attachments),
      };
    });
  }
  prepare(input: CreationInput, target: CreationTarget): CreationRecord {
    if (this.closed) throw new CreationError(503, '创建桥接已关闭');
    if (
      input.hostId !== this.hostId ||
      target.hostId !== this.hostId ||
      input.projectId !== target.projectId
    )
      throw new CreationError(403, '创建目标不一致');
    if (!validId(input.clientOperationId)) throw new CreationError(400, '创建操作 ID 无效');
    if (
      typeof input.text !== 'string' ||
      Buffer.byteLength(input.text) > 100_000 ||
      !Array.isArray(input.attachments) ||
      input.attachments.length > 10
    )
      throw new CreationError(400, '首条消息或附件无效');
    if (
      input.collaborationMode !== undefined &&
      !['plan', 'default'].includes(input.collaborationMode)
    )
      throw new CreationError(400, '无效计划模式');
    if (
      (input.model !== undefined &&
        (typeof input.model !== 'string' || !input.model || input.model.length > 200)) ||
      (input.effort !== undefined &&
        !['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'].includes(
          input.effort,
        )) ||
      (input.permissionMode !== undefined &&
        !['read-only', 'guardian-approvals', 'full-access'].includes(input.permissionMode)) ||
      (input.speed !== undefined && !['standard', 'fast', 'ultrafast'].includes(input.speed))
    )
      throw new CreationError(400, '无效生成设置');
    if (!input.text.trim() && !input.attachments.length)
      throw new CreationError(400, '请添加首条消息或附件');
    if ((input.projectId === null) !== (target.directory === null))
      throw new CreationError(400, '项目目标不明确');
    const ids = new Set<string>();
    for (const file of input.attachments) {
      if (typeof file?.name === 'string' && file.name.length > 200)
        throw new CreationError(400, '附件文件名过长，请缩短到 200 个字符以内');
      if (
        !file ||
        !validId(file.id) ||
        ids.has(file.id) ||
        typeof file.name !== 'string' ||
        !file.name ||
        file.name !== basename(file.name) ||
        [...file.name].some((character) => character.charCodeAt(0) < 32) ||
        file.name.includes('/') ||
        file.name.includes('\\') ||
        file.name === '.' ||
        file.name === '..' ||
        typeof file.mediaType !== 'string' ||
        !/^[\w.+-]+\/[\w.+-]+$/.test(file.mediaType) ||
        !Number.isSafeInteger(file.sizeBytes) ||
        file.sizeBytes < 1 ||
        file.sizeBytes > 20 * 1024 * 1024 ||
        typeof file.sha256 !== 'string' ||
        !/^[a-f0-9]{64}$/.test(file.sha256)
      )
        throw new CreationError(400, '附件清单无效');
      ids.add(file.id);
    }
    const clean: CreationInput = {
      hostId: input.hostId,
      projectId: input.projectId,
      clientOperationId: input.clientOperationId,
      text: input.text,
      ...(input.collaborationMode ? { collaborationMode: input.collaborationMode } : {}),
      ...(input.model ? { model: input.model } : {}),
      ...(input.effort ? { effort: input.effort } : {}),
      ...(input.permissionMode ? { permissionMode: input.permissionMode } : {}),
      ...(input.speed ? { speed: input.speed } : {}),
      attachments: input.attachments.map(({ id, name, mediaType, sizeBytes, sha256 }) => ({
        id,
        name,
        mediaType,
        sizeBytes,
        sha256,
      })),
    };
    // Older journals did not retain the native identity. Keep their immutable
    // fingerprint for reconciliation; new intents bind the selected native ID.
    const previous = Object.hasOwn(this.records, input.clientOperationId)
      ? this.record(input.clientOperationId)
      : undefined;
    if (previous?.nativeProjectId && previous.nativeProjectId !== target.nativeProjectId)
      throw new CreationError(409, '创建 ID 已用于不同内容或目标');
    const nativeProjectId = previous ? previous.nativeProjectId : target.nativeProjectId;
    const nativeTarget = nativeProjectId ? { nativeProjectId } : {};
    const fingerprint = hash(
      JSON.stringify({ ...clean, directory: target.directory, ...nativeTarget }),
    );
    if (Object.hasOwn(this.records, input.clientOperationId)) {
      const existing = this.record(input.clientOperationId);
      if (existing.fingerprint !== fingerprint)
        throw new CreationError(409, '创建 ID 已用于不同内容或目标');
      return this.copy(existing);
    }
    if (!this.driver.available)
      throw new CreationError(409, this.driver.reason ?? 'Codex 创建桥接未通过真实验收');
    const record: CreationRecord = {
      ...clean,
      directory: target.directory,
      ...nativeTarget,
      fingerprint,
      phase: 'prepared',
      uploaded: [],
      existingThreadIds: [...(target.existingThreadIds ?? [])],
      threadId: null,
      error: null,
    };
    this.records[input.clientOperationId] = record;
    this.save();
    return this.copy(record);
  }
  private filePath(record: CreationRecord, file: CreationFile): string {
    return creationAttachmentPath(this.directory, record, file);
  }
  upload(id: string, attachmentId: string, bytes: Buffer): CreationFile {
    const record = this.record(id);
    const file = record.attachments.find((f) => f.id === attachmentId);
    if (!file) throw new CreationError(404, '附件不属于此创建操作');
    if (record.phase !== 'prepared') throw new CreationError(409, '已提交的创建内容不能改写');
    if (bytes.length !== file.sizeBytes || hash(bytes) !== file.sha256)
      throw new CreationError(400, '附件完整性校验失败');
    const dir = join(this.directory, id, attachmentId);
    mkdirSync(dir, { recursive: true, mode: 0o700 });
    const fd = openSync(this.filePath(record, file), 'w', 0o600);
    try {
      writeFileSync(fd, bytes);
      fsyncSync(fd);
    } finally {
      closeSync(fd);
    }
    protectWindowsStateFile(this.filePath(record, file));
    if (!record.uploaded.includes(attachmentId)) record.uploaded.push(attachmentId);
    this.save();
    return structuredClone(file);
  }
  async inspect(id: string): Promise<CreationRecord> {
    const record = this.record(id);
    if (record.phase === 'outcome-unknown' && record.threadId && !this.inFlight.has(id))
      await this.confirm(record);
    return this.copy(record);
  }
  async query(id: string): Promise<CreationRecord> {
    const record = this.record(id);
    if (record.phase === 'outcome-unknown' && !record.detached && this.driver.reconcile) {
      try {
        const threadId = await this.driver.reconcile(this.plan(record));
        if (this.closed) throw new CreationError(503, '创建桥接已关闭');
        if (threadId) {
          record.threadId = threadId;
          this.save();
        }
      } catch {
        if (this.closed) throw new CreationError(503, '创建桥接已关闭');
        // A reconciliation error does not attest the original execution boundary.
        // Even a new BeforeDesktopSubmit cannot retroactively reject an unknown input.
        this.observe(record);
        this.save();
      }
    }
    if (record.phase === 'outcome-unknown' && !record.threadId) {
      try {
        const path = join(this.directory, record.clientOperationId, 'native-result.json');
        assertPrivateFile(path);
        const stat = lstatSync(path);
        if (stat.size <= 4096) {
          const result = JSON.parse(readFileSync(path, 'utf8'));
          if (
            result.hostId === this.hostId &&
            result.clientOperationId === id &&
            result.phase === 'submitted' &&
            typeof result.threadId === 'string' &&
            /^[a-zA-Z0-9_-]{8,150}$/.test(result.threadId)
          ) {
            record.threadId = result.threadId;
            this.save();
          }
        }
      } catch {
        /* Missing or invalid receipt never authorizes another Send. */
      }
    }
    if (record.phase === 'outcome-unknown' && record.threadId) await this.confirm(record);
    return this.copy(record);
  }
  async submit(id: string, background = false): Promise<CreationRecord> {
    const record = this.record(id);
    const pending = this.inFlight.get(id);
    if (pending) return background ? this.copy(record) : this.copy(await pending);
    // Re-read the bridge's durable original-operation disposition. Unknown,
    // old, detached and possibly-written inputs remain query-only.
    const observation = this.driver.observe?.(this.plan(record));
    const retryOriginal =
      record.phase === 'outcome-unknown' &&
      !record.detached &&
      observation?.preparationPhase === 'input-retryable' &&
      observation.diagnostic?.disposition === 'retry-original-preflight' &&
      !!observation.preparedThreadId;
    if (record.phase !== 'prepared' && !retryOriginal) return this.query(id);
    // Refuse known blockers before returning a background acknowledgement. A
    // rejected operation has never crossed the desktop write boundary.
    this.submissionFiles(record);
    const operation = this.tail
      .then(() => this.execute(record))
      .catch((error: unknown) => {
        if (!this.closed && record.phase === 'prepared' && error instanceof CreationError) {
          record.phase = 'rejected';
          record.error = error.message;
          this.save();
        }
        throw error;
      });
    this.tail = operation.catch(() => {});
    this.inFlight.set(id, operation);
    if (background) {
      void operation
        .catch(() => {})
        .finally(() => {
          this.inFlight.delete(id);
          if (this.closed && !this.inFlight.size) this.releaseLease();
        });
      return this.copy(record);
    }
    try {
      return this.copy(await operation);
    } finally {
      this.inFlight.delete(id);
      if (this.closed && !this.inFlight.size) this.releaseLease();
    }
  }
  detach(id: string, acknowledgeUncertain: boolean): CreationRecord {
    const record = this.record(id);
    if (['submitted', 'outcome-unknown'].includes(record.phase) && !acknowledgeUncertain)
      throw new CreationError(409, '请求可能已执行，请确认保留待确认记录后结束等待');
    if (record.phase === 'prepared') {
      record.phase = 'rejected';
      record.error = '已结束未提交的新建请求；草稿与附件已保留';
    }
    record.detached = true;
    this.driver.cancel?.(id);
    this.save();
    return this.copy(record);
  }
  private submissionFiles(record: CreationRecord): DesktopCreationPlan['files'] {
    if (this.closed) throw new CreationError(503, '创建桥接已关闭');
    if (!this.driver.available)
      throw new CreationError(409, this.driver.reason ?? '桌面桥接不可用');
    if (
      !this.driver.independentTargets &&
      Object.values(this.records).some(
        (r) => r !== record && !r.detached && ['submitted', 'outcome-unknown'].includes(r.phase),
      )
    ) {
      record.phase = 'rejected';
      record.error = '前一次桌面创建结果待确认，暂不能创建其他聊天';
      this.save();
      throw new CreationError(409, '前一次桌面创建结果待确认，暂不能创建其他聊天');
    }
    return record.attachments.map((file) => {
      const path = this.filePath(record, file);
      if (!record.uploaded.includes(file.id) || !existsSync(path))
        throw new CreationError(409, '请先完成首条附件上传');
      const stat = lstatSync(path);
      if (!stat.isFile() || stat.isSymbolicLink() || stat.size !== file.sizeBytes)
        throw new CreationError(409, '首条附件文件不可用');
      const bytes = readFileSync(path);
      if (bytes.length !== file.sizeBytes || hash(bytes) !== file.sha256)
        throw new CreationError(409, '首条附件已变化，请重新上传');
      return { ...file, path };
    });
  }
  private async execute(record: CreationRecord): Promise<CreationRecord> {
    if (record.detached) return record;
    const files = this.submissionFiles(record);
    record.phase = 'submitted';
    record.error = null;
    // Persist before any desktop side effect. Any unclassified failure is uncertain.
    this.save();
    mkdirSync(join(this.directory, record.clientOperationId), { recursive: true, mode: 0o700 });
    try {
      const threadId = await this.driver.create({
        ...this.copy(record),
        files,
        receiptPath: join(this.directory, record.clientOperationId, 'native-result.json'),
      });
      if (this.closed) throw new CreationError(503, '创建桥接已关闭');
      if (!/^[a-zA-Z0-9_-]{8,150}$/.test(threadId)) throw new Error('Invalid native thread ID');
      record.threadId = threadId;
      record.phase = 'outcome-unknown';
      this.save();
      await this.confirm(record);
    } catch (error) {
      if (this.closed) throw error;
      record.phase = error instanceof BeforeDesktopSubmit ? 'rejected' : 'outcome-unknown';
      record.error =
        error instanceof BeforeDesktopSubmit
          ? '桌面提交前已拒绝；草稿与附件已保留'
          : '桌面创建结果待确认，请查询状态，勿重复发送';
      this.save();
    }
    this.observe(record);
    this.save();
    return record;
  }
  private async confirm(record: CreationRecord): Promise<void> {
    if (record.phase === 'confirmed') return;
    this.observe(record);
    try {
      const verified = record.threadId && (await this.verify(this.copy(record), record.threadId));
      if (this.closed) throw new CreationError(503, '创建桥接已关闭');
      if (verified) {
        record.phase = 'confirmed';
        record.error = null;
        this.save();
      } else {
        record.error = '尚未确认 Codex会话归属、首条输入和运行时';
        this.save();
      }
    } catch {
      if (this.closed) throw new CreationError(503, '创建桥接已关闭');
      record.error = 'Codex 确认暂不可用，请稍后查询';
      this.save();
    }
  }
  close(): void {
    this.closed = true;
    if (!this.inFlight.size) this.releaseLease();
  }
  private releaseLease(): void {
    if (
      this.lease &&
      existsSync(this.lease) &&
      readFileSync(this.lease, 'utf8') === String(process.pid)
    )
      unlinkSync(this.lease);
    this.lease = undefined;
  }
}
