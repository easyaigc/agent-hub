import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { prepareIsolatedIOSRunner } from './isolated-ios-runner.mjs';
import { verifyIOSResultDevice } from './ios-result-device.mjs';

const argument = (name) => {
  const index = process.argv.indexOf(name);
  assert(index >= 0 && process.argv[index + 1], name);
  return process.argv[index + 1];
};
const directory = resolve(argument('--directory'));
const device = argument('--device');
const bundleId = 'com.easyai.agenthub.thermal.acceptance';
const fingerprint = JSON.parse(await readFile(argument('--fingerprint'), 'utf8'));
assert(fingerprint.sourceSha256 && fingerprint.rootCodeDifferences?.length === 0);
await mkdir(directory, { recursive: true, mode: 0o700 });
const isolated = await prepareIsolatedIOSRunner({
  template: argument('--template'),
  directory: join(directory, 'runner'),
  expectedAppBundleId: bundleId,
});
const configuration = JSON.parse(
  execFileSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-', isolated.template], {
    encoding: 'utf8',
  }),
);
const target = Object.values(configuration).find((item) => item?.IsUITestBundle);
assert(target);
const fixtureIndex = process.argv.indexOf('--live-fixture');
const tests =
  fixtureIndex < 0
    ? ['testHistoryContinuousScrollPerformance', 'testHistoryShortListPrependKeepsPosition']
    : [
        'testHistoryContinuousScrollOriginalDesktopDEV',
        'testHistoryRemotePrependOriginalDesktopDEV',
      ];
if (fixtureIndex >= 0) {
  const fixture = JSON.parse(await readFile(process.argv[fixtureIndex + 1], 'utf8'));
  assert(
    fixture.hostId &&
      fixture.threadId &&
      fixture.token &&
      new URL(fixture.endpoint).protocol === 'https:',
  );
  target.EnvironmentVariables = {
    ...target.EnvironmentVariables,
    HISTORY_SCROLL_LIVE_JSON: JSON.stringify(fixture),
    HISTORY_SCROLL_WORK_PROBE: '1',
  };
}
await writeFile(isolated.template, JSON.stringify(configuration), { mode: 0o600 });
execFileSync('/usr/bin/plutil', ['-convert', 'xml1', isolated.template]);
const resultBundle = join(directory, 'result.xcresult');
const log = createWriteStream(join(directory, 'xcodebuild.log'), { mode: 0o600 });
const child = spawn(
  'xcodebuild',
  [
    'test-without-building',
    '-xctestrun',
    isolated.template,
    '-destination',
    'id=' + device,
    '-resultBundlePath',
    resultBundle,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    ...tests.map((test) => '-only-testing:CodexManageUITests/CodexManageUITests/' + test),
  ],
  { env: process.env, stdio: ['ignore', 'pipe', 'pipe'] },
);
for (const stream of [child.stdout, child.stderr]) stream.pipe(log, { end: false });
const exitCode = await new Promise((done, reject) => {
  child.once('error', reject);
  child.once('close', done);
});
await new Promise((done) => log.end(done));
const summary = JSON.parse(
  execFileSync(
    'xcrun',
    ['xcresulttool', 'get', 'test-results', 'summary', '--path', resultBundle],
    { encoding: 'utf8' },
  ),
);
const devices = verifyIOSResultDevice(summary, device);
const passed =
  exitCode === 0 &&
  summary.failedTests === 0 &&
  summary.passedTests === tests.length &&
  summary.skippedTests === 0;
await writeFile(
  join(directory, 'report.json'),
  JSON.stringify(
    {
      passed,
      status: passed ? 'this_run_pass' : 'this_run_fail',
      scope: 'physical native scrolling/history regression; no battery or temperature comparison',
      syntheticHistory: fixtureIndex < 0,
      realDesktopRead: fixtureIndex >= 0,
      bundleId,
      devices,
      tests,
      runner: isolated.receipt,
      fingerprint,
      summary,
    },
    null,
    2,
  ),
  { mode: 0o600 },
);
console.log(JSON.stringify({ directory, passed, devices, tests, exitCode }));
assert(passed, 'Physical history regression failed; preserve the original xcresult');
