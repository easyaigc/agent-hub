import XCTest
import Foundation
import CoreFoundation
import MobileCore
@testable import R2RealDEVApp

// A separate, fixed module. The original C2/Usage Store suites remain untouched
// in EasyWorkNative and must also run on the controller's final source candidate.
@MainActor final class R2RealDEVAdmissionTests: XCTestCase {
  private func admittedStore() async throws -> SessionStore {
    XCTAssertEqual(Bundle.main.bundleIdentifier, R2AdmissionPolicy.bundle)
    _ = try R2Admission.require()
    for _ in 0..<80 {
      if let store = SessionStore.hostedEasyWorkAcceptanceStore {
        XCTAssertFalse(store.localRecoveryRequired)
        return store
      }
      try await Task.sleep(for: .milliseconds(50))
    }
    throw R2AdmissionPolicy.Denied(reason: "UI-owned store unavailable")
  }
  func testR2FreshLocalEnrollment() async throws {
    let store = try await admittedStore()
    let context = try R2Admission.require()
    XCTAssertEqual(context.lease.mode, "enroll")
    // Never reuse a previous binding/grant, or reset an existing container.
    XCTAssertTrue(store.saved.clients.isEmpty)
    guard store.saved.clients.isEmpty, context.lease.mode == "enroll",
      let descriptor = ProcessInfo.processInfo.environment["R2_FRESH_LAN_DESCRIPTOR"] else {
      throw R2AdmissionPolicy.Denied(reason: "fresh enrollment input required")
    }
    let (binding, candidates) = try await EasyWorkPairing.lan(descriptor, name: "R2 task native acceptance")
    try await store.saveEasyWork(name: "R2 local target", binding: binding, candidates: candidates)
    XCTAssertEqual(store.saved.clients.count, 1)
    try R2Admission.restored(store.saved)
  }
  func testR2CurrentTargetUsageRead() async throws {
    let store = try await admittedStore()
    let context = try R2Admission.require()
    XCTAssertEqual(context.lease.mode, "read")
    guard context.lease.mode == "read", store.saved.clients.count == 1,
      let client = store.saved.clients.first else {
      throw R2AdmissionPolicy.Denied(reason: "one freshly enrolled target required")
    }
    var evidence: [String: Any] = ["caseId": context.manifest.caseId,
      "bundleId": R2AdmissionPolicy.bundle, "newModelInputs": 0,
      "availabilityPassed": false, "sourceVerified": false, "stage": "source-proof-admission"]
    do {
      // Independently frozen Desktop evidence, never learned from this response.
      // Missing proof fails before resolving a provider or issuing a usage read.
      guard let proofData = ProcessInfo.processInfo.environment["R2_USAGE_SOURCE_PROOF_BASE64"]
        .flatMap({ Data(base64Encoded: $0) }), proofData.count <= 16384 else {
        throw R2AdmissionPolicy.Denied(reason: "independent usage source proof required")
      }
      let proof = try JSONDecoder().decode(R2UsageVerification.Proof.self, from: proofData)
      try proof.validate(target: context.manifest.target,
        manifestSHA256: R2AdmissionPolicy.hash(context.data), caseId: context.manifest.caseId)
      evidence["independentProofSHA256"] = R2AdmissionPolicy.hash(proofData)
      evidence["desktopObservationSHA256"] = proof.observationSHA256
      evidence["fullTargetProofVerified"] = true
      evidence["stage"] = "production-provider-read"
      let provider = try AgentClients.resolve(client)
      let data = try await provider.usage(client, target: context.manifest.target)
      evidence.merge(R2UsageVerification.summary(data)) { _, new in new }
      evidence["stage"] = "versioned-shared-projection"
      let projected = try UsageRules.shared.project(json: String(decoding: data, as: UTF8.self),
        targetJson: usageTargetJSON(context.manifest.target), nowMillis: Int64(Date().timeIntervalSince1970 * 1000))
      evidence["kmpProjectionAccepted"] = true
      evidence["projectionSHA256"] = R2AdmissionPolicy.hash(Data(projected.utf8))
      evidence.merge(try R2UsageVerification.evaluate(data, projection: Data(projected.utf8),
        target: context.manifest.target, proof: proof)) { _, new in new }
      guard evidence["availabilityPassed"] as? Bool == true,
        evidence["sourceVerified"] as? Bool == true else {
        throw R2AdmissionPolicy.Denied(reason: "usage unavailable, stale, or source proof not verified")
      }
      evidence["stage"] = "passed-current-available-source-only"
      try saveUsageEvidence(evidence)
    } catch {
      evidence["availabilityPassed"] = false
      evidence["outcome"] = "failed-not-accepted"
      // Fixed categories and hashes only: no token, raw target/account, source
      // labels, amounts, peer address, or arbitrary remote error text in receipts.
      try saveUsageEvidence(evidence)
      throw error
    }
  }
  private func saveUsageEvidence(_ evidence: [String: Any]) throws {
    let data = try JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys])
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("r2-usage-evidence", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    try data.write(to: folder.appendingPathComponent(UUID().uuidString.lowercased() + ".json"),
      options: [.withoutOverwriting, .completeFileProtectionUntilFirstUserAuthentication])
    let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
    attachment.name = "r2-usage-redacted-status.json"; attachment.lifetime = .keepAlways
    add(attachment)
  }
  func testR2ForeignClientRejectedBeforeProvider() async throws {
    let store = try await admittedStore()
    guard var client = store.saved.clients.first, var connection = client.agentConnection else {
      throw R2AdmissionPolicy.Denied(reason: "enroll target before negative check")
    }
    connection.target.runtimeId += "-foreign"
    client.agentConnection = connection
    XCTAssertThrowsError(try AgentClients.resolve(client))
    // The original loaded state and operation records are not changed.
    try R2Admission.restored(store.saved)
  }
}

// BEGIN: R2 usage verification
// This helper checks acceptance/source evidence. The production UsageRules v1
// validator is called by the native method above, never replaced by this helper.
enum R2UsageVerification {
  struct Proof: Codable {
    var version: Int
    var manifestSHA256: String
    var caseId: String
    var target: AgentRuntimeTarget
    var adapterId: String
    var scopeRef: String
    var observationSHA256: String
    func validate(target expected: AgentRuntimeTarget, manifestSHA256: String, caseId: String) throws {
      try R2AdmissionPolicy.require(version == 1 && self.manifestSHA256 == manifestSHA256 &&
        self.caseId == caseId && target.matchesCapturedPayload(expected), "usage proof target/case/manifest")
      try R2AdmissionPolicy.require(!adapterId.isEmpty && adapterId.utf8.count <= 128 &&
        !scopeRef.isEmpty && scopeRef.utf8.count <= 128 &&
        observationSHA256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
        "independent Desktop usage source observation required")
    }
  }
  static func object(_ data: Data) throws -> [String: Any] {
    guard data.count <= 262144, let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw R2AdmissionPolicy.Denied(reason: "usage object")
    }
    return value
  }
  private static func digest(_ value: Any) throws -> String {
    R2AdmissionPolicy.hash(try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]))
  }
  static func summary(_ data: Data) -> [String: Any] {
    var result: [String: Any] = ["rawResponseSHA256": R2AdmissionPolicy.hash(data), "rawByteCount": data.count]
    guard let raw = try? object(data) else { result["rawStatus"] = "invalid-object"; return result }
    let status = raw["status"] as? String
    result["rawStatus"] = ["ok", "unavailable"].contains(status ?? "") ? status : "invalid-status"
    if let number = raw["schemaVersion"] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number == 1 {
      result["schemaVersion"] = 1
    } else { result["schemaVersion"] = "unsupported-or-invalid" }
    if let target = raw["target"] as? [String: Any] { result["responseTargetSHA256"] = try? digest(target) }
    if let requestId = raw["requestId"] as? String { result["requestIdSHA256"] = R2AdmissionPolicy.hash(Data(requestId.utf8)) }
    let reasons = ["not-returned", "unsupported", "not-authenticated", "forbidden", "timeout", "offline",
      "upstream-error", "invalid-data", "unsupported-version"]
    if let reason = raw["reason"] as? String { result["reason"] = reasons.contains(reason) ? reason : "invalid-reason" }
    return result
  }
  static func evaluate(_ data: Data, projection: Data, target: AgentRuntimeTarget, proof: Proof) throws -> [String: Any] {
    let raw = try object(data), projected = try object(projection)
    var result = summary(data)
    result["availabilityPassed"] = false; result["sourceVerified"] = false
    result["classification"] = "invalid-response"
    guard result["schemaVersion"] as? Int == 1,
      let actualTarget = raw["target"] as? [String: Any] else { return result }
    let expected: [String: Any] = ["agentId": target.agentId, "hostId": target.hostId,
      "workspaceId": target.workspaceId, "runtimeId": target.runtimeId]
    result["targetFieldsMatch"] = Dictionary(uniqueKeysWithValues: expected.map {
      ($0.key, (actualTarget[$0.key] as? String) == ($0.value as? String))
    })
    guard try digest(actualTarget) == digest(expected), proof.target.matchesCapturedPayload(target) else {
      result["classification"] = "foreign-target"; return result
    }
    result["versionAndTargetVerified"] = true
    if raw["status"] as? String == "unavailable", projected["status"] as? String == "unavailable" {
      result["classification"] = "unavailable-not-source-verified"
      return result // Legal protocol response; acceptance still fails, never skip/pass.
    }
    guard raw["status"] as? String == "ok", projected["status"] as? String == "ok",
      let snapshot = raw["snapshot"] as? [String: Any], let source = snapshot["source"] as? [String: Any],
      let projectedSource = projected["source"] as? [String: Any] else { return result }
    result["sourceSHA256"] = try digest(source)
    if let adapter = source["adapterId"] as? String { result["adapterIdSHA256"] = R2AdmissionPolicy.hash(Data(adapter.utf8)) }
    if let scope = source["scopeRef"] as? String { result["scopeRefSHA256"] = R2AdmissionPolicy.hash(Data(scope.utf8)) }
    result["coverage"] = ["complete", "partial"].contains(snapshot["coverage"] as? String ?? "") ? snapshot["coverage"] : "invalid"
    result["partial"] = projected["partial"] as? Bool
    result["stale"] = projected["stale"] as? Bool
    guard source["adapterId"] as? String == proof.adapterId, source["scopeRef"] as? String == proof.scopeRef,
      try digest(source) == digest(projectedSource) else {
      result["classification"] = "source-proof-mismatch"; return result
    }
    result["sourceVerified"] = true
    guard projected["stale"] as? Bool == false else { result["classification"] = "not-current"; return result }
    let metrics = projected["metrics"] as? [[String: Any]] ?? []
    let usable = metrics.filter { metric in
      let kind = metric["kind"] as? String, state = metric["state"] as? String
      return kind == "balance" && ["amount", "unlimited"].contains(state ?? "") ||
        ["window-limit", "capacity-limit"].contains(kind ?? "") && ["measured", "unlimited"].contains(state ?? "")
    }
    result["metricCount"] = metrics.count; result["usableMetricCount"] = usable.count
    guard !usable.isEmpty else { result["classification"] = "no-available-metric"; return result }
    result["availabilityPassed"] = true
    result["classification"] = projected["partial"] as? Bool == true ? "available-partial-source-verified" : "available-source-verified"
    return result
  }
}
// END: R2 usage verification
