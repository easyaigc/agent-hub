import { createHash, randomUUID } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  writeFileSync,
  unlinkSync,
  openSync,
  closeSync,
  fsyncSync,
  lstatSync,
} from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { open as openFile, unlink as unlinkFile } from 'node:fs/promises';
import {
  assertPrivateFile,
  assertPrivateFileAsync,
  protectWindowsStateFile,
  protectWindowsStateFileAsync,
} from './desktop-platform.js';
import { runtimeFailure } from './runtime-diagnostics.js';
import { machineRuntimeTargetKey, parseNotificationTarget } from '@codex-manage/protocol';

interface CachedThread {
  id: string;
  messages: unknown[];
}
interface RuntimeState {
  hostId: string;
  threads: CachedThread[];
}
export class RuntimePersistenceError extends Error {
  constructor(
    readonly stage: string,
    cause: unknown,
  ) {
    super('Runtime state could not be persisted', { cause });
  }
}
interface PersistenceOptions {
  protect?: typeof protectWindowsStateFile;
  protectAsync?: typeof protectWindowsStateFileAsync;
  rename?: typeof renameSync;
  retryDelays?: readonly number[];
  cooldownMs?: number;
  report?: typeof runtimeFailure;
  fsync?: typeof fsyncSync;
  platform?: NodeJS.Platform;
}

/** An operator-supplied, private handoff. It is never accepted through HTTP. */
export interface ReplyLedgerCheckpoint {
  version: 2;
  kind: 'reply-ledger-fenced-handoff-v2';
  target: unknown;
  oldPid: number;
  newPid: number;
  ledgerSha256: string;
  fenceFile: string;
  fenceSha256: string;
  operations: { threadId: string; clientMessageId: string; fingerprint: string }[];
}

const digest = (bytes: string | Buffer) => createHash('sha256').update(bytes).digest('hex');
function targetKey(value: unknown): string {
  try {
    return machineRuntimeTargetKey(parseNotificationTarget(value));
  } catch {
    throw new Error('Invalid reply handoff target');
  }
}
const operationKey = (value: ReplyLedgerCheckpoint['operations'][number]) =>
  JSON.stringify([value.threadId, value.clientMessageId, value.fingerprint]);
const gone = (pid: number): boolean => {
  try {
    process.kill(pid, 0);
    return false;
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === 'ESRCH';
  }
};

function privateBytes(file: string): Buffer {
  const info = lstatSync(file);
  if (
    !info.isFile() ||
    info.isSymbolicLink() ||
    (process.platform !== 'win32' && ((info.mode & 0o077) !== 0 || info.uid !== process.getuid?.()))
  )
    throw new Error('Reply handoff must be a private regular file owned by this user');
  // Windows checkpoints remain unavailable until a directory-flush proof is implemented.
  return readFileSync(file);
}

/** Command recovery is durable immediately; replaceable history never sits on its write path. */
export class RuntimePersistence<State extends RuntimeState> {
  private readonly historyDirectory: string;
  private readonly pending = new Map<string, CachedThread>();
  private readonly references = new Map<string, unknown[]>();
  private timer: NodeJS.Timeout | undefined;
  private historyDueAt = 0;
  private continuation: NodeJS.Immediate | undefined;
  private hostId = '';
  private closed = false;
  private backgroundTimer: NodeJS.Timeout | undefined;
  private backgroundDueAt = 0;
  private backgroundHolds = 0;
  private backgroundState: (() => State) | undefined;
  private backgroundVersion = 0;
  private writeVersion = 0;
  private backgroundTask: Promise<void> | undefined;
  private historyTask: Promise<void> | undefined;
  private readonly historyVersions = new Map<string, number>();
  private readonly publicationWaiters = new Set<() => void>();
  private backgroundAttempt = 0;
  private backgroundRetryAfter = 0;
  private readonly failures = new Map<string, { attempt: number; after: number }>();
  private readonly delays: readonly number[];
  private writer?: { pid: number; nonce: string; target: unknown; ledgerId: string };
  private ledgerHead: string | undefined;
  private loadedHead: string | undefined;
  private durableHead: string | undefined;
  private checkpoint: ReplyLedgerCheckpoint | undefined;
  private writerFile: string;

  constructor(
    private readonly file: string,
    directory: string,
    private readonly options: PersistenceOptions = {},
  ) {
    this.delays = options.retryDelays ?? [200, 500, 1500, 5000];
    if (!this.delays.length || this.delays.some((delay) => !Number.isFinite(delay) || delay < 0))
      throw new Error('Invalid persistence retry delays');
    if (
      options.cooldownMs !== undefined &&
      (!Number.isFinite(options.cooldownMs) || options.cooldownMs < 1)
    )
      throw new Error('Invalid persistence cooldown');
    this.historyDirectory = join(directory, 'runtime-history-v1');
    this.writerFile = join(directory, 'reply-writer-v2.json');
    mkdirSync(this.historyDirectory, { recursive: true, mode: 0o700 });
  }

  private historyFile(id: string): string {
    const digest = createHash('sha256')
      .update(this.hostId + '\0' + id)
      .digest('hex');
    return join(this.historyDirectory, digest + '.json');
  }

  private atomicWrite(file: string, value: unknown, durable = false, replaceable = false): void {
    const temporary = file + '.' + randomUUID() + '.tmp';
    let owned = false;
    let published = false;
    let stage = 'serialize';
    try {
      const json = JSON.stringify(value);
      if (
        replaceable &&
        durable &&
        file === this.file &&
        this.writer &&
        this.durableHead !== undefined &&
        this.ledgerHead === this.durableHead &&
        digest(json) === this.durableHead
      ) {
        // saveState already rechecked the real writer nonce and complete file
        // bytes. Also retain the current private-file boundary before a no-op.
        assertPrivateFile(file);
        return;
      }
      // Any failed new write invalidates cached durability, even if its bytes
      // equal the last save (including a publish followed by fsync failure).
      if (file === this.file) this.durableHead = undefined;
      stage = 'write';
      // Exclusive creation prevents cleanup from ever unlinking somebody else's file.
      writeFileSync(temporary, '', { mode: 0o600, flag: 'wx' });
      owned = true;
      writeFileSync(temporary, json);
      stage = 'protect';
      (this.options.protect ?? protectWindowsStateFile)(temporary);
      if (durable) {
        stage = 'file-fsync';
        // Windows FlushFileBuffers requires a writable handle. Reopening the
        // owned temporary file read-only makes fsync fail with EPERM there.
        const fd = openSync(temporary, 'r+');
        try {
          (this.options.fsync ?? fsyncSync)(fd);
        } finally {
          closeSync(fd);
        }
      }
      stage = 'publish';
      (this.options.rename ?? renameSync)(temporary, file);
      published = true;
      if (file === this.file) this.ledgerHead = digest(json);
      if (durable && (this.options.platform ?? process.platform) !== 'win32') {
        stage = 'directory-fsync';
        const fd = openSync(dirname(file), 'r');
        try {
          (this.options.fsync ?? fsyncSync)(fd);
        } finally {
          closeSync(fd);
        }
      }
      if (file === this.file && durable) this.durableHead = digest(json);
    } catch (error) {
      throw new RuntimePersistenceError(stage, error);
    } finally {
      if (owned && !published) {
        try {
          unlinkSync(temporary);
        } catch (error) {
          this.report('temporary-cleanup', new RuntimePersistenceError('cleanup', error));
        }
      }
    }
  }

  /** Acquires one writer before any command save. A stale file never proves absence. */
  acquireReplyWriter(target: unknown, checkpointFile?: string): void {
    const raw = existsSync(this.file) ? readFileSync(this.file) : undefined;
    this.ledgerHead = raw ? digest(raw) : undefined;
    if (this.ledgerHead !== this.loadedHead)
      throw new Error('Reply ledger changed during restoration');
    const prior = raw ? JSON.parse(raw.toString('utf8')).replyLedger : undefined;
    let checkpoint: ReplyLedgerCheckpoint | undefined;
    if (checkpointFile) {
      checkpoint = JSON.parse(privateBytes(checkpointFile).toString('utf8'));
      if (
        !checkpoint ||
        checkpoint.version !== 2 ||
        checkpoint.kind !== 'reply-ledger-fenced-handoff-v2' ||
        checkpoint.newPid !== process.pid ||
        !Number.isSafeInteger(checkpoint.oldPid) ||
        checkpoint.oldPid <= 0 ||
        checkpoint.oldPid === process.pid ||
        !gone(checkpoint.oldPid) ||
        !raw ||
        checkpoint.ledgerSha256 !== this.ledgerHead ||
        targetKey(checkpoint.target) !== targetKey(target) ||
        !Array.isArray(checkpoint.operations) ||
        !checkpoint.operations.length ||
        checkpoint.operations.some(
          (v) =>
            !v ||
            typeof v.threadId !== 'string' ||
            !v.threadId ||
            typeof v.clientMessageId !== 'string' ||
            !v.clientMessageId ||
            v.clientMessageId.length > 128 ||
            !/^[a-f0-9]{64}$/.test(v.fingerprint),
        ) ||
        new Set(checkpoint.operations.map((v) => v.clientMessageId)).size !==
          checkpoint.operations.length
      )
        throw new Error('Invalid or unfenced reply ledger checkpoint');
      const fenceBytes = privateBytes(checkpoint.fenceFile);
      if (digest(fenceBytes) !== checkpoint.fenceSha256)
        throw new Error('Reply fence SHA mismatch');
      const fence = JSON.parse(fenceBytes.toString('utf8'));
      if (
        fence.version !== 2 ||
        fence.oldPid !== checkpoint.oldPid ||
        fence.ledgerSha256 !== this.ledgerHead ||
        targetKey(fence.target) !== targetKey(target)
      )
        throw new Error('Reply fence target mismatch');
      if (
        prior &&
        (prior.version !== 2 ||
          prior.contract !== 'fsync-before-model-v2' ||
          typeof prior.writerNonce !== 'string' ||
          !/^[a-f0-9-]{36}$/.test(prior.writerNonce) ||
          typeof prior.ledgerId !== 'string' ||
          !/^[a-f0-9-]{36}$/.test(prior.ledgerId) ||
          prior.writerPid !== checkpoint.oldPid ||
          targetKey(prior.target) !== targetKey(target) ||
          fence.writerNonce !== prior.writerNonce ||
          fence.ledgerId !== prior.ledgerId)
      )
        throw new Error('Incompatible reply ledger continuity');
      if (prior && fence.kind === 'closed-reply-writer-v2') {
        // A normal v2 boot cannot retroactively bless an unproved legacy/missing ledger.
        // Carry only the explicit scopes actually validated by its private predecessor handoff.
        if (
          resolve(checkpoint.fenceFile) !==
            resolve(join(dirname(this.file), 'reply-writer-closed-v2.json')) ||
          !Array.isArray(fence.absenceOperations) ||
          checkpoint.operations.some(
            (operation) =>
              !fence.absenceOperations.some(
                (covered: ReplyLedgerCheckpoint['operations'][number]) =>
                  covered.threadId === operation.threadId &&
                  covered.clientMessageId === operation.clientMessageId &&
                  covered.fingerprint === operation.fingerprint,
              ),
          )
        )
          throw new Error(
            'Reply close fence has no audited historical coverage for this operation',
          );
      } else {
        // This private operator attestation establishes the missing historical boundary;
        // validating hashes is not an automatic semantic audit of legacy code or logs.
        if (
          fence.kind !== 'operator-audited-legacy-fence-v2' ||
          fence.version !== 2 ||
          fence.boundary !== 'receipt-before-model-write-no-deletion' ||
          fence.singleWriter !== true ||
          !Array.isArray(fence.operations) ||
          JSON.stringify(fence.operations.map(operationKey)) !==
            JSON.stringify(checkpoint.operations.map(operationKey)) ||
          !Array.isArray(fence.sourceFiles) ||
          !fence.sourceFiles.length ||
          !Array.isArray(fence.logFiles) ||
          !fence.logFiles.length
        )
          throw new Error('Legacy reply ledger requires a scoped operator boundary audit');
        for (const artifact of [...fence.sourceFiles, ...fence.logFiles])
          if (
            !artifact ||
            !/^[a-f0-9]{64}$/.test(artifact.sha256) ||
            digest(privateBytes(artifact.file)) !== artifact.sha256
          )
            throw new Error('Legacy reply fence artifact SHA mismatch');
      }
    }
    // Serialize stale-lock replacement too; checking a dead PID then unlinking without
    // this guard lets two successors delete each other's freshly acquired lease.
    const recoveryFile = this.writerFile + '.acquiring';
    const guard = openSync(recoveryFile, 'wx', 0o600);
    try {
      if ((existsSync(this.file) ? digest(readFileSync(this.file)) : undefined) !== this.ledgerHead)
        throw new Error('Reply ledger changed before writer acquisition');
      if (existsSync(this.writerFile)) {
        const owner = JSON.parse(privateBytes(this.writerFile).toString('utf8'));
        if (!Number.isSafeInteger(owner.pid) || owner.pid <= 0 || !gone(owner.pid))
          throw new Error('Reply ledger already has a live or unverified writer');
        if (
          checkpoint &&
          (owner.pid !== checkpoint.oldPid || (prior && owner.nonce !== prior.writerNonce))
        )
          throw new Error('Reply checkpoint does not cover the last writer lease');
        unlinkSync(this.writerFile);
      }
      const writer = {
        pid: process.pid,
        nonce: randomUUID(),
        target,
        ledgerId:
          prior?.version === 2 && typeof prior.ledgerId === 'string'
            ? prior.ledgerId
            : randomUUID(),
      };
      writeFileSync(this.writerFile, JSON.stringify(writer), { flag: 'wx', mode: 0o600 });
      protectWindowsStateFile(this.writerFile);
      this.writer = writer;
      this.checkpoint = checkpoint;
    } finally {
      closeSync(guard);
      unlinkSync(recoveryFile);
    }
  }

  private assertReplyWriter(): void {
    if (!this.writer) return; // Standalone history-cache fixtures do not own execution.
    const owner = JSON.parse(readFileSync(this.writerFile, 'utf8'));
    const head = existsSync(this.file) ? digest(readFileSync(this.file)) : undefined;
    if (owner.nonce !== this.writer.nonce || owner.pid !== process.pid || head !== this.ledgerHead)
      throw new RuntimePersistenceError('writer-continuity', undefined);
  }

  replyAbsence(threadId: string, clientMessageId: string, target: unknown) {
    this.assertReplyWriter();
    if (
      this.closed ||
      !this.writer ||
      (this.options.platform ?? process.platform) === 'win32' ||
      targetKey(this.writer.target) !== targetKey(target)
    )
      return undefined;
    const operation = this.checkpoint?.operations.find(
      (v) => v.threadId === threadId && v.clientMessageId === clientMessageId,
    );
    return operation
      ? {
          kind: 'operator-fenced-checkpoint-v2' as const,
          fingerprint: operation.fingerprint,
          oldPid: this.checkpoint!.oldPid,
          newPid: process.pid,
          ledgerSha256: this.checkpoint!.ledgerSha256,
          fenceSha256: this.checkpoint!.fenceSha256,
          ledgerId: this.writer.ledgerId,
        }
      : undefined;
  }

  replyCheckpointIntent(clientMessageId: string) {
    return this.checkpoint?.operations.find((value) => value.clientMessageId === clientMessageId);
  }

  private report(scope: string, error: unknown, attempt?: number): void {
    try {
      (this.options.report ?? runtimeFailure)(scope, error, attempt);
    } catch {
      // Diagnostics never change write success or retry semantics.
    }
  }

  get status() {
    return {
      backgroundSave: !this.backgroundState
        ? 'idle'
        : this.backgroundAttempt >= this.delays.length
          ? 'stalled'
          : this.backgroundAttempt
            ? 'retrying'
            : 'pending',
      backgroundAttempt: this.backgroundAttempt,
      backgroundRetryAt: this.backgroundRetryAfter || undefined,
      historyPending: this.pending.size,
      historyRetrying: this.failures.size,
      historyStalled: [...this.failures.values()].filter(
        (value) => value.attempt >= this.delays.length,
      ).length,
      commandDurability:
        (this.options.platform ?? process.platform) === 'win32'
          ? 'file-flush-only-unproven'
          : 'file-and-directory-fsync',
    };
  }

  schedule(state: () => State): void {
    if (this.closed) return;
    this.backgroundState = state;
    this.backgroundVersion++;
    if (this.backgroundTimer || this.backgroundTask) return;
    const cooling = this.backgroundAttempt >= this.delays.length;
    const delay = cooling
      ? Math.max(1, this.backgroundRetryAfter - Date.now())
      : this.delays[this.backgroundAttempt]!;
    this.backgroundDueAt ||= Date.now() + delay;
    if (this.backgroundHolds) return;
    this.backgroundTimer = setTimeout(
      () => {
        this.backgroundTimer = undefined;
        if (this.closed || this.backgroundHolds || !this.backgroundState) return;
        this.backgroundDueAt = 0;
        if (cooling) {
          this.backgroundAttempt = 0;
          this.backgroundRetryAfter = 0;
        }
        if ((this.options.platform ?? process.platform) === 'win32') {
          this.backgroundTask = this.saveBackgroundAsync().finally(() => {
            this.backgroundTask = undefined;
            if (!this.closed && this.backgroundState) this.schedule(this.backgroundState);
          });
          return;
        }
        try {
          this.saveState(this.backgroundState(), true);
        } catch (error) {
          this.report('background-save', error, ++this.backgroundAttempt);
          if (this.backgroundAttempt >= this.delays.length)
            this.backgroundRetryAfter = Date.now() + (this.options.cooldownMs ?? 60_000);
          this.schedule(this.backgroundState);
        }
      },
      Math.max(0, this.backgroundDueAt - Date.now()),
    );
  }

  /** Defer only scheduled cache writes; explicit save/read/close barriers still run. */
  deferBackground(): () => void {
    if (this.closed) return () => {};
    this.backgroundHolds++;
    if (this.backgroundTimer) clearTimeout(this.backgroundTimer);
    if (this.timer) clearTimeout(this.timer);
    if (this.continuation) clearImmediate(this.continuation);
    this.backgroundTimer = undefined;
    this.timer = undefined;
    this.continuation = undefined;
    this.historyDueAt = 0;
    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.backgroundHolds--;
      if (this.closed || this.backgroundHolds) return;
      this.wakePublications();
      if (this.backgroundState) this.schedule(this.backgroundState);
      this.scheduleHistory();
    };
  }

  private wakePublications(): void {
    for (const wake of this.publicationWaiters) wake();
    this.publicationWaiters.clear();
  }

  private async publicationAllowed(current: () => boolean): Promise<boolean> {
    while (!this.closed && this.backgroundHolds)
      await new Promise<void>((resolve) => this.publicationWaiters.add(resolve));
    return !this.closed && current();
  }

  /** Only replaceable Windows background I/O uses this path; command barriers stay synchronous. */
  private async atomicWriteBackground(
    file: string,
    value: unknown,
    durable: boolean,
    current: () => boolean,
  ): Promise<boolean> {
    const temporary = file + '.' + randomUUID() + '.tmp';
    let owned = false;
    let published = false;
    let stage = 'serialize';
    let handle: Awaited<ReturnType<typeof openFile>> | undefined;
    try {
      const json = JSON.stringify(value);
      if (file === this.file) {
        this.assertReplyWriter();
        if (
          this.writer &&
          this.durableHead !== undefined &&
          this.ledgerHead === this.durableHead &&
          digest(json) === this.durableHead
        ) {
          await assertPrivateFileAsync(file);
          if (!(await this.publicationAllowed(current)) || this.closed || !current()) return false;
          this.assertReplyWriter();
          return true;
        }
        this.durableHead = undefined;
      }
      stage = 'write';
      handle = await openFile(temporary, 'wx', 0o600);
      owned = true;
      await handle.writeFile(json);
      stage = 'protect';
      if (this.options.protectAsync) await this.options.protectAsync(temporary);
      else if (this.options.protect) this.options.protect(temporary);
      else await protectWindowsStateFileAsync(temporary);
      if (durable) {
        stage = 'file-fsync';
        if (this.options.fsync) this.options.fsync(handle.fd);
        else await handle.sync();
      }
      await handle.close();
      handle = undefined;
      if (!(await this.publicationAllowed(current)) || this.closed || !current()) return false;
      // Main alone publishes. A synchronous save/read/close or newer cache
      // version makes this temporary file obsolete before it can replace data.
      if (file === this.file) this.assertReplyWriter();
      stage = 'publish';
      (this.options.rename ?? renameSync)(temporary, file);
      published = true;
      if (file === this.file) {
        this.ledgerHead = digest(json);
        this.durableHead = digest(json);
      }
      return true;
    } catch (error) {
      throw new RuntimePersistenceError(stage, error);
    } finally {
      await handle?.close().catch(() => {
        // The original failure is retained; cleanup of the owned path still follows.
      });
      if (owned && !published) {
        try {
          await unlinkFile(temporary);
        } catch (error) {
          this.report('temporary-cleanup', new RuntimePersistenceError('cleanup', error));
        }
      }
    }
  }

  private async saveBackgroundAsync(): Promise<void> {
    const version = this.backgroundVersion;
    const writeVersion = this.writeVersion;
    const current = () => version === this.backgroundVersion && writeVersion === this.writeVersion;
    try {
      const state = this.backgroundState!();
      this.hostId = state.hostId;
      const threads = state.threads.map(({ id, messages }) => ({ id, messages }));
      if (
        !(await this.atomicWriteBackground(this.file, this.persistedState(state), true, current)) ||
        !current()
      )
        return;
      for (const thread of threads) {
        if (this.references.get(thread.id) !== thread.messages) this.dirty(thread);
        this.references.set(thread.id, thread.messages);
      }
      this.backgroundState = undefined;
      this.backgroundAttempt = 0;
      this.backgroundRetryAfter = 0;
    } catch (error) {
      if (this.closed || !current()) return;
      this.report('background-save', error, ++this.backgroundAttempt);
      if (this.backgroundAttempt >= this.delays.length)
        this.backgroundRetryAfter = Date.now() + (this.options.cooldownMs ?? 60_000);
    }
  }

  /** Wait for owned temporary I/O cleanup; does not flush or execute a command. */
  async settleBackground(): Promise<void> {
    await Promise.allSettled([this.backgroundTask, this.historyTask]);
  }

  load(): State | undefined {
    if (!existsSync(this.file)) return undefined;
    const bytes = readFileSync(this.file);
    this.loadedHead = digest(bytes);
    const state = JSON.parse(bytes.toString('utf8')) as State & {
      historyCacheVersion?: number;
    };
    this.hostId = state.hostId;
    if (state.historyCacheVersion !== undefined && state.historyCacheVersion !== 1)
      throw new Error('Unsupported runtime history cache version');
    for (const thread of state.threads) {
      if (state.historyCacheVersion === 1) {
        try {
          const cache = JSON.parse(readFileSync(this.historyFile(thread.id), 'utf8'));
          if (
            cache.version === 1 &&
            cache.hostId === state.hostId &&
            cache.threadId === thread.id &&
            Array.isArray(cache.messages)
          )
            thread.messages = cache.messages;
        } catch {
          // Cache loss cannot discard command receipts or grant desktop control.
        }
      } else {
        // Complete migration before replacing the legacy file. On failure the
        // original full state is still recoverable and the next start retries.
        this.writeHistory(thread);
      }
      this.references.set(thread.id, thread.messages);
    }
    return state;
  }

  save(state: State): void {
    this.saveState(state, false);
  }

  /** Reads retain the same synchronous durability for every changed state. */
  saveRead(state: State): void {
    this.saveState(state, true);
  }

  private persistedState(state: State) {
    return {
      ...state,
      ...(this.writer
        ? {
            replyLedger: {
              version: 2,
              contract:
                (this.options.platform ?? process.platform) === 'win32'
                  ? 'file-flush-only'
                  : 'fsync-before-model-v2',
              target: this.writer.target,
              ledgerId: this.writer.ledgerId,
              writerPid: this.writer.pid,
              writerNonce: this.writer.nonce,
            },
          }
        : {}),
      historyCacheVersion: 1,
      threads: state.threads.map((thread) => ({ ...thread, messages: [] })),
    };
  }

  private saveState(state: State, replaceable: boolean): void {
    if (this.closed) throw new RuntimePersistenceError('closed', undefined);
    this.writeVersion++;
    this.hostId = state.hostId;
    this.assertReplyWriter();
    this.atomicWrite(this.file, this.persistedState(state), true, replaceable);
    this.ledgerHead = digest(readFileSync(this.file));
    for (const thread of state.threads) {
      if (this.references.get(thread.id) !== thread.messages) this.dirty(thread);
      this.references.set(thread.id, thread.messages);
    }
    if (this.backgroundTimer) clearTimeout(this.backgroundTimer);
    this.backgroundTimer = undefined;
    this.backgroundDueAt = 0;
    this.backgroundState = undefined;
    this.backgroundAttempt = 0;
    this.backgroundRetryAfter = 0;
  }

  dirty(thread: CachedThread): void {
    if (this.closed) return;
    if (
      !thread.messages.length &&
      !this.references.get(thread.id)?.length &&
      !this.pending.has(thread.id)
    )
      return;
    this.historyVersions.set(thread.id, (this.historyVersions.get(thread.id) ?? 0) + 1);
    this.pending.set(thread.id, thread);
    this.scheduleHistory(1000);
  }

  private scheduleHistory(delay = 0): void {
    if (
      this.closed ||
      this.backgroundHolds ||
      this.historyTask ||
      this.continuation ||
      !this.pending.size
    )
      return;
    const earliest = Math.min(
      ...[...this.pending.keys()].map((id) => this.failures.get(id)?.after ?? 0),
    );
    const dueAt = Math.max(Date.now() + delay, earliest);
    if (this.timer && this.historyDueAt <= dueAt) return;
    if (this.timer) clearTimeout(this.timer);
    this.historyDueAt = dueAt;
    this.timer = setTimeout(
      () => {
        this.timer = undefined;
        this.drainOne();
      },
      Math.max(0, dueAt - Date.now()),
    );
  }

  private writeHistory(thread: CachedThread): void {
    this.atomicWrite(this.historyFile(thread.id), {
      version: 1,
      hostId: this.hostId,
      threadId: thread.id,
      messages: thread.messages,
    });
  }

  private drainOne(): void {
    this.continuation = undefined;
    if (this.closed || this.backgroundHolds) return;
    const next = [...this.pending.entries()].find(([id]) => {
      const failure = this.failures.get(id);
      return !failure || failure.after <= Date.now();
    });
    if (!next) return this.scheduleHistory();
    const [id, thread] = next;
    if ((this.options.platform ?? process.platform) === 'win32') {
      this.historyTask = this.writeHistoryBackground(id, thread).finally(() => {
        this.historyTask = undefined;
        if (!this.closed) this.scheduleHistory();
      });
      return;
    }
    try {
      this.writeHistory(thread);
      this.pending.delete(id);
      this.failures.delete(id);
    } catch (error) {
      const prior = this.failures.get(id)?.attempt ?? 0;
      const attempt = (prior >= this.delays.length ? 0 : prior) + 1;
      this.failures.set(id, {
        attempt,
        after: Date.now() + (this.delays[attempt] ?? this.options.cooldownMs ?? 60_000),
      });
      this.report('history-save', error, attempt);
      // A failed item joins the back of the queue, including after cooldown.
      this.pending.delete(id);
      this.pending.set(id, thread);
    }
    // Give IPC, approvals and sends a turn between different history files.
    if (this.pending.size) this.continuation = setImmediate(() => this.drainOne());
  }

  private async writeHistoryBackground(id: string, thread: CachedThread): Promise<void> {
    const version = this.historyVersions.get(id);
    const current = () => version === this.historyVersions.get(id);
    try {
      const published = await this.atomicWriteBackground(
        this.historyFile(id),
        {
          version: 1,
          hostId: this.hostId,
          threadId: id,
          messages: thread.messages,
        },
        false,
        current,
      );
      if (!published || !current()) return;
      this.pending.delete(id);
      this.historyVersions.delete(id);
      this.failures.delete(id);
    } catch (error) {
      if (this.closed || !current()) return;
      const prior = this.failures.get(id)?.attempt ?? 0;
      const attempt = (prior >= this.delays.length ? 0 : prior) + 1;
      this.failures.set(id, {
        attempt,
        after: Date.now() + (this.delays[attempt] ?? this.options.cooldownMs ?? 60_000),
      });
      this.report('history-save', error, attempt);
      // Preserve the latest pending object, including changes during I/O.
      const latest = this.pending.get(id);
      if (latest) {
        this.pending.delete(id);
        this.pending.set(id, latest);
      }
    }
  }

  close(replyWorkFenced = false): void {
    if (this.closed) return;
    this.closed = true;
    this.writeVersion++;
    this.wakePublications();
    if (this.backgroundTimer) clearTimeout(this.backgroundTimer);
    this.backgroundTimer = undefined;
    this.backgroundState = undefined;
    if (this.timer) clearTimeout(this.timer);
    if (this.continuation) clearImmediate(this.continuation);
    this.timer = undefined;
    this.continuation = undefined;
    let failure: unknown;
    for (const thread of this.pending.values()) {
      try {
        this.writeHistory(thread);
      } catch (error) {
        failure ??= error;
        this.report('history-close', error);
      }
    }
    this.pending.clear();
    this.historyVersions.clear();
    this.failures.clear();
    if (this.writer) {
      try {
        this.assertReplyWriter();
        // Only Session.close may attest that all reply continuations are fenced.
        if (replyWorkFenced)
          this.atomicWrite(
            join(dirname(this.file), 'reply-writer-closed-v2.json'),
            {
              version: 2,
              kind: 'closed-reply-writer-v2',
              oldPid: this.writer.pid,
              target: this.writer.target,
              writerNonce: this.writer.nonce,
              ledgerId: this.writer.ledgerId,
              ledgerSha256: this.ledgerHead,
              absenceOperations: this.checkpoint?.operations ?? [],
            },
            true,
          );
        unlinkSync(this.writerFile);
      } catch (error) {
        failure ??= error;
      }
    }
    if (failure) throw failure;
  }
}
