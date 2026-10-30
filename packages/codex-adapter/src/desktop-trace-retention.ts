import { createHash, randomUUID } from 'node:crypto';
import {
  closeSync,
  existsSync,
  fsyncSync,
  lstatSync,
  openSync,
  readFileSync,
  readSync,
  readdirSync,
  realpathSync,
  renameSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { join, relative, sep } from 'node:path';
import { open as openAsync } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { withWindowsProcessCreation } from './windows-process.js';
import { DesktopPredictionTraceReader } from './desktop-prediction-trace.js';
import {
  DesktopSuggestionStore,
  type CompactDesktopSuggestion,
} from './desktop-suggestion-store.js';
import {
  assertPrivateFile,
  protectWindowsStateFile,
  openPrivateFileSync,
  windowsTraceSnapshot,
  windowsPowerShellEnvironment,
} from './desktop-platform.js';

const execute = promisify(execFile);
const uuid = '[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}';
const bundleName = new RegExp(`^trace-(${uuid})-(${uuid})$`, 'i');
const sha = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');
const day = 24 * 3600_000;
const ordinarySources = [
  'user',
  'agent_created_thread',
  'automation',
  'thread_title',
  'thread_description',
  'ambient_suggestions',
  'ambient_suggestion_safety',
  'guardian_review',
  'mcp_extension_host',
];
type RawEvent = {
  schema_version: number;
  rollout_id: string;
  thread_id?: string | null;
  codex_turn_id?: string | null;
  payload: Record<string, unknown>;
};
/** Older ordinary desktop traces identify their frontend in thread metadata. */
function legacySource(
  directory: string,
  root: string,
  events: RawEvent[],
  threadId: string,
): string | undefined {
  const start = events.find((e) => e.payload?.type === 'thread_started');
  const first = events.find((e) => e.payload?.type === 'inference_started');
  const metadataRef = start?.payload.metadata_payload as { path?: unknown } | undefined;
  const requestRef = first?.payload.request_payload as { path?: unknown } | undefined;
  const safe = (path: unknown): path is string =>
    typeof path === 'string' && /^payloads\/\d+\.json$/.test(path);
  if (
    !start ||
    !first ||
    !safe(metadataRef?.path) ||
    !safe(requestRef?.path) ||
    first.thread_id !== threadId
  )
    return undefined;
  const started = JSON.parse(
    bounded(join(directory, metadataRef.path), 128 * 1024, root).toString('utf8'),
  );
  if (started?.thread_id !== threadId || started.session_source !== 'vscode') return undefined;
  const request = JSON.parse(
    bounded(join(directory, requestRef.path), 32 * 1024 * 1024, root).toString('utf8'),
  );
  const client = request?.client_metadata;
  const raw = client?.['x-codex-turn-metadata'];
  if (typeof raw !== 'string' || raw.length > 1024 * 1024) return undefined;
  const metadata = JSON.parse(raw);
  if (
    client.thread_id !== threadId ||
    metadata?.thread_id !== threadId ||
    client.turn_id !== first.codex_turn_id ||
    metadata.turn_id !== first.codex_turn_id ||
    metadata.request_kind !== 'turn'
  )
    return undefined;
  if (
    metadata.turn_trigger === 'composer_predictions' &&
    ['composer_predictions', 'thread_sample'].includes(metadata.thread_source)
  )
    return metadata.thread_source;
  if (
    metadata.prediction_source_thread_id !== undefined ||
    metadata.prediction_source_turn_id !== undefined ||
    metadata.turn_trigger === 'composer_predictions' ||
    (metadata.thread_source !== undefined && !ordinarySources.includes(metadata.thread_source))
  )
    return undefined;
  if (!Array.isArray(request.input)) return undefined;
  const user = request.input.filter((value: { role?: string }) => value?.role === 'user').at(-1);
  const texts =
    typeof user?.content === 'string'
      ? [user.content]
      : Array.isArray(user?.content)
        ? user.content
            .filter(
              (item: { type?: string; text?: unknown }) =>
                item?.type === 'input_text' && typeof item.text === 'string',
            )
            .map((item: { text: string }) => item.text)
        : [];
  if (
    !texts.length ||
    texts.some((text: string) =>
      text
        .trimStart()
        .startsWith('This is an app-generated request for a suggested next user message.'),
    )
  )
    return undefined;
  return 'user';
}
interface FileProof {
  name: string;
  size: number;
  mtimeMs: number;
  dev: number;
  ino: number;
  sha256?: string;
  id: string;
  mode: number;
  windowsIdentity?: string;
}
function originalWindowsTrace(path: string, root?: string): root is string {
  return (
    process.platform === 'win32' &&
    !!root &&
    bundleName.test(relative(root, path).split(sep)[0] ?? '')
  );
}
function regular(path: string, root?: string): FileProof {
  // R5 upstream Trace objects retain their separate trusted-owner boundary.
  // A positive budget selects the file operation; zero selects a directory.
  const native = originalWindowsTrace(path, root) ? windowsTraceSnapshot(root, path, 1) : undefined;
  if (!native) assertPrivateFile(path);
  const s = lstatSync(path, { bigint: true });
  if (!s.isFile() || s.isSymbolicLink() || s.nlink !== 1n) throw new Error('UNSAFE_TRACE_FILE');
  if (process.platform !== 'win32' && (s.mode & 0o7000n) !== 0n)
    throw new Error('UNSAFE_TRACE_FILE');
  if (root && s.dev !== lstatSync(root, { bigint: true }).dev)
    throw new Error('TRACE_MOUNT_BOUNDARY');
  return {
    name: path,
    size: Number(s.size),
    mtimeMs: Number(s.mtimeNs) / 1e6,
    dev: Number(s.dev),
    ino: Number(s.ino),
    id: `${s.dev}:${s.ino}`,
    mode: Number(s.mode),
    ...(native ? { windowsIdentity: native.identity } : {}),
  };
}
function bounded(path: string, maximum: number, root?: string): Buffer {
  const before = regular(path, root);
  if (before.size > maximum) throw new Error('TRACE_READ_BUDGET');
  const bytes = originalWindowsTrace(path, root)
      ? windowsTraceSnapshot(root, path, maximum).bytes
      : readFileSync(path),
    after = regular(path, root);
  if (JSON.stringify(before) !== JSON.stringify(after)) throw new Error('TRACE_CHANGED');
  return bytes;
}
function privateDirectory(path: string, root = path): void {
  const s = lstatSync(path);
  if (
    !s.isDirectory() ||
    s.isSymbolicLink() ||
    realpathSync(path) !== path ||
    (process.platform !== 'win32' &&
      ((s.mode & 0o7000) !== 0 ||
        (s.mode & (path === root ? 0o077 : 0o022)) !== 0 ||
        s.uid !== process.getuid?.()))
  )
    throw new Error('UNSAFE_TRACE_DIRECTORY');
  if (s.dev !== lstatSync(root).dev) throw new Error('TRACE_MOUNT_BOUNDARY');
  if (process.platform === 'win32') windowsTraceSnapshot(root, path, 0);
}
function tail(path: string, maximum: number, root: string): Buffer {
  if (originalWindowsTrace(path, root)) {
    const bytes = bounded(path, 8 * 1024 * 1024, root);
    return bytes.subarray(Math.max(0, bytes.length - maximum));
  }
  const proof = regular(path, root),
    fd = openSync(path, 'r');
  try {
    const bytes = Buffer.alloc(Math.min(maximum, proof.size));
    readSync(fd, bytes, 0, bytes.length, proof.size - bytes.length);
    if (JSON.stringify(proof) !== JSON.stringify(regular(path, root)))
      throw new Error('TRACE_CHANGED');
    return bytes;
  } finally {
    closeSync(fd);
  }
}
function finalEvent(
  path: string,
  root: string,
):
  | {
      schema_version: number;
      rollout_id: string;
      wall_time_unix_ms: number;
      payload: { type: string; status: string };
    }
  | undefined {
  const bytes = tail(path, 65536, root);
  if (!bytes.length || bytes.at(-1) !== 10) return undefined;
  const lines = bytes.toString('utf8').trimEnd().split('\n');
  return JSON.parse(lines.at(-1)!);
}
interface DirectoryProof {
  id: string;
  mode: number;
  uid: number;
  gid: number;
}
interface DirectoryProofs {
  root: DirectoryProof;
  bundle: DirectoryProof;
  payloads: DirectoryProof;
}
interface TraceInventory {
  files: FileProof[];
  directories: DirectoryProofs;
}
function directories(directory: string, root: string): DirectoryProofs {
  const proof = (path: string): DirectoryProof => {
    privateDirectory(path, root);
    const value = lstatSync(path, { bigint: true });
    return {
      id: `${value.dev}:${value.ino}`,
      mode: Number(value.mode),
      uid: Number(value.uid),
      gid: Number(value.gid),
    };
  };
  return {
    root: proof(root),
    bundle: proof(directory),
    payloads: proof(join(directory, 'payloads')),
  };
}
function sameDirectories(directory: string, root: string, expected: DirectoryProofs): boolean {
  try {
    return JSON.stringify(directories(directory, root)) === JSON.stringify(expected);
  } catch {
    return false;
  }
}
function confirmedNotStarted(error: unknown): boolean {
  const e = error as {
    code?: unknown;
    stdout?: string;
    killed?: boolean;
    signal?: unknown;
    syscall?: unknown;
    spawnStarted?: unknown;
  };
  return (
    !e.killed &&
    !e.signal &&
    ((e.code === 2 && e.stdout?.trim() === '{"state":"not-started"}') ||
      (['WINDOWS_PROCESS_GATE_UNAVAILABLE', 'ETIMEDOUT'].includes(String(e.code)) &&
        e.syscall === 'windows-process-creation' &&
        e.spawnStarted === false))
  );
}
async function inventory(
  directory: string,
  root: string,
  signal?: AbortSignal,
  expected?: DirectoryProofs,
): Promise<TraceInventory> {
  const original = directories(directory, root);
  const verify = () => {
    if (
      !sameDirectories(directory, root, original) ||
      (expected && JSON.stringify(original) !== JSON.stringify(expected))
    )
      throw new Error('TRACE_DIRECTORY_CHANGED');
  };
  verify();
  const names = readdirSync(directory).sort();
  if (JSON.stringify(names) !== JSON.stringify(['manifest.json', 'payloads', 'trace.jsonl']))
    throw new Error('UNKNOWN_TRACE_CONTENT');
  privateDirectory(join(directory, 'payloads'), root);
  const payloads = readdirSync(join(directory, 'payloads')).sort();
  if (payloads.length > 200000 || payloads.some((n) => !/^\d+\.json$/.test(n)))
    throw new Error('UNKNOWN_TRACE_PAYLOAD');
  const files: FileProof[] = [],
    bytes = Buffer.alloc(65536);
  for (const name of ['manifest.json', 'trace.jsonl', ...payloads.map((n) => 'payloads/' + n)]) {
    if (signal?.aborted) throw new Error('TRACE_RETENTION_CLOSED');
    verify();
    const path = join(directory, name),
      before = regular(path, root),
      digest = createHash('sha256');
    if ((before.mode & 0o200) === 0) throw new Error('TRACE_WRITE_PROTECTED');
    const file = await openAsync(path, 'r');
    try {
      verify();
      const identity = await file.stat({ bigint: true });
      if (`${identity.dev}:${identity.ino}` !== before.id) throw new Error('TRACE_CHANGED');
      let position = 0;
      for (;;) {
        if (signal?.aborted) throw new Error('TRACE_RETENTION_CLOSED');
        const { bytesRead } = await file.read(bytes, 0, bytes.length, position);
        verify();
        if (!bytesRead) break;
        position += bytesRead;
        digest.update(bytes.subarray(0, bytesRead));
      }
      if (position !== before.size) throw new Error('TRACE_CHANGED');
    } finally {
      await file.close();
    }
    verify();
    if (JSON.stringify(before) !== JSON.stringify(regular(path, root)))
      throw new Error('TRACE_CHANGED');
    files.push({ ...before, name, sha256: digest.digest('hex') });
  }
  verify();
  return { files, directories: original };
}
async function closedMac(directory: string, signal?: AbortSignal): Promise<boolean> {
  try {
    await execute('/usr/sbin/lsof', ['-t', '+D', directory], {
      timeout: 15000,
      maxBuffer: 65536,
      ...(signal ? { signal } : {}),
    });
    return false;
  } catch (error) {
    const e = error as { code?: unknown; stdout?: string; stderr?: string; killed?: boolean };
    return e.code === 1 && !e.killed && !e.stdout?.trim() && !e.stderr?.trim();
  }
}

/** Runs off the Host request thread. No cleanup decision depends on elapsed age alone. */
export class DesktopTraceRetention {
  private readonly store: DesktopSuggestionStore;
  private readonly reader: DesktopPredictionTraceReader;
  private readonly rootIdentity: string;
  private readonly intentDigests = new Map<string, string>();
  constructor(
    private readonly root: string,
    private readonly targetKey: string,
    private readonly signal?: AbortSignal,
  ) {
    privateDirectory(root);
    const s = lstatSync(root, { bigint: true });
    this.rootIdentity = `${s.dev}:${s.ino}`;
    this.store = new DesktopSuggestionStore(root, targetKey);
    this.store.prepare();
    const ownerPath = join(root, '.agent-hub-retention-owner.json');
    const owner = JSON.stringify({ version: 1, targetKey, rootIdentity: this.rootIdentity });
    if (!existsSync(ownerPath)) {
      try {
        const fd = openPrivateFileSync(ownerPath, 'wx', 0o600);
        try {
          writeFileSync(fd, owner);
          protectWindowsStateFile(ownerPath);
          fsyncSync(fd);
        } finally {
          closeSync(fd);
        }
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
      }
    }
    if (bounded(ownerPath, 16384, this.root).toString('utf8') !== owner)
      throw new Error('TRACE_OWNED_BY_OTHER_RUNTIME');
    this.reader = new DesktopPredictionTraceReader(root, () => targetKey);
  }
  private rootCurrent(): void {
    if (this.signal?.aborted) throw new Error('TRACE_RETENTION_CLOSED');
    privateDirectory(this.root);
    const s = lstatSync(this.root, { bigint: true });
    if (`${s.dev}:${s.ino}` !== this.rootIdentity) throw new Error('TRACE_ROOT_CHANGED');
  }
  private retirementPath(name: string): string {
    return join(this.store.directory, 'retire-' + sha(Buffer.from(name)) + '.json');
  }
  private retirementIntent(
    name: string,
    files: FileProof[],
    retiredName?: string,
    proofs?: DirectoryProofs,
  ): string {
    const path = this.retirementPath(name);
    if (existsSync(path)) throw new Error('RETIREMENT_OUTCOME_REQUIRES_RECONCILIATION');
    if (
      readdirSync(this.store.directory).filter((n) => /^retire-[a-f0-9-]+\.json$/.test(n)).length >=
      64
    )
      throw new Error('PENDING_RETIREMENTS_REQUIRE_RECONCILIATION');
    const bytes = JSON.stringify({
      version: 1,
      root: this.root,
      name,
      files,
      ...(retiredName ? { retiredName, directories: proofs } : {}),
    });
    if (Buffer.byteLength(bytes) > 64 * 1024 * 1024) throw new Error('RETIREMENT_RECEIPT_BUDGET');
    const file = openPrivateFileSync(path, 'wx', 0o600);
    try {
      writeFileSync(file, bytes);
      protectWindowsStateFile(path);
      fsyncSync(file);
    } finally {
      closeSync(file);
    }
    if (process.platform !== 'win32') {
      const directory = openSync(this.store.directory, 'r');
      try {
        fsyncSync(directory);
      } finally {
        closeSync(directory);
      }
    }
    if (bounded(path, 64 * 1024 * 1024, this.root).toString('utf8') !== bytes)
      throw new Error('RETIREMENT_RECEIPT_READBACK');
    this.intentDigests.set(path, sha(Buffer.from(bytes)));
    return path;
  }
  private clearIntent(path: string): void {
    if (this.intentDigests.get(path) !== sha(bounded(path, 64 * 1024 * 1024, this.root)))
      throw new Error('RETIREMENT_INTENT_CHANGED');
    unlinkSync(path);
    this.intentDigests.delete(path);
  }
  async process(name: string, now = Date.now()): Promise<{ state: string; bytes: number }> {
    this.rootCurrent();
    const match = bundleName.exec(name);
    if (!match) throw new Error('EXACT_TRACE_BUNDLE_REQUIRED');
    if (existsSync(this.retirementPath(name))) return { state: 'pending-retirement', bytes: 0 };
    const directory = join(this.root, name);
    const originalDirectories = directories(directory, this.root);
    privateDirectory(directory, this.root);
    privateDirectory(join(directory, 'payloads'), this.root);
    const manifestBytes = bounded(join(directory, 'manifest.json'), 16384, this.root);
    const manifest = JSON.parse(manifestBytes.toString('utf8'));
    if (
      ![1, 2].includes(manifest.schema_version) ||
      manifest.trace_id !== match[1] ||
      manifest.rollout_id !== match[2] ||
      manifest.root_thread_id !== match[2] ||
      manifest.raw_event_log !== 'trace.jsonl' ||
      manifest.payloads_dir !== 'payloads' ||
      !Number.isSafeInteger(manifest.started_at_unix_ms) ||
      manifest.started_at_unix_ms < 0 ||
      manifest.started_at_unix_ms > now
    )
      return { state: 'unsupported', bytes: 0 };
    const logPath = join(directory, 'trace.jsonl'),
      proof = regular(logPath, this.root),
      end = finalEvent(logPath, this.root);
    if (
      !end ||
      end.schema_version !== manifest.schema_version ||
      end.rollout_id !== match[2] ||
      end.payload?.type !== 'rollout_ended' ||
      end.payload?.status !== 'completed' ||
      !Number.isSafeInteger(end.wall_time_unix_ms) ||
      end.wall_time_unix_ms > now ||
      now - proof.mtimeMs < 5000
    )
      return { state: 'active-or-unsealed', bytes: 0 };
    if (proof.size > 8 * 1024 * 1024) {
      // Never expire an oversized prediction whose only body has not been saved.
      // Large ordinary traces can be classified from the original bounded header.
      const fd = openSync(logPath, 'r');
      const header = Buffer.alloc(Math.min(16384, proof.size));
      try {
        if (readSync(fd, header, 0, header.length, 0) !== header.length)
          throw new Error('TRACE_CHANGED');
      } finally {
        closeSync(fd);
      }
      if (JSON.stringify(proof) !== JSON.stringify(regular(logPath, this.root)))
        throw new Error('TRACE_CHANGED');
      const rows = header
        .toString('utf8')
        .split('\n')
        .slice(0, -1)
        .map((s) => JSON.parse(s));
      const configuredEvent = rows.find(
        (e) =>
          e.schema_version === manifest.schema_version &&
          e.rollout_id === match[2] &&
          e.payload?.type === 'protocol_event_observed' &&
          e.payload.event_type === 'session_configured',
      );
      const path = configuredEvent?.payload.event_payload?.path;
      if (typeof path !== 'string' || !/^payloads\/\d+\.json$/.test(path))
        return { state: 'large-trace-source-unverified', bytes: 0 };
      const configured = JSON.parse(
        bounded(join(directory, path), 128 * 1024, this.root).toString('utf8'),
      );
      const source =
        configured.thread_source ?? legacySource(directory, this.root, rows, match[2]!);
      if (
        configured.type !== 'session_configured' ||
        configured.thread_id !== match[2] ||
        !ordinarySources.includes(source)
      )
        return { state: 'large-prediction-or-source-unverified', bytes: 0 };
    }
    let compacted = false;
    let diagnostic = proof.size > 8 * 1024 * 1024;
    if (proof.size <= 8 * 1024 * 1024) {
      const log = bounded(logPath, 8 * 1024 * 1024, this.root),
        events = log
          .toString('utf8')
          .trimEnd()
          .split('\n')
          .map((s) => JSON.parse(s));
      if (
        events.some(
          (e, i) =>
            e.schema_version !== manifest.schema_version ||
            e.rollout_id !== match[2] ||
            e.seq !== i + 1,
        )
      )
        return { state: 'malformed', bytes: 0 };
      const configuration = events.find(
        (e) =>
          e.payload?.type === 'protocol_event_observed' &&
          e.payload.event_type === 'session_configured',
      );
      const configurationPath = configuration?.payload.event_payload?.path;
      let source: string | undefined;
      let configuredSource: { forked_from_id?: string } | undefined;
      if (
        typeof configurationPath === 'string' &&
        /^payloads\/\d+\.json$/.test(configurationPath)
      ) {
        const configured = JSON.parse(
          bounded(join(directory, configurationPath), 128 * 1024, this.root).toString('utf8'),
        );
        configuredSource = configured;
        if (
          configured.type === 'session_configured' &&
          configured.thread_id === match[2] &&
          typeof configured.thread_source === 'string'
        )
          source = configured.thread_source;
      }
      source ??= legacySource(directory, this.root, events, match[2]!);
      if (
        !source ||
        ![...ordinarySources, 'composer_predictions', 'thread_sample'].includes(source)
      )
        return { state: 'source-unverified', bytes: 0 };
      const predictionBundle = source === 'composer_predictions' || source === 'thread_sample';
      if (source && ordinarySources.includes(source)) {
        // These successful execution traces contain no next-user prediction.
        // Their large duplicated request histories need not wait a whole day.
        compacted = true;
        diagnostic = events.some(
          (e) => e.payload?.type === 'codex_turn_ended' && e.payload.status !== 'completed',
        );
      }
      const starts = events.filter((e) => e.payload?.type === 'inference_started');
      for (const event of starts) {
        if (!predictionBundle) continue;
        const ref = event.payload.request_payload;
        if (typeof ref?.path !== 'string' || !/^payloads\/\d+\.json$/.test(ref.path)) continue;
        const request = JSON.parse(
          bounded(join(directory, ref.path), 32 * 1024 * 1024, this.root).toString('utf8'),
        );
        const metadata = JSON.parse(request.client_metadata?.['x-codex-turn-metadata'] ?? '{}');
        if (metadata.thread_source !== source || metadata.turn_trigger !== 'composer_predictions') {
          if (
            source === 'composer_predictions' ||
            metadata.turn_trigger === 'composer_predictions' ||
            metadata.prediction_source_thread_id !== undefined ||
            metadata.prediction_source_turn_id !== undefined
          )
            return { state: 'prediction-unverified', bytes: 0 };
          continue;
        }
        const binding = this.reader.readBinding(match[2]!, event.codex_turn_id);
        if (!binding || configuredSource?.forked_from_id !== binding.sourceThreadId)
          return { state: 'prediction-unverified', bytes: 0 };
        const ends = events.filter(
          (e) => e.payload?.type === 'codex_turn_ended' && e.codex_turn_id === event.codex_turn_id,
        );
        if (ends.length !== 1) return { state: 'prediction-unsettled', bytes: 0 };
        const status = ends[0].payload.status;
        const completed = this.reader.readCompletedPredictionForRetention(
          match[2]!,
          event.codex_turn_id,
        );
        const prediction = completed?.text.trim() ? completed : null;
        let state: CompactDesktopSuggestion['state'];
        if (prediction) state = 'completed';
        else if (completed) state = 'empty';
        else if (status === 'cancelled') state = 'cancelled';
        else if (status === 'failed') state = 'failed';
        else if (status === 'completed') state = 'unverified';
        else return { state: 'prediction-unverified', bytes: 0 };
        const requestedAt = Math.min(
          ...starts
            .filter((start) => start.codex_turn_id === event.codex_turn_id)
            .map((start) => Number(start.wall_time_unix_ms)),
        );
        const runtimeSaved = this.store.acknowledgeRuntimeResult(
          binding.sourceThreadId,
          binding.sourceTurnId,
          requestedAt,
          Number(ends[0].wall_time_unix_ms),
        );
        const saved =
          runtimeSaved ||
          this.store.save({
            version: 1,
            targetKey: this.targetKey,
            sourceThreadId: binding.sourceThreadId,
            sourceTurnId: binding.sourceTurnId,
            predictionThreadId: binding.predictionThreadId,
            predictionTurnId: binding.predictionTurnId,
            requestEvidence: binding.evidence,
            requestedAt: Math.min(
              ...starts
                .filter((start) => start.codex_turn_id === event.codex_turn_id)
                .map((start) => Number(start.wall_time_unix_ms)),
            ),
            recordedAt: now,
            prediction,
            state,
          });
        // An older record is superseded only by a durable newer entry, verified by save().
        if (!saved) return { state: 'superseded-or-conflicted', bytes: 0 };
        // Completed without a verifiable result is unknown, not a diagnostic
        // failure. Age alone must never discard its only possible evidence.
        if (state === 'unverified' && !runtimeSaved)
          return { state: 'prediction-unverified', bytes: 0 };
        compacted = true;
        if (state !== 'completed' && !runtimeSaved) diagnostic = true;
      }
      // A recognized prediction source with no verified inference must not
      // fall through to diagnostic expiry either.
      if (predictionBundle && !compacted) return { state: 'prediction-unverified', bytes: 0 };
    }
    // Confirmed failed/cancelled/empty results have a short diagnostic retention.
    // Successful extraction can retire immediately after the writer is sealed.
    if ((!compacted || diagnostic) && now - proof.mtimeMs < day)
      return { state: 'diagnostic-retention', bytes: 0 };
    const snapshot = await inventory(directory, this.root, this.signal, originalDirectories);
    const files = snapshot.files,
      bytes = files.reduce((sum, f) => sum + f.size, 0);
    this.rootCurrent();
    if (
      sha(bounded(join(directory, 'manifest.json'), 16384, this.root)) !== sha(manifestBytes) ||
      JSON.stringify(proof) !== JSON.stringify(regular(logPath, this.root))
    )
      return { state: 'changed', bytes: 0 };
    if (process.platform === 'darwin') {
      if (!(await closedMac(directory, this.signal))) return { state: 'occupied', bytes: 0 };
      this.rootCurrent();
      const retiredName = '.agent-hub-retired-' + randomUUID(),
        retired = join(this.root, retiredName);
      const receipt = this.retirementIntent(name, files, retiredName, snapshot.directories);
      let deletionStarted = false;
      try {
        renameSync(directory, retired);
        if (
          !(await closedMac(retired, this.signal)) ||
          JSON.stringify(await inventory(retired, this.root, this.signal, snapshot.directories)) !==
            JSON.stringify(snapshot)
        ) {
          if (!existsSync(directory) && sameDirectories(retired, this.root, snapshot.directories)) {
            renameSync(retired, directory);
            this.clearIntent(receipt);
          }
          return { state: 'occupied-or-changed', bytes: 0 };
        }
        const packaged = fileURLToPath(
          new URL('../resources/desktop-trace-retire.py', import.meta.url),
        );
        const helper = existsSync(packaged)
          ? packaged
          : fileURLToPath(new URL('../../../scripts/desktop-trace-retire.py', import.meta.url));
        if (!sameDirectories(retired, this.root, snapshot.directories))
          throw new Error('TRACE_DIRECTORY_CHANGED');
        deletionStarted = true;
        await execute(
          '/usr/bin/python3',
          [
            '-I',
            helper,
            '--receipt',
            receipt,
            '--root',
            this.root,
            '--digest',
            this.intentDigests.get(receipt)!,
          ],
          {
            timeout: 120000,
            maxBuffer: 65536,
            ...(this.signal ? { signal: this.signal } : {}),
          },
        );
        if (existsSync(retired)) throw new Error('RETIREMENT_NOT_CONFIRMED');
        this.clearIntent(receipt);
      } catch (error) {
        const unstarted = confirmedNotStarted(error);
        if (unstarted) deletionStarted = false;
        // A partial/unknown retirement keeps both its immutable file list and
        // quarantine. It must be reconciled, never blindly replayed or hidden.
        if (!deletionStarted && existsSync(retired) && !existsSync(directory)) {
          try {
            if (sameDirectories(retired, this.root, snapshot.directories)) {
              renameSync(retired, directory);
              this.clearIntent(receipt);
            }
          } catch {
            /* Preserve the quarantine and intent when restoration is unknown. */
          }
        }
        if (existsSync(receipt) && !existsSync(retired) && existsSync(directory)) {
          try {
            if (
              JSON.stringify(
                await inventory(directory, this.root, this.signal, snapshot.directories),
              ) === JSON.stringify(snapshot)
            )
              this.clearIntent(receipt);
          } catch {
            /* Unknown outcome: preserve the intent. */
          }
        }
        if (unstarted && !existsSync(receipt) && existsSync(directory))
          return { state: 'native-refused-before-deletion', bytes: 0 };
        throw error;
      }
    } else if (process.platform === 'win32') {
      const receipt = this.retirementIntent(name, files);
      let completed = false;
      try {
        const helper = fileURLToPath(
          new URL('../windows/desktop-trace-retire.ps1', import.meta.url),
        );
        await withWindowsProcessCreation(
          5000,
          () =>
            execute(
              'powershell.exe',
              [
                '-NoProfile',
                '-NonInteractive',
                '-File',
                helper,
                '-Receipt',
                receipt,
                '-Root',
                this.root,
                '-Digest',
                this.intentDigests.get(receipt)!,
              ],
              {
                timeout: 120000,
                maxBuffer: 65536,
                windowsHide: true,
                env: windowsPowerShellEnvironment(),
                ...(this.signal ? { signal: this.signal } : {}),
              },
            ),
          () => !this.signal?.aborted,
        );
        completed = !existsSync(directory);
      } catch (error) {
        // Only an explicit, verified no-deletion outcome permits a later fresh attempt.
        if (confirmedNotStarted(error)) {
          this.rootCurrent();
          if (existsSync(receipt)) this.clearIntent(receipt);
          return { state: 'native-refused-before-deletion', bytes: 0 };
        }
        throw error;
      } finally {
        if (completed && existsSync(receipt)) this.clearIntent(receipt);
      }
    } else return { state: 'unsupported-platform', bytes: 0 };
    return { state: existsSync(directory) ? 'retirement-incomplete' : 'retired', bytes };
  }
}
