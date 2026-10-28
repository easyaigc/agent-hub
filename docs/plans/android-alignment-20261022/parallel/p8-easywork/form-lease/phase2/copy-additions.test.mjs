import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  parseCatalog,
  mergeNativeCatalogs,
  androidCatalog,
} from '../../../../../../../scripts/mobile/localization-catalog.mjs';
const root = new URL('../../../../../../../', import.meta.url);
const read = async (path) => readFile(new URL(path, root), 'utf8');
const shared = await Promise.all(
  ['en', 'zh-Hans'].map(async (x) =>
    parseCatalog(await read(`apps/ios/Resources/${x}.lproj/Localizable.strings`)),
  ),
);
const source = JSON.parse(await read('apps/android/localization/native-copy.json'));
const added = JSON.parse(
  await readFile(new URL('native-copy-additions.json', import.meta.url), 'utf8'),
);
const merged = mergeNativeCatalogs(shared, source);
test('reviewed additions are present in both actual merged source catalogs', () => {
  assert.equal(added.entries.length, 14);
  for (const row of added.entries) {
    assert.equal(merged[0].get(row.key), row.en);
    assert.equal(merged[1].get(row.key), row.zhHans);
  }
});
test('verification code keeps exactly one normal catalog format argument', () => {
  const row = added.entries.find((x) => x.key.includes('核对码'));
  assert(row);
  assert(row.key.endsWith('%@'));
  assert(row.en.endsWith('%@'));
  assert(row.zhHans.endsWith('%@'));
  const broken = structuredClone(source);
  broken.entries.find((x) => x.key === row.key).en = 'Missing format argument';
  assert.throws(() => mergeNativeCatalogs(shared, broken));
});
test('a native addition cannot silently override an existing shared translation', () => {
  const broken = structuredClone(source);
  const key = [...shared[0].keys()][0];
  broken.entries.push({ key, en: 'override', zhHans: 'override' });
  assert.throws(() => mergeNativeCatalogs(shared, broken));
});
test('the existing resource generator renders unique reviewed addition names in each language', () => {
  const en = androidCatalog(new Map(added.entries.map((x) => [x.key, x.en]))),
    zh = androidCatalog(new Map(added.entries.map((x) => [x.key, x.zhHans])));
  assert.equal((en.match(/<string name=/g) || []).length, 14);
  assert.equal((zh.match(/<string name=/g) || []).length, 14);
  assert(en.includes('Verification code: %@'));
  assert(zh.includes('核对码：%@'));
});
