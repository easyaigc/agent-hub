import AVFoundation
import CryptoKit
import SwiftUI
import UserNotifications

private final class NotificationRedirectPolicy: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
struct TaskHostNotificationRevocation: Codable {
  var client: Client
  var deviceId: String
  var target: AgentRuntimeTarget
  var removeCredentials: Bool
}

@MainActor
final class TaskNotificationController: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
  static let shared = TaskNotificationController()
  @Published private(set) var preferences = TaskNotificationShared.preferences
  @Published private(set) var sounds = TaskNotificationSoundLibrary.sounds
  @Published private(set) var authorization = L10n.tr("尚未申请权限")
  @Published private(set) var apnsRegistration = L10n.tr("尚未注册后台推送")
  @Published private(set) var statuses: [String: TaskNotificationStatus] = [:]
  @Published var error: String?
  @Published private(set) var unreadCount = 0
  @Published private(set) var unreadRevision = "0"
  @Published private(set) var unreadSnapshot = MobileUnreadBadge.Snapshot()
  private var badgeTask: Task<Void, Never>?
  private var snapshotTask: Task<Void, Never>?
  private var snapshotRequested = false
  private var desiredBadge = 0
  private weak var store: SessionStore?
  private var monitorTask: Task<Void, Never>?
  private let hostMonitor = NotificationHostMonitor()
  private var token: String?
  private var player: AVAudioPlayer?
  private var cursors: [String: String] = [:]
  private var synced: [String: String] = [:]
  private var registered: [String: String] = [:]
  private var active = true
  private var generation = UUID()
  private var pendingTap: TaskNotificationEvent?
  private var deviceId: String
  private var revoking = false
  private var revocationRetry: Task<Void, Never>?
  private let revocationSession = URLSession(configuration: .ephemeral, delegate: NotificationRedirectPolicy(), delegateQueue: nil)
  private var pendingRevocations: Set<String> {
    get { Set(TaskNotificationShared.read([String].self, name: "pending-revocations.json") ?? []) }
    set { do { try TaskNotificationShared.write(newValue.sorted(), name: "pending-revocations.json") } catch { self.error = L10n.tr("无法保存订阅撤销状态。") } }
  }
  private var pendingHostRevocations: [String: TaskHostNotificationRevocation] {
    get { TaskNotificationShared.read([String: TaskHostNotificationRevocation].self, name: "pending-host-revocations.json") ?? [:] }
    set { do { try TaskNotificationShared.write(newValue, name: "pending-host-revocations.json") } catch { self.error = L10n.tr("无法保存电脑订阅撤销状态。") } }
  }
  override private init() {
    #if DEBUG
    if ProcessInfo.processInfo.arguments.contains("-ui-testing") && ProcessInfo.processInfo.arguments.contains("-reset-notification-settings") {
      try? FileManager.default.removeItem(at: TaskNotificationShared.directory)
      preferences = TaskNotificationPreferences()
    }
    #endif
    deviceId = TaskNotificationShared.read(String.self, name: "device-id.json") ?? UUID().uuidString
    super.init()
    try? TaskNotificationShared.write(deviceId, name: "device-id.json")
    UNUserNotificationCenter.current().delegate = self
    UNUserNotificationCenter.current().setNotificationCategories([UNNotificationCategory(identifier: TaskNotificationPresentation.category, actions: [], intentIdentifiers: [], options: [])])
    try? TaskNotificationSoundLibrary.prepare()
  }
  func attach(_ store: SessionStore) {
    self.store = store
    store.synchronizeMobileUnread()
    if let event = pendingTap { pendingTap = nil; route(event) }
    Task { await refreshPermission(); if preferences.enabled { registerAPNs() }; startMonitoring(); await drainRevocations() }
  }
  func reloadAfterMigration() {
    monitorTask?.cancel(); monitorTask = nil; hostMonitor.cancel()
    preferences = TaskNotificationShared.preferences; sounds = TaskNotificationSoundLibrary.sounds
    deviceId = TaskNotificationShared.read(String.self, name: "device-id.json") ?? UUID().uuidString
    statuses = [:]; synced = [:]; registered = [:]; cursors = [:]; generation = UUID()
    Task { await revokeAll() }
  }
  func setActive(_ active: Bool) {
    self.active = active
    TaskHubBusinessNotifications.shared.setActive(active)
    updateBadge()
    if active { Task { await refreshPermission(); if preferences.enabled { registerAPNs() }; startMonitoring(); await drainRevocations() } }
    else { monitorTask?.cancel(); monitorTask = nil; hostMonitor.cancel(); revocationRetry?.cancel(); revocationRetry = nil }
  }
  func update(_ change: (inout TaskNotificationPreferences) -> Void) {
    var next = preferences; change(&next); next.revision = UUID().uuidString; next.generation += 1
    do {
      try TaskNotificationShared.write(next, name: "preferences.json"); preferences = next; generation = UUID(); synced = [:]; cursors = [:]
      updateBadge()
      queueRevocations { !next.enabled || next.excludedHosts.contains($0.target.hostId) }
      for host in store?.saved.clients.filter({ !$0.isDemo }) ?? [] where !next.enabled || next.excludedHosts.contains(target(host).hostId) { queueHostRevocation(host) }
      startMonitoring(); Task { await drainRevocations() }
    }
    catch { self.error = L10n.tr("无法保存通知设置。") }
  }
  func enable(_ enabled: Bool) {
    if !enabled {
      TaskHubBusinessNotifications.shared.disableAll()
      update { $0.enabled = false }; monitorTask?.cancel(); monitorTask = nil; hostMonitor.cancel()
      UNUserNotificationCenter.current().removeAllPendingNotificationRequests(); UNUserNotificationCenter.current().removeAllDeliveredNotifications()
      Task { await revokeAll() }
      return
    }
    Task {
      do {
        let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        await refreshPermission()
        guard granted else { error = L10n.tr("请在系统设置中允许 Agent Hub 的通知。"); return }
        update { $0.enabled = true }; registerAPNs()
      } catch { self.error = L10n.tr("无法申请通知权限，请检查系统设置。") }
    }
  }
  func refreshPermission() async {
    let settings = await UNUserNotificationCenter.current().notificationSettings()
    switch settings.authorizationStatus {
    case .authorized: authorization = settings.soundSetting == .enabled ? L10n.tr("系统通知已允许") : L10n.tr("系统通知已允许 · 声音已关闭")
    case .denied: authorization = L10n.tr("系统通知已关闭")
    case .provisional: authorization = L10n.tr("仅通知中心")
    case .ephemeral: authorization = L10n.tr("临时通知权限")
    default: authorization = L10n.tr("尚未申请权限")
    }
  }
  func registerAPNs() {
    guard preferences.enabled, !ProcessInfo.processInfo.arguments.contains("-ui-testing") else { return }
    if token == nil { apnsRegistration = L10n.tr("等待系统分配 APNs 设备令牌") }
    UIApplication.shared.registerForRemoteNotifications()
  }
  func receiveToken(_ data: Data) {
    TaskHubBusinessNotifications.shared.receiveToken(data)
    token = data.map { String(format: "%02x", $0) }.joined(); apnsRegistration = L10n.tr("APNs 设备已注册"); synced = [:]; startMonitoring()
  }
  func registrationFailed(_ registrationError: Error) {
    let failure = registrationError as NSError
    apnsRegistration = L10n.tr("APNs 注册失败（\(failure.domain) · \(failure.code)）")
    if preferences.enabled { error = L10n.tr("后台推送尚未连接，当前可使用前台提醒。请检查推送签名和网络。") }
  }
  private func target(_ client: Client) -> AgentRuntimeTarget {
    client.agentConnection?.target ?? client.codexDirect?.target ?? store?.mobileReadingTarget(client) ?? AgentRuntimeTarget(agentId: "codex", hostId: client.id, workspaceId: client.id, runtimeId: store?.saved.workspaces[client.id]?.connectionMode ?? "managed-runtime")
  }
  func hostIdentifier(_ client: Client) -> String { target(client).hostId }
  private func startMonitoring() {
    guard active, preferences.enabled, store != nil else { return }
    monitorTask?.cancel(); hostMonitor.cancel()
    monitorTask = Task {
      while !Task.isCancelled && active && preferences.enabled {
        do { try await store?.waitForSidebarInteraction() } catch { return }
        let ids = Set(store?.saved.clients.filter { !$0.isDemo }.map(\.id) ?? [])
        hostMonitor.reconcile(ids) { [weak self] id in
          guard let self, let host = self.store?.saved.clients.first(where: { $0.id == id && !$0.isDemo }) else { return }
          do { try await self.synchronize(host) }
          catch {
            if Task.isCancelled { return }
            let old = self.statuses[host.id]
            self.statuses[host.id] = TaskNotificationStatus(version: 1, target: self.target(host), delivery: old?.delivery ?? "foreground-only", state: "unavailable", reason: error.localizedDescription, revision: old?.revision, lastDelivery: old?.lastDelivery)
          }
          self.updateBadge()
        }
        try? await Task.sleep(for: .seconds(3))
      }
    }
  }
  private func synchronize(_ host: Client) async throws {
    try await store?.waitForSidebarInteraction()
    let expected = target(host), revision = preferences.revision, currentGeneration = generation
    if pendingRevocations.contains(where: { TaskNotificationShared.bindings[$0]?.target == expected }) || pendingHostRevocations.values.contains(where: { $0.target == expected }) {
      statuses[host.id] = TaskNotificationStatus(version: 1, target: expected, delivery: "apns", state: "unavailable", reason: L10n.tr("旧订阅撤销待联网同步"))
      return
    }
    let registrationSignature = (token ?? "") + (host.endpoint ?? "") + String(describing: host.agentConnection) + String(describing: expected)
    let signature = revision + registrationSignature
    let provider = try AgentClients.resolve(host)
    if synced[host.id] != signature {
      let key = try TaskNotificationShared.privateKey()
      let environment = Bundle.main.object(forInfoDictionaryKey: "NotificationEnvironment") as? String ?? "development"
      var hostPreferences = preferences
      if hostPreferences.excludedHosts.contains(expected.hostId) { hostPreferences.enabled = false }
      let request = TaskNotificationRegistration(deviceId: deviceId, target: expected, publicKey: key.publicKey.rawRepresentation.base64EncodedString(), deviceToken: token, environment: token == nil ? nil : environment, preferences: hostPreferences)
      let status: TaskNotificationStatus
      if registered[host.id] == registrationSignature {
        status = try await provider.updateNotificationPreferences(host, deviceId: deviceId, target: expected, preferences: hostPreferences)
      } else { status = try await provider.registerNotifications(host, registration: request) }
      try await store?.waitForSidebarInteraction()
      try Task.checkCancellation()
      guard generation == currentGeneration, store?.saved.clients.contains(where: { $0.id == host.id && target($0) == expected }) == true, status.target == expected else { return }
      statuses[host.id] = status
      registered[host.id] = registrationSignature
      if let bindingId = status.bindingId, let peer = status.publicKey, let relayURL = status.relayURL, let management = status.managementToken {
        var bindings = TaskNotificationShared.bindings
        bindings[bindingId] = TaskNoticeBinding(target: expected, clientName: host.name, publicKey: peer, relayURL: relayURL, managementToken: management)
        try TaskNotificationShared.setBindings(bindings)
      }
      if status.state == "ready" || status.delivery == "foreground-only" { synced[host.id] = signature }
    }
    let feed = try await provider.notificationEvents(host, deviceId: deviceId, after: cursors[host.id] ?? "")
    try await store?.waitForSidebarInteraction()
    try Task.checkCancellation()
    guard generation == currentGeneration, store?.saved.clients.contains(where: { $0.id == host.id && target($0) == expected }) == true else { return }
    if let status = feed.status {
      guard status.target == expected else { throw HostServiceError.wrongHost }
      statuses[host.id] = status
    }
    for event in feed.events {
      try await store?.waitForSidebarInteraction()
      try Task.checkCancellation()
      guard generation == currentGeneration, store?.saved.clients.contains(where: { $0.id == host.id && target($0) == expected }) == true else { return }
      guard event.target == expected else { throw HostServiceError.wrongHost }
      try MobileUnreadBadge.receive(event)
      updateBadge()
      if statuses[host.id]?.delivery != "apns" { await present(event, clientName: host.name) }
    }
    cursors[host.id] = feed.cursor
    if let activeIds = feed.activeIds {
      // Reading/decrypting the notice archive can take several frames on a real phone.
      // Only immutable identifiers return to the UI actor.
      let (resolvedIds, removedIds) = await Task.detached(priority: .utility) {
        let resolved = TaskNotificationShared.notices.filter {
          $0.target == expected && $0.kind.actionable && !activeIds.contains($0.id)
        }
        let ids = Set(resolved.map(\.id))
        let delivered = await UNUserNotificationCenter.current().deliveredNotifications()
        let removed = delivered.compactMap { notification -> String? in
          let identifier = notification.request.identifier
          if identifier.hasPrefix("local."), ids.contains(String(identifier.dropFirst("local.".count))) { return identifier }
          if let event = try? TaskNotificationShared.decodeEnvelope(notification.request.content.userInfo), ids.contains(event.id) { return identifier }
          return nil
        }
        return (Array(ids), removed)
      }.value
      try await store?.waitForSidebarInteraction()
      try Task.checkCancellation()
      guard generation == currentGeneration, store?.saved.clients.contains(where: { $0.id == host.id && target($0) == expected }) == true else { return }
      UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: removedIds)
      await Task.detached(priority: .utility) {
        for id in resolvedIds { try? FileManager.default.removeItem(at: TaskNotificationShared.directory.appendingPathComponent("notice-" + id + ".json")) }
      }.value
    }
  }
  private func present(_ event: TaskNotificationEvent, clientName: String) async {
    guard preferences.enabled, preferences.kinds.contains(event.kind), !preferences.excludedHosts.contains(event.target.hostId) else { return }
    var seen = TaskNotificationShared.read([String].self, name: "seen.json") ?? []
    guard !seen.contains(event.id) else { return }
    let content = UNMutableNotificationContent()
    let presentation = TaskNotificationPresentation(event: event, computer: clientName, showPreview: preferences.showThreadTitle)
    presentation.apply(to: content)
    content.threadIdentifier = event.target.hostId
    content.sound = sound(for: event.kind)
    if preferences.timeSensitive && [.approval, .question, .plan, .paused, .failed, .uncertain].contains(event.kind) { content.interruptionLevel = .timeSensitive }
    if preferences.badgeEnabled { content.badge = NSNumber(value: MobileUnreadBadge.count) }
    if let data = try? JSONEncoder().encode(event), let value = try? JSONSerialization.jsonObject(with: data) { content.userInfo = ["event": value, "clientName": clientName] }
    do {
      try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "local." + event.id, content: content, trigger: nil))
      try TaskNotificationShared.remember(event); seen.append(event.id); try TaskNotificationShared.write(Array(seen.suffix(2048)), name: "seen.json")
    } catch { self.error = L10n.tr("无法显示通知，请检查系统通知设置。") }
  }
  func sound(for kind: TaskNoticeKind? = nil) -> UNNotificationSound? {
    guard preferences.soundEnabled else { return nil }
    let name = kind.flatMap { preferences.categorySounds[$0.rawValue] } ?? preferences.sound
    if name == "none" { return nil }; if name == "default" { return .default }
    return UNNotificationSound(named: UNNotificationSoundName(rawValue: name))
  }
  func testNotification() {
    Task {
      let settings = await UNUserNotificationCenter.current().notificationSettings()
      guard settings.authorizationStatus == .authorized else { error = L10n.tr("请先开启系统通知权限。"); return }
      let content = UNMutableNotificationContent(); content.title = L10n.tr("Agent Hub 测试通知"); content.body = L10n.tr("通知和铃声设置已应用。"); content.sound = sound()
      do { try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "notification-test", content: content, trigger: nil)) }
      catch { self.error = L10n.tr("测试通知发送失败。") }
    }
  }
  func preview(_ name: String) {
    player?.stop()
    if name == "none" { return }
    if name == "default" { testNotification(); return }
    do { player = try AVAudioPlayer(contentsOf: TaskNotificationSoundLibrary.directory.appendingPathComponent(name)); player?.play() }
    catch { self.error = L10n.tr("无法试听这段铃声。") }
  }
  func importSound(_ url: URL) {
    Task {
      do {
        let item = try await Task.detached(priority: .userInitiated) { try TaskNotificationSoundLibrary.importFile(url) }.value
        let imported = sounds.filter(\.imported) + [item]; try TaskNotificationShared.write(imported, name: "sounds.json")
        sounds = TaskNotificationSoundLibrary.sounds; update { $0.sound = item.id }
      } catch { self.error = error.localizedDescription }
    }
  }
  func deleteSound(_ item: TaskNotificationSound) {
    guard item.imported else { return }
    do {
      update { $0.resetSoundReferences(item.id) }
      guard preferences.sound != item.id && !preferences.categorySounds.values.contains(item.id) else { return }
      try TaskNotificationSoundLibrary.removeImported(item); sounds = TaskNotificationSoundLibrary.sounds
    } catch { self.error = L10n.tr("无法删除铃声。") }
  }
  func removedClient(_ client: Client) async -> Bool {
    if client.isDemo { return true }
    let expected = target(client)
    queueRevocations { $0.target == expected }
    let pendingId = queueHostRevocation(client, removeCredentials: true)
    statuses.removeValue(forKey: client.id); synced.removeValue(forKey: client.id); registered.removeValue(forKey: client.id); cursors.removeValue(forKey: client.id)
    await drainRevocations()
    return pendingHostRevocations[pendingId] == nil
  }
  private func revokeAll() async {
    queueRevocations { _ in true }
    for host in store?.saved.clients.filter({ !$0.isDemo }) ?? [] { queueHostRevocation(host) }
    await drainRevocations()
  }
  @discardableResult private func queueHostRevocation(_ client: Client, removeCredentials: Bool = false) -> String {
    let expected = target(client)
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let data = (try? encoder.encode(expected)) ?? Data()
    let id = client.id + ":" + deviceId + ":" + SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    var pending = pendingHostRevocations
    pending[id] = TaskHostNotificationRevocation(client: client, deviceId: deviceId, target: expected, removeCredentials: removeCredentials || pending[id]?.removeCredentials == true)
    pendingHostRevocations = pending
    return id
  }
  private func queueRevocations(_ matches: (TaskNoticeBinding) -> Bool) {
    pendingRevocations.formUnion(TaskNotificationShared.bindings.filter { matches($0.value) }.keys)
  }
  private func drainRevocations() async {
    guard !revoking else { return }; revoking = true
    defer {
      revoking = false
      if active && (!pendingRevocations.isEmpty || !pendingHostRevocations.isEmpty) && revocationRetry == nil {
        revocationRetry = Task {
          try? await Task.sleep(for: .seconds(30))
          guard !Task.isCancelled else { return }
          revocationRetry = nil; await drainRevocations()
        }
      }
    }
    for id in pendingRevocations {
      guard let binding = TaskNotificationShared.bindings[id] else { pendingRevocations.remove(id); continue }
      guard let base = URL(string: binding.relayURL), base.scheme == "https" || base.host == "127.0.0.1" else { continue }
      do {
        var request = URLRequest(url: base.appendingPathComponent("v1/push/revoke")); request.httpMethod = "POST"; request.timeoutInterval = 10
        request.setValue("Bearer " + binding.managementToken, forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["bindingId": id])
        let (_, response) = try await revocationSession.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw HostServiceError.unavailable }
        var remaining = TaskNotificationShared.bindings; remaining.removeValue(forKey: id); try TaskNotificationShared.setBindings(remaining)
        pendingRevocations.remove(id)
      } catch { self.error = L10n.tr("部分电脑的通知撤销待联网同步。") }
    }
    for (id, pending) in pendingHostRevocations {
      do {
        try await AgentClients.resolve(pending.client).unregisterNotifications(pending.client, deviceId: pending.deviceId)
        var remaining = pendingHostRevocations; remaining.removeValue(forKey: id); pendingHostRevocations = remaining
        if pending.removeCredentials && store?.saved.clients.contains(where: { $0.id == pending.client.id }) != true {
          if let connection = pending.client.agentConnection { try? CredentialVault.set("", id: connection.credentialRef) }
          try? CredentialVault.set("", id: pending.client.id)
        }
        statuses[pending.client.id] = TaskNotificationStatus(version: 1, target: pending.target, delivery: "foreground-only", state: "disabled")
      } catch {
        statuses[pending.client.id] = TaskNotificationStatus(version: 1, target: pending.target, delivery: "foreground-only", state: "disabled", reason: L10n.tr("电脑订阅撤销待联网同步"))
      }
    }
  }
  private func route(_ event: TaskNotificationEvent) {
    guard let store else { pendingTap = event; return }
    guard let host = store.saved.clients.first(where: { target($0) == event.target }) else { error = L10n.tr("这条通知的连接已移除或身份已变化。"); return }
    store.openNotification(event, client: host)
  }
  func updateBadge() {
    snapshotRequested = true
    guard snapshotTask == nil else { return }
    snapshotTask = Task {
      defer { snapshotTask = nil }
      while snapshotRequested {
        snapshotRequested = false
        let snapshot = await Task.detached(priority: .utility) { MobileUnreadBadge.snapshot() }.value
        // Coalesce newer requests and defer publication until the drawer has settled.
        try? await store?.waitForSidebarInteraction()
        if snapshotRequested { continue }
        applyBadge(snapshot)
      }
    }
  }
  private func applyBadge(_ snapshot: MobileUnreadBadge.Snapshot) {
    let count = snapshot.count, revision = snapshot.revision
    if unreadSnapshot != snapshot { unreadSnapshot = snapshot }
    if unreadCount != count { unreadCount = count }
    if unreadRevision != revision { unreadRevision = revision }
    desiredBadge = preferences.badgeEnabled ? count : 0
    guard badgeTask == nil else { return }
    badgeTask = Task {
      defer { badgeTask = nil }
      while true {
        let value = desiredBadge
        try? await UNUserNotificationCenter.current().setBadgeCount(value)
        if desiredBadge == value { return }
      }
    }
  }
  func receiveBackground(_ info: [AnyHashable: Any]) -> UIBackgroundFetchResult {
    guard preferences.enabled, preferences.badgeEnabled,
      let event = try? TaskNotificationShared.decodeEnvelope(info),
      !preferences.excludedHosts.contains(event.target.hostId) else { return .noData }
    let before = MobileUnreadBadge.revision
    do { try MobileUnreadBadge.receive(event); updateBadge() }
    catch { return .failed }
    return before == MobileUnreadBadge.revision ? .noData : .newData
  }
  nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
    let prefs = TaskNotificationShared.preferences
    let info = notification.request.content.userInfo
    if info["businessNotification"] != nil {
      guard let (event, binding) = try? BusinessNotificationShared.decode(info), binding.preferences.kinds.contains(event.kind) else { completionHandler([]); return }
      var options: UNNotificationPresentationOptions = []
      if binding.preferences.foregroundBanner { options.formUnion([.banner, .list]) }
      if binding.preferences.sound != "none" { options.insert(.sound) }
      completionHandler(options); return
    }
    let event: TaskNotificationEvent?
    if let value = info["event"], let data = try? JSONSerialization.data(withJSONObject: value) { event = try? JSONDecoder().decode(TaskNotificationEvent.self, from: data) }
    else { event = try? TaskNotificationShared.decodeEnvelope(info) }
    if let event, prefs.enabled, !prefs.excludedHosts.contains(event.target.hostId) {
      Task { @MainActor in
        try? await self.store?.waitForSidebarInteraction()
        _ = try? MobileUnreadBadge.receive(event)
        self.updateBadge()
      }
    }
    if let event, !prefs.kinds.contains(event.kind) || prefs.excludedHosts.contains(event.target.hostId) { completionHandler([]); return }
    var options: UNNotificationPresentationOptions = []
    if prefs.enabled || notification.request.identifier == "notification-test" {
      if prefs.foregroundBanner { options.formUnion([.banner, .list]) }
      if prefs.soundEnabled { options.insert(.sound) }
      if prefs.badgeEnabled { options.insert(.badge) }
    }
    completionHandler(options)
  }
  nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
    let info = response.notification.request.content.userInfo
    if info["businessNotification"] != nil {
      if let (event, _) = try? BusinessNotificationShared.decode(info) { Task { @MainActor in TaskHubBusinessNotifications.shared.receiveTap(event) } }
      completionHandler(); return
    }
    let event: TaskNotificationEvent?
    if response.notification.request.identifier.hasPrefix("local."), let value = info["event"], let data = try? JSONSerialization.data(withJSONObject: value) { event = try? JSONDecoder().decode(TaskNotificationEvent.self, from: data) }
    else { event = try? TaskNotificationShared.decodeEnvelope(info) }
    if let event { Task { @MainActor in self.route(event) } }
    completionHandler()
  }
}

@MainActor
final class TaskNotificationAppDelegate: NSObject, UIApplicationDelegate {
  func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
    if !TaskHubUploadCoordinator.handleBackgroundEvents(identifier: identifier, completionHandler: completionHandler) { completionHandler() }
  }
  func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any], fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
    completionHandler(TaskNotificationController.shared.receiveBackground(userInfo))
  }
  func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) { TaskNotificationController.shared.receiveToken(deviceToken) }
  func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) { TaskNotificationController.shared.registrationFailed(error) }
}
