import Foundation
import SwiftUI

/// Permission is local to this Task Hub service, human account and phone. It is
/// deliberately independent of SessionStore's direct-Host connections and grants.
struct TaskHubConsentDestination: Codable, Equatable {
  let scope: AISharingScope
  let computerName: String
  let projectName: String

  init(target: AgentRuntimeTarget?, hostId: String, computerName: String, projectName: String) throws {
    func complete(_ value: String) -> Bool {
      !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && value.count <= 256
        && value.rangeOfCharacter(from: .controlCharacters) == nil && value != "unknown"
    }
    guard let target, target.hostId == hostId,
      [target.agentId, target.hostId, target.workspaceId, target.runtimeId].allSatisfy(complete),
      target.agentId == "codex" || target.account.map({ complete($0.authority) && complete($0.subject) }) == true
    else { throw TaskHubError.message(L10n.tr("Agent 的机器身份尚未核对（Agent、Host、workspace 和 runtime）。请刷新原电脑及项目映射；不会仅凭 Host ID 派发。")) }
    // The Worker does not override the model. This is an explicit follow-computer
    // grant, not a claim to know its current model or third-party provider.
    scope = AISharingScope(target: target, model: "follow-computer")
    self.computerName = computerName
    self.projectName = projectName
  }
}

struct TaskHubConsentGrant: Codable, Identifiable {
  let id: String
  let businessScope: String
  let destination: TaskHubConsentDestination
  let grantedAt: Date
}

struct TaskHubConsentRequest: Identifiable {
  let id = UUID()
  let businessScope: String
  let sourcePath: String
  let destinations: [TaskHubConsentDestination]
  // No body, attachments, draft, submission closure or retry intent is retained.
}

enum TaskHubConsent {
  static func requiredReviewers(_ policy: TaskHubJSON?) -> [String] {
    policy?["requiredAcceptances"]?.array.compactMap {
      $0["mode"]?.string == "agent_host" ? $0["reviewerId"]?.string : nil
    } ?? []
  }
  static func unique(_ destinations: [TaskHubConsentDestination]) -> [TaskHubConsentDestination] {
    destinations.reduce(into: []) { result, next in
      if !result.contains(where: { $0.scope == next.scope }) { result.append(next) }
    }
  }
  static func permits(_ destinations: [TaskHubConsentDestination], businessScope: String, grants: [TaskHubConsentGrant]) -> Bool {
    destinations.allSatisfy { destination in
      grants.contains { $0.businessScope == businessScope && $0.destination.scope == destination.scope }
    }
  }
}

private struct TaskHubConsentPresentation: ViewModifier {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let path: String
  @State private var active = false
  func body(content: Content) -> some View {
    content.sheet(item: Binding(
      get: { active && store.consentRequest?.sourcePath == path ? store.consentRequest : nil },
      set: { if active, $0 == nil, let request = store.consentRequest, request.sourcePath == path { store.dismissConsent(request.id) } }
    )) { request in
      TaskHubConsentPermissionView(store: store, request: request)
    }
    .onAppear { active = true }
    .onDisappear { active = false }
  }
}

extension View {
  func taskHubConsent(_ store: TaskHubStore, path: String) -> some View {
    modifier(TaskHubConsentPresentation(store: store, path: path))
  }
}

private struct TaskHubConsentTargetView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  let destination: TaskHubConsentDestination
  var body: some View {
    let target = destination.scope.target
    VStack(alignment: .leading, spacing: 8) {
      Text(destination.computerName).font(.headline)
      Text(destination.projectName).font(.subheadline).foregroundStyle(.secondary)
      LabeledContent("Agent", value: target.agentId)
      if target.agentId == "codex" {
        Text(L10n.tr("Codex 账户仅为信息，不作为机器身份或许可范围。证书及已批准客户端密钥仍须核对。")).font(.caption)
      } else {
        identity(L10n.tr("AI 账户来源"), target.account?.authority ?? L10n.tr("未核对"))
        identity(L10n.tr("AI 账户标识"), target.account?.subject ?? L10n.tr("未核对"))
      }
      identity("Host", target.hostId)
      identity("Workspace", target.workspaceId)
      identity("Runtime", target.runtimeId)
      Text(destination.scope.model == "follow-computer" ? L10n.tr("模型：跟随电脑端当前 AI 配置（含后续调整）") : L10n.tr("模型：") + destination.scope.model)
        .font(.subheadline)
    }.padding(.vertical, 4)
  }
  private func identity(_ name: String, _ value: String) -> some View {
    VStack(alignment: .leading, spacing: 2) {
      Text(name).font(.caption).foregroundStyle(.secondary)
      Text(value).font(.caption).textSelection(.enabled)
    }
  }
}

private struct TaskHubConsentPermissionView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  let request: TaskHubConsentRequest
  var body: some View {
    NavigationStack {
      Form {
        Section(L10n.tr("当前任务账户")) {
          LabeledContent(L10n.tr("任务主服务"), value: store.binding?.service.name ?? L10n.tr("未连接"))
          LabeledContent(L10n.tr("人类账户"), value: store.actor?.name ?? L10n.tr("未登录"))
          Text(store.binding?.service.identity ?? L10n.tr("未核对主服务")).font(.caption).textSelection(.enabled)
          Text(L10n.tr("人类成员 ID：") + (store.actor?.id ?? L10n.tr("未核对"))).font(.caption).textSelection(.enabled)
          Text(L10n.tr("许可仅保存在本机当前任务主服务与人类账户范围内，不沿用聊天连接或另一台手机的许可。")).font(.caption).foregroundStyle(.secondary)
        }
        Section(L10n.tr("允许哪些实际目标")) {
          ForEach(Array(request.destinations.enumerated()), id: \.offset) { _, destination in
            TaskHubConsentTargetView(destination: destination)
          }
        }
        Section(L10n.tr("第三方 AI 如何处理任务")) {
          Text(L10n.tr("任务需求、反馈、回复、图片证据、候选材料及相关任务上下文可能由主服务自动派发到这些电脑，再由电脑配置的第三方 AI 处理，用于开发、自验、独立验收和最终交付。"))
          Text(L10n.tr("跟随电脑表示允许该目标使用电脑端当前及之后调整的 AI 配置；这里没有承诺某个固定模型或服务商。请在电脑端核对实际服务商、数据保留、训练用途和处理地区。Agent 或机器运行目标变化后需要重新确认；Codex 账户变化不改变机器许可，其他 Agent 的账户规则保持。"))
          Text(L10n.tr("允许只保存目标许可。返回后须再次点击原提交按钮；本页不会发送内容、派发任务或重放未知操作。许可不代替项目权限、Host 授权或沙盒权限。"))
          Text(L10n.tr("可在“主服务与账户 → 任务 AI 数据共享”撤回。撤回会阻止本机后续提交，不能撤回已经发到主服务或 AI 的内容，也不会停止已获派发的自动工作流；需要停止时请处理对应电脑的任务。"))
          Link(L10n.tr("隐私政策"), destination: L10n.privacyPolicyURL)
        }
        Section {
          Button(L10n.tr("允许这些目标，返回后再次提交")) { store.allowConsent(request.id) }
            .accessibilityIdentifier("task-hub-ai-sharing-allow")
          Button(L10n.tr("暂不允许"), role: .cancel) { store.dismissConsent(request.id) }
            .accessibilityIdentifier("task-hub-ai-sharing-decline")
        }
        if let error = store.error { Section { Text(error).foregroundStyle(.red) } }
      }
      .navigationTitle(L10n.tr("任务 AI 数据共享")).navigationBarTitleDisplayMode(.inline)
      .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.tr("取消")) { store.dismissConsent(request.id) } } }
    }
  }
}

struct TaskHubConsentSettingsView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: TaskHubStore
  var body: some View {
    List {
      Section {
        Text(L10n.tr("这些许可属于本机当前任务主服务和人类账户。聊天连接的许可独立管理。允许后由你再次提交；未知结果只查询原回执。"))
        Text(L10n.tr("跟随电脑的许可涵盖该目标的 AI 配置后续调整。撤回只影响本机后续提交，不删除已发送内容，也不停止电脑端已获派发的自动工作流。"))
      }
      Section(L10n.tr("已允许的目标")) {
        if store.consentGrants.isEmpty { Text(L10n.tr("尚未允许任务数据共享")).foregroundStyle(.secondary) }
        ForEach(store.consentGrants) { grant in
          VStack(alignment: .leading, spacing: 8) {
            TaskHubConsentTargetView(destination: grant.destination)
            Text(grant.grantedAt, format: .dateTime.year().month().day().hour().minute()).font(.caption).foregroundStyle(.secondary)
            Button(L10n.tr("撤回许可"), role: .destructive) { store.revokeConsent(grant.id) }
              .accessibilityIdentifier("task-hub-ai-sharing-revoke-" + grant.id)
          }
        }
      }
      if let error = store.error { Section { Text(error).foregroundStyle(.red) } }
    }.navigationTitle(L10n.tr("任务 AI 数据共享")).navigationBarTitleDisplayMode(.inline)
  }
}
