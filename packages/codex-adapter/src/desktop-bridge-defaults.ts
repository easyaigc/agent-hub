import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { writePrivateFileSync } from './desktop-platform.js';
import { runtimeFailure } from './runtime-diagnostics.js';
import type { RuntimeOptions } from './runtime.js';

/** Configure the independent bridge without replacing an explicit driver or policy. */
export function defaultDesktopPreparedBridge(
  options: RuntimeOptions & { directory: string },
  hostId: string,
  persistHostIdentity: () => void = () => {},
): string | undefined {
  if (options.desktopPreparedBridge) return options.desktopPreparedBridge;
  if (
    !options.autoConfigureDesktopBridge ||
    process.platform !== 'win32' ||
    options.mode !== 'desktop-runtime' ||
    options.desktopController ||
    options.desktopCreation
  )
    return undefined;
  const file = join(options.directory, 'prepared-bridge.json');
  if (!existsSync(file)) {
    // A restart after provisioning must see the same Host ID even if connect
    // never ran. Failure to persist the primary state is not recoverable here.
    persistHostIdentity();
    try {
      writePrivateFileSync(file, JSON.stringify({ hostId }, null, 2) + '\n', {
        encoding: 'utf8',
        mode: 0o600,
        flag: 'wx',
        flush: true,
      });
    } catch (error) {
      // Preserve a racing writer. An unavailable default bridge must not take
      // down history or control of already loaded conversations.
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') {
        runtimeFailure('desktop-bridge-bootstrap', error);
        return undefined;
      }
    }
  }
  // The bridge still checks private permissions, exact Host identity and live IPC.
  // Existing validationOnly, verification and invalid configurations stay intact.
  return file;
}
