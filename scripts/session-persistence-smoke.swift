import Foundation

private struct State: Codable { let operation: String; let draft: String }
private struct Page: Codable { let revision: Int; let body: String }
private enum Failure: Error { case refused }
private final class Metrics: @unchecked Sendable {
  private let lock = NSLock()
  private var count = 0
  func encoded() { lock.lock(); count += 1; lock.unlock() }
  var encodes: Int { lock.lock(); defer { lock.unlock() }; return count }
}

@main struct PersistenceSmoke {
  static func wait(_ semaphore: DispatchSemaphore, seconds: Double = 10) {
    precondition(semaphore.wait(timeout: .now() + seconds) == .success, "Persistence boundary timed out")
  }
  static func main() throws {
    let directory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
    let metrics = Metrics()
    let writer = SessionPersistenceWriter<State, Page>(legacyURL: directory.appendingPathComponent("session.json"),
      encodeState: { value in
        if value.operation == "refused" { throw Failure.refused }
        return try JSONEncoder().encode(value)
      }, decodeState: { try JSONDecoder().decode(State.self, from: $0) },
      encodePage: { value in
        metrics.encoded()
        if value.revision < 0 { throw Failure.refused }
        if value.revision == 0 { entered.signal(); release.wait() }
        return try JSONEncoder().encode(value)
      }, decodePage: { try JSONDecoder().decode(Page.self, from: $0) })
    _ = try writer.loadInitialState()
    let alpha = "namespace:host-A:thread", beta = "namespace:host-B:thread"
    let initial = DispatchSemaphore(value: 0)
    writer.write(SessionPersistenceSnapshot(state: State(operation: "prepared", draft: "原始草稿"),
      histories: [alpha: Page(revision: 0, body: String(repeating: "x", count: 104 * 1024 * 1024))])) { result, main in
        precondition(!main); precondition((try? result.get()) != nil); initial.signal()
      }
    wait(initial); wait(entered)
    let critical = DispatchSemaphore(value: 0), start = ProcessInfo.processInfo.systemUptime
    writer.write(SessionPersistenceSnapshot(state: State(operation: "same-stable-id:outcome-unknown", draft: "恢复草稿"), histories: [:])) { result, main in
      precondition(!main); precondition((try? result.get()) != nil); critical.signal()
    }
    wait(critical, seconds: 2)
    let criticalMS = (ProcessInfo.processInfo.systemUptime - start) * 1000
    precondition((try? writer.loadState().operation) == "same-stable-id:outcome-unknown")
    let group = DispatchGroup()
    for revision in 1...80 {
      group.enter()
      writer.write(SessionPersistenceSnapshot(state: State(operation: "same-stable-id:outcome-unknown", draft: "最新草稿 \(revision)"),
        histories: [alpha: Page(revision: revision, body: "latest")])) { result, _ in
          precondition((try? result.get()) != nil); group.leave()
        }
    }
    precondition(group.wait(timeout: .now() + 4) == .success)
    let flushed = DispatchSemaphore(value: 0)
    writer.write(SessionPersistenceSnapshot(state: State(operation: "confirmed", draft: "不覆盖新草稿"),
      histories: [beta: Page(revision: 1, body: "other target")]), flushHistory: true) { result, _ in
        precondition((try? result.get()) != nil); flushed.signal()
      }
    release.signal(); wait(flushed, seconds: 30)
    precondition((try? writer.loadHistory(alpha).revision) == 80)
    precondition((try? writer.loadHistory(beta).body) == "other target")
    precondition(metrics.encodes == 3, "Queued history snapshots were not coalesced")
    let refused = DispatchSemaphore(value: 0)
    writer.write(SessionPersistenceSnapshot(state: State(operation: "refused", draft: "must not authorize a command"),
      histories: [alpha: Page(revision: 999, body: "must not write")])) { result, _ in
        if case .success = result { preconditionFailure("Failed intent write reported success") }
        refused.signal()
      }
    wait(refused)
    precondition((try? writer.loadState().operation) == "confirmed")
    precondition((try? writer.loadHistory(alpha).revision) == 80)
    let pageFailure = DispatchSemaphore(value: 0)
    writer.write(SessionPersistenceSnapshot(state: State(operation: "confirmed", draft: "saved despite cache failure"),
      histories: [alpha: Page(revision: -1, body: "refused")]), flushHistory: true) { result, _ in
        if case .success = result { preconditionFailure("History flush failure was hidden") }
        pageFailure.signal()
      }
    wait(pageFailure)
    precondition((try? writer.loadHistory(alpha).revision) == 80)
    let imported = try JSONEncoder().encode(State(operation: "imported", draft: "migration"))
    try writer.replaceLegacy(imported)
    precondition((try? Data(contentsOf: directory.appendingPathComponent("session.json"))) == imported)
    precondition((try? writer.loadState().operation) == "imported")
    let protected = directory.appendingPathComponent("failed-load", isDirectory: true)
    try FileManager.default.createDirectory(at: protected, withIntermediateDirectories: true)
    let original = Data("unreadable encrypted state".utf8)
    let primary = protected.appendingPathComponent("session-state-v1.json")
    try original.write(to: primary)
    let recovering = SessionPersistenceWriter<State, Page>(legacyURL: protected.appendingPathComponent("session.json"),
      encodeState: { try JSONEncoder().encode($0) }, decodeState: { try JSONDecoder().decode(State.self, from: $0) },
      encodePage: { try JSONEncoder().encode($0) }, decodePage: { try JSONDecoder().decode(Page.self, from: $0) })
    do { _ = try recovering.loadInitialState(); preconditionFailure("Unreadable data became a new install") } catch {}
    let blocked = DispatchSemaphore(value: 0)
    recovering.write(SessionPersistenceSnapshot(state: State(operation: "empty", draft: ""), histories: [:])) { result, _ in
      if case .success = result { preconditionFailure("Failed read authorized an empty overwrite") }
      blocked.signal()
    }
    wait(blocked)
    let preservedBytes = try Data(contentsOf: primary)
    precondition(preservedBytes == original)
    try imported.write(to: primary)
    let reloaded = try recovering.loadInitialState()
    precondition(reloaded?.operation == "imported")
    let saved = DispatchSemaphore(value: 0)
    recovering.write(SessionPersistenceSnapshot(state: State(operation: "new", draft: "keep"), histories: [:])) { result, _ in
      precondition((try? result.get()) != nil); saved.signal()
    }
    wait(saved)
    try original.write(to: primary)
    let fallback = try recovering.loadInitialState()
    precondition(fallback?.operation == "imported", "Known-good generation did not recover")
    let evidenceFiles = try FileManager.default.contentsOfDirectory(atPath: protected.appendingPathComponent("Recovery-v1").path)
    precondition(!evidenceFiles.isEmpty)
    let orphan = directory.appendingPathComponent("orphan", isDirectory: true)
    try FileManager.default.createDirectory(at: orphan.appendingPathComponent("History-v1"), withIntermediateDirectories: true)
    try Data("keep".utf8).write(to: orphan.appendingPathComponent("History-v1/page.json"))
    let missing = SessionPersistenceWriter<State, Page>(legacyURL: orphan.appendingPathComponent("session.json"),
      encodeState: { try JSONEncoder().encode($0) }, decodeState: { try JSONDecoder().decode(State.self, from: $0) },
      encodePage: { try JSONEncoder().encode($0) }, decodePage: { try JSONDecoder().decode(Page.self, from: $0) })
    do { _ = try missing.loadInitialState(); preconditionFailure("Orphan data became a new installation") } catch {}
    let recoveredSave = DispatchSemaphore(value: 0)
    recovering.write(SessionPersistenceSnapshot(state: State(operation: "recovered", draft: "keep"),
      histories: ["epoch:missed": Page(revision: 1, body: "missed")]), flushHistory: true) { result, _ in
        precondition((try? result.get()) != nil); recoveredSave.signal()
      }
    wait(recoveredSave)
    let recoveredPages = try recovering.recoverUnindexedHistory(namespace: "epoch", indexed: [], key: { $0.body })
    precondition(recoveredPages["missed"]?.revision == 1)
    let otherEpoch = try recovering.recoverUnindexedHistory(namespace: "other", indexed: [], key: { $0.body })
    precondition(otherEpoch.isEmpty, "Another cache epoch was imported")
    let denied = directory.appendingPathComponent("unreadable-directory", isDirectory: true)
    let deniedHistory = denied.appendingPathComponent("History-v1", isDirectory: true)
    try FileManager.default.createDirectory(at: deniedHistory, withIntermediateDirectories: true)
    try Data("preserved".utf8).write(to: deniedHistory.appendingPathComponent("page.json"))
    try FileManager.default.setAttributes([.posixPermissions: 0], ofItemAtPath: deniedHistory.path)
    defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: deniedHistory.path) }
    let deniedWriter = SessionPersistenceWriter<State, Page>(legacyURL: denied.appendingPathComponent("session.json"),
      encodeState: { try JSONEncoder().encode($0) }, decodeState: { try JSONDecoder().decode(State.self, from: $0) },
      encodePage: { try JSONEncoder().encode($0) }, decodePage: { try JSONDecoder().decode(Page.self, from: $0) })
    do { _ = try deniedWriter.loadInitialState(); preconditionFailure("Unreadable history directory became a fresh install") } catch {}
    do { _ = try deniedWriter.recoverUnindexedHistory(namespace: "epoch", indexed: [], key: { $0.body }); preconditionFailure("Unreadable directory became an empty recovery") } catch {}
    let deniedSave = DispatchSemaphore(value: 0)
    deniedWriter.write(SessionPersistenceSnapshot(state: State(operation: "empty", draft: ""), histories: [:])) { result, _ in
      if case .success = result { preconditionFailure("Directory read failure authorized writes") }
      deniedSave.signal()
    }
    wait(deniedSave)
    precondition(!FileManager.default.fileExists(atPath: denied.appendingPathComponent("session-state-v1.json").path))
    print("{\"suite\":\"session-persistence-boundary\",\"passed\":10,\"failed\":0,\"historyBytes\":109051904,\"criticalWriteMS\":\(criticalMS),\"coalescedUpdates\":80,\"historyEncodes\":\(metrics.encodes),\"physicalPhoneVerified\":false}")
  }
}
