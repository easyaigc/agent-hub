import Foundation
import MobileCore

final class TaskHubClient: @unchecked Sendable {
  let binding: TaskHubBinding
  private let session: URLSession
  private let token: String
  private let deviceId: String
  init(binding: TaskHubBinding, token: String = "", deviceId: String) throws {
    _ = try EasyWorkPairing.localEndpoint(binding.endpoint)
    guard binding.fingerprint.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil else { throw TaskHubError.changedIdentity }
    self.binding = binding; self.token = token; self.deviceId = deviceId
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 180
    config.httpCookieStorage = nil; config.urlCredentialStorage = nil
    session = URLSession(configuration: config, delegate: PairedHostTrust(endpoint: binding.endpoint, fingerprint: binding.fingerprint), delegateQueue: nil)
  }
  deinit { session.invalidateAndCancel() }
  static func probe(endpoint: String, pin: String? = nil) async throws -> TaskHubBinding {
    let url = try EasyWorkPairing.localEndpoint(endpoint)
    let discovered = DiscoveredHostTrust(endpoint: url)
    let delegate: any URLSessionDelegate = pin.map { PairedHostTrust(endpoint: url.absoluteString, fingerprint: $0) } ?? discovered
    let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 5
    let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    defer { session.invalidateAndCancel() }
    let (data, response) = try await session.data(from: url.appendingPathComponent("api/v1/service"))
    guard let response = response as? HTTPURLResponse, response.statusCode == 200, let fingerprint = pin ?? discovered.fingerprint else { throw TaskHubError.message(L10n.tr("该地址没有可用的任务主服务")) }
    let service = try JSONDecoder().decode(TaskHubService.self, from: data)
    try service.validate()
    return TaskHubBinding(service: service, endpoint: url.absoluteString, fingerprint: fingerprint)
  }
  @discardableResult func verify() async throws -> TaskHubService {
    let actual = try await read(TaskHubService.self, path: "service")
    try actual.validate()
    guard actual.identity == binding.service.identity else { throw TaskHubError.changedIdentity }
    return actual
  }
  func read<T: Decodable & Sendable>(_ type: T.Type, path: String, query: [URLQueryItem] = []) async throws -> T {
    try JSONDecoder().decode(type, from: await request(path: path, query: query))
  }
  func request(path: String, method: String = "GET", body: Data? = nil, operationId: String? = nil, query: [URLQueryItem] = [], contentType: String = "application/json") async throws -> Data {
    let request = try makeRequest(path: path, method: method, body: body, operationId: operationId, query: query, contentType: contentType)
    let (data, response) = try await session.data(for: request)
    try Self.check(response, data: data)
    return data
  }
  func download(_ attachment: TaskHubAttachment) async throws -> Data {
    // Attachment URLs are resolved against the selected service, never an arbitrary server.
    return try await request(path: "attachments/\(attachment.id)/file")
  }
  func events(projectId: String, cursor: String?, changed: @escaping @Sendable (TaskHubChange) async throws -> Void) async throws {
    var request = try makeRequest(path: "events", query: [URLQueryItem(name: "projectId", value: projectId)])
    request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
    request.setValue(cursor ?? "0", forHTTPHeaderField: "Last-Event-ID")
    request.timeoutInterval = 180
    let (bytes, response) = try await session.bytes(for: request)
    try Self.check(response, data: Data())
    var framer = TaskHubSSEFramer()
    for try await byte in bytes {
      try Task.checkCancellation()
      if let event = try framer.consume(byte) {
        let payload = try JSONDecoder().decode(TaskHubChangePayload.self, from: event.data)
        guard UUID(uuidString: payload.taskId) != nil, payload.revision > 0, let cursor = event.cursor else { throw TaskHubError.message(L10n.tr("实时业务事件身份无效")) }
        try await changed(TaskHubChange(cursor: cursor, taskId: payload.taskId, revision: payload.revision))
      }
    }
  }
  func makeRequest(path: String, method: String = "GET", body: Data? = nil, operationId: String? = nil, query: [URLQueryItem] = [], contentType: String = "application/json") throws -> URLRequest {
    guard !path.contains(".."), !path.hasPrefix("/"), !path.contains(":") else { throw TaskHubError.message(L10n.tr("无效的任务接口")) }
    let root = try EasyWorkPairing.localEndpoint(binding.endpoint).appendingPathComponent("api/v1")
    var components = URLComponents(url: root.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
    if !query.isEmpty { components.queryItems = query }
    guard let url = components.url else { throw TaskHubError.message(L10n.tr("无效的主服务地址")) }
    var request = URLRequest(url: url); request.httpMethod = method; request.httpBody = body
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    request.setValue(deviceId, forHTTPHeaderField: "X-TaskHub-Device-Id")
    if body != nil { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
    if !token.isEmpty, path != "service" { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
    if let operationId { request.setValue(operationId, forHTTPHeaderField: "X-TaskHub-Operation-Id") }
    return request
  }
  static func check(_ response: URLResponse?, data: Data) throws {
    guard let response = response as? HTTPURLResponse else { throw TaskHubError.message(L10n.tr("主服务返回无效响应")) }
    guard (200..<300).contains(response.statusCode) else {
      let error = try? JSONDecoder().decode(TaskHubErrorBody.self, from: data)
      throw TaskHubError.rejected(response.statusCode, error?.message ?? L10n.tr("主服务请求失败（\(response.statusCode)）"))
    }
  }
}

struct TaskHubSSEEvent: Sendable { let cursor: String?; var data = Data() }
struct TaskHubChange: Sendable { let cursor: String; let taskId: String; let revision: Int }
private struct TaskHubChangePayload: Decodable { let taskId: String; let revision: Int }

/// Read bytes directly: Foundation's line sequence discards empty SSE delimiters.
struct TaskHubSSEFramer {
  private var line = Data()
  private var frameBytes = 0
  private var cursor: String?
  private var hasData = false
  private var dataLines: [String] = []
  private let lineLimit = 256 * 1024
  private let frameLimit = 512 * 1024

  mutating func consume(_ byte: UInt8) throws -> TaskHubSSEEvent? {
    if byte != 10 {
      guard line.count < lineLimit else { throw TaskHubError.message(L10n.tr("任务实时更新单行超过大小限制")) }
      line.append(byte)
      return nil
    }
    frameBytes += line.count + 1
    guard frameBytes <= frameLimit else { throw TaskHubError.message(L10n.tr("任务实时更新消息超过大小限制")) }
    if line.last == 13 { line.removeLast() }
    guard let value = String(data: line, encoding: .utf8) else { throw TaskHubError.message(L10n.tr("任务实时更新包含无效文本")) }
    line.removeAll(keepingCapacity: true)
    if value.isEmpty {
      let event = hasData ? TaskHubSSEEvent(cursor: cursor, data: Data(dataLines.joined(separator: "\n").utf8)) : nil
      cursor = nil; hasData = false; frameBytes = 0; dataLines = []
      return event
    }
    if value.hasPrefix("id:") {
      let id = String(value.dropFirst(3)).trimmingCharacters(in: .whitespaces)
      guard TaskHubRules.shared.validCursor(value: id) else { throw TaskHubError.message(L10n.tr("任务实时更新游标无效")) }
      cursor = id.isEmpty ? nil : id
    }
    if value == "data" || value.hasPrefix("data:") {
      hasData = true
      var data = value == "data" ? "" : String(value.dropFirst(5))
      if data.hasPrefix(" ") { data.removeFirst() }
      dataLines.append(data)
    }
    return nil
  }
}
