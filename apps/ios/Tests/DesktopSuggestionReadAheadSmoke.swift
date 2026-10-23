import Foundation
import CryptoKit

@MainActor
private final class ReadAheadProbe {
  var calls = 0
  var cancelled = 0
  var confirmedScope: DesktopSuggestionScope?
  func fetch(_ scope: DesktopSuggestionScope) async throws -> DesktopSuggestionResponse {
    calls += 1
    do { try await Task.sleep(for: .milliseconds(100)) }
    catch { cancelled += 1; throw error }
    return DesktopSuggestionResponse(schemaVersion: 1, target: scope.target, threadId: scope.threadId,
      sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: nil)
  }
}

@MainActor
private final class ControlledReadAhead {
  var calls = 0
  var active = 0
  var maximumActive = 0
  var startedRead = false
  private var responses: [Int: CheckedContinuation<DesktopSuggestionResponse, Error>] = [:]

  func fetch(_ scope: DesktopSuggestionScope) async throws -> DesktopSuggestionResponse {
    calls += 1
    let call = calls
    active += 1
    maximumActive = max(maximumActive, active)
    defer { active -= 1 }
    return try await withCheckedThrowingContinuation { responses[call] = $0 }
  }

  func finish(_ call: Int, scope: DesktopSuggestionScope, suggestion: DesktopSuggestion? = nil) {
    responses.removeValue(forKey: call)!.resume(returning: DesktopSuggestionResponse(
      schemaVersion: 1, target: scope.target, threadId: scope.threadId,
      sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: suggestion,
      source: suggestion?.isNativeBound == true ? "native-trace" : nil))
  }

  func fail(_ call: Int, error: Error = URLError(.networkConnectionLost)) {
    responses.removeValue(forKey: call)!.resume(throwing: error)
  }
}

/// A local proxy cancellation can finish before the upstream Host read does.
@MainActor
private final class ProxyReadAhead {
  var calls = 0
  var cancelled = 0
  var busy = 0
  private(set) var upstream: Set<Int> = []
  private var responses: [Int: CheckedContinuation<DesktopSuggestionResponse, Error>] = [:]

  func fetch() async throws -> DesktopSuggestionResponse {
    calls += 1
    let call = calls
    guard upstream.isEmpty else { busy += 1; throw URLError(.resourceUnavailable) }
    upstream.insert(call)
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { responses[call] = $0 }
    } onCancel: {
      Task { @MainActor in
        if let response = self.responses.removeValue(forKey: call) {
          self.cancelled += 1
          response.resume(throwing: CancellationError())
        }
      }
    }
  }

  func finish(_ call: Int, scope: DesktopSuggestionScope, suggestion: DesktopSuggestion) {
    precondition(upstream.remove(call) != nil)
    responses.removeValue(forKey: call)?.resume(returning: DesktopSuggestionResponse(
      schemaVersion: 1, target: scope.target, threadId: scope.threadId,
      sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: suggestion, source: "native-trace"))
  }
}

@main
struct DesktopSuggestionReadAheadSmoke {
  private static func nativeValue(_ text: String, expires: Date = Date().addingTimeInterval(6)) -> DesktopSuggestion {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return DesktopSuggestion(id: text, text: text,
      contentSha256: SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined(),
      expiresAt: formatter.string(from: expires), validity: "native-version")
  }

  @MainActor private static func waitFor(_ condition: @MainActor () -> Bool) async throws {
    let deadline = ContinuousClock.now.advanced(by: .seconds(2))
    while !condition() {
      precondition(ContinuousClock.now < deadline, "Controlled read did not reach its checkpoint")
      try await Task.sleep(for: .milliseconds(1))
    }
  }

  @MainActor private static func historyRefreshDuringRead(_ scope: DesktopSuggestionScope) async throws {
    let reader = DesktopSuggestionReadAhead(), network = ControlledReadAhead()
    reader.prepare(scope) { try await network.fetch(scope) }
    try await waitFor { network.calls == 1 }
    let read = Task {
      network.startedRead = true
      return try await reader.read(scope) { try await network.fetch(scope) }
    }
    try await waitFor { network.startedRead }
    reader.prepare(scope) { try await network.fetch(scope) }
    try await Task.sleep(for: .milliseconds(20))
    let calls = network.calls, maximumActive = network.maximumActive
    for call in 1...calls { network.finish(call, scope: scope) }
    _ = try await read.value
    reader.cancel()
    precondition(calls == 1 && maximumActive == 1,
      "History prepare during an awaited same-scope read must keep one HTTP owner; observed \(calls) requests, \(maximumActive) simultaneous")
  }

  @MainActor private static func sharedFailureFallback(_ scope: DesktopSuggestionScope) async throws {
    let reader = DesktopSuggestionReadAhead(), network = ControlledReadAhead()
    var readers = 0, fallbacks = 0
    reader.prepare(scope) { try await network.fetch(scope) }
    try await waitFor { network.calls == 1 }
    func consume() async throws -> DesktopSuggestionResponse {
      readers += 1
      return try await reader.read(scope) { fallbacks += 1; return try await network.fetch(scope) }
    }
    let first = Task { try await consume() }, second = Task { try await consume() }
    try await waitFor { readers == 2 }
    network.fail(1)
    try await waitFor { network.calls == 2 }
    reader.prepare(scope) { try await network.fetch(scope) }
    let third = Task { try await consume() }
    try await waitFor { readers == 3 }
    try await Task.sleep(for: .milliseconds(20))
    precondition(network.calls == 2 && network.active == 1 && fallbacks == 1,
      "Concurrent failed-prefetch consumers and history refresh must share one ordinary fallback")
    network.finish(2, scope: scope)
    let responses = try await [first.value, second.value, third.value]
    precondition(responses.allSatisfy { $0.matches(scope) } && network.maximumActive == 1)

    // The fallback itself is not another prefetch to retry after failure.
    reader.prepare(scope) { try await network.fetch(scope) }
    try await waitFor { network.calls == 3 }
    let failed = Task { try await reader.read(scope) { fallbacks += 1; return try await network.fetch(scope) } }
    network.fail(3)
    try await waitFor { network.calls == 4 }
    network.fail(4, error: URLError(.cannotConnectToHost))
    do { _ = try await failed.value; preconditionFailure("Fallback error must reach the caller") }
    catch { precondition((error as? URLError)?.code == .cannotConnectToHost) }
    precondition(network.calls == 4 && fallbacks == 2 && network.active == 0,
      "A failed ordinary fallback must not replay")
  }

  @MainActor private static func directReadOwnsFlight(_ scope: DesktopSuggestionScope) async throws {
    let reader = DesktopSuggestionReadAhead(), network = ControlledReadAhead()
    var started = 0
    let first = Task { try await reader.read(scope) { try await network.fetch(scope) } }
    try await waitFor { network.calls == 1 }
    reader.prepare(scope) { try await network.fetch(scope) }
    let second = Task {
      started += 1
      return try await reader.read(scope) { try await network.fetch(scope) }
    }
    try await waitFor { started == 1 }
    try await Task.sleep(for: .milliseconds(20))
    precondition(network.calls == 1 && network.maximumActive == 1,
      "An ordinary read without a prefetch also owns the same-scope in-flight request")
    network.finish(1, scope: scope)
    _ = try await first.value; _ = try await second.value
    let fresh = Task { try await reader.read(scope) { try await network.fetch(scope) } }
    try await waitFor { network.calls == 2 }
    network.finish(2, scope: scope)
    _ = try await fresh.value
    precondition(network.calls == 2, "Consumption ends reuse; a later read gets fresh evidence")
  }

  @MainActor private static func staleCompletionKeepsNewOwner(
    _ a: DesktopSuggestionScope, _ b: DesktopSuggestionScope, oldFails: Bool = false,
    duringFallback: Bool = false
  ) async throws {
    let reader = DesktopSuggestionReadAhead(), network = ControlledReadAhead()
    var oldReady = 0, newReady = 0, fallbacks = 0
    reader.prepare(a, onReady: { _, _ in oldReady += 1 }) { try await network.fetch(a) }
    try await waitFor { network.calls == 1 }
    let obsolete = Task {
      network.startedRead = true
      return try await reader.read(a) { fallbacks += 1; return try await network.fetch(a) }
    }
    try await waitFor { network.startedRead }
    var oldCall = 1
    if duringFallback {
      network.fail(1)
      try await waitFor { network.calls == 2 }
      oldCall = 2
    }
    let bCall = oldCall + 1, currentCall = oldCall + 2
    reader.prepare(b, onReady: { _, _ in oldReady += 1 }) { try await network.fetch(b) }
    try await waitFor { network.calls == bCall }
    reader.prepare(a, onReady: { _, _ in newReady += 1 }) { try await network.fetch(a) }
    try await waitFor { network.calls == currentCall }
    // These synthetic transports deliberately ignore cancellation until released.
    if oldFails { network.fail(oldCall) } else { network.finish(oldCall, scope: a) }
    network.finish(bCall, scope: b)
    do { _ = try await obsolete.value; preconditionFailure("Superseded read must be cancelled") }
    catch is CancellationError {} catch { preconditionFailure("Unexpected stale-read error") }
    reader.prepare(a) { try await network.fetch(a) }
    try await Task.sleep(for: .milliseconds(20))
    precondition(network.calls == currentCall && oldReady == 0 && reader.completed(for: a) == nil,
      "Old completion cannot publish, fall back, or release the new generation of the same scope")
    let current = Task { try await reader.read(a) { fallbacks += 1; return try await network.fetch(a) } }
    network.finish(currentCall, scope: a)
    let result = try await current.value
    precondition(result.matches(a) && newReady == 1 && fallbacks == (duringFallback ? 1 : 0) && network.active == 0)
  }

  @MainActor private static func cancellationDuringFallback(_ scope: DesktopSuggestionScope) async throws {
    let reader = DesktopSuggestionReadAhead(), network = ControlledReadAhead()
    var fallbacks = 0
    reader.prepare(scope) { try await network.fetch(scope) }
    try await waitFor { network.calls == 1 }
    let read = Task { try await reader.read(scope) { fallbacks += 1; return try await network.fetch(scope) } }
    network.fail(1)
    try await waitFor { network.calls == 2 }
    read.cancel()
    reader.prepare(scope) { try await network.fetch(scope) }
    try await Task.sleep(for: .milliseconds(20))
    precondition(network.calls == 2, "Cancellation cannot release an unsettled fallback for another prepare")
    network.finish(2, scope: scope)
    do { _ = try await read.value; preconditionFailure("Cancelled fallback cannot deliver") }
    catch is CancellationError {} catch { preconditionFailure("Unexpected cancelled-fallback error") }
    precondition(fallbacks == 1 && reader.completed(for: scope) == nil && network.active == 0)

    reader.prepare(scope) { throw URLError(.cancelled) }
    do {
      _ = try await reader.read(scope) { fallbacks += 1; return try await network.fetch(scope) }
      preconditionFailure("Transport cancellation cannot become a success")
    } catch { precondition((error as? URLError)?.code == .cancelled) }
    precondition(fallbacks == 1 && network.calls == 2, "Transport cancellation is not a retryable prefetch failure")
  }

  @MainActor private static func cancelledConsumerDoesNotReplaySharedRead(
    _ scope: DesktopSuggestionScope, duringFallback: Bool = false
  ) async throws {
    let reader = DesktopSuggestionReadAhead(), network = ControlledReadAhead()
    var started = 0, fallbacks = 0
    reader.prepare(scope) { try await network.fetch(scope) }
    try await waitFor { network.calls == 1 }
    func consume() async throws -> DesktopSuggestionResponse {
      started += 1
      return try await reader.read(scope) { fallbacks += 1; return try await network.fetch(scope) }
    }
    let first = Task { try await consume() }, second = Task { try await consume() }
    try await waitFor { started == 2 }
    var sharedCall = 1
    if duringFallback {
      network.fail(1)
      try await waitFor { network.calls == 2 }
      sharedCall = 2
    }
    first.cancel()
    reader.prepare(scope) { try await network.fetch(scope) }
    let shared = nativeValue("shared consumer response")
    network.finish(sharedCall, scope: scope, suggestion: shared)
    do { _ = try await first.value; preconditionFailure("Cancelled consumer cannot deliver") }
    catch is CancellationError {} catch { preconditionFailure("Unexpected shared cancellation error") }
    do { let response = try await second.value; precondition(response.matches(scope) && response.suggestion == shared) }
    catch { preconditionFailure("Cancelling one consumer must not cancel the other same-scope consumer") }
    precondition(network.calls == sharedCall && fallbacks == (duringFallback ? 1 : 0) && network.active == 0,
      "The uncancelled consumer receives the shared response without another request or fallback")

    let fresh = Task { try await reader.read(scope) { try await network.fetch(scope) } }
    try await waitFor { network.calls == sharedCall + 1 }
    network.finish(sharedCall + 1, scope: scope)
    _ = try await fresh.value
    precondition(network.calls == sharedCall + 1, "A later read after consumption must obtain fresh HTTP evidence")
  }

  @MainActor private static func allConsumersCancel(_ scope: DesktopSuggestionScope, duringFallback: Bool) async throws {
    let reader = DesktopSuggestionReadAhead(), network = ControlledReadAhead()
    var started = 0, ready = 0, fallbacks = 0
    reader.prepare(scope, onReady: { _, _ in ready += 1 }) { try await network.fetch(scope) }
    try await waitFor { network.calls == 1 }
    func consume() async throws -> DesktopSuggestionResponse {
      started += 1
      return try await reader.read(scope) { fallbacks += 1; return try await network.fetch(scope) }
    }
    let first = Task { try await consume() }, second = Task { try await consume() }
    try await waitFor { started == 2 }
    let call = duringFallback ? 2 : 1
    if duringFallback { network.fail(1); try await waitFor { network.calls == 2 } }
    first.cancel(); second.cancel()
    reader.prepare(scope) { try await network.fetch(scope) }
    do {
      _ = try await reader.read(scope) { fallbacks += 1; return try await network.fetch(scope) }
      preconditionFailure("An abandoned unsettled flight must not be rejoined or replaced")
    } catch is CancellationError {} catch { preconditionFailure("Unexpected abandoned-flight error") }
    // The synthetic transport ignores cancellation; release a nonempty late result.
    network.finish(call, scope: scope, suggestion: nativeValue("abandoned response"))
    for task in [first, second] {
      do { _ = try await task.value; preconditionFailure("An abandoned consumer cannot deliver") }
      catch is CancellationError {} catch { preconditionFailure("Unexpected abandoned-consumer error") }
    }
    precondition(network.calls == call && network.active == 0 && ready == 0
      && fallbacks == (duringFallback ? 1 : 0) && reader.completed(for: scope) == nil,
      "All-consumer cancellation prevents publication, duplicate prepare, and fallback replay")
  }

  @MainActor private static func expiredCompletedPrefetch(_ base: DesktopSuggestionScope, prepareAgain: Bool) async throws {
    var scope = base; scope.requiresNativeSource = true
    let reader = DesktopSuggestionReadAhead(), live = DesktopSuggestionReader(), network = ControlledReadAhead()
    var completed = 0, fallbacks = 0, viewFinished = false
    reader.prepare(scope, onReady: { expected, response in
      completed += 1; live.seed(response, for: expected)
    }) { try await network.fetch(scope) }
    try await waitFor { network.calls == 1 }
    network.finish(1, scope: scope, suggestion: nativeValue("expired unconsumed native", expires: Date().addingTimeInterval(-1)))
    try await waitFor { completed == 1 }
    precondition(live.suggestion == nil, "An expired prefetch must never seed the card")
    if prepareAgain { reader.prepare(scope) { try await network.fetch(scope) } }
    let view = Task {
      await live.read(scope) { try await reader.read(scope) { fallbacks += 1; return try await network.fetch(scope) } }
      viewFinished = true
    }
    try await waitFor { network.calls == 2 || viewFinished }
    precondition(network.calls == 2,
      "An unconsumed expired native prefetch must obtain fresh HTTP evidence instead of stopping native refresh")
    let fresh = nativeValue("fresh after long active overlay")
    network.finish(2, scope: scope, suggestion: fresh)
    try await waitFor { live.visibleSuggestion(for: scope) == fresh }
    precondition(live.suggestion?.expiresAt == fresh.expiresAt && network.calls == 2
      && fallbacks == (prepareAgain ? 0 : 1), "Only the fresh response's original lease may be displayed")
    view.cancel(); reader.cancel(); await view.value; live.clear()
  }

  @MainActor private static func malformedExpiredPrefetchIsNotRetried(_ base: DesktopSuggestionScope) async throws {
    var scope = base; scope.requiresNativeSource = true
    let valid = nativeValue("expired fixture", expires: Date().addingTimeInterval(-1))
    let badHash = DesktopSuggestion(id: valid.id, text: valid.text, contentSha256: String(repeating: "0", count: 64),
      expiresAt: valid.expiresAt, validity: valid.validity)
    for (value, source) in [(badHash, "native-trace"), (valid, "desktop-display")] {
      let reader = DesktopSuggestionReadAhead()
      var ready = false, fallbacks = 0
      let invalid = DesktopSuggestionResponse(schemaVersion: 1, target: scope.target, threadId: scope.threadId,
        sourceTurnId: scope.sourceTurnId, readOnly: true, suggestion: value, source: source)
      reader.prepare(scope, onReady: { _, _ in ready = true }) { invalid }
      try await waitFor { ready }
      let result = try await reader.read(scope) { fallbacks += 1; return invalid }
      precondition(fallbacks == 0 && (!result.matches(scope) || result.suggestion?.isValid(at: Date()) != true),
        "Expiry handling cannot filter bad hash/source evidence into a retry")
      let live = DesktopSuggestionReader()
      live.seed(result, for: scope)
      precondition(live.suggestion == nil)
      reader.cancel()
    }
  }

  @MainActor private static func overlayFencesLateResponse(_ scope: DesktopSuggestionScope) async throws {
    let reader = DesktopSuggestionReadAhead(), live = DesktopSuggestionReader(), network = ControlledReadAhead()
    var ready = 0, fallbacks = 0
    let value = nativeValue("late overlay response")
    reader.prepare(scope, onReady: { expected, response in
      ready += 1; live.seed(response, for: expected)
    }) { try await network.fetch(scope) }
    try await waitFor { network.calls == 1 }
    let view = Task {
      await live.read(scope) {
        network.startedRead = true
        return try await reader.read(scope) { fallbacks += 1; return try await network.fetch(scope) }
      }
    }
    try await waitFor { network.startedRead }
    live.suspend(keeping: scope)
    view.cancel()
    reader.prepare(scope) { try await network.fetch(scope) }
    try await Task.sleep(for: .milliseconds(20))
    precondition(network.calls == 1 && network.active == 1,
      "Overlay cancellation must not open a history prepare gap while the transport is unsettled")
    network.finish(1, scope: scope, suggestion: value)
    await view.value
    precondition(ready == 0 && fallbacks == 0 && live.suggestion == nil && reader.completed(for: scope) == nil,
      "An overlay-cancelled nonempty response cannot publish, seed, or start a fallback")

    // Dismissing the overlay may start fresh evidence; the old completion did not cache a lease.
    reader.prepare(scope, onReady: { expected, response in live.seed(response, for: expected) }) {
      try await network.fetch(scope)
    }
    try await waitFor { network.calls == 2 }
    let fresh = nativeValue("fresh foreground response")
    network.finish(2, scope: scope, suggestion: fresh)
    try await waitFor { live.visibleSuggestion(for: scope) != nil }
    live.suspend(keeping: scope)
    precondition(live.visibleSuggestion(for: scope)?.expiresAt == fresh.expiresAt,
      "A temporary overlay retains only the original confirmed lease without renewing it")
    reader.cancel(); live.clear()
  }

  @MainActor private static func backgroundCancellationDoesNotReleaseUpstream(_ scope: DesktopSuggestionScope) async throws {
    let reader = DesktopSuggestionReadAhead(), live = DesktopSuggestionReader(), network = ProxyReadAhead()
    var ready = 0, fallbacks = 0
    reader.prepare(scope, onReady: { expected, response in
      ready += 1; live.seed(response, for: expected)
    }) { try await network.fetch() }
    try await waitFor { network.calls == 1 }
    let view = Task {
      await live.read(scope) { try await reader.read(scope) { fallbacks += 1; return try await network.fetch() } }
    }
    try await waitFor { live.scope == scope }
    // SessionStore's background path cancels read-ahead; the view also suspends its reader.
    reader.cancel(); live.suspend(keeping: scope); view.cancel()
    await view.value
    precondition(network.cancelled == 1 && network.upstream == [1] && ready == 0,
      "Local cancellation can finish with the upstream still active; it proves no Host idleness")

    do {
      _ = try await reader.read(scope) { try await network.fetch() }
      preconditionFailure("The synthetic Host must remain busy until its original read ends")
    } catch { precondition((error as? URLError)?.code == .resourceUnavailable) }
    precondition(network.calls == 2 && network.busy == 1 && network.upstream == [1] && fallbacks == 0,
      "A foreground read returns Host busy without queuing, retrying, or assuming cancellation stopped upstream")
    reader.prepare(scope, onReady: { expected, response in
      ready += 1; live.seed(response, for: expected)
    }) { try await network.fetch() }
    network.finish(1, scope: scope, suggestion: nativeValue("old background response"))
    precondition(live.suggestion == nil && ready == 0 && reader.completed(for: scope) == nil,
      "A late upstream result after local cancellation has no publication authority")
    try await waitFor { network.calls == 3 }
    reader.prepare(scope) { try await network.fetch() }
    let current = Task { try await reader.read(scope) { fallbacks += 1; return try await network.fetch() } }
    let fresh = nativeValue("new foreground response")
    network.finish(3, scope: scope, suggestion: fresh)
    let response = try await current.value
    precondition(network.calls == 3 && network.upstream.isEmpty && network.busy == 1
      && ready == 1 && fallbacks == 0 && response.suggestion == fresh && live.visibleSuggestion(for: scope) == fresh,
      "Only the new foreground owner may publish after the original upstream actually completes")
    reader.cancel(); live.clear()
  }

  @MainActor static func main() async throws {
    let target = AgentRuntimeTarget(agentId: "codex", hostId: "host", workspaceId: "workspace", runtimeId: "runtime")
    let a = DesktopSuggestionScope(target: target, threadId: "a", sourceTurnId: "turn-a", navigation: "first", connection: "paired")
    let b = DesktopSuggestionScope(target: target, threadId: "a", sourceTurnId: "turn-b", navigation: "second", connection: "paired")
    if CommandLine.arguments.contains("--fallback-gap") {
      try await directReadOwnsFlight(a)
      print("Ordinary read/history prepare single-flight check passed")
      return
    }
    if CommandLine.arguments.contains("--shared-cancel") {
      try await cancelledConsumerDoesNotReplaySharedRead(a)
      try await cancelledConsumerDoesNotReplaySharedRead(a, duringFallback: true)
      print("Independent shared consumer cancellation check passed")
      return
    }
    if CommandLine.arguments.contains("--expired-prefetch") {
      try await expiredCompletedPrefetch(a, prepareAgain: true)
      try await expiredCompletedPrefetch(a, prepareAgain: false)
      print("Expired unconsumed prefetch fresh-evidence checks passed")
      return
    }
    try await historyRefreshDuringRead(a)
    try await sharedFailureFallback(a)
    try await directReadOwnsFlight(a)
    try await staleCompletionKeepsNewOwner(a, b)
    try await staleCompletionKeepsNewOwner(a, b, oldFails: true)
    try await staleCompletionKeepsNewOwner(a, b, duringFallback: true)
    try await staleCompletionKeepsNewOwner(a, b, oldFails: true, duringFallback: true)
    try await cancellationDuringFallback(a)
    try await cancelledConsumerDoesNotReplaySharedRead(a)
    try await cancelledConsumerDoesNotReplaySharedRead(a, duringFallback: true)
    try await allConsumersCancel(a, duringFallback: false)
    try await allConsumersCancel(a, duringFallback: true)
    try await expiredCompletedPrefetch(a, prepareAgain: true)
    try await expiredCompletedPrefetch(a, prepareAgain: false)
    try await malformedExpiredPrefetchIsNotRetried(a)
    try await overlayFencesLateResponse(a)
    try await backgroundCancellationDoesNotReleaseUpstream(a)
    let reader = DesktopSuggestionReadAhead(), probe = ReadAheadProbe()
    reader.prepare(a) { try await probe.fetch(a) }
    try await Task.sleep(for: .milliseconds(20))
    precondition(probe.calls == 1, "Read must start while history is still loading")
    var fallbacks = 0
    let confirmed = try await reader.read(a) { fallbacks += 1; return try await probe.fetch(a) }
    precondition(confirmed.matches(a) && fallbacks == 0 && probe.calls == 1, "Fresh matching history consumes the single request")

    reader.prepare(a) { try await probe.fetch(a) }
    try await Task.sleep(for: .milliseconds(20))
    let changed = try await reader.read(b) { fallbacks += 1; return try await probe.fetch(b) }
    precondition(changed.matches(b) && fallbacks == 1 && probe.cancelled == 1, "New source/navigation cancels the candidate and reads its own source")

    reader.prepare(a) { throw URLError(.networkConnectionLost) }
    let recovered = try await reader.read(a) { fallbacks += 1; return try await probe.fetch(a) }
    precondition(recovered.matches(a) && fallbacks == 2, "Failed optimization retains the ordinary read path")

    reader.prepare(a) { try await probe.fetch(a) }
    let cancelled = Task { try await reader.read(a) { fallbacks += 1; return try await probe.fetch(a) } }
    try await Task.sleep(for: .milliseconds(20))
    cancelled.cancel()
    do { _ = try await cancelled.value; preconditionFailure("Cancelled caller must not receive a response") }
    catch is CancellationError {} catch { preconditionFailure("Unexpected cancellation error: \(error)") }
    precondition(probe.cancelled == 2 && fallbacks == 2, "Cancellation reaches the request and never triggers a fallback")

    reader.prepare(a) { try await probe.fetch(a) }
    try await Task.sleep(for: .milliseconds(20))
    reader.cancel()
    try await Task.sleep(for: .milliseconds(20))
    precondition(probe.cancelled == 3, "Leaving the navigation cancels outstanding work")
    let live = DesktopSuggestionReader()
    let text = "existing native suggestion"
    let value = DesktopSuggestion(id: "native", text: text,
      contentSha256: SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined(),
      expiresAt: ISO8601DateFormatter().string(from: Date().addingTimeInterval(30)))
    let response = DesktopSuggestionResponse(schemaVersion: 1, target: a.target, threadId: a.threadId,
      sourceTurnId: a.sourceTurnId, readOnly: true, suggestion: value)
    reader.prepare(a, onReady: { scope, result in
      if probe.confirmedScope == scope { live.seed(result, for: scope) }
    }) { response }
    try await Task.sleep(for: .milliseconds(20))
    precondition(live.suggestion == nil, "A candidate response cannot display before fresh history confirms it")
    probe.confirmedScope = a
    live.seed(reader.completed(for: a)!, for: a)
    precondition(live.visibleSuggestion(for: a) == value, "A confirmed completed response publishes without waiting for a view task")
    let resumed = Task { await live.read(a) { fallbacks += 1; return response } }
    try await Task.sleep(for: .milliseconds(20))
    precondition(fallbacks == 2, "The later view task must reuse the confirmed value")
    resumed.cancel(); await resumed.value
    let obsoleteView = Task { await live.read(nil) { response } }
    obsoleteView.cancel(); await obsoleteView.value
    precondition(live.visibleSuggestion(for: a) == value, "A cancelled old nil-scope view task cannot clear the confirmed suggestion")
    live.clear()
    live.seed(response, for: b)
    precondition(live.suggestion == nil, "An old response cannot seed a new source/navigation")
    reader.cancel()
    print("Suggestion read-ahead checks passed: same-scope history single flight, shared/direct fallback, no fallback replay, stale success/error fencing, independent/all-consumer cancellation, expired prefetch fresh evidence, overlay/background late results, upstream busy after local cancellation, exact source/navigation, confirmed publication and view-task reuse")
  }
}
