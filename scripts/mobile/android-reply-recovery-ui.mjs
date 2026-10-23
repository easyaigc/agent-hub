import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { startReplyRecoveryUIFixture } from './reply-recovery-ui-fixture.mjs';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const serial = process.env.ANDROID_SERIAL;
const physical = serial && !serial.startsWith('emulator-');
assert(serial && (!physical || process.env.ANDROID_ALLOW_PHYSICAL === '1'));
const adb = resolve(
  JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8')).androidSdk,
  'platform-tools/adb',
);
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
const nonce = randomUUID();
const root = '.local/android-development/upstream-migration/UM02/ui-' + serial + '-' + Date.now();
await mkdir(root, { recursive: true, mode: 0o700 });
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const app = '.build/native/android/outputs/apk/debug/android-debug.apk';
const test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
const sourceHash = async () => {
  const paths = execFileSync(
    'git',
    [
      'ls-files',
      '-co',
      '--exclude-standard',
      'apps/android',
      'packages/mobile-core',
      'packages/protocol',
      'scripts/mobile/android-reply-recovery-ui.mjs',
      'scripts/mobile/reply-recovery-ui-fixture.mjs',
      'scripts/mobile/android-install-if-needed.mjs',
      'packages/codex-adapter/src/runtime-session.ts',
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
  for (const name of [...new Set(paths)].sort())
    hash
      .update(name + '\0')
      .update(await readFile(name))
      .update('\0');
  return hash.digest('hex');
};
const report = {
  status: 'running',
  serial,
  api: Number((await call('shell', 'getprop', 'ro.build.version.sdk')).trim()),
  physical: !!physical,
  scope:
    'Production Compose/session/Keystore/real TLS HTTP with isolated per-test storage and synthetic authority/receipts. No original desktop/model proof.',
  class: 'com.agenthub.android.MobileReplyRecoveryUiTest',
  expectedTests: 6,
  sourceSha256: await sourceHash(),
  apkSha256: sha(await readFile(app)),
  testApkSha256: sha(await readFile(test)),
  installations: [],
  screenshots: [],
  visualReview: 'pending',
  realModelTasks: 0,
};
await writeFile(root + '/report.json', JSON.stringify(report, null, 2), { mode: 0o600 });
let fixture;
try {
  for (const [packageName, apk] of [
    ['com.easyai.agenthub.android.debug', app],
    ['com.easyai.agenthub.android.debug.test', test],
  ])
    report.installations.push(
      await installAndroidApkIfNeeded({ adb, serial, packageName, apk, allowPhysical: !!physical }),
    );
  fixture = await startReplyRecoveryUIFixture(adb, serial);
  if (physical) {
    await call('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
    const launch = await call(
      'shell',
      'am',
      'start',
      '-W',
      '-n',
      'com.easyai.agenthub.android.debug/androidx.activity.ComponentActivity',
    );
    await writeFile(root + '/foreground-test-activity.log', launch, { mode: 0o600 });
    assert(
      /Status: ok/.test(launch),
      'Start only the dedicated test Activity in the foreground before MIUI instrumentation',
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
    report.class,
    '-e',
    'recoveryControl',
    fixture.control,
    '-e',
    'recoveryRun',
    nonce,
    '-e',
    'recoveryForeground',
    physical ? '1' : '0',
    'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
  );
  await writeFile(root + '/instrumentation.log', output, { mode: 0o600 });
  assert(output.includes('OK (6 tests)'), 'All six native recovery scenarios must pass');
  assert(
    !/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)/.test(
      output,
    ),
    'No failed/skipped native scenario',
  );
  const peer = fixture.metrics();
  await writeFile(root + '/peer-proof.json', JSON.stringify(peer, null, 2), { mode: 0o600 });
  assert.equal(peer.fixtureErrors.length, 0, 'Synthetic peer assertions must remain valid');
  await call(
    'pull',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/UM02/' + nonce,
    root + '/screenshots',
  );
  for (const name of (await readdir(root + '/screenshots'))
    .filter((name) => name.endsWith('.png'))
    .sort())
    report.screenshots.push({ name, sha256: sha(await readFile(root + '/screenshots/' + name)) });
  assert.equal(report.screenshots.length, 13, 'Every planned native screenshot must exist');
  assert.equal(
    await sourceHash(),
    report.sourceSha256,
    'Native UI source changed during acceptance',
  );
  assert.equal(sha(await readFile(app)), report.apkSha256);
  assert.equal(sha(await readFile(test)), report.testApkSha256);
  report.status = 'passed';
  report.passed = 6;
  report.failed = report.skipped = 0;
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
  if (error.stdout)
    await writeFile(root + '/command-failure-output.log', error.stdout, { mode: 0o600 });
  if (!report.screenshots.length) {
    try {
      await call(
        'pull',
        '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/UM02/' + nonce,
        root + '/retained-screenshots',
      );
      for (const name of (await readdir(root + '/retained-screenshots'))
        .filter((name) => name.endsWith('.png'))
        .sort())
        report.screenshots.push({
          name,
          sha256: sha(await readFile(root + '/retained-screenshots/' + name)),
          retainedFromFailure: true,
        });
    } catch (retentionError) {
      report.screenshotRetentionError = retentionError.message;
    }
  }
} finally {
  if (fixture) {
    await writeFile(root + '/peer-final.json', JSON.stringify(fixture.metrics(), null, 2), {
      mode: 0o600,
    });
    await fixture.close();
  }
  report.finishedAt = new Date().toISOString();
  await writeFile(root + '/report.json', JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(
    JSON.stringify({
      status: report.status,
      serial,
      report: root + '/report.json',
      passed: report.passed,
      visualReview: report.visualReview,
    }),
  );
}
