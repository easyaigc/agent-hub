import SwiftUI

private struct OpenSubagentHistoryKey: EnvironmentKey {
  static let defaultValue: (@MainActor @Sendable (ChatMessage) -> Void)? = nil
}
private extension EnvironmentValues {
  var openSubagentHistory: (@MainActor @Sendable (ChatMessage) -> Void)? {
    get { self[OpenSubagentHistoryKey.self] }
    set { self[OpenSubagentHistoryKey.self] = newValue }
  }
}

/// Keep the presentation above the rolling message window and bind it to one target.
struct SubagentHistoryPresentation: ViewModifier {
  let targetKey: String
  let client: Client?
  let threadId: String?
  var supportsHistory = true
  @State private var selection: Selection?
  private struct Selection: Identifiable { let id: String; let message: ChatMessage }
  func body(content: Content) -> some View {
    let open: @MainActor @Sendable (ChatMessage) -> Void = { message in
      selection = Selection(id: targetKey + "|" + message.id, message: message)
    }
    return content.environment(\.openSubagentHistory, open)
    .sheet(item: $selection) { value in
      SubagentDetailView(message: value.message, client: client, threadId: threadId, supportsHistory: supportsHistory).id(value.id)
    }
    .onChange(of: targetKey) { _, _ in selection = nil }
  }
}

struct SubagentMessageView: View {
  let message: ChatMessage
  @Environment(\.openSubagentHistory) private var openDetail

  var body: some View {
    Button { openDetail?(message) } label: {
      // Detail-only streams must not rebuild the main timeline's status label.
      SubagentStatusRow(title: message.text, status: message.status).equatable()
    }.buttonStyle(.plain)
      .accessibilityIdentifier("subagent-\(message.id)")
      .accessibilityLabel(message.text.isEmpty || message.text == "子 Agent" ? L10n.tr("子 Agent") : L10n.tr("子 Agent") + " · " + message.text)
      .accessibilityValue(L10n.tr(LocalizedText(stringLiteral: SharedMobileRules.toolStatus(message.status))))
  }
}

private struct SubagentStatusRow: View, Equatable {
  let title: String
  let status: String?
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue

  nonisolated static func == (lhs: Self, rhs: Self) -> Bool {
    lhs.title == rhs.title && lhs.status == rhs.status
  }

  var body: some View {
    HStack(spacing: 8) {
      Image(systemName: "person.2").font(.system(size: 16)).frame(width: 18, height: 18)
        .accessibilityHidden(true)
      Text(title.isEmpty || title == "子 Agent" ? L10n.tr("子 Agent") : L10n.tr("子 Agent") + " · " + title).fontWeight(.regular).lineLimit(1)
      Text(L10n.tr(LocalizedText(stringLiteral: SharedMobileRules.toolStatus(status))))
        .font(.caption).fixedSize()
      Image(systemName: "chevron.right").font(.system(size: 11)).accessibilityHidden(true)
      Spacer(minLength: 0)
    }.font(.callout).foregroundStyle(Palette.secondary)
      .frame(minHeight: 44).contentShape(Rectangle())
  }
}

/// Parent and child timelines use the same message and tool presentation.
struct ChatTimelineEntryView: View {
  let item: ChatTimeline.Item
  var client: Client?
  var threadId: String?
  let targetKey: String
  var isRunning = false
  var isLive = false

  var toolDisclosure: ((String) -> Binding<ToolDisclosureState>)?
  @State private var disclosure = ToolDisclosureState()

  @ViewBuilder var body: some View {
    switch item {
    case .message(let message):
      if message.kind == "turnFailure" {
        VStack(alignment: .leading, spacing: 4) {
          Label(L10n.tr("任务失败记录"), systemImage: "exclamationmark.circle")
          Text(EasyWorkFailurePresentation.text(message.text, enabled: client?.agentConnection?.target.agentId == "easywork")).textSelection(.enabled)
        }.font(.caption).foregroundStyle(Palette.secondary)
          .accessibilityIdentifier("task-failure-history-\(message.id)")
      } else if message.kind == "subAgent" {
        SubagentMessageView(message: message)
          .id("\(targetKey)-subagent-\(message.id)")
      } else if message.kind == "plan" {
        ProposedPlanMessageView(message: message, native: client?.agentConnection?.target.agentId == "easywork")
      } else {
        MessageView(message: message, client: client, threadId: threadId).equatable()
      }
    case .tools(let id, let messages):
      ToolCallGroupView(id: id, messages: messages, client: client, disclosure: toolDisclosure?(id) ?? $disclosure)
        .padding(.vertical, messages.count > 1 ? -6 : 0)
    case .compaction(let message):
      ContextCompactionView(message: message, isLive: isLive)
    case .errors(let id, let messages):
      VStack(alignment: .leading, spacing: 4) {
        Label(isRunning ? L10n.tr("电脑报告运行异常，仍在处理中") : L10n.tr("运行异常记录（\(messages.count) 条）"), systemImage: "exclamationmark.arrow.trianglehead.2.clockwise.rotate.90")
        if let last = messages.last {
          let detail = last.detail?.isEmpty == false ? last.detail! : last.text
          if !detail.isEmpty && detail != "error" { Text(detail).lineLimit(3) }
        }
      }.font(.caption).foregroundStyle(Palette.secondary)
        .accessibilityIdentifier("runtime-error-group-\(id)")
    case .replyActions(_, let text, _):
      IconButton(symbol: "doc.on.doc", label: L10n.tr("复制回复"), id: "copy-child-reply") {
        UIPasteboard.general.string = text
      }
    }
  }
}

private struct SubagentDetailView: View {
  let message: ChatMessage
  var client: Client?
  var threadId: String?
  var supportsHistory = true
  @Environment(\.dismiss) private var dismiss
  @Environment(\.scenePhase) private var scenePhase
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @State private var snapshot: SubagentHistorySnapshot?
  @State private var messages: [ChatMessage] = []
  @State private var selectedChild = ""
  @State private var error: String?
  @State private var earlierRequest: UUID?
  @State private var earlierTask: Task<Void, Never>?
  @State private var nextCursor: String?
  @State private var loadedKey = ""
  @State private var retry = 0
  @State private var timelineCache = ChatTimelineCache()

  private var key: String { (client?.id ?? "") + "|" + (threadId ?? "") + "|" + message.id + "|" + selectedChild }
  private var running: Bool { messages.contains { $0.status == "inProgress" } }
  private var loadingEarlier: Bool { earlierRequest != nil }
  private var statusLabel: String {
    let status: String?
    if let snapshot {
      status = snapshot.children.first(where: { $0.id == snapshot.childId })?.status
    } else { status = nil }
    let label = SharedMobileRules.toolStatus(status)
    return L10n.tr(LocalizedText(stringLiteral: label))
  }

  private var refreshKey: String { key + "|" + String(describing: scenePhase) + "|\(retry)" }

  var body: some View {
    NavigationStack {
      ScrollView { content.padding(16) }
        .accessibilityIdentifier("subagent-detail-\(message.id)")
        .background(Palette.background)
        .navigationTitle(L10n.tr("子 Agent"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
          ToolbarItem(placement: .confirmationAction) {
            Button(L10n.tr("完成")) { dismiss() }.accessibilityIdentifier("subagent-detail-close")
          }
        }
    }.presentationDetents([.large]).presentationDragIndicator(.visible)
      .task(id: refreshKey) { await observe() }
      .onDisappear { earlierTask?.cancel(); earlierTask = nil }
      .onChange(of: scenePhase) { _, phase in if phase != .active { earlierTask?.cancel() } }
      .modifier(SubagentHistoryPresentation(targetKey: key, client: client, threadId: snapshot?.threadId, supportsHistory: supportsHistory))
  }

  private var content: some View {
    VStack(alignment: .leading, spacing: 18) {
      if !supportsHistory {
        Text(L10n.tr("此客户端暂不支持完整子 Agent 过程")).font(.caption).foregroundStyle(Palette.secondary)
        Text(verbatim: message.detail ?? "").textSelection(.enabled)
          .accessibilityIdentifier("subagent-legacy-detail")
      } else if let snapshot { historyContent(snapshot) }
      else if error == nil { ProgressView().accessibilityIdentifier("subagent-history-loading") }
      if let error {
        Text(error).font(.callout).foregroundStyle(Palette.secondary).textSelection(.enabled)
          .accessibilityIdentifier("subagent-history-error")
        Button(L10n.tr("重试")) { retry += 1 }
      }
    }.font(.body).frame(maxWidth: .infinity, alignment: .leading)
  }

  @ViewBuilder private func historyContent(_ value: SubagentHistorySnapshot) -> some View {
    if value.children.count > 1 { childPicker(value) }
    else if let child = value.children.first, child.title != "子 Agent" {
      Text(child.title).font(.headline).lineLimit(2).textSelection(.enabled)
        .accessibilityIdentifier("subagent-detail-title-\(message.id)")
    }
    Text(value.source == "saved-history" ? L10n.tr("最近保存的记录") : L10n.tr("实时记录"))
      .font(.caption).foregroundStyle(Palette.secondary)
    Text(statusLabel).font(.callout).foregroundStyle(Palette.secondary)
      .accessibilityIdentifier("subagent-detail-status-\(message.id)")
    if nextCursor != nil {
      Button(L10n.tr("显示更早消息")) { earlierTask = Task { await loadEarlier() } }
        .disabled(loadingEarlier).accessibilityIdentifier("subagent-history-more")
    }
    historyRows(value)
  }

  private func childPicker(_ value: SubagentHistorySnapshot) -> some View {
    Picker(L10n.tr("子 Agent"), selection: Binding<String>(get: {
      selectedChild.isEmpty ? value.childId : selectedChild
    }, set: { selectedChild = $0 })) {
      ForEach(value.children) { child in Text(child.title).tag(child.id) }
    }.pickerStyle(.menu)
  }

  private func historyRows(_ value: SubagentHistorySnapshot) -> some View {
    let timeline = timelineCache.project(targetKey: key, messages: messages, messageLimit: max(1, messages.count),
      activeRunId: messages.last?.runId, isRunning: running, focusedMessageId: nil)
    return VStack(alignment: .leading, spacing: 18) {
      ForEach(timeline.items) { item in
        ChatTimelineEntryView(item: item, client: client, threadId: value.threadId,
          targetKey: key, isRunning: running, isLive: value.source == "desktop-runtime").id(item.id)
      }
    }.accessibilityElement(children: .contain)
      .accessibilityIdentifier("subagent-detail-body-\(message.id)")
  }

  @MainActor private func observe() async {
    guard supportsHistory, scenePhase == .active else { return }
    if loadedKey != key {
      earlierTask?.cancel()
      earlierRequest = nil
      snapshot = nil; messages = []; nextCursor = nil; loadedKey = key
    }
    error = nil
    do {
      while !Task.isCancelled {
        try await refresh()
        try await Task.sleep(for: .seconds(2))
      }
    } catch is CancellationError {
    } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
  }

  @MainActor private func page(cursor: String? = nil) async throws -> SubagentHistorySnapshot {
    guard let client, let threadId else { throw HostServiceError.invalidResponse }
    #if DEBUG && targetEnvironment(simulator)
    if client.isDemo, ProcessInfo.processInfo.arguments.contains("-ui-testing"),
      ProcessInfo.processInfo.arguments.contains("-subagent-timeline-fixture") {
      let childId = "c2-timeline-child"
      let runId = "c2-timeline-run"
      let records = [
        ChatMessage(id: "c2-child-prompt", role: "user", text: "C2 child task", kind: "userMessage", runId: runId),
        ChatMessage(id: "c2-child-command-1", role: "tool", text: "printf child-one", kind: "commandExecution",
          status: "completed", detail: "C2_CHILD_OUTPUT_ONE\nExit 0", runId: runId),
        ChatMessage(id: "c2-child-command-2", role: "tool", text: "printf child-two", kind: "commandExecution",
          status: "completed", detail: "C2_CHILD_OUTPUT_TWO\nExit 0", runId: runId),
        ChatMessage(id: "c2-child-reply", role: "assistant", text: "**C2 child completed**", kind: "agentMessage",
          status: "completed", runId: runId)
      ]
      return SubagentHistorySnapshot(version: 1, hostId: client.id,
        readTarget: AgentRuntimeTarget(agentId: "codex", hostId: client.id, workspaceId: "demo", runtimeId: "demo"),
        parentThreadId: threadId, messageId: message.id,
        children: [.init(id: childId, title: "C2 original child", status: "completed")],
        childId: childId, threadId: childId, source: "saved-history", messages: records, nextCursor: nil)
    }
    #endif
    return try await AgentClients.resolve(client).subagentHistory(client, parentThreadId: threadId,
      messageId: message.id, childId: selectedChild.isEmpty ? (cursor == nil ? nil : snapshot?.childId) : selectedChild, cursor: cursor)
  }
  @MainActor private func refresh() async throws {
    let captured = key
    let value = try await page()
    try Task.checkCancellation()
    guard key == captured else { return }
    let prefix = value.messages.first.flatMap { first in messages.firstIndex(where: { $0.id == first.id }).map { Array(messages[..<$0]) } } ?? []
    messages = prefix + value.messages
    if prefix.isEmpty { nextCursor = value.nextCursor }
    snapshot = value; error = nil
  }
  @MainActor private func loadEarlier() async {
    guard let cursor = nextCursor, !loadingEarlier else { return }
    let captured = key
    let request = UUID()
    earlierRequest = request
    defer { if earlierRequest == request { earlierRequest = nil } }
    do {
      let value = try await page(cursor: cursor)
      try Task.checkCancellation()
      guard key == captured, value.childId == snapshot?.childId else { return }
      let ids = Set(messages.map(\.id))
      messages = value.messages.filter { !ids.contains($0.id) } + messages
      nextCursor = value.nextCursor
    } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
  }
}
