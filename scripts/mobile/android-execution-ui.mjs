import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(process.env.ANDROID_HOME ?? local.androidSdk, 'platform-tools/adb');
const serial = process.env.ANDROID_SERIAL;
const expectedTests = 18;
assert(/^emulator-\d+$/.test(serial ?? ''), 'Use a dedicated emulator, never a paused phone');
const call = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' });
assert.equal(call('get-state').trim(), 'device');
const root = '.local/mobile-acceptance/A4/execution-' + serial + '-' + Date.now();
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
const fingerprint = async () => {
  const digest = createHash('sha256');
  for (const file of new Set(files))
    digest
      .update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return digest.digest('hex');
};
const app = '.build/native/android/outputs/apk/debug/android-debug.apk';
const test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
const report = {
  phase: 'A4',
  scope:
    'Native Codex UI, real HTTP and encrypted storage with synthetic computer responses; no real model/owner proof; not stage completion',
  status: 'running',
  api: Number(call('shell', 'getprop', 'ro.build.version.sdk').trim()),
  fontScale: Number(call('shell', 'settings', 'get', 'system', 'font_scale').trim()),
  osBuild: call('shell', 'getprop', 'ro.build.fingerprint').trim(),
  sourceSha256: await fingerprint(),
  toolSha256: await sha('scripts/mobile/android-execution-ui.mjs'),
  apkSha256: await sha(app),
  testApkSha256: await sha(test),
  expectedTests,
  freshModelExecution: false,
};
await copyFile(app, root + '/artifacts/android-debug.apk');
await copyFile(test, root + '/artifacts/android-debug-androidTest.apk');
await writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n');
try {
  call(
    'shell',
    'rm',
    '-rf',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4',
  );
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
    'com.agenthub.android.MobileExecutionTest',
    'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
  );
  await writeFile(root + '/instrumentation.log', result);
  assert(
    result.includes('OK (' + expectedTests + ' tests)'),
    'All requested boundary cases must pass',
  );
  assert(!/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(result));
  assert(!/INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/.test(result), 'No failed or skipped cases');
  assert.equal(await fingerprint(), report.sourceSha256, 'Native inputs changed during acceptance');
  report.completedTests = expectedTests;
  report.failures = 0;
  report.skips = 0;
  report.status = 'passed';
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
} finally {
  try {
    await mkdir(root + '/screenshots', { recursive: true });
    call(
      'pull',
      '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/A4/.',
      root + '/screenshots',
    );
    const { readdir } = await import('node:fs/promises');
    report.screenshots = await Promise.all(
      (await readdir(root + '/screenshots'))
        .filter((f) => f.endsWith('.png'))
        .sort()
        .map(async (name) => ({
          name,
          sha256: await sha(root + '/screenshots/' + name),
          reviewed: false,
        })),
    );
  } catch (captureError) {
    report.captureError = captureError.message;
  }
  if (report.status === 'passed' && report.screenshots?.length !== 25) {
    report.status = 'failed';
    report.error = 'All required native captures must exist';
    process.exitCode = 1;
  }
  report.completedAt = new Date().toISOString();
  await writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n');
  console.log(
    'A4 synthetic native execution UI:',
    report.status,
    'on API',
    report.api,
    'Evidence:',
    root,
  );
}
