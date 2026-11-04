import {
  closeSync,
  constants,
  existsSync,
  fstatSync,
  lstatSync,
  mkdirSync,
  openSync,
  realpathSync,
  renameSync,
  unlinkSync,
  writeSync,
} from 'node:fs';
import { join } from 'node:path';
import {
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  windowsTraceSnapshot,
} from './desktop-platform.js';

const maximumBytes = 128 * 1024;
const stages = new Set(['initialize', 'observeOutbound', 'observeInbound', 'tick', 'close']);
const events = new Set(['starting', 'listening', 'notice', 'stopped', 'closed', 'capture']);
const states = new Set([
  'pending',
  'completed',
  'cancelled',
  'failed',
  'empty',
  'unverified',
  'suppressed',
]);
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const numbers = [
  'pid',
  'desktopPid',
  'desktopStartedAt',
  'wrapperStartedAt',
  'leaseAgeMs',
  'tickGapMs',
  'phaseElapsedMs',
  'selectedFiles',
  'emptyFiles',
  'directoryEntries',
  'requestSequence',
  'elapsedMs',
] as const;

/** Best-effort, fixed-schema local journal. No frames, bodies, paths or raw errors. */
export class DesktopNativeSampleDiagnostics {
  readonly file: string;
  private readonly directory: string;
  failed = false;
  constructor(private readonly root: string) {
    this.directory = join(root, 'native-diagnostics');
    this.file = join(this.directory, 'producer.jsonl');
  }
  write(event: string, fields: Record<string, unknown> = {}): void {
    try {
      if (!events.has(event)) return;
      const row: Record<string, unknown> = {
        version: 1,
        at: new Date().toISOString(),
        event,
      };
      for (const key of numbers) {
        const value = fields[key];
        if (typeof value === 'number' && Number.isFinite(value) && value >= 0)
          row[key] = Math.round(value);
      }
      for (const key of ['epoch', 'hostId', 'workspaceId', 'sourceThreadId', 'sourceTurnId'])
        if (typeof fields[key] === 'string' && uuid.test(fields[key])) row[key] = fields[key];
      if (
        typeof fields.requestId === 'string' &&
        fields.requestId.startsWith('thread/sample:') &&
        uuid.test(fields.requestId.slice(14))
      )
        row.requestId = fields.requestId;
      if (
        typeof fields.implementationSha256 === 'string' &&
        /^[a-f0-9]{64}$/.test(fields.implementationSha256)
      )
        row.implementationSha256 = fields.implementationSha256;
      if (typeof fields.stage === 'string' && stages.has(fields.stage)) row.stage = fields.stage;
      if (typeof fields.state === 'string' && states.has(fields.state)) row.state = fields.state;
      if (
        typeof fields.code === 'string' &&
        /^native_(bridge|sample)_[a-z_]{1,80}$/.test(fields.code)
      )
        row.code = fields.code;
      if (typeof fields.osCode === 'string' && /^E[A-Z0-9]{1,30}$/.test(fields.osCode))
        row.osCode = fields.osCode;
      this.directoryReady(this.root);
      if (!existsSync(this.directory)) {
        mkdirSync(this.directory, { mode: 0o700 });
        protectWindowsStateDirectory(this.directory);
      }
      this.directoryReady(this.directory);
      // Validate every destination before rotating; never follow or remove an alias.
      for (const path of [this.file, this.file + '.1', this.file + '.2'])
        if (existsSync(path) || this.isAlias(path)) this.privateFile(path);
      if (existsSync(this.file) && lstatSync(this.file).size >= maximumBytes) {
        if (existsSync(this.file + '.2')) unlinkSync(this.file + '.2');
        if (existsSync(this.file + '.1')) renameSync(this.file + '.1', this.file + '.2');
        renameSync(this.file, this.file + '.1');
      }
      const fd = openSync(
        this.file,
        constants.O_WRONLY | constants.O_APPEND | constants.O_CREAT | (constants.O_NOFOLLOW ?? 0),
        0o600,
      );
      try {
        const held = fstatSync(fd),
          current = lstatSync(this.file);
        if (
          !held.isFile() ||
          held.nlink !== 1 ||
          held.dev !== current.dev ||
          held.ino !== current.ino
        )
          throw new Error('Invalid diagnostic file');
        protectWindowsStateFile(this.file);
        this.privateFile(this.file);
        writeSync(fd, JSON.stringify(row) + '\n');
      } finally {
        closeSync(fd);
      }
    } catch {
      // A diagnostic failure must not disable capture or interfere with original bytes.
      if (!this.failed) {
        this.failed = true;
        try {
          process.stderr.write('native_bridge_diagnostic_write_failed\n');
        } catch {
          /* closed stderr */
        }
      }
    }
  }
  private isAlias(path: string): boolean {
    try {
      return lstatSync(path).isSymbolicLink();
    } catch {
      return false;
    }
  }
  private privateFile(path: string): void {
    assertPrivateFile(path);
    const stat = lstatSync(path);
    if (stat.nlink !== 1 || (process.platform !== 'win32' && (stat.mode & 0o777) !== 0o600))
      throw new Error('Private diagnostic file required');
  }
  private directoryReady(path: string): void {
    const stat = lstatSync(path);
    if (!stat.isDirectory() || stat.isSymbolicLink() || realpathSync(path) !== path)
      throw new Error('Invalid diagnostic directory');
    if (process.platform === 'win32') windowsTraceSnapshot(this.root, path, 0);
    else if (stat.uid !== process.getuid!() || (stat.mode & 0o777) !== 0o700)
      throw new Error('Private diagnostic directory required');
  }
}
