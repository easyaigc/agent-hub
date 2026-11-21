import Foundation
import CryptoKit
import Darwin

// Linked only by the task-specific target. This is not a product profile selector.
enum R2AdmissionPolicy {
  static let bundle = "com.easyai.agenthub.easyworkacceptance.r2task20261112"
  static let task = "android-parallel-20261112-01a1cd5e"
  static let ownerHost = "4bcca14d-211e-41ee-bfef-49698d9ce5ca"
  struct Manifest: Codable {
    var version: Int
    var taskId: String
    var bundleId: String
    var ownerHostId: String
    var caseId: String
    var target: AgentRuntimeTarget
    var fingerprint: String
    var publicKey: String
    var endpoints: [String]
  }
  struct Intent: Codable {
    var method: String
    var operationId: String
    var paramsSHA256: String
  }
  struct Lease: Codable {
    var manifestSHA256: String
    var leaseId: String
    var issuedAt: Double
    var expiresAt: Double
    var mode: String // enroll, read, or explicitly authorized model operations
    var intents: [Intent]
  }
  struct Denied: Error, LocalizedError {
    let reason: String
    var errorDescription: String? { "R2 admission denied: " + reason }
  }
  static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
  static func require(_ value: Bool, _ reason: String) throws {
    if !value { throw Denied(reason: reason) }
  }
  static func endpoint(_ text: String) throws -> String {
    guard let url = URLComponents(string: text), url.scheme == "https", let host = url.host,
      url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
      url.path.isEmpty || url.path == "/" else { throw Denied(reason: "endpoint shape") }
    let parts = host.split(separator: ".", omittingEmptySubsequences: false)
    let bytes = parts.compactMap { UInt8($0) }
    let localIPv4 = parts.count == 4 && bytes.count == 4 && (bytes[0] == 10 || bytes[0] == 127 ||
      bytes[0] == 192 && bytes[1] == 168 || bytes[0] == 172 && (16...31).contains(bytes[1]))
    try require(localIPv4 || host.hasSuffix(".local"), "local endpoint required")
    return "https://" + host.lowercased() + ":" + String(url.port ?? 443)
  }
  static func validate(_ manifest: Manifest, lease: Lease, data: Data, bundle: String?, now: Double) throws {
    try require(bundle == Self.bundle && manifest.bundleId == Self.bundle, "fixed bundle")
    try require(manifest.version == 1 && manifest.taskId == task && manifest.ownerHostId == ownerHost, "task/owner")
    try require(UUID(uuidString: manifest.caseId)?.uuidString.lowercased() == manifest.caseId, "case ID")
    try require(manifest.target.agentId == "easywork" && manifest.target.account != nil &&
      [manifest.target.hostId, manifest.target.workspaceId, manifest.target.runtimeId].allSatisfy { !$0.isEmpty }, "full EasyWork target")
    try require(manifest.fingerprint.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil &&
      manifest.publicKey.count == 43, "peer pin/key")
    try require(!manifest.endpoints.isEmpty && manifest.endpoints.count <= 8, "endpoint count")
    let endpoints = try manifest.endpoints.map(endpoint)
    try require(Set(endpoints).count == endpoints.count, "duplicate endpoints")
    try require(lease.manifestSHA256 == hash(data) && UUID(uuidString: lease.leaseId) != nil, "lease binding")
    try require(lease.issuedAt.isFinite && lease.expiresAt.isFinite && now.isFinite &&
      lease.issuedAt <= now && now < lease.expiresAt && lease.expiresAt - lease.issuedAt <= 3600, "lease time")
    try require(["enroll", "read", "model"].contains(lease.mode), "lease mode")
    try require(lease.mode == "model" || lease.intents.isEmpty, "unexpected write intents")
    try require(lease.intents.allSatisfy { !$0.method.isEmpty && !$0.operationId.isEmpty &&
      $0.paramsSHA256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil }, "write intent")
    try require(Set(lease.intents.map(\.operationId)).count == lease.intents.count, "duplicate operation ID")
  }
  static func connection(_ manifest: Manifest, target: AgentRuntimeTarget,
      candidates: [(transport: String, endpoint: String, pin: String?)]) throws {
    // Full captured payload, including the EasyWork account, is checked here.
    try require(target.matchesCapturedPayload(manifest.target), "foreign target")
    let allowed = try Set(manifest.endpoints.map(endpoint))
    try require(!candidates.isEmpty, "missing candidates")
    for candidate in candidates {
      try require(candidate.transport == "lan-wss" && candidate.pin?.lowercased() == manifest.fingerprint,
        "transport/pin")
      try require(allowed.contains(try endpoint(candidate.endpoint)), "foreign endpoint")
    }
  }
  // Task-only admission journal, before identity/transport work. Never reset or
  // overwrite it on restart, including when a pairing response was lost.
  static func captureEnrollment(root: URL, payload: Data) throws {
    let url = root.appendingPathComponent("r2-enrollment-intent.json")
    try require(!FileManager.default.fileExists(atPath: url.path), "existing enrollment intent; query original offer, do not replay")
    try payload.write(to: url, options: [.withoutOverwriting, .completeFileProtectionUntilFirstUserAuthentication])
    let file = try FileHandle(forWritingTo: url)
    defer { try? file.close() }
    try file.synchronize()
    try file.close()
    let directory = open(root.path, O_RDONLY)
    guard directory >= 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
    defer { close(directory) }
    guard fsync(directory) == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
  }
  static let reads: Set<String> = ["usage/read", "runtime/context", "runtime/summary", "project/list",
    "thread/list", "thread/search", "thread/read", "thread/history", "thread/sync", "thread/subscribe",
    "thread/unsubscribe", "thread/suggestion/read", "thread/fork-options", "thread/fork-status",
    "thread/read-state", "thread/read-state-list", "model/list", "queue/list", "queue/status", "command/status"]
  static func request(_ lease: Lease, method: String, id: String, params: Data) throws {
    if reads.contains(method) { return }
    try require(lease.mode == "model" && lease.intents.contains {
      $0.method == method && $0.operationId == id && $0.paramsSHA256 == hash(params)
    }, "write requires exact immutable intent")
  }
}
