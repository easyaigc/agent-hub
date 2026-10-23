import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { join, resolve } from 'node:path';

// Native input and persistence checks with synthetic large history. This does
// not contact a Host or model and cannot replace original-desktop acceptance.
const device = process.env.IOS_SIMULATOR_ID;
assert(device, 'Select a dedicated simulator with IOS_SIMULATOR_ID');
const directory = resolve('.local/composer-performance/' + Date.now());
await mkdir(directory, { recursive: true, mode: 0o700 });
const resultBundle = join(directory, 'result.xcresult');
const tests = [
  'testComposerTypingPerformance',
  'testComposerChinesePasteAndImmediateSend',
  'testClientSwitchKeepsDraftOnOriginalTarget',
  'testUnsentNewDraftSurvivesClientSwitchAndRelaunch',
  'testUnifiedComposerStopResumeAndRestart',
  'testDictationCancelFinishAndSend',
];
const fingerprint = async () => {
  const hash = createHash('sha256');
  const files = execFileSync(
    'git',
    [
      'ls-files',
      '-co',
      '--exclude-standard',
      'apps/ios',
      'packages/protocol',
      'packages/mobile-core',
      'gradle',
      'gradlew',
      'gradlew.bat',
      'settings.gradle.kts',
      'build.gradle.kts',
      'gradle.properties',
      'scripts/mobile/build-ios-core.sh',
      'scripts/mobile/gradle.mjs',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  for (const file of new Set(files))
    hash
      .update(file + '\0')
      .update(await readFile(file))
      .update('\0');
  return hash.digest('hex');
};
const evidence = {
  nativeSourceSha256: await fingerprint(),
  status: 'running',
  syntheticHistory: true,
  device,
  resultBundle,
  isolation: 'fresh-simulator-boot-and-install-per-case',
  tooling: await Promise.all(
    ['scripts/composer-performance-ios.mjs', 'scripts/mobile/native-ios-cases.mjs'].map(
      async (file) => ({
        file,
        sha256: createHash('sha256')
          .update(await readFile(file))
          .digest('hex'),
      }),
    ),
  ),
};
const save = () => writeFile(join(directory, 'run.json'), JSON.stringify(evidence, null, 2));
await save();
const child = spawn(process.execPath, ['scripts/mobile/native-ios-cases.mjs', ...tests], {
  env: {
    ...process.env,
    IOS_RESULT_BUNDLE_PATH: resultBundle,
    IOS_DERIVED_DATA_PATH: '.build/ios-typing',
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});
const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
for (const stream of [child.stdout, child.stderr])
  createInterface({ input: stream }).on('line', (line) => {
    log.write(line + '\n');
    if (line.startsWith('COMPOSER_TYPING_METRICS '))
      evidence.typingMetrics = JSON.parse(line.slice('COMPOSER_TYPING_METRICS '.length));
    if (/^Test Case|: error:|COMPOSER_TYPING_METRICS|^\*\* TEST /.test(line)) console.log(line);
  });
try {
  const code = await new Promise((done, reject) => {
    child.once('close', done);
    child.once('error', reject);
  });
  await new Promise((done) => log.end(done));
  const summary = JSON.parse(
    execFileSync(
      'xcrun',
      [
        'xcresulttool',
        'get',
        'test-results',
        'summary',
        '--path',
        resultBundle,
        '--format',
        'json',
      ],
      { encoding: 'utf8' },
    ),
  );
  Object.assign(evidence, {
    passedTests: summary.passedTests,
    failedTests: summary.failedTests,
    skippedTests: summary.skippedTests,
  });
  assert.equal(code, 0);
  assert.equal(summary.passedTests, tests.length);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  assert.equal(
    await fingerprint(),
    evidence.nativeSourceSha256,
    'Native sources changed during acceptance',
  );
  evidence.status = 'passed';
  await save();
  console.log(JSON.stringify(evidence));
} catch (error) {
  evidence.status = 'failed';
  evidence.error = error.message;
  await save();
  throw error;
}
