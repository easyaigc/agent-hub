import Foundation
import MobileCore

/// Memoize only pure identity encoding, never alias selection, authorization,
/// runtime readiness or an operation receipt. A workspace can use these keys
/// thousands of times per event and per SwiftUI update.
final class RuntimeIdentityKeyCache: @unchecked Sendable {
  enum Kind: Hashable { case cache, reading, nativeReading }
  private struct Key: Hashable {
    let kind: Kind
    let agent: String
    let host: String
    let workspace: String
    let runtime: String
    let authority: String?
    let subject: String?
    let thread: String?
    let project: String?
  }
  private let lock = NSLock()
  private let capacity: Int
  private var values: [Key: String] = [:]
  private var order: [Key] = []
  private var oldest = 0

  init(capacity: Int = 16_384) { self.capacity = max(1, capacity) }

  func value(_ target: AgentRuntimeTarget, kind: Kind, threadId: String?, projectId: String? = nil,
    encode: () -> String) -> String {
    let key = Key(kind: kind, agent: target.agentId, host: target.hostId,
      workspace: target.workspaceId, runtime: target.runtimeId,
      authority: target.agentId == "codex" ? nil : target.account?.authority,
      subject: target.agentId == "codex" ? nil : target.account?.subject,
      thread: threadId, project: kind == .cache && threadId == nil ? projectId : nil)
    lock.lock()
    if let value = values[key] { lock.unlock(); return value }
    lock.unlock()
    // Encoding is pure. Concurrent misses may compute twice; never hold a
    // native lock across the Kotlin bridge or an encoder callback.
    var value = encode()
    // Kotlin returns an NSString-backed String. Materialize UTF-8 once before
    // these long identity keys become hot Swift Dictionary lookup keys.
    value.makeContiguousUTF8()
    lock.lock()
    defer { lock.unlock() }
    if let existing = values[key] { return existing }
    if order.count == capacity {
      values.removeValue(forKey: order[oldest])
      order[oldest] = key
      oldest = (oldest + 1) % capacity
    } else { order.append(key) }
    values[key] = value
    return value
  }
}

/// Memoize the shared pure choice using every input, including current aliases.
/// This holds no runtime readiness, authorization or command result.
final class RuntimePreferredKeyCache: @unchecked Sendable {
  private struct Input: Hashable {
    let canonical: String
    let candidates: [String]
    let selected: String?
  }
  private let lock = NSLock()
  private let capacity: Int
  private var values: [Input: String] = [:]
  private var order: [Input] = []
  private var oldest = 0
  init(capacity: Int = 256) { self.capacity = max(1, capacity) }
  func value(_ canonical: String, candidates: [String], selected: String?, choose: () -> String) -> String {
    let input = Input(canonical: canonical, candidates: candidates, selected: selected)
    lock.lock()
    if let value = values[input] { lock.unlock(); return value }
    lock.unlock()
    var result = choose()
    result.makeContiguousUTF8()
    lock.lock()
    defer { lock.unlock() }
    if let value = values[input] { return value }
    if order.count == capacity {
      values.removeValue(forKey: order[oldest]); order[oldest] = input
      oldest = (oldest + 1) % capacity
    } else { order.append(input) }
    values[input] = result
    return result
  }
}

// Maps platform DTOs only. The identity and composer rules live in mobile-core.
enum SharedMobileRules {
  private static let identityKeys = RuntimeIdentityKeyCache()
  private static let preferredKeys = RuntimePreferredKeyCache()
  static func scope(_ target: AgentRuntimeTarget) -> TargetScope {
    TargetScope(agentId: target.agentId, accountAuthority: target.account?.authority, accountSubject: target.account?.subject,
      hostId: target.hostId, workspaceId: target.workspaceId, runtimeId: target.runtimeId)
  }
  static func machineCacheKey(_ target: AgentRuntimeTarget, threadId: String?, projectId: String?) -> String {
    identityKeys.value(target, kind: .cache, threadId: threadId, projectId: projectId) {
      RuntimeIdentity.shared.cacheKey(clientId: target.hostId, target: scope(target), threadId: threadId, projectId: projectId)
    }
  }
  static func machineReadingKey(_ target: AgentRuntimeTarget, threadId: String?) -> String {
    identityKeys.value(target, kind: .reading, threadId: threadId) {
      RuntimeIdentity.shared.readingKey(target: scope(target), threadId: threadId)
    }
  }
  static func nativeReadingKey(_ target: AgentRuntimeTarget, threadId: String?) -> String {
    identityKeys.value(target, kind: .nativeReading, threadId: threadId) {
      ReadingIdentity.key(agentId: target.agentId, authority: target.account?.authority,
        subject: target.account?.subject, hostId: target.hostId,
        workspaceId: target.workspaceId, runtimeId: target.runtimeId, threadId: threadId)
    }
  }
  static func preferredRuntimeKey(_ canonical: String, candidates: [String], selectedLegacy: String? = nil) -> String {
    preferredKeys.value(canonical, candidates: candidates, selected: selectedLegacy) {
      RuntimeIdentity.shared.preferredKey(canonical: canonical, candidates: candidates, selectedLegacy: selectedLegacy)
    }
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

  static func timeline(_ messages: [ChatMessage], limit: Int, activeRunId: String?, running: Bool,
    focusedMessageId: String? = nil) -> TimelineProjection {
    let values = messages.map {
      TimelineMessage(id: $0.id, role: $0.role, text: $0.text, kind: $0.kind, status: $0.status,
        runId: $0.runId, replyPhase: $0.replyPhase, replyMessageId: $0.replyMessageId)
    }
    return MessageProjection.shared.focus(projection: MessageProjection.shared.project(messages: values,
      messageLimit: Int32(limit), activeRunId: activeRunId, running: running), messages: values, messageId: focusedMessageId)
  }

  static func noticeIndices(_ receipts: [DeliveryReceipt], dismissed: Set<String>, pendingId: String?) -> [Int] {
    SessionPolicies.shared.noticeIndices(receipts: receipts.map {
      ReceiptState(id: $0.id, state: $0.state, supersededByTurnId: $0.supersededByTurnId, queueSyncError: $0.queueSyncError)
    }, dismissed: Array(dismissed), pendingId: pendingId).map { $0.intValue }
  }
}

struct SidebarDisplayOrder {
  let pinned: [WorkspaceSidebar.Item]
  let recentThreadIds: [String]
  let projectIds: [String]
  let projectThreadIds: [String: [String]]
}

extension SharedMobileRules {
  static func sidebarOrder(hostId: String, threads: [Conversation], projects: [Project],
    pinnedThreadIds: [String], sidebar: WorkspaceSidebar?) -> SidebarDisplayOrder {
    let source = sidebar.map { value in
      SidebarSource(version: Int32(clamping: value.version), pinned: value.pinned.map {
        SidebarPinnedItem(kind: $0.kind, id: $0.id)
      }, recentThreadIds: value.recentThreadIds, projectIds: value.projectIds,
        projectThreads: value.projectThreadIds.map { SidebarProjectThreads(projectId: $0.key, threadIds: $0.value) })
    }
    let result = WorkspaceSidebarRules.shared.order(hostId: hostId, threads: threads.map {
      SidebarThreadState(id: $0.id, hostId: $0.hostId, projectId: $0.projectId,
        projectIds: $0.projectIds ?? [], archived: $0.archived == true)
    }, projects: projects.map { SidebarProjectState(id: $0.id, hostId: $0.hostId) },
      pinnedThreadIds: pinnedThreadIds, source: source)
    return SidebarDisplayOrder(pinned: result.pinned.map { WorkspaceSidebar.Item(kind: $0.kind, id: $0.id) },
      recentThreadIds: result.recentThreadIds, projectIds: result.projectIds,
      projectThreadIds: Dictionary(result.projectThreads.map { ($0.projectId, $0.threadIds) }, uniquingKeysWith: { first, _ in first }))
  }
  static func runningConversationCount(hostId: String, online: Bool, demo: Bool,
    workspaceAvailable: Bool, connectionMode: String?, conversations: [Conversation]) -> Int? {
    WorkspaceSidebarRules.shared.runningCount(hostId: hostId, online: online, demo: demo,
      workspaceAvailable: workspaceAvailable, connectionMode: connectionMode, conversations: conversations.map {
        ConversationActivity(id: $0.id, hostId: $0.hostId, state: $0.state)
      })?.intValue
  }
  static func sidebarExpanded(preference: Bool, searching: Bool) -> Bool {
    WorkspaceSidebarRules.shared.expanded(preference: preference, searching: searching)
  }
  static func sidebarVisibleIds(_ ids: [String], expanded: Bool, searching: Bool) -> [String] {
    WorkspaceSidebarRules.shared.visibleIds(ids: ids, expanded: expanded, searching: searching)
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
        #if DEBUG
        let workStart = HistoryScrollProbe.workStart()
        defer { HistoryScrollProbe.workEnd(4, workStart) }
        #endif
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
              #if DEBUG
              let workStart = HistoryScrollProbe.workStart()
              defer { HistoryScrollProbe.workEnd(5, workStart) }
              #endif
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


extension SharedMobileRules {
  static func searchDocument(text: String, detail: String?, files: [String], attachments: [String], parts: [String]) -> String {
    ChatSearchRules.shared.document(text: text, detail: detail, files: files, attachments: attachments, parts: parts)
  }
  static func chatTaskStatus(state: String, lastTurnStatus: String?, archived: Bool, online: Bool, unconfirmed: Bool) -> ChatTaskStatus {
    ChatManagementRules.shared.taskStatus(state: state, lastTurnStatus: lastTurnStatus, archived: archived, online: online, unconfirmed: unconfirmed)
  }
  static func chatCanManage(demo: Bool, online: Bool, supported: Bool, knownTarget: Bool, busy: Bool, pending: Bool) -> Bool {
    ChatManagementRules.shared.canManage(demo: demo, online: online, supported: supported, knownTarget: knownTarget, busy: busy, pending: pending)
  }
  static func workspaceArchiveEligible(knownTarget: Bool, archived: Bool, singleBusy: Bool, singlePending: Bool, batchPending: Bool) -> Bool {
    WorkspaceArchiveRules.shared.eligible(knownTarget: knownTarget, archived: archived,
      singleBusy: singleBusy, singlePending: singlePending, batchPending: batchPending)
  }
  static func workspaceArchiveSelect(current: [String], eligible: [String], all: Bool) -> [String] {
    WorkspaceArchiveRules.shared.select(current: current, eligible: eligible, allSelected: all)
  }
  static func workspaceArchiveRequestValid(_ request: WorkspaceManagementRequest) -> Bool {
    guard let data = try? JSONEncoder().encode(request), let wire = String(data: data, encoding: .utf8) else { return false }
    return WorkspaceArchiveRules.shared.requestValid(wire: wire)
  }
  static func workspaceArchivePending(_ state: String) -> Bool { WorkspaceArchiveRules.shared.pending(state: state) }
  static func workspaceArchiveMerged(_ incoming: WorkspaceManagementOperation, original: WorkspaceManagementOperation, previous: WorkspaceManagementOperation) -> WorkspaceManagementOperation? {
    func wire(_ operation: WorkspaceManagementOperation) -> String? {
      guard let data = try? JSONEncoder().encode(operation), var value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
      value.removeValue(forKey: "labels")
      guard let items = value["items"] as? [[String: Any]] else { return nil }
      value["items"] = items.map { item in var row = item; if row["error"] == nil { row["error"] = NSNull() }; return row }
      guard let output = try? JSONSerialization.data(withJSONObject: value) else { return nil }
      return String(data: output, encoding: .utf8)
    }
    guard let request = try? JSONEncoder().encode(original.request), let text = String(data: request, encoding: .utf8),
      let before = wire(previous), let next = wire(incoming),
      let merged = WorkspaceArchiveRules.shared.merge(original: text, previous: before, incoming: next),
      let decoded = try? JSONDecoder().decode(WorkspaceManagementOperation.self, from: Data(merged.utf8)) else { return nil }
    var result = original
    result.items = decoded.items
    return result
  }
  static func chatActionPending(_ phase: String) -> Bool { ChatManagementRules.shared.pending(phase: phase) }
  static func chatActionMatches(_ receipt: ChatActionReceipt, original: ChatActionReceipt) -> Bool {
    guard let request = try? JSONEncoder().encode(original.request), let outcome = try? JSONEncoder().encode(receipt),
      let requestText = String(data: request, encoding: .utf8) else { return false }
    // waitingEnded belongs only to local waiting state, not the HTTP receipt.
    guard var value = try? JSONSerialization.jsonObject(with: outcome) as? [String: Any] else { return false }
    value.removeValue(forKey: "waitingEnded")
    // Codable omits nil optionals; the strict transport contract names null error.
    if value["error"] == nil { value["error"] = NSNull() }
    guard let wire = try? JSONSerialization.data(withJSONObject: value), let text = String(data: wire, encoding: .utf8) else { return false }
    return ChatManagementRules.shared.outcomeMatches(original: requestText, outcome: text)
  }
  static func chatActionMayAccept(previous: String, next: String) -> Bool {
    ChatManagementRules.shared.mayAcceptOutcome(previousPhase: previous, phase: next)
  }
  static func searchQuery(_ value: String) -> String { ChatSearchRules.shared.query(value: value) }
  static func searchMatches(_ text: String, query: String) -> Bool {
    ChatSearchRules.shared.matches(text: text, value: query, native: NativeChatSearchText.shared)
  }
  static func searchSnippet(_ text: String, query: String) -> String {
    ChatSearchRules.shared.snippet(text: text, value: query, native: NativeChatSearchText.shared).text
  }
  static func longSearchMessage(_ text: String) -> Bool {
    ChatSearchRules.shared.longMessage(text: text, native: NativeChatSearchText.shared)
  }
  static func searchRanges(_ text: String, query: String) -> [Range<String.Index>] {
    ChatSearchRules.shared.ranges(text: text, value: query, native: NativeChatSearchText.shared).map {
      String.Index(utf16Offset: Int($0.start), in: text)..<String.Index(utf16Offset: Int($0.end), in: text)
    }
  }
  static func searchResultsCurrent(_ resultQuery: String, query: String, resultVersion: UUID?, version: UUID) -> Bool {
    ChatSearchRules.shared.resultsCurrent(resultQuery: resultQuery, value: query,
      resultSourceVersion: resultVersion?.uuidString ?? "", sourceVersion: version.uuidString)
  }
  static func searchPage(previous: [String], incoming: [String], nextCursor: String?, seen: [String]) -> ChatSearchPageOrder {
    ChatSearchRules.shared.page(previous: previous, incoming: incoming, nextCursor: nextCursor, seenCursors: seen)
  }
}
