import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { runNativeProcess } from './native-process.mjs';
import { promisify } from 'node:util';

// Each case gets a freshly booted dedicated Simulator. Keep animations and
// assertions intact; relaunches within a case retain the same installation.
const execute = promisify(execFile);
const exec = (command, args, options = {}) =>
  execute(command, args, { timeout: 60000, maxBuffer: 4 * 1024 * 1024, ...options });
const simulator = process.env.IOS_SIMULATOR_ID;
const resultBundle = process.env.IOS_RESULT_BUNDLE_PATH;
const derived = process.env.IOS_DERIVED_DATA_PATH;
const tests = process.argv.slice(2);
const testClass = process.env.IOS_TEST_CLASS ?? 'CodexManageUITests';
assert(/^[A-Za-z][A-Za-z0-9]*UITests$/.test(testClass), 'Use a named native UI test class');
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
  const entry = {
    test,
    phase: 'preparing',
    status: 'running',
    startedAt: new Date().toISOString(),
  };
  results.push(entry);
  const save = () => writeFile(directory + '/index.json', JSON.stringify(results, null, 2) + '\n');
  const phase = async (name) => {
    entry.phase = name;
    console.log('Native phase:', test, name);
    await save();
  };
  console.log('Starting isolated native case:', test);
  try {
    await phase('simulator-state');
    const current = JSON.parse((await exec('xcrun', ['simctl', 'list', 'devices', '-j'])).stdout);
    const state = Object.values(current.devices)
      .flat()
      .find((entry) => entry.udid === simulator)?.state;
    assert(state, 'Dedicated simulator must still exist');
    if (state === 'Booted') {
      await phase('simulator-shutdown');
      await exec('xcrun', ['simctl', 'shutdown', simulator]);
    }
    await phase('simulator-boot');
    await exec('xcrun', ['simctl', 'boot', simulator]);
    await phase('simulator-ready');
    await exec('xcrun', ['simctl', 'bootstatus', simulator, '-b'], { timeout: 180000 });
    await phase('app-uninstall');
    await exec('xcrun', ['simctl', 'uninstall', simulator, 'com.codexmanage.ios']);
    const path = directory + '/' + test + '.xcresult';
    const log = createWriteStream(directory + '/' + test + '.log');
    await phase('xcodebuild');
    let result;
    try {
      result = await runNativeProcess(
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
          `-only-testing:CodexManageUITests/${testClass}/${test}`,
          'test',
        ],
        {
          timeoutMs: 420000,
          onLine: (line) => {
            log.write(line + '\n');
            console.log(
              line.replace('Writing result bundle at path:', 'Isolated result bundle path:'),
            );
          },
        },
      );
    } finally {
      await new Promise((done) => log.end(done));
    }
    Object.assign(entry, result, { resultBundle: path });
    console.log('Native process exited:', test, JSON.stringify(result));
    await save();
    await phase('official-summary');
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
    entry.summary = summary;
    await save();
    assert.equal(result.timedOut, false, 'Native command exceeded its wall deadline');
    assert.equal(result.interruptedBy, undefined, 'Native command interrupted');
    assert.equal(result.exitCode, 0);
    assert.equal(summary.totalTestCount, 1);
    assert.equal(summary.passedTests, 1);
    for (const field of ['failedTests', 'skippedTests', 'expectedFailures'])
      assert.equal(summary[field], 0);
    entry.status = 'passed';
    await save();
    console.log('Passed isolated native case:', test);
  } catch (error) {
    entry.status = 'failed';
    entry.error = { code: error.code, message: error.message, stderr: error.stderr?.slice(-4096) };
    await save();
    console.error('Failed isolated native case:', JSON.stringify(entry));
    throw error;
  }
}
console.log('Native phase: merge-result-bundles');
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
