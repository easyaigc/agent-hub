import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { canonical, makeCodec, parseClientFrames, serverFrame } from './fixture.mjs';

// Importing the fixture does not start listeners, create certificates or contact devices.
const vector = JSON.parse(
  await readFile(
    new URL(
      '../../../../../../apps/android/src/androidTest/assets/easywork-crypto-vector.json',
      import.meta.url,
    ),
  ),
);

function maskedFrame(payload, opcode = 1, lengthBits = 0) {
  const bytes = Buffer.from(payload),
    mask = Buffer.from([0x12, 0x34, 0x56, 0x78]);
  const bits = lengthBits || (bytes.length < 126 ? 7 : 16);
  const header = Buffer.alloc(bits === 7 ? 2 : bits === 16 ? 4 : 10);
  header[0] = 0x80 | opcode;
  header[1] = 0x80 | (bits === 7 ? bytes.length : bits === 16 ? 126 : 127);
  if (bits === 16) header.writeUInt16BE(bytes.length, 2);
  if (bits === 64) header.writeBigUInt64BE(BigInt(bytes.length), 2);
  const masked = Buffer.from(bytes);
  for (let i = 0; i < bytes.length; i++) masked[i] ^= mask[i % 4];
  return Buffer.concat([header, mask, masked]);
}

test('independent synthetic desktop decrypts both established golden frames', () => {
  const codec = makeCodec(vector);
  try {
    assert.deepEqual(codec.open(vector.frame, 'desktop-to-mobile'), vector.payload);
    assert.deepEqual(codec.open(vector.legacy, 'desktop-to-mobile'), vector.payload);
  } finally {
    codec.dispose();
  }
});

test('directional synthetic frames authenticate their payload and original header', () => {
  const codec = makeCodec(vector);
  try {
    for (const direction of ['mobile-to-desktop', 'desktop-to-mobile']) {
      const payload = { kind: 'key-confirmation', value: vector.confirmation, unicode: '中文🙂' };
      const frame = codec.seal(payload, 'public-test-session', 1, direction);
      assert.deepEqual(codec.open(frame, direction), payload);
      assert.throws(() => codec.open({ ...frame, sequence: 2 }, direction));
      const bytes = Buffer.from(frame.ciphertext, 'base64url');
      bytes[0] ^= 1;
      assert.throws(() =>
        codec.open({ ...frame, ciphertext: bytes.toString('base64url') }, direction),
      );
      assert.throws(() =>
        codec.open(
          frame,
          direction === 'mobile-to-desktop' ? 'desktop-to-mobile' : 'mobile-to-desktop',
        ),
      );
    }
  } finally {
    codec.dispose();
  }
});

test('canonical intent uses sorted objects and preserves array order and Unicode', () => {
  assert.equal(
    canonical({ z: [3, { b: 2, a: '中文🙂' }], a: 1 }),
    '{"a":1,"z":[3,{"a":"中文🙂","b":2}]}',
  );
});

test('masked WebSocket frames survive every partial header/body split', () => {
  const expected = Buffer.from('中文🙂 encrypted fixture');
  for (const bits of [7, 16, 64]) {
    const wire = maskedFrame(expected, 1, bits);
    for (let split = 0; split < wire.length; split++) {
      const first = parseClientFrames(wire.subarray(0, split));
      assert.equal(first.frames.length, 0);
      const rest = parseClientFrames(Buffer.concat([first.remaining, wire.subarray(split)]));
      assert.equal(rest.frames.length, 1);
      assert.deepEqual(rest.frames[0].payload, expected);
      assert.equal(rest.remaining.length, 0);
    }
  }
});

test('WebSocket parser separates combined data, ping, pong and close frames', () => {
  const wire = Buffer.concat([
    maskedFrame('a'),
    maskedFrame('ping', 9),
    maskedFrame('', 10),
    maskedFrame('', 8),
  ]);
  const parsed = parseClientFrames(wire);
  assert.deepEqual(
    parsed.frames.map((frame) => frame.opcode),
    [1, 9, 10, 8],
  );
  assert.deepEqual(
    parsed.frames.map((frame) => frame.payload.toString()),
    ['a', 'ping', '', ''],
  );
  assert.equal(parsed.remaining.length, 0);
});

test('fixture framing refuses unmasked, fragmented, reserved and oversized input', () => {
  assert.throws(() => parseClientFrames(serverFrame('unmasked')));
  for (const first of [0x01, 0xc1, 0x83]) {
    const wire = maskedFrame('x');
    wire[0] = first;
    assert.throws(() => parseClientFrames(wire));
  }
  assert.throws(() => parseClientFrames(maskedFrame(Buffer.alloc(126), 9)));
  const huge = Buffer.from([0x81, 0xff, 0, 0, 0, 0, 0, 1, 0, 0]);
  assert.throws(() => parseClientFrames(huge));
  assert.throws(() => parseClientFrames(Buffer.alloc(131073)));
  assert.throws(() => serverFrame(Buffer.alloc(65536)));
});

test('fixture framing preserves bounded 16-bit messages and server frame lengths', () => {
  for (const size of [0, 125, 126, 4096, 65535]) {
    const payload = Buffer.alloc(size, 0xab),
      wire = serverFrame(payload);
    assert.equal(wire[0], 0x81);
    assert.equal(wire[1] & 0x80, 0);
    assert.equal(size < 126 ? wire[1] : wire.readUInt16BE(2), size);
    assert.deepEqual(parseClientFrames(maskedFrame(payload, 2)).frames[0].payload, payload);
  }
});
