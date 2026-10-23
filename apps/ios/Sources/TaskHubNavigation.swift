import Foundation

extension SessionStore {
  /// Resolve the stable execution identity before switching away from the task workspace.
  func openTaskExecution(_ navigation: TaskHubExecutionNavigation) async throws {
    guard let target = navigation.target, let threadId = navigation.threadId, !threadId.isEmpty else { throw TaskHubError.message(L10n.tr("此执行尚未确认原桌面对话")) }
    guard let host = saved.clients.first(where: { !$0.isDemo && mobileReadingTarget($0) == target }) else { throw TaskHubError.message(L10n.tr("请先在客户端菜单连接这台执行电脑及对应 Agent 机器身份，再打开执行对话")) }
    let originalTarget = targetKey; let originalThread = selectedThreadId
    let provider = try AgentClients.resolve(host)
    let workspace = try await provider.workspace(host, projectCursor: nil, threadCursor: nil)
    guard workspace.hostId == host.id, (host.agentConnection?.target ?? workspace.readTarget) == target else { throw TaskHubError.changedIdentity }
    var reader = host; reader.readingTarget = target
    let history = try await provider.history(reader, threadId: threadId, cursor: nil)
    guard history.hostId == host.id, history.threadId == threadId, history.readTarget == target,
      let thread = history.thread, thread.id == threadId, thread.hostId == host.id else { throw TaskHubError.changedIdentity }
    guard let projectId = navigation.remoteProjectId,
      thread.projectId == projectId || thread.projectIds?.contains(projectId) == true else { throw TaskHubError.message(L10n.tr("执行对话的实际项目与任务映射不一致")) }
    guard targetKey == originalTarget, selectedThreadId == originalThread else { throw TaskHubError.message(L10n.tr("当前对话已切换，请重新选择执行记录")) }
    selectClient(host.id, refreshAutomatically: false)
    await refresh()
    guard client?.id == host.id, let current = client, mobileReadingTarget(current) == target else { throw TaskHubError.changedIdentity }
    openThread(thread)
  }
}
