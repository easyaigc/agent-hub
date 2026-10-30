import { createHash, randomUUID } from 'node:crypto';
import {
  closeSync,
  constants,
  fstatSync,
  fsyncSync,
  lstatSync,
  openSync,
  opendirSync,
  readSync,
  realpathSync,
  unlinkSync,
  writeFileSync,
  type BigIntStats,
} from 'node:fs';
import { homedir } from 'node:os';
import { dirname, isAbsolute, join, parse, resolve } from 'node:path';
import { TextDecoder } from 'node:util';
import { isMainThread } from 'node:worker_threads';
import {
  machineRuntimeTargetKey,
  parseNotificationTarget,
  type RuntimeTarget,
} from '@codex-manage/protocol';
import { DesktopNativeSampleCapture } from './desktop-native-sample-capture.js';
import { isOriginalNativeSampleFrame } from './desktop-native-sample-envelope.js';
import {
  inspectNativeSuggestionProducer,
  nativeSuggestionImplementationFingerprint,
} from './desktop-native-sample-platform.js';
import {
  DesktopSuggestionStore,
  type RuntimeSuggestionProducerState,
} from './desktop-suggestion-store.js';
import {
  assertPrivateFile,
  openPrivateFileSync,
  protectWindowsStateFile,
  windowsTraceSnapshot,
  windowsDesktopLogRangeSnapshot,
} from './desktop-platform.js';

const limits = Object.freeze({
  configBytes: 16 * 1024,
  ownerBytes: 4096,
  directories: 4,
  files: 16,
  filesPerTick: 8,
  bytesPerTick: 1024 * 1024,
  lineBytes: 8 * 1024,
  directoryEntries: 512,
  tickMs: 250,
  heartbeatMs: 2000,
  leaseMs: 6000,
});
const uuidSource = '[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}';
const uuid = new RegExp(`^${uuidSource}$`);
const safetyLine = new RegExp(
  `^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z) info \\[cumberbatch\\] ` +
    `cumberbatch_safety_decision sourceThreadId=(${uuidSource}) sourceTurnId=(${uuidSource}) suppressed=(true|false)$`,
);
const digest = (bytes: string | Buffer) => createHash('sha256').update(bytes).digest('hex');
const decoder = new TextDecoder('utf-8', { fatal: true });
type Identity = ReturnType<typeof inspectNativeSuggestionProducer>;
type Producer = RuntimeSuggestionProducerState['producer'];
type Code =
  | 'native_bridge_config_invalid'
  | 'native_bridge_platform_unavailable'
  | 'native_bridge_identity_unavailable'
  | 'native_bridge_owner_busy'
  | 'native_bridge_owner_unknown'
  | 'native_bridge_owner_changed'
  | 'native_bridge_root_changed'
  | 'native_bridge_config_changed'
  | 'native_bridge_log_unavailable'
  | 'native_bridge_log_changed'
  | 'native_bridge_log_truncated'
  | 'native_bridge_log_line_limit'
  | 'native_bridge_log_file_limit'
  | 'native_bridge_log_entry_limit'
  | 'native_bridge_windows_log_range_unavailable'
  | 'native_bridge_clock_invalid'
  | 'native_bridge_lease_expired'
  | 'native_bridge_phase_deadline'
  | 'native_bridge_capture_unavailable'
  | 'native_bridge_store_unavailable'
  | 'native_bridge_observation_failed'
  | 'native_bridge_close_failed'
  | 'native_bridge_sample_frame_invalid'
  | 'native_bridge_sample_frame_limit'
  | 'native_bridge_capture_notice';
const statusCodes: ReadonlySet<string> = new Set<Code>([
  'native_bridge_config_invalid',
  'native_bridge_platform_unavailable',
  'native_bridge_identity_unavailable',
  'native_bridge_owner_busy',
  'native_bridge_owner_unknown',
  'native_bridge_owner_changed',
  'native_bridge_root_changed',
  'native_bridge_config_changed',
  'native_bridge_log_unavailable',
  'native_bridge_log_changed',
  'native_bridge_log_truncated',
  'native_bridge_log_line_limit',
  'native_bridge_log_file_limit',
  'native_bridge_log_entry_limit',
  'native_bridge_windows_log_range_unavailable',
  'native_bridge_clock_invalid',
  'native_bridge_lease_expired',
  'native_bridge_phase_deadline',
  'native_bridge_capture_unavailable',
  'native_bridge_store_unavailable',
  'native_bridge_observation_failed',
  'native_bridge_close_failed',
  'native_bridge_sample_frame_invalid',
  'native_bridge_sample_frame_limit',
  'native_bridge_capture_notice',
]);

export interface DesktopNativeSampleBridge {
  readonly available: boolean;
  /** Complete original JSON lines, supplied beside (never instead of) forwarding. */
  observeOutbound(line: string): void;
  observeInbound(line: string): void;
  close(): void;
}

/** Internal sampler-isolate budget; never supplied by a remote command. */
export interface DesktopNativeSamplePhaseBudget {
  startupRemaining(): number;
  active(): boolean;
  begin(milliseconds: number): void;
  end(): void;
}

/** Paths describe the installed originals; native inspection verifies their provenance. */
export interface DesktopNativeSampleBridgeConfig {
  version: 1;
  target: RuntimeTarget;
  targetKey?: string;
  traceRoot: string;
  applicationPath: string;
  executable: string;
  node: string;
  entry: string;
  logDirectory: string;
  shimPath?: string;
  shimSha256?: string;
}
interface Owner {
  version: 1;
  kind: 'native-sample-owner';
  targetKey: string;
  producer: Producer;
  wrapperStartedAt: number;
  rootIdentity: string;
}
interface FileInfo {
  stat: BigIntStats;
  identity: string;
  stamp: string;
  chain: string;
  privateMode?: boolean;
}
interface Cursor {
  identity: string;
  offset: number;
  size: number;
  stamp: string;
  guard: string;
  tail: Buffer;
  discard: boolean;
}
interface Budget {
  remaining: number;
}
interface Hooks {
  identity?: typeof inspectNativeSuggestionProducer;
  clock?: () => number;
  /** Fixture-only substitute for the fixed macOS root; never accepted in config. */
  loggerPaths?: { root: string };
  status?: (code: string) => void;
  implementationSha256?: string;
  manual?: boolean;
  phaseBudget?: DesktopNativeSamplePhaseBudget;
}

function fail(code: Code): never {
  throw Object.assign(new Error(code), { bridgeCode: code });
}
function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function integer(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}
function validIdentity(value: Identity, at: number): boolean {
  return (
    record(value) &&
    integer(value.pid) &&
    value.pid > 1 &&
    integer(value.desktopPid) &&
    value.desktopPid > 1 &&
    value.pid !== value.desktopPid &&
    integer(value.desktopStartedAt) &&
    integer(value.wrapperStartedAt) &&
    value.wrapperStartedAt >= value.desktopStartedAt &&
    value.wrapperStartedAt <= at
  );
}
function sameIdentity(a: Identity, b: Identity): boolean {
  return (
    a.pid === b.pid &&
    a.desktopPid === b.desktopPid &&
    a.desktopStartedAt === b.desktopStartedAt &&
    a.wrapperStartedAt === b.wrapperStartedAt
  );
}
function sameProducer(a: Producer, b: Producer): boolean {
  return (
    a.epoch === b.epoch &&
    a.pid === b.pid &&
    a.desktopPid === b.desktopPid &&
    a.desktopStartedAt === b.desktopStartedAt
  );
}
function missing(error: unknown): boolean {
  return record(error) && error.code === 'ENOENT';
}
function fixedCode(error: unknown, fallback: Code): Code {
  return record(error) && typeof error.bridgeCode === 'string' && statusCodes.has(error.bridgeCode)
    ? (error.bridgeCode as Code)
    : fallback;
}
function json(bytes: Buffer): unknown {
  const text = decoder.decode(bytes),
    value: unknown = JSON.parse(text);
  // Reject duplicate keys, including escaped aliases; JSON.parse alone loses them.
  const stack: ({ keys: Set<string>; key: boolean } | null)[] = [];
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') {
      const start = i++;
      while (i < text.length) {
        if (text[i] === '\\') i += 2;
        else if (text[i] === '"') break;
        else i++;
      }
      const frame = stack[stack.length - 1];
      if (frame?.key) {
        const key: string = JSON.parse(text.slice(start, i + 1));
        if (frame.keys.has(key)) fail('native_bridge_config_invalid');
        frame.keys.add(key);
        frame.key = false;
      }
    } else if (c === '{' || c === '[') {
      if (stack.length >= 16) fail('native_bridge_config_invalid');
      stack.push(c === '{' ? { keys: new Set(), key: true } : null);
    } else if (c === '}' || c === ']') stack.pop();
    else if (c === ',') {
      const frame = stack[stack.length - 1];
      if (frame) frame.key = true;
    }
  }
  return value;
}
function shape(value: unknown, keys: string[]): value is Record<string, unknown> {
  return record(value) && Object.keys(value).every((key) => keys.includes(key));
}
function pathIdentity(s: BigIntStats): string {
  return [s.dev, s.ino, s.birthtimeNs, s.uid, s.gid, s.mode, s.isDirectory() ? 0n : s.nlink].join(
    ':',
  );
}
function stamp(s: BigIntStats): string {
  return [pathIdentity(s), s.size, s.mtimeNs, s.ctimeNs].join(':');
}

/** Fresh namespace and file checks. Windows privacy ALWAYS goes through native ACL checks. */
class LocalFiles {
  desktopPid = 0;
  canonical(path: string): string {
    if (
      typeof path !== 'string' ||
      !path ||
      path.includes('\0') ||
      !isAbsolute(path) ||
      resolve(path) !== path
    )
      fail('native_bridge_config_invalid');
    const actual = realpathSync.native(path);
    if (
      process.platform === 'win32' ? actual.toLowerCase() !== path.toLowerCase() : actual !== path
    )
      fail('native_bridge_config_invalid');
    const chain: string[] = [];
    for (let p = path; ; p = dirname(p)) {
      const s = lstatSync(p, { bigint: true });
      if (s.isSymbolicLink() || (p !== path && !s.isDirectory()))
        fail('native_bridge_config_invalid');
      chain.push(pathIdentity(s));
      if (p === parse(p).root) break;
    }
    return chain.reverse().join('|');
  }
  directory(path: string, root: string, privateMode = false): string {
    const chain = this.canonical(path),
      s = lstatSync(path, { bigint: true });
    if (!s.isDirectory()) fail('native_bridge_log_unavailable');
    if (process.platform === 'win32')
      return privateMode
        ? windowsTraceSnapshot(root, path, 0).identity
        : windowsDesktopLogRangeSnapshot(root, path, 0, 0, this.desktopPid).identity;
    if (
      s.uid !== BigInt(process.getuid!()) ||
      (s.mode & 0o022n) !== 0n ||
      (privateMode && (s.mode & 0o777n) !== 0o700n)
    )
      fail('native_bridge_log_unavailable');
    return chain;
  }
  info(path: string, root: string, privateMode = false): FileInfo {
    const chain = this.canonical(path),
      s = lstatSync(path, { bigint: true });
    if (!s.isFile() || s.nlink !== 1n || s.size > BigInt(Number.MAX_SAFE_INTEGER))
      fail('native_bridge_log_unavailable');
    if (process.platform === 'win32') {
      if (privateMode) {
        assertPrivateFile(path); // native same-SID private DACL
        return { stat: s, identity: '', stamp: stamp(s), chain, privateMode: true };
      }
      const snapshot = windowsDesktopLogRangeSnapshot(root, path, 0, 0, this.desktopPid),
        after = lstatSync(path, { bigint: true });
      if (pathIdentity(after) !== pathIdentity(s) || after.size < s.size)
        fail('native_bridge_log_changed');
      return {
        stat: after,
        identity: snapshot.identity,
        stamp: stamp(after),
        chain,
        privateMode: false,
      };
    }
    if (
      s.uid !== BigInt(process.getuid!()) ||
      (s.mode & 0o022n) !== 0n ||
      (privateMode && (s.mode & 0o777n) !== 0o600n)
    )
      fail('native_bridge_log_unavailable');
    return { stat: s, identity: pathIdentity(s), stamp: stamp(s), chain, privateMode };
  }
  read(
    path: string,
    root: string,
    info: FileInfo,
    offset: number,
    count: number,
    budget: Budget,
  ): Buffer {
    if (count < 0 || offset < 0 || offset + count > Number(info.stat.size))
      fail('native_bridge_log_changed');
    if (process.platform === 'win32') {
      if (!info.privateMode) {
        if (count > budget.remaining) fail('native_bridge_log_changed');
        const snapshot = windowsDesktopLogRangeSnapshot(root, path, offset, count, this.desktopPid);
        budget.remaining -= snapshot.bytes.length;
        if (
          snapshot.identity !== info.identity ||
          snapshot.bytes.length !== count ||
          this.canonical(path) !== info.chain
        )
          fail('native_bridge_log_changed');
        return snapshot.bytes;
      }
      // Existing helper has a prefix API, not a range API. Never substitute an
      // unchecked Node range read or silently exceed the per-tick byte budget.
      const prefix = Math.max(1, offset + count);
      if (prefix > limits.bytesPerTick) fail('native_bridge_windows_log_range_unavailable');
      if (prefix > budget.remaining) fail('native_bridge_windows_log_range_unavailable');
      const snapshot = windowsTraceSnapshot(root, path, prefix);
      budget.remaining -= snapshot.bytes.length;
      if (
        snapshot.bytes.length !== Math.min(prefix, Number(info.stat.size)) ||
        stamp(lstatSync(path, { bigint: true })) !== info.stamp ||
        this.canonical(path) !== info.chain
      )
        fail('native_bridge_log_changed');
      info.identity = snapshot.identity;
      return snapshot.bytes.subarray(offset, offset + count);
    }
    if (count > budget.remaining) fail('native_bridge_log_changed');
    const fd = openSync(
      path,
      constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0),
    );
    try {
      const stable = (s: BigIntStats) =>
        info.privateMode
          ? stamp(s) === info.stamp
          : pathIdentity(s) === pathIdentity(info.stat) &&
            s.size >= info.stat.size &&
            (s.size > info.stat.size || stamp(s) === info.stamp);
      if (!stable(fstatSync(fd, { bigint: true }))) fail('native_bridge_log_changed');
      const bytes = Buffer.alloc(count);
      let read = 0;
      while (read < count) {
        const n = readSync(fd, bytes, read, count - read, offset + read);
        if (!n) fail('native_bridge_log_changed');
        read += n;
      }
      budget.remaining -= read;
      if (
        !stable(fstatSync(fd, { bigint: true })) ||
        !stable(lstatSync(path, { bigint: true })) ||
        this.canonical(path) !== info.chain
      )
        fail('native_bridge_log_changed');
      return bytes;
    } finally {
      closeSync(fd);
    }
  }
  privateBytes(path: string, maximum: number): { bytes: Buffer; info: FileInfo } {
    const root = dirname(path);
    this.directory(root, root, true);
    const info = this.info(path, root, true);
    if (info.stat.size > BigInt(maximum)) fail('native_bridge_config_invalid');
    const bytes = this.read(path, root, info, 0, Number(info.stat.size), { remaining: maximum });
    return { bytes, info };
  }
}

function configuration(
  path: string,
  files: LocalFiles,
  hooks: Hooks,
): { config: DesktopNativeSampleBridgeConfig; hash: string } {
  const { bytes } = files.privateBytes(path, limits.configBytes),
    value = json(bytes);
  if (
    !shape(value, [
      'version',
      'target',
      'targetKey',
      'traceRoot',
      'applicationPath',
      'executable',
      'node',
      'entry',
      'logDirectory',
      'shimPath',
      'shimSha256',
    ]) ||
    value.version !== 1
  )
    fail('native_bridge_config_invalid');
  const target = parseNotificationTarget(value.target);
  if (
    target.agentId !== 'codex' ||
    (value.targetKey !== undefined && value.targetKey !== machineRuntimeTargetKey(target))
  )
    fail('native_bridge_config_invalid');
  for (const key of [
    'traceRoot',
    'applicationPath',
    'executable',
    'node',
    'entry',
    'logDirectory',
  ] as const) {
    if (typeof value[key] !== 'string') fail('native_bridge_config_invalid');
    if (process.platform === 'win32' && (key === 'applicationPath' || key === 'executable')) {
      // Store-version paths are historical hints. Native inspection binds the
      // current signed parent and CLI; an old missing path is not a new identity.
      const path = value[key] as string;
      if (
        !/^[a-z]:\\/i.test(path) ||
        path.slice(2).includes(':') ||
        path.includes('\0') ||
        resolve(path) !== path
      )
        fail('native_bridge_config_invalid');
    } else files.canonical(value[key]);
  }
  if (
    (value.shimPath === undefined) !== (value.shimSha256 === undefined) ||
    (value.shimSha256 !== undefined &&
      (typeof value.shimSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.shimSha256)))
  )
    fail('native_bridge_config_invalid');
  if (value.shimPath !== undefined) {
    if (typeof value.shimPath !== 'string') fail('native_bridge_config_invalid');
    files.canonical(value.shimPath);
    const info = files.info(value.shimPath, dirname(value.shimPath), true);
    if (info.stat.size > BigInt(limits.bytesPerTick)) fail('native_bridge_config_invalid');
    const bytes = files.read(
      value.shimPath,
      dirname(value.shimPath),
      info,
      0,
      Number(info.stat.size),
      { remaining: limits.bytesPerTick },
    );
    if (digest(bytes) !== value.shimSha256) fail('native_bridge_config_invalid');
  }
  const config = { ...value, target } as unknown as DesktopNativeSampleBridgeConfig;
  for (const key of [
    'executable',
    'node',
    'entry',
    ...(config.shimPath ? (['shimPath'] as const) : []),
  ] as const) {
    const path = config[key];
    if (
      !path ||
      ((process.platform !== 'win32' || key !== 'executable') && !lstatSync(path).isFile())
    )
      fail('native_bridge_config_invalid');
  }
  files.directory(config.traceRoot, config.traceRoot, true);
  if (process.platform !== 'win32') files.directory(config.logDirectory, config.logDirectory);
  if (process.platform === 'darwin' || hooks.loggerPaths) {
    const root = hooks.loggerPaths?.root ?? join(homedir(), 'Library', 'Logs', 'com.openai.codex');
    if (config.logDirectory !== root) fail('native_bridge_config_invalid');
  }
  return { config, hash: digest(bytes) };
}

function logDirectories(root: string, at: number): string[] {
  const result = new Set<string>();
  for (const utc of [true, false]) {
    const today = new Date(at);
    for (let day = 0; day < 2; day++) {
      const d = new Date(today);
      if (utc) d.setUTCDate(d.getUTCDate() - day);
      else d.setDate(d.getDate() - day);
      const year = utc ? d.getUTCFullYear() : d.getFullYear();
      const month = (utc ? d.getUTCMonth() : d.getMonth()) + 1;
      const date = utc ? d.getUTCDate() : d.getDate();
      result.add(
        join(
          root,
          String(year).padStart(4, '0'),
          String(month).padStart(2, '0'),
          String(date).padStart(2, '0'),
        ),
      );
    }
  }
  return [...result];
}
function validOwner(value: unknown, key: string, root: string, at: number): value is Owner {
  if (
    !shape(value, [
      'version',
      'kind',
      'targetKey',
      'producer',
      'wrapperStartedAt',
      'rootIdentity',
    ]) ||
    value.version !== 1 ||
    value.kind !== 'native-sample-owner' ||
    value.targetKey !== key ||
    value.rootIdentity !== root ||
    !shape(value.producer, ['epoch', 'pid', 'desktopPid', 'desktopStartedAt']) ||
    typeof value.producer.epoch !== 'string' ||
    !uuid.test(value.producer.epoch)
  )
    return false;
  return validIdentity(
    { ...value.producer, wrapperStartedAt: value.wrapperStartedAt } as Identity,
    at,
  );
}

class Bridge implements DesktopNativeSampleBridge {
  private readonly files = new LocalFiles();
  private readonly now: () => number;
  private readonly inspect: typeof inspectNativeSuggestionProducer;
  private readonly implementationSha256: string;
  private readonly codes = new Set<string>();
  private readonly cursors = new Map<string, Cursor>();
  private readonly directoryIdentities = new Map<string, string>();
  private config!: DesktopNativeSampleBridgeConfig;
  private configHash = '';
  private identity!: Identity;
  private producer!: Producer;
  private store!: DesktopSuggestionStore;
  private root = '';
  private logRoot = '';
  private ownerPath = '';
  private ownerBytes = '';
  private ownerIdentity = '';
  private capture: DesktopNativeSampleCapture | undefined;
  private timer: ReturnType<typeof setInterval> | undefined;
  private ready = false;
  private closed = false;
  private lastAt = 0;
  private heartbeatAt = 0;
  private producerCheckedAt = 0;
  private phaseDeadline = Infinity;
  private phaseLeaseDeadline = Infinity;
  private roundRobin = 0;

  constructor(
    private readonly configPath: string,
    private readonly hooks: Hooks,
  ) {
    this.now = hooks.clock ?? Date.now;
    this.inspect = hooks.identity ?? inspectNativeSuggestionProducer;
    // Bind loaded code once, never the changing config or files at heartbeat time.
    this.implementationSha256 =
      hooks.implementationSha256 ?? nativeSuggestionImplementationFingerprint();
    if (!/^[a-f0-9]{64}$/.test(this.implementationSha256)) fail('native_bridge_config_invalid');
  }
  report(code: Code): void {
    if (this.codes.has(code)) return;
    this.codes.add(code);
    try {
      if (this.hooks.status) this.hooks.status(code);
      else process.stderr.write(`${code}\n`);
    } catch {
      /* Neither logger exceptions nor exception contents cross the tap. */
    }
  }
  get available(): boolean {
    return this.ready && !this.closed;
  }
  private assertActive(): void {
    if (this.hooks.phaseBudget && !this.hooks.phaseBudget.active())
      fail('native_bridge_observation_failed');
  }
  private publicationGuard(): (() => void) | undefined {
    if (!this.hooks.phaseBudget) return undefined;
    return () => {
      this.assertActive();
      if (performance.now() >= this.phaseDeadline) fail('native_bridge_phase_deadline');
      if (this.time() >= this.phaseLeaseDeadline) fail('native_bridge_lease_expired');
    };
  }
  private phase<T>(startup: boolean, action: () => T): T {
    const budget = this.hooks.phaseBudget;
    if (!budget) return action();
    this.assertActive();
    const remaining = startup
      ? budget.startupRemaining()
      : this.producerCheckedAt + limits.leaseMs - this.time();
    if (!Number.isFinite(remaining) || remaining <= 0)
      fail(startup ? 'native_bridge_phase_deadline' : 'native_bridge_lease_expired');
    const deadline = performance.now() + remaining;
    this.phaseDeadline = deadline;
    this.phaseLeaseDeadline = startup ? Infinity : this.producerCheckedAt + limits.leaseMs;
    budget.begin(Math.max(1, Math.floor(remaining)));
    try {
      const value = action();
      this.assertActive();
      if (performance.now() >= deadline) fail('native_bridge_phase_deadline');
      if (this.ready && this.time() - this.producerCheckedAt >= limits.leaseMs)
        fail('native_bridge_lease_expired');
      return value;
    } finally {
      budget.end();
      this.phaseDeadline = Infinity;
      this.phaseLeaseDeadline = Infinity;
    }
  }
  start(): void {
    this.phase(true, () => this.initialize());
  }
  private initialize(): void {
    if (!this.hooks.identity && process.platform !== 'darwin' && process.platform !== 'win32')
      fail('native_bridge_platform_unavailable');
    const checked = configuration(this.configPath, this.files, this.hooks);
    this.config = checked.config;
    this.configHash = checked.hash;
    const at = this.time();
    try {
      this.identity = this.inspect(process.pid, this.configPath);
    } catch {
      fail('native_bridge_identity_unavailable');
    }
    if (!validIdentity(this.identity, at) || this.identity.pid !== process.pid)
      fail('native_bridge_identity_unavailable');
    this.files.desktopPid = this.identity.desktopPid;
    this.producer = {
      epoch: randomUUID(),
      pid: this.identity.pid,
      desktopPid: this.identity.desktopPid,
      desktopStartedAt: this.identity.desktopStartedAt,
    };
    this.store = new DesktopSuggestionStore(
      this.config.traceRoot,
      machineRuntimeTargetKey(this.config.target),
    );
    this.store.prepare();
    this.root = this.store.rootIdentity();
    this.logRoot = this.files.directory(this.config.logDirectory, this.config.logDirectory);
    this.ownerPath = join(this.store.directory, 'producer-owner.json');
    this.ownerBytes =
      JSON.stringify({
        version: 1,
        kind: 'native-sample-owner',
        targetKey: this.store.targetKey,
        producer: this.producer,
        wrapperStartedAt: this.identity.wrapperStartedAt,
        rootIdentity: this.root,
      } satisfies Owner) + '\n';
    this.acquire(at);
    // No model frame is accepted until every existing current-PID log is at EOF.
    const budget = { remaining: limits.bytesPerTick };
    for (const path of this.scan(at)) this.seed(path, budget);
    const fencedStore = new Proxy(this.store, {
      get: (target, property) => {
        if (property === 'saveRuntime')
          return (value: Parameters<DesktopSuggestionStore['saveRuntime']>[0]) => {
            this.fence(this.time());
            return target.saveRuntime(value, this.publicationGuard());
          };
        if (property === 'readRuntime')
          return (thread: string) => {
            this.fence(this.time());
            return target.readRuntime(thread);
          };
        return Reflect.get(target, property, target);
      },
    });
    this.capture = new DesktopNativeSampleCapture({
      store: fencedStore,
      targetKey: this.store.targetKey,
      producer: this.producer,
      now: this.now,
      onError: (code) =>
        this.report(
          code === 'native_sample_frame_limit'
            ? 'native_bridge_sample_frame_limit'
            : code === 'native_sample_frame_invalid'
              ? 'native_bridge_sample_frame_invalid'
              : 'native_bridge_capture_notice',
        ),
    });
    if (!this.capture.available) fail('native_bridge_capture_unavailable');
    this.checkOwner();
    this.writeProducer('listening', this.time());
    this.heartbeatAt = this.lastAt;
    this.ready = true;
    if (!this.hooks.manual) {
      this.timer = setInterval(() => this.tick(), limits.tickMs);
      this.timer.unref?.();
    }
  }
  observeOutbound(line: string): void {
    this.observe('observeOutbound', line);
  }
  observeInbound(line: string): void {
    this.observe('observeInbound', line);
  }
  private observe(method: 'observeOutbound' | 'observeInbound', line: string): void {
    if (!this.ready || this.closed) return;
    // A large unrelated history can quote native method names or request IDs.
    // Only its bounded top-level envelope may select the capture boundary.
    if (!isOriginalNativeSampleFrame(line, method)) return;
    try {
      this.phase(false, () => {
        this.fence(this.time());
        if (method === 'observeOutbound' && !this.capture!.hasPendingRequests) {
          const budget = { remaining: limits.bytesPerTick };
          for (const path of this.scan(this.time())) this.seed(path, budget);
        }
        this.capture![method](line);
        if (!this.capture!.available) fail('native_bridge_capture_unavailable');
      });
    } catch (error) {
      this.stop(fixedCode(error, 'native_bridge_observation_failed'));
    }
  }
  private time(): number {
    const at = this.now();
    if (!integer(at) || !Number.isFinite(new Date(at).getTime()) || at < this.lastAt)
      fail('native_bridge_clock_invalid');
    this.lastAt = at;
    return at;
  }
  private checkOwner(): void {
    if (!this.ownerIdentity) fail('native_bridge_owner_changed');
    const { bytes, info } = this.files.privateBytes(this.ownerPath, limits.ownerBytes);
    if (bytes.toString('utf8') !== this.ownerBytes || info.identity !== this.ownerIdentity)
      fail('native_bridge_owner_changed');
    if (this.store.rootIdentity() !== this.root) fail('native_bridge_root_changed');
  }
  private fence(at: number): void {
    this.assertActive();
    this.checkOwner();
    const state = this.store.readProducer();
    if (
      !state ||
      !sameProducer(state.producer, this.producer) ||
      state.wrapperStartedAt !== this.identity.wrapperStartedAt ||
      state.rootIdentity !== this.root ||
      state.implementationSha256 !== this.implementationSha256 ||
      state.phase !== 'listening'
    )
      fail('native_bridge_owner_changed');
    if (state.checkedAt > at || at - state.checkedAt >= limits.leaseMs)
      fail('native_bridge_lease_expired');
    this.assertActive();
    if (this.hooks.phaseBudget && this.time() - state.checkedAt >= limits.leaseMs)
      fail('native_bridge_lease_expired');
  }
  private writeProducer(phase: RuntimeSuggestionProducerState['phase'], at: number): void {
    this.checkOwner();
    if (phase === 'listening') this.assertActive();
    this.store.saveProducer(
      {
        version: 1,
        kind: 'native-sample-producer',
        targetKey: this.store.targetKey,
        producer: { ...this.producer },
        wrapperStartedAt: this.identity.wrapperStartedAt,
        rootIdentity: this.root,
        implementationSha256: this.implementationSha256,
        checkedAt: at,
        phase,
      },
      phase === 'listening' ? this.publicationGuard() : undefined,
    );
    if (phase === 'listening') {
      this.assertActive();
      this.producerCheckedAt = at;
    }
  }
  private acquire(at: number): void {
    let existing: ReturnType<LocalFiles['privateBytes']> | undefined;
    try {
      existing = this.files.privateBytes(this.ownerPath, limits.ownerBytes);
    } catch (error) {
      if (!missing(error)) fail('native_bridge_owner_unknown');
    }
    if (existing) {
      const owner = json(existing.bytes);
      if (!validOwner(owner, this.store.targetKey, this.root, at))
        fail('native_bridge_owner_unknown');
      let reclaim = false;
      try {
        const actual = this.inspect(owner.producer.pid, this.configPath);
        if (!validIdentity(actual, at) || actual.pid !== owner.producer.pid)
          fail('native_bridge_owner_unknown');
        reclaim = actual.wrapperStartedAt !== owner.wrapperStartedAt;
      } catch {
        // The four-number inspector currently has NO positive-exit variant.
        // Even ESRCH on a failed helper launch is not evidence about this PID.
        // Until that contract exists, a missing/denied/uninspectable PID stays locked.
        fail('native_bridge_owner_unknown');
      }
      if (!reclaim) fail('native_bridge_owner_busy');
      const again = this.files.privateBytes(this.ownerPath, limits.ownerBytes);
      if (again.info.identity !== existing.info.identity || !again.bytes.equals(existing.bytes))
        fail('native_bridge_owner_changed');
      unlinkSync(this.ownerPath);
    }
    let fd: number;
    try {
      fd = openPrivateFileSync(this.ownerPath, 'wx', 0o600);
    } catch (error) {
      if (record(error) && error.code === 'EEXIST') fail('native_bridge_owner_busy');
      throw error;
    }
    try {
      writeFileSync(fd, this.ownerBytes);
      protectWindowsStateFile(this.ownerPath);
      fsyncSync(fd);
      const held = fstatSync(fd, { bigint: true });
      const owner = this.files.privateBytes(this.ownerPath, limits.ownerBytes);
      if (
        !owner.bytes.equals(Buffer.from(this.ownerBytes)) ||
        pathIdentity(owner.info.stat) !== pathIdentity(held)
      )
        fail('native_bridge_owner_changed');
      this.ownerIdentity = owner.info.identity;
    } finally {
      closeSync(fd);
    }
  }
  private scan(at: number): string[] {
    if (this.files.directory(this.config.logDirectory, this.config.logDirectory) !== this.logRoot)
      fail('native_bridge_log_changed');
    const paths: string[] = [],
      directories = logDirectories(this.config.logDirectory, at);
    const liveDirectories = new Set<string>();
    let entries = 0;
    const pattern = new RegExp(
      `^codex-desktop-[A-Za-z0-9_.-]+-${this.identity.desktopPid}-[A-Za-z0-9_.-]+\\.log$`,
    );
    for (const directory of directories) {
      try {
        // Verify each dated component, not merely the leaf.
        for (const p of [dirname(dirname(directory)), dirname(directory), directory]) {
          const identity = this.files.directory(p, this.config.logDirectory);
          const old = this.directoryIdentities.get(p);
          if (old !== undefined && old !== identity) fail('native_bridge_log_changed');
          this.directoryIdentities.set(p, identity);
          liveDirectories.add(p);
        }
        const dir = opendirSync(directory);
        try {
          for (let item = dir.readSync(); item; item = dir.readSync()) {
            if (++entries > limits.directoryEntries) fail('native_bridge_log_entry_limit');
            if (!pattern.test(item.name)) continue;
            if (!item.isFile() || item.isSymbolicLink()) fail('native_bridge_log_unavailable');
            if (paths.length >= limits.files) fail('native_bridge_log_file_limit');
            paths.push(join(directory, item.name));
          }
        } finally {
          dir.closeSync();
        }
        if (
          this.files.directory(directory, this.config.logDirectory) !==
          this.directoryIdentities.get(directory)
        )
          fail('native_bridge_log_changed');
      } catch (error) {
        if (!missing(error)) throw error;
      }
    }
    for (const path of this.directoryIdentities.keys())
      if (!liveDirectories.has(path)) this.directoryIdentities.delete(path);
    return paths.sort();
  }
  private seed(path: string, budget: Budget): void {
    const info = this.files.info(path, this.config.logDirectory),
      size = Number(info.stat.size);
    const count = Math.min(64, size);
    const tail = this.files.read(path, this.config.logDirectory, info, size - count, count, budget);
    if (!this.cursors.has(path) && this.cursors.size >= limits.files)
      fail('native_bridge_log_file_limit');
    this.cursors.set(path, {
      identity: info.identity,
      offset: size,
      size,
      stamp: info.stamp,
      guard: digest(tail),
      tail: Buffer.alloc(0),
      discard: size > 0 && tail[tail.length - 1] !== 10,
    });
  }
  private readLog(path: string, budget: Budget): void {
    this.checkLogParents(path);
    const info = this.files.info(path, this.config.logDirectory),
      size = Number(info.stat.size);
    let cursor = this.cursors.get(path);
    if (!cursor || cursor.identity !== info.identity) {
      if (!cursor && this.cursors.size >= limits.files) fail('native_bridge_log_file_limit');
      cursor = {
        identity: info.identity,
        offset: 0,
        size: 0,
        stamp: info.stamp,
        guard: digest(Buffer.alloc(0)),
        tail: Buffer.alloc(0),
        discard: false,
      };
      this.cursors.set(path, cursor);
    }
    if (
      size < cursor.offset ||
      size < cursor.size ||
      (size === cursor.size && info.stamp !== cursor.stamp)
    )
      fail('native_bridge_log_truncated');
    if (size === cursor.offset && info.stamp === cursor.stamp) return;
    const guardBytes = Math.min(64, cursor.offset);
    const count = Math.min(size - cursor.offset, Math.max(0, budget.remaining - guardBytes));
    if (budget.remaining < guardBytes || (size > cursor.offset && count === 0)) return;
    const bytes = this.files.read(
      path,
      this.config.logDirectory,
      info,
      cursor.offset - guardBytes,
      guardBytes + count,
      budget,
    );
    if (digest(bytes.subarray(0, guardBytes)) !== cursor.guard) fail('native_bridge_log_truncated');
    const next = bytes.subarray(guardBytes);
    // Verify path/namespace again immediately before delivering any decisions.
    if (this.files.directory(this.config.logDirectory, this.config.logDirectory) !== this.logRoot)
      fail('native_bridge_log_changed');
    this.checkLogParents(path);
    this.consume(cursor, next, this.time());
    cursor.offset += next.length;
    cursor.size = size;
    cursor.stamp = info.stamp;
    cursor.guard = digest(bytes.subarray(Math.max(0, bytes.length - Math.min(64, cursor.offset))));
  }
  private checkLogParents(path: string): void {
    for (let p = dirname(path), depth = 0; depth < 3; p = dirname(p), depth++) {
      if (this.files.directory(p, this.config.logDirectory) !== this.directoryIdentities.get(p))
        fail('native_bridge_log_changed');
    }
  }
  private consume(cursor: Cursor, bytes: Buffer, at: number): void {
    let offset = 0;
    while (offset < bytes.length) {
      const newline = bytes.indexOf(10, offset),
        end = newline < 0 ? bytes.length : newline;
      const part = bytes.subarray(offset, end);
      if (!cursor.discard) {
        if (cursor.tail.length + part.length > limits.lineBytes) {
          cursor.tail = Buffer.alloc(0);
          cursor.discard = true;
          this.report('native_bridge_log_line_limit');
        } else if (part.length) cursor.tail = Buffer.concat([cursor.tail, part]);
      }
      if (newline < 0) break;
      if (!cursor.discard) this.decision(cursor.tail, at);
      cursor.tail = Buffer.alloc(0);
      cursor.discard = false;
      offset = newline + 1;
    }
  }
  private decision(bytes: Buffer, at: number): void {
    let text: string;
    try {
      text = decoder.decode(bytes);
    } catch {
      return;
    }
    const match = safetyLine.exec(text.endsWith('\r') ? text.slice(0, -1) : text);
    if (!match) return;
    const decided = Date.parse(match[1]!);
    if (
      !integer(decided) ||
      new Date(decided).toISOString() !== match[1] ||
      decided < this.identity.wrapperStartedAt ||
      decided > at
    )
      return;
    this.fence(this.time());
    this.capture!.observeSafetyDecision({
      sourceThreadId: match[2]!,
      sourceTurnId: match[3]!,
      suppressed: match[4] === 'true',
      at: decided,
      evidenceSha256: digest(bytes),
    });
    if (!this.capture!.available) fail('native_bridge_capture_unavailable');
  }
  tick(): void {
    if (!this.ready || this.closed) return;
    try {
      this.phase(false, () => {
        const at = this.time();
        this.fence(at);
        if (!this.capture!.available) fail('native_bridge_capture_unavailable');
        if (at - this.heartbeatAt >= limits.heartbeatMs) {
          const current = configuration(this.configPath, this.files, this.hooks);
          if (current.hash !== this.configHash) fail('native_bridge_config_changed');
          const identity = this.inspect(process.pid, this.configPath);
          if (!validIdentity(identity, at) || !sameIdentity(identity, this.identity))
            fail('native_bridge_identity_unavailable');
          this.fence(this.time());
          if (!this.capture!.available) fail('native_bridge_capture_unavailable');
          this.writeProducer('listening', this.time());
          this.heartbeatAt = this.lastAt;
        }
        const paths = this.scan(at),
          budget = { remaining: limits.bytesPerTick };
        if (!this.capture!.hasPendingRequests) return;
        for (const path of this.cursors.keys())
          if (!paths.includes(path)) this.cursors.delete(path);
        const count = Math.min(limits.filesPerTick, paths.length);
        for (let i = 0; i < count && budget.remaining > 0; i++) {
          this.readLog(paths[(this.roundRobin + i) % paths.length]!, budget);
        }
        this.roundRobin = paths.length ? (this.roundRobin + count) % paths.length : 0;
      });
    } catch (error) {
      this.stop(fixedCode(error, 'native_bridge_log_unavailable'));
    }
  }
  private stop(code: Code): void {
    this.report(code);
    this.close();
  }
  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.ready = false;
    if (this.timer !== undefined) clearInterval(this.timer);
    this.timer = undefined;
    // Fenced capture writes cannot alter a replacement owner's runtime body.
    try {
      this.capture?.close();
    } catch {
      this.report('native_bridge_close_failed');
    }
    this.capture = undefined;
    try {
      // Retirement may mark this exact epoch unavailable after its lease has
      // expired. It cannot publish another listening lease or save a result.
      this.hooks.phaseBudget?.begin(1000);
      if (this.ownerIdentity) {
        this.checkOwner();
        const state = this.store.readProducer();
        if (
          state &&
          sameProducer(state.producer, this.producer) &&
          state.rootIdentity === this.root &&
          state.wrapperStartedAt === this.identity.wrapperStartedAt &&
          state.implementationSha256 === this.implementationSha256
        ) {
          // A bad clock must not prolong a lease; unavailable is still monotonic.
          this.writeProducer('unavailable', Math.max(this.lastAt, state.checkedAt));
        }
        // Never unlink a different epoch, even if it uses this exact PID.
        this.checkOwner();
        unlinkSync(this.ownerPath);
        this.ownerIdentity = '';
      }
    } catch {
      this.report('native_bridge_close_failed');
    } finally {
      this.hooks.phaseBudget?.end();
    }
    for (const cursor of this.cursors.values()) cursor.tail = Buffer.alloc(0);
    this.cursors.clear();
    this.directoryIdentities.clear();
  }
}

function create(
  configPath: string | undefined,
  hooks: Hooks,
): DesktopNativeSampleBridge | undefined {
  if (configPath === undefined) return undefined;
  let bridge: Bridge | undefined;
  try {
    bridge = new Bridge(configPath, hooks);
    bridge.start();
    return bridge;
  } catch (error) {
    const code = fixedCode(error, 'native_bridge_config_invalid');
    if (bridge) {
      bridge.report(code);
      bridge.close();
    } else {
      try {
        if (hooks.status) hooks.status(code);
        else process.stderr.write(`${code}\n`);
      } catch {
        /* Constructor failures also emit only a fixed code. */
      }
    }
    return undefined;
  }
}

/** Opt-in only. No RPC, transport, child executor, renderer or alternate body cache. */
export function createDesktopNativeSampleBridge(
  configPath = process.env.AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG,
): DesktopNativeSampleBridge | undefined {
  return create(configPath, {});
}

/** Fixed Windows sampler entry only; real process inspection is never replaced. */
export function createDesktopNativeSampleWorkerBridge(
  configPath: string,
  phaseBudget: DesktopNativeSamplePhaseBudget,
  status: (code: string) => void,
): DesktopNativeSampleBridge | undefined {
  if (process.platform !== 'win32' || isMainThread) return undefined;
  return create(configPath, { phaseBudget, status });
}

/** @internal Local fixture hooks only; not exported through the adapter entry point. */
export const desktopNativeSampleBridgeTesting = Object.freeze({
  limits,
  create: (configPath: string | undefined, hooks: Hooks): DesktopNativeSampleBridge | undefined =>
    create(configPath, hooks),
  tick: (bridge: DesktopNativeSampleBridge): void => {
    if (bridge instanceof Bridge) bridge.tick();
  },
});
