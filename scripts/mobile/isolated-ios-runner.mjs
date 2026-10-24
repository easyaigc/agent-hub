import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';

const uuidString = (bytes) => {
  const hex = bytes.toString('hex');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join('-');
};

// TN3178 / TN3179: distinct apps must not share their main executable UUID.
// Xcode copies the same prebuilt XCTRunner into different UI test apps. Stage
// only our development Runner, preserve its code and permissions, then re-sign.
export function isolateMainUUID(input, bundleId) {
  assert(bundleId.endsWith('.xctrunner'), 'Only an XCTest Runner may be staged');
  assert(input.length >= 32, 'Truncated Mach-O');
  const slices = [];
  const magic = input.readUInt32BE(0);
  if (magic === 0xcafebabe || magic === 0xcafebabf) {
    const count = input.readUInt32BE(4);
    const width = magic === 0xcafebabe ? 20 : 32;
    assert(count > 0 && count <= 16 && 8 + count * width <= input.length, 'Invalid fat header');
    for (let index = 0; index < count; index++) {
      const at = 8 + index * width;
      const offset =
        width === 20 ? input.readUInt32BE(at + 8) : Number(input.readBigUInt64BE(at + 8));
      const size =
        width === 20 ? input.readUInt32BE(at + 12) : Number(input.readBigUInt64BE(at + 16));
      assert(Number.isSafeInteger(offset) && Number.isSafeInteger(size), 'Invalid slice range');
      assert(
        offset >= 8 + count * width && size >= 32 && offset + size <= input.length,
        'Invalid slice bounds',
      );
      slices.push({ offset, size });
    }
    const ordered = slices.toSorted((a, b) => a.offset - b.offset);
    for (let index = 1; index < ordered.length; index++) {
      assert(
        ordered[index - 1].offset + ordered[index - 1].size <= ordered[index].offset,
        'Overlapping slices',
      );
    }
  } else {
    slices.push({ offset: 0, size: input.length });
  }
  const uuids = [];
  for (const slice of slices) {
    const base = slice.offset;
    assert(input.readUInt32LE(base) === 0xfeedfacf, 'Require a 64-bit little-endian Mach-O');
    assert(input.readUInt32LE(base + 4) === 0x100000c, 'Require a physical arm64 Runner');
    const count = input.readUInt32LE(base + 16);
    const end = base + 32 + input.readUInt32LE(base + 20);
    assert(count > 0 && end <= base + slice.size, 'Invalid load commands');
    let at = base + 32;
    const matches = [];
    for (let index = 0; index < count; index++) {
      assert(at + 8 <= end, 'Truncated load command');
      const command = input.readUInt32LE(at);
      const size = input.readUInt32LE(at + 4);
      assert(size >= 8 && at + size <= end, 'Invalid load command size');
      if (command === 0x1b) {
        assert(size === 24, 'Invalid LC_UUID');
        matches.push(at + 8);
      }
      at += size;
    }
    assert(matches.length === 1, 'Require exactly one LC_UUID per slice');
    const offset = matches[0];
    const original = input.subarray(offset, offset + 16);
    const unique = createHash('sha1').update(original).update(bundleId).digest().subarray(0, 16);
    unique[6] = (unique[6] & 0x0f) | 0x50;
    unique[8] = (unique[8] & 0x3f) | 0x80;
    uuids.push({
      offset,
      original: uuidString(original),
      unique: uuidString(unique),
      bytes: unique,
    });
  }
  assert(new Set(uuids.map((row) => row.unique)).size === uuids.length, 'Duplicate slice UUIDs');
  const buffer = Buffer.from(input);
  for (const row of uuids) row.bytes.copy(buffer, row.offset);
  return {
    buffer,
    uuids: uuids.map((row) => ({ offset: row.offset, original: row.original, unique: row.unique })),
  };
}

const run = (command, args) => execFileSync(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
const plist = (path) => JSON.parse(run('/usr/bin/plutil', ['-convert', 'json', '-o', '-', path]));
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

export async function prepareIsolatedIOSRunner({ template, directory, expectedAppBundleId }) {
  assert(process.platform === 'darwin', 'Requires Xcode on macOS');
  assert(
    expectedAppBundleId?.startsWith('com.easyai.agenthub.'),
    'Require an explicit independent App identity',
  );
  template = resolve(template);
  directory = resolve(directory);
  const configuration = plist(template);
  const targets = Object.entries(configuration).filter(
    ([key, value]) => key !== '__xctestrun_metadata__' && value?.IsUITestBundle,
  );
  assert(targets.length === 1, 'Require exactly one UI test target');
  const [, target] = targets[0];
  assert(!target.UseDestinationArtifacts, 'Require original build artifacts');
  const expand = (path) => path.replaceAll('__TESTROOT__', dirname(template));
  const app = plist(join(expand(target.UITargetAppPath), 'Info.plist'));
  assert.equal(
    app.CFBundleIdentifier,
    expectedAppBundleId,
    'Refuse another App, including the formal App',
  );
  const original = expand(target.TestHostPath);
  const info = plist(join(original, 'Info.plist'));
  assert(info.CFBundleSupportedPlatforms?.includes('iPhoneOS'), 'Require a physical iOS Runner');
  assert.equal(info.CFBundleIdentifier, target.TestHostBundleIdentifier);
  assert(
    info.CFBundleIdentifier.startsWith(expectedAppBundleId + '.') &&
      info.CFBundleIdentifier.endsWith('.xctrunner'),
  );
  await mkdir(directory, { mode: 0o700 });
  const entitlementsPath = join(directory, 'entitlements.plist');
  await writeFile(entitlementsPath, run('codesign', ['-d', '--entitlements', ':-', original]), {
    mode: 0o600,
  });
  const entitlements = plist(entitlementsPath);
  assert.equal(entitlements['get-task-allow'], true, 'Require a development signature');
  assert.equal(
    entitlements['application-identifier'],
    entitlements['com.apple.developer.team-identifier'] + '.' + info.CFBundleIdentifier,
  );
  const destination = join(directory, 'Runner.app');
  await cp(original, destination, {
    recursive: true,
    dereference: false,
    force: false,
    errorOnExist: true,
  });
  const executable = join(destination, info.CFBundleExecutable);
  const before = await readFile(executable);
  const isolated = isolateMainUUID(before, info.CFBundleIdentifier);
  await writeFile(executable, isolated.buffer);
  const certificate = join(directory, 'certificate');
  run('codesign', ['-d', '--extract-certificates=' + certificate, original]);
  const identity = createHash('sha1')
    .update(await readFile(certificate + '0'))
    .digest('hex');
  run('codesign', [
    '--force',
    '--sign',
    identity,
    '--entitlements',
    entitlementsPath,
    '--preserve-metadata=identifier,requirements,flags',
    destination,
  ]);
  run('codesign', ['--verify', '--deep', '--strict', destination]);
  const afterEntitlements = join(directory, 'signed-entitlements.plist');
  await writeFile(afterEntitlements, run('codesign', ['-d', '--entitlements', ':-', destination]), {
    mode: 0o600,
  });
  assert.deepEqual(plist(afterEntitlements), entitlements, 'Permissions must remain identical');
  assert.equal(
    sha256(await readFile(join(original, info.CFBundleExecutable))),
    sha256(before),
    'Original Runner must remain unchanged',
  );
  const oldPath = target.TestHostPath;
  const replace = (value) => {
    if (typeof value === 'string')
      return value.replaceAll(oldPath, destination).replaceAll(original, destination);
    if (Array.isArray(value)) return value.map(replace);
    if (value && typeof value === 'object')
      return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, replace(entry)]));
    return value;
  };
  configuration[targets[0][0]] = replace(target);
  configuration[targets[0][0]].TestHostPath = destination;
  const output = join(dirname(template), 'IsolatedRunner-' + randomUUID() + '.xctestrun');
  await writeFile(output, JSON.stringify(configuration), { mode: 0o600 });
  run('/usr/bin/plutil', ['-convert', 'xml1', output]);
  const receipt = {
    originalUUIDs: isolated.uuids,
    bundleId: info.CFBundleIdentifier,
    originalSHA256: sha256(before),
    signedSHA256: sha256(await readFile(executable)),
    originalPreserved: true,
    entitlementsPreserved: true,
    testAppBundleId: expectedAppBundleId,
    template: output,
  };
  await writeFile(join(directory, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n', {
    mode: 0o600,
  });
  return { template: output, receipt };
}
