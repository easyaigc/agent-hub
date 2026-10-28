import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import {
  assertPrivateFile,
  closeWindowsPrivateStateBackend,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
  windowsPowerShellEnvironment,
} from '../packages/codex-adapter/dist/desktop-platform.js';

const native = { skip: process.platform !== 'win32' && 'Requires native Windows file security' };
const powershell = join(
  process.env.SystemRoot ?? 'C:\\Windows',
  'System32/WindowsPowerShell/v1.0/powershell.exe',
);
const helper = (name) =>
  fileURLToPath(new URL('../packages/codex-adapter/windows/' + name, import.meta.url));
const run = (args, environment = {}) =>
  execFileSync(powershell, ['-NoProfile', '-NonInteractive', ...args], {
    encoding: 'utf8',
    timeout: 30000,
    windowsHide: true,
    env: { ...windowsPowerShellEnvironment(), ...environment },
  });
const bootstrap = (action, path) =>
  run(['-File', helper('private-state.ps1'), '-Action', action, '-Path', path]);

test(
  'bootstrap and native worker repeatedly protect ordinary-user state and reject unsafe targets',
  native,
  () => {
    const directory = mkdtempSync(join(tmpdir(), 'codex-private-acl-'));
    const file = join(directory, 'private.json');
    const junction = directory + '-junction';
    try {
      writeFileSync(file, '{}');
      for (let index = 0; index < 3; index++) {
        assert.match(bootstrap('protect', directory), /PRIVATE_STATE_OK/);
        assert.match(bootstrap('protect-file', file), /PRIVATE_STATE_OK/);
        assert.match(bootstrap('check', file), /PRIVATE_STATE_OK/);
        protectWindowsStateDirectory(directory);
        protectWindowsStateFile(file);
        assertPrivateFile(file);
      }
      execFileSync(
        join(process.env.SystemRoot, 'System32/icacls.exe'),
        [file, '/grant', '*S-1-1-0:(R)'],
        { stdio: 'pipe', windowsHide: true },
      );
      assert.throws(() => bootstrap('check', file));
      assert.throws(() => assertPrivateFile(file));
      protectWindowsStateFile(file);
      assert.match(bootstrap('check', file), /PRIVATE_STATE_OK/);
      const longDirectory = join(directory, 'a'.repeat(120), 'b'.repeat(120));
      mkdirSync(longDirectory, { recursive: true });
      const longFile = join(longDirectory, 'private.json');
      writeFileSync(longFile, '{}');
      assert(longFile.length > 260);
      assert.match(bootstrap('protect', longDirectory), /PRIVATE_STATE_OK/);
      assert.match(bootstrap('protect-file', longFile), /PRIVATE_STATE_OK/);
      protectWindowsStateFile(longFile);
      assertPrivateFile(longFile);
      assert.throws(() => bootstrap('protect', file));
      assert.throws(() => bootstrap('protect-file', directory));
      symlinkSync(directory, junction, 'junction');
      assert.throws(() => bootstrap('protect', junction));
      assert.throws(() => protectWindowsStateDirectory(junction));
      // Read actual owner/ACL; do not infer protection from a successful command.
      const details = JSON.parse(
        run(
          [
            '-Command',
            '$a=Get-Acl -LiteralPath $env:CODEX_ACL_TEST_FILE;@{owner=$a.GetOwner([Security.Principal.SecurityIdentifier]).Value;current=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;protected=$a.AreAccessRulesProtected;allowed=@($a.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])|Where-Object AccessControlType -eq Allow|ForEach-Object{$_.IdentityReference.Value})}|ConvertTo-Json -Compress',
          ],
          { CODEX_ACL_TEST_FILE: file },
        ),
      );
      assert.equal(details.owner, details.current);
      assert.equal(details.protected, true);
      assert.deepEqual(
        new Set(details.allowed),
        new Set([details.current, 'S-1-5-18', 'S-1-5-32-544']),
      );
    } finally {
      closeWindowsPrivateStateBackend();
      rmSync(junction, { force: true });
      rmSync(directory, { recursive: true, force: true });
    }
  },
);

test('CLI inspection accepts an unsigned candidate and retains file/reparse checks', native, () => {
  const directory = mkdtempSync(join(tmpdir(), 'codex-cli-identity-'));
  try {
    const file = join(directory, 'codex.exe');
    const bytes = Buffer.from('unsigned native-boundary fixture, never executed');
    writeFileSync(file, bytes);
    const inspect = (path) => JSON.parse(run(['-File', helper('desktop-cli.ps1'), '-Path', path]));
    assert.equal(inspect(file).sha256, createHash('sha256').update(bytes).digest('hex'));
    const container = join(directory, 'container');
    mkdirSync(container);
    assert.throws(() => inspect(container));
    const redirected = join(directory, 'redirected');
    symlinkSync(directory, redirected, 'junction');
    assert.throws(() => inspect(join(redirected, 'codex.exe')));
    rmSync(redirected);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
