import { readFileSync, existsSync, lstatSync } from 'node:fs';
import { homedir } from 'node:os';
import { execFileSync } from 'node:child_process';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { verifyRegularRuntime } from './windows-host-regular-runtime.mjs';
import { protectedHostProgram } from './windows-host-regular-layout.mjs';
import { regularPowerShellEnvironment } from './windows-host-regular-recovery.mjs';

const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
export function routeRegularService(repository, action) {
  const pointer = join(homedir(), '.config/agent-hub/windows-host-v1/current.json');
  if (!existsSync(pointer)) return undefined;
  const route = JSON.parse(
    execFileSync(
      join(
        process.env.SystemRoot ?? 'C:\\Windows',
        'System32/WindowsPowerShell/v1.0/powershell.exe',
      ),
      [
        '-NoProfile',
        '-NonInteractive',
        '-File',
        join(repository, 'scripts/windows-host-regular-route.ps1'),
      ],
      {
        encoding: 'utf8',
        timeout: 30000,
        windowsHide: true,
        maxBuffer: 4096,
        env: regularPowerShellEnvironment(process.env),
      },
    ),
  );
  if (
    !protectedHostProgram(route.program, route.profile) ||
    route.profile.toLowerCase() !== homedir().toLowerCase() ||
    hash(readFileSync(pointer)) !== route.pointerSha256 ||
    lstatSync(pointer).nlink !== 1
  )
    throw new Error('Invalid regular Host installation');
  verifyRegularRuntime(route.program, route.runtimeSha256);
  if (resolve(route.program).toLowerCase() === resolve(repository).toLowerCase())
    return JSON.parse(readFileSync(pointer, 'utf8'));
  if (hash(readFileSync(pointer)) !== route.pointerSha256)
    throw new Error('Regular Host installation changed');
  const output = execFileSync(
    join(route.program, 'bin/node.exe'),
    [join(route.program, 'scripts/windows-desktop-service.mjs'), action],
    {
      encoding: 'utf8',
      timeout: 90000,
      windowsHide: true,
      maxBuffer: 16384,
      env: { ...process.env, NODE_OPTIONS: '', NODE_PATH: '' },
    },
  );
  process.stdout.write(output);
  process.exit(0);
}
