import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { startNativeReadFixture } from './android-read-fixture.mjs';
import { startAndroidDiscoveryFixture } from './android-discovery-fixture.mjs';
import { startRealReadGate } from './android-real-read-gate.mjs';

// A1 targets one dedicated emulator only. Do not use Gradle connected tests,
// which also install on any USB phone attached to the development computer.
const serial = process.env.ANDROID_SERIAL;
assert(serial?.startsWith('emulator-'), 'Set ANDROID_SERIAL to the dedicated emulator');
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT ?? local.androidSdk;
const adb = resolve(sdk, 'platform-tools/adb');
const call = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' }).trim();
assert.equal(call('get-state'), 'device');
const phase = process.env.MOBILE_PHASE ?? 'A1';
assert(
  ['A1', 'A2', 'A3'].includes(phase),
  'Add the later-stage scenarios before enabling another phase',
);
const realReadPath = process.env.ANDROID_REAL_HISTORY;
assert(!realReadPath || phase === 'A3');
const expectedTests = realReadPath ? 1 : phase === 'A3' ? 44 : phase === 'A2' ? 15 : 5;
const testClasses = realReadPath
  ? 'com.agenthub.android.MobileRealReadTest'
  : phase === 'A3'
    ? 'com.agenthub.android.MobileFoundationTest,com.agenthub.android.MobileStateTest,com.agenthub.android.MobileReadTest,com.agenthub.android.MobileDiscoveryTest'
    : phase === 'A2'
      ? 'com.agenthub.android.MobileFoundationTest,com.agenthub.android.MobileStateTest'
      : 'com.agenthub.android.MobileFoundationTest';
const output = '.local/mobile-acceptance/' + phase + '/android-' + serial + '-' + Date.now();
await mkdir(output, { recursive: true });
const fingerprint = async () => {
  const files = execFileSync(
    'git',
    [
      'ls-files',
      '-co',
      '--exclude-standard',
      'apps/android',
      'packages/mobile-core',
      'packages/protocol',
      'scripts/mobile/android-acceptance.mjs',
      'scripts/mobile/android-read-fixture.mjs',
      'scripts/mobile/android-discovery-fixture.mjs',
      'scripts/mobile/android-real-read-gate.mjs',
      'scripts/mobile/generate-design-tokens.mjs',
      'design/mobile/tokens.json',
      'scripts/mobile/gradle.mjs',
      'gradle',
      'gradlew',
      'gradlew.bat',
      'settings.gradle.kts',
      'build.gradle.kts',
      'gradle.properties',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const source = createHash('sha256');
  for (const file of new Set(files))
    source
      .update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return source.digest('hex');
};
const sourceSha256 = await fingerprint();
const report = {
  phase,
  scope: realReadPath
    ? 'native-real-desktop-history-read'
    : phase === 'A3'
      ? 'native-ui-and-readonly-chains'
      : phase === 'A2'
        ? 'native-foundation-and-shared-state'
        : 'native-foundation',
  serial,
  output,
  sourceSha256,
  api: call('shell', 'getprop', 'ro.build.version.sdk'),
  abi: call('shell', 'getprop', 'ro.product.cpu.abi'),
  build: call('shell', 'getprop', 'ro.build.fingerprint'),
  status: 'running',
  fontScale: process.env.ANDROID_FONT_SCALE ?? '1.0',
};
await writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
execFileSync(
  process.execPath,
  ['scripts/mobile/gradle.mjs', ':android:assembleDebug', ':android:assembleDebugAndroidTest'],
  { stdio: 'inherit' },
);
assert.equal(await fingerprint(), sourceSha256, 'Inputs changed during APK build');
await mkdir(output + '/artifacts');
const apk = output + '/artifacts/android-debug.apk';
const tests = output + '/artifacts/android-debug-androidTest.apk';
await copyFile('.build/native/android/outputs/apk/debug/android-debug.apk', apk);
await copyFile(
  '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
  tests,
);
const hash = async (path) =>
  createHash('sha256')
    .update(await readFile(path))
    .digest('hex');
report.apkSha256 = await hash(apk);
report.testApkSha256 = await hash(tests);
console.log('Running', phase, 'Android native tests; evidence:', output);
// Android 8.0 cannot clear individual permission flags. Reinstall only this
// dedicated emulator's synthetic acceptance App to restore actual prompt state.
if (phase === 'A3' && Number(report.api) < 28 && !realReadPath) {
  try {
    call('uninstall', 'com.easyai.agenthub.android.debug');
  } catch {
    // A fresh emulator installation may not contain the previous screenshot directory.
  }
}
call('install', '-r', '-t', apk);
call('install', '-r', '-t', tests);
// Evidence must come from this execution, never an earlier package's leftover PNGs.
call(
  'shell',
  'rm',
  '-rf',
  '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/' + phase,
);
const animations = new Map(
  ['window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'].map((key) => [
    key,
    call('shell', 'settings', 'get', 'global', key),
  ]),
);
for (const key of animations.keys()) call('shell', 'settings', 'put', 'global', key, '0');
const fixture = phase === 'A3' && !realReadPath ? await startNativeReadFixture(adb, serial) : null;
const discovery =
  phase === 'A3' && !realReadPath ? await startAndroidDiscoveryFixture(adb, serial) : null;
const realRead = realReadPath ? await startRealReadGate(realReadPath, adb, serial) : null;
const originalFontScale = call('shell', 'settings', 'get', 'system', 'font_scale');
call('shell', 'settings', 'put', 'system', 'font_scale', report.fontScale);
if (phase === 'A3' && !realRead) {
  // Dedicated acceptance devices only; these cases must exercise actual prompts.
  for (const permission of [
    'android.permission.CAMERA',
    ...(Number(report.api) >= 37 ? ['android.permission.ACCESS_LOCAL_NETWORK'] : []),
  ]) {
    call('shell', 'pm', 'revoke', 'com.easyai.agenthub.android.debug', permission);
    if (Number(report.api) >= 28)
      call(
        'shell',
        'pm',
        'clear-permission-flags',
        'com.easyai.agenthub.android.debug',
        permission,
        'user-set',
        'user-fixed',
      );
  }
}
const child = spawn(adb, [
  '-s',
  serial,
  'shell',
  'am',
  'instrument',
  '-w',
  '-r',
  '-e',
  'class',
  testClasses,
  '-e',
  'phase',
  phase,
  ...(fixture ? ['-e', 'readFixture', fixture.endpoint] : []),
  ...(discovery
    ? ['-e', 'discoveryPairing', discovery.pairing, '-e', 'discoveryWrongPin', discovery.wrongPin]
    : []),
  ...(realRead
    ? [
        '-e',
        'realReadEndpoint',
        realRead.endpoint,
        '-e',
        'realReadThread',
        realRead.proof.threadId,
        '-e',
        'realReadReplyHash',
        realRead.proof.replySha256,
      ]
    : []),
  'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
]);
let text = '';
for (const stream of [child.stdout, child.stderr])
  stream.on('data', (data) => {
    text += data.toString();
  });
let exitCode;
try {
  exitCode = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', resolve);
  });
} finally {
  if (realRead) {
    await realRead.close();
    await writeFile(
      output + '/real-read-requests.json',
      JSON.stringify(realRead.requests, null, 2) + '\n',
    );
    report.realReadRequests = realRead.requests.length;
    report.realReadNonGetRequests = realRead.requests.filter(
      (request) => request.method !== 'GET',
    ).length;
    report.independentDesktopSource = realRead.proof.independentDesktopSource;
    report.independentDesktopState = realRead.proof.independentDesktopState;
    report.replySha256 = realRead.proof.replySha256;
    report.freshModelExecution = false;
  }
  if (discovery) {
    await discovery.close();
    await writeFile(
      output + '/pairing-requests.json',
      JSON.stringify(discovery.requests, null, 2) + '\n',
    );
    await writeFile(
      output + '/pairing-tls-errors.json',
      JSON.stringify(discovery.tlsErrors, null, 2) + '\n',
    );
    report.pairingRequests = discovery.requests.length;
    report.pairingNonGetRequests = discovery.requests.filter(
      (request) => request.method !== 'GET',
    ).length;
    report.unauthenticatedDescriptorOnly = discovery.requests
      .filter((request) => request.path === '/host')
      .every((request) => !request.authorized);
  }
  if (fixture) {
    await fixture.close();
    await writeFile(
      output + '/readonly-requests.json',
      JSON.stringify(fixture.requests, null, 2) + '\n',
    );
    report.readonlyRequests = fixture.requests.length;
    report.nonGetRequests = fixture.requests.filter((request) => request.method !== 'GET').length;
  }
  if (originalFontScale === 'null') call('shell', 'settings', 'delete', 'system', 'font_scale');
  else call('shell', 'settings', 'put', 'system', 'font_scale', originalFontScale);
  for (const [key, value] of animations) {
    if (value === 'null') call('shell', 'settings', 'delete', 'global', key);
    else call('shell', 'settings', 'put', 'global', key, value);
  }
}
await writeFile(output + '/instrumentation.log', text);
report.exitCode = exitCode;
report.expectedTests = expectedTests;
report.completedTests = (text.match(/INSTRUMENTATION_STATUS_CODE: 0\r?\n/g) ?? []).length;
report.passed =
  exitCode === 0 &&
  new RegExp('OK \\(' + expectedTests + ' test' + (expectedTests === 1 ? '' : 's') + '\\)').test(
    text,
  ) &&
  report.completedTests === expectedTests &&
  !/FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/.test(text);
if (fixture) report.passed &&= report.nonGetRequests === 0 && report.readonlyRequests > 10;
if (discovery)
  report.passed &&=
    report.pairingRequests > 5 &&
    report.pairingNonGetRequests === 0 &&
    report.unauthenticatedDescriptorOnly;
if (realRead) report.passed &&= report.realReadRequests >= 2 && report.realReadNonGetRequests === 0;
assert.equal(await fingerprint(), sourceSha256, 'Inputs changed during Android verification');
report.status = report.passed ? 'passed' : 'failed';
await writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
if (!realRead)
  try {
    call(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/' + phase,
      output + '/screenshots',
    );
  } catch {
    // A framework failure before the first screenshot can leave no directory.
  }
console.log('Android native result:', report.status, report.completedTests + '/' + expectedTests);
assert(report.passed, 'Inspect the instrumentation log; no skipped/omitted tests accepted');
