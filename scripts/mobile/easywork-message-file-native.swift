import Foundation
import CoreFoundation

// The runner prepends the actual production enum, not a reimplementation.
enum HostServiceError: Error { case invalidResponse }

// Exercise the exact history rewrite with a transport double, including its
// resource memo, so the second message must not inherit the first provenance.
struct DocumentMessageSource { let messageId: String; let reference: String }
struct FixtureAttachment { let id: String; var documentMessageSource: DocumentMessageSource? }
typealias RemoteAttachment = FixtureAttachment
struct ReadContentProjection { let sourceVersion: String; let displayVersion: String }
enum ReadPosition {
  static func contentVersion(messageId: String, text: String, status: String) -> String { text }
}
struct FixtureMessage {
  let id: String
  let replyMessageId: String?
  let role = "assistant"
  let status: String? = "completed"
  var text: String
  var attachments: [FixtureAttachment]?
  var readingProjection: ReadContentProjection?
}
final class HistoryChannel {
  var calls = 0
  func request(_ method: String, _ params: [String: Any]) async throws -> Data {
    precondition(method == "resource/resolve" && params["threadId"] as? String == "thread-original")
    precondition(params["reference"] as? String == "docs/shared.md")
    calls += 1
    return try JSONSerialization.data(withJSONObject: ["attachment": ["resourceId": "shared-resource"]])
  }
}
enum HistoryProjectionHarness {
  static func object(_ data: Any) throws -> [String: Any] {
    if let bytes = data as? Data { return try JSONSerialization.jsonObject(with: bytes) as! [String: Any] }
    return data as! [String: Any]
  }
  static func resource(_ client: String, threadId: String, data: [String: Any]) -> FixtureAttachment? {
    (data["resourceId"] as? String).map { FixtureAttachment(id: $0) }
  }
  static func project(_ original: [FixtureMessage], channel: HistoryChannel) async throws -> [FixtureMessage] {
    var messages = original
    let cursor: String? = nil, active: String? = nil, client = "own-client", threadId = "thread-original"
    /* PRODUCTION_HISTORY_LINK_PROJECTION */
    return messages
  }
}

@main struct MessageFileBoundary {
  static func main() async throws {
    let source = "1e0800c4-89ef-4226-a394-a82c22131664"
    let thread = "thread-original", link = "file:///workspace/report%20(v2).pdf#L12"
    let payload: [String: Any] = ["version": 1, "threadId": thread, "messageId": source,
      "reference": link, "attachment": ["resourceId": "resource", "name": "report (v2).pdf",
        "size": 10, "sha256": String(repeating: "a", count: 64)]]
    var calls = 0
    _ = try await EasyWorkMessageFileResolution.attachment(threadId: thread,
      messageId: source + ":2", reference: link) { method, params in
      calls += 1
      precondition(method == "resource/resolve")
      precondition(params["threadId"] as? String == thread)
      precondition(params["messageId"] as? String == source)
      precondition(params["reference"] as? String == link)
      return payload
    }
    precondition(calls == 1)
    let failures: [(String, Any)] = [("threadId", "foreign"), ("messageId", "foreign"),
      ("reference", "/workspace/foreign.pdf"), ("version", true), ("version", 1.5),
      ("attachment", NSNull())]
    for (key, value) in failures {
      var changed = payload; changed[key] = value
      var rejected = false, attempts = 0
      do {
        _ = try await EasyWorkMessageFileResolution.attachment(threadId: thread,
          messageId: source, reference: link) { method, _ in
          precondition(method == "resource/resolve"); attempts += 1; return changed
        }
      } catch { rejected = true }
      precondition(rejected && attempts == 1)
    }
    for (key, value) in [("resourceId", "../foreign" as Any), ("name", "../foreign" as Any),
      ("name", "null\0name" as Any), ("size", true as Any), ("size", 10.5 as Any),
      ("size", 20 * 1024 * 1024 + 1 as Any), ("sha256", "bad" as Any)] {
      var changed = payload, file = payload["attachment"] as! [String: Any]
      file[key] = value; changed["attachment"] = file
      var rejected = false
      do {
        _ = try await EasyWorkMessageFileResolution.attachment(threadId: thread,
          messageId: source, reference: link) { _, _ in changed }
      } catch { rejected = true }
      precondition(rejected)
    }
    precondition(EasyWorkMessageFileResolution.originalMessageId("opaque:2") == "opaque:2")
    precondition(EasyWorkMessageFileResolution.originalMessageId(source + ":-1") == source + ":-1")
    let second = "bcdca47b-2e48-42bf-a528-c395f9b6e086"
    let channel = HistoryChannel()
    let projected = try await HistoryProjectionHarness.project([
      FixtureMessage(id: source + ":11", replyMessageId: source, text: "[Guide](docs/shared.md)"),
      FixtureMessage(id: second + ":3", replyMessageId: nil, text: "[Same file, different reply](docs/shared.md)")
    ], channel: channel)
    precondition(channel.calls == 1, "Shared resource bytes must remain memoized")
    for (index, expected) in [source, second].enumerated() {
      let file = projected[index].attachments!.first!
      precondition(file.id == "shared-resource")
      precondition(file.documentMessageSource?.messageId == expected,
        "Each canonical reply must retain its own provenance despite shared bytes")
      precondition(file.documentMessageSource?.reference == "docs/shared.md")
      precondition(projected[index].text.contains("codex-attachment://open?id=shared-resource"))
    }
    print("passed: two canonical replies sharing one memoized resolve retain distinct original message hints")
    print("passed: exact original source request; 13 mismatched/unsafe responses rejected; no fallback/import")
  }
}
