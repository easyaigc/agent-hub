import Foundation

/// The adapter alone translates the local connection key to the authenticated wire Host.
enum EasyWorkChatManagement {
  static func wireRequest(_ request: ChatActionRequest, client: Client) throws -> ChatActionRequest {
    guard SharedMobileRules.chatActionValid(request), request.hostId == client.id,
      let target = client.agentConnection?.target, request.target == target, target.agentId == "easywork" else {
      throw HostServiceError.wrongHost
    }
    return ChatActionRequest(hostId: target.hostId, target: target, threadId: request.threadId,
      clientOperationId: request.clientOperationId, action: request.action, title: request.title)
  }
  static func localReceipt(_ value: [String: Any], client: Client, threadId: String, operationId: String) throws -> ChatActionReceipt {
    let receipt = try JSONDecoder().decode(ChatActionReceipt.self, from: easyWorkJSON(value))
    guard let target = client.agentConnection?.target, receipt.hostId == target.hostId,
      receipt.target == target, receipt.threadId == threadId, receipt.id == operationId,
      value["waitingEnded"] == nil else { throw HostServiceError.wrongHost }
    // Decode only after strict shared validation; unknown fields cannot become a receipt.
    guard SharedMobileRules.chatActionOutcomeValid(try easyWorkJSON(value)) else { throw HostServiceError.invalidResponse }
    return ChatActionReceipt(hostId: client.id, target: receipt.target, threadId: receipt.threadId,
      clientOperationId: receipt.id, action: receipt.action, phase: receipt.phase, error: receipt.error, title: receipt.title)
  }
  static func status(_ value: [String: Any], client: Client, deviceId: String, wireTarget: EasyWorkTarget,
    threadId: String, operationId: String) throws -> ChatActionReceipt {
    guard value["state"] != nil else { throw HostServiceError.rejected(404, L10n.tr("未查到原聊天操作记录，结果仍未确认；不会重新提交。")) }
    guard value["deviceId"] as? String == deviceId, value["operationId"] as? String == operationId,
      value["threadId"] as? String == threadId, value["method"] as? String == "thread/manage",
      let target = value["target"] as? [String: Any],
      try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(target)) == wireTarget,
      let original = value["request"] as? [String: Any], original["protocolVersion"] as? Int == 2,
      original["kind"] as? String == "request", original["method"] as? String == "thread/manage",
      original["requestId"] as? String == operationId, let originalTarget = original["target"] as? [String: Any],
      try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(originalTarget)) == wireTarget,
      let params = original["params"] as? [String: Any] else { throw HostServiceError.wrongHost }
    // The original request, not visible sidebar state, identifies every result.
    var submitted = params; submitted["phase"] = "submitted"; submitted["error"] = NSNull()
    let intent = try localReceipt(submitted, client: client, threadId: threadId, operationId: operationId)
    let state = value["state"] as? String
    if let response = value["response"] as? [String: Any] {
      guard response["protocolVersion"] as? Int == 2, response["kind"] as? String == "response",
        response["requestId"] as? String == operationId else { throw HostServiceError.invalidResponse }
      if state == "completed", response["ok"] as? Bool == true, let result = response["result"] as? [String: Any] {
        let receipt = try localReceipt(result, client: client, threadId: threadId, operationId: operationId)
        guard SharedMobileRules.chatActionMatches(receipt, original: intent) else { throw HostServiceError.wrongHost }
        return receipt
      }
      if state == "failed", response["ok"] as? Bool == false, let error = response["error"] as? [String: Any],
        error["retryable"] as? Bool == false, let code = error["code"] as? String, code != "operation_uncertain" {
        return outcome(intent, phase: "rejected", error: error["message"] as? String ?? code)
      }
    }
    guard ["submitting", "uncertain", "completed", "failed"].contains(state ?? "") else { throw HostServiceError.invalidResponse }
    return outcome(intent, phase: state == "submitting" ? "submitted" : "outcome-unknown",
      error: L10n.tr("操作结果待确认，请在对话选项中查询原记录。"))
  }
  private static func outcome(_ intent: ChatActionReceipt, phase: String, error: String?) -> ChatActionReceipt {
    ChatActionReceipt(hostId: intent.hostId, target: intent.target, threadId: intent.threadId,
      clientOperationId: intent.id, action: intent.action, phase: phase, error: error, title: intent.title)
  }
}

extension EasyWorkAgentClient {
  private func workspaceWire(_ request: WorkspaceManagementRequest, client: Client) throws -> WorkspaceManagementRequest {
    guard request.action == .archiveThreads, request.hostId == client.id,
      let target = client.agentConnection?.target, target == request.target, target.agentId == "easywork",
      SharedMobileRules.workspaceArchiveRequestValid(request) else { throw HostServiceError.wrongHost }
    return WorkspaceManagementRequest(hostId: target.hostId, target: target,
      clientOperationId: request.clientOperationId, action: request.action, ids: request.ids)
  }
  private func workspaceReceipt(_ value: [String: Any], client: Client, request: WorkspaceManagementRequest) throws -> WorkspaceManagementOperation {
    let wire = try workspaceWire(request, client: client)
    let incoming = try JSONDecoder().decode(WorkspaceManagementOperation.self, from: easyWorkJSON(value))
    guard incoming.request == wire, Set(value.keys) == Set(["hostId", "target", "clientOperationId", "action", "ids", "items"]),
      let items = value["items"] as? [[String: Any]],
      items.allSatisfy({ Set($0.keys) == Set(["id", "state", "error"]) }) else { throw HostServiceError.wrongHost }
    let original = WorkspaceManagementOperation(hostId: wire.hostId, target: wire.target,
      clientOperationId: wire.clientOperationId, action: wire.action, ids: wire.ids,
      items: wire.ids.map { .init(id: $0, state: "pending", error: nil) })
    guard let verified = SharedMobileRules.workspaceArchiveMerged(incoming, original: original, previous: original) else {
      throw HostServiceError.invalidResponse
    }
    return WorkspaceManagementOperation(hostId: client.id, target: verified.target,
      clientOperationId: verified.id, action: verified.action, ids: verified.ids, items: verified.items)
  }
  func manageWorkspace(_ client: Client, request: WorkspaceManagementRequest) async throws -> WorkspaceManagementOperation {
    let wire = try workspaceWire(request, client: client)
    let channel = try await EasyWorkSessions.shared.session(client)
    do {
      let value = try easyWorkObject(await channel.request("workspace/manage", easyWorkObject(JSONEncoder().encode(wire)), id: request.clientOperationId))
      return try workspaceReceipt(value, client: client, request: request)
    } catch {
      if Task.isCancelled { throw CancellationError() }
      // An unavailable original response permits a read, never another batch dispatch.
      return try await workspaceManagementStatus(client, request: request)
    }
  }
  func workspaceManagementStatus(_ client: Client, request: WorkspaceManagementRequest) async throws -> WorkspaceManagementOperation {
    let wire = try workspaceWire(request, client: client)
    let channel = try await EasyWorkSessions.shared.session(client)
    let result = try easyWorkObject(await channel.request("workspace/manage-status", easyWorkObject(JSONEncoder().encode(wire))))
    guard let value = result["operation"] as? [String: Any] else {
      throw HostServiceError.rejected(404, L10n.tr("未查到原批量归档记录，结果仍未确认；不会重新提交。"))
    }
    return try workspaceReceipt(value, client: client, request: request)
  }
  func chatAction(_ client: Client, request: ChatActionRequest) async throws -> ChatActionReceipt {
    let wire = try EasyWorkChatManagement.wireRequest(request, client: client)
    let channel = try await EasyWorkSessions.shared.session(client)
    do {
      let params = try easyWorkObject(JSONEncoder().encode(wire))
      let value = try easyWorkObject(await channel.request("thread/manage", params, id: request.clientOperationId))
      let receipt = try EasyWorkChatManagement.localReceipt(value, client: client, threadId: request.threadId, operationId: request.clientOperationId)
      guard receipt.request == request else { throw HostServiceError.wrongHost }
      #if DEBUG
      if receipt.phase == "confirmed" { try await EasyWorkManagementDeliveryFault.shared.afterVerifiedSubmit(request) }
      #endif
      return receipt
    } catch {
      if Task.isCancelled { throw CancellationError() }
      // Exactly one read of the durable original. A missing record never permits another write.
      do {
        let receipt = try await chatActionStatus(client, threadId: request.threadId, operationId: request.clientOperationId)
        guard receipt.request == request else { throw HostServiceError.wrongHost }
        return receipt
      } catch { throw HostServiceError.message(L10n.tr("操作结果待确认，请在对话选项中查询原记录。")) }
    }
  }
  func chatActionStatus(_ client: Client, threadId: String, operationId: String) async throws -> ChatActionReceipt {
    let channel = try await EasyWorkSessions.shared.session(client)
    let data = try await channel.request("command/status", ["commandId": operationId])
    let value = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) as? [String: Any] ?? [:]
    let receipt = try EasyWorkChatManagement.status(value, client: client, deviceId: await channel.mobileDeviceId,
      wireTarget: channel.target, threadId: threadId, operationId: operationId)
    #if DEBUG
    if receipt.phase == "confirmed" { try await EasyWorkManagementDeliveryFault.shared.afterVerifiedQuery(receipt) }
    #endif
    return receipt
  }
}

#if DEBUG
/// Only the independent examiner can withhold local delivery of one authentic,
/// already validated confirmation. The Desktop record and transport stay intact.
private actor EasyWorkManagementDeliveryFault {
  static let shared = EasyWorkManagementDeliveryFault()
  private var original: ChatActionRequest?
  private var reads = 0
  private var enabled: Bool {
    Bundle.main.bundleIdentifier == "com.easyai.agenthub.easyworkacceptance"
      && ProcessInfo.processInfo.arguments.contains("-ui-testing")
      && ProcessInfo.processInfo.arguments.contains("-easywork-local-dev")
      && ProcessInfo.processInfo.environment["EASYWORK_MANAGEMENT_DELIVERY_FAULT_ONCE"] == "1"
  }
  func afterVerifiedSubmit(_ request: ChatActionRequest) throws {
    guard enabled, original == nil, request.action == .rename,
      request.target.agentId == "easywork", request.title?.hasPrefix("EASYWORK_UNKNOWN_ORIGINAL_") == true else { return }
    original = request
    throw HostServiceError.message("验收注入：原管理确认尚未交付，请查询原记录。")
  }
  func afterVerifiedQuery(_ receipt: ChatActionReceipt) async throws {
    guard enabled, receipt.request == original else { return }
    reads += 1
    if reads == 1 { throw HostServiceError.message("验收注入：原管理确认尚未交付，请查询原记录。") }
    if reads == 2 { try await Task.sleep(for: .seconds(8)) }
  }
}
#endif
