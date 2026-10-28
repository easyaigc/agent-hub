// JVM auxiliary: actual Kotlin controller/stores/wire; in-memory storage and Looper fixture, no Android encryption or native execution proof.
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
const directory = resolve(root, '.build/p9-notification-recovery-host-vectors');
mkdirSync(directory, { recursive: true });
function run(args) {
  const result = spawnSync(java, args, {
    cwd: root,
    encoding: 'utf8',
    stdio: 'inherit',
    timeout: 120_000,
    killSignal: 'SIGTERM',
  });
  assert.equal(
    result.status,
    0,
    'Shipping recovery controller/journal/wire auxiliary vectors failed',
  );
}
const app = resolve(
  root,
  '.build/native/android/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar',
);
const core = resolve(
  root,
  '.build/native/mobile-core/intermediates/compile_library_classes_jar/androidMain/bundleAndroidMainClassesToCompileJar/classes.jar',
);
const android = resolve(local.androidSdk, 'platforms/android-37.0/android.jar');
const runtime = [
  directory,
  stdlib,
  jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0'),
  jar('org.json', 'json', '20250517'),
  jar('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', '1.11.0'),
  jar('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.11.0'),
  app,
  core,
  jar('org.bouncycastle', 'bcprov-jdk15to18', '1.86'),
  android,
].join(delimiter);
const javac = resolve(process.env.JAVA_HOME || local.javaHome, 'bin/javac');
assert.equal(
  spawnSync(javac, ['-d', directory, 'scripts/mobile/fixtures/android/os/Looper.java'], {
    stdio: 'inherit',
  }).status,
  0,
);
run([
  '-cp',
  classpath,
  'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
  '-no-stdlib',
  '-no-reflect',
  '-jvm-target',
  '17',
  '-classpath',
  runtime,
  '-Xfriend-paths=' + app,
  '-d',
  resolve(directory, 'vectors.jar'),
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationModels.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationCodec.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationWire.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationCommands.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationForegroundStore.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeWireReader.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRecoveryWire.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRecoveryCommands.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRecoveryStore.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRecoveryController.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationForegroundMonitor.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationIdentity.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationStore.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSessionPort.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeWorkspaceThreadDirectory.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationAuthorizations.kt',
  'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationRecoveryFixture.kt',
  'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationCycleEvidence.kt',
  'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationCycleFiles.kt',
  'scripts/mobile/fixtures/CycleVectors.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationColdPlan.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationColdRouterFiles.kt',
  'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationColdPorts.kt',
  'scripts/mobile/fixtures/ColdVectors.kt',
  'scripts/mobile/fixtures/CoordinatorVectors.kt',
  'scripts/mobile/fixtures/RecoveryStorage.kt',
  'scripts/mobile/fixtures/RecoveryVectors.kt',
]);
run([
  '--add-modules',
  'jdk.httpserver',
  '-cp',
  [resolve(directory, 'vectors.jar'), directory, runtime].join(delimiter),
  'com.agenthub.android.RecoveryVectorsKt',
]);
