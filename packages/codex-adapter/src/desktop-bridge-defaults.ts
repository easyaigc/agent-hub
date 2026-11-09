import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { writePrivateFileSync } from './desktop-platform.js';
import type { RuntimeOptions } from './runtime.js';

/** Configure the independent bridge without replacing an explicit driver or policy. */
export function defaultDesktopPreparedBridge(
  options: RuntimeOptions & { directory: string },
  hostId: string,
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
    try {
      writePrivateFileSync(file, JSON.stringify({ hostId }, null, 2) + '\n', {
        encoding: 'utf8',
        mode: 0o600,
        flag: 'wx',
        flush: true,
      });
    } catch (error) {
      // An existing file belongs to the user; never repair it by overwriting it.
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
    }
  }
  // The bridge still checks private permissions, exact Host identity and live IPC.
  // Existing validationOnly, verification and invalid configurations stay intact.
  return file;
}
