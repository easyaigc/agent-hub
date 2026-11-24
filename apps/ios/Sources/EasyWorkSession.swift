import Foundation
import MobileCore
import os

#if DEBUG
/// Delays only completion of an actual sent read in the normal acceptance App.
/// It never supplies a response, changes credentials or publishes a model input.
enum EasyWorkRequestDeadlineAcceptance {
  static func matches(bundleIdentifier: String?, arguments: [String], environment: [String: String],
    target: [String: Any], request: [String: Any]) -> Bool {
    guard bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      arguments.contains("-easywork-local-dev"), arguments.contains("-request-deadline-acceptance"),
      !arguments.contains("-ui-testing"),
      let desktop = target["desktopDeviceId"] as? String, !desktop.isEmpty,
      let workspace = target["workspaceId"] as? String, !workspace.isEmpty,
      let runtime = target["runtimeId"] as? String, !runtime.isEmpty,
      let account = target["account"] as? [String: Any],
      let authority = account["authority"] as? String, !authority.isEmpty,
      let subject = account["subject"] as? String, !subject.isEmpty,
      request["method"] as? String == "thread/history",
      let id = request["requestId"] as? String, UUID(uuidString: id) != nil,
      let params = request["params"] as? [String: Any], let thread = params["threadId"] as? String, !thread.isEmpty,
      environment["EASYWORK_REQUEST_DEADLINE_THREAD_ID"] == thread,
      let captured = request["target"] as? [String: Any], let encoded = try? easyWorkJSON(target),
      let original = try? easyWorkJSON(captured), original == encoded,
      environment["EASYWORK_REQUEST_DEADLINE_TARGET_SHA256"] == HostService.hash(encoded) else { return false }
    return true
  }
}
/// One original synthetic prepare response can be withheld locally after its
/// authentication. This never changes transport, receipts, IDs or file bytes.
actor EasyWorkFilePrepareReconciliationFault {
  static let shared = EasyWorkFilePrepareReconciliationFault()
  struct Witness: Sendable { let consumed: Bool; let recovered: Bool; let operationId: String? }
  private var target: EasyWorkTarget?
  private var operationId: String?
  private var consumed = false
  private var receiptRecovered = false
  func arm(caseId: String, target: EasyWorkTarget, operationId: String) throws {
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.environment["EASYWORK_HOSTED_NATIVE_CASE"] == caseId,
      UUID(uuidString: caseId) != nil, operationId.hasPrefix("file-prepare-"),
      UUID(uuidString: String(operationId.dropFirst("file-prepare-".count))) != nil,
      self.operationId == nil else { throw HostServiceError.invalidResponse }
    self.target = target; self.operationId = operationId
  }
  func consume(target: EasyWorkTarget, method: String, id: String) -> Bool {
    guard !consumed, self.target == target, method == "resource/upload", operationId == id else { return false }
    consumed = true
    return true
  }
  func recovered(target: EasyWorkTarget, operationId: String) {
    if consumed, self.target == target, self.operationId == operationId { receiptRecovered = true }
  }
  func witness() -> Witness { Witness(consumed: consumed, recovered: receiptRecovered, operationId: operationId) }
}
#endif

struct AgentTransportOptions: Sendable {
  let candidate: AgentConnectionCandidate
  let bindingId: String
  let accessToken: String?
  var grantRef: String? = nil
  var authorizationBinding: EasyWorkBinding? = nil
}
actor AgentTransportRegistry {
  static let shared = AgentTransportRegistry()
  typealias Factory = @Sendable (AgentTransportOptions) async throws -> any AgentTransport
  private var factories: [String: Factory] = [
    "lan-wss": { options in
      var headers: [String: String] = [:]
      if let ref = options.grantRef {
        guard let binding = options.authorizationBinding, var components = URLComponents(string: options.candidate.endpoint) else { throw HostServiceError.invalidResponse }
        components.path = "/channel"; components.query = nil; components.fragment = nil
        guard let url = components.url else { throw HostServiceError.invalidResponse }
        headers = try await EasyWorkDeviceAuthorization.shared.headers(ref: ref, binding: binding, method: "GET", url: url, lanCandidate: options.candidate)
      }
      return try LANAgentTransport(candidate: options.candidate, bindingId: options.bindingId, headers: headers)
    },
    "relay-http": { options in
      guard options.grantRef != nil || options.accessToken != nil else { throw HostServiceError.message(L10n.tr("设备尚未配对，请在电脑上确认授权")) }
      return try RelayAgentTransport(candidate: options.candidate, bindingId: options.bindingId, token: options.accessToken ?? "", grantRef: options.grantRef, binding: options.authorizationBinding)
    },
  ]
  func register(_ id: String, factory: @escaping Factory) { factories[id] = factory }
  func make(_ options: AgentTransportOptions) async throws -> any AgentTransport {
    guard let factory = factories[options.candidate.transportId] else { throw HostServiceError.message(L10n.tr("未注册此连接方式")) }
    return try await factory(options)
  }
}
actor EasyWorkSessions {
  static let shared = EasyWorkSessions()
  private var sessions: [String: (AgentConnection, EasyWorkRemoteSession)] = [:]
  private var preparing: Set<String> = []
  func session(_ client: Client) async throws -> EasyWorkRemoteSession {
    try Task.checkCancellation()
    guard client.migrationNeedsVerification != true else {
      throw HostServiceError.message(L10n.tr("迁移的 EasyWork 连接需要重新配对，请先核对电脑身份。"))
    }
    guard let connection = client.agentConnection else { throw HostServiceError.invalidResponse }
    // Closing a previous actor yields. Serialize the transition so concurrent
    // sends and subscriptions cannot create two consumers of one Relay mailbox.
    while preparing.contains(client.id) { try await Task.sleep(for: .milliseconds(25)) }
    try Task.checkCancellation()
    preparing.insert(client.id); defer { preparing.remove(client.id) }
    if let (previous, session) = sessions[client.id], previous.target == connection.target, previous.preference == connection.preference, previous.credentialRef == connection.credentialRef { return session }
    if let (_, previous) = sessions.removeValue(forKey: client.id) { await previous.close() }
    try Task.checkCancellation()
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(CredentialVault.token(connection.credentialRef).utf8))
    let session = try EasyWorkRemoteSession(connection: connection, credential: credential)
    sessions[client.id] = (connection, session)
    return session
  }
  func updateCandidates(_ clientId: String, connection: AgentConnection) async {
    while preparing.contains(clientId) {
      do { try await Task.sleep(for: .milliseconds(25)) } catch { return }
    }
    guard let (previous, session) = sessions[clientId], previous.target == connection.target,
      previous.preference == connection.preference, previous.credentialRef == connection.credentialRef else { return }
    sessions[clientId] = (connection, session)
    await session.updateCandidates(connection.candidates)
  }
  func remove(_ clientId: String) async {
    while preparing.contains(clientId) {
      do { try await Task.sleep(for: .milliseconds(25)) } catch { return }
    }
    preparing.insert(clientId); defer { preparing.remove(clientId) }
    if let (_, session) = sessions.removeValue(forKey: clientId) { await session.close() }
  }
}
/// An authenticated provider rejection is distinct from transport failure.
struct EasyWorkRemoteRejection: LocalizedError, Sendable {
  let code: String?
  let message: String
  var errorDescription: String? { message }
}

actor EasyWorkEventSignal {
  private var revision: UInt64 = 0
  private var waiters: [UUID: (CheckedContinuation<Void, Error>, Task<Void, Never>)] = [:]

  func current() -> UInt64 { revision }
  func signal() {
    revision &+= 1
    for id in Array(waiters.keys) { finish(id) }
  }
  private func finish(_ id: UUID, cancelled: Bool = false) {
    guard let (continuation, timeout) = waiters.removeValue(forKey: id) else { return }
    timeout.cancel()
    if cancelled { continuation.resume(throwing: CancellationError()) }
    else { continuation.resume() }
  }
  func wait(after observed: UInt64, for duration: Duration) async throws {
    try Task.checkCancellation()
    guard revision == observed, duration > .zero else { return }
    let id = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
        let timeout = Task {
          do { try await Task.sleep(for: duration) } catch { return }
          finish(id)
        }
        waiters[id] = (continuation, timeout)
      }
    } onCancel: {
      Task { await self.finish(id, cancelled: true) }
    }
  }
}

/// Owns request correlation, encryption and recovery; transports only carry frames.
actor EasyWorkRemoteSession {
  // This projection is session-local; only this iOS provider consumes the new
  // shared parser. Existing Android call sites and production rules are unchanged.
  private let subagents = SubagentProjection()
  func subagentActivities(_ snapshot: Data, threadId: String) -> [SubagentActivity] {
    subagents.project(snapshotJson: String(decoding: snapshot, as: UTF8.self), threadId: threadId)
  }
  let target: EasyWorkTarget
  private(set) var connection: AgentConnection
  private let credential: EasyWorkCredential
  var mobileDeviceId: String { credential.binding.mobileDeviceId }
  private var transport: (any AgentTransport)?
  private var crypto: EasyWorkEnvelopeCrypto?
  private var sequence = 1
  private var publishingOwners: [String: UUID] = [:]
  // Read callers own their wait, never the shared transport's send callback.
  // Retired sends remain bounded and cannot release a newer publisher's lock.
  private var readPublishTasks: [UUID: Task<Void, Never>] = [:]
  private var readPublishResults: [UUID: Result<Void, Error>] = [:]
  private var readPublishWaiters: Set<UUID> = []
  private var sendingReads: Set<UUID> = []
  private enum ReadPublishError: Error { case capacity }
  private var keyConfirmed = false
  private var receiveTask: Task<Void, Never>?
  private var responses: [String: [String: Any]] = [:]
  #if DEBUG
  private var fileCompletionFaultConsumed = false
  private var droppedReadRequestId: String?
  private var requestDeadlineFaultConsumed = false
  #endif
  private var pendingRequestIds: Set<String> = []
  private var events: [[String: Any]] = []
  private var threadSequences: [String: Int] = [:]
  private let eventSignal = EasyWorkEventSignal()
  private var subscriptions: [String: String] = [:]
  // Include in-flight subscribe intents: cancellation can follow a server-side
  // subscription but precede its response. The next reader still retires it.
  private var subscribedThreads: Set<String> = []
  private var threadSyncs: [String: EasyWorkThreadSync] = [:]
  private var syncingThreads: Set<String> = []
  private var threadSyncOrder: [String] = []
  private var received: [String: (highest: Int, sequences: Set<Int>)] = [:]
  private var activeCandidate: String?
  private var activeCandidateInfo: AgentConnectionCandidate?
  // Preserve transport error types so expected reconnects remain nonmodal in the UI.
  private var lastError: Error?
  private var connectionTask: (id: UUID, task: Task<Void, Never>)?
  private var connectionDeadline: Task<Void, Never>?
  private var connectionResults: [UUID: Result<Void, Error>] = [:]
  private var connectionWaiters: [UUID: Int] = [:]
  private var lastVerifiedTraffic: ContinuousClock.Instant?
  private var livenessTask: Task<Void, Never>?
  private var livenessResult: Bool?
  private var closed = false
  private static let readMethods: Set<String> = [
    "thread/open-status",
    "usage/read", "runtime/context", "runtime/summary", "project/list", "project/git/status", "project/git/files", "thread/list", "thread/search",
    "thread/read", "thread/history", "thread/sync",
    "thread/suggestion/read",
    "thread/fork-options", "thread/fork-status", "workspace/manage-status", "thread/read-state", "thread/read-state-list",
    "model/list", "queue/list", "queue/status",
    "filesystem/access-status", "filesystem/roots", "filesystem/list-directories", "filesystem/list-entries",
    "voice/readiness", "voice/status", "resource/read", "resource/download", "resource/read-cancel", "resource/transfer-list",
    "resource/upload-status", "command/status",
    "notification/status", "notification/events",
  ]
  private static let subscriptionMethods: Set<String> = ["thread/subscribe", "thread/unsubscribe", "notification/register", "notification/unregister", "notification/preferences"]
  private var authenticatedNetworks: [HostDiscoveryNetwork]?
  private(set) var epoch = UUID().uuidString
  private(set) var eventSequence = 0
  #if DEBUG
  private static let devLogger = Logger(subsystem: "com.codexmanage.ios", category: "EasyWorkSession")
  #endif
  private func diagnostic(_ event: String, _ detail: String = "") {
    #if DEBUG
    if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev") { print("EASYWORK_SESSION", event, detail, "epoch", epoch, "confirmed", keyConfirmed) }
    if ProcessInfo.processInfo.arguments.contains("-native-queue-dev") {
      Self.devLogger.notice("\(event, privacy: .public) \(detail, privacy: .public) confirmed=\(self.keyConfirmed)")
    }
    #endif
  }
  init(connection: AgentConnection, credential: EasyWorkCredential) throws {
    #if R2_REAL_DEV_ACCEPTANCE
    try R2Admission.connection(connection)
    try R2Admission.peer(target: credential.binding.target, fingerprint: connection.candidates.first?.peerFingerprint,
      publicKey: credential.binding.desktopPublicKey, endpoints: connection.candidates.map(\.endpoint))
    #endif
    let target = credential.binding.target
    guard target.desktopDeviceId == connection.target.hostId, target.workspaceId == connection.target.workspaceId, target.runtimeId == connection.target.runtimeId, target.account == connection.target.account else { throw HostServiceError.wrongHost }
    guard credential.binding.deviceAuthorization == nil || credential.grantRef != nil else { throw HostServiceError.message("设备授权凭据缺失，请重新配对") }
    self.connection = connection; self.credential = credential; self.target = target
  }
  func devPreviewCandidate() async throws -> AgentConnectionCandidate {
    try await connect()
    guard let candidate = activeCandidateInfo, candidate.transportId == "lan-wss", candidate.peerFingerprint?.count == 64 else {
      throw HostServiceError.message(L10n.tr("开发服务预览需要局域网或 VPN 直连，请切换连接方式。"))
    }
    return candidate
  }
  func updateCandidates(_ candidates: [AgentConnectionCandidate]) {
    connection.candidates = candidates
    if let current = activeCandidateInfo, let replacement = candidates.first(where: { $0.endpoint == current.endpoint && $0.transportId == current.transportId }) {
      activeCandidate = replacement.id; activeCandidateInfo = replacement
    }
  }
  func rememberAuthenticatedCandidates(_ candidates: [AgentConnectionCandidate]) {
    guard candidates.count <= 32, candidates.allSatisfy({ $0.transportId == "lan-wss" }) else { return }
    // Preserve Relay and old direct pins for recovery while bounding address growth.
    updateCandidates(EasyWorkAuthenticatedConnectionCandidates.merging(candidates, into: connection.candidates))
  }
  func connectionMetadata() -> AgentConnectionMetadata {
    AgentConnectionMetadata(title: activeTransportTitle(), updatedConnection: connection, discoveryNetworks: authenticatedNetworks)
  }
  func rememberAuthenticatedNetworks(_ networks: [HostDiscoveryNetwork]?) {
    authenticatedNetworks = networks
  }
  func close() async {
    closed = true
    if let flight = connectionTask { finishConnection(flight.id, .failure(CancellationError())); flight.task.cancel() }
    connectionDeadline?.cancel(); livenessTask?.cancel()
    await disconnect()
  }
  private func disconnect() async {
    diagnostic("disconnect")
    let previous = transport
    for task in readPublishTasks.values { task.cancel() }
    publishingOwners.removeAll()
    receiveTask?.cancel(); receiveTask = nil; transport = nil; crypto = nil; keyConfirmed = false
    threadSyncs.removeAll(); threadSyncOrder.removeAll()
    await eventSignal.signal()
    subagents.clear()
    await previous?.close()
  }
  private func finishConnection(_ id: UUID, _ result: Result<Void, Error>) {
    guard connectionTask?.id == id else { return }
    if connectionWaiters[id, default: 0] > 0 { connectionResults[id] = result }
    connectionTask = nil
    connectionDeadline?.cancel(); connectionDeadline = nil
  }
  private func requireConnectionOwner(_ id: UUID) throws {
    try Task.checkCancellation()
    guard !closed, connectionTask?.id == id else { throw CancellationError() }
  }
  private func expireConnection(_ id: UUID) async {
    guard let flight = connectionTask, flight.id == id else { return }
    finishConnection(id, .failure(URLError(.timedOut)))
    flight.task.cancel()
    await disconnect()
  }
  private func connect(excluding: String? = nil) async throws {
    #if R2_REAL_DEV_ACCEPTANCE
    try R2Admission.connection(connection)
    #endif
    try Task.checkCancellation()
    guard !closed else { throw CancellationError() }
    if transport != nil && keyConfirmed && (excluding == nil || activeCandidate != excluding) { return }
    let id: UUID
    if let flight = connectionTask { id = flight.id }
    else {
      id = UUID()
      let task = Task { [self] in
        do { try await performConnect(excluding: excluding, owner: id); finishConnection(id, .success(())) }
        catch { finishConnection(id, .failure(error)) }
      }
      connectionTask = (id, task)
      connectionDeadline = Task { [weak self] in
        do { try await Task.sleep(for: .seconds(30)) } catch { return }
        await self?.expireConnection(id)
      }
    }
    connectionWaiters[id, default: 0] += 1
    defer {
      connectionWaiters[id, default: 0] -= 1
      if connectionWaiters[id] == 0 { connectionWaiters.removeValue(forKey: id); connectionResults.removeValue(forKey: id) }
    }
    while connectionResults[id] == nil {
      try Task.checkCancellation()
      guard !closed else { throw CancellationError() }
      try await Task.sleep(for: .milliseconds(25))
    }
    try Task.checkCancellation()
    try connectionResults[id]!.get()
  }
  private func performConnect(excluding: String?, owner: UUID) async throws {
    try requireConnectionOwner(owner)
    await disconnect()
    try requireConnectionOwner(owner)
    var candidates = connection.candidates
    let excludedEndpoint = candidates.first { $0.id == excluding }?.endpoint
    if connection.preference == .lanOnly { candidates = candidates.filter { $0.transportId == "lan-wss" } }
    if connection.preference == .relayOnly { candidates = candidates.filter { $0.transportId == "relay-http" } }
    if connection.preference == .automatic { candidates.sort { ($0.transportId == "lan-wss" ? 0 : 1) < ($1.transportId == "lan-wss" ? 0 : 1) } }
    if let excluding { candidates.removeAll { $0.id == excluding } }
    candidates = await EasyWorkPairing.reachableCandidates(candidates, binding: credential.binding)
    try requireConnectionOwner(owner)
    if let excluding { candidates.removeAll { $0.id == excluding || $0.endpoint == excludedEndpoint } }
    try Task.checkCancellation()
    guard !closed else { throw CancellationError() }
    for candidate in candidates {
      do {
        diagnostic("connect", candidate.transportId)
        let binding = candidate.transportId == "relay-http" ? credential.relayBinding ?? credential.binding : credential.binding
        guard binding.target == target else { throw HostServiceError.wrongHost }
        let channel = try EasyWorkEnvelopeCrypto(credential: credential, binding: binding)
        let route = try await AgentTransportRegistry.shared.make(AgentTransportOptions(candidate: candidate, bindingId: binding.bindingId, accessToken: credential.accessToken, grantRef: credential.grantRef, authorizationBinding: binding))
        do { try requireConnectionOwner(owner) } catch { await route.close(); throw error }
        transport = route; crypto = channel; sequence = 1; keyConfirmed = false; lastError = nil; activeCandidate = candidate.id; activeCandidateInfo = candidate; epoch = UUID().uuidString
        let pumpEpoch = epoch
        receiveTask = Task { await self.pump(route, channel: channel, epoch: pumpEpoch) }
        // URLSession can wait inside WebSocket.send while TCP is still connecting.
        // Close that candidate when the whole handshake exceeds its deadline.
        let handshakeDeadline = Task {
          do { try await Task.sleep(for: .seconds(8)) }
          catch { return }
          await self.expireHandshake(pumpEpoch, route: route)
        }
        defer { handshakeDeadline.cancel() }
        try await publish(["kind": "key-confirmation", "value": channel.confirmation])
        let deadline = ContinuousClock.now.advanced(by: .seconds(8))
        while !keyConfirmed && lastError == nil && !closed && ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(25)) }
        try requireConnectionOwner(owner)
        guard keyConfirmed else { throw lastError ?? HostServiceError.unavailable }
        diagnostic("connected", candidate.transportId)
        return
      } catch {
        diagnostic("connect-error", "\((error as NSError).domain):\((error as NSError).code)")
        try requireConnectionOwner(owner)
        let authorizationFailure = (error as? EasyWorkDeviceAuthorizationFailure) ?? (lastError as? EasyWorkDeviceAuthorizationFailure)
        // A cancelled trust challenge is a real failure unless this task/lifecycle was cancelled.
        lastError = (error as? URLError)?.code == .cancelled ? HostServiceError.message(error.localizedDescription) : error
        await disconnect(); if closed || Task.isCancelled { throw CancellationError() }
        if let authorizationFailure { throw authorizationFailure }
      }
    }
    throw lastError ?? HostServiceError.unavailable
  }
  private func expireHandshake(_ attemptEpoch: String, route: any AgentTransport) async {
    guard epoch == attemptEpoch, !closed, !keyConfirmed else { return }
    diagnostic("handshake-timeout", attemptEpoch)
    lastError = HostServiceError.unavailable
    receiveTask?.cancel(); receiveTask = nil; transport = nil; crypto = nil
    subagents.clear()
    await route.close()
  }
  private func expireRequest(_ id: String, epoch requestEpoch: String) async {
    guard epoch == requestEpoch, !closed, pendingRequestIds.contains(id) else { return }
    diagnostic("request-deadline", id)
    if lastError == nil { lastError = URLError(.timedOut) }
    // Interrupt this connection's blocked send as well as response waiting.
    // The normal recovery path retains the original operation and queries writes.
    await disconnect()
  }
  private func publish(_ payload: [String: Any], owner: UUID = UUID(), readOwner: UUID? = nil,
    expectedEpoch: String? = nil) async throws {
    let publishEpoch = expectedEpoch ?? epoch
    // Actor isolation does not keep HTTP requests ordered across send's await.
    // Assign the authenticated sequence only after the preceding send finishes.
    while publishingOwners[publishEpoch] != nil {
      try await Task.sleep(for: .milliseconds(10))
      guard epoch == publishEpoch, !closed else { throw HostServiceError.unavailable }
    }
    try Task.checkCancellation()
    guard !closed else { throw CancellationError() }
    guard epoch == publishEpoch, let transport, let crypto else { throw HostServiceError.unavailable }
    if let readOwner, !readPublishWaiters.contains(readOwner) { throw CancellationError() }
    publishingOwners[publishEpoch] = owner
    defer { if publishingOwners[publishEpoch] == owner { publishingOwners.removeValue(forKey: publishEpoch) } }
    let frame = try crypto.seal(payload, sequence: sequence); sequence += 1
    if let readOwner { sendingReads.insert(readOwner) }
    do { try await transport.send(frame) }
    catch {
      if let readOwner { sendingReads.remove(readOwner) }
      throw error
    }
    if let readOwner { sendingReads.remove(readOwner) }
    #if DEBUG
    if !requestDeadlineFaultConsumed, let request = payload["envelope"] as? [String: Any],
      EasyWorkRequestDeadlineAcceptance.matches(bundleIdentifier: Bundle.main.bundleIdentifier,
        arguments: ProcessInfo.processInfo.arguments, environment: ProcessInfo.processInfo.environment,
        target: target.dictionary, request: request) {
      requestDeadlineFaultConsumed = true
      diagnostic("request-send-callback-held", request["requestId"] as? String ?? "")
      while !closed && epoch == publishEpoch && self.transport != nil {
        try await Task.sleep(for: .milliseconds(25))
      }
      diagnostic("request-send-callback-released", request["requestId"] as? String ?? "")
      throw URLError(.networkConnectionLost)
    }
    #endif
  }
  private func publishRead(_ payload: [String: Any], id: String,
    deadline: ContinuousClock.Instant) async throws {
    // A lower-level callback may ignore cancellation. Bound retained callbacks
    // without turning local backpressure into a shared connection failure.
    guard readPublishTasks.count < 16 else { throw ReadPublishError.capacity }
    let owner = UUID(), publishEpoch = epoch
    let encoded = try easyWorkJSON(payload)
    readPublishWaiters.insert(owner)
    readPublishTasks[owner] = Task { [self] in
      let result: Result<Void, Error>
      do {
        try await publish(easyWorkObject(encoded), owner: owner, readOwner: owner, expectedEpoch: publishEpoch)
        result = .success(())
      } catch { result = .failure(error) }
      if readPublishWaiters.contains(owner) { readPublishResults[owner] = result }
      readPublishTasks.removeValue(forKey: owner)
    }
    defer {
      readPublishWaiters.remove(owner)
      readPublishResults.removeValue(forKey: owner)
      if publishingOwners[publishEpoch] == owner { publishingOwners.removeValue(forKey: publishEpoch) }
      // Do not cancel an in-flight shared transport send on caller retirement.
      // Queued publishers and local post-send work can be cancelled safely.
      if !sendingReads.contains(owner) { readPublishTasks[owner]?.cancel() }
    }
    while true {
      try Task.checkCancellation()
      if responses[id] != nil { return }
      guard !closed else { throw CancellationError() }
      guard epoch == publishEpoch, transport != nil else { throw lastError ?? HostServiceError.unavailable }
      if let lastError { throw lastError }
      if let result = readPublishResults[owner] { try result.get(); return }
      guard ContinuousClock.now < deadline else { throw URLError(.timedOut) }
      try await Task.sleep(for: .milliseconds(25))
    }
  }
  private func pump(_ route: any AgentTransport, channel: EasyWorkEnvelopeCrypto, epoch pumpEpoch: String) async {
    diagnostic("pump-start", pumpEpoch)
    defer { diagnostic("pump-end", "\(pumpEpoch):cancelled=\(Task.isCancelled)") }
    do {
      while !Task.isCancelled {
        let data = try await route.receive()
        try Task.checkCancellation()
        guard epoch == pumpEpoch, !closed else { return }
        let (payload, id) = try channel.open(data)
        let split = id.lastIndex(of: ":")!
        let peerSession = channel.binding.bindingId + ":" + id[..<split]
        let envelopeSequence = Int(id[id.index(after: split)...])!
        let previous = received[peerSession] ?? (highest: 0, sequences: Set<Int>())
        if previous.sequences.contains(envelopeSequence) || envelopeSequence <= previous.highest - 2000 { try await route.acknowledge(data); continue }
        if payload["kind"] as? String == "key-confirmation" {
          guard payload["value"] as? String == channel.confirmation else { throw HostServiceError.wrongHost }
          keyConfirmed = true
        } else if payload["kind"] as? String == "protocol", let message = payload["envelope"] as? [String: Any] {
          guard message["protocolVersion"] as? Int == 2 else { throw HostServiceError.invalidResponse }
          // A durable Relay mailbox can contain frames from the previous connection
          // before this connection's key confirmation. They cannot be applied to the
          // new session; authoritative history repairs any skipped event interval.
          if !keyConfirmed { try await route.acknowledge(data); continue }
          if message["kind"] as? String == "response", let requestId = message["requestId"] as? String, pendingRequestIds.contains(requestId) { diagnostic("response", requestId); responses[requestId] = message }
          else if message["kind"] as? String == "event" {
            let eventTarget = try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(message["target"] ?? [:]))
            guard eventTarget == target else { throw HostServiceError.wrongHost }
            guard let payload = message["payload"] as? [String: Any], let threadId = payload["threadId"] as? String,
              let cursor = EasyWorkEnvelopeCrypto.wireInteger(message["sequence"])
            else { throw HostServiceError.invalidResponse }
            threadSequences[threadId] = max(threadSequences[threadId] ?? 0, cursor)
            await eventSignal.signal()
            subagents.accept(eventJson: String(decoding: try easyWorkJSON(payload), as: UTF8.self))
            events.append(message); eventSequence += 1
            if events.count > 512 { events.removeFirst(events.count - 512) }
          }
        } else { throw HostServiceError.invalidResponse }
        let highest = max(previous.highest, envelopeSequence)
        var sequences = previous.sequences.filter { $0 > highest - 2000 }
        sequences.insert(envelopeSequence)
        received[peerSession] = (highest, sequences)
        lastVerifiedTraffic = ContinuousClock.now
        try await route.acknowledge(data)
      }
    } catch {
      diagnostic("pump-error", "\((error as NSError).domain):\((error as NSError).code)")
      if !Task.isCancelled && !closed && epoch == pumpEpoch {
        lastError = (error as? URLError)?.code == .cancelled ? HostServiceError.message(error.localizedDescription) : error
        keyConfirmed = false; transport = nil; await route.close()
        await eventSignal.signal()
      }
    }
  }
  nonisolated func request(_ method: String, _ params: [String: Any], id: String = UUID().uuidString) async throws -> Data {
    try await requestData(method, params: easyWorkJSON(params), id: id)
  }
  nonisolated static func recoveryExcludedCandidate(_ failedId: String?,
      preference: AgentConnectionPreference, candidates: [AgentConnectionCandidate]) -> String? {
    guard preference == .automatic, let failedId,
      let failed = candidates.first(where: { $0.id == failedId }),
      candidates.contains(where: { $0.id != failedId && $0.endpoint != failed.endpoint }) else { return nil }
    // A single pinned path must remain available for querying the original receipt.
    // Reconnecting still uses the normal target, certificate and device checks.
    return failedId
  }
  nonisolated static func verifiedWriteResult(_ record: [String: Any], target: EasyWorkTarget,
      mobileDeviceId: String, operationId: String, method: String, params: [String: Any]) throws -> Data {
    guard ["completed", "failed"].contains(record["state"] as? String ?? "") else {
      throw HostServiceError.message(L10n.tr("操作结果仍不确定，请查询状态并核对桌面结果。"))
    }
    let expectedHash = HostService.hash(try easyWorkJSON(["target": target.dictionary, "method": method, "params": params]))
    guard record["operationId"] as? String == operationId, record["method"] as? String == method,
      record["deviceId"] as? String == mobileDeviceId, record["requestHash"] as? String == expectedHash,
      let savedTarget = record["target"] as? [String: Any],
      try easyWorkJSON(savedTarget) == easyWorkJSON(target.dictionary),
      let response = record["response"] as? [String: Any], response["protocolVersion"] as? Int == 2,
      response["kind"] as? String == "response", let ok = response["ok"] as? Bool,
      ok == (record["state"] as? String == "completed") else {
      throw HostServiceError.invalidResponse
    }
    guard ok else {
      let error = response["error"] as? [String: Any]
      throw EasyWorkRemoteRejection(code: error?["code"] as? String,
        message: error?["message"] as? String ?? L10n.tr("请求被拒绝"))
    }
    return try easyWorkJSON(response["result"] ?? NSNull())
  }
  private func verifySessionLiveness(epoch expected: String) async -> Bool {
    if livenessTask == nil {
      livenessResult = nil
      livenessTask = Task { [self] in
        let began = ContinuousClock.now
        do { _ = try await requestData("runtime/context", params: easyWorkJSON([:]), id: UUID().uuidString, livenessCheck: true) }
        catch { /* Authenticated error responses also prove transport liveness. */ }
        livenessResult = epoch == expected && lastError == nil && lastVerifiedTraffic.map { $0 > began } == true
        livenessTask = nil
      }
    }
    while livenessTask != nil {
      if Task.isCancelled || closed || epoch != expected { return false }
      do { try await Task.sleep(for: .milliseconds(25)) } catch { return false }
    }
    return epoch == expected && livenessResult == true
  }
  private func requestData(_ method: String, params: Data, id: String, livenessCheck: Bool = false) async throws -> Data {
    #if R2_REAL_DEV_ACCEPTANCE
    try R2Admission.request(method, id: id, params: params, connection: connection)
    #endif
    diagnostic("request", method)
    guard pendingRequestIds.insert(id).inserted else { throw HostServiceError.message(L10n.tr("同一操作正在等待 Host 回执")) }
    responses.removeValue(forKey: id)
    defer { pendingRequestIds.remove(id); responses.removeValue(forKey: id) }
    #if DEBUG
    if ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"), ProcessInfo.processInfo.arguments.contains("-ui-testing"), method == "turn/start" {
      await EasyWorkDEVCommands.shared.save(target: target, command: EasyWorkDEVCommand(method: method, params: params, id: id))
    }
    #endif
    let request: [String: Any] = ["protocolVersion": 2, "kind": "request", "requestId": id, "target": target.dictionary, "method": method, "params": try easyWorkObject(params)]
    for attempt in 0..<2 {
      try await connect()
      let route = activeCandidate
      let requestEpoch = epoch
      let requestStarted = ContinuousClock.now
      let clock = ContinuousClock()
      let deadline = clock.now.advanced(by: .seconds(livenessCheck ? 5 : 30))
      let isRead = Self.readMethods.contains(method) || Self.subscriptionMethods.contains(method)
      // Writes retain their original bounded send/reconciliation path. A read
      // deadline retires only that waiter; liveness decides shared recovery.
      let requestDeadline: Task<Void, Never>? = isRead ? nil : Task {
        do { try await clock.sleep(until: deadline) }
        catch { return }
        await self.expireRequest(id, epoch: requestEpoch)
      }
      defer { requestDeadline?.cancel() }
      do {
        #if R2_REAL_DEV_ACCEPTANCE
        try R2Admission.request(method, id: id, params: params, connection: connection)
        #endif
        do {
          if isRead { try await publishRead(["kind": "protocol", "envelope": request], id: id, deadline: deadline) }
          else { try await publish(["kind": "protocol", "envelope": request]) }
        } catch {
          // A verified response may precede send's completion callback. Closing
          // that blocked send must not discard the response or republish it.
          guard responses[id] != nil else { throw error }
        }
        try Task.checkCancellation()
        while responses[id] == nil && lastError == nil && !closed && clock.now < deadline { try await Task.sleep(for: .milliseconds(25)) }
        guard !closed else { throw CancellationError() }
        if let response = responses.removeValue(forKey: id) {
          guard response["ok"] as? Bool == true else {
            let error = response["error"] as? [String: Any]
            let message = error?["message"] as? String ?? L10n.tr("请求被拒绝")
            if error?["code"] as? String == "stale_read_position" { throw HostServiceError.rejected(409, message) }
            // An authenticated absence is the explicit query result that lets
            // the file UI offer uploading the same saved intent again.
            if method == "resource/upload-status", ["NOT_FOUND", "TRANSFER_NOT_FOUND", "RESOURCE_NOT_FOUND"].contains((error?["code"] as? String ?? "").uppercased().replacingOccurrences(of: "-", with: "_")) {
              throw HostServiceError.rejected(404, message)
            }
            throw EasyWorkRemoteRejection(code: error?["code"] as? String, message: message)
          }
          #if DEBUG
          // Drop only an authenticated read result in the dedicated acceptance App.
          // The Host has performed the original read; normal recovery must keep its identity.
          if droppedReadRequestId == nil,
            ["project/git/status", "project/git/files", "resource/resolve-document-link"].contains(method),
            Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
            ProcessInfo.processInfo.arguments.contains("-read-recovery-acceptance"),
            ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"),
            ProcessInfo.processInfo.environment["EASYWORK_DROP_READ_COMPLETION_METHOD"] == method,
            let scopeId = ProcessInfo.processInfo.environment["EASYWORK_DROP_READ_SCOPE_ID"], !scopeId.isEmpty,
            (try easyWorkObject(params))[method == "resource/resolve-document-link" ? "threadId" : "projectId"] as? String == scopeId,
            ProcessInfo.processInfo.environment["EASYWORK_DROP_READ_TARGET_SHA256"] == HostService.hash(try easyWorkJSON(target.dictionary)) {
            droppedReadRequestId = id
            diagnostic("read-completion-dropped", "\(method):\(id)")
            throw URLError(.networkConnectionLost)
          }
          if droppedReadRequestId == id,
            ProcessInfo.processInfo.environment["EASYWORK_DROP_READ_COMPLETION_METHOD"] == method {
            diagnostic("read-completion-recovered", "\(method):\(id)")
          }
          if await EasyWorkFilePrepareReconciliationFault.shared.consume(target: target, method: method, id: id) {
            throw URLError(.networkConnectionLost)
          }
          // The authentic completion has arrived; fail only its local delivery.
          // Crypto verification, acknowledgement and the Desktop receipt stay intact.
          if !fileCompletionFaultConsumed, method == "resource/upload-complete", id.hasPrefix("file-complete-"),
            Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance",
            ProcessInfo.processInfo.arguments.contains("-ui-testing"),
            ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"),
            ProcessInfo.processInfo.environment["EASYWORK_DROP_FILE_COMPLETION_ONCE"] == "1" {
            fileCompletionFaultConsumed = true
            throw HostServiceError.message("验收注入：完成回执未交付，原文件已保留，请查询结果。")
          }
          #endif
          return try easyWorkJSON(response["result"] ?? NSNull())
        }
        diagnostic("request-timeout", method)
        throw lastError ?? URLError(.timedOut)
      } catch {
        diagnostic("request-error", "\(method):\((error as NSError).domain):\((error as NSError).code)")
        if closed || Task.isCancelled { throw CancellationError() }
        if livenessCheck { throw error }
        if error is ReadPublishError { throw URLError(.timedOut) }
        // Management recovery validates the saved original in its adapter and never republishes.
        if method == "thread/manage" || method == "workspace/manage" || method == "thread/acknowledge-read" { throw error }
        if let failure = (error as? EasyWorkDeviceAuthorizationFailure) ?? (lastError as? EasyWorkDeviceAuthorizationFailure) { throw failure }
        if error is EasyWorkRemoteRejection { throw error }
        if case HostServiceError.message = error { throw error }
        if method == "resource/upload-status", case HostServiceError.rejected(404, _) = error { throw error }
        if case HostServiceError.invalidResponse = error { throw error }
        if case HostServiceError.wrongHost = error { throw error }
        // Only an actual timeout is eligible for liveness classification.
        // Authenticated rejection and protocol errors keep their original meaning.
        if (error as? URLError)?.code == .timedOut, epoch == requestEpoch, lastError == nil {
          if lastVerifiedTraffic.map({ $0 > requestStarted }) == true { throw error }
          if await verifySessionLiveness(epoch: requestEpoch) { throw error }
          try Task.checkCancellation()
          guard !closed else { throw CancellationError() }
        }
        // A Relay can stay online while the Host restarts. A request timeout must
        // invalidate the old handshake even when the user selected only one path.
        // The retry keeps the original operation ID; Host receipts decide whether
        // the operation has already run or requires explicit status reconciliation.
        if epoch == requestEpoch { await disconnect() }
        if attempt == 0 {
          try await connect(excluding: Self.recoveryExcludedCandidate(route,
            preference: connection.preference, candidates: connection.candidates))
          if !Self.readMethods.contains(method) && !Self.subscriptionMethods.contains(method) {
            let values = try easyWorkObject(params)
            let operationId = values["clientOperationId"] as? String ?? values["clientRequestId"] as? String ?? values["operationId"] as? String ?? values["guidanceId"] as? String ?? id
            let status = try await self.request("command/status", ["commandId": operationId])
            let value = try JSONSerialization.jsonObject(with: status, options: [.fragmentsAllowed])
            if ["turn/start", "turn/steer", "plan/action"].contains(method) {
              // Reply writes are immutable. Empty/terminal broker status is not
              // an exact not-written proof and cannot authorize another publish.
              throw HostServiceError.message(L10n.tr("原消息结果待核对，仅查询原操作 ID；没有重新提交。"))
            }
            guard let record = value as? [String: Any] else {
              throw HostServiceError.message(L10n.tr("操作结果仍不确定，请查询状态并核对桌面结果。"))
            }
            // Adopt only the authenticated original receipt. A missing or
            // uncertain result never authorizes another write publication.
            let recovered = try Self.verifiedWriteResult(record, target: target, mobileDeviceId: mobileDeviceId,
              operationId: operationId, method: method, params: values)
            #if DEBUG
            await EasyWorkFilePrepareReconciliationFault.shared.recovered(target: target, operationId: operationId)
            #endif
            return recovered
          }
        } else { throw error }
      }
    }
    throw HostServiceError.unavailable
  }
  func synchronizedThread(_ threadId: String) async throws -> Data {
    while syncingThreads.contains(threadId) { try await Task.sleep(for: .milliseconds(25)) }
    try Task.checkCancellation()
    syncingThreads.insert(threadId); defer { syncingThreads.remove(threadId) }
    try await connect()
    var readEpoch = epoch
    var previous = threadSyncs[threadId] ?? EasyWorkThreadSync()
    var parameters = try easyWorkJSON(previous.parameters(threadId: threadId))
    var result = try easyWorkObject(await requestData("thread/sync", params: parameters, id: UUID().uuidString))
    if readEpoch != epoch {
      previous = EasyWorkThreadSync()
      readEpoch = epoch
      parameters = try easyWorkJSON(previous.parameters(threadId: threadId))
      result = try easyWorkObject(await requestData("thread/sync", params: parameters, id: UUID().uuidString))
    }
    guard readEpoch == epoch else { throw HostServiceError.unavailable }
    let next: (EasyWorkThreadSync, Data)
    do { next = try previous.merging(result, threadId: threadId) }
    catch HostServiceError.wrongHost { throw HostServiceError.wrongHost }
    catch {
      // Only a read is retried. Invalid/missing bases never partially update a cache or replay input.
      let empty = EasyWorkThreadSync()
      readEpoch = epoch
      parameters = try easyWorkJSON(empty.parameters(threadId: threadId))
      result = try easyWorkObject(await requestData("thread/sync", params: parameters, id: UUID().uuidString))
      next = try empty.merging(result, threadId: threadId)
    }
    try Task.checkCancellation()
    guard !closed else { throw CancellationError() }
    // No await between live-connection validation and cache publication on this actor.
    guard readEpoch == epoch, transport != nil, keyConfirmed else { throw HostServiceError.unavailable }
    threadSyncs[threadId] = next.0
    threadSyncOrder.removeAll { $0 == threadId }; threadSyncOrder.append(threadId)
    while threadSyncOrder.count > 4 { threadSyncs.removeValue(forKey: threadSyncOrder.removeFirst()) }
    return next.1
  }
  func pollEvents(threadId: String, after: Int) async throws -> Int {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(12))
    repeat {
      try Task.checkCancellation()
      guard !closed else { throw CancellationError() }
      let observed = await eventSignal.current()
      if transport == nil || lastError != nil { try await connect() }
      for previous in Array(subscribedThreads) where previous != threadId {
        _ = try await request("thread/unsubscribe", ["threadId": previous])
        try Task.checkCancellation()
        subscribedThreads.remove(previous)
        subscriptions.removeValue(forKey: previous)
        threadSequences.removeValue(forKey: previous)
      }
      if subscriptions[threadId] != epoch {
        subscribedThreads.insert(threadId)
        _ = try await request("thread/subscribe", ["threadId": threadId, "afterSequence": after])
        try Task.checkCancellation()
        subscriptions[threadId] = epoch
        threadSequences[threadId] = max(after, threadSequences[threadId] ?? 0)
      }
      if (threadSequences[threadId] ?? after) > after { break }
      try await eventSignal.wait(after: observed, for: clock.now.duration(to: deadline))
    } while clock.now < deadline
    return threadSequences[threadId] ?? after
  }
  func activeTransportTitle() -> String { activeCandidateInfo?.transportId == "lan-wss" ? (activeCandidateInfo?.networkKind == .vpn ? "VPN" : L10n.tr("直连")) : L10n.tr("中继") }
  #if DEBUG
  func replay(_ command: EasyWorkDEVCommand) async throws -> Data { try await requestData(command.method, params: command.params, id: command.id) }
  #endif
}
#if DEBUG
struct EasyWorkDEVCommand: Sendable { let method: String; let params: Data; let id: String }
actor EasyWorkDEVCommands {
  static let shared = EasyWorkDEVCommands()
  private var commands: [Data: EasyWorkDEVCommand] = [:]
  func save(target: EasyWorkTarget, command: EasyWorkDEVCommand) { if let key = try? easyWorkJSON(target.dictionary) { commands[key] = command } }
  func last(target: EasyWorkTarget) throws -> EasyWorkDEVCommand { guard let command = commands[try easyWorkJSON(target.dictionary)] else { throw HostServiceError.message(L10n.tr("尚未记录当前目标的实际发送操作")) }; return command }
}
#endif
