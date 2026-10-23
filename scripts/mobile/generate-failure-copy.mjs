import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';

// Presentation data only. Platform error classification stays in native adapters.
const source = 'design/mobile/connection-failures.json';
const data = JSON.parse(await readFile(source, 'utf8'));
assert.deepEqual(Object.keys(data).sort(), [
  'connectionTitle',
  'defaultTitle',
  'messages',
  'version',
]);
assert.equal(data.version, 1);
const kinds = ['secureConnection', 'certificate', 'noNetwork', 'timeout', 'unreachable', 'lost'];
assert.deepEqual(Object.keys(data.messages).sort(), [...kinds].sort());
for (const value of [data.defaultTitle, data.connectionTitle, ...Object.values(data.messages)]) {
  assert(
    typeof value === 'string' &&
      value.trim() &&
      ![...value].some((character) => character.charCodeAt(0) < 32),
  );
}
const literal = (value, kotlin = false) => {
  const text = JSON.stringify(value);
  return kotlin ? text.replaceAll('$', '\\$') : text;
};
const kotlinKind = (kind) => kind.replace(/[A-Z]/g, (letter) => '_' + letter).toUpperCase();
const header = `// Generated from ${source}; edit the definition, then regenerate.\n`;
const swift = `${header}enum SharedConnectionFailureKind {\n  case ${kinds.join(', ')}\n}\nenum SharedFailureCopy {\n  static let defaultTitle: LocalizedText = ${literal(data.defaultTitle)}\n  static let connectionTitle: LocalizedText = ${literal(data.connectionTitle)}\n  static func message(_ kind: SharedConnectionFailureKind) -> LocalizedText {\n    switch kind {\n${kinds.map((kind) => `    case .${kind}: return ${literal(data.messages[kind])}`).join('\n')}\n    }\n  }\n}\n`;
const kotlin = `${header}package com.agenthub.core\n\nenum class MobileConnectionFailureKind { ${kinds.map(kotlinKind).join(', ')} }\nobject FailureCopy {\n    const val defaultTitle = ${literal(data.defaultTitle, true)}\n    const val connectionTitle = ${literal(data.connectionTitle, true)}\n    fun message(kind: MobileConnectionFailureKind): String = when (kind) {\n${kinds.map((kind) => `        MobileConnectionFailureKind.${kotlinKind(kind)} -> ${literal(data.messages[kind], true)}`).join('\n')}\n    }\n}\n`;
for (const [path, output] of [
  ['apps/ios/Sources/SharedFailureCopy.swift', swift],
  ['packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/FailureCopy.kt', kotlin],
]) {
  if (process.argv.includes('--write')) await writeFile(path, output);
  else assert.equal(await readFile(path, 'utf8'), output, path + ' is stale');
}
console.log('Shared connection failure presentation generation checked');
