import assert from 'node:assert/strict';
import { readdirSync, readFileSync, mkdirSync } from 'node:fs';
import { resolve, delimiter } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = process.cwd();
const cache = resolve(
  process.env.GRADLE_USER_HOME || '.local/gradle-home',
  'caches/modules-2/files-2.1',
);
function jar(group, artifact, version) {
  const directory = resolve(cache, group, artifact, version);
  const jars = readdirSync(directory).flatMap((hash) =>
    readdirSync(resolve(directory, hash))
      .filter((name) => name === `${artifact}-${version}.jar`)
      .map((name) => resolve(directory, hash, name)),
  );
  assert.equal(jars.length, 1, `Expected one already-cached ${artifact} ${version}`);
  return jars[0];
}
const stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21');
const classpath = [
  jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.21'),
  jar('org.jetbrains.kotlin', 'kotlin-build-tools-api', '2.4.21'),
  stdlib,
  jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.21'),
  jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
  jar('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.4.21'),
  jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
  jar('org.jetbrains', 'annotations', '13.0'),
].join(delimiter);
const local = JSON.parse(readFileSync(resolve(root, '.local/mobile-toolchain.json'), 'utf8'));
const java = resolve(process.env.JAVA_HOME || local.javaHome, 'bin/java');
const directory = resolve(root, '.build/p9-sound-host-vectors');
mkdirSync(directory, { recursive: true });
function run(args) {
  const result = spawnSync(java, args, { cwd: root, encoding: 'utf8', stdio: 'inherit' });
  assert.equal(result.status, 0, 'Shipping PCM compilation/vector execution failed');
}
run([
  '-cp',
  classpath,
  'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
  '-no-stdlib',
  '-no-reflect',
  '-jvm-target',
  '17',
  '-classpath',
  stdlib,
  '-d',
  resolve(directory, 'vectors.jar'),
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationPcm.kt',
  'apps/android/src/test/kotlin/com/agenthub/android/NativeChatNotificationSoundVectors.kt',
]);
run([
  '-cp',
  [resolve(directory, 'vectors.jar'), stdlib].join(delimiter),
  'com.agenthub.android.NativeChatNotificationSoundVectorsKt',
  ...process.argv.slice(2),
]);
