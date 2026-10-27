import Combine
import CryptoKit
import Foundation

/// A read scope never contains or changes a draft or submitted operation.
struct DesktopSuggestionScope: Equatable, Sendable {
  let target: AgentRuntimeTarget
  let threadId: String
  let sourceTurnId: String
  let navigation: String
  let connection: String
  // Production display reads must not silently fall back to legacy trace output.
  var requiresDisplayedSource: Bool = false
  var requiresNativeSource: Bool = false
  var requiresRuntimeSource: Bool = false
}

struct DesktopSuggestion: Decodable, Equatable, Sendable {
  let id: String
  let text: String
  let contentSha256: String
  let expiresAt: String
  var validity: String? = nil

  var isDisplayBound: Bool { validity == "desktop-display" }
  var isNativeBound: Bool { validity == "native-version" }
  var isRuntimeBound: Bool { validity == "runtime-version" }
  var isRefreshBound: Bool { isDisplayBound || isNativeBound || isRuntimeBound }

  var isSourceBound: Bool { validity == "source-turn" }

  static func == (lhs: Self, rhs: Self) -> Bool {
    lhs.id == rhs.id && lhs.text == rhs.text && lhs.contentSha256 == rhs.contentSha256
      && lhs.validity == rhs.validity
      && ((lhs.isRefreshBound && rhs.isRefreshBound) || lhs.expiresAt == rhs.expiresAt)
  }

  var expiration: Date? {
    let parser = ISO8601DateFormatter()
    parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = parser.date(from: expiresAt) { return date }
    parser.formatOptions = [.withInternetDateTime]
    return parser.date(from: expiresAt)
  }

  func isValid(at now: Date) -> Bool {
    guard !id.isEmpty, id.utf8.count <= 256, !text.isEmpty, text.utf8.count <= 64_000,
      validity == nil || isSourceBound || isRefreshBound,
      isSourceBound || (expiration.map { $0 > now } ?? false) else { return false }
    let digest = SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    return contentSha256 == digest
  }
}

struct DesktopSuggestionResponse: Decodable, Sendable {
  let schemaVersion: Int
  let target: AgentRuntimeTarget
  let threadId: String
  let sourceTurnId: String
  let readOnly: Bool
  let suggestion: DesktopSuggestion?
  var source: String? = nil

  func matches(_ scope: DesktopSuggestionScope) -> Bool {
    schemaVersion == 1 && readOnly && target == scope.target
      && threadId == scope.threadId && sourceTurnId == scope.sourceTurnId
      && (source == nil || source == "desktop-display" || source == "native-trace" || source == "runtime")
      && (!scope.requiresDisplayedSource || source == "desktop-display")
      && (!scope.requiresNativeSource || source == "native-trace")
      && (!scope.requiresRuntimeSource || source == "runtime")
      && (source != "runtime" || scope.target.agentId == "easywork")
      && (source != "runtime" || suggestion == nil || suggestion?.isRuntimeBound == true)
      && (suggestion?.isRuntimeBound != true || source == "runtime")
      && (source != "native-trace" || suggestion == nil || suggestion?.isNativeBound == true)
      && (suggestion?.isNativeBound != true || source == "native-trace")
      && (source != "desktop-display" || suggestion == nil || suggestion?.isDisplayBound == true)
      && (suggestion?.isDisplayBound != true || source == "desktop-display")
  }
}

/// A preview is an immutable intent, scoped to both navigation and the exact
/// local text revision. It has no transport or submission operation.
struct DesktopSuggestionDraftSnapshot: Equatable, Sendable {
  let targetKey: String
  let text: String
  let revision: UInt64
}

enum DesktopSuggestionAdoptionMode { case fill, append, replace }

struct DesktopSuggestionDraftAction: Identifiable, Equatable, Sendable {
  let id = UUID()
  let scope: DesktopSuggestionScope
  let suggestion: DesktopSuggestion
  let draft: DesktopSuggestionDraftSnapshot

  func proposedText(mode: DesktopSuggestionAdoptionMode, currentScope: DesktopSuggestionScope?,
    currentSuggestion: DesktopSuggestion?, currentDraft: DesktopSuggestionDraftSnapshot,
    at now: Date = Date()
  ) -> String? {
    guard currentScope == scope, currentSuggestion == suggestion,
      currentSuggestion?.isValid(at: now) == true, currentDraft == draft else { return nil }
    switch mode {
    case .fill: return draft.text.isEmpty ? suggestion.text : nil
    case .append: return draft.text.isEmpty ? suggestion.text : draft.text + "\n\n" + suggestion.text
    case .replace: return suggestion.text
    }
  }
}

struct DesktopSuggestionDraftUndo: Equatable {
  let scope: DesktopSuggestionScope
  let suggestionId: String
  let originalText: String
  let applied: DesktopSuggestionDraftSnapshot

  // Once adopted, this is local text. Reader availability, prediction expiry
  // and view recreation must not discard undo for the unchanged draft.
  func restoredText(currentDraft: DesktopSuggestionDraftSnapshot) -> String? {
    guard currentDraft == applied else { return nil }
    return originalText
  }
}

/// Start one read beside history loading, but consume it only after history
/// confirms the exact same source and navigation. No persisted hint is shown.
@MainActor
final class DesktopSuggestionReadAhead {
  // The cancellation handler can run off MainActor. Publish its flag before
  // scheduling cleanup, so a simultaneous completion cannot revive an abandoned read.
  private final class Consumer: @unchecked Sendable {
    let id = UUID()
    private let lock = NSLock()
    private var cancelled = false
    var isCancelled: Bool {
      lock.lock(); defer { lock.unlock() }
      return cancelled
    }
    func cancel() { lock.lock(); cancelled = true; lock.unlock() }
  }

  @MainActor
  private final class Pending {
    let id: UUID
    let scope: DesktopSuggestionScope
    let task: Task<DesktopSuggestionResponse, Error>
    let allowsFallback: Bool
    var fallback: Task<DesktopSuggestionResponse, Error>?
    var received: DesktopSuggestionResponse?
    var readers: [UUID: Consumer] = [:]
    var wasRead = false
    var abandoned: Bool { wasRead && !readers.values.contains { !$0.isCancelled } }

    init(id: UUID, scope: DesktopSuggestionScope, task: Task<DesktopSuggestionResponse, Error>,
      allowsFallback: Bool
    ) {
      self.id = id; self.scope = scope; self.task = task; self.allowsFallback = allowsFallback
    }
  }
  private var pending: Pending?

  func cancel() {
    pending?.task.cancel()
    pending?.fallback?.cancel()
    pending = nil
  }

  func prepare(_ scope: DesktopSuggestionScope?,
    onReady: @escaping @MainActor @Sendable (DesktopSuggestionScope, DesktopSuggestionResponse) -> Void = { _, _ in },
    fetch: @escaping @MainActor @Sendable () async throws -> DesktopSuggestionResponse
  ) {
    if let scope, let current = pending, current.scope == scope {
      cancelIfUnobserved(current)
      if !current.readers.isEmpty || !hasExpiredResponse(current) { return }
    }
    cancel()
    guard let scope else { return }
    start(scope, allowsFallback: true, onReady: onReady, fetch: fetch)
  }

  @discardableResult private func start(_ scope: DesktopSuggestionScope, allowsFallback: Bool,
    onReady: (@MainActor @Sendable (DesktopSuggestionScope, DesktopSuggestionResponse) -> Void)? = nil,
    fetch: @escaping @MainActor @Sendable () async throws -> DesktopSuggestionResponse
  ) -> Pending {
    let id = UUID()
    let value = Pending(id: id, scope: scope,
      task: makeTask(id, scope: scope, onReady: onReady, fetch: fetch),
      allowsFallback: allowsFallback)
    pending = value
    return value
  }

  private func makeTask(_ id: UUID, scope: DesktopSuggestionScope,
    onReady: (@MainActor @Sendable (DesktopSuggestionScope, DesktopSuggestionResponse) -> Void)? = nil,
    fetch: @escaping @MainActor @Sendable () async throws -> DesktopSuggestionResponse
  ) -> Task<DesktopSuggestionResponse, Error> {
    Task { [weak self] in
      try Task.checkCancellation()
      guard self?.isCurrent(id) == true else { throw CancellationError() }
      #if DEBUG
      if onReady != nil { DesktopSuggestionTiming.mark("readAheadRequest", navigation: scope.navigation) }
      #endif
      let response = try await fetch()
      #if DEBUG
      if onReady != nil { DesktopSuggestionTiming.mark("readAheadResponse", navigation: scope.navigation) }
      #endif
      try Task.checkCancellation()
      guard let self, self.isCurrent(id) else { throw CancellationError() }
      self.pending?.received = response
      onReady?(scope, response)
      return response
    }
  }

  private func isCurrent(_ id: UUID) -> Bool {
    guard let value = pending else { return false }
    return value.id == id && !value.abandoned
  }

  private func cancelIfUnobserved(_ value: Pending) {
    guard value.abandoned else { return }
    value.task.cancel(); value.fallback?.cancel()
  }

  private func hasExpiredResponse(_ value: Pending) -> Bool {
    guard let response = value.received, response.matches(value.scope),
      let suggestion = response.suggestion, suggestion.isRefreshBound,
      let expiry = suggestion.expiration, expiry <= Date() else { return false }
    // Classify expiry only after the original source/hash/shape checks. This
    // validates at a past instant; it does not renew or publish the old lease.
    return suggestion.isValid(at: expiry.addingTimeInterval(-1))
  }

  func completed(for scope: DesktopSuggestionScope) -> DesktopSuggestionResponse? {
    guard let value = pending, value.scope == scope, !value.abandoned,
      !hasExpiredResponse(value) else { return nil }
    return value.received
  }

  private func awaitValue(_ task: Task<DesktopSuggestionResponse, Error>, owner: Pending, consumer: Consumer) async throws
    -> DesktopSuggestionResponse {
    let response: DesktopSuggestionResponse
    do {
      response = try await withTaskCancellationHandler {
        try Task.checkCancellation()
        return try await task.value
      } onCancel: {
        consumer.cancel()
        Task { @MainActor [weak self] in self?.cancelIfUnobserved(owner) }
      }
    } catch {
      try Task.checkCancellation()
      guard !task.isCancelled, isCurrent(owner.id) else { throw CancellationError() }
      throw error
    }
    try Task.checkCancellation()
    guard !task.isCancelled, isCurrent(owner.id) else { throw CancellationError() }
    return response
  }

  func read(_ scope: DesktopSuggestionScope,
    fallback: @escaping @MainActor @Sendable () async throws -> DesktopSuggestionResponse
  ) async throws -> DesktopSuggestionResponse {
    try Task.checkCancellation()
    let value: Pending
    if let current = pending, current.scope == scope,
      !current.readers.isEmpty || !hasExpiredResponse(current) {
      guard !current.abandoned else { cancelIfUnobserved(current); throw CancellationError() }
      value = current
    }
    else {
      cancel()
      value = start(scope, allowsFallback: false, fetch: fallback)
    }
    let consumer = Consumer()
    value.wasRead = true
    value.readers[consumer.id] = consumer
    // Keep ownership through the await (and a possible fallback), so a history
    // refresh or another reader joins this request instead of starting HTTP again.
    defer {
      if Task.isCancelled { consumer.cancel() }
      value.readers.removeValue(forKey: consumer.id)
      cancelIfUnobserved(value)
      if pending?.id == value.id, value.readers.isEmpty { pending = nil }
    }
    do {
      return try await awaitValue(value.task, owner: value, consumer: consumer)
    } catch {
      try Task.checkCancellation()
      if error is CancellationError || (error as? URLError)?.code == .cancelled { throw error }
      guard isCurrent(value.id), !value.task.isCancelled else { throw CancellationError() }
      guard value.allowsFallback else { throw error }
      // All consumers of a failed prefetch share one ordinary read. A fallback
      // failure is returned without retry, and a superseded owner cannot start it.
      if value.fallback == nil {
        value.fallback = makeTask(value.id, scope: scope, fetch: fallback)
      }
      return try await awaitValue(value.fallback!, owner: value, consumer: consumer)
    }
  }
}

/// Only the visible chat is read. A generation fences responses even if a
/// provider completes after cancellation or navigation returns to the same chat.
@MainActor
final class DesktopSuggestionReader: ObservableObject {
  @Published private(set) var suggestion: DesktopSuggestion? {
    didSet {
      #if DEBUG
      if oldValue != suggestion { DesktopSuggestionDiagnostics.record("reader-value", visible: suggestion != nil) }
      #endif
    }
  }
  private(set) var scope: DesktopSuggestionScope?
  private var generation = UUID()
  private var refreshingReadGeneration: UUID?
  private var suspendedExpiration: Task<Void, Never>?

  /// The store calls this only after fresh history confirms this exact scope.
  /// Publish without waiting for SwiftUI to schedule the reader's view task.
  func seed(_ response: DesktopSuggestionResponse, for expected: DesktopSuggestionScope) {
    guard response.matches(expected), let value = response.suggestion, value.isValid(at: Date()) else { return }
    if (response.source == "desktop-display" || response.source == "native-trace" || response.source == "runtime"), scope == expected, refreshingReadGeneration == generation { return }
    guard scope != expected || suggestion != value || value.isRefreshBound else { return }
    scope = expected
    suggestion = value
    suspend(keeping: expected)
    #if DEBUG
    DesktopSuggestionTiming.mark("suggestionPublished", navigation: expected.navigation)
    #endif
  }

  func clear(reason: String = "invalidated") {
    #if DEBUG
    if suggestion != nil { DesktopSuggestionDiagnostics.record(reason, visible: false) }
    #endif
    suspendedExpiration?.cancel(); suspendedExpiration = nil
    generation = UUID()
    scope = nil
    suggestion = nil
  }

  /// A temporary overlay may stop reads without invalidating a confirmed source.
  /// Fence late responses, while retaining only the same unexpired suggestion.
  func suspend(keeping expected: DesktopSuggestionScope?) {
    guard let expected, expected == scope else { clear(reason: "suspend-mismatch"); return }
    suspendedExpiration?.cancel(); suspendedExpiration = nil
    generation = UUID()
    guard let value = suggestion, value.isValid(at: Date()) else {
      suggestion = nil; return
    }
    guard !value.isSourceBound, let expiry = value.expiration else { return }
    let current = generation
    suspendedExpiration = Task { [weak self] in
      do { try await Task.sleep(for: .seconds(max(0, expiry.timeIntervalSinceNow))) }
      catch { return }
      guard let self, self.generation == current, self.scope == expected else { return }
      #if DEBUG
      DesktopSuggestionDiagnostics.record("expired", visible: false)
      #endif
      self.suggestion = nil
      self.suspendedExpiration = nil
    }
  }

  func visibleSuggestion(for expected: DesktopSuggestionScope?) -> DesktopSuggestion? {
    guard let expected, expected == scope, let suggestion,
      suggestion.isValid(at: Date()) else { return nil }
    return suggestion
  }

  func read(_ next: DesktopSuggestionScope?,
    fetch: @escaping @MainActor @Sendable () async throws -> DesktopSuggestionResponse
  ) async {
    guard !Task.isCancelled else { return }
    guard let next else { clear(reason: "missing-scope"); return }
    suspendedExpiration?.cancel(); suspendedExpiration = nil
    if scope != next { clear(reason: "scope-changed") }
    generation = UUID()
    scope = next
    let current = generation
    if next.requiresDisplayedSource || next.requiresNativeSource || next.requiresRuntimeSource || suggestion?.isRefreshBound == true {
      await readRefreshing(next, generation: current, initial: nil, fetch: fetch)
      return
    }
    if let cached = suggestion, cached.isValid(at: Date()) {
      if !cached.isSourceBound, let expiry = cached.expiration {
        await expire(at: expiry, scope: next, generation: current)
      }
      return
    }
    suggestion = nil
    let deadline = ContinuousClock.now.advanced(by: .seconds(60))
    // Predictions may arrive after the completed-turn snapshot. Retry only for
    // this bounded foreground window, never by requesting a new prediction.
    for attempt in 0..<16 {
      guard !Task.isCancelled, generation == current, scope == next,
        attempt == 0 || ContinuousClock.now < deadline else { return }
      do {
        #if DEBUG
        DesktopSuggestionTiming.mark("suggestionRequest", navigation: next.navigation)
        #endif
        let response = try await fetch()
        #if DEBUG
        DesktopSuggestionTiming.mark("suggestionResponse", navigation: next.navigation)
        #endif
        guard !Task.isCancelled, generation == current, scope == next else { return }
        guard response.matches(next) else { return }
        if response.source == "desktop-display" || response.source == "native-trace" {
          await readRefreshing(next, generation: current, initial: response, fetch: fetch)
          return
        }
        if let value = response.suggestion {
          guard value.isValid(at: Date()) else { return }
          suggestion = value
          #if DEBUG
          DesktopSuggestionTiming.mark("suggestionPublished", navigation: next.navigation)
          #endif
          if !value.isSourceBound, let expiry = value.expiration {
            await expire(at: expiry, scope: next, generation: current)
          }
          return
        }
      } catch {
        if Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled { return }
        // Suggestion failures are local to this card, never a connection failure.
      }
      guard attempt < 15, ContinuousClock.now < deadline else { return }
      do { try await Task.sleep(for: .seconds(attempt < 3 ? 1 : 4)) }
      catch { return }
    }
  }

  /// Desktop may regenerate for the same completed turn. Refresh the actual
  /// source text, including its disappearance, without touching any draft.
  private func readRefreshing(_ expected: DesktopSuggestionScope, generation current: UUID,
    initial: DesktopSuggestionResponse?,
    fetch: @escaping @MainActor @Sendable () async throws -> DesktopSuggestionResponse
  ) async {
    refreshingReadGeneration = current
    defer { if refreshingReadGeneration == current { refreshingReadGeneration = nil } }
    var pending = initial
    while !Task.isCancelled, generation == current, scope == expected {
      do {
        let response: DesktopSuggestionResponse
        if let first = pending { response = first; pending = nil }
        else { response = try await fetch() }
        guard !Task.isCancelled, generation == current, scope == expected else { return }
        guard response.matches(expected), (response.source == "desktop-display" || response.source == "native-trace" || response.source == "runtime") else {
          suggestion = nil; return
        }
        suspendedExpiration?.cancel(); suspendedExpiration = nil
        if let value = response.suggestion {
          guard value.isRefreshBound, value.isValid(at: Date()), let expiry = value.expiration else {
            suggestion = nil; return
          }
          suggestion = value
          suspendedExpiration = Task { [weak self] in
            await self?.expire(at: expiry, scope: expected, generation: current)
          }
        } else { suggestion = nil }
      } catch {
        guard !Task.isCancelled, generation == current, scope == expected else { return }
        suggestion = nil
      }
      do { try await Task.sleep(for: .seconds(2)) }
      catch { return }
    }
  }

  private func expire(at expiry: Date, scope expected: DesktopSuggestionScope, generation current: UUID) async {
    // Only legacy timed responses use a timer; source-bound values are removed
    // when fresh history changes their source or the presentation scope ends.
    do { try await Task.sleep(for: .seconds(max(0, expiry.timeIntervalSinceNow))) }
    catch { return }
    if generation == current, scope == expected {
      #if DEBUG
      DesktopSuggestionDiagnostics.record("expired", visible: false)
      #endif
      suggestion = nil
    }
  }
}

#if DEBUG
/// Bounded state reasons only: no target IDs, text, addresses or credentials.
@MainActor
enum DesktopSuggestionDiagnostics {
  private static var records: [[String: Any]] = []
  private static let writer = DispatchQueue(label: "agent-hub.suggestion-state")

  static func record(_ reason: String, visible: Bool) {
    records.append(["uptime": ProcessInfo.processInfo.systemUptime, "reason": reason, "visible": visible])
    if records.count > 64 { records.removeFirst(records.count - 64) }
    guard let bytes = try? JSONSerialization.data(withJSONObject: records),
      let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return }
    writer.async {
      try? bytes.write(to: root.appendingPathComponent("suggestion-state.json"), options: .atomic)
    }
  }
}

/// Local performance evidence contains durations only, never targets or text.
@MainActor
enum DesktopSuggestionTiming {
  private static var navigation = ""
  private static var started = 0.0
  private static var samples: [[String: Double]] = []
  private static let writer = DispatchQueue(label: "agent-hub.suggestion-timing")

  static func begin(_ value: String) {
    navigation = value
    started = ProcessInfo.processInfo.systemUptime
    samples.append([:])
    if samples.count > 12 { samples.removeFirst() }
  }

  static func mark(_ phase: String, navigation expected: String) {
    guard expected == navigation, started > 0, !samples.isEmpty,
      samples[samples.count - 1][phase] == nil else { return }
    samples[samples.count - 1][phase] = (ProcessInfo.processInfo.systemUptime - started) * 1000
    let report = samples
    writer.async {
      guard let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first,
        let bytes = try? JSONEncoder().encode(report) else { return }
      try? bytes.write(to: root.appendingPathComponent("suggestion-latency.json"), options: .atomic)
    }
  }
}
#endif
