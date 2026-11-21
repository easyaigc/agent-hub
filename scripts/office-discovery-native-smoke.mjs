import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
const source = await readFile('apps/ios/Sources/CodexDiscovery.swift', 'utf8');
function block(marker) {
  const start = source.indexOf(marker);
  if (start < 0) throw Error(marker);
  let d = 0,
    opened = false;
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') {
      d++;
      opened = true;
    }
    if (source[i] === '}' && --d === 0 && opened) return source.slice(start, i + 1);
  }
  throw Error('unclosed ' + marker);
}
const fixture = String.raw`
import Foundation
import Combine
import Network
import Darwin
enum HostServiceError: Error { case invalidAddress }
struct Target: Equatable, Sendable, Codable { var hostId: String; var desktopDeviceId: String { hostId } }
struct Candidate: Sendable { var peerFingerprint: String? }
struct CodexDirectConnection: Sendable { struct Network: Codable, Equatable, Sendable { var address: String; var cidr: String }; var target: Target; var candidates: [Candidate]; var fingerprint: String; var observedEndpoint: String? = nil; var discoveryNetworks: [HostDiscoveryNetwork]? = nil }
typealias HostDiscoveryNetwork = CodexDirectConnection.Network
struct CodexHostDescriptor: Sendable { var target: Target; var fingerprint: String; var name: String; var endpoints: [String]; func candidates() throws -> [Candidate] { [] } }
struct EasyWorkHostDescriptor: Sendable { var target: Target; var publicKey: String; var fingerprint: String; var displayName: String }
struct AgentConnection { var target: Target; var credentialRef: String; var candidates: [Candidate] }
struct Client { var id: String; var codexDirect: CodexDirectConnection?; var online: Bool; var agentConnection: AgentConnection? = nil; var isDemo=false }
struct Saved { var clients: [Client] }
@MainActor final class SessionStore { var saved: Saved; var updates=0; var learned: [String]=[]; var observed: String?; init(_ c: [Client]) { saved=Saved(clients:c) }; func updateCodexDiscovery(_ c: Client, host: CodexHostDescriptor, reachableEndpoint: String, verifiedObservedEndpoint: String? = nil) -> Bool { updates += 1; learned=host.endpoints; observed=verifiedObservedEndpoint; return true } }
struct EasyWorkCredential: Codable { struct Binding: Codable { var target: Target; var desktopPublicKey: String }; var binding: Binding }
enum CredentialVault { static func token(_ s: String) -> String { "" } }
enum L10n { static func tr(_ s: String) -> String { s } }
@MainActor final class EasyWorkKnownHosts { static let shared=EasyWorkKnownHosts(); func offerBonjour(endpoint: String, deviceId: String) {} }
enum NetworkKind { case lan }
enum CodexHostDiscovery { struct Host { var id: String; var name: String; var endpoint: String; var known: Bool; var reachable: Bool; var networkKind: NetworkKind?; var observedConnection: CodexDirectConnection? } }
enum EasyWorkDiscovery { struct Host { var id: String; var name: String; var endpoint: String; var knownClientId: String?; var detail: String?; var deviceId: String?; var observedDescriptor: EasyWorkHostDescriptor? } }
actor Probes {
 static let shared=Probes(); var active=0; var peak=0; var calls:[String]=[]; var mode="normal"
 func reset(_ m:String) { active=0; peak=0; calls=[]; mode=m }
 func read(_ endpoint:String, _ codex:Bool, _ timeout:Double) async throws -> CodexHostDescriptor? {
   precondition(timeout == 2); active += 1; peak=max(peak,active); calls.append(endpoint); let m=mode
   try? await Task.sleep(nanoseconds: m == "cancel" || (m == "partial" && !endpoint.contains(".1:8796")) ? 200_000_000 : 500_000)
   active -= 1
   if codex && (endpoint.contains(".254:") || m == "duplicate" && endpoint.contains(".253:") || m == "cancel" || (m == "partial" && endpoint.contains(".1:"))) {
    return CodexHostDescriptor(target:Target(hostId:"synthetic"),fingerprint:m == "conflict" ? "wrong" : "pin",name:"Synthetic",endpoints:(m == "duplicate" ? (1...32).map { "https://advertised-\($0).local:8796" } : []))
   }; return nil
 }
 func snapshot() -> (Int,[String]) { (peak,calls) }
}
enum CodexDiscoveryAPI { static func read(_ endpoint:String, fingerprint:String?, target:Target?, discover:Bool, timeout:Double) async throws -> CodexHostDescriptor { precondition(fingerprint == nil && target == nil && discover); guard let h = try await Probes.shared.read(endpoint,true,timeout) else { throw HostServiceError.invalidAddress }; return h } }
enum EasyWorkPairing { static func readHost(endpoint:String, timeout:Double) async throws -> EasyWorkHostDescriptor { _ = try await Probes.shared.read(endpoint,false,timeout); throw HostServiceError.invalidAddress } }
/*SCOPE*/
/*NETWORK*/
/*LOCAL*/
/*CLASS*/
@main struct Main {
 @MainActor static func main() async throws {
  let scope=try OfficeDiscoveryScope(" 10.200.42.0/24 "); precondition(scope.addressCount == 254 && scope.address(at: 253) == "10.200.42.254")
  for (cidr, count, first, last) in [("10.0.0.0/8", 16777214, "10.0.0.1", "10.255.255.254"), ("172.16.0.0/12", 1048574, "172.16.0.1", "172.31.255.254"), ("192.168.0.0/16", 65534, "192.168.0.1", "192.168.255.254"), ("10.1.2.0/31", 2, "10.1.2.0", "10.1.2.1"), ("10.1.2.3/32", 1, "10.1.2.3", "10.1.2.3"), ("192.168.0.0/23", 510, "192.168.0.1", "192.168.1.254")] { let s=try OfficeDiscoveryScope(cidr); precondition(s.addressCount == count && s.address(at: 0) == first && s.address(at: count-1) == last) }
  for bad in ["10.1.2.1/24", "8.8.8.0/24", "10.0.0.0/7", "172.16.0.0/11", "192.168.0.0/15", "10.01.2.0/24", "172.32.0.0/24"] { do { _ = try OfficeDiscoveryScope(bad); fatalError("accepted \(bad)") } catch {} }
  let saved=Client(id:"synthetic",codexDirect:CodexDirectConnection(target:Target(hostId:"synthetic"),candidates:[],fingerprint:"pin"),online:false)
  let store=SessionStore([saved]); let discovery=OfficeHostDiscovery(localNetworks: { [] })
  // Starting with no learned/current local scope cannot read a legacy manual
  // default or guess a /24 from an endpoint.
  discovery.start(store); precondition(!discovery.searching && discovery.total == 0 && discovery.scopes.isEmpty)
  func learned(_ cidr:String, address:String="10.200.42.10") { store.saved.clients[0].codexDirect?.discoveryNetworks=[HostDiscoveryNetwork(address:address,cidr:cidr)] }
  learned("10.200.42.0/24")
  await Probes.shared.reset("conflict"); discovery.start(store)
  while discovery.searching { try await Task.sleep(nanoseconds:10_000_000) }
  var state=await Probes.shared.snapshot(); precondition(state.0 <= 4 && state.1.count == 508 && state.1.contains("https://10.200.42.254:8796")); precondition(discovery.codexHosts.isEmpty && store.updates == 0)
  print("PASS /24 all 508 probes; .254 reached; <=4 concurrent; saved pin conflict rejected")
  await Probes.shared.reset("duplicate"); discovery.start(store)
  while discovery.searching { try await Task.sleep(nanoseconds:10_000_000) }
  precondition(discovery.codexHosts.count == 1 && store.saved.clients.count == 1 && store.updates == 2 && store.learned.count == 32 && store.learned.allSatisfy { $0.contains("advertised-") } && store.observed != nil && !store.learned.contains(store.observed!))
  print("PASS matching saved identity deduplicated; observed route passed to update")
  await Probes.shared.reset("partial"); discovery.start(store)
  while discovery.codexHosts.isEmpty { try await Task.sleep(nanoseconds:1_000_000) }
  let beforeRows=discovery.codexHosts.map(\.id), beforeCompleted=discovery.completed, beforeTotal=discovery.total
  precondition(beforeCompleted > 0 && beforeCompleted < beforeTotal)
  discovery.stop(); try await Task.sleep(nanoseconds:300_000_000)
  precondition(!discovery.searching && discovery.codexHosts.map(\.id) == beforeRows && discovery.completed == beforeCompleted && discovery.total == beforeTotal)
  print("PASS stop retains discovered row and progress; late probes do not publish")
  store.saved.clients[0].codexDirect?.discoveryNetworks=nil; discovery.start(store); precondition(discovery.codexHosts.isEmpty && discovery.completed == 0 && discovery.total == 0 && discovery.scopes.isEmpty)
  await Probes.shared.reset("cancel"); learned("10.0.0.0/8"); discovery.start(store); precondition(discovery.total == 33554428); try await Task.sleep(nanoseconds:20_000_000); discovery.stop(); try await Task.sleep(nanoseconds:300_000_000)
  state=await Probes.shared.snapshot(); precondition(!discovery.searching && discovery.codexHosts.isEmpty && discovery.completed == 0 && discovery.total == 33554428 && state.1.count <= 4)
  discovery.deactivate(); print("PASS cancellation blocks late publication and further scheduling")
  precondition(OfficeDiscoveryScope.interface(address:"192.168.6.17",netmask:"255.255.254.0")?.cidr == "192.168.6.0/23")
  for bad in [("192.168.1.2","255.0.255.0"),("100.96.0.2","255.255.255.255"),("127.0.0.1","255.0.0.0"),("192.168.1.255","255.255.255.0"),("10.1.1.1","0.0.0.0")] { precondition(OfficeDiscoveryScope.interface(address:bad.0,netmask:bad.1) == nil) }
  let overlapping = [try OfficeDiscoveryScope("10.200.42.0/24"),try OfficeDiscoveryScope("10.200.42.128/25")]
  precondition(OfficeDiscoveryScope.unique(overlapping).count == 1)
  await Probes.shared.reset("cancel")
  learned("10.0.0.0/8")
  let local=OfficeHostDiscovery(localNetworks: { [.init(address:"192.168.77.1",cidr:"192.168.77.0/30")] })
  local.start(store); try await Task.sleep(nanoseconds:20_000_000); local.stop(); try await Task.sleep(nanoseconds:300_000_000)
  state=await Probes.shared.snapshot(); precondition(state.1.count == 4 && state.1.contains("https://192.168.77.1:8796") && state.1.contains("https://10.0.0.1:8796"))
  let restored=OfficeHostDiscovery(localNetworks: { [] }); restored.start(store); precondition(restored.scopes.map(\.cidr) == ["10.0.0.0/8"]); restored.stop()
  store.saved.clients.removeAll(); restored.start(store); precondition(restored.scopes.isEmpty && restored.total == 0)
  print("PASS actual mask/no guessed prefix; overlapping ranges deduplicated; large scope cannot starve another; saved-scope cold initialization and deletion")
 }
}
`;
const dir = await mkdtemp(join(tmpdir(), 'office-discovery-smoke-'));
try {
  const file = join(dir, 'fixture.swift');
  await writeFile(
    file,
    fixture
      .replace('/*SCOPE*/', block('struct OfficeDiscoveryScope:'))
      .replace('/*NETWORK*/', block('extension CodexDirectConnection.Network {'))
      .replace('/*LOCAL*/', block('enum LocalDiscoveryNetworks {'))
      .replace('/*CLASS*/', block('@MainActor final class OfficeHostDiscovery:')),
  );
  execFileSync(
    'xcrun',
    [
      'swiftc',
      '-swift-version',
      '5',
      '-strict-concurrency=complete',
      '-parse-as-library',
      file,
      '-o',
      join(dir, 'smoke'),
    ],
    {
      stdio: 'inherit',
      env: { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' },
    },
  );
  execFileSync(join(dir, 'smoke'), { stdio: 'inherit' });
  console.log('CodexDiscovery.swift sha256=' + createHash('sha256').update(source).digest('hex'));
} finally {
  await rm(dir, { recursive: true, force: true });
}
