import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Swift accepts duplicate switch patterns as warnings; the later route is unreachable.
// Fail the normal check before a provider can silently open an empty sheet.
const source = await readFile('apps/ios/Sources/RootView.swift', 'utf8');
const start = source.indexOf('private func sheets(_ route: AppSheet)');
assert(start >= 0, 'Missing native sheet router');
const switchStart = source.indexOf('switch route {', start);
assert(switchStart > start, 'Missing native sheet route switch');
const end = source.indexOf('\n  }', switchStart);
assert(end > switchStart, 'Missing native sheet route switch boundary');
const cases = [...source.slice(switchStart, end).matchAll(/\bcase\s+\.([A-Za-z0-9_]+)\s*:/g)].map(
  (match) => match[1],
);
assert(cases.length > 0, 'Missing explicit native sheet routes');
const seen = new Set();
for (const route of cases) {
  assert(!seen.has(route), `Duplicate native sheet route: ${route}`);
  seen.add(route);
}
console.log(`Native sheet routes: ${cases.length} unique entries; no shadowed cases`);
