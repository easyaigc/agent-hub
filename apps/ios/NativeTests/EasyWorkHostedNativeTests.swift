import XCTest
import Foundation
import CryptoKit
import SwiftUI
import UIKit
import QuickLook
import Vision
import AVFoundation
import PhotosUI
import Darwin
import MobileCore
import UserNotifications
import Security
#if canImport(CodexManage_com_easyai_agenthub_easyworkacceptance)
@testable import CodexManage_com_easyai_agenthub_easyworkacceptance
#elseif canImport(CodexManage_com_codexmanage_ios)
@testable import CodexManage_com_codexmanage_ios
#else
#error("Build this driver with the independent EasyWork acceptance bundle or the default local compiler target")
#endif

// Integration-only acceptance driver. Historical phone-only and physical-device
// UI probes remain in their original fingerprinted evidence and are not rerun here.
/// Test-process adapter: changes delivery of four genuine authenticated frames,
/// never creates ciphertext, writes keys, or changes the shared DEV transport.
private actor EasyWorkOriginalEventFaultTransport: AgentTransport {
  struct Frame: Sendable, Equatable {
    let cursor: Int
    let ciphertextSHA256: String
    let envelopeIDHash: String
  }
  struct Witness: Sendable {
    let injected: Bool
    let source: [Frame]
    let delivered: [Frame]
    let dropped: [Frame]
    let deliveredCount: Int
    let passthroughEvents: Int
    let pendingFrames: Int
  }
  private let inspector: EasyWorkEnvelopeCrypto
  private let originalCandidate: AgentConnectionCandidate
  private let transportID: String
  private let thread: String
  private var base: LANAgentTransport?
  private var captured: [(Data, Frame)] = []
  private var pending: [(Data, Frame)] = []
  private var source: [Frame] = []
  private var delivered: [Frame] = []
  private var dropped: [Frame] = []
  private var injected = false
  private var passthroughEvents = 0
  private var deliveredCount = 0
  init(credential: EasyWorkCredential, candidate: AgentConnectionCandidate,
    transportID: String, thread: String) throws {
    guard credential.grantRef == nil, candidate.transportId == "lan-wss",
      credential.binding.status == "active" else { throw HostServiceError.wrongHost }
    inspector = try EasyWorkEnvelopeCrypto(credential: credential, binding: credential.binding)
    originalCandidate = candidate; self.transportID = transportID; self.thread = thread
  }
  func make(_ options: AgentTransportOptions) throws -> any AgentTransport {
    guard base == nil, options.bindingId == inspector.binding.bindingId,
      options.grantRef == nil, options.candidate.transportId == transportID,
      options.candidate.endpoint == originalCandidate.endpoint,
      options.candidate.peerFingerprint == originalCandidate.peerFingerprint else {
      throw HostServiceError.wrongHost
    }
    base = try LANAgentTransport(candidate: originalCandidate, bindingId: options.bindingId)
    return self
  }
  func send(_ frame: Data) async throws {
    guard let base else { throw HostServiceError.unavailable }
    try await base.send(frame)
  }
  private func takePending() -> Data {
    let next = pending.removeFirst()
    delivered.append(next.1); deliveredCount += 1
    return next.0
  }
  func receive() async throws -> Data {
    if !pending.isEmpty { return takePending() }
    guard let base else { throw HostServiceError.unavailable }
    while true {
      let data = try await base.receive()
      let (payload, envelopeID) = try inspector.open(data)
      guard payload["kind"] as? String == "protocol",
        let envelope = payload["envelope"] as? [String: Any],
        envelope["kind"] as? String == "event" else { return data }
      let target = try JSONDecoder().decode(EasyWorkTarget.self,
        from: easyWorkJSON(envelope["target"] ?? [:]))
      guard target == inspector.binding.target else { throw HostServiceError.wrongHost }
      guard let event = envelope["payload"] as? [String: Any],
        event["threadId"] as? String == thread else { return data }
      if injected { passthroughEvents += 1; return data }
      guard data.count <= 1024 * 1024, captured.count < 4,
        let cursor = envelope["sequence"] as? Int, cursor > 0,
        source.last.map({ cursor > $0.cursor }) ?? true else {
        throw HostServiceError.invalidResponse
      }
      let frame = Frame(cursor: cursor, ciphertextSHA256: HostService.hash(data),
        envelopeIDHash: HostService.hash(Data(envelopeID.utf8)))
      captured.append((data, frame)); source.append(frame)
      if captured.count < 4 { continue }
      // Newer before older; one identical ciphertext twice; one real gap.
      pending = [captured[3], captured[0], captured[0], captured[2]]
      dropped = [captured[1].1]; captured.removeAll(); injected = true
      return takePending()
    }
  }
  func close() async {
    await base?.close(); base = nil
    captured.removeAll(); pending.removeAll()
  }
  func witness() -> Witness {
    Witness(injected: injected, source: source, delivered: delivered, dropped: dropped,
      deliveredCount: deliveredCount, passthroughEvents: passthroughEvents, pendingFrames: pending.count)
  }
}

/// Delays one genuine, already verified receipt in this test process only.
/// All send paths stay blocked while the UI-owned Store is under examination.
private actor EasyWorkOriginalReceiptGate {
  struct Witness: Sendable {
    let originalQueries: Int
    let held: Bool
    let released: Bool
    let delivered: Int
    let receiptSHA256: String?
    let blockedWrites: [String]
  }
  private let originalID: String
  private var queries = 0
  private var held = false
  private var released = false
  private var closed = false
  private var delivered = 0
  private var digest: String?
  private var originalBytes: Data?
  private var continuation: CheckedContinuation<Void, Never>?
  private var blockedWrites: [String] = []
  init(originalID: String) { self.originalID = originalID }
  func deliver(_ bytes: Data, id: String) async throws -> Data {
    guard !closed else { throw CancellationError() }
    guard id == originalID else { return bytes }
    queries += 1
    guard queries <= 2 else { throw HostServiceError.invalidResponse }
    if queries == 1 {
      digest = HostService.hash(bytes); originalBytes = bytes; held = true
      if !released { await withCheckedContinuation { continuation = $0 } }
    }
    guard !closed else { throw CancellationError() }
    delivered += 1
    return bytes
  }
  func release() { released = true; continuation?.resume(); continuation = nil }
  func cancel() { closed = true; release() }
  func block(_ operation: String) { blockedWrites.append(operation) }
  func capturedBytes() -> Data? { originalBytes }
  func witness() -> Witness {
    Witness(originalQueries: queries, held: held, released: released, delivered: delivered,
      receiptSHA256: digest, blockedWrites: blockedWrites)
  }
}

private struct EasyWorkReceiptGateProvider: AgentClient {
  let base: any AgentClient
  let gate: EasyWorkOriginalReceiptGate
  let originalID: String
  let originalThread: String
  let originalRoot: String
  func workspace(_ client: Client, projectCursor: String?, threadCursor: String?) async throws -> WorkspaceSnapshot {
    try await base.workspace(client, projectCursor: projectCursor, threadCursor: threadCursor)
  }
  func history(_ client: Client, threadId: String, cursor: String?) async throws -> HistorySnapshot {
    try await base.history(client, threadId: threadId, cursor: cursor)
  }
  func receipt(_ client: Client, id: String) async throws -> DeliveryReceipt {
    let receipt = try await base.receipt(client, id: id)
    if id == originalID {
      guard receipt.hostId == client.id, receipt.clientMessageId == originalID,
        receipt.threadId == originalThread, receipt.turnId == originalRoot,
        receipt.state == "submitted", receipt.error == nil else { throw HostServiceError.wrongHost }
    }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let bytes = try encoder.encode(receipt)
    guard try await gate.deliver(bytes, id: id) == bytes else { throw HostServiceError.invalidResponse }
    return receipt
  }
  func events(_ client: Client, epoch: String, after: Int, threadId: String) async throws -> EventBatch {
    try await base.events(client, epoch: epoch, after: after, threadId: threadId)
  }
  func models(_ client: Client) async throws -> ModelList { try await base.models(client) }
  func permissionOptions(_ client: Client) -> [AgentPermissionOption] { base.permissionOptions(client) }
  func normalizedCapabilities(_ capabilities: [String], connectionMode: String?) -> [String] {
    base.normalizedCapabilities(capabilities, connectionMode: connectionMode)
  }
  func connectionTitle(_ client: Client) async -> String? { await base.connectionTitle(client) }
  func close(_ client: Client) async { await base.close(client) }
  func send(_ client: Client, reply: PendingReply) async throws -> DeliveryReceipt {
    await gate.block("send"); throw HostServiceError.unavailable
  }
  func sendCapturedReply(_ client: Client, reply: PendingReply, target: AgentRuntimeTarget) async throws -> DeliveryReceipt {
    await gate.block("sendCapturedReply"); throw HostServiceError.unavailable
  }
  func createThread(_ client: Client, projectId: String?, operationId: String) async throws -> Conversation {
    await gate.block("createThread"); throw HostServiceError.unavailable
  }
  func stop(_ client: Client, threadId: String, turnId: String) async throws -> CommandResult {
    await gate.block("stop"); throw HostServiceError.unavailable
  }
  func approve(_ client: Client, approval: Approval, decision: String, answers: [String: [String]]) async throws -> CommandResult {
    await gate.block("approve"); throw HostServiceError.unavailable
  }
  func upload(_ client: Client, threadId: String, file: LocalAttachment, data: Data) async throws -> RemoteAttachment {
    await gate.block("upload"); throw HostServiceError.unavailable
  }
  func download(_ client: Client, file: RemoteAttachment) async throws -> URL {
    await gate.block("download"); throw HostServiceError.unavailable
  }
}

/// Executes inside the independently identified App through normal XCTest hosting.
/// No second SessionStore or pairing. Model submissions and negative commands

final class EasyWorkHostedNativeTests: XCTestCase {
  /// Reads only this independent App's existing voice files, without restore/expiry side effects.
  @MainActor private func originalVoiceProtectedState() throws -> [String: String] {
    let support = try XCTUnwrap(FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first)
    var result = ["controllerPhase": SessionStore.hostedEasyWorkAcceptanceVoice.map { String(describing: $0.phase) } ?? "unattached"]
    for name in ["voice-transcriptions", "migrated-voice-records-v1"] {
      let root = support.appendingPathComponent(name, isDirectory: true)
      guard FileManager.default.fileExists(atPath: root.path) else { result[name] = "absent"; continue }
      let rootInfo = try root.resourceValues(forKeys: [.isSymbolicLinkKey])
      guard rootInfo.isSymbolicLink != true else { throw HostServiceError.invalidResponse }
      let walker = try XCTUnwrap(FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey]))
      var count = 0
      for case let file as URL in walker {
        count += 1
        guard count <= 512 else { throw HostServiceError.invalidResponse }
        let relative = String(file.path.dropFirst(root.path.count + 1))
        let info = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        if info.isSymbolicLink == true {
          result[name + "/" + relative] = "symlink:" + HostService.hash(Data(try FileManager.default.destinationOfSymbolicLink(atPath: file.path).utf8))
          walker.skipDescendants()
        } else if info.isRegularFile == true {
          let stream = try FileHandle(forReadingFrom: file)
          defer { try? stream.close() }
          var digest = SHA256()
          while let chunk = try stream.read(upToCount: 65_536), !chunk.isEmpty { digest.update(data: chunk) }
          result[name + "/" + relative] = digest.finalize().map { String(format: "%02x", $0) }.joined()
        }
      }
    }
    return result
  }

  @MainActor private func originalReadProtectedDigests(_ store:SessionStore) throws -> [String:String] {
    let encoder=JSONEncoder();encoder.outputFormatting=[.sortedKeys]
    func digest<T:Encodable>(_ value:T)throws->String{HostService.hash(try encoder.encode(value))}
    var state=try XCTUnwrap(try JSONSerialization.jsonObject(with:encoder.encode(store.saved))as?[String:Any])
    state.removeValue(forKey:"history");state.removeValue(forKey:"historyCacheKeys")
    let stable=HostService.hash(try JSONSerialization.data(withJSONObject:state,options:[.sortedKeys]))
    return ["sessionExceptReplaceableHistory":stable,"clients":try digest(store.saved.clients),"drafts":try digest(store.drafts.values),"outbox":try digest(store.saved.outbox),"replyRecovery":try digest(store.saved.replyRecovery),"threadForks":try digest(store.saved.threadForks),"appearance":try digest(store.saved.appearance),"notifications":try digest(TaskNotificationController.shared.preferences),"language":try digest(L10n.selection.rawValue),"voice":try digest(originalVoiceProtectedState()),"identity":HostService.hash(Data(try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity")).utf8)),"selectedClient":try digest(store.saved.selectedClientId),"lastThreads":try digest(store.saved.lastThreads),"lastThreadProjects":try digest(store.saved.lastThreadProjects),"newProjects":try digest(store.saved.newProjects)]
  }

  @MainActor func testConnectFreshSimulatorToOriginalEasyWorkDEV() async throws {
    #if targetEnvironment(simulator)
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen simulator bootstrap required") }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run,
      config["newDeviceConnectionsAuthorized"] as? Int == 1,
      config["newRootInputsAuthorized"] as? Int == 0,
      config["newBusinessCommandsAuthorized"] as? Int == 0,
      let endpoint = config["endpoint"] as? String,
      let fingerprint = config["peerFingerprint"] as? String,
      fingerprint.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
      let publicKey = config["peerPublicKey"] as? String, Data(base64URL: publicKey)?.count == 32 else { throw HostServiceError.invalidResponse }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let wire = try JSONDecoder().decode(EasyWorkTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["wireTarget"])))
    guard target.agentId == "easywork", target.hostId == wire.desktopDeviceId,
      target.workspaceId == wire.workspaceId, target.runtimeId == wire.runtimeId,
      target.account == wire.account else { throw HostServiceError.wrongHost }
    let limit = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < limit { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired, store.saved.clients.allSatisfy(\.isDemo),
      store.saved.outbox?.isEmpty != false else { throw HostServiceError.invalidResponse }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    let identity = try EasyWorkIdentity.load()
    var report: [String: Any] = ["caseId": run, "environment": "ios-simulator",
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "newModels": 0, "newBusinessCommands": 0,
      "simulatorDeviceId": identity.deviceId, "devicePublicKeySHA256": HostService.hash(Data(identity.publicKey.utf8)),
      "physicalDeviceCredentialCopied": false, "wholeFeaturePassed": false, "stage": "connecting-through-production-lan"]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    try save()
    do {
      let offer = EasyWorkLANOffer(endpoints: [endpoint], fingerprint: fingerprint, publicKey: publicKey,
        target: wire, offerId: "", pairingCode: "", expiresAt: "", connectionMode: "automatic", endpointHints: nil)
      let (binding, candidates) = try await EasyWorkPairing.connect(endpoint: endpoint,
        name: "iOS Simulator " + String(run.prefix(8)), offer: offer)
      guard binding.target == wire, binding.mobileDeviceId == identity.deviceId,
        binding.mobilePublicKey == identity.publicKey else { throw HostServiceError.wrongHost }
      report["bindingId"] = binding.bindingId; report["stage"] = "saving-validated-production-connection"; try save()
      try await store.saveEasyWork(name: "EasyWork simulator acceptance", binding: binding, candidates: candidates)
      let clients = store.saved.clients.filter { !$0.isDemo }
      guard clients.count == 1, clients[0].agentConnection?.target == target else { throw HostServiceError.wrongHost }
      let channel = try await EasyWorkSessions.shared.session(clients[0])
      let context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let actual = try JSONDecoder().decode(EasyWorkTarget.self,
        from: easyWorkJSON(context["target"] ?? [:]))
      guard actual == wire, (context["capabilities"] as? [String] ?? []).contains("attachments:resolve-message-link:v1"),
        try EasyWorkIdentity.load().deviceId == identity.deviceId else { throw HostServiceError.wrongHost }
      report["actualAuthenticatedTarget"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(actual))
      report["messageFileCapability"] = true; report["newDeviceConnections"] = 1
      report["status"] = "passed-simulator-production-lan-bootstrap-only"; try save()
    } catch {
      report["status"] = "failed-preserve-original-connection-attempt-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
    #else
    throw XCTSkip("Simulator-only connection bootstrap")
    #endif
  }

  @MainActor func testOriginalAssistantFileLinksThroughProductionEasyWorkProvider() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen message-file integration case required") }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run,
      config["newRootInputsAuthorized"] as? Int == 0,
      config["newBusinessCommandsAuthorized"] as? Int == 3,
      let thread = config["threadId"] as? String, let message = config["assistantMessageId"] as? String,
      let userMessage = config["userMessageId"] as? String, userMessage != message,
      let marker = config["replyMarker"] as? String, !marker.isEmpty,
      let fixtures = config["fixtures"] as? [[String: Any]], fixtures.count == 3 else { throw HostServiceError.invalidResponse }
    let limit = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < limit { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let preferences = try encoder.encode(store.saved.generationPreferences)
    let recoveries = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId, selectedProject = store.selectedProjectId
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "appBundleIdentifier": Bundle.main.bundleIdentifier!,
      "threadId": thread, "assistantMessageId": message, "newModels": 0, "newBusinessCommands": 0,
      "wholeFeaturePassed": false, "phonePreviewUIRetested": false, "fixtures": [[String: Any]]()]
    var cleanup: [URL] = []
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    defer {
      var removed = 0, failures = 0
      for url in cleanup {
        do {
          try FileManager.default.removeItem(at: url); removed += 1
          // rmdir removes only the now-empty directory for this newly granted resource.
          _ = url.deletingLastPathComponent().path.withCString { Darwin.rmdir($0) }
        } catch { failures += 1 }
      }
      report["ownNewCachedFilesRemoved"] = removed; report["cacheCleanupFailures"] = failures
      if failures != 0 { report["status"] = "failed-own-cache-cleanup"; XCTFail("Owned fixture cache cleanup failed") }
      try? save()
    }
    do {
      report["stage"] = "checking-original-conversation"; try save()
      let provider = try AgentClients.resolve(client)
      let history = try await provider.history(client, threadId: thread, cursor: nil)
      var projected = history.messages, next = history.nextCursor, pages = 1, seenCursors = Set<String>()
      func originalRows() -> [ChatMessage] {
        projected.filter { $0.role == "assistant" && ($0.replyMessageId == message || $0.id == message || $0.id.hasPrefix(message + ":")) }
      }
      while originalRows().isEmpty, let cursor = next {
        guard pages < 16, seenCursors.insert(cursor).inserted else { throw HostServiceError.invalidResponse }
        let earlier = try await provider.history(client, threadId: thread, cursor: cursor)
        guard earlier.hostId == client.id, earlier.threadId == thread else { throw HostServiceError.wrongHost }
        projected.append(contentsOf: earlier.messages); next = earlier.nextCursor; pages += 1
      }
      let sourceRows = originalRows()
      let originalText = sourceRows.map(\.text).joined(separator: "\n")
      report["historyPagesRead"] = pages; report["originalProjectedTextParts"] = sourceRows.count
      report["historyRuntimeState"] = history.runtime?.state ?? "missing"
      report["originalTextSHA256"] = HostService.hash(Data(originalText.utf8))
      report["frozenMarkerMatchedAcrossOriginalParts"] = originalText.contains(marker); try save()
      guard ["idle", "cancelled", "failed", "interrupted"].contains(history.runtime?.state ?? ""),
        !sourceRows.isEmpty, originalText.contains(marker) else { throw HostServiceError.invalidResponse }
      var results: [[String: Any]] = [], extensions = Set<String>()
      for fixture in fixtures {
        guard let reference = fixture["reference"] as? String, !reference.isEmpty,
          let name = fixture["name"] as? String, name == (name as NSString).lastPathComponent,
          let hash = fixture["sha256"] as? String, hash.count == 64,
          let size = fixture["size"] as? Int, size > 0, size <= 1024 * 1024 else { throw HostServiceError.invalidResponse }
        let ext = (name as NSString).pathExtension
        guard ["txt", "png", "pdf"].contains(ext), extensions.insert(ext).inserted else { throw HostServiceError.invalidResponse }
        let sourceRow = try XCTUnwrap(sourceRows.first(where: {
          $0.text.contains(reference) || $0.attachments?.contains(where: { $0.name == name }) == true
        }), "Original file link has no matching projected message part")
        let file = try await provider.resolveMessageFile(client, threadId: thread, messageId: sourceRow.id, reference: reference)
        guard file.hostId == client.id, file.threadId == thread, file.name == name,
          file.sizeBytes == size, file.sha256 == hash else { throw HostServiceError.invalidResponse }
        report["activeResourceId"] = file.id; try save()
        let hadCache = try await provider.cachedDownload(client, file: file) != nil
        let url = try await provider.download(client, file: file)
        let bytes = try Data(contentsOf: url)
        let cacheRoot = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].resolvingSymlinksInPath().path + "/Downloads/"
        guard url.standardizedFileURL.path.hasPrefix(cacheRoot), url.lastPathComponent == name,
          bytes.count == size, HostService.hash(bytes) == hash else { throw HostServiceError.invalidResponse }
        if !hadCache { cleanup.append(url) }
        results.append(["resourceId": file.id, "originalDisplayMessageId": sourceRow.id, "referenceSHA256": HostService.hash(Data(reference.utf8)),
          "name": name, "sha256": hash, "bytes": bytes.count, "cacheExistedBefore": hadCache])
        report["fixtures"] = results; report["newBusinessCommands"] = results.count; try save()
      }
      let reference = try XCTUnwrap(fixtures.first?["reference"] as? String)
      func expectOriginalLinkRejection(messageId: String, reference: String) async throws {
        var rejected = false
        do { _ = try await provider.resolveMessageFile(client, threadId: thread, messageId: messageId, reference: reference) }
        catch let error as HostServiceError {
          guard case .message(let text) = error,
            text == L10n.tr("此文件尚未由电脑提供，请刷新对话后重试。") else { throw error }
          rejected = true
        }
        guard rejected else { throw HostServiceError.invalidResponse }
      }
      try await expectOriginalLinkRejection(messageId: userMessage, reference: reference)
      try await expectOriginalLinkRejection(messageId: message, reference: reference + ".not-owned")
      guard try CredentialVault.existingToken("easywork-device-identity") == identity,
        try encoder.encode(store.drafts.values) == drafts, try encoder.encode(store.saved.outbox) == outbox,
        try encoder.encode(store.saved.generationPreferences) == preferences,
        store.saved.replyRecovery?.mapValues(\.fingerprint) == recoveries,
        store.saved.selectedClientId == selectedClient, store.selectedThreadId == selectedThread,
        store.selectedProjectId == selectedProject else { throw HostServiceError.invalidResponse }
      report["wrongOriginalMessageAndPathRejected"] = true
      report["originalIdentityDraftsOutboxRecoveryPreferencesSelectionUnchanged"] = true
      report["status"] = "passed-production-provider-three-original-assistant-file-links-only"; try save()
    } catch {
      report["status"] = "failed-preserve-original-requests-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testPublicOwnSceneBackgroundTransitionWithoutModelInput() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Explicit own-scene exploration required") }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run,
      config["newModelInputsAuthorized"] as? Int == 0,
      config["newBusinessCommandsAuthorized"] as? Int == 0,
      config["ownSceneDestructionRequestsAuthorized"] as? Int == 1,
      config["temporaryBackgroundAssertionsAuthorized"] as? Int == 1 else { throw HostServiceError.invalidResponse }
    let app = UIApplication.shared
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil || app.applicationState != .active,
      Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    let scenes = app.connectedScenes.compactMap { $0 as? UIWindowScene }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target, app.applicationState == .active,
      scenes.count == 1, scenes[0].activationState == .foregroundActive else { throw HostServiceError.wrongHost }
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let selected = store.saved.selectedClientId, thread = store.selectedThreadId, project = store.selectedProjectId
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "appBundleIdentifier": Bundle.main.bundleIdentifier!,
      "appPID": ProcessInfo.processInfo.processIdentifier, "newModels": 0, "newBusinessCommands": 0,
      "supportsMultipleScenes": app.supportsMultipleScenes, "sceneCountBefore": scenes.count,
      "wholeFeaturePassed": false, "APNsDeliveryTested": false, "naturalSuspensionTested": false,
      "publicAPIOnly": true, "samples": [["at": Date().timeIntervalSince1970, "applicationState": app.applicationState.rawValue]],
      "status": "prepared-public-own-scene-request-not-yet-made"]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    var requestError: String?
    let assertion = app.beginBackgroundTask(withName: "FrozenOwnSceneExploration", expirationHandler: nil)
    report["backgroundAssertionGranted"] = assertion != .invalid
    defer { if assertion != .invalid { app.endBackgroundTask(assertion) } }
    report["status"] = "one-public-scene-destruction-about-to-request"; try save()
    app.requestSceneSessionDestruction(scenes[0].session, options: nil) { error in
      Task { @MainActor in
        let value = error as NSError
        requestError = value.domain + ":" + String(value.code)
      }
    }
    let deadline = Date().addingTimeInterval(8)
    var backgroundSince: Date?
    var stable = false
    while Date() < deadline {
      let now = Date()
      var samples = report["samples"] as? [[String: Any]] ?? []
      samples.append(["at": now.timeIntervalSince1970, "applicationState": app.applicationState.rawValue,
        "sceneCount": app.connectedScenes.count])
      report["samples"] = samples
      if let requestError { report["publicAPIError"] = requestError; break }
      if app.applicationState == .background {
        if backgroundSince == nil { backgroundSince = now }
        if now.timeIntervalSince(backgroundSince!) >= 1 { stable = true; break }
      } else { backgroundSince = nil }
      try save(); try await Task.sleep(for: .milliseconds(200))
    }
    guard try CredentialVault.existingToken("easywork-device-identity") == identity,
      try encoder.encode(store.drafts.values) == drafts, try encoder.encode(store.saved.outbox) == outbox,
      store.saved.replyRecovery?.mapValues(\.fingerprint) == recovery,
      store.saved.selectedClientId == selected, store.selectedThreadId == thread,
      store.selectedProjectId == project else { throw HostServiceError.invalidResponse }
    report["originalIdentityDraftsOutboxRecoverySelectionUnchanged"] = true
    report["stableActualBackgroundStateWithTemporaryAssertion"] = stable
    report["status"] = stable ? "observed-real-background-under-debugger-and-own-assertion-only"
      : "public-own-scene-request-did-not-establish-background"
    try save()
    guard stable else { throw NSError(domain: "EasyWorkPublicSceneExploration", code: 1) }
  }

  @MainActor func testOriginalEasyWorkProjectGitVOneReadWithoutSending() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen original-target Git read case required") }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
    let ownedAction = config["ownedProjectAction"] as? String
    let staleSnapshotProbe = config["staleSnapshotProbe"] as? Bool == true
    let currentSnapshotBoundaryProbe = config["currentSnapshotBoundaryProbe"] as? Bool == true
    guard ownedAction == nil || ["create", "delete", "reject"].contains(ownedAction) else { throw HostServiceError.invalidResponse }
    guard config["caseId"] as? String == run,
      config["newModelInputsAuthorized"] as? Int == 0,
      config["newBusinessCommandsAuthorized"] as? Int == (ownedAction == nil || ownedAction == "reject" ? 0 : 1),
      let projectId = config["gitProjectId"] as? String,
      let expectedKind = config["expectedKind"] as? String, ["git", "non-git", "unknown"].contains(expectedKind),
      config["gitReadsAuthorized"] as? Int == (ownedAction == nil ? (expectedKind == "git" ? (staleSnapshotProbe ? 6 : (currentSnapshotBoundaryProbe ? 5 : 3)) : 2) : 0),
      !staleSnapshotProbe || (ownedAction == nil && expectedKind == "git"),
      !currentSnapshotBoundaryProbe || (ownedAction == nil && expectedKind == "git" && !staleSnapshotProbe) else { throw HostServiceError.invalidResponse }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let selected = store.saved.selectedClientId, thread = store.selectedThreadId, project = store.selectedProjectId
    var report: [String: Any] = ["caseId": run, "appBundleIdentifier": Bundle.main.bundleIdentifier!,
      "appPID": ProcessInfo.processInfo.processIdentifier, "newModels": 0, "newBusinessCommands": ownedAction == nil || ownedAction == "reject" ? 0 : 1,
      "wholeFeaturePassed": false, "pureUIReaccepted": false, "gitProjectId": projectId]
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    do {
      report["stage"] = "original-authenticated-git-read"; try save()
      let channel = try await EasyWorkSessions.shared.session(client)
      if let action = ownedAction {
        guard let owner = config["fixtureOwnerId"] as? String, UUID(uuidString: owner) != nil,
          let directory = config["ownedProjectDirectory"] as? String, directory.hasPrefix("/"),
          let name = config["ownedProjectName"] as? String,
          (((directory as NSString).lastPathComponent == "i5-agent-permission-git-" + owner && name == "I5 Git " + owner)
            || ((directory as NSString).lastPathComponent == "i6-current-snapshot-git-" + owner && name == "I6 Git boundary " + owner)),
          let operation = config["ownedProjectOperationId"] as? String, UUID(uuidString: operation) != nil else { throw HostServiceError.invalidResponse }
        report["stage"] = "owned-project-" + action + "-through-original-agent-api"; try save()
        let provider = try AgentClients.resolve(client)
        if action == "create" {
          let before = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
          guard !before.projects.contains(where: { $0.name == name || $0.roots.contains(directory) }) else { throw HostServiceError.invalidResponse }
          let value = try await provider.createProject(client, name: name, directory: directory, operationId: operation)
          let created = try XCTUnwrap(value.projects.first(where: { $0.name == name && $0.roots == [directory] }))
          report["gitProjectId"] = created.id
        } else if action == "reject" {
          guard config["ownedProjectDirectoryIndependentlyAbsent"] as? Bool == true,
            let relativeOperation = config["ownedProjectRelativeEscapeOperationId"] as? String,
            UUID(uuidString: relativeOperation) != nil, relativeOperation != operation else { throw HostServiceError.invalidResponse }
          let before = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
          guard !before.projects.contains(where: { $0.name == name || $0.roots.contains(directory) }) else { throw HostServiceError.invalidResponse }
          let probes = [
            (directory, operation, L10n.tr("请选择已存在且已授权的目录")),
            ("../i6-current-snapshot-git-" + owner, relativeOperation, L10n.tr("相对路径不能超出电脑工作目录")),
          ]
          for (index, probe) in probes.enumerated() {
            var rejected = false
            do { _ = try await provider.createProject(client, name: name, directory: probe.0, operationId: probe.1) }
            catch let error as HostServiceError {
              guard case .message(let text) = error, text == probe.2 else { throw error }
              rejected = true
            }
            guard rejected else { throw HostServiceError.invalidResponse }
            report[index == 0 ? "nonexistentDirectoryRejected" : "relativeWorkspaceEscapeRejected"] = true
            try save()
          }
          let after = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
          guard try encoder.encode(before.projects) == encoder.encode(after.projects) else { throw HostServiceError.invalidResponse }
          report["originalWorkspaceProjectsUnchanged"] = true
          report["projectCreateAttempts"] = 0
        } else {
          guard config["ownedProjectEmptyIndependentlyChecked"] as? Bool == true else { throw HostServiceError.invalidResponse }
          let before = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
          guard before.projects.contains(where: { $0.id == projectId && $0.name == name && $0.roots == [directory] }) else { throw HostServiceError.invalidResponse }
          _ = try await channel.request("project/delete", ["projectId": projectId], id: operation)
          let after = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
          guard !after.projects.contains(where: { $0.id == projectId }) else { throw HostServiceError.invalidResponse }
        }
        guard try CredentialVault.existingToken("easywork-device-identity") == identity,
          try encoder.encode(store.drafts.values) == drafts, try encoder.encode(store.saved.outbox) == outbox,
          store.saved.replyRecovery?.mapValues(\.fingerprint) == recovery,
          store.saved.selectedClientId == selected, store.selectedThreadId == thread,
          store.selectedProjectId == project else { throw HostServiceError.invalidResponse }
        report["originalIdentityDraftsOutboxRecoverySelectionUnchanged"] = true
        report["status"] = "passed-owned-project-" + action + "-only-no-git-or-model"; try save(); return
      }
      let contextBytes = try await channel.request("runtime/context", [:])
      let context = try XCTUnwrap(try JSONSerialization.jsonObject(with: contextBytes) as? [String: Any])
      let capabilities = try XCTUnwrap(context["capabilities"] as? [String])
      guard capabilities.contains("project-git.status.v1"), capabilities.contains("project-git.files.v1") else { throw HostServiceError.invalidResponse }
      let bytes = try await AgentClients.resolve(client).projectGit(client, projectId: projectId, target: target, refresh: true)
      if !currentSnapshotBoundaryProbe { report["actualGitResponsePrivate"] = try JSONSerialization.jsonObject(with: bytes) }
      report["gitResponseSHA256"] = HostService.hash(bytes); try save()
      let targetJSON = try projectGitTargetJSON(target)
      let projection = try ProjectGitRules.shared.project(json: String(decoding: bytes, as: UTF8.self), targetJson: targetJSON, projectId: projectId)
      let snapshot = try JSONDecoder().decode(ProjectGitSnapshot.self, from: Data(projection.utf8))
      guard snapshot.kind == expectedKind,
        snapshot.projectId == projectId, snapshot.remoteKnowledge == "local-refs" else { throw HostServiceError.invalidResponse }
      if !currentSnapshotBoundaryProbe { report["actualSharedProjectionPrivate"] = try JSONSerialization.jsonObject(with: Data(projection.utf8)) }
      if expectedKind == "unknown" {
        guard let reasons = config["expectedIssueReasons"] as? [String], !reasons.isEmpty,
          !snapshot.complete, snapshot.repositories.isEmpty,
          snapshot.issues.map(\.reason).sorted() == reasons.sorted() else { throw HostServiceError.invalidResponse }
        report["boundedUnknownNotInventedAsNonGit"] = true
      } else {
        guard snapshot.complete else { throw HostServiceError.invalidResponse }
      }
      if expectedKind == "git" {
        let item = try XCTUnwrap(snapshot.items.first)
        guard item.tree.state == "ready", item.tree.changes != nil else { throw HostServiceError.invalidResponse }
        if let expectedHead = config["expectedHead"] as? String { XCTAssertEqual(item.tree.head, expectedHead) }
        let pageBytes = try await AgentClients.resolve(client).projectGitFiles(client, projectId: projectId,
          target: target, snapshotId: snapshot.snapshotId, worktreeId: item.tree.id, cursor: nil)
        let validated = try ProjectGitRules.shared.files(json: String(decoding: pageBytes, as: UTF8.self),
          targetJson: targetJSON, projectId: projectId, snapshotId: snapshot.snapshotId, worktreeId: item.tree.id)
        let page = try JSONDecoder().decode(ProjectGitFilePage.self, from: Data(validated.utf8))
        if let expectedFiles = config["expectedFiles"] as? [String] {
          XCTAssertEqual(page.files.map(\.path).sorted(), expectedFiles.sorted())
          XCTAssertNil(page.nextCursor)
        }
        if !currentSnapshotBoundaryProbe { report["actualFilePagePrivate"] = try JSONSerialization.jsonObject(with: pageBytes) }
        if currentSnapshotBoundaryProbe {
          guard page.nextCursor == nil, page.files.count < 999999 else { throw HostServiceError.invalidResponse }
          let wrongWorktree = "missing-" + run
          guard !snapshot.items.contains(where: { $0.tree.id == wrongWorktree }) else { throw HostServiceError.invalidResponse }
          let provider = try AgentClients.resolve(client)
          var wrongWorktreeCode: String?, outOfRangeCode: String?
          do {
            _ = try await provider.projectGitFiles(client, projectId: projectId, target: target,
              snapshotId: snapshot.snapshotId, worktreeId: wrongWorktree, cursor: nil)
          } catch { wrongWorktreeCode = (error as? EasyWorkRemoteRejection)?.code }
          report["currentSnapshotWrongWorktreeRejected"] = wrongWorktreeCode
          try save()
          guard wrongWorktreeCode == "project_git_worktree_not_found" else { throw HostServiceError.invalidResponse }
          do {
            _ = try await provider.projectGitFiles(client, projectId: projectId, target: target,
              snapshotId: snapshot.snapshotId, worktreeId: item.tree.id, cursor: "999999")
          } catch { outOfRangeCode = (error as? EasyWorkRemoteRejection)?.code }
          report["currentSnapshotOutOfRangeCursorRejected"] = outOfRangeCode
          report["validCurrentSnapshotUsedForBothRejectedPages"] = true
          report["normalPageFileCount"] = page.files.count
          report["normalPageSHA256"] = HostService.hash(pageBytes)
          report["snapshotSHA256"] = HostService.hash(Data(snapshot.snapshotId.utf8))
          report["worktreeSHA256"] = HostService.hash(Data(item.tree.id.utf8))
          try save()
          guard outOfRangeCode == "invalid_request" else { throw HostServiceError.invalidResponse }
        }
        if staleSnapshotProbe {
          let provider = try AgentClients.resolve(client)
          // The real collector coalesces forced refreshes for one second.
          // Wait past that sample window, then make one refresh read.
          report["stage"] = "waiting-for-server-refresh-coalescing-window"
          report["refreshCoalescingWaitMilliseconds"] = 1100; try save()
          try await Task.sleep(for: .milliseconds(1100))
          let refreshedBytes = try await provider.projectGit(client, projectId: projectId, target: target, refresh: true)
          let refreshedProjection = try ProjectGitRules.shared.project(json: String(decoding: refreshedBytes, as: UTF8.self),
            targetJson: targetJSON, projectId: projectId)
          let refreshed = try JSONDecoder().decode(ProjectGitSnapshot.self, from: Data(refreshedProjection.utf8))
          report["freshSnapshotSHA256"] = HostService.hash(Data(refreshed.snapshotId.utf8))
          report["refreshProducedDifferentSnapshot"] = refreshed.snapshotId != snapshot.snapshotId; try save()
          guard refreshed.complete, refreshed.kind == "git", refreshed.projectId == projectId,
            refreshed.snapshotId != snapshot.snapshotId,
            let freshItem = refreshed.items.first(where: { $0.tree.id == item.tree.id }),
            freshItem.tree.head == item.tree.head else { throw HostServiceError.invalidResponse }
          report["stage"] = "old-snapshot-invalidated-by-normal-read-only-refresh"
          report["originalSnapshotSHA256"] = HostService.hash(Data(snapshot.snapshotId.utf8))
          report["freshSnapshotSHA256"] = HostService.hash(Data(refreshed.snapshotId.utf8)); try save()
          var staleCode: String?
          do {
            _ = try await provider.projectGitFiles(client, projectId: projectId, target: target,
              snapshotId: snapshot.snapshotId, worktreeId: item.tree.id, cursor: nil)
          } catch { staleCode = (error as? EasyWorkRemoteRejection)?.code }
          guard staleCode == "project_git_snapshot_expired" else { throw HostServiceError.invalidResponse }
          let freshBytes = try await provider.projectGitFiles(client, projectId: projectId, target: target,
            snapshotId: refreshed.snapshotId, worktreeId: freshItem.tree.id, cursor: nil)
          let freshValidated = try ProjectGitRules.shared.files(json: String(decoding: freshBytes, as: UTF8.self),
            targetJson: targetJSON, projectId: projectId, snapshotId: refreshed.snapshotId, worktreeId: freshItem.tree.id)
          let freshPage = try JSONDecoder().decode(ProjectGitFilePage.self, from: Data(freshValidated.utf8))
          guard freshPage.files.map(\.path).sorted() == page.files.map(\.path).sorted(),
            freshPage.nextCursor == page.nextCursor else { throw HostServiceError.invalidResponse }
          report["staleSnapshotRejected"] = staleCode
          report["freshSnapshotPageMatchesOriginalUnchangedGitContent"] = true
          report["actualFreshFilePagePrivate"] = try JSONSerialization.jsonObject(with: freshBytes)
        }
      } else if expectedKind == "non-git" {
        XCTAssertTrue(snapshot.repositories.isEmpty); XCTAssertTrue(snapshot.issues.isEmpty)
        report["nonGitNotInventedAsEmptyRepository"] = true
      }
      try save()
      var wrong = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(targetJSON.utf8)) as? [String: Any])
      wrong["hostId"] = target.hostId + "-wrong"
      var code: String?
      do { _ = try await channel.request("project/git/status", ["schemaVersion": 1, "target": wrong, "projectId": projectId]) }
      catch { code = (error as? EasyWorkRemoteRejection)?.code }
      guard code == "target_forbidden" else { throw HostServiceError.wrongHost }
      report["wrongInnerTargetRejected"] = code
      guard try CredentialVault.existingToken("easywork-device-identity") == identity,
        try encoder.encode(store.drafts.values) == drafts, try encoder.encode(store.saved.outbox) == outbox,
        store.saved.replyRecovery?.mapValues(\.fingerprint) == recovery,
        store.saved.selectedClientId == selected, store.selectedThreadId == thread,
        store.selectedProjectId == project else { throw HostServiceError.invalidResponse }
      report["originalIdentityDraftsOutboxRecoverySelectionUnchanged"] = true
      report["status"] = "passed-original-git-protocol-and-wrong-target-boundary-only"; try save()
    } catch {
      report["status"] = "failed-no-model-or-business-replay"; report["failure"] = error.localizedDescription
      try save(); throw error
    }
  }

  @MainActor func testOriginalEasyWorkFrozenPlanActionThroughProductionProvider() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_PLAN_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen own plan action required") }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run,
      let action = config["planAction"] as? String, ["create", "revise", "approve", "reject-stale", "read"].contains(action),
      let owner = config["fixtureOwnerId"] as? String, UUID(uuidString: owner) != nil,
      let operation = config["sendOperationId"] as? String, UUID(uuidString: operation) != nil,
      let body = config["body"] as? String,
      HostService.hash(Data(body.utf8)) == config["bodySHA256"] as? String,
      let directory = config["ownedDirectory"] as? String, directory.hasPrefix("/"),
      (directory as NSString).lastPathComponent == "i7-plan-contract-" + owner,
      let filename = config["ownedFilename"] as? String, filename == "i7-plan-proof-" + owner + ".txt",
      let marker = config["finalMarker"] as? String, marker == "I7_PLAN_DONE_" + owner,
      config["newModelInputsAuthorized"] as? Int == (["reject-stale", "read"].contains(action) ? 0 : 1),
      config["newThreadsAuthorized"] as? Int == (action == "create" ? 1 : 0),
      config["doNotOverrideGenerationPreferences"] as? Bool == true else { throw HostServiceError.invalidResponse }
    let maximumResultWaitSeconds = config["maximumResultWaitSeconds"] as? Int ?? 180
    guard [180, 600].contains(maximumResultWaitSeconds) else { throw HostServiceError.invalidResponse }
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let preferences = try encoder.encode(store.saved.generationPreferences)
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let selected = store.saved.selectedClientId, threadSelection = store.selectedThreadId, projectSelection = store.selectedProjectId
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "planAction": action,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "appPID": ProcessInfo.processInfo.processIdentifier,
      "sendOperationId": operation, "modelSubmissionAttempts": 0, "wholeFeaturePassed": false,
      "pureUIReaccepted": false, "status": "prepared-no-action"]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func fields(_ plan: ProposedPlan) throws -> (id: String, version: Int) {
      guard plan.id.hasPrefix("easywork-plan."),
        let bytes = Data(base64URL: String(plan.id.dropFirst(14))),
        let value = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
        let id = value["id"] as? String, !id.isEmpty,
        let version = value["version"] as? Int, version > 0 else { throw HostServiceError.invalidResponse }
      return (id, version)
    }
    func protectOriginal() throws {
      guard try CredentialVault.existingToken("easywork-device-identity") == identity,
        try encoder.encode(store.drafts.values) == drafts, try encoder.encode(store.saved.outbox) == outbox,
        try encoder.encode(store.saved.generationPreferences) == preferences,
        store.saved.replyRecovery?.mapValues(\.fingerprint) == recovery,
        store.saved.selectedClientId == selected, store.selectedThreadId == threadSelection,
        store.selectedProjectId == projectSelection else { throw HostServiceError.invalidResponse }
      report["originalIdentityDraftsOutboxRecoveryPreferencesSelectionUnchanged"] = true
    }
    do {
      try save()
      let provider = try AgentClients.resolve(client)
      let thread: String
      var expectedPlan: String?
      if action == "create" {
        guard config["ownedDirectoryEmptyIndependentlyChecked"] as? Bool == true,
          body.contains(directory + "/" + filename), body.contains(marker),
          let createOperation = config["createOperationId"] as? String,
          UUID(uuidString: createOperation) != nil, createOperation != operation else { throw HostServiceError.invalidResponse }
        report["createOperationId"] = createOperation
        report["status"] = "one-owned-projectless-thread-about-to-create"; try save()
        let created = try await provider.createThread(client, projectId: nil, operationId: createOperation)
        thread = created.id
      } else {
        thread = try XCTUnwrap(config["threadId"] as? String)
        guard config["ownedThreadIndependentlyVerified"] as? Bool == true,
          let expectedId = config["expectedPlanId"] as? String,
          let expectedVersion = config["expectedPlanVersion"] as? Int,
          let opaque = config["expectedPlanOpaque"] as? String else { throw HostServiceError.invalidResponse }
        let declared = try fields(ProposedPlan(id: opaque, turnId: "", text: ""))
        guard declared.id == expectedId, declared.version == expectedVersion,
          UUID(uuidString: thread) != nil else { throw HostServiceError.invalidResponse }
        let initial = try await provider.history(client, threadId: thread, cursor: nil)
        let current = try XCTUnwrap(initial.runtime?.proposedPlan)
        let actual = try fields(current)
        guard initial.runtime?.state == "idle" else { throw HostServiceError.invalidResponse }
        if action == "reject-stale" {
          guard config["currentPlanId"] as? String == actual.id,
            config["currentPlanVersion"] as? Int == actual.version,
            expectedId != actual.id || expectedVersion != actual.version else { throw HostServiceError.invalidResponse }
        } else {
          guard current.id == opaque, actual.id == expectedId, actual.version == expectedVersion else { throw HostServiceError.invalidResponse }
        }
        expectedPlan = opaque
      }
      report["threadId"] = thread
      if action == "read" {
        let history = try await provider.history(client, threadId: thread, cursor: nil)
        let plan = try XCTUnwrap(history.runtime?.proposedPlan)
        let actual = try fields(plan)
        let artifact = try XCTUnwrap(history.messages.compactMap(\.planArtifact).last(where: { $0.id == actual.id }))
        guard history.runtime?.state == "idle", plan.id == expectedPlan,
          actual.id == config["expectedPlanId"] as? String,
          actual.version == config["expectedPlanVersion"] as? Int,
          artifact.version == actual.version, ["ready", "saved"].contains(artifact.status) else { throw HostServiceError.invalidResponse }
        report["planId"] = actual.id; report["planVersion"] = actual.version; report["planOpaque"] = plan.id
        report["planMarkdownSHA256"] = HostService.hash(Data(plan.text.utf8)); report["planStatus"] = artifact.status
        report["requestSubmissionAttempts"] = 0
        try protectOriginal()
        report["status"] = "passed-original-owned-ready-plan-read-only-no-input"; try save(); return
      }
      var input = PendingReply(hostId: client.id, threadId: thread, clientMessageId: operation,
        text: body, attachmentIds: [], mode: "next-turn", expectedTurnId: nil,
        model: nil, effort: nil, sourceAttachments: nil, knownRejected: nil)
      input.collaborationMode = ["create", "revise"].contains(action) ? "plan" : "default"
      input.expectedPlanId = expectedPlan
      report["frozenInputSHA256"] = HostService.hash(try encoder.encode(input))
      report["status"] = "one-frozen-action-about-to-submit"
      report["modelSubmissionAttempts"] = action == "reject-stale" ? 0 : 1
      report["requestSubmissionAttempts"] = 1; try save()
      if action == "reject-stale" {
        var code: String?
        do { _ = try await provider.send(client, reply: input) }
        catch { code = (error as? EasyWorkRemoteRejection)?.code }
        report["stalePlanRejection"] = code
        guard code == "stale_plan" else { throw HostServiceError.invalidResponse }
      } else {
        let receipt = try await provider.send(client, reply: input)
        guard receipt.clientMessageId == operation, receipt.threadId == thread,
          ["submitted", "completed"].contains(receipt.state), let root = receipt.turnId, !root.isEmpty else { throw HostServiceError.invalidResponse }
        report["rootRunId"] = root; report["status"] = "accepted-query-only-no-replay"; try save()
        report["maximumResultWaitSeconds"] = maximumResultWaitSeconds; try save()
        let end = Date().addingTimeInterval(TimeInterval(maximumResultWaitSeconds))
        var final: HistorySnapshot?
        while Date() < end {
          let history = try await provider.history(client, threadId: thread, cursor: nil)
          if ["failed", "cancelled", "interrupted"].contains(history.runtime?.state ?? "") { throw HostServiceError.invalidResponse }
          if history.runtime?.state == "idle" {
            if action == "approve", history.runtime?.proposedPlan == nil,
              history.messages.last(where: { $0.role == "assistant" && $0.kind != "plan" && !$0.text.isEmpty })?.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker {
              final = history; break
            }
            if action != "approve", let plan = history.runtime?.proposedPlan {
              let actual = try fields(plan)
              if actual.version == config["expectedReturnedPlanVersion"] as? Int,
                action != "revise" || actual.id != config["expectedPlanId"] as? String {
                final = history; break
              }
            }
          }
          try await Task.sleep(for: .milliseconds(500))
        }
        let history = try XCTUnwrap(final, "Original plan action did not reach its expected terminal projection; query original operation, never replay")
        if let plan = history.runtime?.proposedPlan {
          let actual = try fields(plan)
          let artifact = try XCTUnwrap(history.messages.compactMap(\.planArtifact).last(where: { $0.id == actual.id }))
          guard artifact.version == actual.version, ["ready", "saved"].contains(artifact.status) else { throw HostServiceError.invalidResponse }
          report["planId"] = actual.id; report["planVersion"] = actual.version; report["planOpaque"] = plan.id
          report["planMarkdownSHA256"] = HostService.hash(Data(plan.text.utf8))
          report["planStatus"] = artifact.status
        } else {
          report["exactFinalMarkerInNewestAssistantText"] = true
          report["desktopFinalOutputAndOwnedFileRequireIndependentVerification"] = true
        }
      }
      try protectOriginal()
      report["status"] = "passed-one-original-production-provider-plan-action"; try save()
    } catch {
      report["status"] = "failed-preserve-original-plan-operation-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testOriginalEasyWorkZeroModelFileErrorsOrOwnedPlanArchive() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_ZERO_MODEL_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen zero-model integration case required") }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run,
      let action = config["caseAction"] as? String,
      ["file-rejections", "archive-own-completed-plan", "read-own-completed-plan-archive", "archive-own-completed-control"].contains(action),
      config["newModelInputsAuthorized"] as? Int == 0,
      config["newThreadsAuthorized"] as? Int == 0,
      config["newBusinessCommandsAuthorized"] as? Int == (action == "read-own-completed-plan-archive" ? 0 : 1),
      let operation = config["operationId"] as? String, !operation.isEmpty else { throw HostServiceError.invalidResponse }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let preferences = try encoder.encode(store.saved.generationPreferences)
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let selected = store.saved.selectedClientId, threadSelection = store.selectedThreadId, projectSelection = store.selectedProjectId
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "caseAction": action,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "appPID": ProcessInfo.processInfo.processIdentifier,
      "operationId": operation, "newModels": 0, "newThreads": 0, "wholeFeaturePassed": false,
      "businessCommandSubmissionAttempts": 0, "pureUIReaccepted": false, "status": "prepared-no-command"]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    do {
      try save()
      let provider = try AgentClients.resolve(client)
      let channel = try await EasyWorkSessions.shared.session(client)
      if action == "file-rejections" {
        guard let transfer = config["missingOwnTransferId"] as? String, UUID(uuidString: transfer) != nil,
          operation == "file-chunk-" + transfer + "-0",
          config["originalTransferIndependentlyAbsent"] as? Bool == true else { throw HostServiceError.invalidResponse }
        let before = try await provider.fileTransfers(client)
        guard before.hostId == client.id, !before.files.contains(where: { $0.id == transfer }) else { throw HostServiceError.invalidResponse }
        let originalFiles = try encoder.encode(before.files.sorted { $0.id < $1.id })
        func expectAuthenticatedMissingStatus() async throws {
          var absent = false
          do { _ = try await provider.fileTransfer(client, id: transfer) }
          catch let error as HostServiceError {
            guard case .rejected(404, _) = error else { throw error }
            absent = true
          }
          guard absent else { throw HostServiceError.invalidResponse }
        }
        try await expectAuthenticatedMissingStatus()
        report["authenticatedMissingStatusMapsTo404"] = true
        let parameters: [String: Any] = ["transferId": transfer, "offset": 0,
          "dataBase64": Data([0x49, 0x37]).base64URL]
        report["status"] = "one-unprepared-owned-chunk-about-to-submit"
        report["businessCommandSubmissionAttempts"] = 1; try save()
        var directCode: String?, directMessage: String?
        do { _ = try await channel.request("resource/upload-chunk", parameters, id: operation) }
        catch let error as EasyWorkRemoteRejection { directCode = error.code; directMessage = error.message }
        report["directAuthenticatedRejectionCode"] = directCode; try save()
        guard directCode == "transfer_not_found" else { throw HostServiceError.invalidResponse }
        let record = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
        report["actualCanonicalRecordSHA256"] = HostService.hash(try easyWorkJSON(record))
        report["actualCanonicalRecordState"] = record["state"] as? String; try save()
        let wireTarget = await channel.target, mobile = await channel.mobileDeviceId
        var canonicalCode: String?, canonicalMessage: String?
        do {
          _ = try EasyWorkRemoteSession.verifiedWriteResult(record, target: wireTarget,
            mobileDeviceId: mobile, operationId: operation, method: "resource/upload-chunk", params: parameters)
        } catch let error as EasyWorkRemoteRejection { canonicalCode = error.code; canonicalMessage = error.message }
        guard canonicalCode == directCode, canonicalMessage == directMessage else { throw HostServiceError.invalidResponse }
        report["actualFailedCanonicalReceiptPreservesRejectionCodeAndMessage"] = true
        report["canonicalAuthenticatedRejectionCode"] = canonicalCode
        report["failedCanonicalDecodedDirectlyNoSyntheticTransportLossClaimed"] = true
        try await expectAuthenticatedMissingStatus()
        let after = try await provider.fileTransfers(client)
        guard after.destinationDirectory == before.destinationDirectory,
          after.maxFileBytes == before.maxFileBytes,
          try encoder.encode(after.files.sorted { $0.id < $1.id }) == originalFiles else { throw HostServiceError.invalidResponse }
        report["originalFileCatalogSHA256"] = HostService.hash(originalFiles)
        report["originalFileCatalogUnchangedAndMissingTransferStillAbsent"] = true
        report["prepareOrCompleteCommandsSubmitted"] = 0
        report["actualFileUploadOrNewTransferClaimed"] = false
      } else if action == "archive-own-completed-control" {
        guard UUID(uuidString: operation) != nil,
          let owner = config["fixtureOwnerId"] as? String, UUID(uuidString: owner) != nil,
          let thread = config["ownedCompletedThreadId"] as? String, UUID(uuidString: thread) != nil,
          let root = config["ownedCompletedRootId"] as? String, UUID(uuidString: root) != nil,
          let marker = config["ownedCompletedFinalMarker"] as? String, marker == "I7_CONTROL_" + owner + "_RESUMED",
          config["ownedStopAndContinueRootsIndependentlyQualified"] as? Bool == true else { throw HostServiceError.invalidResponse }
        let history = try await provider.history(client, threadId: thread, cursor: nil)
        guard history.runtime?.state == "idle",
          history.messages.contains(where: { $0.runId == root && $0.role == "assistant" && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker }),
          history.messages.contains(where: { $0.role == "user" && $0.text.contains("I7_CONTROL_" + owner) }) else { throw HostServiceError.invalidResponse }
        let before = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
        guard before.threads.first(where: { $0.id == thread })?.archived == false else { throw HostServiceError.invalidResponse }
        report["ownedCompletedThreadId"] = thread
        report["status"] = "one-exact-owned-completed-control-thread-about-to-archive"
        report["businessCommandSubmissionAttempts"] = 1; try save()
        let result = try easyWorkObject(await channel.request("thread/archive", ["threadId": thread, "archived": true], id: operation))
        guard result["archived"] as? Bool == true else { throw HostServiceError.invalidResponse }
        let after = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
        guard after.threads.first(where: { $0.id == thread })?.archived == true else { throw HostServiceError.invalidResponse }
        report["ownCompletedControlThreadArchivedInAllWorkspace"] = true
        report["otherThreadArchiveRequestsSubmitted"] = 0
      } else if action == "read-own-completed-plan-archive" {
        guard let thread = config["ownedCompletedThreadId"] as? String, UUID(uuidString: thread) != nil,
          config["originalArchiveIndependentlyCompleted"] as? Bool == true else { throw HostServiceError.invalidResponse }
        let record = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
        let result = try easyWorkObject(EasyWorkRemoteSession.verifiedWriteResult(record,
          target: await channel.target, mobileDeviceId: await channel.mobileDeviceId,
          operationId: operation, method: "thread/archive", params: ["threadId": thread, "archived": true]))
        guard result["archived"] as? Bool == true else { throw HostServiceError.invalidResponse }
        report["originalCanonicalArchiveReceiptVerifiedWithoutResubmission"] = true; try save()
        // The production catalog intentionally includes archived conversations.
        let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
        guard workspace.threads.first(where: { $0.id == thread })?.archived == true else { throw HostServiceError.invalidResponse }
        let active = try easyWorkObject(await channel.request("thread/list", ["limit": 100, "status": "active"]))
        let threads = try XCTUnwrap(active["threads"] as? [[String: Any]])
        guard !threads.contains(where: { $0["id"] as? String == thread }) else { throw HostServiceError.invalidResponse }
        report["originalOwnThreadArchivedInAllCatalogAndAbsentFromActiveCatalog"] = true
        report["newArchiveRequestsSubmitted"] = 0
      } else {
        guard UUID(uuidString: operation) != nil,
          let owner = config["fixtureOwnerId"] as? String, UUID(uuidString: owner) != nil,
          let thread = config["ownedCompletedThreadId"] as? String, UUID(uuidString: thread) != nil,
          let planId = config["ownedCompletedPlanId"] as? String,
          config["ownedCompletedPlanVersion"] as? Int == 2,
          let marker = config["ownedCompletedFinalMarker"] as? String, marker == "I7_PLAN_DONE_" + owner,
          config["ownedThreadAndFinalModelIndependentlyCompleted"] as? Bool == true else { throw HostServiceError.invalidResponse }
        let history = try await provider.history(client, threadId: thread, cursor: nil)
        let plan = try XCTUnwrap(history.messages.compactMap(\.planArtifact).last)
        guard history.runtime?.state == "idle", history.runtime?.proposedPlan == nil,
          plan.id == planId, plan.version == 2, plan.status == "completed",
          history.messages.last(where: { $0.role == "assistant" && $0.kind != "plan" && !$0.text.isEmpty })?.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker else { throw HostServiceError.invalidResponse }
        let before = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
        guard before.threads.contains(where: { $0.id == thread }) else { throw HostServiceError.invalidResponse }
        report["ownedCompletedThreadId"] = thread
        report["status"] = "one-exact-owned-completed-thread-about-to-archive"
        report["businessCommandSubmissionAttempts"] = 1; try save()
        _ = try await channel.request("thread/archive", ["threadId": thread, "archived": true], id: operation)
        let after = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
        guard after.threads.first(where: { $0.id == thread })?.archived == true else { throw HostServiceError.invalidResponse }
        report["ownCompletedThreadArchivedInAllWorkspace"] = true
        report["otherThreadArchiveRequestsSubmitted"] = 0
      }
      guard try CredentialVault.existingToken("easywork-device-identity") == identity,
        try encoder.encode(store.drafts.values) == drafts, try encoder.encode(store.saved.outbox) == outbox,
        try encoder.encode(store.saved.generationPreferences) == preferences,
        store.saved.replyRecovery?.mapValues(\.fingerprint) == recovery,
        store.saved.selectedClientId == selected, store.selectedThreadId == threadSelection,
        store.selectedProjectId == projectSelection else { throw HostServiceError.invalidResponse }
      report["originalIdentityDraftsOutboxRecoveryPreferencesSelectionUnchanged"] = true
      report["status"] = "passed-frozen-zero-model-integration-case"; try save()
    } catch {
      report["status"] = "failed-preserve-original-zero-model-operation-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testConsumedOriginalReplyAndDelayedReceiptPreserveNextDraftWithoutSending() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Explicit original-reply examiner required") }
    let config = try easyWorkObject(Data(raw.utf8))
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      config["newRemoteCommandsAuthorized"] as? Int == 0,
      let operation = config["originalOperationId"] as? String,
      let sourceID = config["ownedThreadId"] as? String,
      let rootID = config["ownedRootRunId"] as? String,
      let otherID = config["otherOwnedThreadId"] as? String, otherID != sourceID,
      let otherRoot = config["otherOwnedRootRunId"] as? String,
      let fingerprint = config["originalFingerprint"] as? String,
      let sourceDraft = config["nextSourceDraft"] as? String, sourceDraft == "F61_SOURCE_" + run,
      let otherDraft = config["nextOtherDraft"] as? String, otherDraft == "F61_OTHER_" + run else {
      throw HostServiceError.invalidResponse
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: easyWorkJSON(try XCTUnwrap(config["target"])))
    let ready = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < ready {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired, target.agentId == "easywork",
      store.client?.agentConnection?.target == target else { throw HostServiceError.wrongHost }
    if store.client?.online != true { await store.refresh(includeHistory: false, userInitiated: true) }
    let client = try XCTUnwrap(store.client), connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard client.online, connection.target == target,
      try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId),
      let captured = store.saved.replyRecovery?[operation],
      captured.target == target, captured.fingerprint == fingerprint, captured.retryConsumed,
      captured.original.hostId == client.id, captured.original.threadId == sourceID,
      captured.original.clientMessageId == operation,
      ReplyRecoveryPolicy.sameIntent(captured.original, record: captured) else { throw HostServiceError.invalidResponse }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    func bytes<T: Encodable>(_ value: T) throws -> Data { try encoder.encode(value) }
    let base = try AgentClients.resolve(client)
    let source = try await base.history(client, threadId: sourceID, cursor: nil)
    let other = try await base.history(client, threadId: otherID, cursor: nil)
    let sourceThread = try XCTUnwrap(source.thread), otherThread = try XCTUnwrap(other.thread)
    guard sourceThread.archived == true, otherThread.archived == true,
      source.messages.contains(where: { $0.runId == rootID && $0.role == "assistant" }),
      other.messages.contains(where: { $0.runId == otherRoot && $0.role == "assistant" }),
      (store.hostedEasyWorkReadLoopState["replyRecoveryFlightCount"] as? Int) == 0 else {
      throw HostServiceError.invalidResponse
    }
    let originalThread = store.conversation, originalThreadID = store.selectedThreadId
    let originalProject = store.selectedProjectId, originalClient = store.saved.selectedClientId
    let originalLastThread = store.saved.lastThreads[client.id]
    let originalLastProject = store.saved.lastThreadProjects?[client.id]
    let originalLastProjectsWasNil = store.saved.lastThreadProjects == nil
    let originalNewProject = store.saved.newProjects[client.id]
    let originalDrafts = store.drafts.values, originalOutbox = store.saved.outbox
    let originalRecovery = try bytes(store.saved.replyRecovery)
    let originalPreferences = try bytes(store.saved.generationPreferences)
    let originalConnection = try bytes(connection)
    let originalIdentity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let originalError = store.error
    var report: [String: Any] = ["caseId": run, "newModelInputs": 0, "newRemoteCommands": 0,
      "wholeFeaturePassed": false, "pureUIReaccepted": false,
      "originalOperationId": operation, "threadId": sourceID, "rootRunId": rootID,
      "status": "prepared-original-consumed-reply-read-only"]
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    let gate = EasyWorkOriginalReceiptGate(originalID: operation)
    var sourceKey: String?, otherKey: String?, restored = false
    func restoreLocalState() throws {
      guard !restored else { return }
      var changedByAnotherWriter = false
      for key in [sourceKey, otherKey].compactMap({ $0 }) {
        if let draft = store.drafts.values[key], draft.text != sourceDraft && draft.text != otherDraft,
          !draft.text.isEmpty || !draft.attachments.isEmpty,
          try bytes(Optional(draft)) != bytes(originalDrafts[key]) {
          changedByAnotherWriter = true; continue
        }
        if let pending = store.saved.outbox?[key], pending.clientMessageId != operation,
          try bytes(Optional(pending)) != bytes(originalOutbox?[key]) {
          changedByAnotherWriter = true; continue
        }
        store.drafts.values[key] = originalDrafts[key]
        store.saved.outbox?[key] = originalOutbox?[key]
      }
      if originalOutbox == nil, store.saved.outbox?.isEmpty == true { store.saved.outbox = nil }
      if let originalThread { store.openThread(originalThread, loadAutomatically: false) }
      store.saved.selectedClientId = originalClient
      store.selectedThreadId = originalThreadID; store.selectedProjectId = originalProject
      store.saved.lastThreads[client.id] = originalLastThread
      store.saved.lastThreadProjects?[client.id] = originalLastProject
      if originalLastProjectsWasNil, store.saved.lastThreadProjects?.isEmpty == true { store.saved.lastThreadProjects = nil }
      store.saved.newProjects[client.id] = originalNewProject
      store.error = originalError; store.persist(reconcileUnread: false)
      guard !changedByAnotherWriter else { throw HostServiceError.invalidResponse }
      restored = true
    }
    func wait(_ predicate: () -> Bool) async throws {
      let end = Date().addingTimeInterval(20)
      while !predicate(), Date() < end { try await Task.sleep(for: .milliseconds(50)) }
      guard predicate() else { throw HostServiceError.invalidResponse }
    }
    AgentClients.register("easywork", client: EasyWorkReceiptGateProvider(base: base, gate: gate,
      originalID: operation, originalThread: sourceID, originalRoot: rootID))
    defer {
      AgentClients.register("easywork", client: base)
      try? restoreLocalState()
      try? save()
    }
    do {
      try save()
      // These comparisons use copies only; they never manufacture retry authority in the Store.
      var fenced = captured; fenced.phase = "not-written"
      guard !ReplyRecoveryPolicy.mayRetry(captured.original, record: fenced) else { throw HostServiceError.invalidResponse }
      fenced.retryConsumed = false
      guard ReplyRecoveryPolicy.mayRetry(captured.original, record: fenced),
        !ReplyRecoveryPolicy.mayRetry(captured.original, record: ReplyRecoveryPolicy.imported(captured)) else {
        throw HostServiceError.invalidResponse
      }
      report["coldOriginalConsumedFenceAndImportedQueryOnlyVerified"] = true
      store.openThread(sourceThread, loadAutomatically: false); sourceKey = store.targetKey
      let firstKey = try XCTUnwrap(sourceKey)
      guard originalOutbox?[firstKey] == nil,
        originalDrafts[firstKey]?.text.isEmpty != false,
        originalDrafts[firstKey]?.attachments.isEmpty != false else { throw HostServiceError.invalidResponse }
      if store.saved.outbox == nil { store.saved.outbox = [:] }
      store.saved.outbox?[firstKey] = captured.original
      store.setText(sourceDraft, target: firstKey)
      guard !store.canRetryPending else { throw HostServiceError.invalidResponse }
      store.retryPending(); store.retryPending()
      let holdDeadline = Date().addingTimeInterval(20)
      while !(await gate.witness()).held, Date() < holdDeadline { try await Task.sleep(for: .milliseconds(50)) }
      let held = await gate.witness()
      guard held.held, held.originalQueries == 1, held.delivered == 0,
        (store.hostedEasyWorkReadLoopState["replyRecoveryFlightCount"] as? Int) == 1 else {
        throw HostServiceError.invalidResponse
      }
      report["doubleConsumedRetryOnlyQueriedOriginalOnce"] = true
      report["actualVerifiedReceiptHeldSHA256"] = held.receiptSHA256
      let receiptBytes = await gate.capturedBytes()
      report["verifiedOriginalReceipt"] = try JSONSerialization.jsonObject(with: try XCTUnwrap(receiptBytes))
      store.openThread(otherThread, loadAutomatically: false); otherKey = store.targetKey
      let secondKey = try XCTUnwrap(otherKey)
      guard secondKey != firstKey, originalOutbox?[secondKey] == nil,
        originalDrafts[secondKey]?.text.isEmpty != false,
        originalDrafts[secondKey]?.attachments.isEmpty != false else { throw HostServiceError.invalidResponse }
      store.setText(otherDraft, target: secondKey)
      await gate.release()
      try await wait { (store.hostedEasyWorkReadLoopState["replyRecoveryFlightCount"] as? Int) == 0 }
      guard store.targetKey == secondKey, store.draft.text == otherDraft,
        store.drafts.values[firstKey]?.text == sourceDraft,
        try bytes(store.saved.outbox?[firstKey]) == bytes(captured.original) else {
        throw HostServiceError.invalidResponse
      }
      report["delayedOriginalReceiptDidNotAdoptIntoChangedThreadOrNextDraft"] = true
      store.openThread(sourceThread, loadAutomatically: false)
      guard store.targetKey == firstKey, !store.canRetryPending else { throw HostServiceError.invalidResponse }
      store.reconcilePending()
      try await wait {
        (store.hostedEasyWorkReadLoopState["replyRecoveryFlightCount"] as? Int) == 0
          && store.saved.outbox?[firstKey] == nil && !store.historyLoading
      }
      let final = await gate.witness()
      guard final.originalQueries == 2, final.delivered == 2, final.blockedWrites.isEmpty,
        store.drafts.values[firstKey]?.text == sourceDraft,
        store.drafts.values[secondKey]?.text == otherDraft,
        try bytes(store.saved.replyRecovery) == originalRecovery else { throw HostServiceError.invalidResponse }
      report["freshOriginalQueryClearedOnlyOwnedWaitAndKeptBothNextDrafts"] = true
      report["actualOriginalReceiptQueries"] = final.originalQueries
      report["blockedMutationCalls"] = final.blockedWrites
      await gate.cancel()
      try restoreLocalState()
      guard try bytes(store.drafts.values) == bytes(originalDrafts),
        try bytes(store.saved.outbox) == bytes(originalOutbox),
        try bytes(store.saved.replyRecovery) == originalRecovery,
        try bytes(store.saved.generationPreferences) == originalPreferences,
        try bytes(store.client?.agentConnection) == bytes(Optional(connection)),
        try bytes(connection) == originalConnection,
        try CredentialVault.existingToken("easywork-device-identity") == originalIdentity,
        store.saved.selectedClientId == originalClient, store.selectedThreadId == originalThreadID else {
        throw HostServiceError.invalidResponse
      }
      report["originalIdentityConnectionDraftsOutboxRecoveryPreferencesAndNavigationRestored"] = true
      report["status"] = "passed-consumed-original-and-late-real-receipt-without-send"
      try save()
    } catch {
      await gate.cancel()
      try? await wait { (store.hostedEasyWorkReadLoopState["replyRecoveryFlightCount"] as? Int) == 0 }
      report["status"] = "failed-original-reply-read-only-no-replay"
      report["failure"] = error.localizedDescription
      throw error
    }
  }

  @MainActor func testEasyWorkActualLocalNotWrittenRecoveryAndOneExplicitRetry() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_LOCAL_REPLY_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen local-reply integration case required") }
    let config = try easyWorkObject(Data(raw.utf8))
    guard config["caseId"] as? String == run,
      let action = config["replyAction"] as? String, ["prepare", "retry", "archive"].contains(action),
      config["newModelInputsAuthorized"] as? Int == (action == "retry" ? 1 : 0),
      config["newThreadsAuthorized"] as? Int == (action == "prepare" ? 1 : 0),
      let body = config["body"] as? String,
      HostService.hash(Data(body.utf8)) == config["bodySHA256"] as? String,
      let marker = config["finalMarker"] as? String, marker.hasPrefix("I8_LOCAL_REPLY_") else { throw HostServiceError.invalidResponse }
    let ready = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < ready { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target, clients[0].id != target.hostId else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let oldDrafts = store.drafts.values, oldOutbox = store.saved.outbox ?? [:]
    let oldRecovery = store.saved.replyRecovery ?? [:]
    let preferences = try encoder.encode(store.saved.generationPreferences)
    let originalThread = store.conversation, originalThreadId = store.selectedThreadId
    let originalProject = store.selectedProjectId, originalClient = store.saved.selectedClientId
    let lastThreads = store.saved.lastThreads, lastProjects = store.saved.lastThreadProjects
    let newProjects = store.saved.newProjects
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "replyAction": action,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "appPID": ProcessInfo.processInfo.processIdentifier,
      "localClientId": client.id, "authenticatedWireHostId": target.hostId,
      "wholeFeaturePassed": false, "pureUIReaccepted": false,
      "newModelInputBudget": action == "retry" ? 1 : 0, "status": "prepared-no-action"]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func wait(_ label: String, seconds: Double = 45, _ predicate: () -> Bool) async throws {
      let end = Date().addingTimeInterval(seconds)
      while !predicate(), Date() < end { try await Task.sleep(for: .milliseconds(100)) }
      guard predicate() else { report["failedWait"] = label; try save(); throw HostServiceError.invalidResponse }
    }
    func restoreNavigation() {
      store.devReplyBeforeTransportForAcceptance = nil
      if let originalThread { store.openThread(originalThread, loadAutomatically: false) }
      store.selectedThreadId = originalThreadId; store.selectedProjectId = originalProject
      store.saved.lastThreads = lastThreads; store.saved.lastThreadProjects = lastProjects
      store.saved.newProjects = newProjects; store.persist(reconcileUnread: false)
    }
    defer { restoreNavigation() }
    do {
      try save()
      let provider = try AgentClients.resolve(client)
      let thread: Conversation
      if action == "prepare" {
        guard env["EASYWORK_HOSTED_NATIVE_REPLY_PREFLIGHT_CASE"] == run,
          config["controlledPreTransportFailureAuthorized"] as? Bool == true,
          let create = config["createOperationId"] as? String, UUID(uuidString: create) != nil else { throw HostServiceError.invalidResponse }
        report["status"] = "one-owned-thread-about-to-create"; try save()
        thread = try await provider.createThread(client, projectId: nil, operationId: create)
      } else {
        let id = try XCTUnwrap(config["ownedThreadId"] as? String)
        let history = try await provider.history(client, threadId: id, cursor: nil)
        thread = try XCTUnwrap(history.thread)
      }
      report["threadId"] = thread.id
      await store.refresh(includeHistory: false, userInitiated: true)
      store.openThread(thread, loadAutomatically: false)
      await store.loadHistory(threadId: thread.id)
      await store.loadModels()
      let key = store.targetKey
      report["ownedScopeKey"] = key
      var operation: String
      if action == "prepare" {
        guard store.saved.outbox?[key] == nil, store.draft.text.isEmpty, store.draft.attachments.isEmpty else { throw HostServiceError.invalidResponse }
        let model = store.selectedModel?.id ?? store.runtime?.generationSettings?.model
        let content = Draft(text: body, model: model)
        if !(store.saved.aiSharingGrants ?? []).contains(where: { $0.scope.target == target && $0.scope.model == (model ?? "follow-computer") }) {
          guard config["normalConsentForFrozenOwnInputAuthorized"] as? Bool == true else { throw HostServiceError.invalidResponse }
          store.send(content: content)
          guard store.saved.outbox?[key] == nil, store.aiSharingRequest?.scope.target == target else { throw HostServiceError.invalidResponse }
          store.allowAISharing()
          report["normalConsentGrantedWithoutAutomaticSend"] = true
        }
        var hookCalls = 0
        store.devReplyBeforeTransportForAcceptance = { pending in
          guard pending.hostId == client.id, pending.threadId == thread.id, pending.text == body,
            let captured = store.saved.replyRecovery?[pending.clientMessageId], captured.phase == "not-written",
            captured.target == target, captured.original.clientMessageId == pending.clientMessageId,
            captured.fingerprint == (try ReplyRecoveryPolicy.fingerprint(pending, target: target)) else { throw HostServiceError.invalidResponse }
          hookCalls += 1
          guard hookCalls == 1 else { throw HostServiceError.invalidResponse }
          report["operationId"] = pending.clientMessageId
          report["capturedOriginalPending"] = try JSONSerialization.jsonObject(with: encoder.encode(pending))
          report["originalRecoveryFingerprint"] = captured.fingerprint
          report["actualDurablePreTransportCaptureObserved"] = true; try save()
          throw URLError(.networkConnectionLost)
        }
        report["status"] = "one-normal-send-with-controlled-pre-transport-failure"; try save()
        store.send(content: content)
        try await wait("actual local not-written send released") {
          hookCalls == 1 && !store.sendingKeys.contains(key) && store.saved.outbox?[key]?.text == body
        }
        store.devReplyBeforeTransportForAcceptance = nil
        let pending = try XCTUnwrap(store.saved.outbox?[key])
        let captured = try XCTUnwrap(store.saved.replyRecovery?[pending.clientMessageId])
        operation = pending.clientMessageId
        guard captured.phase == "not-written", !captured.retryConsumed, pending.knownRejected == true,
          ReplyRecoveryPolicy.sameIntent(pending, record: captured), store.canRetryPending else { throw HostServiceError.invalidResponse }
        for field in ["hostId", "threadId", "clientMessageId", "text", "model", "speed"] {
          var changed = try easyWorkObject(encoder.encode(pending)); changed[field] = "changed-" + field
          let altered = try JSONDecoder().decode(PendingReply.self, from: easyWorkJSON(changed))
          guard !ReplyRecoveryPolicy.mayRetry(altered, record: captured) else { throw HostServiceError.invalidResponse }
        }
        var uncertain = captured; uncertain.phase = "uncertain"
        var consumed = captured; consumed.retryConsumed = true
        guard !ReplyRecoveryPolicy.mayRetry(pending, record: uncertain),
          !ReplyRecoveryPolicy.mayRetry(pending, record: consumed) else { throw HostServiceError.invalidResponse }
        report["actualNotWrittenEligibleDespiteDifferentLocalAndWireIds"] = true
        report["mutatedBodyIdentitySettingsUnknownAndConsumedDenied"] = true
        report["preTransportFaultCalls"] = hookCalls; report["modelTransportCalls"] = 0
      } else {
        operation = try XCTUnwrap(config["originalOperationId"] as? String)
        let captured = try XCTUnwrap(store.saved.replyRecovery?[operation])
        guard captured.target == target, captured.fingerprint == config["originalFingerprint"] as? String,
          captured.original.text == body, captured.original.threadId == thread.id else { throw HostServiceError.invalidResponse }
        let next = try XCTUnwrap(config["nextDraftText"] as? String)
        if action == "retry" {
          let pending = try XCTUnwrap(store.saved.outbox?[key])
          guard config["parentVerifiedActualPreTransportFailureAndZeroRemoteSubmission"] as? Bool == true,
            captured.phase == "not-written", !captured.retryConsumed,
            pending.clientMessageId == operation, ReplyRecoveryPolicy.sameIntent(pending, record: captured),
            store.canRetryPending else { throw HostServiceError.invalidResponse }
          report["originalActualNotWrittenRecordColdRestored"] = true
          store.setText(next, target: key)
          report["status"] = "two-explicit-retry-calls-one-original-operation-budget"; try save()
          store.retryPending(); store.retryPending()
          try await wait("same original accepted once", seconds: 120) {
            store.saved.outbox?[key] == nil && store.saved.replyRecovery?[operation]?.retryConsumed == true
              && !store.sendingKeys.contains(key)
          }
          guard store.saved.replyRecovery?[operation]?.fingerprint == captured.fingerprint,
            store.draft.text == next, !store.canRetryPending else { throw HostServiceError.invalidResponse }
          let receipt = try await provider.receipt(client, id: operation)
          let root = try XCTUnwrap(receipt.turnId)
          guard receipt.threadId == thread.id, receipt.clientMessageId == operation else { throw HostServiceError.invalidResponse }
          report["rootRunId"] = root; report["doubleTapDidNotReleaseSecondRetryAuthority"] = true
          let end = Date().addingTimeInterval(600)
          var complete = false
          while Date() < end {
            let h = try await provider.history(client, threadId: thread.id, cursor: nil)
            if h.runtime?.state == "idle", h.messages.contains(where: { $0.role == "assistant" && $0.runId == root && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker }) { complete = true; break }
            if ["failed", "cancelled", "interrupted"].contains(h.runtime?.state ?? "") { break }
            try await Task.sleep(for: .milliseconds(500))
          }
          guard complete, store.drafts.values[key]?.text == next else { throw HostServiceError.invalidResponse }
          report["sameOriginalOperationAcceptedAndExactFinalMarkerRead"] = true
          report["nextDraftPreserved"] = true
        } else {
          guard config["completedOwnRootIndependentlyQualified"] as? Bool == true,
            store.saved.outbox?[key] == nil, captured.retryConsumed,
            store.draft.text == next, store.draft.attachments.isEmpty,
            let archive = config["archiveOperationId"] as? String, UUID(uuidString: archive) != nil else { throw HostServiceError.invalidResponse }
          let h = try await provider.history(client, threadId: thread.id, cursor: nil)
          guard h.runtime?.state == "idle", h.messages.contains(where: { $0.role == "assistant" && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker }) else { throw HostServiceError.invalidResponse }
          report["archiveOperationId"] = archive; report["status"] = "one-owned-recovery-thread-about-to-archive"; try save()
          let channel = try await EasyWorkSessions.shared.session(client)
          _ = try await channel.request("thread/archive", ["threadId": thread.id, "archived": true], id: archive)
          let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
          guard workspace.threads.first(where: { $0.id == thread.id })?.archived == true else { throw HostServiceError.invalidResponse }
          store.drafts.values.removeValue(forKey: key)
          report["onlyOwnedThreadArchivedAndOnlyOwnedSyntheticNextDraftRemoved"] = true
        }
      }
      report["operationId"] = operation
      restoreNavigation()
      for (k, value) in oldDrafts where k != key { guard try encoder.encode(store.drafts.values[k]) == encoder.encode(value) else { throw HostServiceError.invalidResponse } }
      for (k, value) in oldOutbox where k != key { guard try encoder.encode(store.saved.outbox?[k]) == encoder.encode(value) else { throw HostServiceError.invalidResponse } }
      for (k, value) in oldRecovery where k != operation { guard try encoder.encode(store.saved.replyRecovery?[k]) == encoder.encode(value) else { throw HostServiceError.invalidResponse } }
      guard try CredentialVault.existingToken("easywork-device-identity") == identity,
        try encoder.encode(store.saved.generationPreferences) == preferences,
        store.saved.selectedClientId == originalClient, store.selectedThreadId == originalThreadId,
        store.selectedProjectId == originalProject else { throw HostServiceError.invalidResponse }
      report["otherOriginalDraftsOutboxRecoveryIdentityPreferencesAndSelectionPreserved"] = true
      report["status"] = "passed-actual-local-reply-recovery-phase"; try save()
    } catch {
      report["status"] = "failed-preserve-original-local-reply-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testOwnedEasyWorkReadingVersionAndReaderIsolationWithoutModel() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_READING_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen owned reading case required") }
    let config = try easyWorkObject(Data(raw.utf8))
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      config["newThreadsAuthorized"] as? Int == 0, config["maxMetadataCommandsAuthorized"] as? Int == 2,
      config["ownedCompletedRootIndependentlyQualified"] as? Bool == true,
      let thread = config["ownedThreadId"] as? String, let root = config["ownedRootId"] as? String,
      let marker = config["expectedFinalMarker"] as? String,
      let rejectedOperation = config["rejectedStaleOperationId"] as? String,
      UUID(uuidString: rejectedOperation) != nil else { throw HostServiceError.invalidResponse }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target,
      let readingDevice = store.saved.readingDeviceId, !readingDevice.isEmpty else { throw HostServiceError.wrongHost }
    var client = clients[0]
    let connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let reader = ReadingIdentity.readerId(deviceId: readingDevice,
      targetKey: SharedMobileRules.nativeReadingKey(target, threadId: nil))
    client.readingTarget = target; client.readingScopeId = reader
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let original = try encoder.encode(store.drafts.values) + encoder.encode(store.saved.outbox)
      + encoder.encode(store.saved.replyRecovery) + encoder.encode(store.saved.generationPreferences)
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let selectedProject = store.selectedProjectId, identity = try CredentialVault.existingToken("easywork-device-identity")
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "appPID": ProcessInfo.processInfo.processIdentifier,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "readerId": reader, "threadId": thread,
      "newModels": 0, "newThreads": 0, "metadataCommandAttempts": 0,
      "pureUIReaccepted": false, "wholeFeaturePassed": false, "status": "prepared-no-command"]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    do {
      try save()
      let provider = try AgentClients.resolve(client), channel = try await EasyWorkSessions.shared.session(client)
      let history = try await provider.history(client, threadId: thread, cursor: nil)
      guard history.runtime?.state == "idle", history.messages.contains(where: {
        $0.role == "assistant" && $0.runId == root && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker
      }) else { throw HostServiceError.invalidResponse }
      let latest = try JSONDecoder().decode(ReadPosition.self,
        from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["expectedLatestPosition"])))
      let before = try await EasyWorkReading.read(channel, client: client, threadId: thread)
      guard before.state?.latest == latest, before.state?.read != latest else { throw HostServiceError.invalidResponse }
      report["actualUnreadOriginalLatestBeforeAcknowledge"] = true
      report["latestPosition"] = try JSONSerialization.jsonObject(with: encoder.encode(latest))
      let params: [String: Any] = ["threadId": thread, "readerId": reader,
        "position": ["revision": latest.revision, "messageId": latest.messageId, "contentVersion": latest.contentVersion]]
      let wireTarget = await channel.target, mobile = await channel.mobileDeviceId
      let operation = "read-" + HostService.hash(try easyWorkJSON(["target": wireTarget.dictionary, "params": params]))
      report["ackOperationId"] = operation; report["mobileDeviceId"] = mobile
      report["status"] = "one-normal-own-reader-acknowledgement-about-to-submit"
      report["metadataCommandAttempts"] = 1; try save()
      let ack = try await provider.acknowledgeRead(client, threadId: thread, readerId: reader, position: latest)
      guard ack.target == target, ack.hostId == client.id, ack.threadId == thread,
        ack.readerId == reader, ack.read == latest else { throw HostServiceError.invalidResponse }
      let record = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
      let result = try easyWorkObject(EasyWorkRemoteSession.verifiedWriteResult(record, target: wireTarget,
        mobileDeviceId: mobile, operationId: operation, method: "thread/acknowledge-read", params: params))
      _ = try EasyWorkReading.decode(result, client: client, target: wireTarget, threadId: thread, readerId: reader, expected: latest)
      let after = try await EasyWorkReading.read(channel, client: client, threadId: thread)
      guard after.counter == before.counter, after.state?.read == latest else { throw HostServiceError.invalidResponse }
      let repeated = try await provider.acknowledgeRead(client, threadId: thread, readerId: reader, position: latest)
      guard repeated.read == latest else { throw HostServiceError.invalidResponse }
      let repeatedRecord = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
      guard try easyWorkJSON(repeatedRecord) == easyWorkJSON(record) else { throw HostServiceError.invalidResponse }
      report["unreadToReadAndExactOriginalCanonicalAckVerified"] = true
      report["repeatAcknowledgementRetainsOriginalReceiptAndRevision"] = true
      var readerMismatchDenied = false
      let other = reader == String(repeating: "f", count: 64) ? String(repeating: "e", count: 64) : String(repeating: "f", count: 64)
      do { _ = try await provider.acknowledgeRead(client, threadId: thread, readerId: other, position: latest) }
      catch let error as HostServiceError { if case .wrongHost = error { readerMismatchDenied = true } else { throw error } }
      guard readerMismatchDenied else { throw HostServiceError.invalidResponse }
      report["differentReaderIdDeniedBeforeWriteByProductionAdapter"] = true
      let fakeDigest = latest.contentVersion == String(repeating: "0", count: 64) ? String(repeating: "1", count: 64) : String(repeating: "0", count: 64)
      let stale = ReadPosition(revision: latest.revision, messageId: latest.messageId, contentVersion: fakeDigest)
      var staleParams = params
      staleParams["position"] = ["revision": stale.revision, "messageId": stale.messageId, "contentVersion": stale.contentVersion]
      report["rejectedStaleOperationId"] = rejectedOperation
      report["status"] = "one-authenticated-stale-version-negative-about-to-submit"
      report["metadataCommandAttempts"] = 2; try save()
      var rejected = false
      do { _ = try await channel.request("thread/acknowledge-read", staleParams, id: rejectedOperation) }
      catch let error as HostServiceError { if case .rejected(409, _) = error { rejected = true } else { throw error } }
      guard rejected else { throw HostServiceError.invalidResponse }
      let final = try await EasyWorkReading.read(channel, client: client, threadId: thread)
      guard final.counter == before.counter, final.state?.latest == latest, final.state?.read == latest else { throw HostServiceError.invalidResponse }
      report["actualServerStaleVersionRejected409OriginalReadAndHeadRetained"] = true
      let current = try encoder.encode(store.drafts.values) + encoder.encode(store.saved.outbox)
        + encoder.encode(store.saved.replyRecovery) + encoder.encode(store.saved.generationPreferences)
      guard current == original, store.saved.selectedClientId == selectedClient,
        store.selectedThreadId == selectedThread, store.selectedProjectId == selectedProject,
        try CredentialVault.existingToken("easywork-device-identity") == identity else { throw HostServiceError.invalidResponse }
      report["originalLocalDraftsOutboxRecoveryPreferencesSelectionAndIdentityUnchanged"] = true
      report["status"] = "passed-owned-reading-interface-only"; try save()
    } catch {
      report["status"] = "failed-owned-reading-preserve-original-operation-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testOwnedEasyWorkTerminalForkAndColdOriginalStatusWithoutModel() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_OWNED_FORK_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen owned fork case required") }
    let config = try easyWorkObject(Data(raw.utf8))
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      let action = config["forkAction"] as? String, ["fork", "cold-status-archive"].contains(action),
      config["newActualForksAuthorized"] as? Int == (action == "fork" ? 1 : 0),
      config["metadataSubmissionCallsAuthorized"] as? Int == (action == "fork" ? 2 : 1),
      config["ownedCompletedSourceIndependentlyQualified"] as? Bool == true,
      let source = config["ownedSourceThreadId"] as? String, let root = config["ownedSourceRootId"] as? String,
      let operation = config["forkOperationId"] as? String, UUID(uuidString: operation) != nil,
      let marker = config["expectedFinalMarker"] as? String else { throw HostServiceError.invalidResponse }
    let environment = try XCTUnwrap(ThreadForkEnvironment(rawValue: config["forkEnvironment"] as? String ?? "same-directory"))
    let sourceProject = config["expectedSourceProjectId"] as? String
    if environment == .worktree {
      guard sourceProject != nil,
        (config["ownedCleanSourceGitIndependentlyQualified"] as? Bool == true
          || config["ownedSourceGitIndependentlyQualified"] as? Bool == true),
        config["expectedSourceDirectory"] is String, config["expectedSourceHead"] is String,
        config["expectedManagedWorktreeParent"] is String else { throw HostServiceError.invalidResponse }
    }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let original = try encoder.encode(store.drafts.values) + encoder.encode(store.saved.outbox)
      + encoder.encode(store.saved.replyRecovery) + encoder.encode(store.saved.generationPreferences)
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let selectedProject = store.selectedProjectId, identity = try CredentialVault.existingToken("easywork-device-identity")
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "forkAction": action, "appPID": ProcessInfo.processInfo.processIdentifier,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "sourceThreadId": source, "sourceRootId": root,
      "forkOperationId": operation, "newModels": 0, "metadataSubmissionCalls": 0,
      "pureUIReaccepted": false, "wholeFeaturePassed": false, "status": "prepared-no-command"]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    do {
      try save()
      let provider = try AgentClients.resolve(client), channel = try await EasyWorkSessions.shared.session(client)
      let history = try await provider.history(client, threadId: source, cursor: nil)
      guard history.runtime?.state == "idle", history.messages.contains(where: {
        $0.role == "assistant" && $0.runId == root && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker
      }) else { throw HostServiceError.invalidResponse }
      let request = ThreadForkRequest(hostId: client.id, threadId: source, turnId: root,
        clientOperationId: operation, environment: environment)
      let fork: ThreadForkOperation
      if action == "fork" {
        let options = try await provider.forkOptions(client, threadId: source)
        guard options.hostId == client.id, options.threadId == source, options.sameDirectoryAvailable else { throw HostServiceError.invalidResponse }
        if environment == .worktree {
          guard options.worktreeAvailable else { throw HostServiceError.invalidResponse }
          let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
          guard workspace.projects.contains(where: { $0.id == sourceProject && $0.roots == [config["expectedSourceDirectory"] as! String] }),
            history.thread?.projectId == sourceProject else { throw HostServiceError.invalidResponse }
          let git = try easyWorkObject(await provider.projectGit(client, projectId: sourceProject!, target: target, refresh: true))
          guard git["kind"] as? String == "git" else { throw HostServiceError.invalidResponse }
          report["actualOwnedGitProjectWorktreeAvailable"] = true
        } else {
          guard !options.worktreeAvailable, !(options.worktreeReason ?? "").isEmpty else { throw HostServiceError.invalidResponse }
          report["actualProjectlessSameDirectoryAvailableAndWorktreeUnavailable"] = true
        }
        report["status"] = "one-owned-terminal-source-fork-about-to-submit"
        report["metadataSubmissionCalls"] = 1; try save()
        fork = try await provider.forkThread(client, request: request)
        guard fork.request == request, fork.phase == .confirmed, fork.error == nil,
          let id = fork.forkedThreadId, id != source,
          UUID(uuidString: id) != nil else { throw HostServiceError.invalidResponse }
        if environment == .worktree {
          guard let project = fork.projectId, project != sourceProject else { throw HostServiceError.invalidResponse }
          let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
          let created = try XCTUnwrap(workspace.projects.first(where: { $0.id == project }))
          guard created.roots.count == 1,
            created.roots[0].hasPrefix((config["expectedManagedWorktreeParent"] as! String) + "/") else { throw HostServiceError.invalidResponse }
          report["forkedProjectId"] = project; report["forkedWorktreeDirectory"] = created.roots[0]
        } else { guard fork.projectId == sourceProject else { throw HostServiceError.invalidResponse } }
        report["forkedThreadId"] = id; try save()
        var params = try easyWorkObject(encoder.encode(request)); params["hostId"] = target.hostId
        let record = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
        let wireTarget = await channel.target, wireDevice = await channel.mobileDeviceId
        let result = try easyWorkObject(EasyWorkRemoteSession.verifiedWriteResult(record, target: wireTarget,
          mobileDeviceId: wireDevice, operationId: operation, method: "thread/fork", params: params))
        guard result["phase"] as? String == "confirmed", result["forkedThreadId"] as? String == id else { throw HostServiceError.invalidResponse }
        report["metadataSubmissionCalls"] = 2; try save()
        let repeated = try await provider.forkThread(client, request: request)
        let repeatedRecord = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
        guard repeated.request == request, repeated.phase == .confirmed, repeated.forkedThreadId == id,
          try easyWorkJSON(repeatedRecord) == easyWorkJSON(record) else { throw HostServiceError.invalidResponse }
        report["repeatSameImmutableOperationKeepsOneOriginalCanonicalAndDestination"] = true
      } else {
        guard config["ownedForkIndependentlyQualified"] as? Bool == true,
          let expected = config["expectedForkedThreadId"] as? String,
          let archive = config["archiveOperationId"] as? String, UUID(uuidString: archive) != nil else { throw HostServiceError.invalidResponse }
        fork = try await provider.forkStatus(client, threadId: source, operationId: operation)
        guard fork.request == request, fork.phase == .confirmed, fork.forkedThreadId == expected,
          fork.projectId == (config["expectedForkedProjectId"] as? String ?? sourceProject), fork.error == nil else { throw HostServiceError.invalidResponse }
        report["forkedThreadId"] = expected
        report["coldOriginalForkStatusConfirmedWithoutResubmission"] = true
        let copied = try await provider.history(client, threadId: expected, cursor: nil)
        guard copied.messages.contains(where: { $0.role == "assistant" && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker }) else { throw HostServiceError.invalidResponse }
        report["archiveOperationId"] = archive; report["metadataSubmissionCalls"] = 1
        report["status"] = "one-owned-copied-fork-about-to-archive"; try save()
        _ = try await channel.request("thread/archive", ["threadId": expected, "archived": true], id: archive)
        let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
        guard workspace.threads.first(where: { $0.id == expected })?.archived == true else { throw HostServiceError.invalidResponse }
        report["onlyOwnedCopiedThreadNormallyArchived"] = true
      }
      let status = try await provider.forkStatus(client, threadId: source, operationId: operation)
      guard status.request == request, status.phase == .confirmed, status.forkedThreadId == fork.forkedThreadId else { throw HostServiceError.invalidResponse }
      if action == "fork" {
        let copied = try await provider.history(client, threadId: try XCTUnwrap(fork.forkedThreadId), cursor: nil)
        guard copied.messages.contains(where: { $0.role == "assistant" && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker }) else { throw HostServiceError.invalidResponse }
        report["actualOriginalOutputInheritedWithoutStartingRun"] = true
      }
      let current = try encoder.encode(store.drafts.values) + encoder.encode(store.saved.outbox)
        + encoder.encode(store.saved.replyRecovery) + encoder.encode(store.saved.generationPreferences)
      guard current == original, store.saved.selectedClientId == selectedClient,
        store.selectedThreadId == selectedThread, store.selectedProjectId == selectedProject,
        try CredentialVault.existingToken("easywork-device-identity") == identity else { throw HostServiceError.invalidResponse }
      report["originalLocalDraftsOutboxRecoveryPreferencesSelectionAndIdentityUnchanged"] = true
      report["status"] = "passed-owned-terminal-fork-phase-without-model"; try save()
    } catch {
      report["status"] = "failed-owned-fork-preserve-original-operation-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testOwnedMetadataCreationUnknownAndColdOriginalQueryWithoutModel() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_METADATA_CREATE_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen metadata creation case required") }
    let c = try easyWorkObject(Data(raw.utf8))
    let action = try XCTUnwrap(c["action"] as? String)
    let owner = try XCTUnwrap(c["owner"] as? String)
    let name = "I13 F45 metadata " + owner
    let directory = try XCTUnwrap(c["directory"] as? String)
    let body = "I13_F45_UNSENT_" + owner
    guard c["caseId"] as? String == run, UUID(uuidString: owner) != nil,
      ["prepare", "submit-unknown", "cold-query", "cleanup"].contains(action),
      (directory as NSString).lastPathComponent == "f45-owned-create-source-" + owner,
      c["newModelInputsAuthorized"] as? Int == 0,
      env["EASYWORK_HOSTED_NATIVE_REPLY_PREFLIGHT_CASE"] == run else { throw HostServiceError.invalidResponse }
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard clients.count == 1, clients[0].agentConnection?.target == target, target.agentId == "easywork",
      !store.localRecoveryRequired else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard (c["allowedBindingIds"] as? [String])?.contains(credential.binding.bindingId) == true else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let oldDrafts = store.drafts.values, oldThread = store.conversation
    let selectedThread = store.selectedThreadId, selectedProject = store.selectedProjectId
    let lastThreads = store.saved.lastThreads, lastProjects = store.saved.lastThreadProjects, newProjects = store.saved.newProjects
    let oldOutbox = store.saved.outbox, oldRecovery = store.saved.replyRecovery
    let originalIdentity = try CredentialVault.existingToken("easywork-device-identity")
    let stateURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Acceptance/session-state-v1.json")
    func disk() -> SavedSession? { guard let b = try? Data(contentsOf: stateURL) else { return nil }; return try? AgentCache.decode(b) }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "action": action, "appPID": ProcessInfo.processInfo.processIdentifier,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "modelSubmissionAttempts": 0,
      "metadataCreateDispatches": 0, "pureUIReaccepted": false, "wholeFeaturePassed": false]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]).write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, _ test: () -> Bool) async throws {
      let end = Date().addingTimeInterval(60)
      while !test(), Date() < end { try await Task.sleep(for: .milliseconds(100)) }
      guard test() else { report["failedWait"] = label; try save(); throw HostServiceError.invalidResponse }
    }
    defer {
      store.devMetadataCreateBeforeTransportForAcceptance = nil; store.devMetadataCreateAfterVerifiedForAcceptance = nil
      store.devReplyBeforeTransportForAcceptance = nil
      if let oldThread { store.openThread(oldThread, loadAutomatically: false) }
      store.selectedThreadId = selectedThread; store.selectedProjectId = selectedProject
      store.saved.lastThreads = lastThreads; store.saved.lastThreadProjects = lastProjects; store.saved.newProjects = newProjects
      store.persist(reconcileUnread: false)
    }
    do {
      try save()
      let provider = try AgentClients.resolve(client), channel = try await EasyWorkSessions.shared.session(client)
      let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
      if action == "prepare" {
        guard c["metadataWritesAuthorized"] as? Int == 1, !workspace.projects.contains(where: { $0.name == name || $0.roots.contains(directory) }) else { throw HostServiceError.invalidResponse }
        let op = try XCTUnwrap(c["projectCreateOperation"] as? String)
        report["projectCreateOperation"] = op; report["status"] = "before-own-project-create"; try save()
        let value = try await provider.createProject(client, name: name, directory: directory, operationId: op)
        report["projectId"] = try XCTUnwrap(value.projects.first(where: { $0.name == name && $0.roots == [directory] })).id
      } else {
        let projectId = try XCTUnwrap(c["projectId"] as? String)
        let project = try XCTUnwrap(workspace.projects.first(where: { $0.id == projectId && $0.name == name && $0.roots == [directory] }))
        if action == "submit-unknown" || action == "cold-query" {
          await store.refresh(includeHistory: false, userInitiated: true)
          await store.loadModels()
          store.newConversation(in: project)
          let key = store.targetKey
          report["draftScopeKey"] = key
          var dispatches = 0
          store.devMetadataCreateBeforeTransportForAcceptance = { op in
            dispatches += 1; report["metadataCreateDispatches"] = dispatches; report["originalOperationId"] = op; try save()
            guard action == "submit-unknown", dispatches == 1 else { throw HostServiceError.invalidResponse }
          }
          store.devReplyBeforeTransportForAcceptance = { reply in
            guard action == "cold-query", reply.text == body, reply.attachmentIds.isEmpty,
              reply.threadId == c["threadId"] as? String else { throw HostServiceError.invalidResponse }
            report["pendingReplyId"] = reply.clientMessageId
            report["initialReplyDurableBeforeAnyTransport"] = disk()?.outbox?.values.contains(where: { $0.clientMessageId == reply.clientMessageId && $0.text == reply.text && $0.threadId == reply.threadId }) == true
            try save(); throw URLError(.networkConnectionLost)
          }
          if action == "submit-unknown" {
            guard (store.draft.text.isEmpty || store.draft.text == body), store.draft.operationId == nil, store.draft.attachments.isEmpty,
              c["metadataWritesAuthorized"] as? Int == 1 else { throw HostServiceError.invalidResponse }
            // Select an already authorized model for this synthetic draft;
            // never grant new sharing permission or change global defaults.
            let approved = try XCTUnwrap(store.saved.aiSharingGrants?.first(where: { grant in
              grant.scope.target == target && store.models.contains(where: { $0.id == grant.scope.model })
            }))
            store.drafts.values[key, default: Draft()].model = approved.scope.model
            store.setText(body, target: key)
            report["canSendBeforeDispatch"] = store.canSend
            report["existingSharingGrantModelSelected"] = approved.scope.model
            try save()
            guard store.canSend else { throw HostServiceError.invalidResponse }
            store.devMetadataCreateAfterVerifiedForAcceptance = { op, thread in
              guard thread.projectId == projectId, store.selectedThreadId == nil,
                disk()?.drafts[key]?.operationId == op, disk()?.drafts[key]?.text == body else { throw HostServiceError.invalidResponse }
              report["threadId"] = thread.id; report["originalOperationId"] = op
              report["originalIntentDurableBeforeVerifiedResultWithheld"] = true; try save()
              throw URLError(.networkConnectionLost)
            }
            store.send()
            try await wait("unknown metadata result retains actual captured draft") {
              !store.sendingKeys.contains(key) && report["threadId"] != nil && store.error != nil
            }
            guard dispatches == 1, store.selectedThreadId == nil, store.draft.text == body,
              store.draft.operationId == report["originalOperationId"] as? String,
              try encoder.encode(store.saved.outbox) == encoder.encode(oldOutbox),
              try encoder.encode(store.saved.replyRecovery) == encoder.encode(oldRecovery) else { throw HostServiceError.invalidResponse }
          } else {
            let original = try XCTUnwrap(c["originalOperationId"] as? String)
            guard c["metadataWritesAuthorized"] as? Int == 0, store.draft.text == body,
              store.draft.operationId == original, disk()?.drafts[key]?.operationId == original else { throw HostServiceError.invalidResponse }
            report["actualUnknownDraftColdLoaded"] = true
            let missing = try await provider.threadCreationStatus(client, projectId: projectId, operationId: UUID().uuidString)
            guard missing == nil else { throw HostServiceError.invalidResponse }
            var rejectedWrongProject = false
            do { _ = try await provider.threadCreationStatus(client, projectId: UUID().uuidString, operationId: original) }
            catch { rejectedWrongProject = true }
            guard rejectedWrongProject else { throw HostServiceError.invalidResponse }
            report["missingOriginalIsUnknownAndMismatchedParamsRejected"] = true
            store.send()
            try await wait("same created thread adopted and initial reply blocked before transport") {
              !store.sendingKeys.contains(key) && report["pendingReplyId"] != nil
            }
            let replyId = try XCTUnwrap(report["pendingReplyId"] as? String)
            let threadId = try XCTUnwrap(c["threadId"] as? String)
            guard dispatches == 0, store.selectedThreadId == threadId,
              disk()?.replyRecovery?[replyId]?.phase == "not-written",
              disk()?.replyRecovery?[replyId]?.original.text == body,
              disk()?.replyRecovery?[replyId]?.original.threadId == threadId else { throw HostServiceError.invalidResponse }
            report["originalOperationId"] = original; report["threadId"] = threadId
            report["noMetadataResubmitAndNoReplyTransport"] = true
          }
          var remaining = store.drafts.values; remaining.removeValue(forKey: key)
          if let threadId = report["threadId"] as? String { remaining.removeValue(forKey: SharedMobileRules.cacheKey(client, threadId: threadId, projectId: nil, workspace: store.saved.workspaces[client.id])) }
          var original = oldDrafts; original.removeValue(forKey: key)
          guard try encoder.encode(remaining) == encoder.encode(original) else { throw HostServiceError.invalidResponse }
          report["otherDraftsUnchanged"] = true
        } else {
          guard c["metadataWritesAuthorized"] as? Int == 2 else { throw HostServiceError.invalidResponse }
          let thread = try XCTUnwrap(c["threadId"] as? String), replyId = try XCTUnwrap(c["pendingReplyId"] as? String)
          let history = try await provider.history(client, threadId: thread, cursor: nil)
          guard history.messages.isEmpty, history.thread?.projectId == projectId,
            store.saved.replyRecovery?[replyId]?.phase == "not-written",
            store.saved.replyRecovery?[replyId]?.original.threadId == thread else { throw HostServiceError.invalidResponse }
          let operations = try XCTUnwrap(c["cleanupOperations"] as? [String]); guard operations.count == 2 else { throw HostServiceError.invalidResponse }
          _ = try await channel.request("thread/archive", ["threadId": thread, "archived": true], id: operations[0])
          _ = try await channel.request("project/delete", ["projectId": projectId], id: operations[1])
          for key in store.saved.outbox?.keys.map({ $0 }) ?? [] where store.saved.outbox?[key]?.clientMessageId == replyId { store.saved.outbox?.removeValue(forKey: key) }
          store.saved.replyRecovery?.removeValue(forKey: replyId)
          store.drafts.values.removeValue(forKey: try XCTUnwrap(c["draftScopeKey"] as? String))
          store.drafts.values.removeValue(forKey: SharedMobileRules.cacheKey(client, threadId: thread, projectId: nil, workspace: store.saved.workspaces[client.id]))
          store.persist(reconcileUnread: false)
          report["onlyOwnEmptyThreadArchivedProjectDeletedAndNeverWrittenReplyRemoved"] = true
        }
      }
      guard try CredentialVault.existingToken("easywork-device-identity") == originalIdentity else { throw HostServiceError.wrongHost }
      report["status"] = "passed-owned-metadata-phase"; try save()
    } catch { report["status"] = "failed-preserve-prefix-no-replay"; report["error"] = error.localizedDescription; try save(); throw error }
  }

  @MainActor func testOwnedLateMetadataResultKeepsNextProjectDraftWithoutModel() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_METADATA_CREATE_CASE"] == run,
      env["EASYWORK_HOSTED_NATIVE_REPLY_PREFLIGHT_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen late metadata result case required") }
    let c = try easyWorkObject(Data(raw.utf8)), owner = try XCTUnwrap(c["owner"] as? String)
    let directories = try XCTUnwrap(c["directories"] as? [String]), operations = try XCTUnwrap(c["operations"] as? [String])
    guard c["caseId"] as? String == run, UUID(uuidString: owner) != nil,
      c["newModelInputsAuthorized"] as? Int == 0, c["metadataWritesAuthorized"] as? Int == 6,
      directories.count == 2, operations.count == 5, Set(operations).count == 5,
      operations.allSatisfy({ UUID(uuidString: $0) != nil }),
      directories.enumerated().allSatisfy({ ($0.element as NSString).lastPathComponent == "f46-owned-late-\($0.offset)-" + owner }) else { throw HostServiceError.invalidResponse }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard clients.count == 1, clients[0].agentConnection?.target == target, target.agentId == "easywork",
      !store.localRecoveryRequired else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard (c["allowedBindingIds"] as? [String])?.contains(credential.binding.bindingId) == true else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let oldDrafts = store.drafts.values, oldOutbox = store.saved.outbox, oldRecovery = store.saved.replyRecovery
    let oldPreferences = store.saved.generationPreferences, oldGrants = store.saved.aiSharingGrants
    let oldThread = store.conversation, selectedThread = store.selectedThreadId, selectedProject = store.selectedProjectId
    let lastThreads = store.saved.lastThreads, lastProjects = store.saved.lastThreadProjects, newProjects = store.saved.newProjects
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let stateURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Acceptance/session-state-v1.json")
    func disk() -> SavedSession? { guard let b = try? Data(contentsOf: stateURL) else { return nil }; return try? AgentCache.decode(b) }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "appPID": ProcessInfo.processInfo.processIdentifier,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "modelSubmissionAttempts": 0,
      "metadataCreateDispatches": 0, "pureUIReaccepted": false, "wholeFeaturePassed": false]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]).write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, _ predicate: () -> Bool) async throws {
      let deadline = Date().addingTimeInterval(60)
      while !predicate(), Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
      guard predicate() else { report["failedWait"] = label; try save(); throw HostServiceError.invalidResponse }
    }
    defer {
      store.devMetadataCreateBeforeTransportForAcceptance = nil; store.devMetadataCreateAfterVerifiedForAcceptance = nil
      store.devReplyBeforeTransportForAcceptance = nil
      if let oldThread { store.openThread(oldThread, loadAutomatically: false) }
      store.selectedThreadId = selectedThread; store.selectedProjectId = selectedProject
      store.saved.lastThreads = lastThreads; store.saved.lastThreadProjects = lastProjects; store.saved.newProjects = newProjects
      store.persist(reconcileUnread: false)
    }
    do {
      report["status"] = "before-owned-projects"; try save()
      let provider = try AgentClients.resolve(client), channel = try await EasyWorkSessions.shared.session(client)
      let originalWorkspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
      let names = ["I14 F46 source " + owner, "I14 F46 next " + owner]
      guard !originalWorkspace.projects.contains(where: { names.contains($0.name) || $0.roots.contains(where: { directories.contains($0) }) }) else { throw HostServiceError.invalidResponse }
      var projects: [Project] = []
      for index in 0..<2 {
        report["projectCreateAttempts"] = index + 1; try save()
        let workspace = try await provider.createProject(client, name: names[index], directory: directories[index], operationId: operations[index])
        projects.append(try XCTUnwrap(workspace.projects.first(where: { $0.name == names[index] && $0.roots == [directories[index]] })))
        report["projectIds"] = projects.map(\.id); try save()
      }
      await store.refresh(includeHistory: false, userInitiated: true); await store.loadModels()
      store.newConversation(in: projects[0]); let sourceKey = store.targetKey
      guard store.draft.text.isEmpty, store.draft.operationId == nil, store.draft.attachments.isEmpty else { throw HostServiceError.invalidResponse }
      let approved = try XCTUnwrap(store.saved.aiSharingGrants?.first(where: { grant in grant.scope.target == target && store.models.contains(where: { $0.id == grant.scope.model }) }))
      let body = "I14_F46_ORIGINAL_" + owner, nextBody = "I14_F46_NEXT_" + owner
      store.drafts.values[sourceKey, default: Draft()].model = approved.scope.model
      store.setText(body, target: sourceKey)
      guard store.canSend else { throw HostServiceError.invalidResponse }
      var dispatches = 0, nextKey: String?
      store.devMetadataCreateBeforeTransportForAcceptance = { op in
        dispatches += 1; report["metadataCreateDispatches"] = dispatches; report["originalOperationId"] = op; try save()
        guard dispatches == 1 else { throw HostServiceError.invalidResponse }
      }
      store.devMetadataCreateAfterVerifiedForAcceptance = { op, thread in
        guard disk()?.drafts[sourceKey]?.operationId == op, thread.projectId == projects[0].id else { throw HostServiceError.invalidResponse }
        report["threadId"] = thread.id
        store.newConversation(in: projects[1]); nextKey = store.targetKey
        guard nextKey != sourceKey, store.selectedProjectId == projects[1].id, store.selectedThreadId == nil,
          store.draft.text.isEmpty, store.draft.operationId == nil else { throw HostServiceError.invalidResponse }
        store.setText(nextBody, target: store.targetKey); store.persist(reconcileUnread: false)
        report["normalProjectSwitchBeforeOriginalResultAccepted"] = true; try save()
      }
      store.devReplyBeforeTransportForAcceptance = { reply in
        guard reply.text == body, reply.threadId == report["threadId"] as? String,
          reply.hostId == client.id, reply.attachmentIds.isEmpty,
          store.selectedProjectId == projects[1].id, store.selectedThreadId == nil, store.draft.text == nextBody,
          disk()?.outbox?.values.contains(where: { $0.clientMessageId == reply.clientMessageId && $0.text == body }) == true else { throw HostServiceError.invalidResponse }
        report["pendingReplyId"] = reply.clientMessageId
        report["lateOriginalResultDidNotSelectOriginalThreadOrOverwriteNextDraft"] = true; try save()
        throw URLError(.networkConnectionLost)
      }
      report["status"] = "one-normal-send-with-controlled-late-metadata-result"; try save()
      store.send()
      try await wait("late result and old reply released without next draft mutation") { report["pendingReplyId"] != nil && !store.sendingKeys.contains(sourceKey) }
      let thread = try XCTUnwrap(report["threadId"] as? String), reply = try XCTUnwrap(report["pendingReplyId"] as? String)
      let destinationKey = try XCTUnwrap(nextKey)
      try await wait("next draft and original reply are independently encrypted durable") {
        disk()?.drafts[destinationKey]?.text == nextBody && disk()?.drafts[destinationKey]?.operationId == nil
          && disk()?.replyRecovery?[reply]?.phase == "not-written" && disk()?.replyRecovery?[reply]?.original.threadId == thread
      }
      let originalOperation = try XCTUnwrap(report["originalOperationId"] as? String)
      let recovered = try await provider.threadCreationStatus(client, projectId: projects[0].id, operationId: originalOperation)
      guard recovered?.id == thread, dispatches == 1, store.selectedProjectId == projects[1].id,
        store.selectedThreadId == nil, store.targetKey == destinationKey, store.draft.text == nextBody,
        store.draft.operationId == nil else { throw HostServiceError.invalidResponse }
      report["originalQueryPreservedNextProjectAndEncryptedDraft"] = true
      let sourceThreadKey = SharedMobileRules.cacheKey(client, threadId: thread, projectId: nil, workspace: store.saved.workspaces[client.id])
      var otherDrafts = store.drafts.values
      for key in [sourceKey, destinationKey, sourceThreadKey] { otherDrafts.removeValue(forKey: key) }
      guard try encoder.encode(otherDrafts) == encoder.encode(oldDrafts),
        try encoder.encode(store.saved.generationPreferences) == encoder.encode(oldPreferences),
        try encoder.encode(store.saved.aiSharingGrants) == encoder.encode(oldGrants) else { throw HostServiceError.invalidResponse }
      report["otherDraftsPreferencesAndSharingGrantsUnchanged"] = true; try save()
      _ = try await channel.request("thread/archive", ["threadId": thread, "archived": true], id: operations[2])
      for index in 0..<2 { _ = try await channel.request("project/delete", ["projectId": projects[index].id], id: operations[index + 3]) }
      for key in [sourceKey, destinationKey, sourceThreadKey] { store.drafts.values.removeValue(forKey: key) }
      store.saved.outbox?.removeValue(forKey: sourceThreadKey); store.saved.replyRecovery?.removeValue(forKey: reply)
      store.persist(reconcileUnread: false)
      guard try encoder.encode(store.drafts.values) == encoder.encode(oldDrafts),
        try encoder.encode(store.saved.outbox) == encoder.encode(oldOutbox),
        try encoder.encode(store.saved.replyRecovery) == encoder.encode(oldRecovery),
        try CredentialVault.existingToken("easywork-device-identity") == identity else { throw HostServiceError.invalidResponse }
      report["onlyOwnedEmptyThreadProjectsAndNeverWrittenSyntheticDraftsNormallyCleaned"] = true
      report["status"] = "passed-late-metadata-result-next-project-isolated"; try save()
    } catch { report["status"] = "failed-preserve-prefix-no-replay"; report["error"] = error.localizedDescription; try save(); throw error }
  }

  @MainActor func testCompletedOwnedMetadataSameIdConflictPreservesOriginalWithoutModel() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_COMPLETED_CONFLICT_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen completed owned conflict case required") }
    let c = try easyWorkObject(Data(raw.utf8)), operation = try XCTUnwrap(c["originalOperationId"] as? String)
    let thread = try XCTUnwrap(c["ownedArchivedEmptyThreadId"] as? String)
    guard c["caseId"] as? String == run, c["newModelInputsAuthorized"] as? Int == 0,
      c["expectedNewCanonicalWrites"] as? Int == 0, c["confirmedOriginalIndependentlyQualified"] as? Bool == true,
      UUID(uuidString: operation) != nil, UUID(uuidString: thread) != nil else { throw HostServiceError.invalidResponse }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore), clients = store.saved.clients.filter { !$0.isDemo }
    guard clients.count == 1, target.agentId == "easywork", clients[0].agentConnection?.target == target, !store.localRecoveryRequired else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard (c["allowedBindingIds"] as? [String])?.contains(credential.binding.bindingId) == true else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let protected = try encoder.encode(store.drafts.values) + encoder.encode(store.saved.outbox) + encoder.encode(store.saved.replyRecovery)
      + encoder.encode(store.saved.generationPreferences) + encoder.encode(store.saved.aiSharingGrants)
    let selection = [store.saved.selectedClientId, store.selectedThreadId, store.selectedProjectId]
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "appPID": ProcessInfo.processInfo.processIdentifier,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "newModels": 0, "sameOriginalIdAttempts": 0,
      "wholeFeaturePassed": false, "pureUIReaccepted": false, "originalOperationId": operation, "threadId": thread]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]).write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    do {
      report["status"] = "query-confirmed-original-before-conflict-controls"; try save()
      let provider = try AgentClients.resolve(client), channel = try await EasyWorkSessions.shared.session(client)
      let before = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
      let params: [String: Any] = ["threadId": thread, "archived": true]
      let wireTarget = await channel.target
      let original = try EasyWorkRemoteSession.verifiedWriteResult(before, target: wireTarget,
        mobileDeviceId: await channel.mobileDeviceId, operationId: operation, method: "thread/archive", params: params)
      let canonical = try easyWorkJSON(before)
      report["originalCanonicalSHA256"] = HostService.hash(canonical)
      let history = try await provider.history(client, threadId: thread, cursor: nil)
      guard history.thread?.archived == true, history.messages.isEmpty else { throw HostServiceError.invalidResponse }
      report["sameOriginalIdAttempts"] = 1; try save()
      let same = try await channel.request("thread/archive", params, id: operation)
      guard try easyWorkJSON(JSONSerialization.jsonObject(with: same, options: [.fragmentsAllowed])) == easyWorkJSON(JSONSerialization.jsonObject(with: original, options: [.fragmentsAllowed])) else { throw HostServiceError.invalidResponse }
      var errors: [String] = []
      let controls: [(String, [String: Any])] = [
        ("thread/archive", ["threadId": thread, "archived": false]),
        ("project/delete", ["projectId": try XCTUnwrap(c["nonexistentConflictProjectId"] as? String)])]
      for (index, control) in controls.enumerated() {
        report["sameOriginalIdAttempts"] = index + 2; try save()
        do { _ = try await channel.request(control.0, control.1, id: operation); throw HostServiceError.invalidResponse }
        catch let rejection as EasyWorkRemoteRejection {
          guard rejection.code == "operation_conflict" else { throw rejection }
          errors.append(try XCTUnwrap(rejection.code))
        }
      }
      report["sameOriginalIdAttempts"] = 4; try save()
      let again = try await channel.request("thread/archive", params, id: operation)
      let after = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
      let afterHistory = try await provider.history(client, threadId: thread, cursor: nil)
      guard try easyWorkJSON(after) == canonical, try easyWorkJSON(JSONSerialization.jsonObject(with: again, options: [.fragmentsAllowed])) == easyWorkJSON(JSONSerialization.jsonObject(with: original, options: [.fragmentsAllowed])),
        afterHistory.thread?.archived == true, afterHistory.messages.isEmpty else { throw HostServiceError.invalidResponse }
      let current = try encoder.encode(store.drafts.values) + encoder.encode(store.saved.outbox) + encoder.encode(store.saved.replyRecovery)
        + encoder.encode(store.saved.generationPreferences) + encoder.encode(store.saved.aiSharingGrants)
      guard current == protected, [store.saved.selectedClientId, store.selectedThreadId, store.selectedProjectId] == selection,
        try CredentialVault.existingToken("easywork-device-identity") == identity else { throw HostServiceError.invalidResponse }
      report["sameParamsBothCachedResultsExact"] = true; report["differentParamsAndMethodBothOperationConflict"] = errors
      report["originalCanonicalEmptyArchiveAndProtectedLocalStateExact"] = true
      report["status"] = "passed-confirmed-original-conflict-controls-no-new-intent"; try save()
    } catch { report["status"] = "failed-preserve-prefix-no-replay"; report["error"] = error.localizedDescription; try save(); throw error }
  }

  @MainActor func testOwnedCleanGitWorktreeSourcePreparationOrCleanup() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_OWNED_WORKTREE_SOURCE_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen own worktree source case required") }
    let config = try easyWorkObject(Data(raw.utf8))
    let additionalForks = config["additionalOwnedForks"] as? [[String: String]] ?? []
    guard config["caseId"] as? String == run,
      let action = config["sourceAction"] as? String, ["prepare", "cleanup"].contains(action),
      additionalForks.count <= 1,
      config["newModelInputsAuthorized"] as? Int == (action == "prepare" ? 1 : 0),
      config["newThreadsAuthorized"] as? Int == (action == "prepare" ? 1 : 0),
      config["remoteCommandAttemptsAuthorized"] as? Int == (action == "cleanup" ? 3 + additionalForks.count : 3),
      let owner = config["fixtureOwnerId"] as? String, UUID(uuidString: owner) != nil,
      let directory = config["ownedSourceDirectory"] as? String,
      (directory as NSString).lastPathComponent == "f62-clean-source-" + owner,
      let name = config["ownedSourceProjectName"] as? String, name == "I10 F62 source " + owner,
      let marker = config["expectedFinalMarker"] as? String, marker == "I10_F62_WORKTREE_" + owner,
      let operations = config["operations"] as? [String],
      operations.count == (action == "cleanup" ? 3 + additionalForks.count : 3),
      Set(operations).count == operations.count, operations.allSatisfy({ UUID(uuidString: $0) != nil }) else { throw HostServiceError.invalidResponse }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let original = try encoder.encode(store.drafts.values) + encoder.encode(store.saved.outbox)
      + encoder.encode(store.saved.replyRecovery) + encoder.encode(store.saved.generationPreferences)
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let selectedProject = store.selectedProjectId, identity = try CredentialVault.existingToken("easywork-device-identity")
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "sourceAction": action,
      "appPID": ProcessInfo.processInfo.processIdentifier, "appBundleIdentifier": Bundle.main.bundleIdentifier!,
      "modelSubmissionAttempts": 0, "remoteCommandAttempts": 0, "pureUIReaccepted": false,
      "wholeFeaturePassed": false, "status": "prepared-no-command"]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    do {
      try save()
      let provider = try AgentClients.resolve(client), channel = try await EasyWorkSessions.shared.session(client)
      let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
      if action == "prepare" {
        guard config["ownedSourceCleanGitIndependentlyQualified"] as? Bool == true,
          let body = config["body"] as? String, body.contains(marker),
          HostService.hash(Data(body.utf8)) == config["bodySHA256"] as? String,
          !workspace.projects.contains(where: { $0.name == name || $0.roots.contains(directory) }) else { throw HostServiceError.invalidResponse }
        report["remoteCommandAttempts"] = 1; report["status"] = "one-owned-clean-source-project-about-to-register"; try save()
        let value = try await provider.createProject(client, name: name, directory: directory, operationId: operations[0])
        let project = try XCTUnwrap(value.projects.first(where: { $0.name == name && $0.roots == [directory] }))
        report["sourceProjectId"] = project.id; report["remoteCommandAttempts"] = 2; try save()
        let thread = try await provider.createThread(client, projectId: project.id, operationId: operations[1])
        report["sourceThreadId"] = thread.id; report["remoteCommandAttempts"] = 3
        report["modelSubmissionAttempts"] = 1; report["sendOperationId"] = operations[2]
        report["status"] = "one-frozen-source-marker-input-about-to-submit"; try save()
        let pending = PendingReply(hostId: client.id, threadId: thread.id, clientMessageId: operations[2],
          text: body, attachmentIds: [], mode: "next-turn", expectedTurnId: nil,
          model: nil, effort: nil, sourceAttachments: nil, knownRejected: nil)
        let receipt = try await provider.send(client, reply: pending)
        guard receipt.threadId == thread.id, receipt.clientMessageId == operations[2], let root = receipt.turnId else { throw HostServiceError.invalidResponse }
        report["sourceRootId"] = root; try save()
        let end = Date().addingTimeInterval(600)
        var completed = false
        while Date() < end {
          let history = try await provider.history(client, threadId: thread.id, cursor: nil)
          if history.runtime?.state == "idle", history.messages.contains(where: {
            $0.role == "assistant" && $0.runId == root && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker
          }) { completed = true; break }
          if ["failed", "cancelled", "interrupted"].contains(history.runtime?.state ?? "") { break }
          try await Task.sleep(for: .milliseconds(500))
        }
        guard completed else { throw HostServiceError.invalidResponse }
        report["actualSourceExactFinalOutputAndIdle"] = true
      } else {
        guard config["ownedForkAndSourceIndependentlyQualified"] as? Bool == true,
          let sourceProject = config["ownedSourceProjectId"] as? String,
          let forkProject = config["ownedForkedProjectId"] as? String, forkProject != sourceProject,
          let thread = config["ownedSourceThreadId"] as? String,
          let forkThread = config["ownedForkedThreadId"] as? String, thread != forkThread,
          let root = config["ownedSourceRootId"] as? String,
          let forkDirectory = config["ownedForkedDirectory"] as? String,
          workspace.projects.contains(where: { $0.id == sourceProject && $0.name == name && $0.roots == [directory] }),
          workspace.projects.contains(where: { $0.id == forkProject && $0.roots == [forkDirectory] }),
          workspace.threads.first(where: { $0.id == forkThread })?.archived == true else { throw HostServiceError.invalidResponse }
        let history = try await provider.history(client, threadId: thread, cursor: nil)
        guard history.runtime?.state == "idle", history.messages.contains(where: {
          $0.role == "assistant" && $0.runId == root && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker
        }) else { throw HostServiceError.invalidResponse }
        for extra in additionalForks {
          guard let id = extra["projectId"], let threadId = extra["threadId"], let root = extra["directory"],
            id != sourceProject, id != forkProject, threadId != thread, threadId != forkThread,
            workspace.projects.contains(where: { $0.id == id && $0.roots == [root] }),
            workspace.threads.first(where: { $0.id == threadId })?.archived == true else { throw HostServiceError.invalidResponse }
        }
        report["remoteCommandAttempts"] = 1; report["status"] = "one-owned-source-about-to-archive"; try save()
        _ = try await channel.request("thread/archive", ["threadId": thread, "archived": true], id: operations[0])
        let projects = [forkProject] + additionalForks.compactMap { $0["projectId"] } + [sourceProject]
        for (index, project) in projects.enumerated() {
          report["remoteCommandAttempts"] = index + 2; try save()
          _ = try await channel.request("project/delete", ["projectId": project], id: operations[index + 1])
        }
        let after = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
        guard !after.projects.contains(where: { projects.contains($0.id) }),
          after.threads.first(where: { $0.id == thread })?.archived == true,
          after.threads.first(where: { $0.id == forkThread })?.archived == true else { throw HostServiceError.invalidResponse }
        report["onlyOwnedSourceArchivedAndBothOwnedProjectsNormallyDeleted"] = true
        report["onlyOwnedSourceArchivedAndAllDeclaredOwnedProjectsNormallyDeleted"] = true
        report["ownedProjectDeleteCount"] = projects.count
      }
      let current = try encoder.encode(store.drafts.values) + encoder.encode(store.saved.outbox)
        + encoder.encode(store.saved.replyRecovery) + encoder.encode(store.saved.generationPreferences)
      guard current == original, store.saved.selectedClientId == selectedClient,
        store.selectedThreadId == selectedThread, store.selectedProjectId == selectedProject,
        try CredentialVault.existingToken("easywork-device-identity") == identity else { throw HostServiceError.invalidResponse }
      report["originalLocalDraftsOutboxRecoveryPreferencesSelectionAndIdentityUnchanged"] = true
      report["status"] = "passed-owned-clean-worktree-source-phase"; try save()
    } catch {
      report["status"] = "failed-owned-clean-worktree-source-preserve-prefix-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testActualSavedEasyWorkForkUnknownDetachColdQueryOrCleanup() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_SAVED_FORK_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen actual saved fork case required") }
    let config = try easyWorkObject(Data(raw.utf8))
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      let action = config["savedForkAction"] as? String,
      ["submit-unknown", "cold-query", "archive-owned-copy"].contains(action),
      config["newActualForksAuthorized"] as? Int == (action == "submit-unknown" ? 1 : 0),
      config["metadataCommandsAuthorized"] as? Int == (action == "cold-query" ? 0 : 1),
      config["ownedSourceIndependentlyQualified"] as? Bool == true,
      let source = config["ownedSourceThreadId"] as? String, let root = config["ownedSourceRootId"] as? String,
      let project = config["ownedSourceProjectId"] as? String,
      let marker = config["expectedFinalMarker"] as? String,
      let next = config["ownedNextDraftText"] as? String, next.hasPrefix("I11_FORK_NEXT_") else { throw HostServiceError.invalidResponse }
    guard action == "submit-unknown" ? env["EASYWORK_HOSTED_NATIVE_FORK_DELIVERY_FAULT_CASE"] == run
      : env["EASYWORK_HOSTED_NATIVE_FORK_DELIVERY_FAULT_CASE"] == nil else { throw HostServiceError.invalidResponse }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let oldDrafts = store.drafts.values, oldForks = store.saved.threadForks ?? [:]
    let originalProtected = try encoder.encode(store.saved.outbox) + encoder.encode(store.saved.replyRecovery)
      + encoder.encode(store.saved.generationPreferences)
    let originalThread = store.conversation, selectedThread = store.selectedThreadId
    let selectedProject = store.selectedProjectId, selectedClient = store.saved.selectedClientId
    let lastThreads = store.saved.lastThreads, lastProjects = store.saved.lastThreadProjects
    let newProjects = store.saved.newProjects
    let stateURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("Acceptance/session-state-v1.json")
    func persisted() -> SavedSession? {
      guard let data = try? Data(contentsOf: stateURL) else { return nil }
      return try? AgentCache.decode(data)
    }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "savedForkAction": action,
      "appPID": ProcessInfo.processInfo.processIdentifier, "appBundleIdentifier": Bundle.main.bundleIdentifier!,
      "newModels": 0, "newActualForksBudget": action == "submit-unknown" ? 1 : 0,
      "pureUIReaccepted": false, "wholeFeaturePassed": false, "status": "prepared-no-command"]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, _ predicate: () -> Bool) async throws {
      let end = Date().addingTimeInterval(60)
      while !predicate(), Date() < end { try await Task.sleep(for: .milliseconds(100)) }
      guard predicate() else { report["failedWait"] = label; try save(); throw HostServiceError.invalidResponse }
    }
    func restoreNavigation() {
      if let originalThread { store.openThread(originalThread, loadAutomatically: false) }
      store.selectedThreadId = selectedThread; store.selectedProjectId = selectedProject
      store.saved.lastThreads = lastThreads; store.saved.lastThreadProjects = lastProjects
      store.saved.newProjects = newProjects; store.persist(reconcileUnread: false)
    }
    defer { restoreNavigation() }
    do {
      try save()
      let provider = try AgentClients.resolve(client)
      let history = try await provider.history(client, threadId: source, cursor: nil)
      guard history.runtime?.state == "idle", history.thread?.projectId == project,
        history.messages.contains(where: { $0.role == "assistant" && $0.runId == root
          && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker }) else { throw HostServiceError.invalidResponse }
      await store.refresh(includeHistory: false, userInitiated: true)
      store.openThread(try XCTUnwrap(history.thread), loadAutomatically: false)
      await store.loadHistory(threadId: source)
      let key = store.targetKey, sourceIdentity = try XCTUnwrap(store.forkSource)
      guard sourceIdentity.target == target, sourceIdentity.threadId == source else { throw HostServiceError.wrongHost }
      report["ownedDraftScopeKey"] = key
      let operation: String
      if action == "submit-unknown" {
        guard config["controlledOnePostVerifiedLocalDeliveryLossAuthorized"] as? Bool == true,
          config["normalEndWaitingForThisOwnedForkAuthorized"] as? Bool == true,
          store.draft.text.isEmpty, store.draft.attachments.isEmpty else { throw HostServiceError.invalidResponse }
        report["originalOwnedDraft"] = try oldDrafts[key].map { try JSONSerialization.jsonObject(with: encoder.encode($0)) } ?? NSNull()
        await store.loadForkOptions(sourceIdentity, refresh: true)
        guard store.forkReason(source: sourceIdentity, turnId: root, environment: .sameDirectory) == nil else { throw HostServiceError.invalidResponse }
        try await EasyWorkForkDeliveryFault.shared.arm(caseId: run, target: target, sourceThread: source, sourceTurn: root) { request in
          let pending = try XCTUnwrap(store.saved.threadForks?[request.clientOperationId])
          let disk = try XCTUnwrap(persisted()?.threadForks?[request.clientOperationId])
          guard pending.request == request, disk.request == request,
            pending.phase == .submitted, disk.phase == .submitted,
            pending.sourceTarget == target, disk.sourceTarget == target else { throw HostServiceError.invalidResponse }
          report["actualSubmittedIntentDurableBeforeVerifiedResultWasWithheld"] = true
          report["capturedOriginalRequest"] = try JSONSerialization.jsonObject(with: encoder.encode(request))
          report["capturedOriginalRequestSHA256"] = HostService.hash(try encoder.encode(request))
          report["operationId"] = request.clientOperationId
          store.detachFork(pending)
          report["normalEndWaitingCalledAfterVerifiedServerCommit"] = true; try save()
        }
        report["status"] = "one-normal-store-fork-with-post-verified-local-delivery-loss"; try save()
        store.forkThread(source: sourceIdentity, turnId: root, environment: .sameDirectory)
        try await wait("actual Store unknown and detached") {
          guard let id = report["operationId"] as? String, let pending = store.saved.threadForks?[id] else { return false }
          return pending.phase == .outcomeUnknown && pending.detached == true && !store.forkBusy(sourceIdentity)
        }
        let witness = await EasyWorkForkDeliveryFault.shared.witness()
        operation = try XCTUnwrap(witness.operationId)
        guard witness.consumed, let child = witness.forkedThreadId,
          operation == report["operationId"] as? String,
          store.saved.threadForks?[operation]?.forkedThreadId == nil,
          store.saved.threadForks?.count == oldForks.count + 1 else { throw HostServiceError.invalidResponse }
        report["verifiedServerForkedThreadId"] = child
        store.setText(next, target: key); store.persist(reconcileUnread: false)
        try await wait("actual unknown record and next draft encrypted durable") {
          persisted()?.threadForks?[operation]?.phase == .outcomeUnknown
            && persisted()?.threadForks?[operation]?.detached == true && persisted()?.drafts[key]?.text == next
        }
        report["actualUnknownDetachedRecordAndNextDraftDurable"] = true
      } else {
        operation = try XCTUnwrap(config["originalOperationId"] as? String)
        let pending = try XCTUnwrap(store.saved.threadForks?[operation])
        let disk = try XCTUnwrap(persisted()?.threadForks?[operation])
        guard pending.request == disk.request, pending.sourceTarget == target,
          pending.sourceCredentialRef == connection.credentialRef,
          pending.request.threadId == source, pending.request.turnId == root,
          pending.request.environment == .sameDirectory,
          HostService.hash(try encoder.encode(pending.request)) == config["capturedOriginalRequestSHA256"] as? String,
          store.draft.text == next, store.draft.attachments.isEmpty else { throw HostServiceError.invalidResponse }
        let child = try XCTUnwrap(config["expectedForkedThreadId"] as? String)
        if action == "cold-query" {
          guard config["actualUnknownAndServerCommitIndependentlyQualified"] as? Bool == true,
            pending.phase == .outcomeUnknown, disk.phase == .outcomeUnknown,
            pending.detached == true, disk.detached == true, pending.forkedThreadId == nil else { throw HostServiceError.invalidResponse }
          report["actualUnknownOriginalRecordColdLoadedFromEncryptedState"] = true
          report["status"] = "normal-query-of-actual-saved-original-operation-no-submit"; try save()
          store.queryFork(pending)
          try await wait("same original fork confirmed after query") {
            store.saved.threadForks?[operation]?.phase == .confirmed && !store.isQueryingFork(operation)
          }
          guard let confirmed = store.saved.threadForks?[operation], confirmed.request == pending.request,
            confirmed.forkedThreadId == child, confirmed.projectId == project,
            confirmed.detached == true, store.selectedThreadId == source, store.draft.text == next else { throw HostServiceError.invalidResponse }
          try await wait("original confirmation persisted with next draft") {
            persisted()?.threadForks?[operation]?.phase == .confirmed
              && persisted()?.threadForks?[operation]?.request == pending.request && persisted()?.drafts[key]?.text == next
          }
          report["normalOriginalQueryConfirmedWithoutReplayNavigationOrDraftOverwrite"] = true
        } else {
          guard config["actualSavedForkColdQueryIndependentlyQualified"] as? Bool == true,
            pending.phase == .confirmed, pending.forkedThreadId == child,
            let archive = config["archiveOperationId"] as? String, UUID(uuidString: archive) != nil else { throw HostServiceError.invalidResponse }
          report["archiveOperationId"] = archive; report["status"] = "one-owned-saved-fork-copy-about-to-archive"; try save()
          let channel = try await EasyWorkSessions.shared.session(client)
          _ = try await channel.request("thread/archive", ["threadId": child, "archived": true], id: archive)
          let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
          guard workspace.threads.first(where: { $0.id == child })?.archived == true else { throw HostServiceError.invalidResponse }
          if let raw = config["originalOwnedDraft"] as? [String: Any] {
            let original = try JSONDecoder().decode(Draft.self, from: easyWorkJSON(raw))
            guard original.text.isEmpty, original.attachments.isEmpty else { throw HostServiceError.invalidResponse }
            store.drafts.values[key] = original
          } else { store.drafts.values.removeValue(forKey: key) }
          store.persist(reconcileUnread: false)
          try await wait("owned synthetic next draft removed while original receipt persists") {
            persisted()?.drafts[key]?.text != next && persisted()?.threadForks?[operation]?.phase == .confirmed
          }
          report["onlyOwnedCopyArchivedAndSyntheticDraftRestoredOriginalReceiptKept"] = true
        }
      }
      report["operationId"] = operation
      restoreNavigation()
      for (id, value) in oldForks where id != operation {
        guard try encoder.encode(store.saved.threadForks?[id]) == encoder.encode(value) else { throw HostServiceError.invalidResponse }
      }
      for (scope, value) in oldDrafts where scope != key {
        guard try encoder.encode(store.drafts.values[scope]) == encoder.encode(value) else { throw HostServiceError.invalidResponse }
      }
      guard try encoder.encode(store.saved.outbox) + encoder.encode(store.saved.replyRecovery)
          + encoder.encode(store.saved.generationPreferences) == originalProtected,
        try CredentialVault.existingToken("easywork-device-identity") == identity,
        store.saved.selectedClientId == selectedClient, store.selectedThreadId == selectedThread,
        store.selectedProjectId == selectedProject else { throw HostServiceError.invalidResponse }
      report["originalOtherForksDraftsOutboxReplyRecoveryPreferencesIdentityAndNavigationPreserved"] = true
      report["status"] = "passed-actual-saved-fork-recovery-phase"; try save()
    } catch {
      report["status"] = "failed-actual-saved-fork-preserve-original-operation-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testOneOwnedLaterReplyForEarlierForkAndReadingVersionBoundary() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_LATER_REPLY_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen own later reply required") }
    let c = try easyWorkObject(Data(raw.utf8))
    guard c["caseId"] as? String == run, c["newModelInputsAuthorized"] as? Int == 1,
      c["newThreadsAuthorized"] as? Int == 0, c["remoteCommandsAuthorized"] as? Int == 1,
      c["ownedEarlierRootAndSecondaryReaderIndependentlyQualified"] as? Bool == true,
      let thread = c["ownedSourceThreadId"] as? String, let earlier = c["ownedEarlierRootId"] as? String,
      let earlierMarker = c["earlierFinalMarker"] as? String,
      let operation = c["sendOperationId"] as? String, UUID(uuidString: operation) != nil,
      let body = c["body"] as? String, HostService.hash(Data(body.utf8)) == c["bodySHA256"] as? String,
      let marker = c["finalMarker"] as? String, marker.hasPrefix("I12_F62_LATER_") else { throw HostServiceError.invalidResponse }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, clients[0].agentConnection?.target == target,
      target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(c["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let protected = try originalReadProtectedDigests(store)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "appPID": ProcessInfo.processInfo.processIdentifier,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "sendOperationId": operation,
      "newModelSubmissionAttempts": 0, "newThreads": 0, "wholeFeaturePassed": false,
      "pureUIReaccepted": false, "status": "prepared-no-command"]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    do {
      try save()
      let provider = try AgentClients.resolve(client)
      let before = try await provider.history(client, threadId: thread, cursor: nil)
      guard before.runtime?.state == "idle", before.messages.contains(where: {
        $0.role == "assistant" && $0.runId == earlier && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == earlierMarker
      }) else { throw HostServiceError.invalidResponse }
      report["newModelSubmissionAttempts"] = 1; report["status"] = "one-different-frozen-later-input-about-to-submit"; try save()
      let pending = PendingReply(hostId: client.id, threadId: thread, clientMessageId: operation,
        text: body, attachmentIds: [], mode: "next-turn", expectedTurnId: nil,
        model: nil, effort: nil, sourceAttachments: nil, knownRejected: nil)
      let receipt = try await provider.send(client, reply: pending)
      guard receipt.threadId == thread, receipt.clientMessageId == operation,
        let root = receipt.turnId, root != earlier else { throw HostServiceError.invalidResponse }
      report["laterRootId"] = root; try save()
      let end = Date().addingTimeInterval(600)
      var complete = false
      while Date() < end {
        let history = try await provider.history(client, threadId: thread, cursor: nil)
        if history.runtime?.state == "idle", history.messages.contains(where: {
          $0.role == "assistant" && $0.runId == root && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker
        }), history.messages.contains(where: {
          $0.role == "assistant" && $0.runId == earlier && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == earlierMarker
        }) { complete = true; break }
        if ["failed", "cancelled", "interrupted"].contains(history.runtime?.state ?? "") { break }
        try await Task.sleep(for: .milliseconds(500))
      }
      guard complete, try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
      report["actualDifferentLaterRootCompleteAndEarlierOutputRetained"] = true
      report["originalLocalStatePreserved"] = true; report["status"] = "passed-owned-later-source-input"; try save()
    } catch {
      report["status"] = "failed-owned-later-source-input-preserve-original-operation-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testOwnedProtocolReadersAcrossOneNewReplyWithoutModel() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_READER_VERSION_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen logical reader boundary required") }
    let c = try easyWorkObject(Data(raw.utf8))
    guard c["caseId"] as? String == run, c["newModelInputsAuthorized"] as? Int == 0,
      c["newThreadsAuthorized"] as? Int == 0, c["metadataCommandsAuthorized"] as? Int == 1,
      c["controlledSecondaryLogicalReaderFixtureAuthorized"] as? Bool == true,
      let action = c["readerAction"] as? String, ["secondary-before-later", "primary-after-later"].contains(action),
      let seed = c["secondaryReaderSeed"] as? String, UUID(uuidString: seed) != nil,
      let thread = c["ownedSourceThreadId"] as? String,
      let root = c["expectedFinalRootId"] as? String, let marker = c["expectedFinalMarker"] as? String else { throw HostServiceError.invalidResponse }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, clients[0].agentConnection?.target == target,
      target.agentId == "easywork", let device = store.saved.readingDeviceId else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(c["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let protected = try originalReadProtectedDigests(store)
    let readingKey = SharedMobileRules.nativeReadingKey(target, threadId: nil)
    let primaryId = ReadingIdentity.readerId(deviceId: device, targetKey: readingKey)
    let secondaryId = ReadingIdentity.readerId(deviceId: "protocol-fixture-" + seed, targetKey: readingKey)
    guard primaryId != secondaryId else { throw HostServiceError.invalidResponse }
    var primary = client, secondary = client
    primary.readingTarget = target; primary.readingScopeId = primaryId
    secondary.readingTarget = target; secondary.readingScopeId = secondaryId
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "readerAction": action,
      "appPID": ProcessInfo.processInfo.processIdentifier, "appBundleIdentifier": Bundle.main.bundleIdentifier!,
      "primaryReaderId": primaryId, "secondaryReaderId": secondaryId,
      "secondaryIsControlledLogicalReaderNotSecondPhysicalDevice": true,
      "newModels": 0, "newThreads": 0, "wholeFeaturePassed": false,
      "pureUIReaccepted": false, "status": "prepared-no-acknowledgement"]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    do {
      try save()
      let provider = try AgentClients.resolve(client), channel = try await EasyWorkSessions.shared.session(client)
      let history = try await provider.history(client, threadId: thread, cursor: nil)
      let final = try XCTUnwrap(history.messages.first(where: {
        $0.role == "assistant" && $0.runId == root && $0.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker
      }))
      guard history.runtime?.state == "idle" else { throw HostServiceError.invalidResponse }
      let exactVersionBody = [final.id, final.text, final.status ?? "completed"].map { "\($0.utf8.count):\($0)" }.joined()
      let expectedVersion = HostService.hash(Data(exactVersionBody.utf8))
      let readerClient = action == "secondary-before-later" ? secondary : primary
      let readerId = action == "secondary-before-later" ? secondaryId : primaryId
      let before = try await EasyWorkReading.read(channel, client: readerClient, threadId: thread)
      let latest = try XCTUnwrap(before.state?.latest)
      guard latest.messageId == final.id, latest.contentVersion == expectedVersion, before.state?.read != latest else { throw HostServiceError.invalidResponse }
      var oldSecondary: ReadPosition?
      if action == "primary-after-later" {
        let old = try JSONDecoder().decode(ReadPosition.self,
          from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["expectedSecondaryReadPosition"])))
        let secondaryBefore = try await EasyWorkReading.read(channel, client: secondary, threadId: thread)
        guard secondaryBefore.state?.read == old, secondaryBefore.state?.latest == latest,
          latest.revision > old.revision, latest.messageId != old.messageId else { throw HostServiceError.invalidResponse }
        oldSecondary = old
        report["realLaterReplyAdvancedHeadWhileSecondaryStillReadEarlierVersion"] = true
      }
      let wireTarget = await channel.target, mobile = await channel.mobileDeviceId
      let params: [String: Any] = ["threadId": thread, "readerId": readerId,
        "position": ["revision": latest.revision, "messageId": latest.messageId, "contentVersion": latest.contentVersion]]
      let operation = "read-" + HostService.hash(try easyWorkJSON(["target": wireTarget.dictionary, "params": params]))
      report["ackOperationId"] = operation; report["mobileDeviceId"] = mobile
      report["latestPosition"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(latest))
      report["status"] = "one-original-reader-acknowledgement-about-to-submit"; try save()
      let ack = try await provider.acknowledgeRead(readerClient, threadId: thread, readerId: readerId, position: latest)
      guard ack.read == latest, ack.readerId == readerId, ack.target == target else { throw HostServiceError.invalidResponse }
      let record = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
      _ = try EasyWorkRemoteSession.verifiedWriteResult(record, target: wireTarget, mobileDeviceId: mobile,
        operationId: operation, method: "thread/acknowledge-read", params: params)
      if let old = oldSecondary {
        let secondaryAfter = try await EasyWorkReading.read(channel, client: secondary, threadId: thread)
        guard secondaryAfter.state?.read == old, secondaryAfter.state?.latest == latest else { throw HostServiceError.invalidResponse }
        _ = try await provider.acknowledgeRead(primary, threadId: thread, readerId: primaryId, position: latest)
        let repeated = try easyWorkObject(await channel.request("command/status", ["commandId": operation]))
        guard try easyWorkJSON(repeated) == easyWorkJSON(record) else { throw HostServiceError.invalidResponse }
        report["sameThreadOtherLogicalReaderWatermarkPreservedAndRepeatKeepsCanonical"] = true
      }
      guard try originalReadProtectedDigests(store) == protected,
        store.saved.readingDeviceId == device else { throw HostServiceError.invalidResponse }
      report["normalPrimaryIdentityAndAllOriginalLocalStatePreserved"] = true
      report["actualFinalMessageIdTextStatusContentVersionVerified"] = true
      report["status"] = "passed-owned-logical-reader-version-phase"; try save()
    } catch {
      report["status"] = "failed-owned-logical-reader-preserve-original-operation-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testCurrentOwnedHostASRReadinessSyntheticAudioOrOriginalStatus() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_CURRENT_ASR_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen current Host ASR case required") }
    let c = try easyWorkObject(Data(raw.utf8))
    guard c["caseId"] as? String == run, c["newModelInputsAuthorized"] as? Int == 0,
      let action = c["asrAction"] as? String, ["readiness", "transcribe", "status-only"].contains(action),
      c["newSpeechTranscriptionsAuthorized"] as? Int == (action == "transcribe" ? 1 : 0),
      c["newAudioUploadsAuthorized"] as? Int == (action == "transcribe" ? 1 : 0),
      c["newCanonicalMetadataAuthorized"] as? Int == (action == "transcribe" ? 2 : 0) else { throw HostServiceError.invalidResponse }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, clients[0].agentConnection?.target == target,
      target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(c["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let protected = try originalReadProtectedDigests(store)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "asrAction": action,
      "appPID": ProcessInfo.processInfo.processIdentifier, "appBundleIdentifier": Bundle.main.bundleIdentifier!,
      "newChatModelInputs": 0, "newAudioUploadAttempts": 0,
      "syntheticSpeechNotHumanRecording": true, "microphoneUsed": false,
      "pureUIReaccepted": false, "wholeFeaturePassed": false, "status": "prepared-no-upload"]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func normalized(_ text: String) -> String {
      text.lowercased().filter { $0.isLetter }
    }
    do {
      try save()
      let provider = try AgentClients.resolve(client)
      let channel = try await EasyWorkSessions.shared.session(client)
      if action == "readiness" {
        let bytes = try await channel.request("voice/readiness", [:])
        report["actualAuthenticatedReadiness"] = try easyWorkObject(bytes)
        let typed = try await provider.voiceReadiness(client)
        guard typed.hostId == client.id, typed.maxBytes == 12 * 1024 * 1024,
          typed.maxDurationMs == 300_000 else { throw HostServiceError.invalidResponse }
        report["actualAvailable"] = typed.available
        report["readinessProbeOnlyNotProducerPass"] = true
      } else {
        guard let operation = c["recordingOperationId"] as? String, UUID(uuidString: operation) != nil,
          let draft = c["ownedDraftId"] as? String, draft.hasPrefix("f64-owned-draft-"),
          let size = c["audioSizeBytes"] as? Int, let duration = c["audioDurationMs"] as? Int,
          let digest = c["audioSHA256"] as? String, digest.count == 64,
          let expected = c["expectedNormalizedTranscript"] as? String, !expected.isEmpty else { throw HostServiceError.invalidResponse }
        let thread = c["ownedSourceThreadId"] as? String
        let manifest = VoiceTranscriptionRequest(hostId: client.id, threadId: thread, draftId: draft,
          operationId: operation, mediaType: "audio/wav", sizeBytes: size, sha256: digest, durationMs: duration)
        report["recordingOperationId"] = operation
        report["capturedManifest"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(manifest))
        if action == "transcribe" {
          guard c["ownSyntheticSpeechHostTransferAuthorized"] as? Bool == true,
            c["actualCurrentHostReadinessIndependentlyQualified"] as? Bool == true,
            let name = c["ownedAudioRelativeName"] as? String,
            name.hasPrefix("f64-audio-"), name.hasSuffix(".wav"), (name as NSString).lastPathComponent == name else { throw HostServiceError.invalidResponse }
          let file = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AcceptanceFixtures", isDirectory: true).appendingPathComponent(name)
          guard try file.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink != true else { throw HostServiceError.invalidResponse }
          let data = try Data(contentsOf: file)
          guard data.count == size, size >= 44, size <= 12 * 1024 * 1024,
            duration > 0, duration <= 300_000, data.prefix(4) == Data("RIFF".utf8),
            HostService.hash(data) == digest else { throw HostServiceError.invalidResponse }
          let readiness = try await provider.voiceReadiness(client)
          guard readiness.available else { throw HostServiceError.message(readiness.reason ?? "Host ASR unavailable") }
          let absent = try easyWorkObject(await channel.request("voice/status", ["operationId": operation]))
          guard absent["receipt"] is NSNull else { throw HostServiceError.invalidResponse }
          report["status"] = "one-original-synthetic-wav-provider-transfer-about-to-submit"
          report["newAudioUploadAttempts"] = 1; try save()
          let text = try await provider.transcribeVoice(client,
            recording: VoiceRecording(operationId: operation, data: data, sha256: digest, durationMs: duration),
            threadId: thread, draftId: draft)
          guard normalized(text) == expected else { throw HostServiceError.invalidResponse }
          report["returnedActualText"] = text
          report["actualHostASRReturnedExpectedSyntheticSpeech"] = true
        } else {
          guard c["originalASRResultIndependentlyQualified"] as? Bool == true else { throw HostServiceError.invalidResponse }
          let oldEpoch = await channel.epoch
          await provider.close(client)
          let fresh = try await EasyWorkSessions.shared.session(client)
          let value = try easyWorkObject(await fresh.request("voice/status", ["operationId": operation]))
          let receipt = try JSONDecoder().decode(VoiceTranscriptionReceipt.self,
            from: JSONSerialization.data(withJSONObject: try XCTUnwrap(value["receipt"])))
          guard await fresh.epoch != oldEpoch, try receipt.matches(manifest), receipt.phase == "completed",
            let text = receipt.text, normalized(text) == expected else { throw HostServiceError.invalidResponse }
          report["freshEncryptedSessionReturnedOriginalReceiptWithoutPrepareOrUpload"] = true
        }
        let current = try await EasyWorkSessions.shared.session(client)
        let value = try easyWorkObject(await current.request("voice/status", ["operationId": operation]))
        let receipt = try JSONDecoder().decode(VoiceTranscriptionReceipt.self,
          from: JSONSerialization.data(withJSONObject: try XCTUnwrap(value["receipt"])))
        guard try receipt.matches(manifest), receipt.phase == "completed", receipt.error == nil,
          let text = receipt.text, normalized(text) == expected else { throw HostServiceError.invalidResponse }
        report["actualCompletedOriginalReceipt"] = value
        report["manifestAndFrozenDraftThreadTargetValidated"] = true
      }
      guard try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
      report["originalLocalDraftsStateAndIdentityPreservedNoAutomaticChatSend"] = true
      report["status"] = "passed-current-owned-host-asr-phase"; try save()
    } catch {
      report["status"] = "failed-current-owned-host-asr-preserve-original-audio-operation-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testCurrentEasyWorkWorkingStopOrExplicitContinuation() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      env["EASYWORK_HOSTED_NATIVE_CONTROL_CASE"] == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen current control case required") }
    let config = try easyWorkObject(Data(raw.utf8))
    guard config["caseId"] as? String == run,
      let action = config["controlAction"] as? String, ["start-stop", "continue"].contains(action),
      config["newModelInputsAuthorized"] as? Int == 1,
      config["newThreadsAuthorized"] as? Int == (action == "start-stop" ? 1 : 0),
      config["cancelAttemptsAuthorized"] as? Int == (action == "start-stop" ? 1 : 0),
      let operation = config["sendOperationId"] as? String, UUID(uuidString: operation) != nil,
      let body = config["body"] as? String,
      HostService.hash(Data(body.utf8)) == config["bodySHA256"] as? String,
      let marker = config["finalMarker"] as? String, marker.hasPrefix("I7_CONTROL_"),
      config["doNotOverrideGenerationPreferences"] as? Bool == true else { throw HostServiceError.invalidResponse }
    let ready = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < ready { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork",
      clients[0].agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let preferences = try encoder.encode(store.saved.generationPreferences)
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let selected = store.saved.selectedClientId, threadSelection = store.selectedThreadId, projectSelection = store.selectedProjectId
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "controlAction": action,
      "appBundleIdentifier": Bundle.main.bundleIdentifier!, "appPID": ProcessInfo.processInfo.processIdentifier,
      "sendOperationId": operation, "modelSubmissionAttempts": 0, "cancelAttempts": 0,
      "wholeFeaturePassed": false, "pureUIReaccepted": false, "status": "prepared-no-action"]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    do {
      try save()
      let provider = try AgentClients.resolve(client)
      let thread: String
      if action == "start-stop" {
        guard body.contains("sleep 90"), body.contains(marker),
          let create = config["createOperationId"] as? String, UUID(uuidString: create) != nil,
          create != operation else { throw HostServiceError.invalidResponse }
        report["status"] = "one-owned-projectless-thread-about-to-create"; try save()
        thread = try await provider.createThread(client, projectId: nil, operationId: create).id
      } else {
        thread = try XCTUnwrap(config["ownedThreadId"] as? String)
        guard UUID(uuidString: thread) != nil,
          let stopped = config["originalStoppedRootId"] as? String,
          config["originalStoppedRootAndPositiveUsageIndependentlyVerified"] as? Bool == true,
          body == SharedMobileRules.runAgainText(restart: false) else { throw HostServiceError.invalidResponse }
        let old = try await provider.history(client, threadId: thread, cursor: nil)
        guard old.runtime?.state == "cancelled", old.runtime?.turnId == nil,
          old.runtime?.receipts.contains(where: { $0.turnId == stopped && $0.state == "cancelled" }) == true else { throw HostServiceError.invalidResponse }
      }
      report["threadId"] = thread; report["status"] = "one-frozen-input-about-to-submit"
      report["modelSubmissionAttempts"] = 1; try save()
      let input = PendingReply(hostId: client.id, threadId: thread, clientMessageId: operation,
        text: body, attachmentIds: [], mode: "next-turn", expectedTurnId: nil,
        model: nil, effort: nil, sourceAttachments: nil, knownRejected: nil)
      let receipt = try await provider.send(client, reply: input)
      guard receipt.clientMessageId == operation, receipt.threadId == thread,
        ["submitted", "completed"].contains(receipt.state), let root = receipt.turnId,
        UUID(uuidString: root) != nil else { throw HostServiceError.invalidResponse }
      report["rootRunId"] = root; report["status"] = "accepted-only-read-original-root"; try save()
      let end = Date().addingTimeInterval(600)
      var reached = false
      while Date() < end {
        let history = try await provider.history(client, threadId: thread, cursor: nil)
        if action == "start-stop" {
          let liveTool = history.messages.first { message in
            message.runId == root && message.blocks?.contains(where: { part in
              if case .tool(_, _, let status, let input, _) = part {
                return status == "inProgress" && input?.contains("sleep 90") == true && input?.contains(marker) == true
              }
              return false
            }) == true
          }
          if history.runtime?.turnId == root, let liveTool {
            report["sameRootActualRunningToolObservedBeforeStop"] = true
            report["observedToolMessageIdSHA256"] = HostService.hash(Data(liveTool.id.utf8))
            report["status"] = "one-production-provider-stop-about-to-submit"
            report["cancelAttempts"] = 1; try save()
            guard try await provider.stop(client, threadId: thread, turnId: root).ok else { throw HostServiceError.invalidResponse }
            let stoppedEnd = Date().addingTimeInterval(60)
            while Date() < stoppedEnd {
              let stopped = try await provider.history(client, threadId: thread, cursor: nil)
              if stopped.runtime?.state == "cancelled", stopped.runtime?.turnId == nil,
                stopped.runtime?.receipts.contains(where: { $0.turnId == root && $0.state == "cancelled" }) == true {
                reached = true; break
              }
              try await Task.sleep(for: .milliseconds(500))
            }
            break
          }
          if ["idle", "failed", "cancelled", "interrupted"].contains(history.runtime?.state ?? "") { break }
        } else {
          guard root != config["originalStoppedRootId"] as? String else { throw HostServiceError.invalidResponse }
          if history.runtime?.state == "idle",
            history.messages.last(where: { $0.role == "assistant" && !$0.text.isEmpty })?.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker + "_RESUMED" {
            reached = true; break
          }
          if ["failed", "cancelled", "interrupted"].contains(history.runtime?.state ?? "") { break }
        }
        try await Task.sleep(for: .milliseconds(500))
      }
      guard reached else { throw HostServiceError.invalidResponse }
      guard try CredentialVault.existingToken("easywork-device-identity") == identity,
        try encoder.encode(store.drafts.values) == drafts, try encoder.encode(store.saved.outbox) == outbox,
        try encoder.encode(store.saved.generationPreferences) == preferences,
        store.saved.replyRecovery?.mapValues(\.fingerprint) == recovery,
        store.saved.selectedClientId == selected, store.selectedThreadId == threadSelection,
        store.selectedProjectId == projectSelection else { throw HostServiceError.invalidResponse }
      report["originalIdentityDraftsOutboxRecoveryPreferencesSelectionUnchanged"] = true
      report["actualModelUsageAndOneRunPerInputRequireIndependentDesktopVerification"] = true
      report["status"] = "passed-current-provider-control-limited-case"; try save()
    } catch {
      report["status"] = "failed-preserve-original-control-operation-no-replay"
      report["failure"] = error.localizedDescription; try save(); throw error
    }
  }

  @MainActor func testOriginalEasyWorkUsageVOneReadWithoutSending() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen original-target usage read case required") }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run,
      config["newModelInputsAuthorized"] as? Int == 0,
      config["newBusinessCommandsAuthorized"] as? Int == 0,
      config["usageReadsAuthorized"] as? Int == 2,
      let negativeID = config["wrongTargetReadRequestId"] as? String, UUID(uuidString: negativeID) != nil else {
      throw NSError(domain: "EasyWorkNativeUsage", code: 1)
    }
    let end = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < end { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    guard !store.localRecoveryRequired, clients.count == 1, target.agentId == "easywork" else {
      throw NSError(domain: "EasyWorkNativeUsage", code: 2)
    }
    let client = clients[0], connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let identity = try CredentialVault.existingToken("easywork-device-identity")
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let selected = store.saved.selectedClientId, thread = store.selectedThreadId, project = store.selectedProjectId
    var report: [String: Any] = ["caseId": run, "appBundleIdentifier": Bundle.main.bundleIdentifier!,
      "appPID": ProcessInfo.processInfo.processIdentifier, "newModels": 0,
      "newBusinessCommands": 0, "wholeFeaturePassed": false, "pureUIReaccepted": false]
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw NSError(domain: "EasyWorkNativeUsage", code: 3) }
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    do {
      report["stage"] = "original-authenticated-usage-read"; try save()
      let data = try await AgentClients.resolve(client).usage(client, target: target)
      let response = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
      let targetJSON = try usageTargetJSON(target)
      let projected = try UsageRules.shared.project(json: String(decoding: data, as: UTF8.self),
        targetJson: targetJSON, nowMillis: Int64(Date().timeIntervalSince1970 * 1000))
      let value = try JSONDecoder().decode(UsageViewData.self, from: Data(projected.utf8))
      guard response["schemaVersion"] as? Int == 1,
        let actualTarget = response["target"] as? [String: Any],
        try JSONSerialization.data(withJSONObject: actualTarget, options: [.sortedKeys]) == Data(targetJSON.utf8),
        ["ok", "unavailable"].contains(value.status) else { throw HostServiceError.invalidResponse }
      if value.status == "unavailable" {
        guard response["snapshot"] == nil, value.balance == nil, value.metrics == nil else { throw HostServiceError.invalidResponse }
        report["missingDataNotInventedAsZero"] = true
      } else {
        let snapshot = try XCTUnwrap(response["snapshot"] as? [String: Any])
        let source = try XCTUnwrap(snapshot["source"] as? [String: Any])
        guard source["adapterId"] as? String == "easywork-balance-v1",
          snapshot["coverage"] as? String == "partial" else { throw HostServiceError.invalidResponse }
        report["balanceOnlyPartialCoveragePreserved"] = true
      }
      report["actualUsageResponsePrivate"] = response
      report["actualSharedProjectionPrivate"] = try JSONSerialization.jsonObject(with: Data(projected.utf8))
      report["usageResponseSHA256"] = HostService.hash(data)
      report["statusBeforeNegativeRead"] = value.status; try save()
      var wrong = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(targetJSON.utf8)) as? [String: Any])
      wrong["hostId"] = target.hostId + "-wrong"
      let channel = try await EasyWorkSessions.shared.session(client)
      var code: String?
      do { _ = try await channel.request("usage/read", ["schemaVersion": 1, "target": wrong, "requestId": negativeID]) }
      catch { code = (error as? EasyWorkRemoteRejection)?.code }
      guard code == "target_forbidden" else { throw NSError(domain: "EasyWorkNativeUsage", code: 4) }
      report["wrongInnerTargetRejected"] = code
      guard try CredentialVault.existingToken("easywork-device-identity") == identity,
        try encoder.encode(store.drafts.values) == drafts, try encoder.encode(store.saved.outbox) == outbox,
        store.saved.replyRecovery?.mapValues(\.fingerprint) == recovery,
        store.saved.selectedClientId == selected, store.selectedThreadId == thread,
        store.selectedProjectId == project else { throw NSError(domain: "EasyWorkNativeUsage", code: 5) }
      report["originalIdentityDraftsOutboxRecoverySelectionUnchanged"] = true
      report["status"] = "passed-original-usage-protocol-and-wrong-target-boundary-only"
      try save()
    } catch {
      report["status"] = "failed-read-only-no-business-replay"; report["failure"] = error.localizedDescription
      try save(); throw error
    }
  }

  @MainActor func testReadOriginalEasyWorkRuntimeWithoutCommands() async throws {
    let environment=ProcessInfo.processInfo.environment
    guard let run=environment["EASYWORK_HOSTED_NATIVE_CASE"],
      let encoded=environment["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Explicit owned EasyWork hosted-native examiner required") }
    guard UUID(uuidString:run)?.uuidString.lowercased()==run else { throw NSError(domain:"EasyWorkHostedNativeExaminer",code:1) }
    var report:[String:Any]=["caseId":run,"bodyEntered":true,"appPID":ProcessInfo.processInfo.processIdentifier,
      "appBundleIdentifier":Bundle.main.bundleIdentifier ?? "","newModelInputs":0,"newPairingOrVoiceInputs":0,
      "uiAutomationUsed":false,"codexTests":false,"wholeFeaturePassed":false]
    defer {
      if let data=try? JSONSerialization.data(withJSONObject:report,options:[.sortedKeys]) {
        let attachment=XCTAttachment(data:data,uniformTypeIdentifier:"public.json")
        attachment.name="easywork-hosted-native-"+run;attachment.lifetime = .keepAlways;add(attachment)
        if let documents=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask).first {
          let directory=documents.appendingPathComponent("easywork-hosted-native",isDirectory:true)
          try? FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
          try? data.write(to:directory.appendingPathComponent(run+".json"),options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication])
        }
      }
    }
    guard Bundle.main.bundleIdentifier=="com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw NSError(domain:"EasyWorkHostedNativeExaminer",code:2)
    }
    let config=try XCTUnwrap(try JSONSerialization.jsonObject(with:Data(encoded.utf8)) as? [String:Any])
    guard config["caseId"] as? String==run,config["newModelInputsAuthorized"] as? Int==0,
      config["newRemoteCommandsAuthorized"] as? Int==0 else { throw NSError(domain:"EasyWorkHostedNativeExaminer",code:3) }
    let expectedData=try JSONSerialization.data(withJSONObject:try XCTUnwrap(config["target"] as? [String:Any]))
    let target=try JSONDecoder().decode(AgentRuntimeTarget.self,from:expectedData)
    guard target.agentId=="easywork" else { throw NSError(domain:"EasyWorkHostedNativeExaminer",code:4) }
    let deadline=Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore==nil && Date()<deadline { try await Task.sleep(for:.milliseconds(100)) }
    let store=try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore,"The actual UI-owned session has not registered in the hosted XCTest process")
    report["existingUIStoreRegistered"]=true
    report["localRecoveryRequired"]=store.localRecoveryRequired
    report["savedClientCount"]=store.saved.clients.count
    report["clientTargetFieldMatches"]=try store.saved.clients.map { client -> [String: Any] in
      var value: [String: Any] = ["clientIdSHA256":HostService.hash(Data(client.id.utf8)),
        "isDemo":client.isDemo,"hasAgentConnection":client.agentConnection != nil]
      if let actual=client.agentConnection?.target {
        value["agentIdEqual"]=actual.agentId==target.agentId
        value["hostIdEqual"]=actual.hostId==target.hostId
        value["workspaceIdEqual"]=actual.workspaceId==target.workspaceId
        value["runtimeIdEqual"]=actual.runtimeId==target.runtimeId
        value["accountPresenceEqual"]=(actual.account == nil)==(target.account == nil)
        value["accountAuthorityEqual"]=actual.account?.authority==target.account?.authority
        value["accountSubjectEqual"]=actual.account?.subject==target.account?.subject
        value["aggregateTargetEqual"]=actual==target
        let encoder=JSONEncoder();encoder.outputFormatting = [.sortedKeys]
        value["actualTargetSHA256"]=HostService.hash(try encoder.encode(actual))
        value["expectedTargetSHA256"]=HostService.hash(try encoder.encode(target))
      }
      return value
    }
    guard !store.localRecoveryRequired else { throw NSError(domain:"EasyWorkHostedNativeExaminer",code:5) }
    let matches=store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target==target }
    report["matchingOriginalClientCount"]=matches.count
    let client=try XCTUnwrap(matches.count==1 ? matches.first : nil,"Use one exact existing EasyWork target; no new connection is created")
    let connection=try XCTUnwrap(client.agentConnection)
    let rawCredential=try XCTUnwrap(try CredentialVault.existingToken(connection.credentialRef))
    let credential=try JSONDecoder().decode(EasyWorkCredential.self,from:Data(rawCredential.utf8))
    let allowed=try XCTUnwrap(config["allowedBindingIds"] as? [String])
    guard allowed.contains(credential.binding.bindingId) else { throw NSError(domain:"EasyWorkHostedNativeExaminer",code:6) }
    let originalIdentity=try XCTUnwrap(try CredentialVault.existingToken("easywork-device-identity"))
    let originalDevice=try JSONDecoder().decode(EasyWorkIdentity.self,from:Data(originalIdentity.utf8))
    guard originalDevice.deviceId==credential.binding.mobileDeviceId,
      originalDevice.publicKey==credential.binding.mobilePublicKey else { throw NSError(domain:"EasyWorkHostedNativeExaminer",code:8) }
    report["bindingId"]=credential.binding.bindingId;report["deviceId"]=credential.binding.mobileDeviceId
    let encoder=JSONEncoder();encoder.outputFormatting = [.sortedKeys]
    let drafts=try encoder.encode(store.drafts.values)
    let originalSelected=store.saved.selectedClientId,originalThread=store.selectedThreadId
    let originalFingerprints=store.saved.replyRecovery?.mapValues(\.fingerprint)
    let provider=try AgentClients.resolve(client)
    do {
      let workspace=try await provider.workspace(client,projectCursor:nil,threadCursor:nil)
      report["workspaceHostMatches"]=workspace.hostId==client.id
      report["optionalReadTargetPresent"]=workspace.readTarget != nil
      report["connectionTargetMatches"]=workspace.agentConnectionState?.target==target
      report["runtimeConnected"]=workspace.agentConnectionState?.runtimeConnected==true
      guard workspace.hostId==client.id,workspace.agentConnectionState?.target==target,
        workspace.agentConnectionState?.runtimeConnected==true else { throw NSError(domain:"EasyWorkHostedNativeExaminer",code:7) }
      report["workspaceHostMatches"]=true;report["runtimeConnected"]=true
      report["capabilities"]=workspace.capabilities ?? [];report["projectCount"]=workspace.projects.count;report["threadCount"]=workspace.threads.count
      XCTAssertEqual(try CredentialVault.existingToken("easywork-device-identity"),originalIdentity)
      XCTAssertEqual(try encoder.encode(store.drafts.values),drafts)
      XCTAssertEqual(store.saved.selectedClientId,originalSelected);XCTAssertEqual(store.selectedThreadId,originalThread)
      XCTAssertEqual(store.saved.replyRecovery?.mapValues(\.fingerprint),originalFingerprints)
      report["identityDraftSelectionAndOriginalIntentFingerprintsExact"]=true
      report["status"]="passed-scoped-real-app-hosted-easywork-runtime-read"
    } catch {
      report["status"]="failed-with-original-hosted-process-observation"
      report["failureType"]=String(describing:type(of:error))
      report["failureDescription"]=error.localizedDescription
      throw error
    }
  }

  @MainActor func testOriginalOperationReceiptAndMissingQueryWithoutReplay() async throws {
    let environment = ProcessInfo.processInfo.environment
    guard let run = environment["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = environment["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Explicit original-operation examiner in the independent acceptance App required")
    }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      config["newRemoteCommandsAuthorized"] as? Int == 0,
      let original = config["originalOperationId"] as? String, !original.isEmpty,
      let missing = config["missingOperationId"] as? String, UUID(uuidString: missing) != nil, missing != original,
      let expectedThread = config["expectedThreadId"] as? String, !expectedThread.isEmpty,
      let expectedTurn = config["expectedTurnId"] as? String, !expectedTurn.isEmpty,
      let expectedState = config["expectedReceiptState"] as? String,
      ["submitted", "completed"].contains(expectedState),
      let bindings = config["allowedBindingIds"] as? [String], !bindings.isEmpty else {
      throw NSError(domain: "EasyWorkOriginalReceiptExaminer", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"] as? [String: Any])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < deadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw NSError(domain: "EasyWorkOriginalReceiptExaminer", code: 2) }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values)
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let fingerprints = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let provider = try AgentClients.resolve(client)
    let receipt = try await provider.receipt(client, id: original)
    XCTAssertEqual(receipt.clientMessageId, original)
    XCTAssertEqual(receipt.hostId, client.id)
    XCTAssertEqual(receipt.threadId, expectedThread)
    XCTAssertEqual(receipt.turnId, expectedTurn)
    XCTAssertEqual(receipt.state, expectedState)
    XCTAssertNil(receipt.error)
    var absentWasNotAccepted = false
    do { _ = try await provider.receipt(client, id: missing) }
    catch {
      guard error.localizedDescription == L10n.tr("Host 尚未找到此操作回执，请先核对桌面历史。原消息和操作 ID 已保留。") else { throw error }
      absentWasNotAccepted = true
    }
    XCTAssertTrue(absentWasNotAccepted)
    XCTAssertEqual(try CredentialVault.existingToken("easywork-device-identity"), identity)
    XCTAssertEqual(try encoder.encode(store.drafts.values), drafts)
    XCTAssertEqual(store.saved.selectedClientId, selectedClient)
    XCTAssertEqual(store.selectedThreadId, selectedThread)
    XCTAssertEqual(store.saved.replyRecovery?.mapValues(\.fingerprint), fingerprints)
    let report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier,
      "status": "observed-original-receipt-and-absent-query-without-replay",
      "originalOperationId": original, "missingOperationId": missing,
      "threadId": receipt.threadId, "turnId": receipt.turnId ?? "", "receiptState": receipt.state,
      "missingReceiptRejected": absentWasNotAccepted, "newModelInputs": 0, "newRemoteCommands": 0,
      "wholeFeaturePassed": false, "codexTests": false]
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: folder.appendingPathComponent(run + ".json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
  }

  @MainActor func testOneFrozenEasyWorkSubagentProjection() async throws {
    let environment = ProcessInfo.processInfo.environment
    guard let run = environment["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = environment["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Explicit single-input EasyWork child-run case required")
    }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run, config["newRootInputsAuthorized"] as? Int == 1,
      config["newThreadsAuthorized"] as? Int == 1, config["maxChildRunsAuthorized"] as? Int == 1,
      let createID = config["createOperationId"] as? String, UUID(uuidString: createID) != nil,
      let sendID = config["sendOperationId"] as? String, UUID(uuidString: sendID) != nil, sendID != createID,
      let body = config["body"] as? String, !body.isEmpty,
      let expectedBodySHA = config["bodySHA256"] as? String, HostService.hash(Data(body.utf8)) == expectedBodySHA,
      let childToken = config["expectedChildToken"] as? String, !childToken.isEmpty,
      let bindings = config["allowedBindingIds"] as? [String], !bindings.isEmpty else {
      throw NSError(domain: "EasyWorkChildRunExaminer", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"] as? [String: Any])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let readyDeadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < readyDeadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw NSError(domain: "EasyWorkChildRunExaminer", code: 2) }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values)
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let fingerprints = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier,
      "createOperationId": createID, "sendOperationId": sendID, "inputSHA256": expectedBodySHA,
      "status": "prepared-no-input", "wholeFeaturePassed": false, "pureUITests": false, "codexTests": false]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: folder.appendingPathComponent(run + ".json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    func protectedState() throws {
      XCTAssertEqual(try CredentialVault.existingToken("easywork-device-identity"), identity)
      XCTAssertEqual(try encoder.encode(store.drafts.values), drafts)
      XCTAssertEqual(store.saved.selectedClientId, selectedClient)
      XCTAssertEqual(store.selectedThreadId, selectedThread)
      XCTAssertEqual(store.saved.replyRecovery?.mapValues(\.fingerprint), fingerprints)
    }
    let provider = try AgentClients.resolve(client)
    do {
      try save()
      let conversation = try await provider.createThread(client, projectId: nil, operationId: createID)
      report["threadId"] = conversation.id
      report["status"] = "original-create-confirmed-not-yet-sent"
      try save()
      let input = PendingReply(hostId: client.id, threadId: conversation.id, clientMessageId: sendID,
        text: body, attachmentIds: [], mode: "next-turn", expectedTurnId: nil, model: nil, effort: nil,
        permissionMode: "confirm_high_risk", sourceAttachments: nil, knownRejected: nil)
      report["status"] = "one-frozen-input-about-to-submit"
      try save()
      let receipt = try await provider.send(client, reply: input)
      guard receipt.clientMessageId == sendID, receipt.threadId == conversation.id,
        let rootRun = receipt.turnId, !rootRun.isEmpty else { throw HostServiceError.invalidResponse }
      report["rootRunId"] = rootRun
      report["status"] = "one-input-accepted-query-only"
      try save()
      let deadline = Date().addingTimeInterval(210)
      var matched: ChatMessage?
      var liveChildObserved = false
      var rootBecameIdle = false
      repeat {
        let history = try await provider.history(client, threadId: conversation.id, cursor: nil)
        let children = history.messages.filter { $0.kind == "subAgent" && $0.runId == rootRun }
        guard children.count <= 1 else { throw NSError(domain: "EasyWorkChildRunExaminer", code: 3) }
        if let child = children.first {
          liveChildObserved = liveChildObserved || child.status == "inProgress" || child.status == "pending"
          if child.status == "completed", child.detail?.contains(childToken) == true { matched = child }
        }
        if matched != nil, history.runtime?.state == "idle" { rootBecameIdle = true; break }
        try await Task.sleep(for: .milliseconds(600))
      } while Date() < deadline
      let child = try XCTUnwrap(matched, "No authentic completed child projection for the single root input")
      guard rootBecameIdle else { throw NSError(domain: "EasyWorkChildRunExaminer", code: 4) }
      report["childMessageId"] = child.id
      report["childStatus"] = child.status ?? ""
      report["childDetailSHA256"] = HostService.hash(Data((child.detail ?? "").utf8))
      report["childTokenPresent"] = child.detail?.contains(childToken) == true
      report["liveChildObserved"] = liveChildObserved
      report["rootBecameIdle"] = rootBecameIdle
      report["status"] = "observed-one-root-and-authenticated-completed-child-projection"
      try protectedState()
      try save()
    } catch {
      report["status"] = "failed-original-input-state-retained-no-replay"
      report["error"] = error.localizedDescription
      try? protectedState()
      try? save()
      throw error
    }
  }

  @MainActor func testOriginalSyncDeltaResetAndReconnectWithoutSending() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Frozen read-only original sync examiner required")
    }
    let c = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard c["caseId"] as? String == run, c["newModelInputsAuthorized"] as? Int == 0,
      c["newRemoteCommandsAuthorized"] as? Int == 0,
      let thread = c["originalThreadId"] as? String, !thread.isEmpty,
      let otherThread = c["otherOriginalThreadId"] as? String, otherThread != thread,
      let expectedIDs = c["expectedMessageIds"] as? [String], !expectedIDs.isEmpty,
      let expectedText = c["expectedTextSHA256ByMessageId"] as? [String: String],
      Set(expectedText.keys) == Set(expectedIDs),
      let bindings = c["allowedBindingIds"] as? [String], !bindings.isEmpty else {
      throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < deadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw HostServiceError.unavailable }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let preferences = try encoder.encode(store.saved.generationPreferences)
    let recovery = try encoder.encode(store.saved.replyRecovery)
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let url = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: url.path) else {
      throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 2)
    }
    var report: [String: Any] = ["caseId": run, "threadId": thread, "newModels": 0,
      "newBusinessCommands": 0, "wholeFeaturePassed": false, "pureUITests": false]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func protectedState() throws {
      guard try encoder.encode(store.drafts.values) == drafts,
        try encoder.encode(store.saved.outbox) == outbox,
        try encoder.encode(store.saved.generationPreferences) == preferences,
        try encoder.encode(store.saved.replyRecovery) == recovery,
        try CredentialVault.existingToken("easywork-device-identity") == identity,
        store.saved.selectedClientId == selectedClient, store.selectedThreadId == selectedThread else {
        throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 3)
      }
      report["originalIdentityDraftsOutboxRecoveryPreferencesSelectionUnchanged"] = true
    }
    func verify(_ data: Data) throws -> [[String: Any]] {
      let value = try easyWorkObject(data)
      let messages = try XCTUnwrap(value["messages"] as? [[String: Any]])
      guard messages.compactMap({ $0["id"] as? String }) == expectedIDs else {
        throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 4)
      }
      for message in messages {
        let id = try XCTUnwrap(message["id"] as? String)
        let parts = try XCTUnwrap(message["content"] as? [[String: Any]])
        let text = parts.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined()
        guard HostService.hash(Data(text.utf8)) == expectedText[id] else {
          throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 5)
        }
      }
      return messages
    }
    let provider = try AgentClients.resolve(client)
    do {
      let channel = try await EasyWorkSessions.shared.session(client)
      let cold = try easyWorkObject(await channel.request("thread/sync", ["threadId": thread, "known": []]))
      let first = try EasyWorkThreadSync().merging(cold, threadId: thread)
      let originals = try verify(first.1)
      let revision = try XCTUnwrap(cold["branchRevision"] as? Int)
      let warm = try easyWorkObject(await channel.request("thread/sync", first.0.parameters(threadId: thread)))
      guard (warm["messages"] as? [Any])?.isEmpty == true,
        (warm["patches"] as? [Any])?.isEmpty == true, warm["reset"] as? Bool == false else {
        throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 6)
      }
      let second = try first.0.merging(warm, threadId: thread)
      _ = try verify(second.1)
      _ = try verify(second.0.merging(warm, threadId: thread).1)
      report["realUnchangedDeltaAndRepeatedMergePreservedOriginals"] = true
      var missingBaseRejected = false
      do { _ = try EasyWorkThreadSync().merging(warm, threadId: thread) }
      catch EasyWorkSyncError.invalidDelta { missingBaseRejected = true }
      guard missingBaseRejected else { throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 7) }
      report["realDeltaWithoutLocalBaseRejected"] = true
      var stale = try first.0.parameters(threadId: thread)
      stale["branchRevision"] = revision + 1
      let reset = try easyWorkObject(await channel.request("thread/sync", stale))
      guard reset["reset"] as? Bool == true, (reset["messages"] as? [Any])?.count == originals.count else {
        throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 8)
      }
      _ = try verify(second.0.merging(reset, threadId: thread).1)
      report["realStaleBranchRevisionResetRebuiltOriginals"] = true
      var badKnown = try first.0.parameters(threadId: thread)
      var known = try XCTUnwrap(badKnown["known"] as? [[String: Any]])
      known[0]["hash"] = String(repeating: "0", count: 64)
      known[0].removeValue(forKey: "textHash")
      known[0].removeValue(forKey: "textLength")
      known[0].removeValue(forKey: "shapeHash")
      badKnown["known"] = known
      let repair = try easyWorkObject(await channel.request("thread/sync", badKnown))
      guard (repair["messages"] as? [[String: Any]])?.contains(where: { $0["id"] as? String == expectedIDs[0] }) == true else {
        throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 9)
      }
      _ = try verify(first.0.merging(repair, threadId: thread).1)
      report["realUnknownVersionReturnedCompleteMessage"] = true
      let before = try await provider.history(client, threadId: thread, cursor: nil)
      _ = try await provider.history(client, threadId: otherThread, cursor: nil)
      let afterSwitch = try await provider.history(client, threadId: thread, cursor: nil)
      guard try encoder.encode(before.messages) == encoder.encode(afterSwitch.messages) else {
        throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 10)
      }
      let oldEpoch = await channel.epoch
      await provider.close(client)
      let fresh = try await EasyWorkSessions.shared.session(client)
      _ = try verify(await fresh.synchronizedThread(thread))
      let afterReconnect = try await provider.history(client, threadId: thread, cursor: nil)
      guard await fresh.epoch != oldEpoch,
        try encoder.encode(before.messages) == encoder.encode(afterReconnect.messages) else {
        throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 11)
      }
      report["productionAdapterThreadSwitchAndFreshEncryptedSessionPreservedOriginals"] = true
      if let pageThread = c["paginationThreadId"] as? String {
        guard let ids = c["expectedPaginationMessageIds"] as? [String], ids.count > 7, ids.count <= 200,
          Set(ids).count == ids.count,
          let hashes = c["expectedPaginationTextSHA256ByMessageId"] as? [String: String],
          Set(hashes.keys) == Set(ids) else { throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 12) }
        var collected: [String] = [], before: String?, pages = 0
        repeat {
          var params: [String: Any] = ["threadId": pageThread, "limit": 7]
          if let before { params["before"] = before }
          let page = try easyWorkObject(await fresh.request("thread/history", params))
          let messages = try XCTUnwrap(page["messages"] as? [[String: Any]])
          let pageIds = messages.compactMap { $0["id"] as? String }
          guard !messages.isEmpty, messages.count <= 7, pageIds.count == messages.count else {
            throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 13)
          }
          for message in messages {
            let id = try XCTUnwrap(message["id"] as? String)
            let parts = try XCTUnwrap(message["content"] as? [[String: Any]])
            let text = parts.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined()
            guard HostService.hash(Data(text.utf8)) == hashes[id] else {
              throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 14)
            }
          }
          collected = pageIds + collected; pages += 1
          guard pages <= 50, Set(collected).count == collected.count else {
            throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 15)
          }
          if page["hasEarlier"] as? Bool == false { break }
          let next = try XCTUnwrap(page["before"] as? String)
          guard next != before else { throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 16) }
          before = next
        } while true
        guard collected == ids else { throw NSError(domain: "EasyWorkOriginalSyncExaminer", code: 17) }
        report["realSmallPageHistoryReadAllOriginalMessagesWithoutGapOrDuplicate"] = true
        report["paginationThreadId"] = pageThread; report["paginationMessageIds"] = collected
        report["paginationPages"] = pages; report["paginationPageLimit"] = 7
      }
      report["messageIds"] = expectedIDs; report["textSHA256ByMessageId"] = expectedText
      report["branchRevision"] = revision
      report["status"] = "passed-real-original-sync-delta-reset-and-reconnect-read-only"
      try protectedState(); try save()
    } catch {
      report["status"] = "failed-read-only-sync-case-no-input-replayed"
      report["error"] = error.localizedDescription
      try? protectedState(); try? save(); throw error
    }
  }

  @MainActor func testOwnRealChatManagementAndOriginalReceiptsWithoutModelInput() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Frozen own real chat-management examiner required")
    }
    let c = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard c["caseId"] as? String == run, c["newModelInputsAuthorized"] as? Int == 0,
      c["newThreadsAuthorized"] as? Int == 0, c["newRemoteCommandsAuthorized"] as? Int == 4,
      c["deleteAuthorized"] as? Bool == false,
      let thread = c["existingOwnThreadId"] as? String,
      let root = c["existingOwnRootRunId"] as? String,
      let nonce = c["ownInputNonce"] as? String, !nonce.isEmpty,
      c["restoreObservedOriginalTitle"] as? Bool == true,
      let actions = c["actions"] as? [[String: Any]], actions.count == 4,
      actions.compactMap({ $0["action"] as? String }) == ["rename", "archive", "unarchive", "rename"],
      Set(actions.compactMap({ $0["operationId"] as? String })).count == 4,
      actions.last?["restoreObservedOriginalTitle"] as? Bool == true,
      let bindings = c["allowedBindingIds"] as? [String], !bindings.isEmpty else {
      throw NSError(domain: "EasyWorkOwnManagement", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < deadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw HostServiceError.unavailable }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let recovery = try encoder.encode(store.saved.replyRecovery)
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let url = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: url.path) else { throw NSError(domain: "EasyWorkOwnManagement", code: 2) }
    var report: [String: Any] = ["caseId": run, "threadId": thread, "rootRunId": root,
      "newModels": 0, "newThreads": 0, "wholeFeaturePassed": false, "pureUITests": false,
      "newBusinessCommands": 0, "receipts": []]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func protectedState() throws {
      guard try encoder.encode(store.drafts.values) == drafts,
        try encoder.encode(store.saved.outbox) == outbox,
        try encoder.encode(store.saved.replyRecovery) == recovery,
        try CredentialVault.existingToken("easywork-device-identity") == identity,
        store.saved.selectedClientId == selectedClient, store.selectedThreadId == selectedThread else {
        throw NSError(domain: "EasyWorkOwnManagement", code: 3)
      }
      report["originalIdentityDraftsOutboxRecoverySelectionUnchanged"] = true
    }
    let provider = try AgentClients.resolve(client)
    do {
      let before = try await provider.history(client, threadId: thread, cursor: nil)
      let title = try XCTUnwrap(before.thread?.title)
      guard before.runtime?.state == "cancelled",
        before.runtime?.receipts.contains(where: { $0.turnId == root && $0.state == "cancelled" }) == true,
        before.messages.contains(where: { $0.role == "user" && $0.text.contains(nonce) }),
        !title.isEmpty, before.thread?.archived == false else {
        throw NSError(domain: "EasyWorkOwnManagement", code: 4)
      }
      let originalMessages = try encoder.encode(before.messages)
      // Persist the effective title returned by this exact authenticated read,
      // including the normal adapter fallback for a nullable canonical title.
      // Freeze the final restore request before dispatching the first action.
      report["originalEffectiveTitle"] = title
      report["normalizedFrozenActions"] = actions.enumerated().map { index, action in
        var value = action
        if index == 3 { value["title"] = title }
        return value
      }
      try save()
      var receipts: [[String: Any]] = []
      var currentTitle = title, archived = false
      for (index, action) in actions.enumerated() {
        let operation = try XCTUnwrap(action["operationId"] as? String)
        let kind = try XCTUnwrap(ChatAction(rawValue: try XCTUnwrap(action["action"] as? String)))
        guard UUID(uuidString: operation) != nil else { throw HostServiceError.invalidResponse }
        let request = ChatActionRequest(hostId: client.id, target: target, threadId: thread,
          clientOperationId: operation, action: kind, title: index == 3 ? title : action["title"] as? String)
        report["status"] = "about-to-dispatch-one-frozen-own-action"
        report["attemptingOperationId"] = operation; try save()
        let receipt = try await provider.chatAction(client, request: request)
        guard receipt.phase == "confirmed", receipt.request == request else { throw HostServiceError.invalidResponse }
        report["newBusinessCommands"] = receipts.count + 1
        let queried = try await provider.chatActionStatus(client, threadId: thread, operationId: operation)
        guard queried == receipt else { throw HostServiceError.invalidResponse }
        if kind == .rename { currentTitle = try XCTUnwrap(request.title) }
        if kind == .archive { archived = true }
        if kind == .unarchive { archived = false }
        let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
        let summary = try XCTUnwrap(workspace.threads.first { $0.id == thread })
        guard summary.title == currentTitle, summary.archived == archived else {
          throw NSError(domain: "EasyWorkOwnManagement", code: 5)
        }
        let history = try await provider.history(client, threadId: thread, cursor: nil)
        guard try encoder.encode(history.messages) == originalMessages else {
          throw NSError(domain: "EasyWorkOwnManagement", code: 6)
        }
        receipts.append(["operationId": operation, "action": kind.rawValue, "phase": receipt.phase,
          "originalStatusMatched": true, "canonicalTitleAndArchiveMatched": true,
          "canonicalOriginalMessageProjectionUnchanged": true])
        report["receipts"] = receipts; try protectedState(); try save()
      }
      report["status"] = "passed-own-real-thread-rename-archive-restore-and-original-receipts"
      report["originalEffectiveTitleAndActiveStateRestored"] = true
      try protectedState(); try save()
    } catch {
      report["status"] = "failed-own-management-retain-original-operation-query-only"
      report["error"] = error.localizedDescription
      try? protectedState(); try? save(); throw error
    }
  }

  @MainActor func testOriginalChildEventReplayWithoutSending() async throws {
    let environment = ProcessInfo.processInfo.environment
    guard let run = environment["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = environment["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Explicit original-child replay examiner required")
    }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      config["newRemoteCommandsAuthorized"] as? Int == 0,
      let thread = config["originalThreadId"] as? String, !thread.isEmpty,
      let rootRun = config["originalRootRunId"] as? String, !rootRun.isEmpty,
      let childID = config["originalChildMessageId"] as? String, childID.hasPrefix("subagent:"),
      let detailSHA = config["expectedChildDetailSHA256"] as? String, detailSHA.count == 64,
      let expectedCursor = config["expectedMinimumEventCursor"] as? Int, expectedCursor > 0,
      let bindings = config["allowedBindingIds"] as? [String] else {
      throw NSError(domain: "EasyWorkChildReplayExaminer", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"] as? [String: Any])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let readyDeadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < readyDeadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw NSError(domain: "EasyWorkChildReplayExaminer", code: 2) }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values)
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let originalClient = store.saved.selectedClientId, originalThread = store.selectedThreadId
    let fingerprints = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let provider = try AgentClients.resolve(client)
    var epoch = "", cursor = 0, batches = 0
    let deadline = Date().addingTimeInterval(55)
    repeat {
      let batch = try await provider.events(client, epoch: epoch, after: cursor, threadId: thread)
      guard batch.hostId == client.id, batch.epoch == target.runtimeId,
        batch.events.allSatisfy({ $0.threadId == thread }) else { throw HostServiceError.wrongHost }
      epoch = batch.epoch; cursor = max(cursor, batch.cursor); batches += 1
      if cursor >= expectedCursor { break }
    } while Date() < deadline
    XCTAssertGreaterThanOrEqual(cursor, expectedCursor)
    let history = try await provider.history(client, threadId: thread, cursor: nil)
    let child = try XCTUnwrap(history.messages.first { $0.id == childID && $0.kind == "subAgent" })
    XCTAssertEqual(child.runId, rootRun)
    XCTAssertEqual(child.status, "completed")
    XCTAssertEqual(HostService.hash(Data((child.detail ?? "").utf8)), detailSHA)
    XCTAssertEqual(history.runtime?.state, "idle")
    XCTAssertEqual(try encoder.encode(store.drafts.values), drafts)
    XCTAssertEqual(try CredentialVault.existingToken("easywork-device-identity"), identity)
    XCTAssertEqual(store.saved.selectedClientId, originalClient)
    XCTAssertEqual(store.selectedThreadId, originalThread)
    XCTAssertEqual(store.saved.replyRecovery?.mapValues(\.fingerprint), fingerprints)
    let report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier,
      "status": "observed-authenticated-original-event-replay-and-exact-canonical-child",
      "threadId": thread, "rootRunId": rootRun, "childMessageId": childID,
      "childDetailSHA256": HostService.hash(Data((child.detail ?? "").utf8)), "childStatus": child.status ?? "",
      "minimumFrozenCursor": expectedCursor, "replayedCursor": cursor, "batches": batches,
      "newModels": 0, "newBusinessCommands": 0, "wholeFeaturePassed": false, "pureUITests": false]
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: folder.appendingPathComponent(run + ".json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
  }

  @MainActor func testOriginalEncryptedEventDeliveryFaultsAndCanonicalRepairWithoutSending() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Explicit same-original encrypted event-fault examiner required")
    }
    var report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier,
      "stage": "parse-frozen-config", "newModels": 0, "newBusinessCommands": 0,
      "wholeFeaturePassed": false, "pureUITests": false, "systemNetworkFaultClaimed": false]
    defer {
      if let data = try? JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]) {
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
        attachment.name = "easywork-hosted-native-" + run; attachment.lifetime = .keepAlways; add(attachment)
        if let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
          let folder = documents.appendingPathComponent("easywork-hosted-native", isDirectory: true)
          try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
          try? data.write(to: folder.appendingPathComponent(run + ".json"),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
      }
    }
    #if !targetEnvironment(simulator)
    throw XCTSkip("This frozen receive-fault case is Simulator-only")
    #else
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      config["newRemoteCommandsAuthorized"] as? Int == 0,
      config["controlledReceiveFault"] as? Bool == true,
      config["maximumFaultFramesAuthorized"] as? Int == 4,
      let thread = config["originalThreadId"] as? String, !thread.isEmpty,
      let rootRun = config["originalRootRunId"] as? String, !rootRun.isEmpty,
      let childID = config["originalChildMessageId"] as? String, childID.hasPrefix("subagent:"),
      let detailSHA = config["expectedChildDetailSHA256"] as? String, detailSHA.count == 64,
      let expectedCursor = config["expectedMinimumEventCursor"] as? Int, expectedCursor > 0,
      let bindings = config["allowedBindingIds"] as? [String] else {
      throw NSError(domain: "EasyWorkEventFaultExaminer", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"] as? [String: Any])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let ready = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < ready {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let matches = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    report["stage"] = "original-client-guard"; report["matchingOriginalClients"] = matches.count
    guard !store.localRecoveryRequired, matches.count == 1 else { throw HostServiceError.wrongHost }
    let client = try XCTUnwrap(matches.first), connection = try XCTUnwrap(client.agentConnection)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId), credential.grantRef == nil else { throw HostServiceError.wrongHost }
    let candidate = try XCTUnwrap(connection.candidates.first { $0.transportId == "lan-wss" })
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values), outbox = try encoder.encode(store.saved.outbox)
    let clients = try encoder.encode(store.saved.clients), preferences = try encoder.encode(store.saved.generationPreferences)
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let provider = try AgentClients.resolve(client)
    await provider.close(client)
    let transportID = "easywork-acceptance-original-events-" + run
    let fault = try EasyWorkOriginalEventFaultTransport(credential: credential,
      candidate: candidate, transportID: transportID, thread: thread)
    await AgentTransportRegistry.shared.register(transportID) { options in try await fault.make(options) }
    var readClient = client, readConnection = connection, readCandidate = candidate
    readCandidate.id = transportID; readCandidate.transportId = transportID
    readConnection.candidates = [readCandidate]; readConnection.preference = .automatic
    readClient.agentConnection = readConnection
    do {
      report["stage"] = "real-authenticated-replay-through-owned-adapter"
      let channel = try await EasyWorkSessions.shared.session(readClient)
      // Reset only this binding's read subscription through its normal named
      // API. A prior replay must not suppress the frozen original event range.
      _ = try await channel.request("thread/unsubscribe", ["threadId": thread])
      var epoch = "", cursor = 0, batches = 0
      let deadline = Date().addingTimeInterval(55)
      repeat {
        let batch = try await provider.events(readClient, epoch: epoch, after: cursor, threadId: thread)
        guard batch.hostId == client.id, batch.epoch == target.runtimeId,
          batch.events.allSatisfy({ $0.threadId == thread }) else { throw HostServiceError.wrongHost }
        epoch = batch.epoch; cursor = max(cursor, batch.cursor); batches += 1
        if cursor >= expectedCursor { break }
      } while Date() < deadline
      var witness = await fault.witness(), applied = await channel.eventSequence
      let drained = Date().addingTimeInterval(5)
      while Date() < drained && (!witness.injected || witness.pendingFrames != 0 || applied != witness.passthroughEvents + 3) {
        try await Task.sleep(for: .milliseconds(50))
        witness = await fault.witness(); applied = await channel.eventSequence
      }
      func frames(_ values: [EasyWorkOriginalEventFaultTransport.Frame]) -> [[String: Any]] {
        values.map { ["threadSequence": $0.cursor, "ciphertextSHA256": $0.ciphertextSHA256,
          "envelopeIDHash": $0.envelopeIDHash] }
      }
      report["sourceFrames"] = frames(witness.source); report["deliveredFrames"] = frames(witness.delivered)
      report["droppedFrames"] = frames(witness.dropped); report["appliedProductionEventCount"] = applied
      report["untouchedPassthroughEventCount"] = witness.passthroughEvents
      report["replayedCursor"] = cursor; report["batches"] = batches
      report["stage"] = "duplicate-order-gap-assertions"
      guard witness.injected, witness.source.count == 4, witness.pendingFrames == 0,
        witness.delivered == [witness.source[3], witness.source[0], witness.source[0], witness.source[2]],
        witness.dropped == [witness.source[1]], applied == witness.passthroughEvents + 3,
        cursor >= witness.source[3].cursor, cursor >= expectedCursor else {
        throw NSError(domain: "EasyWorkEventFaultExaminer", code: 2)
      }
      report["stage"] = "fresh-original-canonical-child-history"
      let history = try await provider.history(readClient, threadId: thread, cursor: nil)
      let child = try XCTUnwrap(history.messages.first { $0.id == childID && $0.kind == "subAgent" })
      XCTAssertEqual(child.runId, rootRun); XCTAssertEqual(child.status, "completed")
      XCTAssertEqual(HostService.hash(Data((child.detail ?? "").utf8)), detailSHA)
      XCTAssertEqual(history.runtime?.state, "idle")
      XCTAssertEqual(try encoder.encode(store.drafts.values), drafts)
      XCTAssertEqual(try encoder.encode(store.saved.outbox), outbox)
      XCTAssertEqual(try encoder.encode(store.saved.clients), clients)
      XCTAssertEqual(try encoder.encode(store.saved.generationPreferences), preferences)
      XCTAssertEqual(try CredentialVault.existingToken("easywork-device-identity"), identity)
      XCTAssertEqual(store.saved.selectedClientId, selectedClient); XCTAssertEqual(store.selectedThreadId, selectedThread)
      XCTAssertEqual(store.saved.replyRecovery?.mapValues(\.fingerprint), recovery)
      report["childDetailSHA256"] = HostService.hash(Data((child.detail ?? "").utf8))
      report["childStatus"] = child.status ?? ""; report["stage"] = "assertions-completed"
      report["status"] = "observed-real-encrypted-duplicate-reorder-gap-and-original-canonical-repair"
      _ = try await channel.request("thread/unsubscribe", ["threadId": thread])
      await provider.close(readClient); await fault.close()
    } catch {
      report["status"] = "failed-original-event-fault-examiner"
      report["errorDomain"] = (error as NSError).domain; report["errorCode"] = (error as NSError).code
      await provider.close(readClient); await fault.close()
      throw error
    }
    #endif
  }

  @MainActor func testOriginalRootSettingsAndCatalogWithoutSending() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Explicit original-root settings examiner required")
    }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      config["newRemoteCommandsAuthorized"] as? Int == 0,
      let thread = config["originalThreadId"] as? String, !thread.isEmpty,
      let root = config["originalRootRunId"] as? String, !root.isEmpty,
      let bindings = config["allowedBindingIds"] as? [String],
      let mode = config["expectedCollaborationMode"] as? String else {
      throw NSError(domain: "EasyWorkRootSettingsExaminer", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let expected = try JSONDecoder().decode(GenerationOptions.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["expectedGenerationSettings"])))
    guard target.agentId == "easywork", expected.model?.isEmpty == false else { throw HostServiceError.wrongHost }
    let readyDeadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < readyDeadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw NSError(domain: "EasyWorkRootSettingsExaminer", code: 2) }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values), preferences = try encoder.encode(store.saved.generationPreferences)
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let provider = try AgentClients.resolve(client)
    let catalog = try await provider.models(client)
    XCTAssertFalse(catalog.models.isEmpty)
    XCTAssertEqual(Set(catalog.models.map(\.id)).count, catalog.models.count)
    let permissions = provider.permissionOptions(client)
    XCTAssertEqual(Set(permissions.map(\.id)), Set(["confirm_every_operation", "confirm_high_risk", "allow_all"]))
    XCTAssertEqual(permissions.filter(\.highPrivilege).map(\.id), ["allow_all"])
    let history = try await provider.history(client, threadId: thread, cursor: nil)
    XCTAssertEqual(history.hostId, client.id); XCTAssertEqual(history.threadId, thread)
    let runtime = try XCTUnwrap(history.runtime)
    XCTAssertEqual(runtime.state, "idle"); XCTAssertEqual(runtime.collaborationMode, mode)
    XCTAssertEqual(try encoder.encode(try XCTUnwrap(runtime.generationSettings)), try encoder.encode(expected))
    // Reading old execution settings must not overwrite the next-send preferences.
    XCTAssertEqual(try encoder.encode(store.drafts.values), drafts)
    XCTAssertEqual(try encoder.encode(store.saved.generationPreferences), preferences)
    XCTAssertEqual(try CredentialVault.existingToken("easywork-device-identity"), identity)
    XCTAssertEqual(store.saved.selectedClientId, selectedClient); XCTAssertEqual(store.selectedThreadId, selectedThread)
    XCTAssertEqual(store.saved.replyRecovery?.mapValues(\.fingerprint), recovery)
    let settingsJSON = try JSONSerialization.jsonObject(with: encoder.encode(expected))
    let report: [String: Any] = ["caseId": run, "nativeProcessId": ProcessInfo.processInfo.processIdentifier,
      "status": "observed-original-root-settings-and-readonly-catalog", "threadId": thread, "rootRunId": root,
      "generationSettings": settingsJSON, "collaborationMode": runtime.collaborationMode ?? "",
      "modelCount": catalog.models.count, "catalogContainsHistoricalModel": catalog.models.contains { $0.id == expected.model },
      "catalogModelIDsSHA256": HostService.hash(Data(catalog.models.map(\.id).sorted().joined(separator: "\n").utf8)),
      "modelsWithExplicitFastCapability": catalog.models.filter { $0.supportedSpeeds?.contains("fast") == true }.count,
      "permissionIDs": permissions.map(\.id), "draftAndNextSendSettingsUnchanged": true,
      "actualPermissionEnforcementTested": false, "actualFastTierTested": false,
      "newModels": 0, "newBusinessCommands": 0, "wholeFeaturePassed": false, "pureUITests": false]
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: folder.appendingPathComponent(run + ".json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
  }

  @MainActor func testFinishedRunGuidanceRejectedAndReceiptWithoutReplay() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Explicit finished-run guidance examiner required")
    }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      config["newRemoteCommandsAuthorized"] as? Int == 1,
      let thread = config["originalThreadId"] as? String, !thread.isEmpty,
      let root = config["originalRootRunId"] as? String, !root.isEmpty,
      let operation = config["guidanceOperationId"] as? String, UUID(uuidString: operation) != nil,
      let body = config["body"] as? String, !body.isEmpty,
      HostService.hash(Data(body.utf8)) == config["bodySHA256"] as? String,
      let bindings = config["allowedBindingIds"] as? [String] else {
      throw NSError(domain: "EasyWorkGuidanceExaminer", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < deadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw NSError(domain: "EasyWorkGuidanceExaminer", code: 2) }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values), preferences = try encoder.encode(store.saved.generationPreferences)
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let provider = try AgentClients.resolve(client)
    let before = try await provider.history(client, threadId: thread, cursor: nil)
    guard before.runtime?.state == "idle" else { throw NSError(domain: "EasyWorkGuidanceExaminer", code: 3) }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw NSError(domain: "EasyWorkGuidanceExaminer", code: 4) }
    var report: [String: Any] = ["caseId": run, "threadId": thread, "rootRunId": root,
      "operationId": operation, "newModels": 0, "newBusinessCommands": 1,
      "wholeFeaturePassed": false, "pureUITests": false]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    report["sendAttempted"] = true; try save()
    let reply = PendingReply(hostId: client.id, threadId: thread, clientMessageId: operation,
      text: body, attachmentIds: [], mode: "steer", expectedTurnId: root, model: nil, effort: nil)
    let sent = try await provider.send(client, reply: reply)
    XCTAssertEqual(sent.clientMessageId, operation); XCTAssertEqual(sent.threadId, thread)
    XCTAssertEqual(sent.state, "failed"); XCTAssertNil(sent.turnId); XCTAssertNotNil(sent.error)
    report["sendState"] = sent.state; report["sendHasExecutedTurn"] = sent.turnId != nil; try save()
    let queried = try await provider.receipt(client, id: operation)
    XCTAssertEqual(try encoder.encode(sent), try encoder.encode(queried))
    let after = try await provider.history(client, threadId: thread, cursor: nil)
    XCTAssertEqual(after.runtime?.state, "idle")
    XCTAssertEqual(try encoder.encode(before.messages), try encoder.encode(after.messages))
    XCTAssertEqual(try encoder.encode(store.drafts.values), drafts)
    XCTAssertEqual(try encoder.encode(store.saved.generationPreferences), preferences)
    XCTAssertEqual(try CredentialVault.existingToken("easywork-device-identity"), identity)
    XCTAssertEqual(store.saved.selectedClientId, selectedClient); XCTAssertEqual(store.selectedThreadId, selectedThread)
    XCTAssertEqual(store.saved.replyRecovery?.mapValues(\.fingerprint), recovery)
    report["status"] = "observed-finished-run-guidance-refused-and-original-query-matched"
    report["queryState"] = queried.state; report["queryHasExecutedTurn"] = queried.turnId != nil
    report["originalMessagesSHA256"] = HostService.hash(try encoder.encode(after.messages))
    report["nativeProcessId"] = ProcessInfo.processInfo.processIdentifier
    try save()
  }

  @MainActor func testOneFrozenQuestionQueueGuidanceAndStop() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Explicit bounded question/control examiner required")
    }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run, config["newRootInputsAuthorized"] as? Int == 1,
      config["newThreadsAuthorized"] as? Int == 1, config["maxChildRunsAuthorized"] as? Int == 0,
      let bindings = config["allowedBindingIds"] as? [String] else {
      throw NSError(domain: "EasyWorkQuestionControlExaminer", code: 1)
    }
    func field(_ key: String) throws -> String {
      let value = try XCTUnwrap(config[key] as? String); XCTAssertFalse(value.isEmpty); return value
    }
    let body = try field("body"), guidanceBody = try field("guidanceBody"), queueBody = try field("queueBody")
    for (text, key) in [(body, "bodySHA256"), (guidanceBody, "guidanceSHA256"), (queueBody, "queueSHA256")] {
      guard HostService.hash(Data(text.utf8)) == config[key] as? String else { throw HostServiceError.invalidResponse }
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let ready = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < ready {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw NSError(domain: "EasyWorkQuestionControlExaminer", code: 2) }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values), preferences = try encoder.encode(store.saved.generationPreferences)
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let provider = try AgentClients.resolve(client)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw NSError(domain: "EasyWorkQuestionControlExaminer", code: 3) }
    var report: [String: Any] = ["caseId": run, "wholeFeaturePassed": false, "pureUITests": false,
      "maxRootInputs": 1, "maxChildRuns": 0, "stopAttempted": false]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    var ownedThread: String?, ownedRun: String?, stopped = false
    do {
      report["createAttempted"] = true; try save()
      let thread = try await provider.createThread(client, projectId: nil, operationId: try field("createOperationId"))
      ownedThread = thread.id; report["threadId"] = thread.id; try save()
      XCTAssertNil(thread.projectId)
      let reply = PendingReply(hostId: client.id, threadId: thread.id, clientMessageId: try field("sendOperationId"),
        text: body, attachmentIds: [], mode: "next-turn", expectedTurnId: nil, model: nil, effort: nil,
        permissionMode: "confirm_high_risk")
      report["sendAttempted"] = true; try save()
      let sent = try await provider.send(client, reply: reply)
      XCTAssertEqual(sent.state, "submitted")
      let root = try XCTUnwrap(sent.turnId); ownedRun = root; report["rootRunId"] = root; try save()
      func waitQuestion(_ token: String) async throws -> (HistorySnapshot, Approval) {
        let limit = Date().addingTimeInterval(150)
        repeat {
          let value = try await provider.history(client, threadId: thread.id, cursor: nil)
          if let question = value.runtime?.approvals.first(where: { $0.kind == "question" && $0.questions.contains { $0.question.contains(token) } }) {
            guard question.threadId == thread.id, question.turnId == root,
              value.runtime?.state == "waiting-for-approval" else { throw HostServiceError.wrongHost }
            return (value, question)
          }
          if ["failed", "cancelled", "interrupted"].contains(value.runtime?.state ?? "") { break }
          try await Task.sleep(for: .milliseconds(500))
        } while Date() < limit
        throw NSError(domain: "EasyWorkQuestionControlExaminer", code: 4, userInfo: [NSLocalizedDescriptionKey: "Expected original question not observed"])
      }
      let (_, first) = try await waitQuestion(try field("question1Token"))
      XCTAssertEqual(first.questions.count, 1)
      report["firstQuestionId"] = first.id; report["firstQuestionObserved"] = true; try save()
      let queuedID = try field("queueOperationId")
      let queued = PendingReply(hostId: client.id, threadId: thread.id, clientMessageId: queuedID,
        text: queueBody, attachmentIds: [], mode: "queue", expectedTurnId: root, model: nil, effort: nil)
      report["queueAttempted"] = true; try save()
      let queuedReceipt = try await provider.send(client, reply: queued)
      XCTAssertEqual(queuedReceipt.state, "queued")
      report["queueCancelAttempted"] = true; try save()
      let cancelledQueue = try await provider.cancelQueue(client, threadId: thread.id, messageId: queuedID)
      XCTAssertTrue(cancelledQueue.ok)
      let queueStatus = try await provider.receipt(client, id: queuedID)
      XCTAssertEqual(queueStatus.state, "cancelled")
      report["queueCancelled"] = true; try save()
      let guidanceID = try field("guidanceOperationId")
      let guidance = PendingReply(hostId: client.id, threadId: thread.id, clientMessageId: guidanceID,
        text: guidanceBody, attachmentIds: [], mode: "steer", expectedTurnId: root, model: nil, effort: nil)
      report["guidanceAttempted"] = true; try save()
      let guided = try await provider.send(client, reply: guidance)
      XCTAssertEqual(guided.state, "submitted"); XCTAssertEqual(guided.turnId, root)
      let guidanceStatus = try await provider.receipt(client, id: guidanceID)
      XCTAssertEqual(guidanceStatus.state, "submitted")
      report["guidanceAccepted"] = guided.state == "submitted"; try save()
      let question = try XCTUnwrap(first.questions.first), choice = try field("answerChoice")
      guard question.options?.contains(where: { $0.label == choice }) == true else { throw HostServiceError.invalidResponse }
      report["answerAttempted"] = true; try save()
      let answered = try await provider.approve(client, approval: first, decision: "answer", answers: [question.id: [choice]])
      XCTAssertTrue(answered.ok)
      report["firstAnswerAccepted"] = true; try save()
      let (beforeStop, second) = try await waitQuestion(try field("question2Token"))
      XCTAssertNotEqual(first.id, second.id)
      let generated = beforeStop.messages.filter { $0.role == "assistant" }.map(\.text).joined(separator: "\n")
      XCTAssertTrue(generated.contains(try field("answerOutputToken")))
      XCTAssertTrue(generated.contains(try field("guidanceOutputToken")))
      report["generatedAnswerAndGuidanceObserved"] = true; report["secondQuestionId"] = second.id
      report["stopAttempted"] = true; stopped = true; try save()
      let stoppedRun = try await provider.stop(client, threadId: thread.id, turnId: root)
      XCTAssertTrue(stoppedRun.ok)
      var final = try await provider.history(client, threadId: thread.id, cursor: nil)
      let stopLimit = Date().addingTimeInterval(35)
      while !["cancelled", "interrupted"].contains(final.runtime?.state ?? "") && Date() < stopLimit {
        try await Task.sleep(for: .milliseconds(500)); final = try await provider.history(client, threadId: thread.id, cursor: nil)
      }
      XCTAssertTrue(["cancelled", "interrupted"].contains(final.runtime?.state ?? ""))
      XCTAssertEqual(final.runtime?.approvals.count, 0)
      XCTAssertEqual(try encoder.encode(store.drafts.values), drafts)
      XCTAssertEqual(try encoder.encode(store.saved.generationPreferences), preferences)
      XCTAssertEqual(try CredentialVault.existingToken("easywork-device-identity"), identity)
      XCTAssertEqual(store.saved.selectedClientId, selectedClient); XCTAssertEqual(store.selectedThreadId, selectedThread)
      XCTAssertEqual(store.saved.replyRecovery?.mapValues(\.fingerprint), recovery)
      report["status"] = "observed-projectless-first-send-question-answer-queue-cancel-guidance-and-stop"
      report["terminalState"] = final.runtime?.state ?? ""; report["nativeProcessId"] = ProcessInfo.processInfo.processIdentifier
      try save()
    } catch {
      // Release only this owned run if a pre-stop assertion/transport failed.
      // An attempted stop is never replayed, including an unknown response.
      if !stopped, let thread = ownedThread, let root = ownedRun,
        let current = try? await provider.history(client, threadId: thread, cursor: nil), current.runtime?.turnId == root {
        report["stopAttempted"] = true; report["cleanupStop"] = true; try? save()
        _ = try? await provider.stop(client, threadId: thread, turnId: root)
      }
      report["status"] = "failed-no-model-input-replay"; try? save(); throw error
    }
  }

  @MainActor func testOriginalAnsweredAndCancelledQuestionsRejectLateAnswers() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw XCTSkip("Explicit original-question rejection examiner required")
    }
    let config = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])
    guard config["caseId"] as? String == run, config["newModelInputsAuthorized"] as? Int == 0,
      config["newRemoteCommandsAuthorized"] as? Int == 2,
      let thread = config["originalThreadId"] as? String, !thread.isEmpty,
      let root = config["originalRootRunId"] as? String, !root.isEmpty,
      let bindings = config["allowedBindingIds"] as? [String],
      let choice = config["answerChoice"] as? String else {
      throw NSError(domain: "EasyWorkLateQuestionExaminer", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["target"])))
    let approvals = try JSONDecoder().decode([Approval].self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(config["originalApprovals"])))
    guard target.agentId == "easywork", approvals.count == 2, Set(approvals.map(\.id)).count == 2,
      approvals.allSatisfy({ $0.threadId == thread && $0.turnId == root && $0.kind == "question" && $0.questions.count == 1 }) else {
      throw HostServiceError.wrongHost
    }
    let ready = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < ready {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw NSError(domain: "EasyWorkLateQuestionExaminer", code: 2) }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let drafts = try encoder.encode(store.drafts.values), preferences = try encoder.encode(store.saved.generationPreferences)
    let identity = try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
    let selectedClient = store.saved.selectedClientId, selectedThread = store.selectedThreadId
    let recovery = store.saved.replyRecovery?.mapValues(\.fingerprint)
    let provider = try AgentClients.resolve(client)
    let before = try await provider.history(client, threadId: thread, cursor: nil)
    guard before.runtime?.state == "cancelled", before.runtime?.approvals.isEmpty == true else { throw HostServiceError.invalidResponse }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw NSError(domain: "EasyWorkLateQuestionExaminer", code: 3) }
    var report: [String: Any] = ["caseId": run, "threadId": thread, "rootRunId": root,
      "newModels": 0, "wholeFeaturePassed": false, "pureUITests": false]
    var rejected: [String] = []
    func save() throws {
      report["rejectionCodes"] = rejected
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    for (index, approval) in approvals.enumerated() {
      let question = try XCTUnwrap(approval.questions.first)
      guard question.options?.contains(where: { $0.label == choice }) == true else { throw HostServiceError.invalidResponse }
      report["attemptedAnswers"] = index + 1; try save()
      do {
        _ = try await provider.approve(client, approval: approval, decision: "answer", answers: [question.id: [choice]])
        throw NSError(domain: "EasyWorkLateQuestionExaminer", code: 4, userInfo: [NSLocalizedDescriptionKey: "A resolved original question accepted a late answer"])
      } catch let refusal as EasyWorkRemoteRejection {
        guard refusal.code == "question_mismatch" else { throw refusal }
        rejected.append(try XCTUnwrap(refusal.code)); try save()
      }
    }
    let after = try await provider.history(client, threadId: thread, cursor: nil)
    XCTAssertEqual(after.runtime?.state, "cancelled"); XCTAssertEqual(after.runtime?.approvals.count, 0)
    XCTAssertEqual(try encoder.encode(before.messages), try encoder.encode(after.messages))
    XCTAssertEqual(try encoder.encode(store.drafts.values), drafts)
    XCTAssertEqual(try encoder.encode(store.saved.generationPreferences), preferences)
    XCTAssertEqual(try CredentialVault.existingToken("easywork-device-identity"), identity)
    XCTAssertEqual(store.saved.selectedClientId, selectedClient); XCTAssertEqual(store.selectedThreadId, selectedThread)
    XCTAssertEqual(store.saved.replyRecovery?.mapValues(\.fingerprint), recovery)
    report["status"] = "observed-authenticated-late-answer-refusal-for-original-answered-and-cancelled-questions"
    report["nativeProcessId"] = ProcessInfo.processInfo.processIdentifier; try save()
  }

  @MainActor func testRejectInvalidEasyWorkForkSourcesWithoutCreatingForks() async throws {
    let env=ProcessInfo.processInfo.environment
    guard let run=env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString:run)?.uuidString.lowercased()==run,
      let encoded=env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier=="com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw NSError(domain:"EasyWorkHostedForkExaminer",code:1)
    }
    let config=try XCTUnwrap(try JSONSerialization.jsonObject(with:Data(encoded.utf8)) as? [String:Any])
    guard config["caseId"] as? String==run,config["newModelInputsAuthorized"] as? Int==0,
      config["newActualForksAuthorized"] as? Int==0,config["newRemoteCommandsAuthorized"] as? Int==4 else {
      throw NSError(domain:"EasyWorkHostedForkExaminer",code:2)
    }
    let directory=try XCTUnwrap(FileManager.default.urls(for:.documentDirectory,in:.userDomainMask).first)
      .appendingPathComponent("easywork-hosted-native",isDirectory:true)
    try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
    let path=directory.appendingPathComponent(run+".json")
    guard !FileManager.default.fileExists(atPath:path.path) else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:3) }
    var report:[String:Any]=["caseId":run,"bodyEntered":true,"appPID":ProcessInfo.processInfo.processIdentifier,
      "appBundleIdentifier":Bundle.main.bundleIdentifier!,"uiAutomationUsed":false,"newModelInputs":0,
      "newActualForks":0,"codexTests":false,"wholeFeaturePassed":false]
    var results:[[String:Any]]=[]
    func save() throws {
      report["results"]=results
      let data=try JSONSerialization.data(withJSONObject:report,options:[.sortedKeys])
      try data.write(to:path,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication])
    }
    try save()
    defer {
      try? save()
      if let data=try? Data(contentsOf:path) {
        let attachment=XCTAttachment(data:data,uniformTypeIdentifier:"public.json")
        attachment.name="easywork-hosted-fork-"+run;attachment.lifetime = .keepAlways;add(attachment)
      }
    }
    do {
      let target=try JSONDecoder().decode(AgentRuntimeTarget.self,from:JSONSerialization.data(withJSONObject:try XCTUnwrap(config["target"])))
      guard target.agentId=="easywork" else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:4) }
      let deadline=Date().addingTimeInterval(30)
      while SessionStore.hostedEasyWorkAcceptanceStore==nil && Date()<deadline { try await Task.sleep(for:.milliseconds(100)) }
      let store=try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
      guard !store.localRecoveryRequired else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:5) }
      let matches=store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target==target }
      let client=try XCTUnwrap(matches.count==1 ? matches.first : nil)
      let connection=try XCTUnwrap(client.agentConnection)
      let credential=try JSONDecoder().decode(EasyWorkCredential.self,from:Data(try XCTUnwrap(CredentialVault.existingToken(connection.credentialRef)).utf8))
      let allowed=try XCTUnwrap(config["allowedBindingIds"] as? [String])
      guard allowed.contains(credential.binding.bindingId) else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:6) }
      let identity=try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
      let device=try JSONDecoder().decode(EasyWorkIdentity.self,from:Data(identity.utf8))
      guard device.deviceId==credential.binding.mobileDeviceId,device.publicKey==credential.binding.mobilePublicKey else {
        throw NSError(domain:"EasyWorkHostedForkExaminer",code:7)
      }
      let encoder=JSONEncoder();encoder.outputFormatting = [.sortedKeys]
      let drafts=try encoder.encode(store.drafts.values),selected=store.saved.selectedClientId,thread=store.selectedThreadId
      let originalIntents=store.saved.replyRecovery?.mapValues(\.fingerprint)
      let provider=try AgentClients.resolve(client)
      let rows=try XCTUnwrap(config["rejectedRequests"] as? [[String:Any]])
      let ids=rows.compactMap { $0["operationId"] as? String }
      guard rows.count==4,ids.count==4,Set(ids).count==4 else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:8) }
      var originals:[ThreadForkRequest]=[]
      report["existingUIStoreRegistered"]=true;try save()
      for row in rows {
        let kind=try XCTUnwrap(row["kind"] as? String)
        let environment=try XCTUnwrap(ThreadForkEnvironment(rawValue:try XCTUnwrap(row["environment"] as? String)))
        let request=ThreadForkRequest(hostId:client.id,threadId:try XCTUnwrap(row["threadId"] as? String),
          turnId:try XCTUnwrap(row["turnId"] as? String),clientOperationId:try XCTUnwrap(row["operationId"] as? String),environment:environment)
        if kind=="no-project-worktree" {
          let options=try await provider.forkOptions(client,threadId:request.threadId)
          guard !options.worktreeAvailable,!(options.worktreeReason ?? "").isEmpty else {
            throw NSError(domain:"EasyWorkHostedForkExaminer",code:9)
          }
        }
        let requestJSON=try JSONSerialization.jsonObject(with:encoder.encode(request))
        results.append(["kind":kind,"request":requestJSON,"dispatchAttempted":true]);try save()
        let outcome=try await provider.forkThread(client,request:request)
        guard outcome.request==request,outcome.phase == .rejected,outcome.forkedThreadId==nil,outcome.projectId==nil,
          outcome.error==row["expectedError"] as? String else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:10) }
        results[results.count-1]["phase"]=outcome.phase.rawValue
        results[results.count-1]["error"]=outcome.error
        originals.append(request);try save()
      }
      let first=try XCTUnwrap(originals.first)
      let status=try await provider.forkStatus(client,threadId:first.threadId,operationId:first.clientOperationId)
      guard status.request==first,status.phase == .rejected else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:11) }
      var wrongSourceFailed=false
      do {
        _ = try await provider.forkStatus(client,threadId:try XCTUnwrap(config["missingThreadId"] as? String),operationId:first.clientOperationId)
      } catch { wrongSourceFailed=true;report["wrongSourceFailureType"]=String(describing:type(of:error)) }
      guard wrongSourceFailed else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:12) }
      var wrongHostFailed=false
      do {
        _ = try await provider.forkThread(client,request:.init(hostId:"unrelated-"+client.id,threadId:first.threadId,turnId:first.turnId,
          clientOperationId:try XCTUnwrap(config["localHostMismatchOperationId"] as? String),environment:first.environment))
      } catch { wrongHostFailed=true;report["wrongLocalHostFailureType"]=String(describing:type(of:error)) }
      guard wrongHostFailed else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:13) }
      guard try CredentialVault.existingToken("easywork-device-identity")==identity,
        try encoder.encode(store.drafts.values)==drafts,store.saved.selectedClientId==selected,store.selectedThreadId==thread,
        store.saved.replyRecovery?.mapValues(\.fingerprint)==originalIntents else { throw NSError(domain:"EasyWorkHostedForkExaminer",code:14) }
      report["identityDraftSelectionAndOriginalIntentFingerprintsExact"]=true
      report["originalRejectedStatusExact"]=true;report["wrongSourceRejected"]=true;report["wrongLocalHostRejected"]=true
      report["status"]="passed-scoped-real-app-hosted-easywork-fork-source-negatives"
    } catch {
      report["status"]="failed-original-native-fork-prefix-retained-do-not-replay"
      report["failureType"]=String(describing:type(of:error));report["failureDescription"]=error.localizedDescription
      throw error
    }
  }

  @MainActor func testArchiveOneActuallyUnconfirmedEasyWorkFileReply() async throws {
    #if !targetEnvironment(simulator)
    throw XCTSkip("This controlled relay-loss case is restricted to its original owned simulator")
    #endif
    let env=ProcessInfo.processInfo.environment,run=try XCTUnwrap(ProcessInfo.processInfo.environment["EASYWORK_HOSTED_NATIVE_CASE"])
    let config=try XCTUnwrap(try JSONSerialization.jsonObject(with:Data(try XCTUnwrap(env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"]).utf8)) as? [String:Any])
    guard UUID(uuidString:run)?.uuidString.lowercased()==run,config["caseId"] as? String==run,
      config["newModelInputsAuthorized"] as? Int==1,config["newThreadCreationsAuthorized"] as? Int==1,
      config["newAttachmentUploadsAuthorized"] as? Int==1,config["actualRelayLossRequired"] as? Bool==true,
      Bundle.main.bundleIdentifier=="com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:1)
    }
    let documents=try XCTUnwrap(FileManager.default.urls(for:.documentDirectory,in:.userDomainMask).first)
    let folder=documents.appendingPathComponent("easywork-hosted-native",isDirectory:true)
    try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
    let reportURL=folder.appendingPathComponent(run+".json"),permitURL=folder.appendingPathComponent(run+".permit.json")
    guard !FileManager.default.fileExists(atPath:reportURL.path),!FileManager.default.fileExists(atPath:permitURL.path) else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:2) }
    var report:[String:Any]=["caseId":run,"bodyEntered":true,"appPID":ProcessInfo.processInfo.processIdentifier,"uiAutomationUsed":false,
      "codexTests":false,"wholeFeaturePassed":false,"newModelInputBudget":1]
    func save() throws { try JSONSerialization.data(withJSONObject:report,options:[.sortedKeys]).write(to:reportURL,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication]) }
    try save();defer { try? save() }
    do {
      let until=Date().addingTimeInterval(30)
      while SessionStore.hostedEasyWorkAcceptanceStore==nil && Date()<until { try await Task.sleep(for:.milliseconds(100)) }
      let store=try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
      guard !store.localRecoveryRequired,store.saved.outbox?.isEmpty != false else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:3) }
      let target=try JSONDecoder().decode(AgentRuntimeTarget.self,from:JSONSerialization.data(withJSONObject:try XCTUnwrap(config["target"])))
      guard target.agentId=="easywork" else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:4) }
      let matches=store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target==target }
      let client=try XCTUnwrap(matches.count==1 ? matches.first : nil)
      guard store.saved.selectedClientId==client.id,client.agentConnection?.preference == .relayOnly else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:5) }
      let credential=try JSONDecoder().decode(EasyWorkCredential.self,from:Data(try XCTUnwrap(CredentialVault.existingToken(client.agentConnection!.credentialRef)).utf8))
      guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:6) }
      let identity=try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
      let model=try XCTUnwrap(config["model"] as? String),prompt=try XCTUnwrap(config["prompt"] as? String)
      guard prompt.contains(run),store.saved.aiSharingGrants?.contains(where: { $0.scope.target==target && $0.scope.model==model })==true else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:7) }
      let originalThread=try XCTUnwrap(store.saved.workspaces[client.id]?.threads.first(where: { $0.id==store.selectedThreadId }))
      let encoder=JSONEncoder();encoder.outputFormatting = [.sortedKeys]
      let oldDrafts=store.drafts.values,oldRecovery=store.saved.replyRecovery ?? [:]
      let provider=try AgentClients.resolve(client),models=try await provider.models(client)
      guard models.models.contains(where: { $0.id==model }) else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:8) }
      let created=try await provider.createThread(client,projectId:nil,operationId:try XCTUnwrap(config["createOperationId"] as? String))
      report["newOwnedThreadId"]=created.id;try save()
      store.openThread(created,loadAutomatically:false)
      await store.refresh(includeHistory:false);await store.loadHistory(threadId:created.id)
      guard store.selectedThreadId==created.id,store.canCompose,!store.isRunning else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:9) }
      let ownKey=store.targetKey
      let fileName=try XCTUnwrap(config["attachmentName"] as? String),fileText=try XCTUnwrap(config["attachmentText"] as? String)
      guard fileName.contains(run),fileText.contains(run),EncryptedMigration.safeFilename(fileName) else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:10) }
      try store.importData(Data(fileText.utf8),name:fileName,isImage:false,target:ownKey);store.setText(prompt,target:ownKey)
      guard store.draft.attachments.count==1,store.canSend else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:11) }
      let content=Draft(text:prompt,attachments:store.draft.attachments,model:model)
      let intent=try encoder.encode(content),intentSHA=HostService.hash(intent)
      report["preparedContent"]=try JSONSerialization.jsonObject(with:intent);report["preparedContentSha256"]=intentSHA
      report["readyForSingleSubmission"]=true;try save()
      let permitDeadline=Date().addingTimeInterval(60)
      while !FileManager.default.fileExists(atPath:permitURL.path) && Date()<permitDeadline { try await Task.sleep(for:.milliseconds(100)) }
      let permit=try XCTUnwrap(try JSONSerialization.jsonObject(with:Data(contentsOf:permitURL)) as? [String:Any])
      guard permit["caseId"] as? String==run,permit["preparedContentSha256"] as? String==intentSHA,permit["singleSubmissionAllowed"] as? Bool==true else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:12) }
      report["singleSendMethodCalled"]=true;try save()
      store.send(content:content)
      let sendDeadline=Date().addingTimeInterval(120)
      while Date()<sendDeadline {
        if let pending=store.saved.outbox?[ownKey],pending.text==prompt,!store.sendingKeys.contains(ownKey),store.error != nil { break }
        try await Task.sleep(for:.milliseconds(100))
      }
      let pending=try XCTUnwrap(store.saved.outbox?[ownKey])
      let recovery=try XCTUnwrap(store.saved.replyRecovery?[pending.clientMessageId])
      guard pending.text==prompt,pending.threadId==created.id,pending.knownRejected != true,
        pending.sourceAttachments==content.attachments,recovery.phase=="uncertain",!store.sendingKeys.contains(ownKey),store.error != nil else {
        throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:13)
      }
      report["actualOriginalPending"]=try JSONSerialization.jsonObject(with:encoder.encode(pending))
      report["actualOriginalRecovery"]=try JSONSerialization.jsonObject(with:encoder.encode(recovery))
      report["actualTransportFailure"]=store.error;try save()
      let payload=try store.migrationPayload()
      guard try encoder.encode(payload.state.outbox?[ownKey])==encoder.encode(pending),payload.state.replyRecovery?[pending.clientMessageId]?.fingerprint==recovery.fingerprint else {
        throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:14)
      }
      let cipher=try EncryptedMigration.seal(payload,password:try XCTUnwrap(config["archivePassword"] as? String))
      let archiveURL=documents.appendingPathComponent("original-unknown-"+run+".codexmigration")
      try cipher.write(to:archiveURL,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication])
      for (key,draft) in oldDrafts { guard try encoder.encode(store.drafts.values[key])==encoder.encode(draft) else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:15) } }
      for (key,value) in oldRecovery { guard try encoder.encode(store.saved.replyRecovery?[key])==encoder.encode(value) else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:16) } }
      guard try CredentialVault.existingToken("easywork-device-identity")==identity else { throw NSError(domain:"EasyWorkHostedUnknownExaminer",code:17) }
      store.openThread(originalThread,loadAutomatically:false)
      report["archiveFilename"]=archiveURL.lastPathComponent;report["archiveSha256"]=HostService.hash(cipher)
      report["allOldDraftsIntentsAndIdentityExact"]=true
      report["status"]="passed-scoped-app-native-original-unconfirmed-reply-archive-awaiting-network-proof"
    } catch {
      report["status"]="failed-original-unknown-case-prefix-retained-do-not-replay"
      report["failureType"]=String(describing:type(of:error));report["failureDescription"]=error.localizedDescription;throw error
    }
  }

  @MainActor func testTransientRelayFailureRecoversOriginalRuntimeWithoutCommands() async throws {
    let env=ProcessInfo.processInfo.environment,run=try XCTUnwrap(ProcessInfo.processInfo.environment["EASYWORK_HOSTED_NATIVE_CASE"])
    let config=try XCTUnwrap(try JSONSerialization.jsonObject(with:Data(try XCTUnwrap(env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"]).utf8)) as? [String:Any])
    guard UUID(uuidString:run)?.uuidString.lowercased()==run,config["caseId"] as? String==run,
      config["newModelInputsAuthorized"] as? Int==0,config["newRemoteCommandsAuthorized"] as? Int==0,
      config["actualRelayLossRequired"] as? Bool==true,Bundle.main.bundleIdentifier=="com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),ProcessInfo.processInfo.arguments.contains("-restore-testing") else {
      throw NSError(domain:"EasyWorkHostedTransientExaminer",code:1)
    }
    let folder=try XCTUnwrap(FileManager.default.urls(for:.documentDirectory,in:.userDomainMask).first).appendingPathComponent("easywork-hosted-native",isDirectory:true)
    try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
    let path=folder.appendingPathComponent(run+".json"),permit=folder.appendingPathComponent(run+".permit.json")
    guard !FileManager.default.fileExists(atPath:path.path),!FileManager.default.fileExists(atPath:permit.path) else { throw NSError(domain:"EasyWorkHostedTransientExaminer",code:2) }
    var report:[String:Any]=["caseId":run,"bodyEntered":true,"appPID":ProcessInfo.processInfo.processIdentifier,
      "uiAutomationUsed":false,"newModelInputs":0,"newRemoteCommands":0,"codexTests":false,"wholeFeaturePassed":false]
    func save() throws { try JSONSerialization.data(withJSONObject:report,options:[.sortedKeys]).write(to:path,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication]) }
    try save();defer { try? save() }
    do {
      let deadline=Date().addingTimeInterval(30)
      while SessionStore.hostedEasyWorkAcceptanceStore==nil && Date()<deadline { try await Task.sleep(for:.milliseconds(100)) }
      let store=try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
      let target=try JSONDecoder().decode(AgentRuntimeTarget.self,from:JSONSerialization.data(withJSONObject:try XCTUnwrap(config["target"])))
      guard !store.localRecoveryRequired,target.agentId=="easywork" else { throw NSError(domain:"EasyWorkHostedTransientExaminer",code:3) }
      let matches=store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target==target }
      let client=try XCTUnwrap(matches.count==1 ? matches.first : nil)
      guard client.agentConnection?.preference == .relayOnly else { throw NSError(domain:"EasyWorkHostedTransientExaminer",code:4) }
      let credential=try JSONDecoder().decode(EasyWorkCredential.self,from:Data(try XCTUnwrap(CredentialVault.existingToken(client.agentConnection!.credentialRef)).utf8))
      guard try XCTUnwrap(config["allowedBindingIds"] as? [String]).contains(credential.binding.bindingId) else { throw NSError(domain:"EasyWorkHostedTransientExaminer",code:5) }
      let encoder=JSONEncoder();encoder.outputFormatting = [.sortedKeys]
      let identity=try XCTUnwrap(CredentialVault.existingToken("easywork-device-identity"))
      let drafts=try encoder.encode(store.drafts.values),outbox=try encoder.encode(store.saved.outbox),recovery=try encoder.encode(store.saved.replyRecovery)
      let selected=store.saved.selectedClientId,thread=store.selectedThreadId,provider=try AgentClients.resolve(client)
      var failure:Error?
      do { _ = try await provider.workspace(client,projectCursor:nil,threadCursor:nil) } catch { failure=error }
      let unavailable=try XCTUnwrap(failure as? EasyWorkServiceAvailabilityFailure,"The actual temporary gateway response must not be classified as device authorization")
      guard unavailable.status==502 else { throw NSError(domain:"EasyWorkHostedTransientExaminer",code:6) }
      report["actualTemporaryServiceStatus"]=unavailable.status;report["temporaryFailureIsAuthorizationError"]=false
      report["waitingForRelayRestore"]=true;report["existingUIStoreRegistered"]=true;try save()
      let restoreDeadline=Date().addingTimeInterval(90)
      while !FileManager.default.fileExists(atPath:permit.path) && Date()<restoreDeadline { try await Task.sleep(for:.milliseconds(100)) }
      let value=try XCTUnwrap(try JSONSerialization.jsonObject(with:Data(contentsOf:permit)) as? [String:Any])
      guard value["caseId"] as? String==run,value["ownedRelayRestored"] as? Bool==true else { throw NSError(domain:"EasyWorkHostedTransientExaminer",code:7) }
      await provider.close(client)
      let workspace=try await provider.workspace(client,projectCursor:nil,threadCursor:nil)
      guard workspace.hostId==client.id,workspace.agentConnectionState?.target==target,workspace.agentConnectionState?.runtimeConnected==true,
        try CredentialVault.existingToken("easywork-device-identity")==identity,
        try encoder.encode(store.drafts.values)==drafts,try encoder.encode(store.saved.outbox)==outbox,
        try encoder.encode(store.saved.replyRecovery)==recovery,store.saved.selectedClientId==selected,store.selectedThreadId==thread else {
        throw NSError(domain:"EasyWorkHostedTransientExaminer",code:8)
      }
      report["sameOriginalIdentityAndBindingRuntimeRecovered"]=true
      report["allOriginalDraftsOutboxIntentsAndSelectionExact"]=true
      report["status"]="passed-scoped-real-relay-transient-error-and-original-runtime-recovery"
    } catch {
      report["status"]="failed-original-transient-read-prefix-retained"
      report["failureType"]=String(describing:type(of:error));report["failureDescription"]=error.localizedDescription;throw error
    }
  }

  @MainActor func testOwnedTemporaryLANDeviceRevocationPreservesOriginalBindingWithoutModel() async throws {
    #if !targetEnvironment(simulator)
    throw XCTSkip("Independent Simulator acceptance required")
    #else
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw HostServiceError.invalidResponse }
    let c = try easyWorkObject(Data(raw.utf8))
    guard c["caseId"] as? String == run,
      c["newModelInputsAuthorized"] as? Int == 0, c["newRemoteCommandsAuthorized"] as? Int == 0,
      c["temporaryLANDevicesAuthorized"] as? Int == 1,
      let deviceId = c["temporaryDeviceId"] as? String, deviceId == "mobile-acceptance-" + run,
      let alias = c["temporaryDeviceName"] as? String, alias == "iOS revoke " + String(run.prefix(8)),
      let allowed = c["allowedBindingIds"] as? [String] else { throw HostServiceError.invalidResponse }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: easyWorkJSON(try XCTUnwrap(c["target"])))
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < deadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard target.agentId == "easywork", !store.localRecoveryRequired else { throw HostServiceError.wrongHost }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let original = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let originalConnection = try XCTUnwrap(original.agentConnection)
    let token = try XCTUnwrap(CredentialVault.existingToken(originalConnection.credentialRef))
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(token.utf8))
    guard allowed.contains(credential.binding.bindingId), credential.binding.mobileDeviceId != deviceId,
      credential.binding.status == "active" else { throw HostServiceError.wrongHost }
    let candidate = try XCTUnwrap(originalConnection.candidates.first { $0.transportId == "lan-wss" })
    let host = try await EasyWorkPairing.readHost(endpoint: candidate.endpoint,
      fingerprint: candidate.peerFingerprint, target: credential.binding.target,
      publicKey: credential.binding.desktopPublicKey)
    guard host.connectionMode == "automatic" else { throw HostServiceError.invalidResponse }
    let protected = try originalReadProtectedDigests(store)
    let originalProvider = try AgentClients.resolve(original)
    let originalBefore = try await originalProvider.workspace(original, projectCursor: nil, threadCursor: nil)
    guard originalBefore.agentConnectionState?.target == target,
      originalBefore.agentConnectionState?.runtimeConnected == true else { throw HostServiceError.unavailable }
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let reportPath = folder.appendingPathComponent(run + ".json")
    let permitPath = folder.appendingPathComponent(run + ".revoke-permit.json")
    guard !FileManager.default.fileExists(atPath: reportPath.path),
      !FileManager.default.fileExists(atPath: permitPath.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "newModelInputs": 0, "newRemoteCommands": 0,
      "newTemporaryLANDevices": 0, "wholeFeaturePassed": false, "appPID": ProcessInfo.processInfo.processIdentifier,
      "appBundleIdentifier": Bundle.main.bundleIdentifier ?? "", "originalBindingId": credential.binding.bindingId,
      "temporaryDeviceId": deviceId, "temporaryDeviceName": alias]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: reportPath, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 15; configuration.timeoutIntervalForResource = 30
    let base = try EasyWorkPairing.localEndpoint(candidate.endpoint)
    let http = URLSession(configuration: configuration,
      delegate: PairedHostTrust(endpoint: candidate.endpoint, fingerprint: host.fingerprint), delegateQueue: nil)
    defer { http.invalidateAndCancel() }
    // Separate in-memory content key: never replace the real phone's identity or Keychain credential.
    let key = Curve25519.KeyAgreement.PrivateKey()
    let body = try easyWorkJSON(["mobileDeviceId": deviceId,
      "publicKey": key.publicKey.rawRepresentation.base64URL, "name": alias])
    var active: EasyWorkRemoteSession?
    do {
      report["status"] = "creating-one-owned-temporary-lan-device"; try save()
      let request = HostService.request(base.appendingPathComponent("connections"), method: "POST", body: body)
      let (bytes, response) = try await http.data(for: request)
      guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw HostServiceError.invalidResponse }
      let binding = try JSONDecoder().decode(EasyWorkBinding.self,
        from: easyWorkJSON(try easyWorkObject(bytes)["binding"] ?? [:]))
      report["newTemporaryLANDevices"] = 1; report["temporaryBindingId"] = binding.bindingId
      report["temporaryMobilePublicKeySHA256"] = HostService.hash(key.publicKey.rawRepresentation)
      report["status"] = "owned-binding-created-not-yet-read"; try save()
      guard !allowed.contains(binding.bindingId), binding.mobileDeviceId == deviceId,
        binding.mobilePublicKey == key.publicKey.rawRepresentation.base64URL,
        binding.desktopPublicKey == credential.binding.desktopPublicKey,
        binding.target == credential.binding.target, binding.status == "active",
        binding.deviceAuthorization == nil else { throw HostServiceError.wrongHost }
      let temporaryConnection = AgentConnection(target: target, candidates: [candidate],
        preference: .lanOnly, credentialRef: "acceptance-memory-" + run)
      let temporaryCredential = EasyWorkCredential(binding: binding, privateKey: key.rawRepresentation.base64URL)
      let channel = try EasyWorkRemoteSession(connection: temporaryConnection, credential: temporaryCredential)
      active = channel
      let context = try easyWorkObject(await channel.request("runtime/context", [:]))
      let actual = try JSONDecoder().decode(EasyWorkTarget.self,
        from: easyWorkJSON(try XCTUnwrap(context["target"])))
      guard actual == binding.target else { throw HostServiceError.wrongHost }
      report["actualPinnedEncryptedRuntimeReadBeforeRevoke"] = true
      report["status"] = "waiting-for-exact-owned-device-gui-revoke"; try save()
      let revokeDeadline = Date().addingTimeInterval(360)
      while !FileManager.default.fileExists(atPath: permitPath.path) && Date() < revokeDeadline {
        try await Task.sleep(for: .milliseconds(100))
      }
      let permit = try easyWorkObject(Data(contentsOf: permitPath))
      guard permit["caseId"] as? String == run, permit["bindingId"] as? String == binding.bindingId,
        permit["deviceId"] as? String == deviceId, permit["exactOwnedDeviceGUIRevoked"] as? Bool == true else {
        throw HostServiceError.invalidResponse
      }
      var oldReadRejected = false
      do { _ = try await channel.request("runtime/context", [:]) }
      catch { guard !(error is CancellationError) else { throw error }; oldReadRejected = true }
      await channel.close(); active = nil
      guard oldReadRejected else { throw HostServiceError.invalidResponse }
      let fresh = try EasyWorkRemoteSession(connection: temporaryConnection, credential: temporaryCredential)
      active = fresh
      var freshReadRejected = false
      do { _ = try await fresh.request("runtime/context", [:]) }
      catch { guard !(error is CancellationError) else { throw error }; freshReadRejected = true }
      await fresh.close(); active = nil
      guard freshReadRejected else { throw HostServiceError.invalidResponse }
      let (reconnectBytes, reconnectResponse) = try await http.data(for: request)
      let reconnectStatus = try XCTUnwrap(reconnectResponse as? HTTPURLResponse).statusCode
      report["automaticSameDeviceReconnectHTTPStatus"] = reconnectStatus
      report["automaticSameDeviceReconnectBodySHA256"] = HostService.hash(reconnectBytes)
      guard reconnectStatus == 400 else { throw HostServiceError.invalidResponse }
      let originalAfter = try await originalProvider.workspace(original, projectCursor: nil, threadCursor: nil)
      guard originalAfter.agentConnectionState?.target == target,
        originalAfter.agentConnectionState?.runtimeConnected == true,
        try CredentialVault.existingToken(originalConnection.credentialRef) == token,
        try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
      report["oldAndFreshRevokedEncryptedReadsRejected"] = true
      report["automaticSameDeviceReconnectCannotRestoreRevokedAuthority"] = true
      report["originalBindingRuntimeAndCredentialAndProtectedPhoneStateUnchanged"] = true
      report["ephemeralPrivateKeyOnlyInTestProcessMemory"] = true
      report["status"] = "passed-owned-lan-device-revocation-and-original-binding-protection"
      try save()
    } catch {
      await active?.close()
      report["status"] = "failed-owned-device-revoke-case-retain-binding-id-for-cleanup"
      report["failure"] = error.localizedDescription
      try? save(); throw error
    }
    #endif
  }

  @MainActor func testSimulatorForeignDeviceStatusIsolationWithoutReplay() async throws {
    #if !targetEnvironment(simulator)
    throw XCTSkip("Explicit independent Simulator case required")
    #else
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else {
      throw XCTSkip("Frozen Simulator device-isolation case required")
    }
    let c = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
    guard c["caseId"] as? String == run, c["newModelInputsAuthorized"] as? Int == 0,
      c["newBusinessCommandsAuthorized"] as? Int == 0, c["statusReadsAuthorized"] as? Int == 6,
      let foreignDevice = c["foreignDeviceId"] as? String,
      let foreignCommand = c["foreignCommandOperationId"] as? String, UUID(uuidString: foreignCommand) != nil,
      let foreignVoice = c["foreignVoiceOperationId"] as? String, UUID(uuidString: foreignVoice) != nil,
      let ownCommand = c["ownRejectedGuidanceOperationId"] as? String, UUID(uuidString: ownCommand) != nil,
      let bindings = c["allowedBindingIds"] as? [String] else { throw HostServiceError.invalidResponse }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < deadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw HostServiceError.invalidResponse }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId), credential.binding.mobileDeviceId != foreignDevice else {
      throw HostServiceError.wrongHost
    }
    let protected = try originalReadProtectedDigests(store)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "newModels": 0, "newBusinessCommands": 0,
      "newRecordings": 0, "audioUploads": 0, "voicePrepareCalls": 0, "wholeFeaturePassed": false,
      "appPID": ProcessInfo.processInfo.processIdentifier, "appBundleIdentifier": Bundle.main.bundleIdentifier ?? ""]
    func save() throws {
      try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        .write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    do {
      report["status"] = "reading-own-and-foreign-device-status"; try save()
      let provider = try AgentClients.resolve(client)
      let own = try await provider.receipt(client, id: ownCommand)
      guard own.clientMessageId == ownCommand, own.hostId == client.id, own.state == "failed", own.turnId == nil else {
        throw HostServiceError.invalidResponse
      }
      report["ownRejectedReceiptStillReadable"] = true
      let channel = try await EasyWorkSessions.shared.session(client)
      guard try await channel.request("command/status", ["commandId": foreignCommand]).elementsEqual(Data("null".utf8)) else {
        throw HostServiceError.invalidResponse
      }
      do {
        _ = try await provider.receipt(client, id: foreignCommand)
        throw NSError(domain: "EasyWorkSimulatorDeviceIsolation", code: 1)
      } catch let error as HostServiceError {
        guard case let .message(text) = error,
          text == L10n.tr("Host 尚未找到此操作回执，请先核对桌面历史。原消息和操作 ID 已保留。") else { throw error }
      }
      let voice = try easyWorkObject(await channel.request("voice/status", ["operationId": foreignVoice]))
      guard voice["receipt"] is NSNull else { throw HostServiceError.invalidResponse }
      let epoch = await channel.epoch
      await provider.close(client)
      let fresh = try await EasyWorkSessions.shared.session(client)
      guard await fresh.epoch != epoch,
        try await fresh.request("command/status", ["commandId": foreignCommand]).elementsEqual(Data("null".utf8)) else {
        throw HostServiceError.invalidResponse
      }
      let freshVoice = try easyWorkObject(await fresh.request("voice/status", ["operationId": foreignVoice]))
      guard freshVoice["receipt"] is NSNull, try originalReadProtectedDigests(store) == protected else {
        throw HostServiceError.invalidResponse
      }
      report["foreignCommandAndVoiceAbsentBeforeAndAfterReconnect"] = true
      report["missingStatusNotInterpretedAsNeverSubmitted"] = true
      report["originalProtectedDigestsUnchanged"] = true
      report["status"] = "passed-simulator-own-receipt-and-foreign-device-status-isolation-no-replay"
      try save()
    } catch {
      report["status"] = "failed-status-only-no-replay"; report["failure"] = error.localizedDescription
      try? save(); throw error
    }
    #endif
  }

  @MainActor func testOriginalASRStatusAndReconnectWithoutAudioUpload() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled,
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen original ASR status case required") }
    let c = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any])
    guard c["caseId"] as? String == run, c["voiceStatusOnly"] as? Bool == true,
      c["newModelInputsAuthorized"] as? Int == 0, c["newBusinessCommandsAuthorized"] as? Int == 0,
      c["newAudioUploadsAuthorized"] as? Int == 0,
      let expectedText = c["originalTranscriptSHA256"] as? String, expectedText.count == 64,
      let missing = c["absentStatusOperationId"] as? String, UUID(uuidString: missing) != nil,
      let bindings = c["allowedBindingIds"] as? [String], !bindings.isEmpty else {
      throw NSError(domain: "EasyWorkOriginalASRStatus", code: 1)
    }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["target"])))
    let manifest = try JSONDecoder().decode(VoiceTranscriptionRequest.self,
      from: JSONSerialization.data(withJSONObject: try XCTUnwrap(c["originalRecordingManifest"])))
    guard target.agentId == "easywork", missing != manifest.operationId else { throw HostServiceError.wrongHost }
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil && Date() < deadline {
      try await Task.sleep(for: .milliseconds(100))
    }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    guard !store.localRecoveryRequired, manifest.hostId == client.id else { throw HostServiceError.wrongHost }
    let credential = try JSONDecoder().decode(EasyWorkCredential.self,
      from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let protected = try originalReadProtectedDigests(store)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("easywork-hosted-native", isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let file = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: file.path) else { throw NSError(domain: "EasyWorkOriginalASRStatus", code: 2) }
    var report: [String: Any] = ["caseId": run, "newModels": 0, "newBusinessCommands": 0,
      "newRecordings": 0, "audioUploads": 0, "voicePrepareCalls": 0, "wholeFeaturePassed": false,
      "originalOperationId": manifest.operationId, "expiryMeaningNotAssumed": true]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
      .write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func verify(_ data: Data) throws {
      let value = try easyWorkObject(data)
      let receipt = try JSONDecoder().decode(VoiceTranscriptionReceipt.self,
        from: JSONSerialization.data(withJSONObject: try XCTUnwrap(value["receipt"])))
      guard try receipt.matches(manifest), receipt.phase == "completed", receipt.error == nil,
        let text = receipt.text, HostService.hash(Data(text.utf8)) == expectedText,
        receipt.expiresAt == c["originalReceiptExpiresAt"] as? String else {
        throw NSError(domain: "EasyWorkOriginalASRStatus", code: 3)
      }
    }
    do {
      let provider = try AgentClients.resolve(client)
      let channel = try await EasyWorkSessions.shared.session(client)
      try verify(await channel.request("voice/status", ["operationId": manifest.operationId]))
      report["originalAuthenticatedStatusManifestAndTextDigestMatched"] = true
      let epoch = await channel.epoch
      await provider.close(client)
      let fresh = try await EasyWorkSessions.shared.session(client)
      try verify(await fresh.request("voice/status", ["operationId": manifest.operationId]))
      guard await fresh.epoch != epoch else { throw NSError(domain: "EasyWorkOriginalASRStatus", code: 4) }
      report["freshEncryptedSessionReturnedSameOriginalReceipt"] = true
      let absent = try easyWorkObject(await fresh.request("voice/status", ["operationId": missing]))
      guard absent["receipt"] is NSNull else { throw NSError(domain: "EasyWorkOriginalASRStatus", code: 5) }
      report["absentOriginalStatusDidNotPrepareOrUpload"] = true
      // Public synchronization metadata only; no App Group contents or keys.
      let notices = TaskNotificationController.shared
      report["notificationPreferencesGeneration"] = notices.preferences.generation
      report["notificationPreferencesEnabled"] = notices.preferences.enabled
      if let id = TaskNotificationShared.read(String.self, name: "device-id.json") {
        report["notificationDeviceIdSHA256"] = HostService.hash(Data(id.utf8))
      }
      guard try originalReadProtectedDigests(store) == protected else {
        throw NSError(domain: "EasyWorkOriginalASRStatus", code: 6)
      }
      report["originalProtectedDigestsUnchanged"] = true
      report["status"] = "passed-original-asr-status-and-reconnect-without-audio-upload"
      try save()
    } catch {
      report["status"] = "failed-status-only-no-audio-or-input-replayed"
      report["failure"] = error.localizedDescription; try? save(); throw error
    }
  }

  @MainActor func testOriginalNativeQueueReadinessExecutionOrKnownStatus() async throws {
    let env = ProcessInfo.processInfo.environment
    guard SessionStore.hostedEasyWorkAcceptanceEnabled, let run = env["EASYWORK_HOSTED_NATIVE_CASE"],
      UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"] else { throw XCTSkip("Frozen original queue case required") }
    let c = try easyWorkObject(Data(raw.utf8))
    let action = try XCTUnwrap(c["action"] as? String)
    guard c["caseId"] as? String == run, ["inspect", "execute", "cold-status", "cold-history", "fresh-cold-history", "cleanup", "archive-owned-attempts", "stop-owned-paused", "cancel-owned-queued", "pre-acceptance-owner-loss"].contains(action),
      let allowedBindings = c["allowedBindingIds"] as? [String] else { throw HostServiceError.invalidResponse }
    func field(_ name: String) throws -> String { let value = try XCTUnwrap(c[name] as? String); guard !value.isEmpty else { throw HostServiceError.invalidResponse }; return value }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    guard target.agentId == "easywork" else { throw HostServiceError.wrongHost }
    let ready = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < ready { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    guard !store.localRecoveryRequired else { throw HostServiceError.invalidResponse }
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    guard store.client?.id == client.id else { throw HostServiceError.wrongHost }
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard allowedBindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let provider = try AgentClients.resolve(client), channel = try await EasyWorkSessions.shared.session(client)
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let protected = try originalReadProtectedDigests(store)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "action": action, "appPID": ProcessInfo.processInfo.processIdentifier,
      "wholeFeaturePassed": false, "pureUITests": false, "newModelsBeforeExecution": 0]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]).write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    func wait(_ label: String, seconds: Double = 60, _ test: () -> Bool) async throws {
      let end = Date().addingTimeInterval(seconds)
      while !test(), Date() < end { try await Task.sleep(for: .milliseconds(100)) }
      guard test() else { report["failedWait"] = label; try save(); throw HostServiceError.invalidResponse }
    }
    var ownedThread: String?, ownedRoot: String?, queueId: String?
    let oldThread = store.selectedThreadId, oldProject = store.selectedProjectId, oldMode = store.replyMode
    let oldThreadObject = store.threads.first { $0.id == oldThread }
    let oldLast = store.saved.lastThreads[client.id], oldLastProject = store.saved.lastThreadProjects?[client.id]
    let oldLastProjectsNil = store.saved.lastThreadProjects == nil
    let oldDrafts = store.drafts.values, oldPreferences = try encoder.encode(store.saved.generationPreferences)
    let oldGrants = try encoder.encode(store.saved.aiSharingGrants)
    var ownedKey: String?
    defer {
      if let thread = ownedThread, store.selectedThreadId == thread {
        if let oldThreadObject { store.openThread(oldThreadObject, loadAutomatically: false) }
        store.selectedThreadId = oldThread; store.selectedProjectId = oldProject
        if let oldLast { store.saved.lastThreads[client.id] = oldLast } else { store.saved.lastThreads.removeValue(forKey: client.id) }
        var projects = store.saved.lastThreadProjects ?? [:]
        if let oldLastProject { projects[client.id] = oldLastProject } else { projects.removeValue(forKey: client.id) }
        store.saved.lastThreadProjects = oldLastProjectsNil && projects.isEmpty ? nil : projects
      }
      if let key = ownedKey, oldDrafts[key] == nil, let value = store.drafts.values[key], value.text.isEmpty, value.attachments.isEmpty { store.drafts.values.removeValue(forKey: key) }
      if store.replyMode == "queue" { store.replyMode = oldMode }
      store.persist(reconcileUnread: false)
    }
    do {
      try save()
      let workspace = try await provider.workspace(client, projectCursor: nil, threadCursor: nil)
      guard workspace.capabilities?.contains("queue") == true, workspace.capabilities?.contains("queue:execute") == true else { throw HostServiceError.unavailable }
      report["actualOriginalOwnerQueueAndExecuteCapabilities"] = true
      if action == "inspect" {
        guard c["newRootInputsAuthorized"] as? Int == 0, c["newThreadsAuthorized"] as? Int == 0 else { throw HostServiceError.invalidResponse }
        let result = try easyWorkObject(await channel.request("queue/list", ["threadId": try field("probeThreadId")]))
        report["originalRendererQueueReadCount"] = try XCTUnwrap(result["receipts"] as? [[String: Any]]).count
        guard try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
        report["status"] = "passed-original-native-owner-queue-readiness-no-model-or-command"; try save(); return
      }
      if action == "archive-owned-attempts" {
        let items = try XCTUnwrap(c["ownedAttempts"] as? [[String: Any]])
        guard c["newRootInputsAuthorized"] as? Int == 0, c["newThreadsAuthorized"] as? Int == 0,
          !items.isEmpty, items.count <= 5, c["metadataWritesAuthorized"] as? Int == items.count else { throw HostServiceError.invalidResponse }
        var archived: [String] = []
        for item in items {
          let thread = try XCTUnwrap(item["threadId"] as? String)
          let create = try XCTUnwrap(item["createOperationId"] as? String)
          let archive = try XCTUnwrap(item["archiveOperationId"] as? String)
          let roots = try XCTUnwrap(item["allowedRunIds"] as? [String])
          guard [thread, create, archive].allSatisfy({ UUID(uuidString: $0) != nil }),
            roots.allSatisfy({ UUID(uuidString: $0) != nil }) else { throw HostServiceError.invalidResponse }
          let before = try easyWorkObject(await channel.request("command/status", ["commandId": create]))
          let original = try EasyWorkRemoteSession.verifiedWriteResult(before, target: await channel.target,
            mobileDeviceId: await channel.mobileDeviceId, operationId: create, method: "thread/create", params: ["clientRequestId": create])
          let originalObject = try easyWorkObject(original)
          let created = try XCTUnwrap(originalObject["thread"] as? [String: Any])
          guard created["id"] as? String == thread else { throw HostServiceError.wrongHost }
          let history = try await provider.history(client, threadId: thread, cursor: nil)
          guard history.thread?.id == thread,
            history.runtime == nil || ["idle", "failed", "cancelled", "interrupted"].contains(history.runtime?.state ?? ""),
            history.messages.allSatisfy({ $0.runId == nil || roots.contains($0.runId!) }) else { throw HostServiceError.invalidResponse }
          report["nextOwnedArchiveThread"] = thread; report["ownedThreadsAlreadyArchived"] = archived; try save()
          _ = try await channel.request("thread/archive", ["threadId": thread, "archived": true], id: archive)
          let after = try await provider.history(client, threadId: thread, cursor: nil)
          guard after.thread?.archived == true else { throw HostServiceError.invalidResponse }
          archived.append(thread)
        }
        guard try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
        report["ownedThreadsAlreadyArchived"] = archived
        report["status"] = "passed-own-terminal-or-empty-attempts-archived-without-input"; try save(); return
      }
      if action == "cancel-owned-queued" {
        let ids = try XCTUnwrap(c["ownedQueueIds"] as? [String])
        let allowedRoots = try XCTUnwrap(c["allowedRunIds"] as? [String])
        guard c["newRootInputsAuthorized"] as? Int == 0, c["newThreadsAuthorized"] as? Int == 0,
          !ids.isEmpty, ids.count <= 3, c["metadataWritesAuthorized"] as? Int == ids.count,
          ids.allSatisfy({ UUID(uuidString: $0) != nil }), allowedRoots.allSatisfy({ UUID(uuidString: $0) != nil }) else { throw HostServiceError.invalidResponse }
        let thread = try field("threadId"), create = try field("createOperationId")
        let original = try easyWorkObject(await channel.request("command/status", ["commandId": create]))
        let created = try EasyWorkRemoteSession.verifiedWriteResult(original, target: await channel.target,
          mobileDeviceId: await channel.mobileDeviceId, operationId: create, method: "thread/create", params: ["clientRequestId": create])
        let createdThread = try XCTUnwrap(try easyWorkObject(created)["thread"] as? [String: Any])
        guard createdThread["id"] as? String == thread else { throw HostServiceError.wrongHost }
        let history = try await provider.history(client, threadId: thread, cursor: nil)
        guard history.runtime?.state == "idle", history.messages.allSatisfy({ $0.runId == nil || allowedRoots.contains($0.runId!) }) else { throw HostServiceError.invalidResponse }
        var cancelled: [String] = []
        for id in ids {
          let before = try await provider.receipt(client, id: id)
          guard before.threadId == thread, before.state == "queued", before.queueSource == "native", before.queuePresent == true, before.turnId == nil else { throw HostServiceError.invalidResponse }
          report["nextOwnedQueueCancellation"] = id; try save()
          guard try await provider.cancelQueue(client, threadId: thread, messageId: id).ok else { throw HostServiceError.invalidResponse }
          let after = try await provider.receipt(client, id: id)
          guard after.threadId == thread, after.state == "cancelled", after.queueSource == "native", after.queuePresent != true, after.turnId == nil else { throw HostServiceError.invalidResponse }
          cancelled.append(id)
        }
        guard try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
        report["ownedQueueIdsCancelledWithoutDispatch"] = cancelled
        report["status"] = "passed-owned-never-dispatched-queued-input-cancelled"; try save(); return
      }
      if action == "stop-owned-paused" {
        guard c["newRootInputsAuthorized"] as? Int == 0, c["newThreadsAuthorized"] as? Int == 0,
          c["controlWritesAuthorized"] as? Int == 1,
          c["originalAcceptedOwnerLossIndependentlyVerified"] as? Bool == true else { throw HostServiceError.invalidResponse }
        let thread = try field("threadId"), root = try field("rootRunId"), id = try field("queueId")
        guard UUID(uuidString: thread) != nil, UUID(uuidString: root) != nil else { throw HostServiceError.invalidResponse }
        let receipt = try await provider.receipt(client, id: id)
        guard receipt.threadId == thread, receipt.turnId == root, receipt.state == "submitted",
          receipt.queueSource == "native" else { throw HostServiceError.invalidResponse }
        let before = try await provider.history(client, threadId: thread, cursor: nil)
        guard before.runtime?.turnId == root, before.runtime?.state == "paused",
          before.messages.allSatisfy({ $0.runId == nil || $0.runId == root }) else { throw HostServiceError.invalidResponse }
        report["threadId"] = thread; report["rootRunId"] = root; report["queueId"] = id
        report["ownedPausedRootStopAttemptedOnce"] = true; try save()
        guard try await provider.stop(client, threadId: thread, turnId: root).ok else { throw HostServiceError.invalidResponse }
        var after = try await provider.history(client, threadId: thread, cursor: nil)
        let end = Date().addingTimeInterval(60)
        while after.runtime?.state != "cancelled", Date() < end {
          try await Task.sleep(for: .milliseconds(200)); after = try await provider.history(client, threadId: thread, cursor: nil)
        }
        guard after.runtime?.state == "cancelled", after.runtime?.turnId == nil,
          after.runtime?.receipts.contains(where: { $0.turnId == root && $0.state == "cancelled" }) == true,
          try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
        report["sameOwnedPausedRootCancelledWithoutResumeOrInput"] = true
        report["status"] = "passed-owned-paused-root-stopped-without-resume"; try save(); return
      }
      if action == "pre-acceptance-owner-loss" {
        guard c["newRootInputsAuthorized"] as? Int == 1, c["newThreadsAuthorized"] as? Int == 1,
          c["maxChildRunsAuthorized"] as? Int == 0 else { throw HostServiceError.invalidResponse }
        let body = try field("body"), model = try field("model"), operation = try field("sendOperationId")
        guard HostService.hash(Data(body.utf8)) == c["bodySHA256"] as? String else { throw HostServiceError.invalidResponse }
        await store.loadModels()
        guard store.models.contains(where: { $0.id == model }),
          store.saved.aiSharingGrants?.contains(where: { $0.scope.target == target && $0.scope.model == model }) == true else { throw HostServiceError.unavailable }
        report["createAttempted"] = true; try save()
        let thread = try await provider.createThread(client, projectId: nil, operationId: try field("createOperationId"))
        ownedThread = thread.id; report["threadId"] = thread.id; report["queueId"] = operation
        report["status"] = "waiting-independent-pre-acceptance-arming"; try save()
        let armedPath = folder.appendingPathComponent(run + ".receipt-fault-armed.json")
        try await wait("parent arms exact before-acceptance original input", seconds: 45) { FileManager.default.fileExists(atPath: armedPath.path) }
        let armed = try easyWorkObject(Data(contentsOf: armedPath))
        guard armed["caseId"] as? String == run, armed["queueId"] as? String == operation,
          armed["threadId"] as? String == thread.id, armed["dispatchMethod"] as? String == "send" else { throw HostServiceError.invalidResponse }
        report["originalQueuedProviderSendAttemptedOnce"] = true; try save()
        var returned: DeliveryReceipt?
        do {
          returned = try await provider.send(client, reply: PendingReply(hostId: client.id, threadId: thread.id,
            clientMessageId: operation, text: body, attachmentIds: [], mode: "queue", expectedTurnId: nil,
            model: model, effort: nil, collaborationMode: "default", permissionMode: "confirm_high_risk", speed: nil))
          report["unexpectedQueuedProviderReturnState"] = returned?.state
        } catch { report["originalProviderCallErrorAfterOwnerLoss"] = error.localizedDescription }
        let lossPath = folder.appendingPathComponent(run + ".pre-acceptance-owner-loss.json")
        try await wait("parent confirms exact owner exited before runtime acceptance", seconds: 35) { FileManager.default.fileExists(atPath: lossPath.path) }
        let loss = try easyWorkObject(Data(contentsOf: lossPath))
        if let returned {
          guard returned.state == "queued", returned.threadId == thread.id, returned.turnId == nil,
            returned.queueSource == "native" else { throw HostServiceError.invalidResponse }
          report["originalQueueAcknowledgedBeforeOwnerLossWithoutModelAcceptance"] = true
        }
        guard loss["caseId"] as? String == run, loss["threadId"] as? String == thread.id,
          loss["queueId"] as? String == operation, loss["originalDispatchDurablyObserved"] as? Bool == true,
          loss["originalOwnerConfirmedExited"] as? Bool == true, loss["newOwnerNotStarted"] as? Bool == true,
          loss["noAcceptedRootOrModelRun"] as? Bool == true else { throw HostServiceError.invalidResponse }
        guard try originalReadProtectedDigests(store) == protected,
          try encoder.encode(store.drafts.values) == encoder.encode(oldDrafts),
          try encoder.encode(store.saved.generationPreferences) == oldPreferences,
          try encoder.encode(store.saved.aiSharingGrants) == oldGrants else { throw HostServiceError.invalidResponse }
        report["originalProviderInputNotReplayedAfterRealOwnerLoss"] = true
        report["independentParentObservedNoAcceptedRoot"] = true
        report["status"] = "passed-original-pre-acceptance-owner-loss-no-model-no-replay"; try save(); return
      }
      if action == "fresh-cold-history" {
        guard c["newRootInputsAuthorized"] as? Int == 1, c["newThreadsAuthorized"] as? Int == 1,
          c["maxChildRunsAuthorized"] as? Int == 0, c["metadataWritesAuthorized"] as? Int == 1 else { throw HostServiceError.invalidResponse }
        let body = try field("body"), model = try field("model"), marker = try field("finalMarker")
        guard HostService.hash(Data(body.utf8)) == c["bodySHA256"] as? String else { throw HostServiceError.invalidResponse }
        await store.loadModels()
        guard store.models.contains(where: { $0.id == model }),
          store.saved.aiSharingGrants?.contains(where: { $0.scope.target == target && $0.scope.model == model }) == true else { throw HostServiceError.unavailable }
        report["createAttempted"] = true; try save()
        let thread = try await provider.createThread(client, projectId: nil, operationId: try field("createOperationId"))
        ownedThread = thread.id; report["threadId"] = thread.id; report["seedSendAttempted"] = true; try save()
        let sentAt = Date()
        let sent = try await provider.send(client, reply: PendingReply(hostId: client.id, threadId: thread.id,
          clientMessageId: try field("sendOperationId"), text: body, attachmentIds: [], mode: "next-turn",
          expectedTurnId: nil, model: model, effort: nil, permissionMode: "confirm_high_risk"))
        guard sent.state == "submitted", let root = sent.turnId else { throw HostServiceError.invalidResponse }
        ownedRoot = root; report["rootRunId"] = root; report["status"] = "waiting-fresh-original-completed-reply"; try save()
        var history = try await provider.history(client, threadId: thread.id, cursor: nil)
        let completeBy = Date().addingTimeInterval(180)
        while history.runtime?.state != "idle", !["failed", "cancelled", "interrupted"].contains(history.runtime?.state ?? ""), Date() < completeBy {
          try await Task.sleep(for: .milliseconds(250)); history = try await provider.history(client, threadId: thread.id, cursor: nil)
        }
        let final = try XCTUnwrap(history.messages.last { $0.role == "assistant" && $0.runId == root })
        guard history.runtime?.state == "idle", final.text.trimmingCharacters(in: .whitespacesAndNewlines) == marker else { throw HostServiceError.invalidResponse }
        guard history.runtime?.suggestionSourceTurnId == root else { throw HostServiceError.invalidResponse }
        let suggestionScope = DesktopSuggestionScope(target: target, threadId: thread.id, sourceTurnId: root,
          navigation: "native-" + run, connection: "native-" + run, requiresRuntimeSource: true)
        let firstSuggestion = try await provider.nextTurnSuggestion(client, scope: suggestionScope)
        let secondSuggestion = try await provider.nextTurnSuggestion(client, scope: suggestionScope)
        guard firstSuggestion.source == "runtime", firstSuggestion.readOnly,
          secondSuggestion.source == "runtime", secondSuggestion.readOnly,
          firstSuggestion.suggestion == nil || firstSuggestion.suggestion?.isRuntimeBound == true,
          secondSuggestion.suggestion == nil || secondSuggestion.suggestion?.isRuntimeBound == true else { throw HostServiceError.invalidResponse }
        if let first = firstSuggestion.suggestion, let second = secondSuggestion.suggestion {
          guard first.id == second.id, first.contentSha256 == second.contentSha256 else { throw HostServiceError.invalidResponse }
        }
        report["canonicalSyncSourceMatchesOriginalRoot"] = true
        report["canonicalSuggestionReadTwiceWithRuntimeVersionOnly"] = true
        let outputHash = HostService.hash(Data(final.text.utf8)), elapsed = Date().timeIntervalSince(sentAt)
        guard elapsed < 300 else { throw HostServiceError.invalidResponse }
        report["originalFinalMessageId"] = final.id; report["originalFinalOutputSHA256"] = outputHash
        report["freshSendToArchiveRequestSeconds"] = elapsed
        report["archiveAttemptedOnce"] = true; try save()
        _ = try await channel.request("thread/archive", ["threadId": thread.id, "archived": true], id: try field("archiveOperationId"))
        for index in 1...2 {
          let cold = try await provider.history(client, threadId: thread.id, cursor: nil)
          guard cold.thread?.id == thread.id, cold.thread?.archived == true, cold.runtime?.state == "idle",
            cold.runtime?.suggestionSourceTurnId == nil,
            cold.messages.allSatisfy({ $0.runId == nil || $0.runId == root }),
            let original = cold.messages.last(where: { $0.id == final.id && $0.runId == root }),
            HostService.hash(Data(original.text.utf8)) == outputHash else { throw HostServiceError.invalidResponse }
          report["coldHistoryReadsCompleted"] = index; try save()
        }
        guard try originalReadProtectedDigests(store) == protected,
          try encoder.encode(store.drafts.values) == encoder.encode(oldDrafts),
          try encoder.encode(store.saved.generationPreferences) == oldPreferences,
          try encoder.encode(store.saved.aiSharingGrants) == oldGrants else { throw HostServiceError.invalidResponse }
        report["freshCompletionArchivedAndReadTwiceWithinOriginalSuggestionTTL"] = true
        report["unrelatedPhoneStateAndIdentityExact"] = true
        report["status"] = "passed-fresh-original-cold-history-no-replay"; try save(); return
      }
      if action == "cold-history" {
        guard c["newRootInputsAuthorized"] as? Int == 0, c["newThreadsAuthorized"] as? Int == 0,
          c["maxChildRunsAuthorized"] as? Int == 0 else { throw HostServiceError.invalidResponse }
        let thread = try field("threadId"), root = try field("rootRunId")
        guard UUID(uuidString: thread) != nil, UUID(uuidString: root) != nil else { throw HostServiceError.invalidResponse }
        report["threadId"] = thread; report["rootRunId"] = root
        report["status"] = "reading-original-cold-history-without-command"; try save()
        var originalFinalId: String?
        for index in 1...2 {
          let history = try await provider.history(client, threadId: thread, cursor: nil)
          guard history.thread?.id == thread, history.thread?.archived == true,
            history.runtime?.state == "idle",
            history.messages.allSatisfy({ $0.runId == nil || $0.runId == root }) else { throw HostServiceError.invalidResponse }
          let final = try XCTUnwrap(history.messages.last { $0.role == "assistant" && $0.runId == root })
          guard final.text.contains(try field("originMarker")), final.text.contains(try field("queueMarker")),
            HostService.hash(Data(final.text.utf8)) == c["expectedFinalOutputSHA256"] as? String,
            originalFinalId == nil || originalFinalId == final.id else { throw HostServiceError.invalidResponse }
          originalFinalId = final.id
          report["coldHistoryReadsCompleted"] = index; report["originalFinalMessageId"] = final.id; try save()
        }
        guard try originalReadProtectedDigests(store) == protected,
          try encoder.encode(store.drafts.values) == encoder.encode(oldDrafts),
          try encoder.encode(store.saved.generationPreferences) == oldPreferences,
          try encoder.encode(store.saved.aiSharingGrants) == oldGrants else { throw HostServiceError.invalidResponse }
        report["originalColdHistoryAndFinalReadTwiceWithoutArchiveRestoreOrInput"] = true
        report["unrelatedPhoneStateAndIdentityExact"] = true
        report["status"] = "passed-original-cold-history-read-only"; try save(); return
      }
      if action == "cold-status" || action == "cleanup" {
        guard c["newRootInputsAuthorized"] as? Int == 0, c["newThreadsAuthorized"] as? Int == 0 else { throw HostServiceError.invalidResponse }
        let thread = try field("threadId"), id = try field("queueId")
        let root = c["rootRunId"] as? String
        let expectedState = c["expectedQueueState"] as? String ?? "submitted"
        guard ["submitted", "cancelled", "uncertain"].contains(expectedState),
          expectedState != "submitted" || root.flatMap(UUID.init(uuidString:)) != nil,
          action != "cleanup" || expectedState == "submitted" else { throw HostServiceError.invalidResponse }
        let expectedRoot: String? = expectedState == "submitted" ? root : nil
        let first = try await provider.receipt(client, id: id), second = try await provider.receipt(client, id: id)
        guard first.threadId == thread, first.state == expectedState, first.turnId == expectedRoot,
          first.queueSource == "native", second.clientMessageId == first.clientMessageId,
          second.threadId == thread, second.turnId == expectedRoot, second.state == first.state else { throw HostServiceError.invalidResponse }
        let missing = try await channel.request("queue/status", ["threadId": thread, "messageId": try field("missingOperationId")])
        guard missing.elementsEqual(Data("null".utf8)) else { throw HostServiceError.invalidResponse }
        if c["expectOriginalQueueAbsent"] as? Bool == true {
          guard first.queuePresent != true, second.queuePresent != true else { throw HostServiceError.invalidResponse }
          report["sameOriginalQueueCardAbsentOnBothColdQueries"] = true
        }
        if expectedState == "cancelled" {
          guard first.queuePresent != true, second.queuePresent != true else { throw HostServiceError.invalidResponse }
          report["cancelledAdmissionStillHasNoDispatchRoot"] = true
        }
        if expectedState == "uncertain" {
          guard first.queuePresent == true, second.queuePresent == true,
            first.canExecuteNow != true, second.canExecuteNow != true else { throw HostServiceError.invalidResponse }
          report["unknownOriginalAdmissionStillPresentQueryOnlyWithoutAcceptedRoot"] = true
        }
        report["threadId"] = thread; report["rootRunId"] = root; report["queueId"] = id
        report["sameOriginalAdmissionQueriedWithoutEnqueueOrExecute"] = true
        if action == "cleanup" {
          guard c["metadataWritesAuthorized"] as? Int == 1 else { throw HostServiceError.invalidResponse }
          let history = try await provider.history(client, threadId: thread, cursor: nil)
          let text = history.messages.last { $0.role == "assistant" }?.text ?? ""
          guard history.runtime?.state == "idle", text.contains(try field("originMarker")), text.contains(try field("queueMarker")) else { throw HostServiceError.invalidResponse }
          report["archiveAttempted"] = true; try save()
          _ = try await channel.request("thread/archive", ["threadId": thread, "archived": true], id: try field("archiveOperationId"))
          report["status"] = "passed-own-completed-original-queue-thread-archived"
        } else { report["status"] = expectedState == "cancelled" ? "passed-cold-original-native-queue-cancelled-status-only" : expectedState == "uncertain" ? "passed-cold-original-native-queue-unknown-no-replay" : "passed-cold-original-native-queue-status-only" }
        guard try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
        try save(); return
      }
      let cancelBody = c["cancelBody"] as? String
      let automaticNextBody = c["automaticNextQueueBody"] as? String
      let expectsAutomaticSend = c["expectOriginalAutomaticSend"] as? Bool == true
      guard expectsAutomaticSend == (automaticNextBody != nil), !(cancelBody != nil && expectsAutomaticSend),
        c["newRootInputsAuthorized"] as? Int == (cancelBody != nil || expectsAutomaticSend ? 3 : 2), c["newThreadsAuthorized"] as? Int == 1,
        c["maxChildRunsAuthorized"] as? Int == 0 else { throw HostServiceError.invalidResponse }
      let body = try field("body"), queuedBody = try field("queueBody"), model = try field("model")
      guard HostService.hash(Data(body.utf8)) == c["bodySHA256"] as? String,
        HostService.hash(Data(queuedBody.utf8)) == c["queueSHA256"] as? String else { throw HostServiceError.invalidResponse }
      await store.loadModels()
      let selectedModel = try XCTUnwrap(store.models.first { $0.id == model })
      let requestedSpeed = c["speed"] as? String
      guard requestedSpeed == nil || selectedModel.supportedSpeeds?.contains(requestedSpeed!) == true,
        store.saved.aiSharingGrants?.contains(where: { $0.scope.target == target && $0.scope.model == model }) == true else { throw HostServiceError.unavailable }
      report["modelAdvertisedSpeeds"] = selectedModel.supportedSpeeds ?? []
      report["explicitSpeedRequested"] = requestedSpeed ?? "omitted-follow-original-default"
      report["createAttempted"] = true; try save()
      let thread = try await provider.createThread(client, projectId: nil, operationId: try field("createOperationId"))
      ownedThread = thread.id; report["threadId"] = thread.id; try save()
      await store.refresh(includeHistory: false, userInitiated: true)
      guard store.threads.contains(where: { $0.id == thread.id }) else { throw HostServiceError.invalidResponse }
      store.openThread(thread, loadAutomatically: false); ownedKey = store.targetKey
      report["seedSendAttempted"] = true; try save()
      let seeded = try await provider.send(client, reply: PendingReply(hostId: client.id, threadId: thread.id,
        clientMessageId: try field("sendOperationId"), text: body, attachmentIds: [], mode: "next-turn",
        expectedTurnId: nil, model: model, effort: nil, permissionMode: "confirm_high_risk", speed: requestedSpeed))
      guard seeded.state == "submitted", let root = seeded.turnId else { throw HostServiceError.invalidResponse }
      ownedRoot = root; report["rootRunId"] = root; report["status"] = "waiting-independent-original-gate-witness"; try save()
      let witnessPath = folder.appendingPathComponent(run + ".desktop-gate-witness.json")
      try await wait("independent desktop observed original running cat gate", seconds: 110) { FileManager.default.fileExists(atPath: witnessPath.path) }
      let witness = try easyWorkObject(Data(contentsOf: witnessPath))
      guard witness["caseId"] as? String == run, witness["rootRunId"] as? String == root,
        witness["originalExecutionObservedRunning"] as? Bool == true else { throw HostServiceError.invalidResponse }
      _ = await store.loadHistory(threadId: thread.id)
      guard store.runtime?.turnId == root, store.isRunning else { throw HostServiceError.invalidResponse }
      let key = store.targetKey
      func enqueueThroughOriginalStore(_ text: String) async throws -> DeliveryReceipt {
        store.drafts.values[key] = Draft(text: text, model: model, permissionMode: "confirm_high_risk", speed: requestedSpeed)
        store.replyMode = "queue"
        guard store.canSend else { report["canSendQueue"] = false; try save(); throw HostServiceError.invalidResponse }
        report["normalQueueSendAttempted"] = true
        report["normalQueueSendsAttempted"] = (report["normalQueueSendsAttempted"] as? Int ?? 0) + 1
        try save(); store.send()
        var queued: DeliveryReceipt?
        let queueDeadline = Date().addingTimeInterval(55)
        repeat {
          _ = await store.loadHistory(threadId: thread.id)
          queued = store.queuedReplies.first { $0.text == text }
          if queued == nil { try await Task.sleep(for: .milliseconds(150)) }
        } while queued == nil && Date() < queueDeadline
        let receipt = try XCTUnwrap(queued)
        guard receipt.threadId == thread.id, receipt.queueSource == "native", receipt.queuePresent == true,
          receipt.canExecuteNow == true, !store.sendingKeys.contains(key) else { throw HostServiceError.invalidResponse }
        return receipt
      }
      if let cancelBody {
        guard !cancelBody.isEmpty, HostService.hash(Data(cancelBody.utf8)) == c["cancelBodySHA256"] as? String else { throw HostServiceError.invalidResponse }
        let first = try await enqueueThroughOriginalStore(cancelBody); queueId = first.clientMessageId
        report["cancelledQueueId"] = first.clientMessageId; try save()
        guard store.canCancelQueue(first, expectedTargetKey: key) else { throw HostServiceError.invalidResponse }
        store.cancelQueue(first, expectedTargetKey: key)
        guard store.queueActionPending(first) else { throw HostServiceError.invalidResponse }
        store.cancelQueue(first, expectedTargetKey: key)
        try await wait("one original Store cancellation completes", seconds: 55) { !store.queueActionPending(first) }
        let cancelled = try await provider.receipt(client, id: first.clientMessageId)
        _ = await store.loadHistory(threadId: thread.id)
        guard cancelled.state == "cancelled", cancelled.threadId == thread.id, cancelled.queueSource == "native",
          cancelled.turnId == nil, cancelled.queuePresent != true,
          !store.queuedReplies.contains(where: { $0.clientMessageId == first.clientMessageId }),
          store.runtime?.turnId == root, store.isRunning else { throw HostServiceError.invalidResponse }
        report["normalStoreDoubleCancellationConfirmedWithoutDispatch"] = true; try save()
      }
      let receipt = try await enqueueThroughOriginalStore(queuedBody); queueId = receipt.clientMessageId
      report["queueId"] = receipt.clientMessageId; report["originalNativeQueueObserved"] = true; try save()
      if let automaticNextBody {
        guard !automaticNextBody.isEmpty, HostService.hash(Data(automaticNextBody.utf8)) == c["automaticNextQueueSHA256"] as? String else { throw HostServiceError.invalidResponse }
        let second = try await enqueueThroughOriginalStore(automaticNextBody); queueId = second.clientMessageId
        report["secondQueueId"] = second.clientMessageId
        let order = store.queuedReplies.map(\.clientMessageId).filter { $0 == receipt.clientMessageId || $0 == second.clientMessageId }
        guard order == [receipt.clientMessageId, second.clientMessageId] else { throw HostServiceError.invalidResponse }
        report["twoOriginalQueueInputsObservedInOriginalOrder"] = true; try save()
      }
      let expectsRecovery = c["expectOriginalReceiptRecovery"] as? Bool == true
      let expectsOwnerLoss = c["expectOriginalOwnerLoss"] as? Bool == true
      guard !(expectsRecovery && expectsOwnerLoss), !(expectsAutomaticSend && (expectsRecovery || expectsOwnerLoss)) else { throw HostServiceError.invalidResponse }
      if expectsRecovery || expectsOwnerLoss || expectsAutomaticSend {
        let armedPath = folder.appendingPathComponent(run + ".receipt-fault-armed.json")
        try await wait("parent arms one exact original admission acknowledgement loss", seconds: 45) { FileManager.default.fileExists(atPath: armedPath.path) }
        let armed = try easyWorkObject(Data(contentsOf: armedPath))
        guard armed["caseId"] as? String == run, armed["queueId"] as? String == receipt.clientMessageId,
          armed["threadId"] as? String == thread.id, armed["rootRunId"] as? String == root else { throw HostServiceError.invalidResponse }
        report["oneExactDEVReceiptFaultArmed"] = true; try save()
      }
      if expectsAutomaticSend {
        report["desktopSeedReleaseRequested"] = true
        report["status"] = "waiting-original-idle-send-and-independent-receipt-witness"; try save()
        let unknownPath = folder.appendingPathComponent(run + ".original-send-unknown-witness.json")
        try await wait("parent verifies original automatic send acceptance and unknown acknowledgement", seconds: 170) { FileManager.default.fileExists(atPath: unknownPath.path) }
        let unknown = try easyWorkObject(Data(contentsOf: unknownPath))
        let acceptedRoot = try XCTUnwrap(unknown["acceptedRootRunId"] as? String)
        guard unknown["caseId"] as? String == run, unknown["queueId"] as? String == receipt.clientMessageId,
          unknown["threadId"] as? String == thread.id, UUID(uuidString: acceptedRoot) != nil, acceptedRoot != root,
          unknown["originalRuntimeReceiptMatched"] as? Bool == true,
          unknown["originalUnknownAdmissionDurablyObserved"] as? Bool == true else { throw HostServiceError.invalidResponse }
        var first = try await provider.receipt(client, id: receipt.clientMessageId)
        let settlementEnd = Date().addingTimeInterval(55)
        while first.queuePresent == true, Date() < settlementEnd {
          try await Task.sleep(for: .milliseconds(200)); first = try await provider.receipt(client, id: receipt.clientMessageId)
        }
        guard first.state == "submitted", first.queueSource == "native", first.threadId == thread.id,
          first.turnId == acceptedRoot, first.queuePresent != true else { throw HostServiceError.invalidResponse }
        ownedRoot = acceptedRoot
        report["automaticFirstRootRunId"] = acceptedRoot
        report["originalIdleSendRecoveredBySameIdWithoutExecuteOrReplay"] = true; try save()
        let secondID = try XCTUnwrap(report["secondQueueId"] as? String)
        var second = try await provider.receipt(client, id: secondID)
        var final = try await provider.history(client, threadId: thread.id, cursor: nil)
        let end = Date().addingTimeInterval(180)
        while Date() < end {
          if let nextRoot = second.turnId, nextRoot != root, nextRoot != acceptedRoot,
            second.state == "submitted", second.queueSource == "native", second.queuePresent != true,
            final.runtime?.state == "idle" {
            ownedRoot = nextRoot
            let origin = final.messages.last { $0.role == "assistant" && $0.runId == root }?.text ?? ""
            let firstOutput = final.messages.last { $0.role == "assistant" && $0.runId == acceptedRoot }?.text ?? ""
            let nextOutput = final.messages.last { $0.role == "assistant" && $0.runId == nextRoot }?.text ?? ""
            if origin.contains(try field("originMarker")), firstOutput.contains(try field("queueMarker")), nextOutput.contains(try field("automaticNextMarker")) {
              report["automaticNextRootRunId"] = nextRoot
              report["threeOriginalModelOutputsMatchedFrozenMarkers"] = true
              break
            }
          }
          try await Task.sleep(for: .milliseconds(250))
          second = try await provider.receipt(client, id: secondID)
          final = try await provider.history(client, threadId: thread.id, cursor: nil)
        }
        guard report["threeOriginalModelOutputsMatchedFrozenMarkers"] as? Bool == true else { throw HostServiceError.invalidResponse }
        _ = await store.loadHistory(threadId: thread.id)
        guard !store.queuedReplies.contains(where: { $0.clientMessageId == receipt.clientMessageId || $0.clientMessageId == secondID }) else { throw HostServiceError.invalidResponse }
        var others = store.drafts.values; others.removeValue(forKey: key)
        guard try encoder.encode(others) == encoder.encode(oldDrafts),
          try encoder.encode(store.saved.generationPreferences) == oldPreferences,
          try encoder.encode(store.saved.aiSharingGrants) == oldGrants else { throw HostServiceError.invalidResponse }
        report["noExplicitQueueExecuteCalledForAutomaticCase"] = true
        report["originalQueueAdvancedToNextDistinctAdmittedInput"] = true
        report["unrelatedDraftsSettingsAndGrantsExact"] = true
        report["status"] = "passed-original-automatic-send-recovery-and-next-input-order"; try save(); return
      }
      do {
        _ = try await provider.executeQueue(client, threadId: thread.id, messageId: receipt.clientMessageId, expectedTurnId: "wrong-root-" + run)
        throw HostServiceError.invalidResponse
      } catch let rejection as EasyWorkRemoteRejection {
        guard rejection.code == "run_changed" else { throw rejection }
        report["wrongRunRejectedBeforeDispatch"] = true
      }
      _ = await store.loadHistory(threadId: thread.id)
      let current = try XCTUnwrap(store.queuedReplies.first { $0.id == receipt.id })
      guard store.canExecuteQueue(current, expectedTargetKey: key, expectedTurnId: root) else { throw HostServiceError.invalidResponse }
      report["normalStoreExecuteAttempted"] = true; try save()
      store.executeQueue(current, expectedTargetKey: key, expectedTurnId: root)
      guard store.queueActionPending(current) else { throw HostServiceError.invalidResponse }
      store.executeQueue(current, expectedTargetKey: key, expectedTurnId: root)
      try await wait("one original Store queue action completes", seconds: 55) { !store.queueActionPending(current) }
      if expectsOwnerLoss {
        let lossPath = folder.appendingPathComponent(run + ".original-owner-loss.json")
        try await wait("parent confirms exact original owner exited after accepted input", seconds: 30) { FileManager.default.fileExists(atPath: lossPath.path) }
        let loss = try easyWorkObject(Data(contentsOf: lossPath))
        guard loss["caseId"] as? String == run, loss["queueId"] as? String == receipt.clientMessageId,
          loss["threadId"] as? String == thread.id, loss["rootRunId"] as? String == root,
          loss["originalRuntimeReceiptMatched"] as? Bool == true,
          loss["originalDispatchAdmissionDurable"] as? Bool == true,
          loss["originalOwnerConfirmedExited"] as? Bool == true,
          loss["newOwnerNotStarted"] as? Bool == true else { throw HostServiceError.invalidResponse }
        var others = store.drafts.values; others.removeValue(forKey: key)
        guard try encoder.encode(others) == encoder.encode(oldDrafts),
          try encoder.encode(store.saved.generationPreferences) == oldPreferences,
          try encoder.encode(store.saved.aiSharingGrants) == oldGrants else { throw HostServiceError.invalidResponse }
        report["originalRuntimeAcceptedBeforeRealOwnerExit"] = true
        report["originalAdmissionStillDispatchingAtOwnerExit"] = true
        report["unrelatedDraftsSettingsAndGrantsExact"] = true
        report["nativeCaseMadeNoFurtherQueueActionAfterOwnerLoss"] = true
        report["status"] = "passed-real-original-owner-loss-with-accepted-input-awaiting-cold-query"; try save(); return
      }
      if expectsRecovery {
        let unknownPath = folder.appendingPathComponent(run + ".original-unknown-witness.json")
        try await wait("parent verifies original runtime acceptance and durable unknown admission", seconds: 45) { FileManager.default.fileExists(atPath: unknownPath.path) }
        let unknown = try easyWorkObject(Data(contentsOf: unknownPath))
        guard unknown["caseId"] as? String == run, unknown["queueId"] as? String == receipt.clientMessageId,
          unknown["rootRunId"] as? String == root, unknown["originalRuntimeReceiptMatched"] as? Bool == true,
          unknown["originalUnknownAdmissionDurablyObserved"] as? Bool == true else { throw HostServiceError.invalidResponse }
        report["independentAcceptedRuntimeAndDurableUnknownWitnessMatched"] = true; try save()
      }
      let submitted = try await provider.receipt(client, id: receipt.clientMessageId)
      guard submitted.state == "submitted", submitted.turnId == root, submitted.threadId == thread.id else { throw HostServiceError.invalidResponse }
      if expectsRecovery {
        _ = await store.loadHistory(threadId: thread.id)
        guard !store.queuedReplies.contains(where: { $0.clientMessageId == receipt.clientMessageId }) else { throw HostServiceError.invalidResponse }
        report["sameOriginalAdmissionRecoveredByQueryWithoutInputReplay"] = true
        report["originalQueueCardSettledAfterAuthoritativeRecovery"] = true
      }
      report["normalStoreExecuteCompletedOnOriginalRoot"] = true
      report["desktopReleaseRequested"] = true; report["status"] = "waiting-original-model-result"; try save()
      var final = try await provider.history(client, threadId: thread.id, cursor: nil)
      let end = Date().addingTimeInterval(140)
      while final.runtime?.state != "idle", !["failed", "cancelled", "interrupted"].contains(final.runtime?.state ?? ""), Date() < end {
        try await Task.sleep(for: .milliseconds(300)); final = try await provider.history(client, threadId: thread.id, cursor: nil)
      }
      let output = final.messages.last { $0.role == "assistant" }?.text ?? ""
      guard final.runtime?.state == "idle", output.contains(try field("originMarker")), output.contains(try field("queueMarker")) else { throw HostServiceError.invalidResponse }
      if cancelBody != nil {
        guard !output.contains(try field("cancelMarker")) else { throw HostServiceError.invalidResponse }
        report["cancelledFrozenMarkerAbsentFromActualFinal"] = true
      }
      var others = store.drafts.values; others.removeValue(forKey: key)
      guard try encoder.encode(others) == encoder.encode(oldDrafts),
        try encoder.encode(store.saved.generationPreferences) == oldPreferences,
        try encoder.encode(store.saved.aiSharingGrants) == oldGrants else { throw HostServiceError.invalidResponse }
      report["originalModelReturnedBothFrozenMarkers"] = true
      report["unrelatedDraftsSettingsAndGrantsExact"] = true
      report["modelOutputSHA256"] = HostService.hash(Data(output.utf8))
      report["status"] = "passed-normal-ios-original-native-queue-execution"; try save()
    } catch {
      report["status"] = "failed-original-queue-no-input-replayed"; report["failure"] = error.localizedDescription
      if ["execute", "fresh-cold-history"].contains(action), let thread = ownedThread, let root = ownedRoot {
        if let queueId { _ = try? await provider.cancelQueue(client, threadId: thread, messageId: queueId) }
        if let history = try? await provider.history(client, threadId: thread, cursor: nil), history.runtime?.turnId == root,
          ["running", "waiting-for-approval", "paused"].contains(history.runtime?.state ?? "") {
          report["ownedCleanupStopAttempted"] = true; try? save(); _ = try? await provider.stop(client, threadId: thread, turnId: root)
        }
      }
      try? save(); throw error
    }
  }

  @MainActor func testOriginalQuestionMultipleAndCustomAnswerCombination() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let encoded = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-restore-testing") else { throw XCTSkip("Explicit original question combination case required") }
    let c = try easyWorkObject(Data(encoded.utf8))
    guard c["caseId"] as? String == run, c["action"] as? String == "question-combination",
      c["newRootInputsAuthorized"] as? Int == 1, c["newThreadsAuthorized"] as? Int == 1,
      c["maxChildRunsAuthorized"] as? Int == 0,
      let bindings = c["allowedBindingIds"] as? [String], !bindings.isEmpty else { throw HostServiceError.invalidResponse }
    func field(_ key: String) throws -> String {
      let value = try XCTUnwrap(c[key] as? String); guard !value.isEmpty else { throw HostServiceError.invalidResponse }; return value
    }
    let body = try field("body"), customAnswer = try field("customAnswer"), marker = try field("finalMarker")
    let expectsSecret = c["expectSecretCustom"] as? Bool == true
    let desktopAnswersFirst = c["desktopAnswersFirst"] as? Bool == true
    guard !desktopAnswersFirst || expectsSecret else { throw HostServiceError.invalidResponse }
    guard HostService.hash(Data(body.utf8)) == c["bodySHA256"] as? String,
      customAnswer.hasPrefix("EW-SYNTHETIC-CUSTOM-") else { throw HostServiceError.invalidResponse }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(c["target"] ?? [:]))
    let deadline = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    guard !store.localRecoveryRequired, store.client?.id == client.id else { throw HostServiceError.wrongHost }
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let provider = try AgentClients.resolve(client)
    let protected = try originalReadProtectedDigests(store)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    var report: [String: Any] = ["caseId": run, "appPID": ProcessInfo.processInfo.processIdentifier,
      "wholeFeaturePassed": false, "pureUITests": false, "syntheticAnswersOnly": true,
      "newRootInputBudget": 1, "newChildRunBudget": 0]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]).write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    var ownedThread: String?, ownedRoot: String?, finished = false
    do {
      report["createAttempted"] = true; try save()
      let thread = try await provider.createThread(client, projectId: nil, operationId: try field("createOperationId"))
      ownedThread = thread.id; report["threadId"] = thread.id; try save()
      if expectsSecret {
        let rename = ChatActionRequest(hostId: client.id, target: target, threadId: thread.id,
          clientOperationId: try field("renameOperationId"), action: .rename, title: try field("threadTitle"))
        let result = try await provider.chatAction(client, request: rename)
        guard result.phase == "confirmed", result.request == rename else { throw HostServiceError.invalidResponse }
        report["threadTitle"] = try field("threadTitle"); try save()
      }
      let input = PendingReply(hostId: client.id, threadId: thread.id, clientMessageId: try field("sendOperationId"),
        text: body, attachmentIds: [], mode: "next-turn", expectedTurnId: nil,
        model: try field("model"), effort: nil, permissionMode: "confirm_high_risk")
      report["sendAttempted"] = true; try save()
      let sent = try await provider.send(client, reply: input)
      guard sent.state == "submitted", let root = sent.turnId else { throw HostServiceError.invalidResponse }
      ownedRoot = root; report["rootRunId"] = root; try save()
      var approval: Approval?
      let questionDeadline = Date().addingTimeInterval(180)
      while Date() < questionDeadline {
        let h = try await provider.history(client, threadId: thread.id, cursor: nil)
        if let found = h.runtime?.approvals.first(where: { $0.kind == "question" && $0.questions.count == 2 && $0.questions.allSatisfy { $0.question.contains(run) } }) {
          guard found.threadId == thread.id, found.turnId == root, h.runtime?.state == "waiting-for-approval" else { throw HostServiceError.wrongHost }
          approval = found; break
        }
        if ["failed", "cancelled", "interrupted", "idle"].contains(h.runtime?.state ?? "") { break }
        try await Task.sleep(for: .milliseconds(500))
      }
      let question = try XCTUnwrap(approval)
      let multi = try XCTUnwrap(question.questions.first { $0.id == "MultiPick" })
      let custom = try XCTUnwrap(question.questions.first { $0.id == "CustomPick" })
      guard multi.multiple == true, multi.custom == false,
        multi.options?.map(\.label) == ["Alpha", "Beta", "Gamma"],
        custom.multiple == false, custom.custom == true,
        custom.options?.map(\.label) == ["Preset", "Skip"],
        multi.isSecret != true, (expectsSecret ? custom.isSecret == true : custom.isSecret != true) else { throw HostServiceError.invalidResponse }
      report["originalQuestionPrivate"] = try JSONSerialization.jsonObject(with: encoder.encode(question)); try save()
      if expectsSecret {
        guard multi.isSecret == false else { throw HostServiceError.invalidResponse }
        report["originalSecretTrueAndPublicFalseMetadataReceived"] = true
      }
      if expectsSecret && !desktopAnswersFirst {
        report["status"] = "waiting-original-desktop-secret-mask-observation"; try save()
        let witness = folder.appendingPathComponent("secret-display-witness-" + run + ".json")
        let deadline = Date().addingTimeInterval(240)
        while !FileManager.default.fileExists(atPath: witness.path), Date() < deadline {
          try await Task.sleep(for: .milliseconds(500))
        }
        guard FileManager.default.fileExists(atPath: witness.path) else { throw HostServiceError.invalidResponse }
        let proof = try easyWorkObject(Data(contentsOf: witness))
        guard proof["caseId"] as? String == run, proof["threadId"] as? String == thread.id,
          proof["rootRunId"] as? String == root, proof["questionId"] as? String == question.id,
          proof["actualMaskObserved"] as? Bool == true, proof["syntheticDraftClearedWithoutSubmission"] as? Bool == true,
          proof["desktopAppBundleIdentifier"] as? String == "com.easyai.easywork.iosacceptance.dev" else { throw HostServiceError.invalidResponse }
        let pending = try await provider.history(client, threadId: thread.id, cursor: nil)
        guard pending.runtime?.approvals.contains(where: { $0.id == question.id && $0.turnId == root }) == true else { throw HostServiceError.invalidResponse }
        report["actualDesktopMaskAndClearBeforePhoneAnswerVerified"] = true; try save()
      }
      let valid = [multi.id: ["Alpha", "Gamma"], custom.id: [customAnswer]]
      if desktopAnswersFirst {
        report["status"] = "waiting-original-desktop-answer-before-phone-late"; try save()
        let witness = folder.appendingPathComponent("desktop-answer-witness-" + run + ".json")
        let desktopDeadline = Date().addingTimeInterval(240)
        while !FileManager.default.fileExists(atPath: witness.path), Date() < desktopDeadline {
          try await Task.sleep(for: .milliseconds(500))
        }
        guard FileManager.default.fileExists(atPath: witness.path) else { throw HostServiceError.invalidResponse }
        let proof = try easyWorkObject(Data(contentsOf: witness))
        guard proof["caseId"] as? String == run, proof["threadId"] as? String == thread.id,
          proof["rootRunId"] as? String == root, proof["questionId"] as? String == question.id,
          proof["desktopOriginalAnswerSubmitted"] as? Bool == true,
          proof["selectedLabels"] as? [String] == ["Alpha", "Gamma"],
          proof["customAnswerSHA256"] as? String == HostService.hash(Data(customAnswer.utf8)),
          proof["desktopAppBundleIdentifier"] as? String == "com.easyai.easywork.iosacceptance.dev" else { throw HostServiceError.invalidResponse }
        report["desktopAnswerWitnessOnOriginalQuestionReceived"] = true
        report["phoneOriginalAnswerNotSubmittedBeforeDesktop"] = true; try save()
      } else {
        for invalid in [[multi.id: ["not-an-option"], custom.id: [customAnswer]], [multi.id: ["Alpha"], custom.id: ["Preset", "Skip"]]] {
          var rejected = false
          do { _ = try await provider.approve(client, approval: question, decision: "answer", answers: invalid) }
          catch HostServiceError.message { rejected = true }
          guard rejected else { throw HostServiceError.invalidResponse }
        }
        let stillPending = try await provider.history(client, threadId: thread.id, cursor: nil)
        guard stillPending.runtime?.approvals.contains(where: { $0.id == question.id && $0.turnId == root }) == true else { throw HostServiceError.invalidResponse }
        report["invalidCustomAndMultipleAnswersLocallyRejectedOriginalStillPending"] = true
        report["answerAttempted"] = true; try save()
        guard try await provider.approve(client, approval: question, decision: "answer", answers: valid).ok else { throw HostServiceError.invalidResponse }
      }
      var output = ""
      let completionDeadline = Date().addingTimeInterval(180)
      while Date() < completionDeadline {
        let h = try await provider.history(client, threadId: thread.id, cursor: nil)
        output = h.messages.filter { $0.role == "assistant" && $0.runId == root }.map(\.text).joined(separator: "\n")
        if h.runtime?.state == "idle", h.runtime?.turnId == nil,
          output.contains(marker), output.contains("Alpha"), output.contains("Gamma"), output.contains(customAnswer) { finished = true; break }
        if ["failed", "cancelled", "interrupted"].contains(h.runtime?.state ?? "") { break }
        try await Task.sleep(for: .milliseconds(500))
      }
      guard finished else { throw HostServiceError.invalidResponse }
      report["sameOriginalRootReturnedFrozenChoicesAndCustomText"] = true
      report["outputSHA256"] = HostService.hash(Data(output.utf8))
      report["lateOriginalAnswerAttempted"] = true; try save()
      var lateRejected = false
      do { _ = try await provider.approve(client, approval: question, decision: "answer", answers: valid) }
      catch let error as EasyWorkRemoteRejection { guard error.code == "question_mismatch" else { throw error }; lateRejected = true }
      guard lateRejected, try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
      report["lateOriginalAnswerRejectedQuestionMismatch"] = true
      report["unrelatedPhoneStateAndIdentityExact"] = true
      report["status"] = desktopAnswersFirst ? "passed-original-real-desktop-first-phone-late-question" : "passed-original-real-question-multiple-custom-combination"
      try save()
    } catch {
      report["status"] = "failed-original-question-combination-no-replay"; report["failure"] = error.localizedDescription
      if !finished, let thread = ownedThread, let root = ownedRoot,
        let h = try? await provider.history(client, threadId: thread, cursor: nil), h.runtime?.turnId == root,
        ["running", "waiting-for-approval", "paused"].contains(h.runtime?.state ?? "") {
        report["ownCleanupStopAttempted"] = true; try? save(); _ = try? await provider.stop(client, threadId: thread, turnId: root)
      }
      try? save(); throw error
    }
  }

  @MainActor func testOriginalDesktopUnadmittedQueueReadOnly() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let run = env["EASYWORK_HOSTED_NATIVE_CASE"], UUID(uuidString: run)?.uuidString.lowercased() == run,
      let raw = env["EASYWORK_HOSTED_NATIVE_CONFIG_JSON"],
      Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"), ProcessInfo.processInfo.arguments.contains("-restore-testing") else { throw XCTSkip("Explicit original desktop queue case required") }
    let c = try easyWorkObject(Data(raw.utf8))
    guard c["caseId"] as? String == run, c["action"] as? String == "desktop-local-readonly",
      c["newRootInputsAuthorized"] as? Int == 1, c["newThreadsAuthorized"] as? Int == 1,
      c["maxChildRunsAuthorized"] as? Int == 0,
      let bindings = c["allowedBindingIds"] as? [String] else { throw HostServiceError.invalidResponse }
    func field(_ key: String) throws -> String { let value = try XCTUnwrap(c[key] as? String); guard !value.isEmpty else { throw HostServiceError.invalidResponse }; return value }
    let body = try field("body"), localBody = try field("localBody"), marker = try field("finalMarker")
    guard HostService.hash(Data(body.utf8)) == c["bodySHA256"] as? String,
      HostService.hash(Data(localBody.utf8)) == c["localBodySHA256"] as? String else { throw HostServiceError.invalidResponse }
    let target = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(try XCTUnwrap(c["target"])))
    let ready = Date().addingTimeInterval(30)
    while SessionStore.hostedEasyWorkAcceptanceStore == nil, Date() < ready { try await Task.sleep(for: .milliseconds(100)) }
    let store = try XCTUnwrap(SessionStore.hostedEasyWorkAcceptanceStore)
    let clients = store.saved.clients.filter { !$0.isDemo && $0.agentConnection?.target == target }
    let client = try XCTUnwrap(clients.count == 1 ? clients.first : nil)
    guard !store.localRecoveryRequired, store.client?.id == client.id else { throw HostServiceError.wrongHost }
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(try XCTUnwrap(CredentialVault.existingToken(try XCTUnwrap(client.agentConnection).credentialRef)).utf8))
    guard bindings.contains(credential.binding.bindingId) else { throw HostServiceError.wrongHost }
    let provider = try AgentClients.resolve(client), channel = try await EasyWorkSessions.shared.session(client)
    let protected = try originalReadProtectedDigests(store)
    let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("easywork-hosted-native")
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let path = folder.appendingPathComponent(run + ".json")
    guard !FileManager.default.fileExists(atPath: path.path) else { throw HostServiceError.invalidResponse }
    var report: [String: Any] = ["caseId": run, "appPID": ProcessInfo.processInfo.processIdentifier,
      "wholeFeaturePassed": false, "pureUITests": false, "originalLocalQueueSourceRequired": true]
    func save() throws { try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]).write(to: path, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
    var completed = false
    do {
      report["createAttempted"] = true; try save()
      let thread = try await provider.createThread(client, projectId: nil, operationId: try field("createOperationId"))
      report["threadId"] = thread.id
      let rename = ChatActionRequest(hostId: client.id, target: target, threadId: thread.id,
        clientOperationId: try field("renameOperationId"), action: .rename, title: try field("threadTitle"))
      let renamed = try await provider.chatAction(client, request: rename)
      guard renamed.phase == "confirmed", renamed.request == rename else { throw HostServiceError.invalidResponse }
      report["threadTitle"] = try field("threadTitle"); report["sendAttempted"] = true; try save()
      let reply = PendingReply(hostId: client.id, threadId: thread.id, clientMessageId: try field("sendOperationId"),
        text: body, attachmentIds: [], mode: "next-turn", expectedTurnId: nil, model: try field("model"), effort: nil,
        permissionMode: "confirm_high_risk")
      let sent = try await provider.send(client, reply: reply)
      guard sent.state == "submitted", let root = sent.turnId else { throw HostServiceError.invalidResponse }
      report["rootRunId"] = root
      report["desktopPreparationReady"] = true; report["status"] = "awaiting-one-original-desktop-local-queue-row"; try save()
      var local: DeliveryReceipt?
      let queueDeadline = Date().addingTimeInterval(300)
      while Date() < queueDeadline {
        let h = try await provider.history(client, threadId: thread.id, cursor: nil)
        guard h.runtime?.turnId == root, h.runtime?.state == "running" else { throw HostServiceError.invalidResponse }
        let matching = h.runtime?.receipts.filter { $0.text == localBody } ?? []
        guard matching.count <= 1 else { throw HostServiceError.invalidResponse }
        if let receipt = matching.first { local = receipt; break }
        try await Task.sleep(for: .milliseconds(500))
      }
      let receipt = try XCTUnwrap(local)
      guard receipt.threadId == thread.id, UUID(uuidString: receipt.clientMessageId) != nil,
        receipt.queueSource == "native", receipt.queueDisposition == "legacy-review", receipt.state == "uncertain",
        receipt.queuePresent == true, receipt.canExecuteNow == false, receipt.turnId == nil else { throw HostServiceError.invalidResponse }
      let id = receipt.clientMessageId
      guard !QueueRules.shared.active(state: receipt.state, source: receipt.queueSource,
        present: KotlinBoolean(bool: true), disposition: receipt.queueDisposition) else { throw HostServiceError.invalidResponse }
      for _ in 0..<2 {
        let value = try easyWorkObject(await channel.request("queue/status", ["threadId": thread.id, "messageId": id]))
        guard value["clientMessageId"] as? String == id, value["threadId"] as? String == thread.id else { throw HostServiceError.wrongHost }
        let q = try EasyWorkReceiptNormalization.decode(value, clientMessageId: id, hostId: client.id, threadId: thread.id, defaultState: "uncertain")
        guard q.state == "uncertain", q.queueSource == "native", q.queueDisposition == "legacy-review",
          q.text == localBody, q.queuePresent == true, q.canExecuteNow == false, q.turnId == nil else { throw HostServiceError.invalidResponse }
      }
      for (threadId, operation) in [(UUID().uuidString, id), (thread.id, UUID().uuidString)] {
        let missing = try await channel.request("queue/status", ["threadId": threadId, "messageId": operation])
        guard missing.elementsEqual(Data("null".utf8)) else { throw HostServiceError.invalidResponse }
      }
      var rejected = 0
      do { _ = try await provider.executeQueue(client, threadId: thread.id, messageId: id, expectedTurnId: root) }
      catch let error as EasyWorkRemoteRejection { guard error.code == "queue_admission_missing" else { throw error }; rejected += 1 }
      do { _ = try await provider.cancelQueue(client, threadId: thread.id, messageId: id) }
      catch let error as EasyWorkRemoteRejection { guard error.code == "queue_not_pending" else { throw error }; rejected += 1 }
      let replacement = PendingReply(hostId: client.id, threadId: thread.id, clientMessageId: id,
        text: localBody, attachmentIds: [], mode: "queue", expectedTurnId: root, model: try field("model"), effort: nil)
      do { _ = try await provider.send(client, reply: replacement) }
      catch let error as EasyWorkRemoteRejection { guard error.code == "queue_legacy_requires_query" else { throw error }; rejected += 1 }
      guard rejected == 3 else { throw HostServiceError.invalidResponse }
      let preserved = try easyWorkObject(await channel.request("queue/status", ["threadId": thread.id, "messageId": id]))
      guard preserved["clientMessageId"] as? String == id, preserved["queuePresent"] as? Bool == true,
        preserved["queueDisposition"] as? String == "legacy-review" else { throw HostServiceError.invalidResponse }
      report["queueId"] = id; report["legacyReadOnlyVerified"] = true
      report["sameOriginalStatusTwiceAndWrongThreadMissingNull"] = true
      report["threeWriteRefusalsWithoutAdoptionOrReplay"] = true
      report["status"] = "awaiting-original-desktop-removal-and-owned-gate-release"; try save()
      let end = Date().addingTimeInterval(300)
      while Date() < end {
        let h = try await provider.history(client, threadId: thread.id, cursor: nil)
        let output = h.messages.filter { $0.role == "assistant" && $0.runId == root }.map(\.text).joined(separator: "\n")
        if h.runtime?.state == "idle", h.runtime?.turnId == nil, output.contains(marker) {
          guard h.messages.filter({ $0.role == "user" }).count == 1,
            h.messages.first(where: { $0.role == "user" })?.text == body,
            h.runtime?.receipts.contains(where: { $0.clientMessageId == id }) != true else { throw HostServiceError.invalidResponse }
          completed = true; report["originalRootOutputSHA256"] = HostService.hash(Data(output.utf8)); break
        }
        if ["failed", "cancelled", "interrupted"].contains(h.runtime?.state ?? "") { break }
        try await Task.sleep(for: .milliseconds(500))
      }
      guard completed, try originalReadProtectedDigests(store) == protected else { throw HostServiceError.invalidResponse }
      let removed = try await channel.request("queue/status", ["threadId": thread.id, "messageId": id])
      guard removed.elementsEqual(Data("null".utf8)) else { throw HostServiceError.invalidResponse }
      report["onlyOriginalRootInputAndCompletionNoLocalQueueReplay"] = true
      report["unrelatedPhoneStateAndIdentityExact"] = true
      report["status"] = "passed-original-desktop-local-unadmitted-readonly-no-replay"; try save()
    } catch {
      report["status"] = "failed-original-desktop-local-queue-no-replay"; report["failure"] = error.localizedDescription
      report["parentMustRemoveOwnLocalRowBeforeReleasingOrStoppingRoot"] = true
      try? save(); throw error
    }
  }
}
