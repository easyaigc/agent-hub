import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, relative } from 'node:path';

const reportPath = process.argv[2];
assert(reportPath, 'Pass the complete A0 simulator report.json');
const report = JSON.parse(await readFile(reportPath, 'utf8'));
assert.equal(report.phase, 'A0');
assert.equal(report.scope, 'complete-baseline');
assert.equal(report.status, 'passed');
assert(report.groups.length === 14 && report.groups.every((group) => group.passed));
const destination = 'design/mobile/ios-baseline';
await mkdir(destination, { recursive: true });
const images = [];

function pngOrientation(bytes) {
  for (let offset = 8; offset + 12 <= bytes.length;) {
    const length = bytes.readUInt32BE(offset);
    const type = bytes.toString('ascii', offset + 4, offset + 8);
    assert(offset + length + 12 <= bytes.length, 'Truncated PNG chunk');
    if (type === 'eXIf') {
      let tiff = bytes.subarray(offset + 8, offset + 8 + length);
      if (tiff.subarray(0, 6).equals(Buffer.from('Exif\0\0'))) tiff = tiff.subarray(6);
      const littleEndian = tiff.toString('ascii', 0, 2) === 'II';
      assert(littleEndian || tiff.toString('ascii', 0, 2) === 'MM', 'Invalid EXIF byte order');
      const uint16 = (at) => (littleEndian ? tiff.readUInt16LE(at) : tiff.readUInt16BE(at));
      const uint32 = (at) => (littleEndian ? tiff.readUInt32LE(at) : tiff.readUInt32BE(at));
      assert.equal(uint16(2), 42);
      const ifd = uint32(4);
      for (let entry = 0; entry < uint16(ifd); entry++) {
        const at = ifd + 2 + entry * 12;
        if (uint16(at) !== 274) continue;
        assert.equal(uint16(at + 2), 3);
        assert.equal(uint32(at + 4), 1);
        const orientation = uint16(at + 8);
        assert(orientation >= 1 && orientation <= 8);
        return orientation;
      }
    }
    offset += length + 12;
  }
  return 1;
}

for (const group of report.groups) {
  const directory = join(dirname(reportPath), 'attachments', group.group);
  execFileSync(
    'xcrun',
    ['xcresulttool', 'export', 'attachments', '--path', group.result, '--output-path', directory],
    { stdio: 'ignore' },
  );
  const tests = JSON.parse(await readFile(join(directory, 'manifest.json'), 'utf8'));
  for (const test of tests) {
    for (const attachment of test.attachments.sort(
      (left, right) => left.timestamp - right.timestamp,
    )) {
      const label = attachment.suggestedHumanReadableName;
      // Only explicitly named product shots from our synthetic tests. Automatic
      // diagnostics, recordings and failures remain in the private evidence.
      if (
        attachment.isAssociatedWithFailure ||
        !label.endsWith('.png') ||
        label.startsWith('Screenshot')
      )
        continue;
      const name = label.replace(/_\d+_[A-F0-9-]+\.png$/, '').replace(/\.png$/, '');
      assert(/^[a-z0-9-]+$/i.test(name), 'Unexpected screenshot label');
      const testName = test.testIdentifier.split('/').at(-1).replace(/\(\)$/, '');
      assert(/^[a-z0-9]+$/i.test(testName), 'Unexpected test identifier');
      const occurrence =
        1 +
        images.filter(
          (image) =>
            image.group === group.group &&
            image.test === test.testIdentifier &&
            image.state === name,
        ).length;
      const file = group.group + '--' + testName + '--' + name + '--' + occurrence + '.png';
      const source = join(directory, attachment.exportedFileName);
      assert(!images.some((image) => image.file === file), 'Duplicate screenshot state: ' + file);
      const bytes = await readFile(source);
      const encodedPixels = { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
      const exifOrientation = pngOrientation(bytes);
      const pixels =
        exifOrientation >= 5
          ? { width: encodedPixels.height, height: encodedPixels.width }
          : encodedPixels;
      await copyFile(source, join(destination, file));
      images.push({
        file,
        state: name,
        purpose: name === 'floating-chat-jump-latest' ? 'diagnostic-only' : 'product-reference',
        occurrence,
        group: group.group,
        test: test.testIdentifier,
        encodedPixels,
        exifOrientation,
        pixels,
        orientation: pixels.width > pixels.height ? 'landscape' : 'portrait',
        sha256: createHash('sha256').update(bytes).digest('hex'),
      });
    }
  }
}
assert(images.length >= 12, 'Baseline must contain the explicit product states');
const index = {
  version: 1,
  sourceSha256: report.sourceSha256,
  device: 'iPhone 17 Pro',
  os: 'iOS 27',
  dimensions: { widthPt: 402, heightPt: 874, scale: 3 },
  data: 'Synthetic demo and local fixture inputs only',
  status: 'pending-visual-review',
  comparison: 'Compare product regions; system time and system pickers are separate',
  images,
};
await writeFile(join(destination, 'index.json'), JSON.stringify(index, null, 2) + '\n');
console.log('Exported', images.length, 'synthetic states:', relative(process.cwd(), destination));
