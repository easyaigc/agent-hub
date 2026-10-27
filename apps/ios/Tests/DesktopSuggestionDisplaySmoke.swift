import CryptoKit
import Foundation

@main
struct DesktopSuggestionDisplaySmoke {
  @MainActor static func main() async throws {
    let runtime = CommandLine.arguments.contains("runtime-version")
    let target = AgentRuntimeTarget(agentId: runtime ? "easywork" : "codex", hostId: "host", workspaceId: "workspace", runtimeId: "runtime")
    let native = CommandLine.arguments.contains("native-version")
    let source = runtime ? "runtime" : native ? "native-trace" : "desktop-display"
    let validity = runtime ? "runtime-version" : native ? "native-version" : "desktop-display"
    let scope = DesktopSuggestionScope(target: target, threadId: "thread", sourceTurnId: "same-turn", navigation: "current", connection: "paired", requiresDisplayedSource: !native && !runtime, requiresNativeSource: native, requiresRuntimeSource: runtime)
    func value(_ text: String) -> DesktopSuggestion {
      DesktopSuggestion(id: text, text: text,
        contentSha256: SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined(),
        expiresAt: ISO8601DateFormatter().string(from: Date().addingTimeInterval(6)), validity: validity)
    }
    let first = value("first displayed version"), second = value("regenerated displayed version")
    precondition(first.isValid(at: Date()))
    precondition(!first.isValid(at: Date().addingTimeInterval(10)))
    let sourceOnly = DesktopSuggestion(id: "old-prediction", text: first.text,
      contentSha256: first.contentSha256, expiresAt: first.expiresAt, validity: "source-turn")
    let legacy = DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: scope.threadId,
      sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: sourceOnly)
    let strict = DesktopSuggestionReader()
    strict.seed(legacy, for: scope)
    precondition(strict.suggestion == nil, "Read-ahead cannot seed old trace output into a displayed-source scope")
    await strict.read(scope) { legacy }
    precondition(strict.suggestion == nil, "A displayed-source request cannot downgrade to an indefinitely cached trace result")
    let unmarked = DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: scope.threadId,
      sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: first)
    strict.seed(unmarked, for: scope)
    precondition(strict.suggestion == nil, "A lease without displayed-source evidence cannot seed the preview")
    let reader = DesktopSuggestionReader()
    var reads = 0
    var fail = false
    let work = Task {
      await reader.read(scope) {
        reads += 1
        if fail { throw URLError(.cannotConnectToHost) }
        return DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: scope.threadId,
          sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: reads == 1 ? first : second,
          source: source)
      }
    }
    try await Task.sleep(for: .milliseconds(100))
    precondition(reader.suggestion == first)
    let draft = DesktopSuggestionDraftSnapshot(targetKey: "draft", text: "retained draft", revision: 1)
    let oldAction = DesktopSuggestionDraftAction(scope: scope, suggestion: first, draft: draft)
    try await Task.sleep(for: .milliseconds(2200))
    precondition(reader.suggestion == second, "Same-turn regeneration must replace the displayed value")
    reader.seed(legacy, for: scope)
    precondition(reader.suggestion == second, "Late trace read-ahead cannot replace a confirmed displayed version")
    precondition(oldAction.proposedText(mode: .replace, currentScope: scope, currentSuggestion: second, currentDraft: draft) == nil)
    let renew = DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: scope.threadId,
      sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: second, source: source)
    reader.seed(renew, for: scope)
    let captured = DesktopSuggestionDraftAction(scope: scope, suggestion: second, draft: draft)
    let renewed = value(second.text)
    precondition(renewed == second, "Lease renewal does not change the displayed suggestion identity")
    precondition(captured.proposedText(mode: .replace, currentScope: scope, currentSuggestion: renewed, currentDraft: draft) == second.text)
    fail = true
    try await Task.sleep(for: .milliseconds(2200))
    precondition(reader.suggestion == nil, "An unavailable display cannot retain a previous version")
    work.cancel()
    await work.value
    fail = false
    reads = 1
    let empty = Task {
      await reader.read(scope) {
        reads += 1
        return DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: scope.threadId,
          sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: reads == 2 ? second : nil,
          source: source)
      }
    }
    try await Task.sleep(for: .milliseconds(100))
    precondition(reader.suggestion == second)
    try await Task.sleep(for: .milliseconds(2200))
    precondition(reader.suggestion == nil, "Desktop dismissal must clear the same-turn mobile suggestion")
    empty.cancel()
    await empty.value
    precondition(draft.text == "retained draft")
    print("Displayed suggestion checks passed: no legacy downgrade or late trace seeding, same-turn replacement, stale preview rejection, display loss, disappearance, cancellation, finite lease and draft retention")
  }
}
