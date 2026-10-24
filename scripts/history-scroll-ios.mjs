import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { dirname, join, resolve } from 'node:path';
import { verifyIOSResultDevice } from './mobile/ios-result-device.mjs';

// Independent native history acceptance. Live mode reads an explicit existing
// Host/thread; neither mode submits a model input or installs the formal app.
const physical = process.env.IOS_PHYSICAL_DEVICE_ID;
const device = physical ?? process.env.IOS_SIMULATOR_ID;
assert(device, 'Set IOS_PHYSICAL_DEVICE_ID or a dedicated IOS_SIMULATOR_ID');
assert(!physical || !process.env.IOS_SIMULATOR_ID, 'Select one device');
const livePath = process.env.HISTORY_SCROLL_LIVE_FIXTURE;
assert(!process.argv.includes('--live') || livePath, 'Set the private HISTORY_SCROLL_LIVE_FIXTURE');
assert(!livePath || physical, 'Real history regression requires the paired physical iPhone');
const liveFixture = livePath ? JSON.parse(await readFile(livePath, 'utf8')) : undefined;
if (liveFixture) {
  assert(liveFixture.hostId && liveFixture.threadId && liveFixture.token);
  assert.equal(new URL(liveFixture.endpoint).protocol, 'https:');
}
const directory = resolve('.local/history-scroll/' + Date.now());
const source = join(directory, 'source');
await mkdir(source, { recursive: true, mode: 0o700 });
const files = execFileSync(
  'git',
  [
    'ls-files',
    '-co',
    '--exclude-standard',
    'apps/ios',
    'apps/android',
    'packages/mobile-core',
    'packages/protocol',
    'scripts/mobile',
    'gradle',
    'gradlew',
    'gradlew.bat',
    'gradle.properties',
    'build.gradle.kts',
    'settings.gradle.kts',
  ],
  { encoding: 'utf8' },
)
  .trim()
  .split('\n')
  .filter(Boolean)
  .sort();
const hash = createHash('sha256');
for (const file of new Set(files)) {
  const bytes = await readFile(file);
  const mode = (await stat(file)).mode & 0o777;
  hash
    .update(file + '\0' + mode + '\0')
    .update(bytes)
    .update('\0');
  await mkdir(dirname(join(source, file)), { recursive: true });
  await writeFile(join(source, file), bytes, { mode });
}
await mkdir(join(source, '.local'));
try {
  await writeFile(
    join(source, '.local/mobile-toolchain.json'),
    await readFile('.local/mobile-toolchain.json'),
    { mode: 0o600 },
  );
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
const bundleId = physical ? 'com.easyai.agenthub.historyscrolltests' : 'com.codexmanage.ios';
// Match the formal Kotlin/Native optimization mode. DEBUG only enables opt-in
// measurement/fixture hooks in this disposable Swift build, never in the formal app.
const configuration = physical ? 'Release' : 'Debug';
const project = join(source, 'apps/ios/CodexManage.xcodeproj');
if (physical) {
  // Only this disposable test copy omits notification capabilities, allowing a
  // cached wildcard development profile. Never change the production project.
  const path = join(project, 'project.pbxproj');
  const data = JSON.parse(
    execFileSync('plutil', ['-convert', 'json', '-o', '-', path], { encoding: 'utf8' }),
  );
  for (const object of Object.values(data.objects)) {
    for (const target of Object.values(object.attributes?.TargetAttributes ?? {}))
      target.SystemCapabilities = {};
    for (const key of Object.keys(object.buildSettings ?? {}))
      if (key.startsWith('CODE_SIGN_ENTITLEMENTS') || key.startsWith('PROVISIONING_PROFILE'))
        delete object.buildSettings[key];
  }
  await writeFile(path, JSON.stringify(data));
  execFileSync('plutil', ['-convert', 'xml1', path]);
}
const resultBundle = join(directory, 'result.xcresult');
const tests = liveFixture
  ? ['testHistoryContinuousScrollOriginalDesktopDEV', 'testHistoryRemotePrependOriginalDesktopDEV']
  : ['testHistoryContinuousScrollPerformance', 'testHistoryShortListPrependKeepsPosition'];
const evidence = {
  status: 'running',
  sourceSha256: hash.digest('hex'),
  harnessSha256: createHash('sha256')
    .update(await readFile('scripts/history-scroll-ios.mjs'))
    .digest('hex'),
  physical: Boolean(physical),
  syntheticHistory: !liveFixture,
  device,
  bundleId,
  configuration,
  workProbe: process.env.TEST_RUNNER_HISTORY_SCROLL_WORK_PROBE === '1',
  measurementProbeCondition: physical ? 'DEBUG in the independent test copy only' : 'Debug',
  resultBundle,
  notificationCapabilitiesInTestCopy: !physical,
  realDesktop: Boolean(liveFixture),
  targetReactivationsDuringScroll: 0,
};
const save = () => writeFile(join(directory, 'run.json'), JSON.stringify(evidence, null, 2) + '\n');
await save();
const log = createWriteStream(join(directory, 'xcode.log'), { mode: 0o600 });
try {
  if (physical) {
    const detailsPath = join(directory, 'coredevice-before.json');
    execFileSync(
      'xcrun',
      [
        'devicectl',
        'device',
        'info',
        'details',
        '--device',
        physical,
        '--json-output',
        detailsPath,
      ],
      { stdio: 'ignore' },
    );
    const details = JSON.parse(await readFile(detailsPath, 'utf8')).result;
    evidence.deviceState = {
      reality: details.hardwareProperties.reality,
      model: details.hardwareProperties.marketingName,
      osVersion: details.deviceProperties.osVersionNumber,
      pairing: details.connectionProperties.pairingState,
      tunnel: details.connectionProperties.tunnelState,
      transport: details.connectionProperties.transportType,
    };
    assert.equal(evidence.deviceState.reality, 'physical');
    assert.equal(evidence.deviceState.pairing, 'paired');
    assert.equal(evidence.deviceState.tunnel, 'connected');
    await save();
  }
  const child = spawn(
    'xcodebuild',
    [
      '-project',
      project,
      '-scheme',
      'CodexManage',
      '-configuration',
      configuration,
      '-destination',
      `platform=${physical ? 'iOS' : 'iOS Simulator'},id=${device}`,
      '-derivedDataPath',
      join(directory, 'derived'),
      '-resultBundlePath',
      resultBundle,
      '-parallel-testing-enabled',
      'NO',
      '-collect-test-diagnostics',
      'never',
      'SWIFT_OPTIMIZATION_LEVEL=-O',
      ...(physical ? ['SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG'] : []),
      'NOTIFICATION_APP_BUNDLE_ID=' + bundleId,
      ...(!physical ? ['CODE_SIGN_IDENTITY=-', 'CODE_SIGNING_ALLOWED=YES'] : []),
      ...tests.map((test) => '-only-testing:CodexManageUITests/CodexManageUITests/' + test),
      'test',
    ],
    {
      env: {
        ...process.env,
        ...(liveFixture
          ? { TEST_RUNNER_HISTORY_SCROLL_LIVE_JSON: JSON.stringify(liveFixture) }
          : {}),
        GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? resolve('.local/gradle-home'),
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  let scrollingCase = false;
  let issuedGesture = false;
  for (const stream of [child.stdout, child.stderr])
    createInterface({ input: stream }).on('line', (line) => {
      log.write(line + '\n');
      if (/^Test Case .*testHistoryContinuousScrollPerformance.*started/.test(line))
        scrollingCase = true;
      if (liveFixture && line.includes('"message-list" ScrollView')) scrollingCase = true;
      if (scrollingCase && line.includes('Press Target Application')) issuedGesture = true;
      // XCTest can reactivate a background target before injecting a gesture.
      // Such a run cannot establish an uninterrupted physical frame budget.
      if (scrollingCase && issuedGesture && line.trimEnd().endsWith('Activate ' + bundleId))
        evidence.targetReactivationsDuringScroll += 1;
      if (/^Test Case .*testHistoryContinuousScroll.*(?:passed|failed)/.test(line))
        scrollingCase = false;
      if (/^Test Case|HISTORY_SCROLL_(?:LIVE_)?(?:METRICS|PAGING)|^\*\* TEST/.test(line))
        console.log(line);
      if (line.startsWith('HISTORY_SCROLL_METRICS '))
        evidence.metrics = JSON.parse(line.slice('HISTORY_SCROLL_METRICS '.length));
      if (line.startsWith('HISTORY_SCROLL_LIVE_METRICS ')) {
        evidence.metrics = JSON.parse(line.slice('HISTORY_SCROLL_LIVE_METRICS '.length));
        scrollingCase = false;
      }
      if (line.startsWith('HISTORY_SCROLL_LIVE_PAGING '))
        evidence.paging = JSON.parse(line.slice('HISTORY_SCROLL_LIVE_PAGING '.length));
    });
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('close', done);
  });
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
  evidence.verifiedDestinations = verifyIOSResultDevice(summary, device);
  assert.equal(
    evidence.targetReactivationsDuringScroll,
    0,
    'XCTest reactivated a background target during scrolling; preserve this interrupted run and repeat separately',
  );
  assert.equal(code, 0);
  assert.equal(summary.passedTests, tests.length);
  assert.equal(summary.failedTests, 0);
  assert.equal(summary.skippedTests, 0);
  assert(evidence.metrics, 'Native scrolling evidence must be present');
  if (liveFixture) {
    assert(evidence.paging?.remotePages > 0, 'A real Host page must be read');
    assert(evidence.paging.remoteMessagesAdded > 0, 'The remote prefix must actually add messages');
    assert(evidence.paging.maxAnchorDeltaPt <= 3, 'The native reading anchor must stay within 3pt');
  }
  evidence.status = 'passed';
} catch (error) {
  evidence.status = 'failed';
  evidence.error = error.message;
  process.exitCode = 1;
} finally {
  await new Promise((done) => log.end(done));
  await save();
  console.log('History scroll evidence:', join(directory, 'run.json'));
}
