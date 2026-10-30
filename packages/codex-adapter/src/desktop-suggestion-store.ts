import { createHash, randomUUID } from 'node:crypto';
import {
  closeSync,
  existsSync,
  fsyncSync,
  fstatSync,
  lstatSync,
  openSync,
  constants,
  readFileSync,
  realpathSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { threadId } from 'node:worker_threads';
import {
  assertPrivateFile,
  makePrivateDirectorySync,
  openPrivateFileSync,
  renamePrivateFileSync,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  windowsTraceSnapshot,
} from './desktop-platform.js';
import type {
  DesktopPredictionTracePrediction,
  DesktopPredictionTraceBinding,
} from './desktop-prediction-trace.js';

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const hash = (value: string | Buffer) => createHash('sha256').update(value).digest('hex');
export const compactSuggestionMaximum = 128 * 1024;
export interface CompactDesktopSuggestion {
  version: 1;
  targetKey: string;
  sourceThreadId: string;
  sourceTurnId: string;
  predictionThreadId: string;
  predictionTurnId: string;
  requestEvidence: DesktopPredictionTraceBinding['evidence'];
  requestedAt: number;
  recordedAt: number;
  prediction: DesktopPredictionTracePrediction | null;
  state: 'completed' | 'cancelled' | 'failed' | 'empty' | 'unverified';
  /** At most one extracted body; invalid current attempts never expose it as current. */
  latestExtracted?: { requestedAt: number; prediction: DesktopPredictionTracePrediction };
}
export interface RuntimeDesktopSuggestionBody {
  requestId: string;
  sourceTurnId: string;
  text: string;
  contentSha256: string;
  resultSha256: string;
  responseId: string | null;
  completedAt: number;
  safety: { decidedAt: number; evidenceSha256: string };
}
/** A result observed on the original desktop's own connection, never a new request. */
export interface RuntimeDesktopSuggestion {
  version: 2;
  kind: 'native-sample';
  targetKey: string;
  sourceThreadId: string;
  sourceTurnId: string;
  requestId: string;
  requestSequence: number;
  requestedAt: number;
  recordedAt: number;
  producer: { epoch: string; pid: number; desktopPid: number; desktopStartedAt: number };
  state: 'pending' | 'completed' | 'cancelled' | 'failed' | 'empty' | 'unverified' | 'suppressed';
  result: RuntimeDesktopSuggestionBody | null;
  latestExtracted?:
    | { kind: 'native-sample'; requestedAt: number; result: RuntimeDesktopSuggestionBody }
    | { kind: 'native-trace'; record: CompactDesktopSuggestion };
}
export interface RuntimeSuggestionProducerState {
  version: 1;
  kind: 'native-sample-producer';
  targetKey: string;
  producer: RuntimeDesktopSuggestion['producer'];
  wrapperStartedAt: number;
  rootIdentity: string;
  implementationSha256: string;
  checkedAt: number;
  phase: 'listening' | 'unavailable';
}
function validProducer(value: unknown, key: string): value is RuntimeSuggestionProducerState {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const s = value as RuntimeSuggestionProducerState,
    p = s.producer;
  return Boolean(
    s.version === 1 &&
    s.kind === 'native-sample-producer' &&
    s.targetKey === key &&
    p &&
    uuid.test(p.epoch) &&
    integer(p.pid) &&
    p.pid > 1 &&
    integer(p.desktopPid) &&
    p.desktopPid > 1 &&
    integer(p.desktopStartedAt) &&
    integer(s.wrapperStartedAt) &&
    s.wrapperStartedAt >= p.desktopStartedAt &&
    typeof s.rootIdentity === 'string' &&
    s.rootIdentity.length > 0 &&
    s.rootIdentity.length < 2048 &&
    digest(s.implementationSha256) &&
    integer(s.checkedAt) &&
    s.checkedAt >= s.wrapperStartedAt &&
    ['listening', 'unavailable'].includes(s.phase),
  );
}
const sampleId = (id: unknown): id is string =>
  typeof id === 'string' && id.startsWith('thread/sample:') && uuid.test(id.slice(14));
const integer = (n: unknown): n is number => Number.isSafeInteger(n) && Number(n) >= 0;
const digest = (s: unknown): s is string => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s);
function validRuntimeBody(value: unknown): value is RuntimeDesktopSuggestionBody {
  if (!value || typeof value !== 'object') return false;
  const b = value as RuntimeDesktopSuggestionBody;
  return Boolean(
    sampleId(b.requestId) &&
    uuid.test(b.sourceTurnId) &&
    typeof b.text === 'string' &&
    b.text.trim().length > 0 &&
    b.text.length <= 16000 &&
    b.contentSha256 === hash(b.text) &&
    digest(b.resultSha256) &&
    (b.responseId === null || (typeof b.responseId === 'string' && b.responseId.length <= 512)) &&
    integer(b.completedAt) &&
    b.safety &&
    integer(b.safety.decidedAt) &&
    b.safety.decidedAt >= b.completedAt &&
    digest(b.safety.evidenceSha256),
  );
}
function validRuntime(
  value: unknown,
  key: string,
  threadId: string,
): value is RuntimeDesktopSuggestion {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const r = value as RuntimeDesktopSuggestion;
  if (
    r.version !== 2 ||
    r.kind !== 'native-sample' ||
    r.targetKey !== key ||
    r.sourceThreadId !== threadId ||
    !uuid.test(threadId) ||
    !uuid.test(r.sourceTurnId) ||
    r.sourceThreadId === r.sourceTurnId ||
    !sampleId(r.requestId) ||
    !integer(r.requestSequence) ||
    r.requestSequence < 1 ||
    !integer(r.requestedAt) ||
    !integer(r.recordedAt) ||
    r.recordedAt < r.requestedAt ||
    !r.producer ||
    !uuid.test(r.producer.epoch) ||
    !integer(r.producer.pid) ||
    r.producer.pid <= 1 ||
    !integer(r.producer.desktopPid) ||
    r.producer.desktopPid <= 1 ||
    !integer(r.producer.desktopStartedAt) ||
    r.requestedAt < r.producer.desktopStartedAt ||
    !['pending', 'completed', 'cancelled', 'failed', 'empty', 'unverified', 'suppressed'].includes(
      r.state,
    )
  )
    return false;
  if (r.state === 'completed')
    return (
      r.latestExtracted === undefined &&
      validRuntimeBody(r.result) &&
      r.result.requestId === r.requestId &&
      r.result.sourceTurnId === r.sourceTurnId &&
      r.result.completedAt >= r.requestedAt &&
      r.result.safety.decidedAt <= r.recordedAt
    );
  if (r.result !== null) return false;
  const kept = r.latestExtracted;
  if (kept === undefined) return true;
  if (kept.kind === 'native-sample')
    return (
      integer(kept.requestedAt) &&
      kept.requestedAt <= r.requestedAt &&
      validRuntimeBody(kept.result)
    );
  return (
    kept.kind === 'native-trace' &&
    valid(kept.record, key, threadId) &&
    kept.record.state === 'completed' &&
    kept.record.requestedAt <= r.requestedAt
  );
}
interface Snapshot {
  directory(path: string): void;
  file(path: string, maximum: number): Buffer;
  json(bytes: Buffer | string): Record<string, unknown>;
}
function valid(value: unknown, key: string, threadId: string): value is CompactDesktopSuggestion {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const row = value as CompactDesktopSuggestion;
  if (
    row.version !== 1 ||
    row.targetKey !== key ||
    row.sourceThreadId !== threadId ||
    !uuid.test(row.sourceThreadId) ||
    !uuid.test(row.sourceTurnId) ||
    !uuid.test(row.predictionThreadId) ||
    !uuid.test(row.predictionTurnId) ||
    new Set([row.sourceThreadId, row.sourceTurnId, row.predictionThreadId, row.predictionTurnId])
      .size !== 4 ||
    !Number.isSafeInteger(row.requestedAt) ||
    row.requestedAt < 0 ||
    !Number.isSafeInteger(row.recordedAt) ||
    row.recordedAt < row.requestedAt ||
    !['completed', 'cancelled', 'failed', 'empty', 'unverified'].includes(row.state)
  )
    return false;
  const evidence = row.requestEvidence;
  if (
    !evidence ||
    evidence.kind !== 'native-rollout-trace' ||
    evidence.schemaVersion !== 1 ||
    !Array.isArray(evidence.requests) ||
    evidence.requests.length < 1 ||
    evidence.requests.length > 128 ||
    !evidence.requests.every(
      (r) =>
        uuid.test(r.traceId) &&
        uuid.test(r.inferenceCallId) &&
        Number.isSafeInteger(r.sequence) &&
        r.sequence > 0 &&
        /^[a-f0-9]{64}$/.test(r.requestPayloadSha256),
    )
  )
    return false;
  if (row.state !== 'completed') {
    if (row.prediction !== null) return false;
    const kept = row.latestExtracted;
    if (kept === undefined) return true;
    if (
      !kept ||
      !Number.isSafeInteger(kept.requestedAt) ||
      kept.requestedAt < 0 ||
      kept.requestedAt > row.requestedAt ||
      !kept.prediction?.binding
    )
      return false;
    const binding = kept.prediction.binding;
    return valid(
      {
        ...row,
        sourceTurnId: binding.sourceTurnId,
        predictionThreadId: binding.predictionThreadId,
        predictionTurnId: binding.predictionTurnId,
        requestEvidence: binding.evidence,
        requestedAt: kept.requestedAt,
        prediction: kept.prediction,
        state: 'completed',
        latestExtracted: undefined,
      },
      key,
      threadId,
    );
  }
  if (row.latestExtracted !== undefined) return false;
  const p = row.prediction;
  return Boolean(
    p &&
    p.binding.sourceThreadId === threadId &&
    p.binding.sourceTurnId === row.sourceTurnId &&
    p.binding.predictionThreadId === row.predictionThreadId &&
    p.binding.predictionTurnId === row.predictionTurnId &&
    typeof p.text === 'string' &&
    p.text.trim().length > 0 &&
    p.text.length <= 16000 &&
    /^[a-f0-9]{64}$/.test(p.responsePayloadSha256) &&
    Number.isFinite(Date.parse(p.completedAt)) &&
    Date.parse(p.completedAt) >= row.requestedAt &&
    JSON.stringify(p.binding.evidence) === JSON.stringify(evidence) &&
    p.binding.evidence.kind === 'native-rollout-trace' &&
    p.binding.evidence.schemaVersion === 1 &&
    Array.isArray(p.binding.evidence.requests) &&
    p.binding.evidence.requests.length >= 1 &&
    p.binding.evidence.requests.length <= 128 &&
    p.binding.evidence.requests.every(
      (r) =>
        uuid.test(r.traceId) &&
        uuid.test(r.inferenceCallId) &&
        Number.isSafeInteger(r.sequence) &&
        r.sequence > 0 &&
        /^[a-f0-9]{64}$/.test(r.requestPayloadSha256),
    ),
  );
}

/** One record per machine target and source thread; never a request/history archive. */
export class DesktopSuggestionStore {
  readonly directory: string;
  constructor(
    readonly traceRoot: string,
    readonly targetKey: string,
  ) {
    this.directory = join(traceRoot, '.agent-hub-suggestions', hash(targetKey));
  }
  private path(threadId: string): string {
    if (!uuid.test(threadId)) throw new Error('Invalid compact suggestion thread');
    return join(this.directory, threadId + '.json');
  }
  read(snapshot: Snapshot, threadId: string): CompactDesktopSuggestion | undefined {
    const parent = join(this.traceRoot, '.agent-hub-suggestions');
    if (!existsSync(parent)) return undefined;
    snapshot.directory(parent);
    if (!existsSync(this.directory)) return undefined;
    snapshot.directory(this.directory);
    const path = this.path(threadId);
    if (!existsSync(path)) return undefined;
    const envelope = snapshot.json(snapshot.file(path, compactSuggestionMaximum));
    if (typeof envelope.body !== 'string' || envelope.sha256 !== hash(envelope.body))
      throw new Error('Invalid compact suggestion integrity');
    const value = snapshot.json(envelope.body);
    // A live original-connection result owns the single body slot. A trace
    // reader must not reinterpret it or revive an older trace body.
    if (validRuntime(value, this.targetKey, threadId)) return undefined;
    if (!valid(value, this.targetKey, threadId))
      throw new Error('Invalid compact suggestion source');
    return value;
  }
  private runtimeSnapshot(): Snapshot {
    const directory = (path: string) => {
      if (process.platform === 'win32') {
        windowsTraceSnapshot(this.traceRoot, path, 0);
        return;
      }
      const s = lstatSync(path);
      if (
        !s.isDirectory() ||
        s.isSymbolicLink() ||
        s.uid !== process.getuid?.() ||
        s.mode & 0o077 ||
        s.dev !== lstatSync(this.traceRoot).dev ||
        realpathSync(path) !== path
      )
        throw new Error('Unsafe native suggestion directory');
    };
    return {
      directory,
      json: (bytes) => JSON.parse(typeof bytes === 'string' ? bytes : bytes.toString('utf8')),
      file: (path, maximum) => {
        directory(this.traceRoot);
        if (process.platform === 'win32')
          return windowsTraceSnapshot(this.traceRoot, path, maximum).bytes;
        const before = lstatSync(path, { bigint: true });
        if (
          !before.isFile() ||
          before.isSymbolicLink() ||
          before.nlink !== 1n ||
          before.uid !== BigInt(process.getuid!()) ||
          before.mode & 0o077n ||
          before.size > BigInt(maximum)
        )
          throw new Error('Unsafe native suggestion file');
        const stamp = (s: typeof before) =>
          [s.dev, s.ino, s.size, s.mode, s.nlink, s.uid, s.gid, s.mtimeNs, s.ctimeNs].join(':');
        const fd = openSync(
          path,
          constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0),
        );
        try {
          if (stamp(fstatSync(fd, { bigint: true })) !== stamp(before))
            throw new Error('Native suggestion changed');
          const bytes = readFileSync(fd);
          if (
            bytes.length > maximum ||
            stamp(fstatSync(fd, { bigint: true })) !== stamp(before) ||
            stamp(lstatSync(path, { bigint: true })) !== stamp(before)
          )
            throw new Error('Native suggestion changed');
          directory(this.traceRoot);
          return bytes;
        } finally {
          closeSync(fd);
        }
      },
    };
  }
  readRuntime(
    threadId: string,
    snapshot = this.runtimeSnapshot(),
  ): RuntimeDesktopSuggestion | undefined {
    const parent = join(this.traceRoot, '.agent-hub-suggestions');
    if (!existsSync(parent)) return undefined;
    snapshot.directory(this.traceRoot);
    snapshot.directory(parent);
    if (!existsSync(this.directory)) return undefined;
    snapshot.directory(this.directory);
    const path = this.path(threadId);
    if (!existsSync(path)) return undefined;
    const envelope = snapshot.json(snapshot.file(path, compactSuggestionMaximum));
    if (typeof envelope.body !== 'string' || envelope.sha256 !== hash(envelope.body))
      throw new Error('Invalid runtime suggestion integrity');
    const value = snapshot.json(envelope.body);
    if (valid(value, this.targetKey, threadId)) return undefined;
    if (!validRuntime(value, this.targetKey, threadId))
      throw new Error('Invalid runtime suggestion source');
    return value;
  }
  rootIdentity(): string {
    if (process.platform === 'win32')
      return windowsTraceSnapshot(this.traceRoot, this.traceRoot, 0).identity;
    this.runtimeSnapshot().directory(this.traceRoot);
    const s = lstatSync(this.traceRoot, { bigint: true });
    return [s.dev, s.ino, s.uid, s.mode].join(':');
  }
  readProducer(): RuntimeSuggestionProducerState | undefined {
    if (!existsSync(this.directory)) return undefined;
    const snapshot = this.runtimeSnapshot();
    for (const path of [
      this.traceRoot,
      join(this.traceRoot, '.agent-hub-suggestions'),
      this.directory,
    ])
      snapshot.directory(path);
    const path = join(this.directory, 'producer.json');
    if (!existsSync(path)) return undefined;
    const envelope = snapshot.json(snapshot.file(path, compactSuggestionMaximum));
    if (typeof envelope.body !== 'string' || envelope.sha256 !== hash(envelope.body))
      throw new Error('Invalid native producer integrity');
    const value = snapshot.json(envelope.body);
    if (!validProducer(value, this.targetKey)) throw new Error('Invalid native producer');
    return value;
  }
  saveProducer(state: RuntimeSuggestionProducerState, beforePublish?: () => void): void {
    if (!validProducer(state, this.targetKey) || state.rootIdentity !== this.rootIdentity())
      throw new Error('Invalid native producer');
    this.prepare();
    this.publishBytes(join(this.directory, 'producer.json'), state, beforePublish);
  }
  saveRuntime(record: RuntimeDesktopSuggestion, beforePublish?: () => void): boolean {
    return this.withWriteLock(record.sourceThreadId, 1000, () =>
      this.saveRuntimeLocked(record, beforePublish),
    );
  }
  private saveRuntimeLocked(record: RuntimeDesktopSuggestion, beforePublish?: () => void): boolean {
    if (
      !validRuntime(record, this.targetKey, record.sourceThreadId) ||
      realpathSync(this.traceRoot) !== this.traceRoot
    )
      throw new Error('Invalid runtime suggestion');
    this.prepare();
    const path = this.path(record.sourceThreadId);
    let next = record;
    if (existsSync(path)) {
      const bytes = this.runtimeSnapshot().file(path, compactSuggestionMaximum).toString('utf8');
      const envelope = JSON.parse(bytes);
      if (typeof envelope.body !== 'string' || envelope.sha256 !== hash(envelope.body))
        throw new Error('Invalid prior runtime suggestion integrity');
      const old: unknown = JSON.parse(envelope.body);
      let prior: RuntimeDesktopSuggestion['latestExtracted'];
      if (validRuntime(old, this.targetKey, record.sourceThreadId)) {
        const sameProducer = old.producer.epoch === record.producer.epoch;
        if (sameProducer && JSON.stringify(old.producer) !== JSON.stringify(record.producer))
          return false;
        if (sameProducer && old.requestSequence > record.requestSequence) {
          this.confirmDurable(path, bytes);
          return true;
        }
        if (
          sameProducer &&
          old.requestSequence === record.requestSequence &&
          (old.requestId !== record.requestId ||
            old.sourceTurnId !== record.sourceTurnId ||
            old.requestedAt !== record.requestedAt)
        )
          return false;
        if (!sameProducer) {
          if (old.producer.desktopStartedAt > record.producer.desktopStartedAt) return false;
          if (old.producer.desktopStartedAt === record.producer.desktopStartedAt) {
            try {
              process.kill(old.producer.pid, 0);
              return false;
            } catch (error) {
              if ((error as NodeJS.ErrnoException).code !== 'ESRCH') return false;
            }
          }
        }
        prior = old.result
          ? { kind: 'native-sample', requestedAt: old.requestedAt, result: old.result }
          : old.latestExtracted;
      } else if (valid(old, this.targetKey, record.sourceThreadId)) {
        const kept = old.prediction
          ? { requestedAt: old.requestedAt, prediction: old.prediction }
          : old.latestExtracted;
        if (kept) {
          const b = kept.prediction.binding;
          const traceRecord: CompactDesktopSuggestion = {
            ...old,
            sourceTurnId: b.sourceTurnId,
            predictionThreadId: b.predictionThreadId,
            predictionTurnId: b.predictionTurnId,
            requestEvidence: b.evidence,
            requestedAt: kept.requestedAt,
            state: 'completed',
            prediction: kept.prediction,
          };
          delete traceRecord.latestExtracted;
          prior = { kind: 'native-trace', record: traceRecord };
        }
      } else throw new Error('Invalid prior native suggestion');
      if (record.state !== 'completed' && prior) next = { ...record, latestExtracted: prior };
    }
    return this.publish(path, next, beforePublish);
  }
  /** A bound raw attempt may be covered by its result or a strictly later extracted body. */
  acknowledgeRuntimeResult(
    threadId: string,
    sourceTurnId: string,
    startedAt: number,
    endedAt: number,
  ): boolean {
    if (
      !uuid.test(sourceTurnId) ||
      sourceTurnId === threadId ||
      !integer(startedAt) ||
      !integer(endedAt) ||
      startedAt > endedAt
    )
      return false;
    return this.withWriteLock(threadId, 0, () => {
      const value = this.readRuntime(threadId);
      if (!value) return false;
      const kept = value.result
        ? { requestedAt: value.requestedAt, result: value.result }
        : value.latestExtracted?.kind === 'native-sample'
          ? value.latestExtracted
          : undefined;
      if (
        !kept ||
        kept.result.completedAt < kept.requestedAt ||
        kept.result.safety.decidedAt > value.recordedAt
      )
        return false;
      const matching =
        kept.result.sourceTurnId === sourceTurnId &&
        kept.requestedAt <= endedAt &&
        kept.result.completedAt >= startedAt;
      // Latest-only retention is per target/source thread, across source turns.
      // Only an extracted body can supersede; a later pending request cannot.
      const superseded = kept.requestedAt > endedAt;
      if (!matching && !superseded) return false;
      const path = this.path(threadId),
        bytes = this.runtimeSnapshot().file(path, compactSuggestionMaximum).toString('utf8');
      this.confirmDurable(path, bytes);
      return true;
    });
  }
  prepare(): void {
    for (const path of [
      this.traceRoot,
      join(this.traceRoot, '.agent-hub-suggestions'),
      this.directory,
    ]) {
      const created = !existsSync(path);
      if (created && path !== this.traceRoot) {
        makePrivateDirectorySync(path);
        protectWindowsStateDirectory(path);
      }
      const stat = lstatSync(path);
      if (
        !stat.isDirectory() ||
        stat.isSymbolicLink() ||
        stat.dev !== lstatSync(this.traceRoot).dev ||
        (process.platform !== 'win32' &&
          ((stat.mode & 0o077) !== 0 || stat.uid !== process.getuid?.()))
      )
        throw new Error('Unsafe compact suggestion directory');
      if (process.platform === 'win32') windowsTraceSnapshot(this.traceRoot, path, 0);
    }
  }
  private confirmDurable(path: string, bytes: string): void {
    assertPrivateFile(path);
    const file = openSync(path, 'r+');
    try {
      fsyncSync(file);
    } finally {
      closeSync(file);
    }
    if (process.platform !== 'win32') {
      const directory = openSync(this.directory, 'r');
      try {
        fsyncSync(directory);
      } finally {
        closeSync(directory);
      }
    }
    assertPrivateFile(path);
    if (readFileSync(path, 'utf8') !== bytes) throw new Error('Compact suggestion readback failed');
  }
  /** Durable readback is a prerequisite for retirement, not evidence of freshness. */
  save(record: CompactDesktopSuggestion): boolean {
    // Retention is best effort. A live original result writer wins a contested
    // slot; the raw trace remains intact for a later scan.
    return this.withWriteLock(record.sourceThreadId, 0, () => this.saveTraceLocked(record));
  }
  private saveTraceLocked(record: CompactDesktopSuggestion): boolean {
    if (
      !valid(record, this.targetKey, record.sourceThreadId) ||
      realpathSync(this.traceRoot) !== this.traceRoot
    )
      throw new Error('Invalid compact suggestion');
    this.prepare();
    const path = this.path(record.sourceThreadId);
    let next = record;
    if (existsSync(path)) {
      assertPrivateFile(path);
      const before = lstatSync(path);
      if (before.size > compactSuggestionMaximum) throw new Error('Oversized compact suggestion');
      const previousBytes = readFileSync(path, 'utf8');
      const envelope = JSON.parse(previousBytes);
      if (typeof envelope.body !== 'string' || envelope.sha256 !== hash(envelope.body))
        throw new Error('Invalid prior compact suggestion');
      const old: unknown = JSON.parse(envelope.body);
      if (validRuntime(old, this.targetKey, record.sourceThreadId)) {
        // Trace failure metadata never overwrites an original connection's
        // result. A provably later unique body stays raw for reconciliation.
        if (record.prediction && (old.state !== 'completed' || record.requestedAt > old.recordedAt))
          return false;
        this.confirmDurable(path, previousBytes);
        return true;
      }
      if (!valid(old, this.targetKey, record.sourceThreadId))
        throw new Error('Invalid prior source');
      const priorBody = old.prediction
        ? { requestedAt: old.requestedAt, prediction: old.prediction }
        : old.latestExtracted;
      // A late completion can fill the single extracted-body slot, while the
      // newer attempt still controls validity and can never expose that body.
      if (old.requestedAt > record.requestedAt) {
        if (record.prediction && old.state !== 'completed') {
          if (
            priorBody?.requestedAt === record.requestedAt &&
            JSON.stringify(priorBody.prediction) !== JSON.stringify(record.prediction)
          )
            return false;
          if (!priorBody || record.requestedAt > priorBody.requestedAt)
            return this.publish(path, {
              ...old,
              recordedAt: Math.max(old.recordedAt, record.recordedAt),
              latestExtracted: { requestedAt: record.requestedAt, prediction: record.prediction },
            });
        }
        this.confirmDurable(path, previousBytes);
        return true;
      }
      if (old.requestedAt === record.requestedAt) {
        if (
          old.predictionThreadId !== record.predictionThreadId ||
          old.predictionTurnId !== record.predictionTurnId ||
          old.sourceTurnId !== record.sourceTurnId ||
          old.state !== record.state ||
          JSON.stringify(old.requestEvidence) !== JSON.stringify(record.requestEvidence) ||
          JSON.stringify(old.prediction) !== JSON.stringify(record.prediction)
        )
          return false;
        // A prior publish can have survived a failed flush. Equal bytes alone
        // are never a durability acknowledgement for a later retirement.
        this.confirmDurable(path, previousBytes);
        return true;
      }
      if (record.state !== 'completed' && priorBody)
        next = { ...record, latestExtracted: priorBody };
    }
    return this.publish(path, next);
  }
  private withWriteLock(id: string, waitMs: number, operation: () => boolean): boolean {
    this.prepare();
    const lock = this.path(id) + '.write-lock';
    const token = JSON.stringify({ version: 1, pid: process.pid, threadId, token: randomUUID() });
    const until = performance.now() + waitMs;
    let fd: number;
    for (;;) {
      try {
        fd = openPrivateFileSync(lock, 'wx', 0o600);
        break;
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
        try {
          const before = lstatSync(lock, { bigint: true });
          if (
            before.isFile() &&
            !before.isSymbolicLink() &&
            before.nlink === 1n &&
            before.size <= 4096n
          ) {
            const value = JSON.parse(readFileSync(lock, 'utf8'));
            if (
              value.version === 1 &&
              Number.isSafeInteger(value.pid) &&
              value.pid > 1 &&
              typeof value.token === 'string'
            ) {
              let dead = false;
              try {
                process.kill(value.pid, 0);
              } catch (e) {
                dead = (e as NodeJS.ErrnoException).code === 'ESRCH';
              }
              const after = lstatSync(lock, { bigint: true });
              if (
                dead &&
                before.dev === after.dev &&
                before.ino === after.ino &&
                before.ctimeNs === after.ctimeNs
              ) {
                unlinkSync(lock);
                continue;
              }
            }
          }
        } catch {
          /* An unprovable lock remains a live boundary. */
        }
        if (performance.now() >= until) return false;
        Atomics.wait(
          new Int32Array(new SharedArrayBuffer(4)),
          0,
          0,
          Math.min(10, until - performance.now()),
        );
      }
    }
    const identity = fstatSync(fd, { bigint: true });
    let written = false;
    try {
      writeFileSync(fd, token);
      protectWindowsStateFile(lock);
      written = true;
      return operation();
    } finally {
      closeSync(fd);
      if (written && existsSync(lock)) {
        const current = lstatSync(lock, { bigint: true });
        if (
          current.isFile() &&
          !current.isSymbolicLink() &&
          current.dev === identity.dev &&
          current.ino === identity.ino &&
          readFileSync(lock, 'utf8') === token
        )
          unlinkSync(lock);
      }
    }
  }
  private publish(
    path: string,
    record: CompactDesktopSuggestion | RuntimeDesktopSuggestion,
    beforePublish?: () => void,
  ): boolean {
    if (
      !valid(record, this.targetKey, record.sourceThreadId) &&
      !validRuntime(record, this.targetKey, record.sourceThreadId)
    )
      throw new Error('Invalid merged compact suggestion');
    return this.publishBytes(path, record, beforePublish);
  }
  private publishBytes(path: string, record: unknown, beforePublish?: () => void): boolean {
    const body = JSON.stringify(record),
      bytes = JSON.stringify({ body, sha256: hash(body) }) + '\n';
    if (Buffer.byteLength(bytes) > compactSuggestionMaximum)
      throw new Error('Oversized compact suggestion');
    const temporary = path + '.' + randomUUID() + '.tmp';
    let renamed = false;
    try {
      const fd = openPrivateFileSync(temporary, 'wx', 0o600);
      try {
        writeFileSync(fd, bytes);
        protectWindowsStateFile(temporary);
        fsyncSync(fd);
      } finally {
        closeSync(fd);
      }
      // Windows readers retain handles without delete sharing. A short overlap
      // must preserve the old record and retry the same atomic publication.
      const until = performance.now() + 250;
      for (;;) {
        try {
          // Native permission checks also precede the original phase/lease fence.
          renamePrivateFileSync(temporary, path, beforePublish);
          break;
        } catch (error) {
          if (
            !['EPERM', 'EACCES', 'EBUSY'].includes((error as NodeJS.ErrnoException).code ?? '') ||
            performance.now() >= until
          )
            throw error;
          Atomics.wait(
            new Int32Array(new SharedArrayBuffer(4)),
            0,
            0,
            Math.min(5, until - performance.now()),
          );
        }
      }
      renamed = true;
      this.confirmDurable(path, bytes);
      return true;
    } finally {
      if (!renamed && existsSync(temporary)) unlinkSync(temporary);
    }
  }
}
