import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

// Only app-authored catalog keys enter this path. Runtime values stay arguments.
export function parseCatalog(source) {
  const entries = new Map();
  const token =
    /\s+|\/\/[^\n]*(?:\n|$)|\/\*[\s\S]*?\*\/|("(?:[^"\\]|\\.)*")\s*=\s*("(?:[^"\\]|\\.)*")\s*;/gy;
  let offset = 0;
  while (offset < source.length) {
    token.lastIndex = offset;
    const match = token.exec(source);
    assert(match, `Invalid catalog syntax at offset ${offset}`);
    offset = token.lastIndex;
    if (!match[1]) continue;
    const key = JSON.parse(match[1]);
    const value = JSON.parse(match[2]);
    assert(!entries.has(key), 'Duplicate localization key');
    assert.equal(placeholders(key), placeholders(value), 'Changed localization arguments');
    entries.set(key, value);
  }
  return entries;
}

// Platform-owned copy extends Android without adding new lookup keys to iOS.
// A shared key can only change in its original catalog, never through this file.
export function mergeNativeCatalogs(catalogs, supplement) {
  assert(Array.isArray(catalogs) && catalogs.length === 2);
  assert(catalogs.every((catalog) => catalog instanceof Map));
  assert.deepEqual([...catalogs[0].keys()].sort(), [...catalogs[1].keys()].sort());
  assert(supplement && typeof supplement === 'object' && !Array.isArray(supplement));
  assert.deepEqual(Object.keys(supplement).sort(), ['entries', 'schemaVersion']);
  assert.equal(supplement.schemaVersion, 1);
  assert(Array.isArray(supplement.entries) && supplement.entries.length > 0);
  for (const row of supplement.entries) {
    assert(row && typeof row === 'object' && !Array.isArray(row));
    assert.deepEqual(Object.keys(row).sort(), ['en', 'key', 'zhHans']);
    assert(Object.values(row).every((value) => typeof value === 'string'));
    assert(row.key.trim().length > 0);
    assert(
      catalogs.every((catalog) => !catalog.has(row.key)),
      'Native copy overrides a shared key',
    );
  }
  return ['en', 'zhHans'].map((language, index) => {
    // Reuse duplicate/format validation before constructing a Map.
    const native = parseCatalog(
      supplement.entries
        .map((row) => JSON.stringify(row.key) + '=' + JSON.stringify(row[language]) + ';')
        .join('\n'),
    );
    return new Map([...catalogs[index], ...native]);
  });
}

function placeholders(text) {
  let count = 0;
  for (let i = 0; i < text.length; i++) {
    if (text[i] !== '%') continue;
    const next = text[++i];
    assert(next === '%' || next === '@', 'Unsupported localization format');
    if (next === '@') count++;
  }
  return count;
}

export const resourceName = (key) =>
  'product_' + createHash('sha256').update(key, 'utf8').digest('hex').slice(0, 24);

// Android preserves whitespace inside quoted resources. Escape for both its
// resource-string grammar and XML, with formatting handled by the native adapter.
export function resourceValue(value) {
  return (
    '"' +
    value
      .replaceAll('\\', '\\\\')
      .replaceAll('"', '\\"')
      .replaceAll("'", "\\'")
      .replaceAll('\n', '\\n')
      .replaceAll('\r', '\\r')
      .replaceAll('\t', '\\t')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;') +
    '"'
  );
}

export function androidCatalog(entries) {
  const names = new Set();
  const rows = [...entries].map(([key, value]) => {
    const name = resourceName(key);
    assert(!names.has(name), 'Localization resource collision');
    names.add(name);
    return `    <string name="${name}" formatted="false">${resourceValue(value)}</string>`;
  });
  return (
    '<!-- Generated from shared iOS catalogs and Android native-copy.json; do not edit. -->\n' +
    '<resources>\n' +
    rows.join('\n') +
    '\n</resources>\n'
  );
}
