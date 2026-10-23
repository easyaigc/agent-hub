import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { startPrivateConnectionFixture } from './private-connection-fixture.mjs';
import { installAndroidApkIfNeeded } from './android-install-if-needed.mjs';

const serial = process.env.ANDROID_SERIAL;
assert(serial && (serial.startsWith('emulator-') || process.env.ANDROID_ALLOW_PHYSICAL === '1'));
const local = JSON.parse(await readFile('.local/mobile-toolchain.json', 'utf8'));
const adb = resolve(process.env.ANDROID_HOME ?? local.androidSdk, 'platform-tools/adb');
const run = promisify(execFile);
const call = async (...args) =>
  (
    await run(adb, ['-s', serial, ...args], {
      timeout: 600_000,
      maxBuffer: 32 * 1024 * 1024,
      encoding: 'utf8',
    })
  ).stdout;
assert.equal((await call('get-state')).trim(), 'device');
const root =
  '.local/android-development/upstream-migration/UM04/native-' + serial + '-' + Date.now();
await mkdir(root, { recursive: true, mode: 0o700 });
const sha = (v) => createHash('sha256').update(v).digest('hex');
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
      'scripts/mobile/android-private-connection-acceptance.mjs',
      'scripts/mobile/private-connection-fixture.mjs',
      'scripts/mobile/private-connection-vectors.mjs',
      'scripts/mobile/android-install-if-needed.mjs',
      'apps/host-agent/src/private-connections.ts',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n');
  const hash = createHash('sha256');
  for (const file of [...new Set(files)].sort())
    hash
      .update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return hash.digest('hex');
};
const before = await fingerprint();
const app = '.build/native/android/outputs/apk/debug/android-debug.apk';
const test = '.build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk';
const binaries = { appSha256: sha(await readFile(app)), testSha256: sha(await readFile(test)) };
const installs = [];
for (const [packageName, apk] of [
  ['com.easyai.agenthub.android.debug', app],
  ['com.easyai.agenthub.android.debug.test', test],
])
  installs.push(
    await installAndroidApkIfNeeded({
      adb,
      serial,
      packageName,
      apk,
      allowPhysical: process.env.ANDROID_ALLOW_PHYSICAL === '1',
    }),
  );
const fixture = await startPrivateConnectionFixture(root + '/fixture');
const ports = [fixture.port, fixture.controlPort];
const ownedReverses = [];
try {
  // Physical devices can sleep while the API cases run; wake without changing lock settings.
  await call('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP');
  const keyguard = await call('shell', 'dumpsys', 'window', 'policy');
  if (/showing=true/.test(keyguard) && /secure=false/.test(keyguard)) {
    await call('shell', 'wm', 'dismiss-keyguard');
    const size = (await call('shell', 'wm', 'size')).match(/(\d+)x(\d+)/);
    assert(size, 'Capture the current device dimensions before dismissing its nonsecure cover');
    const x = Math.floor(Number(size[1]) / 2);
    await call(
      'shell',
      'input',
      'swipe',
      String(x),
      String(Math.floor(Number(size[2]) * 0.85)),
      String(x),
      String(Math.floor(Number(size[2]) * 0.2)),
      '400',
    );
  }
  for (const port of ports) {
    await call('reverse', '--no-rebind', 'tcp:' + port, 'tcp:' + port);
    ownedReverses.push(port);
  }
  if (Number((await call('shell', 'getprop', 'ro.build.version.sdk')).trim()) >= 37)
    await call(
      'shell',
      'pm',
      'grant',
      'com.easyai.agenthub.android.debug',
      'android.permission.ACCESS_LOCAL_NETWORK',
    );
  const classes = ['MobilePrivateConnectionTest', 'MobilePrivateConnectionUiTest'];
  const runId = randomUUID();
  await mkdir(root + '/screenshots', { recursive: true, mode: 0o700 });
  const expected = (
    await Promise.all(
      classes.map((name) =>
        readFile(
          'apps/android/src/androidTest/kotlin/com/agenthub/android/' + name + '.kt',
          'utf8',
        ),
      ),
    )
  ).reduce((count, source) => count + (source.match(/@Test\s/g)?.length ?? 0), 0);

  const output = await call(
    'shell',
    'am',
    'instrument',
    '-w',
    '-r',
    '-e',
    'class',
    classes.map((name) => 'com.agenthub.android.' + name).join(','),
    '-e',
    'privateRun',
    runId,
    '-e',
    'privateForeground',
    '1',
    '-e',
    'privateEndpoint',
    fixture.endpoint,
    '-e',
    'privateControl',
    'http://127.0.0.1:' + fixture.controlPort,
    'com.easyai.agenthub.android.debug.test/androidx.test.runner.AndroidJUnitRunner',
  );
  await writeFile(root + '/instrumentation.txt', output, { mode: 0o600 });
  const actual = Number(output.match(/OK \((\d+) tests?\)/)?.[1]);
  const passed =
    actual === expected &&
    !/FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[234]/.test(
      output,
    );
  const after = await fingerprint();
  assert.equal(after, before, 'Never mix live source changes into a native pass');
  const report = {
    suite: 'UM04-private-connection-native',
    serial,
    sourceSha256: before,
    ...binaries,
    expected,
    passed: passed ? actual : 0,
    status: passed ? 'passed' : 'failed',
    installs,
    fixture: {
      target: fixture.target,
      fingerprint: fixture.fingerprint,
      producerSourceSha256: sha(await readFile('apps/host-agent/src/private-connections.ts')),
      producerDistSha256: sha(await readFile('apps/host-agent/dist/private-connections.js')),
    },
    lastCaseOnlyStats: fixture.stats(),
    allFixtureRuns: fixture.history(),
    realModelProof: false,
    screenshots: { path: root + '/screenshots/' + runId, realLANorVPN: false },
  };
  await call(
    'pull',
    '/sdcard/Android/data/com.easyai.agenthub.android.debug/files/acceptance/UM04/' + runId,
    root + '/screenshots/' + runId,
  );
  await writeFile(root + '/report.json', JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
  console.log(
    JSON.stringify({
      report: root + '/report.json',
      status: report.status,
      expected,
      passed: report.passed,
    }),
  );
  assert(passed, 'Full selected native group must pass');
} finally {
  for (const port of ownedReverses)
    await call('reverse', '--remove', 'tcp:' + port).catch(() => {});
  await fixture.close();
}
