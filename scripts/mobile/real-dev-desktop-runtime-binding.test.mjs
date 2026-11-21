import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  processIdentity,
  mappedExecutable,
  verifyProcessAndImage,
} from './real-dev-desktop-runtime-binding.mjs';
const path = '/trusted/desktop';
const before = { pid: 10, uid: 501, startedAtText: 'Fri Nov 13 01:00:00 2026', executable: path };
const stat = { dev: 17n, ino: 33n, size: 71n, mtimeNs: 1n, ctimeNs: 2n };
const image = { path, device: '17', inode: '33', size: '71' };
test('ps and mapped txt use the exact executable identity', () => {
  assert.deepEqual(processIdentity('10 501 Fri Nov 13 01:00:00 2026 /trusted/desktop'), before);
  assert.deepEqual(
    mappedExecutable('p10\nftxt\nD0x11\ni33\ns71\nn/trusted/desktop\n', path),
    image,
  );
  assert.equal(verifyProcessAndImage(before, before, image, stat, stat), true);
});
test('reused PID, changed start time or executable cannot verify a runtime', () => {
  for (const edit of [
    { pid: 11 },
    { startedAtText: 'Fri Nov 13 01:00:01 2026' },
    { executable: '/other' },
  ])
    assert.throws(() => verifyProcessAndImage(before, { ...before, ...edit }, image, stat, stat));
});
test('same file path with different mapped inode or device is rejected', () => {
  for (const edit of [{ inode: '34' }, { device: '18' }, { path: '/other' }])
    assert.throws(() => verifyProcessAndImage(before, before, { ...image, ...edit }, stat, stat));
});
test('an executable update during hashing cannot reuse earlier trust', () => {
  for (const key of ['dev', 'ino', 'size', 'mtimeNs', 'ctimeNs'])
    assert.throws(() =>
      verifyProcessAndImage(before, before, image, stat, { ...stat, [key]: stat[key] + 1n }),
    );
});
test('missing or ambiguous txt entries do not pick a convenient image', () => {
  assert.throws(() => mappedExecutable('ftxt\nD0x11\ni33\ns71\nn/other\n', path));
  assert.throws(() => mappedExecutable('ftxt\nn/trusted/desktop\nftxt\nn/trusted/desktop\n', path));
});
