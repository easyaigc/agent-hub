import XCTest
import Foundation
import CryptoKit
import UIKit
@testable import CodexManage_com_easyai_agenthub_easyworkacceptance

/// Changes delivery of genuine frames in this test process only.
private actor InflightWireExaminer: AgentTransport {
  struct Witness: Sendable {
    let writeAttempts: Int
    let operationID: String?
    let requestSHA256: String?
    let rootID: String?
    let responseSHA256: String?
    let submittedAt: Double?
    let responseHeldAt: Double?
    let methods: [String: Int]
    let excludedRoutes: Int
    let lateReadHeld: Bool
    let lateReadReleased: Bool
    let lateReadSHA256: String?
  }
  private let crypto: EasyWorkEnvelopeCrypto
  private let selected: AgentConnectionCandidate
  private let parent: String
  private let other: String
  private let body: String
  private let model: String
  private let readOnly: Bool
  private var base: LANAgentTransport?
  private var writes = 0
  private var operation: String?
  private var requestHash: String?
  private var root: String?
  private var responseHash: String?
  private var submitted: Double?
  private var heldAt: Double?
  private var methods: [String: Int] = [:]
  private var excluded = 0
  private var lateArmed = false
  private var lateRequest: String?
  private var lateData: Data?
  private var lateHash: String?
  private var released = false
  private var queued: [Data] = []

  init(credential: EasyWorkCredential, candidate: AgentConnectionCandidate,
    parent: String, other: String, body: String, model: String, readOnly: Bool,
    originalOperation: String?) throws {
    guard credential.grantRef == nil, credential.binding.status == "active",
      candidate.transportId == "lan-wss", candidate.peerFingerprint?.count == 64 else {
      throw HostServiceError.wrongHost
    }
    crypto = try EasyWorkEnvelopeCrypto(credential: credential, binding: credential.binding)
    selected = candidate; self.parent = parent; self.other = other
    self.body = body; self.model = model; self.readOnly = readOnly
    operation = originalOperation
  }

  func make(_ options: AgentTransportOptions) throws -> any AgentTransport {
    guard options.bindingId == crypto.binding.bindingId, options.grantRef == nil,
      options.candidate.transportId == "lan-wss",
      options.candidate.peerFingerprint == selected.peerFingerprint else { throw HostServiceError.wrongHost }
    // This controlled case represents loss of the selected channel and locally
    // unavailable alternate paths. It never changes OS networking or the Host.
    guard try EasyWorkPairing.localEndpoint(options.candidate.endpoint)
      == EasyWorkPairing.localEndpoint(selected.endpoint) else {
      excluded += 1; throw URLError(.cannotConnectToHost)
    }
    guard base == nil else { throw HostServiceError.unavailable }
    base = try LANAgentTransport(candidate: selected, bindingId: options.bindingId)
    return self
  }

  private func outgoing(_ data: Data) throws -> [String: Any] {
    let frame = try easyWorkObject(data)
    guard EasyWorkEnvelopeCrypto.wireInteger(frame["version"]) == 1,
      frame["bindingId"] as? String == crypto.binding.bindingId,
      frame["senderDeviceId"] as? String == crypto.binding.mobileDeviceId,
      frame["recipientDeviceId"] as? String == crypto.binding.desktopDeviceId,
      let sequence = EasyWorkEnvelopeCrypto.wireInteger(frame["sequence"]), sequence > 0,
      let nonce = Data(base64URL: frame["nonce"] as? String ?? ""),
      let bytes = Data(base64URL: frame["ciphertext"] as? String ?? ""), bytes.count >= 16 else {
      throw HostServiceError.wrongHost
    }
    let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce),
      ciphertext: bytes.dropLast(16), tag: bytes.suffix(16))
    return try easyWorkObject(AES.GCM.open(box, using: crypto.outbound,
      authenticating: crypto.header(frame)))
  }

  func send(_ data: Data) async throws {
    guard let base else { throw HostServiceError.unavailable }
    let payload = try outgoing(data)
    if payload["kind"] as? String == "key-confirmation" {
      guard payload["value"] as? String == crypto.confirmation else { throw HostServiceError.wrongHost }
    } else {
      guard payload["kind"] as? String == "protocol",
        let request = payload["envelope"] as? [String: Any],
        request["kind"] as? String == "request", let method = request["method"] as? String,
        let requestID = request["requestId"] as? String,
        let target = request["target"],
        try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(target)) == crypto.binding.target,
        let params = request["params"] as? [String: Any] else { throw HostServiceError.wrongHost }
      methods[method, default: 0] += 1
      if method == "turn/start" {
        writes += 1
        guard !readOnly, writes == 1, operation == nil,
          UUID(uuidString: requestID) != nil,
          params["clientRequestId"] as? String == requestID,
          params["threadId"] as? String == parent, params["mode"] as? String == "next-turn",
          let parts = params["parts"] as? [[String: Any]], parts.count == 1,
          parts[0]["type"] as? String == "text",
          let text = parts[0]["data"] as? [String: Any], text["text"] as? String == body,
          let settings = params["settings"] as? [String: Any],
          settings["model"] as? String == model else { throw HostServiceError.wrongHost }
        operation = requestID; requestHash = HostService.hash(try easyWorkJSON(params))
        submitted = ProcessInfo.processInfo.systemUptime
      } else {
        let reads: Set<String> = ["runtime/context", "runtime/summary", "project/list", "thread/list",
          "model/list", "thread/read", "thread/history", "thread/sync", "thread/read-state",
          "thread/read-state-list", "thread/subscribe", "thread/unsubscribe", "thread/open-status",
          "command/status", "thread/acknowledge-read", "resource/resolve", "resource/read"]
        if method == "thread/open" {
          guard !readOnly, params["threadId"] as? String == parent,
            methods[method, default: 0] <= 1 else { throw HostServiceError.wrongHost }
        } else {
          guard reads.contains(method) else { throw HostServiceError.wrongHost }
        }
        if method == "command/status" {
          guard let operation, params["commandId"] as? String == operation else { throw HostServiceError.wrongHost }
        }
        if ["thread/read", "thread/history", "thread/sync", "thread/subscribe", "thread/unsubscribe",
          "thread/open-status", "thread/acknowledge-read", "resource/resolve"].contains(method) {
          guard let id = params["threadId"] as? String, [parent, other].contains(id) else {
            throw HostServiceError.wrongHost
          }
        }
        if lateArmed, lateRequest == nil, ["thread/history", "thread/sync"].contains(method),
          params["threadId"] as? String == parent { lateRequest = requestID }
      }
    }
    try await base.send(data)
  }

  func receive() async throws -> Data {
    if !queued.isEmpty { return queued.removeFirst() }
    guard let base else { throw HostServiceError.unavailable }
    while true {
      let data = try await base.receive()
      let (payload, _) = try crypto.open(data)
      if payload["kind"] as? String == "protocol",
        let envelope = payload["envelope"] as? [String: Any],
        envelope["kind"] as? String == "response",
        let id = envelope["requestId"] as? String {
        if !readOnly, id == operation, responseHash == nil {
          guard envelope["ok"] as? Bool == true,
            let result = envelope["result"] as? [String: Any] else { return data }
          let receipt = try EasyWorkReceiptNormalization.decode(result,
            clientMessageId: id, hostId: crypto.binding.desktopDeviceId, threadId: parent,
            defaultState: "submitted")
          guard let rootID = receipt.turnId, !rootID.isEmpty else { throw HostServiceError.invalidResponse }
          root = rootID; responseHash = HostService.hash(data)
          heldAt = ProcessInfo.processInfo.systemUptime
          continue // Only local delivery is lost; no fabricated success or retry.
        }
        if id == lateRequest, lateData == nil, !released {
          lateData = data; lateHash = HostService.hash(data); continue
        }
      }
      if !queued.isEmpty {
        let first = queued.removeFirst(); queued.append(data); return first
      }
      return data
    }
  }

  func armLateRead() { lateArmed = true }
  func releaseLateRead() throws {
    guard let lateData, !released else { throw HostServiceError.invalidResponse }
    released = true; queued.append(lateData)
  }
  func close() async { await base?.close(); base = nil }
  func snapshot() -> Witness {
    Witness(writeAttempts: writes, operationID: operation, requestSHA256: requestHash,
      rootID: root, responseSHA256: responseHash, submittedAt: submitted,
      responseHeldAt: heldAt, methods: methods, excludedRoutes: excluded,
      lateReadHeld: lateData != nil, lateReadReleased: released, lateReadSHA256: lateHash)
  }
}

private struct InflightBackup: Codable {
  let caseID: String
  let target: AgentRuntimeTarget
  let clientID: String
  let parent: String
  let originalDraft: Draft?
  let preparedDraft: Draft
  let futureDraft: Draft
  let otherDraftsSHA256: String
}
private struct InflightOriginal: Codable {
  let caseID: String
  let reply: PendingReply
  let fingerprint: String
  let rootID: String
  let requestSHA256: String
  let responseSHA256: String
}
private struct InflightPreparedIntent: Codable {
  let caseID: String
  let target: AgentRuntimeTarget
  let reply: PendingReply
  let fingerprint: String
}

@MainActor final class EasyWorkInflightRecoveryNativeTests: XCTestCase {
  private struct Context {
    let caseID: String
    let config: [String: Any]
    let store: SessionStore
    let client: Client
    let credential: EasyWorkCredential
    let candidate: AgentConnectionCandidate
    let target: AgentRuntimeTarget
    let directory: URL
    let normalURL: URL
    let parent: String
    let other: String
    let model: String
    let body: String
  }
  private let encoder: JSONEncoder = { let e = JSONEncoder(); e.outputFormatting = [.sortedKeys]; return e }()
  private func hash<T: Encodable>(_ value: T) throws -> String { HostService.hash(try encoder.encode(value)) }
  private func otherDrafts(_ store: SessionStore, key: String) throws -> String {
    try hash(store.drafts.values.filter { $0.key != key })
  }
  private func restoredClientsHash(_ clients: [Client]) throws -> String {
    // Restoration explicitly sets every real client's presentation online flag
    // false. Compare every persisted identity/route field, excluding only that
    // transient flag; it is not a trust or stored-identity difference.
    try hash(clients.map { original in var value = original; value.online = false; return value })
  }
  private func rejectedContext(_ stage: String, _ checks: [String: Bool] = [:]) throws -> Never {
    try report(["stage": stage, "checks": checks, "transportCreated": false,
      "newRootInputs": 0], name: "f44-context-guard-failure")
    throw HostServiceError.wrongHost
  }
  private func context(phase: String, inspectOnly: Bool = false) async throws -> Context {
    let env = ProcessInfo.processInfo.environment
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-hosted-original-store-acceptance"),
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      UUID(uuidString: run)?.uuidString.lowercased() == run,
      env["EASYWORK_HOSTED_NATIVE_NORMAL_STORE_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Named original-store case required") }
    #if !targetEnvironment(simulator)
    throw XCTSkip("Simulator only")
    #else
    let c = try easyWorkObject(Data(raw.utf8))
    guard c["caseId"] as? String == run, c["phase"] as? String == phase,
      c["newRootInputsAuthorized"] as? Int == (phase == "prepare" ? 1 : 0),
      c["newThreadsAuthorized"] as? Int == 0, c["maxChildRunsAuthorized"] as? Int == 1,
      let parent = c["parentThreadId"] as? String, UUID(uuidString: parent) != nil,
      let other = c["otherThreadId"] as? String, UUID(uuidString: other) != nil, parent != other,
      let model = c["modelId"] as? String, !model.isEmpty,
      let body = c["body"] as? String, !body.isEmpty,
      HostService.hash(Data(body.utf8)) == c["bodySHA256"] as? String else { try rejectedContext("config") }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard target.agentId == "easywork", !target.hostId.isEmpty,
      !target.workspaceId.isEmpty, !target.runtimeId.isEmpty, target.account != nil else {
      try rejectedContext("target-shape")
    }
    let until = ContinuousClock.now.advanced(by: .seconds(25))
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && ContinuousClock.now < until {
      try await Task.sleep(for: .milliseconds(50))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw HostServiceError.unavailable }
    let clients = store.saved.clients.filter { !$0.isDemo }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    guard client.agentConnection?.target == target, store.client?.id == client.id,
      (store.selectedThreadId == parent || phase == "recover" && store.selectedThreadId == other),
      (store.saved.desktopCreations ?? [:]).values.allSatisfy({ !["submitted", "outcome-unknown"].contains($0.phase) }),
      (store.saved.threadForks ?? [:]).values.allSatisfy({ !$0.phase.isPending || $0.detached == true }) else {
      try rejectedContext("restored-selection", [
        "clientTargetMatches": client.agentConnection?.target == target,
        "selectedClientMatches": store.client?.id == client.id,
        "selectedThreadMatches": store.selectedThreadId == parent,
        "savedLastThreadMatches": store.saved.lastThreads[client.id] == parent,
        "desktopCreationsSettled": (store.saved.desktopCreations ?? [:]).values.allSatisfy({ !["submitted", "outcome-unknown"].contains($0.phase) }),
        "forksSettled": (store.saved.threadForks ?? [:]).values.allSatisfy({ !$0.phase.isPending || $0.detached == true })])
    }
    let connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard credential.grantRef == nil, credential.binding.bindingId == c["bindingId"] as? String,
      credential.binding.mobileDeviceId == c["mobileDeviceId"] as? String,
      credential.binding.target.desktopDeviceId == target.hostId,
      credential.binding.target.workspaceId == target.workspaceId,
      credential.binding.target.runtimeId == target.runtimeId,
      credential.binding.target.account == target.account else { try rejectedContext("credential-target") }
    let candidates = connection.candidates.filter {
      $0.transportId == "lan-wss" && $0.peerFingerprint == c["peerFingerprint"] as? String
    }
    guard candidates.count == connection.candidates.count else { try rejectedContext("candidate-trust") }
    let candidate = try XCTUnwrap(candidates.first)
    let normal = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("CodexManage", isDirectory: true)
      .appendingPathComponent("session-state-v1.json")
    let data = try Data(contentsOf: normal)
    guard data.starts(with: Data("agent-cache-v1\n".utf8)) else { throw HostServiceError.invalidResponse }
    let value = try AgentCache.decode(data)
    guard try restoredClientsHash(value.clients) == restoredClientsHash(store.saved.clients),
      try hash(value.drafts) == hash(store.drafts.values) else { try rejectedContext("normal-store-digest", [
        "clientsMatch": try restoredClientsHash(value.clients) == restoredClientsHash(store.saved.clients),
        "draftsMatch": try hash(value.drafts) == hash(store.drafts.values)]) }
    let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-inflight-" + run, isDirectory: true)
    if !inspectOnly {
      if phase == "prepare" {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
      } else {
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: directory.path, isDirectory: &isDirectory),
          isDirectory.boolValue,
          (try directory.resourceValues(forKeys: [.isSymbolicLinkKey])).isSymbolicLink != true,
          (try FileManager.default.attributesOfItem(atPath: directory.path)[.posixPermissions] as? NSNumber)?.intValue == 0o700 else {
          throw HostServiceError.wrongHost
        }
      }
    }
    return Context(caseID: run, config: c, store: store, client: client, credential: credential,
      candidate: candidate, target: target, directory: directory, normalURL: normal,
      parent: parent, other: other, model: model, body: body)
    #endif
  }

  func testInspectOriginalContextWithoutTransport() async throws {
    _ = try await context(phase: "prepare", inspectOnly: true)
    try report(["contextGuardsPassed": true, "transportCreated": false,
      "newRootInputs": 0], name: "f44-context-inspection")
  }
  private func writeOnce<T: Encodable>(_ value: T, to url: URL) throws {
    guard !FileManager.default.fileExists(atPath: url.path) else { throw HostServiceError.invalidResponse }
    try AgentCache.seal(value).write(to: url, options: [.withoutOverwriting, .completeFileProtectionUntilFirstUserAuthentication])
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
  }
  private func read<T: Decodable>(_ type: T.Type, at url: URL) throws -> T {
    try AgentCache.open(Data(contentsOf: url), as: type)
  }
  private func wait(_ label: String, seconds: Double, _ condition: () throws -> Bool) async throws {
    let until = ProcessInfo.processInfo.systemUptime + seconds
    while !(try condition()) && ProcessInfo.processInfo.systemUptime < until {
      try await Task.sleep(for: .milliseconds(50))
    }
    guard try condition() else { XCTFail(label); throw HostServiceError.unavailable }
  }
  private func report(_ data: [String: Any], name: String) throws {
    let attachment = XCTAttachment(data: try easyWorkJSON(data), uniformTypeIdentifier: "public.json")
    attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
  }

  func testPrepareOneOriginalInFlightAndEndWait() async throws {
    let c = try await context(phase: "prepare"), store = c.store, key = store.targetKey
    guard store.pending == nil, store.saved.outbox?.isEmpty != false,
      store.saved.firstMessageCreations?.isEmpty != false,
      !store.sendingKeys.contains(key) else { throw HostServiceError.unavailable }
    let backupURL = c.directory.appendingPathComponent("draft-backup.sealed")
    let originalURL = c.directory.appendingPathComponent("original-input.sealed")
    let preparedURL = c.directory.appendingPathComponent("prepared-input.sealed")
    guard !FileManager.default.fileExists(atPath: backupURL.path),
      !FileManager.default.fileExists(atPath: originalURL.path),
      !FileManager.default.fileExists(atPath: preparedURL.path) else { throw HostServiceError.invalidResponse }
    let gate = try InflightWireExaminer(credential: c.credential, candidate: c.candidate,
      parent: c.parent, other: c.other, body: c.body, model: c.model, readOnly: false, originalOperation: nil)
    let provider = try AgentClients.resolve(c.client)
    await provider.close(c.client)
    await AgentTransportRegistry.shared.register("lan-wss") { try await gate.make($0) }
    do {
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let modelList = try await provider.models(c.client)
    let chosen = try XCTUnwrap(modelList.models.first { $0.id == c.model })
    let old = store.drafts.values[key]
    var prepared = old ?? Draft()
    prepared.model = c.model; prepared.effort = chosen.defaultEffort
    prepared.permissionMode = "confirm_high_risk"; prepared.speed = nil; prepared.collaborationMode = "normal"
    let originalPrefix = (old?.text ?? "") + "\n"
    prepared.text = originalPrefix + (try XCTUnwrap(c.config["preflightDraftText"] as? String))
    var future = prepared
    future.text = originalPrefix + (try XCTUnwrap(c.config["futureDraftText"] as? String))
    let backup = InflightBackup(caseID: c.caseID, target: c.target, clientID: c.client.id,
      parent: c.parent, originalDraft: old, preparedDraft: prepared, futureDraft: future,
      otherDraftsSHA256: try otherDrafts(store, key: key))
    try writeOnce(backup, to: backupURL)
    store.drafts.values[key] = prepared
    store.persistForBackground()
    try await wait("The single app-owned store did not publish its prepared draft in the normal namespace",
      seconds: 10) {
      let normal = try AgentCache.decode(Data(contentsOf: c.normalURL))
      return normal.drafts[key] == prepared
    }
    guard await store.loadHistory(threadId: c.parent), store.canSend, !store.isRunning else {
      throw HostServiceError.unavailable
    }
    var bodyDraft = Draft(text: c.body)
    bodyDraft.model = c.model; bodyDraft.effort = chosen.defaultEffort
    bodyDraft.permissionMode = "confirm_high_risk"; bodyDraft.collaborationMode = "normal"
    let priorGrantIDs = Set((store.saved.aiSharingGrants ?? []).map(\.id))
    store.devReplyBeforeTransportForAcceptance = { reply in
      guard reply.hostId == c.client.id, reply.threadId == c.parent,
        reply.text == c.body, reply.attachmentIds.isEmpty else { throw HostServiceError.wrongHost }
      try self.writeOnce(InflightPreparedIntent(caseID: c.caseID, target: c.target,
        reply: reply, fingerprint: ReplyRecoveryPolicy.fingerprint(reply, target: c.target)), to: preparedURL)
    }
    defer { store.devReplyBeforeTransportForAcceptance = nil }
    store.send(content: bodyDraft)
    if let request = store.aiSharingRequest {
      guard request.scope.target == c.target, request.scope.model == c.model,
        store.pending == nil, await gate.snapshot().writeAttempts == 0 else { throw HostServiceError.wrongHost }
      store.allowAISharing()
      store.send(content: bodyDraft) // First invocation above had no admitted input.
    }
    let submittedUntil = ProcessInfo.processInfo.systemUptime + 25
    while await gate.snapshot().responseSHA256 == nil && ProcessInfo.processInfo.systemUptime < submittedUntil {
      try await Task.sleep(for: .milliseconds(50))
    }
    let held = await gate.snapshot()
    let reply = try XCTUnwrap(store.pending)
    guard held.writeAttempts == 1, let operation = held.operationID, operation == reply.clientMessageId,
      reply.threadId == c.parent, reply.text == c.body, reply.attachmentIds.isEmpty,
      let root = held.rootID, let reqSHA = held.requestSHA256, let responseSHA = held.responseSHA256,
      let submittedAt = held.submittedAt else { throw HostServiceError.invalidResponse }
    let fingerprint = try ReplyRecoveryPolicy.fingerprint(reply, target: c.target)
    try writeOnce(InflightOriginal(caseID: c.caseID, reply: reply, fingerprint: fingerprint,
      rootID: root, requestSHA256: reqSHA, responseSHA256: responseSHA), to: originalURL)
    let cancelStart = ProcessInfo.processInfo.systemUptime
    store.detachPendingWait(reply, target: key)
    let cancelMS = (ProcessInfo.processInfo.systemUptime - cancelStart) * 1000
    store.drafts.values[key] = future; store.persistForBackground()
    guard cancelMS <= 1000, store.pending == nil, !store.sendingKeys.contains(key),
      store.canSend else { throw HostServiceError.invalidResponse }
    try await wait("The original request did not end within its declared connected wait budget", seconds: 45) {
      store.hostedEasyWorkReadLoopState["replySendWorkCount"] as? Int == 0
    }
    let elapsed = ProcessInfo.processInfo.systemUptime - submittedAt
    guard (29...40).contains(elapsed), store.draft == future,
      try otherDrafts(store, key: key) == backup.otherDraftsSHA256,
      try CredentialVault.existingToken("easywork-device-identity") == identity,
      store.saved.detachedReplies?[key]?.filter({ $0.id == operation }).count == 1 else {
      throw HostServiceError.invalidResponse
    }
    var liveChild: ChatMessage?
    let liveUntil = ProcessInfo.processInfo.systemUptime + 45
    while liveChild == nil && ProcessInfo.processInfo.systemUptime < liveUntil {
      let h = try await provider.history(c.client, threadId: c.parent, cursor: nil)
      let children = h.messages.filter { $0.kind == "subAgent" && $0.runId == root }
      guard children.count <= 1 else { throw HostServiceError.invalidResponse }
      liveChild = children.first { ["pending", "inProgress"].contains($0.status ?? "") }
      if liveChild == nil { try await Task.sleep(for: .milliseconds(300)) }
    }
    guard let child = liveChild else { throw HostServiceError.unavailable }
    let addedGrants = (store.saved.aiSharingGrants ?? []).filter { !priorGrantIDs.contains($0.id) }
    for grant in addedGrants { store.revokeAISharing(grant.id) }
    store.persistForBackground()
    try await wait("New draft and immutable detached input were not persisted", seconds: 10) {
      let normal = try AgentCache.decode(Data(contentsOf: c.normalURL))
      return normal.drafts[key] == future && normal.detachedReplies?[key]?.contains(where: {
        $0.id == operation && $0.original.text == c.body
      }) == true
    }
    let final = await gate.snapshot()
    guard final.writeAttempts == 1 else { throw HostServiceError.invalidResponse }
    try report(["caseId": c.caseID, "phase": "prepare", "status": "passed-one-original-loss-endwait-inflight",
      "operationId": operation, "rootRunId": root, "childMessageId": child.id,
      "childStatusBeforeCold": child.status ?? "", "wireWriteAttempts": final.writeAttempts,
      "requestSHA256": reqSHA, "authenticResponseSHA256": responseSHA,
      "connectedRequestEndSeconds": elapsed, "localEndWaitMS": cancelMS,
      "methods": final.methods, "controlledUnavailableAlternateRoutes": final.excludedRoutes,
      "identityUnchanged": true, "otherDraftsUnchanged": true,
      "normalNamespaceWriteVerified": true, "futureDraftSHA256": try hash(future),
      "newRootInputs": 1, "osNetworkOutageClaimed": false], name: "f44-phase-a-" + c.caseID)
    await provider.close(c.client); await gate.close()
    } catch {
      let partial = await gate.snapshot()
      try? report(["caseId": c.caseID, "phase": "prepare", "status": "failed-no-replay",
        "wireWriteAttempts": partial.writeAttempts, "operationId": partial.operationID ?? "",
        "rootRunId": partial.rootID ?? "", "requestSHA256": partial.requestSHA256 ?? "",
        "authenticResponseSHA256": partial.responseSHA256 ?? "", "methods": partial.methods,
        "preparedIntentSaved": FileManager.default.fileExists(atPath: preparedURL.path),
        "originalDraftBackupSaved": FileManager.default.fileExists(atPath: backupURL.path),
        "errorDomain": (error as NSError).domain, "errorCode": (error as NSError).code],
        name: "f44-phase-a-failure-" + c.caseID)
      await provider.close(c.client); await gate.close()
      throw error
    }
  }

  func testColdRecoverOriginalAndParentChild() async throws {
    let c = try await context(phase: "recover"), store = c.store
    let backup = try read(InflightBackup.self, at: c.directory.appendingPathComponent("draft-backup.sealed"))
    let original = try read(InflightOriginal.self, at: c.directory.appendingPathComponent("original-input.sealed"))
    // A prior read-only attempt can end on the other existing chat. Return via
    // the ordinary selection API; immutable input and all draft checks remain.
    if store.selectedThreadId != c.parent {
      guard store.selectedThreadId == c.other,
        let parent = store.threads.first(where: { $0.id == c.parent }) else { throw HostServiceError.wrongHost }
      store.openThread(parent, loadAutomatically: false)
    }
    let key = store.targetKey
    guard backup.caseID == c.caseID, original.caseID == c.caseID, backup.target == c.target,
      backup.clientID == c.client.id, backup.parent == c.parent, store.draft == backup.futureDraft,
      original.reply.threadId == c.parent, original.reply.text == c.body,
      try ReplyRecoveryPolicy.fingerprint(original.reply, target: c.target) == original.fingerprint,
      try otherDrafts(store, key: key) == backup.otherDraftsSHA256 else { throw HostServiceError.wrongHost }
    let record = try XCTUnwrap(store.saved.detachedReplies?[key]?.first { $0.id == original.reply.clientMessageId })
    let gate = try InflightWireExaminer(credential: c.credential, candidate: c.candidate,
      parent: c.parent, other: c.other, body: c.body, model: c.model, readOnly: true,
      originalOperation: original.reply.clientMessageId)
    let provider = try AgentClients.resolve(c.client)
    await provider.close(c.client)
    await AgentTransportRegistry.shared.register("lan-wss") { try await gate.make($0) }
    var stage = "query-original-receipt"
    do {
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    store.queryDetachedReply(record, target: key)
    try await wait("Cold query did not recover the original accepted receipt", seconds: 45) {
      store.saved.detachedReplies?[key]?.first { $0.id == original.reply.clientMessageId }?.receipt?.turnId == original.rootID
    }
    guard store.draft == backup.futureDraft else { throw HostServiceError.invalidResponse }
    stage = "hold-parent-read"
    let parent = try XCTUnwrap(store.threads.first { $0.id == c.parent })
    let other = try XCTUnwrap(store.threads.first { $0.id == c.other })
    await gate.armLateRead()
    let late = Task { @MainActor in await store.loadHistory(threadId: c.parent) }
    let heldUntil = ProcessInfo.processInfo.systemUptime + 15
    while !(await gate.snapshot().lateReadHeld) && ProcessInfo.processInfo.systemUptime < heldUntil {
      try await Task.sleep(for: .milliseconds(30))
    }
    guard await gate.snapshot().lateReadHeld else { throw HostServiceError.unavailable }
    stage = "read-other-thread"
    store.openThread(other, loadAutomatically: false)
    guard await store.loadHistory(threadId: c.other), store.selectedThreadId == c.other else {
      throw HostServiceError.unavailable
    }
    let otherIDs = store.messages.map(\.id), otherDraft = store.draft
    stage = "release-original-late-parent-read"
    try await gate.releaseLateRead()
    _ = try await provider.models(c.client) // A real read wakes the genuine queued frame.
    let lateApplied = await late.value
    guard !lateApplied, store.selectedThreadId == c.other, store.messages.map(\.id) == otherIDs,
      store.draft == otherDraft else { throw HostServiceError.wrongHost }
    stage = "read-canonical-parent-child"
    store.openThread(parent, loadAutomatically: false)
    var finalHistory: HistorySnapshot?
    let until = ProcessInfo.processInfo.systemUptime + 210
    while ProcessInfo.processInfo.systemUptime < until {
      let h = try await provider.history(c.client, threadId: c.parent, cursor: nil)
      let children = h.messages.filter { $0.kind == "subAgent" && $0.runId == original.rootID }
      guard children.count <= 1 else { throw HostServiceError.invalidResponse }
      if children.first?.status == "completed", h.runtime?.state == "idle" {
        finalHistory = h; break
      }
      try await Task.sleep(for: .milliseconds(400))
    }
    let history = try XCTUnwrap(finalHistory)
    stage = "verify-canonical-input-and-future-draft"
    let child = try XCTUnwrap(history.messages.first { $0.kind == "subAgent" && $0.runId == original.rootID })
    // EasyWork projects each content part to messageID:partIndex. This original
    // input has exactly one text part, so verify its exact :0 projection and
    // body uniqueness rather than comparing a projected ID to the wire row ID.
    let projectedID = original.reply.clientMessageId + ":0"
    guard history.messages.filter({ $0.role == "user" && $0.id == projectedID && $0.text == c.body }).count == 1,
      history.messages.filter({ $0.role == "user" && $0.text == c.body }).count == 1,
      history.messages.contains(where: { $0.role == "assistant" && $0.runId == original.rootID && !$0.text.isEmpty }),
      store.draft == backup.futureDraft, try CredentialVault.existingToken("easywork-device-identity") == identity,
      try otherDrafts(store, key: key) == backup.otherDraftsSHA256 else {
      try report(["projectedOriginalUserParts": history.messages.filter({ $0.role == "user" && $0.id == projectedID && $0.text == c.body }).count,
        "sameBodyUserParts": history.messages.filter({ $0.role == "user" && $0.text == c.body }).count,
        "originalRootAssistantPresent": history.messages.contains(where: { $0.role == "assistant" && $0.runId == original.rootID && !$0.text.isEmpty }),
        "futureDraftUnchanged": store.draft == backup.futureDraft,
        "identityUnchanged": try CredentialVault.existingToken("easywork-device-identity") == identity,
        "otherDraftsUnchanged": try otherDrafts(store, key: key) == backup.otherDraftsSHA256], name: "f44-canonical-guard-details")
      throw HostServiceError.invalidResponse
    }
    _ = await store.loadHistory(threadId: c.parent)
    stage = "restore-original-draft"
    if let old = backup.originalDraft { store.drafts.values[key] = old }
    else { store.drafts.values.removeValue(forKey: key) }
    store.persistForBackground()
    try await wait("Original draft was not restored after the cold/late-result assertions", seconds: 10) {
      let value = try AgentCache.decode(Data(contentsOf: c.normalURL))
      return value.drafts[key] == backup.originalDraft
    }
    let final = await gate.snapshot()
    guard final.writeAttempts == 0, final.lateReadHeld, final.lateReadReleased else {
      throw HostServiceError.invalidResponse
    }
    try report(["caseId": c.caseID, "phase": "recover", "status": "passed-cold-original-child-and-late-target",
      "operationId": original.reply.clientMessageId, "rootRunId": original.rootID,
      "childMessageId": child.id, "childStatus": child.status ?? "", "newRootInputs": 0,
      "wireWriteAttempts": final.writeAttempts, "methods": final.methods,
      "genuineLateReadSHA256": final.lateReadSHA256 ?? "",
      "lateParentSnapshotIgnoredOnOtherThread": true, "futureDraftSurvivedColdAndLateQuery": true,
      "originalDraftRestored": true, "otherDraftsUnchanged": true,
      "identityUnchanged": true, "wholeFeaturePassed": false], name: "f44-phase-b-" + c.caseID)
    await provider.close(c.client); await gate.close()
    } catch {
      let partial = await gate.snapshot()
      try? report(["caseId": c.caseID, "phase": "recover", "stage": stage,
        "operationId": original.reply.clientMessageId, "rootRunId": original.rootID,
        "wireWriteAttempts": partial.writeAttempts, "methods": partial.methods,
        "lateReadHeld": partial.lateReadHeld, "lateReadReleased": partial.lateReadReleased,
        "selectedParent": store.selectedThreadId == c.parent,
        "selectedOther": store.selectedThreadId == c.other,
        "futureDraftStillCurrent": store.draft == backup.futureDraft,
        "newRootInputs": 0, "errorDomain": (error as NSError).domain,
        "errorCode": (error as NSError).code], name: "f44-cold-failure-stage")
      await provider.close(c.client); await gate.close()
      throw error
    }
  }
}
