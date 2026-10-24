import assert from 'node:assert/strict';
import test from 'node:test';
import { codexSystemEndpoint, parseCodexSystemEndpoints } from '../packages/protocol/dist/index.js';

test('Serve origins retain nonstandard ports and cannot grant system trust to arbitrary addresses', () => {
  assert.equal(
    codexSystemEndpoint('HTTPS://Office.tail.ts.net:08443/'),
    'https://office.tail.ts.net:8443',
  );
  assert.equal(codexSystemEndpoint('https://office.tail.ts.net:443'), 'https://office.tail.ts.net');
  assert.deepEqual(parseCodexSystemEndpoints(undefined), []);
  assert.deepEqual(
    parseCodexSystemEndpoints('["https://office.tail.ts.net/","https://office.tail.ts.net"]'),
    ['https://office.tail.ts.net'],
  );
  for (const invalid of [
    'http://office.tail.ts.net',
    'https://office.tail.ts.net.evil.com',
    'https://user@office.tail.ts.net',
    'https://office.tail.ts.net/?x=1',
    'https://office.tail.ts.net/path',
    'https://office.tail.ts.net:0',
    'https://office.tail.ts.net:65536',
    'https://192.168.1.2:8796',
    'https://office.local:8796',
  ])
    assert.throws(() => codexSystemEndpoint(invalid), invalid);
  for (const invalid of ['{}', '[1]', JSON.stringify(Array(5).fill('https://office.tail.ts.net'))])
    assert.throws(() => parseCodexSystemEndpoints(invalid));
});
