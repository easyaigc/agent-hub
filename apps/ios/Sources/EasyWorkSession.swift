import Foundation
import os

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
        headers = try await EasyWorkDeviceAuthorization.shared.headers(ref: ref, binding: binding, method: "GET", url: url)
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
/// Owns request correlation, encryption and recovery; transports only carry frames.
actor EasyWorkRemoteSession {
  let target: EasyWorkTarget
  private(set) var connection: AgentConnection
  private let credential: EasyWorkCredential
  private var transport: (any AgentTransport)?
  private var crypto: EasyWorkEnvelopeCrypto?
  private var sequence = 1
  private var publishingEpochs: Set<String> = []
  private var keyConfirmed = false
  private var receiveTask: Task<Void, Never>?
  private var responses: [String: [String: Any]] = [:]
  private var pendingRequestIds: Set<String> = []
  private var events: [[String: Any]] = []
  private var threadSequences: [String: Int] = [:]
  private var subscriptions: [String: String] = [:]
  private var threadSyncs: [String: EasyWorkThreadSync] = [:]
  private var syncingThreads: Set<String> = []
  private var threadSyncOrder: [String] = []
  private var received: [String: (highest: Int, sequences: Set<Int>)] = [:]
  private var activeCandidate: String?
  private var activeCandidateInfo: AgentConnectionCandidate?
  // Preserve transport error types so expected reconnects remain nonmodal in the UI.
  private var lastError: Error?
  private var connecting = false
  private var closed = false
  private static let readMethods: Set<String> = [
    "runtime/context", "runtime/summary", "project/list", "thread/list", "thread/search",
    "thread/read", "thread/history", "thread/sync", "thread/subscribe", "thread/unsubscribe",
    "model/list", "queue/list", "queue/status",
    "filesystem/access-status", "filesystem/roots", "filesystem/list-directories", "filesystem/list-entries",
    "voice/readiness", "resource/read", "resource/download", "resource/resolve", "resource/transfer-list",
    "resource/upload-status", "command/status",
    "notification/status", "notification/events",
  ]
  private static let subscriptionMethods: Set<String> = ["notification/register", "notification/unregister", "notification/preferences"]
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
    let target = credential.binding.target
    guard target.desktopDeviceId == connection.target.hostId, target.workspaceId == connection.target.workspaceId, target.runtimeId == connection.target.runtimeId, target.account == connection.target.account else { throw HostServiceError.wrongHost }
    guard credential.binding.deviceAuthorization == nil || credential.grantRef != nil else { throw HostServiceError.message("设备授权凭据缺失，请重新配对") }
    self.connection = connection; self.credential = credential; self.target = target
  }
  func updateCandidates(_ candidates: [AgentConnectionCandidate]) {
    connection.candidates = candidates
    if let current = activeCandidateInfo, let replacement = candidates.first(where: { $0.endpoint == current.endpoint && $0.transportId == current.transportId }) {
      activeCandidate = replacement.id; activeCandidateInfo = replacement
    }
  }
  func close() async { closed = true; await disconnect() }
  private func disconnect() async {
    diagnostic("disconnect")
    let previous = transport
    receiveTask?.cancel(); receiveTask = nil; transport = nil; crypto = nil; keyConfirmed = false
    threadSyncs.removeAll(); threadSyncOrder.removeAll()
    await previous?.close()
  }
  private func connect(excluding: String? = nil) async throws {
    while connecting { try await Task.sleep(for: .milliseconds(25)) }
    try Task.checkCancellation()
    guard !closed else { throw CancellationError() }
    if transport != nil && keyConfirmed && (excluding == nil || activeCandidate != excluding) { return }
    connecting = true; defer { connecting = false }
    await disconnect()
    var candidates = connection.candidates
    let excludedEndpoint = candidates.first { $0.id == excluding }?.endpoint
    if connection.preference == .lanOnly { candidates = candidates.filter { $0.transportId == "lan-wss" } }
    if connection.preference == .relayOnly { candidates = candidates.filter { $0.transportId == "relay-http" } }
    if connection.preference == .automatic { candidates.sort { ($0.transportId == "lan-wss" ? 0 : 1) < ($1.transportId == "lan-wss" ? 0 : 1) } }
    if let excluding { candidates.removeAll { $0.id == excluding } }
    candidates = await EasyWorkPairing.reachableCandidates(candidates, binding: credential.binding)
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
        let deadline = Date().addingTimeInterval(8)
        while !keyConfirmed && lastError == nil && !closed && Date() < deadline { try await Task.sleep(for: .milliseconds(25)) }
        guard !closed else { throw CancellationError() }
        guard keyConfirmed else { throw lastError ?? HostServiceError.unavailable }
        diagnostic("connected", candidate.transportId)
        return
      } catch {
        diagnostic("connect-error", "\((error as NSError).domain):\((error as NSError).code)")
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
    await route.close()
  }
  private func publish(_ payload: [String: Any]) async throws {
    let publishEpoch = epoch
    // Actor isolation does not keep HTTP requests ordered across send's await.
    // Assign the authenticated sequence only after the preceding send finishes.
    while publishingEpochs.contains(publishEpoch) {
      try await Task.sleep(for: .milliseconds(10))
      guard epoch == publishEpoch, !closed else { throw HostServiceError.unavailable }
    }
    try Task.checkCancellation()
    guard !closed else { throw CancellationError() }
    guard epoch == publishEpoch, let transport, let crypto else { throw HostServiceError.unavailable }
    publishingEpochs.insert(publishEpoch)
    defer { publishingEpochs.remove(publishEpoch) }
    let frame = try crypto.seal(payload, sequence: sequence); sequence += 1
    try await transport.send(frame)
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
            guard let payload = message["payload"] as? [String: Any], let threadId = payload["threadId"] as? String, let cursor = message["sequence"] as? Int, cursor >= 0 else { throw HostServiceError.invalidResponse }
            threadSequences[threadId] = max(threadSequences[threadId] ?? 0, cursor)
            events.append(message); eventSequence += 1
            if events.count > 512 { events.removeFirst(events.count - 512) }
          }
        } else { throw HostServiceError.invalidResponse }
        let highest = max(previous.highest, envelopeSequence)
        var sequences = previous.sequences.filter { $0 > highest - 2000 }
        sequences.insert(envelopeSequence)
        received[peerSession] = (highest, sequences)
        try await route.acknowledge(data)
      }
    } catch {
      diagnostic("pump-error", "\((error as NSError).domain):\((error as NSError).code)")
      if !Task.isCancelled && !closed && epoch == pumpEpoch {
        lastError = (error as? URLError)?.code == .cancelled ? HostServiceError.message(error.localizedDescription) : error
        keyConfirmed = false; transport = nil; await route.close()
      }
    }
  }
  nonisolated func request(_ method: String, _ params: [String: Any], id: String = UUID().uuidString) async throws -> Data {
    try await requestData(method, params: easyWorkJSON(params), id: id)
  }
  private func requestData(_ method: String, params: Data, id: String) async throws -> Data {
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
      do {
        try await publish(["kind": "protocol", "envelope": request])
        let deadline = Date().addingTimeInterval(30)
        while responses[id] == nil && lastError == nil && !closed && Date() < deadline { try await Task.sleep(for: .milliseconds(25)) }
        guard !closed else { throw CancellationError() }
        if let response = responses.removeValue(forKey: id) {
          guard response["ok"] as? Bool == true else { let error = response["error"] as? [String: Any]; throw HostServiceError.message(error?["message"] as? String ?? L10n.tr("请求被拒绝")) }
          return try easyWorkJSON(response["result"] ?? NSNull())
        }
        diagnostic("request-timeout", method)
        throw lastError ?? HostServiceError.unavailable
      } catch {
        diagnostic("request-error", "\(method):\((error as NSError).domain):\((error as NSError).code)")
        if closed || Task.isCancelled { throw CancellationError() }
        if let failure = (error as? EasyWorkDeviceAuthorizationFailure) ?? (lastError as? EasyWorkDeviceAuthorizationFailure) { throw failure }
        if case HostServiceError.message = error { throw error }
        // A Relay can stay online while the Host restarts. A request timeout must
        // invalidate the old handshake even when the user selected only one path.
        // The retry keeps the original operation ID; Host receipts decide whether
        // the operation has already run or requires explicit status reconciliation.
        if epoch == requestEpoch { await disconnect() }
        if attempt == 0 {
          if connection.preference == .automatic { try await connect(excluding: route) }
          else { try await connect() }
          if !Self.readMethods.contains(method) && !Self.subscriptionMethods.contains(method) {
            let values = try easyWorkObject(params)
            let operationId = values["clientRequestId"] as? String ?? values["operationId"] as? String ?? values["guidanceId"] as? String ?? id
            let status = try await self.request("command/status", ["commandId": operationId])
            let value = try JSONSerialization.jsonObject(with: status, options: [.fragmentsAllowed])
            if ["turn/start", "turn/steer", "plan/action"].contains(method) {
              // Reply writes are immutable. Empty/terminal broker status is not
              // an exact not-written proof and cannot authorize another publish.
              throw HostServiceError.message(L10n.tr("原消息结果待核对，仅查询原操作 ID；没有重新提交。"))
            }
            if let record = value as? [String: Any], ["submitting", "uncertain"].contains(record["state"] as? String ?? "") {
              throw HostServiceError.message(L10n.tr("操作结果仍不确定，请查询状态并核对桌面结果。"))
            }
            // Retry with the same contents after reconciliation. The Host still
            // checks the payload hash and returns its original canonical receipt.
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
    guard readEpoch == epoch else { throw HostServiceError.unavailable }
    threadSyncs[threadId] = next.0
    threadSyncOrder.removeAll { $0 == threadId }; threadSyncOrder.append(threadId)
    while threadSyncOrder.count > 4 { threadSyncs.removeValue(forKey: threadSyncOrder.removeFirst()) }
    return next.1
  }
  func pollEvents(threadId: String, after: Int) async throws -> Int {
    let deadline = Date().addingTimeInterval(12)
    repeat {
      try Task.checkCancellation()
      if transport == nil || lastError != nil { try await connect() }
      if subscriptions[threadId] != epoch {
        _ = try await request("thread/subscribe", ["threadId": threadId, "afterSequence": after])
        subscriptions[threadId] = epoch
        threadSequences[threadId] = max(after, threadSequences[threadId] ?? 0)
      }
      if (threadSequences[threadId] ?? after) > after { break }
      try await Task.sleep(for: .milliseconds(100))
    } while Date() < deadline
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
