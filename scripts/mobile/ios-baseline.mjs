import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

// Each phase reuses this native regression baseline. Real desktop/device tests
// have separate providers and are never silently skipped by this runner.
const nativeTests = [
  'testCloseDemoReturnsToOnboardingAndPersists',
  'testUnifiedComposerStopResumeAndRestart',
  'testDictationCancelFinishAndSend',
  'testDictationSwitchAndBackgroundKeepOriginalTarget',
  'testDictationDeniedAndDarkAppearance',
  'testDictationDisabledRetainsDraftAndAllowsRetry',
  'testSidebarSearchAndProjectSelection',
  'testSidebarSwipeOpenCloseKeepsDraft',
  'testSidebarSwipeDirectionAndVerticalScrolling',
  'testSidebarContinuousDragAndReversal',
  'testSidebarRecentAndProjectExpansion',
  'testClientSwitchKeepsDraftOnOriginalTarget',
  'testNewConversationCreatedOnFirstSend',
  'testOfflineClientKeepsEditableDraftAndDisablesSend',
  'testUnsentNewDraftSurvivesClientSwitchAndRelaunch',
  'testSystemAttachmentPickers',
  'testFloatingChatControlsAndExpandingDraft',
  'testFloatingJumpToLatestClearsComposer',
  'testDarkAppearance',
  'testTaskNotificationPreferencesAndPermissionDenied',
];
const fixtures = {
  clients: ['client-edit', 2],
  composer: ['composer-state', 2],
  generation: ['generation-settings', 1],
  plan: ['plan-mode', 2],
  receipts: ['receipt-notices', 1],
  compaction: ['context-compaction', 1],
  creation: ['desktop-creation', 1],
  recovery: ['desktop-creation-recovery', 3],
  reading: ['read-cancellation', 2],
  unread: ['unread-state', 3],
  badge: ['mobile-unread-badge', 2],
  history: ['history-pagination', 5],
  sidebar: ['sidebar-refresh', 9],
  readUI: ['native-read-ui', 2],
};
const simulator = process.env.IOS_SIMULATOR_ID;
assert(simulator, 'Set IOS_SIMULATOR_ID to a dedicated simulator');
const devices = JSON.parse(execFileSync('xcrun', ['simctl', 'list', 'devices', '-j']));
const device = Object.values(devices.devices)
  .flat()
  .find((entry) => entry.udid === simulator);
assert(device?.isAvailable, 'The requested simulator must exist and be available');
const phase = process.env.MOBILE_PHASE ?? 'A0';
assert(/^(A[0-8]|UM0[1-8])$/.test(phase));
const groups = process.argv.slice(2);
if (!groups.length) groups.push('native', ...Object.keys(fixtures));
assert(groups.every((group) => group === 'native' || Object.hasOwn(fixtures, group)));
const output = `.local/mobile-acceptance/${phase}/${Date.now()}`;
await mkdir(output, { recursive: true });
const sourceRoots = ['apps/ios', 'packages/protocol'];
if (phase !== 'A0')
  sourceRoots.push(
    'packages/mobile-core',
    'gradle',
    'gradlew',
    'gradlew.bat',
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'scripts/mobile/build-ios-core.sh',
    'scripts/mobile/gradle.mjs',
  );
const files = execFileSync('git', ['ls-files', '-co', '--exclude-standard', ...sourceRoots], {
  encoding: 'utf8',
})
  .trim()
  .split('\n')
  .filter(Boolean)
  .sort();
const digest = createHash('sha256');
for (const file of new Set(files)) {
  digest
    .update(file + '\0')
    .update(await readFile(file))
    .update('\0');
}
const sourceSha256 = digest.digest('hex');
const report = {
  phase,
  scope:
    groups.length === 1 + Object.keys(fixtures).length && new Set(groups).size === groups.length
      ? 'complete-baseline'
      : 'selected-groups',
  sourceSha256,
  simulator,
  device: device.name,
  output,
  groups: [],
  status: 'running',
};
await writeFile(output + '/report.json', JSON.stringify(report, null, 2));
const toolingFiles = [
  'scripts/mobile/ios-baseline.mjs',
  'scripts/mobile/export-ios-baseline.mjs',
  'scripts/mobile/native-ios-cases.mjs',
  'scripts/mobile/android-read-fixture.mjs',
  'scripts/mobile/generate-design-tokens.mjs',
  'package.json',
  'pnpm-lock.yaml',
  'design/mobile/tokens.json',
  ...Object.values(fixtures).map(([file]) => 'scripts/' + file + '-ios-fixture.mjs'),
];
const inputs = await Promise.all(
  toolingFiles.map(async (file) => ({
    file,
    sha256: createHash('sha256')
      .update(await readFile(file))
      .digest('hex'),
  })),
);
await writeFile(
  output + '/tooling-inputs.json',
  JSON.stringify(
    {
      sourceSha256,
      capturedAt: new Date().toISOString(),
      inputs,
    },
    null,
    2,
  ) + '\n',
);
for (const group of groups) {
  // A dedicated simulator contains only synthetic App data. Notification
  // permission survives the App's test reset, so isolate independent groups
  // by reinstalling. Relaunch/persistence cases within a group keep their data.
  execFileSync('xcrun', ['simctl', 'uninstall', simulator, 'com.codexmanage.ios']);
  const start = Date.now();
  const logPath = output + '/' + group + '.log';
  const nativeResult = output + '/native.xcresult';
  const expected = group === 'native' ? nativeTests.length : fixtures[group][1];
  const environment = {
    ...process.env,
    IOS_DERIVED_DATA_PATH: '.build/mobile/' + phase + '/ios',
    IOS_RESULT_BUNDLE_PATH: output + '/' + group + '.xcresult',
    UNREAD_ACCEPTANCE_OUTPUT: output + '/' + group,
  };
  const command = process.execPath;
  const args =
    group === 'native'
      ? ['scripts/mobile/native-ios-cases.mjs', ...nativeTests]
      : ['scripts/' + fixtures[group][0] + '-ios-fixture.mjs'];
  console.log('Running', phase, group, 'on dedicated simulator; log:', logPath);
  const log = createWriteStream(logPath);
  const child = spawn(command, args, { env: environment });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  const code = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', resolve);
  });
  await new Promise((resolve) => log.end(resolve));
  const text = await readFile(logPath, 'utf8');
  const result =
    group === 'native'
      ? nativeResult
      : text.match(/Writing result bundle at path:\s*([^\n]+)/)?.[1]?.trim();
  let summary;
  if (result) {
    try {
      summary = JSON.parse(
        execFileSync(
          'xcrun',
          ['xcresulttool', 'get', 'test-results', 'summary', '--path', result, '--compact'],
          { encoding: 'utf8' },
        ),
      );
    } catch {
      /* Missing or incomplete bundles fail the gate below. */
    }
  }
  const passed =
    code === 0 &&
    summary?.result === 'Passed' &&
    summary.totalTestCount === expected &&
    summary.passedTests === expected &&
    summary.failedTests === 0 &&
    summary.skippedTests === 0 &&
    summary.expectedFailures === 0;
  report.groups.push({
    group,
    expected,
    exitCode: code,
    elapsedMs: Date.now() - start,
    result,
    summary,
    passed,
  });
  report.status = passed ? 'running' : 'failed';
  await writeFile(output + '/report.json', JSON.stringify(report, null, 2));
  assert(passed, `${group} failed or omitted a required test; inspect ${logPath}`);
  console.log('Passed', group, expected, 'tests, zero skips');
}
report.status = 'passed';
await writeFile(output + '/report.json', JSON.stringify(report, null, 2));
console.log('Simulator regression passed; evidence:', output + '/report.json');
