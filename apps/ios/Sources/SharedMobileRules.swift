import Foundation
import MobileCore

// Maps platform DTOs only. The identity and composer rules live in mobile-core.
enum SharedMobileRules {
  static func scope(_ target: AgentRuntimeTarget) -> TargetScope {
    TargetScope(agentId: target.agentId, accountAuthority: target.account?.authority, accountSubject: target.account?.subject,
      hostId: target.hostId, workspaceId: target.workspaceId, runtimeId: target.runtimeId)
  }
  static func machineCacheKey(_ target: AgentRuntimeTarget, threadId: String?, projectId: String?) -> String {
    RuntimeIdentity.shared.cacheKey(clientId: target.hostId, target: scope(target), threadId: threadId, projectId: projectId)
  }
  static func machineReadingKey(_ target: AgentRuntimeTarget, threadId: String?) -> String {
    RuntimeIdentity.shared.readingKey(target: scope(target), threadId: threadId)
  }
  static func preferredRuntimeKey(_ canonical: String, candidates: [String], selectedLegacy: String? = nil) -> String {
    RuntimeIdentity.shared.preferredKey(canonical: canonical, candidates: candidates, selectedLegacy: selectedLegacy)
  }
  static func timelineMessage(_ message: ChatMessage) -> TimelineMessage {
    TimelineMessage(id: message.id, role: message.role, text: message.text, kind: message.kind, status: message.status,
      runId: message.runId, replyPhase: message.replyPhase, replyMessageId: message.replyMessageId)
  }
  static func toolSummary(_ messages: [ChatMessage]) -> String { ToolPresentation.shared.summary(messages: messages.map(timelineMessage)) }
  static func toolTitle(_ message: ChatMessage, single: Bool) -> String { ToolPresentation.shared.title(message: timelineMessage(message), single: single) }
  static func toolStyle(_ kind: String?) -> ToolPresentationStyle { ToolPresentation.shared.style(kind: kind) }
  static func toolStatus(_ status: String?) -> String { ToolPresentation.shared.status(status: status) }
  static func compactionLabel(_ status: String?, live: Bool) -> String { ToolPresentation.shared.compaction(status: status, live: live) }
  static func cacheKey(_ client: Client, threadId: String?, projectId: String?, workspace: WorkspaceSnapshot? = nil) -> String {
    let target = client.agentConnection?.target ?? workspace?.readTarget ?? client.codexDirect?.target ?? (client.isDemo ? nil : AgentRuntimeTarget(
      agentId: workspace?.clientType?.id ?? "codex", hostId: client.id, workspaceId: client.id,
      runtimeId: workspace?.connectionMode ?? "unknown"))
    let scope = target.map { target in
      TargetScope(agentId: target.agentId, accountAuthority: target.account?.authority,
        accountSubject: target.account?.subject, hostId: target.hostId,
        workspaceId: target.workspaceId, runtimeId: target.runtimeId)
    }
    return MobileRules.shared.cacheKey(clientId: client.id, target: scope, threadId: threadId, projectId: projectId)
  }

  static func composer(stopping: Bool, running: Bool, sending: Bool, creationPending: Bool,
    draft: Draft, canSend: Bool, canStop: Bool, canRunAgain: Bool, runtime: ThreadRuntime?,
    messages: [ChatMessage]) -> (action: ComposerAction, enabled: Bool) {
    let input = ComposerInput(stopping: stopping,
      running: running, sending: sending, creationPending: creationPending, text: draft.text,
      attachmentCount: Int32(draft.attachments.count), canSend: canSend, canStop: canStop,
      canRunAgain: canRunAgain, runtimeState: runtime?.state,
      lastReceiptState: runtime?.receipts.last?.state, messages: [])
    let populated = MobileRules.shared.needsRecoveryHistory(input: input)
      ? input.withMessages(messages: messages.map { ComposerMessage(role: $0.role, status: $0.status) }) : input
    let result = MobileRules.shared.composer(input: populated)
    return (ComposerAction(rawValue: result.action) ?? .busy, result.enabled)
  }

  static func runAgainText(restart: Bool) -> String { MobileRules.shared.runAgainText(restart: restart) }

  static func timeline(_ messages: [ChatMessage], limit: Int, activeRunId: String?, running: Bool) -> TimelineProjection {
    MessageProjection.shared.project(messages: messages.map {
      TimelineMessage(id: $0.id, role: $0.role, text: $0.text, kind: $0.kind, status: $0.status,
        runId: $0.runId, replyPhase: $0.replyPhase, replyMessageId: $0.replyMessageId)
    }, messageLimit: Int32(limit), activeRunId: activeRunId, running: running)
  }

  static func noticeIndices(_ receipts: [DeliveryReceipt], dismissed: Set<String>, pendingId: String?) -> [Int] {
    SessionPolicies.shared.noticeIndices(receipts: receipts.map {
      ReceiptState(id: $0.id, state: $0.state, supersededByTurnId: $0.supersededByTurnId)
    }, dismissed: Array(dismissed), pendingId: pendingId).map { $0.intValue }
  }
}

// Platform tasks retain networking and credentials; Kotlin owns one delivery and cancellation.
private final class HistoryCancellation: NSObject, MobileCancellation, @unchecked Sendable {
  private let lock = NSLock()
  private var task: Task<Void, Never>?
  private var request: MobileCancellation?
  private var cancelled = false
  private var finished = false
  func install(_ task: Task<Void, Never>) {
    lock.lock(); if !finished { self.task = task }; let cancel = cancelled; lock.unlock()
    if cancel { task.cancel() }
  }
  func install(_ request: MobileCancellation) {
    lock.lock(); if !finished { self.request = request }; let cancel = cancelled; lock.unlock()
    if cancel { request.cancel() }
  }
  func clear() { lock.lock(); finished = true; task = nil; request = nil; lock.unlock() }
  func cancel() {
    lock.lock(); cancelled = true; let task = task; let request = request; lock.unlock()
    task?.cancel(); request?.cancel()
  }
}

private final class NativeHistoryGateway: NSObject, MobileHistoryGateway {
  let host: Client
  // The shared layer owns delivery/fencing, but a message alone cannot retain
  // URLSession's timeout/offline/cancellation or protocol-error semantics. Keep
  // provenance on this one request's native gateway, never in a shared cache.
  @MainActor private(set) var nativeFailure: Error?
  init(host: Client) { self.host = host }
  func read(targetKey: String, threadId: String, cursor: String?, completion: @escaping (HistoryReply) -> Void) -> MobileCancellation {
    let token = HistoryCancellation()
    let task = Task { @MainActor [host] in
      defer { token.clear() }
      do {
        try Task.checkCancellation()
        let value = try await AgentClients.resolve(host).history(host, threadId: threadId, cursor: cursor)
        try Task.checkCancellation()
        guard value.hostId == host.id, value.threadId == threadId else { throw HostServiceError.wrongHost }
        if let expected = host.agentConnection?.target ?? host.readingTarget,
          let received = value.readTarget, expected != received { throw HostServiceError.wrongHost }
        let json = String(decoding: try JSONEncoder().encode(value), as: UTF8.self)
        completion(HistoryReply(targetKey: targetKey, json: json, failure: nil))
      } catch {
        self.nativeFailure = error
        let code: String
        let status: KotlinInt?
        if error is CancellationError || (error as? URLError)?.code == .cancelled {
          code = "cancelled"; status = nil
        } else if case HostServiceError.wrongHost = error { code = "wrong-target"; status = nil }
        else if case HostServiceError.rejected(let value, _) = error { code = "rejected"; status = KotlinInt(int: Int32(value)) }
        else { code = "transport"; status = nil }
        completion(HistoryReply(targetKey: targetKey, json: nil,
          failure: GatewayFailure(code: code, message: error.localizedDescription, status: status)))
      }
    }
    token.install(task)
    return token
  }
}

extension SharedMobileRules {
  @MainActor static func history(_ host: Client, targetKey: String, threadId: String, cursor: String?) async throws -> HistorySnapshot {
    let token = HistoryCancellation()
    let gateway = NativeHistoryGateway(host: host)
    return try await withTaskCancellationHandler {
      try Task.checkCancellation()
      return try await withCheckedThrowingContinuation { continuation in
        let request = HistoryRequests.shared.start(gateway: gateway,
          targetKey: targetKey, threadId: threadId, cursor: cursor) { reply in
          Task { @MainActor in
            token.clear()
            if let failure = reply.failure {
              switch failure.code {
              case "cancelled": continuation.resume(throwing: CancellationError())
              case "wrong-target": continuation.resume(throwing: HostServiceError.wrongHost)
              case "rejected": continuation.resume(throwing: HostServiceError.rejected(Int(failure.status?.intValue ?? 0), failure.message))
              default: continuation.resume(throwing: gateway.nativeFailure ?? HostServiceError.message(failure.message))
              }
            } else if let json = reply.json {
              do { continuation.resume(returning: try JSONDecoder().decode(HistorySnapshot.self, from: Data(json.utf8))) }
              catch { continuation.resume(throwing: error) }
            } else { continuation.resume(throwing: HostServiceError.invalidResponse) }
          }
        }
        token.install(request)
      }
    } onCancel: { token.cancel() }
  }
}

extension SharedMobileRules {
  static func validateDomain(_ decoder: Decoder, definition: String) throws {
    let raw = try AgentJSONValue(from: decoder)
    let json = String(decoding: try JSONEncoder().encode(raw), as: UTF8.self)
    if let reason = DomainContract.shared.validate(json: json, definition: definition) {
      throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: reason))
    }
  }
}

extension SharedMobileRules {
  // Runs once per upgraded state. Conflicting old entries remain preserved, and
  // future identities never import a previous account's legacy aliases again.
  static func migrateCache(_ saved: inout SavedSession) -> Set<String> {
    guard saved.mobileCacheVersion != 2 else { return [] }
    var dirty = Set<String>()
    for host in saved.clients {
      // Codex uses non-destructive machine aliases. Never rename its captured
      // outbox/creation keys while migrating the other providers' old caches.
      let target = host.agentConnection?.target ?? saved.workspaces[host.id]?.readTarget ?? host.codexDirect?.target
      if !host.isDemo && (target?.agentId ?? "codex") == "codex" { continue }
      let prefix = host.id + ":"
      let keys = Set(saved.history.keys).union(saved.drafts.keys)
        .union(saved.runtimes?.keys ?? [:].keys).union(saved.outbox?.keys ?? [:].keys)
        .union(saved.desktopCreations?.keys ?? [:].keys).union(saved.detachedDesktopCreations?.keys ?? [:].keys)
        .union(saved.dismissedReceiptFailures?.keys ?? [:].keys).union(saved.agentEventCursors?.keys ?? [:].keys)
      for old in keys where old.hasPrefix(prefix) {
        let suffix = String(old.dropFirst(prefix.count))
        let draft = suffix.hasPrefix("new:")
        let project = draft ? String(suffix.dropFirst(4)) : "none"
        let key = cacheKey(host, threadId: draft ? nil : suffix,
          projectId: project == "none" ? nil : project, workspace: saved.workspaces[host.id])
        guard key != old else { continue }
        if saved.history[key] == nil, let value = saved.history.removeValue(forKey: old) {
          saved.history[key] = value; dirty.insert(key)
        }
        if saved.drafts[key] == nil { saved.drafts[key] = saved.drafts.removeValue(forKey: old) }
        if saved.runtimes?[key] == nil { let value = saved.runtimes?.removeValue(forKey: old); saved.runtimes?[key] = value }
        if saved.outbox?[key] == nil { let value = saved.outbox?.removeValue(forKey: old); saved.outbox?[key] = value }
        if saved.desktopCreations?[key] == nil { let value = saved.desktopCreations?.removeValue(forKey: old); saved.desktopCreations?[key] = value }
        if saved.detachedDesktopCreations?[key] == nil { let value = saved.detachedDesktopCreations?.removeValue(forKey: old); saved.detachedDesktopCreations?[key] = value }
        if saved.dismissedReceiptFailures?[key] == nil { let value = saved.dismissedReceiptFailures?.removeValue(forKey: old); saved.dismissedReceiptFailures?[key] = value }
        if saved.agentEventCursors?[key] == nil { let value = saved.agentEventCursors?.removeValue(forKey: old); saved.agentEventCursors?[key] = value }
      }
      let oldPreference = "generation:" + host.id + ":new:none"
      let preference = "generation:" + cacheKey(host, threadId: nil, projectId: nil, workspace: saved.workspaces[host.id])
      if preference != oldPreference, saved.generationPreferences?[preference] == nil {
        let value = saved.generationPreferences?.removeValue(forKey: oldPreference)
        saved.generationPreferences?[preference] = value
      }
    }
    saved.mobileCacheVersion = 2
    return dirty
  }
}
