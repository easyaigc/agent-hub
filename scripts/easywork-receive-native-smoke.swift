import CryptoKit
import Foundation

enum HostServiceError: Error { case wrongHost, invalidResponse, unavailable, invalidAddress }
// This standalone HTTP examiner covers the legacy Relay path. Actual device
// grants and account refresh are exercised by the separately fingerprinted DEV
// App tests; unexpected entry into a device-grant branch fails closed here.
struct EasyWorkBinding { let bindingId: String }
struct EasyWorkDeviceAuthorizationFailure: Error { let status: Int; let code: String }
actor EasyWorkDeviceAuthorization {
  static let shared = EasyWorkDeviceAuthorization()
  func headers(ref: String, binding: EasyWorkBinding, method: String, url: URL, body: Data?) async throws -> [String: String] {
    throw HostServiceError.unavailable
  }
}
func easyWorkJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed, .withoutEscapingSlashes]) }
func easyWorkObject(_ data: Data) throws -> [String: Any] { guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw HostServiceError.invalidResponse }; return value }
extension Data { var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") } }
struct AgentConnectionCandidate { let endpoint: String }
enum EasyWorkLogin { static func validToken(fallback: String) async throws -> String { fallback } }
enum HostService {
  static func baseURL(_ endpoint: String) throws -> URL {
    guard let url = URL(string: endpoint), url.scheme == "http", url.host == "127.0.0.1" else { throw HostServiceError.invalidAddress }; return url
  }
  static func request(_ url: URL, token: String, method: String, body: Data?) -> URLRequest {
    var value = URLRequest(url: url); value.httpMethod = method; value.httpBody = body
    value.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
    value.setValue("application/json", forHTTPHeaderField: "Content-Type"); return value
  }
}

@main struct ReceiveSmoke {
  static func main() async throws {
    if CommandLine.arguments[1] == "relay-boundaries" {
      let endpoint = CommandLine.arguments[2]
      let frame = try easyWorkJSON(["bindingId": "receive-binding", "senderDeviceId": "desktop", "senderSessionId": "session", "sequence": 1])
      let cancelled = try RelayAgentTransport(candidate: AgentConnectionCandidate(endpoint: endpoint), bindingId: "receive-binding", token: "receive-synthetic-token")
      do {
        try await cancelled.acknowledge(easyWorkJSON(["bindingId": "other-binding", "sequence": 1]))
        fatalError("Wrong binding accepted")
      } catch HostServiceError.wrongHost {}
      try await cancelled.acknowledge(frame)
      await cancelled.close()
      try await Task.sleep(for: .milliseconds(200))
      let (cancelledData, _) = try await URLSession.shared.data(from: URL(string: endpoint + "/stats")!)
      guard try easyWorkObject(cancelledData)["ackRequests"] as? Int == 0 else { fatalError("Close did not cancel pending acknowledgement") }
      let failing = try RelayAgentTransport(candidate: AgentConnectionCandidate(endpoint: endpoint), bindingId: "receive-binding", token: "receive-synthetic-token")
      try await failing.acknowledge(frame)
      try await Task.sleep(for: .milliseconds(400))
      do { _ = try await failing.receive(); fatalError("Acknowledgement failure was hidden") }
      catch HostServiceError.unavailable {}
      await failing.close()
      print("Relay binding isolation, close and acknowledgement failure passed")
      return
    }
    if CommandLine.arguments[1] == "relay" {
      let endpoint = CommandLine.arguments[2]
      let route = try RelayAgentTransport(candidate: AgentConnectionCandidate(endpoint: endpoint), bindingId: "receive-binding", token: "receive-synthetic-token")
      let started = Date()
      var last: Data?
      var receivedMs = 0.0
      for index in 0..<12 {
        let frame = try await route.receive()
        if index == 11 { receivedMs = Date().timeIntervalSince(started) * 1000 }
        try await route.acknowledge(frame); last = frame
      }
      // A duplicate must not multiply acknowledgements waiting in the same batch.
      try await route.acknowledge(last!)
      var stats: [String: Any] = [:]
      let deadline = Date().addingTimeInterval(20)
      repeat {
        let (data, _) = try await URLSession.shared.data(from: URL(string: endpoint + "/stats")!)
        stats = try easyWorkObject(data)
        if stats["acknowledged"] as? Int == 12 { break }
        try await Task.sleep(for: .milliseconds(20))
      } while Date() < deadline
      guard stats["acknowledged"] as? Int == 12 else { throw HostServiceError.unavailable }
      await route.close()
      print(String(decoding: try easyWorkJSON(["receivedMs": receivedMs, "acknowledgedMs": Date().timeIntervalSince(started) * 1000, "stats": stats]), as: UTF8.self))
      return
    }
    let fixtures = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [[String: Any]]
    var caches: [String: EasyWorkThreadSync] = [:]
    for fixture in fixtures {
      let scope = fixture["scope"] as? String ?? "primary"
      let previous = caches[scope] ?? EasyWorkThreadSync()
      let oldParameters = try easyWorkJSON(previous.parameters(threadId: "thread"))
      let shouldFail = fixture["shouldFail"] as? Bool == true
      do {
        let (next, data) = try previous.merging(fixture["result"] as! [String: Any], threadId: "thread")
        guard !shouldFail else { fatalError("Expected rejection: " + (fixture["name"] as! String)) }
        let value = try easyWorkObject(data)
        guard try easyWorkJSON(value["messages"]!) == easyWorkJSON(fixture["expectedMessages"]!) else { fatalError("Wrong merged messages: " + (fixture["name"] as! String)) }
        caches[scope] = next
      } catch {
        guard shouldFail else { throw error }
      }
      guard try oldParameters == easyWorkJSON(previous.parameters(threadId: "thread")) else { fatalError("Partial cache mutation") }
    }
    print("Native EasyWork delta checks passed: \(fixtures.count)")
  }
}
