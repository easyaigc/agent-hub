import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Compile production protocol requirements, defaults, adapter methods, request builders and target encoder.
// The transport and credential store are deliberately narrow, local test doubles.
const paths = [
  'apps/ios/Sources/AgentClient.swift',
  'apps/ios/Sources/HostService.swift',
  'apps/ios/Sources/ProjectGit.swift',
];
const [agent, host, git] = await Promise.all(paths.map((path) => readFile(path, 'utf8')));
function extract(source, start, end, from = 0) {
  const first = source.indexOf(start, from);
  assert(first >= 0, `Missing ${start}`);
  const last = source.indexOf(end, first);
  assert(last > first, `Missing boundary ${end}`);
  return source.slice(first, last);
}
const protocol = extract(agent, 'protocol AgentClient: Sendable {', '  func usage(') + '}\n';
const defaults = extract(
  agent,
  '  func projectGit(',
  '  func usage(',
  agent.indexOf('extension AgentClient'),
);
const adapterStart = agent.indexOf('struct CodexAgentClient: AgentClient {');
assert(adapterStart >= 0, 'Missing real CodexAgentClient conformance');
const adapter = extract(agent, '  func projectGit(', '  func models(', adapterStart);
const targetJSON = extract(git, 'func projectGitTargetJSON(', 'struct ProjectGitMetric');
const hostMethods = extract(host, '  static func baseURL(', '  static func bytes(');
for (const [name, source] of [
  ['protocol', protocol],
  ['defaults', defaults],
  ['adapter', adapter],
]) {
  assert.match(source, /func projectGit\(/, `${name} lacks projectGit`);
  assert.match(source, /func projectGitFiles\(/, `${name} lacks projectGitFiles`);
}
if (process.platform !== 'darwin') {
  console.log(
    `SKIP Swift native compilation: macOS required (current: ${process.platform}); source extraction and structure checks passed.`,
  );
  process.exit(0);
}
const swift = `
import Foundation
struct Client: Sendable { let id: String; let endpoint: String? }
struct AgentRuntimeTarget: Sendable { let agentId: String; let hostId: String; let workspaceId: String; let runtimeId: String }
enum HostServiceError: Error { case unavailable, invalidAddress }
enum CredentialVault { static func token(_ id: String) -> String { precondition(id == "host & one"); return "test-token" } }
final class CodexHostRoutes { static let shared = CodexHostRoutes(); var registrations = 0; func register(_ client: Client) { registrations += 1 } }
struct HostService {
${hostMethods}
  static var captured: [(URLRequest, Int?)] = []
  static func bytes(_ request: URLRequest, maxBytes: Int? = nil) async throws -> Data {
    captured.append((request, maxBytes)); return Data("ok".utf8)
  }
}
${targetJSON}
${protocol}
extension AgentClient {
  var requiresConfirmedDesktopOpenReceipt: Bool { false }
${defaults}
}
struct CodexAgentClient: AgentClient {
${adapter}
}
func verify(_ index: Int, _ path: String, _ expected: [String: String]) throws {
  let (request, maxBytes) = HostService.captured[index]
  precondition(request.url!.path == path)
  let items = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
  precondition(Set(items.map(\\.name)).count == items.count)
  let query = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
  precondition(query == expected, "unexpected query: \\(query)")
  precondition(request.httpMethod == "GET" && request.httpBody == nil)
  precondition(request.value(forHTTPHeaderField: "Authorization") == "Bearer test-token")
  precondition(request.value(forHTTPHeaderField: "X-Agent-Hub-Approvals") == "mcp-v1")
  precondition(request.timeoutInterval == 15 && maxBytes == 131072)
}
@main struct Main {
  static func main() async throws {
    let client = Client(id: "host & one", endpoint: "https://example.invalid")
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "host & one", workspaceId: "work / space", runtimeId: "runtime+1")
    let encoded = try projectGitTargetJSON(target)
    let decoded = try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as! [String: String]
    precondition(decoded == ["agentId": "codex", "hostId": "host & one", "workspaceId": "work / space", "runtimeId": "runtime+1"])
    let provider: any AgentClient = CodexAgentClient()
    let common = ["hostId": client.id, "projectId": "project / &", "target": encoded]
    let one = try await provider.projectGit(client, projectId: "project / &", target: target, refresh: false)
    let two = try await provider.projectGit(client, projectId: "project / &", target: target, refresh: true)
    let three = try await provider.projectGitFiles(client, projectId: "project / &", target: target, snapshotId: "snap+1", worktreeId: "tree / 1", cursor: nil)
    let four = try await provider.projectGitFiles(client, projectId: "project / &", target: target, snapshotId: "snap+1", worktreeId: "tree / 1", cursor: "cursor & +")
    precondition([one, two, three, four].allSatisfy { $0 == Data("ok".utf8) })
    precondition(HostService.captured.count == 4 && CodexHostRoutes.shared.registrations == 4)
    try verify(0, "/v1/project-git", common)
    try verify(1, "/v1/project-git", common.merging(["refresh": "1"]) { _, b in b })
    let files = common.merging(["snapshotId": "snap+1", "worktreeId": "tree / 1"]) { _, b in b }
    try verify(2, "/v1/project-git/files", files)
    try verify(3, "/v1/project-git/files", files.merging(["cursor": "cursor & +"]) { _, b in b })
    print("Codex project Git native boundary: 4 requests passed")
  }
}
`;
const dir = await mkdtemp(join(tmpdir(), 'codex-project-git-smoke-'));
try {
  const source = join(dir, 'boundary.swift');
  const executable = join(dir, 'boundary');
  await writeFile(source, swift, { mode: 0o600 });
  execFileSync('xcrun', ['swiftc', '-parse-as-library', source, '-o', executable], {
    stdio: 'inherit',
  });
  execFileSync(executable, { stdio: 'inherit' });
  for (let index = 0; index < paths.length; index++) {
    console.log(
      `${createHash('sha256').update([agent, host, git][index]).digest('hex')}  ${paths[index]}`,
    );
  }
} finally {
  await rm(dir, { recursive: true, force: true });
}
