import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const simulator = process.env.IOS_SIMULATOR_ID;
assert(simulator, 'Set IOS_SIMULATOR_ID to the dedicated iOS simulator');
const phase = process.env.MOBILE_PHASE ?? 'A1';
assert(/^(A[0-8]|UM0[1-8]|P(?:[0-9]|10))$/.test(phase));
const output =
  process.env.MOBILE_CORE_ACCEPTANCE_OUTPUT ??
  '.local/mobile-acceptance/' + phase + '/core-' + Date.now();
await mkdir(output, { recursive: true });
const fingerprint = async () => {
  const paths = execFileSync(
    'git',
    [
      'ls-files',
      '-co',
      '--exclude-standard',
      'packages/mobile-core',
      'packages/protocol/fixtures/mobile',
      'gradle',
      'gradlew',
      'gradlew.bat',
      'settings.gradle.kts',
      'build.gradle.kts',
      'gradle.properties',
      'scripts/mobile/gradle.mjs',
      'scripts/mobile/check-core.mjs',
      'scripts/mobile/verify-ios-core.swift',
      'scripts/mobile/verify-ios-attachments.swift',
      'scripts/mobile/verify-ios-message-links.swift',
      'apps/ios/Sources/MessageLinkDestination.swift',
      'scripts/mobile/verify-ios-state.swift',
      'scripts/mobile/verify-ios-recovery.swift',
      'scripts/mobile/verify-ios-generation-preferences.swift',
      'scripts/mobile/verify-ios-private-connection.swift',
      'scripts/mobile/verify-ios-sharing-voice.swift',
      'scripts/mobile/verify-ios-task-hub.swift',
      'apps/ios/Sources/TaskHubClient.swift',
      'apps/ios/Sources/Localization.swift',
      'apps/ios/Sources/NativeChatSearch.swift',
      'packages/protocol/schemas/task-hub-mobile-v1.schema.json',
      'apps/ios/Sources/CodexDiscovery.swift',
      'apps/ios/Sources/EasyWorkTransport.swift',
      'packages/protocol/src/codex-private-connection.ts',
      'packages/protocol/schemas/codex-host-discovery-v1.schema.json',
      'scripts/mobile/generate-reading-policy.mjs',
      'packages/protocol/schemas/agent-management-v1.schema.json',
      'packages/protocol/schemas/codex-mobile-http-v1.schema.json',
    ],
    { encoding: 'utf8' },
  )
    .trim()
    .split('\n')
    .filter(Boolean)
    .sort();
  const hash = createHash('sha256');
  for (const path of new Set(paths))
    hash
      .update(path + '\0')
      .update(await readFile(path))
      .update('\0');
  return hash.digest('hex');
};
const sourceSha256 = await fingerprint();
const child = spawn(
  process.execPath,
  [
    'scripts/mobile/gradle.mjs',
    ':mobile-core:jvmTest',
    ':mobile-core:iosSimulatorArm64Test',
    ':mobile-core:linkDebugFrameworkIosSimulatorArm64',
    ':mobile-core:linkDebugFrameworkIosArm64',
    ':mobile-core:linkDebugFrameworkIosX64',
    '--rerun-tasks',
  ],
  { stdio: 'inherit' },
);
assert.equal(
  await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', resolve);
  }),
  0,
);
const suites = [];
for (const [runtime, file] of [
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.MessageLinkRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.MessageLinkRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.DesktopSuggestionRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.DesktopSuggestionRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.AttachmentPreparationRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.AttachmentPreparationRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.TaskHubRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.TaskHubRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.AISharingVoiceRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.AISharingVoiceRulesTest.xml',
  ],
]) {
  const xml = await readFile(file, 'utf8');
  const head = xml.match(/<testsuite\s[^>]+>/)?.[0];
  const tests = file.includes('MessageLinkRulesTest')
    ? 2
    : file.includes('DesktopSuggestionRulesTest')
      ? 3
      : file.includes('TaskHubRulesTest') || file.includes('AttachmentPreparationRulesTest')
        ? 4
        : 3;
  assert(head && head.includes('tests="' + tests + '"'));
  for (const key of ['failures', 'errors', 'skipped']) assert(head.includes(key + '="0"'));
  suites.push({
    runtime,
    tests,
    failures: 0,
    skips: 0,
    xmlSha256: createHash('sha256').update(xml).digest('hex'),
  });
}

for (const [runtime, file] of [
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.WorkspaceArchiveRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.WorkspaceArchiveRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.WorkspaceSidebarRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.WorkspaceSidebarRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.V9SessionRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.V9SessionRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.ChatManagementRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.ChatManagementRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.ChatSearchRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.ChatSearchRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.PrivateConnectionRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.PrivateConnectionRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.ThreadForkRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.ThreadForkRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.MobileRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.MobileRulesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.SessionPoliciesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.SessionPoliciesTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.RuntimeIdentityTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.RuntimeIdentityTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.ThreadSyncTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.ThreadSyncTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.ReplyRecoveryTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.ReplyRecoveryTest.xml',
  ],
  [
    'jvm',
    '.build/native/mobile-core/test-results/jvmTest/TEST-com.agenthub.core.GenerationPreferenceRulesTest.xml',
  ],
  [
    'iosSimulatorArm64',
    '.build/native/mobile-core/test-results/iosSimulatorArm64Test/TEST-iosSimulatorArm64Test.com.agenthub.core.GenerationPreferenceRulesTest.xml',
  ],
]) {
  const xml = await readFile(file, 'utf8');
  const head = xml.match(/<testsuite\s[^>]+>/)?.[0];
  assert(head, 'Missing actual test suite');
  const number = (key) => Number(head.match(new RegExp(key + '="(\\d+)"'))?.[1]);
  const expectedTests = file.includes('GenerationPreferenceRulesTest')
    ? 1
    : file.includes('WorkspaceArchiveRulesTest')
      ? 5
      : file.includes('TaskHubRulesTest') ||
          file.includes('ThreadForkRulesTest') ||
          file.includes('PrivateConnectionRulesTest')
        ? 4
        : file.includes('WorkspaceSidebarRulesTest')
          ? 3
          : file.includes('V9SessionRulesTest')
            ? 4
            : file.includes('ChatSearchRulesTest') || file.includes('ChatManagementRulesTest')
              ? 5
              : file.includes('SessionPoliciesTest')
                ? 14
                : file.includes('RuntimeIdentityTest')
                  ? 4
                  : file.includes('ThreadSyncTest')
                    ? 7
                    : file.includes('ReplyRecoveryTest')
                      ? 8
                      : 3;
  assert.equal(number('tests'), expectedTests);
  for (const key of ['failures', 'errors', 'skipped']) assert.equal(number(key), 0);
  suites.push({
    runtime,
    tests: expectedTests,
    failures: 0,
    skips: 0,
    xmlSha256: createHash('sha256').update(xml).digest('hex'),
  });
}
const binary = output + '/verify-ios-core';
const source = output + '/main.swift';
const privateSource = await readFile('apps/ios/Sources/CodexDiscovery.swift', 'utf8');
const privateHelper = privateSource.slice(
  privateSource.indexOf('enum CodexPrivateCredential {'),
  privateSource.indexOf('struct CodexDirectConnection:'),
);
assert(privateHelper.startsWith('enum CodexPrivateCredential {') && privateHelper.endsWith('\n\n'));
const transportSource = await readFile('apps/ios/Sources/EasyWorkTransport.swift', 'utf8');
const taskHubSource = await readFile('apps/ios/Sources/TaskHubClient.swift', 'utf8');
const taskHubFramer = taskHubSource.slice(taskHubSource.indexOf('struct TaskHubSSEEvent:'));
assert(taskHubFramer.startsWith('struct TaskHubSSEEvent:'));
const dataStart = transportSource.indexOf('extension Data {');
assert(dataStart >= 0);
const dataCodec = transportSource.slice(dataStart, transportSource.indexOf('\n}', dataStart) + 2);

await writeFile(
  source,
  (await readFile('scripts/mobile/verify-ios-core.swift', 'utf8')) +
    '\n' +
    (await readFile('scripts/mobile/verify-ios-attachments.swift', 'utf8')) +
    '\n' +
    (await readFile('apps/ios/Sources/MessageLinkDestination.swift', 'utf8')) +
    '\n' +
    (await readFile('scripts/mobile/verify-ios-message-links.swift', 'utf8')) +
    '\n' +
    (await readFile('apps/ios/Sources/NativeChatSearch.swift', 'utf8')) +
    '\n' +
    (await readFile('scripts/mobile/verify-ios-state.swift', 'utf8')) +
    '\n' +
    (await readFile('scripts/mobile/verify-ios-generation-preferences.swift', 'utf8')) +
    '\n' +
    (await readFile('scripts/mobile/verify-ios-recovery.swift', 'utf8')) +
    '\n' +
    privateHelper +
    '\n' +
    dataCodec +
    '\n' +
    (await readFile('scripts/mobile/verify-ios-private-connection.swift', 'utf8')) +
    '\n' +
    (await readFile('scripts/mobile/verify-ios-sharing-voice.swift', 'utf8')) +
    '\n' +
    (await readFile('apps/ios/Sources/Localization.swift', 'utf8')) +
    '\nenum TaskHubError: Error { case message(String) }\n' +
    taskHubFramer +
    '\n' +
    (await readFile('scripts/mobile/verify-ios-task-hub.swift', 'utf8')) +
    '\ntry verifyStateVectors(CommandLine.arguments[2])\nverifyAsyncBoundary()\ntry verifyRecoveryCoreVectors(CommandLine.arguments[3])\ntry verifyPrivateCredential(CommandLine.arguments[4])\ntry verifySharingVoiceVectors(CommandLine.arguments[5])\ntry verifyTaskHubVectors(CommandLine.arguments[6])\ntry verifyGenerationPreferenceVectors(CommandLine.arguments[7])\ntry verifyAttachmentPreparationVectors(CommandLine.arguments[8])\ntry verifyMessageLinkVectors(CommandLine.arguments[9])\n',
);
const sdk = execFileSync('xcrun', ['--sdk', 'iphonesimulator', '--show-sdk-path'], {
  encoding: 'utf8',
}).trim();
execFileSync(
  'xcrun',
  [
    'swiftc',
    '-sdk',
    sdk,
    '-target',
    'arm64-apple-ios17.0-simulator',
    '-F',
    '.build/native/mobile-core/bin/iosSimulatorArm64/debugFramework',
    '-framework',
    'MobileCore',
    source,
    '-o',
    binary,
  ],
  { stdio: 'inherit' },
);
execFileSync('codesign', ['--force', '--sign', '-', binary], { stdio: 'ignore' });
const boundary = execFileSync(
  'xcrun',
  [
    'simctl',
    'spawn',
    simulator,
    resolve(binary),
    resolve('packages/protocol/fixtures/mobile/core-v1.json'),
    resolve('packages/protocol/fixtures/mobile/state-v1.json'),
    resolve('packages/protocol/fixtures/mobile/reply-recovery-v2.json'),
    resolve('packages/protocol/fixtures/mobile/private-connection-v1.json'),
    resolve('packages/protocol/fixtures/mobile/ai-sharing-voice-v1.json'),
    resolve('packages/protocol/fixtures/mobile/task-hub-v1.json'),
    resolve('packages/protocol/fixtures/mobile/generation-preferences-v1.json'),
    resolve('packages/protocol/fixtures/mobile/attachment-preparation-v1.json'),
    resolve('packages/protocol/fixtures/mobile/message-links-v1.json'),
  ],
  { encoding: 'utf8' },
);
const vectors = async (name) =>
  JSON.parse(await readFile('packages/protocol/fixtures/mobile/' + name + '.json', 'utf8'));
const generationVectors = await vectors('generation-preferences-v1');
assert(
  boundary.includes('Message link vectors: ' + (await vectors('message-links-v1')).cases.length),
);
assert(
  boundary.includes(
    `Attachment preparation vectors: ${(await vectors('attachment-preparation-v1')).cases.length}`,
  ),
);
assert(
  boundary.includes(
    `Swift/Kotlin generation preferences passed: ${generationVectors.cases.length} cases`,
  ),
);
const coreVectors = await vectors('core-v1');
const stateVectors = await vectors('state-v1');
assert(
  boundary.includes(
    `Swift/Kotlin queue ownership passed: ${stateVectors.queueOwnership.length} cases`,
  ),
);
assert(
  boundary.includes(`Swift/Kotlin receipt notices passed: ${stateVectors.receipts.length} cases`),
);
assert(
  boundary.includes(`Swift/Kotlin queue actions passed: ${stateVectors.queueActions.length} cases`),
);
const sharingVectors = await vectors('ai-sharing-voice-v1');
const recoveryVectors = await vectors('reply-recovery-v2');
assert(
  boundary.includes(
    `Swift/Kotlin original query backoff passed: ${recoveryVectors.queryBackoff.length} cases`,
  ),
);
assert(
  boundary.includes(
    `Swift/Kotlin original receipt wait passed: ${recoveryVectors.waitReceipts.length} cases`,
  ),
);
assert(
  boundary.includes(
    `Swift/Kotlin receipt evidence merge passed: ${recoveryVectors.receiptMerges.length} cases`,
  ),
);
const taskHubVectors = await vectors('task-hub-v1');
assert(
  boundary.includes(
    `${coreVectors.identities.length} identity, ${coreVectors.composer.length} composer, ${coreVectors.content.length} content vectors`,
  ),
);
assert(
  boundary.includes(
    `${stateVectors.policy.length} policies, ${stateVectors.timeline.length} projections, ${stateVectors.contracts.length} contracts`,
  ),
);
assert(boundary.includes('Swift/Kotlin async boundary passed'));
assert(
  boundary.includes(
    `Swift/Kotlin archive eligibility passed: ${stateVectors.workspaceArchiveEligibility.length} cases`,
  ),
);
assert(
  boundary.includes(
    `Swift/Kotlin running counts passed: ${stateVectors.runningCounts.length} cases`,
  ),
);
assert(
  boundary.includes(
    `Swift/Kotlin chat search passed: ${stateVectors.chatSearch.length} native Unicode/snippet cases`,
  ),
);
assert(
  boundary.includes(
    `Swift/Kotlin sidebar ordering passed: ${stateVectors.sidebar.length} projections, ${stateVectors.sidebarVisibility.length} visibility cases`,
  ),
);
assert(
  boundary.includes(
    `Swift/Kotlin sharing/voice vectors: ${sharingVectors.consent.length} consent, ${sharingVectors.voice.length} receipts, ${sharingVectors.manifests.length} manifests`,
  ),
);
assert(
  boundary.includes(
    `Swift/Kotlin reply recovery passed: ${recoveryVectors.fingerprints.length} Host fingerprints and ${recoveryVectors.proofs.length} strict proof boundaries`,
  ),
);
assert(
  boundary.includes(
    'Swift/Kotlin Codex HTTP boundary passed: ' + stateVectors.http.length + ' cases',
  ),
);
assert(
  boundary.includes(
    'Swift/Kotlin private credential passed: actual iOS helper + cross-platform vector and 6 rejection cases',
  ),
);
assert(
  boundary.includes(
    'Swift/Kotlin tool presentation passed: ' + stateVectors.toolPresentation.length + ' cases',
  ),
);
assert(
  boundary.includes(
    'Swift/Kotlin Codex discovery boundary passed: ' + stateVectors.discovery.length + ' cases',
  ),
);
assert(
  boundary.includes(
    `Swift/Kotlin Task Hub passed: ${taskHubVectors.cursors.length} cursors, ${taskHubVectors.phases.length} phases; actual iOS byte framer and session fences`,
  ),
);
const report = {
  phase,
  status: 'passed',
  sourceSha256,
  suites,
  simulator,
  boundary: boundary.trim(),
  vectorsSha256: createHash('sha256')
    .update(await readFile('packages/protocol/fixtures/mobile/core-v1.json'))
    .digest('hex'),
  stateVectorsSha256: createHash('sha256')
    .update(await readFile('packages/protocol/fixtures/mobile/state-v1.json'))
    .digest('hex'),
  privateConnectionVectorsSha256: createHash('sha256')
    .update(await readFile('packages/protocol/fixtures/mobile/private-connection-v1.json'))
    .digest('hex'),
  generationPreferenceVectorsSha256: createHash('sha256')
    .update(await readFile('packages/protocol/fixtures/mobile/generation-preferences-v1.json'))
    .digest('hex'),
  frameworks: ['iosArm64', 'iosSimulatorArm64', 'iosX64'],
  physicalDeviceVerified: false,
};
assert.equal(await fingerprint(), sourceSha256, 'Shared inputs changed during verification');
await writeFile(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
console.log(boundary.trim());
console.log('Core evidence:', output + '/report.json');
