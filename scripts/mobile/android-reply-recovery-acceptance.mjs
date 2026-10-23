import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const serial = process.env.ANDROID_SERIAL;
const physical = serial && !serial.startsWith('emulator-');
assert(serial && (!physical || process.env.ANDROID_ALLOW_PHYSICAL === '1'));
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(process.env.ANDROID_HOME ?? local.androidSdk, 'platform-tools/adb');
const run = promisify(execFile);
const call = async (...args) =>
  (
    await run(adb, ['-s', serial, ...args], {
      encoding: 'utf8',
      timeout: 300_000,
      maxBuffer: 16 * 1024 * 1024,
    })
  ).stdout;
assert.equal((await call('get-state')).trim(), 'device');
const root =
  '.local/android-development/upstream-migration/UM02/native-' + serial + '-' + Date.now();
await mkdir(root, { recursive: true, mode: 0o700 });
const classes = [
  'MobileReplyRecoveryTest',
  'MobileCommandBoundaryTest',
  'MobileHistoryBoundaryTest',
  'MobileStateTest',
];
const testCount = (
  await Promise.all(
    classes.map((name) =>
      readFile('apps/android/src/androidTest/kotlin/com/agenthub/android/' + name + '.kt', 'utf8'),
    ),
  )
).reduce((count, source) => count + (source.match(/@Test\s/g)?.length ?? 0), 0);
assert.equal(
  testCount,
  41,
  'Review the explicit native acceptance scope before changing case count',
);
const app = '.build/native/android/outputs/apk/debug/android-debug.apk';
const test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fingerprint = async () => {
  const paths = execFileSync(
    'git',
    [
      'ls-files',
      '-co',
      '--exclude-standard',
      'apps/android',
      'packages/mobile-core',
      'packages/protocol',
      'scripts/mobile/android-reply-recovery-acceptance.mjs',
      'scripts/mobile/android-install-if-needed.mjs',
      'pnpm-lock.yaml',
      'gradle',
      'build.gradle.kts',
      'settings.gradle.kts',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean);
  const hash = createHash('sha256');
  for (const path of [...new Set(paths)].sort())
    hash
      .update(path + '\0')
      .update(await readFile(path))
      .update('\0');
  return hash.digest('hex');
};
const report = {
  status: 'running',
  serial,
  physical: !!physical,
  api: Number((await call('shell', 'getprop', 'ro.build.version.sdk')).trim()),
  scope:
    'Actual Android Keystore/journal/concurrency/HTTP and shared rules with synthetic authority; no real desktop or model claim',
  classes,
  expectedTests: testCount,
  sourceSha256: await fingerprint(),
  apkSha256: sha(await readFile(app)),
  testApkSha256: sha(await readFile(test)),
  installations: [],
  realModelTasks: 0,
};
await writeFile(root + '/report.json', JSON.stringify(report, null, 2), { mode: 0o600 });
try {
  for (const [packageName, apk] of [
    ['com.easyai.agenthub.android.debug', app],
    ['com.easyai.agenthub.android.debug.test', test],
  ]) {
    report.installations.push(
      await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: !!physical }),
    );
  }
  const output = await call(
    'shell',
    'am',
    'instrument',
    '-w',
    '-r',
    '-e',
    'class',
    classes.map((name) => 'com.agenthub.android.' + name).join(','),
    'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
  );
  await writeFile(root + '/instrumentation.log', output, { mode: 0o600 });
  assert(
    output.includes('OK (' + testCount + ' tests)'),
    'All selected native assertions must pass',
  );
  assert(
    !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/.test(
      output,
    ),
    'No failures or skips',
  );
  assert.equal(
    await fingerprint(),
    report.sourceSha256,
    'Acceptance source changed during native run',
  );
  assert.equal(sha(await readFile(app)), report.apkSha256);
  assert.equal(sha(await readFile(test)), report.testApkSha256);
  report.status = 'passed';
  report.passed = testCount;
  report.failed = report.skipped = 0;
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  await writeFile(root + '/report.json', JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(
    JSON.stringify({
      status: report.status,
      serial,
      api: report.api,
      report: root + '/report.json',
      passed: report.passed,
    }),
  );
}
