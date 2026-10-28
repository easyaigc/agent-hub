import { createHash } from 'node:crypto';
import { channel } from 'node:diagnostics_channel';
import {
  closeSync,
  constants,
  fstatSync,
  lstatSync,
  openSync,
  opendirSync,
  readSync,
  realpathSync,
  type BigIntStats,
} from 'node:fs';
import { dirname, isAbsolute, join, parse, resolve } from 'node:path';
import {
  windowsTraceSnapshot,
  windowsTraceSnapshotMany,
  windowsTraceSnapshotBatchAvailable,
} from './desktop-platform.js';
import {
  createTraceSnapshotBatchPlanner,
  traceSnapshotBatchLimits,
  type TraceSnapshotValue,
} from './desktop-trace-native.js';
import { createWindowsTraceDiagnostics } from './windows-trace-diagnostics.js';

const traceSummaries = channel('agent-hub.windows-trace.summary');

const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const bundleName = /^trace-([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})-(.+)$/i;
const limits = {
  directoryEntries: 20_000,
  bundles: 16,
  manifest: 16_384,
  eventLog: 8 * 1024 * 1024,
  eventLine: 1024 * 1024,
  request: 32 * 1024 * 1024,
  response: 8 * 1024 * 1024,
  totalBytes: 64 * 1024 * 1024,
  requests: 128,
  initialDiscoveryPrefix: 1024,
  discoveryPrefix: 16_384,
  protocolEvent: 128 * 1024,
};

export interface DesktopPredictionTraceBinding {
  predictionThreadId: string;
  predictionTurnId: string;
  sourceThreadId: string;
  sourceTurnId: string;
  evidence: {
    kind: 'native-rollout-trace';
    schemaVersion: 1;
    requests: {
      traceId: string;
      sequence: number;
      inferenceCallId: string;
      requestPayloadSha256: string;
    }[];
  };
}

export interface DesktopPredictionTracePrediction {
  binding: DesktopPredictionTraceBinding;
  text: string;
  completedAt: string;
  responsePayloadSha256: string;
}

/** Worker-local tentative projection. No data is exposed before commit succeeds. */
export interface PreparedDesktopPredictionTraceRead {
  commit(): DesktopPredictionTracePrediction[] | null;
  dispose(): void;
}

type JsonObject = Record<string, unknown>;
type PathScope = 'ancestor' | 'root' | 'contained';
type Guard = {
  path: string;
  stat: BigIntStats;
  directory: boolean;
  scope: PathScope;
  prefixLength?: number;
  prefixSha256?: string;
  nativeIdentity?: string;
  body?: { length: number; maximum: number; sha256: string };
  prefixMayGrow?: boolean;
  verifiedStat?: BigIntStats;
};
type TraceEvent = { directory: string; event: JsonObject; payload: JsonObject };
type TraceRequest = TraceEvent & { inferenceCallId: string };

/** Decoding only: every use still acquires a full file receipt in TraceSnapshot. */
class PreparedTraceLog {
  private readonly lines: string[];
  private readonly decoded = new Map<number, TraceEvent>();
  private readonly starts = new Map<unknown, TraceEvent[]>();
  private readonly endings = new Map<unknown, TraceEvent[]>();
  private readonly completions: TraceEvent[] = [];
  private readonly terminals: TraceEvent[] = [];
  private validated = false;

  constructor(
    private readonly bytes: Buffer,
    private readonly directory: string,
    private readonly threadId: string,
    private readonly decode: <T>(body: () => T) => T,
  ) {
    this.lines = this.decode(() => bytes.toString('utf8').split('\n'));
  }

  eventAt(index: number, binding = false): TraceEvent {
    return this.decode(() => this.event(index, binding));
  }

  private event(index: number, binding: boolean): TraceEvent {
    const line = this.lines[index] ?? '',
      bom = line.startsWith('\uFEFF');
    // Binding historically decodes each line with TextDecoder, removing one
    // leading BOM. Discovery parses the original string and must still reject
    // that BOM, even after binding has cached this record.
    requireValue(binding || !bom);
    let value = this.decoded.get(index);
    if (!value) {
      const event = parseJson(binding && bom ? line.slice(1) : line);
      value = { directory: this.directory, event, payload: object(event.payload) };
      this.decoded.set(index, value);
    }
    return value;
  }

  forTurn(turnId: string) {
    return this.decode(() => this.turn(turnId));
  }

  private turn(turnId: string) {
    if (!this.validated) {
      // Source discovery historically decodes UTF-8 permissively. The first
      // binding still requires strict UTF-8, every complete line and sequence,
      // including records outside the requested turn. Do not validate earlier
      // or silently omit a malformed/unfinished line when indexing.
      new TextDecoder('utf-8', { fatal: true }).decode(
        this.bytes.subarray(0, this.bytes.lastIndexOf(10) + 1),
      );
      for (let index = 0; index < this.lines.length - 1; index++) {
        requireValue(Buffer.byteLength(this.lines[index]!) <= limits.eventLine);
        const value = this.eventAt(index, true),
          { event, payload } = value;
        requireValue(
          event.schema_version === 1 &&
            event.rollout_id === this.threadId &&
            event.seq === index + 1 &&
            Number.isSafeInteger(event.wall_time_unix_ms),
        );
        const add = (map: Map<unknown, TraceEvent[]>) => {
          // The original selector matches either ID, then rejects disagreement.
          for (const key of new Set([event.codex_turn_id, payload.codex_turn_id])) {
            const values = map.get(key) ?? [];
            values.push(value);
            map.set(key, values);
          }
        };
        if (payload.type === 'inference_started') add(this.starts);
        if (payload.type === 'codex_turn_ended') add(this.endings);
        if (payload.type === 'inference_completed') this.completions.push(value);
        if (payload.type === 'protocol_event_observed' && payload.event_type === 'turn_complete')
          this.terminals.push(value);
      }
      this.validated = true;
    }
    return {
      starts: this.starts.get(turnId) ?? [],
      endings: this.endings.get(turnId) ?? [],
      completions: this.completions,
      terminals: this.terminals,
    };
  }
}

function requireValue(condition: unknown): asserts condition {
  if (!condition) throw new Error('Unavailable prediction trace');
}

function object(value: unknown): JsonObject {
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value));
  return value as JsonObject;
}

function parseJson(bytes: Uint8Array | string): JsonObject {
  const text =
    typeof bytes === 'string' ? bytes : new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  return object(JSON.parse(text));
}

function validUuid(value: unknown): value is string {
  return typeof value === 'string' && uuid.test(value);
}

function sameNode(a: BigIntStats, b: BigIntStats, directory = false): boolean {
  return (
    a.dev === b.dev &&
    a.ino === b.ino &&
    a.mode === b.mode &&
    a.uid === b.uid &&
    (directory || a.nlink === b.nlink)
  );
}

function sameFile(a: BigIntStats, b: BigIntStats): boolean {
  return sameNode(a, b) && a.size === b.size && a.mtimeNs === b.mtimeNs && a.ctimeNs === b.ctimeNs;
}

/** One bounded, read-only snapshot; no cached binding survives a later conflict. */
class TraceSnapshot {
  private guards: Guard[] = [];
  private directories = new Map<string, Guard>();
  private bytes = 0;
  private readonly diagnostic?: ReturnType<typeof createWindowsTraceDiagnostics>;
  private parsing = false;
  private readSnapshotCalls = 0;
  private catalogWalks = 0;
  private readonly uid = BigInt(process.getuid?.() ?? 0);
  private traceRoot = '';
  private nativeNodes = new Map<string, string>();
  private nativeFiles = new Map<string, { stat: BigIntStats; bytes: Buffer; identity: string }>();
  private preparedLogs = new Map<
    string,
    {
      stat: BigIntStats;
      identity: string;
      sha256: string;
      length: number;
      directory: string;
      threadId: string;
      index: PreparedTraceLog;
    }
  >();
  private catalogs = new Map<
    string,
    { root: string; threadId: string | undefined; names: string[] }
  >();
  constructor(private readonly prepared = false) {
    if (
      prepared &&
      process.platform === 'win32' &&
      (traceSummaries.hasSubscribers || process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS === '1')
    )
      this.diagnostic = createWindowsTraceDiagnostics('reader');
  }

  /** Only pure decoding/index work belongs here, never file/native operations. */
  decode<T>(body: () => T): T {
    if (!this.diagnostic || this.parsing) return body();
    this.parsing = true;
    try {
      return this.diagnostic.phase('parse', body);
    } finally {
      this.parsing = false;
    }
  }

  json(bytes: Uint8Array | string): JsonObject {
    return this.decode(() => parseJson(bytes));
  }

  capture<T>(body: () => T): T {
    return this.diagnostic ? this.diagnostic.phase('prepare', body) : body();
  }

  recordReadSnapshot(): void {
    if (this.diagnostic) this.readSnapshotCalls++;
  }

  report(
    reason: 'ok' | 'native_rejected' | 'aborted',
    stage: 'prepared' | 'committed' | 'interrupted',
  ): void {
    try {
      this.diagnostic?.end(reason, {
        operation: 'parser',
        stage,
        parserReadSnapshotCalls: this.readSnapshotCalls,
        parserCatalogWalks: this.catalogWalks,
        parserGuardCount: this.guards.length,
      });
    } catch {
      // Diagnostic delivery cannot change a prediction, rejection or cleanup.
    }
  }

  get isPreparedDiscovery(): boolean {
    return this.prepared;
  }

  releaseBodies(): void {
    this.preparedLogs.clear();
    this.nativeFiles.clear();
    this.bytes = 0;
  }

  dispose(): void {
    this.releaseBodies();
    this.guards = [];
    this.directories.clear();
    this.nativeNodes.clear();
    this.catalogs.clear();
  }

  closeDiscoveryPrefix(path: string): void {
    if (!this.prepared) return;
    for (const guard of this.guards)
      if (guard.path === path && guard.prefixLength !== undefined) guard.prefixMayGrow = true;
  }

  private verifyInputs(afterGuards = false): void {
    if (afterGuards) {
      // Fence inputs confirmed early in the loop against writes during a later
      // body read. These checks add no authority and replace no native guard.
      for (const guard of this.guards) {
        if (guard.directory) continue;
        const current = lstatSync(guard.path, { bigint: true });
        this.validate(current, false, guard.scope);
        if (guard.body) requireValue(sameFile(guard.stat, current));
        else if (guard.prefixLength !== undefined) {
          requireValue(sameNode(guard.stat, current));
          if (!guard.prefixMayGrow) requireValue(current.size === guard.stat.size);
          if (!sameFile(guard.verifiedStat ?? guard.stat, current)) {
            this.guardStat(guard);
            this.prefixAfterBatch(guard, current);
            requireValue(sameNode(current, this.guardStat(guard)));
          }
        }
      }
    }
    for (const receipt of this.catalogs.values())
      requireValue(
        JSON.stringify(this.catalog(receipt.root, receipt.threadId)) ===
          JSON.stringify(receipt.names),
      );
  }

  commit(): void {
    requireValue(this.prepared && process.platform === 'win32');
    const verify = () => {
      if (!this.diagnostic) {
        this.verifyInputs();
        this.verify();
        this.verifyInputs(true);
        return;
      }
      this.diagnostic.phase('verify_inputs', () => this.verifyInputs());
      this.diagnostic.phase('verify', () => this.verify());
      this.diagnostic.phase('verify_inputs_final', () => this.verifyInputs(true));
    };
    if (this.diagnostic) this.diagnostic.phase('commit', verify);
    else verify();
  }

  withByteBudget<T>(read: () => T): T {
    const previousBytes = this.bytes;
    const previousFiles = this.nativeFiles;
    // Discovery bytes stay charged against the same 64 MiB live-cache bound;
    // release this binding's additional bodies/charges when it returns.
    // Only byte accounting/cache are scoped: every file, prefix and native chain
    // guard remains in the source snapshot and participates in its final verify.
    this.nativeFiles = new Map(previousFiles);
    try {
      return read();
    } finally {
      let retainedBytes = previousBytes;
      // An index owns its log bytes/decoded events. Keep those bodies charged
      // and live in this request instead of hiding them behind a reset budget.
      // Requests/responses remain scoped as before; no receipt is removed.
      for (const path of this.preparedLogs.keys()) {
        const file = this.nativeFiles.get(path)!;
        const previous = previousFiles.get(path);
        retainedBytes += Math.max(
          0,
          file.bytes.length -
            (previous && sameFile(previous.stat, file.stat) ? previous.bytes.length : 0),
        );
        previousFiles.set(path, file);
      }
      this.bytes = retainedBytes;
      this.nativeFiles = previousFiles;
      requireValue(this.bytes <= limits.totalBytes);
    }
  }

  preparedLog(path: string, bytes: Buffer, directory: string, threadId: string) {
    if (!this.prepared || process.platform !== 'win32') return undefined;
    // file() must just have recorded a fresh full-body receipt. Path or parsed
    // JSON equality alone never authorizes reuse, nor does a prior API read.
    const guard = this.guards.at(-1),
      file = this.nativeFiles.get(path);
    requireValue(
      guard?.path === path &&
        guard.body &&
        guard.body.length === bytes.length &&
        guard.body.maximum === limits.eventLog &&
        guard.nativeIdentity &&
        file &&
        file.identity === guard.nativeIdentity &&
        sameFile(file.stat, guard.stat),
    );
    const previous = this.preparedLogs.get(path);
    if (previous) {
      requireValue(
        sameFile(previous.stat, guard.stat) &&
          previous.identity === guard.nativeIdentity &&
          previous.sha256 === guard.body.sha256 &&
          previous.length === bytes.length &&
          previous.directory === directory &&
          previous.threadId === threadId,
      );
      return previous.index;
    }
    const index = new PreparedTraceLog(bytes, directory, threadId, (body) => this.decode(body));
    this.preparedLogs.set(path, {
      stat: guard.stat,
      identity: guard.nativeIdentity,
      sha256: guard.body.sha256,
      length: bytes.length,
      directory,
      threadId,
      index,
    });
    return index;
  }

  private native(path: string, maximum = 0): ReturnType<typeof windowsTraceSnapshot> {
    const value = windowsTraceSnapshot(this.traceRoot, path, maximum);
    this.nativeResult(path, value);
    return value;
  }

  private nativeResult(path: string, value: TraceSnapshotValue): void {
    const paths: string[] = [];
    for (let at = path; ; at = dirname(at)) {
      paths.unshift(at.toLowerCase());
      if (at === parse(at).root) break;
    }
    requireValue(paths.length === value.chain.length);
    for (let i = 0; i < paths.length; i++) {
      const prior = this.nativeNodes.get(paths[i]!);
      requireValue(prior === undefined || prior === value.chain[i]);
      this.nativeNodes.set(paths[i]!, value.chain[i]!);
    }
    // Node's surrounding lstat guards must describe the same native file ID.
    const leaf = value.chain.at(-1)!.split(':');
    const info = lstatSync(path, { bigint: true });
    requireValue(info.ino === ((BigInt(leaf[1]!) << 32n) | BigInt(leaf[2]!)));
  }

  private validate(stat: BigIntStats, directory: boolean, scope: PathScope): void {
    requireValue(!stat.isSymbolicLink() && (directory ? stat.isDirectory() : stat.isFile()));
    // Windows ownership/DACL, reparse points and links are checked on retained
    // native handles by windowsTraceSnapshot, never by synthetic mode/uid bits.
    if (process.platform === 'win32') return;
    if (scope !== 'ancestor') {
      requireValue(stat.uid === this.uid);
      requireValue((stat.mode & 0o7000n) === 0n);
      // The continuously rechecked 0700 root supplies confidentiality. Native
      // files may use the desktop's default umask, but may not be writable by others.
      if (scope === 'root') requireValue(directory && (stat.mode & 0o777n) === 0o700n);
      else requireValue((stat.mode & 0o022n) === 0n);
      if (!directory) requireValue(stat.nlink === 1n);
    } else {
      // System ancestors may be root-owned. A sticky temporary directory is allowed.
      requireValue(stat.uid === this.uid || stat.uid === 0n);
      requireValue((stat.mode & 0o022n) === 0n || (stat.mode & 0o1000n) !== 0n);
    }
  }

  directory(path: string, scope: PathScope = 'contained'): void {
    const before = lstatSync(path, { bigint: true });
    this.validate(before, true, scope);
    if (process.platform === 'win32') {
      const previous = this.directories.get(path);
      if (previous) {
        requireValue(sameNode(previous.stat, before, true) && previous.scope === scope);
        return;
      }
      const native = this.native(path);
      requireValue(sameNode(before, lstatSync(path, { bigint: true }), true));
      const guard = { path, stat: before, directory: true, scope, nativeIdentity: native.identity };
      this.guards.push(guard);
      this.directories.set(path, guard);
      return;
    }
    const fd = openSync(path, constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW);
    try {
      requireValue(sameNode(before, fstatSync(fd, { bigint: true }), true));
      requireValue(realpathSync(path) === path);
      const guard = { path, stat: before, directory: true, scope };
      this.guards.push(guard);
      this.directories.set(path, guard);
    } finally {
      closeSync(fd);
    }
  }

  /** Only already-discovered contained directories; root/ancestor paths stay scalar. */
  directoryGroup(paths: readonly string[]): void {
    if (!this.prepared || process.platform !== 'win32' || !windowsTraceSnapshotBatchAvailable()) {
      for (const path of paths) this.directory(path);
      return;
    }
    for (let index = 0; index < paths.length;) {
      if (this.directories.has(paths[index]!)) {
        // Preserve the original map-hit fresh lstat/scope check and unique guard.
        this.directory(paths[index++]!);
        continue;
      }
      const selected: string[] = [];
      const planner = createTraceSnapshotBatchPlanner();
      for (
        let at = index;
        at < paths.length && selected.length < traceSnapshotBatchLimits.items;
        at++
      ) {
        const path = paths[at]!;
        if (
          this.directories.has(path) ||
          selected.includes(path) ||
          !planner.add({ path, maximum: 0 })
        )
          break;
        selected.push(path);
      }
      if (selected.length < 2) {
        this.directory(paths[index++]!);
        continue;
      }
      const plans = selected.map((path) => {
        const before = lstatSync(path, { bigint: true });
        this.validate(before, true, 'contained');
        return { path, before };
      });
      const values = windowsTraceSnapshotMany(
        this.traceRoot,
        plans.map(({ path }) => ({ path, maximum: 0 })),
      );
      requireValue(values.length === plans.length);
      for (let at = 0; at < plans.length; at++) {
        const plan = plans[at]!;
        this.nativeResult(plan.path, values[at]!);
        requireValue(sameNode(plan.before, lstatSync(plan.path, { bigint: true }), true));
      }
      // No early directory receipt is usable before this entire B is fenced.
      for (const plan of plans)
        requireValue(sameNode(plan.before, lstatSync(plan.path, { bigint: true }), true));
      for (let at = 0; at < plans.length; at++) {
        const plan = plans[at]!;
        const guard: Guard = {
          path: plan.path,
          stat: plan.before,
          directory: true,
          scope: 'contained',
          nativeIdentity: values[at]!.identity,
        };
        this.guards.push(guard);
        this.directories.set(plan.path, guard);
      }
      index += plans.length;
    }
  }

  /** A fresh full-file Snapshot already retains and checks its complete directory chain. */
  discoveryFiles(items: readonly { path: string; maximum: number }[]): Buffer[] {
    const paths = items.map((item) => dirname(item.path));
    // Select before I/O, never retry a rejected read or turn a cached body into
    // a new parent proof. Existing request-local receipts keep their old path.
    if (
      !this.prepared ||
      process.platform !== 'win32' ||
      new Set(paths).size !== paths.length ||
      paths.some(
        (path, index) => this.directories.has(path) || this.nativeFiles.has(items[index]!.path),
      )
    ) {
      this.directoryGroup(paths);
      return this.files(items);
    }
    const before = paths.map((path) => {
      const stat = lstatSync(path, { bigint: true });
      this.validate(stat, true, 'contained');
      return stat;
    });
    const bodies = this.files(items);
    const guards = paths.map((path, index): Guard => {
      // No file was cached on entry. files() has just accepted a fresh native
      // full-chain receipt, with every sibling fenced, for this exact discovery file.
      requireValue(this.nativeFiles.has(items[index]!.path));
      const chain: string[] = [];
      for (let at = path; ; at = dirname(at)) {
        const node = this.nativeNodes.get(at.toLowerCase());
        requireValue(node !== undefined);
        chain.unshift(node!);
        if (at === parse(at).root) break;
      }
      const leaf = chain.at(-1)!.split(':');
      requireValue(before[index]!.ino === ((BigInt(leaf[1]!) << 32n) | BigInt(leaf[2]!)));
      requireValue(sameNode(before[index]!, lstatSync(path, { bigint: true }), true));
      return {
        path,
        stat: before[index]!,
        directory: true,
        scope: 'contained',
        nativeIdentity: createHash('sha256').update(chain.join('|')).digest('base64'),
      };
    });
    // Preserve each standalone directory guard for commit; save only its
    // duplicate prepare open. Publish none until the entire group is fenced.
    for (const guard of guards)
      requireValue(sameNode(guard.stat, lstatSync(guard.path, { bigint: true }), true));
    for (const guard of guards) {
      this.guards.push(guard);
      this.directories.set(guard.path, guard);
    }
    return bodies;
  }

  root(path: string): void {
    requireValue(isAbsolute(path) && resolve(path) === path);
    this.traceRoot = path;
    if (process.platform === 'win32') {
      this.directory(path, 'root');
      return;
    }
    const ancestors: string[] = [];
    for (let ancestor = dirname(path); ; ancestor = dirname(ancestor)) {
      ancestors.push(ancestor);
      if (ancestor === parse(ancestor).root) break;
    }
    for (const ancestor of ancestors.reverse()) this.directory(ancestor, 'ancestor');
    this.directory(path, 'root');
  }

  candidates(root: string, threadId?: string): string[] {
    const names = this.catalog(root, threadId);
    if (this.prepared) {
      const key = JSON.stringify([root, threadId]);
      const previous = this.catalogs.get(key);
      requireValue(
        previous === undefined || JSON.stringify(previous.names) === JSON.stringify(names),
      );
      this.catalogs.set(key, { root, threadId, names });
    }
    return names;
  }

  private catalog(root: string, threadId?: string): string[] {
    if (this.diagnostic) this.catalogWalks++;
    const directory = opendirSync(root);
    const names: string[] = [];
    try {
      for (let count = 0, entry; (entry = directory.readSync()) !== null; count++) {
        requireValue(count < limits.directoryEntries);
        // Do not inspect manifests, event logs or request bodies of other threads.
        if (threadId && !entry.name.endsWith('-' + threadId)) continue;
        const match = bundleName.exec(entry.name);
        if (!threadId && (!match || !validUuid(match[2]))) continue;
        requireValue(match && (!threadId || match[2] === threadId));
        requireValue(entry.isDirectory() && !entry.isSymbolicLink());
        names.push(entry.name);
        if (threadId) requireValue(names.length <= limits.bundles);
      }
    } finally {
      directory.closeSync();
    }
    return names.sort();
  }

  file(path: string, maximum: number, prefix = false): Buffer {
    this.verifyParents(path);
    const before = lstatSync(path, { bigint: true });
    this.validate(before, false, 'contained');
    requireValue(prefix || before.size <= BigInt(maximum));
    const length = Math.min(Number(before.size), maximum);
    if (process.platform === 'win32') {
      // Reuse bytes only within this one synchronous logical snapshot. Its final
      // file/chain/ACL verification remains mandatory, including every earlier
      // prefix. No binding or content cache survives an API read.
      const cached = this.nativeFiles.get(path);
      const reusable = cached && sameFile(cached.stat, before);
      this.bytes += Math.max(0, length - (reusable ? cached.bytes.length : 0));
      requireValue(this.bytes <= limits.totalBytes);
      const native =
        reusable && cached.bytes.length >= length
          ? { identity: cached.identity, bytes: cached.bytes.subarray(0, length) }
          : this.native(path, maximum);
      return this.rememberNativeFile(path, maximum, prefix, before, native);
    }
    this.bytes += length;
    requireValue(this.bytes <= limits.totalBytes);
    const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      requireValue(sameFile(before, fstatSync(fd, { bigint: true })));
      const bytes = Buffer.alloc(length);
      let offset = 0;
      while (offset < bytes.length) {
        const read = readSync(fd, bytes, offset, bytes.length - offset, offset);
        requireValue(read > 0);
        offset += read;
      }
      requireValue(sameFile(before, fstatSync(fd, { bigint: true })));
      requireValue(sameFile(before, lstatSync(path, { bigint: true })));
      this.guards.push({
        path,
        stat: before,
        directory: false,
        scope: 'contained',
        ...(prefix
          ? { prefixLength: length, prefixSha256: createHash('sha256').update(bytes).digest('hex') }
          : {}),
      });
      this.verifyParents(path);
      return bytes;
    } finally {
      closeSync(fd);
    }
  }

  private rememberNativeFile(
    path: string,
    maximum: number,
    prefix: boolean,
    before: BigIntStats,
    native: { identity: string; bytes: Buffer },
  ): Buffer {
    const length = Math.min(Number(before.size), maximum);
    const cached = this.nativeFiles.get(path);
    const reusable = cached && sameFile(cached.stat, before);
    this.confirmFileReceipt(path, maximum, prefix, before, native);
    this.guards.push({
      path,
      stat: before,
      directory: false,
      scope: 'contained',
      nativeIdentity: native.identity,
      ...(prefix
        ? {
            prefixLength: length,
            prefixSha256: createHash('sha256').update(native.bytes).digest('hex'),
            ...(this.prepared ? { prefixMayGrow: length === maximum } : {}),
          }
        : this.prepared
          ? {
              body: {
                length,
                maximum,
                sha256: createHash('sha256').update(native.bytes).digest('hex'),
              },
            }
          : {}),
    });
    // A shorter cached prefix must not discard already-accounted wider bytes.
    if (!reusable || cached.bytes.length < length)
      this.nativeFiles.set(path, {
        stat: before,
        bytes: native.bytes,
        identity: native.identity,
      });
    this.verifyParents(path);
    return native.bytes;
  }

  private confirmFileReceipt(
    path: string,
    maximum: number,
    prefix: boolean,
    before: BigIntStats,
    native: { bytes: Buffer },
  ): void {
    const length = Math.min(Number(before.size), maximum);
    const current = lstatSync(path, { bigint: true });
    requireValue(native.bytes.length === length);
    if (sameFile(before, current)) return;
    // A different chat may append after its native prefix handle closes while
    // the other batch items finish. Reopen and compare those exact bytes; no
    // native call is replayed, and full bodies still require identical metadata.
    requireValue(
      this.prepared && prefix && sameNode(before, current) && current.size >= before.size,
    );
    const guard: Guard = {
      path,
      stat: before,
      directory: false,
      scope: 'contained',
      prefixLength: length,
      prefixSha256: createHash('sha256').update(native.bytes).digest('hex'),
      // Tentative only. The real receipt below retains its original short-size
      // guard until a complete configured payload closes that discovery input.
      prefixMayGrow: true,
    };
    this.prefixAfterBatch(guard, current);
    requireValue(sameNode(current, this.guardStat(guard)));
  }

  /** Ordered discovery inputs; each dispatched B is all-or-nothing, never replayed. */
  files(items: readonly { path: string; maximum: number; prefix?: boolean }[]): Buffer[] {
    if (
      !this.prepared ||
      process.platform !== 'win32' ||
      items.length < 2 ||
      !windowsTraceSnapshotBatchAvailable()
    )
      return items.map((item) => this.file(item.path, item.maximum, item.prefix));
    const result: Buffer[] = [];
    for (let index = 0; index < items.length;) {
      const plans: {
        path: string;
        maximum: number;
        prefix?: boolean;
        before: BigIntStats;
        length: number;
        cached: { stat: BigIntStats; bytes: Buffer; identity: string } | undefined;
        reusable: boolean | undefined;
      }[] = [];
      const planner = createTraceSnapshotBatchPlanner();
      for (
        let at = index;
        at < items.length && plans.length < traceSnapshotBatchLimits.items;
        at++
      ) {
        const item = items[at]!;
        // A duplicate must observe the earlier item's normal request-local cache.
        if (plans.some((plan) => plan.path === item.path)) break;
        this.verifyParents(item.path);
        const before = lstatSync(item.path, { bigint: true });
        this.validate(before, false, 'contained');
        requireValue(item.prefix || before.size <= BigInt(item.maximum));
        const length = Math.min(Number(before.size), item.maximum);
        const cached = this.nativeFiles.get(item.path);
        const reusable = cached && sameFile(cached.stat, before);
        if (
          (reusable && cached.bytes.length >= length) ||
          !planner.add({ path: item.path, maximum: Math.max(1, length) })
        )
          break;
        plans.push({ ...item, before, length, cached, reusable });
      }
      // Cache hits, large items and remaining singles choose S/cache before dispatch.
      if (plans.length < 2) {
        const item = items[index++]!;
        result.push(this.file(item.path, item.maximum, item.prefix));
        continue;
      }
      this.bytes += plans.reduce(
        (sum, plan) =>
          sum + Math.max(0, plan.length - (plan.reusable ? plan.cached!.bytes.length : 0)),
        0,
      );
      requireValue(this.bytes <= limits.totalBytes);
      const values = windowsTraceSnapshotMany(
        this.traceRoot,
        plans.map((plan) => ({
          path: plan.path,
          maximum: Math.max(1, plan.length),
        })),
      );
      requireValue(values.length === plans.length);
      for (let at = 0; at < plans.length; at++) {
        const plan = plans[at]!,
          value = values[at]!;
        this.nativeResult(plan.path, value);
        this.confirmFileReceipt(plan.path, plan.maximum, plan.prefix === true, plan.before, value);
      }
      // Fence early items against changes while a later native item ran.
      for (let at = 0; at < plans.length; at++) {
        const plan = plans[at]!;
        this.confirmFileReceipt(
          plan.path,
          plan.maximum,
          plan.prefix === true,
          plan.before,
          values[at]!,
        );
      }
      for (let at = 0; at < plans.length; at++) {
        const plan = plans[at]!;
        result.push(
          this.rememberNativeFile(
            plan.path,
            plan.maximum,
            plan.prefix === true,
            plan.before,
            values[at]!,
          ),
        );
      }
      index += plans.length;
    }
    return result;
  }

  private verifyParents(path: string): void {
    // The Windows native call already retains and checks the complete chain
    // around each read, and native() compares every component with this snapshot.
    // Reopening each parent in separate helper processes adds no protection and
    // can outlast the mobile lease. POSIX retains its original parent checks.
    if (process.platform === 'win32') return;
    // Each read validates its complete parent chain and its own file before/
    // after opening. The final verify still checks every previously read file.
    // Rechecking all earlier siblings around each read made discovery quadratic.
    for (let parent = dirname(path); ; parent = dirname(parent)) {
      const guard = this.directories.get(parent);
      requireValue(guard);
      this.verifyGuard(guard, false);
      if (parent === parse(parent).root) break;
    }
  }

  verify(prefixContents = true): void {
    if (process.platform === 'win32' && windowsTraceSnapshotBatchAvailable()) {
      for (let index = 0; index < this.guards.length;) {
        const guards: Guard[] = [];
        const planner = createTraceSnapshotBatchPlanner();
        const width = this.prepared ? traceSnapshotBatchLimits.items : 2;
        for (let at = index; at < this.guards.length && guards.length < width; at++) {
          const next = this.guards[at]!;
          if (!planner.add({ path: next.path, maximum: this.nativeMaximum(next) })) break;
          guards.push(next);
        }
        const items = guards.map((guard) => ({
          path: guard.path,
          maximum: this.nativeMaximum(guard),
        }));
        if (guards.length < 2) {
          this.verifyGuard(this.guards[index++]!, prefixContents);
          continue;
        }
        // Batching changes interleaving, not authority: each item has both its
        // own preflight and fresh post-reply preconditions. Never reuse a stat.
        const before = guards.map((guard) => this.guardStat(guard));
        const values = windowsTraceSnapshotMany(this.traceRoot, items);
        requireValue(values.length === guards.length);
        for (let offset = 0; offset < guards.length; offset++) {
          const guard = guards[offset]!,
            value = values[offset]!;
          const current = this.guardStat(guard);
          if (guard.body) requireValue(sameFile(before[offset]!, current));
          this.nativeResult(guard.path, value);
          this.nativeGuard(guard, value, prefixContents);
          // If a prefix changed while the other item ran, metadata alone cannot
          // distinguish append from rewrite. Recheck its old prefix on a matching
          // file handle; this adds no authority and replaces no native ACL check.
          if (
            prefixContents &&
            guard.prefixLength !== undefined &&
            !sameFile(before[offset]!, current)
          )
            this.prefixAfterBatch(guard, current);
          const final = this.guardStat(guard);
          requireValue(
            guard.body ? sameFile(current, final) : sameNode(current, final, guard.directory),
          );
          if (this.prepared) guard.verifiedStat = final;
        }
        index += guards.length;
      }
      return;
    }
    for (const guard of this.guards) this.verifyGuard(guard, prefixContents);
  }

  private guardStat(guard: Guard): BigIntStats {
    const current = lstatSync(guard.path, { bigint: true });
    this.validate(current, guard.directory, guard.scope);
    requireValue(
      guard.directory || guard.prefixLength !== undefined
        ? sameNode(current, guard.stat, guard.directory)
        : sameFile(current, guard.stat),
    );
    if (this.prepared && guard.prefixLength !== undefined && !guard.prefixMayGrow)
      requireValue(current.size === guard.stat.size);
    requireValue(realpathSync(guard.path) === guard.path);
    return current;
  }

  private nativeGuard(guard: Guard, native: TraceSnapshotValue, prefixContents: boolean): void {
    requireValue(native.identity === guard.nativeIdentity);
    if (guard.body)
      requireValue(
        native.bytes.length === guard.body.length &&
          createHash('sha256').update(native.bytes).digest('hex') === guard.body.sha256,
      );
    if (guard.prefixLength !== undefined && prefixContents)
      requireValue(
        native.bytes.length === guard.prefixLength &&
          createHash('sha256').update(native.bytes).digest('hex') === guard.prefixSha256,
      );
  }

  private nativeMaximum(guard: Guard): number {
    return guard.directory ? 0 : Math.max(1, guard.body?.length ?? guard.prefixLength ?? 1);
  }

  private prefixAfterBatch(guard: Guard, current: BigIntStats): void {
    const fd = openSync(guard.path, constants.O_RDONLY);
    try {
      const opened = fstatSync(fd, { bigint: true });
      requireValue(sameNode(current, opened) && opened.size >= BigInt(guard.prefixLength!));
      const bytes = Buffer.alloc(guard.prefixLength!);
      for (let offset = 0; offset < bytes.length;) {
        const count = readSync(fd, bytes, offset, bytes.length - offset, offset);
        requireValue(count > 0);
        offset += count;
      }
      requireValue(
        sameNode(current, fstatSync(fd, { bigint: true })) &&
          createHash('sha256').update(bytes).digest('hex') === guard.prefixSha256,
      );
    } finally {
      closeSync(fd);
    }
  }

  private verifyGuard(guard: Guard, prefixContents: boolean): void {
    const current = this.guardStat(guard);
    if (process.platform === 'win32') {
      const native = this.native(guard.path, this.nativeMaximum(guard));
      if (this.prepared) {
        // Large full-body receipts select S before dispatch; they still retain
        // the same three fresh canonical/stat positions as a prepared batch.
        const after = this.guardStat(guard);
        if (guard.body) requireValue(sameFile(current, after));
        this.nativeGuard(guard, native, prefixContents);
        if (prefixContents && guard.prefixLength !== undefined && !sameFile(current, after))
          this.prefixAfterBatch(guard, after);
        const final = this.guardStat(guard);
        requireValue(guard.body ? sameFile(after, final) : sameNode(after, final, guard.directory));
        guard.verifiedStat = final;
        return;
      }
      this.nativeGuard(guard, native, prefixContents);
      requireValue(sameNode(current, lstatSync(guard.path, { bigint: true }), guard.directory));
      return;
    }
    if (guard.prefixLength !== undefined) {
      requireValue(current.size >= BigInt(guard.prefixLength));
      if (!prefixContents) return;
      const fd = openSync(
        guard.path,
        constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK,
      );
      try {
        requireValue(sameNode(current, fstatSync(fd, { bigint: true })));
        const bytes = Buffer.alloc(guard.prefixLength);
        let offset = 0;
        while (offset < bytes.length) {
          const count = readSync(fd, bytes, offset, bytes.length - offset, offset);
          requireValue(count > 0);
          offset += count;
        }
        requireValue(
          createHash('sha256').update(bytes).digest('hex') === guard.prefixSha256 &&
            sameNode(current, fstatSync(fd, { bigint: true })),
        );
      } finally {
        closeSync(fd);
      }
    }
  }
}

function payloadPath(
  directory: string,
  value: unknown,
  type: 'inference_request' | 'inference_response' | 'protocol_event',
): string {
  const reference = object(value);
  const kind = object(reference.kind);
  requireValue(kind.type === type && Object.keys(kind).length === 1);
  const ordinal =
    typeof reference.raw_payload_id === 'string'
      ? /^raw_payload:([1-9][0-9]{0,15})$/.exec(reference.raw_payload_id)?.[1]
      : undefined;
  requireValue(ordinal && reference.path === `payloads/${ordinal}.json`);
  return join(directory, 'payloads', ordinal + '.json');
}
function payloadBytes(
  snapshot: TraceSnapshot,
  directory: string,
  value: unknown,
  type: 'inference_request' | 'inference_response' | 'protocol_event',
  maximum: number,
): Buffer {
  return snapshot.file(payloadPath(directory, value, type), maximum);
}

function predictionText(
  response: JsonObject,
  predictionTurnId: string,
  snapshot: TraceSnapshot,
): string {
  requireValue(Array.isArray(response.output_items) && response.output_items.length > 0);
  let text: string | null = null;
  for (const value of response.output_items) {
    const item = object(value);
    if (item.internal_chat_message_metadata_passthrough !== undefined) {
      requireValue(
        object(item.internal_chat_message_metadata_passthrough).turn_id === predictionTurnId,
      );
    }
    if (item.type === 'reasoning') continue;
    requireValue(
      text === null &&
        item.type === 'message' &&
        item.role === 'assistant' &&
        item.status === 'completed' &&
        item.phase === 'final_answer' &&
        Array.isArray(item.content) &&
        item.content.length === 1,
    );
    const content = object(item.content[0]);
    requireValue(
      content.type === 'output_text' &&
        typeof content.text === 'string' &&
        content.text.length <= 16_000 * 6 + 128 &&
        // Exactly one string field also rejects duplicate suggestion keys; JSON.parse
        // alone would silently choose the last conflicting value.
        /^\s*\{\s*"suggestion"\s*:\s*"(?:[^"\\]|\\.)*"\s*\}\s*$/s.test(content.text),
    );
    const suggestion = snapshot.json(content.text).suggestion;
    requireValue(
      typeof suggestion === 'string' && suggestion.trim().length > 0 && suggestion.length <= 16_000,
    );
    text = suggestion;
  }
  requireValue(text !== null);
  return text;
}

function completedPrediction(
  snapshot: TraceSnapshot,
  binding: DesktopPredictionTraceBinding,
  requests: TraceRequest[],
  completions: TraceEvent[],
  endings: TraceEvent[],
): Omit<DesktopPredictionTracePrediction, 'binding'> {
  // The observed native shape has one inference. Until retry selection is verified,
  // never guess which of several requests or terminal records supplies the result.
  requireValue(requests.length === 1);
  const request = requests[0]!;
  const related = completions.filter(
    ({ event, payload }) =>
      event.codex_turn_id === binding.predictionTurnId ||
      payload.codex_turn_id === binding.predictionTurnId ||
      payload.inference_call_id === request.inferenceCallId,
  );
  requireValue(related.length === 1 && endings.length === 1);
  const completion = related[0]!;
  const ending = endings[0]!;
  for (const record of [completion, ending]) {
    requireValue(
      record.directory === request.directory &&
        record.event.thread_id === binding.predictionThreadId &&
        record.event.codex_turn_id === binding.predictionTurnId &&
        (record.payload.thread_id === undefined ||
          record.payload.thread_id === binding.predictionThreadId) &&
        (record.payload.codex_turn_id === undefined ||
          record.payload.codex_turn_id === binding.predictionTurnId),
    );
  }
  requireValue(
    completion.payload.inference_call_id === request.inferenceCallId &&
      typeof completion.payload.response_id === 'string' &&
      completion.payload.response_id.length > 0 &&
      ending.payload.codex_turn_id === binding.predictionTurnId &&
      ending.payload.status === 'completed' &&
      Number(request.event.seq) < Number(completion.event.seq) &&
      Number(completion.event.seq) < Number(ending.event.seq) &&
      Number(request.event.wall_time_unix_ms) >= 0 &&
      Number(request.event.wall_time_unix_ms) <= Number(completion.event.wall_time_unix_ms) &&
      Number(completion.event.wall_time_unix_ms) <= Number(ending.event.wall_time_unix_ms),
  );
  const bytes = payloadBytes(
    snapshot,
    completion.directory,
    completion.payload.response_payload,
    'inference_response',
    limits.response,
  );
  const response = snapshot.json(bytes);
  requireValue(
    response.response_id === completion.payload.response_id &&
      (response.upstream_request_id === null || typeof response.upstream_request_id === 'string') &&
      response.upstream_request_id === completion.payload.upstream_request_id,
  );
  return {
    text: predictionText(response, binding.predictionTurnId, snapshot),
    completedAt: new Date(Number(ending.event.wall_time_unix_ms)).toISOString(),
    responsePayloadSha256: createHash('sha256').update(bytes).digest('hex'),
  };
}

/**
 * Decoder pinned to openai/codex rust-v0.163.0-alpha.10.2 rollout-trace/{bundle,
 * raw_event,payload,writer}.rs and core/src/responses_metadata.rs. Synthetic tests
 * establish the decoder boundary, not live desktop acceptance. Caller supplies an
 * observed execution; this class does not establish desktop ownership or display.
 * Filesystem safety is delegated to native Windows handles/DACLs or POSIX guards.
 */
export class DesktopPredictionTraceReader {
  constructor(private readonly root: string) {}

  isAvailable(): boolean {
    if (process.platform !== 'win32' && !process.getuid) return false;
    try {
      const snapshot = new TraceSnapshot();
      snapshot.root(this.root);
      // Opening the root checks readability without enumerating other executions.
      const directory = opendirSync(this.root);
      directory.closeSync();
      snapshot.verify();
      return true;
    } catch {
      return false;
    }
  }

  readBinding(
    predictionThreadId: string,
    predictionTurnId: string,
  ): DesktopPredictionTraceBinding | null {
    return this.readSnapshot(predictionThreadId, predictionTurnId, false)?.binding ?? null;
  }

  readPrediction(
    predictionThreadId: string,
    predictionTurnId: string,
  ): DesktopPredictionTracePrediction | null {
    const result = this.readSnapshot(predictionThreadId, predictionTurnId, true);
    return result?.prediction ? { binding: result.binding, ...result.prediction } : null;
  }

  /** Recover missed notifications from existing native evidence, never generate a prediction. */
  readSourcePredictions(
    sourceThreadId: string,
    sourceTurnId: string,
    notBeforeUnixMs = 0,
    latestOnly = false,
  ): DesktopPredictionTracePrediction[] | null {
    return this.scanSourcePredictions(sourceThreadId, sourceTurnId, notBeforeUnixMs, latestOnly);
  }

  prepareSourcePredictions(
    sourceThreadId: string,
    sourceTurnId: string,
    notBeforeUnixMs = 0,
    latestOnly = false,
  ): PreparedDesktopPredictionTraceRead | null {
    if (process.platform !== 'win32') return null;
    let snapshot: TraceSnapshot | null = new TraceSnapshot(true);
    const capture = snapshot;
    let results = capture.capture(() =>
      this.scanSourcePredictions(
        sourceThreadId,
        sourceTurnId,
        notBeforeUnixMs,
        latestOnly,
        capture,
      ),
    );
    capture.report(
      results === null ? 'native_rejected' : 'ok',
      results === null ? 'interrupted' : 'prepared',
    );
    if (results === null) {
      snapshot.dispose();
      return null;
    }
    snapshot.releaseBodies();
    const dispose = () => {
      snapshot?.report('aborted', 'interrupted');
      snapshot?.dispose();
      snapshot = null;
      results = null;
    };
    return Object.freeze({
      commit: () => {
        if (snapshot === null || results === null) return null;
        const pending = snapshot,
          projection = results;
        // Consume before touching the filesystem: failure cannot be retried and
        // re-entrant/disposed/second commits cannot publish a prior projection.
        snapshot = null;
        results = null;
        try {
          pending.commit();
          pending.report('ok', 'committed');
          return projection;
        } catch {
          pending.report('native_rejected', 'interrupted');
          return null;
        } finally {
          pending.dispose();
        }
      },
      dispose,
    });
  }

  private *discoverDirectories(
    snapshot: TraceSnapshot,
    names: string[],
    notBeforeUnixMs: number,
  ): Generator<{
    name: string;
    directory: string;
  }> {
    const preparedWindows = process.platform === 'win32' && snapshot.isPreparedDiscovery;
    const width = preparedWindows ? traceSnapshotBatchLimits.items : 1;
    for (let index = 0; index < names.length; index += width) {
      let group = names
        .slice(index, index + width)
        .map((name) => ({ name, directory: join(this.root, name) }));
      if (!preparedWindows || notBeforeUnixMs <= 0) {
        if (preparedWindows) snapshot.directoryGroup(group.map((item) => item.directory));
        else for (const item of group) snapshot.directory(item.directory);
      }
      if (notBeforeUnixMs > 0) {
        const manifests = preparedWindows
          ? snapshot.discoveryFiles(
              group.map((item) => ({
                path: join(item.directory, 'manifest.json'),
                maximum: limits.manifest,
              })),
            )
          : snapshot.files(
              group.map((item) => ({
                path: join(item.directory, 'manifest.json'),
                maximum: limits.manifest,
              })),
            );
        group = group.filter((_item, offset) => {
          const manifest = snapshot.json(manifests[offset]!);
          requireValue(
            manifest.schema_version === 1 && Number.isSafeInteger(manifest.started_at_unix_ms),
          );
          return Number(manifest.started_at_unix_ms) >= notBeforeUnixMs;
        });
      }
      yield* group;
    }
  }

  private *discoverCandidates(
    snapshot: TraceSnapshot,
    names: string[],
    notBeforeUnixMs: number,
  ): Generator<{
    name: string;
    directory: string;
    configured: JsonObject | undefined;
  }> {
    const preparedWindows = process.platform === 'win32' && snapshot.isPreparedDiscovery;
    const width = preparedWindows ? traceSnapshotBatchLimits.items : 1;
    const directories = this.discoverDirectories(snapshot, names, notBeforeUnixMs);
    for (;;) {
      // Filtering may leave one eligible directory in each manifest batch.
      // Repack only these fresh, request-local receipts so prefixes/payloads
      // keep bounded batches. Excluded manifests retain all commit guards.
      const group: { name: string; directory: string }[] = [];
      while (group.length < width) {
        const next = directories.next();
        if (next.done) break;
        group.push(next.value);
      }
      if (group.length === 0) return;
      const prefixMaximum = preparedWindows
        ? limits.initialDiscoveryPrefix
        : limits.discoveryPrefix;
      const prefixes = snapshot.files(
        group.map((item) => ({
          path: join(item.directory, 'trace.jsonl'),
          maximum: prefixMaximum,
          prefix: true,
        })),
      );
      const contexts = group.map((item) => ({
        ...item,
        configured: undefined as JsonObject | undefined,
      }));
      const reference = (offset: number, prefix: Buffer) => {
        const item = contexts[offset]!;
        const lines = snapshot.decode(() => prefix.toString('utf8').split('\n').slice(0, -1));
        for (const line of lines) {
          const event = snapshot.json(line),
            payload = object(event.payload);
          if (
            payload.type === 'protocol_event_observed' &&
            payload.event_type === 'session_configured'
          ) {
            if (!preparedWindows) snapshot.directory(join(item.directory, 'payloads'));
            return {
              offset,
              path: payloadPath(item.directory, payload.event_payload, 'protocol_event'),
            };
          }
        }
        return undefined;
      };
      const discovered = prefixes.map((prefix, offset) => reference(offset, prefix));
      if (preparedWindows) {
        // Once the first configured event is complete, later prefix lines were
        // never parser inputs. Otherwise extend the successful short read to
        // the original bound, retaining both native/prefix receipts at commit.
        // A rejected native read never reaches this extension path.
        const extend = prefixes.flatMap((prefix, offset) =>
          !discovered[offset] && prefix.length === prefixMaximum ? [offset] : [],
        );
        const extended = snapshot.files(
          extend.map((offset) => ({
            path: join(contexts[offset]!.directory, 'trace.jsonl'),
            maximum: limits.discoveryPrefix,
            prefix: true,
          })),
        );
        for (let at = 0; at < extend.length; at++)
          discovered[extend[at]!] = reference(extend[at]!, extended[at]!);
      }
      const references = discovered.filter((item) => item !== undefined);
      const payloadItems = references.map((item) => ({
        path: item.path,
        maximum: limits.protocolEvent,
      }));
      let payloads: Buffer[];
      if (preparedWindows && notBeforeUnixMs > 0) payloads = snapshot.discoveryFiles(payloadItems);
      else {
        if (preparedWindows)
          snapshot.directoryGroup(
            references.map((item) => join(contexts[item.offset]!.directory, 'payloads')),
          );
        payloads = snapshot.files(payloadItems);
      }
      for (let at = 0; at < references.length; at++) {
        const item = contexts[references[at]!.offset]!;
        item.configured = snapshot.json(payloads[at]!);
        if (item.configured.type === 'session_configured')
          snapshot.closeDiscoveryPrefix(join(item.directory, 'trace.jsonl'));
      }
      yield* contexts;
    }
  }

  private scanSourcePredictions(
    sourceThreadId: string,
    sourceTurnId: string,
    notBeforeUnixMs: number,
    latestOnly: boolean,
    preparedSnapshot?: TraceSnapshot,
  ): DesktopPredictionTracePrediction[] | null {
    if (
      !Number.isFinite(notBeforeUnixMs) ||
      notBeforeUnixMs < 0 ||
      (process.platform !== 'win32' && !process.getuid) ||
      !validUuid(sourceThreadId) ||
      !validUuid(sourceTurnId) ||
      sourceThreadId === sourceTurnId
    )
      return null;
    try {
      const snapshot = preparedSnapshot ?? new TraceSnapshot();
      snapshot.root(this.root);
      const names = snapshot.candidates(this.root);
      const executions = new Map<
        string,
        { threadId: string; turnId: string; requestedAt: number }
      >();
      for (const { name, directory, configured } of this.discoverCandidates(
        snapshot,
        names,
        notBeforeUnixMs,
      )) {
        if (
          configured?.thread_source !== 'composer_predictions' ||
          configured.forked_from_id !== sourceThreadId
        )
          continue;
        const predictionThreadId = bundleName.exec(name)![2]!;
        requireValue(
          configured.type === 'session_configured' &&
            configured.thread_id === predictionThreadId &&
            typeof configured.cwd === 'string',
        );
        const log = snapshot.file(join(directory, 'trace.jsonl'), limits.eventLog);
        requireValue(log.length === 0 || log.at(-1) === 10);
        const indexed = snapshot.preparedLog(
          join(directory, 'trace.jsonl'),
          log,
          directory,
          predictionThreadId,
        );
        let lineIndex = 0;
        const lines = snapshot.decode(() => log.toString('utf8').trimEnd().split('\n'));
        for (const line of lines) {
          const event = indexed ? indexed.eventAt(lineIndex++).event : snapshot.json(line);
          const payload = object(event.payload);
          if (payload.type !== 'inference_started') continue;
          const turnId = event.codex_turn_id;
          requireValue(validUuid(turnId));
          const binding =
            process.platform === 'win32'
              ? snapshot.withByteBudget(
                  () =>
                    this.readSnapshot(predictionThreadId, turnId, false, false, snapshot)?.binding,
                )
              : this.readBinding(predictionThreadId, turnId);
          // Incomplete or contradictory evidence cannot be bypassed by another candidate.
          requireValue(binding);
          if (binding.sourceThreadId !== sourceThreadId || binding.sourceTurnId !== sourceTurnId)
            continue;
          const key = JSON.stringify([predictionThreadId, turnId]);
          const requestedAt = Number(event.wall_time_unix_ms);
          requireValue(Number.isSafeInteger(requestedAt) && requestedAt >= 0);
          executions.set(key, {
            threadId: predictionThreadId,
            turnId,
            requestedAt: Math.min(executions.get(key)?.requestedAt ?? Infinity, requestedAt),
          });
          requireValue(executions.size <= limits.requests);
        }
      }
      let selected = [...executions.values()];
      if (latestOnly && selected.length > 0) {
        // Request order, not completion time or filesystem mtime. A newer
        // incomplete/empty/failed execution must never revive an older result.
        const newest = Math.max(...selected.map((value) => value.requestedAt));
        selected = selected.filter((value) => value.requestedAt === newest);
        requireValue(selected.length === 1);
      }
      const results: DesktopPredictionTracePrediction[] = [];
      for (const execution of selected) {
        const read = () =>
          this.readSnapshot(
            execution.threadId,
            execution.turnId,
            true,
            true,
            process.platform === 'win32' ? snapshot : undefined,
          );
        const result = process.platform === 'win32' ? snapshot.withByteBudget(read) : read();
        requireValue(
          result?.prediction &&
            result.binding.sourceThreadId === sourceThreadId &&
            result.binding.sourceTurnId === sourceTurnId,
        );
        results.push({ binding: result.binding, ...result.prediction });
      }
      requireValue(JSON.stringify(names) === JSON.stringify(snapshot.candidates(this.root)));
      if (!preparedSnapshot) snapshot.verify();
      return results;
    } catch {
      return null;
    }
  }

  private readSnapshot(
    predictionThreadId: string,
    predictionTurnId: string,
    includePrediction: boolean,
    recovery = false,
    sharedSnapshot?: TraceSnapshot,
  ): {
    binding: DesktopPredictionTraceBinding;
    prediction: Omit<DesktopPredictionTracePrediction, 'binding'> | null;
  } | null {
    if (
      (process.platform !== 'win32' && !process.getuid) ||
      !validUuid(predictionThreadId) ||
      !validUuid(predictionTurnId) ||
      predictionThreadId === predictionTurnId
    )
      return null;
    try {
      const snapshot = sharedSnapshot ?? new TraceSnapshot();
      snapshot.recordReadSnapshot();
      if (!sharedSnapshot) snapshot.root(this.root);
      const names = snapshot.candidates(this.root, predictionThreadId);
      let binding: DesktopPredictionTraceBinding | null = null;
      const requests: TraceRequest[] = [];
      const completions: TraceEvent[] = [];
      const endings: TraceEvent[] = [];
      const terminalMessages: TraceEvent[] = [];
      for (const name of names) {
        const directory = join(this.root, name);
        snapshot.directory(directory);
        const manifestBytes = snapshot.file(join(directory, 'manifest.json'), limits.manifest);
        const manifest = snapshot.json(manifestBytes);
        requireValue(
          manifest.schema_version === 1 &&
            validUuid(manifest.trace_id) &&
            name === `trace-${manifest.trace_id}-${predictionThreadId}` &&
            manifest.rollout_id === predictionThreadId &&
            manifest.root_thread_id === predictionThreadId &&
            Number.isSafeInteger(manifest.started_at_unix_ms) &&
            Number(manifest.started_at_unix_ms) >= 0 &&
            manifest.raw_event_log === 'trace.jsonl' &&
            manifest.payloads_dir === 'payloads',
        );
        snapshot.directory(join(directory, 'payloads'));
        const log = snapshot.file(join(directory, 'trace.jsonl'), limits.eventLog);
        if (includePrediction) requireValue(log.length === 0 || log.at(-1) === 10);
        const indexed = snapshot
          .preparedLog(join(directory, 'trace.jsonl'), log, directory, predictionThreadId)
          ?.forTurn(predictionTurnId);
        if (indexed) {
          if (recovery) terminalMessages.push(...indexed.terminals);
          if (includePrediction) {
            completions.push(...indexed.completions);
            endings.push(...indexed.endings);
          }
        }
        let offset = 0;
        let expectedSequence = 1;
        let requestIndex = 0;
        while (indexed ? requestIndex < indexed.starts.length : offset < log.length) {
          let event: JsonObject;
          if (indexed) event = indexed.starts[requestIndex++]!.event;
          else {
            const end = log.indexOf(10, offset);
            // An in-progress tail is not an event and must never supply evidence.
            if (end === -1) break;
            requireValue(end - offset <= limits.eventLine);
            event = snapshot.json(log.subarray(offset, end));
            offset = end + 1;
            requireValue(
              event.schema_version === 1 &&
                event.rollout_id === predictionThreadId &&
                event.seq === expectedSequence++ &&
                Number.isSafeInteger(event.wall_time_unix_ms),
            );
          }
          const payload = object(event.payload);
          if (
            !indexed &&
            recovery &&
            payload.type === 'protocol_event_observed' &&
            payload.event_type === 'turn_complete'
          )
            terminalMessages.push({ directory, event, payload });
          if (!indexed && includePrediction) {
            if (payload.type === 'inference_completed')
              completions.push({ directory, event, payload });
            if (
              payload.type === 'codex_turn_ended' &&
              (event.codex_turn_id === predictionTurnId ||
                payload.codex_turn_id === predictionTurnId)
            )
              endings.push({ directory, event, payload });
          }
          if (payload.type !== 'inference_started') continue;
          if (
            event.codex_turn_id !== predictionTurnId &&
            payload.codex_turn_id !== predictionTurnId
          )
            continue;
          requireValue(
            event.thread_id === predictionThreadId &&
              payload.thread_id === predictionThreadId &&
              event.codex_turn_id === predictionTurnId &&
              payload.codex_turn_id === predictionTurnId &&
              validUuid(payload.inference_call_id),
          );
          const requestBytes = payloadBytes(
            snapshot,
            directory,
            payload.request_payload,
            'inference_request',
            limits.request,
          );
          const request = snapshot.json(requestBytes);
          // Both HTTP and response.create serialize client_metadata at the top level.
          requireValue(request.type === undefined || request.type === 'response.create');
          const clientMetadata = object(request.client_metadata);
          requireValue(
            clientMetadata.thread_id === predictionThreadId &&
              clientMetadata.turn_id === predictionTurnId &&
              (clientMetadata.session_id === undefined || validUuid(clientMetadata.session_id)) &&
              typeof clientMetadata['x-codex-turn-metadata'] === 'string' &&
              clientMetadata['x-codex-turn-metadata'].length <= limits.eventLine,
          );
          const metadata = snapshot.json(clientMetadata['x-codex-turn-metadata']);
          if (recovery) {
            requireValue(
              metadata.thread_source === 'composer_predictions' &&
                metadata.turn_trigger === 'composer_predictions' &&
                Array.isArray(request.input),
            );
            const users = request.input.filter((item) => object(item).role === 'user');
            const last = object(users.at(-1));
            requireValue(Array.isArray(last.content) && last.content.length === 1);
            const content = object(last.content[0]);
            requireValue(
              content.type === 'input_text' &&
                typeof content.text === 'string' &&
                content.text.startsWith(
                  'This is an app-generated request for a suggested next user message.',
                ),
            );
          }
          const sourceThreadId = metadata.prediction_source_thread_id;
          const sourceTurnId = metadata.prediction_source_turn_id;
          requireValue(
            metadata.thread_id === predictionThreadId &&
              metadata.turn_id === predictionTurnId &&
              (metadata.session_id === undefined || validUuid(metadata.session_id)) &&
              (metadata.session_id === undefined ||
                clientMetadata.session_id === undefined ||
                metadata.session_id === clientMetadata.session_id) &&
              metadata.request_kind === 'turn' &&
              validUuid(sourceThreadId) &&
              validUuid(sourceTurnId) &&
              new Set([predictionThreadId, predictionTurnId, sourceThreadId, sourceTurnId]).size ===
                4 &&
              (metadata.forked_from_thread_id === undefined ||
                metadata.forked_from_thread_id === sourceThreadId),
          );
          if (binding) {
            requireValue(
              binding.sourceThreadId === sourceThreadId && binding.sourceTurnId === sourceTurnId,
            );
          } else {
            binding = {
              predictionThreadId,
              predictionTurnId,
              sourceThreadId,
              sourceTurnId,
              evidence: { kind: 'native-rollout-trace', schemaVersion: 1, requests: [] },
            };
          }
          requireValue(binding.evidence.requests.length < limits.requests);
          binding.evidence.requests.push({
            traceId: manifest.trace_id,
            sequence: Number(event.seq),
            inferenceCallId: payload.inference_call_id,
            requestPayloadSha256: createHash('sha256').update(requestBytes).digest('hex'),
          });
          if (includePrediction)
            requests.push({
              directory,
              event,
              payload,
              inferenceCallId: payload.inference_call_id,
            });
        }
      }
      const prediction =
        includePrediction && binding
          ? completedPrediction(snapshot, binding, requests, completions, endings)
          : null;
      if (recovery) {
        requireValue(prediction && terminalMessages.length === 1 && endings.length === 1);
        const terminal = terminalMessages[0]!;
        requireValue(
          terminal.directory === endings[0]!.directory &&
            Number(terminal.event.seq) > Number(endings[0]!.event.seq),
        );
        const messageBytes = payloadBytes(
          snapshot,
          terminal.directory,
          terminal.payload.event_payload,
          'protocol_event',
          limits.protocolEvent,
        );
        const message = snapshot.json(messageBytes);
        requireValue(
          message.type === 'task_complete' &&
            message.turn_id === predictionTurnId &&
            (message.root_turn_id === undefined || message.root_turn_id === predictionTurnId) &&
            typeof message.last_agent_message === 'string',
        );
        requireValue(
          /^\s*\{\s*"suggestion"\s*:\s*"(?:[^"\\]|\\.)*"\s*\}\s*$/s.test(
            message.last_agent_message,
          ),
        );
        const value = snapshot.json(message.last_agent_message);
        requireValue(Object.keys(value).length === 1 && value.suggestion === prediction.text);
      }
      requireValue(
        JSON.stringify(names) ===
          JSON.stringify(snapshot.candidates(this.root, predictionThreadId)),
      );
      if (!sharedSnapshot) snapshot.verify();
      return binding ? { binding, prediction } : null;
    } catch {
      // Missing, changing, non-private, malformed or contradictory evidence is unbound.
      return null;
    }
  }
}
