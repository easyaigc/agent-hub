import Foundation
import CoreFoundation

// The runner prepends the actual production enum, not a reimplementation.
enum HostServiceError: Error { case invalidResponse }

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
    print("passed: exact original source request; 13 mismatched/unsafe responses rejected; no fallback/import")
  }
}
