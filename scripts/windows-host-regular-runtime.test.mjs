import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, realpathSync, mkdirSync, writeFileSync, rmSync, linkSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { regularCompiledFiles, verifyRegularRuntime } from './windows-host-regular-runtime.mjs';
import {
  applyProtectedHostEnvironment,
  protectedHostProgram,
} from './windows-host-regular-layout.mjs';
import { validateWindowsHostEnvironment } from './windows-host-environment.mjs';

const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
test('frozen runtime refuses stale, missing and nested emitted modules before collecting a closure', () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'regular-runtime-source-')));
  const component = 'packages/codex-adapter';
  const source = join(root, component, 'src');
  const output = join(root, component, 'dist');
  try {
    mkdirSync(source, { recursive: true });
    mkdirSync(output, { recursive: true });
    writeFileSync(join(source, 'index.ts'), 'export const value = 1;');
    writeFileSync(join(source, 'types.d.ts'), 'export declare const value: number;');
    writeFileSync(join(output, 'index.js'), 'export const value = 1;');
    writeFileSync(join(output, 'index.d.ts'), 'export declare const value: number;');
    assert.deepEqual(regularCompiledFiles(root, component), ['index.d.ts', 'index.js']);
    const rejected = () =>
      assert.throws(() => regularCompiledFiles(root, component), {
        code: 'HOST_RUNTIME_COMPILED_INVENTORY_CHANGED',
      });
    writeFileSync(join(output, 'orphan.js'), 'export const stale = true;');
    rejected();
    rmSync(join(output, 'orphan.js'));
    rmSync(join(output, 'index.js'));
    rejected();
    writeFileSync(join(output, 'index.js'), 'export const value = 1;');
    mkdirSync(join(output, 'nested'));
    writeFileSync(join(output, 'nested', 'index.js'), 'unexpected nested output');
    rejected();
    rmSync(join(output, 'nested'), { recursive: true });
    mkdirSync(join(source, 'nested'));
    assert.throws(() => regularCompiledFiles(root, component), {
      code: 'HOST_RUNTIME_SOURCE_LAYOUT_CHANGED',
    });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('protected deployment selects Host-only environment before config; ordinary deployments remain valid', () => {
  const profile = join(tmpdir(), 'private-user');
  const program = join(profile, '.config/agent-hub/windows-host-v1/programs', 'a'.repeat(64));
  const original = {
    CODEX_CLI_PATH: 'original-desktop',
    CODEX_DESKTOP_SUGGESTION_OBSERVER: '1',
    CODEX_DESKTOP_TRACE_RETENTION: '1',
    CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG: 'old-research',
  };
  const child = { ...original };
  assert.equal(applyProtectedHostEnvironment(program, child, profile), true);
  assert.equal(child.CODEX_CLI_PATH, original.CODEX_CLI_PATH);
  assert.equal(child.CODEX_MANAGE_WINDOWS_NATIVE_STORAGE, 'profile-v1');
  assert.equal(child.CODEX_DESKTOP_SUGGESTION_OBSERVER, '0');
  assert.equal(child.CODEX_DESKTOP_TRACE_RETENTION, '0');
  assert.equal(child.CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG, undefined);
  assert.equal(original.CODEX_DESKTOP_SUGGESTION_OBSERVER, '1');
  const legacy = { ...original };
  assert.equal(applyProtectedHostEnvironment(join(profile, 'legacy'), legacy, profile), false);
  assert.deepEqual(legacy, original);
  assert.throws(() => protectedHostProgram(join(program, 'nested'), profile));
  assert.throws(() => protectedHostProgram(dirname(program), profile));
  validateWindowsHostEnvironment({
    environment: {
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      CODEX_MANAGE_WINDOWS_NATIVE_STORAGE: 'profile-v1',
    },
  });
  assert.throws(() =>
    validateWindowsHostEnvironment({
      environment: {
        CODEX_RUNTIME_MODE: 'desktop-runtime',
        CODEX_MANAGE_WINDOWS_NATIVE_STORAGE: 'arbitrary',
      },
    }),
  );
});

test('runtime inventory rejects a substituted binary, extra executable input, hardlink and unsafe names', () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'regular-runtime-')));
  const names = [
    'bin/node.exe',
    'scripts/windows-desktop-worker.mjs',
    'apps/host-agent/dist/runtime-entry.js',
  ];
  const content = Buffer.from('synthetic non-executable fixture');
  try {
    for (const name of names) {
      mkdirSync(dirname(join(root, name)), { recursive: true });
      writeFileSync(join(root, name), content);
    }
    const value = {
      format: 'windows-host-regular-runtime-v1',
      sourceCommit: 'a'.repeat(40),
      files: Object.fromEntries(
        names.map((name) => [name, { bytes: content.length, sha256: sha(content) }]),
      ),
    };
    const publish = () => {
      const bytes = Buffer.from(JSON.stringify(value));
      writeFileSync(join(root, 'runtime-manifest.json'), bytes);
      return sha(bytes);
    };
    let expected = publish();
    assert.equal(verifyRegularRuntime(root, expected).sourceCommit, value.sourceCommit);
    writeFileSync(join(root, 'unlisted.cjs'), 'unexpected');
    assert.throws(() => verifyRegularRuntime(root, expected));
    rmSync(join(root, 'unlisted.cjs'));
    linkSync(join(root, names[0]), join(root, 'second-link'));
    assert.throws(() => verifyRegularRuntime(root, expected));
    rmSync(join(root, 'second-link'));
    writeFileSync(join(root, names[0]), 'replacement');
    assert.throws(() => verifyRegularRuntime(root, expected));
    writeFileSync(join(root, names[0]), content);
    value.files['../escape'] = { bytes: 0, sha256: sha('') };
    expected = publish();
    assert.throws(() => verifyRegularRuntime(root, expected));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
