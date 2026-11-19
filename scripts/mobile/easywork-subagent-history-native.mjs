import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmod, mkdir, mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';

const paths = [
  'apps/ios/Sources/EasyWorkAgentClient.swift',
  'apps/ios/Sources/AgentClient.swift',
  'apps/ios/Sources/EasyWorkReading.swift',
  'apps/ios/Sources/SessionStore.swift',
  'scripts/mobile/easywork-subagent-history-native.swift',
];
const [source, agent, reading, store, fixture] = await Promise.all(
  paths.map((p) => readFile(p, 'utf8')),
);
function between(s, a, b) {
  const i = s.indexOf(a),
    j = s.indexOf(b, i + a.length);
  assert(i >= 0 && j > i, `Missing production boundary ${a}`);
  return s.slice(i, j);
}
const attachment = between(
  source,
  'enum EasyWorkHistoricalAttachment {',
  '\n/// Read-only child runs',
);
const helper = between(
  source,
  'enum EasyWorkSubagentHistory {',
  '\nstruct EasyWorkAgentClient: AgentClient {',
);
const projection = between(
  source,
  '  private func projectMessages(',
  '\n  func supportsSubagentHistory(',
);
const provider = between(source, '  func supportsSubagentHistory(', '\n  func history(');
const defaults = between(
  agent,
  'extension AgentClient {\n  func supportsSubagentHistory(',
  '\n  func connectionMetadata(',
).replace('extension AgentClient {', '');
const integer = between(reading, '  static func integer(', '\n  static func position(');
assert(
  agent.includes(
    '  func supportsSubagentHistory(_ client: Client, workspace: WorkspaceSnapshot?) -> Bool',
  ),
);
assert(
  agent.includes(
    '  func subagentHistory(_ client: Client, parentThreadId: String, messageId: String, childId: String?, cursor: String?) async throws -> SubagentHistorySnapshot',
  ),
);
assert(
  store.includes(
    'provider.supportsSubagentHistory(client, workspace: saved.workspaces[client.id])',
  ),
);
assert(source.includes('projectMessages(value["messages"] as? [[String: Any]] ?? []'));
assert(projection.includes('EasyWorkHistoricalAttachment.readOrResolve'));
console.log('Production source contract checks passed');
if (process.platform !== 'darwin') {
  console.log('SKIP: native Foundation compilation requires macOS');
  process.exit(0);
}
const root = resolve(
  process.env.EASYWORK_SUBAGENT_HISTORY_CHECK_OUTPUT ??
    '.local/subagent-easywork-restore-20261119/boundary',
);
await mkdir(root, { recursive: true, mode: 0o700 });
await chmod(root, 0o700);
const dir = await mkdtemp(join(root, 'run-'));
await chmod(dir, 0o700);
const combined = join(dir, 'boundary.swift'),
  executable = join(dir, 'boundary');
const program = fixture.replace(
  '// PRODUCTION_BOUNDARIES',
  `enum EasyWorkReading {\n${integer}\n}\n${attachment}\n${helper}\nextension AgentClient {\n${defaults}\n}\nstruct EasyWorkAgentClient: AgentClient {\n${projection}\n${provider}\n  func testProject(_ originals: [[String: Any]], client: Client, channel: EasyWorkRemoteSession) async throws -> [ChatMessage] { try await projectMessages(originals, client: client, threadId: "parent", channel: channel) }\n}`,
);
assert.notEqual(program, fixture);
try {
  await writeFile(combined, program, { mode: 0o600 });
  execFileSync('xcrun', ['swiftc', '-parse', combined], { stdio: 'inherit' });
  execFileSync('xcrun', ['swiftc', '-parse-as-library', combined, '-o', executable], {
    stdio: 'inherit',
  });
  const result = execFileSync(executable, { encoding: 'utf8' });
  await writeFile(join(dir, 'native.log'), result, { mode: 0o600 });
  await writeFile(
    join(dir, 'summary.json'),
    JSON.stringify(
      {
        status: 'passed-auxiliary-foundation',
        sources: Object.fromEntries(
          paths.map((p, i) => [
            p,
            createHash('sha256').update([source, agent, reading, store, fixture][i]).digest('hex'),
          ]),
        ),
        transport: 'stub',
        nativeGui: false,
        result: result.trim(),
      },
      null,
      2,
    ) + '\n',
    { mode: 0o600 },
  );
  console.log(result.trim(), '\nEvidence:', join(dir, 'summary.json'));
} finally {
  await rm(combined, { force: true });
  await rm(executable, { force: true });
}
