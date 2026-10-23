import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';

// Each case gets a freshly booted dedicated Simulator. Keep animations and
// assertions intact; relaunches within a case retain the same installation.
const exec = promisify(execFile);
const simulator = process.env.IOS_SIMULATOR_ID;
const resultBundle = process.env.IOS_RESULT_BUNDLE_PATH;
const derived = process.env.IOS_DERIVED_DATA_PATH;
const tests = process.argv.slice(2);
assert(simulator && resultBundle && derived && tests.length);
assert(tests.every((test) => /^test[A-Za-z0-9]+$/.test(test)));
const devices = JSON.parse((await exec('xcrun', ['simctl', 'list', 'devices', '-j'])).stdout);
const device = Object.values(devices.devices)
  .flat()
  .find((entry) => entry.udid === simulator);
assert(device?.isAvailable, 'Use an explicit available Simulator');
const directory = resultBundle.replace(/\.xcresult$/, '-cases');
await mkdir(directory, { recursive: true });
const results = [];
for (const test of tests) {
  const current = JSON.parse((await exec('xcrun', ['simctl', 'list', 'devices', '-j'])).stdout);
  const state = Object.values(current.devices)
    .flat()
    .find((entry) => entry.udid === simulator).state;
  if (state === 'Booted') await exec('xcrun', ['simctl', 'shutdown', simulator]);
  await exec('xcrun', ['simctl', 'boot', simulator]);
  await exec('xcrun', ['simctl', 'bootstatus', simulator, '-b']);
  await exec('xcrun', ['simctl', 'uninstall', simulator, 'com.codexmanage.ios']);
  const path = directory + '/' + test + '.xcresult';
  const log = createWriteStream(directory + '/' + test + '.log');
  const child = spawn(
    'xcodebuild',
    [
      '-project',
      'apps/ios/CodexManage.xcodeproj',
      '-scheme',
      'CodexManage',
      '-destination',
      `platform=iOS Simulator,id=${simulator}`,
      '-derivedDataPath',
      derived,
      '-resultBundlePath',
      path,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      '-test-timeouts-enabled',
      'YES',
      '-default-test-execution-time-allowance',
      '180',
      '-maximum-test-execution-time-allowance',
      '180',
      'CODE_SIGNING_ALLOWED=YES',
      'CODE_SIGN_IDENTITY=-',
      'ONLY_ACTIVE_ARCH=YES',
      '-only-testing:CodexManageUITests/CodexManageUITests/' + test,
      'test',
    ],
    { stdio: ['ignore', 'pipe', 'pipe'], env: process.env },
  );
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      log.write(line + '\n');
      console.log(line.replace('Writing result bundle at path:', 'Isolated result bundle path:'));
    });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('close', done);
  });
  await new Promise((done) => log.end(done));
  const summary = JSON.parse(
    (
      await exec('xcrun', [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        path,
        '--format',
        'json',
      ])
    ).stdout,
  );
  results.push({ test, resultBundle: path, exitCode: code, summary });
  await writeFile(directory + '/index.json', JSON.stringify(results, null, 2) + '\n');
  assert.equal(code, 0);
  assert.equal(summary.totalTestCount, 1);
  assert.equal(summary.passedTests, 1);
  for (const field of ['failedTests', 'skippedTests', 'expectedFailures'])
    assert.equal(summary[field], 0);
  console.log('Passed isolated native case:', test);
}
if (results.length === 1) {
  // A one-case diagnostic still gets a distinct final result bundle.
  const { cp } = await import('node:fs/promises');
  await cp(results[0].resultBundle, resultBundle, { recursive: true });
} else
  await exec('xcrun', [
    'xcresulttool',
    'merge',
    '--output-path',
    resultBundle,
    ...results.map((r) => r.resultBundle),
  ]);
const summary = JSON.parse(
  (
    await exec('xcrun', [
      'xcresulttool',
      'get',
      'test-results',
      'summary',
      '--path',
      resultBundle,
      '--format',
      'json',
    ])
  ).stdout,
);
assert.equal(summary.totalTestCount, tests.length);
assert.equal(summary.passedTests, tests.length);
for (const field of ['failedTests', 'skippedTests', 'expectedFailures'])
  assert.equal(summary[field], 0);
console.log('Writing result bundle at path: ' + resultBundle);
console.log(
  JSON.stringify({
    suite: 'native-isolated',
    passed: tests.length,
    failed: 0,
    skipped: 0,
    isolation: 'fresh-simulator-boot-and-install-per-case',
  }),
);
