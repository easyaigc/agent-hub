import XCTest
import Foundation
import CryptoKit
@testable import CodexManage_com_easyai_agenthub_easyworkacceptance

private struct OwnedRefreshCheckpoint: Codable {
  let caseID: String
  let origin: String
  let ref: String
  let claimKey: String
  let binding: EasyWorkBinding
  let operationID: String
  let tokenSHA256: String
  let bodySHA256: String
  let recordSHA256: String
  let identitySHA256: String
  let preparedPID: Int32
}
private struct OwnedRefreshDelivery: Codable {
  let operationID: String
  let sealedSHA256: String
  let accessExpiresAt: String
  let refreshExpiresAt: String
  let heldPID: Int32
}

// A new actor owns each controlled fault; no registry or shared session changes.
private actor OwnedRefreshHTTP: EasyWorkAuthorizationHTTPTransport {
  enum Mode: Sendable {
    case transient(Int)
    case holdVerifiedDelivery
    case observePinnedRecovery(endpoint: String, fingerprint: String)
  }
  let checkpoint: OwnedRefreshCheckpoint
  let mode: Mode
  private var count = 0
  private var delivery: OwnedRefreshDelivery?
  init(_ checkpoint: OwnedRefreshCheckpoint, mode: Mode) {
    self.checkpoint = checkpoint; self.mode = mode
  }
  func witness() -> (Int, OwnedRefreshDelivery?) { (count, delivery) }
  func response(for request: URLRequest, trust: EasyWorkAuthorizationHTTPTrust) async throws -> EasyWorkAuthorizationHTTPResponse {
    switch (mode, trust) {
    case (.observePinnedRecovery(let endpoint, let pin), .paired(let actualEndpoint, let actualPin)):
      guard endpoint == actualEndpoint, pin == actualPin,
        var expected = URLComponents(string: try EasyWorkPairing.localEndpoint(endpoint).absoluteString) else { throw HostServiceError.wrongHost }
      expected.path = "/device-authorization/refresh"
      expected.queryItems = [URLQueryItem(name: "bindingId", value: checkpoint.binding.bindingId)]
      guard request.url == expected.url else { throw HostServiceError.wrongHost }
    case (.transient, .issuer), (.holdVerifiedDelivery, .issuer):
      guard request.url?.absoluteString == checkpoint.origin + "/v2/remote-access/device-grants/refresh" else { throw HostServiceError.wrongHost }
    default: throw HostServiceError.wrongHost
    }
    guard request.httpMethod == "POST", let body = request.httpBody,
      HostService.hash(body) == checkpoint.bodySHA256,
      try easyWorkObject(body)["operationId"] as? String == checkpoint.operationID,
      let authorization = request.value(forHTTPHeaderField: "Authorization"),
      authorization.hasPrefix("DPoP "),
      HostService.hash(Data(authorization.dropFirst(5).utf8)) == checkpoint.tokenSHA256,
      request.value(forHTTPHeaderField: "DPoP") != nil, count == 0 else { throw HostServiceError.wrongHost }
    count += 1
    switch mode {
    case .transient(let status):
      guard [408, 429, 503].contains(status) else { throw HostServiceError.invalidResponse }
      return .init(status: status, body: try easyWorkJSON(["error": ["code": "owned_controlled_transient"]]))
    case .holdVerifiedDelivery, .observePinnedRecovery:
      let response = try await EasyWorkNativeAuthorizationHTTPTransport().response(for: request, trust: trust)
      guard response.status == 200 else { return response }
      let value = try easyWorkObject(response.body)
      guard value["protocol"] as? String == "EWDAP/1",
        value["grantId"] as? String == checkpoint.binding.deviceAuthorization?.grantId,
        let sealed = value["sealedDelivery"] as? String else { throw HostServiceError.invalidResponse }
      let identity = try EasyWorkIdentity.load(), signer = try EasyWorkDPoP.load(identity)
      let clear = try EasyWorkDeviceDelivery.open(identity: identity, delivery: sealed,
        expectedDeliveryId: checkpoint.operationID)
      let grant = try JSONDecoder().decode(EasyWorkDeviceGrant.self, from: easyWorkJSON(clear))
      try grant.verify(identity, signer: signer, expected: checkpoint.binding)
      guard grant.deliveryId == checkpoint.operationID,
        HostService.hash(Data(grant.refreshToken.utf8)) != checkpoint.tokenSHA256 else { throw HostServiceError.invalidResponse }
      delivery = .init(operationID: checkpoint.operationID,
        sealedSHA256: HostService.hash(Data(sealed.utf8)), accessExpiresAt: grant.accessExpiresAt,
        refreshExpiresAt: grant.refreshExpiresAt, heldPID: getpid())
      if case .observePinnedRecovery = mode { return response }
      // The server really completed and its authenticated response was verified.
      // Only local delivery is lost. Do not hand its new token to the actor.
      throw URLError(.networkConnectionLost)
    }
  }
}

@MainActor final class EasyWorkAuthorizationRecoveryNativeTests: XCTestCase {
  private struct Context {
    let id: String
    let config: [String: Any]
    let folder: URL
    func url(_ name: String) -> URL { folder.appendingPathComponent(name) }
  }
  private func context(_ phase: String, inspectOnly: Bool = false) throws -> Context {
    #if !targetEnvironment(simulator)
    throw XCTSkip("Owned simulator only")
    #else
    let env = ProcessInfo.processInfo.environment
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing"),
      let id = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: id)?.uuidString.lowercased() == id,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Named owned authorization case required") }
    let config = try easyWorkObject(Data(raw.utf8))
    guard config["caseId"] as? String == id, config["phase"] as? String == phase,
      config["simulatorId"] as? String == env["SIMULATOR_UDID"],
      config["newRootInputsAuthorized"] as? Int == 0,
      config["newGrantsAuthorized"] as? Int == (phase == "prepare" ? 1 : 0),
      config["issuer"] as? String == "http://127.0.0.1:56245" else {
      let issuer = config["issuer"] as? String
      let safeIssuer = issuer.flatMap { value in
        value.range(of: #"^http://(?:127\.0\.0\.1|localhost|\[::1\]):[0-9]+/?$"#, options: .regularExpression) != nil ? value : nil
      }
      try report(["checks": ["caseMatches": config["caseId"] as? String == id,
        "phaseMatches": config["phase"] as? String == phase,
        "simulatorMatches": config["simulatorId"] as? String == env["SIMULATOR_UDID"],
        "zeroRootInputs": config["newRootInputsAuthorized"] as? Int == 0,
        "grantScopeMatches": config["newGrantsAuthorized"] as? Int == (phase == "prepare" ? 1 : 0),
        "issuerMatches": config["issuer"] as? String == "http://127.0.0.1:56245"],
        "observedSimulatorId": env["SIMULATOR_UDID"] ?? "absent",
        "issuerValueType": config["issuer"].map { String(reflecting: type(of: $0)) } ?? "missing",
        "safeObservedIssuer": safeIssuer ?? "not-a-plain-loopback-origin",
        "issuerUtf8Count": issuer?.utf8.count ?? -1,
        "issuerValueSHA256": issuer.map { HostService.hash(Data($0.utf8)) } ?? "not-string",
        "newGrants": 0,
        "newRootInputs": 0], "f07-owned-context-admission-failure")
      throw HostServiceError.wrongHost
    }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-owned-refresh-" + id, isDirectory: true)
    if !inspectOnly {
      try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
        attributes: [.posixPermissions: 0o700])
    }
    return .init(id: id, config: config, folder: folder)
    #endif
  }
  func testInspectOwnedConfigurationWithoutPairing() async throws {
    let c = try context("prepare", inspectOnly: true)
    // This task configuration contains only non-secret target and case fields;
    // compare its complete decoded value locally with the frozen input.
    try report(["caseId": c.id, "configurationMatches": true, "configuration": c.config,
      "identityAlreadyExists": try CredentialVault.existingToken("easywork-device-identity") != nil,
      "newGrants": 0, "newRootInputs": 0, "transportCreated": false], "f07-owned-admission-inspection")
  }
  private func write(_ value: [String: Any], _ url: URL) throws {
    try easyWorkJSON(value).write(to: url, options: .withoutOverwriting)
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
  }
  private func seal<T: Encodable>(_ value: T, _ url: URL) throws {
    try AgentCache.seal(value).write(to: url, options: .withoutOverwriting)
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
  }
  private func open<T: Decodable>(_ type: T.Type, _ url: URL) throws -> T {
    try AgentCache.open(Data(contentsOf: url), as: type)
  }
  private func report(_ value: [String: Any], _ name: String) throws {
    let a = XCTAttachment(data: try easyWorkJSON(value), uniformTypeIdentifier: "public.json")
    a.name = name; a.lifetime = .keepAlways; add(a)
  }
  private func existingRecord(_ ref: String) throws -> (String, [String: Any], EasyWorkDeviceGrant) {
    let raw = try XCTUnwrap(CredentialVault.existingToken(ref))
    let object = try easyWorkObject(Data(raw.utf8))
    let grant = try JSONDecoder().decode(EasyWorkDeviceGrant.self,
      from: easyWorkJSON(try XCTUnwrap(object["grant"])))
    return (raw, object, grant)
  }
  private func checkIdentity(_ checkpoint: OwnedRefreshCheckpoint) throws {
    let raw = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    guard HostService.hash(Data(raw.utf8)) == checkpoint.identitySHA256 else { throw HostServiceError.wrongHost }
  }
  private func headers(_ auth: EasyWorkDeviceAuthorization, _ checkpoint: OwnedRefreshCheckpoint,
    binding: EasyWorkBinding? = nil, candidate: AgentConnectionCandidate? = nil) async throws -> [String: String] {
    try await auth.headers(ref: checkpoint.ref, binding: binding ?? checkpoint.binding,
      method: "GET", url: try XCTUnwrap(URL(string: checkpoint.origin + "/v2/remote-access/bindings")),
      lanCandidate: candidate)
  }
  func testPrepareOwnedIntentAndTransientFailures() async throws {
    try await prepareOwnedGrant(resumingReady: false)
  }
  func testResumeOwnedReadyAndTransientFailures() async throws {
    try await prepareOwnedGrant(resumingReady: true)
  }
  private func prepareOwnedGrant(resumingReady: Bool) async throws {
    let c = try context("prepare")
    let priorIdentity = try CredentialVault.existingToken("easywork-device-identity")
    guard !FileManager.default.fileExists(atPath: c.url("checkpoint.sealed").path),
      !FileManager.default.fileExists(atPath: c.url("offer.json").path),
      !FileManager.default.fileExists(atPath: c.url("pending.json").path) else { throw HostServiceError.wrongHost }
    if resumingReady {
      guard priorIdentity != nil,
        FileManager.default.fileExists(atPath: c.url("ready.json").path) else { throw HostServiceError.wrongHost }
    } else {
      guard priorIdentity == nil,
        !FileManager.default.fileExists(atPath: c.url("ready.json").path) else { throw HostServiceError.wrongHost }
    }
    let identity = try EasyWorkIdentity.load(), signer = try EasyWorkDPoP.load(identity)
    guard identity.deviceId != c.config["protectedMobileDeviceId"] as? String else { throw HostServiceError.wrongHost }
    let ready: [String: Any] = ["caseId": c.id, "deviceId": identity.deviceId,
      "publicKeySHA256": HostService.hash(Data(identity.publicKey.utf8)), "authThumbprint": try signer.thumbprint]
    if resumingReady {
      guard try easyWorkJSON(easyWorkObject(Data(contentsOf: c.url("ready.json")))) == easyWorkJSON(ready),
        try CredentialVault.existingToken("easywork-device-identity") == priorIdentity else { throw HostServiceError.wrongHost }
    } else { try write(ready, c.url("ready.json")) }
    let deadline = Date().addingTimeInterval(240)
    while !FileManager.default.fileExists(atPath: c.url("offer.json").path), Date() < deadline {
      try await Task.sleep(for: .milliseconds(200))
    }
    let offerValue = try easyWorkObject(Data(contentsOf: c.url("offer.json")))
    guard offerValue["caseId"] as? String == c.id else { throw HostServiceError.wrongHost }
    let offer = try EasyWorkDeviceOffer.parse(try XCTUnwrap(offerValue["pairingUri"] as? String))
    guard offer.origin == c.config["issuer"] as? String else { throw HostServiceError.wrongHost }
    let claimKey = "easywork-device-claim:" + HostService.hash(Data((offer.origin + "\n" + (try signer.thumbprint) + "\n" + offer.code).utf8))
    let (binding, ref) = try await EasyWorkDeviceAuthorization.shared.pair(offer,
      deviceName: "Agent Hub owned F07 " + c.id.prefix(8)) { status in
        if !FileManager.default.fileExists(atPath: c.url("pending.json").path) {
          try? self.write(["caseId": c.id, "deviceId": identity.deviceId,
            "verificationStatusSHA256": HostService.hash(Data(status.utf8))], c.url("pending.json"))
        }
      }
    let expected = try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(try XCTUnwrap(c.config["target"])))
    guard binding.target == expected, binding.mobileDeviceId == identity.deviceId,
      binding.bindingId != c.config["protectedBindingId"] as? String else { throw HostServiceError.wrongHost }
    let (_, object, grant) = try existingRecord(ref)
    try grant.verify(identity, signer: signer, expected: binding)
    guard object["refreshOperation"] == nil,
      (object["refreshReceipts"] as? [[String: Any]] ?? []).isEmpty else { throw HostServiceError.invalidResponse }
    let operation = UUID().uuidString.lowercased(), body = try easyWorkJSON(["operationId": operation])
    var prepared = object; prepared["refreshOperation"] = ["operationId": operation, "token": grant.refreshToken]
    try CredentialVault.set(String(decoding: easyWorkJSON(prepared), as: UTF8.self), id: ref)
    let record = try existingRecord(ref).0
    let checkpoint = OwnedRefreshCheckpoint(caseID: c.id, origin: offer.origin, ref: ref,
      claimKey: claimKey, binding: binding, operationID: operation,
      tokenSHA256: HostService.hash(Data(grant.refreshToken.utf8)), bodySHA256: HostService.hash(body),
      recordSHA256: HostService.hash(Data(record.utf8)),
      identitySHA256: HostService.hash(Data(try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity")).utf8)), preparedPID: getpid())
    try seal(checkpoint, c.url("checkpoint.sealed"))
    try write(["caseId": c.id, "bindingId": binding.bindingId, "grantId": grant.grantId,
      "operationId": operation, "requestSHA256": checkpoint.bodySHA256], c.url("prepared.json"))
    let wrongGate = OwnedRefreshHTTP(checkpoint, mode: .transient(408))
    var wrong = binding; wrong.target.workspaceId = "workspace-wrong-" + c.id
    do { _ = try await headers(EasyWorkDeviceAuthorization(transport: wrongGate), checkpoint, binding: wrong)
      XCTFail("Wrong workspace accepted"); throw HostServiceError.invalidResponse
    } catch { guard await wrongGate.witness().0 == 0 else { throw HostServiceError.invalidResponse } }
    for status in [408, 429, 503] {
      let gate = OwnedRefreshHTTP(checkpoint, mode: .transient(status))
      do { _ = try await headers(EasyWorkDeviceAuthorization(transport: gate), checkpoint)
        XCTFail("Controlled transient unexpectedly completed"); throw HostServiceError.invalidResponse
      } catch let failure as EasyWorkServiceAvailabilityFailure {
        XCTAssertEqual(failure.status, status); XCTAssertFalse(failure.localizedDescription.isEmpty)
      }
      guard await gate.witness().0 == 1, try existingRecord(ref).0 == record else { throw HostServiceError.invalidResponse }
    }
    try checkIdentity(checkpoint)
    try report(["caseId": c.id, "bindingId": binding.bindingId, "grantId": grant.grantId,
      "operationId": operation, "wrongWorkspaceBeforeHTTP": true,
      "controlledHTTPStatuses": [408, 429, 503], "preparedIntentAndGrantUnchanged": true,
      "actualIssuerRefreshes": 0, "newRootInputs": 0], "f07-owned-prepared-errors")
  }
  func testColdIssueOnceAndLoseVerifiedDelivery() async throws {
    let c = try context("hold"), checkpoint = try open(OwnedRefreshCheckpoint.self, c.url("checkpoint.sealed"))
    try checkIdentity(checkpoint)
    guard checkpoint.caseID == c.id, checkpoint.preparedPID != getpid(),
      HostService.hash(Data(try existingRecord(checkpoint.ref).0.utf8)) == checkpoint.recordSHA256,
      !FileManager.default.fileExists(atPath: c.url("delivery.sealed").path) else { throw HostServiceError.wrongHost }
    let gate = OwnedRefreshHTTP(checkpoint, mode: .holdVerifiedDelivery)
    do { _ = try await headers(EasyWorkDeviceAuthorization(transport: gate), checkpoint)
      XCTFail("Verified response was not withheld"); throw HostServiceError.invalidResponse
    } catch let failure as URLError { XCTAssertEqual(failure.code, .networkConnectionLost) }
    let (attempts, delivery) = await gate.witness()
    guard attempts == 1, let delivery,
      HostService.hash(Data(try existingRecord(checkpoint.ref).0.utf8)) == checkpoint.recordSHA256 else { throw HostServiceError.invalidResponse }
    try seal(delivery, c.url("delivery.sealed"))
    try report(["caseId": c.id, "operationId": checkpoint.operationID,
      "sealedResponseSHA256": delivery.sealedSHA256, "verifiedOriginalDeliveryHeld": true,
      "originalIntentAndOldGrantUnchanged": true, "actualIssuerRefreshPOSTs": 1,
      "newRootInputs": 0], "f07-original-refresh-unknown")
  }
  func testColdRecoverSameIntentViaPinnedDesktop() async throws {
    let c = try context("recover"), checkpoint = try open(OwnedRefreshCheckpoint.self, c.url("checkpoint.sealed"))
    let delivery = try open(OwnedRefreshDelivery.self, c.url("delivery.sealed"))
    try checkIdentity(checkpoint)
    guard checkpoint.caseID == c.id, delivery.heldPID != getpid(),
      delivery.operationID == checkpoint.operationID,
      HostService.hash(Data(try existingRecord(checkpoint.ref).0.utf8)) == checkpoint.recordSHA256 else { throw HostServiceError.wrongHost }
    let candidate = AgentConnectionCandidate(id: "owned-f07-lan", transportId: "lan-wss",
      endpoint: try XCTUnwrap(c.config["lanEndpoint"] as? String),
      peerFingerprint: try XCTUnwrap(c.config["peerFingerprint"] as? String))
    let host = try await EasyWorkPairing.readHost(endpoint: candidate.endpoint,
      fingerprint: try XCTUnwrap(candidate.peerFingerprint), target: checkpoint.binding.target,
      publicKey: checkpoint.binding.desktopPublicKey, timeout: 5)
    guard host.deviceAuthorizationRefresh == "lan-forward-v1" else { throw HostServiceError.invalidResponse }
    let gate = OwnedRefreshHTTP(checkpoint, mode: .observePinnedRecovery(
      endpoint: candidate.endpoint, fingerprint: try XCTUnwrap(candidate.peerFingerprint)))
    var currentAccessReturned = false
    do {
      _ = try await headers(EasyWorkDeviceAuthorization(transport: gate), checkpoint, candidate: candidate)
      currentAccessReturned = true
    } catch {
      // An old, genuine completion may have an expired access token. Production
      // commits that original receipt before requesting a later refresh. This
      // case must stop at the same completion, never create a new refresh.
      let (_, record, completed) = try existingRecord(checkpoint.ref)
      guard error is RecoverableConnectionReadFailure, record["refreshOperation"] == nil,
        try EasyWorkDeviceGrant.date(completed.accessExpiresAt) <= Date(),
        try EasyWorkDeviceGrant.date(completed.refreshExpiresAt) > Date() else { throw error }
    }
    let (attempts, observed) = await gate.witness()
    guard attempts == 1, observed?.sealedSHA256 == delivery.sealedSHA256 else { throw HostServiceError.invalidResponse }
    let (_, object, grant) = try existingRecord(checkpoint.ref)
    let identity = try EasyWorkIdentity.load(); try grant.verify(identity, signer: EasyWorkDPoP.load(identity), expected: checkpoint.binding)
    let receipts = try XCTUnwrap(object["refreshReceipts"] as? [[String: Any]])
    guard object["refreshOperation"] == nil, receipts.count == 1,
      receipts[0]["operationId"] as? String == checkpoint.operationID,
      receipts[0]["requestSha256"] as? String == checkpoint.bodySHA256,
      receipts[0]["responseSha256"] as? String == delivery.sealedSHA256,
      receipts[0]["accessExpiresAt"] as? String == delivery.accessExpiresAt,
      receipts[0]["refreshExpiresAt"] as? String == delivery.refreshExpiresAt else { throw HostServiceError.invalidResponse }
    try report(["caseId": c.id, "operationId": checkpoint.operationID,
      "bindingId": checkpoint.binding.bindingId, "grantId": grant.grantId,
      "sameRequestAndSealedOriginalResponse": true, "savedOriginalReceipts": 1,
      "actualPinnedDesktopRefreshPOSTs": attempts, "currentAccessReturned": currentAccessReturned,
      "intentClearedAfterVerifiedCompletion": true, "freshProcess": true,
      "newRootInputs": 0], "f07-original-refresh-cold-recovered")
    try write(["caseId": c.id, "bindingId": checkpoint.binding.bindingId,
      "grantId": grant.grantId, "operationId": checkpoint.operationID], c.url("recovered.json"))
  }

  func testConfirmOwnedRevocationAndRemoveOwnedSecrets() async throws {
    let c = try context("cleanup"), checkpoint = try open(OwnedRefreshCheckpoint.self, c.url("checkpoint.sealed"))
    let proof = try easyWorkObject(Data(contentsOf: c.url("revoked.json")))
    guard checkpoint.caseID == c.id, proof["caseId"] as? String == c.id,
      proof["bindingId"] as? String == checkpoint.binding.bindingId,
      proof["grantId"] as? String == checkpoint.binding.deviceAuthorization?.grantId else { throw HostServiceError.wrongHost }
    try checkIdentity(checkpoint)
    let (_, _, grant) = try existingRecord(checkpoint.ref), identity = try EasyWorkIdentity.load()
    let url = try XCTUnwrap(URL(string: checkpoint.origin + "/v2/remote-access/bindings"))
    var request = URLRequest(url: url); request.httpMethod = "GET"; request.timeoutInterval = 20
    request.setValue("DPoP " + grant.accessToken, forHTTPHeaderField: "Authorization")
    request.setValue(try EasyWorkDPoP.load(identity).proof(method: "GET", url: url,
      accessToken: grant.accessToken), forHTTPHeaderField: "DPoP")
    let response = try await EasyWorkNativeAuthorizationHTTPTransport().response(for: request, trust: .issuer)
    guard [401, 403].contains(response.status) else { throw HostServiceError.invalidResponse }
    try CredentialVault.set("", id: checkpoint.ref)
    try CredentialVault.set("", id: checkpoint.claimKey)
    try report(["caseId": c.id, "bindingId": checkpoint.binding.bindingId,
      "grantId": grant.grantId, "revokedSignedReadStatus": response.status,
      "ownedGrantAndClaimCleared": true, "newRootInputs": 0], "f07-owned-revoked-cleanup")
  }
}
