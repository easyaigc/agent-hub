import SwiftUI
import UniformTypeIdentifiers

struct AppIconBadgeToggle: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject private var controller = TaskNotificationController.shared

  var body: some View {
    Toggle(L10n.tr("显示 App 图标角标"), isOn: Binding(
      get: { controller.preferences.badgeEnabled },
      set: { value in controller.update { $0.badgeEnabled = value } }
    )).accessibilityIdentifier("notification-badge-enabled")
  }
}

struct TaskNotificationSettingsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  @ObservedObject var controller = TaskNotificationController.shared
  @State private var importing = false
  private func toggle(_ key: WritableKeyPath<TaskNotificationPreferences, Bool>) -> Binding<Bool> {
    Binding(get: { controller.preferences[keyPath: key] }, set: { value in controller.update { $0[keyPath: key] = value } })
  }
  var body: some View {
    Form {
      Section {
        Toggle(L10n.tr("消息通知"), isOn: Binding(get: { controller.preferences.enabled }, set: { controller.enable($0) })).accessibilityIdentifier("notifications-enabled")
        Text(controller.authorization).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("notification-permission")
        if controller.preferences.enabled {
          Text(controller.apnsRegistration).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("notification-apns-registration")
        }
        Button(L10n.tr("打开系统通知设置")) { if let url = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(url) } }
      } footer: { Text(L10n.tr("电脑继续执行任务。开启后可在前台、后台和锁屏收到提醒；后台提醒需要电脑接入推送服务。")) }
      Section(L10n.tr("通知类型")) {
        ForEach(TaskNoticeKind.allCases) { kind in
          HStack {
            NavigationLink { TaskNotificationCategorySettingsView(controller: controller, kind: kind) } label: { Text(kind.title) }
            Toggle(kind.title, isOn: kindBinding(kind)).labelsHidden().accessibilityIdentifier("notification-kind-" + kind.rawValue)
          }
        }
      }
      Section(L10n.tr("电脑")) {
        ForEach(store.saved.clients.filter { !$0.isDemo }) { host in
          VStack(alignment: .leading, spacing: 5) {
            Toggle(host.name, isOn: hostBinding(host)).accessibilityIdentifier("notification-host-" + host.id)
            Text(controller.statuses[host.id]?.label ?? (controller.preferences.enabled ? L10n.tr("等待连接") : L10n.tr("已关闭"))).font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("notification-status-" + host.id)
            if let reason = controller.statuses[host.id]?.reason { Text(reason).font(.caption2).foregroundStyle(.secondary).accessibilityIdentifier("notification-reason-" + host.id) }
            if let delivery = controller.statuses[host.id]?.lastDelivery {
              Text(L10n.tr("最近投递：") + delivery.label).font(.caption2).foregroundStyle(.secondary).accessibilityIdentifier("notification-delivery-" + host.id)
            }
          }
        }
      }
      Section {
        Toggle(L10n.tr("通知声音"), isOn: toggle(\.soundEnabled)).accessibilityIdentifier("notification-sound-enabled")
        Picker(L10n.tr("默认铃声"), selection: Binding(get: { controller.preferences.sound }, set: { value in controller.update { $0.sound = value } })) {
          Text(L10n.tr("静音")).tag("none"); ForEach(controller.sounds) { Text($0.title).tag($0.id) }
        }.accessibilityIdentifier("notification-sound-picker")
        ForEach(controller.sounds.filter { $0.id != "default" }) { item in
          HStack {
            Button { controller.preview(item.id) } label: { Label(item.title, systemImage: "play.circle") }
            Spacer()
            if item.imported { Button(role: .destructive) { controller.deleteSound(item) } label: { Image(systemName: "trash") }.accessibilityLabel(L10n.tr("删除 ") + item.title) }
          }
        }
        Button(L10n.tr("导入铃声"), systemImage: "square.and.arrow.down") { importing = true }.accessibilityIdentifier("notification-import-sound")
      } header: { Text(L10n.tr("声音")) } footer: { Text(L10n.tr("支持 WAV、AIFF、CAF、M4A 和 MP3，最多 10 MB、29 秒。铃声仅保存在这部手机。音量和振动遵循系统设置。")) }
      Section {
        Toggle(L10n.tr("前台显示横幅"), isOn: toggle(\.foregroundBanner))
        AppIconBadgeToggle()
        Toggle(L10n.tr("显示会话标题与待处理概要"), isOn: toggle(\.showThreadTitle)).accessibilityIdentifier("notification-content-preview")
        Toggle(L10n.tr("及时通知"), isOn: toggle(\.timeSensitive))
      } header: { Text(L10n.tr("显示与提醒")) } footer: { Text(L10n.tr("通知优先显示会话标题，正文显示待审批操作、具体问题、待确认计划或需要核对的原操作。新设置默认开启内容预览；已有关闭选择保持。关闭后仅显示电脑和通知类型。角标按手机未读对话计数，电脑上的阅读不影响手机。及时通知遵循系统权限和专注模式。")) }
      Section { Button(L10n.tr("发送测试通知"), systemImage: "bell.badge") { controller.testNotification() }.accessibilityIdentifier("notification-test") }
    }
    .navigationTitle(L10n.tr("通知")).navigationBarTitleDisplayMode(.inline)
    .fileImporter(isPresented: $importing, allowedContentTypes: [.audio]) { result in
      switch result { case .success(let url): controller.importSound(url); case .failure(let error): controller.error = error.localizedDescription }
    }
    .alert(L10n.tr("通知"), isPresented: Binding(get: { controller.error != nil }, set: { if !$0 { controller.error = nil } })) { Button(L10n.tr("好")) { controller.error = nil } } message: { Text(controller.error ?? "") }
    .task { await controller.refreshPermission() }
  }
  private func kindBinding(_ kind: TaskNoticeKind) -> Binding<Bool> {
    Binding(get: { controller.preferences.kinds.contains(kind) }, set: { enabled in controller.update { prefs in
      prefs.kinds.removeAll { $0 == kind }
      if enabled { prefs.kinds.append(kind) }
    } })
  }
  private func hostBinding(_ host: Client) -> Binding<Bool> {
    let id = controller.hostIdentifier(host)
    return Binding(get: { !controller.preferences.excludedHosts.contains(id) }, set: { enabled in
      controller.update { prefs in
        prefs.excludedHosts.removeAll { $0 == id }
        if !enabled { prefs.excludedHosts.append(id) }
      }
    })
  }
}

private struct TaskNotificationCategorySettingsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var controller: TaskNotificationController
  let kind: TaskNoticeKind
  private var enabled: Binding<Bool> {
    Binding(get: { controller.preferences.kinds.contains(kind) }, set: { value in
      controller.update { prefs in
        prefs.kinds.removeAll { $0 == kind }
        if value { prefs.kinds.append(kind) }
      }
    })
  }
  private var sound: Binding<String> {
    Binding(get: { controller.preferences.categorySounds[kind.rawValue] ?? "inherit" }, set: { value in
      controller.update { prefs in
        if value == "inherit" { prefs.categorySounds.removeValue(forKey: kind.rawValue) }
        else { prefs.categorySounds[kind.rawValue] = value }
      }
    })
  }
  var body: some View {
    Form {
      Toggle(L10n.tr("接收此类通知"), isOn: enabled)
      Picker(L10n.tr("铃声"), selection: sound) {
        Text(L10n.tr("跟随默认铃声")).tag("inherit")
        Text(L10n.tr("静音")).tag("none")
        ForEach(controller.sounds) { Text($0.title).tag($0.id) }
      }
    }.navigationTitle(kind.title)
  }
}
