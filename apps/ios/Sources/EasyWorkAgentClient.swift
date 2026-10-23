import Foundation
import UniformTypeIdentifiers

enum EasyWorkReceiptNormalization {
  static func decode(_ result: [String: Any], clientMessageId: String, hostId: String,
    threadId: String, defaultState: String, fallbackTurnId: String? = nil,
    fallbackError: String? = nil) throws -> DeliveryReceipt {
    var receipt = result
    receipt["clientMessageId"] = clientMessageId
    receipt["hostId"] = hostId
    receipt["threadId"] = threadId
    receipt["state"] = result["state"] as? String ?? defaultState
    receipt["turnId"] = result["runId"] as? String ?? result["turnId"] as? String ?? fallbackTurnId
    if result["error"] == nil { receipt["error"] = fallbackError }
    return try JSONDecoder().decode(DeliveryReceipt.self, from: easyWorkJSON(receipt))
  }
}

struct EasyWorkAgentClient: AgentClient {
  func updateNotificationPreferences(_ client: Client, deviceId: String, target: AgentRuntimeTarget, preferences: TaskNotificationPreferences) async throws -> TaskNotificationStatus {
    let prefs = try JSONSerialization.jsonObject(with: JSONEncoder().encode(preferences))
    let identity = try JSONSerialization.jsonObject(with: JSONEncoder().encode(target))
    return try JSONDecoder().decode(TaskNotificationStatus.self, from: await session(client).request("notification/preferences", ["deviceId": deviceId, "target": identity, "preferences": prefs]))
  }
  func registerNotifications(_ client: Client, registration: TaskNotificationRegistration) async throws -> TaskNotificationStatus {
    let encoded = try JSONEncoder().encode(registration)
    let value = try JSONSerialization.jsonObject(with: encoded)
    return try JSONDecoder().decode(TaskNotificationStatus.self, from: await session(client).request("notification/register", ["registration": value]))
  }
  func unregisterNotifications(_ client: Client, deviceId: String) async throws {
    _ = try await session(client).request("notification/unregister", ["deviceId": deviceId])
  }
  func notificationEvents(_ client: Client, deviceId: String, after: String) async throws -> TaskNotificationFeed {
    try JSONDecoder().decode(TaskNotificationFeed.self, from: await session(client).request("notification/events", ["deviceId": deviceId, "after": after]))
  }
  func permissionOptions(_ client: Client) -> [AgentPermissionOption] {
    [AgentPermissionOption(id: "confirm_every_operation", title: L10n.tr("逐项确认"), detail: L10n.tr("每项受权限管理的工具操作都请求确认"), icon: "hand.raised"),
     AgentPermissionOption(id: "confirm_high_risk", title: L10n.tr("高风险确认"), detail: L10n.tr("按 EasyWork 风险策略对高风险操作请求确认"), icon: "checkmark.shield"),
     AgentPermissionOption(id: "allow_all", title: L10n.tr("允许已授权操作"), detail: L10n.tr("免去任务工具确认，仍受设备授权和 Host 边界约束"), icon: "exclamationmark.shield", highPrivilege: true)]
  }
  func models(_ client: Client) async throws -> ModelList {
    try JSONDecoder().decode(ModelList.self, from: await session(client).request("model/list", [:]))
  }
  func executeQueue(_ client: Client, threadId: String, messageId: String, expectedTurnId: String?) async throws -> CommandResult {
    var params: [String: Any] = ["threadId": threadId, "messageId": messageId]
    if let expectedTurnId { params["expectedRunId"] = expectedTurnId }
    let result = try object(await session(client).request("queue/execute", params))
    return CommandResult(ok: ["submitted", "completed"].contains(result["state"] as? String ?? ""))
  }
  func cancelQueue(_ client: Client, threadId: String, messageId: String) async throws -> CommandResult {
    let result = try object(await session(client).request("queue/cancel", ["threadId": threadId, "messageId": messageId]))
    return CommandResult(ok: result["cancelled"] as? Bool == true)
  }
  func createProject(_ client: Client, name: String, directory: String, operationId: String) async throws -> WorkspaceSnapshot {
    let channel = try await session(client)
    let access = try object(await channel.request("filesystem/access-status", [:]))["access"] as? [String: Any]
    guard access?["status"] as? String == "granted" else {
      _ = try await channel.request("filesystem/request-access", [:])
      throw HostServiceError.message(L10n.tr("请在 EasyWork 桌面确认目录授权，然后重试。"))
    }
    let roots = try object(await channel.request("filesystem/roots", [:]))["roots"] as? [[String: Any]] ?? []
    let requested: String
    if directory.hasPrefix("/") { requested = (directory as NSString).standardizingPath }
    else {
      let context = try object(await channel.request("runtime/context", [:]))
      guard let root = context["workspaceDirectory"] as? String, root.hasPrefix("/") else { throw HostServiceError.message(L10n.tr("此电脑未提供默认目录，请填写完整目录路径")) }
      requested = ((root as NSString).appendingPathComponent(directory) as NSString).standardizingPath
      guard requested == root || requested.hasPrefix(root + "/") else { throw HostServiceError.message(L10n.tr("相对路径不能超出电脑工作目录")) }
    }
    guard var selected = roots.filter({ root in
      guard let path = root["displayPath"] as? String else { return false }
      return requested == path || requested.hasPrefix(path.hasSuffix("/") ? path : path + "/")
    }).max(by: { ($0["displayPath"] as? String ?? "").count < ($1["displayPath"] as? String ?? "").count }) else { throw HostServiceError.message(L10n.tr("目录不在此设备已授权范围中")) }
    while selected["displayPath"] as? String != requested {
      let value = try object(await channel.request("filesystem/list-directories", ["directoryRef": selected["directoryRef"] ?? ""]))
      let directories = value["directories"] as? [[String: Any]] ?? []
      guard let next = directories.first(where: { entry in
        guard let path = entry["displayPath"] as? String else { return false }
        return requested == path || requested.hasPrefix(path + "/")
      }) else { throw HostServiceError.message(L10n.tr("请选择已存在且已授权的目录")) }
      selected = next
    }
    _ = try await channel.request("project/create", ["name": name, "directoryRef": selected["directoryRef"] ?? "", "clientRequestId": operationId], id: operationId)
    return try await workspace(client, projectCursor: nil, threadCursor: nil)
  }
  func connectionTitle(_ client: Client) async -> String? { try? await session(client).activeTransportTitle() }
  func close(_ client: Client) async { await EasyWorkSessions.shared.remove(client.id) }
  private func session(_ client: Client) async throws -> EasyWorkRemoteSession { try await EasyWorkSessions.shared.session(client) }
  private func object(_ value: Any) throws -> [String: Any] { if let data = value as? Data { return try easyWorkObject(data) }; guard let result = value as? [String: Any] else { throw HostServiceError.invalidResponse }; return result }
  func workspace(_ client: Client, projectCursor: String?, threadCursor: String?) async throws -> WorkspaceSnapshot {
    let channel = try await session(client)
    let context = try object(await channel.request("runtime/context", [:]))
    let confirmed = try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(context["target"] ?? [:]))
    guard confirmed == channel.target else { throw HostServiceError.wrongHost }
    let projects = try object(await channel.request("project/list", [:]))["projects"] as? [[String: Any]] ?? []
    var params: [String: Any] = ["limit": 100]; if let threadCursor { params["cursor"] = threadCursor }
    let threads = try object(await channel.request("thread/list", params))
    let observed = try object(context["state"] ?? [:])
    let connectionState = client.agentConnection.map { AgentConnectionState(target: $0.target, phase: .connected, transportId: nil, processHealthy: observed["processHealthy"] as? Bool == true, runtimeConnected: observed["runtimeConnected"] as? Bool == true, historyReadable: observed["historyReadable"] as? Bool == true) }
    return WorkspaceSnapshot(hostId: client.id, name: client.name, connectionMode: "easywork-runtime",
      projects: projects.map { Project(id: $0["id"] as? String ?? "", hostId: client.id, name: $0["name"] as? String ?? L10n.tr("项目"), roots: ($0["workspacePath"] as? String).map { [$0] } ?? []) },
      threads: (threads["threads"] as? [[String: Any]] ?? []).map { conversation(client, $0) }, nextProjectCursor: nil, nextThreadCursor: threads["nextCursor"] as? String,
      capabilities: context["capabilities"] as? [String] ?? [], clientType: ClientType(id: "easywork", name: "EasyWork"), agentConnectionState: connectionState,
      pinnedThreadIds: threads["pinnedThreadIds"] as? [String])
  }
  private func conversation(_ client: Client, _ value: [String: Any]) -> Conversation {
    let active = value["activeRun"] as? [String: Any]
    return Conversation(id: value["id"] as? String ?? "", hostId: client.id, projectId: value["projectId"] as? String,
      title: value["title"] as? String ?? value["firstUserMessageText"] as? String ?? L10n.tr("新对话"), state: runState(active),
      hasUnreadTurn: value["hasUnreadTurn"] as? Bool)
  }
  private func runState(_ active: [String: Any]?) -> String {
    guard let active else { return "idle" }
    let status = active["status"] as? String ?? "unknown"
    switch status {
    case "paused", "interrupted", "cancelled", "failed": return status
    case "completed": return "idle"
    case "running", "queued", "starting", "pending": return "running"
    default: return "unknown"
    }
  }
  func createThread(_ client: Client, projectId: String?, operationId: String) async throws -> Conversation {
    var params: [String: Any] = ["clientRequestId": operationId]; if let projectId { params["projectId"] = projectId }
    let value = try object(await session(client).request("thread/create", params, id: operationId))
    return conversation(client, try object(value["thread"] ?? [:]))
  }
  func history(_ client: Client, threadId: String, cursor: String?) async throws -> HistorySnapshot {
    let channel = try await session(client)
    let value: [String: Any]
    let snapshot: [String: Any]
    let latestPage = try object(await channel.synchronizedThread(threadId))
    guard let revision = latestPage["branchRevision"] as? Int, revision >= 0 else { throw HostServiceError.invalidResponse }
    if let cursor {
      value = try object(await channel.request("thread/history", ["threadId": threadId, "before": cursor, "limit": 60])); snapshot = [:]
      let after = try object(await channel.synchronizedThread(threadId))
      guard after["branchRevision"] as? Int == revision else { throw HostServiceError.message("聊天历史已变化，请刷新后重试。") }
    } else {
      value = latestPage; snapshot = try object(value["snapshot"] ?? [:])
    }
    let thread = snapshot["thread"] as? [String: Any] ?? ["id": threadId]
    guard thread["id"] as? String == threadId else { throw HostServiceError.wrongHost }
    let active = snapshot["activeRun"] as? [String: Any]
    let pendingApproval = snapshot["pendingPermissionConfirmation"] as? [String: Any]
    let pendingQuestion = snapshot["pendingQuestion"] as? [String: Any]
    let control = snapshot["runControl"] as? [String: Any]
    let latest = snapshot["latestRun"] as? [String: Any]
    let terminalStatus = latest?["status"] as? String
    let state = pendingApproval != nil || pendingQuestion != nil ? "waiting-for-approval" : control?["phase"] as? String == "paused" ? "paused" : active != nil ? runState(active) : ["failed", "cancelled", "interrupted"].contains(terminalStatus ?? "") ? terminalStatus! : "idle"
    var approvals: [Approval] = []
    if let request = pendingApproval {
      let keys = ["threadId", "runId", "toolCallId", "operationId", "requestHash"]
      var identity: [String: Any] = [:]
      for key in keys { guard let value = request[key] as? String, !value.isEmpty else { throw HostServiceError.invalidResponse }; identity[key] = value }
      let labels = ["command": L10n.tr("命令"), "workdir": L10n.tr("目录"), "shell": L10n.tr("终端")]
      let summary = (request["argsSummary"] as? [[String: Any]] ?? []).compactMap { argument -> String? in
        guard let name = argument["name"] as? String, let value = argument["value"] as? String, !value.isEmpty else { return nil }
        return (labels[name] ?? name) + ": " + value
      }.joined(separator: "\n")
      let detail = request["message"] as? String ?? request["description"] as? String ?? (summary.isEmpty ? request["title"] as? String ?? L10n.tr("请确认此操作。") : summary)
      approvals.append(Approval(id: "approval." + (try easyWorkJSON(identity)).base64URL, threadId: threadId, turnId: request["runId"] as? String ?? "", kind: "command", title: request["title"] as? String ?? L10n.tr("操作需要确认"), detail: detail, questions: [], decisions: ["approve", "deny"], notificationRequestId: request["requestHash"] as? String))
    }
    if let request = pendingQuestion {
      let questions = (request["questions"] as? [[String: Any]] ?? []).map { question in
        ApprovalQuestion(id: question["header"] as? String ?? L10n.tr("问题"), question: question["question"] as? String ?? "", options: (question["options"] as? [[String: Any]])?.map { QuestionOption(label: $0["label"] as? String ?? "", description: $0["description"] as? String ?? "") }, isSecret: question["isSecret"] as? Bool, multiple: question["multiple"] as? Bool, custom: question["custom"] as? Bool)
      }
      approvals.append(Approval(id: "question." + (try easyWorkJSON(request)).base64URL, threadId: threadId, turnId: request["runId"] as? String ?? "", kind: "question", title: L10n.tr("需要你的回答"), detail: "", questions: questions, decisions: ["answer", "deny"], notificationRequestId: request["toolCallId"] as? String))
    }
    var messages: [ChatMessage] = []
    for message in value["messages"] as? [[String: Any]] ?? [] {
      let id = message["id"] as? String ?? UUID().uuidString
      let role = message["role"] as? String ?? "assistant"
      let status = message["status"] as? String
      let messageStart = messages.count
      for (index, part) in (message["content"] as? [[String: Any]] ?? []).enumerated() {
        let type = part["type"] as? String ?? "extension"
        var data = part; if let nested = part["data"] as? [String: Any] { data.merge(nested) { _, rhs in rhs } }
        if type == "text" || type == "reasoning" {
          let reasoningStatus = status == "complete" ? "completed" : ["running", "streaming"].contains(status ?? "") ? "inProgress" : status
          messages.append(ChatMessage(id: "\(id):\(index)", role: type == "reasoning" ? "tool" : role, text: type == "reasoning" ? "" : data["text"] as? String ?? "", kind: type == "reasoning" ? "reasoning" : nil, status: type == "reasoning" ? reasoningStatus : status, detail: type == "reasoning" ? data["text"] as? String : nil, blocks: type == "text" ? [.text(data["text"] as? String ?? "")] : [.extensionData(namespace: "easywork.agent-protocol-v2.reasoning", data: try JSONDecoder().decode([String: AgentJSONValue].self, from: easyWorkJSON(data)))]))
        } else if type == "plan-artifact" {
          messages.append(ChatMessage(id: "\(id):\(index)", role: "assistant", text: data["markdown"] as? String ?? "", kind: "plan", status: data["status"] as? String ?? status))
        } else if type == "agent-debug" {
          let diagnostics = data["contextCompressionSampling"] as? [[String: Any]] ?? []
          for (offset, item) in diagnostics.enumerated() {
            let projection = item["projectionStatus"] as? String ?? "none"
            let confirmed = ["created", "regenerated", "repaired"].contains(projection)
            guard confirmed || item["action"] as? String == "compression_started" else { continue }
            let compressionStatus = confirmed ? "completed" : data["status"] as? String == "running" ? "inProgress" : "interrupted"
            messages.append(ChatMessage(id: "\(id):\(index):compression:\(offset)", role: "tool", text: confirmed ? L10n.tr("上下文已压缩") : L10n.tr("上下文压缩"), kind: "contextCompaction", status: compressionStatus, detail: String(decoding: try easyWorkJSON(item), as: UTF8.self)))
          }
          if let error = data["errorMessage"] as? String, data["status"] as? String == "failed" { messages.append(ChatMessage(id: "\(id):\(index)", role: "tool", text: error, kind: "tool", status: "failed")) }
        } else if type == "token-usage" { continue
        } else if ["context-compression", "context-compaction", "contextCompaction"].contains(type) {
          messages.append(ChatMessage(id: "\(id):\(index)", role: "tool", text: data["summary"] as? String ?? L10n.tr("上下文压缩"), kind: "contextCompaction", status: data["status"] as? String ?? status, detail: data["detail"] as? String))
        } else if type == "attachment" {
          if let resource = resource(client, threadId: threadId, data: data) { messages.append(ChatMessage(id: "\(id):\(index)", role: role, text: "", status: status, attachments: [resource], blocks: [.resource(id: resource.id, name: resource.name, mediaType: resource.mediaType)])) }
        } else {
          let args = data["args"] as? [String: Any]
          let text = args?["target"] as? String ?? args?["description"] as? String ?? data["toolName"] as? String ?? data["name"] as? String ?? type
          let detail = String(decoding: try easyWorkJSON(data), as: UTF8.self)
          let toolStatus = type == "tool-call" ? (data["isError"] as? Bool == true ? "failed" : data["result"] != nil ? "completed" : status == "interrupted" ? "interrupted" : "inProgress") : (data["status"] as? String ?? "completed")
          var blocks: [AgentContentPart] = [.extensionData(namespace: "easywork.agent-protocol-v2." + type, data: try JSONDecoder().decode([String: AgentJSONValue].self, from: easyWorkJSON(data)))]
          if type == "tool-call" { blocks.insert(.tool(id: data["toolCallId"] as? String ?? "\(id):\(index)", name: text, status: toolStatus, input: args.map { String(decoding: (try? easyWorkJSON($0)) ?? Data(), as: UTF8.self) }, output: data["result"].map { String(decoding: (try? easyWorkJSON($0)) ?? Data(), as: UTF8.self) }), at: 0) }
          let patch = args?["patch"] as? String ?? args?["stdin"] as? String
          let canonicalPatch = patch?.hasPrefix("*** Begin Patch") == true ? patch : nil
          let resultDiff = (data["result"] as? [String: Any])?["diff"] as? String
          let isDiff = ["diff", "file-change", "fileChange"].contains(type) || type == "tool-call" && (canonicalPatch != nil || resultDiff != nil)
          let diff = resultDiff ?? canonicalPatch ?? data["diff"] as? String ?? data["text"] as? String ?? detail
          if isDiff { blocks.insert(.diff(id: "\(id):\(index)", text: diff), at: 0) }
          messages.append(ChatMessage(id: "\(id):\(index)", role: "tool", text: text, kind: isDiff ? "fileChange" : "tool", status: toolStatus, detail: isDiff ? diff : detail, blocks: blocks))
        }
      }
      for index in messageStart..<messages.count {
        messages[index].runId = message["sourceRunId"] as? String
        messages[index].replyMessageId = id
      }
    }
    // Resolve only references supplied by the authoritative conversation. The host
    // rechecks provenance and workspace scope before exposing a logical resource.
    if cursor == nil, active == nil, let pattern = try? NSRegularExpression(pattern: #"\]\(([^\s\)]+)\)"#) {
      for index in messages.indices where messages[index].role == "assistant" {
        let text = messages[index].text
        var files: [RemoteAttachment] = []
        var replacements: [(NSRange, String)] = []
        for match in pattern.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
          guard let range = Range(match.range(at: 1), in: text) else { continue }
          let reference = String(text[range]).removingPercentEncoding ?? String(text[range])
          if reference.hasPrefix("http:") || reference.hasPrefix("https:") { continue }
          // Message identity avoids keeping an old digest when a later run rewrites a path.
          let key = (try easyWorkJSON(["target": channel.target.dictionary, "threadId": threadId, "messageId": messages[index].id, "reference": reference])).base64URL
          do {
            let value: Data
            if let cached = await EasyWorkResolvedResources.shared.get(key) { value = cached }
            else { value = try await channel.request("resource/resolve", ["threadId": threadId, "reference": reference]); await EasyWorkResolvedResources.shared.put(key, value) }
            let resolved = try object(value)
            if let file = resource(client, threadId: threadId, data: try object(resolved["attachment"] ?? [:])) {
              if !files.contains(where: { $0.id == file.id }) { files.append(file) }
              var destination = URLComponents()
              destination.scheme = "codex-attachment"; destination.host = "open"
              destination.queryItems = [URLQueryItem(name: "id", value: file.id)]
              if let url = destination.url { replacements.append((match.range(at: 1), url.absoluteString)) }
            }
          } catch { /* A non-file markdown link keeps its original text. */ }
        }
        if !files.isEmpty {
          var combined = messages[index].attachments ?? []
          for file in files {
            if let existing = combined.firstIndex(where: { $0.id == file.id }) { combined[existing] = file }
            else { combined.append(file) }
          }
          messages[index].attachments = combined
          var rewritten = text
          for (range, destination) in replacements.reversed() {
            if let range = Range(range, in: rewritten) { rewritten.replaceSubrange(range, with: destination) }
          }
          messages[index].text = rewritten
        }
      }
    }
    let plan = snapshot["currentPlan"] as? [String: Any]
    let steps = (plan?["steps"] as? [[String: Any]] ?? []).map { PlanStep(step: $0["title"] as? String ?? "", status: $0["status"] as? String == "in_progress" ? "inProgress" : $0["status"] as? String ?? "pending") }
    var proposed: ProposedPlan?
    if let plan, let id = plan["planId"] as? String, let version = plan["version"] as? Int, ["ready", "saved"].contains(plan["status"] as? String ?? "") {
      proposed = ProposedPlan(id: "easywork-plan." + (try easyWorkJSON(["id": id, "version": version])).base64URL, turnId: plan["createdRunId"] as? String ?? "", text: plan["markdown"] as? String ?? "")
    }
    var preferences = thread["generationPreferences"] as? [String: Any] ?? [:]
    if let effective = latest?["settings"] as? [String: Any] { preferences.merge(effective) { _, runtime in runtime } }
    let model = preferences["modelRef"] as? [String: Any]
    let modelId = model.flatMap { value -> String? in guard let provider = value["providerId"] as? String, let id = value["modelId"] as? String else { return nil }; return provider + "/" + id }
    let settings = GenerationOptions(model: modelId, effort: (preferences["reasoning"] as? [String: Any])?["effort"] as? String, permissionMode: preferences["permissionMode"] as? String)
    var receipts = try JSONDecoder().decode([DeliveryReceipt].self, from: easyWorkJSON((snapshot["queueReceipts"] as? [[String: Any]] ?? []).map { value in var receipt = value; receipt["hostId"] = client.id; return receipt }))
    if let latest = snapshot["latestRun"] as? [String: Any], let status = latest["status"] as? String, ["failed", "cancelled", "interrupted"].contains(status), active == nil { receipts.append(DeliveryReceipt(clientMessageId: latest["id"] as? String ?? "", hostId: client.id, threadId: threadId, state: status, turnId: latest["id"] as? String, error: latest["error"] as? String)) }
    let controlTurn = ["paused", "pausing", "resuming"].contains(control?["phase"] as? String ?? "") ? control?["runId"] as? String : nil
    let runtime = cursor == nil ? ThreadRuntime(state: state, turnId: active?["runId"] as? String ?? active?["id"] as? String ?? controlTurn, plan: steps, error: active == nil ? latest?["error"] as? String : nil, approvals: approvals, receipts: receipts, canControl: true, collaborationMode: (active?["agentRunMode"] as? String ?? preferences["agentRunMode"] as? String) == "plan" ? "plan" : "default", proposedPlan: proposed, generationSettings: settings) : nil
    var summary = conversation(client, thread); summary.state = state
    return HistorySnapshot(hostId: client.id, threadId: threadId, thread: summary, messages: messages, nextCursor: value["hasEarlier"] as? Bool == true ? value["before"] as? String : nil, runtime: runtime, epoch: await channel.epoch, cursor: await channel.eventSequence, historyWindow: HistoryWindow(version: 1, kind: cursor == nil ? "latest" : "earlier"), historyRevision: revision)
  }
  func send(_ client: Client, reply: PendingReply) async throws -> DeliveryReceipt {
    guard reply.hostId == client.id, ["next-turn", "queue", "steer"].contains(reply.mode) else { throw HostServiceError.message(L10n.tr("此客户端未开放此发送方式")) }
    let channel = try await session(client)
    var parts: [[String: Any]] = reply.text.isEmpty ? [] : [["type": "text", "data": ["text": reply.text]]]
    for id in reply.attachmentIds {
      let result = try object(await channel.request("resource/read", ["resourceId": id]))
      let record = try object(result["transfer"] ?? [:])
      guard record["threadId"] as? String == reply.threadId else { throw HostServiceError.wrongHost }
      let contentType = record["contentType"] as? String ?? "application/octet-stream"
      var attachment = record.filter { ["name", "resourceId", "resourceUri", "workspaceRelativePath", "absolutePath", "size", "sha256"].contains($0.key) }
      attachment["id"] = record["transferId"]; attachment["contentType"] = contentType; attachment["kind"] = contentType.hasPrefix("image/") ? "image" : "file"
      parts.append(["type": "attachment", "data": attachment])
    }
    var settings: [String: Any] = [:]
    if let model = reply.model { settings["model"] = model }
    if let effort = reply.effort { settings["effort"] = effort }
    if let permission = reply.permissionMode { settings["permissionMode"] = permission }
    if let mode = reply.collaborationMode { settings["mode"] = mode == "plan" ? "plan" : "normal" }
    if reply.speed != nil { throw HostServiceError.message(L10n.tr("EasyWork 未开放生成速度设置")) }
    let method: String
    var params: [String: Any] = ["threadId": reply.threadId]
    if reply.mode == "steer" {
      guard let turn = reply.expectedTurnId else { throw HostServiceError.invalidResponse }
      method = "turn/steer"; params["turnId"] = turn; params["guidanceId"] = reply.clientMessageId; params["parts"] = parts; params["message"] = reply.text
    } else {
      params["clientRequestId"] = reply.clientMessageId
      if let opaque = reply.expectedPlanId {
        guard opaque.hasPrefix("easywork-plan."), let bytes = Data(base64URL: String(opaque.dropFirst(14))), let plan = try? easyWorkObject(bytes), let id = plan["id"] as? String, let version = plan["version"] as? Int else { throw HostServiceError.invalidResponse }
        if reply.collaborationMode == "plan" {
          method = "turn/start"; params["revisionOfPlanId"] = id; params["expectedPlanVersion"] = version; params["parts"] = parts; params["settings"] = settings
        } else {
          method = "plan/action"; params["planId"] = id; params["expectedVersion"] = version; params["action"] = "approve-current"; params["settings"] = settings.filter { $0.key != "mode" }
        }
      } else { method = "turn/start"; params["parts"] = parts; params["mode"] = reply.mode; params["settings"] = settings }
    }
    let result = try object(await channel.request(method, params, id: reply.clientMessageId))
    return try EasyWorkReceiptNormalization.decode(result, clientMessageId: reply.clientMessageId,
      hostId: client.id, threadId: reply.threadId, defaultState: "submitted",
      fallbackTurnId: reply.expectedTurnId)
  }
  func receipt(_ client: Client, id: String) async throws -> DeliveryReceipt {
    let bytes = try await session(client).request("command/status", ["commandId": id])
    guard !bytes.elementsEqual(Data("null".utf8)) else { throw HostServiceError.message(L10n.tr("Host 尚未找到此操作回执，请先核对桌面历史。原消息和操作 ID 已保留。")) }
    let value = try object(bytes)
    let response = value["response"] as? [String: Any]
    var result = response?["result"] as? [String: Any]
    if result?["state"] as? String == "queued" || result?["queueSource"] as? String != nil {
      guard let thread = result?["threadId"] as? String else { throw HostServiceError.invalidResponse }
      result = try object(await session(client).request("queue/status", ["threadId": thread, "messageId": id]))
      return try EasyWorkReceiptNormalization.decode(result ?? [:], clientMessageId: id,
        hostId: client.id, threadId: thread, defaultState: "uncertain")
    }
    return try EasyWorkReceiptNormalization.decode(result ?? [:], clientMessageId: id,
      hostId: client.id, threadId: result?["threadId"] as? String ?? value["threadId"] as? String ?? "",
      defaultState: value["state"] as? String == "completed" ? "submitted" : value["state"] as? String ?? "uncertain",
      fallbackError: (response?["error"] as? [String: Any])?["message"] as? String)
  }
  func events(_ client: Client, epoch: String, after: Int, threadId: String) async throws -> EventBatch {
    let channel = try await session(client)
    let eventEpoch = channel.target.runtimeId
    let cursor = try await channel.pollEvents(threadId: threadId, after: epoch == eventEpoch ? after : 0)
    // Coalesced deltas or unavailable older events are repaired by canonical history.
    let snapshot = try await history(client, threadId: threadId, cursor: nil)
    return EventBatch(hostId: client.id, epoch: eventEpoch, cursor: cursor, reset: epoch != eventEpoch, events: [HostEvent(threadId: threadId, snapshot: snapshot)], ready: true)
  }
  func stop(_ client: Client, threadId: String, turnId: String) async throws -> CommandResult {
    let response = try object(await session(client).request("turn/cancel", ["threadId": threadId, "turnId": turnId]))
    return CommandResult(ok: response["cancelled"] as? Bool == true)
  }
  func approve(_ client: Client, approval: Approval, decision: String, answers: [String: [String]]) async throws -> CommandResult {
    let channel = try await session(client)
    let result: [String: Any]
    if approval.id.hasPrefix("question."), let data = Data(base64URL: String(approval.id.dropFirst(9))) {
      let question = try object(easyWorkObject(data))
      var params: [String: Any] = ["threadId": approval.threadId, "kind": "question", "action": decision == "deny" ? "abandon" : "answer", "serverRequestId": question["toolCallId"] ?? "", "sessionId": question["sessionId"] ?? "", "runId": approval.turnId]
      if decision != "deny" {
        params["answer"] = ["questions": try approval.questions.map { item -> [String: Any] in
          let values = (answers[item.id] ?? []).filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
          let labels = Set((item.options ?? []).map(\.label))
          let selected = values.filter { labels.contains($0) }
          let custom = values.filter { !labels.contains($0) }.joined(separator: "\n")
          guard !values.isEmpty, item.multiple == true || selected.count <= 1, custom.isEmpty || item.custom != false else { throw HostServiceError.message(L10n.tr("回答不符合此问题的选项规则")) }
          var answer: [String: Any] = ["header": item.id]
          if !selected.isEmpty { answer["selectedOptions"] = selected }
          if !custom.isEmpty { answer["customAnswer"] = custom }
          return answer
        }]
      }
      result = try object(await channel.request("serverRequest/resolve", params))
    } else { result = try object(await channel.request("approval/respond", ["threadId": approval.threadId, "serverRequestId": approval.id, "decision": decision == "approve" || decision == "accept" ? "approve" : "deny"])) }
    return CommandResult(ok: result["accepted"] as? Bool == true || result["denied"] as? Bool == true)
  }
  func upload(_ client: Client, threadId: String, file: LocalAttachment, data: Data) async throws -> RemoteAttachment {
    let channel = try await session(client)
    let mediaType = UTType(filenameExtension: (file.name as NSString).pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    _ = try await channel.request("resource/upload", ["transferId": file.id, "name": file.name, "mediaType": mediaType, "size": data.count, "expectedSha256": HostService.hash(data), "purpose": "attachment", "threadId": threadId], id: "upload-" + file.id)
    let status = try object(await channel.request("resource/upload-status", ["transferId": file.id]))
    var offset = (status["transfer"] as? [String: Any])?["transferredBytes"] as? Int ?? status["transferredBytes"] as? Int ?? 0
    while offset < data.count {
      let end = min(offset + 192 * 1024, data.count)
      _ = try await channel.request("resource/upload-chunk", ["transferId": file.id, "offset": offset, "dataBase64": data.subdata(in: offset..<end).base64URL], id: "chunk-\(file.id)-\(offset)")
      offset = end
    }
    let completed = try object(await channel.request("resource/upload-complete", ["transferId": file.id], id: "complete-" + file.id))
    guard let attachment = resource(client, threadId: threadId, data: try object(completed["attachment"] ?? [:])), attachment.sha256 == HostService.hash(data), attachment.sizeBytes == data.count else { throw HostServiceError.invalidResponse }
    return attachment
  }
  private func resource(_ client: Client, threadId: String, data: [String: Any]) -> RemoteAttachment? {
    guard let id = data["resourceId"] as? String, let hash = data["sha256"] as? String else { return nil }
    return RemoteAttachment(id: id, hostId: client.id, threadId: threadId, name: data["name"] as? String ?? L10n.tr("文件"), mediaType: data["contentType"] as? String ?? "application/octet-stream", sizeBytes: data["size"] as? Int ?? 0, sha256: hash)
  }
  func download(_ client: Client, file: RemoteAttachment) async throws -> URL {
    guard file.hostId == client.id, let target = client.agentConnection?.target else { throw HostServiceError.wrongHost }
    let scope = HostService.hash(try easyWorkJSON([target.agentId, target.account?.authority ?? "", target.account?.subject ?? "", target.hostId, target.workspaceId, target.runtimeId, file.threadId]))
    let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads/agent-" + scope + "/" + file.id, isDirectory: true)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    let destination = root.appendingPathComponent((file.name as NSString).lastPathComponent)
    if let cached = try? Data(contentsOf: destination), cached.count == file.sizeBytes, HostService.hash(cached) == file.sha256 { return destination }
    let channel = try await session(client)
    let value = try object(await channel.request("resource/read", ["resourceId": file.id]))
    let record = try object(value["transfer"] ?? [:]); guard record["threadId"] as? String == file.threadId, let transferId = record["transferId"] as? String else { throw HostServiceError.wrongHost }
    var data = Data()
    while data.count < file.sizeBytes {
      let response = try object(await channel.request("resource/download", ["resourceId": file.id, "transferId": transferId, "offset": data.count, "length": 192 * 1024]))
      guard let chunk = Data(base64URL: response["dataBase64"] as? String ?? ""), !chunk.isEmpty, response["offset"] as? Int == data.count else { throw HostServiceError.invalidResponse }
      data.append(chunk)
    }
    guard data.count == file.sizeBytes, HostService.hash(data) == file.sha256 else { throw HostServiceError.message(L10n.tr("文件完整性校验失败")) }
    try data.write(to: destination, options: [.atomic, .completeFileProtectionUnlessOpen])
    _ = try await channel.request("resource/download-complete", ["resourceId": file.id, "transferId": transferId], id: "download-complete-" + transferId)
    return destination
  }
}
#if DEBUG
extension EasyWorkAgentClient {
  func devReplay(_ client: Client, operation: AgentDEVReplayOperation) async throws -> [String] {
    guard ProcessInfo.processInfo.arguments.contains("-easywork-local-dev"), ProcessInfo.processInfo.arguments.contains("-ui-testing"), var lan = client.agentConnection else { throw HostServiceError.unavailable }
    let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(CredentialVault.token(lan.credentialRef).utf8))
    var relay = lan; lan.preference = .lanOnly; relay.preference = .relayOnly
    let local = try EasyWorkRemoteSession(connection: lan, credential: credential)
    let remote = try EasyWorkRemoteSession(connection: relay, credential: credential)
    let command: EasyWorkDEVCommand
    if operation == .create {
      let id = UUID().uuidString
      command = EasyWorkDEVCommand(method: "thread/create", params: try easyWorkJSON(["clientRequestId": id]), id: id)
    } else { command = try await EasyWorkDEVCommands.shared.last(target: local.target) }
    do {
      async let first = local.replay(command)
      async let second = remote.replay(command)
      let values = try await [first, second].map { try easyWorkObject($0) }
      let ids = values.compactMap { operation == .create ? ($0["thread"] as? [String: Any])?["id"] as? String : $0["runId"] as? String }
      guard ids.count == 2, Set(ids).count == 1 else { throw HostServiceError.invalidResponse }
      await local.close(); await remote.close()
      return ids
    } catch { await local.close(); await remote.close(); throw error }
  }
}
#endif

actor EasyWorkResolvedResources {
  static let shared = EasyWorkResolvedResources()
  private var values: [String: Data] = [:]
  func get(_ key: String) -> Data? { values[key] }
  func put(_ key: String, _ data: Data) { values[key] = data }
}
