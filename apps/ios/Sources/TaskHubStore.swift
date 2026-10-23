import Foundation
import MobileCore
import Combine
import UIKit

private struct TaskHubHistoryWindow: Codable {
  var roundPages = 1
  var eventPages: [String: Int] = [:]
}

// Status does not echo the subscription generation. Only an immutable, confirmed
// save for this device scope supplies preferences for a recovered crypto binding.
enum BusinessNotificationConfirmation {
  private struct Input: Decodable { var projectId: String; var preferences: BusinessNotificationPreferences }
  static func preferences(operations: [TaskHubOperation], scope: String, projectId: String, target: BusinessNoticeTarget) -> BusinessNotificationPreferences? {
    let matching = operations.filter { operation in
      operation.scope == scope && operation.path == "business-notifications/subscription" && operation.method == "PUT"
        && (try? JSONDecoder().decode(Input.self, from: operation.body))?.projectId == projectId
    }
    // This array retains durable append/SQLite position order. Receipt updates
    // replace in place; wall clocks and random IDs are not intent order.
    guard let latest = matching.last,
      latest.phase == "confirmed", latest.bodySha256 == nil || latest.bodySha256 == HostService.hash(latest.body),
      let input = try? JSONDecoder().decode(Input.self, from: latest.body), let response = latest.response,
      let status = try? JSONDecoder().decode(BusinessNotificationStatus.self, from: response),
      status.version == 1, status.domain == "taskhub.business", status.target == target, status.projectId == projectId,
      (try? input.preferences.validate()) != nil else { return nil }
    return input.preferences
  }
}

// A validated navigation result is distinct from a raw notification tap.
// Device and session epoch are part of readIdentity; target binds all three
// service IDs and the human account. Only the root's final acceptance publishes it.
struct TaskHubValidatedBusinessNavigation: Identifiable, Equatable, Sendable {
  let id = UUID()
  let event: BusinessNotice
  let readIdentity: String
  let detailGeneration: Int
  let projectId: String
  let taskId: String
  let revision: Int
  let roundIds: [String]

  func matches(readIdentity: String, target: BusinessNoticeTarget?, projectId: String?, taskId: String?, revision: Int?, roundIds: [String], detailGeneration: Int, sessionVerified: Bool) -> Bool {
    sessionVerified && self.readIdentity == readIdentity && event.target == target
      && event.projectId == projectId && event.taskId == taskId
      && (revision ?? 0) >= event.revision && roundIds.contains(event.roundId)
      && self.detailGeneration == detailGeneration
  }
  func permitsModalDismiss(readIdentity: String, presentationReadIdentity: String, target: BusinessNoticeTarget?, sessionVerified: Bool) -> Bool {
    sessionVerified && self.readIdentity == readIdentity && self.readIdentity == presentationReadIdentity && event.target == target
  }
}

// Presented views register synchronous, throwing LOCAL persistence guards.
// MainActor does not yield between their acknowledgements and root acceptance.
@MainActor final class TaskHubBusinessModalGuards {
  struct Acknowledgement { let generation: Int; let ids: Set<UUID> }
  private struct Entry { let readIdentity: String; let save: () throws -> Void }
  private var entries: [UUID: Entry] = [:]
  private var order: [UUID] = []
  private var generation = 0

  func register(_ id: UUID, readIdentity: String, save: @escaping () throws -> Void) {
    if entries[id] == nil { order.append(id) }
    entries[id] = Entry(readIdentity: readIdentity, save: save); generation += 1
  }
  func remove(_ id: UUID) {
    guard entries.removeValue(forKey: id) != nil else { return }
    order.removeAll { $0 == id }; generation += 1
  }
  func savePresented(readIdentity: String, isCurrent: () -> Bool) throws -> Acknowledgement? {
    guard isCurrent() else { return nil }
    let capturedGeneration = generation
    let presented = order.compactMap { id in entries[id].map { (id, $0) } }
    // An old-account form must not be destroyed by a new-account path commit.
    guard presented.allSatisfy({ $0.1.readIdentity == readIdentity }) else { throw TaskHubError.changedIdentity }
    for (_, entry) in presented {
      try entry.save()
      guard generation == capturedGeneration, isCurrent() else { return nil }
    }
    guard generation == capturedGeneration, isCurrent() else { return nil }
    return Acknowledgement(generation: capturedGeneration, ids: Set(presented.map { $0.0 }))
  }
  func isCurrent(_ acknowledgement: Acknowledgement) -> Bool { generation == acknowledgement.generation }
}

private struct TaskHubSaved: Codable {
  var binding: TaskHubBinding?
  var actors: [TaskHubActor] = []
  var projects: [TaskHubProject] = []
  var lists: [String: TaskHubTaskList] = [:]
  var details: [String: TaskHubDetail] = [:]
  var drafts: [String: String] = [:]
  var operations: [TaskHubOperation] = []
  var eventCursors: [String: String] = [:]
  var lastSync: Date?
  var consentGrants: [TaskHubConsentGrant]?
  var bookmarks: [String: TaskHubBookmark]?
  var uploads: [TaskHubUploadAssociation]?
  var businessPreferences: [String: BusinessNotificationPreferences]?
  var businessCursors: [String: String]?
  var businessNotices: [String: [BusinessNotice]]?
  var creations: [TaskHubCreationAssociation]?
  var historyWindows: [String: TaskHubHistoryWindow]?
}

@MainActor final class TaskHubStore: ObservableObject {
  @Published private(set) var binding: TaskHubBinding?
  @Published private(set) var actor: TaskHubActor?
  @Published private(set) var actors: [TaskHubActor] = []
  @Published private(set) var projects: [TaskHubProject] = []
  @Published private(set) var tasks: [TaskHubTask] = []
  @Published private(set) var total = 0
  @Published private(set) var page = 1
  @Published private(set) var statusCounts: [String: Int] = [:]
  @Published private(set) var detail: TaskHubDetail?
  @Published private(set) var runs: [TaskHubRun] = []
  @Published private(set) var roundSummaries: [TaskHubRoundSummary] = []
  @Published private(set) var roundsNextCursor: String?
  @Published private(set) var eventsNextCursor: [String: String] = [:]
  @Published private(set) var hosts: [TaskHubHost] = []
  @Published private(set) var projectBindings: [TaskHubProjectBinding] = []
  @Published private(set) var operations: [TaskHubOperation] = []
  @Published private(set) var online = false
  @Published private(set) var busy = false
  @Published private(set) var needsLogin = true
  @Published var error: String?
  @Published var projectId: String? {
    didSet { if oldValue != projectId { resetProjectReads() } }
  }
  @Published var query = ""
  @Published var status = ""
  @Published var scopeFilter = "all"
  @Published var ownerFilter = ""
  @Published private(set) var lastSync: Date?
  @Published private(set) var activeOperationId: String?
  @Published private(set) var consentRequest: TaskHubConsentRequest?
  @Published private(set) var consentGrants: [TaskHubConsentGrant] = []
  @Published private(set) var businessNotices: [BusinessNotice] = []
  @Published private(set) var focusedRoundId: String?
  @Published private(set) var businessModalDismissal: TaskHubValidatedBusinessNavigation?
  private let businessModalGuards = TaskHubBusinessModalGuards()
  private var businessModalAcknowledged = Set<UUID>()
  private var businessNoticeRead: TaskHubValidatedBusinessNavigation?
  private var saved = TaskHubSaved()
  private var client: TaskHubClient?
  private var realtime: Task<Void, Never>?
  private var activeSubmission: Task<Data, Error>?
  private var refreshGeneration = 0
  private var detailGeneration = 0
  private var listGeneration = 0
  private var readSnapshot: TaskHubReadSnapshot?
  private var refreshFlight: Task<Void, Never>?
  private var refreshFlightId: UUID?
  private var sessionGeneration = UUID()
  private var verifiedSessionGeneration: UUID?
  private var activeListKey: String?
  private var pageFlights = Set<String>()
  private var preparingUpload = false
  private var eventsEnabled = true
  private let deviceId: String
  private let file: URL
  private let cache: TaskHubSQLitePersistence
  private var cacheLoadFailure: Error?
  var canWrite: Bool { cacheLoadFailure == nil && online && !needsLogin && actor?.kind == "human" && verifiedSessionGeneration == sessionGeneration && !busy }
  var isAdmin: Bool { actor?.role == "admin" }
  var scope: String { (binding?.service.identity ?? "unbound") + "/" + (actor?.id ?? "anonymous") + "/" + deviceId }
  var notificationReadIdentity: String { scope + "/" + sessionGeneration.uuidString }
  var canReadNotificationStatus: Bool { online && !needsLogin && actor?.kind == "human" && verifiedSessionGeneration == sessionGeneration }
  var pendingOperations: [TaskHubOperation] { operations.filter { $0.scope == scope && ["pending", "unknown"].contains($0.phase) } }

  init() {
    let existingDevice = CredentialVault.token("task-hub-device-id-v1")
    if UUID(uuidString: existingDevice) != nil { deviceId = existingDevice }
    else {
      let nextDevice = UUID().uuidString.lowercased()
      if let _ = try? CredentialVault.set(nextDevice, id: "task-hub-device-id-v1") { deviceId = nextDevice }
      else { deviceId = "unavailable" }
    }
    file = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("task-hub-v1.bin")
    cache = TaskHubSQLitePersistence(databaseURL: file.deletingLastPathComponent().appendingPathComponent("task-hub-v1.sqlite"), legacyURL: file)
    do {
      if let data = try cache.load() { saved = try JSONDecoder().decode(TaskHubSaved.self, from: data) }
    } catch {
      cacheLoadFailure = error
      self.error = error.localizedDescription
    }
    normalizeOperations()
    binding = saved.binding
    if let binding, let session = Self.session(binding) { actor = session.actor; needsLogin = false; client = try? TaskHubClient(binding: binding, token: session.token, deviceId: deviceId) }
    restoreCache()
    if let binding, actor != nil { _ = try? TaskHubUploadCoordinator.shared.restore(binding: binding, scope: scope) }
  }
  static private func session(_ binding: TaskHubBinding) -> TaskHubLogin? {
    let value = CredentialVault.token("task-hub-session-" + HostService.hash(Data(binding.service.identity.utf8)))
    guard let session = try? JSONDecoder().decode(TaskHubLogin.self, from: Data(value.utf8)),
      session.actor.kind == "human", !session.token.isEmpty,
      let expires = BusinessNotice.date(session.expiresAt), expires > Date() else { return nil }
    return session
  }
  func choose(_ candidate: TaskHubBinding) async throws {
    guard !busy else { throw TaskHubError.message(L10n.tr("正在提交原操作，请等待回执后切换服务")) }
    try candidate.service.validate()
    let context = sessionGeneration
    let checked = try await TaskHubClient.probe(endpoint: candidate.endpoint, pin: candidate.fingerprint)
    guard context == sessionGeneration else { throw CancellationError() }
    guard checked.service.identity == candidate.service.identity else { throw TaskHubError.changedIdentity }
    TaskHubBusinessNotifications.shared.detach()
    invalidateSessionReads()
    realtime?.cancel(); client = nil; online = false; detail = nil; runs = []; tasks = []; hosts = []; projectBindings = []; projectId = nil
    roundSummaries = []; roundsNextCursor = nil; eventsNextCursor = [:]; readSnapshot = nil
    binding = checked; saved.binding = checked
    actor = Self.session(checked)?.actor; needsLogin = actor == nil
    restoreCache(); try persist()
    if let session = Self.session(checked) { client = try TaskHubClient(binding: checked, token: session.token, deviceId: deviceId); await refresh() }
  }
  func login(username: String, password: String) async throws {
    guard !busy else { throw TaskHubError.message(L10n.tr("请先查询当前提交结果，再切换人类账户")) }
    guard let binding else { throw TaskHubError.message(L10n.tr("请先选择任务主服务")) }
    let context = sessionGeneration
    let api = try TaskHubClient(binding: binding, deviceId: deviceId)
    try await api.verify()
    let body = try easyWorkJSON(["username": username, "password": password, "deviceId": deviceId, "deviceName": UIDevice.current.name])
    let response = try await api.request(path: "auth/mobile/login", method: "POST", body: body)
    let value = try JSONDecoder().decode(TaskHubLogin.self, from: response)
    guard context == sessionGeneration, self.binding?.service.identity == binding.service.identity else { throw CancellationError() }
    guard value.actor.kind == "human", !value.token.isEmpty else { throw TaskHubError.message(L10n.tr("手机需要使用人类成员账户登录")) }
    try CredentialVault.set(String(decoding: JSONEncoder().encode(value), as: UTF8.self), id: "task-hub-session-" + HostService.hash(Data(binding.service.identity.utf8)))
    // Account replacement is an explicit user action, unlike a failed status GET.
    TaskHubBusinessNotifications.shared.detach()
    invalidateSessionReads()
    actor = value.actor; needsLogin = false; client = try TaskHubClient(binding: binding, token: value.token, deviceId: deviceId)
    restoreCache(); await refresh()
  }
  func logout() async throws {
    guard !busy else { throw TaskHubError.message(L10n.tr("请先结束本机等待或取得回执，再退出账户")) }
    guard let binding else { return }
    let remote = client; let originalScope = scope
    invalidateSessionReads()
    let context = sessionGeneration
    TaskHubBusinessNotifications.shared.detach(clearNavigation: true)
    TaskHubUploadCoordinator.shared.revoke(scope: originalScope)
    realtime?.cancel(); client = nil; actor = nil; needsLogin = true; online = false; projects = []; actors = []; tasks = []; detail = nil; runs = []; operations = []; consentRequest = nil; consentGrants = []
    businessNotices = []; hosts = []; projectBindings = []; projectId = nil; focusedRoundId = nil
    roundSummaries = []; roundsNextCursor = nil; eventsNextCursor = [:]; readSnapshot = nil
    var localFailure: Error?
    do { try CredentialVault.set("", id: "task-hub-session-" + HostService.hash(Data(binding.service.identity.utf8))) }
    catch { localFailure = error }
    var remoteFailure: Error?
    do { if let remote { _ = try await remote.request(path: "auth/mobile/logout", method: "POST", body: Data("{}".utf8)) } }
    catch {
      if case TaskHubError.rejected(401, _) = error { /* Already invalid on the server. */ }
      else { remoteFailure = error }
    }
    if let localFailure { throw localFailure }
    if remoteFailure != nil {
      let message = TaskHubError.message(L10n.tr("已退出本机并取消本机上传；主服务会话撤销结果未确认，请在其他有效设备核对原会话。"))
      if context == sessionGeneration { error = message.localizedDescription }
      throw message
    }
    // Encrypted records retain their original actor scope and cannot enter the next login.
  }
  func refresh() async {
    eventsEnabled = true
    if let flight = refreshFlight { await flight.value; return }
    let id = UUID()
    let flight = Task { await refreshNow() }
    refreshFlight = flight; refreshFlightId = id
    await flight.value
    if refreshFlightId == id { refreshFlight = nil; refreshFlightId = nil }
  }
  private func refreshNow() async {
    guard let client, actor != nil else { return }
    refreshGeneration += 1; let generation = refreshGeneration; let identity = scope; let context = sessionGeneration
    do {
      let service = try await client.verify()
      struct Me: Decodable, Sendable { var actor: TaskHubActor }
      let me = try await client.read(Me.self, path: "auth/me")
      guard generation == refreshGeneration, currentSession(identity, context) else { return }
      guard me.actor.kind == "human", me.actor.id == actor?.id else { throw TaskHubError.changedIdentity }
      let nextProjects = try await client.read([TaskHubProject].self, path: "projects")
      let nextActors = try await client.read([TaskHubActor].self, path: "actors")
      guard generation == refreshGeneration, currentSession(identity, context) else { return }
      binding?.service = service; saved.binding = binding
      actor = me.actor; projects = nextProjects; actors = nextActors; online = true; needsLogin = false; error = nil; lastSync = Date()
      saved.projects = projects; saved.actors = actors; saved.lastSync = lastSync
      verifiedSessionGeneration = context
      pruneBusinessNotices()
      if let binding, let actor {
        let capturedScope = identity
        try TaskHubBusinessNotifications.shared.attach(service: binding.service, actor: actor, deviceId: deviceId, sessionEpoch: context, saveReleased: { [weak self] project in
          guard let self, self.currentSession(identity, context), self.verifiedSessionGeneration == context else { return }
          self.refreshNotificationStatus(projectId: project)
        }) { [weak self] request in
          guard let self else { throw TaskHubError.changedIdentity }
          return try await self.businessRequest(request, identity: capturedScope, context: context)
        }
      }
      try TaskHubBusinessNotifications.shared.retainProjects(Set(projects.map(\.id)))
      for project in projects { refreshNotificationStatus(projectId: project.id, includeDisabled: false) }
      if let projectId, !projects.contains(where: { $0.id == projectId }) {
        self.projectId = nil; detail = nil; tasks = []; runs = []
      }
      if projectId == nil { projectId = projects.first(where: { $0.id == bookmark?.projectId })?.id ?? projects.first?.id }
      try persist(); guard await loadTasks() else { return }
      if let taskId = detail?.task.id ?? (bookmark?.projectId == projectId ? bookmark?.taskId : nil) { guard await loadDetail(taskId) else { return } }
      // Recovery reads the original receipts; it never sends saved request bodies.
      for id in pendingOperations.map(\.id) {
        guard currentSession(identity, context), online else { return }
        do { try await queryReceipt(id, refreshAfter: false) } catch { if currentSession(identity, context) { handle(error) } }
      }
      guard currentSession(identity, context), online else { return }
      try adoptSnapshotCursor()
      if binding?.service.capabilities.contains("business-notification-feed") == true {
        for project in projects {
          guard currentSession(identity, context), online else { return }
          do { try await catchUpBusiness(project.id, identity: identity, context: context) }
          catch {
            guard currentSession(identity, context) else { return }
            if case TaskHubError.rejected(let status, _) = error, status == 401 || status == 403 { handle(error); return }
            self.error = L10n.tr("业务提醒补齐待恢复：") + error.localizedDescription
          }
        }
      }
      startEvents()
    } catch { if currentSession(identity, context), generation == refreshGeneration { handle(error) } }
  }
  @discardableResult func loadTasks(page nextPage: Int = 1) async -> Bool {
    listGeneration += 1; let generation = listGeneration
    guard let projectId else { tasks = []; total = 0; return false }
    let identity = scope; let context = sessionGeneration; let key = listKey(projectId)
    if activeListKey != key {
      activeListKey = key
      let cached = saved.lists[key]
      tasks = cached?.items ?? []; total = cached?.total ?? 0
      page = max(1, cached?.page ?? 1); statusCounts = cached?.statusCounts ?? [:]
    }
    guard let client, online else { return false }
    let depth = nextPage == 1 ? max(1, page) : max(1, nextPage)
    var queryItems = [URLQueryItem(name: "projectId", value: projectId), URLQueryItem(name: "q", value: query), URLQueryItem(name: "pageSize", value: "30")]
    if !status.isEmpty { queryItems.append(URLQueryItem(name: "status", value: status)) }
    if scopeFilter == "mine" { queryItems.append(URLQueryItem(name: "ownerId", value: actor?.id)) }
    else if !ownerFilter.isEmpty { queryItems.append(URLQueryItem(name: "ownerId", value: ownerFilter)) }
    if scopeFilter == "pending" { queryItems.append(URLQueryItem(name: "scope", value: "pending")) }
    do {
      var window: [TaskHubTask] = []
      var latest: TaskHubTaskList?
      var cursor: UInt64?
      var allPagesHaveCursor = true
      for requestedPage in 1...depth {
        try Task.checkCancellation()
        let value = try await client.read(TaskHubTaskList.self, path: "tasks", query: queryItems + [URLQueryItem(name: "page", value: String(requestedPage))])
        guard generation == listGeneration, currentSession(identity, context), !Task.isCancelled, self.projectId == projectId, key == listKey(projectId) else { return false }
        guard value.page == requestedPage, value.pageSize == 30, value.items.allSatisfy({ $0.projectId == projectId }) else { throw TaskHubError.changedIdentity }
        for incoming in value.items {
          let newest = tasks.first(where: { $0.id == incoming.id && $0.revision > incoming.revision }) ?? incoming
          if let index = window.firstIndex(where: { $0.id == newest.id }) {
            if window[index].revision < newest.revision { window[index] = newest }
          } else { window.append(newest) }
        }
        if let valueCursor = value.syncCursor.flatMap({ UInt64($0) }) { cursor = cursor.map { min($0, valueCursor) } ?? valueCursor }
        else { allPagesHaveCursor = false }
        latest = value
        if requestedPage * value.pageSize >= value.total { break }
      }
      guard let value = latest else { return false }
      tasks = window; total = value.total; page = value.page; statusCounts = value.statusCounts ?? [:]
      // Offset pages may observe different transactions; replay from their earliest watermark.
      saved.lists[key] = TaskHubTaskList(items: tasks, total: total, page: page, pageSize: value.pageSize, statusCounts: value.statusCounts, syncCursor: allPagesHaveCursor ? cursor.map { String($0) } : nil)
      lastSync = Date(); saved.lastSync = lastSync; try persist()
      return true
    } catch {
      if generation == listGeneration, currentSession(identity, context), self.projectId == projectId, key == listKey(projectId), !cancelled(error) { handle(error) }
      return false
    }
  }
  @discardableResult func loadDetail(_ id: String, preservingActiveSelection: Bool = false) async -> Bool {
    guard !preservingActiveSelection || detail?.task.id == id else { return false }
    let selectedFocus = focusedRoundId
    detailGeneration += 1; let generation = detailGeneration; let identity = scope; let context = sessionGeneration; let selectedProject = projectId
    let readIsCurrent = {
      self.detailReadCurrent(identity, context, generation, selectedProject)
        && (!preservingActiveSelection || self.detail?.task.id == id && self.focusedRoundId == selectedFocus)
    }
    let key = identity + "/" + id
    let previous = detail?.task.id == id ? detail : saved.details[key]
    let window = saved.historyWindows?[key] ?? historyWindow(previous)
    if detail?.task.id != id { detail = saved.details[scope + "/" + id]; runs = []; roundSummaries = []; readSnapshot = nil; eventsNextCursor = [:]; roundsNextCursor = nil }
    guard let client, online else { return false }
    do {
      let value: TaskHubDetail
      var snapshot: TaskHubReadSnapshot?
      var summaries: [TaskHubRoundSummary] = []
      var nextRounds: String?
      var nextEvents: [String: String] = [:]
      var appliedWindow = TaskHubHistoryWindow()
      if binding?.service.capabilities.contains("task-history-pagination") == true {
        let current = try await client.read(TaskHubSnapshot.self, path: "tasks/\(id)/snapshot")
        guard readIsCurrent() else { return false }
        guard current.task.id == id, current.snapshot.taskId == id, current.snapshot.projectId == current.task.projectId,
          selectedProject == nil || selectedProject == current.task.projectId,
          current.currentRoundDetail?.id == current.snapshot.currentRoundId,
          current.currentRoundDetail == nil || current.currentRoundDetail?.number == current.task.currentRound else { throw TaskHubError.changedIdentity }
        snapshot = current.snapshot
        let token = current.snapshot.token
        let roundQuery = [URLQueryItem(name: "snapshot", value: token), URLQueryItem(name: "pageSize", value: "30")]
        for index in 0..<max(1, window.roundPages) {
          try Task.checkCancellation()
          let page = try await client.read(TaskHubRoundsPage.self, path: "tasks/\(id)/rounds", query: roundQuery + (nextRounds.map { [URLQueryItem(name: "cursor", value: $0)] } ?? []))
          guard readIsCurrent() else { return false }
          guard page.snapshot.token == token else { throw TaskHubError.changedIdentity }
          summaries += page.items.filter { incoming in !summaries.contains { $0.id == incoming.id } }
          nextRounds = page.nextCursor; appliedWindow.roundPages = index + 1
          if nextRounds == nil { break }
        }
        var loadedRounds: [TaskHubRound] = []
        var loadedEvents: [TaskHubEvent] = []
        var wanted = current.currentRoundDetail.map { [$0.id] } ?? []
        for round in previous?.rounds ?? [] where !wanted.contains(round.id) { wanted.append(round.id) }
        for roundId in wanted {
          let round: TaskHubRound
          if let currentRound = current.currentRoundDetail, currentRound.id == roundId { round = currentRound }
          else {
            let result = try await client.read(TaskHubRoundDetail.self, path: "tasks/\(id)/rounds/\(roundId)", query: [URLQueryItem(name: "snapshot", value: token)])
            guard readIsCurrent() else { return false }
            guard result.snapshot.token == token, result.round.id == roundId else { throw TaskHubError.changedIdentity }
            round = result.round
          }
          loadedRounds.append(round)
          var eventCursor: String?
          let query = [URLQueryItem(name: "snapshot", value: token), URLQueryItem(name: "pageSize", value: "50")]
          for index in 0..<max(1, window.eventPages[roundId] ?? 1) {
            try Task.checkCancellation()
            let result = try await client.read(TaskHubEventsPage.self, path: "tasks/\(id)/rounds/\(roundId)/events", query: query + (eventCursor.map { [URLQueryItem(name: "cursor", value: $0)] } ?? []))
            guard readIsCurrent() else { return false }
            guard result.snapshot.token == token, result.items.allSatisfy({ $0.roundNumber == round.number }) else { throw TaskHubError.changedIdentity }
            loadedEvents += result.items.filter { incoming in !loadedEvents.contains { $0.id == incoming.id } }
            eventCursor = result.nextCursor; appliedWindow.eventPages[roundId] = index + 1
            if eventCursor == nil { break }
          }
          if let eventCursor { nextEvents[roundId] = eventCursor }
        }
        value = TaskHubDetail(task: current.task, rounds: loadedRounds, events: loadedEvents)
      } else { value = try await client.read(TaskHubDetail.self, path: "tasks/\(id)") }
      guard readIsCurrent() else { return false }
      let executions = try await client.read([TaskHubRun].self, path: "tasks/\(id)/runs")
      guard readIsCurrent() else { return false }
      guard value.task.id == id, selectedProject == nil || value.task.projectId == selectedProject,
        executions.allSatisfy({ $0.taskId == id }) else { throw TaskHubError.changedIdentity }
      guard detail?.task.id != id || value.task.revision >= (detail?.task.revision ?? 0) else { return false }
      detail = value; runs = executions; readSnapshot = snapshot
      roundSummaries = summaries; roundsNextCursor = nextRounds; eventsNextCursor = nextEvents
      if saved.historyWindows == nil { saved.historyWindows = [:] }
      saved.historyWindows?[key] = appliedWindow
      saved.details[key] = value; try persist()
      return true
    } catch { if readIsCurrent(), !cancelled(error) { handle(error) }; return false }
  }
  func loadMoreRounds() async {
    guard let client, online, let snapshot = readSnapshot, let cursor = roundsNextCursor else { return }
    let identity = scope; let context = sessionGeneration; let generation = detailGeneration
    let flight = context.uuidString + "/" + String(generation) + "/" + snapshot.token + "/rounds/" + cursor
    guard pageFlights.insert(flight).inserted else { return }
    defer { pageFlights.remove(flight) }
    do {
      let page = try await client.read(TaskHubRoundsPage.self, path: "tasks/\(snapshot.taskId)/rounds", query: [URLQueryItem(name: "snapshot", value: snapshot.token), URLQueryItem(name: "pageSize", value: "30"), URLQueryItem(name: "cursor", value: cursor)])
      guard pageReadCurrent(identity, context, generation, snapshot), roundsNextCursor == cursor else { return }
      guard page.snapshot.token == snapshot.token else { throw TaskHubError.changedIdentity }
      roundSummaries += page.items.filter { incoming in !roundSummaries.contains { $0.id == incoming.id } }; roundsNextCursor = page.nextCursor
      updateHistoryWindow(snapshot.taskId) { $0.roundPages += 1 }
      try persist()
    } catch { await handlePageError(error, identity: identity, context: context, generation: generation, snapshot: snapshot) }
  }
  func loadRound(_ roundId: String) async {
    guard let client, online, let snapshot = readSnapshot, detail?.rounds.contains(where: { $0.id == roundId }) == false else { return }
    let identity = scope; let context = sessionGeneration; let generation = detailGeneration
    let flight = context.uuidString + "/" + String(generation) + "/" + snapshot.token + "/round/" + roundId
    guard pageFlights.insert(flight).inserted else { return }
    defer { pageFlights.remove(flight) }
    do {
      let round = try await client.read(TaskHubRoundDetail.self, path: "tasks/\(snapshot.taskId)/rounds/\(roundId)", query: [URLQueryItem(name: "snapshot", value: snapshot.token)])
      guard pageReadCurrent(identity, context, generation, snapshot) else { return }
      let events = try await client.read(TaskHubEventsPage.self, path: "tasks/\(snapshot.taskId)/rounds/\(roundId)/events", query: [URLQueryItem(name: "snapshot", value: snapshot.token), URLQueryItem(name: "pageSize", value: "50")])
      guard pageReadCurrent(identity, context, generation, snapshot), detail?.rounds.contains(where: { $0.id == roundId }) == false else { return }
      guard round.snapshot.token == snapshot.token, events.snapshot.token == snapshot.token, round.round.id == roundId,
        events.items.allSatisfy({ $0.roundNumber == round.round.number }) else { throw TaskHubError.changedIdentity }
      detail?.rounds.append(round.round); detail?.events += events.items
      if let cursor = events.nextCursor { eventsNextCursor[roundId] = cursor }
      updateHistoryWindow(snapshot.taskId) { $0.eventPages[roundId] = 1 }
      if let detail { saved.details[scope + "/" + snapshot.taskId] = detail }; try persist()
    } catch { await handlePageError(error, identity: identity, context: context, generation: generation, snapshot: snapshot) }
  }
  func loadMoreEvents(_ roundId: String) async {
    guard let client, online, let snapshot = readSnapshot, let cursor = eventsNextCursor[roundId] else { return }
    let identity = scope; let context = sessionGeneration; let generation = detailGeneration
    let flight = context.uuidString + "/" + String(generation) + "/" + snapshot.token + "/events/" + roundId + "/" + cursor
    guard pageFlights.insert(flight).inserted else { return }
    defer { pageFlights.remove(flight) }
    do {
      let page = try await client.read(TaskHubEventsPage.self, path: "tasks/\(snapshot.taskId)/rounds/\(roundId)/events", query: [URLQueryItem(name: "snapshot", value: snapshot.token), URLQueryItem(name: "pageSize", value: "50"), URLQueryItem(name: "cursor", value: cursor)])
      guard pageReadCurrent(identity, context, generation, snapshot), eventsNextCursor[roundId] == cursor else { return }
      guard page.snapshot.token == snapshot.token, let round = detail?.rounds.first(where: { $0.id == roundId }),
        page.items.allSatisfy({ $0.roundNumber == round.number }) else { throw TaskHubError.changedIdentity }
      let oldIds = Set(detail?.events.map(\.id) ?? [])
      detail?.events += page.items.filter { !oldIds.contains($0.id) }; eventsNextCursor[roundId] = page.nextCursor
      updateHistoryWindow(snapshot.taskId) { $0.eventPages[roundId] = ($0.eventPages[roundId] ?? 1) + 1 }
      if let detail { saved.details[scope + "/" + snapshot.taskId] = detail }; try persist()
    } catch { await handlePageError(error, identity: identity, context: context, generation: generation, snapshot: snapshot) }
  }
  private func handlePageError(_ error: Error, identity: String, context: UUID, generation: Int, snapshot: TaskHubReadSnapshot) async {
    guard pageReadCurrent(identity, context, generation, snapshot), !cancelled(error) else { return }
    if case TaskHubError.rejected(409, _) = error {
      self.error = L10n.tr("任务已更新，正在重新读取当前轮；草稿继续保留。")
      await loadDetail(snapshot.taskId)
    } else { handle(error) }
  }
  private func adoptSnapshotCursor() throws {
    guard let projectId, let cursor = saved.lists[listKey(projectId)]?.syncCursor else { return }
    var applied = UInt64(cursor) ?? 0
    if let detail, detail.task.projectId == projectId {
      guard let snapshot = readSnapshot, snapshot.taskId == detail.task.id else { return }
      applied = min(applied, UInt64(snapshot.syncCursor) ?? 0)
    }
    let key = scope + "/" + projectId
    if applied >= (UInt64(saved.eventCursors[key] ?? "0") ?? 0) { saved.eventCursors[key] = String(applied); try persist() }
  }
  func loadProjectSettings(_ id: String) async throws {
    guard let client, online else { throw TaskHubError.message(L10n.tr("主服务离线")) }
    let identity = scope; let context = sessionGeneration; let selectedProject = projectId
    do {
      let nextHosts = try await client.read([TaskHubHost].self, path: "host-connections")
      guard currentSession(identity, context), projectId == selectedProject else { throw CancellationError() }
      let nextBindings = try await client.read([TaskHubProjectBinding].self, path: "projects/\(id)/host-bindings")
      guard currentSession(identity, context), projectId == selectedProject else { throw CancellationError() }
      hosts = nextHosts; projectBindings = nextBindings
    } catch { handleAuthorizationError(error, identity: identity, context: context); throw error }
  }
  func read<T: Decodable & Sendable>(_ type: T.Type, path: String) async throws -> T {
    guard let client, online else { throw TaskHubError.message(L10n.tr("主服务离线")) }
    let identity = scope; let context = sessionGeneration
    do {
      let result = try await client.read(type, path: path)
      guard currentSession(identity, context), !Task.isCancelled else { throw CancellationError() }
      return result
    } catch { handleAuthorizationError(error, identity: identity, context: context); throw error }
  }
  func dismissConsent(_ id: UUID) {
    if consentRequest?.id == id { consentRequest = nil }
  }
  func allowConsent(_ id: UUID) {
    guard let request = consentRequest, request.id == id, request.businessScope == scope,
      actor?.kind == "human", !needsLogin else { dismissConsent(id); return }
    let original = saved.consentGrants
    var grants = original ?? []
    for destination in request.destinations {
      grants.removeAll { $0.businessScope == scope && $0.destination.scope == destination.scope }
      grants.append(TaskHubConsentGrant(id: UUID().uuidString, businessScope: scope, destination: destination, grantedAt: Date()))
    }
    saved.consentGrants = grants
    do {
      try persist(); syncConsentGrants(); consentRequest = nil
      // Only grant the displayed targets. Never submit a captured body or resume an operation.
    } catch { saved.consentGrants = original; self.error = error.localizedDescription }
  }
  func revokeConsent(_ id: String) {
    let original = saved.consentGrants
    guard original?.contains(where: { $0.id == id && $0.businessScope == scope }) == true else { return }
    saved.consentGrants?.removeAll { $0.id == id && $0.businessScope == scope }
    do { try persist(); syncConsentGrants() }
    catch { saved.consentGrants = original; self.error = error.localizedDescription }
  }
  private func syncConsentGrants() { consentGrants = (saved.consentGrants ?? []).filter { $0.businessScope == scope } }
  private func validateConsent(_ destinations: [TaskHubConsentDestination], identity: String) throws {
    guard identity == scope, actor?.kind == "human", !needsLogin else { throw TaskHubError.changedIdentity }
    guard TaskHubConsent.permits(destinations, businessScope: identity, grants: saved.consentGrants ?? []) else {
      throw TaskHubError.message(L10n.tr("任务 AI 数据共享许可已撤回或目标已变化。草稿保留，请核对目标后再次主动提交。"))
    }
  }
  /// Reads public Task Hub projections, never SessionStore's private Host objects.
  /// Resolve the complete future workflow before the first write: creation and
  /// feedback can cause development, review, retry rounds and closing work.
  private func requiredConsent(path: String, method: String, body: Data, client: TaskHubClient) async throws -> [TaskHubConsentDestination] {
    guard method != "DELETE", let input = try JSONSerialization.jsonObject(with: body) as? [String: Any] else { return [] }
    let parts = path.split(separator: "/").map(String.init)
    guard let area = parts.first, ["tasks", "projects"].contains(area) else { return [] }
    let isTask = area == "tasks"
    let isBinding = area == "projects" && parts.count == 3 && parts[2] == "host-bindings"
    let policyChange = area == "projects" && input["workflowPolicy"] != nil
    let resumeProject = area == "projects" && (input["archived"] as? Bool) == false
    let sourceChange = area == "projects" && parts.count == 3 && parts[2] == "source"
    let agentGrant = area == "projects" && parts.count == 4 && parts[2] == "members" && (input["canWrite"] as? Bool) == true
    guard isTask || isBinding || policyChange || resumeProject || sourceChange || agentGrant else { return [] }
    if isBinding {
      guard let hostId = input["hostId"] as? String, let remoteId = input["remoteProjectId"] as? String, let root = input["root"] as? String else { throw TaskHubError.message(L10n.tr("请选择准确电脑、项目和根目录")) }
      let currentHosts = try await client.read([TaskHubHost].self, path: "host-connections")
      guard let host = currentHosts.first(where: { $0.id == hostId && $0.status == "online" }) else { throw TaskHubError.message(L10n.tr("Agent 电脑尚未在线核对，映射未保存")) }
      let catalog = try await client.read([TaskHubHostProject].self, path: "host-connections/\(hostId)/projects")
      guard let remote = catalog.first(where: { $0.id == remoteId && $0.roots.contains(root) }) else { throw TaskHubError.message(L10n.tr("项目和根目录已变化，请重新读取真实目录")) }
      return [try TaskHubConsentDestination(target: host.target, hostId: hostId, computerName: host.name, projectName: remote.name)]
    }
    let directory = try await client.read([TaskHubActor].self, path: "actors")
    let project: TaskHubProject
    var task: TaskHubDetail?
    if isTask && parts.count > 1 {
      task = try await client.read(TaskHubDetail.self, path: "tasks/\(parts[1])")
    }
    let projectId = task?.task.projectId ?? (isTask ? input["projectId"] as? String : parts.dropFirst().first)
    let projectDirectory = try await client.read([TaskHubProject].self, path: "projects")
    guard let projectId, let currentProject = projectDirectory.first(where: { $0.id == projectId }) else {
      // Ordinary human project creation does not reach this branch.
      throw TaskHubError.message(L10n.tr("项目尚未保存，无法核对实际 Agent 目标；请先创建项目并设置映射"))
    }
    project = currentProject
    var requirements: [(actorId: String, role: String)] = []
    func participant(_ id: String?, role: String, mustBeAgent: Bool = false) throws {
      guard let id, let person = directory.first(where: { $0.id == id }) else { throw TaskHubError.message(L10n.tr("执行或验收身份已变化，请刷新成员与任务")) }
      if mustBeAgent && person.kind != "agent" { throw TaskHubError.message(L10n.tr("Agent 验收须使用当前已核对的 Agent 身份")) }
      if person.kind == "agent" { requirements.append((id, role)) }
    }
    if isTask {
      try participant(task?.task.ownerId ?? input["ownerId"] as? String, role: "executor")
      let mode = task?.task.acceptanceMode ?? input["acceptanceMode"] as? String
      try participant(task?.task.reviewerId ?? input["reviewerId"] as? String, role: "reviewer", mustBeAgent: mode == "agent_host")
      for policy in [project.workflowPolicy, task?.task.workflowPolicy, task?.current?.policy] {
        for reviewer in TaskHubConsent.requiredReviewers(policy) { try participant(reviewer, role: "reviewer", mustBeAgent: true) }
      }
      for acceptance in task?.current?.acceptances ?? [] where acceptance.mode == "agent_host" {
        try participant(acceptance.reviewerId, role: "reviewer", mustBeAgent: true)
      }
      if let responder = input["responderId"] as? String { try participant(responder, role: "executor") }
    }
    if policyChange {
      let policy = try JSONDecoder().decode(TaskHubJSON.self, from: JSONSerialization.data(withJSONObject: input["workflowPolicy"]!))
      for reviewer in TaskHubConsent.requiredReviewers(policy) { try participant(reviewer, role: "reviewer", mustBeAgent: true) }
    }
    if agentGrant { try participant(parts[3], role: "executor") }
    // Human-only task operations do not require an admin Host directory or any AI consent.
    if isTask && requirements.isEmpty { return [] }
    if agentGrant && requirements.isEmpty { return [] }
    let bindings = try await client.read([TaskHubProjectBinding].self, path: "projects/\(projectId)/host-bindings")
    if policyChange || resumeProject || sourceChange {
      // Re-enabling or changing a project may unblock existing automatic work.
      for binding in bindings { try participant(binding.actorId, role: binding.role, mustBeAgent: true) }
    }
    if agentGrant {
      requirements = bindings.filter { $0.actorId == parts[3] }.map { ($0.actorId, $0.role) }
      guard !requirements.isEmpty else { throw TaskHubError.message(L10n.tr("Agent 尚无当前项目的实际执行映射，授权未更新")) }
    }
    var destinations: [TaskHubConsentDestination] = []
    for required in requirements {
      let matches = bindings.filter { $0.projectId == projectId && $0.actorId == required.actorId && $0.role == required.role }
      guard !matches.isEmpty else { throw TaskHubError.message(L10n.tr("Agent 尚无准确开发／验收映射；未提交内容，请先核对项目设置")) }
      for binding in matches {
        guard binding.status == "verified", directory.first(where: { $0.id == required.actorId })?.hostId == binding.hostId else { throw TaskHubError.message(L10n.tr("Agent 映射或账户归属已变化，请重新核验")) }
        destinations.append(try TaskHubConsentDestination(target: binding.target, hostId: binding.hostId, computerName: binding.hostId, projectName: binding.remoteProjectName))
      }
    }
    if parts.last == "dispatches" {
      guard let bindingId = input["bindingId"] as? String, let role = input["role"] as? String,
        let selected = bindings.first(where: { $0.id == bindingId && $0.role == role && $0.status == "verified" }),
        requirements.contains(where: { $0.actorId == selected.actorId && $0.role == role })
      else { throw TaskHubError.message(L10n.tr("派发映射必须属于当前任务的准确执行／验收身份")) }
    }
    return TaskHubConsent.unique(destinations)
  }
  func creationOperation(draftKey: String, draftId: String) -> TaskHubOperation? {
    guard let association = saved.creations?.last(where: { $0.scope == scope && $0.draftKey == draftKey && $0.draftId == draftId }) else { return nil }
    return saved.operations.first { $0.scope == association.scope && $0.id == association.operationId }
  }
  func createTask(body: [String: Any], draftKey: String, draftId: String) async throws -> (data: Data, recovered: Bool, sameDraftBody: Bool) {
    guard canWrite else { throw TaskHubError.message(L10n.tr("请重新核对原主服务、人类会话和项目权限后提交")) }
    guard !draftKey.isEmpty, UUID(uuidString: draftId) != nil else { throw TaskHubError.message(L10n.tr("创建草稿的稳定身份尚未保存")) }
    let identity = scope
    let data = try easyWorkJSON(body); let digest = HostService.hash(data)
    var association = saved.creations?.last { $0.scope == identity && $0.draftKey == draftKey && $0.draftId == draftId }
    if association == nil {
      let assignedIds = Set((saved.creations ?? []).filter { $0.scope == identity }.map(\.operationId))
      let legacy = saved.operations.filter {
        $0.scope == identity && $0.path == "tasks" && $0.method == "POST" && !assignedIds.contains($0.id)
      }
      let matches = legacy.filter { HostService.hash($0.body) == digest }
      guard matches.count <= 1 else { throw TaskHubError.message(L10n.tr("多个旧创建回执匹配此草稿，请先核对原操作；不能猜测或重新创建")) }
      if let original = matches.first, let project = body["projectId"] as? String {
        association = TaskHubCreationAssociation(scope: identity, draftKey: draftKey, draftId: draftId, operationId: original.id, bodySha256: digest, projectId: project)
        try registerCreation(association!)
      } else if let unresolved = legacy.first(where: { ["pending", "unknown"].contains($0.phase) }) {
        throw TaskHubError.message(L10n.tr("旧创建 \(unresolved.id) 尚未确认；请查询原回执，不能用变化后的草稿重新创建"))
      }
    }
    if let original = association,
      let operation = saved.operations.first(where: { $0.scope == identity && $0.id == original.operationId }) {
      guard operation.path == "tasks", operation.method == "POST", HostService.hash(operation.body) == original.bodySha256 else {
        throw TaskHubError.message(L10n.tr("原创建关联与不可变操作不一致，请核对原回执"))
      }
      if operation.phase == "confirmed" {
        guard projects.contains(where: { $0.id == original.projectId }), let response = operation.response else {
          throw TaskHubError.message(L10n.tr("原创建已确认，但项目权限或原结果尚未核对：\(operation.id)"))
        }
        let result = try JSONDecoder().decode(TaskHubDetail.self, from: response)
        guard result.task.projectId == original.projectId else { throw TaskHubError.changedIdentity }
        // Later edits belong to the editable draft, not to this submitted generation.
        return (response, true, original.bodySha256 == digest)
      }
      guard operation.phase == "rejected" else {
        throw TaskHubError.message(L10n.tr("原创建 \(operation.id) 的结果尚未确认，请查询原回执；草稿变化不会产生新操作"))
      }
      association = nil // An explicit new submit after a definitive rejection.
    }
    if let original = association {
      guard original.bodySha256 == digest, body["projectId"] as? String == original.projectId else {
        throw TaskHubError.message(L10n.tr("原创建 \(original.operationId) 已绑定提交内容；请核对原操作，或明确另建新草稿"))
      }
    } else {
      guard let project = body["projectId"] as? String, projects.contains(where: { $0.id == project }) else {
        throw TaskHubError.message(L10n.tr("创建项目当前无权访问"))
      }
      association = TaskHubCreationAssociation(scope: identity, draftKey: draftKey, draftId: draftId, operationId: unusedOperationId(), bodySha256: digest, projectId: project)
      try registerCreation(association!)
    }
    guard let original = association else { throw TaskHubError.message(L10n.tr("创建关联未保存")) }
    let response = try await submit(path: "tasks", method: "POST", data: data, operationId: original.operationId)
    return (response, false, original.bodySha256 == digest)
  }
  private func registerCreation(_ association: TaskHubCreationAssociation) throws {
    let previous = saved.creations
    saved.creations = (previous ?? []) + [association]
    do { try persist() } catch { saved.creations = previous; throw error }
  }
  @discardableResult func mutate(path: String, method: String = "POST", body: [String: Any]) async throws -> Data {
    try await submit(path: path, method: method, data: easyWorkJSON(body))
  }
  private func submit(path: String, method: String, data: Data, query: [URLQueryItem] = [], contentType: String = "application/json", operationId: String? = nil) async throws -> Data {
    guard canWrite, let client else { throw TaskHubError.message(L10n.tr("请连接原主服务并登录后提交；草稿已保留")) }
    guard !pendingOperations.contains(where: { $0.path == path && $0.method == method }) else { throw TaskHubError.message(L10n.tr("这项操作的结果尚未确认，请先查询原回执")) }
    busy = true; defer { busy = false }
    let identity = scope; let context = sessionGeneration
    try await client.verify()
    guard currentSession(identity, context) else { throw TaskHubError.changedIdentity }
    let destinations = contentType == "application/json" ? try await requiredConsent(path: path, method: method, body: data, client: client) : []
    guard currentSession(identity, context) else { throw TaskHubError.changedIdentity }
    if !TaskHubConsent.permits(destinations, businessScope: identity, grants: saved.consentGrants ?? []) {
      consentRequest = TaskHubConsentRequest(businessScope: identity, sourcePath: path, destinations: destinations)
      throw TaskHubError.message(L10n.tr("请先确认任务 AI 数据共享目标。允许后再次点击原提交按钮；当前内容未提交。"))
    }
    try validateConsent(destinations, identity: identity)
    let id = operationId ?? unusedOperationId()
    guard !saved.operations.contains(where: { $0.id == id && $0.scope == identity }) else { throw TaskHubError.message(L10n.tr("原操作已登记，请核对原回执")) }
    var operation = TaskHubOperation(id: id, scope: identity, path: path, method: method, body: data, query: Dictionary(uniqueKeysWithValues: query.compactMap { item in item.value.map { (item.name, $0) } }), contentType: contentType, phase: "pending", submittedAt: Date(), bodySha256: HostService.hash(data))
    try appendOperation(operation) // Save immutable intent before any request.
    var attempted = false
    do {
      activeOperationId = id
      let submission = Task {
        guard currentSession(identity, context) else { throw CancellationError() }
        try validateConsent(destinations, identity: identity)
        attempted = true
        return try await client.request(path: path, method: method, body: data, operationId: id, query: query, contentType: contentType)
      }
      activeSubmission = submission
      defer { if activeOperationId == id { activeOperationId = nil; activeSubmission = nil } }
      let response = try await submission.value
      if activeOperationId == id { activeOperationId = nil; activeSubmission = nil }
      operation.phase = "confirmed"; operation.response = response
      replace(operation); try persist()
      guard currentSession(identity, context) else { throw TaskHubError.changedIdentity }
      if path != "attachments/prepare" { await loadTasks(); if let detail { await loadDetail(detail.task.id) } }
      return response
    } catch {
      if activeOperationId == id { activeOperationId = nil; activeSubmission = nil }
      if let retained = saved.operations.first(where: { $0.id == id && $0.scope == identity }) { operation.waitEndedAt = retained.waitEndedAt }
      if !attempted { operation.phase = "rejected" }
      else if case TaskHubError.rejected(let status, _) = error, (400..<500).contains(status), status != 409 { operation.phase = "rejected" }
      else if operation.phase != "confirmed" { operation.phase = "unknown" }
      operation.message = operation.waitEndedAt == nil ? error.localizedDescription : L10n.tr("已结束手机等待；远端结果仍需查询原回执")
      replace(operation); try? persist()
      if operation.waitEndedAt == nil, currentSession(identity, context) { handle(error) }
      if operation.waitEndedAt != nil { throw TaskHubError.message(operation.message!) }
      throw error
    }
  }
  func endWaiting(_ id: String) {
    guard activeOperationId == id, let index = saved.operations.firstIndex(where: { $0.id == id && $0.scope == scope && $0.phase == "pending" }) else { return }
    saved.operations[index].waitEndedAt = Date()
    saved.operations[index].phase = "unknown"
    saved.operations[index].message = L10n.tr("已结束手机等待；远端结果仍需查询原回执")
    syncOperations(); try? persist()
    activeSubmission?.cancel()
  }
  func queryReceipt(_ id: String, refreshAfter: Bool = true) async throws {
    guard let client, saved.operations.contains(where: { $0.id == id && $0.scope == scope }) else { throw TaskHubError.message(L10n.tr("没有当前账户的原操作记录")) }
    let identity = scope; let context = sessionGeneration
    do {
      try await client.verify()
      let receipt = try await client.read(TaskHubReceipt.self, path: "operations/\(id)")
      guard receipt.operationId == id, currentSession(identity, context),
        var operation = saved.operations.first(where: { $0.id == id && $0.scope == identity }) else { throw TaskHubError.changedIdentity }
      guard ["pending", "unknown", "confirmed", "rejected"].contains(receipt.phase) else { throw TaskHubError.message(L10n.tr("原操作返回无效状态")) }
      guard receipt.phase != "confirmed" || receipt.result != nil else { throw TaskHubError.message(L10n.tr("原确认回执缺少结果，请继续核对同一操作 ID")) }
      online = true; needsLogin = false; error = nil; lastSync = Date()
      operation.phase = receipt.phase
      operation.message = receipt.error?.message
      if let result = receipt.result {
        let response = try JSONEncoder().encode(result)
        if operation.path == "attachments", receipt.phase == "confirmed",
          let association = saved.uploads?.first(where: { $0.operationId == id && $0.scope == identity }) {
          let attachment = try JSONDecoder().decode(TaskHubAttachment.self, from: response)
          guard attachment.sha256 == nil || attachment.sha256 == association.sha256 else { throw TaskHubError.message(L10n.tr("原上传回执的附件 SHA-256 不一致")) }
        }
        operation.response = response
      }
      replace(operation); try persist()
      if operation.path == "business-notifications/subscription", operation.method == "PUT",
        let input = try? JSONSerialization.jsonObject(with: operation.body) as? [String: Any], let project = input["projectId"] as? String {
        refreshNotificationStatus(projectId: project)
      }
      if receipt.phase == "confirmed", let uploadFile = operation.uploadFile { try? FileManager.default.removeItem(atPath: uploadFile) }
      if refreshAfter { await refresh() }
    } catch { handleAuthorizationError(error, identity: identity, context: context); throw error }
  }
  func upload(name: String, mediaType: String, data: Data, projectId: String, draftKey: String, evidenceId: String, draftId: String, taskId: String?, roundId: String?, action: String) async throws -> TaskHubAttachment {
    guard canWrite, !preparingUpload, let client, let binding else { throw TaskHubError.message(L10n.tr("请连接原主服务并登录后上传")) }
    guard !data.isEmpty, data.count <= 20 * 1024 * 1024 else { throw TaskHubError.message(L10n.tr("附件须为 1 byte–20 MB")) }
    guard uploadOperation(draftKey: draftKey, evidenceId: evidenceId) == nil,
      !pendingOperations.contains(where: { $0.path == "attachments" }) else { throw TaskHubError.message(L10n.tr("请先核对原上传回执，不能重复上传")) }
    preparingUpload = true; defer { preparingUpload = false }
    let identity = scope; let context = sessionGeneration
    try await client.verify()
    guard currentSession(identity, context) else { throw TaskHubError.changedIdentity }
    let digest = HostService.hash(data)
    let supportsTickets = binding.service.capabilities.contains("attachment-upload-tickets")
    var association = saved.uploads?.last { $0.scope == identity && $0.draftKey == draftKey && $0.evidenceId == evidenceId }
    if let previous = association, saved.operations.contains(where: {
      $0.scope == identity && ($0.id == previous.operationId || $0.id == previous.prepareOperationId) && $0.phase == "rejected"
    }) { association = nil }
    if let existing = association {
      guard existing.projectId == projectId, existing.sha256 == digest,
        existing.draftId == draftId, existing.taskId == taskId, existing.roundId == roundId, existing.action == action else {
        throw TaskHubError.message(L10n.tr("原附件已绑定其他草稿或轮次，请保留原记录并重新选择证据"))
      }
    } else {
      let uploadId = unusedOperationId()
      var prepareId = unusedOperationId()
      while prepareId == uploadId { prepareId = unusedOperationId() }
      var ticketId = unusedOperationId()
      while ticketId == uploadId || ticketId == prepareId { ticketId = unusedOperationId() }
      association = TaskHubUploadAssociation(scope: identity, draftKey: draftKey, evidenceId: evidenceId, operationId: uploadId, projectId: projectId, sha256: digest,
        ticketId: supportsTickets ? ticketId : nil, prepareOperationId: supportsTickets ? prepareId : nil,
        draftId: draftId, taskId: taskId, roundId: roundId, action: action)
      let originalUploads = saved.uploads
      saved.uploads = (originalUploads ?? []) + [association!]
      do { try persist() } catch { saved.uploads = originalUploads; throw error }
    }
    guard let association else { throw TaskHubError.message(L10n.tr("上传草稿关联尚未保存")) }
    if let ticketId = association.ticketId, let prepareId = association.prepareOperationId {
      let intent = try easyWorkJSON(["ticketId": ticketId, "operationId": association.operationId, "projectId": projectId,
        "scope": ["draftId": draftId, "evidenceId": evidenceId.lowercased(), "action": action, "taskId": taskId as Any? ?? NSNull(), "roundId": roundId as Any? ?? NSNull()],
        "name": name, "mediaType": mediaType, "sizeBytes": data.count, "sha256": digest])
      if let original = saved.operations.first(where: { $0.id == prepareId && $0.scope == identity }) {
        guard original.path == "attachments/prepare", original.body == intent, original.phase == "confirmed" else {
          throw TaskHubError.message(L10n.tr("原上传票据尚未确认或内容已改变，请核对原准备回执"))
        }
      } else {
        _ = try await submit(path: "attachments/prepare", method: "POST", data: intent, operationId: prepareId)
      }
      guard currentSession(identity, context) else { throw TaskHubError.changedIdentity }
    }
    guard !saved.operations.contains(where: { $0.id == association.operationId && $0.scope == identity }) else {
      throw TaskHubError.message(L10n.tr("原上传操作已登记，请查询原回执"))
    }
    busy = true; defer { busy = false }
    let boundary = "task-hub-" + UUID().uuidString
    let safeName = name.replacingOccurrences(of: "\"", with: "_").replacingOccurrences(of: "\r", with: "_").replacingOccurrences(of: "\n", with: "_")
    var body = Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(safeName)\"\r\nContent-Type: \(mediaType)\r\n\r\n".utf8)
    body.append(data); body.append(Data("\r\n--\(boundary)--\r\n".utf8))
    let id = association.operationId
    let directory = file.deletingLastPathComponent().appendingPathComponent("task-hub-uploads", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    let uploadFile = directory.appendingPathComponent(id + ".body")
    try body.write(to: uploadFile, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: uploadFile.path)
    let type = "multipart/form-data; boundary=\(boundary)"
    var uploadQuery = ["projectId": projectId]
    if let ticketId = association.ticketId { uploadQuery["ticketId"] = ticketId }
    let queryItems = uploadQuery.map { URLQueryItem(name: $0.key, value: $0.value) }
    var operation = TaskHubOperation(id: id, scope: identity, path: "attachments", method: "POST", body: Data(), query: uploadQuery, contentType: type, phase: "pending", submittedAt: Date(), bodySha256: HostService.hash(body), uploadFile: uploadFile.path)
    do { try appendOperation(operation) } catch {
      try? FileManager.default.removeItem(at: uploadFile); throw error
    }
    // Draft/evidence linkage, immutable intent and fromFile bytes are all durable now.
    do {
      var request = try client.makeRequest(path: "attachments", method: "POST", body: Data(), operationId: id, query: queryItems, contentType: type)
      request.httpBody = nil; request.setValue(String(body.count), forHTTPHeaderField: "Content-Length")
      activeOperationId = id
      let submission = Task { try await TaskHubUploadCoordinator.shared.upload(request: request, file: uploadFile, binding: binding, scope: identity, operationId: id) }
      activeSubmission = submission
      defer { if activeOperationId == id { activeOperationId = nil; activeSubmission = nil } }
      let response = try await submission.value
      let remote = try JSONDecoder().decode(TaskHubAttachment.self, from: response)
      guard remote.sizeBytes == data.count, remote.sha256 == nil || remote.sha256 == association.sha256 else { throw TaskHubError.message(L10n.tr("上传回执的摘要或大小不一致")) }
      operation.phase = "confirmed"; operation.response = response; replace(operation); try persist()
      try? FileManager.default.removeItem(at: uploadFile)
      guard currentSession(identity, context) else { throw TaskHubError.changedIdentity }
      return remote
    } catch {
      if let retained = saved.operations.first(where: { $0.id == id && $0.scope == identity }) { operation.waitEndedAt = retained.waitEndedAt }
      // 409 also carries OPERATION_UNRESOLVED; only GET can establish a definitive rejection.
      if case TaskHubError.rejected(let status, _) = error, (400..<500).contains(status), status != 409 { operation.phase = "rejected" }
      else if operation.phase != "confirmed" { operation.phase = "unknown" }
      operation.message = operation.waitEndedAt == nil ? error.localizedDescription : L10n.tr("已结束手机等待；后台上传与原回执仍可核对")
      replace(operation); try? persist()
      if operation.waitEndedAt == nil, currentSession(identity, context) { handle(error) }
      throw error
    }
  }
  func uploadOperation(draftKey: String, evidenceId: String) -> TaskHubOperation? {
    guard let association = saved.uploads?.last(where: { $0.scope == scope && $0.draftKey == draftKey && $0.evidenceId == evidenceId }) else { return nil }
    if let upload = operations.first(where: { $0.id == association.operationId && $0.phase != "rejected" }) { return upload }
    return operations.first { $0.id == association.prepareOperationId && ["pending", "unknown"].contains($0.phase) }
  }
  func revokeDeviceSession(_ id: String) async throws {
    guard canWrite, let client else { throw TaskHubError.message(L10n.tr("连接原主服务后可撤销设备会话")) }
    let identity = scope; let context = sessionGeneration
    do {
      try await client.verify()
      guard currentSession(identity, context) else { throw CancellationError() }
      _ = try await client.request(path: "auth/mobile/sessions/\(id)", method: "DELETE")
      guard currentSession(identity, context) else { throw TaskHubError.changedIdentity }
      await refresh()
    } catch { handleAuthorizationError(error, identity: identity, context: context); throw error }
  }
  private func businessRequest(_ request: TaskHubBusinessNotifications.APIRequest, identity: String, context: UUID) async throws -> Data {
    guard currentSession(identity, context), verifiedSessionGeneration == context, online, let client else { throw TaskHubError.changedIdentity }
    let path = "business-notifications"
    let result: Data
    do {
      switch request {
      case .subscription(let projectId, let publicKey, let token, let environment, let preferences):
        var body: [String: Any] = ["projectId": projectId, "publicKey": publicKey, "preferences": try JSONSerialization.jsonObject(with: JSONEncoder().encode(preferences))]
        if let token { body["deviceToken"] = token }
        if let environment { body["environment"] = environment }
        result = try await submit(path: path + "/subscription", method: "PUT", data: easyWorkJSON(body))
      case .status(let projectId):
        guard projects.contains(where: { $0.id == projectId }) else { throw TaskHubError.changedIdentity }
        result = try await client.request(path: path + "/status", query: [URLQueryItem(name: "projectId", value: projectId)])
        guard currentSession(identity, context), verifiedSessionGeneration == context, !Task.isCancelled,
          projects.contains(where: { $0.id == projectId }) else { throw CancellationError() }
      case .events(let projectId, let after): result = try await client.request(path: path + "/events", query: [URLQueryItem(name: "projectId", value: projectId), URLQueryItem(name: "after", value: after)])
      case .revoke(let projectId, let generation): result = try await submit(path: path + "/revoke", method: "POST", data: easyWorkJSON(["projectId": projectId, "generation": generation]))
      case .read(let eventId): result = try await submit(path: path + "/\(eventId)/read", method: "POST", data: Data("{}".utf8))
      }
    } catch { handleAuthorizationError(error, identity: identity, context: context); throw error }
    guard currentSession(identity, context) else { throw CancellationError() }
    return result
  }
  func notificationPreferences(_ projectId: String) -> BusinessNotificationPreferences { saved.businessPreferences?[scope + "/" + projectId] ?? BusinessNotificationPreferences() }
  func refreshNotificationStatus(projectId: String, includeDisabled: Bool = true) {
    guard canReadNotificationStatus, let binding, let actor, projects.contains(where: { $0.id == projectId }) else { return }
    let target = BusinessNoticeTarget(deploymentId: binding.service.deploymentId, serverInstanceId: binding.service.serverInstanceId, primaryHostId: binding.service.primaryHostId, actorId: actor.id)
    let preferences = BusinessNotificationConfirmation.preferences(operations: saved.operations, scope: scope, projectId: projectId, target: target)
    let notices = TaskHubBusinessNotifications.shared
    do {
      try notices.restoreConfirmedPreferences(projectId: projectId, preferences: preferences)
      guard let preferences, includeDisabled || preferences.enabled else { return }
      notices.startStatusRefresh(projectId: projectId, preferences: preferences)
    } catch { self.error = L10n.tr("本地通知绑定清理尚未完成：") + error.localizedDescription }
  }
  func synchronizeNotifications(projectId: String, preferences: BusinessNotificationPreferences, environment: String?) async throws {
    guard online, !needsLogin, projects.contains(where: { $0.id == projectId }) else { throw TaskHubError.changedIdentity }
    let identity = scope; let context = sessionGeneration
    if saved.businessPreferences == nil { saved.businessPreferences = [:] }
    saved.businessPreferences?[scope + "/" + projectId] = preferences; try persist()
    try await TaskHubBusinessNotifications.shared.synchronize(projectId: projectId, preferences: preferences, environment: environment)
    guard currentSession(identity, context) else { throw TaskHubError.changedIdentity }
  }
  private func catchUpBusiness(_ projectId: String, identity: String, context: UUID) async throws {
    let key = identity + "/" + projectId
    let cursor = saved.businessCursors?[key] ?? "0"
    let next = try await TaskHubBusinessNotifications.shared.catchUp(projectId: projectId, cursor: cursor) { [weak self] events in
      try await self?.mergeBusinessNotices(events, key: key, identity: identity, context: context, project: projectId)
    }
    guard currentSession(identity, context), projects.contains(where: { $0.id == projectId }) else { throw CancellationError() }
    if saved.businessCursors == nil { saved.businessCursors = [:] }
    saved.businessCursors?[key] = next; try persist()
  }
  private func mergeBusinessNotices(_ events: [BusinessNotice], key: String, identity: String, context: UUID, project: String) throws {
    guard currentSession(identity, context), verifiedSessionGeneration == context,
      projects.contains(where: { $0.id == project }), key == identity + "/" + project else { throw CancellationError() }
    if saved.businessNotices == nil { saved.businessNotices = [:] }
    var notices = saved.businessNotices?[key] ?? []
    for event in events where !notices.contains(where: { $0.eventId == event.eventId }) { notices.append(event) }
    saved.businessNotices?[key] = Array(notices.suffix(200))
    publishBusinessNotices()
    try persist()
  }
  func openBusinessNotice(_ event: BusinessNotice) async throws -> TaskHubValidatedBusinessNavigation {
    try event.validate()
    guard let binding, let actor, actor.kind == "human", !needsLogin,
      event.target == BusinessNoticeTarget(deploymentId: binding.service.deploymentId, serverInstanceId: binding.service.serverInstanceId, primaryHostId: binding.service.primaryHostId, actorId: actor.id) else { throw TaskHubError.message(L10n.tr("通知属于另一服务或账户，请先连接原服务并核对登录")) }
    let identity = scope; let context = sessionGeneration; let readIdentity = notificationReadIdentity
    await refresh()
    try Task.checkCancellation()
    guard currentSession(identity, context), verifiedSessionGeneration == context, online,
      projects.contains(where: { $0.id == event.projectId }), let client else { throw TaskHubError.message(L10n.tr("通知项目当前无权访问")) }
    let generation = detailGeneration
    let readIsCurrent = {
      self.currentSession(identity, context) && self.verifiedSessionGeneration == context && self.online
        && !Task.isCancelled && self.detailGeneration == generation && self.projects.contains(where: { $0.id == event.projectId })
    }
    // Validate into a private candidate. Do not replace the current project/detail
    // or its modal anchor until all presented drafts have durably saved.
    let value: TaskHubDetail
    if binding.service.capabilities.contains("task-history-pagination") {
      let current = try await client.read(TaskHubSnapshot.self, path: "tasks/\(event.taskId)/snapshot")
      guard readIsCurrent() else { throw CancellationError() }
      guard current.task.id == event.taskId, current.task.projectId == event.projectId, current.task.revision >= event.revision,
        current.snapshot.taskId == event.taskId, current.snapshot.projectId == event.projectId, current.snapshot.revision == current.task.revision,
        current.currentRoundDetail?.id == current.snapshot.currentRoundId,
        current.currentRoundDetail == nil || current.currentRoundDetail?.number == current.task.currentRound else { throw TaskHubError.changedIdentity }
      let round: TaskHubRound
      if let currentRound = current.currentRoundDetail, currentRound.id == event.roundId { round = currentRound }
      else {
        let result = try await client.read(TaskHubRoundDetail.self, path: "tasks/\(event.taskId)/rounds/\(event.roundId)", query: [URLQueryItem(name: "snapshot", value: current.snapshot.token)])
        guard readIsCurrent() else { throw CancellationError() }
        guard result.snapshot.token == current.snapshot.token, result.snapshot.taskId == event.taskId,
          result.snapshot.projectId == event.projectId, result.snapshot.revision == current.task.revision,
          result.round.id == event.roundId else { throw TaskHubError.changedIdentity }
        round = result.round
      }
      value = TaskHubDetail(task: current.task, rounds: [round], events: [])
    } else {
      value = try await client.read(TaskHubDetail.self, path: "tasks/\(event.taskId)")
      guard readIsCurrent() else { throw CancellationError() }
    }
    guard readIsCurrent(), value.task.id == event.taskId, value.task.projectId == event.projectId,
      value.task.revision >= event.revision, value.rounds.contains(where: { $0.id == event.roundId }) else { throw TaskHubError.message(L10n.tr("通知原任务或轮次尚未确认，未切换页面")) }
    let navigation = TaskHubValidatedBusinessNavigation(event: event, readIdentity: readIdentity, detailGeneration: generation,
      projectId: value.task.projectId, taskId: value.task.id, revision: value.task.revision, roundIds: value.rounds.map(\.id))
    businessNoticeRead = navigation
    return navigation
  }
  private var businessNoticeTarget: BusinessNoticeTarget? {
    guard let binding, let actor, actor.kind == "human" else { return nil }
    return BusinessNoticeTarget(deploymentId: binding.service.deploymentId, serverInstanceId: binding.service.serverInstanceId, primaryHostId: binding.service.primaryHostId, actorId: actor.id)
  }
  private func businessNavigationIsCurrent(_ navigation: TaskHubValidatedBusinessNavigation) -> Bool {
    !Task.isCancelled && businessNoticeRead == navigation && navigation.matches(readIdentity: notificationReadIdentity, target: businessNoticeTarget,
      projectId: navigation.projectId, taskId: navigation.taskId, revision: navigation.revision, roundIds: navigation.roundIds,
      detailGeneration: detailGeneration, sessionVerified: canReadNotificationStatus) && projects.contains(where: { $0.id == navigation.event.projectId })
  }
  func registerBusinessModal(_ id: UUID, readIdentity: String, save: @escaping () throws -> Void) {
    businessModalGuards.register(id, readIdentity: readIdentity, save: save)
  }
  func unregisterBusinessModal(_ id: UUID) { businessModalGuards.remove(id) }
  func commitBusinessNoticeNavigation(_ navigation: TaskHubValidatedBusinessNavigation, isPending: () -> Bool) throws -> Bool {
    guard let acknowledgement = try businessModalGuards.savePresented(readIdentity: navigation.readIdentity, isCurrent: {
      self.businessNavigationIsCurrent(navigation) && isPending()
    }) else { return false }
    guard businessNavigationIsCurrent(navigation), isPending(), businessModalGuards.isCurrent(acknowledgement) else { return false }
    // Persist the bookmark with rollback before publishing any navigation. Unlike
    // remember(), a storage error here must reach the root and keep the tap pending.
    let previousBookmarks = saved.bookmarks
    if saved.bookmarks == nil { saved.bookmarks = [:] }
    saved.bookmarks?[scope] = TaskHubBookmark(projectId: navigation.event.projectId, taskId: navigation.event.taskId, roundId: navigation.event.roundId)
    do { try persist() } catch { saved.bookmarks = previousBookmarks; throw error }
    guard businessNavigationIsCurrent(navigation), isPending(), businessModalGuards.isCurrent(acknowledgement) else {
      saved.bookmarks = previousBookmarks; try persist(); return false
    }
    // No await or throwing save follows this final acceptance.
    projectId = navigation.event.projectId
    focusedRoundId = navigation.event.roundId
    businessModalAcknowledged = acknowledgement.ids
    businessNoticeRead = nil
    businessModalDismissal = navigation
    return true
  }
  func canDismissBusinessModal(_ navigation: TaskHubValidatedBusinessNavigation, presentationId: UUID, presentationReadIdentity: String) -> Bool {
    businessModalAcknowledged.contains(presentationId) && businessModalDismissal?.id == navigation.id
      && navigation.permitsModalDismiss(readIdentity: notificationReadIdentity,
        presentationReadIdentity: presentationReadIdentity, target: businessNoticeTarget, sessionVerified: canReadNotificationStatus)
  }
  func attachmentData(_ value: TaskHubAttachment) async throws -> Data {
    guard let client, online else { throw TaskHubError.message(L10n.tr("连接主服务后可以查看证据附件")) }
    let identity = scope; let context = sessionGeneration
    do {
      let bytes = try await client.download(value)
      guard currentSession(identity, context), !Task.isCancelled else { throw CancellationError() }
      guard bytes.count == value.sizeBytes, value.sha256 == nil || HostService.hash(bytes) == value.sha256 else { throw TaskHubError.message(L10n.tr("附件字节或 SHA-256 与登记不一致")) }
      return bytes
    } catch { handleAuthorizationError(error, identity: identity, context: context); throw error }
  }
  func thumbnailData(_ value: TaskHubAttachment) async throws -> Data {
    guard let client, online, value.mediaType.hasPrefix("image/"), value.thumbnailUrl != nil else { throw TaskHubError.message(L10n.tr("图片缩略图不可用")) }
    let identity = scope; let context = sessionGeneration
    do {
      let data = try await client.request(path: "attachments/\(value.id)/thumbnail")
      guard currentSession(identity, context), !Task.isCancelled else { throw CancellationError() }
      return data
    } catch { handleAuthorizationError(error, identity: identity, context: context); throw error }
  }
  func draft(_ key: String) -> String { saved.drafts[scope + "/" + key] ?? "" }
  func saveDraft(_ value: String, key: String) { saved.drafts[scope + "/" + key] = value; do { try persist() } catch { self.error = error.localizedDescription } }
  func saveSubmissionDraft(_ value: String, key: String) throws {
    let scopedKey = scope + "/" + key; let original = saved.drafts[scopedKey]
    saved.drafts[scopedKey] = value
    do { try persist() } catch { saved.drafts[scopedKey] = original; throw error }
  }
  func stop() { eventsEnabled = false; realtime?.cancel(); realtime = nil; TaskHubBusinessNotifications.shared.cancelStatusReads() }
  private func startEvents() {
    realtime?.cancel()
    guard eventsEnabled, online, !needsLogin, let projectId, let client else { return }
    let identity = scope; let context = sessionGeneration; let key = identity + "/" + projectId
    realtime = Task { [weak self] in
      while !Task.isCancelled {
        do {
          let cursor = self?.saved.eventCursors[key]
          try await client.events(projectId: projectId, cursor: cursor) { [weak self] change in
            try await self?.eventChanged(change, key: key, identity: identity, context: context)
          }
        } catch {
          if Task.isCancelled { return }
          guard self?.currentSession(identity, context) == true, self?.projectId == projectId else { return }
          if case TaskHubError.rejected(let status, _) = error, status == 401 || status == 403 { self?.handle(error); return }
        }
        do { try await Task.sleep(for: .seconds(5)) } catch { return }
        guard self?.currentSession(identity, context) == true, self?.projectId == projectId else { return }
        _ = await self?.recoverActiveExecutionRead(identity: identity, context: context, project: projectId)
      }
    }
  }
  private func eventChanged(_ change: TaskHubChange, key: String, identity: String, context: UUID) async throws {
    guard currentSession(identity, context), online, !Task.isCancelled, key == identity + "/" + (projectId ?? "") else { throw CancellationError() }
    guard let cursor = UInt64(change.cursor), cursor <= 9_007_199_254_740_991 else { throw TaskHubError.changedIdentity }
    if let previous = saved.eventCursors[key], (UInt64(previous) ?? 0) >= cursor { return }
    let activeTask = detail?.task.id, activeGeneration = detailGeneration, activeFocus = focusedRoundId
    guard await loadTasks() else { throw TaskHubError.message(L10n.tr("实时快照未同步，保留原游标等待恢复")) }
    guard currentSession(identity, context), online, !Task.isCancelled, key == identity + "/" + (projectId ?? ""),
      detail?.task.id == activeTask, detailGeneration == activeGeneration, focusedRoundId == activeFocus else { throw CancellationError() }
    if let activeTask, activeTask == change.taskId {
      // Execution state has its own stable change cursor, even at the same business revision.
      guard await loadDetail(activeTask, preservingActiveSelection: true), detail?.task.id == activeTask,
        detail?.task.revision ?? 0 >= change.revision else { throw TaskHubError.message(L10n.tr("当前任务及执行尚未同步，保留原游标")) }
    }
    guard currentSession(identity, context), key == identity + "/" + (projectId ?? ""), online, !Task.isCancelled,
      detail?.task.id == activeTask, focusedRoundId == activeFocus,
      detailGeneration == activeGeneration + (activeTask == change.taskId ? 1 : 0) else { throw CancellationError() }
    if let previous = saved.eventCursors[key], (UInt64(previous) ?? 0) >= cursor { return }
    let previous = saved.eventCursors[key]
    saved.eventCursors[key] = change.cursor
    do { try persist() } catch { saved.eventCursors[key] = previous; throw error }
  }
  private func recoverActiveExecutionRead(identity: String, context: UUID, project: String) async -> Bool {
    guard currentSession(identity, context), online, !Task.isCancelled, projectId == project else { return false }
    let activeTask = detail?.task.id, activeGeneration = detailGeneration, activeFocus = focusedRoundId
    guard await loadTasks(), currentSession(identity, context), online, !Task.isCancelled, projectId == project,
      detail?.task.id == activeTask, detailGeneration == activeGeneration, focusedRoundId == activeFocus else { return false }
    if let activeTask {
      guard await loadDetail(activeTask, preservingActiveSelection: true), detail?.task.id == activeTask else { return false }
    }
    return currentSession(identity, context) && online && !Task.isCancelled && projectId == project
      && detail?.task.id == activeTask && focusedRoundId == activeFocus
      && detailGeneration == activeGeneration + (activeTask == nil ? 0 : 1)
  }
  func projectChanged() async { await loadTasks(); startEvents() }
  private func listKey(_ project: String) -> String { scope + "/" + project + "/" + query + "/" + status + "/" + scopeFilter + "/" + ownerFilter }

  var bookmark: TaskHubBookmark? { saved.bookmarks?[scope] }
  func remember(projectId: String, taskId: String? = nil, roundId: String? = nil) {
    if saved.bookmarks == nil { saved.bookmarks = [:] }
    saved.bookmarks?[scope] = TaskHubBookmark(projectId: projectId, taskId: taskId, roundId: roundId)
    focusedRoundId = roundId
    do { try persist() } catch { self.error = error.localizedDescription }
  }
  private func currentSession(_ identity: String, _ context: UUID) -> Bool {
    TaskHubRules.shared.currentSession(expectedScope: identity, scope: scope, expectedGeneration: context.uuidString,
      generation: sessionGeneration.uuidString, actorKind: actor?.kind, needsLogin: needsLogin)
  }
  private func detailReadCurrent(_ identity: String, _ context: UUID, _ generation: Int, _ project: String?) -> Bool {
    currentSession(identity, context) && !Task.isCancelled && generation == detailGeneration && projectId == project
  }
  private func pageReadCurrent(_ identity: String, _ context: UUID, _ generation: Int, _ snapshot: TaskHubReadSnapshot) -> Bool {
    currentSession(identity, context) && !Task.isCancelled && generation == detailGeneration && readSnapshot?.token == snapshot.token
      && detail?.task.id == snapshot.taskId && projectId == snapshot.projectId
  }
  private func cancelled(_ error: Error) -> Bool {
    error is CancellationError || (error as? URLError)?.code == .cancelled
  }
  private func resetProjectReads() {
    listGeneration += 1; detailGeneration += 1
    activeListKey = nil; tasks = []; total = 0; page = 1; statusCounts = [:]
    detail = nil; runs = []; roundSummaries = []; roundsNextCursor = nil; eventsNextCursor = [:]; readSnapshot = nil
    pageFlights.removeAll(); realtime?.cancel(); realtime = nil
  }
  private func invalidateSessionReads() {
    TaskHubBusinessNotifications.shared.cancelStatusReads()
    sessionGeneration = UUID(); verifiedSessionGeneration = nil; businessNotices = []; businessModalDismissal = nil; businessNoticeRead = nil; businessModalAcknowledged = []; online = false
    refreshGeneration += 1; refreshFlight?.cancel(); refreshFlight = nil; refreshFlightId = nil
    resetProjectReads()
  }
  private func historyWindow(_ value: TaskHubDetail?) -> TaskHubHistoryWindow {
    var result = TaskHubHistoryWindow()
    for round in value?.rounds ?? [] {
      let count = value?.events.filter { $0.roundNumber == round.number }.count ?? 0
      result.eventPages[round.id] = max(1, (count + 49) / 50)
    }
    return result
  }
  private func updateHistoryWindow(_ taskId: String, update: (inout TaskHubHistoryWindow) -> Void) {
    let key = scope + "/" + taskId
    var window = saved.historyWindows?[key] ?? historyWindow(detail)
    update(&window)
    if saved.historyWindows == nil { saved.historyWindows = [:] }
    saved.historyWindows?[key] = window
  }
  private func publishBusinessNotices() {
    guard verifiedSessionGeneration == sessionGeneration, !needsLogin, let actor, actor.kind == "human", let binding else {
      businessNotices = []; return
    }
    let target = BusinessNoticeTarget(deploymentId: binding.service.deploymentId, serverInstanceId: binding.service.serverInstanceId, primaryHostId: binding.service.primaryHostId, actorId: actor.id)
    let allowed = Set(projects.map(\.id)); let prefix = scope + "/"
    var notices: [BusinessNotice] = []
    for (key, events) in saved.businessNotices ?? [:] where key.hasPrefix(prefix) {
      for event in events {
        guard event.target == target, allowed.contains(event.projectId), key == prefix + event.projectId else { continue }
        notices.append(event)
      }
    }
    businessNotices = notices.sorted { first, second in
      if first.occurredAt == second.occurredAt { return first.eventId < second.eventId }
      return first.occurredAt > second.occurredAt
    }
  }
  private func pruneBusinessNotices() {
    let prefix = scope + "/"; let keys = Set(projects.map { prefix + $0.id })
    saved.businessNotices = saved.businessNotices?.filter { !$0.key.hasPrefix(prefix) || keys.contains($0.key) }
    saved.businessCursors = saved.businessCursors?.filter { !$0.key.hasPrefix(prefix) || keys.contains($0.key) }
    publishBusinessNotices()
  }
  private func handleAuthorizationError(_ error: Error, identity: String, context: UUID) {
    guard currentSession(identity, context) else { return }
    if case TaskHubError.rejected(let status, _) = error, status == 401 || status == 403 { handle(error) }
  }
  private func unusedOperationId() -> String {
    var used = Set(saved.operations.map(\.id))
    used.formUnion((saved.creations ?? []).map(\.operationId))
    for upload in saved.uploads ?? [] {
      used.insert(upload.operationId)
      if let id = upload.prepareOperationId { used.insert(id) }
      if let id = upload.ticketId { used.insert(id) }
    }
    var id = UUID().uuidString.lowercased()
    while used.contains(id) { id = UUID().uuidString.lowercased() }
    return id
  }
  private func appendOperation(_ operation: TaskHubOperation) throws {
    guard !saved.operations.contains(where: { $0.scope == operation.scope && $0.id == operation.id }) else {
      throw TaskHubError.message(L10n.tr("原操作已登记；请查询同一 ID，不能重复提交"))
    }
    saved.operations.append(operation)
    do { try persist() }
    catch {
      saved.operations.removeAll { $0.scope == operation.scope && $0.id == operation.id }
      syncOperations(); throw error
    }
    syncOperations()
  }
  private func normalizeOperations() {
    var indexes: [String: Int] = [:]; var unique: [TaskHubOperation] = []
    for operation in saved.operations {
      let key = operation.scope + "/" + operation.id
      guard let index = indexes[key] else { indexes[key] = unique.count; unique.append(operation); continue }
      var original = unique[index]
      let sameIntent = original.path == operation.path && original.method == operation.method && original.body == operation.body && original.query == operation.query
      original.waitEndedAt = original.waitEndedAt ?? operation.waitEndedAt
      if sameIntent, original.phase == "confirmed", operation.phase != "confirmed" { unique[index] = original; continue }
      if sameIntent, operation.phase == "confirmed", original.phase != "confirmed" {
        original.phase = "confirmed"; original.response = operation.response; original.message = operation.message
      } else if !(sameIntent && original.phase == "confirmed" && operation.phase == "confirmed" && original.response == operation.response)
        && !(sameIntent && original.phase == "rejected" && operation.phase == "rejected") {
        // Old duplicate pending records are not proof that another request was safe.
        original.phase = "unknown"; original.response = nil; original.message = L10n.tr("发现重复原操作记录，请查询原 ID；不会重放")
      }
      unique[index] = original
    }
    saved.operations = unique
  }
  private func syncOperations() { operations = saved.operations.filter { $0.scope == scope } }
  private func replace(_ value: TaskHubOperation) {
    if let index = saved.operations.firstIndex(where: { $0.id == value.id && $0.scope == value.scope }) {
      let original = saved.operations[index]
      // Late cancellation/timeout must not undo an already read terminal receipt.
      guard TaskHubRules.shared.acceptsPhase(current: original.phase, next: value.phase) else { return }
      var next = value; next.waitEndedAt = next.waitEndedAt ?? original.waitEndedAt
      saved.operations[index] = next
    }
    syncOperations()
  }
  private func restoreCache() {
    // Project and actor directories must never cross a service or account boundary.
    projects = []; actors = []; tasks = []; detail = nil; businessNotices = []; focusedRoundId = bookmark?.roundId
    consentRequest = nil; syncConsentGrants()
    if let actor, saved.binding?.service.identity == binding?.service.identity {
      let cache = saved.drafts[scope + "/directory"]
      if let cache, let data = Data(base64Encoded: cache), let directory = try? JSONDecoder().decode(Directory.self, from: data) { projects = directory.projects; actors = directory.actors }
      _ = actor
      // Notification content stays hidden until auth/me and the project directory are revalidated.
    }
    lastSync = saved.lastSync; syncOperations()
  }
  private struct Directory: Codable { var projects: [TaskHubProject]; var actors: [TaskHubActor] }
  private func persist() throws {
    if let cacheLoadFailure { throw cacheLoadFailure }
    if actor != nil { saved.drafts[scope + "/directory"] = try JSONEncoder().encode(Directory(projects: projects, actors: actors)).base64EncodedString() }
    try cache.save(JSONEncoder().encode(saved))
  }
  private func handle(_ error: Error) {
    self.error = error.localizedDescription
    if case TaskHubError.rejected(let status, _) = error {
      if status == 401 || status == 403 {
        TaskHubBusinessNotifications.shared.detach(revoke: false)
        let prefix = scope + "/"
        invalidateSessionReads()
        // Retain isolated editable drafts, but stop showing revoked cached content.
        projects = []; actors = []; tasks = []; detail = nil; runs = []; hosts = []; projectBindings = []
        roundSummaries = []; roundsNextCursor = nil; eventsNextCursor = [:]; readSnapshot = nil
        saved.lists = saved.lists.filter { !$0.key.hasPrefix(scope + "/") }
        saved.details = saved.details.filter { !$0.key.hasPrefix(scope + "/") }
        saved.drafts.removeValue(forKey: scope + "/directory")
        saved.businessNotices = saved.businessNotices?.filter { !$0.key.hasPrefix(prefix) }
        saved.businessCursors = saved.businessCursors?.filter { !$0.key.hasPrefix(prefix) }
        stop(); try? persist()
      }
      if status == 401 { needsLogin = true }
      return
    }
    if case TaskHubError.changedIdentity = error { invalidateSessionReads() }
    online = false; stop()
  }
}
