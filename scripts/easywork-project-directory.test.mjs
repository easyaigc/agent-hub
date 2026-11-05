import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { test } from 'node:test';

const run = promisify(execFile);
const root = fileURLToPath(new URL('../', import.meta.url));

// Exercise the actual adapter method against a directory peer. A broken peer
// must not leave project creation in an unbounded read loop or submit an empty ref.
test(
  'EasyWork iOS project traversal rejects cycles and missing references before writing',
  {
    skip: process.platform !== 'darwin',
  },
  async (t) => {
    const source = await readFile(join(root, 'apps/ios/Sources/EasyWorkAgentClient.swift'), 'utf8');
    const start = source.indexOf('  func createProject(');
    const end = source.indexOf('\n  func connectionTitle(', start);
    assert(start >= 0 && end > start);
    await mkdir(join(root, '.local'), { recursive: true, mode: 0o700 });
    const directory = await mkdtemp(join(root, '.local/easywork-directory-'));
    t.after(() => rm(directory, { recursive: true, force: true }));
    const swift = join(directory, 'DirectoryPeer.swift');
    await writeFile(
      swift,
      `import Foundation
enum L10n { static func tr(_ value: String) -> String { value } }
enum HostServiceError: Error { case message(String), invalidResponse }
struct Client {}
struct WorkspaceSnapshot {}
final class DirectoryPeer {
  let mode: String
  var reads = 0
  var writes: [[String: Any]] = []
  init(_ mode: String) { self.mode = mode }
  func request(_ method: String, _ params: [String: Any], id: String? = nil) async throws -> Data {
    let value: [String: Any]
    switch method {
    case "filesystem/access-status": value = ["access": ["status": "granted"]]
    case "filesystem/roots": value = ["roots": [mode == "missing" ? ["displayPath": "/authorized"] : ["displayPath": "/authorized", "directoryRef": "root-ref"]]]
    case "runtime/context": value = ["workspaceDirectory": "/authorized"]
    case "filesystem/list-directories":
      reads += 1
      if reads > 3 { throw HostServiceError.message("peer read limit") }
      if mode == "cycle" { value = ["directories": [["displayPath": "/authorized", "directoryRef": "root-ref"]]] }
      else if reads == 1 { value = ["directories": [["displayPath": "/authorized/child", "directoryRef": "child-ref"]]] }
      else { value = ["directories": [["displayPath": "/authorized/child/nested", "directoryRef": "nested-ref"]]] }
    case "project/create":
      var write = params; write["capturedOperationId"] = id; writes.append(write); value = [:]
    default: throw HostServiceError.invalidResponse
    }
    return try JSONSerialization.data(withJSONObject: value)
  }
}
final class ActualAdapterMethod {
  let peer: DirectoryPeer
  init(_ peer: DirectoryPeer) { self.peer = peer }
  func session(_ client: Client) async throws -> DirectoryPeer { peer }
  func object(_ value: Data) throws -> [String: Any] { try JSONSerialization.jsonObject(with: value) as! [String: Any] }
  func workspace(_ client: Client, projectCursor: String?, threadCursor: String?) async throws -> WorkspaceSnapshot { WorkspaceSnapshot() }
${source.slice(start, end)}
}
@main struct Examiner {
  static func main() async throws {
    var results: [[String: Any]] = []
    for mode in ["valid", "cycle", "missing", "escape"] {
      let peer = DirectoryPeer(mode), adapter = ActualAdapterMethod(peer)
      var rejected = false
      let path = mode == "missing" ? "/authorized" : mode == "escape" ? "../outside" : "/authorized/child/nested"
      do { _ = try await adapter.createProject(Client(), name: "fixture", directory: path, operationId: "stable-operation") } catch { rejected = true }
      results.append(["mode": mode, "reads": peer.reads, "writes": peer.writes, "rejected": rejected])
    }
    print(String(decoding: try JSONSerialization.data(withJSONObject: results), as: UTF8.self))
  }
}
`,
      { mode: 0o600 },
    );
    const env = Object.fromEntries(
      Object.entries(process.env).filter(([key]) =>
        /^(PATH|HOME|TMPDIR|SDKROOT|DEVELOPER_DIR)$/i.test(key),
      ),
    );
    const binary = join(directory, 'examiner');
    await run('/usr/bin/swiftc', ['-parse-as-library', swift, '-o', binary], {
      env,
      timeout: 30000,
    });
    const values = JSON.parse((await run(binary, [], { env, timeout: 5000 })).stdout);
    const byMode = Object.fromEntries(values.map((row) => [row.mode, row]));
    assert.equal(byMode.valid.rejected, false);
    assert.equal(byMode.valid.reads, 2);
    assert.equal(byMode.valid.writes.length, 1);
    assert.equal(byMode.valid.writes[0].directoryRef, 'nested-ref');
    assert.equal(byMode.valid.writes[0].clientRequestId, 'stable-operation');
    assert.equal(byMode.valid.writes[0].capturedOperationId, 'stable-operation');
    assert.equal(
      byMode.cycle.reads,
      1,
      'A repeated opaque directory reference must stop before a second read',
    );
    for (const mode of ['cycle', 'missing', 'escape']) {
      assert.equal(byMode[mode].rejected, true, mode);
      assert.equal(byMode[mode].writes.length, 0, mode + ' must not create a project');
    }
  },
);
