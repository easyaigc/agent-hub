// Model/store JVM auxiliaries. No Android, device, KeyStore or natural execution caller is loaded.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { delimiter, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { parseArgs } from 'node:util';

const { values } = parseArgs({ options: { suite: { type: 'string', default: 'model' } } });
assert.ok(['model', 'store', 'bootstrap'].includes(values.suite));

const root = process.cwd();
const cache = resolve(
  process.env.GRADLE_USER_HOME || '.local/gradle-home',
  'caches/modules-2/files-2.1',
);
function artifact(group, name, version) {
  const directory = resolve(cache, group, name, version);
  const filename = `${name}-${version}.jar`;
  const found = readdirSync(directory).flatMap((hash) =>
    readdirSync(resolve(directory, hash))
      .filter((file) => file === filename)
      .map((file) => resolve(directory, hash, file)),
  );
  assert.equal(found.length, 1, `Expected cached ${group}/${filename}`);
  return found[0];
}
const local = JSON.parse(readFileSync(resolve(root, '.local/mobile-toolchain.json'), 'utf8'));
const java = resolve(process.env.JAVA_HOME || local.javaHome, 'bin/java');
const stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21');
const runtime = [
  stdlib,
  ...(values.suite === 'bootstrap'
    ? [artifact('junit', 'junit', '4.13.2'), artifact('org.hamcrest', 'hamcrest-core', '1.3')]
    : []),
].join(delimiter);
const compiler = [
  artifact('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.21'),
  artifact('org.jetbrains.kotlin', 'kotlin-build-tools-api', '2.4.21'),
  stdlib,
  artifact('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.21'),
  artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
  artifact('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.4.21'),
  artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
  artifact('org.jetbrains', 'annotations', '13.0'),
].join(delimiter);
const sources = (
  values.suite === 'bootstrap'
    ? [
        'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalFamilyInventory.kt',
        'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalBootstrapFence.kt',
        'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalEvidenceBoundary.kt',
        'scripts/mobile/fixtures/NaturalBootstrapVectors.kt',
      ]
    : [
        'apps/android/src/debug/kotlin/com/agenthub/android/NativeChatNotificationNaturalPermit.kt',
        ...(values.suite === 'store'
          ? [
              'apps/android/src/debug/kotlin/com/agenthub/android/NativeChatNotificationNaturalFiles.kt',
              'scripts/mobile/fixtures/NaturalStoreVectors.kt',
            ]
          : ['scripts/mobile/fixtures/NaturalPermitVectors.kt']),
      ]
).map((path) => resolve(root, path));
const output = resolve(root, `.build/p9-natural-${values.suite}`);
mkdirSync(output, { recursive: true });
function run(args) {
  const result = spawnSync(java, args, {
    stdio: 'inherit',
    timeout: 120_000,
    killSignal: 'SIGTERM',
  });
  assert.equal(result.status, 0, 'Natural permit auxiliary failed');
}
run([
  '-cp',
  compiler,
  'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
  '-no-stdlib',
  '-no-reflect',
  '-jvm-target',
  '17',
  '-classpath',
  runtime,
  '-d',
  output,
  ...sources,
]);
console.log(
  JSON.stringify({
    scope:
      values.suite === 'bootstrap'
        ? 'Bootstrap filesystem/fence/PIDs; no product storage or Android key/native proof'
        : values.suite === 'store'
          ? 'JVM crypto/files/lock; key/install/OS seams; no native proof'
          : 'pure proposal; no execution authorization or native proof',
    sourceSha256: Object.fromEntries(
      sources.map((path) => [path, createHash('sha256').update(readFileSync(path)).digest('hex')]),
    ),
  }),
);
run([
  '-cp',
  [output, runtime].join(delimiter),
  `com.agenthub.android.${values.suite === 'bootstrap' ? 'NaturalBootstrapVectorsKt' : values.suite === 'store' ? 'NaturalStoreVectorsKt' : 'NaturalPermitVectorsKt'}`,
]);
