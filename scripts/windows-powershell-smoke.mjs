import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { windowsPowerShellEnvironment } from '../packages/codex-adapter/dist/desktop-platform.js';

const contaminated = {
  ...process.env,
  PSModulePath: 'synthetic-ps7-modules',
  pSmOdUlEpAtH: 'synthetic-other-case',
};
const environment = windowsPowerShellEnvironment(contaminated);
assert(!Object.keys(environment).some((key) => key.toUpperCase() === 'PSMODULEPATH'));
assert.equal(contaminated.PSModulePath, 'synthetic-ps7-modules');
assert.equal(environment.PATH, contaminated.PATH);
if (process.platform === 'win32') {
  const result = execFileSync(
    join(process.env.SystemRoot ?? 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe'),
    [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      " $ErrorActionPreference='Stop'; Import-Module Microsoft.PowerShell.Security; Import-Module Microsoft.PowerShell.Utility; Import-Module Microsoft.PowerShell.Management; $null=Get-AuthenticodeSignature -LiteralPath $env:ComSpec; $null=Get-Acl -LiteralPath $env:TEMP; [Console]::Out.WriteLine('PS5_MODULES_OK')",
    ],
    { encoding: 'utf8', timeout: 30000, env: environment },
  );
  assert.equal(result.trim(), 'PS5_MODULES_OK');
}
console.log(
  JSON.stringify({
    ps5ChildEnvironmentIsolation: 'passed',
    nativeWindowsModules: process.platform === 'win32' ? 'passed' : 'not-run',
  }),
);
