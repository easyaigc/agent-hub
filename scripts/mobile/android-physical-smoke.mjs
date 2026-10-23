import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, readdir, copyFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const serial = process.env.ANDROID_SERIAL;
assert(
  serial && !serial.startsWith('emulator-') && process.env.ANDROID_ALLOW_PHYSICAL === '1',
  'Explicitly select the user-authorized physical device',
);
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(process.env.ANDROID_HOME ?? local.androidSdk, 'platform-tools/adb');
const call = (...args) =>
  execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8', timeout: 60_000 }).trim();
assert.equal(call('get-state'), 'device');
const root = resolve('.local/mobile-acceptance/A4/physical-smoke-' + Date.now());
await mkdir(root, { recursive: true, mode: 0o700 });
const sha = (b) => createHash('sha256').update(b).digest('hex');
const suite = 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhysicalSmokeTest.kt';
const report = {
  stage: 'A4',
  status: 'running',
  scope:
    'Physical native onboarding, settings/demo, send/stop/resume; demonstration states, no real model task or failed-Turn proof',
  api: Number(call('shell', 'getprop', 'ro.build.version.sdk')),
  androidRelease: call('shell', 'getprop', 'ro.build.version.release'),
  deviceModel: call('shell', 'getprop', 'ro.product.model'),
  suiteSha256: sha(await readFile(suite)),
  toolSha256: sha(await readFile('scripts/mobile/android-physical-smoke.mjs')),
  installerSha256: sha(await readFile('scripts/mobile/android-install-if-needed.mjs')),
  actualFailedTurn: false,
  allowsNextStage: false,
  oemBackgroundPermissionChanged: false,
  globalDeviceSettingsChanged: false,
  screenshots: [],
};
const save = () =>
  writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
try {
  report.installations = [];
  for (const [packageName, apk] of [
    [
      'com.easyai.agenthub.android.debug',
      '.build/native/android/outputs/apk/debug/android-debug.apk',
    ],
    [
      'com.easyai.agenthub.android.debug.test',
      '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk',
    ],
  ])
    report.installations.push(
      await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: true }),
    );
  await copyFile(suite, root + '/MobilePhysicalSmokeTest.kt');
  const remote =
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4-physical-smoke';
  call('shell', 'rm', '-rf', remote);
  await save();
  const result = await promisify(execFile)(
    adb,
    [
      '-s',
      serial,
      'shell',
      'am',
      'instrument',
      '-w',
      '-r',
      '-e',
      'class',
      'com.agenthub.android.MobilePhysicalSmokeTest#foregroundNativeControls',
      '-e',
      'physicalAcceptance',
      'true',
      'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { encoding: 'utf8', timeout: 180_000, maxBuffer: 1024 * 1024 },
  );
  await writeFile(root + '/instrumentation.log', result.stdout + result.stderr, { mode: 0o600 });
  await mkdir(root + '/screenshots');
  call('pull', remote + '/.', root + '/screenshots');
  report.screenshots = await Promise.all(
    (await readdir(root + '/screenshots'))
      .filter((n) => n.endsWith('.png'))
      .sort()
      .map(async (name) => ({
        name,
        sha256: sha(await readFile(root + '/screenshots/' + name)),
        reviewed: false,
      })),
  );
  assert(
    result.stdout.includes('OK (1 test)') &&
      !/FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED/.test(result.stdout),
  );
  assert.equal(report.screenshots.length, 4);
  assert.equal(sha(await readFile(suite)), report.suiteSha256);
  report.completedTests = 1;
  report.failures = 0;
  report.skips = 0;
  report.status = 'passed-functional-pending-visual-review';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  if (error.stdout !== undefined)
    await writeFile(
      root + '/instrumentation.log',
      String(error.stdout) + String(error.stderr ?? ''),
      { mode: 0o600 },
    );
  if (error.killed) call('shell', 'am', 'force-stop', 'com.easyai.agenthub.android.debug');
  process.exitCode = 1;
} finally {
  report.completedAt = new Date().toISOString();
  await save();
  console.log(report.status, root);
}
