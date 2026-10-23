import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const simulator = process.env.IOS_SIMULATOR_ID;
assert(simulator);
const output = '.local/mobile-acceptance/A2/state-' + Date.now();
await mkdir(output, { recursive: true });
const paths = [
  'apps/ios/Sources/Models.swift',
  'apps/ios/Sources/AgentClient.swift',
  'apps/ios/Sources/NotificationModels.swift',
  'apps/ios/Sources/ThreadReading.swift',
  'apps/ios/Sources/CodexDiscovery.swift',
  'apps/ios/Sources/SharedMobileRules.swift',
  'apps/ios/Sources/SharedReadingPolicy.swift',
  'scripts/mobile/verify-ios-migration.swift',
  'scripts/mobile/check-ios-state.mjs',
  'packages/protocol/fixtures/mobile/state-v1.json',
  'apps/ios/Sources/HostService.swift',
  'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/RuntimeIdentity.kt',
  'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ReplyRecovery.kt',
  'packages/protocol/fixtures/mobile/reply-recovery-v2.json',
];
const fingerprint = async () => {
  const hash = createHash('sha256');
  for (const path of paths)
    hash
      .update(path + '\0')
      .update(await readFile(path))
      .update('\0');
  return hash.digest('hex');
};
const sourceSha256 = await fingerprint();
const [models, agent, notifications, reading, discovery, shared, generated, verifier] =
  await Promise.all(paths.slice(0, 8).map((p) => readFile(p, 'utf8')));
const reference = execFileSync('git', ['show', '595deca:apps/ios/Sources/Models.swift'], {
  encoding: 'utf8',
});
const legacy = reference
  .slice(reference.indexOf('struct ChatTimeline'), reference.indexOf('\nstruct Draft:'))
  .replace('struct ChatTimeline', 'struct LegacyChatTimeline');
const facade =
  shared.slice(0, shared.indexOf('// Platform tasks')) +
  '\n' +
  shared.slice(
    shared.indexOf('extension SharedMobileRules {\n  static func validateDomain'),
    shared.indexOf('extension SharedMobileRules {\n  // Runs once'),
  ) +
  '\n' +
  shared.slice(shared.indexOf('extension SharedMobileRules {\n  // Runs once'));
const code =
  (await readFile('apps/ios/Sources/HostService.swift', 'utf8')).split('// Tokens never enter')[0] +
  '\n' +
  notifications.slice(0, notifications.indexOf('enum TaskNoticeKind:')) +
  '\n' +
  agent.slice(0, agent.indexOf('/// Product operations.')) +
  '\n' +
  agent.slice(agent.indexOf('extension AgentConnection {')) +
  '\n' +
  discovery.slice(
    discovery.indexOf('struct CodexDirectConnection'),
    discovery.indexOf('struct CodexHostDescriptor'),
  ) +
  '\n' +
  reading +
  '\n' +
  generated +
  '\n' +
  models +
  '\n' +
  facade +
  '\n' +
  legacy +
  '\n' +
  verifier;
const source = output + '/main.swift',
  binary = output + '/verify-state';
await writeFile(source, code);
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
const result = execFileSync(
  'xcrun',
  [
    'simctl',
    'spawn',
    simulator,
    resolve(binary),
    resolve('packages/protocol/fixtures/mobile/state-v1.json'),
    resolve('packages/protocol/fixtures/mobile/reply-recovery-v2.json'),
  ],
  { encoding: 'utf8' },
);
assert(result.includes('2000 projection comparisons; 9 migration checks; 5 UTF-8/identity checks'));
assert(
  result.includes(
    'Swift reply wire and recovery policy passed: 4 Host fingerprints and 26 proof boundaries',
  ),
);
assert(
  result.includes(
    'Native recovery consent persistence and nested creation codec passed: 9 boundaries',
  ),
);
assert.equal(await fingerprint(), sourceSha256);
await writeFile(
  output + '/report.json',
  JSON.stringify(
    {
      phase: 'A2',
      status: 'passed',
      sourceSha256,
      simulator,
      referenceCommit: '595deca',
      referenceSha256: createHash('sha256').update(legacy).digest('hex'),
      generatedSourceSha256: createHash('sha256').update(code).digest('hex'),
      result: result.trim(),
      evidenceKind: 'native-simulator-boundary; UI regression recorded separately',
    },
    null,
    2,
  ) + '\n',
);
console.log(result.trim());
console.log('iOS state evidence:', output + '/report.json');
