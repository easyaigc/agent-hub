import { lstatSync, realpathSync } from 'node:fs';
import { dirname, join } from 'node:path';
import type { RuntimeOptions } from './runtime.js';
import { loadWindowsDesktop, readMacDesktopVersion } from './desktop-platform.js';

export const macMetadataExecutable =
  '/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex';
type Installation = {
  executable: string;
  version: string;
  generation: string;
  windows?: ReturnType<typeof loadWindowsDesktop>;
};

// Cache only while the actual files retain their cheap identity. Replacing a
// same-path CLI must invalidate trust immediately, even inside the probe floor.
function stamp(installation: Installation): string | undefined {
  const paths = [installation.executable];
  if (installation.windows)
    paths.push(
      installation.windows.identity.executable,
      join(dirname(installation.windows.identity.executable), 'resources/app.asar'),
    );
  try {
    return JSON.stringify(
      paths.map((path) => {
        if (path === 'codex' && !installation.windows) return ['explicit-cli'];
        const stat = lstatSync(path, { bigint: true });
        if (!stat.isFile() || stat.isSymbolicLink()) throw new Error('Installation changed');
        return [
          realpathSync.native(path),
          ...[
            stat.dev,
            stat.ino,
            stat.size,
            stat.mtimeNs,
            stat.ctimeNs,
            stat.birthtimeNs,
            stat.mode,
            stat.uid,
            stat.gid,
          ].map(String),
        ];
      }),
    );
  } catch {
    return undefined;
  }
}

/** One discovery budget for all readers of this runtime. Never executes a task. */
export class DesktopInstallation {
  private snapshot?: Installation;
  private snapshotStamp: string | undefined;
  private pending: Promise<Installation> | undefined;
  private failure?: unknown;
  private nextCheck = 0;
  private retryDelay = 1000;
  get available(): boolean {
    return this.failure === undefined;
  }
  constructor(
    private readonly options: RuntimeOptions,
    private readonly hostId: string,
  ) {}

  resolve(force = false): Promise<Installation> {
    if (this.pending) return this.pending;
    const now = Date.now();
    if (now < this.nextCheck) {
      if (this.failure !== undefined) return Promise.reject(this.failure);
      if (
        this.snapshot &&
        (!force || now < this.nextCheck - 29_000) &&
        this.snapshotStamp !== undefined &&
        stamp(this.snapshot) === this.snapshotStamp
      )
        return Promise.resolve(this.snapshot);
    }
    const work = Promise.resolve()
      .then(() => {
        let snapshot: Installation;
        if (process.platform === 'win32') {
          if (!this.options.windowsDesktopConfig)
            throw new Error('CODEX_DESKTOP_WINDOWS_CONFIG required');
          const windows = loadWindowsDesktop(this.options.windowsDesktopConfig, this.hostId, {
            executable: this.options.executable,
          });
          snapshot = {
            executable: windows.executable!,
            version: windows.archive.version,
            windows,
            generation: JSON.stringify([
              windows.executable,
              windows.identity.pid,
              windows.identity.startedAtUnixMs,
              windows.identity.executableSha256,
              windows.archive.asarSha256,
            ]),
          };
        } else {
          // macOS updates replace the bundle at a stable path. Respect a caller's
          // explicit CLI; do not silently switch to PATH or a different install.
          const version = readMacDesktopVersion();
          snapshot = {
            executable:
              this.options.executable ??
              (process.platform === 'darwin' ? macMetadataExecutable : 'codex'),
            version,
            generation: version,
          };
        }
        this.snapshotStamp = stamp(snapshot);
        snapshot.generation = JSON.stringify([snapshot.generation, this.snapshotStamp]);
        this.snapshot = snapshot;
        this.failure = undefined;
        this.retryDelay = 1000;
        this.nextCheck = Date.now() + 30_000;
        return snapshot;
      })
      .catch((error: unknown) => {
        this.failure = error;
        this.nextCheck = Date.now() + this.retryDelay;
        this.retryDelay = Math.min(15_000, this.retryDelay * 2);
        throw error;
      })
      .finally(() => {
        if (this.pending === work) this.pending = undefined;
      });
    this.pending = work;
    return work;
  }
}
