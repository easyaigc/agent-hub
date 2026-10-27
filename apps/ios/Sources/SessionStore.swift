import SwiftUI
import UniformTypeIdentifiers
import MobileCore

@MainActor
final class SessionStore: ObservableObject {
  // A Published getter copies this large value, including all cache dictionaries,
  // even when a view only reads one member. Keep mutation notifications while
  // allowing ordinary stored-property reads and immutable persistence snapshots.
  // BEGIN: saved-state-publication
  private var updatingCursorMetadata = false
  var saved = SavedSession() {
    willSet { if !updatingCursorMetadata { objectWillChange.send() } }
  }
  // END: saved-state-publication
  @Published private(set) var managementBusy = false
  private var managementRevisions: [String: UUID] = [:]
  @Published private(set) var localRecoveryRequired = false
  let drafts = ComposerDraftStore()
  @Published var selectedThreadId: String?
  @Published var selectedProjectId: String?
  @Published var drawerOpen = false
  // Interaction-only gate: changing it must not invalidate the chat view.
  var sidebarInteracting = false
  @Published var sheet: AppSheet?
  @Published var search = ""
  @Published var chatSearchJump: ChatSearchJump?
  @Published private(set) var chatActionBusy: [String: String] = [:]
  private var errorRevision: UInt64 = 0
  @Published private var operationFailure: OperationFailure? { didSet { errorRevision &+= 1 } }
  var error: String? {
    get { operationFailure?.message }
    set { operationFailure = newValue.map { OperationFailure(message: $0) } }
  }
  var errorTitle: String { operationFailure?.title ?? L10n.tr("无法完成操作") }
  func presentError(_ failure: Error) { operationFailure = OperationFailure(failure) }
  @Published var loading = false
  @Published private var loadingHistoryRequests: [String: Int] = [:]
  @Published var historyCursor: String?
  @Published var sendingKeys: Set<String> = []
  private var replySendFlights: [String: UUID] = [:]
  @Published private var replyRecoveryFlights: [String: UUID] = [:]
  private var replyRecoveryQueries: [String: (id: String, next: Double, delay: Double)] = [:]
  @Published private var stoppingKeys: Set<String> = []
  @Published private var transfers: [String: String] = [:]
  @Published private var imports: [String: Int] = [:]
  private var localImportJobs: Set<String> = []
  private var localImportTargets: [String: AttachmentDraftDestination] = [:]
  private var localImportCancellations: [String: () -> Void] = [:]
  private var recoveringAttachmentKeys: Set<String> = []
  private var completedLocalImportJobs: [String] = []
  private var attachmentSlots: [String: Int] = [:]
  private var recoveredLocalAttachments = false
  @Published private var detachingCreationKeys: Set<String> = []
  @Published private var answeringApprovals: [UUID: ApprovalAnswerFlight] = [:]
  @Published private var queueActionOwners: [String: UUID] = [:]
  #if DEBUG
  @Published private var devQueueActionTrace = "none"
  @Published private(set) var devQueueBatchRunning = false
  private var devQueueBatches: Set<String> = []
  #endif
  @Published var replyMode = "queue"
  @Published var devAttachmentEvidence: String?
  @Published var devOperationEvidence: String?
  @Published var pendingConnection: (agentId: String, payload: String, presentation: UUID)?
  @Published var connectionRoutes: [String: String] = [:]
  enum ConnectionPhase { case connecting, waiting, failed }
  @Published private var connectionPhases: [String: ConnectionPhase] = [:]
  @Published var models: [CodexModel] = []
  @Published private(set) var aiSharingRequest: AISharingRequest?

  private var eventTask: Task<Void, Never>?
  private var lastConnectionSuccess: [String: TimeInterval] = [:]
  private var workspaceRefreshGeneration: UUID?
  private var workspaceRefreshOwner: UUID?
  private var pendingManualHistoryRefresh: (client: UUID, history: UUID)?

private var userRequestedWorkspaceGeneration: UUID?
  private var latestHistoryReads: [String: UUID] = [:]
  private var earlierHistoryReads: [String: UUID] = [:]
  private var unreadProjectionTask: Task<Void, Never>?
  private var eventRecoveryTask: Task<Void, Never>?
  private var replyRecoveryTask: Task<Void, Never>?
  private var replyRecoveryGeneration: UUID?
  private var replyRecoveryHostId: String?
  private var lastSnapshotAt: [String: Double] = [:]
  private struct ConversationSyncIssue: Equatable { var message: String; var historyFresh = false; var liveWindow = false }
  @Published private var conversationSyncFailures: [String: ConversationSyncIssue] = [:]
  private var historySyncChecks: [String: String] = [:]
  var conversationSyncFailure: String? {
    guard let host = client, let id = selectedThreadId else { return nil }
    return conversationSyncFailures[cacheKey(host, threadId: id)]?.message
  }
  var conversationSyncFailureTitle: String {
    if let host = client, let id = selectedThreadId,
      conversationSyncFailures[cacheKey(host, threadId: id)]?.liveWindow == true {
      return L10n.tr("实时进度已同步")
    }
    guard let host = client, let id = selectedThreadId,
      conversationSyncFailures[cacheKey(host, threadId: id)]?.historyFresh == true else {
      return L10n.tr("对话同步失败")
    }
    return L10n.tr("实时状态暂不可用")
  }
  private var loadedHistoryGeneration: UUID?
  private var suggestionNeedsFreshHistory = false
  private let suggestionReadAhead = DesktopSuggestionReadAhead()
  let desktopSuggestionReader = DesktopSuggestionReader()
  private var persistScheduled = false
  private let persistenceURL: URL
  private let testMode: Bool
  private let persistenceWriter: SessionPersistenceWriter<SavedSession, SessionHistoryPage>
  private var dirtyHistoryKeys: Set<String> = []
  private var draftSaveTask: Task<Void, Never>?
  private var draftSaveDeadlineTask: Task<Void, Never>?
  private var clientGeneration = UUID() { didSet { suggestionReadAhead.cancel(); desktopSuggestionReader.clear(reason: "connection-generation") } }
  private var historyGeneration = UUID() { didSet { suggestionReadAhead.cancel(); desktopSuggestionReader.clear(reason: "navigation-generation") } }
  private var pagedHistoryGeneration: UUID?
  private var historyWindowRevisions: [String: Int] = [:]
  private var demoTasks: [String: Task<Void, Never>] = [:]
  private var creationObservers: [String: Task<Void, Never>] = [:]
  @Published private var forkOptionsBySource: [String: ThreadForkOptions] = [:]
  @Published private var forkOptionErrors: [String: String] = [:]
  @Published private var forkOptionsLoading: Set<String> = []
  @Published private var forkSubmittingIDs: Set<String> = []
  @Published private var forkQueryingIDs: Set<String> = []
  private var forkObservers: [String: Task<Void, Never>] = [:]
  private var forkNavigationOwners: [String: String] = [:]
  private struct ForkNavigation {
    let client: UUID
    let history: UUID
    let draft: Draft
  }
  private var creatingOperations: [String: String] = [:]
  private var readingTasks: [String: Task<Void, Never>] = [:]
  private var codexStorageKeys: [String] = []
  private var codexStorageCandidateCache: [String: [String]] = [:]
  private var codexStorageKeySet: Set<String> = []
  private var codexStorageContexts: [String: AgentRuntimeTarget] = [:]
  private var codexReadingIndexKeys: Set<String> = []
  private var codexReadingIndex = CodexMachineStorage.ReadingKeyIndex()
  private var codexReadingMemo = CodexMachineStorage.ReadingKeyMemo()
  private let unreadReconciler = MobileUnreadReconciler()

  #if DEBUG
  private var typingDurations: [Double] = []
  private var typingChatRenders = 0
  private var typingWrites = 0
  private var typingMainThreadWrites = 0
  @Published var typingEvidence = ""
  var typingFixture: Bool { testMode && ProcessInfo.processInfo.arguments.contains("-composer-performance-fixture") }
  func recordTypingChatRender() { if typingFixture { typingChatRenders += 1 } }
  func resetTypingMetrics() {
    typingDurations = []; typingChatRenders = 0; typingWrites = 0; typingMainThreadWrites = 0
  }
  func showTypingMetrics() {
    let sorted = typingDurations.sorted()
    let values: [String: Double] = ["edits": Double(sorted.count), "p95MS": sorted.isEmpty ? 0 : sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))], "maxMS": sorted.last ?? 0, "chatRenders": Double(typingChatRenders), "writes": Double(typingWrites), "mainThreadWrites": Double(typingMainThreadWrites)]
    if let data = try? JSONSerialization.data(withJSONObject: values, options: [.sortedKeys]) { typingEvidence = String(decoding: data, as: UTF8.self) }
  }
  #endif

  #if DEBUG
  func devReplayAgentOperation(_ operation: AgentDEVReplayOperation) {
    guard ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"), let host = client else { return }
    let target = targetKey
    devOperationEvidence = "\(operation.rawValue):pending"
    Task {
      do {
        suspendEvents()
        let adapter = try AgentClients.resolve(host)
        await adapter.close(host)
        let ids = try await adapter.devReplay(host, operation: operation)
        await adapter.close(host)
        if targetKey == target { devOperationEvidence = "\(operation.rawValue):passed:" + ids.joined(separator: "|"); await refresh() }
      } catch { if targetKey == target { devOperationEvidence = "\(operation.rawValue):failed:" + error.localizedDescription; await refresh() } }
    }
  }
  #endif

  init() {
    testMode = ProcessInfo.processInfo.arguments.contains("-ui-testing")
    if testMode && !ProcessInfo.processInfo.arguments.contains("-restore-testing") {
      UserDefaults.standard.removeObject(forKey: VoiceTranscriptionPreference.storageKey)
    }
    let directory = FileManager.default.urls(
      for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent(testMode ? (ProcessInfo.processInfo.arguments.contains("-composer-performance-fixture") ? "ComposerPerformance" : "Acceptance") : "CodexManage", isDirectory: true)
    try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let legacyURL = directory.appendingPathComponent("session.json")
    persistenceURL = legacyURL
    persistenceWriter = SessionPersistenceWriter(legacyURL: persistenceURL,
      encodeState: { state in try state.clients.contains(where: { $0.agentConnection != nil }) ? AgentCache.encode(state) : JSONEncoder().encode(state) },
      decodeState: { try AgentCache.decode($0) },
      encodePage: { page in try page.encrypted ? AgentCache.seal(page) : JSONEncoder().encode(page) },
      decodePage: { try AgentCache.open($0, as: SessionHistoryPage.self) })
    if !testMode || ProcessInfo.processInfo.arguments.contains("-restore-testing") {
      do { saved = try loadLocalSession() }
      catch { localRecoveryRequired = true }
    } else {
      try? persistenceWriter.replaceLegacy(JSONEncoder().encode(SavedSession()))
      #if DEBUG
      try? MobileUnreadBadge.reset()
      #endif
    }
    guard !localRecoveryRequired else { return }
    finishLocalRestoration()
  }

  private func loadLocalSession() throws -> SavedSession {
    guard var loaded = try persistenceWriter.loadInitialState() else { return SavedSession() }
    var recoveredPageCount = 0
    if let namespace = loaded.historyCacheNamespace {
      for key in loaded.historyCacheKeys ?? [] {
        do {
          let page = try persistenceWriter.loadHistory(namespace + ":" + key)
          if page.targetKey == key {
            loaded.history[key] = page.messages
            if page.encrypted != loaded.historyCacheEncrypted { dirtyHistoryKeys.insert(key) }
          }
        } catch {
          let failure = error as NSError
          if failure.domain == NSCocoaErrorDomain && failure.code == NSFileReadNoPermissionError { throw error }
          // Keep the original cache index even when a replaceable page is unavailable.
          if let data = try? Data(contentsOf: persistenceURL), let old = try? AgentCache.decode(data), let messages = old.history[key] {
            loaded.history[key] = messages; dirtyHistoryKeys.insert(key)
          }
        }
      }
      let recovered = try persistenceWriter.recoverUnindexedHistory(namespace: namespace,
        indexed: loaded.historyCacheKeys ?? [], key: { $0.targetKey })
      for (key, page) in recovered {
        loaded.history[key] = page.messages
      }
      recoveredPageCount = recovered.count
      loaded.historyCacheKeys = Array(Set(loaded.historyCacheKeys ?? []).union(recovered.keys))
    } else {
      loaded.historyCacheNamespace = UUID().uuidString
      dirtyHistoryKeys = Set(loaded.history.keys)
    }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    func digest<T: Encodable>(_ value: T) -> String {
      guard let bytes = try? encoder.encode(value) else { return "unavailable" }
      return HostService.hash(bytes)
    }
    persistenceWriter.recordLoadReport([
      "applicationState": String(UIApplication.shared.applicationState.rawValue),
      "protectedDataAvailable": String(UIApplication.shared.isProtectedDataAvailable),
      "clients": String(loaded.clients.count), "drafts": String(loaded.drafts.count),
      "workspaces": String(loaded.workspaces.count), "historyPagesLoaded": String(loaded.history.count),
      "historyPagesIndexed": String(loaded.historyCacheKeys?.count ?? loaded.history.count),
      "unindexedPagesRecovered": String(recoveredPageCount),
      "messages": String(loaded.history.values.reduce(0) { $0 + $1.count }),
      "pendingReplies": String(loaded.outbox?.count ?? 0),
      "pendingCreations": String(loaded.desktopCreations?.values.filter { ["submitted", "outcome-unknown"].contains($0.phase) }.count ?? 0),
      "clientsSha256": digest(loaded.clients), "draftsSha256": digest(loaded.drafts),
      "submittedInputsSha256": digest(loaded.outbox),
    ])
    return loaded
  }

  @discardableResult func retryLocalRecovery() -> Bool {
    guard localRecoveryRequired else { return true }
    do {
      let restored = try loadLocalSession()
      saved = restored; localRecoveryRequired = false
      finishLocalRestoration()
      return true
    } catch { return false }
  }

  private func finishLocalRestoration() {
    if let id = saved.selectedClientId { NativeReadAcceptanceTiming.record("session-start", hostId: id) }
    if saved.historyCacheNamespace == nil { saved.historyCacheNamespace = UUID().uuidString }
    if saved.readingDeviceId == nil { saved.readingDeviceId = UUID().uuidString }
    let migrateDetachedReplies = saved.mobileCacheVersion != 2
    dirtyHistoryKeys.formUnion(SharedMobileRules.migrateCache(&saved))
    if migrateDetachedReplies {
      for host in saved.clients where !host.isDemo {
        let target = host.agentConnection?.target ?? saved.workspaces[host.id]?.readTarget ?? host.codexDirect?.target
        guard (target?.agentId ?? "codex") != "codex" else { continue }
        let prefix = host.id + ":"
        for old in Array(saved.detachedReplies?.keys ?? [:].keys) where old.hasPrefix(prefix) {
          let suffix = String(old.dropFirst(prefix.count))
          let isDraft = suffix.hasPrefix("new:")
          let project = isDraft ? String(suffix.dropFirst(4)) : "none"
          let key = SharedMobileRules.cacheKey(host, threadId: isDraft ? nil : suffix,
            projectId: project == "none" ? nil : project, workspace: saved.workspaces[host.id])
          if key != old, saved.detachedReplies?[key] == nil {
            let value = saved.detachedReplies?.removeValue(forKey: old)
            saved.detachedReplies?[key] = value
          }
        }
      }
    }
    captureCodexStorageKeys()
    // Migrate explicit choices from the previously selected draft on an upgrade.
    for host in saved.clients {
      let key = "generation:" + cacheKey(host, threadId: nil)
      guard saved.generationPreferences?[key] == nil else { continue }
      let newProject = saved.newProjects[host.id]
      let previousKey = cacheKey(host, threadId: newProject == nil ? saved.lastThreads[host.id] : nil,
        projectId: newProject == "__projectless__" ? nil : newProject)
      if let previous = saved.drafts[previousKey], previous.model != nil || previous.effort != nil || previous.permissionMode != nil || previous.speed != nil {
        if saved.generationPreferences == nil { saved.generationPreferences = [:] }
        saved.generationPreferences?[key] = GenerationOptions(model: previous.model, effort: previous.effort,
          permissionMode: previous.permissionMode, speed: previous.speed)
      }
    }
    preserveCodexReaderScopeIds()
    drafts.values = saved.drafts
    saved.drafts = [:]
    if testMode && !ProcessInfo.processInfo.arguments.contains("-restore-testing") {
      enableDemo()
    }
    if saved.runtimes == nil { saved.runtimes = [:] }
    if saved.outbox == nil { saved.outbox = [:] }
    // Older versions only changed the banner when ending a wait. Move those
    // immutable inputs out of the active composer on upgrade as well.
    for (key, reply) in saved.outbox ?? [:] where saved.detachedReplyWaits?.contains(reply.clientMessageId) == true {
      archiveReply(reply, key: key)
    }
    for index in saved.clients.indices where !saved.clients[index].isDemo {
      saved.clients[index].online = false
      connectionPhases[saved.clients[index].id] = .connecting
      CodexHostRoutes.shared.register(saved.clients[index])
    }
    if let hostId = saved.selectedClientId { restoreSelection(hostId) }
    #if DEBUG
    if testMode && ProcessInfo.processInfo.arguments.contains("-history-short-paging-fixture") {
      saved.history[targetKey] = (0..<72).map { index in
        ChatMessage(id: "short-history-\(index)", role: "user", text: String(format: "HISTORY_SHORT_%03d", index))
      }
    }
    if testMode && ProcessInfo.processInfo.arguments.contains("-history-scroll-fixture") {
      let paragraph = "历史滑动回归：这是包含 **强调**、`代码` 和 [链接](https://example.com) 的长回复。内容应当完整保留，连续阅读时不能因为界面状态刷新而反复计算全部历史。"
      saved.history[targetKey] = (0..<2400).map { index in
        ChatMessage(id: "scroll-history-\(index)", role: index % 3 == 0 ? "user" : "assistant",
          text: index % 3 == 0 ? "HISTORY_SCROLL_\(index)" : "## HISTORY_SCROLL_\(index)\n\n" + Array(repeating: paragraph, count: 12).joined(separator: "\n\n") + "\n\n```swift\nlet value = 42\n```\n\n| 项目 | 状态 |\n| --- | --- |\n| 历史 | 完整 |", status: "completed")
      }
    }
    if typingFixture && !ProcessInfo.processInfo.arguments.contains("-restore-testing") {
      saved.history[targetKey] = (0..<2400).map { index in
        ChatMessage(id: "typing-history-\(index)", role: "assistant", text: index < 2340 ? String(repeating: "Large cached conversation for native input performance. ", count: 80) : L10n.tr("已完成的验收历史 \(index)"), status: "completed")
      }
      dirtyHistoryKeys.insert(targetKey)
      persist()
    }
    #endif
    persistenceWriter.onHistoryFailure { [weak self] failure in
      Task { @MainActor in self?.recordPersistence(.failure(failure), onMainThread: false) }
    }
    for (key, pending) in saved.desktopCreations ?? [:] where ["submitted", "outcome-unknown"].contains(pending.phase) {
      if let host = saved.clients.first(where: { $0.id == pending.hostId }) {
        startObservingCreation(host, key: key, pending: pending, receiptOnly: true)
      }
    }
    for pending in (saved.threadForks ?? [:]).values where pending.phase.isPending && pending.detached != true {
      // Restoring a receipt can never take over the user's current screen.
      if let host = forkClient(for: pending.source) {
        observeFork(host, pending: pending, navigation: nil)
      }
    }
  }

  var client: Client? {
    let id = saved.selectedClientId
    return saved.clients.first { $0.id == id }
  }

  struct VoiceReadinessScope: Hashable {
    let generation: UUID
    let connection: Client
    let target: AgentRuntimeTarget
    func hash(into hasher: inout Hasher) { hasher.combine(generation) }
  }
  var voiceReadinessScope: VoiceReadinessScope? {
    guard VoiceTranscriptionPreference.current == .desktop, let host = client else { return nil }
    return VoiceReadinessScope(generation: clientGeneration, connection: readingClient(host),
      target: aiSharingScope(host, model: "codex-dictation").target)
  }
  private var preparedVoiceScope: VoiceReadinessScope?
  private var desktopVoiceAvailable = false
  private var desktopVoiceWrongHost = false
  private var voiceCheckedAt: ContinuousClock.Instant?
  private var voiceMonitorId: UUID?

  // Owned by the foreground view task. Recording only reads this snapshot.
  func monitorVoiceReadiness() async {
    guard let scope = voiceReadinessScope else { return }
    let monitorId = UUID()
    voiceMonitorId = monitorId
    preparedVoiceScope = scope
    desktopVoiceAvailable = false
    desktopVoiceWrongHost = false
    voiceCheckedAt = nil
    defer {
      if voiceMonitorId == monitorId { preparedVoiceScope = nil; voiceMonitorId = nil }
    }
    while !Task.isCancelled, voiceReadinessScope == scope {
      var available = false, wrongHost = false
      do {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-ui-testing"),
          ProcessInfo.processInfo.arguments.contains("-voice-input-fixture") {
          if ProcessInfo.processInfo.arguments.contains("-voice-readiness-delayed") {
            try await Task.sleep(for: .seconds(8))
          }
          available = !ProcessInfo.processInfo.arguments.contains("-voice-input-disabled")
        } else {
          if scope.connection.online {
            available = try await AgentClients.resolve(scope.connection).voiceReadiness(scope.connection).available
          }
        }
        #else
        if scope.connection.online {
          available = try await AgentClients.resolve(scope.connection).voiceReadiness(scope.connection).available
        }
        #endif
      } catch is CancellationError { return }
      catch HostServiceError.wrongHost { wrongHost = true }
      catch { /* Failed preflight selects phone recognition without blocking input. */ }
      guard !Task.isCancelled, voiceMonitorId == monitorId, voiceReadinessScope == scope else { return }
      desktopVoiceAvailable = available
      desktopVoiceWrongHost = wrongHost
      voiceCheckedAt = .now
      do { try await Task.sleep(for: .seconds(30)) } catch { return }
    }
  }

  func voiceProvider(draftId: String) -> VoiceTranscriptionProvider? {
    guard let host = client else { return nil }
    if VoiceTranscriptionPreference.current == .local { return .local(scopeKey: draftId) }
    let scope = aiSharingScope(host, model: "codex-dictation")
    let scopeEncoder = JSONEncoder(); scopeEncoder.outputFormatting = [.sortedKeys]
    guard let encodedScope = try? scopeEncoder.encode(scope),
      let encodedDraft = try? scopeEncoder.encode([encodedScope.base64EncodedString(), draftId]) else { return nil }
    let voiceDraftId = "voice-v1:" + encodedDraft.base64EncodedString()
    let target = targetKey, threadId = selectedThreadId, navigation = clientGeneration
    let connection = readingClient(host)
    let agent = try? AgentClients.resolve(connection)
    func validateTarget() throws {
      guard clientGeneration == navigation, targetKey == target, client?.id == host.id else { throw HostServiceError.message(L10n.tr("录音目标已变化")) }
    }
    return VoiceTranscriptionProvider(scopeKey: voiceDraftId, prepare: {
      try validateTarget()
      guard let prepared = self.preparedVoiceScope, prepared == self.voiceReadinessScope,
        let checkedAt = self.voiceCheckedAt, checkedAt.duration(to: .now) < .seconds(60) else { return false }
      if self.desktopVoiceWrongHost { throw HostServiceError.wrongHost }
      guard self.desktopVoiceAvailable, agent != nil else { return false }
      guard self.requireAISharing(host, model: "codex-dictation") else { throw CancellationError() }
      try self.validateAISharing(host, scope: scope)
      return true
    }, transcribe: { recording in
      try validateTarget()
      guard self.requireAISharing(host, model: "codex-dictation") else { throw CancellationError() }
      try self.validateAISharing(host, scope: scope)
      guard let agent else { throw HostServiceError.unavailable }
      let text = try await agent.transcribeVoice(connection, recording: recording, threadId: threadId, draftId: voiceDraftId)
      try validateTarget()
      try self.validateAISharing(host, scope: scope)
      return text
    })
  }

  private func aiSharingScope(_ host: Client, model: String?) -> AISharingScope {
    let workspace = saved.workspaces[host.id]
    let identity = host.agentConnection?.target ?? host.codexDirect?.target ?? workspace?.readTarget
      ?? AgentRuntimeTarget(agentId: workspace?.clientType?.id ?? "codex", hostId: host.id,
        workspaceId: host.id, runtimeId: workspace?.connectionMode ?? "unknown")
    return AISharingScope(target: identity, model: AISharingRules.shared.model(value: model))
  }

  private func requireAISharing(_ host: Client, model: String? = nil) -> Bool {
    let effectiveModel = model ?? runtime?.generationSettings?.model ?? selectedModel?.id
    return requireAISharing(host, scope: aiSharingScope(host, model: effectiveModel))
  }
  private func requireAISharing(_ host: Client, scope: AISharingScope) -> Bool {
    if host.isDemo { return true }
    guard aiSharingScope(host, model: scope.model) == scope else {
      error = "原输入的授权目标已变化，请查询原回执。"; return false
    }
    if saved.aiSharingGrants?.contains(where: { $0.scope == scope }) == true { return true }
    aiSharingRequest = AISharingRequest(scope: scope, targetKey: targetKey, computerName: host.name,
      agentName: saved.workspaces[host.id]?.clientType?.name ?? (scope.target.agentId == "easywork" ? "EasyWork" : "Codex"),
      modelName: scope.model == "codex-dictation" ? L10n.tr("Codex 语音转写") : scope.model == "follow-computer" ? L10n.tr("跟随电脑端配置") : models.first(where: { $0.id == scope.model })?.name ?? scope.model)
    sheet = .aiSharing
    return false
  }

  func allowAISharing() {
    guard let request = aiSharingRequest, let host = client,
      request.targetKey == targetKey,
      aiSharingScope(host, model: request.scope.model) == request.scope else {
      dismissAISharing()
      return
    }
    if saved.aiSharingGrants == nil { saved.aiSharingGrants = [] }
    saved.aiSharingGrants?.removeAll { $0.scope == request.scope }
    saved.aiSharingGrants?.append(AISharingGrant(id: UUID().uuidString, scope: request.scope,
      computerName: request.computerName, agentName: request.agentName,
      modelName: request.modelName, grantedAt: Date()))
    persist()
    // Grant permission only. The user explicitly submits again; no captured input is replayed.
    dismissAISharing()
  }

  func dismissAISharing() {
    aiSharingRequest = nil
    if sheet == .aiSharing { sheet = nil }
  }

  func revokeAISharing(_ id: String) {
    saved.aiSharingGrants?.removeAll { $0.id == id }
    persist()
  }
  private func validateAISharing(_ host: Client, scope: AISharingScope) throws {
    if host.isDemo { return }
    guard aiSharingScope(host, model: scope.model) == scope,
      saved.aiSharingGrants?.contains(where: { $0.scope == scope }) == true else {
      throw HostServiceError.message(L10n.tr("AI 数据共享许可已撤销或目标已变化。内容未继续提交，请重新确认后主动发送。"))
    }
  }
  var fileTransferClient: Client? { client.map { readingClient($0) } }
  var hasDemoClients: Bool { saved.clients.contains(where: \.isDemo) }
  var projects: [Project] {
    guard let client else { return [] }
    return client.isDemo
      ? DemoData.projects.filter { $0.hostId == client.id && !(saved.demoDeletedProjectIds ?? []).contains($0.id) }
        + (saved.workspaces[client.id]?.projects ?? []).filter { !(saved.demoDeletedProjectIds ?? []).contains($0.id) }
      : saved.workspaces[client.id]?.projects ?? []
  }
  var threads: [Conversation] {
    guard let client else { return [] }
    return client.isDemo
      ? saved.demoThreads.filter { $0.hostId == client.id && !(saved.demoArchivedThreadIds ?? []).contains($0.id) }
        + DemoData.threads.filter { $0.hostId == client.id && !(saved.demoArchivedThreadIds ?? []).contains($0.id) }
      : saved.workspaces[client.id]?.threads ?? []
  }
  var conversation: Conversation? {
    let id = selectedThreadId
    return threads.first { $0.id == id }
  }

  private struct ReadingKeyContext {
    let canonical: AgentRuntimeTarget
    let machine: AgentRuntimeTarget
    init(_ host: Client, workspace: WorkspaceSnapshot?) {
      let fallback = AgentRuntimeTarget(agentId: workspace?.clientType?.id ?? "codex",
        hostId: host.id, workspaceId: host.id, runtimeId: workspace?.connectionMode ?? "unknown")
      canonical = host.agentConnection?.target ?? workspace?.readTarget ?? host.codexDirect?.target ?? fallback
      machine = host.agentConnection?.target ?? host.codexDirect?.target ?? workspace?.readTarget ?? fallback
    }
  }
  private func readingKey(_ host: Client, threadId: String?) -> String {
    let state = saved
    return readingKey(host, threadId: threadId,
      context: ReadingKeyContext(host, workspace: state.workspaces[host.id]), state: state)
  }
  private func readingKey(_ host: Client, threadId: String?, context: ReadingKeyContext, state: SavedSession, readingIndex: CodexMachineStorage.ReadingKeyIndex? = nil) -> String {
    func canonicalKey() -> String {
      SharedMobileRules.nativeReadingKey(context.canonical, threadId: threadId)
    }
    guard context.machine.agentId == "codex" else { return canonicalKey() }
    let index = readingIndex ?? codexReadingIndex
    let value = codexReadingMemo.value(hostId: host.id, target: context.canonical, candidate: context.machine, threadId: threadId) {
      let canonical = canonicalKey(), candidate = SharedMobileRules.machineReadingKey(context.machine, threadId: threadId)
      return .init(canonical: canonical, candidate: candidate,
        selected: index.preferredKey(canonical, candidateKey: candidate))
    }
    // New canonical records take precedence immediately, including records added
    // during reconciliation before the next persistence snapshot rebuilds the index.
    return value.preferredKey {
      state.mobileReads?[value.candidate] != nil || state.mobileReadBaselines?[value.candidate] != nil
    }
  }
  func mobileReadingTarget(_ host: Client) -> AgentRuntimeTarget {
    ReadingKeyContext(host, workspace: saved.workspaces[host.id]).machine
  }
  private func readingClient(_ host: Client) -> Client {
    var result = host
    result.readingTarget = host.agentConnection?.target ?? saved.workspaces[host.id]?.readTarget ?? host.codexDirect?.target
    let machine = mobileReadingTarget(host)
    result.readingScopeId = machine.agentId == "codex"
      ? saved.codexReaderScopeIds?[CodexMachineStorage.readerScope(machine)] ?? ReadingIdentity.readerId(deviceId: saved.readingDeviceId ?? "", targetKey: readingKey(host, threadId: nil))
      : ReadingIdentity.readerId(deviceId: saved.readingDeviceId ?? "", targetKey: readingKey(host, threadId: nil))
    return result
  }
  var readingContextKey: String {
    client.map { readingKey($0, threadId: selectedThreadId) } ?? "none"
  }
  var nextTurnSuggestionScope: DesktopSuggestionScope? {
    guard loadedHistoryGeneration == historyGeneration, !suggestionNeedsFreshHistory else { return nil }
    return suggestionReadCandidate
  }
  var nextTurnSuggestionPresentationScope: DesktopSuggestionScope? {
    if let scope = nextTurnSuggestionScope { return scope }
    // A failed long poll is not evidence that an already confirmed prediction
    // changed. Keep its presentation through a transient transport interruption;
    // reading and adoption still require nextTurnSuggestionScope to be live.
    guard loadedHistoryGeneration == historyGeneration, let host = client,
      connectionPhases[host.id] != .failed,
      suggestionNeedsFreshHistory || connectionPhases[host.id] == .waiting,
      let scope = suggestionScope(requiringOnline: false),
      desktopSuggestionReader.visibleSuggestion(for: scope) != nil else { return nil }
    return scope
  }
  private var suggestionReadCandidate: DesktopSuggestionScope? {
    suggestionScope(requiringOnline: true)
  }
  #if DEBUG
  var suggestionUnavailableReason: String {
    guard loadedHistoryGeneration == historyGeneration else { return "history-unconfirmed" }
    guard let host = client else { return "no-client" }
    guard !host.isDemo else { return "demo" }
    guard pendingConnection == nil else { return "pending-connection" }
    guard selectedThreadId != nil else { return "no-thread" }
    guard let workspace = saved.workspaces[host.id] else { return "no-workspace" }
    guard ["desktop-runtime", "easywork-runtime"].contains(workspace.connectionMode) else { return "runtime-mode" }
    guard supportsSuggestionSource(workspace) else { return "capability" }
    guard let target = workspace.readTarget,
      target == mobileReadingTarget(host) else { return "target" }
    guard let runtime else { return "no-runtime" }
    guard runtime.state == "idle" else { return "runtime-not-idle" }
    guard runtime.canControl == true else { return "runtime-owner" }
    guard let source = runtime.suggestionSourceTurnId, !source.isEmpty else { return "runtime-source" }
    guard host.online else { return "offline" }
    return suggestionNeedsFreshHistory ? "history-revalidating" : "available"
  }
  #endif
  private func supportsSuggestionSource(_ workspace: WorkspaceSnapshot) -> Bool {
    let capabilities = workspace.capabilities ?? []
    if workspace.connectionMode == "easywork-runtime", workspace.readTarget?.agentId == "easywork" {
      return capabilities.contains("desktop:suggestions:runtime-version:v1")
    }
    return workspace.connectionMode == "desktop-runtime" && workspace.readTarget?.agentId == "codex"
      && (capabilities.contains("desktop:suggestions:native-version:v1") || capabilities.contains("desktop:suggestions:displayed:v1"))
  }
  private func suggestionScope(requiringOnline: Bool) -> DesktopSuggestionScope? {
    guard let host = client, !host.isDemo, (!requiringOnline || host.online), pendingConnection == nil,
      let threadId = selectedThreadId,
      let workspace = saved.workspaces[host.id], supportsSuggestionSource(workspace),
      let target = workspace.readTarget, target == mobileReadingTarget(host),
      let runtime, runtime.state == "idle", runtime.canControl == true,
      let sourceTurnId = runtime.suggestionSourceTurnId, !sourceTurnId.isEmpty else { return nil }
    return DesktopSuggestionScope(target: target, threadId: threadId, sourceTurnId: sourceTurnId,
      navigation: clientGeneration.uuidString + ":" + historyGeneration.uuidString,
      connection: (host.endpoint ?? "") + ":" + (host.codexDirect?.fingerprint ?? ""),
      requiresDisplayedSource: workspace.capabilities?.contains("desktop:suggestions:displayed:v1") == true,
      requiresNativeSource: workspace.connectionMode == "desktop-runtime" && workspace.capabilities?.contains("desktop:suggestions:displayed:v1") != true,
      requiresRuntimeSource: workspace.connectionMode == "easywork-runtime")
  }
  func readNextTurnSuggestion(_ host: Client, scope: DesktopSuggestionScope) async throws -> DesktopSuggestionResponse {
    try await suggestionReadAhead.read(scope) {
      try await AgentClients.resolve(host).nextTurnSuggestion(host, scope: scope)
    }
  }
  private func prepareNextTurnSuggestion(_ host: Client, scope: DesktopSuggestionScope) {
    guard UIApplication.shared.applicationState == .active else { return }
    suggestionReadAhead.prepare(scope, onReady: { [weak self] expected, response in
      guard let self, UIApplication.shared.applicationState == .active,
        self.nextTurnSuggestionScope == expected else { return }
      self.desktopSuggestionReader.seed(response, for: expected)
    }) {
      try await AgentClients.resolve(host).nextTurnSuggestion(host, scope: scope)
    }
    if nextTurnSuggestionScope == scope, let response = suggestionReadAhead.completed(for: scope) {
      desktopSuggestionReader.seed(response, for: scope)
    }
  }
  var suggestionDraftSnapshot: DesktopSuggestionDraftSnapshot {
    DesktopSuggestionDraftSnapshot(targetKey: targetKey, text: draft.text,
      revision: drafts.textRevisions[targetKey, default: 0])
  }
  func adoptDesktopSuggestion(_ action: DesktopSuggestionDraftAction, mode: DesktopSuggestionAdoptionMode,
    currentSuggestion: DesktopSuggestion?
  ) -> Bool {
    guard canCompose, pending == nil, pendingDesktopCreation == nil,
      !sendingKeys.contains(targetKey), !isImporting,
      let text = action.proposedText(mode: mode, currentScope: nextTurnSuggestionScope,
        currentSuggestion: currentSuggestion, currentDraft: suggestionDraftSnapshot) else { return false }
    setText(text, target: action.draft.targetKey)
    drafts.suggestionUndo = DesktopSuggestionDraftUndo(scope: action.scope, suggestionId: action.suggestion.id,
      originalText: action.draft.text, applied: suggestionDraftSnapshot,
      handledVersionKey: action.suggestion.handledVersionKey(targetKey: action.draft.targetKey, sourceTurnId: action.scope.sourceTurnId))
    return true
  }
  func undoDesktopSuggestion(_ undo: DesktopSuggestionDraftUndo) -> Bool {
    guard drafts.suggestionUndo == undo,
      let text = undo.restoredText(currentDraft: suggestionDraftSnapshot) else { return false }
    setText(text, target: undo.applied.targetKey)
    drafts.suggestionUndo = nil
    return true
  }
  func showsUnreadDot(_ thread: Conversation) -> Bool {
    guard !["running", "waiting-for-approval"].contains(thread.state) else { return false }
    guard let host = client, thread.hostId == host.id, !host.isDemo else { return thread.showsUnreadDot }
    return TaskNotificationController.shared.unreadSnapshot.isUnread(target: mobileReadingTarget(host), threadId: thread.id)
  }
  func pendingAsyncQuestionCount(for thread: Conversation) -> Int {
    if let count = thread.pendingAsyncQuestionCount { return max(0, count) }
    guard let host = client, host.id == thread.hostId else { return 0 }
    return saved.runtimes?[cacheKey(host, threadId: thread.id)]?.approvals.filter {
      $0.threadId == thread.id && $0.interactionMode == "async"
    }.count ?? 0
  }
  func unreadCount(for host: Client, snapshot: MobileUnreadBadge.Snapshot) -> Int {
    host.isDemo ? 0 : snapshot.count(for: mobileReadingTarget(host))
  }
  func runningConversationCount(for host: Client) -> Int? {
    let workspace = saved.workspaces[host.id]
    let conversations = host.isDemo
      ? saved.demoThreads.filter { $0.hostId == host.id } + DemoData.threads.filter { $0.hostId == host.id }
      : workspace?.threads ?? []
    return SharedMobileRules.runningConversationCount(hostId: host.id, online: host.online, demo: host.isDemo,
      workspaceAvailable: workspace != nil, connectionMode: workspace?.connectionMode, conversations: conversations)
  }
  func isConnecting(_ host: Client) -> Bool {
    !host.isDemo && !host.online && connectionPhases[host.id] != .failed
  }
  func connectionLabel(for host: Client) -> String {
    if host.online { return L10n.tr("已连接") }
    if host.isDemo { return L10n.tr("离线 · 查看缓存") }
    switch connectionPhases[host.id] {
    case .failed: return L10n.tr("连接未完成 · 查看缓存")
    case .waiting: return L10n.tr("正在重新连接…")
    default: return L10n.tr("正在连接…")
    }
  }
  func prepareForegroundConnections() {
    for index in saved.clients.indices where !saved.clients[index].isDemo {
      saved.clients[index].online = false
      connectionPhases[saved.clients[index].id] = .connecting
    }
  }
  private func recordConnectionSuccess(_ host: Client) {
    lastConnectionSuccess[host.id] = ProcessInfo.processInfo.systemUptime
    if let index = saved.clients.firstIndex(where: { $0.id == host.id }), !saved.clients[index].online { saved.clients[index].online = true }
    if connectionPhases[host.id] != nil { connectionPhases.removeValue(forKey: host.id) }
  }
  private func recordConnectionFailure(_ failure: Error, host: Client, displayError: Bool = true, startedAt: TimeInterval? = nil) {
    let transient: Bool
    if let url = failure as? URLError {
      transient = [.networkConnectionLost, .notConnectedToInternet, .timedOut, .cannotFindHost,
        .cannotConnectToHost, .dnsLookupFailed, .resourceUnavailable, .backgroundSessionWasDisconnected].contains(url.code)
    } else if case HostServiceError.unavailable = failure { transient = true }
    else if case HostServiceError.rejected(let status, _) = failure { transient = [502, 503, 504].contains(status) }
    else { transient = false }
    // A slow read is independent of the event/receipt connection. A newer,
    // validated response from this Host disproves this request's offline inference.
    // Authentication, identity and protocol failures still fail closed.
    if transient, let startedAt, let success = lastConnectionSuccess[host.id], success > startedAt { return }
    if host.id == client?.id { suggestionNeedsFreshHistory = transient }
    if let index = saved.clients.firstIndex(where: { $0.id == host.id }) { saved.clients[index].online = false }
    connectionPhases[host.id] = transient ? .waiting : .failed
    #if DEBUG
    if host.id == client?.id {
      DesktopSuggestionDiagnostics.record(transient ? "transport-waiting" : "transport-failed",
        visible: desktopSuggestionReader.suggestion != nil)
    }
    #endif
    // A saved keyless binding can require authorization after a Host upgrade.
    // Background reads must not reopen a blocking alert every scan/retry.
    // Explicit connection attempts keep their own inline reauthorization action.
    if case HostServiceError.rejected(401, _) = failure, host.agentConnection == nil { return }
    if !transient && displayError && client?.id == host.id { presentError(failure) }
  }
  private func mobileUnreadSnapshots() async throws -> (scopes: [AgentRuntimeTarget], snapshots: [MobileUnreadSnapshot]) {
    // Keep one frozen input across batches; newer work cancels this projection.
    let state = saved
    let readingIndex = codexReadingIndex
    let hosts = state.clients.filter { !$0.isDemo }
    var scopes: [AgentRuntimeTarget] = []
    var snapshots: [MobileUnreadSnapshot] = []
    #if DEBUG
    var batchStart = HistoryScrollProbe.workStart()
    defer { HistoryScrollProbe.workEnd(1, batchStart) }
    #endif
    try Task.checkCancellation()
    for host in hosts {
      let workspace = state.workspaces[host.id]
      let context = ReadingKeyContext(host, workspace: workspace)
      scopes.append(context.machine)
      for thread in workspace?.threads ?? [] {
        // Bulk directory projection must not occupy one uninterrupted frame.
        // A brief suspension lets native scrolling/layout run between batches.
        if !snapshots.isEmpty && snapshots.count.isMultiple(of: 32) {
          #if DEBUG
          HistoryScrollProbe.workEnd(1, batchStart)
          batchStart = nil
          #endif
          try await Task.sleep(for: .milliseconds(1))
          #if DEBUG
          batchStart = HistoryScrollProbe.workStart()
          #endif
        }
        let key = readingKey(host, threadId: thread.id, context: context, state: state, readingIndex: readingIndex)
        let record = state.mobileReads?[key]
        let latest = thread.readState?.latest
        var localReply: (messageId: String, contentVersion: String)?
        if latest == nil, let reply = state.history[cacheKey(host, threadId: thread.id,
          state: state, machine: context.machine)]?.last(where: {
          $0.role == "assistant" && !["inProgress", "running", "streaming", "unknown"].contains($0.status ?? "completed")
        }) {
          localReply = (reply.id, ReadPosition.contentVersion(messageId: reply.id, text: reply.text, status: reply.status ?? "completed"))
        }
        snapshots.append(MobileUnreadSnapshot(target: context.machine, threadId: thread.id,
          latest: latest, read: record?.position,
          unread: ReadingReconciliation.unread(state: thread.readState, legacyUnread: thread.hasUnreadTurn == true,
            fallbackUnread: thread.showsUnreadDot, record: record, baseline: state.mobileReadBaselines?[key]),
          localReply: localReply))
      }
    }
    return (scopes, snapshots)
  }
  func synchronizeMobileUnread() {
    unreadProjectionTask?.cancel()
    guard !localRecoveryRequired else { return }
    let backgroundSave: SessionBackgroundSave? = UIApplication.shared.applicationState == .active
      ? nil : SessionBackgroundSave()
    backgroundSave?.start()
    unreadProjectionTask = Task { @MainActor [weak self] in
      guard let self else { backgroundSave?.finish(); return }
      do {
        let projection = try await mobileUnreadSnapshots()
        try Task.checkCancellation()
        guard !localRecoveryRequired else { backgroundSave?.finish(); return }
        unreadReconciler.submit(scopes: projection.scopes, snapshots: projection.snapshots) { [weak self] result in
          Task { @MainActor in
            defer { backgroundSave?.finish() }
            guard let self else { return }
            if case .failure(let failure) = result {
              self.error = (failure as? MobileUnreadPersistenceError)?.description
                ?? L10n.tr("无法保存手机阅读状态，已有记录已保留，请稍后重试。")
            }
            TaskNotificationController.shared.updateBadge()
          }
        }
      } catch is CancellationError {
        backgroundSave?.finish()
      } catch {
        backgroundSave?.finish()
      }
    }
  }
  var visibleReadPosition: ReadPosition? {
    guard let state = conversation?.readState, state.version == 1 else { return nil }
    let position = state.latest
    return messages.contains { $0.role == "assistant" && position.matches(messageId: $0.id, text: $0.text, status: $0.status) } ? position : nil
  }
  func recordVisibleContent() {
    guard let host = client, let thread = conversation, !host.isDemo, !historyLoading else { return }
    let reply = messages.last { $0.role == "assistant" && !["inProgress", "running", "streaming", "unknown"].contains($0.status ?? "completed") }
    let validPosition = visibleReadPosition
    if let validPosition { recordVisibleRead(validPosition) }
    let scope = mobileReadingTarget(host)
    let visible = MobileUnreadBadge.notices(target: scope, threadId: thread.id).filter { notice in
      let completedRun = notice.runId.map { run in
        ["idle", "failed"].contains(runtime?.state ?? "") && messages.contains {
          $0.role == "assistant" && $0.runId == run && $0.replyPhase != "commentary"
            && ["completed", "failed", "interrupted"].contains($0.status ?? "completed")
        }
      } ?? false
      if completedRun { return true }
      switch notice.kind {
      case .approval, .question:
        return runtime?.approvals.contains { ($0.notificationRequestId ?? $0.id) == notice.requestId && $0.turnId == notice.runId } == true
      case .plan: return runtime?.proposedPlan?.id == notice.requestId && runtime?.proposedPlan?.turnId == notice.runId
      case .paused: return runtime?.state == "paused" && runtime?.turnId == notice.runId
      case .uncertain: return runtime?.receipts.contains { $0.clientMessageId == notice.operationId } == true
      case .completed: return validPosition != nil && notice.runId == reply?.runId
      case .failed, .interrupted, .stopped:
        return runtime?.receipts.contains { $0.turnId == notice.runId && ["failed", "interrupted", "completed"].contains($0.state) } == true
      case .connection: return false
      }
    }
    let context = readingContextKey
    let value = MobileVisibleRead(target: scope, threadId: thread.id, position: validPosition,
      noticeIds: Set(visible.map(\.id)), legacyReply: thread.readState == nil ? reply.map {
        ($0.id, ReadPosition.contentVersion(messageId: $0.id, text: $0.text, status: $0.status ?? "completed"))
      } : nil)
    unreadReconciler.submitVisible(value) { [weak self] result in
      Task { @MainActor in
        guard let self else { return }
        if case .failure(let failure) = result, self.readingContextKey == context {
          self.error = (failure as? MobileUnreadPersistenceError)?.description
            ?? L10n.tr("无法保存手机阅读状态，已有记录已保留，请稍后重试。")
        }
        TaskNotificationController.shared.updateBadge()
      }
    }
  }
  private func mergeReadingSummary(_ incoming: Conversation, prior: Conversation?) -> Conversation {
    var result = incoming
    if result.archived == nil { result.archived = prior?.archived }
    // Losing the owner cannot confirm that an outstanding question was answered.
    if incoming.state == "unknown", let pending = prior?.pendingAsyncQuestionCount {
      result.pendingAsyncQuestionCount = max(pending, incoming.pendingAsyncQuestionCount ?? 0)
    }
    if let old = prior?.readState, let new = incoming.readState,
      old.version == new.version, old.latest.revision > new.latest.revision {
      result.readState = old
    }
    return result
  }
  private func reconcileReading(_ host: Client, updatedThreadId: String? = nil) {
    let readerId = readingClient(host).readingScopeId
    // A conversation snapshot updates one summary, not the entire directory.
    // Other pending acknowledgements must still be retried at the same time.
    let pendingKeys = Set((saved.mobileReads ?? [:]).compactMap { $0.value.pending ? $0.key : nil })
    for thread in saved.workspaces[host.id]?.threads ?? [] {
      let changed = updatedThreadId == nil || updatedThreadId == thread.id
      if !changed && pendingKeys.isEmpty { continue }
      let key = readingKey(host, threadId: thread.id)
      if changed, let state = thread.readState, state.version == 1, saved.mobileReadBaselines?[key] == nil {
        if saved.mobileReadBaselines == nil { saved.mobileReadBaselines = [:] }
        saved.mobileReadBaselines?[key] = MobileReadBaseline(position: state.latest, unread: thread.hasUnreadTurn == true)
      }
      if changed, let state = thread.readState, state.version == 1, state.readerId == readerId,
        let read = state.read,
        let next = ReadingReconciliation.acknowledgedChange(current: saved.mobileReads?[key], incoming: read, latest: state.latest) {
        if saved.mobileReads == nil { saved.mobileReads = [:] }
        saved.mobileReads?[key] = next
      }
      if pendingKeys.contains(key) { syncReading(host, threadId: thread.id, knownKey: key) }
    }
  }
  func recordVisibleRead(_ position: ReadPosition) {
    guard let host = client, let thread = conversation, !host.isDemo,
      let state = thread.readState, state.version == 1, state.latest == position,
      messages.contains(where: { $0.role == "assistant" && position.matches(messageId: $0.id, text: $0.text, status: $0.status) }) else { return }
    let key = readingKey(host, threadId: thread.id)
    let old = saved.mobileReads?[key]
    guard old?.position != position, (old?.position.revision ?? 0) <= position.revision else {
      syncReading(host, threadId: thread.id); return
    }
    if saved.mobileReads == nil { saved.mobileReads = [:] }
    saved.mobileReads?[key] = MobileReadRecord(position: position, pending: true)
    persist()
    syncReading(host, threadId: thread.id)
  }
  private func syncReading(_ host: Client, threadId: String, knownKey: String? = nil) {
    let key = knownKey ?? readingKey(host, threadId: threadId)
    guard readingTasks[key] == nil, host.online,
      saved.workspaces[host.id]?.capabilities?.contains("threads:read-state") == true,
      let record = saved.mobileReads?[key], record.pending else { return }
    let scoped = readingClient(host), position = record.position
    readingTasks[key] = Task {
      defer {
        readingTasks.removeValue(forKey: key)
        if saved.mobileReads?[key]?.position != position { syncReading(host, threadId: threadId) }
      }
      do {
        try await persistBeforeCommand()
        guard saved.mobileReads?[key]?.position == position,
          readingClient(host).readingScopeId == scoped.readingScopeId,
          saved.clients.contains(where: { $0.id == host.id && $0.endpoint == host.endpoint && $0.agentConnection == host.agentConnection }) else { return }
        let acknowledgement = try await AgentClients.resolve(scoped).acknowledgeRead(scoped,
          threadId: threadId, readerId: scoped.readingScopeId!, position: position)
        guard acknowledgement.hostId == host.id, acknowledgement.threadId == threadId,
          acknowledgement.target == scoped.readingTarget,
          acknowledgement.readerId == scoped.readingScopeId, acknowledgement.read == position,
          saved.mobileReads?[key]?.position == position else { return }
        saved.mobileReads?[key]?.pending = false
        persist()
      } catch HostServiceError.rejected(let status, _) where status == 409 {
        // This version was read, but a newer reply must stay unread. No replay of newer content.
        if saved.mobileReads?[key]?.position == position { saved.mobileReads?[key]?.pending = false; persist() }
      } catch {
        // Keep the local position and retry the same acknowledgement on reconnect.
      }
    }
  }
  var project: Project? {
    let id = conversation?.projectId ?? selectedProjectId
    return projects.first { $0.id == id }
  }
  var targetKey: String {
    client.map { cacheKey($0, threadId: selectedThreadId, projectId: selectedProjectId) } ?? "none:new:none"
  }
  private func captureCodexStorageKeys() {
    #if DEBUG
    let workStart = HistoryScrollProbe.workStart()
    defer { HistoryScrollProbe.workEnd(7, workStart) }
    #endif
    let readingKeys = Set((saved.mobileReads ?? [:]).keys).union((saved.mobileReadBaselines ?? [:]).keys)
    if readingKeys != codexReadingIndexKeys {
      codexReadingIndexKeys = readingKeys
      codexReadingIndex = CodexMachineStorage.ReadingKeyIndex(keys: readingKeys)
      codexReadingMemo = CodexMachineStorage.ReadingKeyMemo()
    }
    var keys = Set(saved.history.keys).union(saved.drafts.keys).union(drafts.values.keys)
    keys.formUnion((saved.runtimes ?? [:]).keys); keys.formUnion((saved.outbox ?? [:]).keys)
    keys.formUnion((saved.detachedReplies ?? [:]).keys)
    keys.formUnion((saved.desktopCreations ?? [:]).keys); keys.formUnion((saved.detachedDesktopCreations ?? [:]).keys)
    keys.formUnion((saved.agentEventCursors ?? [:]).keys); keys.formUnion((saved.dismissedReceiptFailures ?? [:]).keys)
    for key in (saved.generationPreferences ?? [:]).keys where key.hasPrefix("generation:") { keys.insert(String(key.dropFirst("generation:".count))) }
    let contexts = saved.clients.reduce(into: [String: AgentRuntimeTarget]()) { values, host in
      values[host.id] = host.codexDirect?.target ?? saved.workspaces[host.id]?.readTarget
    }
    if keys != codexStorageKeySet || contexts != codexStorageContexts {
      codexStorageKeySet = keys
      codexStorageContexts = contexts
      codexStorageKeys = Array(keys)
      codexStorageCandidateCache = [:]
    }
  }
  private func compatibleCodexStorageKeys(_ host: Client, machine: AgentRuntimeTarget, threadId: String?, projectId: String?, canonical: String) -> [String] {
    if let cached = codexStorageCandidateCache[canonical] { return cached }
    let candidates = CodexMachineStorage.compatibleCacheKeys(machine, threadId: threadId, projectId: projectId, keys: codexStorageKeys,
      legacyDirectTarget: host.codexDirect?.target ?? saved.workspaces[host.id]?.readTarget)
    codexStorageCandidateCache[canonical] = candidates
    return candidates
  }
  private func cacheKey(_ host: Client, threadId: String?, projectId: String? = nil) -> String {
    let state = saved
    let machine = ReadingKeyContext(host, workspace: state.workspaces[host.id]).machine
    return cacheKey(host, threadId: threadId, projectId: projectId, state: state, machine: machine)
  }
  private func cacheKey(_ host: Client, threadId: String?, projectId: String? = nil,
    state: SavedSession, machine: AgentRuntimeTarget) -> String {
    guard !host.isDemo, machine.agentId == "codex" else { return SharedMobileRules.cacheKey(host, threadId: threadId, projectId: projectId, workspace: state.workspaces[host.id]) }
    let canonical = SharedMobileRules.machineCacheKey(machine, threadId: threadId, projectId: projectId)
    return SharedMobileRules.preferredRuntimeKey(canonical, candidates: compatibleCodexStorageKeys(host, machine: machine, threadId: threadId, projectId: projectId, canonical: canonical), selectedLegacy: state.codexStorageAliases?[canonical])
  }
  var codexStorageConflictMessage: String? {
    guard let host = client, !host.isDemo else { return nil }
    let machine = mobileReadingTarget(host); guard machine.agentId == "codex" else { return nil }
    let canonical = SharedMobileRules.machineCacheKey(machine, threadId: selectedThreadId, projectId: selectedProjectId)
    let candidates = compatibleCodexStorageKeys(host, machine: machine, threadId: selectedThreadId, projectId: selectedProjectId, canonical: canonical)
    let operationKeys = candidates.filter { saved.outbox?[$0] != nil || saved.desktopCreations?[$0] != nil }
    let selected = saved.codexStorageAliases?[canonical].map { candidates.contains($0) } == true
    if (!candidates.contains(canonical) && !selected && candidates.count > 1) || operationKeys.contains(where: { $0 != targetKey }) {
      return L10n.tr("这台电脑的旧草稿或原回执范围有冲突，全部记录已保留。核对原记录后再提交；不会覆盖当前草稿或重放未知操作。")
    }
    return nil
  }
  var codexLegacyStorageChoices: [String] {
    guard let host = client, !host.isDemo else { return [] }
    let machine = mobileReadingTarget(host); guard machine.agentId == "codex" else { return [] }
    let canonical = SharedMobileRules.machineCacheKey(machine, threadId: selectedThreadId, projectId: selectedProjectId)
    let candidates = compatibleCodexStorageKeys(host, machine: machine, threadId: selectedThreadId, projectId: selectedProjectId, canonical: canonical)
    return candidates.count < 2 ? [] : candidates
  }
  func selectCodexLegacyStorage(_ key: String) {
    guard let host = client, codexLegacyStorageChoices.contains(key) else { return }
    let canonical = SharedMobileRules.machineCacheKey(mobileReadingTarget(host), threadId: selectedThreadId, projectId: selectedProjectId)
    if saved.codexStorageAliases == nil { saved.codexStorageAliases = [:] }
    saved.codexStorageAliases?[canonical] = key
    clientGeneration = UUID(); historyGeneration = UUID()
    persist()
  }
  private func preserveCodexReaderScopeIds() {
    for host in saved.clients where !host.isDemo {
      let target = mobileReadingTarget(host); guard target.agentId == "codex" else { continue }
      let key = CodexMachineStorage.readerScope(target)
      guard saved.codexReaderScopeIds?[key] == nil else { continue }
      let legacy = ReadingIdentity.legacyKey(agentId: target.agentId, authority: target.account?.authority, subject: target.account?.subject,
        hostId: target.hostId, workspaceId: target.workspaceId, runtimeId: target.runtimeId, threadId: nil)
      if saved.codexReaderScopeIds == nil { saved.codexReaderScopeIds = [:] }
      let readerId = ReadingIdentity.readerId(deviceId: saved.readingDeviceId ?? "", targetKey: legacy)
      saved.codexReaderScopeIds?[key] = readerId
    }
  }
  private static func legacyCacheKey(_ client: Client, threadId: String?, projectId: String? = nil) -> String {
    guard let target = client.agentConnection?.target else { return client.id + ":" + (threadId ?? "new:" + (projectId ?? "none")) }
    let scope: [Any] = [target.agentId, target.account?.authority as Any? ?? NSNull(), target.account?.subject as Any? ?? NSNull(), target.hostId, target.workspaceId, target.runtimeId, threadId == nil ? "draft" : "thread", threadId as Any? ?? projectId as Any? ?? NSNull()]
    // Typed tuple framing prevents delimiter, draft/thread and account collisions.
    let data = try! JSONSerialization.data(withJSONObject: scope)
    return "agent-v1:" + data.base64EncodedString()
  }
  var draft: Draft { drafts.values[targetKey] ?? Draft() }
  var model: String {
    get { draft.model ?? "" }
    set {
      guard canConfigureGeneration else { return }
      let priorEffort = selectedEffort
      drafts.values[targetKey, default: Draft()].model = newValue.isEmpty ? nil : newValue
      let chosen = models.first { $0.id == newValue }
      let choice = SessionPolicies.shared.changeModel(priorEffort: priorEffort, draftSpeed: draft.speed,
        supportedEfforts: chosen?.supportedEfforts ?? [], defaultEffort: chosen?.defaultEffort,
        supportedSpeeds: chosen?.supportedSpeeds ?? [])
      drafts.values[targetKey, default: Draft()].effort = choice.effort
      drafts.values[targetKey, default: Draft()].speed = choice.speed
      rememberGeneration(.model)
      persist()
    }
  }
  var effort: String {
    get { draft.effort ?? "" }
    set { guard canConfigureGeneration else { return }; drafts.values[targetKey, default: Draft()].effort = newValue.isEmpty ? nil : newValue; rememberGeneration(.effort); persist() }
  }
  var permissionMode: String {
    get { draft.permissionMode ?? "" }
    set { guard canConfigureGeneration else { return }; drafts.values[targetKey, default: Draft()].permissionMode = newValue.isEmpty ? nil : newValue; rememberGeneration(.permission); persist() }
  }
  var speed: String {
    get { draft.speed ?? "" }
    set {
      guard canConfigureGeneration, supportsGenerationSpeed else { return }
      drafts.values[targetKey, default: Draft()].speed = newValue.isEmpty ? nil : newValue
      rememberGeneration(.speed)
      persist()
    }
  }
  var supportsGenerationSpeed: Bool { client?.isDemo == true || saved.workspaces[client?.id ?? ""]?.capabilities?.contains("generation-speed") == true }
  var availableSpeeds: [String] { selectedModel?.supportedSpeeds ?? [] }
  var selectedSpeed: String? { draft.speed ?? runtime?.generationSettings?.speed ?? selectedModel?.defaultSpeed }
  func speedLabel(_ speed: String?) -> String {
    switch speed { case "fast": L10n.tr("快速"); case "ultrafast": L10n.tr("极速"); case "standard": L10n.tr("标准"); default: L10n.tr("跟随电脑") }
  }
  private var generationPreferenceKey: String? {
    client.map { "generation:" + cacheKey($0, threadId: nil) }
  }
  private func rememberGeneration(_ edit: GenerationPreferenceEdit) {
    guard let key = generationPreferenceKey else { return }
    let preference = saved.generationPreferences?[key] ?? GenerationOptions()
    let next = GenerationPreferenceRules.shared.remember(
      previous: GenerationPreferenceValues(model: preference.model, effort: preference.effort, permissionMode: preference.permissionMode, speed: preference.speed),
      current: GenerationPreferenceValues(model: draft.model, effort: draft.effort, permissionMode: draft.permissionMode, speed: draft.speed), edit: edit)
    if saved.generationPreferences == nil { saved.generationPreferences = [:] }
    saved.generationPreferences?[key] = GenerationOptions(model: next.model, effort: next.effort, permissionMode: next.permissionMode, speed: next.speed)
  }
  private func applyNewConversationDefaults() {
    guard let key = generationPreferenceKey, let preference = saved.generationPreferences?[key] else { return }
    let current = draft
    guard SessionPolicies.shared.inheritDefaults(threadSelected: selectedThreadId != nil, replyPending: pending != nil,
      creationPending: pendingDesktopCreation != nil, text: current.text, attachmentCount: Int32(current.attachments.count),
      operationId: current.operationId) else { return }
    // Workspace refreshes may reapply defaults while an empty draft's photo
    // picker is open. Preserve its import generation so that the captured
    // picker still commits only to that original draft.
    var updated = current
    updated.model = preference.model
    updated.effort = preference.effort
    updated.permissionMode = preference.permissionMode
    updated.speed = preference.speed
    drafts.values[targetKey] = updated
  }
  var supportsGenerationSettings: Bool { client?.isDemo == true || saved.workspaces[client?.id ?? ""]?.capabilities?.contains("generation-settings") == true }
  var modelLoadingKey: String? {
    guard pendingConnection == nil, supportsGenerationSettings, let host = client else { return nil }
    return host.id + ":" + clientGeneration.uuidString
  }
  var canConfigureGeneration: Bool { canCompose && SessionPolicies.shared.configureGeneration(access: accessPolicy, state: submissionPolicy) }
  var selectedModel: CodexModel? { models.first { $0.id == (draft.model ?? runtime?.generationSettings?.model) } }
  var modelLabel: String { selectedModel?.name ?? draft.model ?? runtime?.generationSettings?.model ?? L10n.tr("电脑默认") }
  var availableEfforts: [String] { selectedModel?.supportedEfforts ?? [] }
  var selectedEffort: String? { draft.effort ?? runtime?.generationSettings?.effort }
  var generationDisplay: GenerationDisplay {
    SessionPolicies.shared.generationDisplay(threadSelected: selectedThreadId != nil,
      draftModel: draft.model, draftEffort: draft.effort,
      runtimeModel: runtime?.generationSettings?.model, runtimeEffort: runtime?.generationSettings?.effort)
  }
  var displayedModelLabel: String {
    let model = generationDisplay.model
    return models.first { $0.id == model }?.name ?? model ?? L10n.tr(selectedThreadId == nil ? "跟随电脑" : "同步中")
  }
  var displayedEffortLabel: String {
    generationDisplay.effort.map { effortLabel($0) } ?? L10n.tr("跟随电脑")
  }
  var selectedPermissionMode: String? { draft.permissionMode ?? runtime?.generationSettings?.permissionMode }
  var permissionOptions: [AgentPermissionOption] { guard let client, let agent = try? AgentClients.resolve(client) else { return [] }; return agent.permissionOptions(client) }
  var selectedPermissionOption: AgentPermissionOption? { permissionOptions.first { $0.id == selectedPermissionMode } }
  var permissionLabel: String { selectedPermissionOption?.title ?? L10n.tr("跟随电脑权限") }
  func effortLabel(_ value: String?) -> String {
    switch value { case "none": L10n.tr("无"); case "minimal": L10n.tr("最小"); case "low": L10n.tr("低"); case "medium": L10n.tr("中"); case "high": L10n.tr("高"); case "xhigh": L10n.tr("极高"); case "max": L10n.tr("最高"); case "ultra": L10n.tr("超高"); case .some(let text): text; case nil: L10n.tr("默认强度") }
  }

  var messages: [ChatMessage] {
    if let cached = saved.history[targetKey] {
      guard client?.isDemo == true,
        let sample = DemoData.messages.first(where: { $0.id == "demo-assistant" })
      else { return cached }
      // Older builds saved this app-owned sample alongside the demo conversation.
      return cached.map { message in
        guard message.id == sample.id else { return message }
        var updated = message
        updated.text = sample.text
        return updated
      }
    }
    guard client?.isDemo == true, selectedThreadId != nil else { return [] }
    return selectedThreadId == "navigation"
      ? DemoData.messages
      : [ChatMessage(id: "sample", role: "assistant", text: L10n.tr("这是本地示例对话，可以继续输入文字或选择附件来体验界面。"))]
  }
  private var accessPolicy: SessionAccess {
    let workspace = saved.workspaces[client?.id ?? ""]
    return SessionAccess(demo: client?.isDemo == true, online: client?.online == true,
      runtimeConnected: (workspace?.agentConnectionState?.runtimeConnected).map { KotlinBoolean(bool: $0) },
      capabilities: workspace?.capabilities ?? [], threadSelected: selectedThreadId != nil,
      hasProject: project != nil, projectlessDraft: isProjectlessDraft, canControl: runtime?.canControl == true,
      testing: testMode)
  }
  private var submissionPolicy: SubmissionState {
    SubmissionState(historyLoading: initialHistoryLoading, sending: sendingKeys.contains(targetKey),
      stopping: stoppingKeys.contains(targetKey), replyPending: pending != nil,
      creationPending: pendingDesktopCreation != nil, importing: isImporting,
      running: isRunning, preferredReplyMode: replyMode)
  }
  var isWritable: Bool { conversation?.archived != true && SessionPolicies.shared.writable(access: accessPolicy) }
  var canCompose: Bool { conversation?.archived != true && codexStorageConflictMessage == nil && SessionPolicies.shared.compose(access: accessPolicy) }
  var canCreateThread: Bool { SessionPolicies.shared.createThread(access: accessPolicy) }
  var canCreateProject: Bool {
    client?.isDemo == true || saved.workspaces[client?.id ?? ""]?.capabilities?.contains("projects:create") == true
  }
  var canCreateProjectless: Bool { SessionPolicies.shared.createProjectless(access: accessPolicy) }
  private func hasDesktopCapability(_ name: String, hostId: String? = nil) -> Bool {
    let workspace = saved.workspaces[hostId ?? client?.id ?? ""]
    let access = SessionAccess(demo: false, online: false, runtimeConnected: nil,
      capabilities: workspace?.capabilities ?? [], threadSelected: false, hasProject: false,
      projectlessDraft: false, canControl: false, testing: testMode)
    return SessionPolicies.shared.desktop(access: access, capability: name)
  }
  var canConnectForReply: Bool { SessionPolicies.shared.connectForReply(access: accessPolicy) }


  var forkSource: ThreadForkSource? {
    guard let host = client, let threadId = selectedThreadId else { return nil }
    return ThreadForkSource(target: mobileReadingTarget(host), threadId: threadId)
  }
  private func forkClient(for source: ThreadForkSource) -> Client? {
    saved.clients.first { $0.id == source.target.hostId && mobileReadingTarget($0) == source.target }
  }
  private func forkBaseReason(_ source: ThreadForkSource?) -> String? {
    let host = source.flatMap { forkClient(for: $0) }
    let workspace = host.flatMap { saved.workspaces[$0.id] }
    let reason = ThreadForkRules.shared.baseReason(supportedSource: host != nil && host?.isDemo == false && source?.target.agentId == "codex",
      desktopConnected: workspace?.connectionMode == "desktop-runtime" && workspace?.agentConnectionState?.runtimeConnected != false,
      capabilities: workspace?.capabilities ?? [], online: host?.online == true)
    return reason.map { L10n.tr(LocalizedText(stringLiteral: $0)) }
  }
  func forkReason(source: ThreadForkSource?, turnId: String?, environment: ThreadForkEnvironment) -> String? {
    let options = source.flatMap { forkOptionsBySource[$0.key] }
    let availability = options.map { ForkAvailability(sameDirectory: $0.sameDirectoryAvailable, worktree: $0.worktreeAvailable,
      reason: $0.reason, worktreeReason: $0.worktreeReason) }
    let reason = ThreadForkRules.shared.reason(turnId: turnId, baseReason: forkBaseReason(source),
      pending: source.map { visibleForks($0).contains(where: { $0.phase.isPending && $0.detached != true }) } ?? false,
      options: availability, environment: environment.rawValue,
      worktreeCapability: source.map { saved.workspaces[$0.target.hostId]?.capabilities?.contains("threads:fork:worktree") == true } ?? false,
      optionError: source.flatMap { forkOptionErrors[$0.key] })
    return reason.map { L10n.tr(LocalizedText(stringLiteral: $0)) }
  }
  func loadForkOptions(_ source: ThreadForkSource?, refresh: Bool = false) async {
    guard let source, forkBaseReason(source) == nil, let host = forkClient(for: source),
      !forkOptionsLoading.contains(source.key) else { return }
    guard refresh || (forkOptionsBySource[source.key] == nil && forkOptionErrors[source.key] == nil) else { return }
    forkOptionsLoading.insert(source.key)
    defer { forkOptionsLoading.remove(source.key) }
    do {
      let options = try await AgentClients.resolve(host).forkOptions(host, threadId: source.threadId)
      guard options.hostId == host.id, options.threadId == source.threadId,
        forkClient(for: source) != nil else { throw HostServiceError.wrongHost }
      forkOptionsBySource[source.key] = options
      forkOptionErrors.removeValue(forKey: source.key)
    } catch {
      forkOptionsBySource.removeValue(forKey: source.key)
      forkOptionErrors[source.key] = error.userFacingDescription
    }
  }
  func visibleForks(_ source: ThreadForkSource? = nil) -> [PendingThreadFork] {
    guard let source = source ?? forkSource else { return [] }
    return (saved.threadForks ?? [:]).values.filter { $0.source == source }.sorted { $0.createdAt < $1.createdAt }
  }
  func forkBusy(_ source: ThreadForkSource?) -> Bool {
    visibleForks(source).contains { $0.detached != true && ($0.phase.isPending || forkSubmittingIDs.contains($0.id)) }
  }
  func isQueryingFork(_ id: String) -> Bool { forkQueryingIDs.contains(id) }
  func forkThread(source: ThreadForkSource?, turnId: String?, environment: ThreadForkEnvironment) {
    guard let source, source == forkSource, let turnId, let host = forkClient(for: source),
      forkReason(source: source, turnId: turnId, environment: environment) == nil else { return }
    guard requireAISharing(host) else { return }
    let request = ThreadForkRequest(hostId: host.id, threadId: source.threadId, turnId: turnId,
      clientOperationId: UUID().uuidString, environment: environment)
    let pending = PendingThreadFork(request: request, sourceTarget: source.target, createdAt: Date(), phase: .submitted)
    if saved.threadForks == nil { saved.threadForks = [:] }
    saved.threadForks?[pending.id] = pending
    forkNavigationOwners[source.key] = pending.id
    forkSubmittingIDs.insert(pending.id)
    let navigation = ForkNavigation(client: clientGeneration, history: historyGeneration, draft: draft)
    Task {
      defer { forkSubmittingIDs.remove(pending.id) }
      do { try await persistBeforeCommand() }
      catch {
        saved.threadForks?[pending.id]?.phase = .rejected
        saved.threadForks?[pending.id]?.error = L10n.tr("未发送分支请求：无法保存本地操作。")
        persist()
        return
      }
      do {
        // Only this path submits. Every recovery path below queries the same ID.
        let operation = try await AgentClients.resolve(host).forkThread(host, request: request)
        try await acceptFork(operation, host: host, pending: pending, navigation: navigation)
      } catch { recordForkFailure(error, pending: pending) }
      observeFork(host, pending: pending, navigation: navigation)
    }
  }
  private func recordForkFailure(_ failure: Error, pending: PendingThreadFork) {
    guard saved.threadForks?[pending.id]?.request == pending.request else { return }
    if saved.threadForks?[pending.id]?.phase.isPending == true {
      saved.threadForks?[pending.id]?.phase = .outcomeUnknown
    }
    saved.threadForks?[pending.id]?.error = failure.userFacingDescription + L10n.tr("\n请查询原回执；不会再次提交。")
    persist()
  }
  private func acceptFork(_ operation: ThreadForkOperation, host: Client, pending: PendingThreadFork,
    navigation: ForkNavigation?) async throws {
    guard operation.request == pending.request, saved.threadForks?[pending.id]?.request == pending.request,
      forkClient(for: pending.source) != nil else { throw HostServiceError.wrongHost }
    if operation.phase == .confirmed {
      guard let id = operation.forkedThreadId, !id.isEmpty, id != pending.request.threadId else {
        throw HostServiceError.invalidResponse
      }
    }
    // A late submitted/unknown response must not roll back a terminal receipt.
    if let prior = saved.threadForks?[pending.id],
      !ThreadForkRules.shared.mayAcceptOutcome(previousPhase: prior.phase.rawValue, previousFork: prior.forkedThreadId,
        previousProject: prior.projectId, phase: operation.phase.rawValue, fork: operation.forkedThreadId, project: operation.projectId) {
      throw HostServiceError.invalidResponse
    }
    saved.threadForks?[pending.id]?.phase = operation.phase
    saved.threadForks?[pending.id]?.forkedThreadId = operation.forkedThreadId
    saved.threadForks?[pending.id]?.projectId = operation.projectId
    saved.threadForks?[pending.id]?.error = operation.error
    persist()
    if operation.phase == .confirmed, let current = saved.threadForks?[pending.id],
      canOpenFork(current, navigation: navigation) {
      try await openConfirmedFork(current, host: host, navigation: navigation)
    }
  }
  private func canOpenFork(_ pending: PendingThreadFork, navigation: ForkNavigation?) -> Bool {
    guard let navigation else { return false }
    return navigation.client == clientGeneration && navigation.history == historyGeneration && navigation.draft == draft
      && !drawerOpen
      && forkSource == pending.source && forkClient(for: pending.source) != nil
      && saved.threadForks?[pending.id]?.detached != true
      && forkNavigationOwners[pending.source.key] == pending.id
  }
  private func openConfirmedFork(_ pending: PendingThreadFork, host: Client, navigation: ForkNavigation?) async throws {
    guard let id = pending.forkedThreadId, pending.phase == .confirmed,
      canOpenFork(pending, navigation: navigation) else { return }
    let agent = try AgentClients.resolve(host)
    let workspace = try await completeReadingThreadPages(
      try await agent.workspace(readingClient(host), projectCursor: nil, threadCursor: nil), host: host)
    guard workspace.hostId == host.id, workspace.connectionMode == "desktop-runtime",
      workspace.projects.allSatisfy({ $0.hostId == host.id }), workspace.threads.allSatisfy({ $0.hostId == host.id }),
      workspace.readTarget == nil || workspace.readTarget == pending.sourceTarget else { throw HostServiceError.wrongHost }
    let history = try await agent.history(readingClient(host), threadId: id, cursor: nil)
    guard history.hostId == host.id, history.threadId == id,
      history.readTarget == nil || history.readTarget == pending.sourceTarget,
      let thread = workspace.threads.first(where: { $0.id == id }) ?? history.thread,
      thread.id == id, thread.hostId == host.id, thread.projectId == pending.projectId else { throw HostServiceError.wrongHost }
    try await waitForSidebarInteraction()
    // Switching away and back invalidates this intent too; source equality alone is insufficient.
    guard canOpenFork(pending, navigation: navigation) else { return }
    var snapshot = workspace
    if !snapshot.threads.contains(where: { $0.id == id }) { snapshot.threads.insert(thread, at: 0) }
    saved.workspaces[host.id] = snapshot
    openThread(thread, loadAutomatically: false)
    apply(history)
    startEvents()
    persist()
  }
  private func observeFork(_ host: Client, pending: PendingThreadFork, navigation: ForkNavigation?) {
    guard saved.threadForks?[pending.id]?.phase.isPending == true,
      saved.threadForks?[pending.id]?.detached != true, forkObservers[pending.id] == nil else { return }
    forkObservers[pending.id] = Task { [weak self] in
      guard let self else { return }
      defer { forkObservers.removeValue(forKey: pending.id) }
      let deadline = ProcessInfo.processInfo.systemUptime + 120
      var delay = 1.0
      while !Task.isCancelled && ProcessInfo.processInfo.systemUptime < deadline,
        saved.threadForks?[pending.id]?.phase.isPending == true,
        saved.threadForks?[pending.id]?.detached != true {
        do { try await Task.sleep(for: .seconds(delay)) } catch { return }
        guard !forkQueryingIDs.contains(pending.id), forkClient(for: pending.source) != nil else { continue }
        do {
          let operation = try await AgentClients.resolve(host).forkStatus(host, threadId: pending.request.threadId, operationId: pending.id)
          try await acceptFork(operation, host: host, pending: pending, navigation: navigation)
        } catch {
          if Task.isCancelled { return }
          recordForkFailure(error, pending: pending)
          if case HostServiceError.rejected(let status, _) = error, [400, 401, 403, 404, 405, 409].contains(status) { return }
          if case HostServiceError.wrongHost = error { return }
        }
        delay = min(delay * 2, 10)
      }
    }
  }
  func queryFork(_ pending: PendingThreadFork) {
    guard forkSource == pending.source, let host = forkClient(for: pending.source),
      !forkQueryingIDs.contains(pending.id), !forkSubmittingIDs.contains(pending.id) else { return }
    forkQueryingIDs.insert(pending.id)
    // Detached uncertain receipts are read-only. Opening a known child is a new explicit navigation intent.
    if pending.phase == .confirmed { saved.threadForks?[pending.id]?.detached = false }
    let mayNavigate = pending.detached != true || pending.phase == .confirmed
    if mayNavigate { forkNavigationOwners[pending.source.key] = pending.id }
    let navigation = mayNavigate ? ForkNavigation(client: clientGeneration, history: historyGeneration, draft: draft) : nil
    Task {
      defer { forkQueryingIDs.remove(pending.id) }
      do {
        let operation = try await AgentClients.resolve(host).forkStatus(host, threadId: pending.request.threadId, operationId: pending.id)
        try await acceptFork(operation, host: host, pending: pending, navigation: navigation)
      } catch { recordForkFailure(error, pending: pending) }
      observeFork(host, pending: pending, navigation: navigation)
    }
  }
  func detachFork(_ pending: PendingThreadFork) {
    guard forkSource == pending.source, saved.threadForks?[pending.id]?.request == pending.request,
      saved.threadForks?[pending.id]?.phase.isPending == true else { return }
    saved.threadForks?[pending.id]?.detached = true
    forkObservers[pending.id]?.cancel()
    forkObservers.removeValue(forKey: pending.id)
    if forkNavigationOwners[pending.source.key] == pending.id {
      forkNavigationOwners.removeValue(forKey: pending.source.key)
    }
    persist()
  }
  var isProjectlessDraft: Bool { selectedThreadId == nil && saved.newProjects[client?.id ?? ""] == "__projectless__" }
  var runtime: ThreadRuntime? { saved.runtimes?[targetKey] }
  var supportsPlanMode: Bool {
    client?.isDemo == true || saved.workspaces[client?.id ?? ""]?.capabilities?.contains("plan-mode") == true
  }
  var selectedCollaborationMode: String { draft.collaborationMode ?? runtime?.collaborationMode ?? "default" }
  var canSelectPlanMode: Bool { canCompose && SessionPolicies.shared.selectPlan(access: accessPolicy, state: submissionPolicy) }
  func setPlanMode(_ enabled: Bool) {
    guard canSelectPlanMode else { return }
    drafts.values[targetKey, default: Draft()].collaborationMode = enabled ? "plan" : "default"
    persist()
  }
  func canRespondToPlan(_ plan: ProposedPlan) -> Bool {
    // Keep the cheap guards ahead of composer/content allocation.
    guard SessionPolicies.shared.planPrerequisites(currentPlanId: runtime?.proposedPlan?.id,
      expectedPlanId: plan.id, running: isRunning, supportsPlan: supportsPlanMode,
      threadSelected: selectedThreadId != nil) else { return false }
    return SessionPolicies.shared.respondToPlan(currentPlanId: runtime?.proposedPlan?.id, expectedPlanId: plan.id,
      running: isRunning, supportsPlan: supportsPlanMode, threadSelected: selectedThreadId != nil,
      submittable: canSubmit(Draft(text: L10n.tr("实施此计划。"))))
  }

  func implementPlan(_ plan: ProposedPlan) {
    guard canRespondToPlan(plan) else { return }
    send(content: Draft(text: "PLEASE IMPLEMENT THIS PLAN:\n" + plan.text, collaborationMode: "default"), expectedPlanId: plan.id)
  }
  func revisePlan(_ plan: ProposedPlan, feedback: String) {
    guard canRespondToPlan(plan), !feedback.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
    send(content: Draft(text: feedback, collaborationMode: "plan"), expectedPlanId: plan.id)
  }
  var queuedReplies: [DeliveryReceipt] { runtime?.receipts.filter { $0.hostId == client?.id && $0.threadId == selectedThreadId && QueueRules.shared.active(state: $0.state, source: $0.queueSource, present: $0.queuePresent.map { KotlinBoolean(bool: $0) }, disposition: $0.queueDisposition) } ?? [] }
  var receiptNotices: [DeliveryReceipt] {
    let receipts = runtime?.receipts.filter { $0.hostId == client?.id && $0.threadId == selectedThreadId } ?? []
    let dismissed = saved.dismissedReceiptFailures?[targetKey] ?? []
    let sharedIndices = Set(SharedMobileRules.noticeIndices(receipts, dismissed: dismissed, pendingId: pending?.clientMessageId))
    return receipts.enumerated().compactMap { index, receipt in
      // Native queue synchronization failures remain visible even after acceptance.
      if QueueRules.shared.hasSyncError(error: receipt.queueSyncError) { return receipt }
      if receipt.state == "uncertain",
        saved.detachedReplies?[targetKey]?.contains(where: { $0.id == receipt.id }) == true { return nil }
      return sharedIndices.contains(index) ? receipt : nil
    }
  }

  func dismissReceiptFailure(_ receipt: DeliveryReceipt, expectedTargetKey: String) {
    guard targetKey == expectedTargetKey, receipt.hostId == client?.id,
      receipt.threadId == selectedThreadId,
      runtime?.receipts.contains(where: { $0.id == receipt.id && $0.state == "failed" }) == true else { return }
    if saved.dismissedReceiptFailures == nil { saved.dismissedReceiptFailures = [:] }
    saved.dismissedReceiptFailures?[targetKey, default: []].insert(receipt.id)
    persist()
  }
  var isRunning: Bool { SessionPolicies.shared.running(turnId: runtime?.turnId, state: runtime?.state) }
  var pending: PendingReply? { saved.outbox?[targetKey] }
  func detachedReplies(for key: String) -> [DetachedReplyRecord] { saved.detachedReplies?[key] ?? [] }
  var showsReplyMode: Bool {
    MobileRules.shared.showsReplyMode(running: isRunning, composable: canCompose,
      modesAvailable: !runningReplyModes.isEmpty, text: draft.text,
      attachmentCount: Int32(draft.attachments.count))
  }
  var pendingDesktopCreation: PendingDesktopCreation? { saved.desktopCreations?[targetKey] }
  var detachedDesktopCreations: [PendingDesktopCreation] { saved.detachedDesktopCreations?[targetKey] ?? [] }
  var detachingDesktopCreation: Bool { detachingCreationKeys.contains(targetKey) }
  var historyLoading: Bool { (loadingHistoryRequests[targetKey] ?? 0) > 0 }
  // Only the first read of this navigation can block composition. Subsequent
  // catch-up and paging reads must not change a ready composer's availability.
  var initialHistoryLoading: Bool { historyLoading && loadedHistoryGeneration != historyGeneration }
  #if DEBUG
  var devQueueReadiness: String {
    "online=\(client?.online == true),compose=\(canCompose),writable=\(isWritable),running=\(isRunning),history=\(historyLoading),confirmed=\(loadedHistoryGeneration == historyGeneration),sending=\(sendingKeys.contains(targetKey)),pending=\(pending != nil),creation=\(pendingDesktopCreation != nil),stopping=\(stoppingKeys.contains(targetKey)),importing=\(isImporting),modes=\(runningReplyModes.joined(separator: ",")),draft=\(!draft.text.isEmpty),action=\(devQueueActionTrace)"
  }
  #endif
  var transferStatus: String? { transfers[targetKey] }
  func clientTypeName(for host: Client?) -> String {
    guard let host else { return L10n.tr("客户端") }
    // Hosts predating clientType only support Codex. Keep their saved connections readable.
    let name = saved.workspaces[host.id]?.clientType?.name.trimmingCharacters(in: .whitespacesAndNewlines)
    if let name, !name.isEmpty { return name }
    return "Codex"
  }
  var clientTypeName: String { clientTypeName(for: client) }
  func sourceLabel(for host: Client?) -> String {
    guard let host else { return L10n.tr("未连接") }
    if host.isDemo { return L10n.tr("界面体验 · 本地示例") }
    if host.agentConnection != nil {
      return L10n.tr("\(clientTypeName(for: host)) · 同一运行时 · \(connectionRoutes[host.id] ?? L10n.tr("等待连接"))")
    }
    switch saved.workspaces[host.id]?.connectionMode {
    case "history-only": return L10n.tr("\(clientTypeName(for: host)) 历史 · 只读")
    case "managed-runtime": return L10n.tr("\(clientTypeName(for: host)) · Agent 托管")
    case "shared-runtime": return L10n.tr("\(clientTypeName(for: host)) · 共享运行时")
    case "desktop-runtime": return L10n.tr("\(clientTypeName(for: host)) · 同一运行时")
    default: return L10n.tr("等待确认数据来源")
    }
  }
  var modeLabel: String {
    guard let client else { return L10n.tr("未连接") }
    if client.isDemo { return L10n.tr("界面体验 · 本地示例") }
    if !client.online { return L10n.tr("\(connectionLabel(for: client)) · 缓存与草稿已保留") }
    if saved.workspaces[client.id]?.capabilities?.contains("runtime:control-required") == true {
      if selectedThreadId == nil {
        return isWritable ? L10n.tr("\(clientTypeName) 新聊天 · 首条发送时创建") : L10n.tr("\(clientTypeName) 新建暂不可用 · 草稿已保留")
      }
      if runtime?.canControl == true { return L10n.tr("\(clientTypeName) · 实时连接") }
      return canConnectForReply ? L10n.tr("\(clientTypeName) · 发送时自动连接") : L10n.tr("\(clientTypeName) 未打开此对话 · 当前只读")
    }
    if saved.workspaces[client.id]?.agentConnectionState?.runtimeConnected == false {
      return L10n.tr("\(clientTypeName) · 正在连接运行时")
    }
    if saved.workspaces[client.id]?.connectionMode != "history-only" && !isWritable {
      return L10n.tr("\(clientTypeName) · 已连接 · 当前只读")
    }
    if client.agentConnection != nil { return L10n.tr("\(clientTypeName) · \(connectionRoutes[client.id] ?? L10n.tr("连接中")) · 实时连接") }
    switch saved.workspaces[client.id]?.connectionMode {
    case "managed-runtime": return L10n.tr("\(clientTypeName) · Agent 托管 · 实时连接")
    case "shared-runtime": return L10n.tr("\(clientTypeName) · 共享运行时 · 实时连接")
    default: return L10n.tr("\(clientTypeName) 历史连接 · 运行状态未同步")
    }
  }
  var composerUnavailableMessage: String {
    guard let client, client.online else {
      return L10n.tr("等待重新连接；草稿保存在此对话。")
    }
    let workspace = saved.workspaces[client.id]
    if selectedThreadId == nil,
      workspace?.capabilities?.contains("runtime:control-required") == true, !isWritable {
      return L10n.tr("\(clientTypeName) 暂不能新建聊天。草稿已保留。")
    }
    if workspace?.connectionMode == "history-only" {
      return L10n.tr("当前连接仅支持查看历史；草稿保存在此对话。")
    }
    return L10n.tr("当前只能查看历史；草稿保存在此对话。")
  }
  var canSend: Bool {
    canSubmit(draft)
  }
  var runningReplyModes: [String] { SessionPolicies.shared.replyModes(access: accessPolicy) }
  var activeReplyMode: String { SessionPolicies.shared.activeReplyMode(access: accessPolicy, preferred: replyMode) }
  private func canSubmit(_ content: Draft) -> Bool {
    guard canCompose, !isImporting else { return false }
    return SessionPolicies.shared.submit(access: accessPolicy, state: submissionPolicy,
      text: content.text, attachmentCount: Int32(content.attachments.count))
  }
  var canStop: Bool { SessionPolicies.shared.stop(running: isRunning, writable: isWritable,
    online: client?.online == true, stopping: stoppingKeys.contains(targetKey)) }
  var canRunAgain: Bool {
    guard SessionPolicies.shared.recoveryPrerequisites(threadSelected: selectedThreadId != nil,
      running: isRunning, runtimeState: runtime?.state, runtimePresent: runtime != nil) else { return false }
    return messages.contains(where: { $0.role == "user" })
      && canSubmit(Draft(text: L10n.tr(LocalizedText(stringLiteral: SharedMobileRules.runAgainText(restart: false)))))
  }
  private var composerDecision: (action: ComposerAction, enabled: Bool) {
    return SharedMobileRules.composer(stopping: stoppingKeys.contains(targetKey), running: isRunning,
      sending: sendingKeys.contains(targetKey), creationPending: pendingDesktopCreation.map { $0.phase != "rejected" } == true,
      draft: draft, canSend: canSend, canStop: canStop, canRunAgain: canRunAgain,
      runtime: runtime, messages: messages)
  }
  var composerAction: ComposerAction { composerDecision.action }
  var canPerformComposerAction: Bool { composerDecision.enabled }
  func performComposerAction() {
    switch composerAction {
    case .send: send()
    case .stop: stop()
    case .resume: runAgain(restart: false)
    case .busy: break
    }
  }
  func runAgain(restart: Bool) {
    guard canRunAgain else { return }
    // Both actions are explicit new turns in the selected Host/Thread.
    send(content: Draft(text: L10n.tr(LocalizedText(stringLiteral: SharedMobileRules.runAgainText(restart: restart)))))
  }
  var isImporting: Bool {
    recoveringAttachmentKeys.contains(targetKey) || localImportTargets.values.contains {
      $0.key == targetKey && $0.generation == draft.attachmentImportGeneration
    }
  }
  func beginImport(_ target: String) { imports[target, default: 0] += 1 }
  func endImport(_ target: String) { imports[target] = max(0, imports[target, default: 0] - 1) }
  var colorScheme: ColorScheme? {
    saved.appearance == .system ? nil : saved.appearance == .dark ? .dark : .light
  }

  private func persistenceSnapshot(includeHistory: Bool = true) -> SessionPersistenceSnapshot<SavedSession, SessionHistoryPage> {
    #if DEBUG
    let workStart = HistoryScrollProbe.workStart()
    defer { HistoryScrollProbe.workEnd(6, workStart) }
    #endif
    var snapshot = saved
    snapshot.drafts = drafts.values
    let encrypted = snapshot.clients.contains(where: { $0.agentConnection != nil })
    if snapshot.historyCacheEncrypted != encrypted { dirtyHistoryKeys.formUnion(saved.history.keys) }
    snapshot.historyCacheEncrypted = encrypted
    if saved.historyCacheEncrypted != encrypted { saved.historyCacheEncrypted = encrypted }
    snapshot.historyCacheKeys = Array(Set(saved.historyCacheKeys ?? []).union(saved.history.keys))
    snapshot.history = [:]
    var pages: [String: SessionHistoryPage] = [:]
    if includeHistory {
      for key in dirtyHistoryKeys {
        if let history = saved.history[key] {
          pages[(snapshot.historyCacheNamespace ?? "") + ":" + key] = SessionHistoryPage(targetKey: key, messages: history, encrypted: encrypted)
        }
      }
      dirtyHistoryKeys.removeAll()
    }
    return SessionPersistenceSnapshot(state: snapshot, histories: pages)
  }

  private func cancelDraftSave() {
    draftSaveTask?.cancel(); draftSaveTask = nil
    draftSaveDeadlineTask?.cancel(); draftSaveDeadlineTask = nil
  }

  private func scheduleDraftSave() {
    draftSaveTask?.cancel()
    draftSaveTask = Task { [weak self] in
      do { try await Task.sleep(for: .milliseconds(350)) } catch { return }
      self?.persist()
    }
    // A long uninterrupted edit still saves periodically.
    if draftSaveDeadlineTask == nil {
      draftSaveDeadlineTask = Task { [weak self] in
        do { try await Task.sleep(for: .seconds(2)) } catch { return }
        self?.persist()
      }
    }
  }

  private func recordPersistence(_ result: Result<Void, Error>, onMainThread: Bool) {
    #if DEBUG
    if typingFixture { typingWrites += 1; if onMainThread { typingMainThreadWrites += 1 } }
    #endif
    if case .failure = result {
      dirtyHistoryKeys.formUnion(saved.history.keys)
      error = L10n.tr("无法保存本地会话，请检查设备存储空间。")
    }
  }

  func persist(reconcileUnread: Bool = true) {
    #if DEBUG
    let workStart = HistoryScrollProbe.workStart()
    defer { HistoryScrollProbe.workEnd(2, workStart) }
    #endif
    guard !localRecoveryRequired else { return }
    captureCodexStorageKeys()
    if reconcileUnread { synchronizeMobileUnread() }
    cancelDraftSave()
    persistenceWriter.write(persistenceSnapshot()) { [weak self] result, onMainThread in
      Task { @MainActor in self?.recordPersistence(result, onMainThread: onMainThread) }
    }
  }

  // Commands must keep their recovery record durable before contacting a Host.
  private func persistBeforeCommand() async throws {
    guard !localRecoveryRequired else { throw SessionPersistenceError.recoveryRequired }
    cancelDraftSave()
    let snapshot = persistenceSnapshot(includeHistory: false)
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      persistenceWriter.write(snapshot) { [weak self] result, onMainThread in
        Task { @MainActor in
          self?.recordPersistence(result, onMainThread: onMainThread)
          continuation.resume(with: result)
        }
      }
    }
  }

  func persistForBackground() {
    guard !localRecoveryRequired else { return }
    synchronizeMobileUnread()
    cancelDraftSave()
    let backgroundSave = SessionBackgroundSave()
    backgroundSave.start()
    persistenceWriter.write(persistenceSnapshot(), flushHistory: true) { [weak self] result, onMainThread in
      Task { @MainActor in
        self?.recordPersistence(result, onMainThread: onMainThread)
        backgroundSave.finish()
      }
    }
  }

  func enableDemo() {
    for host in DemoData.clients where !saved.clients.contains(where: { $0.id == host.id }) {
      saved.clients.append(host)
    }
    saved.expanded["demo-mac"] = ["mobile"]
    selectClient("demo-mac")
  }

  func disableDemo() {
    let demoIds = Set(saved.clients.filter(\.isDemo).map(\.id))
    guard !demoIds.isEmpty else { return }
    for task in demoTasks.values { task.cancel() }
    demoTasks.removeAll()
    for key in (saved.runtimes ?? [:]).keys where demoIds.contains(where: { key.hasPrefix($0 + ":") }) {
      saved.runtimes?[key]?.state = "idle"
      saved.runtimes?[key]?.turnId = nil
    }
    // Keep local sample history and drafts so opening the experience again is reversible.
    saved.clients.removeAll(where: \.isDemo)
    drawerOpen = false
    sheet = nil
    search = ""
    error = nil
    if let selectedId = saved.selectedClientId, demoIds.contains(selectedId) {
      historyGeneration = UUID()
      eventTask?.cancel()
      saved.selectedClientId = nil
      selectedThreadId = nil
      selectedProjectId = nil
      historyCursor = nil
      models = []
      if let next = saved.clients.first { selectClient(next.id) }
    }
    persist()
  }

  func selectClient(_ id: String, refreshAutomatically: Bool = true) {
    guard saved.clients.contains(where: { $0.id == id }) else { return }
    clientGeneration = UUID()
    historyGeneration = UUID()
    eventTask?.cancel()
    saved.selectedClientId = id
    models = []
    loading = false
    restoreSelection(id)
    drawerOpen = false
    search = ""
    sheet = nil
    historyCursor = nil
    error = nil
    persist()
    if refreshAutomatically && client?.isDemo == false { Task { await refresh() } }
  }
  func openNotification(_ event: TaskNotificationEvent, client host: Client) {
    selectClient(host.id, refreshAutomatically: false)
    let initialSelection = selectedThreadId, selectedGeneration = clientGeneration
    Task {
      await refresh()
      guard client?.id == host.id, clientGeneration == selectedGeneration, selectedThreadId == initialSelection,
        saved.clients.contains(where: { $0.id == host.id && $0.agentConnection == host.agentConnection && $0.endpoint == host.endpoint }) else { return }
      if let threadId = event.threadId {
        if let thread = threads.first(where: { $0.id == threadId && $0.hostId == host.id }) {
          openThread(thread, loadAutomatically: false)
          await loadHistory(threadId: threadId)
          guard client?.id == host.id, selectedThreadId == threadId else { return }
          startEvents()
          if event.kind.actionable, event.kind != .uncertain {
            let stillPending = runtime?.approvals.contains(where: { $0.turnId == event.runId && $0.notificationRequestId == event.requestId }) == true
              || (event.kind == .plan && runtime?.proposedPlan?.id == event.requestId)
              || (event.kind == .paused && runtime?.state == "paused")
            if !stillPending { error = L10n.tr("此事项已处理，已显示最新任务状态。") }
          }
        } else { error = L10n.tr("此聊天已移除或暂不可读取，请刷新电脑连接。") }
      } else if let id = event.operationId {
        if let reply = saved.outbox?.values.first(where: { $0.hostId == host.id && $0.clientMessageId == id })
          ?? saved.detachedReplies?.values.flatMap({ $0 }).first(where: { $0.original.hostId == host.id && $0.id == id })?.original,
          let thread = threads.first(where: { $0.id == reply.threadId && $0.hostId == host.id }) {
          openThread(thread, loadAutomatically: false); await loadHistory(threadId: reply.threadId)
          guard client?.id == host.id, selectedThreadId == reply.threadId else { return }; startEvents(); return
        }
        // A successful creation removes the phone's pending record. Resolve old
        // notifications through the Host's durable receipt, without submitting again.
        do {
          let operation = try await AgentClients.resolve(host).creationReceipt(host, id: id)
          guard client?.id == host.id, clientGeneration == selectedGeneration, selectedThreadId == initialSelection,
            saved.clients.contains(where: { $0.id == host.id && $0.agentConnection == host.agentConnection && $0.endpoint == host.endpoint }) else { return }
          guard operation.hostId == host.id, operation.clientOperationId == id else { throw HostServiceError.wrongHost }
          if operation.phase == "confirmed", let thread = operation.thread {
            guard thread.hostId == host.id, thread.id == operation.threadId, thread.projectId == operation.projectId else { throw HostServiceError.wrongHost }
            if !(saved.workspaces[host.id]?.threads.contains(where: { $0.id == thread.id }) ?? false) {
              saved.workspaces[host.id]?.threads.insert(thread, at: 0)
            }
            openThread(thread, loadAutomatically: false); await loadHistory(threadId: thread.id)
            guard client?.id == host.id, selectedThreadId == thread.id else { return }; startEvents(); return
          }
        } catch {
          guard client?.id == host.id, clientGeneration == selectedGeneration, selectedThreadId == initialSelection else { return }
          if case HostServiceError.wrongHost = error { self.presentError(error); return }
        }
        let pending = saved.desktopCreations?.values.first(where: { $0.hostId == host.id && $0.clientOperationId == id })
          ?? saved.detachedDesktopCreations?.values.flatMap { $0 }.first(where: { $0.hostId == host.id && $0.clientOperationId == id })
        if let pending {
          selectedThreadId = nil; selectedProjectId = pending.projectId; saved.newProjects[host.id] = pending.projectId ?? "__projectless__"; persist()
        } else { error = L10n.tr("此操作已处理或回执不在这部手机，请查看最新任务状态。") }
      }
    }
  }

  func migrationPayload() throws -> EncryptedMigration.Payload {
    var snapshot = saved; snapshot.drafts = drafts.values
    var files: [LocalAttachment] = []
    for draft in snapshot.drafts.values { files.append(contentsOf: draft.attachments) }
    for pending in snapshot.outbox?.values ?? Dictionary<String, PendingReply>().values { files.append(contentsOf: pending.sourceAttachments ?? []) }
    for record in snapshot.detachedReplies?.values.flatMap({ $0 }) ?? [] { files.append(contentsOf: record.original.sourceAttachments ?? []) }
    for pending in snapshot.desktopCreations?.values ?? Dictionary<String, PendingDesktopCreation>().values { files.append(contentsOf: pending.attachments) }
    for pendingList in snapshot.detachedDesktopCreations?.values ?? Dictionary<String, [PendingDesktopCreation]>().values {
      for pending in pendingList { files.append(contentsOf: pending.attachments) }
    }
    var attachments: [String: Data] = [:]
    for file in files { attachments[file.fileName] = try Data(contentsOf: attachmentURL(file)) }
    let hostRevocations = TaskNotificationShared.read([String: TaskHostNotificationRevocation].self, name: "pending-host-revocations.json") ?? [:]
    let notificationClients = snapshot.clients + hostRevocations.values.map(\.client)
    let ids = Set(notificationClients.filter { !$0.isDemo }.flatMap { [$0.id, $0.agentConnection?.credentialRef].compactMap { $0 } }
      + ["easywork-device-identity", "easywork-oidc-session", "agent-cache-key-v1"])
    var credentials: [String: String] = [:]
    for id in ids { let value = CredentialVault.token(id); if !value.isEmpty { credentials[id] = value } }
    var sounds: [String: Data] = [:]
    for item in TaskNotificationSoundLibrary.sounds where item.imported {
      sounds[item.id] = try Data(contentsOf: TaskNotificationSoundLibrary.directory.appendingPathComponent(item.id))
    }
    return EncryptedMigration.Payload(bundleIdentifier: Bundle.main.bundleIdentifier ?? "", state: snapshot,
      credentials: credentials, attachments: attachments, notificationPreferences: TaskNotificationShared.preferences,
      notificationKey: TaskNotificationShared.secret("device-key"), notificationDeviceId: TaskNotificationShared.read(String.self, name: "device-id.json"),
      notificationBindings: TaskNotificationShared.bindings, notificationSounds: TaskNotificationSoundLibrary.sounds.filter(\.imported), soundFiles: sounds,
      notificationSeen: TaskNotificationShared.read([String].self, name: "seen.json"), notificationNotices: TaskNotificationShared.notices, notificationHostRevocations: hostRevocations)
  }

  func restoreMigration(_ payload: EncryptedMigration.Payload) throws {
    guard MigrationIdentityPolicy.accepts(source: payload.bundleIdentifier, destination: Bundle.main.bundleIdentifier) else {
      throw HostServiceError.message(L10n.tr("这份备份不属于当前 App 或受支持的旧版本，现有数据未更改。"))
    }
    guard !saved.clients.contains(where: { !$0.isDemo }),
      Set(payload.state.clients.map(\.id)).count == payload.state.clients.count else {
      throw HostServiceError.message(L10n.tr("请在尚未添加真实电脑的新安装中导入迁移文件，现有连接未更改。"))
    }
    let notificationClients = payload.state.clients + (payload.notificationHostRevocations?.values.map(\.client) ?? [])
    let allowedIds = Set(notificationClients.filter { !$0.isDemo }.flatMap { [$0.id, $0.agentConnection?.credentialRef].compactMap { $0 } }
      + ["easywork-device-identity", "easywork-oidc-session", "agent-cache-key-v1"])
    guard payload.credentials.keys.allSatisfy({ allowedIds.contains($0) }),
      payload.attachments.keys.allSatisfy(EncryptedMigration.safeFilename),
      payload.soundFiles.keys.allSatisfy({ EncryptedMigration.safeFilename($0) && $0.hasSuffix(".caf") }),
      payload.notificationSounds.allSatisfy({ $0.imported && TaskNotificationValidation.sound($0.id) && payload.soundFiles[$0.id] != nil && !["soft.caf", "chime.caf", "attention.caf"].contains($0.id) }),
      Set(payload.notificationSounds.map(\.id)) == Set(payload.soundFiles.keys),
      payload.notificationKey == nil || payload.notificationKey?.count == 32,
      TaskNotificationValidation.preferences(payload.notificationPreferences),
      (payload.notificationSeen ?? []).allSatisfy(TaskNotificationValidation.eventId),
      (payload.notificationNotices ?? []).allSatisfy(TaskNotificationValidation.event),
      payload.notificationBindings.values.allSatisfy({ TaskNotificationValidation.target($0.target) && Data(base64Encoded: $0.publicKey)?.count == 32 }),
      (payload.notificationHostRevocations ?? [:]).values.allSatisfy({ pending in !pending.client.isDemo && TaskNotificationValidation.target(pending.target) && pending.deviceId.range(of: "^[A-Za-z0-9._-]{1,128}$", options: .regularExpression) != nil && (pending.client.agentConnection.map { connection in connection.target == pending.target } ?? (pending.target.agentId == "codex" && pending.target.hostId == pending.client.id)) }),
      payload.notificationDeviceId == nil || payload.notificationDeviceId?.range(of: "^[A-Za-z0-9._-]{1,128}$", options: .regularExpression) != nil else { throw HostServiceError.invalidResponse }
    var attachmentReferences: [LocalAttachment] = payload.state.drafts.values.flatMap(\.attachments)
    if let outbox = payload.state.outbox { for pending in outbox.values { attachmentReferences.append(contentsOf: pending.sourceAttachments ?? []) } }
    for record in payload.state.detachedReplies?.values.flatMap({ $0 }) ?? [] { attachmentReferences.append(contentsOf: record.original.sourceAttachments ?? []) }
    if let creations = payload.state.desktopCreations { for pending in creations.values { attachmentReferences.append(contentsOf: pending.attachments) } }
    if let detached = payload.state.detachedDesktopCreations { for pendingList in detached.values { for pending in pendingList { attachmentReferences.append(contentsOf: pending.attachments) } } }
    guard attachmentReferences.allSatisfy({ EncryptedMigration.safeFilename($0.fileName) && payload.attachments[$0.fileName]?.count == $0.size }) else { throw HostServiceError.invalidResponse }
    for data in payload.soundFiles.values { try TaskNotificationSoundLibrary.validateArchiveSound(data) }
    let previous = Dictionary(uniqueKeysWithValues: payload.credentials.keys.map { ($0, CredentialVault.token($0)) })
    let previousSecrets = ["device-key", "bindings"].map { ($0, TaskNotificationShared.secret($0)) }
    var importedState = payload.state
    // Consent belongs to this installation; a migrated backup cannot grant permission.
    importedState.aiSharingGrants = nil
    importedState.replyRecovery = importedState.replyRecovery?.mapValues(ReplyRecoveryPolicy.imported)
    importedState.readingDeviceId = saved.readingDeviceId ?? UUID().uuidString
    importedState.codexReaderScopeIds = nil // Imported readers never impersonate the prior phone.
    importedState.mobileReads = [:]
    importedState.mobileReadBaselines = [:]
    var rollback: [(URL, Data?)] = []
    func preserve(_ url: URL) { rollback.append((url, try? Data(contentsOf: url))) }
    do {
      for (id, value) in payload.credentials { try CredentialVault.set(value, id: id) }
      let attachments = persistenceURL.deletingLastPathComponent().appendingPathComponent("Attachments", isDirectory: true)
      try FileManager.default.createDirectory(at: attachments, withIntermediateDirectories: true)
      for (name, data) in payload.attachments { let url = attachments.appendingPathComponent(name); preserve(url); try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
      try TaskNotificationSoundLibrary.prepare()
      for (name, data) in payload.soundFiles { let url = TaskNotificationSoundLibrary.directory.appendingPathComponent(name); preserve(url); try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
      for name in ["sounds.json", "device-id.json", "preferences.json", "seen.json", "pending-revocations.json", "pending-host-revocations.json", "mobile-unread-v1.json"] { preserve(TaskNotificationShared.directory.appendingPathComponent(name)) }
      try TaskNotificationShared.write(payload.notificationSounds, name: "sounds.json")
      if let key = payload.notificationKey { try TaskNotificationShared.setSecret(key, account: "device-key") }
      if let id = payload.notificationDeviceId { try TaskNotificationShared.write(id, name: "device-id.json") }
      try TaskNotificationShared.setBindings(payload.notificationBindings)
      try TaskNotificationShared.write(payload.notificationSeen ?? [], name: "seen.json")
      try TaskNotificationShared.write(Array(payload.notificationBindings.keys), name: "pending-revocations.json")
      try TaskNotificationShared.write(payload.notificationHostRevocations ?? [:], name: "pending-host-revocations.json")
      for event in payload.notificationNotices ?? [] { preserve(TaskNotificationShared.directory.appendingPathComponent("notice-" + event.id + ".json")); try TaskNotificationShared.remember(event) }
      var prefs = payload.notificationPreferences; prefs.enabled = false; prefs.generation += 1; prefs.revision = UUID().uuidString
      try TaskNotificationShared.write(prefs, name: "preferences.json")
      let data = try importedState.clients.contains(where: { $0.agentConnection != nil }) ? AgentCache.encode(importedState) : JSONEncoder().encode(importedState)
      try MobileUnreadBadge.reset()
      try persistenceWriter.replaceLegacy(data)
    } catch {
      for (url, data) in rollback.reversed() { if let data { try? data.write(to: url, options: [.atomic]) } else { try? FileManager.default.removeItem(at: url) } }
      for (id, value) in previous { try? CredentialVault.set(value, id: id) }
      for (account, data) in previousSecrets { if let data { try? TaskNotificationShared.setSecret(data, account: account) } else { TaskNotificationShared.removeSecret(account) } }
      throw error
    }
    eventTask?.cancel(); saved = importedState; drafts.values = saved.drafts; saved.drafts = [:]; captureCodexStorageKeys(); preserveCodexReaderScopeIds()
    saved.historyCacheNamespace = UUID().uuidString
    saved.historyCacheKeys = nil; saved.historyCacheEncrypted = nil
    dirtyHistoryKeys = Set(saved.history.keys)
    for index in saved.clients.indices { saved.clients[index].online = false }
    if let id = saved.selectedClientId { restoreSelection(id) }
    TaskNotificationController.shared.reloadAfterMigration()
    synchronizeMobileUnread()
  }

  func finishConnectionLink(presentation: UUID? = nil) {
    guard let pending = pendingConnection, presentation == nil || pending.presentation == presentation else { return }
    pendingConnection = nil
    Task { await refresh() }
  }

  // A new connection configuration invalidates readers that captured the old one.
  // Otherwise a long-lived event reader can replace the newly selected transport.
  func invalidateAgentConnection(_ id: String) {
    guard client?.id == id else { return }
    clientGeneration = UUID()
    historyGeneration = UUID()
    eventTask?.cancel()
    eventTask = nil
    connectionRoutes.removeValue(forKey: id)
  }

  private func restoreSelection(_ id: String) {
    defer { applyNewConversationDefaults() }
    if saved.newProjects[id] == "__projectless__" {
      selectedThreadId = nil
      selectedProjectId = nil
      return
    }
    if let projectId = saved.newProjects[id], projects.contains(where: { $0.id == projectId }) {
      selectedThreadId = nil
      selectedProjectId = projectId
      return
    }
    selectedThreadId = threads.first { $0.id == saved.lastThreads[id] }?.id ?? threads.first?.id
    selectedProjectId = conversation?.projectId ?? saved.lastThreadProjects?[id]
    if selectedThreadId == nil { selectedProjectId = selectedProjectId ?? projects.first?.id }
  }

  func openThread(_ thread: Conversation, in project: Project? = nil, loadAutomatically: Bool = true) {
    guard thread.hostId == client?.id else { return }
    if let project, !thread.belongs(to: project) { return }
    selectedProjectId = thread.projectId ?? project?.id
    if saved.lastThreadProjects == nil { saved.lastThreadProjects = [:] }
    saved.lastThreadProjects?[thread.hostId] = selectedProjectId
    saved.lastThreads[thread.hostId] = thread.id
    saved.newProjects.removeValue(forKey: thread.hostId)
    if selectedThreadId == thread.id {
      // Opening the already selected chat is presentation navigation. Retain
      // its read generation so an in-flight catch-up and this tap coalesce.
      drawerOpen = false; sheet = nil; error = nil
      if loadAutomatically && client?.isDemo == false {
        Task {
          await loadHistory(threadId: thread.id)
          if client?.id == thread.hostId && selectedThreadId == thread.id { startEvents() }
        }
      }
      persist()
      return
    }
    historyGeneration = UUID()
    #if DEBUG
    DesktopSuggestionTiming.begin(clientGeneration.uuidString + ":" + historyGeneration.uuidString)
    #endif
    eventTask?.cancel()
    selectedThreadId = thread.id
    drawerOpen = false
    sheet = nil
    historyCursor = nil
    error = nil
    if loadAutomatically, let host = client, let scope = suggestionReadCandidate {
      prepareNextTurnSuggestion(host, scope: scope)
    }
    if loadAutomatically && client?.isDemo == false {
      Task {
        await loadHistory(threadId: thread.id)
        if client?.id == thread.hostId && selectedThreadId == thread.id { startEvents() }
      }
    }
    // Start both reads before the local navigation snapshot's unread scan.
    // Submitted commands retain their separate durable-before-send path.
    Task { persist() }
  }

  func newConversation(in project: Project) {
    guard project.hostId == client?.id, canCreateThread, client?.online == true else {
      return
    }
    historyGeneration = UUID()
    selectedThreadId = nil
    selectedProjectId = project.id
    saved.newProjects[project.hostId] = project.id
    applyNewConversationDefaults()
    drawerOpen = false
    sheet = nil
    error = nil
    historyCursor = nil
    persist()
  }
  func newProjectlessConversation() {
    guard canCreateProjectless, let host = client, host.online else { return }
    historyGeneration = UUID()
    selectedThreadId = nil
    selectedProjectId = nil
    saved.newProjects[host.id] = "__projectless__"
    applyNewConversationDefaults()
    drawerOpen = false
    sheet = nil
    error = nil
    historyCursor = nil
    persist()
  }

  func removeClient(_ id: String) {
    if let removed = saved.clients.first(where: { $0.id == id }) {
      Task {
        let revoked = await TaskNotificationController.shared.removedClient(removed)
        if let provider = try? AgentClients.resolve(removed) { await provider.close(removed) }
        if revoked && !saved.clients.contains(where: { $0.id == id }) {
          if let connection = removed.agentConnection { try? CredentialVault.set("", id: connection.credentialRef) }
          try? CredentialVault.set("", id: id)
        }
      }
    }
    saved.clients.removeAll { $0.id == id }
    if saved.selectedClientId == id {
      eventTask?.cancel()
      saved.selectedClientId = nil
      selectedThreadId = nil
      selectedProjectId = nil
      if let next = saved.clients.first { selectClient(next.id) }
    }
    persist()
  }
  func toggleProject(_ id: String) {
    guard let client else { return }
    if saved.expanded[client.id, default: []].contains(id) {
      saved.expanded[client.id]?.remove(id)
    } else {
      saved.expanded[client.id, default: []].insert(id)
    }
    persist(reconcileUnread: false)
  }

  func setText(_ text: String, target: String? = nil) {
    #if DEBUG
    let typingStarted = ProcessInfo.processInfo.systemUptime
    defer { if typingFixture { typingDurations.append((ProcessInfo.processInfo.systemUptime - typingStarted) * 1000) } }
    #endif
    let key = target ?? targetKey
    guard drafts.values[key]?.text != text else { return }
    if drafts.suggestionUndo?.applied.targetKey == key { drafts.suggestionUndo = nil }
    drafts.textRevisions[key, default: 0] &+= 1
    drafts.values[key, default: Draft()].text = text
    scheduleDraftSave()
  }
  func removeAttachment(_ attachment: LocalAttachment) {
    drafts.values[targetKey]?.attachments.removeAll { $0.id == attachment.id }
    removeUnreferencedAttachment(attachment)
    persist()
  }
  func clearDraft(target: String) {
    for (id, destination) in localImportTargets where destination.key == target { localImportCancellations[id]?() }
    drafts.textRevisions[target, default: 0] &+= 1
    if drafts.suggestionUndo?.applied.targetKey == target { drafts.suggestionUndo = nil }
    let files = drafts.values[target]?.attachments ?? []
    drafts.values[target] = Draft()
    for file in files { removeUnreferencedAttachment(file) }
    persist()
  }
  private func removeUnreferencedAttachment(_ file: LocalAttachment) {
    let referenced = drafts.values.values.contains { $0.attachments.contains { $0.id == file.id } }
      || (saved.desktopCreations?.values.contains { $0.attachments.contains { $0.id == file.id } } ?? false)
      || (saved.detachedDesktopCreations?.values.flatMap { $0 }.contains { $0.attachments.contains { $0.id == file.id } } ?? false)
      || (saved.outbox?.values.contains { $0.sourceAttachments?.contains { $0.id == file.id } == true } ?? false)
      || (saved.detachedReplies?.values.flatMap { $0 }.contains { $0.original.sourceAttachments?.contains { $0.id == file.id } == true } ?? false)
    if !referenced { try? FileManager.default.removeItem(at: attachmentURL(file)) }
  }
  func attachmentURL(_ file: LocalAttachment) -> URL {
    persistenceURL.deletingLastPathComponent().appendingPathComponent(
      "Attachments", isDirectory: true
    ).appendingPathComponent(file.fileName)
  }
  func attachmentDestination() -> AttachmentDraftDestination {
    let key = targetKey
    if drafts.values[key]?.attachmentImportGeneration == nil {
      drafts.values[key, default: Draft()].attachmentImportGeneration = UUID().uuidString
    }
    return AttachmentDraftDestination(key: key, generation: drafts.values[key]!.attachmentImportGeneration!)
  }

  /// Every entry point ends here. This method has no AgentClient/Host dependency.
  func addLocalAttachments(to destination: AttachmentDraftDestination, importId: String = UUID().uuidString,
    attachmentIds: [String]? = nil, loaders: [() async throws -> LocalAttachmentInput]) async {
    LocalAttachmentDiagnostics.trace("import-request recovery=\(localRecoveryRequired) generation=\(drafts.values[destination.key]?.attachmentImportGeneration == destination.generation) current=\(destination.key == targetKey)")
    guard attachmentIds == nil || (attachmentIds?.count == loaders.count && attachmentIds?.allSatisfy({ UUID(uuidString: $0) != nil }) == true) else { return }
    guard !localImportJobs.contains(importId), !completedLocalImportJobs.contains(importId) else { return }
    guard !localRecoveryRequired,
      AttachmentPreparationRules.shared.canCommit(capturedTarget: destination.key, currentTarget: destination.key,
        capturedGeneration: destination.generation, currentGeneration: drafts.values[destination.key]?.attachmentImportGeneration,
        alreadyCompleted: false) else { return }
    guard AttachmentPreparationRules.shared.canReserve(existing: Int32(drafts.values[destination.key]?.attachments.count ?? 0),
      reserved: Int32(attachmentSlots[destination.generation, default: 0]), requested: Int32(loaders.count)) else {
      presentError(AttachmentError.tooMany); return
    }
    localImportJobs.insert(importId)
    localImportTargets[importId] = destination
    attachmentSlots[destination.generation, default: 0] += loaders.count
    beginImport(destination.key)
    defer {
      localImportJobs.remove(importId)
      localImportTargets.removeValue(forKey: importId)
      localImportCancellations.removeValue(forKey: importId)
      completedLocalImportJobs.append(importId)
      if completedLocalImportJobs.count > 256 { completedLocalImportJobs.removeFirst() }
      attachmentSlots[destination.generation, default: 0] -= loaders.count
      if attachmentSlots[destination.generation] == 0 { attachmentSlots.removeValue(forKey: destination.generation) }
      endImport(destination.key)
    }
    do {
      // Persist the draft generation before an asynchronous provider can return.
      LocalAttachmentDiagnostics.trace("persist-generation-start")
      try await persistBeforeCommand()
      LocalAttachmentDiagnostics.trace("persist-generation-complete")
      let directory = persistenceURL.deletingLastPathComponent().appendingPathComponent("Attachments", isDirectory: true)
      for (index, loader) in loaders.enumerated() {
        do {
          guard !localRecoveryRequired else { throw SessionPersistenceError.recoveryRequired }
          try Task.checkCancellation()
          guard drafts.values[destination.key]?.attachmentImportGeneration == destination.generation else { break }
          let loading = Task { try await loader() }
          LocalAttachmentDiagnostics.trace("provider-start")
          localImportCancellations[importId] = { loading.cancel() }
          let input = try await loading.value
          LocalAttachmentDiagnostics.trace("provider-complete")
          let worker = Task.detached(priority: .userInitiated) {
            try LocalAttachmentPreparation.prepare(input, directory: directory, destination: destination,
              attachmentId: attachmentIds?[index])
          }
          localImportCancellations[importId] = { worker.cancel() }
          let prepared = try await withTaskCancellationHandler { try await worker.value } onCancel: { worker.cancel() }
          LocalAttachmentDiagnostics.trace("prepare-complete")
          guard !localRecoveryRequired else { throw SessionPersistenceError.recoveryRequired }
          guard AttachmentPreparationRules.shared.canCommit(capturedTarget: destination.key, currentTarget: destination.key,
            capturedGeneration: destination.generation, currentGeneration: drafts.values[destination.key]?.attachmentImportGeneration,
            alreadyCompleted: false) else {
            try? FileManager.default.removeItem(at: prepared.journal ?? prepared.url)
            removeUnreferencedAttachment(prepared.file)
            continue
          }
          if let existing = drafts.values[destination.key]?.attachments.first(where: { $0.id == prepared.file.id }) {
            if existing.fileName != prepared.file.fileName { try? FileManager.default.removeItem(at: prepared.url) }
            if let journal = prepared.journal { try? FileManager.default.removeItem(at: journal) }
            continue
          }
          drafts.values[destination.key, default: Draft()].attachments.append(prepared.file)
          try await persistBeforeCommand()
          if let journal = prepared.journal { try? FileManager.default.removeItem(at: journal) }
        } catch {
          if destination.key == targetKey,
            drafts.values[destination.key]?.attachmentImportGeneration == destination.generation { presentError(error) }
        }
      }
    } catch { presentError(error) }
  }

  /// A shared inbox may be consumed only after the exact attachment IDs are durable.
  func confirmLocalAttachmentImport(_ ids: [String], to destination: AttachmentDraftDestination) async throws -> Bool {
    func matches() -> Bool {
      !localRecoveryRequired && drafts.values[destination.key]?.attachmentImportGeneration == destination.generation
        && ids.allSatisfy { id in drafts.values[destination.key]?.attachments.contains { $0.id == id } == true }
    }
    guard matches() else { return false }
    try await persistBeforeCommand()
    return matches()
  }

  func recoverLocalAttachmentImports() async {
    guard !recoveredLocalAttachments, !localRecoveryRequired else { return }
    recoveredLocalAttachments = true
    let directory = persistenceURL.deletingLastPathComponent().appendingPathComponent("Attachments", isDirectory: true)
    let journals = directory.appendingPathComponent("Prepared", isDirectory: true)
    let paths = (try? FileManager.default.contentsOfDirectory(at: journals, includingPropertiesForKeys: nil)) ?? []
    for path in paths where path.pathExtension == "json" {
      do {
        let record = try JSONDecoder().decode(PreparedAttachmentRecord.self, from: Data(contentsOf: path))
        guard UUID(uuidString: record.file.fileName) != nil,
          record.file.size > 0, record.file.size <= LocalAttachmentPreparation.maximumBytes else { continue }
        let url = directory.appendingPathComponent(record.file.fileName)
        let valid = try await Task.detached(priority: .utility) {
          let data = try LocalAttachmentPreparation.read(url)
          return data.count == record.file.size && HostService.hash(data) == record.sha256
        }.value
        guard valid else { continue }
        if let existing = drafts.values[record.destination.key]?.attachments.first(where: { $0.id == record.file.id }) {
          if existing.fileName != record.file.fileName { try? FileManager.default.removeItem(at: url) }
          try FileManager.default.removeItem(at: path); continue
        }
        guard AttachmentPreparationRules.shared.canCommit(capturedTarget: record.destination.key, currentTarget: record.destination.key,
          capturedGeneration: record.destination.generation,
          currentGeneration: drafts.values[record.destination.key]?.attachmentImportGeneration, alreadyCompleted: false),
          (drafts.values[record.destination.key]?.attachments.count ?? 0) < 10 else {
          removeUnreferencedAttachment(record.file)
          try FileManager.default.removeItem(at: path); continue
        }
        recoveringAttachmentKeys.insert(record.destination.key)
        beginImport(record.destination.key)
        drafts.values[record.destination.key, default: Draft()].attachments.append(record.file)
        do { try await persistBeforeCommand(); try FileManager.default.removeItem(at: path) }
        catch { presentError(error) }
        recoveringAttachmentKeys.remove(record.destination.key)
        endImport(record.destination.key)
      } catch { presentError(error) }
    }
  }

  #if DEBUG
  func checkAttachmentImportAcceptance() async throws {
    guard QuickAttachmentAcceptance.enabled, client?.isDemo == true else { throw AttachmentError.unsupportedImage }
    let key = targetKey
    clearDraft(target: key)
    let destination = attachmentDestination()
    let job = UUID().uuidString
    let input = LocalAttachmentInput.data(Data("local-only".utf8), name: "boundary.txt", image: false)
    await addLocalAttachments(to: destination, importId: job, loaders: [{ input }])
    await addLocalAttachments(to: destination, importId: job, loaders: [{ input }])
    guard drafts.values[key]?.attachments.count == 1 else { throw AttachmentError.tooMany }
    let delayed = Task {
      await addLocalAttachments(to: destination, loaders: [{
        try await Task.sleep(for: .milliseconds(250)); return input
      }])
    }
    try await Task.sleep(for: .milliseconds(80))
    clearDraft(target: key)
    await delayed.value
    guard drafts.values[key]?.attachments.isEmpty == true else { throw AttachmentError.tooMany }
    let edited = attachmentDestination()
    let pending = Task {
      await addLocalAttachments(to: edited, loaders: [{
        try await Task.sleep(for: .milliseconds(250)); return input
      }])
    }
    try await Task.sleep(for: .milliseconds(80))
    setText("edited-during-import", target: key)
    await pending.value
    guard drafts.values[key]?.text == "edited-during-import", drafts.values[key]?.attachments.count == 1 else {
      throw AttachmentError.unsupportedImage
    }
    do {
      _ = try LocalAttachmentPreparation.normalize(Data(count: LocalAttachmentPreparation.maximumBytes + 1), name: "large.txt", image: false)
      throw AttachmentError.unsupportedImage
    } catch AttachmentError.tooLarge { /* Actual byte boundary must reject. */ }
    clearDraft(target: key)
  }

  func prepareAttachmentRecoveryAcceptance() async throws {
    guard QuickAttachmentAcceptance.enabled else { throw AttachmentError.unsupportedImage }
    let destination = attachmentDestination()
    try await persistBeforeCommand()
    let directory = persistenceURL.deletingLastPathComponent().appendingPathComponent("Attachments", isDirectory: true)
    _ = try await Task.detached {
      try LocalAttachmentPreparation.prepare(.data(Data("recover-local-only".utf8), name: "recovered-local.txt", image: false),
        directory: directory, destination: destination)
    }.value
  }
  #endif

  func importData(_ data: Data, name: String, isImage: Bool, target: String) throws {
    guard AttachmentPreparationRules.shared.canReserve(existing: Int32(drafts.values[target]?.attachments.count ?? 0),
      reserved: Int32((drafts.values[target]?.attachmentImportGeneration).map { attachmentSlots[$0, default: 0] } ?? 0), requested: 1) else { throw AttachmentError.tooMany }
    let directory = persistenceURL.deletingLastPathComponent().appendingPathComponent("Attachments", isDirectory: true)
    let prepared = try LocalAttachmentPreparation.prepare(.data(data, name: name, image: isImage), directory: directory)
    drafts.values[target, default: Draft()].attachments.append(prepared.file)
    persist()
  }

  func sendDemo(content override: Draft? = nil) {
    let content = override ?? draft
    guard canSubmit(content), let client else { return }
    let project = project
    let oldKey = targetKey
    if selectedThreadId == nil {
      let id = UUID().uuidString
      let title = String(
        content.text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(24))
      saved.demoThreads.insert(
        Conversation(
          id: id, hostId: client.id, projectId: project?.id,
          title: title.isEmpty ? L10n.tr("附件对话") : title, state: "idle"), at: 0)
      selectedThreadId = id
      saved.lastThreads[client.id] = id
      saved.newProjects.removeValue(forKey: client.id)
      saved.history[targetKey] = []
      dirtyHistoryKeys.insert(targetKey)
    }
    let key = targetKey
    var history = messages
    history.append(
      ChatMessage(
        id: UUID().uuidString, role: "user", text: content.text,
        files: content.attachments.map(\.name)))
    saved.history[key] = history
    dirtyHistoryKeys.insert(key)
    if override == nil { drafts.values.removeValue(forKey: oldKey) }
    let turnId = UUID().uuidString
    saved.runtimes?[key] = ThreadRuntime(state: "running", turnId: turnId, plan: [], approvals: [], receipts: [])
    persist()
    demoTasks[key] = Task {
      defer {
        for file in content.attachments { try? FileManager.default.removeItem(at: attachmentURL(file)) }
      }
      let delay = testMode && ProcessInfo.processInfo.arguments.contains("-composer-action-fixture") ? 12000 : 1500
      do { try await Task.sleep(for: .milliseconds(delay)) } catch { return }
      saved.history[key, default: []].append(
        ChatMessage(
          id: UUID().uuidString, role: "assistant",
          text:
            L10n.tr("本地体验已接收这条消息，目标为 **\(client.name) · \(project?.name ?? L10n.tr("无项目"))**。\n\n这是界面示例回复，没有向电脑或模型发送。"), status: "completed"
        ))
      dirtyHistoryKeys.insert(key)
      if saved.runtimes?[key]?.turnId == turnId {
        saved.runtimes?[key]?.state = "idle"
        saved.runtimes?[key]?.turnId = nil
        demoTasks.removeValue(forKey: key)
      }
      persist()
    }
  }

  func addClient(name: String, address: String, token: String = "") async throws {
    guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      throw ClientFormError.emptyName
    }
    var snapshot = try await HostService.workspace(address, token: token)
    snapshot.capabilities = CodexAgentClient().normalizedCapabilities(snapshot.capabilities ?? [], connectionMode: snapshot.connectionMode)
    guard
      ["history-only", "managed-runtime", "shared-runtime", "desktop-runtime", "easywork-runtime"].contains(snapshot.connectionMode),
      snapshot.projects.allSatisfy({ $0.hostId == snapshot.hostId }),
      snapshot.threads.allSatisfy({ $0.hostId == snapshot.hostId })
    else { throw HostServiceError.invalidResponse }
    try CredentialVault.set(token, id: snapshot.hostId)
    if let index = saved.clients.firstIndex(where: { $0.id == snapshot.hostId }) {
      saved.clients[index].endpoint = address
      saved.clients[index].name = name
      saved.clients[index].online = true
    } else {
      saved.clients.append(
        Client(
          id: snapshot.hostId, name: name.trimmingCharacters(in: .whitespacesAndNewlines),
          endpoint: address, isDemo: false, online: true))
    }
    saved.workspaces[snapshot.hostId] = snapshot
    selectVerifiedClient(snapshot.hostId)
  }

  private func selectVerifiedClient(_ id: String) {
    // The connection flow has just authenticated and validated this workspace.
    // Start its current chat from that snapshot, without another serial catalog
    // request. Ordinary foreground/sidebar/unread refresh still updates it.
    selectClient(id, refreshAutomatically: false)
    guard let host = client else { return }
    recordConnectionSuccess(host)
    let generation = clientGeneration
    let initialEvents = saved.workspaces[id]?.capabilities?.contains("events") == true && selectedThreadId != nil
    startEvents(initialReadGrace: initialEvents ? 8 : 0)
    if initialEvents { return }
    Task {
      guard clientGeneration == generation, client?.id == id else { return }
      await loadHistory()
    }
  }

  func addCodexQR(_ text: String, name: String) async throws {
    let (offer, incoming, rawSnapshot) = try await CodexDiscoveryAPI.connect(text, name: name)
    try saveCodexConnection(offer, incoming: incoming, rawSnapshot: rawSnapshot, name: name)
  }
  func addCodexDiscovered(_ host: CodexHostDiscovery.Host, hostKey: String = "") async throws {
    let expected = saved.clients.first(where: { $0.id == host.id })?.codexDirect
    let (offer, incoming, rawSnapshot) = try await CodexDiscoveryAPI.connectDiscovered(host.endpoint, hostId: host.id, name: "", hostKey: hostKey, expected: expected)
    try saveCodexConnection(offer, incoming: incoming, rawSnapshot: rawSnapshot, name: "")
  }
  private func saveCodexConnection(_ offer: CodexHostDescriptor, incoming: Client, rawSnapshot: WorkspaceSnapshot, name: String) throws {
    guard rawSnapshot.projects.allSatisfy({ $0.hostId == incoming.id }), rawSnapshot.threads.allSatisfy({ $0.hostId == incoming.id }) else { throw HostServiceError.wrongHost }
    if let old = saved.clients.first(where: { $0.id == incoming.id }),
      let target = old.codexDirect?.target ?? saved.workspaces[old.id]?.readTarget, target != offer.target { throw HostServiceError.wrongHost }
    var incoming = incoming
    if let old = saved.clients.first(where: { $0.id == incoming.id }), var direct = incoming.codexDirect {
      guard old.codexDirect == nil || old.codexDirect?.fingerprint == direct.fingerprint else { throw HostServiceError.wrongHost }
      let legacy = old.codexDirect?.candidates.contains(where: { $0.endpoint == old.endpoint }) == true ? nil : old.endpoint
      direct.systemEndpoints = CodexRouteRules.shared.mergeSystemEndpoints(existing: old.codexDirect?.systemEndpoints ?? [], supplied: direct.systemEndpoints ?? [], legacy: legacy)
      let oldCandidates = old.codexDirect?.candidates ?? []
      direct.candidates += oldCandidates.filter { old in !direct.candidates.contains(where: { $0.endpoint == old.endpoint }) }
      direct.candidates = Array(direct.candidates.prefix(32))
      incoming.codexDirect = direct
    }
    try CredentialVault.set(offer.token!, id: incoming.id)
    var snapshot = rawSnapshot
    snapshot.capabilities = CodexAgentClient().normalizedCapabilities(snapshot.capabilities ?? [], connectionMode: snapshot.connectionMode)
    var client = incoming; client.online = true
    if let index = saved.clients.firstIndex(where: { $0.id == incoming.id }) {
      saved.clients[index].endpoint = incoming.endpoint; saved.clients[index].codexDirect = incoming.codexDirect
      saved.clients[index].online = true; if !name.isEmpty { saved.clients[index].name = name }
      client = saved.clients[index]
    } else { saved.clients.append(client) }
    CodexHostRoutes.shared.register(client)
    saved.workspaces[client.id] = snapshot
    selectVerifiedClient(client.id); persist()
  }

  func updateCodexDiscovery(_ previous: Client, host: CodexHostDescriptor, reachableEndpoint: String) -> Bool {
    guard let index = saved.clients.firstIndex(where: { $0.id == previous.id }), saved.clients[index].codexDirect == previous.codexDirect,
      saved.clients[index].endpoint == previous.endpoint,
      host.target == (previous.codexDirect?.target ?? saved.workspaces[previous.id]?.readTarget), host.target.hostId == previous.id,
      previous.codexDirect == nil || previous.codexDirect?.fingerprint == host.fingerprint.lowercased(),
      let hostCandidates = try? host.candidates() else { return false }
    let isPinnedRoute = previous.codexDirect?.candidates.contains(where: { $0.endpoint == reachableEndpoint }) == true || reachableEndpoint.lowercased().contains(".local")
    let reachable: [AgentConnectionCandidate] = isPinnedRoute ? ((try? EasyWorkPairing.directCandidates([reachableEndpoint], fingerprint: host.fingerprint)) ?? []).map { value in var item = value; item.transportId = "codex-https"; return item } : []
    let candidates = reachable + hostCandidates.filter { item in !reachable.contains(where: { $0.endpoint == item.endpoint }) }
    let old = previous.codexDirect?.candidates ?? []
    let combined = candidates + old.filter { item in !candidates.contains(where: { $0.endpoint == item.endpoint }) }
    let legacy = previous.codexDirect?.candidates.contains(where: { $0.endpoint == previous.endpoint }) == true ? nil : previous.endpoint
    let systemEndpoints = CodexRouteRules.shared.mergeSystemEndpoints(existing: previous.codexDirect?.systemEndpoints ?? [], supplied: host.systemEndpoints ?? [], legacy: legacy)
    let connection = CodexDirectConnection(target: host.target, candidates: Array(combined.prefix(32)), fingerprint: host.fingerprint.lowercased(), systemEndpoints: systemEndpoints)
    let priorEndpoint = saved.clients[index].endpoint
    let changed = saved.clients[index].codexDirect != connection || saved.clients[index].endpoint != reachableEndpoint
    saved.clients[index].codexDirect = connection
    // The first successful probe uses the current address before alternatives.
    saved.clients[index].endpoint = reachable.first?.endpoint ?? reachableEndpoint
    CodexHostRoutes.shared.register(saved.clients[index])
    if client?.id == previous.id, saved.clients[index].endpoint != priorEndpoint {
      // Retire reads captured on the old route without cancelling/replaying any
      // submitted write. The reachable route need not wait for its 35 s timeout.
      invalidateAgentConnection(previous.id)
      Task { await refresh() }
    }
    if changed { persist() }
    return true
  }

  func updateClient(_ id: String, name: String, address: String, token: String) async throws {
    let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
    let address = address.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !name.isEmpty else { throw ClientFormError.emptyName }
    guard let host = saved.clients.first(where: { $0.id == id }), !host.isDemo else {
      throw HostServiceError.wrongHost
    }
    if address == host.endpoint, token == CredentialVault.token(id) {
      // A local nickname change also works while the computer is offline.
      if let index = saved.clients.firstIndex(where: { $0.id == id }) {
        saved.clients[index].name = name
      }
      persist()
      return
    }
    var snapshot = try await HostService.workspace(address, token: token)
    snapshot.capabilities = CodexAgentClient().normalizedCapabilities(snapshot.capabilities ?? [], connectionMode: snapshot.connectionMode)
    guard snapshot.hostId == id else { throw HostServiceError.wrongHost }
    if let direct = host.codexDirect, snapshot.readTarget != direct.target { throw HostServiceError.wrongHost }
    guard
      ["history-only", "managed-runtime", "shared-runtime", "desktop-runtime", "easywork-runtime"].contains(snapshot.connectionMode),
      snapshot.projects.allSatisfy({ $0.hostId == id }),
      snapshot.threads.allSatisfy({ $0.hostId == id })
    else { throw HostServiceError.invalidResponse }
    guard let index = saved.clients.firstIndex(where: { $0.id == id }) else {
      throw HostServiceError.wrongHost
    }
    try CredentialVault.set(token, id: id)
    saved.clients[index].name = name
    saved.clients[index].endpoint = address
    if var direct = host.codexDirect {
      let legacy = direct.candidates.contains(where: { $0.endpoint == host.endpoint }) ? nil : host.endpoint
      let supplied = direct.candidates.contains(where: { $0.endpoint == address }) ? [] : [address]
      direct.systemEndpoints = CodexRouteRules.shared.mergeSystemEndpoints(existing: direct.systemRoutes, supplied: supplied, legacy: legacy)
      saved.clients[index].codexDirect = direct
      CodexHostRoutes.shared.register(saved.clients[index])
    }
    saved.clients[index].online = true
    // Keep loaded pages and the current conversation when editing the connection.
    let previous = saved.workspaces[id]
    let sameReadingTarget = previous?.readTarget == snapshot.readTarget
    saved.workspaces[id] = WorkspaceSnapshot(
      hostId: id, name: snapshot.name, connectionMode: snapshot.connectionMode,
      projects: sameReadingTarget ? previous?.projects ?? snapshot.projects : snapshot.projects,
      threads: sameReadingTarget ? previous?.threads ?? snapshot.threads : snapshot.threads,
      nextProjectCursor: sameReadingTarget ? previous?.nextProjectCursor : snapshot.nextProjectCursor,
      nextThreadCursor: sameReadingTarget ? previous?.nextThreadCursor : snapshot.nextThreadCursor,
      capabilities: snapshot.capabilities, clientType: snapshot.clientType, readTarget: snapshot.readTarget,
      pinnedThreadIds: snapshot.pinnedThreadIds, sidebar: snapshot.sidebar)
    if client?.id == id {
      clientGeneration = UUID()
      historyGeneration = UUID()
      eventTask?.cancel()
      models = []
      loading = false
      error = nil
      startEvents()
      Task { await loadHistory() }
    }
    persist()
  }

  func refresh(loadMore: Bool = false, includeHistory: Bool = true, userInitiated: Bool = false) async {
    #if DEBUG
    if userInitiated, client?.isDemo == true,
      ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-sidebar-refresh-hold-fixture") {
      loading = true
      defer { loading = false }
      try? await Task.sleep(for: .seconds(30))
      return
    }
    #endif
    guard !sidebarInteracting else { return }
    guard pendingConnection == nil, let host = client, !host.isDemo, host.endpoint != nil || host.agentConnection != nil else { return }
    let generation = clientGeneration
    if userInitiated { userRequestedWorkspaceGeneration = generation }
    if workspaceRefreshGeneration == generation {
      // Coalesce an explicit latest-history refresh behind the occupied reader.
      // Preserve its navigation; a later target must not inherit this request.
      if userInitiated && includeHistory && !loadMore {
        pendingManualHistoryRefresh = (generation, historyGeneration)
      }
      guard userInitiated else { return }
      do {
        while workspaceRefreshGeneration == generation, clientGeneration == generation {
          try await Task.sleep(for: .milliseconds(50))
        }
      } catch {}
      return
    }
    let owner = UUID()
    workspaceRefreshOwner = owner
    workspaceRefreshGeneration = generation
    let startedAt = ProcessInfo.processInfo.systemUptime
    let managementRevision = managementRevisions[host.id]
    loading = true
    if !host.online { connectionPhases[host.id] = .connecting }
    defer {
      if workspaceRefreshOwner == owner {
        workspaceRefreshOwner = nil; workspaceRefreshGeneration = nil
        if userRequestedWorkspaceGeneration == generation { userRequestedWorkspaceGeneration = nil }
        if clientGeneration == generation { loading = false }
        if let pending = pendingManualHistoryRefresh {
          pendingManualHistoryRefresh = nil
          Task {
            guard clientGeneration == pending.client, historyGeneration == pending.history else { return }
            await refresh(userInitiated: true)
          }
        }
      }
    }
    do {
      let previous = saved.workspaces[host.id]
      var result = try await AgentClients.resolve(host).workspace(
        readingClient(host),
        projectCursor: loadMore ? previous?.nextProjectCursor : nil,
        threadCursor: loadMore ? previous?.nextThreadCursor : nil)
      try await waitForSidebarInteraction()
      try Task.checkCancellation()
      guard clientGeneration == generation, client?.id == host.id, workspaceRefreshOwner == owner else { return }
      if host.agentConnection != nil {
        let route = await (try AgentClients.resolve(host)).connectionTitle(host)
        try await waitForSidebarInteraction()
        try Task.checkCancellation()
        guard clientGeneration == generation, client?.id == host.id, workspaceRefreshOwner == owner else { return }
        connectionRoutes[host.id] = route
      }
      guard result.hostId == host.id, result.projects.allSatisfy({ $0.hostId == host.id }),
        result.threads.allSatisfy({ $0.hostId == host.id })
      else { throw HostServiceError.wrongHost }
      if host.agentConnection == nil, let target = result.readTarget,
        target.hostId != host.id || target.agentId != (result.clientType?.id ?? "codex") {
        throw HostServiceError.wrongHost
      }
      guard managementRevisions[host.id] == managementRevision else { return }
      if previous?.readTarget == result.readTarget {
        result.threads = result.threads.map { incoming in
          mergeReadingSummary(incoming, prior: previous?.threads.first { $0.id == incoming.id })
        }
      }
      // Display the first page and selected conversation before fetching an
      // entire catalog over a high-latency link. Retain cached tail entries only
      // while a next page exists; the unread sweep replaces the complete catalog.
      if !loadMore, result.nextThreadCursor != nil, let previous, previous.readTarget == result.readTarget {
        let ids = Set(result.threads.map(\.id))
        result.threads += previous.threads.filter { !ids.contains($0.id) }
      }
      if loadMore, let previous, previous.readTarget == result.readTarget {
        let projects = result.projects.reduce(into: previous.projects) { items, new in
          if !items.contains(where: { $0.id == new.id }) { items.append(new) }
        }
        let threads = result.threads.reduce(into: previous.threads) { items, new in
          if !items.contains(where: { $0.id == new.id }) { items.append(new) }
        }
        saved.workspaces[host.id] = WorkspaceSnapshot(
          hostId: host.id, name: result.name, connectionMode: result.connectionMode,
          projects: projects, threads: threads,
          nextProjectCursor: previous.nextProjectCursor == nil
            ? nil : result.nextProjectCursor,
          nextThreadCursor: previous.nextThreadCursor == nil
            ? nil : result.nextThreadCursor, capabilities: result.capabilities, clientType: result.clientType, agentConnectionState: result.agentConnectionState, readTarget: result.readTarget,
          pinnedThreadIds: result.pinnedThreadIds ?? previous.pinnedThreadIds, sidebar: result.sidebar ?? previous.sidebar)
      } else {
        saved.workspaces[host.id] = result
      }
      recordConnectionSuccess(host)
      if let index = saved.clients.firstIndex(where: { $0.id == host.id }) {
        if saved.clients[index].name == L10n.tr("本机 Codex · 原桌面") {
          saved.clients[index].name = result.name
        }
      }
      reconcileReading(host)
      if client?.id == host.id {
        if selectedThreadId == nil { restoreSelection(host.id) }
        // A restored pending input must be queried even if the first history
        // request never returns. The workspace above has validated this target.
        let initialEvents = !userInitiated && includeHistory && selectedThreadId != nil
          && loadedHistoryGeneration != historyGeneration
          && saved.workspaces[host.id]?.capabilities?.contains("events") == true
          && latestHistoryReads[targetKey] != historyGeneration
        startEvents(initialReadGrace: initialEvents ? 8 : 0)
        if includeHistory, !initialEvents, let thread = conversation { await loadHistory(threadId: thread.id, userInitiated: userInitiated) }
        try Task.checkCancellation()
        guard clientGeneration == generation, client?.id == host.id, workspaceRefreshOwner == owner else { return }
        startEvents()
      }
      persist()
    } catch {
      guard !isReadCancellation(error), clientGeneration == generation, client?.id == host.id, workspaceRefreshOwner == owner else { return }
      recordConnectionFailure(error, host: host, startedAt: startedAt)
      if userInitiated || userRequestedWorkspaceGeneration == generation {
        self.error = "刷新失败，请重试。\n" + error.userFacingDescription
      }
      if client?.id == host.id { startEvents() }
      persist()
    }
  }

  /// Read every configured workspace without navigating, loading a conversation or changing selection.
  private func completeReadingThreadPages(_ first: WorkspaceSnapshot, host: Client) async throws -> WorkspaceSnapshot {
    var result = first
    var cursors: Set<String> = []
    let provider = try AgentClients.resolve(host)
    while let cursor = result.nextThreadCursor {
      guard cursors.insert(cursor).inserted else { throw HostServiceError.invalidResponse }
      let page = try await provider.workspace(readingClient(host), projectCursor: nil, threadCursor: cursor)
      guard page.hostId == host.id, page.readTarget == result.readTarget,
        page.threads.allSatisfy({ $0.hostId == host.id }) else { throw HostServiceError.wrongHost }
      let threads = page.threads.reduce(into: result.threads) { items, thread in
        if !items.contains(where: { $0.id == thread.id }) { items.append(thread) }
      }
      result = WorkspaceSnapshot(hostId: result.hostId, name: result.name, connectionMode: result.connectionMode,
        projects: result.projects, threads: threads, nextProjectCursor: result.nextProjectCursor,
        nextThreadCursor: page.nextThreadCursor, capabilities: result.capabilities, clientType: result.clientType,
        agentConnectionState: result.agentConnectionState, readTarget: result.readTarget,
        pinnedThreadIds: result.pinnedThreadIds ?? page.pinnedThreadIds, sidebar: result.sidebar ?? page.sidebar)
      try Task.checkCancellation()
    }
    return result
  }

  func refreshMobileUnread() async {
    guard !sidebarInteracting else { return }
    guard pendingConnection == nil else { return }
    for host in saved.clients.filter({ !$0.isDemo }) {
      if Task.isCancelled { return }
      if host.id == client?.id && (loading || historyLoading) { continue }
      do {
        let provider = try AgentClients.resolve(host)
        let previous = saved.workspaces[host.id]
        var result = try await provider.workspace(readingClient(host), projectCursor: nil, threadCursor: nil)
        result = try await completeReadingThreadPages(result, host: host)
        try await waitForSidebarInteraction()
        try Task.checkCancellation()
        if host.id == client?.id && loading { continue }
        guard saved.clients.contains(where: { $0.id == host.id && $0.endpoint == host.endpoint && $0.agentConnection == host.agentConnection }),
          saved.workspaces[host.id]?.readTarget == previous?.readTarget else { continue }
        guard result.hostId == host.id, result.threads.allSatisfy({ $0.hostId == host.id }),
          result.projects.allSatisfy({ $0.hostId == host.id }),
          result.readTarget.map({ $0.hostId == host.id && $0.agentId == (result.clientType?.id ?? "codex") }) ?? true else { throw HostServiceError.wrongHost }
        if result.readTarget == previous?.readTarget {
          result.threads = result.threads.map { incoming in mergeReadingSummary(incoming, prior: previous?.threads.first { $0.id == incoming.id }) }
        }
        if let previous, previous.readTarget == result.readTarget {
          result.projects = previous.projects + result.projects.filter { project in !previous.projects.contains { $0.id == project.id } }
        }
        saved.workspaces[host.id] = result
        if let index = saved.clients.firstIndex(where: { $0.id == host.id }) { saved.clients[index].online = true }
        reconcileReading(host)
        persist()
      } catch {
        // Offline hosts retain this phone's last known count. No desktop flag or failed read clears it.
        if isReadCancellation(error) { return }
        if saved.clients.contains(where: { $0.id == host.id && $0.endpoint == host.endpoint && $0.agentConnection == host.agentConnection }),
          host.id != client?.id { recordConnectionFailure(error, host: host, displayError: false) }
      }
    }
    synchronizeMobileUnread()
  }

  private func requireReplyTarget(_ host: Client, threadId: String, generation: UUID) throws {
    try Task.checkCancellation()
    guard historyGeneration == generation, client?.id == host.id, selectedThreadId == threadId,
      client?.endpoint == host.endpoint, client?.agentConnection == host.agentConnection else { throw CancellationError() }
  }

  private func connectForReply(_ host: Client, threadId: String, key: String, generation: UUID) async throws {
    try requireReplyTarget(host, threadId: threadId, generation: generation)
    let adapter = try AgentClients.resolve(host)
    let existingOperation = drafts.values[key]?.desktopOpenOperationId
    transfers[key] = L10n.tr("正在连接 \(clientTypeName(for: host))…")
    // History can contain an owner snapshot cached before the renderer unloaded
    // this chat. The named open operation checks the actual owner first and
    // acknowledges an already connected chat without navigating the desktop.
    let operationId = existingOperation ?? UUID().uuidString
    drafts.values[key, default: Draft()].desktopOpenOperationId = operationId
    try await persistBeforeCommand()
    try requireReplyTarget(host, threadId: threadId, generation: generation)
    var operation: DesktopOpenOperation
    if existingOperation != nil {
      do { operation = try await adapter.openStatus(host, threadId: threadId, operationId: operationId) }
      catch HostServiceError.rejected(let status, _) where status == 404 {
        // A new explicit Send may retry an unrecorded connection with the same
        // idempotency key. A recorded unknown result is only queried, never replayed.
        try requireReplyTarget(host, threadId: threadId, generation: generation)
        operation = try await adapter.openThread(host, threadId: threadId, operationId: operationId)
      }
    } else {
      operation = try await adapter.openThread(host, threadId: threadId, operationId: operationId)
    }
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(180))
    while clock.now < deadline {
      try requireReplyTarget(host, threadId: threadId, generation: generation)
      guard operation.hostId == host.id, operation.threadId == threadId,
        operation.clientOperationId == operationId else { throw HostServiceError.wrongHost }
      if ["opened", "outcome-unknown"].contains(operation.phase),
        try await verifyReplyRuntime(host, threadId: threadId, generation: generation) {
        if drafts.values[key]?.desktopOpenOperationId == operationId {
          drafts.values[key]?.desktopOpenOperationId = nil
          persist()
        }
        return
      }
      if operation.phase == "opened" {
        throw HostServiceError.message(L10n.tr("\(clientTypeName(for: host)) 尚未连接此对话，消息和附件仍保留在草稿中。"))
      }
      // An unknown open result can precede a late renderer owner. Continue
      // querying this same operation within the monotonic wait deadline;
      // never repeat navigation or submit before the owner is confirmed.
      try await Task.sleep(for: .seconds(1))
      try requireReplyTarget(host, threadId: threadId, generation: generation)
      operation = try await adapter.openStatus(host, threadId: threadId, operationId: operationId)
    }
    throw HostServiceError.message(L10n.tr("连接结果待确认，消息仍保留在草稿中；再次发送会先查询连接状态。"))
  }

  private func verifyReplyRuntime(_ host: Client, threadId: String, generation: UUID) async throws -> Bool {
    let snapshot = try await AgentClients.resolve(host).history(readingClient(host), threadId: threadId, cursor: nil)
    try requireReplyTarget(host, threadId: threadId, generation: generation)
    guard snapshot.hostId == host.id, snapshot.threadId == threadId else { throw HostServiceError.wrongHost }
    apply(snapshot)
    return snapshot.runtime?.canControl == true
  }

  @discardableResult
  func loadHistory(threadId: String? = nil, more: Bool = false, userInitiated: Bool = false,
    beforePrepend: (@MainActor (Int) -> Void)? = nil) async -> Bool {
    guard let host = client, !host.isDemo, let id = threadId ?? selectedThreadId else { return false }
    guard selectedThreadId == id, threads.contains(where: { $0.id == id && $0.hostId == host.id }) else { return false }
    let generation = historyGeneration
    let key = cacheKey(host, threadId: id)
    if more {
      guard historyCursor != nil, earlierHistoryReads[key] == nil else { return false }
      earlierHistoryReads[key] = generation
    } else {
      guard latestHistoryReads[key] != generation, earlierHistoryReads[key] != generation else { return false }
      latestHistoryReads[key] = generation
    }
    defer {
      if more, earlierHistoryReads[key] == generation { earlierHistoryReads.removeValue(forKey: key) }
      if !more, latestHistoryReads[key] == generation { latestHistoryReads.removeValue(forKey: key) }
    }
    if more {
      // Keep one explicit prefix action while a latest read completes. Reserve
      // before yielding so repeated actions and new refreshes cannot overtake it.
      while (loadingHistoryRequests[key] ?? 0) > 0 {
        guard !Task.isCancelled, historyGeneration == generation,
          client?.id == host.id, selectedThreadId == id, targetKey == key else { return false }
        do { try await Task.sleep(for: .milliseconds(50)) } catch { return false }
      }
      guard !Task.isCancelled, historyGeneration == generation,
        client?.id == host.id, selectedThreadId == id, targetKey == key, historyCursor != nil else { return false }
    }
    let startedAt = ProcessInfo.processInfo.systemUptime
    loadingHistoryRequests[key, default: 0] += 1
    defer {
      loadingHistoryRequests[key, default: 0] -= 1
      if loadingHistoryRequests[key] == 0 { loadingHistoryRequests.removeValue(forKey: key) }
    }
    do {
      try Task.checkCancellation()
      #if DEBUG
      let timingNavigation = clientGeneration.uuidString + ":" + generation.uuidString
      DesktopSuggestionTiming.mark("historyRequest", navigation: timingNavigation)
      #endif
      let result = try await SharedMobileRules.history(
        readingClient(host), targetKey: key, threadId: id, cursor: more ? historyCursor : nil)
      #if DEBUG
      DesktopSuggestionTiming.mark("historyResponse", navigation: timingNavigation)
      #endif
      try await waitForSidebarInteraction()
      try Task.checkCancellation()
      guard historyGeneration == generation, client?.id == host.id, selectedThreadId == id, !more || targetKey == key
      else { return false }
      guard result.hostId == host.id, result.threadId == id else { throw HostServiceError.wrongHost }
      recordConnectionSuccess(host)
      let windowed = result.historyWindow?.isWindowed == true
      let knownRevisionMatches = result.historyRevision.map { historyWindowRevisions[key] == $0 } ?? false
      let legacyV9Page = windowed && result.historyRevision == nil
        && result.synchronization != nil && loadedHistoryGeneration == generation
        && result.historyWindow?.kind == "earlier"
      if more, windowed, !knownRevisionMatches {
        // A revision appearing/disappearing is stale too. Only the pre-revision
        // V9 anchored earlier-page protocol retains its explicit compatibility path.
        guard historyWindowRevisions[key] == nil, legacyV9Page else {
          throw HostServiceError.message(L10n.tr("聊天历史已变化，请刷新后重试。"))
        }
      }
      let preserve = SharedReadingPolicy.preserveHistoryPrefix(
        windowed: result.runtime == nil || windowed,
        hasPaged: pagedHistoryGeneration != nil, sameGeneration: pagedHistoryGeneration == generation,
        // Plain historical paging has no live-window revision contract.
        sameRevision: !windowed || knownRevisionMatches)
      let resultMessageIds = Set(result.messages.map(\.id))
      let preserveLegacyV9 = windowed && result.historyRevision == nil && historyWindowRevisions[key] == nil && result.synchronization != nil
        && pagedHistoryGeneration == generation
        && (saved.history[key] ?? []).contains { resultMessageIds.contains($0.id) }
      if more && result.runtime == nil {
        let existing = saved.history[key] ?? []
        var ids = Set(existing.map(\.id))
        let prefix = result.messages.filter { ids.insert($0.id).inserted }
        beforePrepend?(prefix.count)
        saved.history[key] = prefix + existing
        dirtyHistoryKeys.insert(key)
        pagedHistoryGeneration = generation
      } else if !more, preserve, result.historyWindow?.isWindowed != true {
        // A foreground/latest-page read must not discard earlier pages or
        // rearm an exhausted cursor while this same reading session is open.
        let existing = saved.history[key] ?? []
        let incomingIds = Set(result.messages.map(\.id))
        let boundary = existing.firstIndex { incomingIds.contains($0.id) } ?? existing.endIndex
        apply(HistorySnapshot(readTarget: result.readTarget,
          hostId: result.hostId, threadId: result.threadId, thread: result.thread,
          messages: Array(existing[..<boundary]) + result.messages,
          nextCursor: result.nextCursor, runtime: result.runtime,
          epoch: result.epoch, cursor: result.cursor))
      } else {
        apply(result, historyPage: more, beforePrepend: more ? beforePrepend : nil)
      }
      if result.historyWindow?.isWindowed == true, more { pagedHistoryGeneration = generation }
      if more || !(preserve || preserveLegacyV9) {
        historyCursor = result.nextCursor
      }
      if !more, let scope = nextTurnSuggestionScope { prepareNextTurnSuggestion(host, scope: scope) }
      #if DEBUG
      DesktopSuggestionTiming.mark("historyApplied", navigation: timingNavigation)
      #endif
      // apply() already schedules a coalesced save, also flushed on background.
      // Only the manual earlier-page merge above bypasses that save.
      if more && result.runtime == nil { persist() }
      return true
    } catch {
      guard !isReadCancellation(error), historyGeneration == generation,
        client?.id == host.id, selectedThreadId == id else { return false }
      recordConnectionFailure(error, host: host, startedAt: startedAt)
      if !more {
        let message = (error as? URLError)?.code == .timedOut
          ? L10n.tr("读取对话超时，已有消息和草稿已保留。") : error.userFacingDescription
        conversationSyncFailures[key] = ConversationSyncIssue(message: message)
      }
      // Earlier-history reads are an explicit user action. Automatic reconnect
      // reads stay quiet, but a failed pull/tap must offer visible retry feedback.
      if more { self.error = L10n.tr("加载更早消息失败，请重试。\n") + error.userFacingDescription }
      else if userInitiated { self.error = L10n.tr("加载历史失败，请重试。\n") + error.userFacingDescription }
      return false
    }
  }

  private func isReadCancellation(_ error: Error) -> Bool {
    // SwiftUI tasks and event subscriptions are cancelled when their view or
    // target changes. That lifecycle cleanup is not a failed user operation.
    Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled
  }

  func waitForSidebarInteraction() async throws {
    // Keep the original response/cursor, without issuing another read or replaying input.
    while sidebarInteracting { try await Task.sleep(for: .milliseconds(50)) }
    try Task.checkCancellation()
  }

  private func startReplyRecovery() {
    guard pendingConnection == nil, let host = client, !host.isDemo else { return }
    let generation = clientGeneration
    // Receipt recovery must not wait for a history request, long poll or an
    // events capability. A stalled read can otherwise strand a completed send.
    if replyRecoveryTask == nil || replyRecoveryTask?.isCancelled == true
      || replyRecoveryGeneration != generation || replyRecoveryHostId != host.id {
      replyRecoveryTask?.cancel()
      replyRecoveryGeneration = generation
      replyRecoveryHostId = host.id
      replyRecoveryTask = Task {
        while !Task.isCancelled && clientGeneration == generation && client?.id == host.id {
          if UIApplication.shared.applicationState == .active { reconcilePending(automatically: true) }
          do { try await Task.sleep(for: .seconds(1)) } catch { return }
        }
      }
    }
  }

  func startEvents(initialReadGrace: TimeInterval = 0) {
    startReplyRecovery()
    guard pendingConnection == nil, let host = client, !host.isDemo else { return }
    let generation = clientGeneration
    guard eventTask == nil || eventTask?.isCancelled == true,
      saved.workspaces[host.id]?.capabilities?.contains("events") == true else { return }
    eventRecoveryTask?.cancel()
    let initialCatchUpAfter = ProcessInfo.processInfo.systemUptime + initialReadGrace
    eventRecoveryTask = Task {
      var lastAttempt = initialReadGrace > 0 ? -1 : Int64(ProcessInfo.processInfo.systemUptime * 1000)
      var attemptThread = selectedThreadId
      // This read is independent of the long poll, so a silently stalled event
      // connection cannot prevent the selected conversation from catching up.
      while !Task.isCancelled && clientGeneration == generation && client?.id == host.id {
        if let id = selectedThreadId {
          if attemptThread != id { attemptThread = id; lastAttempt = -1 }
          let key = cacheKey(host, threadId: id)
          let active = saved.runtimes?[key]?.state == "running" || saved.outbox?[key] != nil || conversationSyncFailures[key] != nil
          let now = Int64(ProcessInfo.processInfo.systemUptime * 1000)
          if OperationPolicies.shared.historyCatchUpDue(now: now,
            lastUseful: lastSnapshotAt[key].map { Int64($0 * 1000) } ?? -1,
            lastAttempt: lastAttempt, active: active, graceUntil: Int64(initialCatchUpAfter * 1000)),
            loadingHistoryRequests[key, default: 0] == 0 {
            lastAttempt = now
            await loadHistory(threadId: id)
          }
        }
        do { try await Task.sleep(for: .seconds(1)) } catch { return }
      }
    }
    eventTask = Task {
      var epoch = ""
      var cursor = 0
      var activeThread: String?
      var delay = 1
      while !Task.isCancelled && clientGeneration == generation && client?.id == host.id {
        let startedAt = ProcessInfo.processInfo.systemUptime
        do {
          guard let threadId = selectedThreadId else {
            epoch = ""
            cursor = 0
            try await Task.sleep(for: .seconds(1))
            continue
          }
          if activeThread != threadId {
            activeThread = threadId; epoch = ""; cursor = 0
            if let savedCursor = saved.agentEventCursors?[targetKey], savedCursor.target == mobileReadingTarget(host), savedCursor.threadId == threadId {
              epoch = savedCursor.epoch; cursor = savedCursor.sequence
            }
          }
          let currentHost = client ?? host
          let expectedReadingTarget = mobileReadingTarget(currentHost)
          let batch = try await AgentClients.resolve(currentHost).events(readingClient(currentHost), epoch: epoch, after: cursor, threadId: threadId)
          try Task.checkCancellation()
          guard clientGeneration == generation, client?.id == host.id else { break }
          guard selectedThreadId == threadId else {
            epoch = ""
            cursor = 0
            continue
          }
          let route = await (try AgentClients.resolve(host)).connectionTitle(host)
          try await waitForSidebarInteraction()
          try Task.checkCancellation()
          guard clientGeneration == generation, client?.id == host.id, selectedThreadId == threadId else { continue }
          if connectionRoutes[host.id] != route { connectionRoutes[host.id] = route }
          guard batch.hostId == host.id else { throw HostServiceError.wrongHost }
          guard batch.cursor >= 0 else { throw HostServiceError.invalidResponse }
          if let rawCapabilities = batch.capabilities {
            let capabilities = try AgentClients.resolve(host).normalizedCapabilities(
              rawCapabilities, connectionMode: saved.workspaces[host.id]?.connectionMode)
            if saved.workspaces[host.id]?.capabilities != capabilities {
              saved.workspaces[host.id]?.capabilities = capabilities
            }
          }
          let decision = OperationPolicies.shared.event(previousEpoch: epoch, previousCursor: Int64(cursor),
            epoch: batch.epoch, cursor: Int64(batch.cursor), reset: batch.reset, ready: batch.ready == true, snapshotCount: Int32(batch.events.count))
          for event in batch.events {
            guard event.snapshot.hostId == host.id,
              event.snapshot.threadId == event.threadId, event.threadId == threadId
            else { throw HostServiceError.wrongHost }
            if let received = event.snapshot.readTarget, received != expectedReadingTarget { throw HostServiceError.wrongHost }
            if decision.apply { apply(event.snapshot) }
          }
          epoch = decision.epoch
          cursor = Int(decision.cursor)
          if decision.apply {
            let target = mobileReadingTarget(host)
            storeEventCursor(AgentEventCursor(target: target, threadId: threadId, epoch: epoch, sequence: cursor), key: targetKey)
            // apply already checkpoints the latest state/history and unread
            // projection on the bounded snapshot checkpoint. A second immediate save for
            // every streaming batch doubled metadata encode/decode and I/O.
            // Cursor-only batches have no apply task and still need a checkpoint.
            if batch.events.isEmpty { scheduleSnapshotPersistence() }
          }
          delay = 1
          recordConnectionSuccess(host)
          // A reset already carries the selected snapshot. An extra history GET
          // doubles the most expensive read on a slow link without adding state.
          if (decision.reload && batch.events.isEmpty) || suggestionNeedsFreshHistory { await loadHistory() }
        } catch {
          if Task.isCancelled || clientGeneration != generation || client?.id != host.id { break }
          recordConnectionFailure(error, host: host, displayError: false, startedAt: startedAt)
          try? await Task.sleep(for: .seconds(delay))
          delay = min(delay * 2, 15)
        }
      }
    }
  }
  func suspendEvents() {
    suggestionReadAhead.cancel()
    eventTask?.cancel()
    eventRecoveryTask?.cancel()
    replyRecoveryTask?.cancel()
    persist()
  }
  func recoverNetworkReads() {
    guard let host = client, !host.isDemo,
      (eventTask != nil && eventTask?.isCancelled == false)
        || (replyRecoveryTask != nil && replyRecoveryTask?.isCancelled == false) else { return }
    eventTask?.cancel()
    eventRecoveryTask?.cancel()
    replyRecoveryTask?.cancel()
    if let id = selectedThreadId { lastSnapshotAt.removeValue(forKey: cacheKey(host, threadId: id)) }
    startEvents()
  }
  // BEGIN: history-state-publication
  // Cursor progress is durable transport metadata. Updating an existing full
  // target/thread cannot change its storage aliases or any presentation value.
  private func storeEventCursor(_ cursor: AgentEventCursor, key: String) {
    let previous = saved.agentEventCursors?[key]
    guard previous != cursor else { return }
    let wasUpdating = updatingCursorMetadata
    updatingCursorMetadata = wasUpdating || (previous?.target == cursor.target && previous?.threadId == cursor.threadId)
    defer { updatingCursorMetadata = wasUpdating }
    if saved.agentEventCursors == nil { saved.agentEventCursors = [:] }
    saved.agentEventCursors?[key] = cursor
  }

  private func storeRuntime(_ runtime: ThreadRuntime, key: String) {
    if saved.runtimes?[key] != runtime { saved.runtimes?[key] = runtime }
  }

  private func storeConversationSyncIssue(_ issue: ConversationSyncIssue?, key: String) {
    guard conversationSyncFailures[key] != issue else { return }
    if let issue { conversationSyncFailures[key] = issue }
    else { conversationSyncFailures.removeValue(forKey: key) }
  }

  // These non-published fields used to rely on an unrelated saved-value write
  // to refresh controls. Publish their real changes explicitly before removing
  // no-op runtime/summary publications.
  private func confirmSelectedHistory(runtimePresent: Bool) {
    guard loadedHistoryGeneration != historyGeneration || (runtimePresent && suggestionNeedsFreshHistory) else { return }
    objectWillChange.send()
    loadedHistoryGeneration = historyGeneration
    if runtimePresent { suggestionNeedsFreshHistory = false }
  }
  // END: history-state-publication

  private func apply(_ snapshot: HistorySnapshot, historyPage: Bool = false,
    beforePrepend: (@MainActor (Int) -> Void)? = nil) {
    #if DEBUG
    let workStart = HistoryScrollProbe.workStart()
    defer { HistoryScrollProbe.workEnd(3, workStart) }
    #endif
    if let target = snapshot.readTarget,
      let current = saved.workspaces[snapshot.hostId]?.readTarget, target != current { return }
    if !snapshot.messages.isEmpty {
      NativeReadAcceptanceTiming.record("history-applying", hostId: snapshot.hostId, threadId: snapshot.threadId)
    }
    let key = saved.clients.first(where: { $0.id == snapshot.hostId }).map { cacheKey($0, threadId: snapshot.threadId) } ?? snapshot.hostId + ":" + snapshot.threadId
    let liveWindow = snapshot.synchronization?.state == "live-window"
    let historyFresh = (snapshot.synchronization?.state == "history" || liveWindow) && snapshot.synchronization?.checkedAt != nil
    let unavailable = snapshot.synchronization?.state == "unavailable"
      || (saved.workspaces[snapshot.hostId]?.connectionMode == "desktop-runtime"
        && snapshot.runtime?.canControl == false && snapshot.runtime?.state == "unknown")
    let archivedHistory = snapshot.thread?.archived == true && snapshot.runtime?.canControl == false
      && ["unknown", "idle", "failed"].contains(snapshot.runtime?.state ?? "") && saved.outbox?[key] == nil
    let finishedHistory = snapshot.synchronization?.state == "history"
      && ["completed", "interrupted", "failed"].contains(snapshot.thread?.lastTurnStatus ?? "")
      && snapshot.runtime?.canControl == false && snapshot.runtime?.turnId == nil
      && snapshot.runtime?.approvals.isEmpty == true && saved.outbox?[key] == nil
      && snapshot.runtime?.receipts.contains(where: {
        $0.noticeContext != "historical" && ["pending", "queued", "sending", "submitted", "accepted", "running", "uncertain"].contains($0.state)
      }) == false
    if historyFresh, let checkedAt = snapshot.synchronization?.checkedAt {
      if archivedHistory || finishedHistory {
        // A freshly verified terminal turn needs no active stream. This does
        // not grant execution control or hide failures of actual history reads.
        storeConversationSyncIssue(nil, key: key)
      } else {
        storeConversationSyncIssue(ConversationSyncIssue(
          message: liveWindow ? L10n.tr("当前进度正从电脑实时同步；完整桌面状态暂不可用，较早历史按需加载。") : L10n.tr("已从电脑同步最新历史进度，正在重试实时连接。"), historyFresh: true, liveWindow: liveWindow), key: key)
      }
      // Repeated cached events do not postpone the next actual history read.
      if historySyncChecks[key].map({ checkedAt > $0 }) ?? true {
        historySyncChecks[key] = checkedAt
        lastSnapshotAt[key] = ProcessInfo.processInfo.systemUptime
      }
    } else if unavailable {
      // An old event's missing owner is expected for an archived chat. A real
      // history request failure is still retained until a fresh read succeeds.
      if !archivedHistory && (conversationSyncFailures[key] == nil || conversationSyncFailures[key]?.historyFresh == true) {
        storeConversationSyncIssue(ConversationSyncIssue(
          message: L10n.tr("未能取得此对话的最新进度，当前显示缓存。正在自动重试。")), key: key)
      }
    } else {
      lastSnapshotAt[key] = ProcessInfo.processInfo.systemUptime
      storeConversationSyncIssue(nil, key: key)
    }
    if snapshot.hostId == client?.id, snapshot.threadId == selectedThreadId {
      confirmSelectedHistory(runtimePresent: snapshot.runtime != nil)
    }
    if let summary = snapshot.thread, summary.hostId == snapshot.hostId,
      summary.id == snapshot.threadId,
      let index = saved.workspaces[snapshot.hostId]?.threads.firstIndex(where: {
        $0.id == summary.id
      })
    {
      let merged = mergeReadingSummary(summary, prior: saved.workspaces[snapshot.hostId]?.threads[index])
      if saved.workspaces[snapshot.hostId]?.threads[index] != merged {
        saved.workspaces[snapshot.hostId]?.threads[index] = merged
      }
    }
    let isWindow = snapshot.historyWindow?.isWindowed == true
    let earlier = isWindow && (historyPage || snapshot.historyWindow?.kind == "earlier")
    let previous = saved.history[key] ?? []
    let knownRevisionMatches = snapshot.historyRevision.map { historyWindowRevisions[key] == $0 } ?? false
    let preserve = SharedReadingPolicy.preserveHistoryPrefix(windowed: isWindow,
      hasPaged: pagedHistoryGeneration != nil,
      sameGeneration: pagedHistoryGeneration == historyGeneration && client?.id == snapshot.hostId && selectedThreadId == snapshot.threadId,
      sameRevision: knownRevisionMatches)
    // Legacy V9 has no revision guarantee. Preserve only a positively anchored
    // prefix loaded in this reading generation, never treat nil == nil as a revision match.
    let incomingIds = Set(snapshot.messages.map(\.id))
    let preserveLegacyV9 = isWindow && snapshot.historyRevision == nil && historyWindowRevisions[key] == nil && snapshot.synchronization != nil
      && pagedHistoryGeneration == historyGeneration && client?.id == snapshot.hostId && selectedThreadId == snapshot.threadId
      && previous.contains { incomingIds.contains($0.id) }
    if isWindow {
      if historyWindowRevisions[key] != snapshot.historyRevision,
        client?.id == snapshot.hostId, selectedThreadId == snapshot.threadId {
        pagedHistoryGeneration = nil
        historyCursor = snapshot.nextCursor
      }
      historyWindowRevisions[key] = snapshot.historyRevision
    }
    let updatedMessages: [ChatMessage]
    if earlier {
      let existingIds = Set(previous.map(\.id))
      let replacements = Dictionary(snapshot.messages.map { ($0.id, $0) }, uniquingKeysWith: { _, newest in newest })
      let prefix = snapshot.messages.filter { !existingIds.contains($0.id) }
      beforePrepend?(prefix.count)
      updatedMessages = prefix
        + previous.map { replacements[$0.id] ?? $0 }
    } else if preserve || preserveLegacyV9 {
      let ids = Set(snapshot.messages.map(\.id))
      let boundary = previous.firstIndex { ids.contains($0.id) } ?? previous.endIndex
      updatedMessages = Array(previous[..<boundary]) + snapshot.messages
    } else { updatedMessages = snapshot.messages }
    // Freshness/runtime metadata may change without any message changing.
    // Do not invalidate the timeline or re-encrypt/rewrite an identical history page.
    if saved.history[key] == nil || updatedMessages != previous {
      saved.history[key] = updatedMessages
      dirtyHistoryKeys.insert(key)
    }
    if let host = saved.clients.first(where: { $0.id == snapshot.hostId }) { reconcileReading(host, updatedThreadId: snapshot.threadId) }
    if let runtime = snapshot.runtime {
      storeRuntime(runtime, key: key)
      if let index = saved.workspaces[snapshot.hostId]?.threads.firstIndex(where: {
        $0.id == snapshot.threadId
      }) {
        if saved.workspaces[snapshot.hostId]?.threads[index].state != runtime.state {
          saved.workspaces[snapshot.hostId]?.threads[index].state = runtime.state
        }
        if runtime.state != "unknown" {
          let count = runtime.approvals.filter {
            $0.threadId == snapshot.threadId && $0.interactionMode == "async"
          }.count
          if saved.workspaces[snapshot.hostId]?.threads[index].pendingAsyncQuestionCount != count {
            saved.workspaces[snapshot.hostId]?.threads[index].pendingAsyncQuestionCount = count
          }
        }
      }
      if let pending = saved.outbox?[key],
        let receipt = runtime.receipts.first(where: {
          $0.clientMessageId == pending.clientMessageId && $0.hostId == pending.hostId && $0.threadId == pending.threadId
        }),
        ["submitted", "queued", "completed", "interrupted", "failed", "cancelled"].contains(receipt.state)
      {
        if ["failed", "cancelled"].contains(receipt.state) { archiveReply(pending, key: key, receipt: receipt) }
        else { finishPending(pending, key: key) }
      }
      for receipt in runtime.receipts {
        if let index = saved.detachedReplies?[key]?.firstIndex(where: { $0.id == receipt.clientMessageId && $0.original.hostId == receipt.hostId && $0.original.threadId == receipt.threadId }) {
          updateDetachedReceipt(receipt, key: key, index: index)
        }
      }
    }
    scheduleSnapshotPersistence()
  }

  private func scheduleSnapshotPersistence() {
    if !persistScheduled {
      persistScheduled = true
      Task {
        // Stream/freshness snapshots are replaceable and already visible.
        // Bound checkpoints to two seconds even under a continuous stream;
        // command intents, drafts and background flushes keep their own fences.
        try? await Task.sleep(for: .seconds(2))
        persistScheduled = false
        persist()
      }
    }
  }

  func send(content override: Draft? = nil, expectedPlanId: String? = nil) {
    if client?.isDemo == true {
      sendDemo(content: override)
      return
    }
    let content = override ?? draft
    guard canSubmit(content), let host = client else { return }
    let sharingModel = isRunning && activeReplyMode == "steer" ? runtime?.generationSettings?.model
      : content.model ?? draft.model ?? runtime?.generationSettings?.model ?? selectedModel?.id
    guard requireAISharing(host, model: sharingModel) else { return }
    let sharingScope = aiSharingScope(host, model: sharingModel ?? runtime?.generationSettings?.model ?? selectedModel?.id)
    if selectedThreadId == nil && saved.workspaces[host.id]?.capabilities?.contains("threads:create:staged") == true {
      startDesktopCreation(host)
      return
    }
    let selectedProject = project
    let oldKey = targetKey
    let requestedMode = isRunning ? activeReplyMode : "next-turn"
    let expected = runtime?.turnId
    let existingId = selectedThreadId
    let generation = historyGeneration
    // A confirmed writable owner can accept the immutable reply directly. The
    // Host rechecks its actual owner before execution; opening and rereading it
    // here added two network round trips to every already connected send.
    let connectsDesktop = existingId != nil && canConnectForReply
      && !(loadedHistoryGeneration == historyGeneration && isWritable)
    error = nil
    let selectedModel = requestedMode != "steer" ? content.model ?? draft.model : nil
    let selectedEffort = requestedMode != "steer" ? content.effort ?? draft.effort : nil
    let selectedPermission = requestedMode != "steer" ? content.permissionMode ?? draft.permissionMode : nil
    let selectedSpeed = requestedMode != "steer" && supportsGenerationSpeed ? content.speed ?? draft.speed : nil
    let selectedMode = supportsPlanMode && requestedMode != "steer" ? (content.collaborationMode ?? selectedCollaborationMode) : nil
    let sendFlight = UUID()
    replySendFlights[oldKey] = sendFlight
    sendingKeys.insert(oldKey)
    Task {
      var key = oldKey
      var attempt: PendingReply?
      var clearedDraft: Draft?
      var mayHaveSubmitted = false
      defer {
        for target in Set([oldKey, key]) where replySendFlights[target] == sendFlight {
          replySendFlights.removeValue(forKey: target)
          sendingKeys.remove(target)
          transfers.removeValue(forKey: target)
        }
      }
      do {
        var threadId = existingId
        if threadId == nil {
          let operationId = drafts.values[oldKey]?.operationId ?? UUID().uuidString
          drafts.values[oldKey, default: Draft()].operationId = operationId
          try await persistBeforeCommand()
          try validateAISharing(host, scope: sharingScope)
          let thread: Conversation = try await AgentClients.resolve(host).createThread(host, projectId: selectedProject?.id, operationId: operationId)
          guard thread.hostId == host.id else { throw HostServiceError.wrongHost }
          threadId = thread.id
          if !(saved.workspaces[host.id]?.threads.contains(where: { $0.id == thread.id })
            ?? false)
          {
            saved.workspaces[host.id]?.threads.insert(thread, at: 0)
          }
          key = cacheKey(host, threadId: thread.id)
          drafts.values[key] = drafts.values[oldKey] ?? content
          drafts.values.removeValue(forKey: oldKey)
          if targetKey == oldKey {
            selectedThreadId = thread.id
            saved.lastThreads[host.id] = thread.id
            saved.newProjects.removeValue(forKey: host.id)
          }
          replySendFlights[key] = sendFlight
          sendingKeys.insert(key)
          persist()
        }
        guard let threadId else { throw HostServiceError.invalidResponse }
        if connectsDesktop {
          try await connectForReply(host, threadId: threadId, key: key, generation: generation)
        }
        var attachments: [RemoteAttachment] = []
        for (index, file) in content.attachments.enumerated() {
          if connectsDesktop { try requireReplyTarget(host, threadId: threadId, generation: generation) }
          try validateAISharing(host, scope: sharingScope)
          transfers[key] = L10n.tr("上传附件 \(index + 1)/\(content.attachments.count) · \(file.name)")
          attachments.append(
            try await AgentClients.resolve(host).upload(
              host, threadId: threadId, file: file,
              data: Data(contentsOf: attachmentURL(file))))
        }
        if connectsDesktop { try requireReplyTarget(host, threadId: threadId, generation: generation) }
        try validateAISharing(host, scope: sharingScope)
        let reply = PendingReply(
          hostId: host.id, threadId: threadId, clientMessageId: UUID().uuidString,
          text: content.text, attachmentIds: attachments.map(\.id), mode: requestedMode,
          expectedTurnId: expected,
          model: selectedModel, effort: selectedEffort, sourceAttachments: content.attachments)
        var configuredReply = reply
        configuredReply.collaborationMode = selectedMode
        configuredReply.expectedPlanId = expectedPlanId
        configuredReply.permissionMode = selectedPermission
        configuredReply.speed = selectedSpeed
        saved.outbox?[key] = configuredReply
        let capturedTarget = aiSharingScope(host, model: configuredReply.model).target
        if saved.replyRecovery == nil { saved.replyRecovery = [:] }
        saved.replyRecovery?[configuredReply.clientMessageId] = ReplyRecoveryRecord(original: configuredReply,
          target: capturedTarget, trust: replyRecoveryTrust(host),
          fingerprint: try ReplyRecoveryPolicy.fingerprint(configuredReply, target: capturedTarget), phase: "not-written",
          sharingScope: sharingScope)
        attempt = configuredReply
        if override == nil, let current = drafts.values[key], current.text == content.text,
          current.attachments == content.attachments
        {
          let emptyDraft = Draft(collaborationMode: selectedMode, model: content.model ?? selectedModel, effort: content.effort ?? selectedEffort, permissionMode: content.permissionMode ?? selectedPermission, speed: content.speed ?? selectedSpeed)
          drafts.values[key] = emptyDraft
          clearedDraft = emptyDraft
        }
        try await persistBeforeCommand()
        if connectsDesktop { try requireReplyTarget(host, threadId: threadId, generation: generation) }
        try validateAISharing(host, scope: sharingScope)
        if replySendFlights[key] == sendFlight { transfers[key] = L10n.tr("提交给 \(host.name)…") }
        // Persist a may-write disposition BEFORE the first transport call. A
        // restart, lost response, HTTP rejection or owner idle cannot undo it.
        saved.replyRecovery?[configuredReply.clientMessageId]?.phase = "uncertain"
        try await persistBeforeCommand()
        if connectsDesktop { try requireReplyTarget(host, threadId: threadId, generation: generation) }
        try validateAISharing(host, scope: sharingScope)
        mayHaveSubmitted = true
        let provider = try AgentClients.resolve(host)
        let receipt: DeliveryReceipt
        if host.codexDirect != nil || host.agentConnection != nil {
          receipt = try await provider.sendCapturedReply(host, reply: configuredReply, target: capturedTarget)
        } else { receipt = try await provider.send(host, reply: configuredReply) }
        try accept(receipt, reply: configuredReply, key: key)
        if !isDetachedReply(configuredReply, key: key), ["submitted", "completed"].contains(receipt.state), let clearedDraft,
          drafts.values[key] == clearedDraft {
          drafts.values[key, default: Draft()].collaborationMode = nil
          drafts.values[key, default: Draft()].model = nil
          drafts.values[key, default: Draft()].effort = nil
          drafts.values[key, default: Draft()].permissionMode = nil
          drafts.values[key, default: Draft()].speed = nil
          persist()
        }
        if !isDetachedReply(configuredReply, key: key), client?.id == host.id && selectedThreadId == threadId {
          // Acceptance already released the immutable outbox item. A slow
          // canonical refresh must not keep the next input's send lock held.
          Task { await loadHistory(threadId: threadId) }
        }
      } catch {
        let definitelyUnsubmitted = !mayHaveSubmitted
        if let attempt, definitelyUnsubmitted,
          saved.outbox?[key]?.clientMessageId == attempt.clientMessageId {
          // Only our local pre-transport boundary proves no send call happened.
          // Never infer absence from an HTTP code, empty history or owner state.
          saved.outbox?[key]?.knownRejected = true
          saved.replyRecovery?[attempt.clientMessageId]?.phase = "not-written"
          saved.replyRecovery?[attempt.clientMessageId]?.inspection = nil
          persist()
        }
        if let attempt, let index = saved.detachedReplies?[key]?.firstIndex(where: { $0.id == attempt.clientMessageId }) {
          saved.detachedReplies?[key]?[index].error = error.userFacingDescription
          persist()
        }
        if attempt.map({ samePending($0, key: key) }) ?? true,
          !isReadCancellation(error), client?.id == host.id && targetKey == key,
          !connectsDesktop || historyGeneration == generation { self.presentError(error) }
      }
    }
  }
  private func startDesktopCreation(_ host: Client) {
    let key = targetKey
    let content = draft
    do {
      let manifest = try content.attachments.map { file in
        HostService.creationManifest(file, data: try Data(contentsOf: attachmentURL(file)))
      }
      let pending = PendingDesktopCreation(hostId: host.id, clientOperationId: UUID().uuidString,
        projectId: project?.id, text: content.text, attachments: content.attachments, manifest: manifest, phase: "prepared",
        collaborationMode: supportsPlanMode ? selectedCollaborationMode : nil,
        model: draft.model, effort: draft.effort, permissionMode: draft.permissionMode, speed: supportsGenerationSpeed ? draft.speed : nil,
        stage: "saving", startedAt: Date(), updatedAt: Date(),
        sharingScope: aiSharingScope(host, model: content.model ?? runtime?.generationSettings?.model ?? selectedModel?.id),
        capturedTarget: aiSharingScope(host, model: content.model).target, trust: replyRecoveryTrust(host))
      if saved.desktopCreations == nil { saved.desktopCreations = [:] }
      saved.desktopCreations?[key] = pending
      persist()
      runDesktopCreation(host, key: key, pending: pending)
    } catch { self.presentError(error) }
  }
  private func runDesktopCreation(_ host: Client, key: String, pending: PendingDesktopCreation) {
    guard !sendingKeys.contains(key) else { return }
    let sharingScope = pending.sharingScope ?? aiSharingScope(host, model: pending.model)
    sendingKeys.insert(key)
    creatingOperations[key] = pending.clientOperationId
    Task {
      var mayHaveSubmitted = false
      defer {
        if creatingOperations[key] == pending.clientOperationId {
          creatingOperations.removeValue(forKey: key)
          sendingKeys.remove(key); transfers.removeValue(forKey: key)
        }
      }
      do {
        try await persistBeforeCommand()
        guard isCurrentCreation(key, pending) else { return }
        try validateAISharing(host, scope: sharingScope)
        updateCreation(key, pending) { $0.stage = "preparing"; $0.error = nil }
        let operation = try await AgentClients.resolve(host).prepareCreation(host, pending: pending)
        try validateCreation(operation, pending: pending)
        guard operation.phase == "prepared" else {
          try await acceptCreation(operation, host: host, key: key, pending: pending)
          startObservingCreation(host, key: key, pending: pending)
          return
        }
        for (index, file) in pending.attachments.enumerated() where !operation.uploaded.contains(file.id) {
          guard isCurrentCreation(key, pending) else { return }
          try validateAISharing(host, scope: sharingScope)
          updateCreation(key, pending) { $0.stage = "uploading" }
          transfers[key] = L10n.tr("上传首条附件 \(index + 1)/\(pending.attachments.count) · \(file.name)")
          let bytes = try Data(contentsOf: attachmentURL(file))
          guard pending.manifest.first(where: { $0.id == file.id })?.sha256 == HostService.hash(bytes) else {
            throw HostServiceError.message(L10n.tr("首条附件已变化，原创建内容已保留。"))
          }
          try await AgentClients.resolve(host).uploadCreation(host, id: pending.clientOperationId, file: file, data: bytes)
        }
        // Persist uncertainty before submitting. Restarting the app never sends again automatically.
        guard isCurrentCreation(key, pending) else { return }
        updateCreation(key, pending) { $0.phase = "outcome-unknown"; $0.stage = "submitting" }
        try await persistBeforeCommand()
        guard isCurrentCreation(key, pending) else { return }
        try validateAISharing(host, scope: sharingScope)
        mayHaveSubmitted = true
        transfers[key] = L10n.tr("正在 \(clientTypeName) 中创建聊天…")
        let result: DesktopCreationOperation = try await AgentClients.resolve(host).submitCreation(host, id: pending.clientOperationId)
        try await acceptCreation(result, host: host, key: key, pending: pending)
        startObservingCreation(host, key: key, pending: pending)
      } catch {
        let status: KotlinInt?
        if case HostServiceError.rejected(let code, _) = error { status = KotlinInt(int: Int32(code)) } else { status = nil }
        let phase = OperationPolicies.shared.creationFailurePhase(current: saved.desktopCreations?[key]?.phase ?? pending.phase,
          mayHaveSubmitted: mayHaveSubmitted, rejectionStatus: status)
        updateCreation(key, pending) { $0.phase = phase }
        recordCreationFailure(error, key: key, pending: pending, displayError: true)
        // A transport timeout does not prove rejection. Observe this same ID;
        // never re-prepare, upload or submit from an observation task.
        startObservingCreation(host, key: key, pending: pending)
      }
    }
  }
  private func isCurrentCreation(_ key: String, _ pending: PendingDesktopCreation) -> Bool {
    saved.desktopCreations?[key]?.clientOperationId == pending.clientOperationId
  }
  private func updateCreation(_ key: String, _ pending: PendingDesktopCreation,
    _ update: (inout PendingDesktopCreation) -> Void) {
    guard isCurrentCreation(key, pending), var current = saved.desktopCreations?[key] else { return }
    update(&current); current.updatedAt = Date()
    saved.desktopCreations?[key] = current
    persist()
  }
  private func recordCreationFailure(_ failure: Error, key: String, pending: PendingDesktopCreation,
    displayError: Bool = false) {
    guard !Task.isCancelled else { return }
    let text: String
    if case HostServiceError.rejected(let status, _) = failure, status == 404 {
      text = L10n.tr("电脑未找到此创建记录。原内容已保留，可以结束等待后继续编辑。")
    } else { text = failure.userFacingDescription }
    if isCurrentCreation(key, pending) {
      updateCreation(key, pending) { $0.error = text; $0.stage = "querying" }
      if displayError && targetKey == key { error = text }
    } else if let index = saved.detachedDesktopCreations?[key]?.firstIndex(where: { $0.clientOperationId == pending.clientOperationId }) {
      saved.detachedDesktopCreations?[key]?[index].error = text
      saved.detachedDesktopCreations?[key]?[index].updatedAt = Date()
      persist()
    }
  }
  private func startObservingCreation(_ host: Client, key: String, pending: PendingDesktopCreation, receiptOnly: Bool = false) {
    guard key == cacheKey(host, threadId: nil, projectId: pending.projectId),
      OperationPolicies.shared.observeCreation(phase: saved.desktopCreations?[key]?.phase ?? "rejected",
        currentOperation: saved.desktopCreations?[key]?.clientOperationId, expectedOperation: pending.clientOperationId),
      creationObservers[pending.clientOperationId] == nil else { return }
    creationObservers[pending.clientOperationId] = Task { [weak self] in
      guard let self else { return }
      defer { creationObservers.removeValue(forKey: pending.clientOperationId) }
      await followCreation(host, key: key, pending: pending, receiptOnly: receiptOnly)
    }
  }
  private func followCreation(_ host: Client, key: String, pending: PendingDesktopCreation, receiptOnly: Bool = false) async {
    let deadline = ProcessInfo.processInfo.systemUptime + 240
    var delay = 0.5
    while !Task.isCancelled && ProcessInfo.processInfo.systemUptime < deadline {
      guard isCurrentCreation(key, pending),
        saved.desktopCreations?[key]?.phase != "rejected" else { return }
      do {
        try await Task.sleep(for: .seconds(delay))
        let adapter = try AgentClients.resolve(host)
        let operation: DesktopCreationOperation
        if receiptOnly { operation = try await adapter.creationReceipt(host, id: pending.clientOperationId) }
        else { operation = try await adapter.creation(host, id: pending.clientOperationId) }
        try await acceptCreation(operation, host: host, key: key, pending: pending)
        delay = 1
      } catch {
        if Task.isCancelled { return }
        recordCreationFailure(error, key: key, pending: pending)
        if case HostServiceError.rejected(let status, _) = error, [400, 401, 403, 404, 405, 409, 413].contains(status) { return }
        if case HostServiceError.wrongHost = error { return }
        delay = min(max(delay * 2, 1), 10)
      }
    }
  }
  private func validateCreation(_ operation: DesktopCreationOperation, pending: PendingDesktopCreation) throws {
    let expected = CreationIdentity(hostId: pending.hostId, operationId: pending.clientOperationId, projectId: pending.projectId)
    let received = CreationIdentity(hostId: operation.hostId, operationId: operation.clientOperationId, projectId: operation.projectId)
    guard OperationPolicies.shared.sameCreation(expected: expected, received: received) else { throw HostServiceError.wrongHost }
  }
  private func acceptCreation(_ operation: DesktopCreationOperation, host: Client, key: String, pending: PendingDesktopCreation) async throws {
    try validateCreation(operation, pending: pending)
    let current = saved.desktopCreations?[key]?.clientOperationId == pending.clientOperationId
    if !current {
      if let index = saved.detachedDesktopCreations?[key]?.firstIndex(where: { $0.clientOperationId == pending.clientOperationId }) {
        saved.detachedDesktopCreations?[key]?[index].phase = operation.phase
        saved.detachedDesktopCreations?[key]?[index].error = operation.error
        if let thread = operation.thread, operation.phase == "confirmed", thread.hostId == host.id,
          thread.id == operation.threadId, thread.projectId == pending.projectId,
          !(saved.workspaces[host.id]?.threads.contains(where: { $0.id == thread.id }) ?? false) {
          saved.workspaces[host.id]?.threads.insert(thread, at: 0)
        }
        persist()
      }
      return
    }
    let unchanged = saved.desktopCreations?[key]?.phase == operation.phase
      && saved.desktopCreations?[key]?.error == operation.error && saved.desktopCreations?[key]?.stage == "querying"
      && saved.desktopCreations?[key]?.preparation == operation.preparation
    if !unchanged {
      saved.desktopCreations?[key]?.phase = operation.phase
      saved.desktopCreations?[key]?.error = operation.error
      saved.desktopCreations?[key]?.stage = "querying"
      saved.desktopCreations?[key]?.preparation = operation.preparation
    }
    if operation.detached == true {
      archiveCreationWait(key: key, pending: pending, operation: operation)
      return
    }
    guard operation.phase == "confirmed" else {
      if !unchanged { saved.desktopCreations?[key]?.updatedAt = Date(); persist() }
      return
    }
    guard let thread = operation.thread, thread.id == operation.threadId, thread.hostId == host.id,
      thread.projectId == pending.projectId else { throw HostServiceError.wrongHost }
    if !(saved.workspaces[host.id]?.threads.contains(where: { $0.id == thread.id }) ?? false) {
      saved.workspaces[host.id]?.threads.insert(thread, at: 0)
    }
    if let current = drafts.values[key], current.text == pending.text, current.attachments == pending.attachments {
      drafts.values.removeValue(forKey: key)
    }
    saved.desktopCreations?.removeValue(forKey: key)
    if targetKey == key {
      historyGeneration = UUID()
      selectedThreadId = thread.id
      saved.lastThreads[host.id] = thread.id
      saved.newProjects.removeValue(forKey: host.id)
      await loadHistory(threadId: thread.id)
      startEvents()
    }
    persist()
  }
  func queryDesktopCreation() {
    guard let host = client, let pending = pendingDesktopCreation else { return }
    let key = targetKey
    Task {
      do {
        try await acceptCreation(try await AgentClients.resolve(host).creation(host, id: pending.clientOperationId), host: host, key: key, pending: pending)
        startObservingCreation(host, key: key, pending: pending)
      }
      catch {
        recordCreationFailure(error, key: key, pending: pending, displayError: true)
        if case HostServiceError.rejected(let status, _) = error, status == 404 { return }
        startObservingCreation(host, key: key, pending: pending)
      }
    }
  }
  func resumeDesktopCreation() {
    guard let host = client, let pending = pendingDesktopCreation, canResumeDesktopCreation else { return }
    let sharing = pending.sharingScope ?? aiSharingScope(host, model: pending.model)
    guard requireAISharing(host, scope: sharing) else { return }
    if pending.phase == "prepared" { runDesktopCreation(host, key: targetKey, pending: pending); return }
    let key = targetKey, epoch = historyGeneration
    guard !sendingKeys.contains(key) else { return }
    sendingKeys.insert(key)
    Task {
      defer { sendingKeys.remove(key) }
      do {
        let provider = try AgentClients.resolve(host)
        // A fresh read-only receipt precedes this explicit same-ID submit.
        let fresh = try await provider.creationReceipt(host, id: pending.clientOperationId)
        try validateCreation(fresh, pending: pending)
        guard targetKey == key, historyGeneration == epoch, isCurrentCreation(key, pending),
          creationRecoveryTargetMatches(host, pending: pending), fresh.detached != true, fresh.phase == "outcome-unknown",
          mayContinueCreation(fresh.phase, preparation: fresh.preparation) else { return }
        try validateAISharing(host, scope: sharing)
        updateCreation(key, pending) { $0.stage = "submitting"; $0.preparation = nil }
        try await persistBeforeCommand()
        guard targetKey == key, historyGeneration == epoch, isCurrentCreation(key, pending),
          creationRecoveryTargetMatches(client ?? host, pending: pending) else { return }
        try validateAISharing(host, scope: sharing)
        let result = try await provider.submitCreation(host, id: pending.clientOperationId)
        try await acceptCreation(result, host: host, key: key, pending: pending)
        startObservingCreation(host, key: key, pending: pending)
      } catch {
        recordCreationFailure(error, key: key, pending: pending, displayError: true)
        startObservingCreation(host, key: key, pending: pending)
      }
    }
  }
  private func mayContinueCreation(_ phase: String, preparation: DesktopCreationPreparation?) -> Bool {
    ReplyRecoveryRules.shared.mayContinueCreation(phase: phase, submissionAttempted: phase != "prepared",
      diagnosticDisposition: preparation?.diagnostic?.disposition, preparationPhase: preparation?.preparationPhase,
      preparedThreadId: preparation?.preparedThreadId)
  }
  private func creationRecoveryTargetMatches(_ host: Client, pending: PendingDesktopCreation) -> Bool {
    guard let target = pending.capturedTarget, let trust = pending.trust else { return false }
    return host.id == pending.hostId && target.matchesCapturedPayload(aiSharingScope(host, model: pending.model).target)
      && trust == replyRecoveryTrust(host)
  }
  var canResumeDesktopCreation: Bool {
    guard let host = client, let pending = pendingDesktopCreation,
      !sendingKeys.contains(targetKey), codexStorageConflictMessage == nil else { return false }
    if pending.phase == "prepared" { return true }
    return creationRecoveryTargetMatches(host, pending: pending) && mayContinueCreation(pending.phase, preparation: pending.preparation)
  }
  func restoreRejectedCreation() {
    guard let pending = pendingDesktopCreation, pending.phase == "rejected" else { return }
    saved.desktopCreations?.removeValue(forKey: targetKey)
    persist()
  }
  private func archiveCreationWait(key: String, pending: PendingDesktopCreation, operation: DesktopCreationOperation? = nil) {
    guard isCurrentCreation(key, pending), var receipt = saved.desktopCreations?[key] else { return }
    if let operation { receipt.phase = operation.phase; receipt.error = operation.error }
    if saved.detachedDesktopCreations == nil { saved.detachedDesktopCreations = [:] }
    if !(saved.detachedDesktopCreations?[key]?.contains { $0.clientOperationId == pending.clientOperationId } ?? false) {
      saved.detachedDesktopCreations?[key, default: []].append(receipt)
    }
    saved.desktopCreations?.removeValue(forKey: key)
    creationObservers[pending.clientOperationId]?.cancel()
    if creatingOperations[key] == pending.clientOperationId {
      creatingOperations.removeValue(forKey: key)
      sendingKeys.remove(key); transfers.removeValue(forKey: key)
    }
    persist()
  }
  func detachDesktopCreation(target: String, operationId: String) {
    guard let pending = saved.desktopCreations?[target], pending.clientOperationId == operationId,
      let host = saved.clients.first(where: { $0.id == pending.hostId }), !detachingCreationKeys.contains(target) else { return }
    detachingCreationKeys.insert(target)
    Task {
      defer { detachingCreationKeys.remove(target) }
      do {
        // Local waiting is independent of Host availability. Preserve the same
        // immutable receipt and attachments before notifying the Host.
        archiveCreationWait(key: target, pending: pending)
        try await persistBeforeCommand()
        let result = try await AgentClients.resolve(host).detachCreation(host, id: operationId)
        try validateCreation(result, pending: pending)
        try await acceptCreation(result, host: host, key: target, pending: pending)
      } catch { recordCreationFailure(error, key: target, pending: pending) }
    }
  }
  func queryDetachedCreation(_ pending: PendingDesktopCreation, target: String) {
    guard let host = saved.clients.first(where: { $0.id == pending.hostId }) else { return }
    Task {
      do {
        try await acceptCreation(try await AgentClients.resolve(host).creation(host, id: pending.clientOperationId), host: host, key: target, pending: pending)
      } catch {
        recordCreationFailure(error, key: target, pending: pending)
        if targetKey == target { self.presentError(error) }
      }
    }
  }
  private func accept(_ receipt: DeliveryReceipt, reply: PendingReply, key: String, reportUnknown: Bool = true) throws {
    guard receipt.hostId == reply.hostId, receipt.threadId == reply.threadId,
      receipt.clientMessageId == reply.clientMessageId
    else { throw HostServiceError.wrongHost }
    if let index = saved.detachedReplies?[key]?.firstIndex(where: { $0.id == reply.clientMessageId }) {
      updateDetachedReceipt(receipt, key: key, index: index)
    } else if ["failed", "cancelled"].contains(receipt.state), samePending(reply, key: key) {
      archiveReply(reply, key: key, receipt: receipt)
    } else if ReplyRecoveryRules.shared.acceptedReceipt(state: receipt.state, source: receipt.queueSource, present: receipt.queuePresent.map { KotlinBoolean(bool: $0) }, disposition: receipt.queueDisposition) {
      finishPending(reply, key: key)
    } else if reportUnknown, targetKey == key && samePending(reply, key: key) {
      error = receipt.error ?? L10n.tr("发送结果：\(receipt.state)，请查询发送状态。")
    }
    persist()
  }
  private func finishPending(_ reply: PendingReply, key: String) {
    guard samePending(reply, key: key) else { return }
    saved.outbox?.removeValue(forKey: key)
    releaseReplyWait(key: key)
    // Never alter the composer here: it may already contain the user's next message.
  }
  private func releaseReplyWait(key: String) {
    let flight = replySendFlights[key]
    let keys = flight.map { id in replySendFlights.filter { $0.value == id }.map(\.key) } ?? [key]
    for target in keys {
      replySendFlights.removeValue(forKey: target)
      sendingKeys.remove(target)
      transfers.removeValue(forKey: target)
    }
    replyRecoveryFlights.removeValue(forKey: key)
    replyRecoveryQueries.removeValue(forKey: key)
  }
  private func isDetachedReply(_ reply: PendingReply, key: String) -> Bool {
    saved.detachedReplies?[key]?.contains(where: { $0.id == reply.clientMessageId }) == true
  }
  private func updateDetachedReceipt(_ receipt: DeliveryReceipt, key: String, index: Int) {
    if let previous = saved.detachedReplies?[key]?[index].receipt,
      ReplyRecoveryRules.shared.preservesReceiptEvidence(previous: previous.state, source: previous.queueSource, present: previous.queuePresent.map { KotlinBoolean(bool: $0) }, disposition: previous.queueDisposition, incoming: receipt.state) { return }
    saved.detachedReplies?[key]?[index].receipt = receipt
    saved.detachedReplies?[key]?[index].error = receipt.error
  }
  private func archiveReply(_ reply: PendingReply, key: String, receipt: DeliveryReceipt? = nil) {
    guard samePending(reply, key: key) else { return }
    if saved.detachedReplies == nil { saved.detachedReplies = [:] }
    if !isDetachedReply(reply, key: key) {
      saved.detachedReplies?[key, default: []].append(DetachedReplyRecord(original: reply, receipt: receipt))
    }
    saved.outbox?.removeValue(forKey: key)
    releaseReplyWait(key: key)
    // Archiving never submits, cancels, or rewrites the original operation.
    // A late callback owns only this record, not the next draft or send lock.
  }
  func queryDetachedReply(_ record: DetachedReplyRecord, target: String) {
    let reply = record.original
    guard targetKey == target, client?.id == reply.hostId, selectedThreadId == reply.threadId,
      let host = client, codexStorageConflictMessage == nil, isDetachedReply(reply, key: target),
      replyRecoveryFlights[record.id] == nil else { return }
    let epoch = historyGeneration, trust = replyRecoveryTrust(host), flight = UUID()
    replyRecoveryFlights[record.id] = flight
    Task {
      defer { if replyRecoveryFlights[record.id] == flight { replyRecoveryFlights.removeValue(forKey: record.id) } }
      @MainActor func stillCurrent() -> Bool {
        targetKey == target && historyGeneration == epoch && selectedThreadId == reply.threadId
          && client?.id == host.id && replyRecoveryTrust(client ?? host) == trust
          && aiSharingScope(client ?? host, model: reply.model).target == aiSharingScope(host, model: reply.model).target
          && codexStorageConflictMessage == nil && isDetachedReply(reply, key: target)
      }
      do {
        let receipt = try await AgentClients.resolve(host).receipt(host, id: reply.clientMessageId)
        guard stillCurrent() else { return }
        try accept(receipt, reply: reply, key: target)
      } catch {
        guard stillCurrent(), let index = saved.detachedReplies?[target]?.firstIndex(where: { $0.id == record.id }) else { return }
        saved.detachedReplies?[target]?[index].error = error.userFacingDescription
        persist()
      }
    }
  }
  private func replyRecoveryTrust(_ host: Client) -> String {
    if let direct = host.codexDirect { return "codex:" + direct.fingerprint.lowercased() }
    // Preserve provider credentialRef and all approved connection metadata.
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    return (try? encoder.encode(host.agentConnection)).map { $0.base64EncodedString() } ?? "unsupported"
  }
  private func samePending(_ reply: PendingReply, key: String) -> Bool {
    guard let current = saved.outbox?[key] else { return false }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    // knownRejected is local presentation metadata, never immutable wire input.
    var old = reply, new = current; old.knownRejected = nil; new.knownRejected = nil
    return (try? encoder.encode(old)) == (try? encoder.encode(new))
  }
  private func currentReplyRecovery(_ host: Client, reply: PendingReply, key: String, epoch: UUID) -> Bool {
    targetKey == key && historyGeneration == epoch && selectedThreadId == reply.threadId
      && client?.id == host.id && samePending(reply, key: key)
      && aiSharingScope(client ?? host, model: reply.model).target == aiSharingScope(host, model: reply.model).target
      && replyRecoveryTrust(client ?? host) == replyRecoveryTrust(host)
      && codexStorageConflictMessage == nil
  }
  private func replyQueryRecord(_ host: Client, reply: PendingReply) -> ReplyRecoveryRecord? {
    guard let record = saved.replyRecovery?[reply.clientMessageId], record.trust == replyRecoveryTrust(host),
      record.target == aiSharingScope(host, model: reply.model).target,
      ReplyRecoveryPolicy.sameIntent(reply, record: record) else { return nil }
    return record
  }
  private func recoveryRecord(_ host: Client, reply: PendingReply) -> ReplyRecoveryRecord? {
    guard let record = replyQueryRecord(host, reply: reply),
      record.target.agentId != "codex" || host.codexDirect != nil else { return nil }
    return record
  }
  var canRetryPending: Bool {
    guard let host = client, let pending, host.online, codexStorageConflictMessage == nil,
      !sendingKeys.contains(targetKey), replyRecoveryFlights[targetKey] == nil else { return false }
    return ReplyRecoveryPolicy.mayRetry(pending, record: recoveryRecord(host, reply: pending))
  }
  var pendingWaitDetached: Bool { pending.map { saved.detachedReplyWaits?.contains($0.clientMessageId) == true } ?? false }
  func detachPendingWait(_ reply: PendingReply, target: String) {
    guard targetKey == target, samePending(reply, key: target) else { return }
    archiveReply(reply, key: target)
    persist()
  }
  func reconcilePending(automatically: Bool = false) {
    guard let host = client, let pending, codexStorageConflictMessage == nil, replyRecoveryFlights[targetKey] == nil else { return }
    let key = targetKey, epoch = historyGeneration, flight = UUID()
    if automatically {
      guard host.online, replyQueryRecord(host, reply: pending)?.phase != "not-written" else { return }
      let now = ProcessInfo.processInfo.systemUptime
      let previous = replyRecoveryQueries[key].flatMap { $0.id == pending.clientMessageId ? $0 : nil }
      guard now >= (previous?.next ?? 0) else { return }
      let delay = Double(ReplyRecoveryRules.shared.nextReplyQueryDelayMillis(previousMillis: Int64((previous?.delay ?? 0) * 1000))) / 1000
      replyRecoveryQueries[key] = (pending.clientMessageId, now + delay, delay)
    }
    replyRecoveryFlights[key] = flight
    Task {
      defer { if replyRecoveryFlights[key] == flight { replyRecoveryFlights.removeValue(forKey: key) } }
      do {
        let provider = try AgentClients.resolve(host)
        do {
          let receipt = try await provider.receipt(host, id: pending.clientMessageId)
          guard currentReplyRecovery(host, reply: pending, key: key, epoch: epoch) else { return }
          try accept(receipt, reply: pending, key: key, reportUnknown: !automatically)
        } catch {
          guard currentReplyRecovery(host, reply: pending, key: key, epoch: epoch) else { return }
          // A missing receipt is unknown. Only Codex's exact canonical GET can
          // supply its fenced negative proof; other adapters remain receipt-only.
          guard let record = replyQueryRecord(host, reply: pending), record.target.agentId == "codex" else { throw error }
          var queryHost = host
          queryHost.readingTarget = record.target
          let value = try await provider.replyDeliveryStatus(queryHost, reply: pending, target: record.target)
          guard currentReplyRecovery(host, reply: pending, key: key, epoch: epoch) else { return }
          if host.codexDirect != nil, ReplyRecoveryPolicy.strongNotAccepted(value, record: record) {
            saved.replyRecovery?[pending.clientMessageId]?.phase = "strong-not-accepted"
            saved.replyRecovery?[pending.clientMessageId]?.inspection = value
            try await persistBeforeCommand()
            guard currentReplyRecovery(host, reply: pending, key: key, epoch: epoch) else { return }
            if !automatically { self.error = L10n.tr("电脑已明确原输入未接受；可显式重试同一原消息。") }
          } else if value.version == 2, value.status == "accepted",
            value.hostId == pending.hostId, value.threadId == pending.threadId,
            value.clientMessageId == pending.clientMessageId, value.target == record.target,
            let receipt = value.receipt {
            try accept(receipt, reply: pending, key: key, reportUnknown: !automatically)
          } else if !automatically {
            self.error = L10n.tr("原消息结果仍待核对，仅查询原操作 ID；没有重新提交。")
          }
        }
        // Only the receipt/status GET owns this flight. A manual history pull
        // must not prevent the independent task from querying the same ID.
        if replyRecoveryFlights[key] == flight { replyRecoveryFlights.removeValue(forKey: key) }
        if !automatically, targetKey == key && historyGeneration == epoch && !pendingWaitDetached { await loadHistory(threadId: pending.threadId) }
      } catch { if !automatically, currentReplyRecovery(host, reply: pending, key: key, epoch: epoch) { self.presentError(error) } }
    }
  }
  func retryPending() {
    guard let host = client, let pending else { return }
    guard canRetryPending, let record = recoveryRecord(host, reply: pending) else {
      error = L10n.tr("原消息结果待核对，不能重新提交；请查询原回执。")
      reconcilePending(); return
    }
    let sharing = record.sharingScope ?? aiSharingScope(host, model: pending.model)
    guard requireAISharing(host, scope: sharing) else { return }
    let key = targetKey, epoch = historyGeneration, flight = UUID()
    replyRecoveryFlights[key] = flight
    Task {
      defer { if replyRecoveryFlights[key] == flight { replyRecoveryFlights.removeValue(forKey: key) } }
      do {
        let provider = try AgentClients.resolve(host)
        var freshAuthority = record.inspection
        if record.phase == "strong-not-accepted" {
          let fresh = try await provider.replyDeliveryStatus(host, reply: pending, target: record.target)
          guard currentReplyRecovery(host, reply: pending, key: key, epoch: epoch),
            ReplyRecoveryPolicy.strongNotAccepted(fresh, record: record) else { return }
          freshAuthority = fresh
          saved.replyRecovery?[pending.clientMessageId]?.inspection = fresh
        }
        guard currentReplyRecovery(host, reply: pending, key: key, epoch: epoch),
          ReplyRecoveryPolicy.mayRetry(pending, record: recoveryRecord(host, reply: pending)) else { return }
        try validateAISharing(host, scope: sharing)
        // Consume local retry authority durably before POST. A failed/lost send
        // returns to query-only even after a process restart or double tap.
        saved.replyRecovery?[pending.clientMessageId]?.phase = "uncertain"
        saved.replyRecovery?[pending.clientMessageId]?.inspection = nil
        saved.replyRecovery?[pending.clientMessageId]?.retryConsumed = true
        try await persistBeforeCommand()
        guard currentReplyRecovery(host, reply: pending, key: key, epoch: epoch) else { return }
        if record.phase == "strong-not-accepted" {
          guard let freshAuthority, ReplyRecoveryPolicy.strongNotAccepted(freshAuthority, record: record) else { return }
        }
        try validateAISharing(host, scope: sharing)
        let receipt = try await provider.sendCapturedReply(host, reply: pending, target: record.target)
        if isDetachedReply(pending, key: key) {
          try accept(receipt, reply: pending, key: key)
          return
        }
        guard currentReplyRecovery(host, reply: pending, key: key, epoch: epoch) else { return }
        try accept(receipt, reply: pending, key: key)
        if !pendingWaitDetached { await loadHistory(threadId: pending.threadId) }
      } catch { if currentReplyRecovery(host, reply: pending, key: key, epoch: epoch) { self.presentError(error) } }
    }
  }
  func editFailedPending() {
    guard let host = client, let pending, canRetryPending else {
      error = L10n.tr("原输入未提交尚未得到确认，原消息和当前草稿均已保留。"); return
    }
    let key = targetKey
    guard recoveryRecord(host, reply: pending) != nil else { return }
    let current = drafts.values[key] ?? Draft()
    guard current.text.isEmpty && current.attachments.isEmpty else {
      error = L10n.tr("请先处理当前草稿，再恢复原消息。当前草稿已保留。"); return
    }
    drafts.values[key] = Draft(text: pending.text, attachments: pending.sourceAttachments ?? [], collaborationMode: pending.collaborationMode, model: pending.model, effort: pending.effort, permissionMode: pending.permissionMode, speed: pending.speed)
    saved.outbox?.removeValue(forKey: key)
    // replyRecovery keeps the original operation/body/target and disposition.
    persist()
  }
  func stop() {
    guard canStop, let host = client, let threadId = selectedThreadId, let turnId = runtime?.turnId
    else { return }
    let key = targetKey
    if host.isDemo {
      demoTasks.removeValue(forKey: key)?.cancel()
      saved.runtimes?[key]?.state = "idle"
      saved.runtimes?[key]?.turnId = nil
      saved.history[key, default: []].append(ChatMessage(id: UUID().uuidString, role: "assistant", text: L10n.tr("本地示例任务已停止。"), status: "interrupted"))
      dirtyHistoryKeys.insert(key)
      persist()
      return
    }
    stoppingKeys.insert(key)
    Task {
      defer { stoppingKeys.remove(key) }
      do {
        let result: CommandResult = try await AgentClients.resolve(host).stop(host, threadId: threadId, turnId: turnId)
        guard result.ok else { throw HostServiceError.message(L10n.tr("停止未确认，请刷新任务状态。")) }
        if targetKey == key { await loadHistory(threadId: threadId) }
      } catch { if targetKey == key { self.presentError(error) } }
    }
  }
  func queueActionPending(_ receipt: DeliveryReceipt) -> Bool { queueActionOwners[targetKey + ":" + receipt.id] != nil }
  var supportsQueueExecution: Bool { saved.workspaces[client?.id ?? ""]?.capabilities?.contains("queue:execute") == true }
  private func currentQueueReceipt(_ receipt: DeliveryReceipt) -> DeliveryReceipt? {
    guard receipt.hostId == client?.id, receipt.threadId == selectedThreadId, !receipt.clientMessageId.isEmpty,
      QueueRules.shared.active(state: receipt.state, source: receipt.queueSource, present: receipt.queuePresent.map { KotlinBoolean(bool: $0) }, disposition: receipt.queueDisposition) else { return nil }
    return queuedReplies.first { $0.id == receipt.id }
  }
  private func queueActionAllowed(_ receipt: DeliveryReceipt, target: String, execute: Bool, turn: String?, pending: Bool) -> Bool {
    guard target == targetKey, loadedHistoryGeneration == historyGeneration,
      !execute || runtime?.turnId == turn, let current = currentQueueReceipt(receipt) else { return false }
    return QueueRules.shared.actionAllowed(state: current.state, source: current.queueSource, present: current.queuePresent.map { KotlinBoolean(bool: $0) }, disposition: current.queueDisposition,
      online: client?.online == true, writable: isWritable, pending: pending, execute: execute,
      supportsExecute: supportsQueueExecution, canExecuteNow: current.canExecuteNow.map { KotlinBoolean(bool: $0) })
  }
  func canExecuteQueue(_ receipt: DeliveryReceipt, expectedTargetKey: String, expectedTurnId: String?) -> Bool {
    queueActionAllowed(receipt, target: expectedTargetKey, execute: true, turn: expectedTurnId, pending: queueActionPending(receipt))
  }
  func canCancelQueue(_ receipt: DeliveryReceipt, expectedTargetKey: String) -> Bool {
    queueActionAllowed(receipt, target: expectedTargetKey, execute: false, turn: nil, pending: queueActionPending(receipt))
  }
  func executeQueue(_ receipt: DeliveryReceipt, expectedTargetKey: String, expectedTurnId: String?) { queueAction(receipt, target: expectedTargetKey, execute: true, turn: expectedTurnId) }
  func cancelQueue(_ receipt: DeliveryReceipt, expectedTargetKey: String) { queueAction(receipt, target: expectedTargetKey, execute: false, turn: nil) }
  #if DEBUG
  func enqueueDEVQueueBatch() {
    let arguments = ProcessInfo.processInfo.arguments
    guard arguments.contains("-native-queue-dev"), arguments.contains("-ui-testing"),
      let nonce = ProcessInfo.processInfo.environment["EASYWORK_NATIVE_QUEUE_NONCE"],
      nonce.range(of: "^[a-f0-9]{8}$", options: .regularExpression) != nil,
      let preference = client?.agentConnection?.preference,
      preference == .lanOnly || preference == .relayOnly,
      let turnId = runtime?.turnId, isRunning, !devQueueBatchRunning else { return }
    let target = targetKey, generation = historyGeneration
    guard devQueueBatches.insert(target + ":" + nonce).inserted else { return }
    let marker = "EASYWORK_NATIVE_QUEUE_" + (preference == .lanOnly ? "lan" : "relay") + "_" + nonce
    devQueueBatchRunning = true
    Task {
      defer { devQueueBatchRunning = false }
      for suffix in ["FIFO", "PROMOTE", "CANCEL"] {
        let text = "本条执行时只原样回复 " + marker + "_" + suffix + "，不调用工具。"
        let content = Draft(text: text)
        guard targetKey == target, historyGeneration == generation, isRunning,
          runtime?.turnId == turnId, canSubmit(content) else {
          devQueueActionTrace = "batch-target-unavailable"; return
        }
        replyMode = "queue"
        // Use the normal immutable outbox, persistence and AgentClient path.
        // The DEV button only avoids keyboard automation consuming the Run.
        send(content: content)
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        while sendingKeys.contains(target) || saved.outbox?[target] != nil {
          guard ContinuousClock.now < deadline, targetKey == target else {
            devQueueActionTrace = "batch-awaiting-receipt"; return
          }
          do { try await Task.sleep(for: .milliseconds(50)) } catch { return }
        }
      }
      devQueueActionTrace = "batch-complete"
    }
  }
  #endif
  private func queueAction(_ receipt: DeliveryReceipt, target: String, execute: Bool, turn: String?) {
    #if DEBUG
    devQueueActionTrace = "entered:host=\(receipt.hostId == client?.id):thread=\(receipt.threadId == selectedThreadId)"
    #endif
    guard let host = client, queueActionAllowed(receipt, target: target, execute: execute, turn: turn, pending: queueActionPending(receipt)) else { return }
    let sharing = execute ? aiSharingScope(host, model: runtime?.generationSettings?.model ?? selectedModel?.id) : nil
    if let sharing, !requireAISharing(host, scope: sharing) {
      #if DEBUG
      devQueueActionTrace = "sharing"
      #endif
      return
    }
    let pending = target + ":" + receipt.id, owner = UUID()
    let connection = clientGeneration, navigation = historyGeneration, errorAtStart = errorRevision
    let boundHost = readingClient(host)
    queueActionOwners[pending] = owner
    #if DEBUG
    devQueueActionTrace = "dispatching"
    #endif
    func current() -> Bool {
      targetKey == target && clientGeneration == connection && historyGeneration == navigation
        && client?.endpoint == host.endpoint && client?.codexDirect?.fingerprint == host.codexDirect?.fingerprint
    }
    Task {
      defer { if queueActionOwners[pending] == owner { queueActionOwners.removeValue(forKey: pending) } }
      do {
        guard current(), queueActionOwners[pending] == owner,
          queueActionAllowed(receipt, target: target, execute: execute, turn: turn, pending: false) else { return }
        if let sharing { try validateAISharing(host, scope: sharing) }
        let agent = try AgentClients.resolve(boundHost)
        let result = try await (execute
          ? agent.executeQueue(boundHost, threadId: receipt.threadId, messageId: receipt.clientMessageId, expectedTurnId: turn)
          : agent.cancelQueue(boundHost, threadId: receipt.threadId, messageId: receipt.clientMessageId))
        guard result.ok else { throw HostServiceError.message(L10n.tr("队列操作结果待核对，请刷新原任务")) }
        if current() { await loadHistory(threadId: receipt.threadId) }
      } catch {
        guard current() else { return }
        if errorRevision == errorAtStart { self.presentError(error) }
        await loadHistory(threadId: receipt.threadId)
      }
    }
  }
  // BEGIN: approval-callback-boundary
  struct ApprovalActionContext: Equatable {
    let target: String
    let navigation: UUID
    let connection: UUID
  }
  private struct ApprovalAnswerFlight {
    let target: String
    let approval: Approval
  }
  var approvalActionContext: ApprovalActionContext {
    ApprovalActionContext(target: targetKey, navigation: historyGeneration, connection: clientGeneration)
  }
  private func knowsApproval(_ approval: Approval, context: ApprovalActionContext) -> Bool {
    context == approvalActionContext && approval.threadId == selectedThreadId
      && runtime?.approvals.contains(approval) == true
  }
  func isAnswering(_ approval: Approval) -> Bool {
    answeringApprovals.values.contains {
      $0.target == targetKey && $0.approval.threadId == approval.threadId
        && $0.approval.turnId == approval.turnId && $0.approval.id == approval.id
    }
  }
  func canEditApproval(_ approval: Approval, context: ApprovalActionContext) -> Bool {
    knowsApproval(approval, context: context) && !isAnswering(approval)
  }
  func canAnswer(_ approval: Approval, context: ApprovalActionContext) -> Bool {
    canEditApproval(approval, context: context) && loadedHistoryGeneration == historyGeneration
      && client?.online == true && isWritable && SessionPolicies.shared.supports(access: accessPolicy, capability: "approvals")
  }
  func answer(_ approval: Approval, decision: String, answers: [String: [String]] = [:], expectedTargetKey: String? = nil,
    expectedContext: ApprovalActionContext? = nil
  ) {
    let context = expectedContext ?? approvalActionContext
    guard let host = client, expectedTargetKey == nil || expectedTargetKey == targetKey,
      canAnswer(approval, context: context) else { return }
    if approval.kind == "question" {
      if decision == "skip" {
        guard approval.interactionMode == "async" else { return }
      } else {
        guard decision.isEmpty, Set(answers.keys) == Set(approval.questions.map(\.id)),
          answers.values.allSatisfy({ !$0.isEmpty && $0.allSatisfy { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } }) else { return }
      }
    } else if !approval.decisions.contains(decision) { return }
    let sharing = AISharingRules.shared.requiresContentGrant(decision: decision)
      ? aiSharingScope(host, model: runtime?.generationSettings?.model) : nil
    if let sharing, !requireAISharing(host, scope: sharing) { return }
    let owner = UUID(), errorAtStart = errorRevision, boundHost = readingClient(host)
    let capturedAnswers = answers
    answeringApprovals[owner] = ApprovalAnswerFlight(target: context.target, approval: approval)
    func current() -> Bool {
      knowsApproval(approval, context: context) && answeringApprovals[owner] != nil
        && client?.endpoint == host.endpoint && client?.codexDirect?.fingerprint == host.codexDirect?.fingerprint
        && client.map { mobileReadingTarget($0) } == mobileReadingTarget(boundHost)
        && UIApplication.shared.applicationState == .active
    }
    Task {
      // Each invocation owns its own wait; an older callback cannot release a replacement request.
      defer { answeringApprovals.removeValue(forKey: owner) }
      do {
        guard current() else { return }
        if let sharing { try validateAISharing(host, scope: sharing) }
        let result = try await AgentClients.resolve(boundHost).approve(boundHost, approval: approval, decision: decision, answers: capturedAnswers)
        guard result.ok else { throw HostServiceError.message(L10n.tr("此请求已被处理，请刷新审批状态。")) }
        if current() { await loadHistory(threadId: approval.threadId) }
      } catch {
        if current() && errorRevision == errorAtStart { self.presentError(error) }
      }
    }
  }
  // END: approval-callback-boundary
  var workspaceManagementTarget: AgentRuntimeTarget? { client.map { mobileReadingTarget($0) } }
  var workspaceManagementOperations: [WorkspaceManagementOperation] {
    guard let host = client else { return [] }
    let target = mobileReadingTarget(host)
    return (saved.workspaceManagement ?? []).filter { $0.hostId == host.id && $0.target == target }.reversed()
  }
  func canManageWorkspace(_ action: WorkspaceManagementAction) -> Bool {
    client?.isDemo == true || saved.workspaces[client?.id ?? ""]?.capabilities?.contains(action.capability) == true
  }
  private func workspaceArchivePendingIds(excluding operationId: String? = nil) -> Set<String> {
    Set(workspaceManagementOperations.filter { $0.action == .archiveThreads && $0.id != operationId }.flatMap {
      $0.items.filter { SharedMobileRules.workspaceArchivePending($0.state) }.map(\.id)
    })
  }
  func canArchive(_ thread: Conversation) -> Bool { canArchiveExcluding(thread, operationId: nil) }
  private func canArchiveExcluding(_ thread: Conversation, operationId: String?) -> Bool {
    guard let host = client else { return false }
    return SharedMobileRules.workspaceArchiveEligible(
      knownTarget: thread.hostId == host.id && threads.contains { $0.id == thread.id && $0.hostId == thread.hostId },
      archived: thread.archived == true, singleBusy: chatActionBusy[cacheKey(host, threadId: thread.id)] != nil,
      singlePending: !pendingChatActions(thread.id).isEmpty, batchPending: workspaceArchivePendingIds(excluding: operationId).contains(thread.id))
  }
  func manageWorkspace(_ action: WorkspaceManagementAction, ids: [String], labels: [String: String]) async throws {
    guard !managementBusy, let host = client, host.online, canManageWorkspace(action), !ids.isEmpty, ids.count <= 100 else { throw HostServiceError.unavailable }
    let target = mobileReadingTarget(host)
    guard !workspaceManagementOperations.contains(where: { operation in
      operation.action == action && operation.items.contains { ids.contains($0.id) && ["pending", "submitting", "outcome-unknown"].contains($0.state) }
    }) else { throw HostServiceError.message(L10n.tr("所选内容还有待核对的操作，请先核对结果。")) }
    if action == .archiveThreads {
      guard ids.count == Set(ids).count, ids.allSatisfy({ id in
        threads.contains { $0.id == id && $0.hostId == host.id && canArchive($0) }
      }) else { throw HostServiceError.message(L10n.tr("所选内容还有待核对的操作，请先核对结果。")) }
    }
    managementBusy = true
    defer { managementBusy = false }
    let operation = WorkspaceManagementOperation(hostId: host.id, target: target, clientOperationId: UUID().uuidString,
      action: action, ids: ids, items: ids.map { .init(id: $0, state: "pending", error: nil) }, labels: labels)
    if action == .archiveThreads, !SharedMobileRules.workspaceArchiveRequestValid(operation.request) { throw HostServiceError.wrongHost }
    let archiveGeneration = clientGeneration
    saved.workspaceManagement = (saved.workspaceManagement ?? []) + [operation]
    try await persistBeforeCommand()
    if action == .archiveThreads, !host.isDemo {
      guard clientGeneration == archiveGeneration, let current = client, current.id == host.id,
        current.endpoint == host.endpoint, mobileReadingTarget(current) == target, current.online, canManageWorkspace(action),
        ids.allSatisfy({ id in threads.contains { $0.id == id && $0.hostId == host.id && canArchiveExcluding($0, operationId: operation.id) } }) else {
        if let index = saved.workspaceManagement?.firstIndex(where: { $0.id == operation.id }) {
          saved.workspaceManagement?[index].items = operation.items.map { .init(id: $0.id, state: "rejected", error: L10n.tr("管理目标已变化，请重新选择。")) }
          try await persistBeforeCommand()
        }
        throw HostServiceError.wrongHost
      }
    }
    if host.isDemo {
      var result = operation
      result.items = result.items.map { .init(id: $0.id, state: "completed", error: nil) }
      try applyWorkspaceManagement(result, original: operation, host: host)
      return
    }
    do {
      let result = try await AgentClients.resolve(host).manageWorkspace(host, request: operation.request)
      try applyWorkspaceManagement(result, original: operation, host: host)
      // Submission and completion are separate. Continue only read-only checks of this ID.
      for _ in 0..<10 {
        guard saved.workspaceManagement?.first(where: { $0.id == operation.id })?.needsCheck == true else { break }
        try await Task.sleep(for: .seconds(1))
        let value = try await AgentClients.resolve(host).workspaceManagementStatus(host, request: operation.request)
        try applyWorkspaceManagement(value, original: operation, host: host)
        if value.items.contains(where: { $0.state == "outcome-unknown" }) { break }
      }
    } catch { throw HostServiceError.message(L10n.tr("批量操作结果尚未确认，请使用“核对结果”查询原操作。")) }
  }
  func checkWorkspaceManagement(_ operation: WorkspaceManagementOperation) async throws {
    guard !managementBusy, let host = client, host.id == operation.hostId,
      mobileReadingTarget(host) == operation.target else { throw HostServiceError.wrongHost }
    managementBusy = true
    defer { managementBusy = false }
    let result = try await AgentClients.resolve(host).workspaceManagementStatus(host, request: operation.request)
    try applyWorkspaceManagement(result, original: operation, host: host)
  }
  private func applyWorkspaceManagement(_ incoming: WorkspaceManagementOperation, original: WorkspaceManagementOperation, host: Client) throws {
    var incoming = incoming
    if original.action == .archiveThreads {
      guard let previous = saved.workspaceManagement?.first(where: { $0.id == original.id }),
        let merged = SharedMobileRules.workspaceArchiveMerged(incoming, original: original, previous: previous) else { throw HostServiceError.wrongHost }
      incoming = merged
    }
    guard incoming.request == original.request, incoming.target.matchesCapturedPayload(original.target), incoming.items.map(\.id) == original.ids,
      incoming.items.allSatisfy({ ["pending", "submitting", "completed", "rejected", "outcome-unknown"].contains($0.state) }),
      let index = saved.workspaceManagement?.firstIndex(where: { $0.id == original.id }) else { throw HostServiceError.wrongHost }
    var result = incoming
    result.labels = original.labels
    saved.workspaceManagement?[index] = result
    let ids = Set(result.items.filter { $0.state == "completed" }.map(\.id))
    if !ids.isEmpty {
      managementRevisions[host.id] = UUID()
      if original.action == .archiveThreads, client?.id == host.id {
        workspaceRefreshOwner = nil; workspaceRefreshGeneration = nil; loading = false
      }
    }
    // Late results remain attached to their original target; a changed connection is untouched.
    guard saved.clients.first(where: { $0.id == host.id }).map({ mobileReadingTarget($0) == original.target }) == true else { persist(); return }
    if result.action == .deleteProjects {
      if host.isDemo { saved.demoDeletedProjectIds = Array(Set(saved.demoDeletedProjectIds ?? []).union(ids)) }
      saved.workspaces[host.id]?.projects.removeAll { ids.contains($0.id) }
      if let projects = saved.workspaces[host.id]?.threads {
        saved.workspaces[host.id]?.threads = projects.map { thread in
          Conversation(id: thread.id, hostId: thread.hostId, projectId: thread.projectId.flatMap { ids.contains($0) ? nil : $0 },
            title: thread.title, state: thread.state, projectIds: thread.projectIds?.filter { !ids.contains($0) }, hasUnreadTurn: thread.hasUnreadTurn,
            readState: thread.readState, pendingAsyncQuestionCount: thread.pendingAsyncQuestionCount, archiveUnavailableReason: thread.archiveUnavailableReason)
        }
      }
      saved.expanded[host.id]?.subtract(ids)
      if ids.contains(saved.newProjects[host.id] ?? "") { saved.newProjects[host.id] = nil }
      if client?.id == host.id, ids.contains(selectedProjectId ?? "") { selectedProjectId = nil }
    } else {
      if host.isDemo { saved.demoArchivedThreadIds = Array(Set(saved.demoArchivedThreadIds ?? []).union(ids)) }
      saved.workspaces[host.id]?.threads.removeAll { ids.contains($0.id) }
      saved.workspaces[host.id]?.pinnedThreadIds?.removeAll { ids.contains($0) }
      if ids.contains(saved.lastThreads[host.id] ?? "") { saved.lastThreads[host.id] = nil }
      if client?.id == host.id, ids.contains(selectedThreadId ?? "") {
        eventTask?.cancel(); historyGeneration = UUID(); selectedThreadId = nil
      }
    }
    persist()
  }

  func addProject(_ name: String, directory: String) {
    if client?.isDemo == true {
      addDemoProject(name)
      return
    }
    guard let host = client else { return }
    Task {
      do {
        let snapshot: WorkspaceSnapshot = try await AgentClients.resolve(host).createProject(host, name: name, directory: directory, operationId: UUID().uuidString)
        guard snapshot.hostId == host.id else { throw HostServiceError.wrongHost }
        saved.workspaces[host.id] = snapshot
        if client?.id == host.id {
          drawerOpen = false
          sheet = nil
        }
        persist()
      } catch { self.presentError(error) }
    }
  }
  func loadModels() async {
    guard let host = client, let key = modelLoadingKey else { return }
    guard !Task.isCancelled else { return }
    if host.isDemo {
      models = [CodexModel(id: "demo-model", name: L10n.tr("示例模型"), isDefault: true, defaultEffort: "medium", supportedEfforts: ["low", "medium", "high"], defaultSpeed: "standard", supportedSpeeds: ["standard", "fast"])]
      return
    }
    do {
      let result: ModelList = try await AgentClients.resolve(host).models(host)
      try Task.checkCancellation()
      guard modelLoadingKey == key else { return }
      models = result.models
    } catch {
      guard !isReadCancellation(error), modelLoadingKey == key else { return }
      recordConnectionFailure(error, host: host)
    }
  }

  func addDemoProject(_ name: String) {
    guard let host = client, host.isDemo else { return }
    let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !name.isEmpty else {
      error = L10n.tr("请输入项目名称")
      return
    }
    let old = saved.workspaces[host.id]
    let project = Project(id: UUID().uuidString, hostId: host.id, name: name, roots: [])
    saved.workspaces[host.id] = WorkspaceSnapshot(
      hostId: host.id, name: host.name, connectionMode: "demo",
      projects: (old?.projects ?? []) + [project], threads: [], nextProjectCursor: nil,
      nextThreadCursor: nil)
    persist()
    newConversation(in: project)
  }
}

enum AttachmentError: LocalizedError {
  case tooLarge, unsupportedImage, tooMany
  var errorDescription: String? {
    if self == .tooMany { return L10n.tr("单条消息最多添加 10 个附件。") }
    return self == .tooLarge ? L10n.tr("请选择非空且不超过 20 MB 的文件。") : L10n.tr("无法读取这张图片，请选择可预览的静态图片。")
  }
}
enum ClientFormError: LocalizedError {
  case emptyName
  var errorDescription: String? { L10n.tr("请输入客户端名称。") }
}


// Typing publishes only to the composer; the timeline does not observe drafts.
@MainActor
final class ComposerDraftStore: ObservableObject {
  @Published var values: [String: Draft] = [:]
  @Published fileprivate(set) var suggestionUndo: DesktopSuggestionDraftUndo?
  fileprivate var textRevisions: [String: UInt64] = [:]
}

@MainActor
final class SessionBackgroundSave {
  private var identifier = UIBackgroundTaskIdentifier.invalid
  var isActive: Bool { identifier != .invalid }
  func start(onExpiration: @escaping @Sendable () -> Void = {}, finishOnExpiration: Bool = true) {
    identifier = UIApplication.shared.beginBackgroundTask(withName: "Save conversation drafts") { [weak self] in
      onExpiration()
      if finishOnExpiration { self?.finish() }
    }
  }
  static func installUnreadTransactionLifetime() {
    MobileUnreadTransactionLifetime.install {
      let create = { @MainActor () throws -> MobileUnreadTransactionLease in
        let activity = SessionBackgroundSave()
        let lease = MobileUnreadTransactionLease {
          Task { @MainActor in activity.finish() }
        }
        activity.start(onExpiration: { [weak lease] in lease?.cancel() }, finishOnExpiration: false)
        guard activity.isActive else { throw CocoaError(.userCancelled) }
        return lease
      }
      if Thread.isMainThread { return try MainActor.assumeIsolated { try create() } }
      return try DispatchQueue.main.sync { try MainActor.assumeIsolated { try create() } }
    }
  }
  func finish() {
    guard identifier != .invalid else { return }
    UIApplication.shared.endBackgroundTask(identifier)
    identifier = .invalid
  }
}


extension SessionStore {
  var archivedChats: [Conversation] { threads.filter { $0.archived == true } }
  var supportsChatManagement: Bool {
    guard let host = client, !host.isDemo else { return false }
    return saved.workspaces[host.id]?.capabilities?.contains("threads:manage") == true
  }
  var currentChatPinned: Bool {
    guard let host = client, let id = selectedThreadId else { return false }
    return saved.workspaces[host.id]?.pinnedThreadIds?.contains(id) == true
  }
  func chatActionRecordLabel(_ receipt: ChatActionReceipt) -> String {
    let title = threads.first { $0.hostId == receipt.hostId && $0.id == receipt.threadId }?.title ?? receipt.threadId
    return receipt.action.title + " · " + String(receipt.id.prefix(8)) + " · " + title
  }
  func pendingChatActions(_ threadId: String? = nil) -> [ChatActionReceipt] {
    guard let host = client else { return [] }
    let target = mobileReadingTarget(host)
    return (saved.chatActionReceipts ?? [:]).values.filter { receipt in
      receipt.hostId == host.id && receipt.target == target && receipt.pending
        && (threadId == nil || receipt.threadId == threadId)
    }.sorted { $0.id < $1.id }
  }
  var currentChatActionBusy: Bool {
    guard let host = client, let id = selectedThreadId else { return false }
    return chatActionBusy[cacheKey(host, threadId: id)] != nil
  }
  func canManageChat(_ thread: Conversation?) -> Bool { canManageChatExcluding(thread, operationId: nil) }
  private func canManageChatExcluding(_ thread: Conversation?, operationId: String?) -> Bool {
    guard let host = client, let thread else { return false }
    let busy = chatActionBusy[cacheKey(host, threadId: thread.id)].map { $0 != operationId } ?? false
    return SharedMobileRules.chatCanManage(demo: host.isDemo, online: host.online, supported: supportsChatManagement,
      knownTarget: thread.hostId == host.id && threads.contains { $0.id == thread.id && $0.hostId == thread.hostId },
      busy: busy || managementBusy,
      pending: pendingChatActions(thread.id).contains { $0.id != operationId } || workspaceArchivePendingIds().contains(thread.id))
  }
  func chatTaskStatus(_ thread: Conversation) -> String {
    let selected = thread.hostId == client?.id && thread.id == selectedThreadId
    let state = selected ? runtime?.state ?? thread.state : thread.state
    let status = SharedMobileRules.chatTaskStatus(state: state, lastTurnStatus: thread.lastTurnStatus,
      archived: thread.archived == true, online: client?.online == true,
      unconfirmed: selected && (loadedHistoryGeneration != historyGeneration || conversationSyncFailure != nil))
    let text: String
    switch status.code {
    case "running": text = L10n.tr("执行中")
    case "approval": text = L10n.tr("等待审批")
    case "failed": text = L10n.tr("执行失败")
    case "stopped": text = L10n.tr("已停止")
    case "completed": text = L10n.tr("已完成")
    case "idle": text = L10n.tr("空闲")
    default: text = L10n.tr("状态未确认")
    }
    return status.lastKnown ? L10n.tr("上次状态：\(text)") : text
  }
  func chatLifecycleStatus(_ thread: Conversation) -> String {
    let archive = thread.archived == true ? L10n.tr("已归档") : L10n.tr("未归档")
    return L10n.tr("\(archive) · 任务状态：\(chatTaskStatus(thread))")
  }
  func manageChat(_ action: ChatAction, thread: Conversation?) {
    guard let thread, thread.id == selectedThreadId, canManageChat(thread), let host = client else { return }
    let operation = ChatActionReceipt(hostId: host.id, target: mobileReadingTarget(host), threadId: thread.id,
      clientOperationId: UUID().uuidString, action: action, phase: "submitted")
    if saved.chatActionReceipts == nil { saved.chatActionReceipts = [:] }
    saved.chatActionReceipts?[operation.id] = operation
    let key = cacheKey(host, threadId: thread.id), generation = historyGeneration
    chatActionBusy[key] = operation.id
    Task {
      defer { if chatActionBusy[key] == operation.id { chatActionBusy.removeValue(forKey: key) } }
      do { try await persistBeforeCommand() }
      catch {
        saved.chatActionReceipts?[operation.id]?.phase = "rejected"
        saved.chatActionReceipts?[operation.id]?.error = L10n.tr("未发送操作：无法保存本地记录。")
        persist(); return
      }
      guard let current = client, current.id == host.id, current.endpoint == host.endpoint,
        mobileReadingTarget(current) == operation.target, historyGeneration == generation,
        selectedThreadId == thread.id, canManageChatExcluding(thread, operationId: operation.id) else {
        saved.chatActionReceipts?[operation.id]?.phase = "rejected"
        saved.chatActionReceipts?[operation.id]?.error = L10n.tr("未发送操作：目标已变化。")
        persist(); return
      }
      do {
        let receipt = try await AgentClients.resolve(host).chatAction(host, request: operation.request)
        try await acceptChatAction(receipt, original: operation, host: host, originGeneration: generation)
      } catch {
        if let phase = saved.chatActionReceipts?[operation.id]?.phase, ["confirmed", "rejected"].contains(phase) { return }
        saved.chatActionReceipts?[operation.id]?.phase = "outcome-unknown"
        let rejected: Bool
        if case HostServiceError.rejected(let code, _) = error, [400, 401, 403, 404, 409, 422].contains(code) { rejected = true }
        else { rejected = false }
        if rejected { saved.chatActionReceipts?[operation.id]?.phase = "rejected" }
        saved.chatActionReceipts?[operation.id]?.error = error.userFacingDescription
        persist()
        if let current = client, current.id == host.id, current.endpoint == host.endpoint, mobileReadingTarget(current) == operation.target, historyGeneration == generation, selectedThreadId == operation.threadId {
          self.error = rejected ? ChatAction.errorDescription(error.userFacingDescription) : L10n.tr("操作结果待确认，请在对话选项中查询原记录。")
        }
      }
    }
  }
  func endChatActionWaiting(_ operation: ChatActionReceipt) {
    guard saved.chatActionReceipts?[operation.id]?.request == operation.request else { return }
    Task {
      saved.chatActionReceipts?[operation.id]?.waitingEnded = true
      do { try await persistBeforeCommand() }
      catch { saved.chatActionReceipts?[operation.id]?.waitingEnded = operation.waitingEnded; self.presentError(error) }
    }
  }
  func queryChatAction(_ operation: ChatActionReceipt) {
    guard let host = client, mobileReadingTarget(host) == operation.target else { return }
    let generation = historyGeneration
    Task {
      do {
        let receipt = try await AgentClients.resolve(host).chatActionStatus(host, threadId: operation.threadId, operationId: operation.id)
        try await acceptChatAction(receipt, original: operation, host: host, originGeneration: generation)
      } catch {
        if let current = client, current.id == host.id, current.endpoint == host.endpoint,
          mobileReadingTarget(current) == operation.target, historyGeneration == generation,
          selectedThreadId == operation.threadId {
          if case HostServiceError.rejected(let code, _) = error, code == 404 {
            self.error = L10n.tr("未查到原聊天操作记录，结果仍未确认；不会重新提交。")
          } else { self.presentError(error) }
        }
      }
    }
  }
  private func acceptChatAction(_ receipt: ChatActionReceipt, original: ChatActionReceipt, host: Client, originGeneration: UUID) async throws {
    guard SharedMobileRules.chatActionMatches(receipt, original: original),
      SharedMobileRules.chatActionMayAccept(previous: saved.chatActionReceipts?[original.id]?.phase ?? original.phase, next: receipt.phase) else { throw HostServiceError.invalidResponse }
    var accepted = original
    accepted.phase = receipt.phase; accepted.error = receipt.error
    accepted.waitingEnded = saved.chatActionReceipts?[receipt.id]?.waitingEnded
    saved.chatActionReceipts?[receipt.id] = accepted
    persist()
    guard let current = client, current.id == host.id, current.endpoint == host.endpoint,
      mobileReadingTarget(current) == original.target else { return }
    if receipt.phase == "confirmed" {
      managementRevisions[host.id] = UUID()
      // Retire only the catalog slot. Old success/error/finally cannot consume
      // a fresh read or restore the pre-action sidebar after confirmation.
      workspaceRefreshOwner = nil; workspaceRefreshGeneration = nil; loading = false
      if receipt.action == .delete {
        saved.workspaces[host.id]?.threads.removeAll { $0.id == receipt.threadId }
        if selectedThreadId == receipt.threadId {
          historyGeneration = UUID(); eventTask?.cancel(); selectedThreadId = nil; historyCursor = nil
        }
      }
      await refresh()
    } else if historyGeneration == originGeneration, selectedThreadId == original.threadId,
      let detail = receipt.error { error = ChatAction.errorDescription(detail) }
  }
  func completeChatSearchHistory(targetKey: String) async throws -> [ChatMessage] {
    guard self.targetKey == targetKey, let host = client, let id = selectedThreadId else { throw CancellationError() }
    let target = mobileReadingTarget(host), generation = historyGeneration
    let provider = try AgentClients.resolve(host)
    var cursor: String?, seen: [String] = [], collected: [ChatMessage] = []
    repeat {
      try Task.checkCancellation()
      let page = try await provider.searchHistory(readingClient(host), threadId: id, cursor: cursor)
      guard self.targetKey == targetKey, historyGeneration == generation,
        client?.endpoint == host.endpoint, page.hostId == host.id, page.threadId == id,
        page.readTarget.map({ $0 == target }) ?? true else { throw CancellationError() }
      let order = SharedMobileRules.searchPage(previous: collected.map(\.id), incoming: page.messages.map(\.id), nextCursor: page.nextCursor, seen: seen)
      guard order.valid else { throw HostServiceError.invalidResponse }
      let values = Dictionary((page.messages + collected).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
      collected = order.messageIds.compactMap { values[$0] }
      seen = order.seenCursors; cursor = page.nextCursor
    } while cursor != nil
    return collected
  }
  func locateSearchMessage(_ message: ChatMessage, query: String, targetKey: String, history: [ChatMessage], complete: Bool) {
    guard self.targetKey == targetKey else { return }
    // Importing a search result establishes a reading session with older pages.
    // A concurrent latest-page read must not discard the located original item.
    pagedHistoryGeneration = historyGeneration
    if complete { historyCursor = nil }
    let ids = Set(history.map(\.id))
    saved.history[targetKey] = history + messages.filter { !ids.contains($0.id) }
    dirtyHistoryKeys.insert(targetKey)
    chatSearchJump = ChatSearchJump(targetKey: targetKey, message: message, query: query)
    persist()
  }
}
