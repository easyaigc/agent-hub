import assert from 'node:assert/strict';
import test from 'node:test';
import { isolateMainUUID } from './isolated-ios-runner.mjs';

const bundle = 'com.easyai.agenthub.hostkeydev.uitests.xctrunner';
function thin(uuid = 'e71c241a2fbd38b5aabe840359bcce5c') {
  const bytes = Buffer.alloc(80, 0xa5);
  bytes.fill(0, 0, 32);
  bytes.writeUInt32LE(0xfeedfacf, 0);
  bytes.writeUInt32LE(0x100000c, 4);
  bytes.writeUInt32LE(1, 16);
  bytes.writeUInt32LE(24, 20);
  bytes.writeUInt32LE(0x1b, 32);
  bytes.writeUInt32LE(24, 36);
  Buffer.from(uuid, 'hex').copy(bytes, 40);
  return bytes;
}

test('unique app identity changes only LC_UUID and preserves input code', () => {
  const input = thin();
  const original = Buffer.from(input);
  const result = isolateMainUUID(input, bundle);
  assert.equal(result.uuids[0].unique, '5c76def7-9129-59d7-8745-6c343038a6c9');
  assert.deepEqual(input, original);
  assert.deepEqual(result.buffer.subarray(0, 40), original.subarray(0, 40));
  assert.deepEqual(result.buffer.subarray(56), original.subarray(56));
  assert.deepEqual(isolateMainUUID(input, bundle).buffer, result.buffer);
  assert.notEqual(
    isolateMainUUID(input, 'com.easyai.agenthub.otherdev.uitests.xctrunner').uuids[0].unique,
    result.uuids[0].unique,
  );
});

test('Xcode single-slice fat Runner preserves its wrapper and code', () => {
  const input = Buffer.alloc(112);
  input.writeUInt32BE(0xcafebabe, 0);
  input.writeUInt32BE(1, 4);
  input.writeUInt32BE(0x100000c, 8);
  input.writeUInt32BE(32, 16);
  input.writeUInt32BE(80, 20);
  thin().copy(input, 32);
  const result = isolateMainUUID(input, bundle);
  assert.equal(result.uuids[0].offset, 72);
  assert.deepEqual(result.buffer.subarray(0, 72), input.subarray(0, 72));
  assert.deepEqual(result.buffer.subarray(88), input.subarray(88));
});

test('fat64 slices are independently validated and isolated', () => {
  const input = Buffer.alloc(240);
  input.writeUInt32BE(0xcafebabf, 0);
  input.writeUInt32BE(2, 4);
  for (const [index, offset] of [80, 160].entries()) {
    input.writeUInt32BE(0x100000c, 8 + index * 32);
    input.writeBigUInt64BE(BigInt(offset), 16 + index * 32);
    input.writeBigUInt64BE(80n, 24 + index * 32);
    thin(index === 0 ? undefined : 'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa').copy(input, offset);
  }
  const result = isolateMainUUID(input, bundle);
  assert.equal(new Set(result.uuids.map((row) => row.unique)).size, 2);
  assert.deepEqual(result.buffer.subarray(0, 80), input.subarray(0, 80));
  input.writeBigUInt64BE(100n, 48);
  assert.throws(() => isolateMainUUID(input, bundle), /Overlapping/);
});

test('malformed or non-Runner input is rejected without changing bytes', () => {
  assert.throws(() => isolateMainUUID(thin(), 'com.easyai.agenthub'), /Only an XCTest/);
  for (const mutate of [
    (bytes) => bytes.writeUInt32LE(0, 0),
    (bytes) => bytes.writeUInt32LE(7, 4),
    (bytes) => bytes.writeUInt32LE(2, 16),
    (bytes) => bytes.writeUInt32LE(0xffffffff, 20),
    (bytes) => bytes.writeUInt32LE(0, 36),
    (bytes) => bytes.writeUInt32LE(1, 32),
  ]) {
    const bytes = thin();
    mutate(bytes);
    const before = Buffer.from(bytes);
    assert.throws(() => isolateMainUUID(bytes, bundle));
    assert.deepEqual(bytes, before);
  }
});
