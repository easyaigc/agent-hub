import test from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  writeFile,
  readFile,
  realpath,
  rm,
  lstat,
  symlink,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { recoverTaskHubBundle } from './task-hub-recover-bundle.mjs';
import {
  fileInventory,
  hash,
  verifyBundle,
} from '../skills/codex-host-connect/scripts/task-hub-core.mjs';

async function fixture(t) {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'task-hub-bundle-')));
  t.after(() => rm(root, { recursive: true, force: true }));
  const source = join(root, 'source');
  await mkdir(join(source, 'runtime/packages/pkg'), { recursive: true });
  await mkdir(join(source, 'runtime/node_modules'), { recursive: true });
  await writeFile(join(source, 'runtime/packages/pkg/index.cjs'), 'module.exports = 42;\n');
  await writeFile(join(source, 'runtime/node_modules/pkg'), '../packages/pkg');
  const files = await fileInventory(source);
  files['runtime/node_modules/pkg'] = { link: '../packages/pkg' };
  const manifest = {
    format: 1,
    contractVersion: '1',
    files,
    payloadSha256: hash(JSON.stringify(files)),
    sourceSha256: 'a'.repeat(64),
    primaryPlatforms: [process.platform],
    architecture: process.arch,
  };
  const bytes = JSON.stringify(manifest);
  await writeFile(join(source, 'manifest.json'), bytes);
  return { root, source, output: join(root, 'recovered'), manifestSha256: hash(bytes), manifest };
}

test('materialized relative links recover only into a new verified candidate', async (t) => {
  const f = await fixture(t);
  await assert.rejects(verifyBundle(f.source), /contents differ/);
  const result = await recoverTaskHubBundle(f);
  assert.equal(result.restoredLinks, 1);
  assert((await lstat(join(f.source, 'runtime/node_modules/pkg'))).isFile());
  assert((await lstat(join(f.output, 'runtime/node_modules/pkg'))).isSymbolicLink());
  assert.equal(
    await readFile(join(f.output, 'runtime/node_modules/pkg/index.cjs'), 'utf8'),
    'module.exports = 42;\n',
  );
  await verifyBundle(f.output);
  await assert.rejects(recoverTaskHubBundle(f), /already exists/);
});

test('wrong manifest, changed regular content and nonmatching link text are rejected', async (t) => {
  const f = await fixture(t);
  await assert.rejects(
    recoverTaskHubBundle({ ...f, manifestSha256: '0'.repeat(64) }),
    /independently frozen/,
  );
  const file = join(f.source, 'runtime/packages/pkg/index.cjs');
  const original = await readFile(file);
  await writeFile(file, 'changed');
  await assert.rejects(recoverTaskHubBundle(f), /Bundle file differs/);
  await writeFile(file, original);
  await writeFile(join(f.source, 'runtime/node_modules/pkg'), '../packages/other');
  await assert.rejects(recoverTaskHubBundle(f), /link text differs/);
  await assert.rejects(lstat(f.output), { code: 'ENOENT' });
});

test('declared escaping link, missing or extra entry and nested output fail closed', async (t) => {
  const f = await fixture(t);
  await assert.rejects(
    recoverTaskHubBundle({ ...f, output: join(f.source, 'nested') }),
    /separate trees/,
  );
  await symlink(f.source, join(f.root, 'alias'));
  await assert.rejects(
    recoverTaskHubBundle({ ...f, output: join(f.root, 'alias/nested') }),
    /separate physical trees/,
  );
  await writeFile(join(f.source, 'extra'), 'unreviewed');
  await assert.rejects(recoverTaskHubBundle(f), /Unexpected or missing/);
  await rm(join(f.source, 'extra'));
  f.manifest.files['runtime/node_modules/pkg'] = { link: '../../../outside' };
  f.manifest.payloadSha256 = hash(JSON.stringify(f.manifest.files));
  const bytes = JSON.stringify(f.manifest);
  await writeFile(join(f.source, 'manifest.json'), bytes);
  await writeFile(join(f.source, 'runtime/node_modules/pkg'), '../../../outside');
  await assert.rejects(recoverTaskHubBundle({ ...f, manifestSha256: hash(bytes) }), /Link escapes/);
  await assert.rejects(lstat(f.output), { code: 'ENOENT' });
});

test('valid internal links preserve their manifest through a separate copy', async (t) => {
  const f = await fixture(t);
  await rm(join(f.source, 'runtime/node_modules/pkg'));
  await symlink('../packages/pkg', join(f.source, 'runtime/node_modules/pkg'));
  await verifyBundle(f.source);
  assert.equal((await recoverTaskHubBundle(f)).restoredLinks, 0);
  await verifyBundle(f.output);
});
