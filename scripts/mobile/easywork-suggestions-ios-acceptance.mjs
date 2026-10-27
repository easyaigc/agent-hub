import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { prepareIsolatedIOSRunner } from './isolated-ios-runner.mjs';
import { verifyIOSResultDevice } from './ios-result-device.mjs';

const argument = (name) => {
  const at = process.argv.indexOf(name);
  assert(at >= 0 && process.argv[at + 1], name);
  return process.argv[at + 1];
};
const directory = resolve(argument('--directory'));
const device = argument('--device');
const bundleId = 'com.easyai.agenthub.suggestions.acceptance';
const environment = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
await mkdir(directory, { recursive: true, mode: 0o700 });
const fixture = JSON.parse(await readFile(argument('--fixture'), 'utf8'));
assert(fixture.threadId && fixture.sourceTurnId && fixture.text && fixture.pairingUri);
const isolated = await prepareIsolatedIOSRunner({
  template: argument('--template'),
  directory: join(directory, 'isolated-runner'),
  expectedAppBundleId: bundleId,
});
const configuration = JSON.parse(
  execFileSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-', isolated.template], {
    encoding: 'utf8',
  }),
);
const target = Object.values(configuration).find((value) => value?.IsUITestBundle);
assert(target);
const appPath = target.UITargetAppPath.replaceAll('__TESTROOT__', dirname(isolated.template));
const appInfo = JSON.parse(
  execFileSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-', join(appPath, 'Info.plist')], {
    encoding: 'utf8',
  }),
);
assert.equal(appInfo.CFBundleIdentifier, bundleId);
const appSha256 = createHash('sha256')
  .update(await readFile(join(appPath, appInfo.CFBundleExecutable)))
  .digest('hex');
target.EnvironmentVariables = {
  ...target.EnvironmentVariables,
  PROMPT_SUGGESTION_FIXTURE_JSON: JSON.stringify(fixture),
};
await writeFile(isolated.template, JSON.stringify(configuration), { mode: 0o600 });
execFileSync('/usr/bin/plutil', ['-convert', 'xml1', isolated.template]);
const resultBundle = join(directory, 'result.xcresult');
const output = createWriteStream(join(directory, 'xcodebuild.log'), { mode: 0o600 });
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
    '-only-testing:CodexManageUITests/CodexManageUITests/testEasyWorkRealDEVPromptSuggestion',
  ],
  { env: environment, stdio: ['ignore', 'pipe', 'pipe'] },
);
for (const stream of [child.stdout, child.stderr]) stream.pipe(output, { end: false });
const exitCode = await new Promise((done, reject) => {
  child.once('error', reject);
  child.once('close', done);
});
await new Promise((done) => output.end(done));
const summary = JSON.parse(
  execFileSync(
    'xcrun',
    ['xcresulttool', 'get', 'test-results', 'summary', '--path', resultBundle],
    { env: environment, encoding: 'utf8' },
  ),
);
const devices = verifyIOSResultDevice(summary, device);
const passed = exitCode === 0 && summary.failedTests === 0 && summary.passedTests >= 1;
await writeFile(
  join(directory, 'report.json'),
  JSON.stringify(
    {
      passed,
      status: passed ? 'this_run_pass' : 'this_run_fail',
      scope: 'EasyWork real Host runtime suggestion read/adopt/undo; no mobile send',
      devices,
      bundleId,
      appSha256,
      sourceTurnId: fixture.sourceTurnId,
      threadId: fixture.threadId,
      runner: isolated.receipt,
      summary,
    },
    null,
    2,
  ) + '\n',
  { mode: 0o600 },
);
assert(passed, 'Native suggestion acceptance failed; inspect private report');
console.log('Native EasyWork suggestion acceptance passed: ' + directory);
