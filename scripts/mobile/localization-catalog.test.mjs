import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  parseCatalog,
  resourceName,
  androidCatalog,
  mergeNativeCatalogs,
} from './localization-catalog.mjs';

test('catalog parsing keeps interpolation and escaped content separate', () => {
  const entries = parseCatalog('// comment\n"%@，100%%" = "%@, 100%%";\n"路径\\n" = "Path\\n";');
  assert.equal(entries.get('%@，100%%'), '%@, 100%%');
  assert.equal(entries.get('路径\n'), 'Path\n');
  assert.equal(resourceName('设置'), resourceName('设置'));
  assert.notEqual(resourceName('设置'), resourceName('Settings'));
  const xml = androidCatalog(new Map([['key', ' <&> "quoted" \'literal\'\n@not_a_reference ']]));
  assert(xml.includes('&lt;&amp;&gt;'));
  assert(xml.includes('\\"quoted\\"'));
  assert(xml.includes('\\n@not_a_reference '));
  assert(xml.includes('formatted="false"'));
});

test('duplicate keys, malformed files and changed format arguments are rejected', () => {
  for (const source of ['"a"="b";"a"="c";', '"a"="b"; garbage', '"%@"="missing";', '"%d"="%d";']) {
    assert.throws(() => parseCatalog(source));
  }
});

test('native copy extends Android without mutating or adding keys to shared catalogs', () => {
  const shared = [new Map([['Shared', 'Shared']]), new Map([['Shared', '共同']])];
  const result = mergeNativeCatalogs(shared, {
    schemaVersion: 1,
    entries: [{ key: '本机 %@', en: 'Local %@', zhHans: '本机 %@' }],
  });
  assert.equal(result[0].get('本机 %@'), 'Local %@');
  assert.equal(result[1].get('本机 %@'), '本机 %@');
  assert.equal(result[0].get('Shared'), 'Shared');
  assert.equal(result[1].get('Shared'), '共同');
  for (const base of shared) {
    assert.equal(base.size, 1);
    assert.equal(base.has('本机 %@'), false);
  }
});

test('native copy rejects shared overrides, duplicates, corrupt formats and unknown fields', () => {
  const shared = () => [new Map([['Shared', 'Shared']]), new Map([['Shared', '共同']])];
  const valid = { key: '本机 %@', en: 'Local %@', zhHans: '本机 %@' };
  const rows = [
    [{ ...valid, key: 'Shared', en: 'Override', zhHans: 'Override' }],
    [valid, valid],
    [{ ...valid, en: 'Missing argument' }],
    [{ ...valid, en: 'Local %d' }],
    [{ ...valid, provider: 'runtime value' }],
    [{ key: '本机 %@', en: 'Local %@' }],
  ];
  for (const entries of rows)
    assert.throws(() => mergeNativeCatalogs(shared(), { schemaVersion: 1, entries }));
  assert.throws(() => mergeNativeCatalogs(shared(), { schemaVersion: 2, entries: [valid] }));
  assert.throws(() => mergeNativeCatalogs(shared(), { schemaVersion: 1, entries: {} }));
  assert.throws(() =>
    mergeNativeCatalogs([new Map(), new Map([['Shared', '共同']])], {
      schemaVersion: 1,
      entries: [valid],
    }),
  );
});
