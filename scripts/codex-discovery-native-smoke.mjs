import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdtemp, rm, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { startDirectDiscovery } from '../apps/host-agent/dist/direct-discovery.js';
import { createServer } from 'node:https';
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
const [discovery, easywork, transport, shared, client, host, facade, store] = await Promise.all(
  [
    'CodexDiscovery',
    'EasyWorkDiscovery',
    'EasyWorkTransport',
    'NotificationModels',
    'AgentClient',
    'HostService',
    'SharedMobileRules',
    'SessionStore',
  ].map((name) => readFile('apps/ios/Sources/' + name + '.swift', 'utf8')),
);
let reads = 0,
  writes = 0,
  networkReads = 0,
  networkMode = 'valid';
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
    if (
      !req.url.startsWith('/v1/discovery/networks') &&
      !req.url.startsWith('/fixture-network-mode/')
    )
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
    if (req.url.startsWith('/fixture-network-mode/')) {
      networkMode = req.url.split('/').at(-1);
      res.writeHead(200);
      res.end('{}');
      return;
    }
    if (req.url.startsWith('/v1/discovery/networks?')) {
      networkReads++;
      if (networkMode === 'old' || networkMode === 'revoked') {
        res.writeHead(networkMode === 'old' ? 404 : 401);
        res.end('{}');
        return;
      }
      const snapshot = {
        version: networkMode === 'version' ? 2 : 1,
        target: networkMode === 'target' ? { ...target, runtimeId: 'wrong' } : target,
        networks: Array.from({ length: 40 }, (_, i) => ({
          address: `10.${160 + i}.0.1`,
          cidr: `10.${160 + i}.0.0/24`,
        })),
      };
      if (networkMode === 'large')
        snapshot.networks = Array.from({ length: 2048 }, (_, i) => ({
          address: `10.${Math.floor(i / 256)}.${i % 256}.1`,
          cidr: `10.${Math.floor(i / 256)}.${i % 256}.0/24`,
        }));
      if (networkMode === 'malformed') snapshot.networks[0].cidr = '10.160.0.1/24';
      if (networkMode === 'public')
        snapshot.networks[0] = { address: '8.8.8.8', cidr: '8.8.8.0/24' };
      if (networkMode === 'extra') snapshot.token = 'must-not-be-exported';
      const body = JSON.stringify(snapshot);
      if (networkMode === 'large') assert.ok(Buffer.byteLength(body) > 65536);
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(body);
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
let legacyClaims = 0;
const legacyServer = createServer(
  {
    cert: await readFile(join(directory, 'direct-discovery/cert.pem')),
    key: await readFile(join(directory, 'direct-discovery/key.pem')),
  },
  async (req, res) => {
    if (req.method === 'GET' && req.url === '/host') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(
        JSON.stringify({
          ...service.descriptor(),
          connectionMode: 'automatic',
          endpoints: Array.from(
            { length: 32 },
            (_, index) => `https://192.168.91.${index + 1}:8796`,
          ),
          endpointHints: [],
        }),
      );
    } else if (req.method === 'POST' && req.url === '/connection/claim') {
      assert.equal(
        req.headers.authorization,
        undefined,
        'Host Key must not be sent to a legacy automatic endpoint',
      );
      legacyClaims++;
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const reply = privateConnections.claim(JSON.parse(Buffer.concat(chunks).toString()), target);
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify(reply));
    } else {
      service.server.listeners('request')[0](req, res);
    }
  },
);
await new Promise((done) => legacyServer.listen(0, '127.0.0.1', done));
const legacyEndpoint = 'https://127.0.0.1:' + legacyServer.address().port;
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
    block(discovery, 'enum DiscoveryResponse {'),
    host.slice(
      host.indexOf('struct FileDownloadProgress:'),
      host.indexOf('enum NativeReadAcceptanceTiming {'),
    ),
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
    block(discovery, 'struct OfficeDiscoveryScope:'),
    block(discovery, 'extension CodexDirectConnection.Network {'),
    block(discovery, 'enum LocalDiscoveryNetworks {'),
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
enum HostServiceError: Error { case invalidAddress, invalidResponse, unavailable, wrongHost, hostKeyRequired, message(String), rejected(Int, String) }
enum TaskNotificationValidation { static func target(_ target: AgentRuntimeTarget) -> Bool { target.agentId == "codex" && !target.hostId.isEmpty && !target.workspaceId.isEmpty && !target.runtimeId.isEmpty } }
struct Client: Sendable { var id: String; var name: String; var endpoint: String?; var isDemo: Bool; var online: Bool; var codexDirect: CodexDirectConnection? }
struct WorkspaceSnapshot: Decodable { var hostId: String; var readTarget: AgentRuntimeTarget?; var connectionMode: String }
struct SavedFixture { var clients: [Client]; var workspaces: [String: WorkspaceSnapshot] = [:] }
@MainActor final class SessionStore {
 var saved: SavedFixture; var client: Client? = nil; var persisted: Data? = nil
 init(_ value: Client) { saved = SavedFixture(clients: [value]) }
 func persist() { persisted = try? JSONEncoder().encode(saved.clients[0].codexDirect) }
 func invalidateAgentConnection(_ id: String) {}
 func refresh() async {}
 ${block(store, '  func updateCodexDiscovery(')}
}
enum CredentialVault { static var tokens: [String: String] = [:]; static func token(_ id: String) -> String { tokens[id] ?? "" }; static func set(_ value: String, id: String) throws { tokens[id] = value } }
struct HostService {
${block(host, '  static func baseURL(')}
${block(host, '  static func request(')}
${block(host, '  static func hash(')}
}
@main struct Check {
@MainActor static func main() async throws {
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
let legacy = try await CodexDiscoveryAPI.connectDiscovered(${JSON.stringify(legacyEndpoint)}, hostId: offer.target.hostId, name: "Legacy", hostKey: offer.token!)
precondition(legacy.2.readTarget == offer.target && legacy.0.token == automatic.0.token)
let legacyConnection = legacy.1.codexDirect!
precondition(legacy.0.endpoints.count == 32 && legacyConnection.candidates.map(\\.endpoint) == legacy.0.endpoints && legacyConnection.observedRoute == ${JSON.stringify(legacyEndpoint)})
let legacyRoundtrip = try JSONDecoder().decode(CodexDirectConnection.self, from: JSONEncoder().encode(legacyConnection))
precondition(legacyRoundtrip == legacyConnection)
CodexHostRoutes.shared.register(legacy.1)
let legacyRequest = URLRequest(url: URL(string: ${JSON.stringify(legacyEndpoint)} + "/host")!)
precondition(CodexHostRoutes.shared.session(for: legacyRequest).delegate is PairedHostTrust)
let (_, legacyPinnedResponse) = try await CodexHostRoutes.shared.data(for: legacyRequest)
precondition((legacyPinnedResponse as? HTTPURLResponse)?.statusCode == 200)
print("PASS connectDiscovered preserves all 32 advertisements plus separate pinned observed route")
precondition(automatic.1.codexDirect?.discoveryNetworks?.count == 40)
precondition(legacyRoundtrip.discoveryNetworks?.count == 40)
let scopeSession = URLSession(configuration: .ephemeral, delegate: PairedHostTrust(endpoint: endpoint, fingerprint: offer.fingerprint), delegateQueue: nil)
defer { scopeSession.invalidateAndCancel() }
let scopeBase = URL(string:endpoint)!
for mode in ["old", "version", "malformed", "target", "public", "extra", "revoked"] {
  _ = try await scopeSession.data(for:HostService.request(scopeBase.appendingPathComponent("fixture-network-mode/" + mode),token:offer.token!))
  let unsupported = await CodexDiscoveryAPI.networks(scopeSession,base:scopeBase,token:automatic.0.token!,host:automatic.0)
  precondition(unsupported == nil, "Unexpected networks for " + mode)
}
_ = try await scopeSession.data(for:HostService.request(scopeBase.appendingPathComponent("fixture-network-mode/valid"),token:offer.token!))
let networkSnapshot = await CodexDiscoveryAPI.networks(scopeSession,base:scopeBase,token:automatic.0.token!,host:automatic.0)
precondition(networkSnapshot?.count == 40)
let unauthenticated = await CodexDiscoveryAPI.networks(scopeSession,base:scopeBase,token:"",host:automatic.0)
precondition(unauthenticated == nil)
print("PASS authenticated device-token learning; 40 networks independent of 32 advertisements; old/unknown/malformed/public/wrong-target/revoked hints rejected")
_ = try await scopeSession.data(for:HostService.request(scopeBase.appendingPathComponent("fixture-network-mode/large"),token:offer.token!))
let largeNetworkSnapshot = await CodexDiscoveryAPI.networks(scopeSession,base:scopeBase,token:automatic.0.token!,host:automatic.0)
precondition(largeNetworkSnapshot?.count == 2048)
precondition(largeNetworkSnapshot?.last?.address == "10.7.255.1")
_ = try await scopeSession.data(for:HostService.request(scopeBase.appendingPathComponent("fixture-network-mode/valid"),token:offer.token!))


do { _ = try await CodexDiscoveryAPI.read(endpoint, fingerprint: String(repeating: "0", count: 64), target: offer.target); fatalError("Wrong pin accepted") } catch {}
var other = offer.target; other.runtimeId = "wrong"
do { _ = try await CodexDiscoveryAPI.read(endpoint, fingerprint: offer.fingerprint, target: other); fatalError("Wrong target accepted") } catch {}
for invalid in ["https://100.63.255.255", "https://100.128.0.0", "https://100.64.0.1/path", "https://user@100.64.0.1", "https://100.64.0.1?query=1"] {
do { _ = try EasyWorkPairing.localEndpoint(invalid); fatalError("Invalid address accepted") } catch {}
}
var full = offer
full.endpoints = (1...32).map { "https://192.168.90.\\($0):8796" }
full.endpointHints = nil; full.systemEndpoints = nil; full.discoveryNetworks = largeNetworkSnapshot
let allAdvertised = try full.candidates()
precondition(allAdvertised.count == 32)
let prior = Client(id: offer.target.hostId, name: "Saved", endpoint: allAdvertised[0].endpoint, isDemo: false, online: false, codexDirect: CodexDirectConnection(target: offer.target, candidates: allAdvertised, fingerprint: offer.fingerprint))
let state = SessionStore(prior)
precondition(state.updateCodexDiscovery(prior, host: full, reachableEndpoint: endpoint, verifiedObservedEndpoint: endpoint))
let updated = state.saved.clients[0].codexDirect!
precondition(updated.candidates.map(\\.endpoint) == allAdvertised.map(\\.endpoint) && updated.observedRoute == endpoint)
precondition(!updated.systemRoutes.contains(endpoint) && state.saved.clients.count == 1)
let persisted = try JSONDecoder().decode(CodexDirectConnection.self, from: state.persisted!)
precondition(persisted == updated)
var legacyJSON = try JSONSerialization.jsonObject(with: state.persisted!) as! [String: Any]
legacyJSON.removeValue(forKey: "observedEndpoint")
legacyJSON.removeValue(forKey: "discoveryNetworks")
let old = try JSONDecoder().decode(CodexDirectConnection.self, from: JSONSerialization.data(withJSONObject: legacyJSON))
precondition(old.observedEndpoint == nil && old.discoveryNetworks == nil && old.candidates.count == 32)
full.discoveryNetworks = nil // old/failed optional endpoint preserves this same record
precondition(persisted.discoveryNetworks == largeNetworkSnapshot)
precondition(persisted.discoveryNetworks?.count == 2048 && state.persisted!.count > 65536)
print("PASS >64 KiB authenticated TLS inventory: all 2048 ranges persisted under the original record, target and pin")
let current = state.saved.clients[0]
precondition(state.updateCodexDiscovery(current, host: full, reachableEndpoint: endpoint))
precondition(state.saved.clients[0].codexDirect?.observedRoute == endpoint && !(state.saved.clients[0].codexDirect?.systemRoutes.contains(endpoint) ?? true))
var priorWithRoute = prior
priorWithRoute.endpoint = endpoint
priorWithRoute.codexDirect!.candidates = try EasyWorkPairing.directCandidates([endpoint] + Array(full.endpoints.prefix(31)), fingerprint: full.fingerprint)
let legacyState = SessionStore(priorWithRoute)
precondition(legacyState.updateCodexDiscovery(priorWithRoute, host: full, reachableEndpoint: endpoint, verifiedObservedEndpoint: endpoint))
precondition(legacyState.saved.clients[0].codexDirect!.candidates.map(\\.endpoint) == allAdvertised.map(\\.endpoint))
precondition(legacyState.saved.clients[0].codexDirect!.observedRoute == endpoint)
let pinnedSession = CodexHostRoutes.shared.session(for: URLRequest(url: URL(string: endpoint + "/host")!))
precondition(pinnedSession.delegate is PairedHostTrust)
let (pinnedData, pinnedResponse) = try await CodexHostRoutes.shared.data(for: URLRequest(url: URL(string: endpoint + "/host")!))
precondition((pinnedResponse as? HTTPURLResponse)?.statusCode == 200 && !pinnedData.isEmpty)
var wrong = state.saved.clients[0]; wrong.codexDirect!.fingerprint = String(repeating: "0", count: 64)
CodexHostRoutes.shared.register(wrong)
do { _ = try await CodexHostRoutes.shared.data(for: URLRequest(url: URL(string: endpoint + "/host")!)); fatalError("Wrong observed route pin accepted") } catch {}
print("PASS actual update, 32 advertisements, observed route persistence, legacy decoding and pinned TLS")
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
  assert.equal(reads, 8);
  assert.equal(legacyClaims, 1);
  console.log(
    JSON.stringify({
      passed: true,
      writes,
      authenticatedReads: reads,
      networkReads,
      native: result.stdout.trim(),
      evidence: 'auxiliary actual Swift/Foundation code; no desktop model or physical VPN',
    }),
  );
} finally {
  legacyServer.close();
  service.close();
  await rm(directory, { recursive: true, force: true });
}
