import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash, X509Certificate } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import https from 'node:https';
import path from 'node:path';
import { promisify } from 'node:util';

// Actual Swift parser, pairing and pinned Foundation reads. Auxiliary evidence;
// this fixture does not prove a physical VPN, desktop runtime or model task.
assert.equal(process.platform, 'darwin');
const exec = promisify(execFile);
const env = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR || '/Applications/Xcode.app/Contents/Developer',
};
await mkdir('.local/vpn-discovery-20261011', { recursive: true });
const directory = await mkdtemp(path.resolve('.local/vpn-discovery-20261011/native-'));
let server;
let connectionRequests = 0;
let pairingClaims = 0;
let hostRequests = 0;
let activeReads = 0;
let maxReads = 0;
function block(source, marker) {
  const start = source.indexOf(marker);
  assert.ok(start >= 0, marker);
  let depth = 0;
  let opened = false;
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') {
      depth++;
      opened = true;
    }
    if (source[i] === '}' && --depth === 0 && opened) return source.slice(start, i + 1);
  }
  throw new Error('Unclosed declaration ' + marker);
}
try {
  await exec('openssl', [
    'req',
    '-x509',
    '-newkey',
    'rsa:2048',
    '-nodes',
    '-days',
    '1',
    '-subj',
    '/CN=localhost',
    '-keyout',
    path.join(directory, 'key.pem'),
    '-out',
    path.join(directory, 'cert.pem'),
  ]);
  const cert = await readFile(path.join(directory, 'cert.pem'));
  const pin = createHash('sha256').update(new X509Certificate(cert).raw).digest('hex');
  const target = {
    account: { authority: 'https://account.example', subject: 'owner' },
    desktopDeviceId: 'desktop',
    workspaceId: 'workspace',
    runtimeId: 'local',
  };
  const publicKey = 'd'.repeat(43);
  let descriptor;
  server = https.createServer(
    { key: await readFile(path.join(directory, 'key.pem')), cert },
    async (request, response) => {
      response.setHeader('content-type', 'application/json');
      if (request.url === '/host') {
        hostRequests++;
        maxReads = Math.max(maxReads, ++activeReads);
        await new Promise((resolve) => setTimeout(resolve, 500));
        activeReads--;
        response.end(JSON.stringify(descriptor));
        return;
      }
      if (request.url === '/connections' || request.url === '/pairing/claim') {
        if (request.url === '/connections') connectionRequests++;
        else pairingClaims++;
        let body = '';
        for await (const chunk of request) body += chunk;
        const claim = JSON.parse(body);
        response.end(
          JSON.stringify({
            binding: {
              bindingId: 'binding',
              account: target.account,
              desktopDeviceId: target.desktopDeviceId,
              mobileDeviceId: claim.mobileDeviceId,
              desktopPublicKey: publicKey,
              mobilePublicKey: claim.publicKey,
              target,
              status: 'active',
              createdAt: new Date().toISOString(),
            },
          }),
        );
        return;
      }
      response.writeHead(404);
      response.end();
    },
  );
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const endpoint = `https://127.0.0.1:${server.address().port}`;
  descriptor = {
    version: 1,
    name: 'fixture',
    fingerprint: pin,
    publicKey,
    target,
    connectionMode: 'automatic',
    endpoints: [
      endpoint,
      'https://100.64.0.1:4319',
      'https://[fd7a:115c:a1e0::1]:4319',
      'https://office.example.ts.net:4319',
    ],
    endpointHints: [{ endpoint: 'https://100.64.0.1:4319', networkKind: 'vpn' }],
  };
  const offer = {
    ...descriptor,
    offerId: 'offer',
    pairingCode: '123456',
    expiresAt: new Date(Date.now() + 60000).toISOString(),
  };
  const uri =
    'easywork-host://pair?descriptor=' + Buffer.from(JSON.stringify(offer)).toString('base64url');
  const legacyOffer = { ...offer };
  delete legacyOffer.version;
  delete legacyOffer.connectionMode;
  delete legacyOffer.endpointHints;
  legacyOffer.endpoints = [endpoint];
  const legacyURI =
    'easywork-host://pair?descriptor=' +
    Buffer.from(JSON.stringify(legacyOffer)).toString('base64url');
  const transport = await readFile('apps/ios/Sources/EasyWorkTransport.swift', 'utf8');
  const discovery = await readFile('apps/ios/Sources/EasyWorkDiscovery.swift', 'utf8');
  const pairing = await readFile('apps/ios/Sources/EasyWorkPairing.swift', 'utf8');
  const client = await readFile('apps/ios/Sources/AgentClient.swift', 'utf8');
  const shared = await readFile('apps/ios/Sources/NotificationModels.swift', 'utf8');
  const host = await readFile('apps/ios/Sources/HostService.swift', 'utf8');
  const declarations = [
    block(shared, 'struct AgentAccount:'),
    block(shared, 'struct AgentRuntimeTarget:'),
    block(client, 'struct AgentConnectionCandidate:'),
    block(client, 'enum AgentConnectionPreference:'),
    block(client, 'struct AgentConnection:'),
    block(transport, 'struct EasyWorkTarget:'),
    block(transport, 'struct EasyWorkBinding:'),
    block(transport, 'struct EasyWorkCredential:'),
    block(transport, 'extension Data {'),
    transport.slice(
      transport.indexOf('func easyWorkJSON('),
      transport.indexOf('/// Only the specifically'),
    ),
    block(transport, 'final class PairedHostTrust:'),
    block(transport, 'final class DiscoveredHostTrust:'),
    block(discovery, 'struct EasyWorkEndpointHint:'),
    block(discovery, 'struct EasyWorkHostDescriptor:'),
    block(discovery, 'actor EasyWorkProbeBudget {'),
    block(pairing, 'struct EasyWorkLANOffer:'),
    'enum EasyWorkPairing { ' + block(pairing, '  static func lan(') + ' }',
    block(discovery, 'extension EasyWorkPairing {'),
    block(discovery, '@MainActor final class EasyWorkKnownHosts:'),
    block(discovery, 'extension SessionStore {'),
  ].join('\n');
  const source = `import Foundation
import CryptoKit
import Security
import Darwin
import Combine
import Network
${declarations}
enum HostServiceError: Error { case invalidAddress, invalidResponse, unavailable, wrongHost, message(String) }
enum HostService {
${block(host, '  static func baseURL(')}
${block(host, '  static func request(')}
  static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}
struct EasyWorkIdentity { var deviceId = "phone"; var publicKey = String(repeating: "m", count: 43); static func load() throws -> Self { Self() } }
struct Client { var id: String; var name: String; var online: Bool; var agentConnection: AgentConnection? }
struct FixtureSaved { var clients: [Client] }
@MainActor final class SessionStore {
  var saved: FixtureSaved; var persists = 0; var refreshes = 0
  var client: Client? { saved.clients.first }
  init(_ client: Client) { saved = FixtureSaved(clients: [client]) }
  func persist() { persists += 1 }
  func refresh() async { refreshes += 1 }
}
enum CredentialVault { static var value = ""; static func token(_ ref: String) -> String { value } }
actor EasyWorkSessions { static let shared = EasyWorkSessions(); func updateCandidates(_ id: String, connection: AgentConnection) {} }
@main struct Check {
  @MainActor static func main() async throws {
    let endpoint = CommandLine.arguments[1], pin = CommandLine.arguments[2], uri = CommandLine.arguments[3]
    let accepted = ["https://100.64.0.0:4319", "https://100.127.255.255:4319", "https://[::ffff:100.100.1.2]:4319", "https://[::ffff:6440:1]:4319", "https://[0:0:0:0:0:ffff:100.64.0.1]:4319", "https://[fd7a:115c:a1e0::1]:4319", "https://Office.Example.ts.net.:4319/"]
    for address in accepted { _ = try EasyWorkPairing.localEndpoint(address) }
    for address in ["https://100.63.255.255:4319", "https://100.128.0.0:4319", "https://[::ffff:8.8.8.8]:4319", "https://8.8.8.8", "https://fake.ts.net.evil.example", "https://user@office.example.ts.net", "https://office.example.ts.net/path", "https://office.example.ts.net?x=1", "http://100.64.0.1"] {
      do { _ = try EasyWorkPairing.localEndpoint(address); fatalError("accepted invalid endpoint: " + address) } catch {}
    }
    let normalized = try EasyWorkPairing.directCandidates(["https://Office.Example.ts.net.:443/", "https://office.example.ts.net"], fingerprint: pin)
    precondition(normalized.count == 1 && normalized[0].endpoint == "https://office.example.ts.net")
    let offer = try EasyWorkLANOffer.parse(uri)
    let host = try await EasyWorkPairing.readHost(endpoint: endpoint, fingerprint: pin, target: offer.target, publicKey: offer.publicKey)
    precondition(host.endpoints?.count == 4)
    var wrongTarget = offer.target; wrongTarget.workspaceId = "another-workspace"
    do { _ = try await EasyWorkPairing.readHost(endpoint: endpoint, fingerprint: pin, target: wrongTarget); fatalError("accepted other target") } catch {}
    do { _ = try await EasyWorkPairing.readHost(endpoint: endpoint, fingerprint: pin, target: offer.target, publicKey: String(repeating: "x", count: 43)); fatalError("accepted other public key") } catch {}
    var wrongOffer = offer; wrongOffer.fingerprint = String(repeating: "0", count: 64)
    do { _ = try await EasyWorkPairing.connect(endpoint: endpoint, name: "phone", offer: wrongOffer); fatalError("accepted other certificate") } catch {}
    let (binding, candidates) = try await EasyWorkPairing.lan(uri, name: "phone")
    let (automatic, automaticCandidates) = try await EasyWorkPairing.connectDiscovered(endpoint: endpoint, name: "phone")
    precondition(automatic.target == binding.target && automaticCandidates.count == 4)
    precondition(candidates.count == 4 && Set(candidates.map(\\.id)).count == 4)
    precondition(candidates.allSatisfy { $0.peerFingerprint == pin })
    precondition(candidates.contains { $0.endpoint == "https://100.64.0.1:4319" && $0.networkKind == .vpn })
    precondition(candidates.contains { $0.endpoint == "https://[fd7a:115c:a1e0::1]:4319" })
    let (legacyBinding, legacyCandidates) = try await EasyWorkPairing.lan(CommandLine.arguments[4], name: "phone")
    precondition(legacyBinding.target == binding.target && legacyBinding.desktopPublicKey == binding.desktopPublicKey && legacyCandidates.count == 4, "legacy QR lost verified host addresses")
    let oldCandidates = try EasyWorkPairing.directCandidates([endpoint], fingerprint: pin)
    let repaired = await EasyWorkPairing.reachableCandidates(oldCandidates, binding: binding)
    precondition(repaired.count == 4)
    let oldCache = try JSONDecoder().decode(AgentConnectionCandidate.self, from: Data(#"{"id":"lan-0","transportId":"lan-wss","endpoint":"https://100.64.0.1:4319"}"#.utf8))
    precondition(oldCache.networkKind == nil)
    try await withThrowingTaskGroup(of: Void.self) { group in
      for _ in 0..<9 { group.addTask { _ = try await EasyWorkPairing.readHost(endpoint: endpoint, fingerprint: pin, target: binding.target, publicKey: binding.desktopPublicKey) } }
      try await group.waitForAll()
    }
    let connection = AgentConnection(target: AgentRuntimeTarget(agentId: "easywork", hostId: binding.target.desktopDeviceId, workspaceId: binding.target.workspaceId, runtimeId: binding.target.runtimeId, account: binding.target.account), candidates: oldCandidates, preference: .automatic, credentialRef: "fixture")
    CredentialVault.value = String(decoding: try JSONEncoder().encode(EasyWorkCredential(binding: binding, privateKey: "fixture")), as: UTF8.self)
    let store = SessionStore(Client(id: "known", name: "fixture", online: true, agentConnection: connection))
    let service = EasyWorkKnownHosts()
    let stopped = Task { await service.run(store) }
    try await Task.sleep(for: .milliseconds(100))
    service.stop(); stopped.cancel()
    try await Task.sleep(for: .milliseconds(700))
    precondition(store.persists == 0 && store.saved.clients[0].agentConnection?.candidates.count == 1, "background late result persisted")
    let foreground = Task { await service.run(store) }
    try await Task.sleep(for: .milliseconds(1100))
    precondition(store.saved.clients[0].agentConnection?.candidates.count == 4 && service.hosts.first?.reachable == true)
    precondition(store.refreshes == 0, "healthy connection refreshed")
    service.stop(); foreground.cancel()
    print("native discovery/pairing passed")
  }
}`;
  const swift = path.join(directory, 'Check.swift');
  const executable = path.join(directory, 'check');
  await writeFile(swift, source);
  await exec(
    'xcrun',
    ['swiftc', '-parse-as-library', swift, 'apps/ios/Sources/Localization.swift', '-o', executable],
    { env },
  );
  const result = await exec(executable, [endpoint, pin, uri, legacyURI], { env, timeout: 45000 });
  assert.equal(
    connectionRequests,
    2,
    'Pinned discovery and rejected peers must not create bindings',
  );
  assert.ok(hostRequests >= 3);
  assert.equal(pairingClaims, 1, 'Legacy QR is the only pairing claim');
  assert.equal(maxReads, 4, 'Discovery and reconnects share the four-read limit');
  assert.match(result.stdout, /passed/);
  console.log(
    JSON.stringify({
      status: 'passed',
      boundary: 'actual Swift + Foundation TLS',
      connectionRequests,
      pairingClaims,
      hostRequests,
      maxReads,
    }),
  );
} finally {
  if (server) {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
  await rm(directory, { recursive: true, force: true });
}
