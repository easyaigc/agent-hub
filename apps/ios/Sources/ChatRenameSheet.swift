import SwiftUI

struct ChatRenameContext: Identifiable {
  let id = UUID()
  let host: Client
  let thread: Conversation
  let target: AgentRuntimeTarget
  let generation: UUID
}

struct ChatRenameSheet: View {
  @ObservedObject var store: SessionStore
  let context: ChatRenameContext
  let close: () -> Void
  @State private var title: String
  @State private var busy = false
  @State private var original: ChatActionReceipt?
  @State private var message: String?
  init(store: SessionStore, context: ChatRenameContext, close: @escaping () -> Void) {
    self.store = store; self.context = context; self.close = close
    _title = State(initialValue: context.thread.title)
  }
  private var record: ChatActionReceipt? { original.flatMap { store.chatActionReceipt($0.id) } }
  private func finish(_ receipt: ChatActionReceipt, confirmed: Bool) {
    guard original?.id == receipt.id else { return }
    if confirmed, store.chatRenameContextCurrent(context),
      SharedMobileRules.normalizedChatTitle(title) == SharedMobileRules.normalizedChatTitle(receipt.title ?? "") { close() }
    else if confirmed, store.chatRenameContextCurrent(context) {
      message = L10n.tr("原操作已完成，当前修改尚未保存。")
    }
    else { message = store.chatActionQueryError(receipt.id) ?? store.chatActionReceipt(receipt.id)?.error ?? L10n.tr("操作结果待确认，请在对话选项中查询原记录。") }
  }
  private func saveRename() {
    guard !busy, store.chatRenameContextCurrent(context), let normalized = SharedMobileRules.normalizedChatTitle(title),
      let submission = store.manageChat(.rename, thread: context.thread, title: normalized, showsErrorAlert: false) else { return }
    original = submission.operation; message = nil; busy = true
    Task {
      let confirmed = await submission.task.value
      busy = false; finish(submission.operation, confirmed: confirmed)
    }
  }
  @ViewBuilder private var saveControl: some View {
    if context.target.agentId == "easywork" {
      NativeContentButton(identifier: "chat-rename-save", label: L10n.tr("保存"), content: {
        if busy { ProgressView() } else { Text(L10n.tr("保存")) }
      }, action: saveRename)
        .fixedSize(horizontal: true, vertical: false)
        .accessibilityIdentifier("chat-rename-save")
    } else {
      Button(action: saveRename) { if busy { ProgressView() } else { Text(L10n.tr("保存")) } }
        .accessibilityIdentifier("chat-rename-save")
    }
  }
  var body: some View {
    NavigationStack {
      Form {
        TextField(L10n.tr("对话名称"), text: $title, axis: .vertical)
          .accessibilityIdentifier("chat-rename-title")
        if let detail = original.flatMap({ store.chatActionQueryError($0.id) }) ?? message {
          Text(detail).foregroundStyle(.secondary).accessibilityIdentifier("chat-rename-error")
        }
        if let receipt = record, receipt.pending {
          Button(L10n.tr("查询聊天操作记录")) {
            guard !busy else { return }; busy = true
            Task {
              let confirmed = await store.queryChatAction(receipt, showsErrorAlert: false)?.value ?? false
              busy = false; finish(receipt, confirmed: confirmed)
            }
          }.disabled(busy || !store.chatRenameContextCurrent(context)).accessibilityIdentifier("chat-rename-query")
          if receipt.waitingEnded != true {
            Button(L10n.tr("结束等待")) { store.endChatActionWaiting(receipt, showsErrorAlert: false) }
              .disabled(busy).accessibilityIdentifier("chat-rename-end")
          }
        }
      }
      .navigationTitle(L10n.tr("重命名"))
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("取消"), action: close).accessibilityIdentifier("chat-rename-cancel") }
        ToolbarItem(placement: .confirmationAction) {
          saveControl
          .disabled(busy || !store.chatRenameContextCurrent(context) || !store.canManageChat(context.thread) || SharedMobileRules.normalizedChatTitle(title) == nil)
        }
      }
    }
  }
}
