import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const step = process.env.MOBILE_MIGRATION_STEP ?? 'UM03';
assert(/^UM0[1-8]$/.test(step));
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
      timeout: 600_000,
      maxBuffer: 16 * 1024 * 1024,
    })
  ).stdout;
assert.equal((await call('get-state')).trim(), 'device');
const scope = process.env.THREAD_FORK_ANDROID_SCOPE ?? 'full';
assert(['full', 'fork'].includes(scope));
const classes = [
  'MobileThreadForkTest',
  'MobileThreadForkUiTest',
  ...(scope === 'full'
    ? [
        'MobileExecutionTest',
        'MobileReplyRecoveryTest',
        'MobileCommandBoundaryTest',
        'MobileHistoryBoundaryTest',
        'MobileStateTest',
      ]
    : []),
];
const testCount = (
  await Promise.all(
    classes.map((name) =>
      readFile('apps/android/src/androidTest/kotlin/com/agenthub/android/' + name + '.kt', 'utf8'),
    ),
  )
).reduce((n, s) => n + (s.match(/@Test\s/g)?.length ?? 0), 0);
assert.equal(testCount, scope === 'full' ? 81 : 21, 'Review changed acceptance scope');
const root =
  '.local/android-development/upstream-migration/' +
  step +
  '/regression-' +
  serial +
  '-' +
  Date.now();
await mkdir(root, { recursive: true, mode: 0o700 });
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
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
      'scripts/mobile/android-thread-fork-acceptance.mjs',
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
  for (const file of [...new Set(files)].sort())
    hash
      .update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return hash.digest('hex');
};
const app = '.build/native/android/outputs/apk/debug/android-debug.apk';
const test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
const report = {
  status: 'running',
  step,
  serial,
  physical: !!physical,
  api: Number((await call('shell', 'getprop', 'ro.build.version.sdk')).trim()),
  scope: 'Actual native HTTP/Keystore/UI with synthetic Host; no real desktop/Git/model claim',
  classes,
  expectedTests: testCount,
  sourceSha256: await fingerprint(),
  apkSha256: sha(await readFile(app)),
  testApkSha256: sha(await readFile(test)),
  installations: [],
  realModelTasks: 0,
};
const save = () =>
  writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
await save();
try {
  if (physical) {
    const snapshot = (
      await run(
        adb,
        [
          '-s',
          serial,
          'exec-out',
          'run-as',
          'com.easyai.agenthub.android.debug',
          'tar',
          '-cf',
          '-',
          'no_backup',
          'files',
        ],
        { encoding: 'buffer', timeout: 60_000, maxBuffer: 128 * 1024 * 1024 },
      )
    ).stdout;
    await writeFile(root + '/prior-private-app.tar', snapshot, { mode: 0o600 });
    report.priorPrivateAppSha256 = sha(snapshot);
    await save();
  }
  for (const [packageName, apk] of [
    ['com.easyai.agenthub.android.debug', app],
    ['com.easyai.agenthub.android.debug.test', test],
  ])
    report.installations.push(
      await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: !!physical }),
    );
  const output = await call(
    'shell',
    'am',
    'instrument',
    '-w',
    '-r',
    '-e',
    'class',
    classes.map((n) => 'com.agenthub.android.' + n).join(','),
    'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
  );
  await writeFile(root + '/instrumentation.log', output, { mode: 0o600 });
  assert(output.includes('OK (' + testCount + ' tests)'), 'Every selected test must pass');
  assert(
    !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/.test(
      output,
    ),
    'No failures/skips',
  );
  assert.equal(await fingerprint(), report.sourceSha256, 'Inputs changed during run');
  assert.equal(sha(await readFile(app)), report.apkSha256);
  assert.equal(sha(await readFile(test)), report.testApkSha256);
  report.status = 'passed';
  report.passed = testCount;
  report.failed = report.skipped = 0;
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  if (error.stdout)
    await writeFile(root + '/instrumentation-incomplete.log', error.stdout, { mode: 0o600 });
  if (error.stderr)
    await writeFile(root + '/instrumentation-stderr.log', error.stderr, { mode: 0o600 });
  process.exitCode = 1;
} finally {
  try {
    await run(
      adb,
      [
        '-s',
        serial,
        'pull',
        '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/UM03',
        root + '/screenshots',
      ],
      { timeout: 60_000 },
    );
  } catch (error) {
    report.screenshotCollectionError = error.message;
  }
  report.finishedAt = new Date().toISOString();
  await save();
  console.log(
    JSON.stringify({
      status: report.status,
      serial,
      api: report.api,
      passed: report.passed,
      report: root + '/report.json',
    }),
  );
}
