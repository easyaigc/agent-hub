import CryptoKit
import Foundation
import MobileCore
import Security
import UniformTypeIdentifiers

// Opt-in metadata for the independent physical acceptance app. Keep capture
// traffic out of the timed interval; never record a URL, header, body or token.
enum NativeReadAcceptanceTiming {
  #if DEBUG
  private final class State: @unchecked Sendable {
    let lock = NSLock()
    var count = 0
  }
  private static let state = State()
  private static let configuration: (host: String, file: URL)? = {
    let env = ProcessInfo.processInfo.environment
    guard Bundle.main.bundleIdentifier == "com.easyai.agenthub.um06local",
      let host = env["AGENT_HUB_READ_ACCEPTANCE_HOST"], UUID(uuidString: host) != nil,
      let run = env["AGENT_HUB_READ_ACCEPTANCE_RUN"], UUID(uuidString: run) != nil,
      let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
    return (host, directory.appendingPathComponent("native-read-" + run + ".jsonl"))
  }()
  #endif
  static func discovery(_ endpoint: String, event: String, startedAt: Double, error: Error? = nil) {
    #if DEBUG
    guard let config = configuration,
      let encoded = ProcessInfo.processInfo.environment["AGENT_HUB_READ_ACCEPTANCE_ENDPOINTS"],
      let data = encoded.data(using: .utf8),
      let endpoints = try? JSONDecoder().decode([String].self, from: data),
      endpoints.contains(endpoint) else { return }
    let suffix = error.map { value in let native = value as NSError; return "-\(native.domain)-\(native.code)" } ?? ""
    record("discovery-" + event + suffix, hostId: config.host, startedAt: startedAt)
    #endif
  }
  static func record(_ event: String, hostId: String, threadId: String? = nil, startedAt: Double? = nil) {
    #if DEBUG
    guard let config = configuration, config.host == hostId else { return }
    state.lock.lock(); defer { state.lock.unlock() }
    guard state.count < 256 else { return }
    let now = ProcessInfo.processInfo.systemUptime
    var row: [String: Any] = ["event": event, "uptime": now, "utc": Date().timeIntervalSince1970]
    if let threadId { row["threadId"] = threadId }
    if let startedAt { row["startedAt"] = startedAt; row["elapsedMS"] = (now - startedAt) * 1000 }
    guard var data = try? JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]) else { return }
    data.append(10)
    do {
      if state.count == 0 { try data.write(to: config.file, options: .atomic) }
      else {
        let file = try FileHandle(forWritingTo: config.file); defer { try? file.close() }
        try file.seekToEnd(); try file.write(contentsOf: data)
      }
      state.count += 1
    } catch { /* Acceptance instrumentation never changes a product outcome. */ }
    #endif
  }
}

enum HostServiceError: LocalizedError {
  case invalidAddress, invalidResponse, unavailable, wrongHost, hostKeyRequired
  case message(String)
  case rejected(Int, String)
  var errorDescription: String? {
    switch self {
    case .invalidAddress: return L10n.tr("使用 HTTPS 电脑地址；本机模拟器可用 http://127.0.0.1:8788。")
    case .invalidResponse: return L10n.tr("连接程序返回了不兼容的数据。")
    case .unavailable: return L10n.tr("无法连接电脑，请检查地址和连接程序。")
    case .wrongHost: return L10n.tr("电脑身份不一致，已停止操作。请重新添加客户端。")
    case .hostKeyRequired: return L10n.tr("请输入这台电脑的 Host Key")
    case .message(let text): return text
    case .rejected(_, let text): return text
    }
  }
}

// Tokens never enter SavedSession, conversation history, or logs.
enum CredentialVault {
  // A temporarily inaccessible Keychain item is not a missing credential.
  static func existingToken(_ id: String) throws -> String? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "com.codexmanage.host", kSecAttrAccount as String: id,
      kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var value: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &value)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess, let data = value as? Data,
      let token = String(data: data, encoding: .utf8) else {
      throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }
    return token
  }
  static func token(_ id: String) -> String {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "com.codexmanage.host", kSecAttrAccount as String: id,
      kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var value: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &value) == errSecSuccess,
      let data = value as? Data
    else { return "" }
    return String(data: data, encoding: .utf8) ?? ""
  }
  static func set(_ token: String, id: String) throws {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "com.codexmanage.host", kSecAttrAccount as String: id,
    ]
    let status: OSStatus
    if token.isEmpty {
      status = SecItemDelete(query as CFDictionary)
    } else {
      let attributes: [String: Any] = [
        kSecValueData as String: Data(token.utf8),
        kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
      ]
      let updated = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
      status = updated == errSecItemNotFound
        ? SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
        : updated
    }
    guard status == errSecSuccess || (token.isEmpty && status == errSecItemNotFound) else {
      throw HostServiceError.message(L10n.tr("无法安全保存客户端密钥"))
    }
  }
}

struct HostService {
  static func baseURL(_ address: String) throws -> URL {
    guard let url = URL(string: address.trimmingCharacters(in: .whitespacesAndNewlines)),
      url.scheme == "https"
        || (url.scheme == "http"
          && ["127.0.0.1", "localhost", "::1"].contains(url.host ?? "")),
      url.host != nil, url.user == nil, url.password == nil, url.query == nil,
      url.fragment == nil, url.path.isEmpty || url.path == "/"
    else {
      throw HostServiceError.invalidAddress
    }
    return url
  }
  static func url(_ client: Client, _ path: String, _ query: [String: String] = [:]) throws -> URL {
    guard let endpoint = client.endpoint else { throw HostServiceError.invalidAddress }
    var components = URLComponents(
      url: try baseURL(endpoint).appendingPathComponent(path), resolvingAgainstBaseURL: false)!
    components.queryItems = (["hostId": client.id].merging(query) { _, rhs in rhs }).map {
      URLQueryItem(name: $0.key, value: $0.value)
    }
    return components.url!
  }
  static func request(_ url: URL, token: String = "", method: String = "GET", body: Data? = nil)
    -> URLRequest
  {
    var request = URLRequest(url: url)
    request.timeoutInterval = 35
    request.cachePolicy = .reloadIgnoringLocalCacheData
    request.httpMethod = method
    request.httpBody = body
    request.setValue("mcp-v1", forHTTPHeaderField: "X-Agent-Hub-Approvals")
    if !token.isEmpty {
      request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    }
    if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
    return request
  }
  static func bytes(_ request: URLRequest) async throws -> Data {
    let (data, response) = try await CodexHostRoutes.shared.data(for: request)
    guard let http = response as? HTTPURLResponse else {
      throw HostServiceError.invalidResponse
    }
    guard http.statusCode == 200 else {
      let error = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
      var detail = error?["error"] as? String ?? L10n.tr("电脑请求失败（\(http.statusCode)）")
      if request.url?.path.hasPrefix("/v1/attachments") == true {
        detail += "\nHTTP \(http.statusCode)"
        if let code = error?["code"] as? String { detail += " · " + code }
        if let id = error?["requestId"] as? String { detail += "\n" + L10n.tr("诊断编号") + "：" + id }
      }
      throw HostServiceError.rejected(http.statusCode, detail)
    }
    return data
  }
  static func load<T: Decodable>(_ request: URLRequest) async throws -> T {
    let data = try await bytes(request)
    let contract: String?
    if T.self == WorkspaceSnapshot.self { contract = "workspace" }
    else if T.self == HistorySnapshot.self { contract = "history" }
    else if T.self == EventBatch.self { contract = "events" }
    else if T.self == ReplyDeliveryInspection.self { contract = "replyInspection" }
    else if T.self == ThreadForkOptions.self { contract = "threadForkOptions" }
    else if T.self == ThreadForkOperation.self { contract = "threadForkOperation" }
    else if T.self == VoiceTranscriptionReadiness.self { contract = "voiceReadiness" }
    else if T.self == VoiceTranscriptionReceipt.self { contract = "voiceReceipt" }
    else { contract = nil }
    if let contract {
      guard let json = String(data: data, encoding: .utf8),
        CodexHttpContract.shared.validate(json: json, definition: contract) == nil else { throw HostServiceError.invalidResponse }
    }
    guard let result = try? JSONDecoder().decode(T.self, from: data) else {
      throw HostServiceError.invalidResponse
    }
    return result
  }
  static func nextTurnSuggestion(_ client: Client, scope: DesktopSuggestionScope) async throws -> DesktopSuggestionResponse {
    let machine = AgentRuntimeTarget(agentId: scope.target.agentId, hostId: scope.target.hostId,
      workspaceId: scope.target.workspaceId, runtimeId: scope.target.runtimeId)
    guard client.id == scope.target.hostId, scope.target.agentId == "codex",
      !scope.threadId.isEmpty, !scope.sourceTurnId.isEmpty,
      let target = String(data: try JSONEncoder().encode(machine), encoding: .utf8) else {
      throw HostServiceError.wrongHost
    }
    var read = request(try url(client, "v1/threads/next-turn-suggestion", [
      "target": target, "threadId": scope.threadId, "sourceTurnId": scope.sourceTurnId,
    ]), token: CredentialVault.token(client.id))
    read.timeoutInterval = 8
    return try await load(read)
  }
  static func workspace(
    _ address: String, token: String = "", projectCursor: String? = nil,
    threadCursor: String? = nil, readerId: String? = nil
  ) async throws -> WorkspaceSnapshot {
    var components = URLComponents(
      url: try baseURL(address).appendingPathComponent("v1/workspace"),
      resolvingAgainstBaseURL: false)!
    components.queryItems = [
      projectCursor.map { URLQueryItem(name: "projectCursor", value: $0) },
      threadCursor.map { URLQueryItem(name: "threadCursor", value: $0) },
      readerId.map { URLQueryItem(name: "readerId", value: $0) },
    ].compactMap { $0 }
    return try await load(request(components.url!, token: token))
  }
  static func history(_ client: Client, threadId: String, cursor: String? = nil) async throws
    -> HistorySnapshot
  {
    let startedAt = ProcessInfo.processInfo.systemUptime
    var query = ["threadId": threadId, "window": "latest-v1"]
    if let readerId = client.readingScopeId { query["readerId"] = readerId }
    if let cursor { query["cursor"] = cursor }
    let result: HistorySnapshot = try await load(
      request(try url(client, "v1/history", query), token: CredentialVault.token(client.id)))
    guard result.hostId == client.id, result.threadId == threadId else {
      throw HostServiceError.wrongHost
    }
    NativeReadAcceptanceTiming.record("history-response", hostId: client.id, threadId: threadId, startedAt: startedAt)
    return result
  }
  static func post<T: Decodable>(_ client: Client, _ path: String, _ payload: [String: Any], query: [String: String] = [:])
    async throws -> T
  {
    let data = try JSONSerialization.data(
      withJSONObject: ["hostId": client.id].merging(payload) { _, rhs in rhs })
    return try await load(
      request(
        try url(client, path, query), token: CredentialVault.token(client.id), method: "POST",
        body: data))
  }
  static func send(_ client: Client, _ reply: PendingReply, target: AgentRuntimeTarget? = nil) async throws -> DeliveryReceipt {
    guard
      var wire = try JSONSerialization.jsonObject(with: JSONEncoder().encode(reply))
        as? [String: Any]
    else { throw HostServiceError.invalidResponse }
    wire.removeValue(forKey: "sourceAttachments")
    wire.removeValue(forKey: "knownRejected")
    if let target { wire["target"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(target)) }
    let data = try JSONSerialization.data(withJSONObject: wire)
    return try await load(
      request(
        try url(client, "v1/replies"), token: CredentialVault.token(client.id),
        method: "POST", body: data))
  }
  static func replyDeliveryStatus(_ client: Client, reply: PendingReply, target: AgentRuntimeTarget) async throws -> ReplyDeliveryInspection {
    guard reply.hostId == client.id, target.hostId == client.id else { throw HostServiceError.wrongHost }
    let targetJSON = try JSONEncoder().encode(target)
    guard let captured = String(data: targetJSON, encoding: .utf8) else { throw HostServiceError.invalidResponse }
    return try await load(request(try url(client, "v1/replies/status", ["threadId": reply.threadId, "id": reply.clientMessageId, "target": captured]), token: CredentialVault.token(client.id)))
  }
  static func receipt(_ client: Client, id: String) async throws -> DeliveryReceipt {
    try await load(
      request(
        try url(client, "v1/receipts", ["id": id]), token: CredentialVault.token(client.id))
    )
  }
  static func events(_ client: Client, epoch: String, after: Int, threadId: String) async throws -> EventBatch {
    var query = ["epoch": epoch, "after": String(after), "threadId": threadId, "window": "latest-v1"]
    if let readerId = client.readingScopeId { query["readerId"] = readerId }
    return try await load(
      request(
        try url(client, "v1/events", query),
        token: CredentialVault.token(client.id)))
  }
  static func upload(_ client: Client, threadId: String, file: LocalAttachment, data: Data)
    async throws -> RemoteAttachment
  {
    var request = request(
      try url(client, "v1/attachments", ["threadId": threadId, "name": file.name]),
      token: CredentialVault.token(client.id), method: "POST", body: data)
    request.setValue(
      UTType(filenameExtension: (file.name as NSString).pathExtension)?.preferredMIMEType
        ?? (file.isImage ? "image/jpeg" : "application/octet-stream"),
      forHTTPHeaderField: "Content-Type")
    request.setValue(hash(data), forHTTPHeaderField: "X-Content-SHA256")
    let result: RemoteAttachment = try await load(request)
    guard result.hostId == client.id, result.threadId == threadId, result.sha256 == hash(data),
      result.sizeBytes == data.count
    else { throw HostServiceError.invalidResponse }
    return result
  }
  static func hash(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
  }
  static func creationManifest(_ file: LocalAttachment, data: Data) -> DesktopCreationFile {
    DesktopCreationFile(id: file.id, name: file.name,
      mediaType: UTType(filenameExtension: (file.name as NSString).pathExtension)?.preferredMIMEType
        ?? (file.isImage ? "image/jpeg" : "application/octet-stream"),
      sizeBytes: data.count, sha256: hash(data))
  }
  static func prepareCreation(_ client: Client, pending: PendingDesktopCreation) async throws -> DesktopCreationOperation {
    let files = try JSONSerialization.jsonObject(with: JSONEncoder().encode(pending.manifest))
    var body: [String: Any] = [
      "clientOperationId": pending.clientOperationId, "projectId": pending.projectId as Any? ?? NSNull(),
      "text": pending.text, "attachments": files,
    ]
    if let mode = pending.collaborationMode { body["collaborationMode"] = mode }
    if let model = pending.model { body["model"] = model }
    if let effort = pending.effort { body["effort"] = effort }
    if let permission = pending.permissionMode { body["permissionMode"] = permission }
    if let speed = pending.speed { body["speed"] = speed }
    return try await post(client, "v1/desktop/creations", body)
  }
  static func creation(_ client: Client, id: String) async throws -> DesktopCreationOperation {
    try await load(request(try url(client, "v1/desktop/creations", ["id": id]), token: CredentialVault.token(client.id)))
  }
  static func creationReceipt(_ client: Client, id: String) async throws -> DesktopCreationOperation {
    try await load(request(try url(client, "v1/desktop/creation-receipts", ["id": id]), token: CredentialVault.token(client.id)))
  }
  static func uploadCreation(_ client: Client, id: String, file: LocalAttachment, data: Data) async throws {
    let request = request(try url(client, "v1/desktop/creation-attachments", ["id": id, "attachmentId": file.id]),
      token: CredentialVault.token(client.id), method: "POST", body: data)
    struct Uploaded: Decodable { let hostId: String; let clientOperationId: String; let id: String; let sha256: String; let sizeBytes: Int }
    let result: Uploaded = try await load(request)
    guard result.hostId == client.id, result.clientOperationId == id, result.id == file.id,
      result.sha256 == hash(data), result.sizeBytes == data.count else { throw HostServiceError.wrongHost }
  }
  static func resolveMessageFile(_ client: Client, threadId: String, messageId: String, reference: String) async throws -> RemoteAttachment {
    struct Resolution: Decodable {
      let hostId: String; let threadId: String; let messageId: String; let reference: String
      let readTarget: AgentRuntimeTarget; let attachment: RemoteAttachment
    }
    let result: Resolution = try await load(request(
      try url(client, "v1/attachments/resolve-link", ["threadId": threadId, "messageId": messageId, "reference": reference]),
      token: CredentialVault.token(client.id)))
    let file = result.attachment
    guard result.hostId == client.id, result.threadId == threadId, result.messageId == messageId,
      result.reference == reference, client.readingTarget == nil || result.readTarget == client.readingTarget,
      file.hostId == client.id, file.threadId == threadId, !file.id.isEmpty,
      file.id != ".", file.id != "..", (file.id as NSString).lastPathComponent == file.id,
      !file.name.isEmpty, (file.name as NSString).lastPathComponent == file.name,
      file.sizeBytes >= 0, file.sizeBytes <= 20 * 1024 * 1024,
      file.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil
    else { throw HostServiceError.wrongHost }
    return file
  }
  static func download(_ client: Client, file: RemoteAttachment) async throws -> URL {
    guard file.hostId == client.id else { throw HostServiceError.wrongHost }
    // Check the source before consulting the cache, so a prior preview cannot hide an update.
    for attempt in 0..<2 {
      try Task.checkCancellation()
      guard let current = try await currentAttachment(client, file: file) else {
        // Older Hosts retain their exact-version download behavior.
        return try await downloadVersion(client, file: file)
      }
      do { return try await downloadVersion(client, file: current) }
      catch HostServiceError.rejected(409, _) where attempt == 0 {
        // A writer can replace the file between resolution and download. Resolve once more.
      }
    }
    throw HostServiceError.invalidResponse
  }
  private static func currentAttachment(_ client: Client, file: RemoteAttachment) async throws -> RemoteAttachment? {
    struct Resolution: Decodable {
      let hostId: String
      let threadId: String
      let sourceAttachmentId: String
      let readTarget: AgentRuntimeTarget
      let attachment: RemoteAttachment
    }
    let result: Resolution
    do {
      result = try await load(request(
        try url(client, "v1/attachments/resolve", ["threadId": file.threadId, "id": file.id]),
        token: CredentialVault.token(client.id)))
    } catch HostServiceError.rejected(404, _) { return nil }
    let current = result.attachment
    guard result.hostId == client.id, result.threadId == file.threadId,
      result.sourceAttachmentId == file.id,
      client.readingTarget == nil || result.readTarget == client.readingTarget,
      current.hostId == client.id, current.threadId == file.threadId,
      !current.id.isEmpty, current.id != ".", current.id != "..",
      (current.id as NSString).lastPathComponent == current.id,
      current.name == file.name, current.mediaType == file.mediaType,
      current.sizeBytes >= 0, current.sizeBytes <= 20 * 1024 * 1024,
      current.sha256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil
    else { throw HostServiceError.wrongHost }
    return current
  }
  private static func downloadVersion(_ client: Client, file: RemoteAttachment) async throws -> URL {
    let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("Downloads/\(file.id)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let destination = directory.appendingPathComponent(
      (file.name as NSString).lastPathComponent)
    if let cached = try? Data(contentsOf: destination), hash(cached) == file.sha256 {
      return destination
    }
    let data = try await bytes(
      request(
        try url(client, "v1/attachments", ["threadId": file.threadId, "id": file.id]),
        token: CredentialVault.token(client.id)))
    guard data.count == file.sizeBytes, hash(data) == file.sha256 else {
      throw HostServiceError.message(L10n.tr("下载文件完整性校验失败"))
    }
    try Task.checkCancellation()
    try data.write(to: destination, options: [.atomic, .completeFileProtectionUnlessOpen])
    return destination
  }
}
