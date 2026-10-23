import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { validateManifest, validateChangeCoverage, renderManifest } from './parity.mjs';

const manifest = JSON.parse(await readFile('docs/product/mobile-parity.manifest.json', 'utf8'));
const template = await readFile('docs/product/mobile-parity.template.md', 'utf8');
const copy = () => structuredClone(manifest);

test('existing product scope cannot disappear during a platform migration', () => {
  const input = copy();
  input.features = input.features.filter((feature) => feature.id !== 'F58');
  assert.throws(() => validateManifest(input), /Existing scope removed/);
});
test('unknown scenario and unsafe source references fail before rendering', () => {
  const input = copy();
  input.features[0].scenarios = ['S99'];
  assert.throws(() => validateManifest(input));
  input.features[0].scenarios = ['S01'];
  input.sourceAliases.UI = ['apps/../private/config'];
  assert.throws(() => validateManifest(input));
});
test('an unimplemented Android feature cannot be declared verified', () => {
  const input = copy();
  input.features[0].implementation.android = 'planned';
  input.features[0].verification.android = 'passed';
  assert.throws(() => validateManifest(input), /Code presence is not verified delivery/);
});
test('new Android behavior requires a feature change record for the actual bytes', () => {
  const input = copy();
  const file = 'apps/android/src/main/kotlin/NewBehavior.kt';
  const digest = 'a'.repeat(64);
  const hashes = new Map([[file, digest]]);
  assert.throws(() => validateChangeCoverage(input, [file], hashes), /missing or stale/);
  input.changes.push({
    id: 'example-change',
    baseCommit: '595deca',
    features: ['F39'],
    summary: 'Change shared composer behavior on both platforms',
    files: { [file]: digest },
  });
  validateManifest(input);
  validateChangeCoverage(input, [file], hashes);
  assert.throws(
    () => validateChangeCoverage(input, [file], new Map([[file, 'b'.repeat(64)]])),
    /missing or stale/,
  );
  assert.throws(
    () => validateChangeCoverage(input, [file], new Map()),
    /Missing current source digest/,
  );
});
test('shared iOS behavior is governed even when an Android screen is still planned', () => {
  const input = copy();
  const file = 'apps/ios/Sources/SessionStore.swift';
  assert.throws(
    () => validateChangeCoverage(input, [file], new Map([[file, 'c'.repeat(64)]])),
    /missing or stale/,
  );
});
test('generated table reflects a changed assertion and has exactly one insertion boundary', async () => {
  const current = await renderManifest(manifest, template);
  assert.equal(current, await readFile('docs/product/mobile-parity.md', 'utf8'));
  const input = copy();
  input.features[0].assertion += '；新增断言必须覆盖另一端。';
  assert.notEqual(await renderManifest(input, template), current);
  await assert.rejects(
    renderManifest(input, template.replace('<!-- mobile-parity:generated-tables -->', '')),
    /generated table marker/,
  );
});
