import assert from 'node:assert/strict';
import { cp, lstat, readFile, realpath, symlink, unlink } from 'node:fs/promises';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  fileInventory,
  hash,
  verifyBundle,
} from '../skills/codex-host-connect/scripts/task-hub-core.mjs';

function within(root, path) {
  const name = relative(root, path);
  return name !== '..' && !name.startsWith('..' + sep) && !isAbsolute(name);
}

/** Recover a separately frozen candidate, never an installed or existing tree. */
export async function recoverTaskHubBundle({ source, output, manifestSha256 }) {
  assert.match(
    manifestSha256 ?? '',
    /^[a-f0-9]{64}$/,
    'Require an independently frozen manifest SHA-256',
  );
  source = resolve(source);
  output = resolve(output);
  assert(
    !within(source, output) && !within(output, source),
    'Source and output must be separate trees',
  );
  assert((await lstat(source)).isDirectory(), 'Source must be a real directory');
  const physicalSource = await realpath(source);
  const physicalOutput = resolve(await realpath(dirname(output)), basename(output));
  assert(
    !within(physicalSource, physicalOutput) && !within(physicalOutput, physicalSource),
    'Source and output must be separate physical trees',
  );
  // macOS may expose an OS-owned /var alias. Compare and copy under the same
  // physical roots; no bundle symlink is followed to choose an output.
  source = physicalSource;
  output = physicalOutput;
  try {
    await lstat(output);
    throw Error('Output already exists; preserve immutable candidates');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const bytes = await readFile(resolve(source, 'manifest.json'));
  assert.equal(
    hash(bytes),
    manifestSha256,
    'Manifest differs from the independently frozen SHA-256',
  );
  const manifest = JSON.parse(bytes);
  assert.equal(manifest.format, 1);
  assert.equal(manifest.contractVersion, '1');
  assert.equal(hash(JSON.stringify(manifest.files)), manifest.payloadSha256);
  const before = await fileInventory(source, new Set(['manifest.json']));
  assert.deepEqual(
    Object.keys(before).sort(),
    Object.keys(manifest.files).sort(),
    'Unexpected or missing bundle entry',
  );
  const repairs = [];
  for (const [name, expected] of Object.entries(manifest.files)) {
    assert(name && !name.includes('\\') && !name.includes('\0') && !isAbsolute(name));
    assert(within(source, resolve(source, name)) && !name.split('/').includes('..'));
    const actual = before[name];
    if (Object.hasOwn(expected, 'link')) {
      const target = expected.link;
      assert(
        typeof target === 'string' &&
          target.length &&
          !target.includes('\0') &&
          !target.includes('\\'),
      );
      assert(
        !isAbsolute(target) && within(source, resolve(dirname(resolve(source, name)), target)),
        'Link escapes bundle',
      );
      if (actual.link === target) continue;
      assert(Object.hasOwn(actual, 'sha256'), 'Unsupported link entry');
      assert((await lstat(resolve(source, name))).isFile());
      assert.equal(
        (await readFile(resolve(source, name))).toString('utf8'),
        target,
        'Materialized link text differs',
      );
      repairs.push({ name, target });
    } else {
      assert.deepEqual(actual, expected, 'Bundle file differs: ' + name);
    }
  }
  // Copy only after all input bytes/types have been checked. Failure leaves a
  // distinct candidate for diagnosis; it never restores over the source.
  await cp(source, output, {
    recursive: true,
    verbatimSymlinks: true,
    force: false,
    errorOnExist: true,
  });
  for (const { name, target } of repairs) {
    const file = resolve(output, name);
    assert((await lstat(file)).isFile());
    assert.equal((await readFile(file)).toString('utf8'), target);
    await unlink(file);
    await symlink(target, file);
  }
  assert.deepEqual(
    await fileInventory(source, new Set(['manifest.json'])),
    before,
    'Source changed during recovery',
  );
  assert.equal(hash(await readFile(resolve(source, 'manifest.json'))), manifestSha256);
  await verifyBundle(output);
  assert.equal(hash(await readFile(resolve(output, 'manifest.json'))), manifestSha256);
  return {
    output,
    manifestSha256,
    restoredLinks: repairs.length,
    sourceSha256: manifest.sourceSha256,
    payloadSha256: manifest.payloadSha256,
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const args = process.argv.slice(2);
  assert(
    args.length === 6 &&
      args[0] === '--source' &&
      args[2] === '--output' &&
      args[4] === '--manifest-sha256',
    'Use --source <frozen bundle> --output <new directory> --manifest-sha256 <independently frozen SHA-256>',
  );
  console.log(
    JSON.stringify(
      await recoverTaskHubBundle({ source: args[1], output: args[3], manifestSha256: args[5] }),
    ),
  );
}
