import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync, renameSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import {
  assertPrivateFile,
  closeWindowsPrivateStateBackend,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { withWindowsProcessCreation } from '../packages/codex-adapter/dist/windows-process.js';

assert.equal(process.platform, 'win32', 'Real Windows required');
const directory = resolve('.local/windows-private-state-acceptance', randomUUID());
mkdirSync(directory, { recursive: true });
let result;
try {
  protectWindowsStateDirectory(directory);
  const file = join(directory, 'dummy-private-state.json');
  writeFileSync(file, '{}');
  protectWindowsStateFile(file);
  assertPrivateFile(file);
  // Deliberately broaden only our disposable fixture, then require rejection.
  withWindowsProcessCreation(5000, (timeout) =>
    execFileSync(
      join(process.env.SystemRoot, 'System32/icacls.exe'),
      [file, '/grant', '*S-1-1-0:(R)'],
      { stdio: 'pipe', timeout },
    ),
  );
  assert.throws(() => assertPrivateFile(file));
  protectWindowsStateFile(file);
  assertPrivateFile(file);
  const started = Date.now();
  for (let index = 0; index < 24; index++) {
    const temporary = file + '.' + index + '.tmp';
    writeFileSync(temporary, JSON.stringify({ index }), { flag: 'wx' });
    protectWindowsStateFile(temporary);
    renameSync(temporary, file);
    assertPrivateFile(file);
  }
  const elapsedMs = Date.now() - started;
  assert.ok(elapsedMs < 10000, 'Per-save ACL operations must not repeatedly start PowerShell');
  const longDirectory = join(directory, 'nested-' + 'a'.repeat(110), 'nested-' + 'b'.repeat(110));
  mkdirSync(longDirectory, { recursive: true });
  protectWindowsStateDirectory(longDirectory);
  const longFile = join(longDirectory, 'long-private-state.json');
  assert.ok(longFile.length > 300);
  writeFileSync(longFile, '{}', { flag: 'wx' });
  protectWindowsStateFile(longFile);
  assertPrivateFile(longFile);
  const longTemporary = longFile + '.tmp';
  writeFileSync(longTemporary, '{"saved":true}', { flag: 'wx' });
  protectWindowsStateFile(longTemporary);
  renameSync(longTemporary, longFile);
  assertPrivateFile(longFile);
  const report = {
    kind: 'real-windows-private-state',
    adapterSha256: preparedAdapterDigest(),
    privateOwnerAndAcl: true,
    broadAclRejected: true,
    protectedAgain: true,
    atomicWrites: 24,
    elapsedMs,
    longPathCharacters: longFile.length,
    longPathProtectedAndSaved: true,
    passed: true,
  };
  rmSync(file);
  rmSync(longDirectory, { recursive: true });
  const reportPath = join(directory, 'report.json');
  writeFileSync(reportPath, JSON.stringify(report, null, 2));
  protectWindowsStateFile(reportPath);
  result = { ...report, report: reportPath };
} finally {
  closeWindowsPrivateStateBackend();
}
console.log(JSON.stringify(result));
