// Native scheduler boundary: the marked declaration is read from production.
// Network/platform/store doubles deliberately do not stand in for native UI or real HTTPS acceptance.
import Foundation
import Combine
import Darwin
struct AgentRuntimeTarget: Equatable, Sendable { var hostId: String; var agentId = "codex" }
struct AgentConnectionCandidate: Sendable { enum NetworkKind: Sendable { case lan, vpn }; var endpoint: String; var peerFingerprint: String? }
struct Connection: Sendable { var target: AgentRuntimeTarget; var candidates: [AgentConnectionCandidate]; var fingerprint: String }
struct Client: Sendable { var id = "fixture-host"; var name = "Fixture"; var endpoint: String? = "https://fixture.local:11111"; var codexDirect: Connection? = Connection(target: .init(hostId: "fixture-host"), candidates: [.init(endpoint: "https://fixture.local:11111", peerFingerprint: "fixture-pin")], fingerprint: "fixture-pin"); var isDemo = false; var online = false; var agentConnection: String? = nil }
struct Workspace { var readTarget: AgentRuntimeTarget? }
struct Saved { var clients = [Client()]; var workspaces: [String: Workspace] = [:] }
struct CodexHostDescriptor: Sendable { var target: AgentRuntimeTarget; var fingerprint: String }
protocol NetServiceBrowserDelegate: AnyObject {}
protocol NetServiceDelegate: AnyObject {}
final class NetService: NSObject { var name = "New fixture service"; var hostName: String? = "fixture.local."; var port = 22222; weak var delegate: (any NetServiceDelegate)?; func startMonitoring() {}; func resolve(withTimeout: Double) {}; func stop() {}; func txtRecordData() -> Data? { Data() }; static func dictionary(fromTXTRecord: Data) -> [String: Data] { ["hostId": Data("fixture-host".utf8)] } }
final class NetServiceBrowser { weak var delegate: (any NetServiceBrowserDelegate)?; func schedule(in loop: RunLoop, forMode mode: RunLoop.Mode) {}; func searchForServices(ofType: String, inDomain: String) {}; func stop() {} }
final class NWPathMonitor { var pathUpdateHandler: (@Sendable (Int) -> Void)?; func start(queue: DispatchQueue) {}; func cancel() {} }
final class CodexHostRoutes: @unchecked Sendable { static let shared = CodexHostRoutes(); func register(_ client: Client) {} }
enum CredentialVault { static func token(_ id: String) -> String { "unused-fixture-secret" } }
enum VPNPeerSnapshot { static func orderedEndpoints(_ values: [String]) -> [String] { values } }
@MainActor final class VPNHostDiscovery { static let shared = VPNHostDiscovery(); func offerBonjour(endpoint: String, hostId: String) {} }
actor ProbeFixture {
 static let shared = ProbeFixture(); var oldReads = 0; var newReads = 0
 var oldWait: CheckedContinuation<Void, Never>?; var refreshWait: CheckedContinuation<Void, Never>?
 var oldReleased = false; var closed = false
 func read(_ endpoint: String, pin: String?, target: AgentRuntimeTarget?, token: String) async throws -> CodexHostDescriptor {
  guard pin == "fixture-pin", target?.hostId == "fixture-host", token.isEmpty else { fatalError("Probe scope, certificate and no-Bearer boundary changed") }
  if endpoint.hasSuffix(":11111") { oldReads += 1; if !oldReleased { await withCheckedContinuation { oldWait = $0 } }; throw NSError(domain: "closed-old-route", code: 1) }
  guard endpoint == "https://fixture.local:22222" else { fatalError("Unexpected probe route") }
  newReads += 1; return CodexHostDescriptor(target: .init(hostId: "fixture-host"), fingerprint: "fixture-pin")
 }
 func waitRefresh() async { if !closed { await withCheckedContinuation { refreshWait = $0 } } }
 func reset() { oldReads = 0; newReads = 0; oldReleased = false; closed = false; oldWait = nil; refreshWait = nil }
 func releaseOld() { oldReleased = true; oldWait?.resume(); oldWait = nil }
 func close() { closed = true; releaseOld(); refreshWait?.resume(); refreshWait = nil }
}
enum CodexDiscoveryAPI { static func read(_ endpoint: String, fingerprint: String?, target: AgentRuntimeTarget?, token: String = "") async throws -> CodexHostDescriptor { try await ProbeFixture.shared.read(endpoint, pin: fingerprint, target: target, token: token) } }
@MainActor final class SessionStore {
 var saved = Saved(); var client: Client? { saved.clients.first }
 func refresh() async { await ProbeFixture.shared.waitRefresh() }
 func updateCodexDiscovery(_ previous: Client, host: CodexHostDescriptor, reachableEndpoint: String) -> Bool {
  guard previous.endpoint == saved.clients[0].endpoint, host.target == previous.codexDirect?.target, host.fingerprint == previous.codexDirect?.fingerprint else { return false }
  saved.clients[0].endpoint = reachableEndpoint; return true
 }
}
/*__DISCOVERY_CLASS__*/
@main struct Verify {
 static func scenario(stopped: Bool) async -> Bool {
  await ProbeFixture.shared.reset()
  let store = await MainActor.run { SessionStore() }; let discovery = await MainActor.run { CodexHostDiscovery() }
  let run = Task { await discovery.run(store) }
  let start = Date(); while await ProbeFixture.shared.oldReads == 0 && Date().timeIntervalSince(start) < 2 { try? await Task.sleep(for: .milliseconds(10)) }
  guard await ProbeFixture.shared.oldReads > 0 else { fatalError("Initial production scan was not suspended") }
  await MainActor.run { for _ in 0..<3 { discovery.netServiceDidResolveAddress(NetService()) } }
  try? await Task.sleep(for: .milliseconds(50))
  if stopped { await MainActor.run { discovery.stop() } }
  await ProbeFixture.shared.releaseOld()
  let deadline = Date().addingTimeInterval(stopped ? 0.1 : 1)
  while await ProbeFixture.shared.newReads == 0 && Date() < deadline { try? await Task.sleep(for: .milliseconds(10)) }
  let reads = await ProbeFixture.shared.newReads
  let endpoint = await MainActor.run { discovery.hosts.first(where: { $0.id == "fixture-host" })?.endpoint ?? "missing" }
  let passed = stopped ? reads == 0 && endpoint != "https://fixture.local:22222" : reads == 1 && endpoint == "https://fixture.local:22222"
  run.cancel(); await ProbeFixture.shared.close(); await run.value
  let label = stopped ? "Stopped discovery discards queued hints and late probes" : "Bonjour burst during suspended old probe is coalesced before slow workspace refresh"
  print("{\"test\":\"\(label)\",\"newProbeReads\":\(reads),\"rowEndpoint\":\"\(endpoint)\",\"passed\":\(passed)}")
  return passed
 }
 static func main() async {
  let first = await scenario(stopped: false)
  let stopped = await scenario(stopped: true)
  await ProbeFixture.shared.reset()
  let store = await MainActor.run { () -> SessionStore in
    let value = SessionStore()
    value.saved.clients[0].codexDirect?.candidates.append(.init(endpoint: "https://fixture.local:22222", peerFingerprint: "fixture-pin"))
    return value
  }
  let discovery = await MainActor.run { CodexHostDiscovery() }
  let run = Task { await discovery.run(store) }
  let deadline = Date().addingTimeInterval(1)
  var early = false
  while Date() < deadline {
    early = await MainActor.run { discovery.hosts.first?.endpoint == "https://fixture.local:22222" }
    if early { break }
    try? await Task.sleep(for: .milliseconds(10))
  }
  // The old probe is still blocked: success must be published before it ends.
  let pending = await ProbeFixture.shared.oldReleased == false
  run.cancel(); await ProbeFixture.shared.close(); await run.value
  print("{\"test\":\"Authenticated alternate route is applied before an old route timeout\",\"passed\":\(early && pending)}")
  if !first || !stopped || !early || !pending { exit(1) }
 }
}
