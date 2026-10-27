import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { androidCatalog, parseCatalog, mergeNativeCatalogs } from './localization-catalog.mjs';

const sharedCatalogs = await Promise.all(
  ['en', 'zh-Hans'].map(async (language) =>
    parseCatalog(
      await readFile(`apps/ios/Resources/${language}.lproj/Localizable.strings`, 'utf8'),
    ),
  ),
);
const catalogs = mergeNativeCatalogs(
  sharedCatalogs,
  JSON.parse(await readFile('apps/android/localization/native-copy.json', 'utf8')),
);
assert.deepEqual(
  [...catalogs[0].keys()].sort(),
  [...catalogs[1].keys()].sort(),
  'Language keys differ',
);
for (const [index, directory] of ['values', 'values-zh'].entries()) {
  const path = `apps/android/src/main/res/${directory}/product_strings.xml`;
  const output = androidCatalog(catalogs[index]);
  if (process.argv.includes('--write')) {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, output);
  } else assert.equal(await readFile(path, 'utf8'), output, path + ' is stale');
}
const fixture = 'apps/android/src/androidTest/assets/localization-catalog.json';
const expected =
  JSON.stringify(
    Object.fromEntries(
      ['en', 'zh-Hans'].map((language, index) => [language, Object.fromEntries(catalogs[index])]),
    ),
    null,
    2,
  ) + '\n';
if (process.argv.includes('--write')) {
  await mkdir(dirname(fixture), { recursive: true });
  await writeFile(fixture, expected);
} else assert.equal(await readFile(fixture, 'utf8'), expected, fixture + ' is stale');
console.log(`Android native catalogs synchronized: ${catalogs[0].size} keys in each language`);
