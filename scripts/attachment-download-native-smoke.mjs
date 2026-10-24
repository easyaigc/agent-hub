import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

// Production Swift download functions against synthetic HTTP; no desktop/model evidence.
if (process.platform !== 'darwin') {
  console.log('Attachment native regression requires macOS Swift.');
  process.exit(0);
}
const execute = promisify(execFile);
await mkdir('.local/attachment-download-native', { recursive: true, mode: 0o700 });
const directory = await mkdtemp(resolve('.local/attachment-download-native/run-'));
const source = await readFile('apps/ios/Sources/HostService.swift', 'utf8');
const models = await readFile('apps/ios/Sources/Models.swift', 'utf8');
const methods = source.slice(
  source.indexOf('  static func baseURL('),
  source.indexOf('  static func nextTurnSuggestion('),
);
const downloads = source.slice(source.indexOf('  static func download('), source.lastIndexOf('}'));
const errors = source.slice(
  source.indexOf('enum HostServiceError:'),
  source.indexOf('// Tokens never'),
);
const attachment = models.slice(
  models.indexOf('struct RemoteAttachment:'),
  models.indexOf('struct PlanStep:'),
);
const nonce = randomUUID();
const target = { agentId: 'codex', hostId: nonce, workspaceId: 'workspace', runtimeId: 'runtime' };
const hash = (data) => createHash('sha256').update(data).digest('hex');
const counts = new Map();
const version = new Map();
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const send = (code, value) =>
    res.writeHead(code, { 'Content-Type': 'application/json' }).end(JSON.stringify(value));
  assert.equal(req.headers.authorization, 'Bearer synthetic-token');
  assert.equal(url.searchParams.get('hostId'), nonce);
  const id = url.searchParams.get('id');
  const scenario = id?.split('_')[0];
  const key = scenario + url.pathname;
  counts.set(key, (counts.get(key) ?? 0) + 1);
  if (url.pathname === '/v1/attachments/resolve') {
    if (scenario === 'legacy') return send(404, { error: 'old-host' });
    if (scenario === 'removed' && version.has(scenario))
      return send(410, { error: 'file-removed' });
    const n = (version.get(scenario) ?? 0) + 1;
    version.set(scenario, n);
    const content = `${scenario}-version-${n}`;
    return send(200, {
      hostId: scenario === 'wrongHost' ? 'other-host' : nonce,
      threadId: 'thread',
      sourceAttachmentId: scenario === 'wrongSource' ? 'other-id' : id,
      readTarget: scenario === 'wrongRuntime' ? { ...target, runtimeId: 'other-runtime' } : target,
      attachment: {
        id: `${scenario}_${nonce}_${n}`,
        hostId: nonce,
        threadId: scenario === 'wrongThread' ? 'other-thread' : 'thread',
        name: 'report.txt',
        mediaType: 'text/plain',
        sizeBytes: Buffer.byteLength(content),
        sha256: hash(content),
      },
    });
  }
  assert.equal(url.pathname, '/v1/attachments');
  if (scenario === 'changing' || (scenario === 'race' && counts.get(key) === 1))
    return send(409, { error: 'changed-during-download' });
  const content =
    scenario === 'legacy' ? 'original' : `${scenario}-version-${id.split('_').at(-1)}`;
  res.writeHead(200).end(scenario === 'corrupt' ? 'invalid-bytes' : content);
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
try {
  const swift = `import Foundation
import CryptoKit
enum L10n { static func tr(_ text: String) -> String { text } }
${errors}
struct AgentRuntimeTarget: Codable, Equatable { let agentId: String; let hostId: String; let workspaceId: String; let runtimeId: String }
struct Client { let id: String; let endpoint: String?; let readingTarget: AgentRuntimeTarget? }
// The attachment resolution DTO does not use these unrelated MobileCore contracts.
struct WorkspaceSnapshot {}; struct HistorySnapshot {}; struct EventBatch {}; struct ReplyDeliveryInspection {}
struct ThreadForkOptions {}; struct ThreadForkOperation {}; struct VoiceTranscriptionReadiness {}; struct VoiceTranscriptionReceipt {}
struct CodexHttpContract {
  static let shared = CodexHttpContract()
  func validate(json: String, definition: String) -> String? { fatalError("Unexpected non-attachment contract in download fixture") }
}
${attachment}
enum CredentialVault { static func token(_ id: String) -> String { "synthetic-token" } }
final class CodexHostRoutes {
  static let shared = CodexHostRoutes()
  func data(for request: URLRequest) async throws -> (Data, URLResponse) { try await URLSession.shared.data(for: request) }
}
struct HostService {
${methods}
  static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
${downloads}
}
@main struct Check {
  static func main() async throws {
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "${nonce}", workspaceId: "workspace", runtimeId: "runtime")
    let client = Client(id: "${nonce}", endpoint: "http://127.0.0.1:${server.address().port}", readingTarget: target)
    let scenarios = ["cached", "removed", "race", "changing", "legacy", "wrongHost", "wrongThread", "wrongSource", "wrongRuntime", "corrupt"]
    defer {
      let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads")
      for scenario in scenarios { for suffix in ["original", "1", "2"] {
        try? FileManager.default.removeItem(at: caches.appendingPathComponent("\\(scenario)_${nonce}_\\(suffix)"))
      } }
    }
    for scenario in scenarios {
      let file = RemoteAttachment(id: "\\(scenario)_${nonce}_original", hostId: client.id, threadId: "thread", name: "report.txt", mediaType: "text/plain", sizeBytes: 8, sha256: HostService.hash(Data("original".utf8)))
      do {
        let url = try await HostService.download(client, file: file)
        let body = try String(contentsOf: url, encoding: .utf8)
        switch scenario {
        case "cached":
          precondition(body == "cached-version-1")
          let refreshed = try await HostService.download(client, file: file)
          let refreshedBody = try String(contentsOf: refreshed, encoding: .utf8)
          precondition(refreshedBody == "cached-version-2")
        case "race": precondition(body == "race-version-2")
        case "removed":
          precondition(body == "removed-version-1")
          do { _ = try await HostService.download(client, file: file); fatalError("Removed file reused cached bytes") }
          catch HostServiceError.rejected(410, _) {}
        case "legacy": precondition(body == "original")
        default: fatalError("Unexpected success: \\(scenario)")
        }
      } catch HostServiceError.rejected(409, _) where scenario == "changing" {
      } catch HostServiceError.wrongHost where scenario.hasPrefix("wrong") {
      } catch HostServiceError.message(_) where scenario == "corrupt" {
      }
    }
    print("current bytes before cache; removed file rejects old cache; bounded race retry; old Host fallback; host/thread/source/runtime rejection; corrupt bytes rejected")
  }
}`;
  const file = join(directory, 'Check.swift'),
    binary = join(directory, 'check');
  await writeFile(file, swift, { mode: 0o600 });
  await execute('xcrun', ['swiftc', '-parse-as-library', file, '-o', binary], { timeout: 120000 });
  const result = await execute(binary, [], { timeout: 30000 });
  assert.equal(counts.get('cached/v1/attachments/resolve'), 2);
  assert.equal(counts.get('cached/v1/attachments'), 2);
  assert.equal(counts.get('removed/v1/attachments/resolve'), 2);
  assert.equal(counts.get('removed/v1/attachments'), 1);
  assert.equal(counts.get('race/v1/attachments/resolve'), 2);
  assert.equal(counts.get('changing/v1/attachments/resolve'), 2);
  assert.equal(counts.get('changing/v1/attachments'), 2);
  for (const scenario of ['wrongHost', 'wrongThread', 'wrongSource', 'wrongRuntime'])
    assert.equal(counts.get(scenario + '/v1/attachments'), undefined);
  const report = {
    passed: true,
    productionSourceSha256: hash(source),
    native: result.stdout.trim(),
    counts: Object.fromEntries(counts),
  };
  await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(report));
} finally {
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
