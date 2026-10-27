import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('Native Codex authorization alert check requires macOS Swift.');
  process.exit(0);
}
const source = await readFile('apps/ios/Sources/SessionStore.swift', 'utf8');
const marker = '  private func recordConnectionFailure(';
const start = source.indexOf(marker);
assert(start >= 0);
let end = source.indexOf('{', start) + 1,
  depth = 1;
while (depth) {
  if (source[end] === '{') depth++;
  else if (source[end] === '}') depth--;
  end++;
}
const method = source.slice(start, end);
const directory = resolve('.local/codex-authorization-alert-native');
await mkdir(directory, { recursive: true });
const input = directory + '/check.swift',
  binary = directory + '/check';
await writeFile(
  input,
  `import Foundation
struct Client { var id: String; var online = true; var agentConnection: Bool? = nil }
struct Saved { var clients: [Client] }
enum Phase { case failed, waiting }
enum HostServiceError: Error { case unavailable, rejected(Int, String) }
final class Store {
  var saved = Saved(clients: [Client(id: "codex")])
  var client: Client? = Client(id: "codex")
  var lastConnectionSuccess: [String: TimeInterval] = [:]
  var suggestionNeedsFreshHistory = false
  var connectionPhases: [String: Phase] = [:]
  var alerts = 0
  func presentError(_ error: Error) { alerts += 1 }
${method}
  func fail(_ status: Int, agent: Bool? = nil) {
    recordConnectionFailure(HostServiceError.rejected(status, "authorization"), host: Client(id: "codex", agentConnection: agent))
  }
}
let store = Store()
for _ in 0..<20 { store.fail(401) }
guard store.alerts == 0, store.saved.clients[0].online == false,
  store.connectionPhases["codex"] == .failed else { print("Background 401 repeatedly opened a blocking alert"); exit(1) }
store.fail(403)
guard store.alerts == 1 else { exit(2) }
store.fail(401, agent: true)
guard store.alerts == 2 else { exit(3) }
store.client = Client(id: "other")
store.fail(401)
guard store.alerts == 2, store.client?.id == "other" else { exit(4) }
print("20 background Codex 401 retries keep the failure state without modal alerts; unrelated rejection, other Agent authorization and target switching preserved")
`,
);
execFileSync('xcrun', ['swiftc', input, '-o', binary], {
  env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
  stdio: 'pipe',
});
const result = spawnSync(binary, [], { encoding: 'utf8' });
assert.equal(result.status, 0, result.stdout + result.stderr);
console.log(result.stdout.trim());
