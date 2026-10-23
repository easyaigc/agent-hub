import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { startNativeReadFixture } from './mobile/android-read-fixture.mjs';
const simulator = process.env.IOS_SIMULATOR_ID;
assert(simulator, 'Use a dedicated native read UI simulator');
const directory = '.local/native-read-ui-dev';
await mkdir(directory, { recursive: true });
const originalFont = execFileSync('xcrun', ['simctl', 'ui', simulator, 'content_size'], {
  encoding: 'utf8',
}).trim();
const fixture = await startNativeReadFixture(null, null, { simulator, port: 8817 });
const tests = [
  'testNativeReadMarkdownToolAndDiffParity',
  'testNativeReadDarkLandscapeAndLargeFontParity',
];
const resultBundle =
  process.env.IOS_RESULT_BUNDLE_PATH ?? directory + '/fixture-' + Date.now() + '.xcresult';
const child = spawn(
  'xcodebuild',
  [
    '-project',
    'apps/ios/CodexManage.xcodeproj',
    '-scheme',
    'CodexManage',
    '-destination',
    'platform=iOS Simulator,id=' + simulator,
    '-derivedDataPath',
    process.env.IOS_DERIVED_DATA_PATH ?? '.build/ios-native-read-ui',
    '-resultBundlePath',
    resultBundle,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    'CODE_SIGNING_ALLOWED=YES',
    'CODE_SIGN_IDENTITY=-',
    'ONLY_ACTIVE_ARCH=YES',
    ...tests.map((test) => '-only-testing:CodexManageUITests/CodexManageUITests/' + test),
    'test',
  ],
  { stdio: 'inherit', env: process.env },
);
try {
  const code = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', resolve);
  });
  assert(fixture.requests.some((request) => request.path === '/v1/history'));
  assert(
    fixture.requests.every((request) => request.method === 'GET'),
    'Native read scene must not send remote commands',
  );
  await writeFile(
    directory + '/fixture-result.json',
    JSON.stringify(
      {
        status: code === 0 ? 'passed' : 'failed',
        tests,
        resultBundle,
        realCodexRuntime: false,
        fonts: ['large', 'extra-extra-extra-large'],
        requests: fixture.requests,
      },
      null,
      2,
    ) + '\n',
  );
  assert.equal(code, 0, 'Both native read UI tests must pass');
} finally {
  child.kill('SIGTERM');
  await fixture.close();
  execFileSync('xcrun', ['simctl', 'ui', simulator, 'content_size', originalFont]);
}
