import SwiftUI

struct ClientPickerView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @ObservedObject var store: SessionStore
    @ObservedObject private var notifications = TaskNotificationController.shared
    @State private var editingClient: Client?
    var body: some View {
        NavigationStack {
            List {
                ForEach(store.saved.clients) { client in
                    HStack(spacing: 12) {
                        Button {
                            store.selectClient(client.id)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "laptopcomputer").font(.title3)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(client.name)
                                    ClientUsageSummary(client: client, target: store.mobileReadingTarget(client))
                                    Text(store.sourceLabel(for: client))
                                        .font(.caption).foregroundStyle(Palette.secondary)
                                    let running = store.runningConversationCount(for: client).map { $0.formatted() } ?? "—"
                                    let unread = store.unreadCount(for: client, snapshot: notifications.unreadSnapshot)
                                    ViewThatFits(in: .horizontal) {
                                        HStack(spacing: 6) {
                                            Text(store.connectionLabel(for: client)).foregroundStyle(Palette.secondary)
                                            clientCounts(client, running: running, unread: unread)
                                        }
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(store.connectionLabel(for: client)).foregroundStyle(Palette.secondary)
                                            clientCounts(client, running: running, unread: unread)
                                        }
                                    }.font(.caption2)
                                }
                                Spacer()
                                if client.id == store.client?.id { Image(systemName: "checkmark") }
                            }.frame(minHeight: 48).contentShape(Rectangle())
                        }.buttonStyle(.plain).foregroundStyle(Color.primary)
                            .accessibilityIdentifier("client-\(client.id)")
                            .accessibilityAddTraits(client.id == store.client?.id ? .isSelected : [])
                            .accessibilityValue(L10n.tr("\(store.unreadCount(for: client, snapshot: notifications.unreadSnapshot))个未读对话，\(store.connectionLabel(for: client))，\(store.runningConversationCount(for: client).map { L10n.tr("\($0)个会话进行中") } ?? L10n.tr("进行中数量未知"))"))
                        if !client.isDemo {
                            Button(L10n.tr("编辑")) { editingClient = client }
                                .buttonStyle(.borderless).frame(minHeight: 44)
                                .accessibilityLabel(L10n.tr("编辑\(client.name)"))
                                .accessibilityIdentifier("edit-client-\(client.id)")
                        }
                    }.swipeActions {
                        Button(L10n.tr("移除连接"), role: .destructive) { store.removeClient(client.id) }
                    }
                }
                Button {
                    store.sheet = .addClient
                } label: {
                    Label(L10n.tr("添加客户端"), systemImage: "plus")
                }.accessibilityIdentifier("add-client")
            }.navigationTitle(L10n.tr("客户端")).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.tr("完成")) { store.sheet = nil }
                    }
                }
        }.presentationDetents([.medium, .large])
            .sheet(item: $editingClient) { client in
                AddClientView(store: store, clientToEdit: client)
            }
    }
    private func clientCounts(_ client: Client, running: String, unread: Int) -> some View {
        HStack(spacing: 6) {
            Text("·").foregroundStyle(Palette.secondary)
            Text(L10n.tr("进行中 \(running)"))
                .foregroundStyle(Palette.secondary).monospacedDigit()
                .accessibilityIdentifier("client-running-\(client.id)")
            Text("·").foregroundStyle(Palette.secondary)
            Text(L10n.tr("未读 \(unread.formatted())"))
                .fontWeight(unread > 0 ? .semibold : .regular)
                .foregroundStyle(unread > 0 ? Color.blue : Palette.secondary).monospacedDigit()
                .accessibilityIdentifier("client-unread-\(client.id)")
        }.fixedSize(horizontal: true, vertical: false)
    }
}

@MainActor enum AgentConfigurationViews {
    struct Registration: Identifiable { let id: String; let name: String; let form: (SessionStore, Client?) -> AnyView; let accepts: (URL) -> Bool }
    private static var registrations: [Registration] = [
        Registration(id: "codex", name: "Codex", form: { AnyView(CodexConnectionForm(store: $0, clientToEdit: $1)) }, accepts: { $0.scheme == "codex-host" && $0.host == "pair" }),
        Registration(id: "easywork", name: "EasyWork", form: { AnyView(EasyWorkConnectionForm(store: $0, clientToEdit: $1)) }, accepts: { ["easywork-host", "easywork"].contains($0.scheme ?? "") && $0.host == "pair" }),
    ]
    private static var linkPresentation = UUID()
    static func open(_ url: URL, store: SessionStore) {
        guard let registration = registrations.first(where: { $0.accepts(url) }) else { return }
        if let client = store.client { store.invalidateAgentConnection(client.id) }
        let presentation = UUID(), target = store.targetKey
        linkPresentation = presentation
        store.error = nil
        store.sheet = nil
        store.pendingConnection = (registration.id, url.absoluteString, presentation)
        // Let the previous alert or sheet finish dismissing before presenting this link.
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(350))
            guard linkPresentation == presentation, store.pendingConnection?.payload == url.absoluteString else { return }
            guard store.targetKey == target, store.sheet == nil else { store.finishConnectionLink(); return }
            store.error = nil
            store.sheet = .addClient
        }
    }
    static var all: [Registration] { registrations }
    static func register(_ registration: Registration) { registrations.removeAll { $0.id == registration.id }; registrations.append(registration) }
    static func form(_ id: String, store: SessionStore, client: Client?) -> AnyView { registrations.first { $0.id == id }?.form(store, client) ?? AnyView(Text(L10n.tr("此 Agent 的配置界面尚未注册"))) }
}

struct AddClientView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @ObservedObject var store: SessionStore
    @Environment(\.dismiss) private var dismiss
    let clientToEdit: Client?
    @State private var agentId: String
    @State private var connectionLinkPresentation: UUID?
    init(store: SessionStore, clientToEdit: Client? = nil) { self.store = store; self.clientToEdit = clientToEdit; _agentId = State(initialValue: clientToEdit?.agentConnection?.target.agentId ?? store.pendingConnection?.agentId ?? "codex"); _connectionLinkPresentation = State(initialValue: store.pendingConnection?.presentation) }
    var body: some View {
        NavigationStack {
            Form {
                if clientToEdit == nil { Picker(L10n.tr("Agent 客户端"), selection: $agentId) { ForEach(AgentConfigurationViews.all) { Text($0.name).tag($0.id) } }.pickerStyle(.segmented).accessibilityIdentifier("agent-type") }
                AgentConfigurationViews.form(agentId, store: store, client: clientToEdit)
            }.navigationTitle(clientToEdit == nil ? L10n.tr("添加客户端") : L10n.tr("编辑客户端")).navigationBarTitleDisplayMode(.inline)
             .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button(L10n.tr("完成输入")) { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) } }; ToolbarItem(placement: .cancellationAction) { Button(clientToEdit == nil ? L10n.tr("取消") : L10n.tr("完成")) { if clientToEdit != nil { dismiss() } else { store.finishConnectionLink(); store.sheet = .clients } }.accessibilityIdentifier("cancel-client-form") } }
        }.onAppear {
            connectionLinkPresentation = store.pendingConnection?.presentation
        }.onDisappear {
            if store.sheet != .addClient, let presentation = connectionLinkPresentation { store.finishConnectionLink(presentation: presentation) }
        }
    }
}

struct CodexConnectionForm: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    private enum ConnectionMethod: String, CaseIterable {
        case discovery = "自动发现"
        case qr = "扫码连接"
        case manual = "手动连接"

  var title: String {
    switch self {
    case .discovery: return L10n.tr("自动发现")
    case .qr: return L10n.tr("扫码连接")
    case .manual: return L10n.tr("手动连接")
    }
  }
}

    @ObservedObject var store: SessionStore
    @Environment(\.dismiss) private var dismiss
    private let clientToEdit: Client?
    @State private var name: String
    @State private var address: String
    @State private var connecting = false
    @State private var formError: String?
    @State private var token: String
    @ObservedObject private var discovery = CodexHostDiscovery.shared
    @ObservedObject private var vpnDiscovery = VPNHostDiscovery.shared
    @State private var showScanner = false
    @State private var qrText = ""
    @State private var connectionMethod = ConnectionMethod.discovery
    @State private var authorizationHost: CodexHostDiscovery.Host?
    @State private var hostKey = ""
    @State private var failedKnownHost: CodexHostDiscovery.Host?

    init(store: SessionStore, clientToEdit: Client? = nil) {
        self.store = store
        self.clientToEdit = clientToEdit
        _name = State(initialValue: clientToEdit?.name ?? L10n.tr("本机 Mac"))
        _address = State(initialValue: clientToEdit?.endpoint ?? "http://127.0.0.1:8788")
        _token = State(initialValue: clientToEdit.map { CredentialVault.token($0.id) } ?? "")
    }
    var body: some View {
        Group {
            if clientToEdit == nil {
                Picker(L10n.tr("连接方式"), selection: $connectionMethod) {
                    ForEach(ConnectionMethod.allCases, id: \.self) { method in
                        Text(method.title).tag(method)
                    }
                }
                .pickerStyle(.segmented)
                .frame(minHeight: 44)
                .accessibilityIdentifier("codex-connection-method")
            }
            if clientToEdit != nil || connectionMethod == .manual {
                manualConnection
            } else if connectionMethod == .qr {
                qrConnection
            } else {
                discoveredHosts
            }
            Section {
                Text(L10n.tr("默认使用局域网与 Tailscale HTTPS 双通道，自动切换并保留同一台电脑的连接和草稿。只在局域网使用可不安装 Tailscale；需要在外远程控制，请为手机和电脑配置 Tailscale 并保持在线。"))
                    .font(.footnote).foregroundStyle(Palette.secondary)
                    .accessibilityIdentifier("codex-dual-channel-help")
            }
        }.disabled(connecting).interactiveDismissDisabled(connecting)
        .onChange(of: connectionMethod) { _, _ in
            formError = nil
            authorizationHost = nil; hostKey = ""; failedKnownHost = nil
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
        .task {
            if let pending = store.pendingConnection, pending.agentId == "codex" {
                connectionMethod = .qr
                qrText = pending.payload
            }
            await discovery.scan(store)
            await vpnDiscovery.scan(store)
        }
        .sheet(isPresented: $showScanner) { EasyWorkQRScanner(scanned: { text in qrText = text; showScanner = false; connectQR() }, prefix: "codex-host://pair?") }
    }

    private var qrConnection: some View {
        Section {
            Button(L10n.tr("扫描电脑二维码"), systemImage: "qrcode.viewfinder") { showScanner = true }
                .accessibilityIdentifier("codex-scan-qr")
            TextField(L10n.tr("或粘贴连接二维码内容"), text: $qrText)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .accessibilityIdentifier("codex-qr-description")
            if let formError {
                Text(formError).foregroundStyle(.red).accessibilityIdentifier("client-form-error")
            }
            Button { connectQR() } label: {
                HStack {
                    Text(connecting ? L10n.tr("正在连接…") : L10n.tr("使用二维码连接"))
                    Spacer()
                    if connecting { ProgressView() }
                }
            }
            .disabled(qrText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .accessibilityIdentifier("codex-connect-qr")
        } footer: {
            Text(L10n.tr("可扫描包含密钥的连接信息，或自动发现后输入 Host Key 授权。"))
        }
    }

    @ViewBuilder private var discoveredHosts: some View {
        if let host = authorizationHost {
            Section {
                Text(host.name)
                SecureField(L10n.tr("Host Key"), text: $hostKey)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("codex-host-key")
                if let formError { Text(formError).foregroundStyle(.red).accessibilityIdentifier("client-form-error") }
                Button(L10n.tr("授权并连接")) { connectDiscovered(host) }
                    .disabled(hostKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("codex-authorize-connect")
                Button(L10n.tr("取消")) { authorizationHost = nil; hostKey = ""; formError = nil }
                    .accessibilityIdentifier("codex-authorize-cancel")
            } header: { Text(L10n.tr("首次连接授权")) } footer: {
                Text(L10n.tr("请从这台电脑的私有密钥文件读取 Host Key。授权后将安全保存设备凭据，后续自动重连。"))
            }
        }
        Section {
            ForEach(discoveryRows) { host in
                Button {
                    authorizationHost = nil; hostKey = ""
                    connectDiscovered(host)
                } label: {
                    HStack {
                        Image(systemName: "desktopcomputer")
                        VStack(alignment: .leading, spacing: 4) {
                            Text(host.name)
                            Text(discoveryDetail(host))
                                .font(.caption).foregroundStyle(Palette.secondary)
                        }
                        Spacer()
                        if store.saved.clients.contains(where: { $0.id == host.id && $0.online }) {
                            Image(systemName: "checkmark").foregroundStyle(.green)
                        }
                    }
                }.buttonStyle(.plain).foregroundStyle(Color.primary).disabled(connecting)
                    .accessibilityIdentifier("codex-discovered-\(host.id)")
                    .accessibilityValue(host.endpoint)
            }
            if discoveryRows.isEmpty {
                Text(L10n.tr("正在查找同一网络的电脑…")).foregroundStyle(Palette.secondary)
            }
            Button(L10n.tr("重新查找"), systemImage: "arrow.clockwise") { Task { await discovery.scan(store); await vpnDiscovery.scan(store) } }
        } header: { Text(L10n.tr("自动发现")) } footer: {
            Text(L10n.tr("自动查找同一网络的电脑；需要密钥的电脑会在首次连接时要求输入 Host Key。"))
        }
        if authorizationHost == nil, let formError { Section {
            Text(formError).foregroundStyle(.red).accessibilityIdentifier("client-form-error")
            if var host = failedKnownHost {
                Button(L10n.tr("使用 Host Key 重新授权")) {
                    host.known = false; authorizationHost = host; hostKey = ""; self.failedKnownHost = nil; self.formError = nil
                }.accessibilityIdentifier("codex-reauthorize")
            }
        } }
    }

    private var discoveryRows: [CodexHostDiscovery.Host] {
        let saved = store.saved.clients.filter { !$0.isDemo && $0.agentConnection == nil }
        var discovered = vpnDiscovery.codexHosts
        for host in discovery.hosts where !discovered.contains(where: { $0.id == host.id }) { discovered.append(host) }
        return saved.map { client in
            let found = discovered.first { $0.id == client.id }
            return CodexHostDiscovery.Host(id: client.id, name: client.name,
                endpoint: found?.endpoint ?? client.endpoint ?? "", known: true, reachable: found?.reachable ?? false, networkKind: found?.networkKind)
        } + discovered.filter { found in !saved.contains { $0.id == found.id } }
    }
    private func discoveryDetail(_ host: CodexHostDiscovery.Host) -> String {
        if let client = store.saved.clients.first(where: { $0.id == host.id }) {
            let network = host.networkKind == .vpn ? "VPN · " : ""
            if client.online { return network + L10n.tr("已连接") }
            return host.reachable ? network + L10n.tr("未连接 · 点选连接") : L10n.tr("未连接 · \(store.connectionLabel(for: client))")
        }
        return (host.networkKind == .vpn ? "VPN · " : "") + L10n.tr("未连接 · 点选连接")
    }

    private func connectDiscovered(_ host: CodexHostDiscovery.Host) {
        guard !connecting else { return }
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        NativeReadAcceptanceTiming.record("connection-start", hostId: host.id)
        connecting = true; formError = nil; failedKnownHost = nil
        Task {
            do {
                if !host.known {
                    try await store.addCodexDiscovered(host, hostKey: hostKey)
                    hostKey = ""; authorizationHost = nil
                    NativeReadAcceptanceTiming.record("connection-success", hostId: host.id)
                    dismiss(); connecting = false; return
                }
                if let previous = store.saved.clients.first(where: { $0.id == host.id }), let direct = previous.codexDirect, host.reachable {
                    let descriptor = try await CodexDiscoveryAPI.read(host.endpoint, fingerprint: direct.fingerprint, target: direct.target, timeout: 8)
                    guard store.updateCodexDiscovery(previous, host: descriptor, reachableEndpoint: host.endpoint) else { throw HostServiceError.wrongHost }
                }
                store.selectClient(host.id, refreshAutomatically: false); await store.refresh()
                guard store.client?.online == true else { throw HostServiceError.unavailable }
                NativeReadAcceptanceTiming.record("connection-success", hostId: host.id)
                dismiss()
            } catch {
                NativeReadAcceptanceTiming.record("connection-failed", hostId: host.id)
                if case HostServiceError.hostKeyRequired = error {
                    authorizationHost = host; hostKey = ""; formError = nil
                } else {
                    formError = error.userFacingDescription
                    if host.known { failedKnownHost = host }
                }
            }
            connecting = false
        }
    }

    private var manualConnection: some View {
        Section {
            TextField(L10n.tr("客户端名称"), text: $name).accessibilityIdentifier("client-name")
            TextField(L10n.tr("连接地址"), text: $address).keyboardType(.URL)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .accessibilityIdentifier("client-address")
            SecureField(L10n.tr("客户端密钥"), text: $token).textInputAutocapitalization(.never)
                .autocorrectionDisabled().accessibilityIdentifier("client-token")
            if let formError {
                Text(formError).foregroundStyle(.red).accessibilityIdentifier("client-form-error")
            }
            Button {
                connecting = true
                formError = nil
                Task {
                    do {
                        if let clientToEdit {
                            try await store.updateClient(clientToEdit.id, name: name, address: address, token: token)
                            dismiss()
                        } else {
                            try await store.addClient(name: name, address: address, token: token)
                        }
                    } catch {
                        formError = error.userFacingDescription
                    }
                    connecting = false
                }
            } label: {
                HStack {
                    Text(connecting
                        ? (clientToEdit == nil ? L10n.tr("正在连接…") : L10n.tr("正在保存…"))
                        : (clientToEdit == nil ? L10n.tr("连接客户端") : L10n.tr("保存修改")))
                    Spacer()
                    if connecting { ProgressView() }
                }
            }
            .accessibilityIdentifier(clientToEdit == nil ? "connect-client" : "save-client")
        } header: {
            Text(L10n.tr("客户端"))
        } footer: {
            Text(L10n.tr("填写电脑上 Host Agent 的 HTTPS 地址和客户端密钥。密钥保存在系统钥匙串。本机模拟器可使用回环地址；历史只读连接无需密钥。"))
        }
    }

    private func connectQR() {
        connecting = true; formError = nil
        Task {
            do { try await store.addCodexQR(qrText, name: name); store.finishConnectionLink() }
            catch { formError = error.userFacingDescription }
            connecting = false
        }
    }
}

struct ProjectPickerView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @ObservedObject var store: SessionStore
    var body: some View {
        NavigationStack {
            List {
                if store.canCreateProjectless {
                    Section {
                        Button { store.newProjectlessConversation() } label: {
                            Label(L10n.tr("不选择项目"), systemImage: "square.and.pencil")
                        }.foregroundStyle(Color.primary).accessibilityIdentifier("choose-projectless")
                    }
                }
                Section {
                    ForEach(store.projects) { project in
                        Button {
                            store.newConversation(in: project)
                        } label: {
                            Label(project.name, systemImage: "folder")
                        }.foregroundStyle(Color.primary).accessibilityIdentifier(
                            "choose-project-\(project.id)")
                    }
                    if store.projects.isEmpty { Text(L10n.tr("先添加一个项目")).foregroundStyle(Palette.secondary) }
                } header: {
                    Text(store.client?.name ?? L10n.tr("未连接"))
                }
            }.navigationTitle(L10n.tr("选择项目")).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L10n.tr("取消")) { store.sheet = nil }
                    }
                }
        }.presentationDetents([.medium, .large])
    }
}

struct AddProjectView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @ObservedObject var store: SessionStore
    @State private var name = ""
    @State private var directory = "."
    var body: some View {
        NavigationStack {
            Form {
                Section { TextField(L10n.tr("项目名称"), text: $name).accessibilityIdentifier("project-name") }
                if store.client?.isDemo == false {
                    Section(L10n.tr("电脑上的目录")) {
                        TextField(L10n.tr("相对于授权工作目录的路径"), text: $directory)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .accessibilityIdentifier("project-directory")
                    }
                }
                Section {
                    Text(store.client?.isDemo == true ? L10n.tr("这里只添加本地示例项目。") : L10n.tr("选择电脑授权工作目录中的已有文件夹。")).font(
                        .subheadline
                    ).foregroundStyle(
                        Palette.secondary)
                }
                Button(L10n.tr("添加项目")) { store.addProject(name, directory: directory) }.disabled(
                    name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ).accessibilityIdentifier("confirm-project")
            }.navigationTitle(L10n.tr("添加项目")).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L10n.tr("取消")) { store.sheet = nil }
                    }
                }
        }.presentationDetents([.medium, .large])
    }
}

struct WorkspaceManagementView: View {
  @ObservedObject var store: SessionStore
  @State private var action = WorkspaceManagementAction.deleteProjects
  @State private var search = ""
  @State private var selected: Set<String> = []
  @State private var confirming = false
  @State private var confirmedIds: [String] = []
  @State private var confirmedLabels: [String: String] = [:]
  @State private var confirmedTarget: AgentRuntimeTarget?
  @State private var error: String?

  private struct Row: Identifiable { let id: String; let title: String; let detail: String; let enabled: Bool }
  private var rows: [Row] {
    if action == .deleteProjects {
      return store.projects.map { Row(id: $0.id, title: $0.name, detail: $0.roots.first ?? "", enabled: true) }
    }
    return store.threads.filter { $0.archived != true }.map { thread in
      let project = store.projects.first { thread.belongs(to: $0) }?.name ?? L10n.tr("无项目")
      return Row(id: thread.id, title: thread.title,
        detail: project + " · " + store.chatLifecycleStatus(thread), enabled: store.canArchive(thread))
    }
  }
  private var filteredRows: [Row] { rows.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.detail.localizedCaseInsensitiveContains(search) } }
  private var eligibleIds: Set<String> { Set(filteredRows.filter(\.enabled).map(\.id)) }
  private var allSelected: Bool { !eligibleIds.isEmpty && eligibleIds.isSubset(of: selected) }
  private var archiveEligibleIds: Set<String> { Set(rows.filter(\.enabled).map(\.id)) }
  private var validSelection: Bool {
    !selected.isEmpty && (action != .archiveThreads || selected.isSubset(of: archiveEligibleIds))
  }
  private var available: Bool { store.canManageWorkspace(action) && store.client?.online == true }
  private var actionLabel: String {
    action == .deleteProjects ? L10n.tr("删除 \(selected.count) 个项目") : L10n.tr("归档 \(selected.count) 个对话")
  }
  var body: some View {
    List {
      Section {
        LabeledContent(L10n.tr("客户端"), value: store.client?.name ?? L10n.tr("未连接"))
        Picker(L10n.tr("管理内容"), selection: $action) {
          Text(L10n.tr("项目")).tag(WorkspaceManagementAction.deleteProjects)
          Text(L10n.tr("对话")).tag(WorkspaceManagementAction.archiveThreads)
        }.pickerStyle(.segmented).accessibilityIdentifier("management-kind")
      } footer: {
        Text(action == .deleteProjects ? L10n.tr("从此电脑的项目列表移除，保留文件和对话。") : store.supportsChatManagement ? L10n.tr("归档后从活跃列表移除，可在已归档对话中恢复。") : L10n.tr("归档后从活跃列表移除，可在电脑端恢复。"))
      }
      if !store.canManageWorkspace(action) {
        Section { Text(L10n.tr("此客户端尚未支持此操作，请更新电脑连接程序或切换客户端。")) }
      } else if store.client?.online != true {
        Section { Text(L10n.tr("电脑离线，连接后可批量管理。")) }
      }
      if let error { Section { Text(error).foregroundStyle(.red).accessibilityIdentifier("management-error") } }
      Section {
        HStack {
          Button(allSelected ? L10n.tr("取消全选") : L10n.tr("全选")) {
            if action == .archiveThreads {
              selected = Set(SharedMobileRules.workspaceArchiveSelect(current: Array(selected), eligible: filteredRows.filter(\.enabled).map(\.id), all: allSelected))
            } else if allSelected { selected.subtract(eligibleIds) }
            else { selected.formUnion(filteredRows.filter { $0.enabled && !selected.contains($0.id) }.prefix(max(0, 100 - selected.count)).map(\.id)) }
          }.disabled(!available || store.managementBusy || eligibleIds.isEmpty)
            .accessibilityIdentifier("management-select-all")
          Spacer()
          Text(L10n.tr("已选 \(selected.count) 项")).accessibilityIdentifier("management-selected-count").foregroundStyle(.secondary)
        }
        if filteredRows.isEmpty {
          Text(search.isEmpty ? L10n.tr("没有可管理的内容") : L10n.tr("没有找到相关内容")).foregroundStyle(.secondary)
        }
        ForEach(filteredRows) { row in
          Button {
            if selected.contains(row.id) { selected.remove(row.id) }
            else if selected.count < 100 { selected.insert(row.id) }
          } label: {
            HStack(spacing: 12) {
              Image(systemName: selected.contains(row.id) ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(selected.contains(row.id) ? Color.accentColor : Color.secondary)
              VStack(alignment: .leading, spacing: 4) {
                Text(row.title).foregroundStyle(.primary).lineLimit(2)
                if !row.detail.isEmpty { Text(row.detail).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
              }
              Spacer(minLength: 0)
            }.frame(minHeight: 44).contentShape(Rectangle())
          }.buttonStyle(.plain)
            .disabled(!available || store.managementBusy || !row.enabled || (!selected.contains(row.id) && selected.count >= 100))
            .accessibilityIdentifier("management-item-" + row.id)
            .accessibilityLabel(row.title)
            .accessibilityValue(selected.contains(row.id) ? L10n.tr("已选择") : L10n.tr("未选择"))
        }
        if store.client.flatMap({ store.saved.workspaces[$0.id] }).map({ $0.nextProjectCursor != nil || $0.nextThreadCursor != nil }) == true {
          Button(L10n.tr("加载更多")) { Task { await store.refresh(loadMore: true, includeHistory: false) } }.disabled(store.loading || store.managementBusy)
        }
      } header: { Text(action == .deleteProjects ? L10n.tr("选择要删除的项目") : L10n.tr("选择要归档的对话")) }
        footer: { Text(L10n.tr("每批最多选择 100 项。全选仅选择当前搜索结果。")) }
      if !store.workspaceManagementOperations.isEmpty {
        Section(L10n.tr("操作记录")) {
          ForEach(store.workspaceManagementOperations) { operation in
            VStack(alignment: .leading, spacing: 8) {
              Text(operation.action.title).font(.headline)
              Text(operation.id).font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary)
                .textSelection(.enabled).accessibilityIdentifier("management-operation-" + operation.id)
              let completed = operation.items.filter { $0.state == "completed" }.count
              let rejected = operation.items.filter { $0.state == "rejected" }.count
              Text(L10n.tr("成功 \(completed) 项 · 失败 \(rejected) 项 · 待确认 \(operation.items.count - completed - rejected) 项"))
                .font(.subheadline).foregroundStyle(.secondary).accessibilityIdentifier("management-result-" + operation.id)
              ForEach(operation.items.filter { $0.error != nil }, id: \.id) { item in
                Text((operation.labels?[item.id] ?? item.id) + "：" + ChatAction.errorDescription(item.error ?? "")).font(.caption).foregroundStyle(.secondary)
              }
              if operation.needsCheck {
                Button(L10n.tr("核对结果")) { Task {
                  do { try await store.checkWorkspaceManagement(operation); selected.formIntersection(Set(rows.map(\.id))); error = nil }
                  catch { self.error = error.localizedDescription }
                } }.disabled(store.managementBusy || store.client?.online != true)
                  .accessibilityIdentifier("management-check-" + operation.id)
              }
            }.padding(.vertical, 4)
          }
        }
      }
    }.navigationTitle(L10n.tr("项目与对话管理")).navigationBarTitleDisplayMode(.inline)
      .searchable(text: $search, prompt: L10n.tr("搜索项目和对话"))
      .safeAreaInset(edge: .bottom) {
        VStack(spacing: 8) {
          if store.managementBusy { ProgressView(L10n.tr("正在处理批量操作")) }
          Button {
            confirmedIds = rows.filter { selected.contains($0.id) && $0.enabled }.map(\.id)
            confirmedLabels = Dictionary(uniqueKeysWithValues: rows.filter { selected.contains($0.id) }.map { ($0.id, $0.title) })
            confirmedTarget = store.workspaceManagementTarget
            confirming = true
          } label: {
            Label(actionLabel, systemImage: action == .deleteProjects ? "trash" : "archivebox")
              .frame(maxWidth: .infinity, minHeight: 36)
          }.buttonStyle(.borderedProminent).tint(action == .deleteProjects ? .red : .accentColor)
            .disabled(!validSelection || !available || store.managementBusy)
            .accessibilityIdentifier("management-submit")
        }.padding().background(.bar)
      }
      .alert(action == .deleteProjects ? L10n.tr("删除选中的 \(confirmedIds.count) 个项目？") : L10n.tr("归档选中的 \(confirmedIds.count) 个对话？"), isPresented: $confirming) {
        Button(action.title, role: .destructive) { Task {
          guard confirmedTarget == store.workspaceManagementTarget else { error = L10n.tr("管理目标已变化，请重新选择。"); return }
          do { try await store.manageWorkspace(action, ids: confirmedIds, labels: confirmedLabels); selected.formIntersection(Set(rows.map(\.id))); error = nil }
          catch { self.error = error.localizedDescription }
        } }.accessibilityIdentifier("management-confirm")
        Button(L10n.tr("取消"), role: .cancel) {}
      } message: {
        Text(action == .deleteProjects ? L10n.tr("仅移除此电脑的项目记录，文件和对话历史会保留。") : L10n.tr("所选对话将从活跃列表移除，历史会保留。"))
      }
      .onChange(of: archiveEligibleIds) { _, ids in
        if action == .archiveThreads { selected.formIntersection(ids) }
      }
      .onChange(of: action) { _, _ in selected = []; error = nil }
      .onChange(of: store.client?.id) { _, _ in selected = []; error = nil }
      .refreshable { await store.refresh(includeHistory: false) }
      .task { if store.client?.isDemo == false { await store.refresh(includeHistory: false) } }
  }
}

struct SettingsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @ObservedObject var store: SessionStore
    @AppStorage(VoiceTranscriptionPreference.storageKey) private var voicePreference: VoiceTranscriptionPreference = .desktop
    var body: some View {
        NavigationStack {
            Form {
                Section(L10n.tr("连接")) {
                    Button {
                        store.sheet = .clients
                    } label: {
                        HStack {
                            Label(L10n.tr("客户端"), systemImage: "laptopcomputer")
                            Spacer()
                            Text(L10n.tr("\(store.saved.clients.count) 个"))
                                .foregroundStyle(Palette.secondary)
                            Image(systemName: "chevron.right")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .accessibilityLabel(L10n.tr("客户端"))
                    .accessibilityValue(L10n.tr("\(store.saved.clients.count) 个"))
                    .accessibilityIdentifier("settings-clients")
                    Button {
                        store.sheet = .addClient
                    } label: {
                        Label(L10n.tr("添加客户端"), systemImage: "plus")
                    }
                    .accessibilityIdentifier("settings-add-client")
                }
                Section(L10n.tr("管理")) {
                    NavigationLink { WorkspaceManagementView(store: store) } label: {
                        Label(L10n.tr("项目与对话管理"), systemImage: "checklist")
                    }.accessibilityIdentifier("settings-workspace-management")
                }
                Section {
                    Picker(L10n.tr("外观"), selection: $store.saved.appearance) {
                        ForEach(Appearance.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.navigationLink)
                    .accessibilityIdentifier("appearance-picker").onChange(
                        of: store.saved.appearance
                    ) { _, _ in store.persist() }
                    AppIconBadgeToggle()
                } header: {
                    Text(L10n.tr("外观"))
                } footer: {
                    Text(L10n.tr("仅控制手机桌面 App 图标上的未读数量，不影响消息通知和客户端列表。"))
                }
                Section {
                    Picker(L10n.tr("语言"), selection: $appLanguage) {
                        ForEach(AppLanguage.allCases) { language in
                            Text(language.title).tag(language.rawValue)
                        }
                    }
                    .accessibilityIdentifier("language-picker")
                } footer: {
                    Text(L10n.tr("跟随系统，或选择中文和英文。更改会立即生效。"))
                }
                Section {
                    Picker(L10n.tr("默认转写"), selection: $voicePreference) {
                        ForEach(VoiceTranscriptionPreference.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.navigationLink)
                    .accessibilityIdentifier("voice-transcription-picker")
                } header: {
                    Text(L10n.tr("语音输入"))
                } footer: {
                    Text(L10n.tr("优先使用所选方式。电脑端转写不可用时，自动使用本地听写。本地听写使用手机系统语音识别，部分设备或语言可能需要联网。"))
                }
                Section {
                    NavigationLink { TaskNotificationSettingsView(store: store) } label: { Label(L10n.tr("通知"), systemImage: "bell.badge") }
                        .accessibilityIdentifier("notification-settings")
                    NavigationLink { AISharingSettingsView(store: store) } label: { Label(L10n.tr("AI 数据共享"), systemImage: "hand.raised") }
                        .accessibilityIdentifier("ai-sharing-settings")
                    NavigationLink { EncryptedMigrationView(store: store) } label: { Label(L10n.tr("安全迁移"), systemImage: "lock.doc") }
                }
                Section {
                    Toggle(L10n.tr("界面示例"), isOn: Binding(
                        get: { store.hasDemoClients },
                        set: { enabled in
                            if enabled { store.enableDemo() } else { store.disableDemo() }
                            store.sheet = .settings
                        }
                    )).accessibilityIdentifier("settings-demo-toggle")
                } header: {
                    Text(L10n.tr("本地体验"))
                } footer: {
                    Text(L10n.tr("无需账号或连接电脑即可浏览示例、切换项目和体验对话。示例回复在手机本地生成，不会发送给电脑或 AI 服务。"))
                }
                Section(L10n.tr("关于")) {
                    LabeledContent("Agent Hub", value: "0.1.0")
                    Link(destination: L10n.privacyPolicyURL) {
                        Label(L10n.tr("隐私政策"), systemImage: "hand.raised")
                    }.accessibilityIdentifier("settings-privacy-policy")
                    Link(destination: L10n.supportURL) {
                        Label(L10n.tr("帮助与支持"), systemImage: "questionmark.circle")
                    }.accessibilityIdentifier("settings-support")
                    Text(
                        L10n.tr("统一管理局域网内多台电脑上的 Agent 客户端，目前支持 Codex 和 Easy Work，后续可兼容更多客户端。\n\n一键切换客户端，随时发起任务，让 AI 自动完成并交付工作。")
                    ).font(.subheadline)
                        .foregroundStyle(Palette.secondary)
                }
            }.navigationTitle(L10n.tr("设置")).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.tr("完成")) { store.sheet = nil }
                    }
                }
        }.onAppear {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
    }
}

struct AISharingPermissionView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @ObservedObject var store: SessionStore
    var body: some View {
        NavigationStack {
            Form {
                if let request = store.aiSharingRequest {
                    Section(L10n.tr("发送到哪里")) {
                        LabeledContent(L10n.tr("电脑"), value: request.computerName)
                        LabeledContent("Agent", value: request.agentName)
                        LabeledContent(L10n.tr("模型"), value: request.modelName)
                    }
                    Section(L10n.tr("第三方 AI 如何处理内容")) {
                        Text(request.scope.model == "codex-dictation"
                          ? L10n.tr("录音结束后，原录音文件将发送至这台电脑，再使用 Codex 桌面当前 ChatGPT 账户提交给 OpenAI 的语音转写服务。手机只录音，返回的文字填入输入框，由你确认后发送。")
                          : L10n.tr("你的文字、图片、文件、问题回答及相关对话上下文将发送至这台电脑，并由电脑端实际配置的第三方 AI 服务处理，用于生成回复、分析附件和执行任务。"))
                        Text(L10n.tr("模型名称不一定代表服务商；请在电脑端确认实际服务商、数据保留、训练用途和处理地区。切换电脑或模型后需要重新允许。选择跟随电脑时，你允许使用该电脑的当前 AI 配置。"))
                        Text(L10n.tr("只发送你有权分享的内容。连接加密不影响 AI 服务读取已发送内容。你可以在设置中撤回许可；撤回不删除已经发送的内容，也不停止电脑上已有任务。"))
                        Link(L10n.tr("阅读隐私政策"), destination: L10n.privacyPolicyURL)
                    }
                    Section {
                        Button(L10n.tr("允许此电脑和模型处理内容")) { store.allowAISharing() }
                            .accessibilityIdentifier("ai-sharing-allow")
                        Button(L10n.tr("暂不允许"), role: .cancel) { store.dismissAISharing() }
                            .accessibilityIdentifier("ai-sharing-decline")
                    } footer: {
                        Text(L10n.tr("允许后返回原界面，再次点击发送或原操作按钮。当前草稿和附件会保留；本页不会自动发送。"))
                    }
                }
            }.navigationTitle(L10n.tr("AI 数据共享")).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("取消")) { store.dismissAISharing() } } }
        }.onDisappear { store.dismissAISharing() }
    }
}

struct AISharingSettingsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @ObservedObject var store: SessionStore
    var body: some View {
        List {
            Section {
                Text(L10n.tr("发送前由你明确允许所选电脑及其 AI 配置处理文字、图片、文件和对话上下文。本地界面示例不访问 AI 服务。"))
                Link(L10n.tr("隐私政策"), destination: L10n.privacyPolicyURL)
            }
            Section {
                if (store.saved.aiSharingGrants ?? []).isEmpty {
                    Text(L10n.tr("尚未允许共享")).foregroundStyle(.secondary)
                }
                ForEach(store.saved.aiSharingGrants ?? []) { grant in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(grant.computerName).font(.headline)
                        Text(grant.agentName + " · " + grant.modelName).font(.subheadline)
                        Button(L10n.tr("撤回许可"), role: .destructive) { store.revokeAISharing(grant.id) }
                            .accessibilityIdentifier("ai-sharing-revoke-" + grant.id)
                    }.padding(.vertical, 4)
                }
            } header: {
                Text(L10n.tr("已允许的电脑和模型"))
            } footer: {
                Text(L10n.tr("撤回后，新发送、回答及继续执行操作会再次询问。已经提交的内容和电脑端任务不受撤回影响；请到对应电脑或 AI 服务处理删除和停止请求。"))
            }
        }.navigationTitle(L10n.tr("AI 数据共享")).navigationBarTitleDisplayMode(.inline)
    }
}

struct GenerationSettings: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
    @ObservedObject var store: SessionStore
    @ObservedObject private var drafts: ComposerDraftStore
    init(store: SessionStore) { self.store = store; drafts = store.drafts }
    private var effortIndex: Binding<Double> {
        Binding(get: { Double(store.availableEfforts.firstIndex(of: store.selectedEffort ?? "") ?? 0) }, set: { value in
            let index = Int(value.rounded())
            if store.availableEfforts.indices.contains(index) { store.effort = store.availableEfforts[index] }
        })
    }
    var body: some View {
        NavigationStack {
            Form {
                if store.selectedThreadId != nil {
                    Section(L10n.tr("电脑当前设置")) {
                        LabeledContent(L10n.tr("模型"), value: store.displayedModelLabel)
                        LabeledContent(L10n.tr("推理强度"), value: store.displayedEffortLabel)
                    }
                    Section {
                        Text(L10n.tr("以下选择用于下次发送，发送前不会修改电脑当前设置。"))
                            .font(.caption).foregroundStyle(Palette.secondary)
                    } header: { Text(L10n.tr("下次发送")) }
                }
                Section(L10n.tr("选择模型")) {
                    Picker(L10n.tr("模型"), selection: Binding(get: { store.draft.model ?? store.runtime?.generationSettings?.model ?? store.selectedModel?.id ?? "" }, set: { store.model = $0 })) {
                        Text(L10n.tr("跟随电脑")).tag("")
                        ForEach(store.models) { Text($0.name + ($0.isDefault ? L10n.tr(" · 推荐") : "")).tag($0.id) }
                    }.pickerStyle(.menu).accessibilityIdentifier("model-picker")
                    if store.models.isEmpty { Text(L10n.tr("正在读取电脑的模型列表…")).font(.caption).foregroundStyle(Palette.secondary) }
                }
                Section(L10n.tr("推理强度")) {
                    HStack {
                        Image(systemName: "bolt.fill").foregroundStyle(Color.blue)
                        Text(store.effortLabel(store.selectedEffort)).font(.title3.weight(.semibold)).foregroundStyle(Color.blue)
                        Spacer()
                        Button(L10n.tr("跟随电脑"), systemImage: "arrow.counterclockwise") { store.effort = "" }.labelStyle(.iconOnly)
                            .accessibilityIdentifier("reset-effort")
                    }
                    if store.availableEfforts.count > 1 {
                        Slider(value: effortIndex, in: 0...Double(store.availableEfforts.count - 1), step: 1)
                            .accessibilityIdentifier("effort-slider").accessibilityLabel(L10n.tr("推理强度"))
                    }
                    Picker(L10n.tr("强度"), selection: Binding(get: { store.selectedEffort ?? "" }, set: { store.effort = $0 })) {
                        Text(L10n.tr("跟随电脑")).tag("")
                        ForEach(store.availableEfforts, id: \.self) { Text(store.effortLabel($0)).tag($0) }
                    }.pickerStyle(.menu).accessibilityIdentifier("effort-picker")
                }
                if store.supportsGenerationSpeed {
                    Section(L10n.tr("速度")) {
                        if store.availableSpeeds.contains("ultrafast") {
                            Picker(L10n.tr("速度模式"), selection: Binding(get: { store.selectedSpeed ?? "" }, set: { store.speed = $0 })) {
                                Text(L10n.tr("跟随电脑")).tag("")
                                ForEach(store.availableSpeeds, id: \.self) { Text(store.speedLabel($0)).tag($0) }
                            }.pickerStyle(.menu).accessibilityIdentifier("speed-picker")
                        } else {
                            Toggle(L10n.tr("快速模式"), isOn: Binding(get: { store.selectedSpeed == "fast" }, set: { store.speed = $0 ? "fast" : "standard" }))
                                .disabled(!store.availableSpeeds.contains("fast"))
                                .accessibilityIdentifier("fast-mode-toggle")
                        }
                        Button(L10n.tr("跟随电脑速度")) { store.speed = "" }.accessibilityIdentifier("reset-speed")
                        Text(store.availableSpeeds.contains("fast") ? L10n.tr("加快模型响应，增加用量消耗。") : L10n.tr("当前模型未提供加速模式。"))
                            .font(.caption).foregroundStyle(Palette.secondary)
                    }
                }
                Section(L10n.tr("应如何批准操作？")) {
                    Button { store.permissionMode = "" } label: {
                        HStack { Text(L10n.tr("跟随电脑权限")); Spacer(); if store.selectedPermissionMode == nil { Image(systemName: "checkmark") } }
                    }.foregroundStyle(Palette.secondary).accessibilityIdentifier("permission-default")
                    ForEach(store.permissionOptions) { option in
                        Button { store.permissionMode = option.id } label: {
                            HStack(spacing: 12) {
                                Image(systemName: option.icon).frame(width: 24)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(option.title).font(.body.weight(.medium))
                                    Text(option.detail).font(.caption).foregroundStyle(Palette.secondary)
                                }
                                Spacer(minLength: 0)
                                if store.selectedPermissionMode == option.id { Image(systemName: "checkmark") }
                            }.padding(.vertical, 5)
                        }.foregroundStyle(option.highPrivilege ? Color.orange : Color.primary)
                            .accessibilityIdentifier("permission-" + option.id)
                    }
                }
                Text(L10n.tr("选择会记住，供此客户端的新对话使用。设置随下一条消息或排队消息提交，补充指令沿用当前任务设置。")).font(.caption).foregroundStyle(Palette.secondary)
            }.disabled(!store.canConfigureGeneration)
                .navigationTitle(L10n.tr("模型、推理与权限")).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L10n.tr("完成")) { store.sheet = nil }.accessibilityIdentifier("generation-done") } }
                .task { await store.loadModels() }
        }.presentationDetents([.large])
    }
}
