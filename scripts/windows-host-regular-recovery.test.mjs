import assert from 'node:assert/strict';
import {
  mkdtempSync,
  writeFileSync,
  mkdirSync,
  rmSync,
  realpathSync,
  symlinkSync,
  readFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';
import test from 'node:test';
import {
  parseRegularPreflight,
  publicInventory,
  regularPowerShellEnvironment,
  observePublicProgram,
} from './windows-host-regular-recovery.mjs';

const argv = [
  'preflight',
  '--installed-program',
  'D:\\Product\\program',
  '--legacy-bundle',
  'D:\\Evidence\\payload',
  '--host-id',
  '11111111-1111-4111-8111-111111111111',
  '--legacy-operation-id',
  '22222222-2222-4222-8222-222222222222',
];
test('preflight cannot select a mutation, shell path, unknown field or ambiguous identity', () => {
  assert.equal(parseRegularPreflight(argv)['installed-program'], 'D:\\Product\\program');
  for (const action of ['start', 'repair-task', 'cleanup', 'retire', 'restart'])
    assert.throws(() => parseRegularPreflight([action, ...argv.slice(1)]));
  for (const path of [
    '\\\\server\\share',
    'C:relative',
    'C:\\x\\..\\y',
    'C:\\x\\bad.',
    'C:\\x\\stream:ads',
    'C:\\x\nsecret',
    'C:/x',
  ])
    assert.throws(() => parseRegularPreflight([argv[0], argv[1], path, ...argv.slice(3)]));
  assert.throws(() => parseRegularPreflight([...argv, '--force']));
  assert.throws(() =>
    parseRegularPreflight([...argv, '--host-id', '33333333-3333-4333-8333-333333333333']),
  );
});
test('PS5 environment preparation removes only inherited module lookup overrides', () => {
  const source = Object.freeze({
    PSModulePath: 'other-version',
    pSmOdUlEpAtH: 'shadow',
    CODEX_CLI_PATH: 'preserved',
    RETAINED: 'value',
  });
  assert.deepEqual(regularPowerShellEnvironment(source), {
    CODEX_CLI_PATH: 'preserved',
    RETAINED: 'value',
  });
  assert.equal(source.PSModulePath, 'other-version');
});
test('public inventories do not execute modules, are stable by name and detect changed resources', () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'regular-public-code-')));
  try {
    writeFileSync(join(root, 'z.js'), 'throw new Error("MUST_NOT_EXECUTE")');
    mkdirSync(join(root, 'nested'));
    writeFileSync(join(root, 'nested/a.js'), 'export default 1');
    const before = publicInventory(root);
    assert.equal(before.files, 2);
    assert.equal(before.executionClosureVerified, false);
    assert.deepEqual(publicInventory(root), before);
    writeFileSync(join(root, 'nested/a.js'), 'export default 2');
    assert.notEqual(publicInventory(root).inventorySha256, before.inventorySha256);
    writeFileSync(join(root, 'oversized.js'), Buffer.alloc(4194305));
    assert.throws(() => publicInventory(root), /BUDGET_REJECTED/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
test('public observations reject a linked namespace and never authorize missing inventories', () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'regular-public-link-')));
  try {
    const outside = join(root, 'outside');
    mkdirSync(outside);
    writeFileSync(join(outside, 'a.js'), 'private fixture');
    const directory = join(root, 'code');
    mkdirSync(directory);
    symlinkSync(outside, join(directory, 'linked'), 'junction');
    assert.throws(() => publicInventory(directory), /NAMESPACE_REJECTED/);
    const observed = observePublicProgram(join(root, 'missing'));
    assert.equal(observed.inventoryIsExecutionApproval, false);
    assert.ok(Object.values(observed.inventories).every((value) => value.unavailable));
    assert.equal(
      Object.values(observed.inventories).some((value) => JSON.stringify(value).includes(root)),
      false,
    );
    assert.equal(readFileSync(join(outside, 'a.js'), 'utf8'), 'private fixture');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
test(
  'read-only PS snapshot uses actual memory-defined function scope and bounded UTF-8 JSON',
  { skip: !process.env.AGENT_HUB_TEST_PWSH && process.platform !== 'win32' },
  () => {
    const root = mkdtempSync(join(tmpdir(), 'regular-preflight-snapshot-'));
    try {
      const source = readFileSync(
        new URL('./windows-host-regular-preflight.ps1', import.meta.url),
        'utf8',
      );
      const marker = '# BEGIN REGULAR READONLY EXECUTION';
      assert.equal(source.indexOf(marker), source.lastIndexOf(marker));
      const quote = (s) => "'" + s.replaceAll("'", "''") + "'";
      const payload = join(root, 'state.json');
      writeFileSync(payload, JSON.stringify({ value: '合成 only' }));
      const script = join(root, 'fixture.ps1');
      writeFileSync(
        script,
        `\uFEFF$ErrorActionPreference='Stop'\n. ([ScriptBlock]::Create(${quote(source.slice(0, source.indexOf(marker)))}))\n$path=${quote(payload)}\n$hold=[IO.FileStream]::new($path,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))\ntry{$a=Regular-Snapshot $path 4096;if($a.value.value -cne '合成 only'){throw 'wrong text'}}finally{$hold.Dispose()}\n$rejected=$false;try{[void](Regular-Snapshot $path 1)}catch{$rejected=$true};if(-not $rejected){throw 'budget ignored'}\nif(([IO.File]::ReadAllText($path,[Text.Encoding]::UTF8)) -notmatch '合成 only'){throw 'input changed'}\n'{"cases":2,"readOnly":true}'\n`,
      );
      const ps =
        process.env.AGENT_HUB_TEST_PWSH ??
        join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe');
      const raw = execFileSync(ps, ['-NoProfile', '-NonInteractive', '-File', script], {
        encoding: 'utf8',
        timeout: 10000,
      });
      assert.deepEqual(JSON.parse(raw.trim()), { cases: 2, readOnly: true });
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  },
);
