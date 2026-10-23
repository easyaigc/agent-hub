import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('Native history gateway check skipped: macOS Swift/MobileCore required.');
  process.exit(0);
}

// Compile the actual native gateway and continuation against the actual shared
// HistoryRequests implementation. The provider injects errors; no user data/model.
const source = await readFile('apps/ios/Sources/SharedMobileRules.swift', 'utf8');
function block(marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, marker);
  let depth = 0,
    opened = false;
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') {
      depth++;
      opened = true;
    }
    if (source[i] === '}' && --depth === 0 && opened) return source.slice(start, i + 1);
  }
  throw Error('Unclosed source declaration');
}
const directory = resolve('.local/history-error-native-' + Date.now());
await mkdir(directory, { recursive: true, mode: 0o700 });
const program = `import Foundation
import MobileCore
struct AgentRuntimeTarget: Codable, Equatable { var hostId: String }
struct AgentConnection { var target: AgentRuntimeTarget }
struct Client { var id = "native-host"; var readingTarget: AgentRuntimeTarget? = nil; var agentConnection: AgentConnection? = nil }
struct HistorySnapshot: Codable { var hostId: String; var threadId: String; var readTarget: AgentRuntimeTarget? = nil }
enum HostServiceError: LocalizedError {
  case wrongHost, invalidResponse, rejected(Int, String), message(String)
  var errorDescription: String? {
    switch self { case .rejected(_, let text), .message(let text): return text; default: return "native-failure" }
  }
}
@MainActor enum AgentClients {
  static var injected: Error?; static var reads = 0
  static func resolve(_ client: Client) throws -> Provider { Provider() }
  struct Provider {
    @MainActor func history(_ client: Client, threadId: String, cursor: String?) async throws -> HistorySnapshot {
      AgentClients.reads += 1
      if let failure = AgentClients.injected { throw failure }
      return HistorySnapshot(hostId: client.id, threadId: threadId)
    }
  }
}
${block('private final class HistoryCancellation:')}
${block('private final class NativeHistoryGateway:')}
enum SharedMobileRules {}
extension SharedMobileRules {
${block('  @MainActor static func history(')}
}
@main struct Verify {
  @MainActor static func main() async throws {
    let codes: [URLError.Code] = [.timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed]
    for code in codes {
      AgentClients.injected = URLError(code)
      do { _ = try await SharedMobileRules.history(Client(), targetKey: "target", threadId: "thread", cursor: nil); fatalError("Expected URL failure") }
      catch { precondition((error as? URLError)?.code == code, "Shared delivery erased the native URL error") }
    }
    for status in [401, 403, 503] {
      AgentClients.injected = HostServiceError.rejected(status, "original-rejection")
      do { _ = try await SharedMobileRules.history(Client(), targetKey: "target", threadId: "thread", cursor: nil); fatalError("Expected rejection") }
      catch HostServiceError.rejected(let actual, let message) { precondition(actual == status && message == "original-rejection") }
    }
    AgentClients.injected = HostServiceError.wrongHost
    do { _ = try await SharedMobileRules.history(Client(), targetKey: "target", threadId: "thread", cursor: nil); fatalError("Expected target failure") }
    catch HostServiceError.wrongHost {}
    AgentClients.injected = HostServiceError.invalidResponse
    do { _ = try await SharedMobileRules.history(Client(), targetKey: "target", threadId: "thread", cursor: nil); fatalError("Expected protocol failure") }
    catch HostServiceError.invalidResponse {}
    AgentClients.injected = URLError(.cancelled)
    do { _ = try await SharedMobileRules.history(Client(), targetKey: "target", threadId: "thread", cursor: nil); fatalError("Expected cancellation") }
    catch is CancellationError {}
    AgentClients.injected = nil
    let snapshot = try await SharedMobileRules.history(Client(), targetKey: "target", threadId: "thread", cursor: nil)
    precondition(snapshot.hostId == "native-host" && snapshot.threadId == "thread")
    precondition(AgentClients.reads == 13, "Failure recovery must not replay a read or write")
    print("13 native/shared delivery checks passed: six URL failures, three HTTP statuses, identity, protocol, cancellation and subsequent success; exactly one provider read each")
  }
}`;
const input = directory + '/check.swift',
  binary = directory + '/check';
await writeFile(input, program);
const framework = resolve('.build/native/mobile-core/bin/macosArm64/debugFramework');
execFileSync(
  'xcrun',
  [
    'swiftc',
    '-parse-as-library',
    '-F',
    framework,
    '-framework',
    'MobileCore',
    '-Xlinker',
    '-rpath',
    '-Xlinker',
    framework,
    input,
    '-o',
    binary,
  ],
  { stdio: 'inherit' },
);
const output = execFileSync(binary, { encoding: 'utf8' });
assert(output.includes('13 native/shared delivery checks passed'));
await writeFile(
  directory + '/report.json',
  JSON.stringify(
    {
      status: 'passed',
      evidence:
        'actual native/shared gateway with injected provider errors; no real network or model',
      sourceSha256: createHash('sha256').update(source).digest('hex'),
      output,
    },
    null,
    2,
  ) + '\n',
  { mode: 0o600 },
);
console.log(JSON.stringify({ directory, output }));
