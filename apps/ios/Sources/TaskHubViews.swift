import PhotosUI
import QuickLook
import SwiftUI
import UniformTypeIdentifiers
import VisionKit

private struct TaskHubConflictKey: EnvironmentKey { static let defaultValue = false }
private extension EnvironmentValues {
  var taskHubConflict: Bool {
    get { self[TaskHubConflictKey.self] }
    set { self[TaskHubConflictKey.self] = newValue }
  }
}

// Registration supplies a synchronous save acknowledgement to the root.
// The later UI signal only dismisses an already acknowledged presentation.
private struct HubBusinessNoticeModalDismissal: ViewModifier {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var dismissOnCommit: Bool
  var beforeDismiss: () throws -> Void
  @Environment(\.dismiss) private var dismiss
  @State private var presentationId = UUID()
  @State private var presentationReadIdentity: String?
  @State private var consumed: UUID?

  func body(content: Content) -> some View {
    content
      .onAppear {
        if presentationReadIdentity == nil { presentationReadIdentity = store.notificationReadIdentity }
        if let presentationReadIdentity {
          // The closure reads mounted @State storage, not a frozen draft value.
          store.registerBusinessModal(presentationId, readIdentity: presentationReadIdentity, save: beforeDismiss)
        }
      }
      .onDisappear { store.unregisterBusinessModal(presentationId) }
      .onChange(of: store.businessModalDismissal?.id) { _, _ in
        guard dismissOnCommit, let navigation = store.businessModalDismissal, let presentationReadIdentity, consumed != navigation.id,
          store.canDismissBusinessModal(navigation, presentationId: presentationId, presentationReadIdentity: presentationReadIdentity) else { return }
        consumed = navigation.id
        dismiss()
      }
  }
}
private extension View {
  func taskHubDismissOnBusinessNavigation(_ store: TaskHubStore, beforeDismiss: @escaping () throws -> Void = {}) -> some View {
    modifier(HubBusinessNoticeModalDismissal(store: store, dismissOnCommit: true, beforeDismiss: beforeDismiss))
  }
  func taskHubPreserveOnBusinessNavigation(_ store: TaskHubStore, save: @escaping () throws -> Void) -> some View {
    modifier(HubBusinessNoticeModalDismissal(store: store, dismissOnCommit: false, beforeDismiss: save))
  }
}

private enum HubRoute: Hashable { case project(String), task(String) }

/// The business workspace owns its navigation without changing the active chat or Host.
struct TaskHubView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var openExecution: ((TaskHubExecutionNavigation) async throws -> Void)? = nil
  @StateObject private var discovery = TaskHubDiscovery()
  @ObservedObject private var notices = TaskHubBusinessNotifications.shared
  @Environment(\.dismiss) private var dismiss
  @Environment(\.scenePhase) private var scenePhase
  @State private var path: [HubRoute] = []
  @State private var showService = false
  private var noticeNavigationKey: String {
    (notices.pendingNavigation?.id ?? "") + "/" + store.notificationReadIdentity + "/" + String(store.needsLogin) + "/" + String(store.online)
  }

  private var conflict: Bool {
    var identities = Set(discovery.candidates.compactMap { $0.binding?.service.identity })
    if let selected = store.binding { identities.insert(selected.service.identity) }
    return identities.count > 1
  }

  var body: some View {
    NavigationStack(path: $path) {
      Group {
        if store.binding == nil {
          HubDiscoveryView(store: store, discovery: discovery)
        } else if store.needsLogin {
          HubLoginView(store: store)
        } else {
          HubProjectsView(store: store, open: openProject)
        }
      }
      .navigationTitle(L10n.tr("项目与任务"))
      .toolbar {
        HubKeyboardToolbar()
        ToolbarItem(placement: .topBarLeading) {
          Button(L10n.tr("返回"), systemImage: "chevron.backward") { dismiss() }
            .accessibilityIdentifier("task-hub-dismiss")
        }
        ToolbarItem(placement: .topBarTrailing) {
          Button(L10n.tr("服务与账户"), systemImage: "network") { showService = true }
            .accessibilityIdentifier("task-hub-service")
        }
      }
      .navigationDestination(for: HubRoute.self) { route in
        switch route {
        case .project(let id):
          HubTaskListView(store: store, projectId: id) { taskId in store.remember(projectId: id, taskId: taskId); path.append(.task(taskId)) }
        case .task(let id): HubTaskDetailView(store: store, taskId: id, openExecution: openExecution)
        }
      }
    }
    .environment(\.taskHubConflict, conflict)
    .sheet(isPresented: $showService) {
      NavigationStack {
        HubServiceView(store: store, discovery: discovery)
          .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("完成")) { showService = false } } }
      }
      .environment(\.taskHubConflict, conflict)
      .taskHubDismissOnBusinessNavigation(store)
    }
    .task {
      discovery.start()
      let scope = store.scope
      if !store.needsLogin { await store.refresh() }
      guard !Task.isCancelled, scope == store.scope, !store.needsLogin, path.isEmpty else { return }
      let projectId = store.bookmark?.projectId ?? store.draft("workspace/last-project")
      if store.projects.contains(where: { $0.id == projectId && !hubArchived($0) }) {
        path = [.project(projectId)]
        if let taskId = store.bookmark?.taskId, store.detail?.task.id == taskId { path.append(.task(taskId)) }
      }
    }
    .onDisappear { discovery.stop() }
    .task(id: noticeNavigationKey) {
      guard let navigation = notices.pendingNavigation, !store.needsLogin, store.actor?.kind == "human", store.online else { return }
      let readIdentity = store.notificationReadIdentity
      do {
        let validated = try await store.openBusinessNotice(navigation.event)
        try finishBusinessNoticeNavigation(validated, pendingId: navigation.id)
      } catch {
        if !Task.isCancelled, readIdentity == store.notificationReadIdentity, !(error is CancellationError) { store.error = error.localizedDescription }
      }
    }
    .onChange(of: scenePhase) { _, phase in
      if phase == .active { discovery.start(); Task { await store.refresh() } }
      else { discovery.stop() }
    }
    .onChange(of: store.scope) { _, _ in path = [] }
    .onChange(of: store.needsLogin) { _, needsLogin in if needsLogin { path = [] } }
    .onChange(of: path) { _, routes in
      guard !store.needsLogin, let project = routes.compactMap({ route -> String? in if case .project(let id) = route { return id }; return nil }).last else { return }
      let task = routes.compactMap { route -> String? in if case .task(let id) = route { return id }; return nil }.last
      let round = store.bookmark?.taskId == task ? store.bookmark?.roundId : store.detail?.rounds.first { $0.number == store.detail?.task.currentRound }?.id
      store.remember(projectId: project, taskId: task, roundId: task == nil ? nil : round)
    }
  }

  private func finishBusinessNoticeNavigation(_ validated: TaskHubValidatedBusinessNavigation, pendingId: String) throws {
    guard !Task.isCancelled, try store.commitBusinessNoticeNavigation(validated, isPending: {
      notices.pendingNavigation?.id == pendingId && notices.pendingNavigation?.event == validated.event
    }) else { return }
    path = [.project(validated.event.projectId), .task(validated.event.taskId)]
    notices.navigationConsumed(pendingId)
  }

  private func openProject(_ id: String) {
    store.saveDraft(id, key: "workspace/last-project")
    store.remember(projectId: id)
    path.append(.project(id))
  }
}

private struct HubNotice: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  var title: String
  var message: String = ""
  var symbol: String = "info.circle"
  var warning = false
  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      Label(title, systemImage: symbol).font(.headline)
        .foregroundStyle(warning ? Color.orange : Color.primary)
      if !message.isEmpty { Text(message).font(.subheadline).foregroundStyle(.secondary) }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .accessibilityElement(children: .combine)
  }
}

private struct HubConnectionSection: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  @Environment(\.taskHubConflict) private var conflict
  var body: some View {
    Section {
      Label(store.binding?.service.name ?? L10n.tr("未选择主服务"), systemImage: store.online ? "network" : "wifi.slash")
      if conflict {
        HubNotice(title: L10n.tr("发现不同主服务实例"), message: L10n.tr("请在服务设置核对身份。新提交与派发已暂停，原主服务保持绑定。"), symbol: "exclamationmark.shield", warning: true)
          .accessibilityIdentifier("task-hub-service-conflict")
      } else if !store.online {
        HubNotice(title: store.needsLogin ? L10n.tr("需要人类账号登录") : L10n.tr("主服务离线"), message: L10n.tr("可以查看已有缓存、编辑并保存草稿。连接原主服务后才能提交。"), symbol: "wifi.slash")
      }
      if let date = store.lastSync {
        LabeledContent(L10n.tr("最后同步")) { Text(date, format: .dateTime.month().day().hour().minute()).foregroundStyle(.secondary) }
      }
      if let error = store.error {
        Text(error).font(.subheadline).foregroundStyle(.red).textSelection(.enabled)
      }
      if !store.pendingOperations.isEmpty {
        NavigationLink { HubReceiptsView(store: store) } label: {
          Label(L10n.tr("\(store.pendingOperations.count) 项原操作待核对"), systemImage: "clock.arrow.circlepath")
        }.accessibilityIdentifier("task-hub-receipts")
      }
    }
  }
}

private struct HubDiscoveryView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  @ObservedObject var discovery: TaskHubDiscovery
  @State private var endpoint = ""
  @State private var manual: TaskHubBinding?
  @State private var inspected: TaskHubBinding?
  @State private var probing = false
  @State private var scanning = false
  @State private var error: String?

  var body: some View {
    List {
      Section {
        HubNotice(title: L10n.tr("选择任务主服务"), message: L10n.tr("手机与电脑共用一套任务库。发现服务后，核对电脑上的实例信息和证书指纹，再点选连接。"), symbol: "network")
        if let binding = store.binding {
          LabeledContent(L10n.tr("当前绑定"), value: binding.service.name)
          Text(binding.service.identity).font(.caption).textSelection(.enabled)
        }
      }
      Section(L10n.tr("当前网络发现")) {
        if let error = discovery.error { Text(error).foregroundStyle(.orange).accessibilityIdentifier("task-hub-discovery-error") }
        if discovery.searching { Label(L10n.tr("正在发现局域网服务"), systemImage: "antenna.radiowaves.left.and.right").foregroundStyle(.secondary) }
        ForEach(discovery.candidates) { candidate in
          Button {
            if let binding = candidate.binding { inspected = binding }
          } label: {
            VStack(alignment: .leading, spacing: 5) {
              Text(candidate.name).foregroundStyle(.primary)
              Text(candidate.endpoint).font(.caption).foregroundStyle(.secondary)
              if let error = candidate.error { Text(error).font(.caption).foregroundStyle(.red) }
              else { Text(candidate.binding == nil ? L10n.tr("核验中…") : L10n.tr("已探测 · 点按核对身份")).font(.caption).foregroundStyle(.secondary) }
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
          }.disabled(candidate.binding == nil)
            .accessibilityIdentifier("task-hub-candidate-" + candidate.id)
        }
        if discovery.candidates.isEmpty {
          Text(L10n.tr("尚未发现服务。请确认主电脑服务正在运行，并允许 App 访问本地网络。")).foregroundStyle(.secondary)
        }
        Button(L10n.tr("重新发现"), systemImage: "arrow.clockwise") { discovery.stop(); discovery.start() }
          .accessibilityIdentifier("task-hub-discover")
        Button(L10n.tr("打开系统设置"), systemImage: "gear") {
          if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        }
      }
      Section {
        TextField(L10n.tr("HTTPS 地址或配对 JSON"), text: $endpoint, axis: .vertical)
          .keyboardType(.URL).textContentType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
          .accessibilityIdentifier("task-hub-endpoint")
        Button(probing ? L10n.tr("正在探测…") : L10n.tr("探测 HTTPS 地址")) { Task { await probe() } }
          .disabled(probing || endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
          .accessibilityIdentifier("task-hub-probe")
        if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
          Button(L10n.tr("扫描主电脑配对二维码"), systemImage: "qrcode.viewfinder") { scanning = true }
            .accessibilityIdentifier("task-hub-scan")
        } else { Text(L10n.tr("此设备当前无法扫描，请粘贴主电脑提供的配对 JSON。")).font(.caption).foregroundStyle(.secondary) }
        if let manual { Button(L10n.tr("核对 \(manual.service.name)")) { inspected = manual } }
        if let error { Text(error).foregroundStyle(.red).font(.subheadline) }
      } header: { Text(L10n.tr("手动探测")) } footer: {
        Text(L10n.tr("只接受可信私网 HTTPS 服务。地址变化不会自动切换数据归属；未发现主服务时请在原主电脑恢复服务。"))
      }
    }
    .sheet(item: Binding(item: $inspected)) { binding in
      NavigationStack { HubBindingReview(store: store, binding: binding) }
    }
    .sheet(isPresented: $scanning) {
      NavigationStack {
        EasyWorkQRScanner(scanned: { value in endpoint = value; scanning = false; Task { await probe() } }, prefix: "{")
          .ignoresSafeArea(edges: .bottom).navigationTitle(L10n.tr("扫描任务主服务"))
          .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("取消")) { scanning = false } } }
      }
    }
  }

  private func probe() async {
    probing = true; error = nil; manual = nil
    defer { probing = false }
    do {
      let text = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
      manual = text.hasPrefix("{") ? try await TaskHubPairing.probe(text) : try await TaskHubClient.probe(endpoint: text)
      inspected = manual
    }
    catch { self.error = error.localizedDescription }
  }
}

// A wrapper keeps TaskHubBinding's domain conformance untouched.
private struct HubBindingItem: Identifiable { var value: TaskHubBinding; var id: String { value.service.identity + value.endpoint } }
private extension Binding where Value == HubBindingItem? {
  init(item: Binding<TaskHubBinding?>) {
    self.init(get: { item.wrappedValue.map { HubBindingItem(value: $0) } }, set: { item.wrappedValue = $0?.value })
  }
}

private struct HubBindingReview: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let binding: HubBindingItem
  @Environment(\.dismiss) private var dismiss
  @State private var checked = false
  @State private var connecting = false
  @State private var error: String?
  private var different: Bool { store.binding.map { $0.service.identity != binding.value.service.identity } ?? false }
  var body: some View {
    Form {
      HubServiceIdentity(binding: binding.value)
      Section {
        if different {
          HubNotice(title: L10n.tr("与原主服务身份不同"), message: L10n.tr("此实例不能替代已绑定任务库。请核对主电脑登记及原主服务；当前绑定保持不变。"), symbol: "exclamationmark.shield", warning: true)
        } else {
          Toggle(L10n.tr("已与主电脑核对实例和证书指纹"), isOn: $checked).accessibilityIdentifier("task-hub-trust-confirmation")
          Button(connecting ? L10n.tr("连接中…") : L10n.tr("连接此主服务")) {
            Task {
              connecting = true
              defer { connecting = false }
              do { try await store.choose(binding.value); dismiss() }
              catch { self.error = error.localizedDescription }
            }
          }.disabled(!checked || connecting).accessibilityIdentifier("task-hub-connect")
        }
        if let error { Text(error).foregroundStyle(.red) }
      }
    }.navigationTitle(L10n.tr("核对主服务"))
      .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("取消")) { dismiss() } } }
  }
}

private struct HubServiceIdentity: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let binding: TaskHubBinding
  var body: some View {
    Section(L10n.tr("服务身份")) {
      LabeledContent(L10n.tr("名称"), value: binding.service.name)
      HubValue(label: L10n.tr("HTTPS 地址"), value: binding.endpoint)
      HubValue(label: L10n.tr("部署 ID"), value: binding.service.deploymentId)
      HubValue(label: L10n.tr("实例 ID"), value: binding.service.serverInstanceId)
      HubValue(label: L10n.tr("主电脑"), value: binding.service.primaryHostId)
      HubValue(label: L10n.tr("证书 SHA-256"), value: binding.fingerprint)
        .accessibilityIdentifier("task-hub-certificate-fingerprint")
      DisclosureGroup(L10n.tr("已声明能力")) { ForEach(binding.service.capabilities, id: \.self) { Text($0).font(.caption) } }
    }
  }
}

private struct HubLoginView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  @Environment(\.taskHubConflict) private var conflict
  @State private var username = ""
  @State private var password = ""
  @State private var visible = false
  @State private var submitting = false
  @State private var error: String?
  var body: some View {
    Form {
      Section {
        HubNotice(title: L10n.tr("登录人类成员账户"), message: L10n.tr("\(store.binding?.service.name ?? L10n.tr("已选主服务")) · 设备会话由系统安全保存。"))
        TextField(L10n.tr("账号"), text: $username).textContentType(.username)
          .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("task-hub-username")
        Group {
          if visible { TextField(L10n.tr("密码"), text: $password) }
          else { SecureField(L10n.tr("密码"), text: $password) }
        }.textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
          .accessibilityIdentifier("task-hub-password")
        Toggle(L10n.tr("显示密码"), isOn: $visible)
        Button(submitting ? L10n.tr("登录中…") : L10n.tr("登录")) {
          Task {
            submitting = true; error = nil
            defer { submitting = false }
            do { try await store.login(username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password); password = "" }
            catch { self.error = error.localizedDescription }
          }
        }.disabled(submitting || username.isEmpty || password.isEmpty || conflict)
          .accessibilityIdentifier("task-hub-login")
      }
      if conflict { Section { HubNotice(title: L10n.tr("请先核对主服务冲突"), message: L10n.tr("发现多个实例，登录已暂停。"), warning: true) } }
      if let error { Section { Text(error).foregroundStyle(.red) } }
    }
    .onAppear {
      #if DEBUG
      let process = ProcessInfo.processInfo
      if process.arguments.contains("-ui-testing") && process.arguments.contains("-task-hub-acceptance") {
        password = process.environment["TASK_HUB_DEV_PASSWORD"] ?? ""
      }
      #endif
    }
  }
}

private struct HubServiceView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  @ObservedObject var discovery: TaskHubDiscovery
  @State private var logout = false
  @State private var error: String?
  var body: some View {
    List {
      HubConnectionSection(store: store)
      if let binding = store.binding { HubServiceIdentity(binding: binding) }
      Section(L10n.tr("连接与账户")) {
        Button(L10n.tr("重连原主服务"), systemImage: "arrow.clockwise") { Task { await store.refresh() } }
          .disabled(store.needsLogin).accessibilityIdentifier("task-hub-reconnect")
        NavigationLink(L10n.tr("发现与核对服务")) { HubDiscoveryView(store: store, discovery: discovery).navigationTitle(L10n.tr("发现主服务")) }
        if let actor = store.actor {
          LabeledContent(L10n.tr("人类账户"), value: actor.name)
          LabeledContent(L10n.tr("角色"), value: store.isAdmin ? L10n.tr("管理员") : L10n.tr("成员"))
          if store.binding?.service.capabilities.contains("mobile-device-sessions") == true {
            NavigationLink(L10n.tr("登录设备与会话")) { HubDeviceSessionsView(store: store) }
              .accessibilityIdentifier("task-hub-device-sessions")
          }
          Button(L10n.tr("退出此设备登录"), role: .destructive) { logout = true }.accessibilityIdentifier("task-hub-logout")
        }
        NavigationLink(L10n.tr("原操作记录")) { HubReceiptsView(store: store) }
        NavigationLink(L10n.tr("任务 AI 数据共享")) { TaskHubConsentSettingsView(store: store) }
          .accessibilityIdentifier("task-hub-ai-sharing-settings")
        if store.binding?.service.capabilities.contains("business-notification-feed") == true {
          NavigationLink(L10n.tr("业务待办与通知")) { HubBusinessNoticesView(store: store) }
            .accessibilityIdentifier("task-hub-business-notices")
        }
        if let error { Text(error).foregroundStyle(.red) }
      }
    }.navigationTitle(L10n.tr("主服务与账户"))
      .alert(L10n.tr("退出当前设备登录？"), isPresented: $logout) {
        Button(L10n.tr("退出登录"), role: .destructive) {
          Task { do { try await store.logout() } catch { self.error = error.localizedDescription } }
        }.accessibilityIdentifier("task-hub-confirm-logout")
        Button(L10n.tr("取消"), role: .cancel) { }
      } message: { Text(L10n.tr("该账户草稿和原操作记录保留在原服务与账户范围内。")) }
  }
}

private struct HubBusinessNoticesView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var body: some View {
    List {
      HubConnectionSection(store: store)
      Section(L10n.tr("业务提醒")) {
        ForEach(store.businessNotices, id: \.eventId) { event in
          Button { TaskHubBusinessNotifications.shared.receiveTap(event) } label: {
            VStack(alignment: .leading, spacing: 6) {
              Text(event.kind.title).font(.headline)
              Text(event.title ?? L10n.tr("打开原任务核对")).font(.subheadline)
              Text(hubDate(event.occurredAt)?.formatted(Date.FormatStyle(date: .numeric, time: .shortened).locale(L10n.locale)) ?? event.occurredAt).font(.caption).foregroundStyle(.secondary)
            }.foregroundStyle(.primary).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
          }.buttonStyle(.plain).accessibilityIdentifier("task-hub-business-notice-" + event.eventId)
        }
        if store.businessNotices.isEmpty { Text(L10n.tr("暂无已同步的业务提醒。")).foregroundStyle(.secondary) }
      }
      Section(L10n.tr("项目通知设置")) {
        ForEach(store.projects.filter { !hubArchived($0) }) { project in
          NavigationLink(project.name) { HubBusinessNotificationSettings(store: store, project: project) }
        }
      }
    }.navigationTitle(L10n.tr("业务待办与通知")).refreshable { await store.refresh() }
  }
}

private struct HubBusinessNotificationSettings: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let project: TaskHubProject
  @ObservedObject private var notices = TaskHubBusinessNotifications.shared
  @ObservedObject private var system = TaskNotificationController.shared
  @State private var preferences = BusinessNotificationPreferences()
  @State private var error: String?
  @State private var saving = false
  private var channelLabel: String {
    if notices.bindingCleanupError != nil { return L10n.tr("本地通知绑定尚未确认") }
    guard store.canReadNotificationStatus else { return L10n.tr("待连接原主服务核对") }
    if notices.refreshingProjects.contains(project.id) { return L10n.tr("正在核对系统通知通道") }
    guard let status = notices.statuses[project.id] else { return L10n.tr("通知设置尚待核对") }
    if notices.refreshMessages[project.id] != nil { return L10n.tr("通道仍待核对，可前台补齐") }
    switch status.state {
    case "ready": return status.delivery == "apns" ? L10n.tr("系统推送通道已就绪") : L10n.tr("通道仍待核对")
    case "pending": return L10n.tr("系统推送通道准备中，可前台补齐")
    case "disabled": return L10n.tr("项目通知已关闭")
    default: return L10n.tr("系统推送暂不可用，可前台补齐")
    }
  }
  var body: some View {
    Form {
      Section {
        Text(project.name).font(.headline)
        LabeledContent(L10n.tr("系统权限"), value: system.authorization)
        if !system.preferences.enabled {
          Button(L10n.tr("开启 App 通知权限")) { system.enable(true) }.accessibilityIdentifier("task-hub-enable-system-notifications")
        }
        Toggle(L10n.tr("接收此项目业务通知"), isOn: $preferences.enabled).accessibilityIdentifier("task-hub-notifications-enabled")
        LabeledContent(L10n.tr("当前通道"), value: channelLabel)
        if let failure = notices.bindingCleanupError { Text(failure).font(.caption).foregroundStyle(.red) }
        if let message = notices.refreshMessages[project.id] { Text(message).font(.caption).foregroundStyle(.secondary) }
        if let status = notices.statuses[project.id] {
          if let reason = status.reason { Text(reason).font(.caption).foregroundStyle(.secondary) }
          if let last = status.lastDelivery { Text(L10n.tr("最近投递：\(last.state)")).font(.caption).foregroundStyle(.secondary) }
        }
        Text(L10n.tr("手机回到前台会核对原任务和原操作。通知点击只定位服务、账户、任务及原轮次。")).font(.caption).foregroundStyle(.secondary)
      }
      Section(L10n.tr("提醒内容")) {
        ForEach(BusinessNoticeKind.allCases, id: \.rawValue) { kind in
          Toggle(kind.title, isOn: Binding(get: { preferences.kinds.contains(kind) }, set: { value in
            preferences.kinds.removeAll { $0 == kind }; if value { preferences.kinds.append(kind) }
          }))
        }
        Toggle(L10n.tr("显示任务名称和摘要"), isOn: $preferences.showPreview)
        Toggle(L10n.tr("前台显示横幅"), isOn: $preferences.foregroundBanner)
        Picker(L10n.tr("提醒声音"), selection: $preferences.sound) { Text(L10n.tr("系统默认")).tag("default"); Text(L10n.tr("静音")).tag("none") }
      }
      Section {
        Button(saving ? L10n.tr("正在同步…") : L10n.tr("保存并核对通知通道")) {
          Task {
            let identity = store.notificationReadIdentity
            saving = true; defer { saving = false }
            do {
              var next = preferences; next.generation += 1
              preferences = next
              let actual = Bundle.main.object(forInfoDictionaryKey: "NotificationEnvironment") as? String
              let environment = ["development", "production"].contains(actual ?? "") ? actual : nil
              try await store.synchronizeNotifications(projectId: project.id, preferences: next, environment: environment)
              guard identity == store.notificationReadIdentity, !Task.isCancelled else { return }
              error = nil
            } catch {
              guard identity == store.notificationReadIdentity, !Task.isCancelled else { return }
              self.error = error.localizedDescription
            }
          }
        }.disabled(saving || !store.canWrite || preferences.enabled && !system.preferences.enabled)
          .accessibilityIdentifier("task-hub-save-notification-settings")
        Button(L10n.tr("刷新通道")) { store.refreshNotificationStatus(projectId: project.id) }
          .disabled(!store.canReadNotificationStatus || saving || notices.refreshingProjects.contains(project.id))
          .accessibilityIdentifier("task-hub-refresh-notification-status")
        if let error { Text(error).foregroundStyle(.red) }
      }
    }.navigationTitle(L10n.tr("项目业务通知"))
      .task(id: store.notificationReadIdentity + "/" + project.id) {
        let identity = store.notificationReadIdentity
        preferences = store.notificationPreferences(project.id)
        await system.refreshPermission()
        guard identity == store.notificationReadIdentity, !Task.isCancelled else { return }
        store.refreshNotificationStatus(projectId: project.id)
      }
  }
}

private struct HubDeviceSessionsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  @State private var sessions: [TaskHubDeviceSession] = []
  @State private var selected: TaskHubDeviceSession?
  @State private var loading = false
  @State private var error: String?
  var body: some View {
    List {
      Section { Text(L10n.tr("会话属于当前主服务和人类账户。会话过期后需要重新登录；撤销设备会话会停止该设备继续读取和提交。")).font(.subheadline) }
      ForEach(sessions) { session in
        Section {
          Text(session.deviceName).font(.headline)
          if session.current { Label(L10n.tr("当前设备会话"), systemImage: "iphone") }
          HubValue(label: L10n.tr("状态"), value: ["active":L10n.tr("有效"), "expired":L10n.tr("已过期"), "revoked":L10n.tr("已撤销")][session.status] ?? session.status)
          HubValue(label: L10n.tr("到期时间"), value: hubDate(session.expiresAt)?.formatted(Date.FormatStyle(date: .numeric, time: .shortened).locale(L10n.locale)) ?? session.expiresAt)
          if session.status == "active" {
            Button(L10n.tr("撤销此会话"), role: .destructive) { selected = session }
              .disabled(loading || !store.online).accessibilityIdentifier("task-hub-revoke-session-" + session.id)
          }
        }
      }
      if loading { ProgressView(L10n.tr("正在核对设备会话")) }
      if let error { Text(error).foregroundStyle(.red) }
    }.navigationTitle(L10n.tr("登录设备与会话"))
      .task { await load() }.refreshable { await load() }
      .alert(L10n.tr("撤销此设备会话？"), isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } })) {
        Button(L10n.tr("撤销会话"), role: .destructive) {
          guard let selected else { return }
          Task {
            do { try await store.revokeDeviceSession(selected.id); await load() }
            catch { self.error = error.localizedDescription }
            self.selected = nil
          }
        }
        Button(L10n.tr("取消"), role: .cancel) { selected = nil }
      } message: { Text(selected?.current == true ? L10n.tr("当前设备将需要重新登录。原草稿与原操作记录继续保留。") : L10n.tr("此设备下次访问需要重新登录。")) }
  }
  private func load() async {
    guard !store.needsLogin else { sessions = []; return }
    loading = true; defer { loading = false }
    let identity = store.scope
    do {
      let result = try await store.read([TaskHubDeviceSession].self, path: "auth/mobile/sessions")
      guard identity == store.scope else { return }; sessions = result; error = nil
    } catch { self.error = error.localizedDescription }
  }
}

private struct HubValue: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let label: String
  let value: String
  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      Text(label).font(.caption).foregroundStyle(.secondary)
      Text(value.isEmpty ? L10n.tr("未提供") : value).textSelection(.enabled)
    }.frame(maxWidth: .infinity, alignment: .leading)
  }
}

private struct HubProjectsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var open: (String) -> Void
  @State private var query = ""
  @State private var showArchived = false
  @State private var create = false
  private var projects: [TaskHubProject] {
    store.projects.filter {
      (showArchived || !hubArchived($0)) && (query.isEmpty || ($0.name + $0.key + $0.description).localizedCaseInsensitiveContains(query))
    }
  }
  var body: some View {
    List {
      HubConnectionSection(store: store)
      Section(L10n.tr("业务项目")) {
        ForEach(projects) { project in
          Button { open(project.id) } label: {
            VStack(alignment: .leading, spacing: 5) {
              HStack { Text(project.name).font(.headline); if hubArchived(project) { Text(L10n.tr("已归档")).font(.caption).foregroundStyle(.secondary) } }
              Text(project.key).font(.caption).foregroundStyle(.secondary)
              if !project.description.isEmpty { Text(project.description).font(.subheadline).foregroundStyle(.secondary).lineLimit(2) }
            }.foregroundStyle(.primary).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
          }.accessibilityIdentifier("task-hub-project-" + project.id)
        }
        if projects.isEmpty {
          ContentUnavailableView(query.isEmpty ? L10n.tr("暂无项目") : L10n.tr("没有匹配项目"), systemImage: "folder", description: Text(query.isEmpty ? L10n.tr("连接主服务后创建业务项目。") : L10n.tr("尝试其他名称或清除搜索。")))
        }
      }
      Section { Toggle(L10n.tr("显示归档项目"), isOn: $showArchived) }
    }.searchable(text: $query, prompt: L10n.tr("搜索业务项目"))
      .refreshable { await store.refresh() }
      .toolbar {
        if store.isAdmin {
          ToolbarItem(placement: .topBarTrailing) {
            Button(L10n.tr("创建项目"), systemImage: "folder.badge.plus") { create = true }.accessibilityIdentifier("task-hub-project-create")
          }
        }
      }
      .sheet(isPresented: $create) { NavigationStack { HubProjectForm(store: store, project: nil) }.taskHubDismissOnBusinessNavigation(store) }
  }
}

private func hubArchived(_ project: TaskHubProject) -> Bool { project.archived == true || project.archivedAt != nil }
@MainActor private func hubActor(_ id: String, _ store: TaskHubStore) -> String { store.actors.first { $0.id == id }?.name ?? id }
@MainActor private func hubHost(_ id: String?, _ store: TaskHubStore) -> String { guard let id else { return L10n.tr("未指定电脑") }; return store.hosts.first { $0.id == id }?.name ?? id }
private func hubStage(_ value: String?) -> String {
  guard let value else { return L10n.tr("服务未提供细分阶段") }
  return ["requirements":L10n.tr("需求响应"), "development":L10n.tr("开发中"), "acceptance":L10n.tr("验收中"), "closing":L10n.tr("验收已通过 · 待最终交付"), "completed":L10n.tr("已完成"), "legacy":L10n.tr("历史流程")] [value] ?? value
}
private func hubPhase(_ value: String) -> String {
  ["pending":L10n.tr("等待回执"), "unknown":L10n.tr("结果未知"), "confirmed":L10n.tr("已确认"), "rejected":L10n.tr("已拒绝"), "queued":L10n.tr("已排队"), "prepared":L10n.tr("已准备"), "submitted":L10n.tr("已提交"), "running":L10n.tr("运行中"), "completed":L10n.tr("执行已结束"), "uncertain":L10n.tr("结果待核对"), "blocked":L10n.tr("被阻塞"), "failed":L10n.tr("失败"), "superseded":L10n.tr("输入已被接续"), "online":L10n.tr("在线"), "offline":L10n.tr("离线"), "needs_upgrade":L10n.tr("需要升级"), "revoked":L10n.tr("已撤销"), "unverified":L10n.tr("未核验"), "passed":L10n.tr("通过")] [value] ?? value
}

private struct HubListPreferences: Codable {
  var query = ""
  var status = ""
  var scope = "all"
  var board = false
  var column = "todo"
  var ownerId: String?
}

private struct HubTaskListView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let projectId: String
  var open: (String) -> Void
  @Environment(\.dynamicTypeSize) private var typeSize
  @State private var preferences = HubListPreferences()
  @State private var create = false
  @State private var settings = false
  @State private var ready = false
  @State private var loading = false
  @State private var draftScope = ""
  private var project: TaskHubProject? { store.projects.first { $0.id == projectId } }
  private var filterKey: String { preferences.query + "/" + preferences.scope + "/" + preferences.status + "/" + String(preferences.board) + "/" + preferences.column + "/" + (preferences.ownerId ?? "") }

  var body: some View {
    List {
      HubConnectionSection(store: store)
      Section {
        scopePicker
        if preferences.scope != "mine" {
          Picker(L10n.tr("负责人"), selection: Binding(get: { preferences.ownerId ?? "" }, set: { preferences.ownerId = $0 })) {
            Text(L10n.tr("全部负责人")).tag("")
            ForEach(store.actors) { actor in Text(actor.name).tag(actor.id) }
          }.accessibilityIdentifier("task-hub-owner-filter")
        }
        Picker(L10n.tr("显示方式"), selection: $preferences.board) { Text(L10n.tr("列表")).tag(false); Text(L10n.tr("看板")).tag(true) }
          .pickerStyle(.segmented).accessibilityIdentifier("task-hub-layout")
        if preferences.board { boardPicker }
        else {
          Picker(L10n.tr("状态"), selection: $preferences.status) {
            Text(L10n.tr("全部状态")).tag("")
            ForEach(["todo", "doing", "review", "done"], id: \.self) { Text(statusTitle($0)).tag($0) }
          }.accessibilityIdentifier("task-hub-status-filter")
        }
      }
      Section {
        ForEach(store.tasks.filter { $0.projectId == projectId }) { task in
          Button { open(task.id) } label: { HubTaskRow(task: task, store: store) }
            .buttonStyle(.plain).accessibilityIdentifier("task-hub-task-" + task.id)
        }
        if store.tasks.isEmpty && !loading {
          ContentUnavailableView(L10n.tr("暂无匹配任务"), systemImage: "checklist", description: Text(L10n.tr("可以清除筛选，或在此项目创建任务。")))
          Button(L10n.tr("清除筛选")) { preferences.query = ""; preferences.scope = "all"; preferences.status = ""; preferences.ownerId = nil }
        }
        if loading { ProgressView(L10n.tr("正在同步任务")) }
        if store.tasks.count < store.total {
          Button(L10n.tr("加载更多（已显示 \(store.tasks.count) / \(store.total)）")) {
            Task { loading = true; await store.loadTasks(page: store.page + 1); loading = false }
          }.disabled(loading || !store.online).accessibilityIdentifier("task-hub-load-more")
        }
      } header: { Text(preferences.board ? statusTitle(preferences.column) + " · \(store.total)" : L10n.tr("任务 · \(store.total)")) }
      if let project, hubArchived(project) { Section { HubNotice(title: L10n.tr("项目已归档"), message: L10n.tr("现有任务可以查看。创建和管理操作不可用。"), symbol: "archivebox") } }
    }
    .navigationTitle(project?.name ?? L10n.tr("项目任务"))
    .searchable(text: $preferences.query, prompt: L10n.tr("搜索编号、标题或内容"))
    .toolbar {
      ToolbarItemGroup(placement: .topBarTrailing) {
        Button(L10n.tr("项目设置"), systemImage: "gearshape") { settings = true }.accessibilityIdentifier("task-hub-project-settings")
        Button(L10n.tr("新建任务"), systemImage: "plus") { create = true }
          .disabled(project.map(hubArchived) ?? false).accessibilityIdentifier("task-hub-create")
      }
    }
    .refreshable { await store.refresh(); await reload() }
    .task {
      preferences = hubRestore(store.draft("workspace/" + projectId), default: HubListPreferences())
      draftScope = store.scope; store.projectId = projectId; ready = true; await reload()
    }
    .task(id: filterKey) {
      guard ready else { return }
      do { try await Task.sleep(for: .milliseconds(280)); try Task.checkCancellation(); await reload() } catch { }
    }
    .onDisappear { if draftScope == store.scope { store.saveDraft(hubEncode(preferences), key: "workspace/" + projectId) } }
    .sheet(isPresented: $create) {
      NavigationStack {
        HubTaskForm(store: store, projectId: projectId, action: .create, snapshot: nil) { id in create = false; open(id) }
      }
    }
    .sheet(isPresented: $settings) { NavigationStack { HubProjectSettings(store: store, projectId: projectId) }.taskHubDismissOnBusinessNavigation(store) }
  }

  @ViewBuilder private var scopePicker: some View {
    Picker(L10n.tr("任务范围"), selection: $preferences.scope) {
      Text(L10n.tr("全部")).tag("all"); Text(L10n.tr("我负责")).tag("mine"); Text(L10n.tr("待我处理")).tag("pending")
    }.pickerStyle(.segmented).accessibilityIdentifier("task-hub-scope-filter")
  }
  @ViewBuilder private var boardPicker: some View {
    if typeSize.isAccessibilitySize { boardOptions.pickerStyle(.menu) }
    else { boardOptions.pickerStyle(.segmented) }
  }
  private var boardOptions: some View {
    Picker(L10n.tr("看板状态"), selection: $preferences.column) {
      ForEach(["todo", "doing", "review", "done"], id: \.self) { key in
        Text(statusTitle(key) + " \(store.statusCounts[key] ?? 0)").tag(key)
      }
    }.accessibilityIdentifier("task-hub-board-column")
  }
  private func statusTitle(_ value: String) -> String { ["todo":L10n.tr("待处理"), "doing":L10n.tr("进行中"), "review":L10n.tr("待验收"), "done":L10n.tr("已完成")][value] ?? value }
  private func reload() async {
    store.projectId = projectId; store.query = preferences.query; store.scopeFilter = preferences.scope
    store.ownerFilter = preferences.ownerId ?? ""
    store.status = preferences.board ? preferences.column : preferences.status
    loading = true; await store.projectChanged(); loading = false
    if draftScope == store.scope { store.saveDraft(hubEncode(preferences), key: "workspace/" + projectId) }
  }
}

private struct HubTaskRow: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let task: TaskHubTask
  @ObservedObject var store: TaskHubStore
  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      ViewThatFits(in: .horizontal) {
        HStack { Text(task.key).font(.caption).foregroundStyle(.secondary); Text(task.priority).font(.caption); Spacer(); Text(task.statusTitle).font(.subheadline).foregroundStyle(task.status == "done" ? Color.green : Color.secondary) }
        VStack(alignment: .leading) { Text(task.key + " · " + task.priority).font(.caption); Text(task.statusTitle).font(.subheadline) }
      }
      Text(task.title).font(.headline).foregroundStyle(.primary).multilineTextAlignment(.leading)
      Text(["feature":L10n.tr("功能"), "bug":L10n.tr("缺陷"), "chore":L10n.tr("维护")][task.type] ?? task.type).font(.caption).foregroundStyle(.secondary)
      Text(hubActor(task.ownerId, store) + L10n.tr(" · 第 \(task.currentRound) 轮")).font(.subheadline).foregroundStyle(.secondary)
      Text(hubStage(task.workflowStage)).font(.caption).foregroundStyle(.secondary)
      if let host = task.hostId { Text(hubHost(host, store)).font(.caption).foregroundStyle(.secondary) }
      if task.pendingResponses > 0 { Label(L10n.tr("\(task.pendingResponses) 条待响应"), systemImage: "text.bubble").font(.caption).foregroundStyle(.orange) }
    }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
      .accessibilityElement(children: .combine)
  }
}

private struct HubReceiptsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  @State private var querying: String?
  @State private var error: String?
  var body: some View {
    List {
      Section {
        HubNotice(title: L10n.tr("保留原操作，不重新提交"), message: L10n.tr("结果未知时查询相同操作 ID。返回工作区只离开此页面，原意图、附件和回执继续保留，远端执行不会被取消。"), symbol: "clock.arrow.circlepath")
        if let error { Text(error).foregroundStyle(.red) }
      }
      ForEach(store.operations.sorted { $0.submittedAt > $1.submittedAt }) { operation in
        Section {
          HubValue(label: L10n.tr("操作"), value: operation.method + " " + operation.path)
          LabeledContent(L10n.tr("状态"), value: hubPhase(operation.phase))
          HubValue(label: L10n.tr("原操作 ID"), value: operation.id)
          Text(operation.submittedAt, format: .dateTime.month().day().hour().minute()).font(.caption).foregroundStyle(.secondary)
          if let message = operation.message { Text(message).font(.subheadline).foregroundStyle(.red) }
          DisclosureGroup(L10n.tr("不可变提交内容")) {
            if let object = try? JSONDecoder().decode(TaskHubJSON.self, from: operation.body) {
              Text(hubPretty(object)).font(.caption.monospaced()).textSelection(.enabled)
            } else { Text(L10n.tr("附件上传正文 · \(operation.body.count) 字节")).font(.caption) }
          }
          if ["pending", "unknown"].contains(operation.phase) {
            Button(querying == operation.id ? L10n.tr("正在查询…") : L10n.tr("查询原回执")) { Task { await query(operation.id) } }
              .disabled(querying != nil || store.needsLogin)
              .accessibilityIdentifier("task-hub-receipt-" + operation.id)
            if store.activeOperationId == operation.id {
              Button(L10n.tr("结束当前等待")) { store.endWaiting(operation.id) }
                .accessibilityIdentifier("task-hub-end-waiting")
              Text(L10n.tr("只结束手机等待，不取消远端。原操作 ID、正文和附件继续保留。")).font(.caption).foregroundStyle(.secondary)
            }
          }
        }
      }
      if store.operations.isEmpty { ContentUnavailableView(L10n.tr("暂无已提交操作"), systemImage: "clock.arrow.circlepath") }
    }.navigationTitle(L10n.tr("原操作回执"))
  }
  private func query(_ id: String) async {
    querying = id; error = nil; defer { querying = nil }
    do { try await store.queryReceipt(id) } catch { self.error = error.localizedDescription }
  }
}

private struct HubAttachmentList: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let attachments: [TaskHubAttachment]
  @State private var preview: URL?
  @State private var downloading: String?
  @State private var error: String?
  @State private var files: [URL] = []
  var body: some View {
    ForEach(attachments) { attachment in
      Button { Task { await download(attachment) } } label: {
        HStack(alignment: .top) {
          HubRemoteThumbnail(store: store, attachment: attachment)
          VStack(alignment: .leading, spacing: 4) {
            Text(attachment.name).foregroundStyle(.primary)
            Text(ByteCountFormatter.string(fromByteCount: Int64(attachment.sizeBytes), countStyle: .file)).font(.caption).foregroundStyle(.secondary)
          }
          Spacer()
          if downloading == attachment.id { ProgressView() }
          else { Image(systemName: "arrow.down.circle").foregroundStyle(.secondary) }
        }.frame(minHeight: 44)
      }.disabled(!store.online || downloading != nil)
        .accessibilityLabel(L10n.tr("下载并预览 ") + attachment.name)
        .accessibilityIdentifier("task-hub-attachment-" + attachment.id)
    }
    .quickLookPreview($preview)
    if let error { Text(error).font(.subheadline).foregroundStyle(.red) }
    if let preview { ShareLink(item: preview) { Label(L10n.tr("分享已下载附件"), systemImage: "square.and.arrow.up") } }
    Color.clear.frame(height: 0).onDisappear {
      preview = nil
      for file in files { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
      files = []
    }
  }
  private func download(_ attachment: TaskHubAttachment) async {
    downloading = attachment.id; error = nil
    let scope = store.scope
    defer { downloading = nil }
    do {
      let bytes = try await store.attachmentData(attachment)
      guard scope == store.scope else { throw TaskHubError.changedIdentity }
      let directory = FileManager.default.temporaryDirectory.appendingPathComponent("task-hub-preview-" + UUID().uuidString, isDirectory: true)
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      let filename = URL(fileURLWithPath: attachment.name).lastPathComponent
      let file = directory.appendingPathComponent(filename.isEmpty ? "attachment" : filename)
      try bytes.write(to: file, options: [.atomic, .completeFileProtection])
      files.append(file); preview = file
    } catch { self.error = error.localizedDescription }
  }
}

private struct HubRemoteThumbnail: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var attachment: TaskHubAttachment
  @State private var image: UIImage?
  var body: some View {
    Group {
      if let image { Image(uiImage: image).resizable().scaledToFill().frame(width: 44, height: 44).clipped().clipShape(RoundedRectangle(cornerRadius: 6)) }
      else { Image(systemName: attachment.mediaType.hasPrefix("image/") ? "photo" : "doc").frame(width: 44, height: 44) }
    }.accessibilityHidden(true)
      .task(id: store.scope + "/" + attachment.id + "/" + String(store.online)) {
        image = nil; let identity = store.scope
        guard let bytes = try? await store.thumbnailData(attachment), identity == store.scope, bytes.count <= 2 * 1024 * 1024 else { return }
        image = UIImage(data: bytes)
      }
  }
}

private func hubEncode<T: Encodable>(_ value: T) -> String { (try? JSONEncoder().encode(value)).map { String(decoding: $0, as: UTF8.self) } ?? "" }
private func hubRestore<T: Decodable>(_ string: String, default fallback: T) -> T { (try? JSONDecoder().decode(T.self, from: Data(string.utf8))) ?? fallback }
private func hubPretty(_ value: TaskHubJSON) -> String {
  let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
  return (try? encoder.encode(value)).map { String(decoding: $0, as: UTF8.self) } ?? ""
}
private extension TaskHubJSON {
  var hubInt: Int? { if case .number(let value) = self { return Int(value) }; return nil }
  var hubAny: Any {
    switch self {
    case .object(let value): return value.mapValues { $0.hubAny }
    case .array(let value): return value.map { $0.hubAny }
    case .string(let value): return value
    case .number(let value): return value
    case .bool(let value): return value
    case .null: return NSNull()
    }
  }
}

private struct HubProjectDraft: Codable, Equatable {
  var name = ""
  var key = ""
  var description = ""
}

private struct HubProjectForm: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let project: TaskHubProject?
  @Environment(\.dismiss) private var dismiss
  @Environment(\.taskHubConflict) private var conflict
  @Environment(\.scenePhase) private var scenePhase
  @State private var draft = HubProjectDraft()
  @State private var submitting = false
  @State private var error: String?
  @State private var completed = false
  @State private var draftScope = ""
  private var key: String { "project-form/" + (project?.id ?? "new") }
  private var path: String { project.map { "projects/" + $0.id } ?? "projects" }
  private var valid: Bool {
    !draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && (project != nil || draft.key.range(of: "^[A-Z][A-Z0-9_]{1,15}$", options: .regularExpression) != nil)
  }
  var body: some View {
    Form {
      Section(L10n.tr("项目资料")) {
        TextField(L10n.tr("项目名称"), text: $draft.name).accessibilityIdentifier("task-hub-title")
        if project == nil {
          TextField(L10n.tr("编号前缀（如 APP）"), text: $draft.key).textInputAutocapitalization(.characters).autocorrectionDisabled()
            .accessibilityIdentifier("task-hub-project-key")
          Text(L10n.tr("2–16 位大写字母、数字或下划线，以字母开头。")).font(.caption).foregroundStyle(.secondary)
        } else { LabeledContent(L10n.tr("编号前缀"), value: project?.key ?? "") }
        HubTextEditor(label: L10n.tr("项目说明"), text: $draft.description, identifier: "task-hub-description")
      }
      HubWriteState(store: store, path: path)
      Section {
        Button(L10n.tr("保存草稿"), systemImage: "square.and.arrow.down") { save() }
        if let error { Text(error).foregroundStyle(.red) }
      }
    }.navigationTitle(project == nil ? L10n.tr("创建项目") : L10n.tr("编辑项目"))
      .taskHubConsent(store, path: path)
      .toolbar {
        HubKeyboardToolbar()
        ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("取消")) { save(); dismiss() } }
        ToolbarItem(placement: .confirmationAction) {
          Button(submitting ? L10n.tr("提交中…") : L10n.tr("保存")) { Task { await submit() } }
            .disabled(!valid || submitting || !hubWritable(store, conflict: conflict, path: path) || !store.isAdmin)
            .accessibilityIdentifier("task-hub-submit")
        }
      }
      .task { draftScope = store.scope; draft = hubRestore(store.draft(key), default: HubProjectDraft(name: project?.name ?? "", key: project?.key ?? "", description: project?.description ?? "")) }
      .onDisappear { if !completed { save() } }
      .taskHubPreserveOnBusinessNavigation(store) {
        if !completed {
          guard draftScope == store.scope else { throw TaskHubError.changedIdentity }
          try store.saveSubmissionDraft(hubEncode(draft), key: key)
        }
      }
      .onChange(of: scenePhase) { _, phase in if phase != .active { save() } }
  }
  private func save() { if draftScope == store.scope { store.saveDraft(hubEncode(draft), key: key) } }
  private func submit() async {
    let intent = draft; save(); submitting = true; error = nil; defer { submitting = false }
    do {
      var body: [String: Any] = ["name": intent.name.trimmingCharacters(in: .whitespacesAndNewlines), "description": intent.description]
      if project == nil { body["key"] = intent.key }
      _ = try await store.mutate(path: path, method: project == nil ? "POST" : "PATCH", body: body)
      guard draftScope == store.scope else { return }
      await store.refresh()
      if draft == intent { completed = true; store.saveDraft("", key: key); dismiss() }
      else { error = L10n.tr("原操作已确认；新编辑的草稿已保留。"); save() }
    } catch { self.error = error.localizedDescription }
  }
}

@MainActor private func hubWritable(_ store: TaskHubStore, conflict: Bool, path: String) -> Bool {
  store.canWrite && !conflict && !store.pendingOperations.contains { $0.path == path }
}

private struct HubWriteState: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var path: String
  @Environment(\.taskHubConflict) private var conflict
  var body: some View {
    if conflict {
      Section { HubNotice(title: L10n.tr("主服务冲突，提交已暂停"), message: L10n.tr("先核对已绑定主服务。草稿仍可编辑和保存。"), warning: true) }
    } else if !store.online || store.needsLogin {
      Section { HubNotice(title: L10n.tr("草稿可编辑，当前无法提交"), message: L10n.tr("请连接原主服务并重新登录。"), symbol: "wifi.slash") }
    }
    if store.pendingOperations.contains(where: { $0.path == path }) {
      Section {
        HubNotice(title: L10n.tr("已有原操作等待确认"), message: L10n.tr("此表单是可编辑草稿；查询回执不会重放原提交或覆盖草稿。"), symbol: "clock.arrow.circlepath")
        NavigationLink(L10n.tr("查询原操作回执")) { HubReceiptsView(store: store) }
        if let id = store.activeOperationId, store.pendingOperations.contains(where: { $0.id == id && $0.path == path }) {
          Button(L10n.tr("结束当前等待")) { store.endWaiting(id) }.accessibilityIdentifier("task-hub-end-waiting")
          Text(L10n.tr("只结束手机等待，不表示取消远端。原操作与附件保留，可继续查询原回执。")).font(.caption).foregroundStyle(.secondary)
        }
      }
    }
  }
}

private struct HubTextEditor: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  var label: String
  @Binding var text: String
  var identifier: String
  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      Text(label).font(.caption).foregroundStyle(.secondary)
      TextEditor(text: $text).frame(minHeight: 100).accessibilityLabel(label).accessibilityIdentifier(identifier)
    }
  }
}

private struct HubKeyboardToolbar: ToolbarContent {
  var body: some ToolbarContent {
    ToolbarItemGroup(placement: .keyboard) {
      Spacer()
      Button(L10n.tr("收起键盘")) { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
        .accessibilityIdentifier("task-hub-keyboard-dismiss")
    }
  }
}

private struct HubMember: Codable, Identifiable, Sendable {
  var actor: TaskHubActor
  var canWrite: Bool
  var id: String { actor.id }
}

private struct HubProjectSettings: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var projectId: String
  @Environment(\.dismiss) private var dismiss
  @Environment(\.taskHubConflict) private var conflict
  @State private var source: TaskHubJSON?
  @State private var members: [HubMember] = []
  @State private var bindings: [TaskHubProjectBinding] = []
  @State private var error: String?
  @State private var memberError: String?
  @State private var archive = false
  @State private var loading = false
  private var project: TaskHubProject? { store.projects.first { $0.id == projectId } }
  var body: some View {
    List {
      HubConnectionSection(store: store)
      projectSection
      sourceSection
      bindingSection
      memberSection
      Section(L10n.tr("流程策略")) {
        if let policy = project?.workflowPolicy { HubPolicySummary(policy: policy, store: store) }
        else { Text(L10n.tr("主服务未提供此项目的策略。历史流程不会补填证明。")).foregroundStyle(.secondary) }
        if store.isAdmin && store.binding?.service.capabilities.contains("workflow-closing") == true {
          NavigationLink(L10n.tr("编辑后续轮次策略")) { HubPolicyForm(store: store, projectId: projectId, policy: project?.workflowPolicy) }
            .accessibilityIdentifier("task-hub-policy-edit")
        }
      }
      if loading { Section { ProgressView(L10n.tr("正在读取项目设置")) } }
      if let error { Section { Text(error).foregroundStyle(.red); Button(L10n.tr("重新读取")) { Task { await load() } } } }
    }.navigationTitle(L10n.tr("项目设置"))
      .taskHubConsent(store, path: "projects/" + projectId)
      .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("完成")) { dismiss() } } }
      .task { await load() }
      .refreshable { await store.refresh(); await load() }
      .confirmationDialog(project.map(hubArchived) == true ? L10n.tr("恢复此归档项目？") : L10n.tr("归档此项目？"), isPresented: $archive, titleVisibility: .visible) {
        Button(project.map(hubArchived) == true ? L10n.tr("恢复项目") : L10n.tr("归档项目"), role: .destructive) {
          Task {
            do { _ = try await store.mutate(path: "projects/" + projectId, method: "PATCH", body: ["archived": !(project.map(hubArchived) ?? false)]); await store.refresh() }
            catch { self.error = error.localizedDescription }
          }
        }
      } message: { Text(L10n.tr("保留项目与任务历史，按服务端权限及项目策略生效。")) }
  }
  private var projectSection: some View {
    Section(L10n.tr("项目")) {
      if let project {
        HubValue(label: project.key, value: project.name)
        Text(project.description)
        if store.isAdmin {
          NavigationLink(L10n.tr("编辑项目资料")) { HubProjectForm(store: store, project: project) }
          Button(hubArchived(project) ? L10n.tr("恢复项目") : L10n.tr("归档项目"), role: .destructive) { archive = true }
            .disabled(!hubWritable(store, conflict: conflict, path: "projects/" + projectId))
            .accessibilityIdentifier("task-hub-action-archive")
        }
      }
    }
  }
  private var sourceSection: some View {
    Section(L10n.tr("唯一项目源")) {
      if let source, source != .null {
        LabeledContent(L10n.tr("类型"), value: source["kind"]?.string == "git" ? "Git" : L10n.tr("目录"))
        HubValue(label: L10n.tr("来源"), value: source["reference"]?.string ?? L10n.tr("未提供"))
        LabeledContent(L10n.tr("默认分支"), value: source["defaultBranch"]?.string ?? L10n.tr("未提供"))
        LabeledContent(L10n.tr("来源版本"), value: String(source["revision"]?.hubInt ?? 0))
      } else { Text(L10n.tr("未设置项目源，设备派发暂不可用。")).foregroundStyle(.secondary) }
      if store.isAdmin { NavigationLink(L10n.tr("设置项目源")) { HubSourceForm(store: store, projectId: projectId, source: source) } }
    }
  }
  private var bindingSection: some View {
    Section(L10n.tr("开发与验收电脑映射")) {
      ForEach(bindings) { binding in
        VStack(alignment: .leading, spacing: 5) {
          Text(binding.remoteProjectName).font(.headline)
          Text((binding.role == "executor" ? L10n.tr("开发") : L10n.tr("验收")) + " · " + hubHost(binding.hostId, store)).font(.subheadline)
          Text(binding.root).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
          Text(binding.status == "verified" ? L10n.tr("已核验映射") : L10n.tr("需要重新核验")).font(.caption).foregroundStyle(binding.status == "verified" ? Color.secondary : Color.orange)
          Text(L10n.tr("执行身份：") + hubActor(binding.actorId, store)).font(.caption)
          if let revision = binding.sourceRevision { Text(L10n.tr("项目源版本 \(revision)")).font(.caption).foregroundStyle(.secondary) }
        }.accessibilityElement(children: .combine)
      }
      if bindings.isEmpty { Text(L10n.tr("暂无真实设备映射。")).foregroundStyle(.secondary) }
      if store.isAdmin { NavigationLink(L10n.tr("添加或重新核验映射")) { HubBindingForm(store: store, projectId: projectId) } }
    }
  }
  private var memberSection: some View {
    Section(L10n.tr("项目成员")) {
      ForEach(members) { member in
        NavigationLink {
          HubMemberForm(store: store, projectId: projectId, member: member)
        } label: {
          VStack(alignment: .leading, spacing: 4) {
            Text(member.actor.name)
            Text((member.actor.kind == "human" ? L10n.tr("人类") : "Agent") + " · " + (member.canWrite ? L10n.tr("可写") : L10n.tr("只读"))).font(.caption).foregroundStyle(.secondary)
          }
        }
      }
      if let memberError { Text(memberError).font(.subheadline).foregroundStyle(.secondary) }
      if store.isAdmin && store.binding?.service.capabilities.contains("project-members") == true {
        NavigationLink(L10n.tr("添加成员")) { HubMemberForm(store: store, projectId: projectId, member: nil) }
      }
    }
  }
  private func load() async {
    guard store.online else { return }
    loading = true; error = nil; memberError = nil; defer { loading = false }
    do {
      source = try await store.read(TaskHubJSON.self, path: "projects/\(projectId)/source")
      bindings = try await store.read([TaskHubProjectBinding].self, path: "projects/\(projectId)/host-bindings")
      if store.isAdmin { try await store.loadProjectSettings(projectId) }
    } catch { self.error = error.localizedDescription }
    if store.binding?.service.capabilities.contains("project-members") == true {
      do { members = try await store.read([HubMember].self, path: "projects/\(projectId)/members") }
      catch { memberError = L10n.tr("成员目录不可用：") + error.localizedDescription }
    } else { memberError = L10n.tr("主服务尚未声明成员管理能力。") }
  }
}

private struct HubSourceDraft: Codable, Equatable { var kind = "git"; var reference = ""; var branch = "main" }
private struct HubSourceForm: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let projectId: String
  let source: TaskHubJSON?
  @Environment(\.dismiss) private var dismiss
  @Environment(\.taskHubConflict) private var conflict
  @State private var draft = HubSourceDraft()
  @State private var error: String?
  @State private var submitting = false
  @State private var completed = false
  @State private var draftScope = ""
  private var key: String { "source/" + projectId }
  private var path: String { "projects/\(projectId)/source" }
  var body: some View {
    Form {
      Section(L10n.tr("项目源")) {
        Picker(L10n.tr("类型"), selection: $draft.kind) { Text("Git").tag("git"); Text(L10n.tr("目录")).tag("directory") }
        TextField(draft.kind == "git" ? L10n.tr("仓库地址") : L10n.tr("项目源目录"), text: $draft.reference).textInputAutocapitalization(.never).autocorrectionDisabled()
          .accessibilityIdentifier("task-hub-source-reference")
        TextField(L10n.tr("默认分支"), text: $draft.branch).textInputAutocapitalization(.never).autocorrectionDisabled()
        Text(L10n.tr("来源发生变化后，旧电脑映射需重新核验。服务端保存真实来源版本。")).font(.subheadline).foregroundStyle(.secondary)
      }
      HubWriteState(store: store, path: path)
      Section { Button(L10n.tr("保存草稿")) { save() }; if let error { Text(error).foregroundStyle(.red) } }
    }.navigationTitle(L10n.tr("设置项目源"))
      .taskHubConsent(store, path: path)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(submitting ? L10n.tr("提交中…") : L10n.tr("保存")) { Task { await submit() } }
            .disabled(draft.reference.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || submitting || !store.isAdmin || !hubWritable(store, conflict: conflict, path: path)).accessibilityIdentifier("task-hub-submit")
        }
      }
      .task { draftScope = store.scope; draft = hubRestore(store.draft(key), default: HubSourceDraft(kind: source?["kind"]?.string ?? "git", reference: source?["reference"]?.string ?? "", branch: source?["defaultBranch"]?.string ?? "main")) }
      .onDisappear { if !completed { save() } }
      .taskHubPreserveOnBusinessNavigation(store) {
        if !completed {
          guard draftScope == store.scope else { throw TaskHubError.changedIdentity }
          try store.saveSubmissionDraft(hubEncode(draft), key: key)
        }
      }
  }
  private func save() { if draftScope == store.scope { store.saveDraft(hubEncode(draft), key: key) } }
  private func submit() async {
    let intent = draft; save(); submitting = true; defer { submitting = false }
    do {
      _ = try await store.mutate(path: path, method: "PUT", body: ["kind": intent.kind, "reference": intent.reference, "defaultBranch": intent.branch])
      guard draftScope == store.scope else { return }
      if draft == intent { completed = true; store.saveDraft("", key: key); dismiss() }
    } catch { self.error = error.localizedDescription }
  }
}

private struct HubBindingForm: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let projectId: String
  @Environment(\.dismiss) private var dismiss
  @Environment(\.taskHubConflict) private var conflict
  @State private var hostId = ""
  @State private var remoteId = ""
  @State private var root = ""
  @State private var role = "executor"
  @State private var catalog: [TaskHubHostProject] = []
  @State private var loading = false
  @State private var submitting = false
  @State private var error: String?
  private var path: String { "projects/\(projectId)/host-bindings" }
  private var roots: [String] { catalog.first { $0.id == remoteId }?.roots ?? [] }
  var body: some View {
    Form {
      Section(L10n.tr("真实执行空间")) {
        Picker(L10n.tr("电脑"), selection: $hostId) {
          Text(L10n.tr("选择在线电脑")).tag("")
          ForEach(store.hosts.filter { $0.status == "online" }) { Text($0.name).tag($0.id) }
        }.accessibilityIdentifier("task-hub-binding-host")
        Picker(L10n.tr("电脑上的项目"), selection: $remoteId) {
          Text(L10n.tr("选择真实项目")).tag(""); ForEach(catalog) { Text($0.name).tag($0.id) }
        }.disabled(loading || hostId.isEmpty)
        Picker(L10n.tr("根目录"), selection: $root) { Text(L10n.tr("选择根目录")).tag(""); ForEach(roots, id: \.self) { Text($0).tag($0) } }
          .disabled(remoteId.isEmpty)
        Picker(L10n.tr("角色"), selection: $role) { Text(L10n.tr("开发执行")).tag("executor"); Text(L10n.tr("独立验收")).tag("reviewer") }
        if loading { ProgressView(L10n.tr("正在读取真实项目清单")) }
        Text(L10n.tr("设备、项目和根目录均来自主服务核验的当前目录。保存后由服务端登记独立执行身份。")).font(.subheadline).foregroundStyle(.secondary)
      }
      HubWriteState(store: store, path: path)
      if let error { Section { Text(error).foregroundStyle(.red) } }
    }.navigationTitle(L10n.tr("电脑映射"))
      .taskHubConsent(store, path: path)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(submitting ? L10n.tr("核验中…") : L10n.tr("核验并保存")) { Task { await submit() } }
            .disabled(hostId.isEmpty || remoteId.isEmpty || !roots.contains(root) || loading || submitting || !store.isAdmin || !hubWritable(store, conflict: conflict, path: path))
            .accessibilityIdentifier("task-hub-submit")
        }
      }
      .task { do { try await store.loadProjectSettings(projectId) } catch { self.error = error.localizedDescription } }
      .task(id: hostId) {
        remoteId = ""; root = ""; catalog = []
        guard !hostId.isEmpty else { return }
        let expected = hostId; loading = true; defer { loading = false }
        do {
          let result = try await store.read([TaskHubHostProject].self, path: "host-connections/\(expected)/projects")
          if expected == hostId { catalog = result }
        } catch { self.error = error.localizedDescription }
      }
      .onChange(of: remoteId) { _, _ in root = "" }
  }
  private func submit() async {
    submitting = true; error = nil; defer { submitting = false }
    do {
      _ = try await store.mutate(path: path, body: ["hostId": hostId, "remoteProjectId": remoteId, "root": root, "role": role])
      try await store.loadProjectSettings(projectId); await store.refresh(); dismiss()
    } catch { self.error = error.localizedDescription }
  }
}

private struct HubMemberForm: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let projectId: String
  let member: HubMember?
  @Environment(\.dismiss) private var dismiss
  @Environment(\.taskHubConflict) private var conflict
  @State private var actorId = ""
  @State private var canWrite = false
  @State private var removing = false
  @State private var submitting = false
  @State private var error: String?
  private var path: String { "projects/\(projectId)/members/\(actorId)" }
  var body: some View {
    Form {
      Section(L10n.tr("成员授权")) {
        if let member { LabeledContent(L10n.tr("成员"), value: member.actor.name) }
        else {
          Picker(L10n.tr("成员"), selection: $actorId) { Text(L10n.tr("选择成员")).tag(""); ForEach(store.actors) { Text($0.name).tag($0.id) } }
        }
        Toggle(L10n.tr("允许业务写入"), isOn: $canWrite).disabled(!store.isAdmin)
        Text(L10n.tr("Agent 执行身份与人类设备会话分别授权。权限变更由主服务核验。")).font(.subheadline).foregroundStyle(.secondary)
      }
      HubWriteState(store: store, path: path)
      if member != nil && store.isAdmin {
        Section { Button(L10n.tr("移除项目成员"), role: .destructive) { removing = true }.disabled(!hubWritable(store, conflict: conflict, path: path) || submitting) }
      }
      if let error { Section { Text(error).foregroundStyle(.red) } }
    }.navigationTitle(member == nil ? L10n.tr("添加成员") : L10n.tr("成员权限"))
      .taskHubConsent(store, path: path)
      .toolbar {
        if store.isAdmin {
          ToolbarItem(placement: .confirmationAction) {
            Button(submitting ? L10n.tr("提交中…") : L10n.tr("保存")) { Task { await submit(removal: false) } }
              .disabled(actorId.isEmpty || submitting || !hubWritable(store, conflict: conflict, path: path)).accessibilityIdentifier("task-hub-submit")
          }
        }
      }
      .task { actorId = member?.actor.id ?? ""; canWrite = member?.canWrite ?? false }
      .confirmationDialog(L10n.tr("移除此成员的项目授权？"), isPresented: $removing, titleVisibility: .visible) {
        Button(L10n.tr("移除成员"), role: .destructive) { Task { await submit(removal: true) } }
      }
  }
  private func submit(removal: Bool) async {
    submitting = true; error = nil; defer { submitting = false }
    do { _ = try await store.mutate(path: path, method: removal ? "DELETE" : "PUT", body: removal ? [:] : ["canWrite": canWrite]); await store.refresh(); dismiss() }
    catch { self.error = error.localizedDescription }
  }
}

private struct HubRequiredReviewer: Codable, Equatable, Identifiable {
  var id = UUID().uuidString
  var mode = "human"
  var reviewerId = ""
}
private struct HubPolicyDraft: Codable, Equatable {
  var version = 1
  var reviewers: [HubRequiredReviewer] = []
  var finalCommit = true
  var materials = true
  var independentHost = true
  var indexes: [String] = []
  init() { }
  init(_ policy: TaskHubJSON?) {
    version = max(1, policy?["version"]?.hubInt ?? 1)
    finalCommit = policy?["requireFinalCommit"]?.bool ?? true
    materials = policy?["requireMaterials"]?.bool ?? true
    independentHost = policy?["requireIndependentHost"]?.bool ?? true
    indexes = policy?["requiredIndexes"]?.array.compactMap(\.string) ?? []
    reviewers = policy?["requiredAcceptances"]?.array.map { HubRequiredReviewer(mode: $0["mode"]?.string ?? "human", reviewerId: $0["reviewerId"]?.string ?? "") } ?? []
  }
  var body: [String: Any] {
    ["version": version, "requiredAcceptances": reviewers.map { ["mode": $0.mode, "reviewerId": $0.reviewerId] }, "requireSelfCheck": true, "requireFinalCommit": finalCommit, "requireMaterials": materials, "requiredIndexes": indexes, "requireIndependentHost": independentHost]
  }
}

private struct HubPolicySummary: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let policy: TaskHubJSON
  @ObservedObject var store: TaskHubStore
  var body: some View {
    LabeledContent(L10n.tr("策略版本"), value: String(policy["version"]?.hubInt ?? 0))
    LabeledContent(L10n.tr("候选自验"), value: policy["requireSelfCheck"]?.bool == true ? L10n.tr("必须通过") : L10n.tr("未声明"))
    LabeledContent(L10n.tr("最终提交"), value: policy["requireFinalCommit"]?.bool == true ? L10n.tr("必须登记") : L10n.tr("允许说明无需提交"))
    LabeledContent(L10n.tr("交付材料"), value: policy["requireMaterials"]?.bool == true ? L10n.tr("必须登记") : L10n.tr("按实际候选"))
    LabeledContent(L10n.tr("独立验收电脑"), value: policy["requireIndependentHost"]?.bool == true ? L10n.tr("必须独立") : L10n.tr("按验收角色"))
    ForEach(Array((policy["requiredAcceptances"]?.array ?? []).enumerated()), id: \.offset) { _, value in
      Text((value["mode"]?.string == "agent_host" ? L10n.tr("Agent 验收 · ") : L10n.tr("人工验收 · ")) + hubActor(value["reviewerId"]?.string ?? L10n.tr("未指定"), store)).font(.subheadline)
    }
    let indexes = policy["requiredIndexes"]?.array.compactMap(\.string) ?? []
    if !indexes.isEmpty { HubValue(label: L10n.tr("必要索引"), value: indexes.joined(separator: L10n.tr("、"))) }
  }
}

private struct HubPolicyForm: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let projectId: String
  let policy: TaskHubJSON?
  @Environment(\.dismiss) private var dismiss
  @Environment(\.taskHubConflict) private var conflict
  @State private var draft = HubPolicyDraft()
  @State private var error: String?
  @State private var submitting = false
  @State private var completed = false
  @State private var draftScope = ""
  private var key: String { "policy/" + projectId }
  private var path: String { "projects/" + projectId }
  private var currentVersion: Int? { store.projects.first { $0.id == projectId }?.workflowPolicy?["version"]?.hubInt }
  private var staleVersion: Bool { currentVersion.map { $0 != draft.version } ?? false }
  private var valid: Bool {
    draft.reviewers.allSatisfy { !$0.reviewerId.isEmpty } && Set(draft.reviewers.map(\.reviewerId)).count == draft.reviewers.count && draft.indexes.allSatisfy { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } && Set(draft.indexes).count == draft.indexes.count
  }
  var body: some View {
    Form {
      Section {
        HubNotice(title: L10n.tr("用于后续任务和新轮次"), message: L10n.tr("已有轮次保留冻结策略。任务指定的验收人会由服务端加入必要验收。"))
        LabeledContent(L10n.tr("拟保存版本"), value: String(draft.version + 1))
        if staleVersion, let currentVersion {
          HubNotice(title: L10n.tr("策略版本已变化"), message: L10n.tr("编辑内容已保留。先核对最新策略，再主动采用当前版本并提交。"), symbol: "exclamationmark.triangle", warning: true)
          Button(L10n.tr("保留编辑内容，采用当前版本 \(currentVersion)")) { draft.version = currentVersion; save() }
            .accessibilityIdentifier("task-hub-policy-rebase")
        }
      }
      Section(L10n.tr("必要验收角色")) {
        ForEach($draft.reviewers) { $reviewer in
          VStack(alignment: .leading) {
            Picker(L10n.tr("方式"), selection: $reviewer.mode) { Text(L10n.tr("人工")).tag("human"); Text("Agent Host").tag("agent_host") }
              .onChange(of: reviewer.mode) { _, _ in reviewer.reviewerId = "" }
            Picker(L10n.tr("验收身份"), selection: $reviewer.reviewerId) {
              Text(L10n.tr("选择身份")).tag("")
              ForEach(store.actors.filter { reviewer.mode == "human" ? $0.kind == "human" : $0.kind == "agent" && $0.hostId != nil }) { Text($0.name).tag($0.id) }
            }
          }
        }.onDelete { draft.reviewers.remove(atOffsets: $0) }
        Button(L10n.tr("增加必要验收")) { draft.reviewers.append(HubRequiredReviewer()) }.disabled(draft.reviewers.count >= 20)
      }
      Section(L10n.tr("完成门槛")) {
        LabeledContent(L10n.tr("候选自验"), value: L10n.tr("必须通过"))
        Toggle(L10n.tr("要求最终提交引用"), isOn: $draft.finalCommit)
          .accessibilityIdentifier("task-hub-policy-final-commit")
        Toggle(L10n.tr("要求交付材料"), isOn: $draft.materials)
        Toggle(L10n.tr("Agent 验收必须使用独立电脑"), isOn: $draft.independentHost)
      }
      Section(L10n.tr("必要索引名称")) {
        ForEach(draft.indexes.indices, id: \.self) { index in TextField(L10n.tr("索引名称"), text: $draft.indexes[index]) }
          .onDelete { draft.indexes.remove(atOffsets: $0) }
        Button(L10n.tr("增加索引要求")) { draft.indexes.append("") }.disabled(draft.indexes.count >= 100)
      }
      HubWriteState(store: store, path: path)
      Section { Button(L10n.tr("保存草稿")) { save() }; if let error { Text(error).foregroundStyle(.red) } }
    }.navigationTitle(L10n.tr("流程策略"))
      .taskHubConsent(store, path: path)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(submitting ? L10n.tr("提交中…") : L10n.tr("保存")) { Task { await submit() } }
            .disabled(!valid || staleVersion || !store.isAdmin || submitting || !hubWritable(store, conflict: conflict, path: path)).accessibilityIdentifier("task-hub-submit")
        }
      }
      .task { draftScope = store.scope; draft = hubRestore(store.draft(key), default: HubPolicyDraft(policy)) }
      .onDisappear { if !completed { save() } }
      .taskHubPreserveOnBusinessNavigation(store) {
        if !completed {
          guard draftScope == store.scope else { throw TaskHubError.changedIdentity }
          try store.saveSubmissionDraft(hubEncode(draft), key: key)
        }
      }
  }
  private func save() { if draftScope == store.scope { store.saveDraft(hubEncode(draft), key: key) } }
  private func submit() async {
    let intent = draft; save(); submitting = true; error = nil; defer { submitting = false }
    do {
      _ = try await store.mutate(path: path, method: "PATCH", body: ["workflowPolicy": intent.body])
      guard draftScope == store.scope else { return }; await store.refresh()
      if draft == intent { completed = true; store.saveDraft("", key: key); dismiss() }
    } catch {
      self.error = error.localizedDescription
      if case TaskHubError.rejected(409, _) = error { await store.refresh() }
    }
  }
}

private enum HubTaskAction: Identifiable, Equatable {
  case create, edit, feedback, progress, selfCheck, delivery, finalize
  case response(String), acceptance(String, Bool)
  var id: String {
    switch self {
    case .create: return "create"
    case .edit: return "edit"
    case .feedback: return "feedback"
    case .progress: return "progress"
    case .selfCheck: return "self-check"
    case .delivery: return "delivery"
    case .finalize: return "finalize"
    case .response(let id): return "response-" + id
    case .acceptance(let id, let passed): return (passed ? "pass-" : "fail-") + id
    }
  }
  var title: String {
    switch self {
    case .create: return L10n.tr("新建任务")
    case .edit: return L10n.tr("编辑任务")
    case .feedback: return L10n.tr("添加反馈")
    case .response: return L10n.tr("负责人响应")
    case .progress: return L10n.tr("记录进度")
    case .selfCheck: return L10n.tr("记录候选自验")
    case .delivery: return L10n.tr("提交冻结候选")
    case .acceptance(_, let passed): return passed ? L10n.tr("人工验收通过") : L10n.tr("人工验收不通过")
    case .finalize: return L10n.tr("最终交付")
    }
  }
  func path(_ taskId: String?) -> String {
    guard let taskId else { return "tasks" }
    let root = "tasks/" + taskId
    switch self {
    case .create: return "tasks"
    case .edit: return root
    case .feedback: return root + "/feedback"
    case .response(let id): return root + "/feedback/\(id)/responses"
    case .progress: return root + "/progress"
    case .selfCheck: return root + "/self-checks"
    case .delivery: return root + "/deliveries"
    case .acceptance(let id, _): return root + "/acceptances/\(id)/decision"
    case .finalize: return root + "/finalizations"
    }
  }
  var usesEvidence: Bool {
    switch self { case .edit, .selfCheck, .finalize: return false; default: return true }
  }
  var uploadAction: String {
    switch self {
    case .create: return "create"
    case .feedback: return "feedback"
    case .response: return "response"
    case .progress: return "progress"
    case .selfCheck: return "self_check"
    case .delivery: return "delivery"
    case .acceptance: return "acceptance"
    case .finalize: return "finalization"
    case .edit: return "progress"
    }
  }
  var requiresClosingCapability: Bool { self == .selfCheck || self == .delivery || self == .finalize }
}

private struct HubMaterialDraft: Codable, Equatable, Identifiable {
  var id = UUID().uuidString
  var name = ""
  var reference = ""
  var sha256 = ""
  var body: [String: String] { ["name": name, "reference": reference, "sha256": sha256.lowercased()] }
  var valid: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !reference.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && hubDigest(sha256) }
}
private struct HubCheckDraft: Codable, Equatable, Identifiable {
  var id = UUID().uuidString
  var name = ""
  var result = "failed"
  var evidence = ""
  var body: [String: String] { ["name": name, "result": result, "evidence": evidence] }
  var valid: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !evidence.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
private struct HubIndexDraft: Codable, Equatable, Identifiable {
  var id = UUID().uuidString
  var name = ""
  var reference = ""
  var body: [String: String] { ["name": name, "reference": reference] }
  var valid: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !reference.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
private struct HubEvidence: Codable, Identifiable, Equatable {
  var id = UUID().uuidString
  var name: String
  var mediaType: String
  var bytes: Data?
  var remote: TaskHubAttachment?
  var operationId: String?
  var failure: String?
  static func == (lhs: Self, rhs: Self) -> Bool {
    lhs.id == rhs.id && lhs.name == rhs.name && lhs.bytes == rhs.bytes && lhs.remote?.id == rhs.remote?.id && lhs.operationId == rhs.operationId && lhs.failure == rhs.failure
  }
}

private struct HubTaskDraft: Codable, Equatable {
  var draftId: String? = UUID().uuidString.lowercased()
  var revision = 0
  var round = 1
  var title = ""
  var description = ""
  var criteria = ""
  var type = "feature"
  var priority = "P2"
  var ownerId = ""
  var reviewerId = ""
  var mode = "human"
  var hostId = ""
  var hasDate = false
  var date = Date()
  var body = ""
  var decision = "fix"
  var candidateRef = ""
  var sourceRevision = ""
  var digest = ""
  var commitRef = ""
  var noCommitReason = ""
  var materials: [HubMaterialDraft] = []
  var checks: [HubCheckDraft] = [HubCheckDraft()]
  var indexes: [HubIndexDraft] = []
  var evidence: [HubEvidence] = []
  var dispatchAfterCreate = false
  var bindingId = ""

  init() { }
  init(detail: TaskHubDetail?, action: HubTaskAction, actorId: String?, frozenCandidate: TaskHubJSON? = nil) {
    guard let detail else { ownerId = actorId ?? ""; return }
    let task = detail.task
    revision = task.revision; round = task.currentRound
    title = action == .edit ? task.title : ""
    description = task.description; criteria = task.acceptanceCriteria; type = task.type; priority = task.priority
    ownerId = task.ownerId; reviewerId = task.reviewerId; mode = task.acceptanceMode; hostId = task.hostId ?? ""
    if let due = task.dueDate, let value = hubDate(due) { hasDate = true; date = value }
    let candidate = action == .finalize ? frozenCandidate : detail.current?.selfCheck?["candidate"]
    candidateRef = detail.current?.delivery?.candidateRef ?? candidate?["reference"]?.string ?? ""
    sourceRevision = candidate?["sourceRevision"]?.string ?? ""
    digest = candidate?["artifactDigest"]?.string ?? ""
    materials = candidate?["materials"]?.array.map {
      HubMaterialDraft(name: $0["name"]?.string ?? "", reference: $0["reference"]?.string ?? "", sha256: $0["sha256"]?.string ?? "")
    } ?? []
    if let recorded = detail.current?.selfCheck?["checks"]?.array, !recorded.isEmpty {
      checks = recorded.map { HubCheckDraft(name: $0["name"]?.string ?? "", result: $0["result"]?.string ?? "failed", evidence: $0["evidence"]?.string ?? "") }
    }
    indexes = (detail.current?.policy ?? task.workflowPolicy)?["requiredIndexes"]?.array.compactMap(\.string).map { HubIndexDraft(name: $0) } ?? []
  }
}

private func hubDigest(_ string: String) -> Bool { string.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil }

private struct HubTaskForm: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let projectId: String
  let action: HubTaskAction
  let snapshot: TaskHubDetail?
  var frozenCandidate: TaskHubJSON? = nil
  var created: ((String) -> Void)? = nil
  @Environment(\.dismiss) private var dismiss
  @Environment(\.taskHubConflict) private var conflict
  @Environment(\.scenePhase) private var scenePhase
  @State private var draft = HubTaskDraft()
  @State private var base: TaskHubDetail?
  @State private var bindings: [TaskHubProjectBinding] = []
  @State private var members: [HubMember] = []
  @State private var submitting = false
  @State private var uploading = false
  @State private var error: String?
  @State private var notice: String?
  @State private var completed = false
  @State private var draftScope = ""
  private var taskId: String? { snapshot?.task.id }
  private var path: String { action.path(taskId) }
  private var key: String { "task-form/\(projectId)/\(taskId ?? "new")/\(snapshot?.task.currentRound ?? 0)/" + action.id }
  private var latest: TaskHubDetail? { store.detail?.task.id == taskId ? store.detail : base }
  private var changed: Bool { taskId != nil && latest?.task.revision != draft.revision }
  private var focusedWritable: Bool { action == .create || store.focusedRoundId == nil || store.focusedRoundId == base?.current?.id }
  private var creationOperation: TaskHubOperation? {
    guard action == .create, let draftId = draft.draftId else { return nil }
    return store.creationOperation(draftKey: key, draftId: draftId)
  }
  private var supported: Bool { !action.requiresClosingCapability || store.binding?.service.capabilities.contains("workflow-closing") == true }
  private var people: [TaskHubActor] { members.isEmpty ? store.actors : members.map(\.actor) }
  private var executorBindings: [TaskHubProjectBinding] { bindings.filter { $0.role == "executor" && $0.status == "verified" } }

  var body: some View {
    Form {
      contextSection
      inputSections
      if action.usesEvidence {
        HubEvidenceEditor(store: store, projectId: projectId, draftKey: key, draftId: draft.draftId ?? "", taskId: taskId, roundId: base?.current?.id, action: action.uploadAction, evidence: $draft.evidence, uploading: $uploading) { save() }
      }
      HubWriteState(store: store, path: path)
      Section {
        Button(L10n.tr("保存草稿"), systemImage: "square.and.arrow.down") { save(); notice = L10n.tr("草稿已保存") }
          .accessibilityIdentifier("task-hub-save-draft")
        if let notice { Text(notice).font(.subheadline).foregroundStyle(.secondary) }
        if let error { Text(error).font(.subheadline).foregroundStyle(.red).textSelection(.enabled) }
      }
    }
    .navigationTitle(action.title)
    .taskHubConsent(store, path: path)
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      HubKeyboardToolbar()
      ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("关闭")) { save(); dismiss() } }
      ToolbarItem(placement: .confirmationAction) {
        Button(submitting ? L10n.tr("提交中…") : creationOperation?.phase == "confirmed" ? L10n.tr("打开原任务") : L10n.tr("提交")) { Task { await submit() } }
          .disabled(!valid || changed || !focusedWritable || submitting || uploading || !supported || !hubWritable(store, conflict: conflict, path: path))
          .accessibilityIdentifier("task-hub-submit")
      }
    }
    .task { await prepare() }
    .onDisappear { if !completed { save() } }
    .taskHubDismissOnBusinessNavigation(store) {
      if !completed {
        guard draftScope == store.scope else { throw TaskHubError.changedIdentity }
        try store.saveSubmissionDraft(hubEncode(draft), key: key)
      }
    }
    .onChange(of: scenePhase) { _, phase in if phase != .active && !completed { save() } }
  }

  @ViewBuilder private var contextSection: some View {
    Section {
      if let base {
        Text(base.task.key + L10n.tr(" · 第 \(draft.round) 轮 · 版本 \(draft.revision)")).font(.caption).foregroundStyle(.secondary)
      }
      if changed {
        HubNotice(title: L10n.tr("服务端版本已变化"), message: L10n.tr("草稿保留在原轮次。先核对最新任务，再明确采用新版本；不会覆盖他人改动。"), warning: true)
        if let latest {
          HubValue(label: L10n.tr("服务端版本 \(latest.task.revision) · 第 \(latest.task.currentRound) 轮"), value: latest.task.title)
          DisclosureGroup(L10n.tr("核对最新需求")) { Text(latest.task.description); Text(latest.task.acceptanceCriteria) }
        }
        Button(L10n.tr("刷新并采用最新版本")) { Task { await rebase() } }.disabled(!store.online)
          .accessibilityIdentifier("task-hub-rebase")
      }
      if !supported { HubNotice(title: L10n.tr("此服务暂不支持完整交付流程"), message: L10n.tr("主服务未声明 workflow-closing 能力，请完成主服务升级后提交。"), warning: true) }
      if !focusedWritable { HubNotice(title: L10n.tr("当前正在查看历史轮次"), message: L10n.tr("关闭此表单并明确进入当前轮后，才能提交。"), warning: true) }
      if action == .create, let operation = creationOperation {
        if operation.phase == "confirmed" {
          Text(L10n.tr("原创建已确认。打开原任务会保留其操作结果；恢复时不会再次创建或自动派发。")).font(.subheadline)
          Button(L10n.tr("查看已创建任务")) {
            guard let response = operation.response, let original = try? JSONDecoder().decode(TaskHubDetail.self, from: response) else { return }
            let identity = store.scope
            Task {
              await store.loadDetail(original.task.id)
              guard identity == store.scope, !store.needsLogin, store.detail?.task.id == original.task.id else { return }
              save(); created?(original.task.id); dismiss()
            }
          }.accessibilityIdentifier("task-hub-open-original-created-task")
          Button(L10n.tr("明确另建新任务")) {
            draft.draftId = UUID().uuidString.lowercased(); save()
            notice = L10n.tr("已开始一份新的创建草稿，请核对后提交。")
          }.accessibilityIdentifier("task-hub-new-creation-draft")
        } else if ["pending", "unknown"].contains(operation.phase) {
          Text(L10n.tr("原创建结果尚未确认。请查询原回执；本草稿不会换新编号重新创建。")).font(.subheadline)
        }
      }
      if action == .feedback && ["review", "done"].contains(base?.task.status ?? "") {
        Text(L10n.tr("新反馈会开启返修轮，旧候选与验收保留。")).font(.subheadline).foregroundStyle(.secondary)
      }
      if action == .edit { Text(L10n.tr("需求变化由服务端形成反馈或新轮次；已有验收不会被静默沿用。")).font(.subheadline).foregroundStyle(.secondary) }
      if case .response(let id) = action, let feedback = base?.current?.feedbacks.first(where: { $0.id == id }) {
        HubValue(label: L10n.tr("对应原反馈：") + feedback.title, value: feedback.body)
        Text(L10n.tr("仅指定负责人本人可以响应。")).font(.caption).foregroundStyle(.secondary)
      }
      if case .acceptance(let id, let passed) = action {
        if let acceptance = hubAcceptances(base?.current).first(where: { $0.id == id }) { HubValue(label: L10n.tr("本次验收候选"), value: acceptance.candidateRef) }
        if !passed { Text(L10n.tr("不通过必须说明未通过项，服务端将保留本轮并开启返修轮。")).font(.subheadline).foregroundStyle(.secondary) }
      }
      if action == .finalize { Text(L10n.tr("提交后由服务端核验冻结候选、必要验收、材料及索引。全部门槛通过后才完成任务。")).font(.subheadline).foregroundStyle(.secondary) }
    }
  }

  @ViewBuilder private var inputSections: some View {
    switch action {
    case .create, .edit: taskFields
    case .feedback:
      Section(L10n.tr("反馈")) {
        TextField(L10n.tr("反馈标题"), text: $draft.title).accessibilityIdentifier("task-hub-title")
        HubTextEditor(label: L10n.tr("反馈内容"), text: $draft.body, identifier: "task-hub-body")
        LabeledContent(L10n.tr("应响应人"), value: hubActor(base?.task.ownerId ?? "", store))
      }
    case .response:
      Section(L10n.tr("明确响应")) {
        Picker(L10n.tr("处理决定"), selection: $draft.decision) { Text(L10n.tr("接受并处理")).tag("fix"); Text(L10n.tr("需要澄清")).tag("clarify") }
          .accessibilityIdentifier("task-hub-response-decision")
        HubTextEditor(label: L10n.tr("响应内容"), text: $draft.body, identifier: "task-hub-body")
        if draft.decision == "clarify" { Text(L10n.tr("澄清响应仍会阻止交付。信息齐全后，在同一反馈补充明确处理响应。")).font(.subheadline).foregroundStyle(.orange) }
      }
    case .progress:
      Section(L10n.tr("实际进展")) { HubTextEditor(label: L10n.tr("进展、结果与下一步"), text: $draft.body, identifier: "task-hub-body") }
    case .selfCheck:
      candidateFields
      HubMaterialEditor(materials: $draft.materials)
      HubChecksEditor(checks: $draft.checks)
    case .delivery:
      Section(L10n.tr("候选交付说明")) { HubTextEditor(label: L10n.tr("逐项交付说明"), text: $draft.body, identifier: "task-hub-body") }
      candidateFields
      HubMaterialEditor(materials: $draft.materials)
      Section(L10n.tr("精确覆盖本轮反馈")) {
        ForEach(base?.current?.feedbacks ?? []) { feedback in
          Label(feedback.title, systemImage: feedback.responses.last?.decision == "fix" ? "checkmark.circle" : "exclamationmark.circle")
        }
        Text(L10n.tr("候选必须与本轮已通过自验的候选、产物摘要和材料一致。")).font(.caption).foregroundStyle(.secondary)
      }
    case .acceptance:
      Section(L10n.tr("人工验收结论")) { HubTextEditor(label: L10n.tr("逐项验收结果与意见（必填）"), text: $draft.body, identifier: "task-hub-body") }
    case .finalize:
      Section(L10n.tr("本轮冻结候选")) {
        HubValue(label: L10n.tr("候选引用"), value: draft.candidateRef)
        HubValue(label: L10n.tr("产物 SHA-256"), value: draft.digest)
        ForEach(draft.materials) { HubValue(label: $0.name, value: $0.reference + "\n" + $0.sha256) }
      }
      Section(L10n.tr("最终提交")) {
        TextField(L10n.tr("最终提交引用"), text: $draft.commitRef).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("task-hub-final-commit")
        if (base?.current?.policy ?? base?.task.workflowPolicy)?["requireFinalCommit"]?.bool != true {
          HubTextEditor(label: L10n.tr("无需提交的具体说明（与提交引用二选一）"), text: $draft.noCommitReason, identifier: "task-hub-no-commit-reason")
        }
      }
      HubIndexesEditor(indexes: $draft.indexes)
    }
  }

  @ViewBuilder private var taskFields: some View {
    Section(L10n.tr("需求与验收标准")) {
      TextField(L10n.tr("任务标题"), text: $draft.title).accessibilityIdentifier("task-hub-title")
      HubTextEditor(label: L10n.tr("需求描述"), text: $draft.description, identifier: "task-hub-description")
      HubTextEditor(label: L10n.tr("验收标准"), text: $draft.criteria, identifier: "task-hub-acceptance-criteria")
      if action == .create { Picker(L10n.tr("类型"), selection: $draft.type) { Text(L10n.tr("功能")).tag("feature"); Text(L10n.tr("缺陷")).tag("bug"); Text(L10n.tr("维护")).tag("chore") } }
      Picker(L10n.tr("优先级"), selection: $draft.priority) { ForEach(["P0", "P1", "P2", "P3"], id: \.self) { Text($0).tag($0) } }
      Toggle(L10n.tr("设置截止日期"), isOn: $draft.hasDate)
      if draft.hasDate { DatePicker(L10n.tr("截止日期"), selection: $draft.date, displayedComponents: .date) }
    }
    if action == .create {
      Section(L10n.tr("开发与验收")) {
        Picker(L10n.tr("负责人"), selection: $draft.ownerId) { Text(L10n.tr("选择负责人")).tag(""); ForEach(people) { Text($0.name).tag($0.id) } }
          .accessibilityIdentifier("task-hub-owner")
        Picker(L10n.tr("开发电脑"), selection: $draft.hostId) {
          Text(L10n.tr("未指定")).tag("")
          ForEach(Array(Set(bindings.map(\.hostId))).sorted(), id: \.self) { Text(hubHost($0, store)).tag($0) }
        }
        Picker(L10n.tr("验收方式"), selection: Binding(get: { draft.mode }, set: { mode in
          guard mode != draft.mode else { return }
          draft.mode = mode; draft.reviewerId = ""
        })) { Text(L10n.tr("人工")).tag("human"); Text("Agent Host").tag("agent_host") }
        Picker(L10n.tr("验收人"), selection: $draft.reviewerId) {
          Text(L10n.tr("选择独立验收人")).tag("")
          ForEach(people.filter { $0.id != draft.ownerId && (draft.mode == "human" ? $0.kind == "human" : $0.kind == "agent" && $0.hostId != nil) }) { Text($0.name).tag($0.id) }
        }.accessibilityIdentifier("task-hub-reviewer")
      }
      Section(L10n.tr("创建后执行")) {
        Toggle(L10n.tr("创建并派发到真实电脑"), isOn: $draft.dispatchAfterCreate)
        if draft.dispatchAfterCreate {
          Picker(L10n.tr("开发映射"), selection: $draft.bindingId) {
            Text(L10n.tr("选择已核验的开发空间")).tag("")
            ForEach(executorBindings.filter { $0.actorId == draft.ownerId }) { Text($0.remoteProjectName + " · " + hubHost($0.hostId, store)).tag($0.id) }
          }.accessibilityIdentifier("task-hub-dispatch-binding")
          Text(L10n.tr("必须使用负责人对应的开发映射。创建已确认但派发失败时会保留任务，不能重新创建。")).font(.caption).foregroundStyle(.secondary)
        }
      }
    }
  }

  private var candidateFields: some View {
    Section(L10n.tr("真实候选指纹")) {
      TextField(L10n.tr("候选引用"), text: $draft.candidateRef).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("task-hub-candidate-reference")
      TextField(L10n.tr("源码版本"), text: $draft.sourceRevision).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("task-hub-source-revision")
      TextField(L10n.tr("产物 SHA-256（64 位）"), text: $draft.digest).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("task-hub-artifact-digest")
      Text(L10n.tr("填写本次实际源码与构建产物指纹。不会自动生成或代填通过证明。")).font(.caption).foregroundStyle(.secondary)
    }
  }

  private var valid: Bool {
    func text(_ value: String) -> Bool { !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    let candidateValid = text(draft.candidateRef) && text(draft.sourceRevision) && hubDigest(draft.digest) && draft.materials.allSatisfy(\.valid) && Set(draft.materials.map(\.name)).count == draft.materials.count
    if draft.evidence.contains(where: { $0.operationId != nil && $0.remote == nil }) { return false }
    switch action {
    case .create: return text(draft.title) && text(draft.description) && text(draft.criteria) && !draft.ownerId.isEmpty && !draft.reviewerId.isEmpty && draft.ownerId != draft.reviewerId && (!draft.dispatchAfterCreate || executorBindings.contains { $0.id == draft.bindingId && $0.actorId == draft.ownerId })
    case .edit: return text(draft.title) && text(draft.description) && text(draft.criteria)
    case .feedback: return text(draft.title) && text(draft.body)
    case .response, .progress, .acceptance: return text(draft.body)
    case .selfCheck: return candidateValid && !draft.checks.isEmpty && draft.checks.allSatisfy(\.valid)
    case .delivery: return candidateValid && text(draft.body) && base?.current?.selfCheck != nil && base?.current?.feedbacks.allSatisfy { $0.responses.last?.decision == "fix" } == true
    case .finalize:
      let requireCommit = (base?.current?.policy ?? base?.task.workflowPolicy)?["requireFinalCommit"]?.bool == true
      return text(draft.candidateRef) && hubDigest(draft.digest) && draft.indexes.allSatisfy(\.valid) && (requireCommit ? text(draft.commitRef) && !text(draft.noCommitReason) : text(draft.commitRef) != text(draft.noCommitReason))
    }
  }

  private func prepare() async {
    draftScope = store.scope; base = snapshot
    draft = hubRestore(store.draft(key), default: HubTaskDraft(detail: snapshot, action: action, actorId: store.actor?.id, frozenCandidate: frozenCandidate))
    if draft.draftId == nil { draft.draftId = UUID().uuidString.lowercased(); save() }
    if action == .create && store.online {
      do {
        bindings = try await store.read([TaskHubProjectBinding].self, path: "projects/\(projectId)/host-bindings")
        if store.binding?.service.capabilities.contains("project-members") == true { members = try await store.read([HubMember].self, path: "projects/\(projectId)/members") }
      } catch { self.error = L10n.tr("执行空间或成员目录暂不可用：") + error.localizedDescription }
    }
  }
  private func save() { if draftScope == store.scope { store.saveDraft(hubEncode(draft), key: key) } }
  private func rebase() async {
    guard let taskId else { return }
    await store.loadDetail(taskId)
    guard store.online, let next = store.detail, next.task.id == taskId else { error = L10n.tr("未能读取最新任务"); return }
    guard next.task.currentRound == draft.round else { error = L10n.tr("轮次已改变，请关闭表单并进入当前轮；旧草稿继续保留。"); return }
    if case .acceptance(let id, _) = action, !hubAcceptances(next.current).contains(where: { $0.id == id && $0.status == "pending" }) { error = L10n.tr("原验收已失效，草稿保留。"); return }
    base = next; draft.revision = next.task.revision; save(); error = nil; notice = L10n.tr("已采用最新版本，请核对内容后提交。")
  }
  private func body(_ intent: HubTaskDraft) -> [String: Any] {
    let attachmentIds = intent.evidence.compactMap { $0.remote?.id }
    let date: Any = intent.hasDate ? ISO8601DateFormatter().string(from: intent.date) : NSNull()
    var value: [String: Any] = ["revision": intent.revision, "attachmentIds": attachmentIds]
    switch action {
    case .create:
      value = ["projectId": projectId, "title": intent.title, "description": intent.description, "acceptanceCriteria": intent.criteria, "type": intent.type, "priority": intent.priority, "ownerId": intent.ownerId, "reviewerId": intent.reviewerId, "acceptanceMode": intent.mode, "hostId": intent.hostId.isEmpty ? NSNull() : intent.hostId as Any, "dueDate": date, "attachmentIds": attachmentIds]
    case .edit: value = ["revision": intent.revision, "title": intent.title, "description": intent.description, "acceptanceCriteria": intent.criteria, "priority": intent.priority, "dueDate": date]
    case .feedback: value["title"] = intent.title; value["body"] = intent.body; value["responderId"] = base?.task.ownerId
    case .response: value["body"] = intent.body; value["decision"] = intent.decision
    case .progress: value["body"] = intent.body
    case .selfCheck:
      value = ["revision": intent.revision, "candidateRef": intent.candidateRef, "sourceRevision": intent.sourceRevision, "artifactDigest": intent.digest.lowercased(), "materials": intent.materials.map(\.body), "checks": intent.checks.map(\.body)]
    case .delivery:
      value["summary"] = intent.body; value["candidateRef"] = intent.candidateRef
      value["feedbackIds"] = base?.current?.feedbacks.map(\.id) ?? []
      value["candidate"] = ["reference": intent.candidateRef, "sourceRevision": intent.sourceRevision, "artifactDigest": intent.digest.lowercased(), "materials": intent.materials.map(\.body)]
    case .acceptance(_, let passed): value["result"] = passed ? "passed" : "failed"; value["note"] = intent.body
    case .finalize:
      value = ["revision": intent.revision, "candidateRef": intent.candidateRef, "artifactDigest": intent.digest.lowercased(), "materials": intent.materials.map(\.body), "indexes": intent.indexes.map(\.body)]
      if !intent.commitRef.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { value["commitRef"] = intent.commitRef }
      if !intent.noCommitReason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { value["noCommitReason"] = intent.noCommitReason }
    }
    return value
  }
  private func submit() async {
    save(); error = nil; notice = nil
    guard focusedWritable else { error = L10n.tr("历史轮次只读，请先明确进入当前轮。"); return }
    guard draft.evidence.allSatisfy({ $0.remote != nil }) else { error = L10n.tr("请先上传全部附件，或移除不提交的草稿附件。"); return }
    let intent = draft; let scope = store.scope
    submitting = true; defer { submitting = false }
    do {
      let response: Data
      var recoveredCreation = false
      var originalCreationBody = true
      if action == .create {
        guard let draftId = intent.draftId else { throw TaskHubError.message(L10n.tr("创建草稿身份尚未保存")) }
        try store.saveSubmissionDraft(hubEncode(intent), key: key)
        let result = try await store.createTask(body: body(intent), draftKey: key, draftId: draftId)
        response = result.data; recoveredCreation = result.recovered; originalCreationBody = result.sameDraftBody
      } else { response = try await store.mutate(path: path, method: action == .edit ? "PATCH" : "POST", body: body(intent)) }
      let confirmed = try JSONDecoder().decode(TaskHubDetail.self, from: response)
      guard scope == store.scope else { return }
      if action == .create && intent.dispatchAfterCreate && !recoveredCreation {
        do { _ = try await store.mutate(path: "tasks/\(confirmed.task.id)/dispatches", body: ["revision": confirmed.task.revision, "bindingId": intent.bindingId, "role": "executor"]) }
        catch { store.error = L10n.tr("任务已创建，派发尚未确认：") + error.localizedDescription }
      }
      if draft == intent && originalCreationBody {
        completed = true; store.saveDraft("", key: key)
        if action == .create { created?(confirmed.task.id) }
        dismiss()
      } else { notice = L10n.tr("原操作已确认，新草稿继续保留。"); save() }
    } catch {
      self.error = error.localizedDescription
      if case TaskHubError.rejected(409, _) = error, let taskId { await store.loadDetail(taskId) }
    }
  }
}

private struct HubMaterialEditor: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @Binding var materials: [HubMaterialDraft]
  var body: some View {
    Section(L10n.tr("候选材料")) {
      ForEach($materials) { $material in
        VStack(alignment: .leading, spacing: 8) {
          TextField(L10n.tr("材料名称"), text: $material.name).accessibilityIdentifier("task-hub-material-name-" + material.id)
          TextField(L10n.tr("准确材料引用"), text: $material.reference).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("task-hub-material-reference-" + material.id)
          TextField(L10n.tr("材料 SHA-256（64 位）"), text: $material.sha256).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("task-hub-material-digest-" + material.id)
        }
      }.onDelete { materials.remove(atOffsets: $0) }
      Button(L10n.tr("增加候选材料"), systemImage: "plus") { materials.append(HubMaterialDraft()) }.disabled(materials.count >= 100)
        .accessibilityIdentifier("task-hub-add-material")
      Text(L10n.tr("材料引用可使用 attachment:<已上传附件 ID> 或实际产物地址，摘要应来自对应真实文件。")).font(.caption).foregroundStyle(.secondary)
    }
  }
}

private struct HubChecksEditor: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @Binding var checks: [HubCheckDraft]
  var body: some View {
    Section(L10n.tr("自验检查与证据")) {
      ForEach($checks) { $check in
        VStack(alignment: .leading, spacing: 8) {
          TextField(L10n.tr("检查项名称"), text: $check.name).accessibilityIdentifier("task-hub-check-name-" + check.id)
          Picker(L10n.tr("实际结果"), selection: $check.result) { Text(L10n.tr("未通过")).tag("failed"); Text(L10n.tr("通过")).tag("passed") }
            .accessibilityIdentifier("task-hub-check-result-" + check.id)
          HubTextEditor(label: L10n.tr("实际检查结果和证据引用"), text: $check.evidence, identifier: "task-hub-check-evidence-" + check.id)
        }
      }.onDelete { checks.remove(atOffsets: $0) }
      Button(L10n.tr("增加自验检查"), systemImage: "plus") { checks.append(HubCheckDraft()) }.disabled(checks.count >= 100)
        .accessibilityIdentifier("task-hub-add-check")
      Text(L10n.tr("检查结果默认未通过。所有必要检查通过后才能交付；保存自验不会完成任务。")).font(.caption).foregroundStyle(.secondary)
    }
  }
}

private struct HubIndexesEditor: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @Binding var indexes: [HubIndexDraft]
  var body: some View {
    Section(L10n.tr("索引同步结果")) {
      ForEach($indexes) { $index in
        VStack(alignment: .leading, spacing: 8) {
          TextField(L10n.tr("索引名称"), text: $index.name)
          TextField(L10n.tr("实际同步结果引用"), text: $index.reference).textInputAutocapitalization(.never).autocorrectionDisabled()
            .accessibilityIdentifier("task-hub-index-reference-" + index.id)
        }
      }.onDelete { indexes.remove(atOffsets: $0) }
      Button(L10n.tr("增加索引记录"), systemImage: "plus") { indexes.append(HubIndexDraft()) }.disabled(indexes.count >= 100)
      Text(L10n.tr("按冻结策略补齐所有必要索引。填写引用后仍由服务端核对。")).font(.caption).foregroundStyle(.secondary)
    }
  }
}

private struct HubEvidenceEditor: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var projectId: String
  var draftKey: String
  var draftId: String
  var taskId: String?
  var roundId: String?
  var action: String
  @Binding var evidence: [HubEvidence]
  @Binding var uploading: Bool
  var save: () -> Void
  @Environment(\.taskHubConflict) private var conflict
  @State private var photos: [PhotosPickerItem] = []
  @State private var files = false
  @State private var importing = false
  @State private var current: String?
  @State private var error: String?
  private var allowsFiles: Bool { store.binding?.service.capabilities.contains("private-file-attachments") == true }
  private var receiptSignature: String { store.operations.map { $0.id + $0.phase }.joined(separator: "/") }
  var body: some View {
    Section(L10n.tr("图片与文件证据")) {
      ForEach(evidence) { item in evidenceRow(item) }
        .onDelete { offsets in
          guard !uploading else { return }
          evidence.remove(atOffsets: offsets); save()
        }
      PhotosPicker(selection: $photos, maxSelectionCount: min(6, max(1, 20 - evidence.count)), matching: .images, preferredItemEncoding: .compatible) {
        Label(L10n.tr("从照片选择"), systemImage: "photo.on.rectangle")
      }.disabled(importing || uploading || evidence.count >= 20).accessibilityIdentifier("task-hub-photos")
      Button(L10n.tr("从文件选择"), systemImage: "folder") { files = true }
        .disabled(importing || uploading || evidence.count >= 20).accessibilityIdentifier("task-hub-files")
      if !allowsFiles { Text(L10n.tr("当前主服务只声明 PNG、JPEG、WebP、GIF 图片附件能力；普通文件上传暂不可用。")).font(.caption).foregroundStyle(.secondary) }
      if importing { ProgressView(L10n.tr("正在读取所选附件")) }
      if evidence.contains(where: { $0.remote == nil && $0.operationId == nil }) {
        Button(uploading ? L10n.tr("上传中…") : L10n.tr("上传草稿附件")) { Task { await upload() } }
          .disabled(uploading || importing || !hubWritable(store, conflict: conflict, path: "attachments"))
          .accessibilityIdentifier("task-hub-upload")
      }
      if let error { Text(error).font(.subheadline).foregroundStyle(.red) }
      Text(L10n.tr("每项最多 20 MB、共 20 项。已上传证据在业务提交成功后关联本轮事件；失败与未知回执保留在原记录。")).font(.caption).foregroundStyle(.secondary)
    }
    .fileImporter(isPresented: $files, allowedContentTypes: allowsFiles ? [.data] : [.png, .jpeg, .webP, .gif], allowsMultipleSelection: true) { result in
      Task { await importFiles(result) }
    }
    .task(id: photos) {
      guard !photos.isEmpty else { return }
      let selected = photos; let scope = store.scope
      importing = true; error = nil; defer { importing = false; photos = [] }
      for item in selected {
        do {
          guard let data = try await item.loadTransferable(type: Data.self) else { throw TaskHubError.message(L10n.tr("无法读取所选照片")) }
          guard scope == store.scope else { return }
          if let type = imageType(data) { try append(name: L10n.tr("照片-") + UUID().uuidString.prefix(8) + "." + type.ext, type: type.mime, bytes: data) }
          else if let converted = UIImage(data: data)?.jpegData(compressionQuality: 0.92) { try append(name: L10n.tr("照片-") + UUID().uuidString.prefix(8) + ".jpg", type: "image/jpeg", bytes: converted) }
          else { throw TaskHubError.message(L10n.tr("所选照片格式暂不支持")) }
        } catch { self.error = error.localizedDescription }
      }
      save()
    }
    .onChange(of: receiptSignature) { _, _ in reconcile() }
    .onAppear { reconcile() }
  }
  @ViewBuilder private func evidenceRow(_ item: HubEvidence) -> some View {
    VStack(alignment: .leading, spacing: 6) {
      HStack {
        if let bytes = item.bytes, item.mediaType.hasPrefix("image/"), let image = UIImage(data: bytes) {
          Image(uiImage: image).resizable().scaledToFill().frame(width: 44, height: 44).clipShape(RoundedRectangle(cornerRadius: 6)).accessibilityHidden(true)
        } else { Image(systemName: item.mediaType.hasPrefix("image/") ? "photo" : "doc") }
        Text(item.name)
        Spacer()
        if current == item.id { ProgressView() }
      }
      if let remote = item.remote {
        Text(L10n.tr("已上传 · 待关联业务提交")).font(.caption).foregroundStyle(.secondary)
        HubAttachmentList(store: store, attachments: [remote])
      } else if item.operationId != nil {
        Text(L10n.tr("上传结果待核对，不能重复上传")).font(.caption).foregroundStyle(.orange)
        NavigationLink(L10n.tr("查询此原上传回执")) { HubReceiptsView(store: store) }
      } else { Text(item.failure == nil ? L10n.tr("本地草稿 · 尚未上传") : L10n.tr("上传失败，可重试")).font(.caption).foregroundStyle(.secondary) }
      if let failure = item.failure { Text(failure).font(.caption).foregroundStyle(.red) }
    }.accessibilityIdentifier("task-hub-evidence-" + item.id)
  }
  private func append(name: String, type: String, bytes: Data) throws {
    guard bytes.count <= 20 * 1024 * 1024 else { throw TaskHubError.message(L10n.tr("\(name) 超过 20 MB")) }
    guard evidence.count < 20 else { throw TaskHubError.message(L10n.tr("最多选择 20 个附件")) }
    evidence.append(HubEvidence(name: name, mediaType: type, bytes: bytes))
  }
  private func importFiles(_ result: Result<[URL], Error>) async {
    importing = true; error = nil; defer { importing = false }
    do {
      let urls = try result.get()
      for url in urls {
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        if let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 20 * 1024 * 1024 { throw TaskHubError.message(L10n.tr("\(url.lastPathComponent) 超过 20 MB")) }
        let bytes = try Data(contentsOf: url, options: .mappedIfSafe)
        let image = imageType(bytes)
        guard allowsFiles || image != nil else { throw TaskHubError.message(L10n.tr("当前主服务暂不支持此文件格式")) }
        let fileTypes = ["txt":"text/plain", "log":"text/plain", "md":"text/plain", "json":"text/plain", "csv":"text/plain", "patch":"text/x-diff", "diff":"text/x-diff", "zip":"application/zip"]
        guard let type = image?.mime ?? fileTypes[url.pathExtension.lowercased()] else { throw TaskHubError.message(L10n.tr("仅支持图片、UTF-8 文本、日志、JSON、CSV、patch/diff 和受限 ZIP")) }
        try append(name: url.lastPathComponent, type: type, bytes: bytes)
      }
      save()
    } catch { self.error = error.localizedDescription; save() }
  }
  private func imageType(_ data: Data) -> (mime: String, ext: String)? {
    if data.starts(with: [0x89, 0x50, 0x4e, 0x47]) { return ("image/png", "png") }
    if data.starts(with: [0xff, 0xd8, 0xff]) { return ("image/jpeg", "jpg") }
    if data.starts(with: Data("GIF8".utf8)) { return ("image/gif", "gif") }
    if data.starts(with: Data("RIFF".utf8)), data.count > 12, data.subdata(in: 8..<12) == Data("WEBP".utf8) { return ("image/webp", "webp") }
    return nil
  }
  private func upload() async {
    uploading = true; error = nil; let scope = store.scope
    defer { uploading = false; current = nil; save() }
    let staged = evidence.filter { $0.remote == nil && $0.operationId == nil }
    for item in staged {
      guard scope == store.scope, let bytes = item.bytes else { return }
      current = item.id
      do {
        let remote = try await store.upload(name: item.name, mediaType: item.mediaType, data: bytes, projectId: projectId, draftKey: draftKey, evidenceId: item.id, draftId: draftId, taskId: taskId, roundId: roundId, action: action)
        guard scope == store.scope, let index = evidence.firstIndex(where: { $0.id == item.id }) else { return }
        evidence[index].remote = remote; evidence[index].bytes = nil; evidence[index].failure = nil; save()
      } catch {
        guard scope == store.scope, let index = evidence.firstIndex(where: { $0.id == item.id }) else { return }
        evidence[index].failure = error.localizedDescription
        evidence[index].operationId = store.uploadOperation(draftKey: draftKey, evidenceId: item.id)?.id
        self.error = error.localizedDescription; save(); return
      }
    }
  }
  private func reconcile() {
    for index in evidence.indices {
      if evidence[index].operationId == nil, evidence[index].remote == nil {
        evidence[index].operationId = store.uploadOperation(draftKey: draftKey, evidenceId: evidence[index].id)?.id
      }
      guard let id = evidence[index].operationId, let receipt = store.operations.first(where: { $0.id == id }) else { continue }
      if receipt.phase == "confirmed", receipt.path == "attachments/prepare" {
        evidence[index].operationId = nil; evidence[index].failure = nil
      } else if receipt.phase == "confirmed", let data = receipt.response, let remote = try? JSONDecoder().decode(TaskHubAttachment.self, from: data) {
        evidence[index].remote = remote; evidence[index].bytes = nil; evidence[index].failure = nil; evidence[index].operationId = nil
      } else if receipt.phase == "rejected" { evidence[index].failure = receipt.message; evidence[index].operationId = nil }
    }
    save()
  }
}

private func hubAcceptances(_ round: TaskHubRound?) -> [TaskHubAcceptance] {
  if let values = round?.acceptances, !values.isEmpty { return values }
  return round?.acceptance.map { [$0] } ?? []
}

private enum HubDetailTab: String, CaseIterable { case requirements = "需求", process = "过程", evidence = "证据", delivery = "交付", runs = "执行"
  var title: String {
    switch self {
    case .requirements: return L10n.tr("需求")
    case .process: return L10n.tr("过程")
    case .evidence: return L10n.tr("证据")
    case .delivery: return L10n.tr("交付")
    case .runs: return L10n.tr("执行")
    }
  }
}
private struct HubTaskPresentation: Identifiable {
  var action: HubTaskAction
  var detail: TaskHubDetail
  var candidate: TaskHubJSON?
  var id: String { detail.task.id + "/\(detail.task.currentRound)/" + action.id }
}
private struct HubDispatchPresentation: Identifiable {
  var detail: TaskHubDetail
  var id: String { detail.task.id + "/\(detail.task.revision)" }
}

private struct HubTaskDetailView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var taskId: String
  var openExecution: ((TaskHubExecutionNavigation) async throws -> Void)?
  @Environment(\.taskHubConflict) private var conflict
  @Environment(\.dynamicTypeSize) private var typeSize
  @State private var tab: HubDetailTab = .process
  @State private var presentation: HubTaskPresentation?
  @State private var dispatchPresentation: HubDispatchPresentation?
  @State private var starting = false
  @State private var expanded: Set<Int> = []
  @State private var error: String?
  @State private var rawDetail: TaskHubJSON?
  private var detail: TaskHubDetail? { store.detail?.task.id == taskId ? store.detail : nil }
  private var rawKey: String { store.scope + "/" + String(store.online) + "/" + String(detail?.task.revision ?? 0) }
  private func writableFocus(_ detail: TaskHubDetail) -> Bool { store.focusedRoundId == nil || store.focusedRoundId == detail.current?.id }
  private func visibleRoundSummaries(_ detail: TaskHubDetail) -> [TaskHubRoundSummary] {
    var values = store.roundSummaries
    if let focused = store.focusedRoundId, !values.contains(where: { $0.id == focused }),
      let round = detail.rounds.first(where: { $0.id == focused }) {
      values.append(TaskHubRoundSummary(id: round.id, number: round.number, status: round.status, createdAt: round.createdAt,
        feedbackRevision: 0, feedbackCount: round.feedbacks.count, eventCount: detail.events.filter { $0.roundNumber == round.number }.count))
    }
    return values.sorted { $0.number > $1.number }
  }
  var body: some View {
    List {
      HubConnectionSection(store: store)
      if let detail {
        HubTaskSummary(detail: detail, store: store)
        detailSections(detail)
      } else {
        Section { ContentUnavailableView(L10n.tr("任务尚未同步"), systemImage: "checklist", description: Text(L10n.tr("连接原主服务后重试。离线时仅显示已保存的任务。"))); Button(L10n.tr("重试")) { Task { await store.loadDetail(taskId) } } }
      }
      if let error { Section { Text(error).foregroundStyle(.red) } }
    }
    .navigationTitle(detail?.task.key ?? L10n.tr("任务详情"))
    .taskHubConsent(store, path: "tasks/\(taskId)/start")
    .navigationBarTitleDisplayMode(.inline)
    .refreshable { await store.loadDetail(taskId) }
    .task {
      let requestedRound = store.bookmark?.taskId == taskId ? store.bookmark?.roundId : nil
      await store.loadDetail(taskId)
      if let requestedRound { await store.loadRound(requestedRound) }
      if let detail {
        let current = detail.rounds.first { $0.number == detail.task.currentRound }
        expanded = [detail.task.currentRound]
        if let requested = detail.rounds.first(where: { $0.id == requestedRound }) { expanded.insert(requested.number) }
        store.remember(projectId: detail.task.projectId, taskId: taskId, roundId: requestedRound ?? current?.id)
      }
    }
    .task(id: rawKey) { await loadFrozenCandidates() }
    .onChange(of: detail?.task.currentRound) { _, round in if let round { expanded.insert(round) } }
    .onChange(of: store.focusedRoundId) { _, focused in
      guard let detail, let round = detail.rounds.first(where: { $0.id == focused }) else { return }
      expanded.insert(round.number); tab = .process
    }
    .safeAreaInset(edge: .top) {
      if detail != nil { tabPicker.padding(.horizontal).padding(.vertical, 8).background(.bar) }
    }
    .safeAreaInset(edge: .bottom) {
      if let detail {
        if let focused = store.focusedRoundId, focused != detail.current?.id {
          VStack(alignment: .leading, spacing: 8) {
            Text(L10n.tr("正在查看通知原轮次 · 历史只读")).font(.subheadline)
            Button(L10n.tr("进入当前第 \(detail.task.currentRound) 轮")) {
              store.remember(projectId: detail.task.projectId, taskId: taskId, roundId: detail.current?.id)
              expanded.insert(detail.task.currentRound); tab = .process
            }.accessibilityIdentifier("task-hub-enter-current-round")
          }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.bar)
        } else {
          HubTaskActions(store: store, detail: detail, starting: starting, start: { Task { await start(detail) } }, open: { present($0) }, dispatch: { if writableFocus(detail) { dispatchPresentation = HubDispatchPresentation(detail: detail) } })
        }
      }
    }
    .sheet(item: $presentation) { value in
      NavigationStack { HubTaskForm(store: store, projectId: value.detail.task.projectId, action: value.action, snapshot: value.detail, frozenCandidate: value.candidate) }
    }
    .sheet(item: $dispatchPresentation) { value in
      NavigationStack { HubDispatchForm(store: store, detail: value.detail) }.taskHubDismissOnBusinessNavigation(store)
    }
  }
  @ViewBuilder private var tabPicker: some View {
    if typeSize.isAccessibilitySize { tabs.pickerStyle(.menu) }
    else { tabs.pickerStyle(.segmented) }
  }
  private var tabs: some View {
    Picker(L10n.tr("详情分段"), selection: $tab) { ForEach(HubDetailTab.allCases, id: \.self) { Text($0.title).tag($0) } }
      .accessibilityIdentifier("task-hub-detail-tabs")
  }
  @ViewBuilder private func detailSections(_ detail: TaskHubDetail) -> some View {
    switch tab {
    case .requirements:
      Section(L10n.tr("原始需求")) { Text(detail.task.description).textSelection(.enabled) }
      Section(L10n.tr("验收标准")) { Text(detail.task.acceptanceCriteria).textSelection(.enabled); HubAttachmentList(store: store, attachments: detail.task.attachments) }
      Section(L10n.tr("本轮反馈与负责人响应")) {
        ForEach(detail.current?.feedbacks ?? []) { feedback in
          HubFeedbackRow(store: store, feedback: feedback, active: writableFocus(detail), task: detail.task) { present(.response(feedback.id)) }
        }
        if detail.current?.feedbacks.isEmpty != false { Text(L10n.tr("本轮暂无反馈。")).foregroundStyle(.secondary) }
      }
    case .process:
      if !store.roundSummaries.isEmpty {
        ForEach(visibleRoundSummaries(detail)) { summary in
          Section {
            DisclosureGroup(isExpanded: Binding(get: { expanded.contains(summary.number) }, set: { value in
              if value { expanded.insert(summary.number); Task { await store.loadRound(summary.id) } }
              else { expanded.remove(summary.number) }
            })) {
              if let round = detail.rounds.first(where: { $0.id == summary.id }) {
                HubRoundHistory(store: store, round: round, detail: detail, candidate: candidate(round.number)) { present(.response($0)) }
              } else if store.online { ProgressView(L10n.tr("正在读取这一轮过程")).task { await store.loadRound(summary.id) } }
              else { Text(L10n.tr("这一轮尚未缓存，连接原主服务后读取。")).foregroundStyle(.secondary) }
            } label: {
              Text(L10n.tr("第 \(summary.number) 轮 · ") + (summary.number == detail.task.currentRound ? L10n.tr("当前轮") : L10n.tr("历史只读")) + " · " + hubRoundTitle(summary.status))
            }.accessibilityIdentifier("task-hub-round-\(summary.number)")
          }
        }
        if store.roundsNextCursor != nil { Button(L10n.tr("加载更早轮次")) { Task { await store.loadMoreRounds() } }.accessibilityIdentifier("task-hub-load-rounds") }
      } else { ForEach(detail.rounds.sorted { $0.number > $1.number }) { round in
        Section {
          DisclosureGroup(isExpanded: Binding(get: { expanded.contains(round.number) }, set: { if $0 { expanded.insert(round.number) } else { expanded.remove(round.number) } })) {
            HubRoundHistory(store: store, round: round, detail: detail, candidate: candidate(round.number)) { present(.response($0)) }
          } label: {
            Text(L10n.tr("第 \(round.number) 轮 · ") + (round.number == detail.task.currentRound ? L10n.tr("当前轮") : L10n.tr("历史只读")) + " · " + hubRoundTitle(round.status))
          }.accessibilityIdentifier("task-hub-round-\(round.number)")
        }
      } }
    case .evidence: HubEvidenceSections(store: store, detail: detail)
    case .delivery:
      Section(L10n.tr("必要验收")) {
        ForEach(hubAcceptances(detail.current)) { acceptance in
          HubAcceptanceRow(store: store, acceptance: acceptance, active: writableFocus(detail)) { present(.acceptance(acceptance.id, $0)) }
        }
        if hubAcceptances(detail.current).isEmpty { Text(L10n.tr("冻结候选后由服务端创建必要验收。")).foregroundStyle(.secondary) }
      }
      Section(L10n.tr("本轮候选自验")) {
        if let check = detail.current?.selfCheck { HubSelfCheckSummary(check: check) }
        else { Text(L10n.tr("尚未记录本轮候选自验。历史记录不补填通过证明。")).foregroundStyle(.secondary) }
      }
      Section(L10n.tr("本轮冻结候选")) {
        if let delivery = detail.current?.delivery { HubDeliverySummary(store: store, delivery: delivery, round: detail.current, candidate: candidate(detail.task.currentRound)) }
        else { Text(L10n.tr("尚未提交本轮候选。")).foregroundStyle(.secondary) }
      }
      Section(L10n.tr("最终交付门槛")) { HubClosingSummary(task: detail.task) }
      if let policy = detail.current?.policy ?? detail.task.workflowPolicy { Section(L10n.tr("本轮冻结策略")) { HubPolicySummary(policy: policy, store: store) } }
    case .runs: HubExecutionsSection(store: store, taskId: taskId, openExecution: openExecution)
    }
  }
  private func start(_ detail: TaskHubDetail) async {
    guard writableFocus(detail) else { error = L10n.tr("历史轮次只读，请先明确进入当前轮。"); return }
    starting = true; error = nil; defer { starting = false }
    do { _ = try await store.mutate(path: "tasks/\(taskId)/start", body: ["revision": detail.task.revision]) }
    catch { self.error = error.localizedDescription }
  }
  private func present(_ action: HubTaskAction) {
    if let detail {
      guard writableFocus(detail) else { error = L10n.tr("历史轮次只读，请先明确进入当前轮。"); return }
      if action == .finalize && candidate(detail.task.currentRound) == nil {
        Task {
          await loadFrozenCandidates()
          guard let current = self.detail, writableFocus(current), let frozen = candidate(current.task.currentRound) else {
            error = L10n.tr("本轮冻结候选尚未同步。连接原服务并刷新后再提交最终交付。"); return
          }
          presentation = HubTaskPresentation(action: action, detail: current, candidate: frozen)
        }
        return
      }
      presentation = HubTaskPresentation(action: action, detail: detail, candidate: candidate(detail.task.currentRound))
    }
  }
  private func candidate(_ round: Int) -> TaskHubJSON? {
    if let value = detail?.rounds.first(where: { $0.number == round })?.delivery?.candidate, value != .null { return value }
    guard rawDetail?["revision"]?.hubInt == detail?.task.revision else { return nil }
    guard let record = rawDetail?["rounds"]?.array.first(where: { $0["number"]?.hubInt == round }),
      let value = record["delivery"]?["candidate"], value != .null else { return nil }
    return value
  }
  private func loadFrozenCandidates() async {
    guard store.online, let detail else { return }
    guard store.binding?.service.capabilities.contains("task-history-pagination") != true,
      detail.rounds.contains(where: { $0.delivery != nil && $0.delivery?.candidate == nil }) else { rawDetail = nil; return }
    let scope = store.scope; let revision = detail.task.revision
    do {
      let value = try await store.read(TaskHubJSON.self, path: "tasks/" + taskId)
      guard !Task.isCancelled, store.scope == scope, store.detail?.task.revision == revision, value["id"]?.string == taskId, value["revision"]?.hubInt == revision else { return }
      rawDetail = value
      if self.error?.hasPrefix(L10n.tr("冻结材料尚未同步：")) == true { self.error = nil }
    } catch {
      guard !Task.isCancelled, !(error is CancellationError), (error as? URLError)?.code != .cancelled,
        store.scope == scope, store.detail?.task.revision == revision else { return }
      self.error = L10n.tr("冻结材料尚未同步：") + error.localizedDescription
    }
  }
}

private struct HubTaskSummary: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let detail: TaskHubDetail
  @ObservedObject var store: TaskHubStore
  var body: some View {
    Section {
      Text(detail.task.title).font(.title2.bold()).accessibilityIdentifier("task-hub-detail-title")
      LabeledContent(L10n.tr("类型"), value: ["feature":L10n.tr("功能"), "bug":L10n.tr("缺陷"), "chore":L10n.tr("维护")][detail.task.type] ?? detail.task.type)
      Text(detail.task.statusTitle + L10n.tr(" · 第 \(detail.task.currentRound) 轮 · ") + detail.task.priority).font(.subheadline)
      Text(hubStage(detail.task.workflowStage)).font(.subheadline).foregroundStyle(.secondary)
        .accessibilityIdentifier("task-hub-workflow-stage")
      LabeledContent(L10n.tr("负责人"), value: hubActor(detail.task.ownerId, store))
      LabeledContent(L10n.tr("开发电脑"), value: hubHost(detail.task.hostId, store))
      LabeledContent(L10n.tr("主要验收"), value: (detail.task.acceptanceMode == "human" ? L10n.tr("人工 · ") : "Agent · ") + hubActor(detail.task.reviewerId, store))
      if let date = detail.task.dueDate { HubValue(label: L10n.tr("截止日期"), value: hubFormattedDate(date)) }
      if detail.task.pendingResponses > 0 { Label(L10n.tr("\(detail.task.pendingResponses) 条待响应"), systemImage: "text.bubble").foregroundStyle(.orange) }
      Text(L10n.tr("版本 \(detail.task.revision) · ") + hubFormattedDate(detail.task.updatedAt)).font(.caption).foregroundStyle(.secondary)
    }
  }
}

private struct HubTaskActions: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let detail: TaskHubDetail
  var starting: Bool
  var start: () -> Void
  var open: (HubTaskAction) -> Void
  var dispatch: () -> Void
  @Environment(\.taskHubConflict) private var conflict
  private var owner: Bool { store.actor?.id == detail.task.ownerId }
  private var resolved: Bool { detail.current?.feedbacks.allSatisfy { $0.responses.last?.decision == "fix" } == true }
  var body: some View {
    HStack(spacing: 14) {
      if owner && detail.task.status == "todo" {
        Button(starting ? L10n.tr("提交中…") : L10n.tr("开始处理"), action: start).buttonStyle(.borderedProminent)
          .disabled(starting || !resolved || !hubWritable(store, conflict: conflict, path: "tasks/\(detail.task.id)/start"))
          .accessibilityIdentifier("task-hub-action-start")
      }
      Menu {
        Button(L10n.tr("添加反馈"), systemImage: "text.bubble") { open(.feedback) }.accessibilityIdentifier("task-hub-action-feedback")
        if owner && (detail.task.status == "doing" || detail.task.workflowStage == "closing") {
          Button(L10n.tr("记录进度")) { open(.progress) }.accessibilityIdentifier("task-hub-action-progress")
        }
        if owner && detail.task.status == "doing" {
          Button(L10n.tr("记录候选自验")) { open(.selfCheck) }.disabled(!resolved).accessibilityIdentifier("task-hub-action-self-check")
          Button(L10n.tr("提交冻结候选")) { open(.delivery) }.disabled(!resolved || detail.current?.selfCheck == nil || detail.current?.selfCheck?["checks"]?.array.contains(where: { $0["result"]?.string != "passed" }) == true)
            .accessibilityIdentifier("task-hub-action-delivery")
        }
        if owner || store.isAdmin {
          Button(L10n.tr("编辑任务")) { open(.edit) }.accessibilityIdentifier("task-hub-action-edit")
          if detail.task.workflowStage == "closing" {
            Button(L10n.tr("提交最终交付")) { open(.finalize) }.accessibilityIdentifier("task-hub-action-finalize")
          }
        }
        if detail.task.status != "done" {
          Button(L10n.tr("派发开发或验收")) { dispatch() }.accessibilityIdentifier("task-hub-action-dispatch")
        }
      } label: { Label(L10n.tr("本轮操作"), systemImage: "ellipsis.circle").frame(minHeight: 44) }
        .accessibilityIdentifier("task-hub-actions")
      Spacer(minLength: 0)
    }
    .padding(.horizontal).padding(.vertical, 6).background(.bar)
  }
}

private struct HubFeedbackRow: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var feedback: TaskHubFeedback
  var active: Bool
  var task: TaskHubTask
  var respond: () -> Void
  var body: some View {
    VStack(alignment: .leading, spacing: 10) {
      Text(feedback.title).font(.headline)
      Text(feedback.responses.last?.decision == "fix" ? L10n.tr("已明确处理") : feedback.responses.isEmpty ? L10n.tr("待负责人响应") : L10n.tr("已响应 · 待澄清")).font(.caption).foregroundStyle(feedback.responses.last?.decision == "fix" ? Color.secondary : Color.orange)
      Text(feedback.body).textSelection(.enabled)
      Text(hubActor(feedback.authorId, store) + " · " + hubFormattedDate(feedback.createdAt)).font(.caption).foregroundStyle(.secondary)
      Text(L10n.tr("应响应人：") + hubActor(feedback.responderId, store)).font(.caption).foregroundStyle(.secondary)
      HubAttachmentList(store: store, attachments: feedback.attachments)
      ForEach(feedback.responses) { response in
        VStack(alignment: .leading, spacing: 6) {
          Label(response.decision == "fix" ? L10n.tr("接受并处理") : L10n.tr("需要澄清"), systemImage: "arrow.turn.down.right").font(.subheadline.bold())
          Text(response.body).textSelection(.enabled)
          Text(hubActor(response.actorId, store) + " · " + hubFormattedDate(response.createdAt)).font(.caption).foregroundStyle(.secondary)
          HubAttachmentList(store: store, attachments: response.attachments)
        }.padding(.leading, 12)
      }
      if active && feedback.responderId == store.actor?.id && ["todo", "doing"].contains(task.status) {
        Button(feedback.responses.isEmpty ? L10n.tr("提交本人响应") : L10n.tr("补充本人响应"), action: respond).frame(minHeight: 44)
          .accessibilityIdentifier("task-hub-action-response-" + feedback.id)
      }
    }.padding(.vertical, 5).accessibilityElement(children: .contain).accessibilityIdentifier("task-hub-feedback-" + feedback.id)
  }
}

private struct HubRoundHistory: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var round: TaskHubRound
  var detail: TaskHubDetail
  var candidate: TaskHubJSON?
  var respond: (String) -> Void
  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      ForEach(round.feedbacks) { feedback in HubFeedbackRow(store: store, feedback: feedback, active: round.number == detail.task.currentRound && (store.focusedRoundId == nil || store.focusedRoundId == round.id), task: detail.task) { respond(feedback.id) } }
      if let selfCheck = round.selfCheck { Text(L10n.tr("候选自验")).font(.headline); HubSelfCheckSummary(check: selfCheck) }
      if let delivery = round.delivery { Text(L10n.tr("交付候选")).font(.headline); HubDeliverySummary(store: store, delivery: delivery, round: round, candidate: candidate) }
      ForEach(hubAcceptances(round)) { acceptance in HubAcceptanceRow(store: store, acceptance: acceptance, active: false, decide: { _ in }) }
      Text(L10n.tr("本轮时间线")).font(.headline)
      ForEach(detail.events.filter { $0.roundNumber == round.number }.sorted { $0.createdAt < $1.createdAt }) { event in
        HubEventRow(store: store, event: event)
      }
      if store.eventsNextCursor[round.id] != nil {
        Button(L10n.tr("加载更多本轮过程")) { Task { await store.loadMoreEvents(round.id) } }.accessibilityIdentifier("task-hub-load-events-" + round.id)
      }
    }
  }
}

private struct HubEventRow: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var event: TaskHubEvent
  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      Label(hubEventTitle(event.kind), systemImage: "circle.fill").font(.subheadline.bold())
      Text(event.body).textSelection(.enabled)
      Text(hubActor(event.actorId, store) + " · " + hubFormattedDate(event.createdAt)).font(.caption).foregroundStyle(.secondary)
      HubAttachmentList(store: store, attachments: event.attachments)
    }.padding(.vertical, 4).accessibilityIdentifier("task-hub-event-" + event.id)
  }
}

private struct HubEvidenceSections: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var detail: TaskHubDetail
  var body: some View {
    Section(L10n.tr("原始需求附件")) { HubAttachmentList(store: store, attachments: detail.task.attachments); if detail.task.attachments.isEmpty { Text(L10n.tr("无附件")).foregroundStyle(.secondary) } }
    ForEach(detail.rounds.sorted { $0.number > $1.number }) { round in
      Section(L10n.tr("第 \(round.number) 轮 · 反馈与响应")) {
        ForEach(round.feedbacks) { feedback in
          if !feedback.attachments.isEmpty { Text(feedback.title).font(.subheadline.bold()); HubAttachmentList(store: store, attachments: feedback.attachments) }
          ForEach(feedback.responses.filter { !$0.attachments.isEmpty }) { response in
            Text(L10n.tr("响应 · ") + hubActor(response.actorId, store)).font(.caption); HubAttachmentList(store: store, attachments: response.attachments)
          }
        }
      }
      Section(L10n.tr("第 \(round.number) 轮 · 过程证据")) {
        ForEach(detail.events.filter { $0.roundNumber == round.number && !$0.attachments.isEmpty }) { event in
          Text(hubEventTitle(event.kind) + " · " + hubFormattedDate(event.createdAt)).font(.caption)
          HubAttachmentList(store: store, attachments: event.attachments)
        }
        if let selfCheck = round.selfCheck { HubSelfCheckSummary(check: selfCheck) }
      }
      Section(L10n.tr("第 \(round.number) 轮 · 交付与验收证据")) {
        if let delivery = round.delivery { HubAttachmentList(store: store, attachments: delivery.attachments) }
        ForEach(hubAcceptances(round)) { acceptance in
          if !acceptance.attachments.isEmpty { Text(hubActor(acceptance.reviewerId, store)).font(.caption); HubAttachmentList(store: store, attachments: acceptance.attachments) }
        }
      }
    }
    if !store.online { Section { Text(L10n.tr("连接原主服务后才能下载证据附件。")).foregroundStyle(.secondary) } }
  }
}

private struct HubSelfCheckSummary: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let check: TaskHubJSON
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      if let candidate = check["candidate"] { HubCandidateSummary(candidate: candidate) }
      ForEach(Array((check["checks"]?.array ?? []).enumerated()), id: \.offset) { _, item in
        VStack(alignment: .leading, spacing: 5) {
          Label((item["name"]?.string ?? L10n.tr("检查项")) + " · " + (item["result"]?.string == "passed" ? L10n.tr("通过") : L10n.tr("未通过")), systemImage: item["result"]?.string == "passed" ? "checkmark.circle" : "exclamationmark.circle")
          Text(item["evidence"]?.string ?? L10n.tr("缺少证据")).font(.subheadline).foregroundStyle(.secondary).textSelection(.enabled)
        }
      }
      if let date = check["recordedAt"]?.string { Text(hubFormattedDate(date)).font(.caption).foregroundStyle(.secondary) }
    }
  }
}

private struct HubCandidateSummary: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let candidate: TaskHubJSON
  private var materials: [TaskHubJSON] { candidate["materials"]?.array ?? [] }
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      HubValue(label: L10n.tr("候选引用"), value: candidate["reference"]?.string ?? L10n.tr("未提供"))
      HubValue(label: L10n.tr("源码版本"), value: candidate["sourceRevision"]?.string ?? L10n.tr("未提供"))
      HubValue(label: L10n.tr("产物 SHA-256"), value: candidate["artifactDigest"]?.string ?? L10n.tr("未提供"))
      if let frozen = candidate["frozenAt"]?.string { HubValue(label: L10n.tr("冻结时间"), value: hubFormattedDate(frozen)) }
      if !materials.isEmpty {
        DisclosureGroup(L10n.tr("冻结材料 · \(materials.count) 项")) {
          ForEach(Array(materials.enumerated()), id: \.offset) { _, material in
            HubValue(label: material["name"]?.string ?? L10n.tr("材料"), value: (material["reference"]?.string ?? "") + "\n" + (material["sha256"]?.string ?? ""))
          }
        }
      }
    }
  }
}

private struct HubDeliverySummary: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let delivery: TaskHubDelivery
  let round: TaskHubRound?
  var candidate: TaskHubJSON? = nil
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(delivery.summary).textSelection(.enabled)
      HubValue(label: L10n.tr("冻结候选引用"), value: delivery.candidateRef)
      if let candidate { HubCandidateSummary(candidate: candidate) }
      else { Text(L10n.tr("结构化冻结材料尚未同步。")).font(.caption).foregroundStyle(.secondary) }
      Text(L10n.tr("覆盖 \(delivery.feedbackIds.count) 条反馈")).font(.subheadline)
      ForEach(delivery.feedbackIds, id: \.self) { id in Text(round?.feedbacks.first { $0.id == id }?.title ?? id).font(.caption).foregroundStyle(.secondary) }
      HubAttachmentList(store: store, attachments: delivery.attachments)
    }
  }
}

private struct HubAcceptanceRow: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let acceptance: TaskHubAcceptance
  var active: Bool
  var decide: (Bool) -> Void
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text((acceptance.mode == "human" ? L10n.tr("人工") : "Agent Host") + " · " + hubActor(acceptance.reviewerId, store)).font(.headline)
      Text(hubPhase(acceptance.status)).font(.subheadline).foregroundStyle(acceptance.status == "failed" ? Color.red : Color.secondary)
      HubValue(label: L10n.tr("被验候选"), value: acceptance.candidateRef)
      if acceptance.mode == "agent_host" {
        Text(L10n.tr("实际验收电脑：") + hubHost(acceptance.hostId, store)).font(.caption).foregroundStyle(.secondary)
        if acceptance.status == "pending" { Text(L10n.tr("等待真实 Agent 运行回报，可在执行记录中核对。手机不能代 Agent 给出通过结论。")).font(.subheadline).foregroundStyle(.secondary) }
      }
      if let note = acceptance.decisionNote, !note.isEmpty { Text(note).textSelection(.enabled) }
      if let date = acceptance.decidedAt { Text(hubFormattedDate(date)).font(.caption).foregroundStyle(.secondary) }
      HubAttachmentList(store: store, attachments: acceptance.attachments)
      if active && acceptance.status == "pending" && acceptance.mode == "human" && acceptance.reviewerId == store.actor?.id && store.actor?.kind == "human" {
        Button { decide(true) } label: { Text(L10n.tr("人工验收通过")).frame(minHeight: 44) }
          .buttonStyle(.bordered).accessibilityIdentifier("task-hub-action-pass-" + acceptance.id)
        Button(role: .destructive) { decide(false) } label: { Text(L10n.tr("人工验收不通过")).frame(minHeight: 44) }
          .buttonStyle(.bordered).accessibilityIdentifier("task-hub-action-fail-" + acceptance.id)
      }
    }.padding(.vertical, 4).accessibilityElement(children: .contain).accessibilityIdentifier("task-hub-acceptance-" + acceptance.id)
  }
}

private struct HubClosingSummary: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let task: TaskHubTask
  var body: some View {
    if let closing = task.closing {
      LabeledContent(L10n.tr("服务端关闭状态"), value: ["blocked":L10n.tr("待补齐"), "ready":L10n.tr("可关闭"), "complete":L10n.tr("已完成"), "legacy":L10n.tr("历史记录")][closing["status"]?.string ?? ""] ?? L10n.tr("未提供"))
        .accessibilityIdentifier("task-hub-closing-state")
      ForEach(Array((closing["missing"]?.array.compactMap(\.string) ?? []).enumerated()), id: \.offset) { _, item in Label(item, systemImage: "exclamationmark.circle").font(.subheadline).foregroundStyle(.orange) }
      if let finalization = closing["finalization"], finalization != .null {
        HubValue(label: L10n.tr("最终交付候选"), value: finalization["candidateRef"]?.string ?? L10n.tr("未提供"))
        HubValue(label: L10n.tr("产物 SHA-256"), value: finalization["artifactDigest"]?.string ?? L10n.tr("未提供"))
        if let commit = finalization["commitRef"]?.string { HubValue(label: L10n.tr("最终提交"), value: commit) }
        if let reason = finalization["noCommitReason"]?.string { HubValue(label: L10n.tr("无需提交说明"), value: reason) }
        ForEach(Array((finalization["indexes"]?.array ?? []).enumerated()), id: \.offset) { _, item in HubValue(label: item["name"]?.string ?? L10n.tr("索引"), value: item["reference"]?.string ?? L10n.tr("未提供")) }
      }
    } else { Text(L10n.tr("主服务未提供结构化关闭证明，不能据此宣称完整交付通过。")).font(.subheadline).foregroundStyle(.secondary) }
  }
}

private func hubRoundTitle(_ value: String) -> String { ["open":L10n.tr("处理中"), "delivered":L10n.tr("已交付"), "accepted":L10n.tr("必要验收已通过"), "rejected":L10n.tr("已拒绝")][value] ?? value }
private func hubEventTitle(_ value: String) -> String { ["task.created":L10n.tr("创建任务"), "claim.accepted":L10n.tr("开始处理"), "feedback.created":L10n.tr("新反馈"), "feedback.responded":L10n.tr("负责人响应"), "progress.reported":L10n.tr("开发进度"), "acceptance.progress":L10n.tr("验收进度"), "delivery.submitted":L10n.tr("冻结候选"), "acceptance.decided":L10n.tr("验收结论"), "host.dispatched":L10n.tr("设备派发"), "self_check.recorded":L10n.tr("候选自验"), "task.finalized":L10n.tr("最终交付"), "requirement.revised":L10n.tr("需求修订"), "task.updated":L10n.tr("任务资料更新")][value] ?? L10n.tr("任务更新") }
private func hubDate(_ value: String) -> Date? {
  let parser = ISO8601DateFormatter(); parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
  return parser.date(from: value) ?? ISO8601DateFormatter().date(from: value)
}
private func hubFormattedDate(_ value: String) -> String {
  guard let date = hubDate(value) else { return value }
  let formatter = DateFormatter(); formatter.locale = L10n.locale; formatter.dateStyle = .medium; formatter.timeStyle = .short
  formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
  return formatter.string(from: date)
}

private struct HubExecutionsSection: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let taskId: String
  var openExecution: ((TaskHubExecutionNavigation) async throws -> Void)?
  @State private var opening: String?
  @State private var error: String?
  var body: some View {
    Section {
      Text(L10n.tr("执行状态与业务状态分别记录。历史会话和执行目标不证明电脑运行时当前在线。")).font(.subheadline).foregroundStyle(.secondary)
      if let error { Text(error).foregroundStyle(.red) }
    }
    ForEach(["executor", "reviewer"], id: \.self) { role in
      Section(role == "executor" ? L10n.tr("开发执行") : L10n.tr("验收执行")) {
        ForEach(store.runs.filter { $0.taskId == taskId && $0.role == role }) { run in
          HubRunRow(store: store, run: run, opening: opening == run.id, canOpen: openExecution != nil && opening == nil) { Task { await open(run) } }
        }
        if !store.runs.contains(where: { $0.taskId == taskId && $0.role == role }) { Text(L10n.tr("暂无真实执行记录")).foregroundStyle(.secondary) }
      }
    }
    Section { Button(L10n.tr("刷新执行记录")) { Task { await store.loadDetail(taskId) } }.disabled(!store.online) }
  }
  private func open(_ run: TaskHubRun) async {
    guard let openExecution, let target = run.target, let thread = run.threadId, !thread.isEmpty else { return }
    opening = run.id; error = nil; defer { opening = nil }
    do {
      try await openExecution(TaskHubExecutionNavigation(target: target, threadId: thread, remoteProjectId: run.remoteProjectId, runId: run.id))
    } catch { self.error = error.localizedDescription }
  }
}

private struct HubRunRow: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var run: TaskHubRun
  var opening: Bool
  var canOpen: Bool
  var open: () -> Void
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(hubPhase(run.state)).font(.headline)
      targetRows
      receiptRows
      Button(opening ? L10n.tr("核对目标中…") : L10n.tr("查看关联对话"), action: open)
        .disabled(!canOpen || run.target == nil || run.threadId?.isEmpty != false)
        .accessibilityIdentifier("task-hub-run-open-" + run.id)
      if ["uncertain", "prepared", "submitted", "queued"].contains(run.state) {
        Text(L10n.tr("由主服务核对原执行回执，不能重新派发相同输入。刷新会读取实际运行记录。")).font(.caption).foregroundStyle(.secondary)
      }
    }.padding(.vertical, 4).accessibilityIdentifier("task-hub-run-" + run.id)
  }
  @ViewBuilder private var targetRows: some View {
    HubValue(label: L10n.tr("电脑"), value: hubHost(run.hostId, store))
    HubValue(label: L10n.tr("电脑项目"), value: run.remoteProjectId)
    if let target = run.target {
      HubValue(label: "Agent", value: target.agentId)
      HubValue(label: L10n.tr("运行目标"), value: [target.hostId, target.workspaceId, target.runtimeId].joined(separator: " / "))
      if let account = target.account { HubValue(label: L10n.tr("运行账户"), value: account.authority + " / " + account.subject) }
    } else { Text(L10n.tr("服务端尚未提供完整运行目标，关联对话不可打开。")).font(.caption).foregroundStyle(.secondary) }
    if let thread = run.threadId { HubValue(label: L10n.tr("原会话"), value: thread) }
    else { Text(L10n.tr("尚未确认原会话")).font(.caption).foregroundStyle(.secondary) }
    if let turn = run.turnId { HubValue(label: L10n.tr("原模型轮"), value: turn) }
  }
  @ViewBuilder private var receiptRows: some View {
    HubValue(label: L10n.tr("原操作 ID"), value: run.operationId)
    LabeledContent(L10n.tr("请求推理档位"), value: run.requestedEffort)
    LabeledContent(L10n.tr("实际推理档位"), value: run.actualEffort ?? L10n.tr("尚未核对"))
    Text(L10n.tr("更新于 ") + hubFormattedDate(run.updatedAt)).font(.caption).foregroundStyle(.secondary)
    if let message = run.lastError { Text(message).font(.subheadline).foregroundStyle(.red) }
  }
}

private struct HubDispatchForm: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let detail: TaskHubDetail
  @Environment(\.dismiss) private var dismiss
  @Environment(\.taskHubConflict) private var conflict
  @State private var role = "executor"
  @State private var bindingId = ""
  @State private var bindings: [TaskHubProjectBinding] = []
  @State private var submitting = false
  @State private var error: String?
  private var path: String { "tasks/\(detail.task.id)/dispatches" }
  private var candidates: [TaskHubProjectBinding] {
    bindings.filter { binding in
      guard binding.role == role && binding.status == "verified" else { return false }
      if role == "executor" { return binding.actorId == detail.task.ownerId }
      return hubAcceptances(detail.current).contains { $0.mode == "agent_host" && $0.reviewerId == binding.actorId && $0.status == "pending" }
    }
  }
  private var stageAllows: Bool {
    role == "executor" ? (["todo", "doing"].contains(detail.task.status) || detail.task.workflowStage == "closing") : detail.task.status == "review" && detail.task.workflowStage == "acceptance"
  }
  var body: some View {
    Form {
      Section(L10n.tr("准确任务目标")) {
        Text(detail.task.key + L10n.tr(" · 第 \(detail.task.currentRound) 轮 · 版本 \(detail.task.revision)"))
        Picker(L10n.tr("执行角色"), selection: $role) { Text(detail.task.workflowStage == "closing" ? L10n.tr("最终交付") : L10n.tr("开发")).tag("executor"); Text(L10n.tr("Agent 验收")).tag("reviewer") }
          .onChange(of: role) { _, _ in bindingId = "" }
        Picker(L10n.tr("已核验映射"), selection: $bindingId) {
          Text(L10n.tr("选择准确执行空间")).tag("")
          ForEach(candidates) { Text($0.remoteProjectName + " · " + hubHost($0.hostId, store)).tag($0.id) }
        }.accessibilityIdentifier("task-hub-dispatch-binding")
        if let selected = candidates.first(where: { $0.id == bindingId }) {
          HubValue(label: "Host ID", value: selected.hostId)
          HubValue(label: L10n.tr("电脑项目"), value: selected.remoteProjectId)
          HubValue(label: L10n.tr("根目录"), value: selected.root)
          LabeledContent(L10n.tr("执行身份"), value: hubActor(selected.actorId, store))
        }
        Text(L10n.tr("由主服务核对在线能力、项目源、权限和原执行意图。请求 xhigh；实际档位以执行回执为准。")).font(.subheadline).foregroundStyle(.secondary)
      }
      if candidates.isEmpty { Section { HubNotice(title: L10n.tr("没有可用映射"), message: L10n.tr("开发映射须属于任务负责人；验收映射须属于本輪待验收 Agent。请管理员在项目设置核验。"), warning: true) } }
      if !stageAllows { Section { Text(L10n.tr("当前业务阶段不能派发此角色。")).foregroundStyle(.secondary) } }
      if store.detail?.task.id == detail.task.id && store.detail?.task.revision != detail.task.revision { Section { Text(L10n.tr("任务版本已变化，请关闭后核对当前轮再派发。")).foregroundStyle(.orange) } }
      HubWriteState(store: store, path: path)
      if let error { Section { Text(error).foregroundStyle(.red) } }
    }.navigationTitle(detail.task.workflowStage == "closing" ? L10n.tr("派发最终交付") : L10n.tr("派发真实执行"))
      .taskHubConsent(store, path: path)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("关闭")) { dismiss() } }
        ToolbarItem(placement: .confirmationAction) {
          Button(submitting ? L10n.tr("提交中…") : L10n.tr("派发")) { Task { await submit() } }
            .disabled(!stageAllows || !candidates.contains(where: { $0.id == bindingId }) || submitting || !hubWritable(store, conflict: conflict, path: path) || (store.detail?.task.id == detail.task.id && store.detail?.task.revision != detail.task.revision))
            .accessibilityIdentifier("task-hub-submit")
        }
      }
      .task {
        do { bindings = try await store.read([TaskHubProjectBinding].self, path: "projects/\(detail.task.projectId)/host-bindings") }
        catch { self.error = error.localizedDescription }
        if detail.task.status == "review" && detail.task.workflowStage == "acceptance" { role = "reviewer" }
      }
  }
  private func submit() async {
    submitting = true; error = nil; defer { submitting = false }
    guard store.focusedRoundId == nil || store.focusedRoundId == detail.current?.id else { error = L10n.tr("历史轮次只读，请先明确进入当前轮。"); return }
    do { _ = try await store.mutate(path: path, body: ["revision": detail.task.revision, "bindingId": bindingId, "role": role]); dismiss() }
    catch { self.error = error.localizedDescription }
  }
}
