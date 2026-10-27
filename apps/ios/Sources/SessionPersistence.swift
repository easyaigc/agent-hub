import CryptoKit
import Darwin
import Foundation

enum SessionPersistenceError: Error { case recoveryRequired, notLoaded }

// Value snapshots cross the queues unchanged. The UI owns subsequent mutations.
struct SessionPersistenceSnapshot<State, Page>: @unchecked Sendable {
  let state: State
  let histories: [String: Page]
}

// Recovery state has its own ordered queue. Large, replaceable history pages
// cannot delay a command's durable intent, and only the newest queued page wins.
final class SessionPersistenceWriter<State, Page>: @unchecked Sendable {
  typealias Completion = @Sendable (Result<Void, Error>, Bool) -> Void
  private let stateQueue = DispatchQueue(label: "com.codexmanage.session-state", qos: .userInitiated)
  private let historyQueue = DispatchQueue(label: "com.codexmanage.session-history", qos: .utility)
  private let lock = NSLock()
  private var pendingPages: [String: Page] = [:]
  private var pendingCompletions: [Completion] = []
  private var historyScheduled = false
  private var historyFailure: (@Sendable (Error) -> Void)?
  private let stateURL: URL
  private let legacyURL: URL
  private let historyURL: URL
  private let backupURL: URL
  private let previousURL: URL
  private var writesAuthorized = false // Accessed only on stateQueue.
  private var lastGoodBytes: Data?
  private var validatedBackupBytes: Data?
  private var loadedDigest: String?
  private let encodeState: @Sendable (State) throws -> Data
  private let decodeState: @Sendable (Data) throws -> State
  private let encodePage: @Sendable (Page) throws -> Data
  private let decodePage: @Sendable (Data) throws -> Page
  private let cloneFile: @Sendable (URL, URL) -> Bool

  init(legacyURL: URL, encodeState: @escaping @Sendable (State) throws -> Data,
    decodeState: @escaping @Sendable (Data) throws -> State,
    encodePage: @escaping @Sendable (Page) throws -> Data,
    decodePage: @escaping @Sendable (Data) throws -> Page,
    cloneFile: @escaping @Sendable (URL, URL) -> Bool = {
      Darwin.clonefile($0.path, $1.path, UInt32(CLONE_NOFOLLOW)) == 0
    }) {
    self.legacyURL = legacyURL
    stateURL = legacyURL.deletingLastPathComponent().appendingPathComponent("session-state-v1.json")
    historyURL = legacyURL.deletingLastPathComponent().appendingPathComponent("History-v1", isDirectory: true)
    backupURL = legacyURL.deletingLastPathComponent().appendingPathComponent("session-state-v1.backup.json")
    previousURL = legacyURL.deletingLastPathComponent().appendingPathComponent("session-state-v1.previous.json")
    self.encodeState = encodeState; self.decodeState = decodeState
    self.encodePage = encodePage; self.decodePage = decodePage
    self.cloneFile = cloneFile
  }

  func loadState() throws -> State { try decodeState(readData(stateURL)) }
  /// Only an absent store is a new installation. An unreadable store never authorizes writes.
  func loadInitialState() throws -> State? {
    try stateQueue.sync {
      writesAuthorized = false
      var failures: [Error] = []
      var damaged: [(URL, Data)] = []
      for url in [stateURL, backupURL, previousURL, legacyURL] {
        let bytes: Data
        do { bytes = try readData(url) }
        catch {
          let failure = error as NSError
          if failure.domain == NSCocoaErrorDomain && failure.code == NSFileReadNoSuchFileError { continue }
          // In particular, never replace a protected file while the phone is locked.
          recordRecoveryFailure(error)
          throw error
        }
        do {
          let state = try decodeState(bytes)
          try migrateProtection(url)
          // Preserve a corrupt primary before publishing any recovered snapshot.
          for (original, data) in damaged {
            let evidence = stateURL.deletingLastPathComponent().appendingPathComponent("Recovery-v1", isDirectory: true)
            try FileManager.default.createDirectory(at: evidence, withIntermediateDirectories: true)
            try atomicWrite(data, to: evidence.appendingPathComponent(UUID().uuidString + "-" + original.lastPathComponent))
          }
          lastGoodBytes = bytes
          loadedDigest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
          writesAuthorized = true
          return state
        } catch {
          failures.append(error); damaged.append((url, bytes))
        }
      }
      if !failures.isEmpty {
        recordRecoveryFailure(failures[0]); throw failures[0]
      }
      // Orphan history is evidence of an existing installation, even without its index.
      if !(try historyFiles()).isEmpty { throw SessionPersistenceError.recoveryRequired }
      writesAuthorized = true; lastGoodBytes = nil
      return nil
    }
  }
  private func recordRecoveryFailure(_ error: Error) {
    let value = error as NSError
    let diagnostic: [String: Any] = ["time": Date().timeIntervalSince1970, "domain": value.domain, "code": value.code]
    if let data = try? JSONSerialization.data(withJSONObject: diagnostic) {
      try? atomicWrite(data, to: stateURL.deletingLastPathComponent().appendingPathComponent("session-recovery-error.json"))
    }
  }
  // Counts and fingerprints only; never credentials, text, targets or filenames.
  func recordLoadReport(_ fields: [String: String]) {
    stateQueue.async { [self] in
      var report = fields
      report["sourceSha256"] = loadedDigest
      report["time"] = String(Date().timeIntervalSince1970)
      if let bytes = try? JSONEncoder().encode(report) {
        try? atomicWrite(bytes, to: stateURL.deletingLastPathComponent().appendingPathComponent("session-load-report.json"))
      }
    }
  }
  func loadHistory(_ id: String) throws -> Page {
    let url = pageURL(id)
    let page = try decodePage(readData(url))
    try migrateProtection(url)
    return page
  }
  /// Rebuild only this namespace's index from authenticated pages. Other epochs stay untouched.
  func recoverUnindexedHistory(namespace: String, indexed: [String], key: (Page) -> String) throws -> [String: Page] {
    let known = Set(indexed.map { pageURL(namespace + ":" + $0).lastPathComponent })
    let files = try historyFiles()
    var recovered: [String: Page] = [:]
    for url in files where url.pathExtension == "json" && !known.contains(url.lastPathComponent) {
      do {
        let page = try decodePage(readData(url))
        let target = key(page)
        guard pageURL(namespace + ":" + target).lastPathComponent == url.lastPathComponent else { continue }
        try migrateProtection(url)
        recovered[target] = page
      } catch {
        let failure = error as NSError
        if failure.domain == NSCocoaErrorDomain && failure.code == NSFileReadNoPermissionError { throw error }
        // A damaged replaceable cache cannot authorize deletion or overwrite of its source.
      }
    }
    return recovered
  }
  private func historyFiles() throws -> [URL] {
    do { return try FileManager.default.contentsOfDirectory(at: historyURL, includingPropertiesForKeys: nil) }
    catch {
      let failure = error as NSError
      if failure.domain == NSCocoaErrorDomain && failure.code == NSFileReadNoSuchFileError { return [] }
      recordRecoveryFailure(error)
      throw error
    }
  }
  private func migrateProtection(_ url: URL) throws {
    #if os(iOS)
    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
    #endif
  }
  func onHistoryFailure(_ handler: @escaping @Sendable (Error) -> Void) {
    lock.lock(); historyFailure = handler; lock.unlock()
  }
  private func pageURL(_ id: String) -> URL {
    let name = SHA256.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined()
    return historyURL.appendingPathComponent(name + ".json")
  }
  private func readData(_ url: URL) throws -> Data {
    let bytes = try Data(contentsOf: url)
    #if DEBUG && os(iOS)
    NativeThermalIOCounters.recordRead(bytes: bytes.count)
    #endif
    return bytes
  }
  private func measured<T>(_ stage: String, _ body: () throws -> T) rethrows -> T {
    #if DEBUG && os(iOS)
    return try NativeThermalIOCounters.measure(stage, body)
    #else
    return try body()
    #endif
  }

  private func atomicWrite(_ data: Data, to url: URL) throws {
    #if os(iOS)
    try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    #else
    try data.write(to: url, options: [.atomic])
    #endif
    #if DEBUG && os(iOS)
    let kind = url == stateURL ? "State" : (url == backupURL || url == previousURL) ? "Backup" : url.deletingLastPathComponent() == historyURL ? "History" : "Other"
    NativeThermalIOCounters.recordWrite(bytes: data.count, kind: kind)
    #endif
  }

  // A validated APFS clone shares the old encrypted blocks until either copy
  // changes. Publish the private staging file atomically, keeping the primary
  // and the previous generation intact if cloning/validation/publication fails.
  // Unsupported filesystems use the original byte-write path.
  private func preserveGeneration(from source: URL, to destination: URL,
    expected: Data? = nil, valid: (Data) -> Bool) throws {
    let staging = destination.deletingLastPathComponent()
      .appendingPathComponent(".session-generation-" + UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: staging) }
    if cloneFile(source, staging), let bytes = try? readData(staging), valid(bytes) {
      try migrateProtection(staging)
      guard Darwin.rename(staging.path, destination.path) == 0 else {
        throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
      }
      #if DEBUG && os(iOS)
      NativeThermalIOCounters.recordClone(bytes: bytes.count)
      #endif
      return
    }
    // A changed primary must not displace its known-good in-memory bytes.
    // A corrupt backup has no such fallback and must leave previous untouched.
    if let bytes = expected ?? (try? readData(source)), valid(bytes) {
      try atomicWrite(bytes, to: destination)
    }
  }

  func write(_ snapshot: SessionPersistenceSnapshot<State, Page>, flushHistory: Bool = false,
    completion: @escaping Completion) {
    stateQueue.async { [self] in
      let result = Result<Void, Error> {
        guard writesAuthorized else { throw SessionPersistenceError.notLoaded }
        let data = try measured("StateEncode") { try encodeState(snapshot.state) }
        // Validate before touching either the primary or its known-good generations.
        _ = try measured("StateValidate") { try decodeState(data) }
        if let lastGoodBytes {
          try preserveGeneration(from: backupURL, to: previousURL) { prior in
            prior == validatedBackupBytes || (try? decodeState(prior)) != nil
          }
          try preserveGeneration(from: stateURL, to: backupURL, expected: lastGoodBytes) {
            $0 == lastGoodBytes
          }
          validatedBackupBytes = lastGoodBytes
        }
        try atomicWrite(data, to: stateURL)
        lastGoodBytes = data
      }
      guard case .success = result else { completion(result, Thread.isMainThread); return }
      enqueueHistory(snapshot.histories, completion: flushHistory ? completion : nil)
      if !flushHistory { completion(result, Thread.isMainThread) }
    }
  }

  private func enqueueHistory(_ pages: [String: Page], completion: Completion?) {
    lock.lock()
    pendingPages.merge(pages) { _, latest in latest }
    if let completion { pendingCompletions.append(completion) }
    let schedule = !historyScheduled && (!pendingPages.isEmpty || !pendingCompletions.isEmpty)
    if schedule { historyScheduled = true }
    lock.unlock()
    if schedule { historyQueue.async { [self] in drainHistory() } }
  }
  private func drainHistory() {
    while true {
      lock.lock()
      if pendingPages.isEmpty && pendingCompletions.isEmpty {
        historyScheduled = false; lock.unlock(); return
      }
      let pages = pendingPages, completions = pendingCompletions
      pendingPages = [:]; pendingCompletions = []
      lock.unlock()
      let result = Result<Void, Error> {
        try FileManager.default.createDirectory(at: historyURL, withIntermediateDirectories: true)
        for (id, page) in pages { try atomicWrite(encodePage(page), to: pageURL(id)) }
      }
      if case .failure(let error) = result {
        lock.lock(); let handler = historyFailure; lock.unlock()
        handler?(error)
      }
      for completion in completions { completion(result, Thread.isMainThread) }
    }
  }

  // Import is a one-time replacement. Drain previous writes before installing
  // the legacy-format payload; the next snapshot gets a fresh cache namespace.
  func replaceLegacy(_ data: Data) throws {
    try stateQueue.sync {
      historyQueue.sync {}
      _ = try decodeState(data)
      if let lastGoodBytes { try atomicWrite(lastGoodBytes, to: backupURL) }
      try atomicWrite(data, to: legacyURL)
      try atomicWrite(data, to: stateURL)
      lastGoodBytes = data; writesAuthorized = true
    }
  }
}
