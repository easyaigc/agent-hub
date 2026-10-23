import CryptoKit
import Foundation

@main
struct DesktopSuggestionSourceValiditySmoke {
  static func value(_ text: String, validity: String? = "source-turn") throws -> DesktopSuggestion {
    var body: [String: Any] = [
      "id": text, "text": text,
      "contentSha256": SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined(),
      "expiresAt": ISO8601DateFormatter().string(from: Date().addingTimeInterval(-7200)),
    ]
    if let validity { body["validity"] = validity }
    return try JSONDecoder().decode(DesktopSuggestion.self, from: JSONSerialization.data(withJSONObject: body))
  }

  @MainActor static func main() async throws {
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "host", workspaceId: "workspace", runtimeId: "runtime")
    let a = DesktopSuggestionScope(target: target, threadId: "thread", sourceTurnId: "a", navigation: "current", connection: "paired")
    let b = DesktopSuggestionScope(target: target, threadId: "thread", sourceTurnId: "b", navigation: "current", connection: "paired")
    let original = try value("original native output")
    precondition(original.isValid(at: Date().addingTimeInterval(86400)), "A confirmed source must not be hidden by the legacy one-hour timestamp")
    let legacy = try value("legacy", validity: nil), unknown = try value("unknown", validity: "forever")
    precondition(!legacy.isValid(at: Date()) && !unknown.isValid(at: Date()), "Legacy expiry and unknown validity remain fail-closed")
    let reader = DesktopSuggestionReader()
    let response = DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: a.threadId, sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: original)
    reader.seed(response, for: a)
    precondition(reader.visibleSuggestion(for: a) == original)
    reader.suspend(keeping: a)
    try await Task.sleep(for: .milliseconds(100))
    precondition(reader.visibleSuggestion(for: a) == original, "An overlay cannot arm a legacy expiration timer for source-bound content")
    var reads = 0
    await reader.read(a) { reads += 1; return response }
    precondition(reads == 0 && reader.visibleSuggestion(for: a) == original)
    let draft = DesktopSuggestionDraftSnapshot(targetKey: "target", text: "", revision: 0)
    let action = DesktopSuggestionDraftAction(scope: a, suggestion: original, draft: draft)
    precondition(action.proposedText(mode: .fill, currentScope: a, currentSuggestion: original, currentDraft: draft, at: Date().addingTimeInterval(86400)) == original.text)
    precondition(action.proposedText(mode: .fill, currentScope: b, currentSuggestion: original, currentDraft: draft) == nil)
    precondition(reader.visibleSuggestion(for: b) == nil)
    let next = try value("new source output")
    await reader.read(b) { DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: b.threadId, sourceTurnId: b.sourceTurnId, readOnly: true, suggestion: next) }
    precondition(reader.visibleSuggestion(for: a) == nil && reader.visibleSuggestion(for: b) == next)
    reader.clear()
    precondition(reader.suggestion == nil && reader.scope == nil)
    print("Source validity passed: native output survives clock age, legacy/unknown expiry rejects, overlay/resume retains, changed source cannot adopt or display old content")
  }
}
