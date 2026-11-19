import CryptoKit
import Foundation

@main
struct DesktopSuggestionDisplaySmoke {
  enum Failure: Error { case timeout(String) }
  static func value(_ text: String, validity: String) -> DesktopSuggestion {
    DesktopSuggestion(id: text, text: text,
      contentSha256: SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined(),
      expiresAt: ISO8601DateFormatter().string(from: Date().addingTimeInterval(6)), validity: validity)
  }
  @MainActor static func main() async throws {
    @MainActor func observed(_ description: String, _ condition: () -> Bool) async throws {
      let deadline = ContinuousClock.now.advanced(by: .seconds(10))
      while !condition() {
        guard ContinuousClock.now < deadline else { throw Failure.timeout(description) }
        try await Task.sleep(for: .milliseconds(20))
      }
      guard ContinuousClock.now < deadline else { throw Failure.timeout(description) }
    }
    let runtime = CommandLine.arguments.contains("runtime-version")
    let target = AgentRuntimeTarget(agentId: runtime ? "easywork" : "codex", hostId: "host", workspaceId: "workspace", runtimeId: "runtime")
    let native = CommandLine.arguments.contains("native-version")
    let source = runtime ? "runtime" : native ? "native-trace" : "desktop-display"
    let validity = runtime ? "runtime-version" : native ? "native-version" : "desktop-display"
    let scope = DesktopSuggestionScope(target: target, threadId: "thread", sourceTurnId: "same-turn", navigation: "current", connection: "paired", requiresDisplayedSource: !native && !runtime, requiresNativeSource: native, requiresRuntimeSource: runtime)
    let first = Self.value("first displayed version", validity: validity), second = Self.value("regenerated displayed version", validity: validity)
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
    var fail = false
    var regenerate = false
    var failedReads = 0
    let work = Task {
      await reader.read(scope) {
        if fail { failedReads += 1; throw URLError(.cannotConnectToHost) }
        return DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: scope.threadId,
          sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: Self.value(regenerate ? second.text : first.text, validity: validity),
          source: source)
      }
    }
    try await observed("initial displayed version") { reader.suggestion == first }
    precondition(reader.suggestion == first)
    let draft = DesktopSuggestionDraftSnapshot(targetKey: "draft", text: "retained draft", revision: 1)
    let oldAction = DesktopSuggestionDraftAction(scope: scope, suggestion: first, draft: draft)
    regenerate = true
    try await observed("same-turn regenerated version") { reader.suggestion == second }
    precondition(reader.suggestion == second, "Same-turn regeneration must replace the displayed value")
    reader.seed(legacy, for: scope)
    precondition(reader.suggestion == second, "Late trace read-ahead cannot replace a confirmed displayed version")
    precondition(oldAction.proposedText(mode: .replace, currentScope: scope, currentSuggestion: second, currentDraft: draft) == nil)
    let renew = DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: scope.threadId,
      sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: second, source: source)
    reader.seed(renew, for: scope)
    let captured = DesktopSuggestionDraftAction(scope: scope, suggestion: second, draft: draft)
    let renewed = Self.value(second.text, validity: validity)
    precondition(renewed == second, "Lease renewal does not change the displayed suggestion identity")
    precondition(captured.proposedText(mode: .replace, currentScope: scope, currentSuggestion: renewed, currentDraft: draft) == second.text)
    fail = true
    try await observed("failed display read clears suggestion") { failedReads > 0 && reader.suggestion == nil }
    precondition(reader.suggestion == nil, "An unavailable display cannot retain a previous version")
    work.cancel()
    await work.value
    fail = false
    var dismissed = false
    var dismissalReads = 0
    let empty = Task {
      await reader.read(scope) {
        if dismissed { dismissalReads += 1 }
        return DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: scope.threadId,
          sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: dismissed ? nil : Self.value(second.text, validity: validity),
          source: source)
      }
    }
    try await observed("new display read publishes a fresh lease") { reader.suggestion == second }
    precondition(reader.suggestion == second)
    dismissed = true
    try await observed("desktop dismissal read clears suggestion") { dismissalReads > 0 && reader.suggestion == nil }
    precondition(reader.suggestion == nil, "Desktop dismissal must clear the same-turn mobile suggestion")
    empty.cancel()
    await empty.value
    precondition(draft.text == "retained draft")
    print("Displayed suggestion checks passed: no legacy downgrade or late trace seeding, same-turn replacement, stale preview rejection, display loss, disappearance, cancellation, finite lease and draft retention")
  }
}
