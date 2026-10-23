import Foundation

/// The OS owns file-backed transfers. Relaunch only reconnects sessions and reads
/// business receipts; it does not create a replacement POST for an unknown upload.
final class TaskHubUploadCoordinator: NSObject, URLSessionDataDelegate, @unchecked Sendable {
  static let shared = TaskHubUploadCoordinator()
  private static let prefix = "com.easyai.agenthub.taskhub.upload."
  private let lock = NSLock()
  private var sessions: [String: URLSession] = [:]
  private var trusts: [String: PairedHostTrust] = [:]
  private var waiters: [String: CheckedContinuation<Data, Error>] = [:]
  private var responses: [String: Data] = [:]
  private var completions: [String: () -> Void] = [:]
  private struct Registration: Codable { var scope: String; var binding: TaskHubBinding }
  private var registrations: [String: Registration] = [:]
  private let registryFile = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("task-hub-background-uploads-v1.bin")
  private override init() {
    super.init()
    if let bytes = try? Data(contentsOf: registryFile), let stored = try? AgentCache.open(bytes, as: [String: Registration].self) {
      registrations = stored
      for (id, value) in stored {
        if authorized(binding: value.binding, scope: value.scope) { _ = try? restore(binding: value.binding, scope: value.scope) }
        else {
          registrations.removeValue(forKey: id)
          // Reconnect only to cancel old system transfers. No request is created.
          URLSession(configuration: .background(withIdentifier: id)).invalidateAndCancel()
        }
      }
      try? saveRegistry()
    }
  }
  private func saveRegistry() throws {
    try FileManager.default.createDirectory(at: registryFile.deletingLastPathComponent(), withIntermediateDirectories: true)
    try AgentCache.seal(registrations).write(to: registryFile, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
  }

  private func identifier(_ scope: String) -> String { Self.prefix + HostService.hash(Data(scope.utf8)) }
  private func authorized(binding: TaskHubBinding, scope: String) -> Bool {
    let token = CredentialVault.token("task-hub-session-" + HostService.hash(Data(binding.service.identity.utf8)))
    let deviceId = CredentialVault.token("task-hub-device-id-v1")
    guard UUID(uuidString: deviceId) != nil,
      let login = try? JSONDecoder().decode(TaskHubLogin.self, from: Data(token.utf8)), login.actor.kind == "human", !login.token.isEmpty,
      let expires = BusinessNotice.date(login.expiresAt), expires > Date() else { return false }
    return scope == binding.service.identity + "/" + login.actor.id + "/" + deviceId
  }
  private func key(_ session: URLSession, _ task: URLSessionTask) -> String {
    (session.configuration.identifier ?? "") + "/" + (task.taskDescription ?? String(task.taskIdentifier))
  }
  @discardableResult func restore(binding: TaskHubBinding, scope: String) throws -> URLSession {
    guard authorized(binding: binding, scope: scope) else { throw TaskHubError.message(L10n.tr("原上传账户已退出或会话过期；请登录后查询原回执")) }
    let id = identifier(scope)
    return try lock.withLock {
      if let existing = sessions[id] { return existing }
      registrations[id] = Registration(scope: scope, binding: binding)
      try saveRegistry()
      let configuration = URLSessionConfiguration.background(withIdentifier: id)
      configuration.isDiscretionary = false
      configuration.sessionSendsLaunchEvents = true
      configuration.allowsCellularAccess = false
      configuration.httpCookieStorage = nil; configuration.urlCredentialStorage = nil
      configuration.timeoutIntervalForRequest = 30
      configuration.timeoutIntervalForResource = 12 * 60 * 60
      trusts[id] = PairedHostTrust(endpoint: binding.endpoint, fingerprint: binding.fingerprint)
      let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
      sessions[id] = session
      session.getAllTasks { _ in } // Reattach existing system transfers without resuming a new input.
      return session
    }
  }
  func upload(request: URLRequest, file: URL, binding: TaskHubBinding, scope: String, operationId: String) async throws -> Data {
    let session = try restore(binding: binding, scope: scope)
    let operationKey = identifier(scope) + "/" + operationId
    return try await withTaskCancellationHandler {
      try Task.checkCancellation()
      return try await withCheckedThrowingContinuation { continuation in
        guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
        let task = session.uploadTask(with: request, fromFile: file)
        task.taskDescription = operationId
        lock.withLock { waiters[operationKey] = continuation; responses[operationKey] = Data() }
        if Task.isCancelled {
          let waiter = lock.withLock { waiters.removeValue(forKey: operationKey) }
          waiter?.resume(throwing: CancellationError()); task.cancel(); return
        }
        task.resume()
      }
    } onCancel: {
      // End only the UI wait. The immutable system transfer and original receipt remain.
      let continuation = self.lock.withLock { self.waiters.removeValue(forKey: operationKey) }
      continuation?.resume(throwing: CancellationError())
    }
  }
  func revoke(scope: String) {
    let id = identifier(scope)
    let session = lock.withLock {
      registrations.removeValue(forKey: id); try? saveRegistry()
      return sessions.removeValue(forKey: id)
    }
    session?.invalidateAndCancel()
  }
  static func handleBackgroundEvents(identifier: String, completionHandler: @escaping () -> Void) -> Bool {
    guard identifier.hasPrefix(prefix) else { return false }
    let attached = shared.lock.withLock {
      guard shared.sessions[identifier] != nil else { return false }
      shared.completions[identifier] = completionHandler; return true
    }
    if !attached { completionHandler() }
    return true
  }
  func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
    let trust = lock.withLock { trusts[session.configuration.identifier ?? ""] }
    guard let trust else { completionHandler(.cancelAuthenticationChallenge, nil); return }
    trust.urlSession(session, didReceive: challenge, completionHandler: completionHandler)
  }
  func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
    urlSession(session, didReceive: challenge, completionHandler: completionHandler)
  }
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    let exceeded = lock.withLock {
      let id = key(session, dataTask)
      var bytes = responses[id] ?? Data(); bytes.append(data)
      guard bytes.count <= 512 * 1024 else { return true }
      responses[id] = bytes; return false
    }
    if exceeded { dataTask.cancel() }
  }
  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    let id = key(session, task)
    let (continuation, data) = lock.withLock { (waiters.removeValue(forKey: id), responses.removeValue(forKey: id) ?? Data()) }
    guard let continuation else { return } // Original GET receipt resolves a relaunch or ended wait.
    if let error { continuation.resume(throwing: error); return }
    do { try TaskHubClient.check(task.response, data: data); continuation.resume(returning: data) }
    catch { continuation.resume(throwing: error) }
  }
  func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
    let handler = lock.withLock { completions.removeValue(forKey: session.configuration.identifier ?? "") }
    if let handler { DispatchQueue.main.async { handler() } }
  }
}
