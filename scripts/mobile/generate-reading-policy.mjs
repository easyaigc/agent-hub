import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';

// Small typed expression tree: one definition emits both native languages.
const source = 'packages/protocol/fixtures/mobile/reading-policy.json';
const policy = JSON.parse(await readFile(source, 'utf8'));
assert.equal(policy.version, 1);
function expressionType(value, parameters) {
  if (typeof value === 'boolean') return 'boolean';
  if (Number.isSafeInteger(value)) return 'integer';
  if (typeof value === 'string') {
    assert(Object.hasOwn(parameters, value));
    return parameters[value];
  }
  assert(Array.isArray(value));
  const [op, ...args] = value;
  const types = args.map((arg) => expressionType(arg, parameters));
  if (op === 'if') {
    assert.equal(args.length, 3);
    assert.equal(types[0], 'boolean');
    assert.equal(types[1], types[2]);
    return types[1];
  }
  if (op === 'not') {
    assert.equal(args.length, 1);
    assert.equal(types[0], 'boolean');
    return 'boolean';
  }
  assert.equal(args.length, 2);
  if (op === 'and' || op === 'or') {
    types.forEach((type) => assert.equal(type, 'boolean'));
    return 'boolean';
  }
  assert(op === '+' || op === '<');
  types.forEach((type) => assert.equal(type, 'integer'));
  return op === '+' ? 'integer' : 'boolean';
}
function expression(value, parameters, language) {
  if (typeof value === 'boolean' || Number.isSafeInteger(value)) return String(value);
  if (typeof value === 'string') {
    assert(Object.hasOwn(parameters, value));
    return value;
  }
  assert(Array.isArray(value));
  const [op, ...args] = value;
  const values = args.map((arg) => expression(arg, parameters, language));
  if (op === 'if') {
    assert.equal(args.length, 3);
    return language === 'swift'
      ? `(${values[0]} ? ${values[1]} : ${values[2]})`
      : `(if (${values[0]}) ${values[1]} else ${values[2]})`;
  }
  if (op === 'not') {
    assert.equal(args.length, 1);
    return `(!${values[0]})`;
  }
  const symbol = { and: '&&', or: '||', '+': '+', '<': '<' }[op];
  assert(symbol);
  assert.equal(args.length, 2);
  return `(${values[0]} ${symbol} ${values[1]})`;
}
const names = new Set();
function render(language) {
  const types =
    language === 'swift'
      ? { integer: 'Int64', boolean: 'Bool' }
      : { integer: 'Long', boolean: 'Boolean' };
  const methods = policy.functions
    .map((fn) => {
      assert(/^[a-zA-Z][a-zA-Z0-9]*$/.test(fn.name));
      if (language === 'swift') {
        assert(!names.has(fn.name));
        names.add(fn.name);
      }
      assert(types[fn.returns]);
      assert.equal(expressionType(fn.expression, fn.parameters), fn.returns);
      const params = Object.entries(fn.parameters)
        .map(([name, type]) => {
          assert(/^[a-zA-Z][a-zA-Z0-9]*$/.test(name) && types[type]);
          return `${name}: ${types[type]}`;
        })
        .join(', ');
      const body = expression(fn.expression, fn.parameters, language);
      return language === 'swift'
        ? `  static func ${fn.name}(${params}) -> ${types[fn.returns]} { ${body} }`
        : `    fun ${fn.name}(${params}): ${types[fn.returns]} = ${body}`;
    })
    .join('\n');
  const header = `// Generated from ${source}; edit the definition, then regenerate.\n`;
  return language === 'swift'
    ? header + `enum SharedReadingPolicy {\n${methods}\n}\n`
    : header + `package com.agenthub.core\n\nobject ReadingPolicy {\n${methods}\n}\n`;
}
for (const [language, path] of [
  ['swift', 'apps/ios/Sources/SharedReadingPolicy.swift'],
  ['kotlin', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ReadingPolicy.kt'],
]) {
  const output = render(language);
  if (process.argv.includes('--write')) await writeFile(path, output);
  else assert.equal(await readFile(path, 'utf8'), output, path + ' is stale');
}
console.log('Shared reading policy generation checked');
