import Combine
import Foundation
import UserNotifications

struct TaskHubBusinessNavigation: Identifiable, Equatable, Sendable {
  var event: BusinessNotice
  var id: String { event.eventId }
}
struct BusinessNotificationReadScope: Hashable, Sendable {
  var target: BusinessNoticeTarget
  var deviceId: String
  var sessionEpoch: UUID
  func hash(into hasher: inout Hasher) {
    hasher.combine(target.deploymentId); hasher.combine(target.serverInstanceId); hasher.combine(target.primaryHostId)
    hasher.combine(target.actorId); hasher.combine(deviceId); hasher.combine(sessionEpoch)
  }
}
private struct BusinessNotificationSaveKey: Hashable, Sendable {
  var scope: BusinessNotificationReadScope
  var projectId: String
}
/// No Store/View/Host dependency. The parent supplies authorized narrow business API calls.
@MainActor final class TaskHubBusinessNotifications: ObservableObject {
  static let shared = TaskHubBusinessNotifications()
  enum APIRequest: Sendable {
    case subscription(projectId: String, publicKey: String, deviceToken: String?, environment: String?, preferences: BusinessNotificationPreferences)
    case status(projectId: String), events(projectId: String, after: String)
    case revoke(projectId: String, generation: Int), read(eventId: String)
  }
  typealias Request = @Sendable (APIRequest) async throws -> Data
  @Published private(set) var pendingNavigation: TaskHubBusinessNavigation?
  @Published private(set) var statuses: [String: BusinessNotificationStatus] = [:]
  @Published private(set) var deviceToken: String?
  @Published private(set) var error: String?
  @Published private(set) var refreshMessages: [String: String] = [:]
  @Published private(set) var refreshingProjects = Set<String>()
  @Published private(set) var bindingCleanupError: String?
  private var context: BusinessNoticeTarget?
  private var readScope: BusinessNotificationReadScope?
  private var request: Request?
  private var generation = UUID()
  private var active = true
  private var confirmedPreferences: [String: BusinessNotificationPreferences] = [:]
  private var allowedProjects = Set<String>()
  private var revisions: [String: UUID] = [:]
  private var saveFlights: [BusinessNotificationSaveKey: UUID] = [:]
  private var deferredRestores = Set<BusinessNotificationSaveKey>()
  private var saveReleased: (@MainActor @Sendable (String) -> Void)?
  private var refreshTasks: [String: Task<Void, Never>] = [:]
  private var deadlines: [String: Task<Void, Never>] = [:]
  private var queuedRevocations: [String: BusinessNotificationBinding] {
    get { TaskNotificationShared.secret("business-pending-revocations-v1").flatMap { try? JSONDecoder().decode([String: BusinessNotificationBinding].self, from: $0) } ?? [:] }
    set { do { try TaskNotificationShared.setSecret(JSONEncoder().encode(newValue), account: "business-pending-revocations-v1") } catch { self.error = L10n.tr("无法保存业务通知撤销状态") } }
  }
  private init() {}
  func attach(service: TaskHubService, actor: TaskHubActor, deviceId: String, sessionEpoch: UUID, saveReleased: (@MainActor @Sendable (String) -> Void)? = nil, request: @escaping Request) throws {
    guard actor.kind == "human" else { throw BusinessNotificationError.changedTarget }
    let next = BusinessNoticeTarget(deploymentId: service.deploymentId, serverInstanceId: service.serverInstanceId, primaryHostId: service.primaryHostId, actorId: actor.id)
    guard BusinessNotificationShared.targetValid(next), BusinessNotificationShared.uuid(deviceId) else { throw BusinessNotificationError.changedTarget }
    let scope = BusinessNotificationReadScope(target: next, deviceId: deviceId, sessionEpoch: sessionEpoch)
    if readScope != scope {
      cancelStatusReads()
      generation = UUID(); statuses = [:]; confirmedPreferences = [:]; allowedProjects = []; refreshMessages = [:]
      context = nil; readScope = nil; self.request = nil; self.saveReleased = nil
      // A cold process has no prior in-memory context. Persisted eligibility
      // must also be removed; current bindings rehydrate from confirmed GETs.
      try reconcileBindings(target: next, allowed: [])
    }
    context = next; readScope = scope; self.request = request; self.saveReleased = saveReleased
    if let tap = pendingNavigation, tap.event.target != next { error = L10n.tr("这条通知属于另一服务或人类账户，请先连接原服务并核对登录") }
  }
  func receiveToken(_ data: Data) { deviceToken = data.map { String(format: "%02x", $0) }.joined() }
  func setActive(_ value: Bool) {
    active = value
    if value { Task { await drainRevocations() } } else { cancelStatusReads() }
  }
  func receiveTap(_ event: BusinessNotice) {
    do { try event.validate() } catch { self.error = L10n.tr("无法打开无效业务通知"); return }
    pendingNavigation = TaskHubBusinessNavigation(event: event)
    if let context, context != event.target { error = L10n.tr("通知账户与当前登录不同；须重新核对原服务及账户") }
    // Only a navigation intent. No dispatch, acceptance, receipt replay or read write here.
  }
  func navigationConsumed(_ eventId: String) { if pendingNavigation?.id == eventId { pendingNavigation = nil } }
  func synchronize(projectId: String, preferences: BusinessNotificationPreferences, environment: String?) async throws {
    try preferences.validate()
    guard !preferences.enabled || TaskNotificationShared.preferences.enabled else { throw BusinessNotificationError.invalidPayload }
    guard let scope = readScope, let request, active, allowedProjects.contains(projectId), BusinessNotificationShared.uuid(projectId) else { throw BusinessNotificationError.changedTarget }
    let key = BusinessNotificationSaveKey(scope: scope, projectId: projectId)
    guard saveFlights[key] == nil else { throw BusinessNotificationError.changedTarget }
    if deviceToken != nil && !["development", "production"].contains(environment ?? "") { throw BusinessNotificationError.invalidPayload }
    cancelStatusReads(projectId: projectId)
    let revision = UUID(); revisions[projectId] = revision
    let flight = UUID(); saveFlights[key] = flight; confirmedPreferences.removeValue(forKey: projectId)
    defer { releaseSave(key, flight: flight) }
    try discardBindings(target: scope.target, projectId: projectId)
    refreshMessages[projectId] = L10n.tr("正在保存通知设置")
    let publicKey = try BusinessNotificationShared.privateKey().publicKey.rawRepresentation.base64EncodedString()
    do {
      // Exactly one explicit save. Channel recovery below never calls subscription.
      let value = try await request(.subscription(projectId: projectId, publicKey: publicKey, deviceToken: deviceToken, environment: deviceToken == nil ? nil : environment, preferences: preferences))
      guard readScope == scope, revisions[projectId] == revision, active, allowedProjects.contains(projectId), !Task.isCancelled else { throw CancellationError() }
      let initial = try JSONDecoder().decode(BusinessNotificationStatus.self, from: value)
      try apply(initial, projectId: projectId, target: scope.target, preferences: preferences)
      confirmedPreferences[projectId] = preferences
      refreshMessages.removeValue(forKey: projectId)
      if initial.state == "pending" { startStatusRefresh(projectId: projectId, preferences: preferences) }
    } catch {
      if readScope == scope, revisions[projectId] == revision, !Task.isCancelled {
        refreshMessages[projectId] = L10n.tr("保存结果需核对原回执；不会再次提交")
      }
      throw error
    }
  }
  private func releaseSave(_ key: BusinessNotificationSaveKey, flight: UUID) {
    guard saveFlights[key] == flight else { return }
    saveFlights.removeValue(forKey: key)
    let needsRestore = deferredRestores.remove(key) != nil
    // Never release another scope/flight or reuse preferences captured while
    // its intent was unresolved. The Store re-reads the durable receipt ledger.
    if needsRestore, readScope == key.scope, active, allowedProjects.contains(key.projectId) { saveReleased?(key.projectId) }
  }
  func restoreConfirmedPreferences(projectId: String, preferences: BusinessNotificationPreferences?) throws {
    guard let scope = readScope, allowedProjects.contains(projectId) else { throw BusinessNotificationError.changedTarget }
    let key = BusinessNotificationSaveKey(scope: scope, projectId: projectId)
    if saveFlights[key] != nil { deferredRestores.insert(key); return }
    if confirmedPreferences[projectId] != preferences {
      cancelStatusReads(projectId: projectId)
      confirmedPreferences[projectId] = preferences
    }
    if preferences == nil {
      statuses.removeValue(forKey: projectId)
      refreshMessages[projectId] = L10n.tr("通知设置尚未确认；当前仅提供前台补齐")
      try discardBindings(target: scope.target, projectId: projectId)
    }
  }
  func retainProjects(_ allowed: Set<String>) throws {
    guard let context else { throw BusinessNotificationError.changedTarget }
    do { try reconcileBindings(target: context, allowed: allowed) }
    catch {
      allowedProjects = []; cancelStatusReads(); statuses = [:]; confirmedPreferences = [:]
      throw error
    }
    allowedProjects = allowed
    for id in Set(statuses.keys).union(confirmedPreferences.keys).union(revisions.keys) where !allowed.contains(id) {
      cancelStatusReads(projectId: id); confirmedPreferences.removeValue(forKey: id); statuses.removeValue(forKey: id); refreshMessages.removeValue(forKey: id)
    }
  }
  func cancelStatusReads(projectId: String? = nil) {
    let ids = projectId.map { Set([$0]) } ?? Set(refreshTasks.keys).union(deadlines.keys)
    for id in ids {
      revisions[id] = UUID(); refreshTasks.removeValue(forKey: id)?.cancel(); deadlines.removeValue(forKey: id)?.cancel(); refreshingProjects.remove(id)
    }
  }
  private func readCurrent(_ scope: BusinessNotificationReadScope, projectId: String, revision: UUID, preferences: BusinessNotificationPreferences) -> Bool {
    active && !Task.isCancelled && allowedProjects.contains(projectId) && readScope == scope && revisions[projectId] == revision && confirmedPreferences[projectId] == preferences
  }
  func startStatusRefresh(projectId: String, preferences: BusinessNotificationPreferences) {
    guard let scope = readScope, let request, active, allowedProjects.contains(projectId), confirmedPreferences[projectId] == preferences else { return }
    if refreshTasks[projectId] != nil { return } // Appearance/foreground share one read flight.
    let revision = UUID(); revisions[projectId] = revision; refreshingProjects.insert(projectId)
    refreshMessages.removeValue(forKey: projectId)
    deadlines[projectId] = Task { [weak self] in
      do { try await Task.sleep(nanoseconds: 12_000_000_000) } catch { return }
      guard let self, self.readCurrent(scope, projectId: projectId, revision: revision, preferences: preferences) else { return }
      self.cancelStatusReads(projectId: projectId)
      self.refreshMessages[projectId] = L10n.tr("设置已保存，通道仍待核对；可刷新通道")
    }
    refreshTasks[projectId] = Task { [weak self] in
      guard let self else { return }
      defer {
        if self.revisions[projectId] == revision {
          self.refreshTasks.removeValue(forKey: projectId); self.deadlines.removeValue(forKey: projectId)?.cancel(); self.refreshingProjects.remove(projectId)
        }
      }
      do {
        for attempt in 0..<6 {
          guard self.readCurrent(scope, projectId: projectId, revision: revision, preferences: preferences) else { return }
          let data = try await request(.status(projectId: projectId))
          guard self.readCurrent(scope, projectId: projectId, revision: revision, preferences: preferences) else { return }
          let status = try JSONDecoder().decode(BusinessNotificationStatus.self, from: data)
          try self.apply(status, projectId: projectId, target: scope.target, preferences: preferences)
          if status.state != "pending" { return }
          if attempt < 5 { try await Task.sleep(nanoseconds: 1_000_000_000) }
        }
        if self.readCurrent(scope, projectId: projectId, revision: revision, preferences: preferences) {
          self.refreshMessages[projectId] = L10n.tr("设置已保存，通道仍待核对；可刷新通道")
        }
      } catch {
        guard self.readCurrent(scope, projectId: projectId, revision: revision, preferences: preferences) else { return }
        self.refreshMessages[projectId] = L10n.tr("设置已保存，通道核对暂未完成；可刷新通道")
      }
    }
  }
  func catchUp(projectId: String, cursor: String, apply: @Sendable ([BusinessNotice]) async throws -> Void) async throws -> String {
    struct Feed: Decodable { var events: [BusinessNotice]; var cursor: String; var hasMore: Bool }
    guard let context, let request, cursor.range(of: "^[0-9]{1,16}$", options: .regularExpression) != nil else { throw BusinessNotificationError.changedTarget }
    let scope = generation; var next = cursor
    for _ in 0..<100 {
      let data = try await request(.events(projectId: projectId, after: next)); let feed = try JSONDecoder().decode(Feed.self, from: data)
      guard scope == generation, self.context == context, feed.cursor.range(of: "^[0-9]{1,16}$", options: .regularExpression) != nil,
        let cursorValue = UInt64(feed.cursor), let oldValue = UInt64(next), cursorValue >= oldValue, cursorValue <= 9_007_199_254_740_991 else { throw BusinessNotificationError.changedTarget }
      for event in feed.events { try event.validate(); guard event.target == context, event.projectId == projectId else { throw BusinessNotificationError.changedTarget } }
      try await apply(feed.events)
      guard scope == generation, self.context == context else { throw BusinessNotificationError.changedTarget }
      if feed.hasMore && feed.cursor == next { throw BusinessNotificationError.invalidPayload }
      next = feed.cursor; if !feed.hasMore { return next }
    }
    return next // Bounded work; parent persists only this applied cursor and continues later.
  }
  func revoke(projectId: String, generation: Int) async throws {
    guard let request, let context else { throw BusinessNotificationError.changedTarget }
    queueRevocations { $0.target == context && $0.projectId == projectId }
    _ = try await request(.revoke(projectId: projectId, generation: generation))
    await drainRevocations()
  }
  func detach(revoke: Bool = true, clearNavigation: Bool = false) {
    if revoke, let context { queueRevocations { $0.target == context } }
    if !revoke, let context { try? discardBindings(target: context) }
    cancelStatusReads()
    context = nil; readScope = nil; request = nil; saveReleased = nil; generation = UUID(); statuses = [:]; confirmedPreferences = [:]; allowedProjects = []; refreshMessages = [:]
    if clearNavigation { pendingNavigation = nil }
    if revoke { Task { await drainRevocations() } }
  }
  func disableAll() { queueRevocations { _ in true }; Task { await drainRevocations() } }
  private func apply(_ status: BusinessNotificationStatus, projectId: String, target: BusinessNoticeTarget, preferences: BusinessNotificationPreferences) throws {
    try preferences.validate()
    guard status.version == 1, status.domain == "taskhub.business", status.target == target, status.projectId == projectId,
      ["foreground-only", "apns"].contains(status.delivery), ["disabled", "ready", "pending", "unavailable"].contains(status.state) else { throw BusinessNotificationError.changedTarget }
    var bindings = BusinessNotificationShared.bindings
    for (id, binding) in bindings where binding.target == target && binding.projectId == projectId { bindings.removeValue(forKey: id) }
    if status.delivery == "apns", status.state == "ready" {
      guard status.publisherAvailable, preferences.enabled, let id = status.bindingId, BusinessNotificationShared.uuid(id),
        let key = status.publicKey, Data(base64Encoded: key)?.count == 32, let relay = status.relayURL, let url = URL(string: relay),
        url.scheme == "https", url.host != nil, url.user == nil, url.password == nil, url.query == nil, url.fragment == nil, url.path.isEmpty || url.path == "/",
        let management = status.managementToken, management.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil else { throw BusinessNotificationError.invalidPayload }
      bindings[id] = BusinessNotificationBinding(target: target, projectId: projectId, bindingId: id, publicKey: key, preferences: preferences, relayURL: relay, managementToken: management)
    }
    try persistBindings(bindings); statuses[projectId] = status
  }
  private func reconcileBindings(target: BusinessNoticeTarget, allowed: Set<String>) throws {
    try persistBindings(BusinessNotificationShared.bindings.filter { _, binding in
      binding.target == target && allowed.contains(binding.projectId)
    })
  }
  private func persistBindings(_ bindings: [String: BusinessNotificationBinding]) throws {
    do { try BusinessNotificationShared.setBindings(bindings); bindingCleanupError = nil }
    catch {
      bindingCleanupError = L10n.tr("本地通知绑定保存或清理失败，通道尚未确认；请稍后核对")
      throw error
    }
  }
  private func discardBindings(target: BusinessNoticeTarget, projectId: String? = nil) throws {
    try persistBindings(BusinessNotificationShared.bindings.filter { _, binding in
      binding.target != target || projectId != nil && binding.projectId != projectId
    })
  }
  private func queueRevocations(where matches: (BusinessNotificationBinding) -> Bool) {
    var bindings = BusinessNotificationShared.bindings, queued = queuedRevocations
    for (id, binding) in bindings where matches(binding) { queued[id] = binding; bindings.removeValue(forKey: id) }
    queuedRevocations = queued
    do { try BusinessNotificationShared.setBindings(bindings) } catch { self.error = L10n.tr("无法清理业务通知绑定") }
  }
  private func drainRevocations() async {
    for (id, binding) in queuedRevocations {
      do {
        guard let root = URL(string: binding.relayURL), root.scheme == "https", root.user == nil, root.password == nil, root.query == nil, root.fragment == nil else { throw BusinessNotificationError.invalidPayload }
        var request = URLRequest(url: root.appendingPathComponent("v1/business-push/revoke")); request.httpMethod = "POST"; request.timeoutInterval = 10
        request.setValue("Bearer " + binding.managementToken, forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["bindingId": id])
        let configuration = URLSessionConfiguration.ephemeral; configuration.httpCookieStorage = nil
        let session = URLSession(configuration: configuration, delegate: BusinessNotificationNoRedirect(), delegateQueue: nil); defer { session.invalidateAndCancel() }
        let (_, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse, response.statusCode == 200 else { throw BusinessNotificationError.invalidPayload }
        var queued = queuedRevocations; queued.removeValue(forKey: id); queuedRevocations = queued
      } catch { self.error = L10n.tr("业务通知撤销待联网同步") }
    }
  }
}
private final class BusinessNotificationNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
