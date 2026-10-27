import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { prepareIsolatedIOSRunner } from './isolated-ios-runner.mjs';
import { verifyIOSResultDevice } from './ios-result-device.mjs';

const argument = (name) => {
  const index = process.argv.indexOf(name);
  assert(index >= 0 && process.argv[index + 1], name);
  return process.argv[index + 1];
};
const directory = resolve(argument('--directory'));
const device = argument('--device');
const bundleId = process.argv.includes('--bundle-id')
  ? argument('--bundle-id')
  : 'com.easyai.agenthub.quickattachments.acceptance';
assert(
  [
    'com.easyai.agenthub.quickattachments.acceptance',
    'com.easyai.agenthub.externalattachments.acceptance',
    'com.easyai.agenthub.attachmentsplan.acceptance',
    'com.easyai.agenthub.imagepreview.acceptance',
  ].includes(bundleId),
  'Use an independent attachment acceptance App',
);
const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
await mkdir(directory, { recursive: true, mode: 0o700 });
const isolated = await prepareIsolatedIOSRunner({
  template: argument('--template'),
  directory: join(directory, 'runner'),
  expectedAppBundleId: bundleId,
});
const configuration = JSON.parse(
  execFileSync('plutil', ['-convert', 'json', '-o', '-', isolated.template], { encoding: 'utf8' }),
);
const target = Object.values(configuration).find((value) => value?.IsUITestBundle);
assert(target);
const sourceApp = process.argv.includes('--source-app')
  ? resolve(argument('--source-app'))
  : undefined;
let sourceBundleId;
let sourceAppSha256;
if (sourceApp) {
  assert.equal(bundleId, 'com.easyai.agenthub.externalattachments.acceptance');
  const sourceInfo = JSON.parse(
    execFileSync('plutil', ['-convert', 'json', '-o', '-', join(sourceApp, 'Info.plist')], {
      encoding: 'utf8',
    }),
  );
  sourceBundleId = sourceInfo.CFBundleIdentifier;
  assert.equal(sourceBundleId, 'com.easyai.agenthub.externalfilesource.acceptance');
  sourceAppSha256 = createHash('sha256')
    .update(await readFile(join(sourceApp, sourceInfo.CFBundleExecutable)))
    .digest('hex');
}
const app = target.UITargetAppPath.replaceAll('__TESTROOT__', dirname(isolated.template));
const info = JSON.parse(
  execFileSync('plutil', ['-convert', 'json', '-o', '-', join(app, 'Info.plist')], {
    encoding: 'utf8',
  }),
);
assert.equal(info.CFBundleIdentifier, bundleId);
const appSha256 = createHash('sha256')
  .update(await readFile(join(app, info.CFBundleExecutable)))
  .digest('hex');
target.EnvironmentVariables = {
  ...target.EnvironmentVariables,
  QUICK_ATTACHMENT_BUNDLE_ID: bundleId,
  QUICK_ATTACHMENT_CODE: 'QA-' + randomUUID().slice(0, 8).toUpperCase(),
};
if (sourceBundleId) target.EnvironmentVariables.QUICK_ATTACHMENT_SOURCE_BUNDLE_ID = sourceBundleId;
const fixtureIndex = process.argv.indexOf('--fixture');
let liveFixture;
if (fixtureIndex >= 0) {
  const fixture = JSON.parse(await readFile(process.argv[fixtureIndex + 1], 'utf8'));
  assert(fixture.connection && fixture.pairingUri && fixture.control);
  assert(new URL(fixture.connection.endpoint).protocol === 'https:');
  liveFixture = fixture;
  target.EnvironmentVariables.QUICK_ATTACHMENT_FIXTURE = JSON.stringify(fixture);
}
await writeFile(isolated.template, JSON.stringify(configuration), { mode: 0o600 });
execFileSync('plutil', ['-convert', 'xml1', isolated.template]);
const install = execFileSync(
  'xcrun',
  [
    'devicectl',
    'device',
    'install',
    'app',
    '--device',
    device,
    '--json-output',
    join(directory, 'install.json'),
    app,
  ],
  { env, encoding: 'utf8' },
);
await writeFile(join(directory, 'install.log'), install, { mode: 0o600 });
if (sourceApp) {
  const output = execFileSync(
    'xcrun',
    [
      'devicectl',
      'device',
      'install',
      'app',
      '--device',
      device,
      '--json-output',
      join(directory, 'source-install.json'),
      sourceApp,
    ],
    { env, encoding: 'utf8' },
  );
  await writeFile(join(directory, 'source-install.log'), output, { mode: 0o600 });
}
let tests =
  fixtureIndex >= 0
    ? ['testQuickAttachmentSendOnDemandDEV']
    : [
        'testQuickAttachmentLocalDraftAndRecovery',
        'testQuickAttachmentCameraAndPaste',
        'testSystemAttachmentPickers',
      ];
const selected = process.argv.indexOf('--tests');
if (selected >= 0) {
  tests = argument('--tests').split(',');
  assert(
    tests.length > 0 &&
      tests.every(
        (name) =>
          /^testQuickAttachment[A-Za-z]+$/.test(name) || name === 'testSystemAttachmentPickers',
      ),
  );
}
const log = createWriteStream(join(directory, 'xcodebuild.log'), { mode: 0o600 });
const result = join(directory, 'result.xcresult');
const child = spawn(
  'xcodebuild',
  [
    'test-without-building',
    '-xctestrun',
    isolated.template,
    '-destination',
    'id=' + device,
    '-parallel-testing-enabled',
    'NO',
    '-collect-test-diagnostics',
    'never',
    '-resultBundlePath',
    result,
    ...tests.map((name) => '-only-testing:CodexManageUITests/CodexManageUITests/' + name),
  ],
  { env, stdio: ['ignore', 'pipe', 'pipe'] },
);
let sendIntentSeen = false;
for (const stream of [child.stdout, child.stderr]) {
  stream.pipe(log, { end: false });
  if (liveFixture)
    createInterface({ input: stream }).on('line', (line) => {
      if (line.includes('QUICK_ATTACHMENT_SEND_AUTHORIZED ' + liveFixture.connection.nonce)) {
        sendIntentSeen = true;
        writeFileSync(liveFixture.control.phaseFile, 'send-intent', { mode: 0o600 });
      }
      if (
        sendIntentSeen &&
        (line.includes('QUICK_ATTACHMENT_SEND_NATIVE_AUTHORIZED ' + liveFixture.connection.nonce) ||
          line.includes('Tap "send-message" Button'))
      ) {
        writeFileSync(liveFixture.control.phaseFile, 'send-authorized', { mode: 0o600 });
      }
    });
}
const exitCode = await new Promise((done, reject) => {
  child.once('error', reject);
  child.once('close', done);
});
await new Promise((done) => log.end(done));
const summary = JSON.parse(
  execFileSync('xcrun', ['xcresulttool', 'get', 'test-results', 'summary', '--path', result], {
    env,
    encoding: 'utf8',
  }),
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
      status: passed ? 'this_run_pass' : 'this_run_fail',
      passed,
      exitCode,
      bundleId,
      sourceBundleId,
      sourceAppSha256,
      appSha256,
      devices,
      tests,
      scope:
        fixtureIndex >= 0
          ? 'physical native attachment add-before-send and real desktop send'
          : 'physical native PhotoKit addition, local import/recovery, camera, explicit paste and system picker; no WeChat automation',
      runner: isolated.receipt,
      summary,
    },
    null,
    2,
  ) + '\n',
  { mode: 0o600 },
);
console.log(JSON.stringify({ passed, tests, directory, exitCode }));
assert(passed, 'Physical quick attachment acceptance failed; preserve original xcresult');
