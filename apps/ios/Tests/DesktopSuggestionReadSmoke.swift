import CryptoKit
import Foundation

@MainActor
private final class DelayedSuggestion {
  var continuation: CheckedContinuation<DesktopSuggestionResponse, Error>?
  func read() async throws -> DesktopSuggestionResponse {
    try await withCheckedThrowingContinuation { continuation = $0 }
  }
  func finish(_ value: DesktopSuggestionResponse) {
    continuation?.resume(returning: value)
    continuation = nil
  }
}

@main
struct DesktopSuggestionReadSmoke {
  @MainActor
  static func eventually(_ message: String, _ condition: () -> Bool) async {
    for _ in 0..<300 {
      if condition() { return }
      try? await Task.sleep(for: .milliseconds(10))
    }
    preconditionFailure(message)
  }

  static func value(_ text: String, expires: Date = Date().addingTimeInterval(30)) -> DesktopSuggestion {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return DesktopSuggestion(id: UUID().uuidString, text: text,
      contentSha256: SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined(),
      expiresAt: formatter.string(from: expires))
  }

  static func response(_ scope: DesktopSuggestionScope, _ text: String) -> DesktopSuggestionResponse {
    DesktopSuggestionResponse(schemaVersion: 1, target: scope.target, threadId: scope.threadId,
      sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: value(text))
  }

  @MainActor
  static func main() async {
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "host", workspaceId: "workspace", runtimeId: "runtime")
    let a = DesktopSuggestionScope(target: target, threadId: "a", sourceTurnId: "turn-a", navigation: "first", connection: "https://fixture.invalid")
    let b = DesktopSuggestionScope(target: target, threadId: "b", sourceTurnId: "turn-b", navigation: "second", connection: a.connection)
    let reader = DesktopSuggestionReader()
    let delayedA = DelayedSuggestion(), delayedB = DelayedSuggestion()
    let taskA = Task { await reader.read(a) { try await delayedA.read() } }
    await eventually("A must be waiting") { delayedA.continuation != nil }
    let taskB = Task { await reader.read(b) { try await delayedB.read() } }
    await eventually("B must replace A's scope") { delayedB.continuation != nil }
    delayedB.finish(response(b, "B 原始建议\n保留换行"))
    await eventually("B must display its original text") { reader.visibleSuggestion(for: b)?.text == "B 原始建议\n保留换行" }
    precondition(reader.visibleSuggestion(for: a) == nil)
    delayedA.finish(response(a, "late A must never replace B"))
    await taskA.value
    precondition(reader.visibleSuggestion(for: b)?.text == "B 原始建议\n保留换行")
    taskB.cancel(); await taskB.value; reader.clear()

    let canceled = DelayedSuggestion()
    let old = Task { await reader.read(a) { try await canceled.read() } }
    await eventually("Old request must be waiting") { canceled.continuation != nil }
    old.cancel(); reader.clear()
    canceled.finish(response(a, "canceled response"))
    await old.value
    precondition(reader.suggestion == nil && reader.scope == nil)

    var changedTarget = target; changedTarget.runtimeId = "another-runtime"
    let valid = value("original text")
    let invalid = [
      DesktopSuggestionResponse(schemaVersion: 2, target: target, threadId: a.threadId, sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: valid),
      DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: a.threadId, sourceTurnId: a.sourceTurnId, readOnly: false, suggestion: valid),
      DesktopSuggestionResponse(schemaVersion: 1, target: changedTarget, threadId: a.threadId, sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: valid),
      DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: b.threadId, sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: valid),
      DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: a.threadId, sourceTurnId: b.sourceTurnId, readOnly: true, suggestion: valid),
      DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: a.threadId, sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: value("expired", expires: Date().addingTimeInterval(-1))),
      DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: a.threadId, sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: DesktopSuggestion(id: "bad-hash", text: valid.text, contentSha256: String(repeating: "0", count: 64), expiresAt: valid.expiresAt)),
    ]
    for response in invalid {
      await reader.read(a) { response }
      precondition(reader.visibleSuggestion(for: a) == nil)
    }
    var legacyAccount = target
    legacyAccount.account = AgentAccount(authority: "legacy", subject: "changed-account")
    let legacyResponse = DesktopSuggestionResponse(schemaVersion: 1, target: legacyAccount, threadId: a.threadId, sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: valid)
    precondition(legacyResponse.matches(a), "Codex legacy accounts must not change machine identity")

    var requests = 0
    let latePrediction = Task {
      await reader.read(a) {
        requests += 1
        return DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: a.threadId,
          sourceTurnId: a.sourceTurnId, readOnly: true,
          suggestion: requests == 1 ? nil : value("arrived after completion", expires: Date().addingTimeInterval(0.2)))
      }
    }
    await eventually("A late prediction must display after a bounded read retry") { reader.suggestion?.text == "arrived after completion" }
    await latePrediction.value
    precondition(requests == 2 && reader.suggestion == nil)
    reader.clear()

    var overlayRequests = 0
    let overlayValue = value("侧栏前已经读到的原文", expires: Date().addingTimeInterval(1.5))
    let visible = Task {
      await reader.read(a) {
        overlayRequests += 1
        return DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: a.threadId,
          sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: overlayValue)
      }
    }
    await eventually("Initial suggestion must be visible") { reader.visibleSuggestion(for: a) == overlayValue }
    visible.cancel(); reader.suspend(keeping: a); await visible.value
    precondition(reader.visibleSuggestion(for: a) == overlayValue, "Opening a sidebar must retain a valid confirmed suggestion")
    let resumed = Task {
      await reader.read(a) {
        overlayRequests += 1
        throw URLError(.notConnectedToInternet)
      }
    }
    await Task.yield()
    precondition(reader.visibleSuggestion(for: a) == overlayValue && overlayRequests == 1,
      "Closing a transient overlay must restore the same suggestion without a network round trip")
    await resumed.value
    precondition(reader.suggestion == nil, "Overlay suspension must not extend the original expiration")

    let shortLived = value("expires while sidebar stays open", expires: Date().addingTimeInterval(0.2))
    let beforePause = Task {
      await reader.read(a) {
        DesktopSuggestionResponse(schemaVersion: 1, target: target, threadId: a.threadId,
          sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: shortLived)
      }
    }
    await eventually("Short-lived suggestion must load") { reader.suggestion == shortLived }
    beforePause.cancel(); reader.suspend(keeping: a); await beforePause.value
    await eventually("Expiration must clear even while the overlay remains open") { reader.suggestion == nil }

    let suspendedRequest = DelayedSuggestion()
    let interrupted = Task { await reader.read(a) { try await suspendedRequest.read() } }
    await eventually("Overlay must interrupt an in-flight read") { suspendedRequest.continuation != nil }
    reader.suspend(keeping: a)
    suspendedRequest.finish(response(a, "late suspended response"))
    await interrupted.value
    precondition(reader.suggestion == nil, "An uncancelled provider must not publish after suspension")
    reader.suspend(keeping: b)
    precondition(reader.scope == nil && reader.suggestion == nil, "Another source cannot inherit the suspended cache")
    reader.clear()
    let original = DesktopSuggestionDraftSnapshot(targetKey: "host:a", text: "", revision: 0)
    let suggestion = value("建议正文\n保持原文")
    let intent = DesktopSuggestionDraftAction(scope: a, suggestion: suggestion, draft: original)
    precondition(intent.proposedText(mode: .fill, currentScope: a, currentSuggestion: suggestion, currentDraft: original) == suggestion.text)
    precondition(intent.proposedText(mode: .fill, currentScope: b, currentSuggestion: suggestion, currentDraft: original) == nil)
    precondition(intent.proposedText(mode: .fill, currentScope: a, currentSuggestion: nil, currentDraft: original) == nil)
    let changedBack = DesktopSuggestionDraftSnapshot(targetKey: original.targetKey, text: "", revision: 2)
    precondition(intent.proposedText(mode: .fill, currentScope: a, currentSuggestion: suggestion, currentDraft: changedBack) == nil,
      "Changing text and restoring its bytes must invalidate an older preview")
    precondition(intent.proposedText(mode: .fill, currentScope: a, currentSuggestion: suggestion, currentDraft: original,
      at: Date().addingTimeInterval(120)) == nil)
    let existing = DesktopSuggestionDraftSnapshot(targetKey: "host:a", text: " 原草稿 \n", revision: 3)
    let append = DesktopSuggestionDraftAction(scope: a, suggestion: suggestion, draft: existing)
    precondition(append.proposedText(mode: .fill, currentScope: a, currentSuggestion: suggestion, currentDraft: existing) == nil,
      "Adopting into an occupied draft must never directly overwrite it")
    precondition(append.proposedText(mode: .append, currentScope: a, currentSuggestion: suggestion, currentDraft: existing) == existing.text + "\n\n" + suggestion.text)
    precondition(append.proposedText(mode: .replace, currentScope: a, currentSuggestion: suggestion, currentDraft: existing) == suggestion.text)
    let applied = DesktopSuggestionDraftSnapshot(targetKey: "host:a", text: suggestion.text, revision: 4)
    let undo = DesktopSuggestionDraftUndo(scope: a, suggestionId: suggestion.id, originalText: existing.text, applied: applied)
    reader.clear()
    precondition(undo.restoredText(currentDraft: applied) == existing.text,
      "Local undo must survive prediction reader clearing or expiry")
    let otherDraft = DesktopSuggestionDraftSnapshot(targetKey: "host:b", text: applied.text, revision: applied.revision)
    precondition(undo.restoredText(currentDraft: otherDraft) == nil)
    let editedAgain = DesktopSuggestionDraftSnapshot(targetKey: applied.targetKey, text: applied.text, revision: 6)
    precondition(undo.restoredText(currentDraft: editedAgain) == nil)
    print("Suggestion adoption checks passed: exact scope/revision, expired preview, protected nonempty draft, append/explicit replace and guarded undo")
    print("Desktop suggestion native checks passed: late A/B isolation, cancellation, exact target and source, schema/readOnly, hash/expiry, late prediction, overlay retention without refetch, suspended response fencing and Codex machine identity")
  }
}
