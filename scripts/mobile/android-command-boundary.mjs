import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(process.env.ANDROID_HOME ?? local.androidSdk, 'platform-tools/adb');
const serial = process.env.ANDROID_SERIAL;
assert([undefined, '1'].includes(process.env.ANDROID_COMMAND_STATE));
const includeState = process.env.ANDROID_COMMAND_STATE === '1';
const expectedTests = includeState ? 31 : 21;
assert(/^emulator-\d+$/.test(serial ?? ''), 'Use a dedicated emulator, never a paused phone');
const call = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' });
assert.equal(call('get-state').trim(), 'device');
const root = '.local/mobile-acceptance/A4/commands-' + serial + '-' + Date.now();
await mkdir(root + '/artifacts', { recursive: true });
const sha = async (path) =>
  createHash('sha256')
    .update(await readFile(path))
    .digest('hex');
const files = execFileSync(
  'git',
  [
    'ls-files',
    '-co',
    '--exclude-standard',
    'apps/android',
    'packages/mobile-core',
    'packages/protocol',
  ],
  { encoding: 'utf8' },
)
  .trim()
  .split('\n')
  .filter(Boolean)
  .sort();
const digest = createHash('sha256');
for (const file of new Set(files))
  digest
    .update(file + '\0')
    .update(await readFile(file))
    .update('\0');
const app = '.build/native/android/outputs/apk/debug/android-debug.apk';
const test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
const report = {
  phase: 'A4',
  scope:
    'Command transport, encrypted intent journal' +
    (includeState ? ' and shared state' : '') +
    ' boundaries only; not stage completion',
  status: 'running',
  api: Number(call('shell', 'getprop', 'ro.build.version.sdk').trim()),
  osBuild: call('shell', 'getprop', 'ro.build.fingerprint').trim(),
  sourceSha256: digest.digest('hex'),
  toolSha256: await sha('scripts/mobile/android-command-boundary.mjs'),
  apkSha256: await sha(app),
  testApkSha256: await sha(test),
  expectedTests,
  freshModelExecution: false,
};
await copyFile(app, root + '/artifacts/android-debug.apk');
await copyFile(test, root + '/artifacts/android-debug-androidTest.apk');
await writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n');
try {
  call('install', '-r', app);
  call('install', '-r', test);
  const result = call(
    'shell',
    'am',
    'instrument',
    '-w',
    '-r',
    '-e',
    'class',
    'com.agenthub.android.MobileCommandBoundaryTest,com.agenthub.android.MobileHistoryBoundaryTest' +
      (includeState ? ',com.agenthub.android.MobileStateTest' : ''),
    'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
  );
  await writeFile(root + '/instrumentation.log', result);
  assert(
    result.includes('OK (' + expectedTests + ' tests)'),
    'All requested boundary cases must pass',
  );
  assert(!/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(result));
  assert(!/INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/.test(result), 'No failed or skipped cases');
  report.completedTests = expectedTests;
  report.failures = 0;
  report.skips = 0;
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
} finally {
  report.completedAt = new Date().toISOString();
  await writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n');
  console.log('A4 command boundaries:', report.status, 'on API', report.api, 'Evidence:', root);
}
