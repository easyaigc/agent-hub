import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { verifyHostRuntime } from './verify-host-runtime.mjs';

const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const names = ['codex-adapter', 'protocol', 'service-runtime', 'notification-runtime'];

async function fixture(run) {
  const root = await mkdtemp(join(tmpdir(), 'host-runtime-resolution-'));
  const body = 'throw new Error("Runtime verification must not execute this module");\n';
  const fingerprint = sha256(JSON.stringify({ 'index.js': sha256(body) }));
  const makePackage = async (path, name, dependencies = {}, source = body) => {
    await mkdir(join(path, 'dist'), { recursive: true });
    await writeFile(
      join(path, 'package.json'),
      JSON.stringify({
        name: '@codex-manage/' + name,
        type: 'module',
        dependencies,
        exports: { '.': { import: './dist/index.js', require: './missing-require-only.js' } },
      }),
    );
    await writeFile(join(path, 'dist/index.js'), source);
  };
  try {
    const host = join(root, 'apps/host-agent');
    await makePackage(
      host,
      'host-agent',
      Object.fromEntries(names.map((name) => ['@codex-manage/' + name, 'workspace:*'])),
    );
    for (const name of names)
      await makePackage(
        join(root, 'node_modules/@codex-manage', name),
        name,
        name === 'codex-adapter' ? { '@codex-manage/protocol': 'workspace:*' } : {},
      );
    const bundle = join(root, 'bundle.json');
    await writeFile(
      bundle,
      JSON.stringify({
        runtimeFingerprints: Object.fromEntries([
          ['apps/host-agent', fingerprint],
          ...names.map((name) => ['packages/' + name, fingerprint]),
        ]),
      }),
    );
    await run({
      root,
      host,
      bundle,
      makePackage,
      body,
      fingerprint,
      entry: join(host, 'dist/index.js'),
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test('uses real ESM import resolution and verifies without running application code', () =>
  fixture(async ({ entry, bundle }) => {
    const result = await verifyHostRuntime({ entry, bundle });
    assert.equal(result.ok, true);
    assert.equal(result.packages.length, 5);
  }));

test('rejects a stale nested Adapter even when nominal packages match the bundle', () =>
  fixture(async ({ root, host, entry, bundle, makePackage, fingerprint }) => {
    const shadow = join(host, 'dist/node_modules/@codex-manage/codex-adapter');
    await makePackage(shadow, 'codex-adapter', {}, 'export const stale = true;\n');
    const shadowRoot = await realpath(shadow);
    const nominal = await readFile(
      join(root, 'node_modules/@codex-manage/codex-adapter/dist/index.js'),
    );
    assert.equal(sha256(JSON.stringify({ 'index.js': sha256(nominal) })), fingerprint);
    const result = await verifyHostRuntime({ entry, bundle });
    assert.equal(result.ok, false);
    assert(
      result.mismatches.some(
        (item) => item.name === '@codex-manage/codex-adapter' && item.root === shadowRoot,
      ),
    );
  }));

test('checks transitive shadow dependencies in addition to direct Host imports', () =>
  fixture(async ({ root, entry, bundle, makePackage }) => {
    const shadow = join(
      root,
      'node_modules/@codex-manage/codex-adapter/dist/node_modules/@codex-manage/protocol',
    );
    await makePackage(shadow, 'protocol', {}, 'export const staleProtocol = true;\n');
    const shadowRoot = await realpath(shadow);
    const result = await verifyHostRuntime({ entry, bundle });
    assert.equal(result.ok, false);
    assert.equal(
      result.packages.filter((item) => item.name === '@codex-manage/protocol').length,
      2,
    );
    assert(result.mismatches.some((item) => item.root === shadowRoot));
  }));
