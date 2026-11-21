import XCTest
import Foundation
import Combine
#if canImport(CodexManage_com_easyai_agenthub_easyworkacceptance)
@testable import CodexManage_com_easyai_agenthub_easyworkacceptance
#elseif canImport(CodexManage_com_codexmanage_ios)
@testable import CodexManage_com_codexmanage_ios
#else
#error("Build with the independent EasyWork acceptance bundle or default local compiler target")
#endif

@MainActor private final class UsageTestClock {
  let initial = Date(timeIntervalSince1970: 1793149200)
  var value = Date(timeIntervalSince1970: 1793149200)
}
@MainActor private final class UsageControlledRead {
  var calls: [String] = []
  var pending: [String: [CheckedContinuation<Data, Error>]] = [:]
  var active = 0; var peak = 0
  func read(_ client: Client, _ target: AgentRuntimeTarget) async throws -> Data {
    calls.append(client.id); active += 1; peak = max(peak, active)
    defer { active -= 1 }
    return try await withCheckedThrowingContinuation { pending[client.id, default: []].append($0) }
  }
  func finish(_ id: String, _ bytes: Data, newest: Bool = false) {
    guard pending[id]?.isEmpty == false else { return }
    let continuation = newest ? pending[id]!.removeLast() : pending[id]!.removeFirst()
    continuation.resume(returning: bytes)
  }
  func cancelAll() {
    let continuations = pending.values.flatMap { $0 }; pending.removeAll()
    for continuation in continuations { continuation.resume(throwing: CancellationError()) }
  }
}

/// Calls the App's actual UsageStore and MobileCore policy. Only the read boundary is controlled.
/// These are native auxiliary checks, not real Electron/Provider/phone UI acceptance.
final class EasyWorkUsageStoreNativeTests: XCTestCase {
  @MainActor private func target(_ id: String = "a") -> AgentRuntimeTarget {
    .init(agentId: "codex", hostId: id, workspaceId: "workspace", runtimeId: "runtime", account: nil)
  }
  @MainActor private func client(_ id: String = "a") -> Client {
    .init(id: id, name: "Usage fixture", endpoint: "https://fixture.invalid", isDemo: false, online: true)
  }
  @MainActor private func wire(_ target: AgentRuntimeTarget, amount: String = "7", forbiddenSibling: Bool = false) throws -> Data {
    let metric: [String: Any] = ["id": "balance", "label": "Credits", "kind": "balance",
      "observedAt": "2026-10-28T01:00:00.000Z", "validUntil": "2026-10-28T01:01:00.000Z",
      "appliesTo": ["kind": "all"], "unit": ["kind": "credits", "code": "credit", "label": "credits"],
      "reading": ["state": "amount", "amount": amount, "entitlement": "finite"], "expiresAt": NSNull()]
    var metrics = [metric]
    if forbiddenSibling {
      var denied = metric; denied["id"] = "denied"; denied["reading"] = ["state": "unavailable", "reason": "forbidden"]
      metrics.append(denied)
    }
    return try JSONSerialization.data(withJSONObject: ["schemaVersion": 1,
      "target": ["agentId": target.agentId, "hostId": target.hostId, "workspaceId": target.workspaceId, "runtimeId": target.runtimeId],
      "requestId": "usage-fixture", "status": "ok", "snapshot": ["snapshotId": "sample-" + amount,
        "sampledAt": "2026-10-28T01:00:00.000Z",
        "source": ["adapterId": "codex", "scopeRef": "fixture-wallet", "scopeLabel": "Personal", "planLabel": "Pro"],
        "coverage": forbiddenSibling ? "partial" : "complete", "issues": [],
        "buckets": [["id": "pool", "label": NSNull(), "sharedPoolRef": NSNull(),
          "combination": ["kind": "provider-defined"], "metrics": metrics]]]])
  }
  @MainActor private func until(_ condition: () -> Bool) async throws {
    let deadline = Date().addingTimeInterval(3)
    while !condition() {
      guard Date() < deadline else { throw NSError(domain: "UsageControlledReadTimeout", code: 1) }
      try await Task.sleep(for: .milliseconds(1))
    }
  }
  @MainActor func testF73ForceRespectsFiveSecondMinimum() async throws {
    let clock = UsageTestClock(); var count = 0
    let store = UsageStore(now: { clock.value }, read: { _, target in count += 1; return try self.wire(target) })
    await store.refresh(client(), target: target())
    await store.refresh(client(), target: target(), force: true); XCTAssertEqual(count, 1)
    clock.value = clock.initial.addingTimeInterval(4.999)
    await store.refresh(client(), target: target(), force: true); XCTAssertEqual(count, 1)
    clock.value = clock.initial.addingTimeInterval(5)
    await store.refresh(client(), target: target(), force: true); XCTAssertEqual(count, 2)
  }
  @MainActor func testF73GlobalTwoSlotsAndHiddenQueuedCancellation() async throws {
    let clock = UsageTestClock(); let reads = UsageControlledRead()
    let stores = (0..<3).map { _ in UsageStore(now: { clock.value }, read: reads.read) }
    var entered = 0
    var jobs = (0..<2).map { index in Task { entered += 1; await stores[index].refresh(self.client(String(index)), target: self.target(String(index))) } }
    defer { jobs.forEach { $0.cancel() }; reads.cancelAll() }
    try await until { reads.calls.count == 2 }
    jobs.append(Task { entered += 1; await stores[2].refresh(self.client("2"), target: self.target("2")) })
    try await until { entered == 3 }
    XCTAssertEqual(reads.calls.count, 2); XCTAssertEqual(reads.peak, 2)
    jobs[2].cancel()
    reads.finish("0", try wire(target("0"))); reads.finish("1", try wire(target("1")))
    for job in jobs { await job.value }
    XCTAssertFalse(reads.calls.contains("2"))
    let next = Task { await stores[2].refresh(self.client("3"), target: self.target("3")) }
    defer { next.cancel(); reads.cancelAll() }
    try await until { reads.calls.contains("3") }; reads.finish("3", try wire(target("3"))); await next.value
  }
  @MainActor func testF73OneHiddenConsumerPreservesSharedRead() async throws {
    let clock = UsageTestClock(); let reads = UsageControlledRead()
    let store = UsageStore(now: { clock.value }, read: reads.read); var entered = 0
    let jobs = (0..<3).map { _ in Task { entered += 1; await store.refresh(self.client(), target: self.target()) } }
    defer { jobs.forEach { $0.cancel() }; reads.cancelAll() }
    try await until { reads.calls.count == 1 && entered == 3 }
    jobs[0].cancel(); XCTAssertEqual(reads.active, 1); XCTAssertEqual(reads.calls.count, 1)
    reads.finish("a", try wire(target())); for job in jobs { await job.value }
    XCTAssertEqual(store.value(client(), target: target()).balance, "7")
  }
  @MainActor func testF73CancelledLateCallbackCannotOverwriteNewRead() async throws {
    let clock = UsageTestClock(); let reads = UsageControlledRead()
    let store = UsageStore(now: { clock.value }, read: reads.read)
    let old = Task { await store.refresh(self.client(), target: self.target()) }
    defer { old.cancel(); reads.cancelAll() }
    try await until { reads.calls.count == 1 }; old.cancel(); clock.value = clock.initial.addingTimeInterval(5)
    let current = Task { await store.refresh(self.client(), target: self.target(), force: true) }
    defer { current.cancel(); reads.cancelAll() }
    try await until { reads.calls.count == 2 }
    reads.finish("a", try wire(target(), amount: "9"), newest: true); await current.value
    reads.finish("a", try wire(target(), amount: "1")); await old.value
    XCTAssertEqual(store.value(client(), target: target()).balance, "9")
  }
  @MainActor func testF73HTTP401403AndUnattestedOfflineWithdrawValues() async throws {
    for status in [401, 403, 0] {
      let clock = UsageTestClock(); var fail = false
      let store = UsageStore(now: { clock.value }, read: { _, target in
        if fail { if status == 0 { throw URLError(.notConnectedToInternet) }; throw HostServiceError.rejected(status, "fixture denial") }
        return try self.wire(target)
      })
      await store.refresh(client(), target: target()); XCTAssertEqual(store.value(client(), target: target()).balance, "7")
      fail = true; clock.value = clock.initial.addingTimeInterval(5); await store.refresh(client(), target: target(), force: true)
      let value = store.value(client(), target: target())
      XCTAssertNil(value.balance); XCTAssertNil(value.metrics)
      XCTAssertEqual(value.reason, status == 401 ? "http-unauthenticated" : status == 403 ? "http-forbidden" : "source-unconfirmed")
    }
  }
  @MainActor func testF73PartialSiblingDebtSourceExpiryAndWrongTarget() async throws {
    let clock = UsageTestClock(); var wrong = false
    let exact = "-0.000000000000000000000001"
    let store = UsageStore(now: { clock.value }, read: { _, target in try self.wire(wrong ? self.target("wrong-target") : target, amount: exact, forbiddenSibling: true) })
    await store.refresh(client(), target: target()); let value = store.value(client(), target: target())
    XCTAssertTrue(value.partial == true); XCTAssertTrue(value.balanceDebt == true)
    XCTAssertEqual(value.metrics?.first?.value, exact); XCTAssertEqual(value.metrics?.last?.value, "forbidden")
    XCTAssertEqual(value.source?.scopeLabel, "Personal"); XCTAssertEqual(value.source?.planLabel, "Pro")
    clock.value = clock.initial.addingTimeInterval(120)
    XCTAssertTrue(store.cached(client(), target: target())); XCTAssertEqual(store.value(client(), target: target()).balance, value.balance)
    wrong = true; await store.refresh(client(), target: target(), force: true)
    XCTAssertNil(store.value(client(), target: target()).balance)
    XCTAssertEqual(store.value(client(), target: target()).reason, "wrong-target")
  }
  @MainActor func testF73PairedCodexMetadataRebuildSharesQuotaAndFingerprintIsolates() async throws {
    let clock = UsageTestClock(); let reads = UsageControlledRead()
    var oldTarget = target(); oldTarget.account = AgentAccount(authority: "legacy-display", subject: "old")
    var rebuiltTarget = target(); rebuiltTarget.account = AgentAccount(authority: "legacy-display", subject: "new")
    let candidate = AgentConnectionCandidate(id: "lan", transportId: "lan-wss", endpoint: "https://fixture.invalid", peerFingerprint: String(repeating: "a", count: 64), networkKind: .lan)
    var first = client(); first.codexDirect = CodexDirectConnection(target: oldTarget, candidates: [candidate], fingerprint: String(repeating: "a", count: 64), systemEndpoints: ["https://system.fixture.invalid"])
    var rebuilt = client(); rebuilt.codexDirect = CodexDirectConnection(target: rebuiltTarget, candidates: [candidate], fingerprint: String(repeating: "a", count: 64), systemEndpoints: ["https://system.fixture.invalid"])
    let store = UsageStore(now: { clock.value }, read: reads.read)
    XCTAssertEqual(store.identity(first, target: oldTarget), store.identity(rebuilt, target: rebuiltTarget))
    var entered = 0
    let one = Task { entered += 1; await store.refresh(first, target: oldTarget) }
    let two = Task { entered += 1; await store.refresh(rebuilt, target: rebuiltTarget, force: true) }
    defer { one.cancel(); two.cancel(); reads.cancelAll() }
    try await until { entered == 2 && !reads.calls.isEmpty }; XCTAssertEqual(reads.calls.count, 1)
    reads.finish("a", try wire(target())); await one.value; await two.value
    XCTAssertEqual(store.value(rebuilt, target: rebuiltTarget).balance, "7")
    await store.refresh(rebuilt, target: rebuiltTarget, force: true); XCTAssertEqual(reads.calls.count, 1)
    var repinned = rebuilt; repinned.codexDirect?.fingerprint = String(repeating: "b", count: 64)
    XCTAssertNotEqual(store.identity(rebuilt, target: rebuiltTarget), store.identity(repinned, target: rebuiltTarget))
    XCTAssertNil(store.value(repinned, target: rebuiltTarget).balance)
    let next = Task { await store.refresh(repinned, target: rebuiltTarget, force: true) }
    defer { next.cancel(); reads.cancelAll() }
    try await until { reads.calls.count == 2 }; reads.finish("a", try wire(target(), amount: "9")); await next.value
    XCTAssertEqual(store.value(repinned, target: rebuiltTarget).balance, "9")
    XCTAssertEqual(first.codexDirect?.target.account?.subject, "old")
    XCTAssertEqual(rebuilt.codexDirect?.target.account?.subject, "new")
  }

  @MainActor func testF73SharedLoadingPublishesCreationAndCancellationCompletion() async throws {
    let clock = UsageTestClock(); let reads = UsageControlledRead()
    let store = UsageStore(now: { clock.value }, read: reads.read)
    var publications = 0
    let subscription = store.objectWillChange.sink { publications += 1 }
    let job = Task { await store.refresh(self.client(), target: self.target()) }
    defer { job.cancel(); reads.cancelAll(); subscription.cancel() }
    try await until { reads.calls.count == 1 }
    XCTAssertTrue(store.refreshing(client(), target: target()))
    XCTAssertGreaterThan(publications, 0, "Shared read creation must publish without a quota entry")
    publications = 0
    // Cancel without applying a success/failure quota entry: only pending removal can publish.
    job.cancel(); reads.cancelAll(); await job.value
    XCTAssertFalse(store.refreshing(client(), target: target()))
    XCTAssertGreaterThan(publications, 0, "Shared cancellation completion must publish without an entry update")
    XCTAssertNil(store.value(client(), target: target()).balance)
  }

}
