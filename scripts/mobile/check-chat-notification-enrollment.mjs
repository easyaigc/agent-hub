// JVM auxiliary for supplied P14 coordinator/enrollment bytes. Archive validation, Main and storage are explicit seams.
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { resolve, delimiter } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';

const { values } = parseArgs({
  options: {
    'source-root': { type: 'string' },
    coordinator: { type: 'string' },
    label: { type: 'string', default: 'current' },
    suite: { type: 'string', default: 'enrollment' },
    case: { type: 'string', default: 'all' },
  },
});
assert.match(values.label, /^[a-z0-9-]{1,48}$/);
assert.ok(['enrollment', 'worker-close'].includes(values.suite));
assert.ok(['all', 'entry-reentry', 'normal-close', 'sync-return-retire'].includes(values.case));
assert.ok(values.suite === 'worker-close' || values.case === 'all');
const root = process.cwd();
const supplied = resolve(values['source-root'] || root);
const sourceDir = resolve(supplied, 'apps/android/src/main/kotlin/com/agenthub/android');
const coordinator = resolve(
  values.coordinator || resolve(sourceDir, 'NativeChatNotificationCoordinator.kt'),
);
const parentSources = [
  coordinator,
  resolve(sourceDir, 'NativeMigrationNotificationEnrollment.kt'),
  resolve(sourceDir, 'NativeMigrationIoWork.kt'),
];
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const suppliedHashes = Object.fromEntries(
  parentSources.map((path) => [path, hash(readFileSync(path))]),
);
const cache = resolve(
  process.env.GRADLE_USER_HOME || '.local/gradle-home',
  'caches/modules-2/files-2.1',
);
function artifact(group, name, version, filename = `${name}-${version}.jar`) {
  const base = resolve(cache, group, name, version);
  const found = readdirSync(base).flatMap((item) =>
    readdirSync(resolve(base, item))
      .filter((file) => file === filename)
      .map((file) => resolve(base, item, file)),
  );
  assert.equal(found.length, 1, `Expected cached ${group}/${name}/${version}/${filename}`);
  return found[0];
}
const local = JSON.parse(readFileSync(resolve(root, '.local/mobile-toolchain.json'), 'utf8'));
const java = resolve(process.env.JAVA_HOME || local.javaHome, 'bin/java');
const javac = resolve(process.env.JAVA_HOME || local.javaHome, 'bin/javac');
const directory = resolve(root, '.build/p9-notification-enrollment-host-vectors', values.label);
mkdirSync(directory, { recursive: true });
function run(binary, args, options = {}) {
  const result = spawnSync(binary, args, {
    cwd: root,
    stdio: 'inherit',
    timeout: 120_000,
    killSignal: 'SIGTERM',
    ...options,
  });
  assert.equal(result.status, 0, `Enrollment coordinator auxiliary failed: ${binary}`);
  return result;
}
const aarJars = [
  ['runtime-android', 'runtime.aar'],
  ['runtime-annotation-android', 'runtime-annotation.aar'],
].map(([name, file]) => {
  const aar = artifact('androidx.compose.runtime', name, '1.12.1', file);
  const extracted = run('unzip', ['-p', aar, 'classes.jar'], {
    stdio: 'pipe',
    maxBuffer: 10 * 1024 * 1024,
  });
  const output = resolve(directory, `${name}.jar`);
  writeFileSync(output, extracted.stdout);
  return output;
});
const stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21');
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
const app = resolve(
  root,
  '.build/native/android/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar',
);
const runtime = [
  directory,
  stdlib,
  artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0'),
  artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-test-jvm', '1.11.0'),
  artifact('org.json', 'json', '20250517'),
  artifact('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', '1.11.0'),
  artifact('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.11.0'),
  artifact('androidx.collection', 'collection-jvm', '1.5.0'),
  ...aarJars,
  app,
  resolve(
    root,
    '.build/native/mobile-core/intermediates/compile_library_classes_jar/androidMain/bundleAndroidMainClassesToCompileJar/classes.jar',
  ),
  artifact('org.bouncycastle', 'bcprov-jdk15to18', '1.86'),
  resolve(local.androidSdk, 'platforms/android-37.0/android.jar'),
].join(delimiter);
run(javac, ['-d', directory, 'scripts/mobile/fixtures/android/os/Looper.java']);
const sources = [
  'NativeChatNotificationModels',
  'NativeChatNotificationCodec',
  'NativeChatNotificationWire',
  'NativeChatNotificationCommands',
  'NativeChatNotificationForegroundStore',
  'NativeWireReader',
  'NativeChatNotificationRecoveryWire',
  'NativeChatNotificationRecoveryCommands',
  'NativeChatNotificationRecoveryStore',
  'NativeChatNotificationRecoveryController',
  'NativeChatNotificationForegroundMonitor',
  'NativeChatNotificationIdentity',
  'NativeChatNotificationStore',
  'NativeChatNotificationSessionPort',
  'NativeWorkspaceThreadDirectory',
  'NativeChatNotificationAuthorizations',
  'NativeChatNotificationForegroundDelivery',
].map((name) => `apps/android/src/main/kotlin/com/agenthub/android/${name}.kt`);
run(java, [
  '-cp',
  compiler,
  'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
  '-no-stdlib',
  '-no-reflect',
  '-jvm-target',
  '17',
  '-classpath',
  runtime,
  `-Xfriend-paths=${app}`,
  '-d',
  resolve(directory, 'vectors.jar'),
  ...sources,
  ...parentSources,
  'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationRecoveryFixture.kt',
  'scripts/mobile/fixtures/RecoveryStorage.kt',
  'scripts/mobile/fixtures/EnrollmentArchiveBoundary.kt',
  ...(values.suite === 'enrollment'
    ? ['scripts/mobile/fixtures/EnrollmentCoordinatorVectors.kt']
    : [
        'scripts/mobile/fixtures/native/NativeChatNotificationWorkerCloseFixture.kt',
        'scripts/mobile/fixtures/WorkerCloseVectors.kt',
      ]),
]);
run(java, [
  '-cp',
  [resolve(directory, 'vectors.jar'), runtime].join(delimiter),
  values.suite === 'enrollment'
    ? 'com.agenthub.android.EnrollmentCoordinatorVectorsKt'
    : 'com.agenthub.android.WorkerCloseVectorsKt',
  ...(values.suite === 'worker-close' ? [values.case] : []),
]);
assert.deepEqual(
  Object.fromEntries(parentSources.map((path) => [path, hash(readFileSync(path))])),
  suppliedHashes,
  'Supplied source changed during validation',
);
console.log(
  JSON.stringify({
    suite: values.suite,
    case: values.case,
    suppliedSha256: suppliedHashes,
    native: 'not-run',
    archiveValidation: 'fixed JVM seam; not verified here',
  }),
);
