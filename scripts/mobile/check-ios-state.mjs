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
  'apps/ios/Sources/Localization.swift',
  'apps/ios/Sources/SessionStore.swift',
  'apps/ios/Sources/MobileUnreadBadge.swift',
  'scripts/mobile/fixtures/legacy-chat-timeline.swift',
  'apps/ios/Sources/NativeChatSearch.swift',
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
const store = await readFile('apps/ios/Sources/SessionStore.swift', 'utf8');
const reconcileStart = store.indexOf('  private func reconcileReading(');
const reconcileEnd = store.indexOf('  func recordVisibleRead(', reconcileStart);
assert(
  reconcileStart >= 0 && reconcileEnd > reconcileStart,
  'Missing production reading coordinator',
);
const reconciliation = store.slice(reconcileStart, reconcileEnd);
assert(verifier.includes('// PRODUCTION_RECONCILE_READING'));
const selectionGetters = [
  ['  var client: Client? {', '  struct VoiceReadinessScope:'],
  ['  var conversation: Conversation? {', '  private func readingKey('],
  ['  var project: Project? {', '  var targetKey: String {'],
]
  .map(([start, end]) => {
    const offset = store.indexOf(start),
      limit = store.indexOf(end, offset);
    assert(offset >= 0 && limit > offset, 'Missing production selection getter');
    return store.slice(offset, limit);
  })
  .join('\n');
assert(verifier.includes('// PRODUCTION_SELECTION_GETTERS'));
const contextStart = store.indexOf('  private struct ReadingKeyContext {');
const contextEnd = store.indexOf('  private func readingClient(', contextStart);
const projectionStart = store.indexOf('  private func mobileUnreadSnapshots()');
const projectionEnd = store.indexOf('  func synchronizeMobileUnread()', projectionStart);
assert(contextStart >= 0 && contextEnd > contextStart && projectionEnd > projectionStart);
const unreadSource = await readFile('apps/ios/Sources/MobileUnreadBadge.swift', 'utf8');
const snapshotType = unreadSource.slice(
  unreadSource.indexOf('struct MobileUnreadSnapshot:'),
  unreadSource.indexOf('struct MobileVisibleRead:'),
);
const unreadProjection =
  store.slice(contextStart, contextEnd) + store.slice(projectionStart, projectionEnd);
const reference = await readFile('scripts/mobile/fixtures/legacy-chat-timeline.swift', 'utf8');
const legacy = reference.replace('struct ChatTimeline', 'struct LegacyChatTimeline');
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
  (await readFile('apps/ios/Sources/Localization.swift', 'utf8')) +
  '\n' +
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
  (await readFile('apps/ios/Sources/NativeChatSearch.swift', 'utf8')) +
  '\n' +
  facade +
  '\n' +
  legacy +
  '\n' +
  verifier
    .replace('// PRODUCTION_RECONCILE_READING', reconciliation)
    .replace('// PRODUCTION_SELECTION_GETTERS', selectionGetters)
    .replace('// PRODUCTION_UNREAD_SNAPSHOT_TYPE', snapshotType)
    .replace('// PRODUCTION_UNREAD_PROJECTION', unreadProjection);
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
assert(
  result.includes(
    'History projection cache passed: 500 scroll reuses and 17 invalidation boundaries',
  ),
);
assert(
  result.includes(
    'Preferred identity key cache passed: 2000 warm reuses; alias, candidate, runtime, Unicode and concurrent eviction boundaries',
  ),
);
assert(
  result.includes(
    'Runtime identity key cache passed: 6000 warm reuses; full-target isolation and bounded concurrent eviction',
  ),
);
assert(
  result.includes(
    'Reading key selection passed: 6000 canonical fast paths; live legacy precedence and target isolation',
  ),
);
assert(
  result.includes(
    'Reading acknowledgement merge passed: 7 duplicate, pending, missing, advancing, stale, conflicting and future boundaries',
  ),
);
assert(
  result.includes(
    'Production reading coordinator passed: 818-row full refresh, one-row snapshot, other pending retry and host isolation',
  ),
);
assert(
  result.includes(
    'Production selection lookup passed: one observable read per lookup, project fallback and immediate metadata changes',
  ),
);
assert(
  result.includes(
    'Production unread projection passed: one observable snapshot for 818 rows; live reads, legacy reply and target isolation',
  ),
);
assert(
  result.includes(
    'Unread predicate passed: 10 record, baseline, legacy and content-change boundaries',
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
