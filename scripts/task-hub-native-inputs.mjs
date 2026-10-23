import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstat, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';

const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const safe = (path) =>
  assert.ok(
    !path.startsWith('/') &&
      !path.includes('\\') &&
      !path.split('/').some((p) => ['', '.', '..'].includes(p)),
  );

export function taskHubNativeSourceFiles(repository) {
  const directories = [
    'apps/ios',
    'packages/mobile-core',
    'packages/protocol/schemas',
    'packages/protocol/fixtures/mobile',
    'scripts/mobile',
    'gradle',
  ];
  return [
    ...new Set([
      'apps/ios/Info.plist',
      'apps/android/build.gradle.kts',
      'build.gradle.kts',
      'settings.gradle.kts',
      'gradle.properties',
      'gradlew',
      'gradlew.bat',
      'package.json',
      'pnpm-lock.yaml',
      'gradle/wrapper/gradle-wrapper.jar',
      'gradle/wrapper/gradle-wrapper.properties',
      'scripts/task-hub-ios-acceptance.mjs',
      'scripts/task-hub-ios-network-acceptance.mjs',
      'scripts/task-hub-acceptance-state.mjs',
      'scripts/task-hub-native-inputs.mjs',
      ...execFileSync('rg', ['--files', ...directories], { cwd: repository, encoding: 'utf8' })
        .trim()
        .split('\n')
        .filter((path) => !path.includes('xcuserdata')),
    ]),
  ].sort();
}

export async function taskHubNativeFingerprint(repository, paths) {
  const files = {},
    modes = {};
  for (const path of paths) {
    safe(path);
    const source = join(repository, path),
      metadata = await lstat(source);
    assert.ok(
      metadata.isFile() && !metadata.isSymbolicLink(),
      'Native input must be a regular file',
    );
    files[path] = hash(await readFile(source));
    modes[path] = metadata.mode & 0o111 ? '100755' : '100644';
  }
  return { files, modes, sha256: hash(JSON.stringify({ files, modes })) };
}

export async function freezeTaskHubNativeInputs(repository, destination, expected) {
  const paths = Object.keys(expected.files);
  assert.equal(
    (await taskHubNativeFingerprint(repository, paths)).sha256,
    expected.sha256,
    'Native inputs changed before freeze',
  );
  await mkdir(destination, { mode: 0o700 });
  for (const path of paths) {
    const bytes = await readFile(join(repository, path));
    assert.equal(hash(bytes), expected.files[path], 'Native input changed during freeze');
    const target = join(destination, path);
    await mkdir(dirname(target), { recursive: true, mode: 0o700 });
    await writeFile(target, bytes, {
      mode: expected.modes[path] === '100755' ? 0o700 : 0o600,
      flag: 'wx',
    });
  }
  assert.equal(
    (await taskHubNativeFingerprint(repository, paths)).sha256,
    expected.sha256,
    'Native inputs changed during freeze; no native test may start',
  );
  assert.equal(
    (await taskHubNativeFingerprint(destination, paths)).sha256,
    expected.sha256,
    'Frozen native inputs differ from captured bytes or executable modes',
  );
}
