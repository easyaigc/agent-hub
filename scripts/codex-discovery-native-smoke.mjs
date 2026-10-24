import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdtemp, rm, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { startDirectDiscovery } from '../apps/host-agent/dist/direct-discovery.js';
import { PrivateConnections } from '../apps/host-agent/dist/private-connections.js';

assert.equal(process.platform, 'darwin');
const execute = promisify(execFile);
await mkdir('.local/codex-discovery-20261011', { recursive: true });
const directory = await mkdtemp(resolve('.local/codex-discovery-20261011/native-'));
function block(source, marker) {
  const start = source.indexOf(marker);
  assert.ok(start >= 0, marker);
  let depth = 0,
    opened = false;
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') {
      depth++;
      opened = true;
    }
    if (source[i] === '}' && --depth === 0 && opened) return source.slice(start, i + 1);
  }
  throw Error('Unclosed declaration ' + marker);
}
const [discovery, easywork, transport, shared, client, host, facade] = await Promise.all(
  [
    'CodexDiscovery',
    'EasyWorkDiscovery',
    'EasyWorkTransport',
    'NotificationModels',
    'AgentClient',
    'HostService',
    'SharedMobileRules',
  ].map((name) => readFile('apps/ios/Sources/' + name + '.swift', 'utf8')),
);
let reads = 0,
  writes = 0;
const token = 'fixture-token-with-32-characters-or-more';
const target = {
  agentId: 'codex',
  hostId: 'host',
  workspaceId: 'host',
  runtimeId: 'desktop',
  account: { authority: 'fixture', subject: 'owner' },
};
process.env.HOST_AGENT_DISCOVERY_BONJOUR = '0';
const privateConnections = new PrivateConnections(directory);
const service = await startDirectDiscovery({
  directory,
  name: 'Fixture',
  token,
  target: () => target,
  peers: async () => [{ name: 'VPN', addresses: ['100.64.0.1'] }],
  claim: (input) => privateConnections.claim(input, target),
  port: 0,
  bind: '127.0.0.1',
  handler: async (req, res) => {
    if (req.method === 'POST') {
      writes++;
      res.writeHead(502);
      res.end();
      return;
    }
    reads++;
    if (
      req.headers.authorization !== 'Bearer ' + token &&
      !privateConnections.accepts(
        Buffer.from((req.headers.authorization ?? '').replace(/^Bearer /, '')),
        target,
      )
    ) {
      res.writeHead(401);
      res.end();
      return;
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    if (req.url.startsWith('/v1/connection-routes?')) {
      res.end(
        JSON.stringify({
          version: 1,
          target,
          httpsEndpoints: ['https://fixture.tail.ts.net:8443'],
        }),
      );
      return;
    }
    res.end(
      JSON.stringify({
        hostId: 'host',
        name: 'Fixture',
        connectionMode: 'desktop-runtime',
        readTarget: target,
        projects: [],
        threads: [],
        capabilities: ['projects:read', 'threads:read', 'history:read'],
      }),
    );
  },
});
const descriptor = service.descriptor();
descriptor.endpoints.push(
  'https://100.64.0.1:8796',
  'https://[fd00::1]:8796',
  'https://office.test.ts.net:8796',
);
const uri =
  'codex-host://pair?descriptor=' +
  Buffer.from(JSON.stringify({ ...descriptor, token })).toString('base64url');
try {
  const declarations = [
    block(host, 'enum NativeReadAcceptanceTiming {'),
    block(shared, 'struct AgentAccount:'),
    block(shared, 'struct AgentRuntimeTarget:'),
    block(client, 'struct AgentConnectionCandidate:'),
    block(transport, 'extension Data {'),
    block(transport, 'final class PairedHostTrust:'),
    block(transport, 'final class DiscoveredHostTrust:'),
    block(transport, 'struct EasyWorkIdentity:'),
    block(easywork, 'struct EasyWorkEndpointHint:'),
    block(easywork, 'actor EasyWorkProbeBudget {'),
    'enum EasyWorkPairing { ' +
      block(easywork, '  static func localEndpoint(') +
      '\n' +
      block(easywork, '  static func directCandidates(') +
      ' }',
    discovery.slice(
      discovery.indexOf('struct CodexDirectConnection:'),
      discovery.indexOf('struct VPNPeerSnapshot:'),
    ),
    block(discovery, 'struct VPNPeerSnapshot:'),
    block(discovery, 'enum CodexPrivateCredential {'),
    'enum SharedMobileRules { ' + block(facade, '  static func scope(') + ' }',
  ].join('\n');
  const source = `import Foundation
import Network
import Security
import CryptoKit
import Darwin
import MobileCore
${declarations}
enum HostServiceError: Error { case invalidAddress, invalidResponse, unavailable, wrongHost, message(String) }
enum TaskNotificationValidation { static func target(_ target: AgentRuntimeTarget) -> Bool { target.agentId == "codex" && !target.hostId.isEmpty && !target.workspaceId.isEmpty && !target.runtimeId.isEmpty } }
struct Client: Sendable { var id: String; var name: String; var endpoint: String?; var isDemo: Bool; var online: Bool; var codexDirect: CodexDirectConnection? }
struct WorkspaceSnapshot: Decodable { var hostId: String; var readTarget: AgentRuntimeTarget?; var connectionMode: String }
enum CredentialVault { static var tokens: [String: String] = [:]; static func token(_ id: String) -> String { tokens[id] ?? "" }; static func set(_ value: String, id: String) throws { tokens[id] = value } }
struct HostService {
${block(host, '  static func baseURL(')}
${block(host, '  static func request(')}
${block(host, '  static func hash(')}
}
${block(host, 'enum NativeReadAcceptanceTiming {')}
@main struct Check {
static func main() async throws {
let offer = try CodexHostDescriptor.parse(${JSON.stringify(uri)})
let peer = VPNPeerSnapshot.Peer(name: "VPN", addresses: ["100.64.0.1", "fd7a:115c:a1e0::1"])
try VPNPeerSnapshot(version: 1, target: offer.target, peers: [peer]).validate(offer.target)
for invalid in ["100.63.255.255", "100.128.0.0", "192.168.1.2", "https://100.64.0.1", "fd00::1", "office.ts.net"] { precondition(!VPNPeerSnapshot.isVPNAddress(invalid)) }
let arranged = VPNPeerSnapshot.orderedEndpoints(["https://office.local:8796", "https://192.168.1.2:8796", "https://100.64.0.8:8796", "https://[fd7a:115c:a1e0::8]:8796", "https://100.64.0.8:8796"])
precondition(arranged.count == 4 && arranged[0].contains("100.64.0.8") && arranged[1].contains("fd7a:"))
let candidates = try offer.candidates()
precondition(candidates.count == 4 && Set(candidates.map { $0.id }).count == 4)
let endpoint = offer.endpoints[0]
let connected = try await CodexDiscoveryAPI.connect(${JSON.stringify(uri)}, name: "Native")
precondition(connected.1.codexDirect!.candidates.count == 4)
precondition(connected.1.codexDirect!.systemEndpoints == ["https://fixture.tail.ts.net:8443"])
precondition(connected.2.readTarget == offer.target)
let value = try await CodexDiscoveryAPI.read(endpoint, fingerprint: offer.fingerprint, target: offer.target)
precondition(value.target == offer.target)
let discovered = try await CodexDiscoveryAPI.read(endpoint, fingerprint: nil, target: nil, discover: true)
precondition(discovered.target == offer.target && discovered.fingerprint == offer.fingerprint)
let unpaired = try await CodexDiscoveryAPI.readPeers(endpoint, fingerprint: discovered.fingerprint, target: discovered.target)
precondition(unpaired.peers.count == 1)
for key in ["", "wrong-host-key-with-at-least-32-characters"] {
do { _ = try await CodexDiscoveryAPI.connectDiscovered(endpoint, hostId: offer.target.hostId, name: "Authorized", hostKey: key); fatalError("Missing or wrong Host Key accepted") } catch {}
}
let automatic = try await CodexDiscoveryAPI.connectDiscovered(endpoint, hostId: offer.target.hostId, name: "Authorized", hostKey: offer.token!)
precondition(automatic.2.readTarget == offer.target && automatic.0.token != offer.token)
var staleTarget = offer.target; staleTarget.runtimeId = "other"
let stale = CodexDirectConnection(target: staleTarget, candidates: [], fingerprint: offer.fingerprint)
do { _ = try await CodexDiscoveryAPI.connectDiscovered(endpoint, hostId: offer.target.hostId, name: "Reauthorize", hostKey: offer.token!, expected: stale); fatalError("Changed saved target accepted") } catch {}
precondition(automatic.1.codexDirect!.systemEndpoints == ["https://fixture.tail.ts.net:8443"])
let recovered = try await CodexDiscoveryAPI.connectDiscovered(endpoint, hostId: offer.target.hostId, name: "Authorized", hostKey: offer.token!)
precondition(recovered.0.token == automatic.0.token)
do { _ = try await CodexDiscoveryAPI.read(endpoint, fingerprint: String(repeating: "0", count: 64), target: offer.target); fatalError("Wrong pin accepted") } catch {}
var other = offer.target; other.runtimeId = "wrong"
do { _ = try await CodexDiscoveryAPI.read(endpoint, fingerprint: offer.fingerprint, target: other); fatalError("Wrong target accepted") } catch {}
for invalid in ["https://100.63.255.255", "https://100.128.0.0", "https://100.64.0.1/path", "https://user@100.64.0.1", "https://100.64.0.1?query=1"] {
do { _ = try EasyWorkPairing.localEndpoint(invalid); fatalError("Invalid address accepted") } catch {}
}
CodexHostRoutes.shared.register(connected.1)
var request = HostService.request(try HostService.baseURL(endpoint).appendingPathComponent("write"), token: offer.token!, method: "POST", body: Data("once".utf8))
request.timeoutInterval = 2
let (_, response) = try await CodexHostRoutes.shared.data(for: request)
precondition((response as! HTTPURLResponse).statusCode == 502)
print("Codex native QR, all paths, pins, identity, URL boundary and one submitted request passed")
}}
`;
  const file = join(directory, 'Check.swift'),
    binary = join(directory, 'check');
  await writeFile(file, source, { mode: 0o600 });
  const framework = resolve('.build/native/mobile-core/bin/macosArm64/debugFramework');
  await execute(
    process.execPath,
    ['scripts/mobile/gradle.mjs', ':mobile-core:linkDebugFrameworkMacosArm64'],
    { timeout: 180000, maxBuffer: 4 * 1024 * 1024 },
  );
  await execute(
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
      'apps/ios/Sources/Localization.swift',
      file,
      '-o',
      binary,
    ],
    {
      env: {
        ...process.env,
        DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer',
      },
      timeout: 120000,
      maxBuffer: 1024 * 1024,
    },
  );
  const result = await execute(binary, [], { timeout: 60000 });
  assert.equal(writes, 1);
  assert.equal(reads, 6);
  console.log(
    JSON.stringify({
      passed: true,
      writes,
      authenticatedReads: reads,
      native: result.stdout.trim(),
      evidence: 'auxiliary actual Swift/Foundation code; no desktop model or physical VPN',
    }),
  );
} finally {
  service.close();
  await rm(directory, { recursive: true, force: true });
}
