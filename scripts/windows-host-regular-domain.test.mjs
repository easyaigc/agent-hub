import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, linkSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import {
  parseRegularDomain,
  nativeP1Binding,
  verifyDomainMatrix,
  verifyDomainPackage,
  regularDomainCases,
  preRunFailureBinding,
} from './windows-host-regular-domain.mjs';
const sha = (x) => createHash('sha256').update(x).digest('hex');
const id = (n) => `${n.repeat(8)}-${n.repeat(4)}-4${n.repeat(3)}-8${n.repeat(3)}-${n.repeat(12)}`;
const argv = [
  'verify',
  ...Object.entries({
    'installed-program': 'D:\\Host\\program',
    'legacy-bundle': 'C:\\Private\\t11',
    'host-id': id('1'),
    'legacy-operation-id': id('2'),
    'operation-id': id('3'),
    'p0-report': 'C:\\Private\\p0.json',
    'p0-sha256': 'a'.repeat(64),
    'p1-report': 'C:\\Private\\p1.json',
    'p1-sha256': 'b'.repeat(64),
    'manifest-sha256': 'c'.repeat(64),
  }).flatMap(([k, v]) => ['--' + k, v]),
];
const p1 = () => ({
  format: 'windows-host-regular-prepare-v1',
  status: 'prepared',
  action: 'prepare',
  sourceCommit: '4901b94999bdb9aad5698133f34df9edef2acc54',
  operationId: id('4'),
  checkpointSha256: 'd'.repeat(64),
  hostId: id('1'),
  rollbackCapsuleVerified: true,
  originalFilesUnchanged: true,
  installedProgramStillMatchesP0: true,
  nodeFileStillMatchesP0: true,
  hostStarted: false,
  originalTaskMutationAttempted: false,
  pendingReleased: false,
  historicalSubtreeVerified: false,
  restartAuthorized: false,
  limitedTaskExecutionVerified: false,
  activationImplemented: false,
  privateCheck: { exitConfirmed: true, exitCode: 0 },
  leaseNegatives: {
    writeRejected: true,
    fileRenameRejected: true,
    parentRenameRejected: true,
    positiveControlsPassed: true,
  },
});
const matrix = () => ({
  status: 'verified',
  limitedTaskExecutionVerified: true,
  allSubjectsCovered: false,
  argumentCharacters: 11264,
  argumentsRegisteredExactly: true,
  doubleRunSingleInstance: true,
  processSubtreeClosed: true,
  retainedProcessAnchors: 3,
  fixturesRemoved: true,
  actualMatrix: regularDomainCases.map((name, i) => ({
    name,
    attempted: true,
    outcome: i < 2 ? 'success' : 'denied',
    hresult: i < 2 ? null : '0x80070005',
  })),
});
test('domain only accepts a fresh bounded verification or same-operation status', () => {
  assert.equal(parseRegularDomain(argv).action, 'verify');
  assert.equal(parseRegularDomain(['status', ...argv.slice(1)]).action, 'status');
  for (const bad of [
    ['activate', ...argv.slice(1)],
    ['cleanup', ...argv.slice(1)],
    [...argv, '--force'],
    [...argv, '--operation-id', id('5')],
    argv.map((x) => (x === 'C:\\Private\\p1.json' ? x + ':stream' : x)),
  ])
    assert.throws(() => parseRegularDomain(bad));
});

test('same-operation resume requires the known pre-run failure and explicit prior package binding', () => {
  const failed = {
    format: 'windows-host-regular-domain-v1',
    sourceCommit: '7c6fb472526c93db43466d1c19de52c17cd0ebe4',
    status: 'rejected',
    phase: 'compile-fixed-domain-executable',
    code: 'HOST_DOMAIN_NATIVE_FAILURE',
    errorType: 'System.UnauthorizedAccessException',
    hresult: '0x80070005',
    line: 7,
    taskMutationAttempted: false,
    runAttempted: false,
    originalTaskMutationAttempted: false,
    hostStarted: false,
    pendingReleased: false,
    historicalSubtreeVerified: false,
    restartAuthorized: false,
    installedProgramStillMatchesP0: true,
    nodeFileStillMatchesP0: true,
  };
  assert.equal(preRunFailureBinding(failed, id('3')).operationId, id('3'));
  for (const [key, value] of [
    ['sourceCommit', 'a'.repeat(40)],
    ['phase', 'run-limited-fixture'],
    ['runAttempted', true],
    ['taskMutationAttempted', true],
    ['installedProgramStillMatchesP0', false],
    ['line', 81],
  ])
    assert.throws(() => preRunFailureBinding({ ...failed, [key]: value }, id('3')));
  assert.throws(() => parseRegularDomain(['resume', ...argv.slice(1)]));
  const options = [
    '--failure-report',
    'C:\\Private\\failed.json',
    '--failure-sha256',
    'd'.repeat(64),
    '--previous-manifest-sha256',
    'e'.repeat(64),
  ];
  assert.equal(parseRegularDomain(['resume', ...argv.slice(1), ...options]).action, 'resume');
  assert.throws(() => parseRegularDomain([...argv, ...options]));
});
test('native P1 checkpoint is a prerequisite, never an activation or Limited proof', () => {
  assert.equal(nativeP1Binding(p1(), id('3')).preparationId, id('4'));
  for (const mutate of [
    (x) => (x.status = 'observed'),
    (x) => (x.sourceCommit = 'e'.repeat(40)),
    (x) => (x.privateCheck.exitConfirmed = false),
    (x) => (x.leaseNegatives.positiveControlsPassed = false),
    (x) => (x.historicalSubtreeVerified = true),
    (x) => (x.hostStarted = true),
    (x) => (x.limitedTaskExecutionVerified = true),
  ]) {
    const x = p1();
    mutate(x);
    assert.throws(() => nativeP1Binding(x, id('3')));
  }
  assert.throws(() => nativeP1Binding(p1(), id('4')));
});
test('a pre-read failure, unrelated HRESULT or missing process exit cannot pass the native matrix', () => {
  assert.equal(verifyDomainMatrix(matrix()), true);
  for (const mutate of [
    (x) => (x.actualMatrix[2].attempted = false),
    (x) => (x.actualMatrix[2].hresult = '0x80070002'),
    (x) => (x.actualMatrix[2].outcome = 'success'),
    (x) => x.actualMatrix.reverse(),
    (x) => (x.processSubtreeClosed = false),
    (x) => (x.retainedProcessAnchors = 0),
    (x) => (x.fixturesRemoved = false),
    (x) => (x.argumentCharacters = 11263),
    (x) => (x.allSubjectsCovered = true),
    (x) => (x.limitedTaskExecutionVerified = false),
  ]) {
    const x = matrix();
    mutate(x);
    assert.throws(() => verifyDomainMatrix(x));
  }
});
test('complete package verification rejects extra files, changed bytes and hard links without executing source', () => {
  const dir = realpathSync(mkdtempSync(join(tmpdir(), 'host-domain-package-')));
  const names = [
    'scripts/windows-host-regular-domain.mjs',
    'scripts/windows-host-regular-domain.ps1',
    'scripts/windows-host-regular-domain-common.ps1',
    'scripts/windows-host-regular-limited.ps1',
    'scripts/windows-host-regular-prepare.mjs',
    'scripts/windows-host-regular-recovery.mjs',
    ...[
      'host-domain.cs',
      'private-state.cs',
      'private-state-compilation.cs',
      'private-state-recovery.cs',
      'private-bootstrap.ps1',
    ].map((n) => 'packages/codex-adapter/windows/' + n),
  ];
  try {
    const files = {};
    for (const n of names) {
      const p = join(dir, n);
      mkdirSync(join(p, '..'), { recursive: true });
      const raw = Buffer.from('throw new Error("verification must never execute modules");');
      writeFileSync(p, raw);
      files[n] = { bytes: raw.length, sha256: sha(raw) };
    }
    const raw = Buffer.from(
      JSON.stringify({
        format: 'windows-host-regular-domain-package-v1',
        sourceCommit: 'a'.repeat(40),
        files,
      }),
    );
    writeFileSync(join(dir, 'manifest.json'), raw);
    assert.equal(verifyDomainPackage(dir, sha(raw)).sourceCommit, 'a'.repeat(40));
    writeFileSync(join(dir, 'extra'), 'extra');
    assert.throws(() => verifyDomainPackage(dir, sha(raw)));
    rmSync(join(dir, 'extra'));
    linkSync(join(dir, names[0]), join(dir, 'hardlink'));
    assert.throws(() => verifyDomainPackage(dir, sha(raw)));
    rmSync(join(dir, 'hardlink'));
    writeFileSync(join(dir, names[0]), 'changed');
    assert.throws(() => verifyDomainPackage(dir, sha(raw)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
test(
  'exception cleanup and deletion preserve replaced, denied, missing and active fixtures',
  { skip: !process.env.AGENT_HUB_TEST_PWSH && process.platform !== 'win32' },
  () => {
    const repo = fileURLToPath(new URL('..', import.meta.url));
    const shell =
      process.env.AGENT_HUB_TEST_PWSH ??
      join(
        process.env.SystemRoot ?? 'C:\\Windows',
        'System32/WindowsPowerShell/v1.0/powershell.exe',
      );
    const out = execFileSync(
      shell,
      [
        '-NoProfile',
        '-NonInteractive',
        '-File',
        join(repo, 'scripts/fixtures/windows-domain-cleanup.ps1'),
        '-Common',
        join(repo, 'scripts/windows-host-regular-domain-common.ps1'),
      ],
      { encoding: 'utf8', timeout: 30000, maxBuffer: 8192 },
    );
    assert.deepEqual(JSON.parse(out.trim()), { cases: 17, nativeScheduler: false });
  },
);

test(
  'actual PowerShell parses file-defined modules and compiles all domain partials; pure 11KiB arguments survive quoting',
  { skip: !process.env.AGENT_HUB_TEST_PWSH },
  () => {
    const dir = realpathSync(mkdtempSync(join(tmpdir(), 'host-domain-compile-')));
    const repo = fileURLToPath(new URL('..', import.meta.url));
    const q = (x) => "'" + x.replaceAll("'", "''") + "'";
    try {
      const ps = [
        'windows-host-regular-domain.ps1',
        'windows-host-regular-domain-common.ps1',
        'windows-host-regular-limited.ps1',
      ].map((n) => join(repo, 'scripts', n));
      const cs = [
        'private-state.cs',
        'private-state-compilation.cs',
        'private-state-recovery.cs',
        'host-domain.cs',
      ].map((n) => join(repo, 'packages/codex-adapter/windows', n));
      const path = join(dir, 'check.ps1');
      writeFileSync(
        path,
        `\uFEFF$ErrorActionPreference='Stop'\nforeach($file in @(${ps.map(q).join(',')})){$t=$null;$p=$null;[void][Management.Automation.Language.Parser]::ParseFile($file,[ref]$t,[ref]$p);if($p.Count -ne 0){throw 'parse failure'}}\n${process.platform === 'win32' ? '$compile=@{Path=@(' + cs.map(q).join(',') + ")};if($PSVersionTable.PSVersion.Major -le 5){$compile.ReferencedAssemblies=@('System.dll','System.Core.dll','System.Xml.dll','System.Runtime.Serialization.dll')};Add-Type @compile" : 'Add-Type -Path @(' + cs.map(q).join(',') + ')'}\n$a=[CodexManageHostDomain]::Arguments('C:\\Private Space\\plan.json',('a'*64))\nif(-not $a.EndsWith(('v'*11264)) -or $a.Length -lt 11300){throw 'long arguments failed'}\nif([CodexManageHostDomain]::Quote('x"y\\') -cne '"x\\"y\\\\"'){throw 'quote failed'}\n'{"parsed":3,"compiledPartials":4,"argumentCharacters":11264,"windowsNative":false}'\n`,
      );
      const out = execFileSync(
        process.env.AGENT_HUB_TEST_PWSH,
        ['-NoProfile', '-NonInteractive', '-File', path],
        { encoding: 'utf8', timeout: 30000, maxBuffer: 8192 },
      );
      assert.deepEqual(JSON.parse(out.trim()), {
        parsed: 3,
        compiledPartials: 4,
        argumentCharacters: 11264,
        windowsNative: false,
      });
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  },
);
