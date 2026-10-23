import { spawn, execFile, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import {
  BeforeDesktopSubmit,
  type DesktopCreationDriver,
  type DesktopCreationPlan,
} from './desktop-creation.js';

const digest = (file: string) => createHash('sha256').update(readFileSync(file)).digest('hex');
export const creationGates = [
  'project',
  'empty-project',
  'projectless',
  'first-attachments',
  'owner-controls',
  'recovery',
] as const;
export interface DesktopCreationConfiguration {
  helper: string;
  profile: string;
  verification: string;
}

/** Local configuration only. An HTTP caller can never choose the executable or UI profile. */
export class NativeDesktopCreationDriver implements DesktopCreationDriver {
  private permissionsReady = false;
  private readonly accepted: boolean;
  private children = new Set<ChildProcessWithoutNullStreams>();
  private closed = false;
  get available(): boolean {
    return this.accepted && this.permissionsReady && this.installedVersion() === this.version;
  }
  get reason(): string | null {
    return !this.accepted
      ? 'Codex 创建桥接尚未通过真实验收'
      : !this.permissionsReady
        ? 'Codex 创建所需权限不可用'
        : null;
  }
  private helper = '';
  private profile = '';
  constructor(
    private readonly config: DesktopCreationConfiguration | undefined,
    private readonly version: string,
    private readonly installedVersion: () => string = () => version,
  ) {
    let ready = false;
    try {
      if (!config || process.platform !== 'darwin') throw new Error('Not configured');
      for (const path of [config.helper, config.profile, config.verification]) {
        const stat = lstatSync(path);
        if (
          !stat.isFile() ||
          stat.isSymbolicLink() ||
          stat.uid !== process.getuid?.() ||
          stat.mode & 0o022
        )
          throw new Error('Unsafe bridge configuration');
      }
      this.helper = realpathSync(config.helper);
      this.profile = realpathSync(config.profile);
      const verification = JSON.parse(readFileSync(config.verification, 'utf8'));
      const profile = JSON.parse(readFileSync(this.profile, 'utf8'));
      ready =
        verification.kind === 'real-desktop' &&
        verification.desktopVersion === version &&
        profile.desktopVersion === version &&
        verification.helperSha256 === digest(this.helper) &&
        verification.profileSha256 === digest(this.profile) &&
        typeof verification.verifiedAt === 'string' &&
        Number.isFinite(Date.parse(verification.verifiedAt)) &&
        creationGates.every((gate) => verification.gates?.[gate] === true);
    } catch {
      /* No real acceptance evidence means no creation capability. */
    }
    this.accepted = ready;
  }
  async refresh(): Promise<void> {
    this.permissionsReady = false;
    // Recompiling the binary, changing selectors or upgrading the desktop invalidates acceptance.
    if (
      this.closed ||
      this.installedVersion() !== this.version ||
      !this.accepted ||
      !new NativeDesktopCreationDriver(this.config, this.version).accepted
    )
      return;
    await new Promise<void>((done) => {
      execFile(this.helper, ['--status'], { timeout: 3000, maxBuffer: 4096 }, (error, stdout) => {
        try {
          const status = JSON.parse(stdout);
          this.permissionsReady =
            !error &&
            status.accessibility === true &&
            status.postEvents === true &&
            status.desktopVersion === this.version;
        } catch {
          this.permissionsReady = false;
        }
        done();
      });
    });
  }
  async create(plan: DesktopCreationPlan): Promise<string> {
    await this.refresh();
    if (!this.available) throw new BeforeDesktopSubmit(this.reason ?? 'Bridge unavailable');
    return new Promise((resolve, reject) => {
      const child = spawn(this.helper, ['--profile', this.profile], { stdio: 'pipe' });
      this.children.add(child);
      let output = '',
        settled = false;
      const finish = (error?: Error, id?: string) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (error) reject(error);
        else resolve(id!);
      };
      const timer = setTimeout(() => {
        child.kill('SIGKILL');
        finish(new Error('Desktop result unknown'));
      }, 90_000);
      child.stderr.resume();
      child.stdout.on('data', (bytes: Buffer) => {
        output += bytes.toString('utf8');
        if (Buffer.byteLength(output) > 16_384) {
          child.kill('SIGKILL');
          finish(new Error('Invalid desktop result'));
        }
      });
      child.once('error', () => finish(new BeforeDesktopSubmit('Helper could not start')));
      child.stdin.on('error', () => finish(new Error('Desktop transport closed')));
      child.once('close', (code) => {
        this.children.delete(child);
        if (settled) return;
        try {
          const result = JSON.parse(output);
          if (result.clientOperationId !== plan.clientOperationId || result.hostId !== plan.hostId)
            throw new Error('Native result target mismatch');
          if (code === 0 && result.phase === 'submitted' && typeof result.threadId === 'string')
            finish(undefined, result.threadId);
          else if (result.phase === 'rejected-before-submit')
            finish(new BeforeDesktopSubmit('Desktop refused before send'));
          else finish(new Error('Desktop result unknown'));
        } catch {
          finish(new Error('Desktop result unknown'));
        }
      });
      child.stdin.end(JSON.stringify(plan));
    });
  }
  close(): void {
    this.closed = true;
    this.permissionsReady = false;
    for (const child of this.children) child.kill('SIGKILL');
  }
}
