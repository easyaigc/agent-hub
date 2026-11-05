import Foundation
import MobileCore
import CryptoKit
import UniformTypeIdentifiers
import Darwin
import CoreFoundation

#if DEBUG
/// Withholds one already verified synthetic fork result in the independent
/// acceptance App. The wire request, canonical receipt and destination remain
/// untouched; normal Store persistence and original-ID recovery are exercised.
actor EasyWorkForkDeliveryFault {
  static let shared = EasyWorkForkDeliveryFault()
  struct Witness: Sendable {
    let consumed: Bool
    let operationId: String?
    let forkedThreadId: String?
  }
  private var target: AgentRuntimeTarget?
  private var sourceThread: String?
  private var sourceTurn: String?
  private var afterVerified: (@MainActor @Sendable (ThreadForkRequest) throws -> Void)?
  private var consumed = false
  private var operationId: String?
  private var forkedThreadId: String?
  func arm(caseId: String, target: AgentRuntimeTarget, sourceThread: String, sourceTurn: String,
    afterVerified: @escaping @MainActor @Sendable (ThreadForkRequest) throws -> Void) async throws {
    let env = ProcessInfo.processInfo.environment
    guard await MainActor.run(body: { SessionStore.hostedEasyWorkAcceptanceEnabled }),
      env["EASYWORK_HOSTED_NATIVE_CASE"] == caseId,
      env["EASYWORK_HOSTED_NATIVE_FORK_DELIVERY_FAULT_CASE"] == caseId,
      target.agentId == "easywork", UUID(uuidString: sourceThread) != nil,
      UUID(uuidString: sourceTurn) != nil, self.target == nil else { throw HostServiceError.invalidResponse }
    self.target = target; self.sourceThread = sourceThread; self.sourceTurn = sourceTurn
    self.afterVerified = afterVerified
  }
  func afterVerifiedFork(_ request: ThreadForkRequest, result: ThreadForkOperation,
    target: AgentRuntimeTarget) async throws {
    guard !consumed, self.target == target, request.threadId == sourceThread,
      request.turnId == sourceTurn, request.environment == .sameDirectory,
      result.request == request, result.phase == .confirmed,
      let id = result.forkedThreadId, let handler = afterVerified else { return }
    consumed = true; operationId = request.clientOperationId; forkedThreadId = id
    afterVerified = nil
    try await handler(request)
    throw URLError(.networkConnectionLost)
  }
  func witness() -> Witness {
    Witness(consumed: consumed, operationId: operationId, forkedThreadId: forkedThreadId)
  }
}
#endif

/// The authenticated Host must confirm this exact attachment before it can enter a reply.
enum EasyWorkAttachmentUpload {
  static func displayName(_ value: String) -> String {
    let base = value.trimmingCharacters(in: .whitespacesAndNewlines)
      .split(separator: "/", omittingEmptySubsequences: true).last.map(String.init) ?? ""
    let safe = base.replacingOccurrences(of: "\0", with: "_").replacingOccurrences(of: "\\", with: "_")
    let name = String(decoding: safe.utf16.prefix(255), as: UTF16.self)
    return name.isEmpty ? "未命名文件" : name
  }
  static func perform(file: LocalAttachment, data: Data, threadId: String, mobileDeviceId: String,
    mediaType: String, request: (String, [String: Any], String?) async throws -> [String: Any]) async throws -> [String: Any] {
    guard !file.id.isEmpty, !threadId.isEmpty, !mobileDeviceId.isEmpty else { throw HostServiceError.invalidResponse }
    guard !data.isEmpty, data.count <= 20 * 1024 * 1024, data.count == file.size else {
      throw HostServiceError.message(L10n.tr("原附件内容已改变，未提交"))
    }
    let hash = HostService.hash(data), name = displayName(file.name)
    func transfer(_ response: [String: Any], completed: Bool = false) throws -> [String: Any] {
      guard let record = response["transfer"] as? [String: Any], record["transferId"] as? String == file.id,
        record["threadId"] as? String == threadId, record["mobileDeviceId"] as? String == mobileDeviceId,
        record["direction"] as? String == "mobile-to-desktop", record["purpose"] as? String == "attachment",
        record["name"] as? String == name, record["contentType"] as? String == mediaType,
        try EasyWorkReading.integer(record["size"]) == data.count,
        !completed || (record["status"] as? String == "completed" && record["sha256"] as? String == hash) else { throw HostServiceError.invalidResponse }
      return record
    }
    _ = try transfer(await request("resource/upload", ["transferId": file.id, "name": file.name,
      "mediaType": mediaType, "size": data.count, "expectedSha256": hash, "purpose": "attachment",
      "threadId": threadId], "upload-" + file.id))
    let status = try transfer(await request("resource/upload-status", ["transferId": file.id], nil))
    var offset = try EasyWorkReading.integer(status["transferredBytes"])
    guard offset <= data.count else { throw HostServiceError.invalidResponse }
    while offset < data.count {
      try Task.checkCancellation()
      let end = min(offset + 192 * 1024, data.count)
      let response = try await request("resource/upload-chunk", ["transferId": file.id, "offset": offset,
        "dataBase64": data.subdata(in: offset..<end).base64URL], "chunk-\(file.id)-\(offset)")
      guard try EasyWorkReading.integer(response["nextOffset"]) == end,
        let complete = response["complete"] as? NSNumber, CFGetTypeID(complete) == CFBooleanGetTypeID(),
        complete.boolValue == (end == data.count) else { throw HostServiceError.invalidResponse }
      offset = end
    }
    try Task.checkCancellation()
    let completed = try await request("resource/upload-complete", ["transferId": file.id], "complete-" + file.id)
    let record = try transfer(completed, completed: true)
    guard try EasyWorkReading.integer(record["transferredBytes"]) == data.count,
      let attachment = completed["attachment"] as? [String: Any], attachment["id"] as? String == file.id,
      let id = attachment["resourceId"] as? String, !id.isEmpty, record["resourceId"] as? String == id,
      attachment["name"] as? String == name, attachment["contentType"] as? String == mediaType,
      attachment["sha256"] as? String == hash, try EasyWorkReading.integer(attachment["size"]) == data.count else { throw HostServiceError.invalidResponse }
    return attachment
  }
}

enum EasyWorkPlanArtifactProjection {
  static func decode(_ data: [String: Any]) -> PlanArtifactSnapshot? {
    guard let id=data["planId"]as?String,let version=data["version"],
      let status=data["status"]as?String,let title=data["title"]as?String,
      let original=data["steps"]as?[[String:Any]] else{return nil}
    var steps:[[String:Any]]=[]
    for step in original {
      guard let id=step["stepId"]as?String,let order=step["order"],
        let title=step["title"]as?String,let status=step["status"]as?String else{return nil}
      steps.append(["id":id,"order":order,"title":title,"status":status])
    }
    let value:[String:Any]=["id":id,"version":version,"status":status,"title":title,"steps":steps]
    guard let bytes=try?JSONSerialization.data(withJSONObject:value),
      let result=try?JSONDecoder().decode(PlanArtifactSnapshot.self,from:bytes),result.valid else{return nil}
    return result
  }
}

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
    // turn/steer returns an acceptance decision, not a delivery state. A
    // completed command can still have refused guidance for an inactive run.
    if result["accepted"] != nil || ["accepted", "stale", "inactive"].contains(result["status"] as? String ?? "") {
      guard let accepted = result["accepted"] as? NSNumber, String(cString: accepted.objCType) == "c",
        let status = result["status"] as? String,
        accepted.boolValue ? status == "accepted" : ["stale", "inactive"].contains(status) else {
        throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Invalid EasyWork guidance acceptance"))
      }
      receipt["state"] = accepted.boolValue ? "submitted" : "failed"
      if !accepted.boolValue {
        // The expected old run is a routing precondition, not an executed turn.
        receipt.removeValue(forKey: "turnId")
        receipt["error"] = L10n.tr("原任务已变化或结束，追加未提交。")
      }
    }
    return try JSONDecoder().decode(DeliveryReceipt.self, from: easyWorkJSON(receipt))
  }
}

/// Bind a fresh file grant to the exact original message and link.
enum EasyWorkMessageFileResolution {
  static func originalMessageId(_ displayId: String) -> String {
    // EasyWork's tool rows use the adapter-owned <canonical UUID>:<part index>.
    // Other identifiers stay exact; never infer an ID from a title or filename.
    let pieces = displayId.split(separator: ":", omittingEmptySubsequences: false)
    if pieces.count == 2, UUID(uuidString: String(pieces[0])) != nil,
      !pieces[1].isEmpty, pieces[1].allSatisfy({ $0.isASCII && $0.isNumber }),
      UInt64(pieces[1]) != nil { return String(pieces[0]) }
    return displayId
  }
  static func attachment(threadId: String, messageId: String, reference: String,
    request: (String, [String: Any]) async throws -> [String: Any]) async throws -> [String: Any] {
    let original = originalMessageId(messageId)
    guard !threadId.isEmpty, !original.isEmpty, !reference.isEmpty, reference.utf8.count <= 8192 else {
      throw HostServiceError.invalidResponse
    }
    let value = try await request("resource/resolve", ["threadId": threadId,
      "messageId": original, "reference": reference])
    guard let version = value["version"] as? NSNumber,
      CFGetTypeID(version) != CFBooleanGetTypeID(), version.doubleValue == 1,
      value["threadId"] as? String == threadId, value["messageId"] as? String == original,
      value["reference"] as? String == reference, let file = value["attachment"] as? [String: Any],
      let id = file["resourceId"] as? String, !id.isEmpty, ![".", ".."].contains(id),
      !id.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
      (id as NSString).lastPathComponent == id,
      let name = file["name"] as? String, !name.isEmpty, (name as NSString).lastPathComponent == name,
      !name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
      let hash = file["sha256"] as? String,
      hash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
      let size = file["size"] as? NSNumber, CFGetTypeID(size) != CFBooleanGetTypeID(),
      size.doubleValue == Double(size.int64Value), size.int64Value >= 0,
      size.int64Value <= 20 * 1024 * 1024 else { throw HostServiceError.invalidResponse }
    return file
  }
}

/// Missing historical digests come only from an existing authenticated,
/// thread-bound Host grant. A missing grant may resolve only its authoritative
/// conversation resource URI; it never re-imports the underlying file.
enum EasyWorkHistoricalAttachment {
  static func readOrResolve(threadId:String, original:[String:Any],
    request:(String,[String:Any]) async throws -> [String:Any]) async throws -> [String:Any]? {
    try await metadata(threadId:threadId,original:original) { params in
      do {return try await request("resource/read",params)}
      catch is CancellationError {throw CancellationError()}
      catch {
        try Task.checkCancellation()
        guard let reference=original["resourceUri"] as? String,reference.hasPrefix("workspace://resource/") else{throw error}
        return try await request("resource/resolve",["threadId":threadId,"reference":reference])
      }
    }
  }

  static func metadata(threadId: String, original: [String: Any],
    read: ([String: Any]) async throws -> [String: Any]) async throws -> [String: Any]? {
    func valid(_ value: [String: Any]) -> Bool {
      guard let id=value["resourceId"] as? String,!id.isEmpty,let hash=value["sha256"] as? String,
        hash.range(of: "^[a-f0-9]{64}$",options:.regularExpression) != nil,
        let size=value["size"] as? NSNumber,String(cString:size.objCType) != "c",
        size.doubleValue>=0,size.doubleValue==Double(size.int64Value) else{return false}
      return true
    }
    if original["sha256"] != nil{return valid(original) ? original : nil}
    guard let id=original["resourceId"] as? String,!id.isEmpty else{return nil}
    do {
      guard let value=try await read(["threadId":threadId,"resourceId":id])["transfer"] as? [String:Any],
        valid(value),value["resourceId"] as? String==id,value["threadId"] as? String==threadId,
        value["name"] as? String==original["name"] as? String,
        let size=original["size"] as? NSNumber,String(cString:size.objCType) != "c",
        size.doubleValue==(value["size"] as? NSNumber)?.doubleValue else{return nil}
      return value
    } catch is CancellationError {throw CancellationError()} catch {try Task.checkCancellation();return nil}
  }
}

struct EasyWorkAgentClient: AgentClient {
  func projectGit(_ client: Client, projectId: String, target: AgentRuntimeTarget, refresh: Bool) async throws -> Data {
    guard target.agentId == "easywork", client.agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let channel = try await session(client)
    guard channel.target.desktopDeviceId == target.hostId, channel.target.workspaceId == target.workspaceId,
      channel.target.runtimeId == target.runtimeId, channel.target.account == target.account else { throw HostServiceError.wrongHost }
    let identity = try JSONSerialization.jsonObject(with: Data(projectGitTargetJSON(target).utf8))
    let data = try await channel.request("project/git/status", ["schemaVersion": 1, "target": identity, "projectId": projectId, "refresh": refresh])
    guard data.count <= 131072, let raw = String(data: data, encoding: .utf8) else { throw HostServiceError.invalidResponse }
    _ = try ProjectGitRules.shared.project(json: raw, targetJson: projectGitTargetJSON(target), projectId: projectId)
    return data
  }

  func projectGitFiles(_ client: Client, projectId: String, target: AgentRuntimeTarget, snapshotId: String, worktreeId: String, cursor: String?) async throws -> Data {
    guard target.agentId == "easywork", client.agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let channel = try await session(client)
    guard channel.target.desktopDeviceId == target.hostId, channel.target.workspaceId == target.workspaceId,
      channel.target.runtimeId == target.runtimeId, channel.target.account == target.account else { throw HostServiceError.wrongHost }
    let identity = try JSONSerialization.jsonObject(with: Data(projectGitTargetJSON(target).utf8))
    var params: [String: Any] = ["schemaVersion": 1, "target": identity, "projectId": projectId, "snapshotId": snapshotId, "worktreeId": worktreeId]
    if let cursor { params["cursor"] = cursor }
    let data = try await channel.request("project/git/files", params)
    guard data.count <= 131072, let raw = String(data: data, encoding: .utf8) else { throw HostServiceError.invalidResponse }
    _ = try ProjectGitRules.shared.files(json: raw, targetJson: projectGitTargetJSON(target), projectId: projectId, snapshotId: snapshotId, worktreeId: worktreeId)
    return data
  }

  func usage(_ client: Client, target: AgentRuntimeTarget) async throws -> Data {
    guard target.agentId == "easywork", client.agentConnection?.target == target else { throw HostServiceError.wrongHost }
    let channel = try await session(client)
    guard channel.target.desktopDeviceId == target.hostId, channel.target.workspaceId == target.workspaceId,
      channel.target.runtimeId == target.runtimeId, channel.target.account == target.account else { throw HostServiceError.wrongHost }
    let requestId = UUID().uuidString
    let identity = try JSONSerialization.jsonObject(with: Data(usageTargetJSON(target).utf8))
    let data = try await channel.request("usage/read", ["schemaVersion": 1, "target": identity, "requestId": requestId])
    guard data.count <= 262144, let raw = String(data: data, encoding: .utf8),
      let response = try JSONSerialization.jsonObject(with: data) as? [String: Any],
      response["requestId"] as? String == requestId else { throw HostServiceError.invalidResponse }
    _ = try UsageRules.shared.project(json: raw, targetJson: usageTargetJSON(target), nowMillis: Int64(Date().timeIntervalSince1970 * 1000))
    return data
  }

  func desktopSuggestionSource(_ client: Client, workspace: WorkspaceSnapshot) -> DesktopSuggestionSourceKind? {
    guard workspace.connectionMode == "easywork-runtime", workspace.readTarget?.agentId == "easywork",
      workspace.capabilities?.contains("desktop:suggestions:runtime-version:v1") == true else { return nil }
    return .runtime
  }
  func nextTurnSuggestion(_ client: Client, scope: DesktopSuggestionScope) async throws -> DesktopSuggestionResponse {
    guard scope.target.agentId == "easywork", client.agentConnection?.target == scope.target else { throw HostServiceError.wrongHost }
    let channel = try await session(client)
    guard channel.target.desktopDeviceId == scope.target.hostId,
      channel.target.workspaceId == scope.target.workspaceId,
      channel.target.runtimeId == scope.target.runtimeId,
      channel.target.account == scope.target.account else { throw HostServiceError.wrongHost }
    var value = try object(await channel.request("thread/suggestion/read", ["threadId": scope.threadId, "sourceTurnId": scope.sourceTurnId]))
    // The encrypted session has already verified the exact response-envelope target.
    value["target"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(scope.target))
    let data = try easyWorkJSON(value)
    guard let json = String(data: data, encoding: .utf8),
      CodexHttpContract.shared.validate(json: json, definition: "desktopSuggestionResponse") == nil else { throw HostServiceError.invalidResponse }
    let response = try JSONDecoder().decode(DesktopSuggestionResponse.self, from: data)
    guard response.matches(scope) else { throw HostServiceError.invalidResponse }
    return response
  }
  func voiceReadiness(_ client: Client) async throws -> VoiceTranscriptionReadiness {
    var value = try object(await session(client).request("voice/readiness", [:]))
    value["hostId"] = client.id
    return try JSONDecoder().decode(VoiceTranscriptionReadiness.self, from: easyWorkJSON(value))
  }
  func transcribeVoice(_ client: Client, recording: VoiceRecording, threadId: String?, draftId: String) async throws -> String {
    guard recording.data.count >= 44, recording.data.count <= 12 * 1024 * 1024,
      recording.durationMs > 0, recording.durationMs <= 300_000,
      SHA256.hash(data: recording.data).map({ String(format: "%02x", $0) }).joined() == recording.sha256 else {
      throw HostServiceError.message(L10n.tr("原录音清单或内容已变化"))
    }
    let manifest = VoiceTranscriptionRequest(hostId: client.id, threadId: threadId, draftId: draftId,
      operationId: recording.operationId, mediaType: "audio/wav", sizeBytes: recording.data.count,
      sha256: recording.sha256, durationMs: recording.durationMs)
    let channel = try await session(client)
    func decode(_ data: Data) throws -> VoiceTranscriptionReceipt {
      let value = try JSONDecoder().decode(VoiceTranscriptionReceipt.self, from: data)
      guard try value.matches(manifest) else { throw HostServiceError.message(L10n.tr("转写回执与原录音或目标不一致")) }
      return value
    }
    func status() async throws -> VoiceTranscriptionReceipt? {
      let value = try easyWorkObject(await channel.request("voice/status", ["operationId": manifest.operationId]))
      guard let receipt = value["receipt"] as? [String: Any] else { return nil }
      return try decode(easyWorkJSON(receipt))
    }
    try Task.checkCancellation()
    var receipt: VoiceTranscriptionReceipt
    if let existing = try await status() { receipt = existing }
    else {
      let original = try JSONSerialization.jsonObject(with: JSONEncoder().encode(manifest))
      receipt = try decode(await channel.request("voice/prepare", ["recording": original], id: manifest.operationId + ":prepare"))
    }
    if receipt.phase == "awaiting-audio" {
      try Task.checkCancellation()
      receipt = try decode(await channel.request("voice/upload", ["operationId": manifest.operationId, "audioBase64": recording.data.base64URL], id: manifest.operationId))
    }
    let deadline = Date().addingTimeInterval(100)
    while receipt.phase == "transcribing", Date() < deadline {
      try await Task.sleep(for: .milliseconds(700)); try Task.checkCancellation()
      guard let current = try await status() else { throw HostServiceError.message(L10n.tr("原转写结果尚未确认，录音已保留。")) }
      receipt = current
    }
    if receipt.phase == "completed", let text = receipt.text { return text }
    throw HostServiceError.message(receipt.error ?? L10n.tr("转写仍在处理中，录音已保留；点击重试查询原结果。"))
  }
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
    var visitedDirectoryRefs = Set<String>()
    while selected["displayPath"] as? String != requested {
      guard let reference = selected["directoryRef"] as? String, !reference.isEmpty,
        visitedDirectoryRefs.insert(reference).inserted else { throw HostServiceError.message(L10n.tr("请选择已存在且已授权的目录")) }
      let value = try object(await channel.request("filesystem/list-directories", ["directoryRef": reference]))
      let directories = value["directories"] as? [[String: Any]] ?? []
      guard let next = directories.first(where: { entry in
        guard let path = entry["displayPath"] as? String else { return false }
        return requested == path || requested.hasPrefix(path + "/")
      }) else { throw HostServiceError.message(L10n.tr("请选择已存在且已授权的目录")) }
      selected = next
    }
    guard let reference = selected["directoryRef"] as? String, !reference.isEmpty else { throw HostServiceError.message(L10n.tr("请选择已存在且已授权的目录")) }
    _ = try await channel.request("project/create", ["name": name, "directoryRef": reference, "clientRequestId": operationId], id: operationId)
    return try await workspace(client, projectCursor: nil, threadCursor: nil)
  }
  func connectionTitle(_ client: Client) async -> String? { try? await session(client).activeTransportTitle() }
  func close(_ client: Client) async { await EasyWorkSessions.shared.remove(client.id) }
  private func forkValue(_ value: [String: Any], client: Client, threadId: String) throws -> [String: Any] {
    guard let hostId = client.agentConnection?.target.hostId, value["hostId"] as? String == hostId,
      value["threadId"] as? String == threadId else { throw HostServiceError.wrongHost }
    var normalized = value; normalized["hostId"] = client.id
    return normalized
  }
  func forkOptions(_ client: Client, threadId: String) async throws -> ThreadForkOptions {
    let value = try object(await session(client).request("thread/fork-options", ["threadId": threadId]))
    return try JSONDecoder().decode(ThreadForkOptions.self, from: easyWorkJSON(forkValue(value, client: client, threadId: threadId)))
  }
  func forkThread(_ client: Client, request: ThreadForkRequest) async throws -> ThreadForkOperation {
    guard request.hostId == client.id, let hostId = client.agentConnection?.target.hostId else { throw HostServiceError.wrongHost }
    var wire = try object(JSONSerialization.jsonObject(with: JSONEncoder().encode(request)))
    wire["hostId"] = hostId
    let value = try object(await session(client).request("thread/fork", wire, id: request.clientOperationId))
    let result = try JSONDecoder().decode(ThreadForkOperation.self, from: easyWorkJSON(forkValue(value, client: client, threadId: request.threadId)))
    guard result.request == request else { throw HostServiceError.wrongHost }
    #if DEBUG
    if let target = client.agentConnection?.target {
      try await EasyWorkForkDeliveryFault.shared.afterVerifiedFork(request, result: result, target: target)
    }
    #endif
    return result
  }
  func forkStatus(_ client: Client, threadId: String, operationId: String) async throws -> ThreadForkOperation {
    let value = try object(await session(client).request("thread/fork-status", ["threadId": threadId, "operationId": operationId]))
    guard let operation = value["operation"] as? [String: Any] else { throw HostServiceError.rejected(404, L10n.tr("未查到原分支记录；结果仍未确认，不会重新提交。")) }
    let result = try JSONDecoder().decode(ThreadForkOperation.self, from: easyWorkJSON(forkValue(operation, client: client, threadId: threadId)))
    guard result.clientOperationId == operationId else { throw HostServiceError.wrongHost }
    return result
  }
  private func session(_ client: Client) async throws -> EasyWorkRemoteSession { try await EasyWorkSessions.shared.session(client) }
  private func object(_ value: Any) throws -> [String: Any] { if let data = value as? Data { return try easyWorkObject(data) }; guard let result = value as? [String: Any] else { throw HostServiceError.invalidResponse }; return result }
  func workspace(_ client: Client, projectCursor: String?, threadCursor: String?) async throws -> WorkspaceSnapshot {
    let channel = try await session(client)
    let context = try object(await channel.request("runtime/context", [:]))
    let confirmed = try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(context["target"] ?? [:]))
    guard confirmed == channel.target else { throw HostServiceError.wrongHost }
    let capabilities = context["capabilities"] as? [String] ?? []
    try await EasyWorkReadingCapabilities.shared.remember(channel, capabilities: capabilities)
    let projects = try object(await channel.request("project/list", [:]))["projects"] as? [[String: Any]] ?? []
    var params: [String: Any] = ["limit": 100]; if let threadCursor { params["cursor"] = threadCursor }
    if capabilities.contains("threads:manage") { params["status"] = "all" }
    let threads = try object(await channel.request("thread/list", params))
    let observed = try object(context["state"] ?? [:])
    let connectionState = client.agentConnection.map { AgentConnectionState(target: $0.target, phase: .connected, transportId: nil, processHealthy: observed["processHealthy"] as? Bool == true, runtimeConnected: observed["runtimeConnected"] as? Bool == true, historyReadable: observed["historyReadable"] as? Bool == true) }
    var summaries = (threads["threads"] as? [[String: Any]] ?? []).map { conversation(client, $0) }
    if client.readingScopeId != nil, capabilities.contains("threads:read-state:list") {
      let values = try await EasyWorkReading.readList(channel, client: client, threadIds: summaries.map(\.id))
      for (index, value) in values.enumerated() { summaries[index].readState = value.state; summaries[index].readAuthorityRevision = value.counter }
    } else if client.readingScopeId != nil, capabilities.contains("threads:read-state") {
      // Bound catalog fan-out; do not pull every thread's message history to discover unread replies.
      for start in stride(from: 0, to: summaries.count, by: 4) {
        let batch = Array(summaries[start..<min(start + 4, summaries.count)])
        let decorated = try await withThrowingTaskGroup(of: (Int, EasyWorkReading.Summary).self) { group in
          for (offset, thread) in batch.enumerated() {
            group.addTask { (offset, try await EasyWorkReading.read(channel, client: client, threadId: thread.id)) }
          }
          var result: [(Int, EasyWorkReading.Summary)] = []
          for try await value in group { result.append(value) }
          return result
        }
        for (offset, value) in decorated { summaries[start + offset].readState = value.state; summaries[start + offset].readAuthorityRevision = value.counter }
      }
    }
    return WorkspaceSnapshot(hostId: client.id, name: client.name, connectionMode: "easywork-runtime",
      projects: projects.map { Project(id: $0["id"] as? String ?? "", hostId: client.id, name: $0["name"] as? String ?? L10n.tr("项目"), roots: ($0["workspacePath"] as? String).map { [$0] } ?? []) },
      threads: summaries, nextProjectCursor: nil, nextThreadCursor: threads["nextCursor"] as? String,
      capabilities: capabilities, clientType: ClientType(id: "easywork", name: "EasyWork"), agentConnectionState: connectionState,
      readTarget: readingTarget(confirmed),
      pinnedThreadIds: threads["pinnedThreadIds"] as? [String])
  }
  private func readingTarget(_ target: EasyWorkTarget) -> AgentRuntimeTarget {
    AgentRuntimeTarget(agentId: "easywork", hostId: target.desktopDeviceId, workspaceId: target.workspaceId, runtimeId: target.runtimeId, account: target.account)
  }
  private func conversation(_ client: Client, _ value: [String: Any]) -> Conversation {
    let active = value["activeRun"] as? [String: Any]
    return Conversation(id: value["id"] as? String ?? "", hostId: client.id, projectId: value["projectId"] as? String,
      title: value["title"] as? String ?? value["firstUserMessageText"] as? String ?? L10n.tr("新对话"), state: runState(active),
      hasUnreadTurn: value["hasUnreadTurn"] as? Bool,
      archived: value["archived"] as? Bool ?? !(value["archivedAt"] == nil || value["archivedAt"] is NSNull),
      pendingAsyncQuestionCount: value["pendingAsyncQuestionCount"] as? Int,
      archiveUnavailableReason: value["archiveUnavailableReason"] as? String,
      lastTurnStatus: value["lastTurnStatus"] as? String ?? (value["latestRun"] as? [String: Any])?["status"] as? String)
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
  func threadCreationStatus(_ client: Client, projectId: String?, operationId: String) async throws -> Conversation? {
    let channel = try await session(client)
    let data = try await channel.request("command/status", ["commandId": operationId])
    let raw = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    if raw is NSNull { return nil }
    let record = try object(raw)
    var params: [String: Any] = ["clientRequestId": operationId]
    if let projectId { params["projectId"] = projectId }
    let result = try EasyWorkRemoteSession.verifiedWriteResult(record, target: channel.target,
      mobileDeviceId: await channel.mobileDeviceId, operationId: operationId, method: "thread/create", params: params)
    let value = try object(JSONSerialization.jsonObject(with: result))
    let thread = conversation(client, try object(value["thread"] ?? [:]))
    guard !thread.id.isEmpty, thread.hostId == client.id, thread.projectId == projectId else {
      throw HostServiceError.invalidResponse
    }
    return thread
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
          messages.append(ChatMessage(id: "\(id):\(index)", role: "assistant", text: data["markdown"] as? String ?? "", kind: "plan", status: data["status"] as? String ?? status, planArtifact:EasyWorkPlanArtifactProjection.decode(data)))
        } else if type == "agent-debug" {
          let diagnostics = data["contextCompressionSampling"] as? [[String: Any]] ?? []
          for (offset, item) in diagnostics.enumerated() {
            let projection = item["projectionStatus"] as? String ?? "none"
            let confirmed = ["created", "regenerated", "repaired"].contains(projection)
            guard confirmed || item["action"] as? String == "compression_started" else { continue }
            let compressionStatus = confirmed ? "completed" : data["status"] as? String == "running" ? "inProgress" : "interrupted"
            messages.append(ChatMessage(id: "\(id):\(index):compression:\(offset)", role: "tool", text: confirmed ? L10n.tr("上下文已压缩") : L10n.tr("上下文压缩"), kind: "contextCompaction", status: compressionStatus, detail: String(decoding: try easyWorkJSON(item), as: UTF8.self)))
          }
          if let error = data["errorMessage"] as? String, data["status"] as? String == "failed" { messages.append(ChatMessage(id: "\(id):\(index)", role: role, text: error, kind: "turnFailure", status: "failed")) }
        } else if type == "token-usage" { continue
        } else if ["context-compression", "context-compaction", "contextCompaction"].contains(type) {
          messages.append(ChatMessage(id: "\(id):\(index)", role: "tool", text: data["summary"] as? String ?? L10n.tr("上下文压缩"), kind: "contextCompaction", status: data["status"] as? String ?? status, detail: data["detail"] as? String))
        } else if type == "attachment" {
          let verified = try await EasyWorkHistoricalAttachment.readOrResolve(threadId: threadId, original: data) { method,params in
            try object(await channel.request(method, params))
          }
          let file = verified.flatMap { resource(client, threadId: threadId, data: $0) }
          let name = data["name"] as? String ?? L10n.tr("文件")
          messages.append(ChatMessage(id: "\(id):\(index)", role: role,
            text: file == nil ? L10n.tr("附件暂不可下载") : "", files: file == nil ? [name] : [], status: status,
            attachments: file.map { [$0] }, blocks: [.resource(id: data["resourceId"] as? String ?? "\(id):\(index)", name: name, mediaType: data["contentType"] as? String ?? "application/octet-stream")]))
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
    if cursor == nil {
      let activities = await channel.subagentActivities(try easyWorkJSON(snapshot), threadId: threadId)
      for item in activities {
        messages.append(ChatMessage(id: item.id, role: "tool", text: item.title, kind: "subAgent",
          status: item.status, detail: item.detail, runId: item.rootRunId))
      }
    }
    // Resolve only references supplied by the authoritative conversation. The host
    // rechecks provenance and workspace scope before exposing a logical resource.
    if cursor == nil, let pattern = try? NSRegularExpression(pattern: #"\]\(([^\s\)]+)\)"#) {
      // Host resolution supplies the current version. Memoization is limited to
      // this one history read, so an unchanged message cannot pin an old digest.
      var resolvedReferences: [String: Data] = [:]
      // A new running turn must not remove resources from earlier completed
      // replies. Resolve their current versions; skip the active partial reply.
      for index in messages.indices where messages[index].role == "assistant"
        && (active == nil || ["complete", "completed"].contains(messages[index].status ?? "")) {
        let text = messages[index].text
        var files: [RemoteAttachment] = []
        var replacements: [(NSRange, String)] = []
        for match in pattern.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
          guard let range = Range(match.range(at: 1), in: text) else { continue }
          let reference = String(text[range]).removingPercentEncoding ?? String(text[range])
          if reference.hasPrefix("http:") || reference.hasPrefix("https:") { continue }
          do {
            let value: Data
            if let cached = resolvedReferences[reference] { value = cached }
            else { value = try await channel.request("resource/resolve", ["threadId": threadId, "reference": reference]); resolvedReferences[reference] = value }
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
          // Resolving a file changes its hidden link destination, not the visible source reply.
          // Bind both digests so a later, unrelated text edit cannot reuse this projection.
          messages[index].readingProjection = ReadContentProjection(
            sourceVersion: ReadPosition.contentVersion(messageId: messages[index].id, text: text, status: messages[index].status ?? "completed"),
            displayVersion: ReadPosition.contentVersion(messageId: messages[index].id, text: rewritten, status: messages[index].status ?? "completed"))
        }
      }
    }
    let plan = snapshot["currentPlan"] as? [String: Any]
    let steps = (plan?["steps"] as? [[String: Any]] ?? []).map { PlanStep(step: $0["title"] as? String ?? "", status: $0["status"] as? String == "in_progress" ? "inProgress" : $0["status"] as? String ?? "pending") }
    var proposed: ProposedPlan?
    if let plan, let id = plan["planId"] as? String, let version = plan["version"] as? Int, ["ready", "saved"].contains(plan["status"] as? String ?? "") {
      proposed = ProposedPlan(id: "easywork-plan." + (try easyWorkJSON(["id": id, "version": version])).base64URL, turnId: plan["createdRunId"] as? String ?? "", text: plan["markdown"] as? String ?? "")
    }
    // A legacy completed run without a recorded setting is unknown, not today's thread default.
    var preferences = latest == nil ? thread["generationPreferences"] as? [String: Any] ?? [:] : [:]
    if let effective = latest?["settings"] as? [String: Any] {
      if effective["version"] != nil {
        guard try EasyWorkReading.integer(effective["version"]) == 1,
          Set(effective.keys) == Set(["version", "modelRef", "reasoning", "speed", "permissionMode", "agentRunMode"]) else { throw HostServiceError.invalidResponse }
        preferences = effective
      } else { preferences = effective }
    }
    let model = preferences["modelRef"] as? [String: Any]
    let modelId = model.flatMap { value -> String? in guard let provider = value["providerId"] as? String, let id = value["modelId"] as? String else { return nil }; return provider + "/" + id }
    let settings = GenerationOptions(model: modelId, effort: (preferences["reasoning"] as? [String: Any])?["effort"] as? String, permissionMode: preferences["permissionMode"] as? String, speed: preferences["speed"] as? String)
    var receipts = try JSONDecoder().decode([DeliveryReceipt].self, from: easyWorkJSON((snapshot["queueReceipts"] as? [[String: Any]] ?? []).map { value in var receipt = value; receipt["hostId"] = client.id; return receipt }))
    if let latest = snapshot["latestRun"] as? [String: Any], let status = latest["status"] as? String, ["failed", "cancelled", "interrupted"].contains(status), active == nil { receipts.append(DeliveryReceipt(clientMessageId: latest["id"] as? String ?? "", hostId: client.id, threadId: threadId, state: status, turnId: latest["id"] as? String, error: latest["error"] as? String)) }
    let controlTurn = ["paused", "pausing", "resuming"].contains(control?["phase"] as? String ?? "") ? control?["runId"] as? String : nil
    let runtime = cursor == nil ? ThreadRuntime(state: state, turnId: active?["runId"] as? String ?? active?["id"] as? String ?? controlTurn, plan: steps, error: active == nil ? latest?["error"] as? String : nil, approvals: approvals, receipts: receipts, canControl: true, collaborationMode: (active?["agentRunMode"] as? String ?? preferences["agentRunMode"] as? String) == "plan" ? "plan" : "default", proposedPlan: proposed, generationSettings: settings, suggestionSourceTurnId: state == "idle" ? snapshot["suggestionSourceTurnId"] as? String : nil) : nil
    var summary = conversation(client, thread); summary.state = state
    if cursor == nil, client.readingScopeId != nil, try await EasyWorkReadingCapabilities.shared.supports(channel) {
      let reading = try await EasyWorkReading.read(channel, client: client, threadId: threadId)
      summary.readState = reading.state; summary.readAuthorityRevision = reading.counter
    }
    return HistorySnapshot(readTarget: readingTarget(channel.target), hostId: client.id, threadId: threadId, thread: summary, messages: messages, nextCursor: value["hasEarlier"] as? Bool == true ? value["before"] as? String : nil, runtime: runtime, epoch: await channel.epoch, cursor: await channel.eventSequence, historyWindow: HistoryWindow(version: 1, kind: cursor == nil ? "latest" : "earlier"), historyRevision: revision)
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
    if let speed = reply.speed {
      guard ["standard", "fast", "ultrafast"].contains(speed) else { throw HostServiceError.invalidResponse }
      settings["speed"] = speed
    }
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
    let channel = try await session(client)
    let bytes = try await channel.request("command/status", ["commandId": id])
    guard !bytes.elementsEqual(Data("null".utf8)) else { throw HostServiceError.message(L10n.tr("Host 尚未找到此操作回执，请先核对桌面历史。原消息和操作 ID 已保留。")) }
    let value = try object(bytes)
    for key in ["operationId", "clientMessageId"] {
      if let observed = value[key], !(observed is NSNull), observed as? String != id { throw HostServiceError.wrongHost }
    }
    if let raw = value["response"], !(raw is NSNull), !(raw is [String: Any]) { throw HostServiceError.invalidResponse }
    let response = value["response"] as? [String: Any]
    if let raw = response?["result"], !(raw is NSNull), !(raw is [String: Any]) { throw HostServiceError.invalidResponse }
    let result = response?["result"] as? [String: Any]
    if let result {
      for key in ["operationId", "clientMessageId"] {
        if let observed = result[key], !(observed is NSNull), observed as? String != id { throw HostServiceError.wrongHost }
      }
      if let recorded = value["threadId"] as? String, let reported = result["threadId"] as? String, recorded != reported { throw HostServiceError.wrongHost }
    }
    func originalQueueReceipt(_ thread: String) async throws -> DeliveryReceipt? {
      let queued = try await channel.request("queue/status", ["threadId": thread, "messageId": id])
      if queued.elementsEqual(Data("null".utf8)) { return nil }
      let value = try object(queued)
      guard value["clientMessageId"] as? String == id,
        value["threadId"] as? String == thread else { throw HostServiceError.wrongHost }
      return try EasyWorkReceiptNormalization.decode(value, clientMessageId: id,
        hostId: client.id, threadId: thread, defaultState: "uncertain")
    }
    if result?["state"] as? String == "queued" || result?["queueSource"] as? String != nil {
      guard let thread = result?["threadId"] as? String else { throw HostServiceError.invalidResponse }
      guard let receipt = try await originalQueueReceipt(thread) else { throw HostServiceError.invalidResponse }
      return receipt
    }
    // The queue may have durably accepted this input before its command acknowledgement was saved.
    // Query the original admission; its absence never grants permission to resend the input.
    if result == nil, value["method"] as? String == "turn/start",
      ["submitting", "uncertain"].contains(value["state"] as? String ?? ""),
      let thread = value["threadId"] as? String, !thread.isEmpty {
      do {
        if let receipt = try await originalQueueReceipt(thread) { return receipt }
      } catch let error as EasyWorkRemoteRejection
        where ["method_unavailable", "queue_unavailable"].contains(error.code ?? "") {
        // Older hosts can still report the original unknown command without a queue query port.
      }
    }
    return try EasyWorkReceiptNormalization.decode(result ?? [:], clientMessageId: id,
      hostId: client.id, threadId: result?["threadId"] as? String ?? value["threadId"] as? String ?? "",
      defaultState: result != nil && value["state"] as? String == "completed" ? "submitted" : "uncertain",
      fallbackError: (response?["error"] as? [String: Any])?["message"] as? String)
  }
  func events(_ client: Client, epoch: String, after: Int, threadId: String) async throws -> EventBatch {
    let channel = try await session(client)
    let eventEpoch = channel.target.runtimeId + ":" + (await channel.epoch)
    let cursor = try await channel.pollEvents(threadId: threadId, after: epoch == eventEpoch ? after : 0)
    let receivedEpoch = channel.target.runtimeId + ":" + (await channel.epoch)
    guard OperationPolicies.shared.eventNeedsHistory(previousEpoch: epoch, epoch: receivedEpoch,
      previousCursor: Int64(after), cursor: Int64(cursor)) else {
      return EventBatch(hostId: client.id, epoch: receivedEpoch, cursor: cursor, reset: false, events: [], ready: true)
    }
    // Coalesced deltas or unavailable older events are repaired by canonical history.
    let snapshot = try await history(client, threadId: threadId, cursor: nil)
    return EventBatch(hostId: client.id, epoch: receivedEpoch, cursor: cursor, reset: epoch != receivedEpoch, events: [HostEvent(threadId: threadId, snapshot: snapshot)], ready: true)
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
  private func independentFile(_ client: Client, _ value: [String: Any], directory: String) throws -> ComputerFileReceipt {
    guard value["purpose"] as? String == "transfer", value["direction"] as? String == "mobile-to-desktop",
      value["threadId"] == nil, let id = value["transferId"] as? String, UUID(uuidString: id) != nil,
      let name = value["name"] as? String, let mediaType = value["contentType"] as? String,
      let size = value["size"] as? Int, size > 0, size <= 20 * 1024 * 1024,
      let expected = value["expectedSha256"] as? String, expected.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
      let status = value["status"] as? String, ["waiting", "transferring", "completed"].contains(status),
      let createdAt = value["createdAt"] as? String else { throw HostServiceError.invalidResponse }
    let completed = status == "completed"
    guard !completed || value["sha256"] as? String == expected else { throw HostServiceError.invalidResponse }
    let path = completed ? value["absolutePath"] as? String : (directory as NSString).appendingPathComponent(name)
    guard let path, !path.isEmpty else { throw HostServiceError.invalidResponse }
    return ComputerFileReceipt(hostId: client.id, id: id, name: name, mediaType: mediaType, sizeBytes: size,
      sha256: expected, phase: completed ? "completed" : "prepared", destinationPath: path, createdAt: createdAt)
  }
  func fileTransfers(_ client: Client) async throws -> ComputerFileCatalog {
    let value = try object(await session(client).request("resource/transfer-list", [:]))
    guard let directory = value["destinationDirectory"] as? String, !directory.isEmpty,
      let limit = value["maxFileBytes"] as? Int, limit > 0, limit <= 20 * 1024 * 1024,
      let records = value["transfers"] as? [[String: Any]] else { throw HostServiceError.message(L10n.tr("请更新电脑连接程序以使用文件互传。")) }
    let independent = records.filter { $0["purpose"] as? String == "transfer" && $0["direction"] as? String == "mobile-to-desktop" && $0["threadId"] == nil && ["waiting", "transferring", "completed"].contains($0["status"] as? String ?? "") }
    return ComputerFileCatalog(hostId: client.id, destinationDirectory: directory, maxFileBytes: limit,
      files: try independent.map { try independentFile(client, $0, directory: directory) })
  }
  func fileTransfer(_ client: Client, id: String) async throws -> ComputerFileReceipt {
    guard UUID(uuidString: id) != nil else { throw HostServiceError.invalidResponse }
    let value = try object(await session(client).request("resource/upload-status", ["transferId": id]))
    let record = try object(value["transfer"] ?? [:])
    guard record["transferId"] as? String == id else { throw HostServiceError.invalidResponse }
    if ["failed", "cancelled"].contains(record["status"] as? String ?? "") {
      throw HostServiceError.message(L10n.tr("电脑未完成原文件接收，原记录和手机文件已保留；此查询不会重新上传。"))
    }
    let catalog = try await fileTransfers(client)
    return try independentFile(client, record, directory: catalog.destinationDirectory)
  }
  func prepareFileTransfer(_ client: Client, file: ComputerFileManifest) async throws -> ComputerFileReceipt {
    guard file.hostId == client.id, UUID(uuidString: file.id) != nil, file.sizeBytes > 0,
      file.sizeBytes <= 20 * 1024 * 1024, !file.name.isEmpty, file.name == file.name.trimmingCharacters(in: .whitespacesAndNewlines),
      file.name.count <= 255, !file.name.contains("/"), !file.name.contains("\\"), !file.name.contains("\0"),
      file.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw HostServiceError.invalidResponse }
    _ = try await session(client).request("resource/upload", ["transferId": file.id, "name": file.name, "mediaType": file.mediaType,
      "size": file.sizeBytes, "expectedSha256": file.sha256, "purpose": "transfer"], id: "file-prepare-" + file.id)
    let receipt = try await fileTransfer(client, id: file.id); try receipt.validate(file); return receipt
  }
  func uploadFileTransfer(_ client: Client, file: ComputerFileManifest, data: Data) async throws -> ComputerFileReceipt {
    guard file.hostId == client.id, data.count == file.sizeBytes, HostService.hash(data) == file.sha256 else { throw HostServiceError.invalidResponse }
    let channel = try await session(client)
    let current = try await fileTransfer(client, id: file.id); try current.validate(file)
    if current.phase == "completed" { return current }
    let value = try object(await channel.request("resource/upload-status", ["transferId": file.id]))
    let record = try object(value["transfer"] ?? [:])
    guard let offsetValue = record["transferredBytes"] as? Int, offsetValue >= 0, offsetValue <= data.count else { throw HostServiceError.invalidResponse }
    var offset = offsetValue
    while offset < data.count {
      try Task.checkCancellation()
      let end = min(offset + 192 * 1024, data.count)
      let response = try object(await channel.request("resource/upload-chunk", ["transferId": file.id, "offset": offset,
        "dataBase64": data.subdata(in: offset..<end).base64URL], id: "file-chunk-\(file.id)-\(offset)"))
      guard response["nextOffset"] as? Int == end else { throw HostServiceError.invalidResponse }; offset = end
    }
    _ = try await channel.request("resource/upload-complete", ["transferId": file.id], id: "file-complete-" + file.id)
    let receipt = try await fileTransfer(client, id: file.id); try receipt.validate(file)
    guard receipt.phase == "completed" else { throw HostServiceError.invalidResponse }; return receipt
  }
  func upload(_ client: Client, threadId: String, file: LocalAttachment, data: Data) async throws -> RemoteAttachment {
    let channel = try await session(client)
    let mediaType = UTType(filenameExtension: (file.name as NSString).pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    let mobileDeviceId = await channel.mobileDeviceId
    let completed = try await EasyWorkAttachmentUpload.perform(file: file, data: data, threadId: threadId,
      mobileDeviceId: mobileDeviceId, mediaType: mediaType) { method, params, id in
        if let id { return try object(await channel.request(method, params, id: id)) }
        return try object(await channel.request(method, params))
      }
    guard let attachment = resource(client, threadId: threadId, data: completed) else { throw HostServiceError.invalidResponse }
    return attachment
  }
  func resolveMessageFile(_ client: Client, threadId: String, messageId: String, reference: String) async throws -> RemoteAttachment {
    let channel = try await session(client)
    let context = try object(await channel.request("runtime/context", [:]))
    let confirmed = try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(context["target"] ?? [:]))
    guard confirmed == channel.target else { throw HostServiceError.wrongHost }
    guard (context["capabilities"] as? [String] ?? []).contains("attachments:resolve-message-link:v1") else {
      throw HostServiceError.message(L10n.tr("当前连接程序不支持原消息文件解析，请更新电脑连接程序后重试。"))
    }
    let data: [String: Any]
    do {
      data = try await EasyWorkMessageFileResolution.attachment(threadId: threadId,
        messageId: messageId, reference: reference) { method, params in
        try object(await channel.request(method, params))
      }
    } catch let error as EasyWorkRemoteRejection where error.code == "message_file_link_mismatch" {
      throw HostServiceError.message(L10n.tr("此文件尚未由电脑提供，请刷新对话后重试。"))
    }
    guard let file = resource(client, threadId: threadId, data: data) else { throw HostServiceError.invalidResponse }
    return file
  }
  private func resource(_ client: Client, threadId: String, data: [String: Any]) -> RemoteAttachment? {
    guard let id = data["resourceId"] as? String, let hash = data["sha256"] as? String else { return nil }
    return RemoteAttachment(id: id, hostId: client.id, threadId: threadId, name: data["name"] as? String ?? L10n.tr("文件"), mediaType: data["contentType"] as? String ?? "application/octet-stream", sizeBytes: data["size"] as? Int ?? 0, sha256: hash)
  }
  private func downloadDestination(_ client: Client,file: RemoteAttachment) throws -> URL {
    guard file.hostId == client.id, let target = client.agentConnection?.target else { throw HostServiceError.wrongHost }
    guard target.agentId=="easywork",file.name==(file.name as NSString).lastPathComponent,!file.name.isEmpty,
      !file.id.isEmpty,!file.id.contains("/"),![".",".."].contains(file.id),file.sizeBytes>=0,file.sizeBytes<=20*1024*1024 else { throw HostServiceError.invalidResponse }
    let scope = HostService.hash(try easyWorkJSON([target.agentId, target.account?.authority ?? "", target.account?.subject ?? "", target.hostId, target.workspaceId, target.runtimeId, file.threadId]))
    let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].resolvingSymlinksInPath().appendingPathComponent("Downloads/agent-" + scope + "/" + file.id, isDirectory: true)
    let destination=root.appendingPathComponent(file.name)
    guard destination.standardizedFileURL.path==destination.resolvingSymlinksInPath().standardizedFileURL.path else { throw HostServiceError.invalidResponse }
    return destination
  }
  func cachedDownload(_ client: Client,file:RemoteAttachment) async throws -> URL? {
    let destination=try downloadDestination(client,file:file)
    return await Task.detached(priority:.utility) {
      guard destination.standardizedFileURL.path==destination.resolvingSymlinksInPath().standardizedFileURL.path else { return nil as URL? }
      let descriptor=Darwin.open(destination.path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW)
      guard descriptor>=0 else { return nil }
      let input=FileHandle(fileDescriptor:descriptor,closeOnDealloc:true);defer { try? input.close() }
      var info=stat()
      guard fstat(descriptor,&info)==0,info.st_mode & S_IFMT == S_IFREG,info.st_size==file.sizeBytes else { return nil }
      var data=Data()
      do {
        while data.count<=file.sizeBytes {
          guard let chunk=try input.read(upToCount:min(65_536,file.sizeBytes+1-data.count)),!chunk.isEmpty else { break }
          data.append(chunk)
        }
      } catch { return nil }
      return data.count==file.sizeBytes && HostService.hash(data)==file.sha256 ? destination : nil
    }.value
  }
  func download(_ client: Client, file: RemoteAttachment) async throws -> URL {
    let destination=try downloadDestination(client,file:file),root=destination.deletingLastPathComponent()
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    if let cached=try await cachedDownload(client,file:file) { return cached }
    let channel = try await session(client)
    let context = try object(await channel.request("runtime/context", [:]))
    let confirmedTarget = try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(context["target"] ?? [:]))
    guard confirmedTarget == channel.target else { throw HostServiceError.wrongHost }
    var params: [String: Any] = ["resourceId": file.id]
    if (context["capabilities"] as? [String] ?? []).contains("attachments:download:thread-bound") { params["threadId"] = file.threadId }
    let value = try object(await channel.request("resource/read", params))
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
