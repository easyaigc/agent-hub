import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { rmSync, mkdirSync, readFileSync, lstatSync, existsSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import {
  assertPrivateFile,
  windowsPowerShellEnvironment,
  closeWindowsPrivateStateBackend,
  makePrivateDirectorySync,
  writePrivateFileSync,
  renamePrivateFileSync,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { preparedAdapterDigest } from '../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { withWindowsProcessCreation } from '../packages/codex-adapter/dist/windows-process.js';
import { RuntimePersistence } from '../packages/codex-adapter/dist/runtime-persistence.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { verifyLegacySessionAttachments } from './fixtures/runtime-private-attachment-layout.mjs';

assert.equal(process.platform, 'win32', 'Real Windows required');
const directory = resolve('.local/windows-private-state-acceptance', randomUUID());
let result;
try {
  makePrivateDirectorySync(directory);
  protectWindowsStateDirectory(directory);
  const file = join(directory, 'dummy-private-state.json');
  writePrivateFileSync(file, '{}');
  protectWindowsStateFile(file);
  assertPrivateFile(file);
  // Reproduce the former Node-mkdir layout only inside this task's fixture.
  // On an elevated token v1 defaults to Administrators; never change its owner.
  const history = join(directory, 'legacy-layout');
  makePrivateDirectorySync(history);
  const legacy = join(history, 'runtime-history-v1');
  mkdirSync(legacy);
  const retained = join(legacy, 'cached-history.json');
  writeFileSync(retained, 'preserve-legacy-history', { flag: 'wx' });
  const inspectLegacy = () =>
    withWindowsProcessCreation(5000, (timeout) =>
      JSON.parse(
        execFileSync(
          join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
          [
            '-NoProfile',
            '-NonInteractive',
            '-Command',
            '$i=[Security.Principal.WindowsIdentity]::GetCurrent();' +
              "$a=Get-Acl -LiteralPath '" +
              legacy.replaceAll("'", "''") +
              "';" +
              '@{sddl=$a.Sddl;ownerIsUser=$a.GetOwner([Security.Principal.SecurityIdentifier]).Equals($i.User)}|ConvertTo-Json -Compress',
          ],
          {
            encoding: 'utf8',
            stdio: 'pipe',
            timeout,
            windowsHide: true,
            env: windowsPowerShellEnvironment(),
          },
        ),
      ),
    );
  const legacySecurity = inspectLegacy();
  const legacyStat = lstatSync(retained, { bigint: true });
  const statePath = join(history, 'runtime-state.json');
  const originalState = {
    hostId: 'fixture-only',
    threads: [],
    draft: 'keep',
    receipts: { unknown: 'query-only' },
  };
  writePrivateFileSync(statePath, JSON.stringify(originalState), { flag: 'wx' });
  const persistence = new RuntimePersistence(statePath, history);
  try {
    assert.deepEqual(persistence.load(), originalState);
    assert(existsSync(join(history, 'runtime-history-v2')));
    assert.equal(readFileSync(retained, 'utf8'), 'preserve-legacy-history');
    assert.equal(lstatSync(retained, { bigint: true }).ctimeNs, legacyStat.ctimeNs);
    assert.deepEqual(inspectLegacy(), legacySecurity);
  } finally {
    persistence.close();
  }
  // The session root itself must be absent: pre-protecting it would hide the
  // elevated token's Administrators default-owner regression in production.
  const first = join(directory, 'first-session');
  assert.equal(existsSync(first), false);
  const security = (path) =>
    withWindowsProcessCreation(5000, (timeout) =>
      JSON.parse(
        execFileSync(
          join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
          [
            '-NoProfile',
            '-NonInteractive',
            '-Command',
            "$a=Get-Acl -LiteralPath '" +
              path.replaceAll("'", "''") +
              "';" +
              '$i=[Security.Principal.WindowsIdentity]::GetCurrent();' +
              '$o=$a.GetOwner([Security.Principal.SecurityIdentifier]);' +
              "$r=@($a.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])|Where-Object {$_.AccessControlType -eq 'Allow'});" +
              "$trusted=@($i.User.Value,'S-1-5-18','S-1-5-32-544');" +
              "@{sddl=$a.Sddl;ownerIsUser=$o.Equals($i.User);ownerIsAdministrators=($o.Value -eq 'S-1-5-32-544');ownerMatchesDefaultOwner=$o.Equals($i.Owner);defaultOwnerIsAdministrators=($i.Owner.Value -eq 'S-1-5-32-544');protected=$a.AreAccessRulesProtected;inheritedAllowOnly=($r.Count -gt 0 -and @($r|Where-Object {!$_.IsInherited}).Count -eq 0);trustedAllowOnly=(@($r|Where-Object {$trusted -notcontains $_.IdentityReference.Value}).Count -eq 0)}|ConvertTo-Json -Compress",
          ],
          {
            encoding: 'utf8',
            stdio: 'pipe',
            timeout,
            windowsHide: true,
            env: windowsPowerShellEnvironment(),
          },
        ),
      ),
    );
  const assertCreatedPrivate = (path) => {
    const value = security(path);
    assert.equal(value.ownerIsUser, true);
    assert.equal(value.protected, true);
  };
  const options = (directory) => ({
    directory,
    root: directory,
    name: 'Synthetic private state',
    mode: 'desktop-runtime',
  });
  const firstSession = new RuntimeSession(options(first));
  try {
    assertCreatedPrivate(first);
    assertCreatedPrivate(join(first, 'attachments-v2'));
    assertCreatedPrivate(join(first, 'runtime-history-v2'));
  } finally {
    firstSession.close();
  }
  assertPrivateFile(join(first, 'runtime.json'));
  assertCreatedPrivate(join(first, 'runtime.json'));
  rmSync(first, { recursive: true });

  const legacyAttachmentResult = await verifyLegacySessionAttachments(history, security);
  assert.equal(readFileSync(retained, 'utf8'), 'preserve-legacy-history');
  assert.equal(lstatSync(retained, { bigint: true }).ctimeNs, legacyStat.ctimeNs);
  assert.deepEqual(inspectLegacy(), legacySecurity);
  rmSync(history, { recursive: true });
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
    writePrivateFileSync(temporary, JSON.stringify({ index }), { flag: 'wx' });
    protectWindowsStateFile(temporary);
    renamePrivateFileSync(temporary, file);
    assertPrivateFile(file);
  }
  const elapsedMs = Date.now() - started;
  assert.ok(elapsedMs < 10000, 'Per-save ACL operations must not repeatedly start PowerShell');
  const longDirectory = join(directory, 'nested-' + 'a'.repeat(110), 'nested-' + 'b'.repeat(110));
  makePrivateDirectorySync(longDirectory);
  protectWindowsStateDirectory(longDirectory);
  const longFile = join(longDirectory, 'long-private-state.json');
  assert.ok(longFile.length > 300);
  writePrivateFileSync(longFile, '{}', { flag: 'wx' });
  protectWindowsStateFile(longFile);
  assertPrivateFile(longFile);
  const longTemporary = longFile + '.tmp';
  writePrivateFileSync(longTemporary, '{"saved":true}', { flag: 'wx' });
  protectWindowsStateFile(longTemporary);
  renamePrivateFileSync(longTemporary, longFile);
  assertPrivateFile(longFile);
  const report = {
    kind: 'real-windows-private-state',
    adapterSha256: preparedAdapterDigest(),
    privateOwnerAndAcl: true,
    broadAclRejected: true,
    protectedAgain: true,
    legacyHistoryBytesAndOwnerUnchanged: true,
    legacyHistoryOwnerDiffersFromUser: !legacySecurity.ownerIsUser,
    newHistoryNamespace: 'runtime-history-v2',
    firstSessionRootInitiallyAbsent: true,
    firstSessionUserOwnerAndProtected: true,
    ...legacyAttachmentResult,
    newAttachmentNamespace: 'attachments-v2',
    sessionLocalIoOnly: true,
    globalStateReceiptsAndDraftRetained: true,
    atomicWrites: 24,
    elapsedMs,
    longPathCharacters: longFile.length,
    longPathProtectedAndSaved: true,
    passed: true,
  };
  rmSync(file);
  rmSync(longDirectory, { recursive: true });
  const reportPath = join(directory, 'report.json');
  writePrivateFileSync(reportPath, JSON.stringify(report, null, 2));
  protectWindowsStateFile(reportPath);
  result = { ...report, report: reportPath };
} finally {
  closeWindowsPrivateStateBackend();
}
console.log(JSON.stringify(result));
