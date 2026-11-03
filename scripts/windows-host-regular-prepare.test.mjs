import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, realpathSync, mkdirSync, writeFileSync, linkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  parseRegularPreparation,
  preparationExpected,
  verifyPreparationPackage,
} from './windows-host-regular-prepare.mjs';
import { windowsNativeStorageRoot } from '../packages/codex-adapter/dist/desktop-platform.js';
const hash = (x) => createHash('sha256').update(x).digest('hex');
const host = '11111111-1111-4111-8111-111111111111';
const legacy = '22222222-2222-4222-8222-222222222222';
const operation = '33333333-3333-4333-8333-333333333333';
const values = { 'host-id': host, 'legacy-operation-id': legacy };
const args = [
  'prepare',
  '--installed-program',
  'D:\\Host\\program',
  '--legacy-bundle',
  'C:\\Package\\original',
  '--host-id',
  host,
  '--legacy-operation-id',
  legacy,
  '--operation-id',
  operation,
  '--p0-report',
  'C:\\Private\\p0.json',
  '--p0-sha256',
  'a'.repeat(64),
  '--manifest-sha256',
  'b'.repeat(64),
];
const p0 = () => ({
  format: 'windows-host-regular-preflight-v1',
  status: 'observed',
  readOnly: true,
  mutationAuthorized: false,
  hostId: host,
  legacyOperationId: legacy,
  configuration: { sha256: 'c'.repeat(64) },
  task: {
    instances: 0,
    principalMatchesCurrentUser: true,
    runLevel: 0,
    logonType: 3,
    legacyOriginalComparison: {
      available: true,
      taskXmlMatches: true,
      taskSdMatches: true,
      rootSdMatches: true,
    },
  },
  boot: { lastBootUpTime: '2026-01-01T00:00:00.0000000Z' },
  legacy: {
    pending: {
      phase: 'domain-verify',
      sha256: 'd'.repeat(64),
      operationMatches: true,
      manifestMatches: true,
    },
    'domain.process-anchors.json': { exists: false },
    'intent.json': {
      sha256: 'e'.repeat(64),
      hostIdMatches: true,
      installedProgramMatches: true,
      configurationMatches: true,
      operationMatches: true,
      manifestMatches: true,
    },
    'domain.intent.json': { sha256: 'f'.repeat(64), operationMatches: true, manifestMatches: true },
    'domain.run-attempt.json': { sha256: '0'.repeat(64), operationMatches: true },
  },
});
test('preparation has fixed actions, separate IDs and no mutation/force escape', () => {
  assert.equal(parseRegularPreparation(args).action, 'prepare');
  for (const action of ['status', 'resume'])
    assert.equal(parseRegularPreparation([action, ...args.slice(1)]).action, action);
  for (const bad of [
    ['activate', ...args.slice(1)],
    [...args, '--force'],
    [...args, '--operation-id', operation],
    args.map((x) => (x === operation ? legacy : x)),
    args.map((x) => (x === 'C:\\Private\\p0.json' ? 'C:\\Private\\p0.json:stream' : x)),
  ])
    assert.throws(() => parseRegularPreparation(bad));
});
test('P0 observations require exact legacy/host/task bindings and do not supply missing proofs', () => {
  const expected = preparationExpected(p0(), values);
  assert.equal(expected.files.configuration, 'c'.repeat(64));
  for (const alter of [
    (p) => delete p.task.legacyOriginalComparison,
    (p) => (p.legacy['intent.json'].hostIdMatches = false),
    (p) => (p.task.instances = 1),
    (p) => (p.task.runLevel = 1),
    (p) => (p.legacy['domain.process-anchors.json'].exists = true),
    (p) => (p.hostId = operation),
  ]) {
    const p = p0();
    alter(p);
    assert.throws(() => preparationExpected(p, values));
  }
  assert.equal('historicalSubtreeVerified' in expected, false);
});
test('protected storage is a fixed profile child, rejects unknown modes and leaves caller environment unchanged', () => {
  const environment = {
    LOCALAPPDATA: join(tmpdir(), 'local'),
    CODEX_MANAGE_WINDOWS_NATIVE_STORAGE: 'profile-v1',
  };
  const before = { ...environment };
  const profile = join(tmpdir(), 'profile');
  assert.equal(
    windowsNativeStorageRoot(environment, profile),
    join(profile, '.config/agent-hub/windows-native-v1/native-helpers'),
  );
  assert.deepEqual(environment, before);
  assert.equal(
    windowsNativeStorageRoot({ LOCALAPPDATA: environment.LOCALAPPDATA }, profile),
    join(environment.LOCALAPPDATA, 'CodexManage/native-helpers'),
  );
  assert.throws(() =>
    windowsNativeStorageRoot(
      { ...environment, CODEX_MANAGE_WINDOWS_NATIVE_STORAGE: 'C:\\arbitrary' },
      profile,
    ),
  );
  assert.throws(() => windowsNativeStorageRoot(environment, 'relative'));
});
test('frozen preparation package verification never evaluates resource bytes and rejects changes/hard links', () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'host-preparation-package-')));
  try {
    const paths = [
      'scripts/windows-host-regular-prepare.mjs',
      'scripts/windows-host-regular-prepare.ps1',
      'scripts/windows-host-regular-recovery.mjs',
      ...[
        'private-state.cs',
        'private-state-compilation.cs',
        'private-state-recovery.cs',
        'private-state.ps1',
        'private-bootstrap.ps1',
        'host-launcher.cs',
      ].map((x) => 'packages/codex-adapter/windows/' + x),
    ];
    const files = {};
    for (const name of paths) {
      const path = join(root, name);
      mkdirSync(dirname(path), { recursive: true });
      const data = Buffer.from('throw new Error("MUST_NOT_EXECUTE")');
      writeFileSync(path, data);
      files[name] = { bytes: data.length, sha256: hash(data) };
    }
    const raw = Buffer.from(
      JSON.stringify({
        format: 'windows-host-regular-prepare-package-v1',
        sourceCommit: 'a'.repeat(40),
        files,
      }),
    );
    writeFileSync(join(root, 'manifest.json'), raw);
    assert.equal(verifyPreparationPackage(root, hash(raw)).sourceCommit, 'a'.repeat(40));
    assert.throws(() => verifyPreparationPackage(root, '0'.repeat(64)));
    linkSync(join(root, paths[0]), join(root, 'outside-link'));
    assert.throws(() => verifyPreparationPackage(root, hash(raw)));
    rmSync(join(root, 'outside-link'));
    writeFileSync(join(root, paths[0]), 'changed');
    assert.throws(() => verifyPreparationPackage(root, hash(raw)));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
test(
  'actual PowerShell parser and C# compiler accept the preparation scope without invoking Windows APIs',
  { skip: !process.env.AGENT_HUB_TEST_PWSH },
  () => {
    const root = realpathSync(mkdtempSync(join(tmpdir(), 'host-preparation-compile-')));
    const repository = fileURLToPath(new URL('..', import.meta.url));
    const quote = (x) => "'" + x.replaceAll("'", "''") + "'";
    try {
      const cs = [
        'private-state.cs',
        'private-state-compilation.cs',
        'private-state-recovery.cs',
      ].map((x) => join(repository, 'packages/codex-adapter/windows', x));
      const ps = [
        'scripts/windows-host-regular-prepare.ps1',
        'packages/codex-adapter/windows/private-state.ps1',
        'packages/codex-adapter/windows/private-bootstrap.ps1',
      ].map((x) => join(repository, x));
      const script = join(root, 'compile.ps1');
      writeFileSync(
        script,
        `\uFEFF$ErrorActionPreference='Stop'\nforeach($file in @(${ps.map(quote).join(',')})){$tokens=$null;$problems=$null;[void][Management.Automation.Language.Parser]::ParseFile($file,[ref]$tokens,[ref]$problems);if($problems.Count -ne 0){throw 'parse failed'}}\nAdd-Type -Path @(${cs.map(quote).join(',')})\nif(-not [CodexManagePrivateStateSecurity].GetMethod('OpenRecoveryRead').IsPublic){throw 'missing API'}\n'{"parserFiles":3,"compiled":true,"nativeWindowsExecuted":false}'\n`,
      );
      const out = execFileSync(
        process.env.AGENT_HUB_TEST_PWSH,
        ['-NoProfile', '-NonInteractive', '-File', script],
        { encoding: 'utf8', timeout: 30000, maxBuffer: 8192 },
      );
      assert.deepEqual(JSON.parse(out.trim()), {
        parserFiles: 3,
        compiled: true,
        nativeWindowsExecuted: false,
      });
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  },
);
