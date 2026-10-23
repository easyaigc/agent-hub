import Foundation
import SQLite3
import Darwin

/// Device-local encrypted cache only. Callers must stop submission when load/save throws.
final class TaskHubSQLitePersistence {
  private struct Location: Codable, Hashable {
    var scope: String; var key: String; var field: String; var type: String; var order: Int
  }
  private struct Frame: Codable, Equatable {
    var version = 1; var generation: String; var location: Location; var body: Data
  }
  private struct Manifest: Codable { var root: Data; var entries: [Location] }
  private enum Failure: LocalizedError {
    case invalid, unsafePath, sqlite(Int32), databaseOpen(Int32, Int32, Int32)
    var errorDescription: String? {
      switch self {
      case .invalid: return L10n.tr("任务本机缓存损坏或版本不兼容，请保留原缓存并停止提交。")
      case .unsafePath: return L10n.tr("任务本机缓存路径不安全，未保存。")
      case .sqlite(let code): return L10n.tr("任务本机缓存未完成保存或读取（SQLite \(code)）。")
      case .databaseOpen(let code, let extended, let system):
        return L10n.tr("任务本机缓存无法打开（SQLite \(code)/\(extended)，系统 \(system)）。请保留缓存并停止提交。")
      }
    }
  }
  private static let dictionaries: Set<String> = ["lists", "details", "drafts", "eventCursors", "bookmarks", "businessPreferences", "businessCursors", "businessNotices", "historyWindows"]
  private static let arrays = ["operations": "id", "uploads": "operationId", "creations": "operationId", "consentGrants": "id"]
  private static let metadata = Location(scope: "", key: "", field: "", type: "metadata", order: -1)
  private static let prefix = Data("agent-cache-v1\n".utf8)
  private static let schema = "CREATE TABLE cache_rows(scope TEXT NOT NULL,key TEXT NOT NULL,field TEXT NOT NULL,type TEXT NOT NULL,position INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(field,scope,key)) WITHOUT ROWID"
  private let databaseURL: URL, legacyURL: URL
  private let lock = NSLock()
  #if TASKHUB_SQLITE_HARNESS
  // Standalone test builds may observe SQL; production has no injectable database behavior.
  static var observeConnection: ((OpaquePointer) -> Void)?
  #endif
  init(databaseURL: URL, legacyURL: URL) {
    self.databaseURL = Self.cacheLocation(databaseURL); self.legacyURL = Self.cacheLocation(legacyURL)
  }
  private static func cacheLocation(_ url: URL) -> URL {
    let input = url.standardizedFileURL
    #if os(iOS)
    let reported = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].standardizedFileURL
    guard input.path.hasPrefix(reported.path + "/") else { return input }
    // iOS reports an OS-owned /var prefix that SQLite NOFOLLOW refuses. Resolve
    // only that prefix; Application Support and every cache component stay un-followed.
    let anchor = applicationSupportAnchor()
    return anchor.appendingPathComponent(String(input.path.dropFirst(reported.path.count + 1))).standardizedFileURL
    #else
    return input
    #endif
  }
  #if os(iOS)
  private static func applicationSupportAnchor() -> URL {
    let reported = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].standardizedFileURL
    return reported.deletingLastPathComponent().resolvingSymlinksInPath()
      .appendingPathComponent(reported.lastPathComponent, isDirectory: true).standardizedFileURL
  }
  #endif
  func load() throws -> Data? {
    lock.lock(); defer { lock.unlock() }
    try prepareDirectory()
    if try exists(databaseURL) { return try database(databaseURL) { try read($0) } }
    return try migrate()
  }
  func save(_ data: Data) throws {
    lock.lock(); defer { lock.unlock() }
    let frames = try split(data, generation: UUID().uuidString)
    try prepareDirectory()
    if !(try exists(databaseURL)) { _ = try migrate() }
    if try exists(databaseURL) {
      try database(databaseURL) { db in
        // Authenticate the old snapshot before replacing it, including after a failed load.
        _ = try read(db); try write(frames, db: db, creating: false)
      }
    } else { try install(frames) }
  }
  private func migrate() throws -> Data? {
    guard try exists(legacyURL) else { return nil }
    guard legacyURL.deletingLastPathComponent() == databaseURL.deletingLastPathComponent() else { throw Failure.unsafePath }
    try protect(legacyURL, directory: false)
    // AgentCache's legacy reader intentionally retains its original JSON compatibility.
    let data = try JSONEncoder().encode(AgentCache.open(Data(contentsOf: legacyURL), as: TaskHubJSON.self))
    let frames = try split(data, generation: UUID().uuidString)
    try install(frames) // Never rewrite or remove the private historical .bin.
    return data
  }
  private func split(_ data: Data, generation: String) throws -> [Frame] {
    guard var root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Failure.invalid }
    // Synthesized TaskHubSaved decoding requires these even when every collection is empty.
    guard ["lists", "details", "drafts", "eventCursors"].allSatisfy({ root[$0] is [String: Any] }),
      ["operations", "actors", "projects"].allSatisfy({ root[$0] is [Any] }) else { throw Failure.invalid }
    var frames: [Frame] = []
    for field in root.keys.sorted() {
      let value = root[field]!
      if value is NSNull { continue }
      if Self.dictionaries.contains(field) {
        guard let entries = value as? [String: Any] else { throw Failure.invalid }
        for (order, key) in entries.keys.sorted().enumerated() {
          // The complete scoped dictionary key is opaque: never truncate namespace/actor/device.
          frames.append(Frame(generation: generation, location: Location(scope: key, key: key, field: field, type: "dictionary", order: order), body: try json(entries[key]!)))
        }
        root[field] = [String: Any]()
      } else if let stableID = Self.arrays[field] {
        guard let entries = value as? [Any] else { throw Failure.invalid }
        for (order, entry) in entries.enumerated() {
          let scopeField = field == "consentGrants" ? "businessScope" : "scope"
          guard let object = entry as? [String: Any], let scope = object[scopeField] as? String, !scope.isEmpty,
            let key = object[stableID] as? String, !key.isEmpty else { throw Failure.invalid }
          frames.append(Frame(generation: generation, location: Location(scope: scope, key: key, field: field, type: "array", order: order), body: try json(entry)))
        }
        root[field] = [Any]()
      }
    }
    let identities = frames.map { [$0.location.field, $0.location.scope, $0.location.key] }
    guard Set(identities).count == frames.count,
      identities.joined().allSatisfy({ !$0.contains("\0") }) else { throw Failure.invalid }
    let manifest = Manifest(root: try json(root), entries: frames.map(\.location))
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    frames.append(Frame(generation: generation, location: Self.metadata, body: try encoder.encode(manifest)))
    return frames
  }
  private func read(_ db: OpaquePointer) throws -> Data {
    try validateSchema(db)
    var frames: [Location: Frame] = [:]
    try statement("SELECT scope,key,field,type,position,payload FROM cache_rows", db: db) { stmt in
      while true {
        let result = sqlite3_step(stmt)
        if result == SQLITE_DONE { break }; try check(result, expected: SQLITE_ROW)
        guard (0..<4).allSatisfy({ sqlite3_column_type(stmt, Int32($0)) == SQLITE_TEXT }),
          sqlite3_column_type(stmt, 4) == SQLITE_INTEGER, sqlite3_column_type(stmt, 5) == SQLITE_BLOB,
          let bytes = sqlite3_column_blob(stmt, 5) else { throw Failure.invalid }
        func text(_ index: Int32) throws -> String {
          let count = Int(sqlite3_column_bytes(stmt, index))
          guard let pointer = sqlite3_column_text(stmt, index),
            let value = String(data: Data(bytes: pointer, count: count), encoding: .utf8), !value.contains("\0") else { throw Failure.invalid }
          return value
        }
        let location = Location(scope: try text(0), key: try text(1), field: try text(2), type: try text(3), order: Int(sqlite3_column_int64(stmt, 4)))
        let sealed = Data(bytes: bytes, count: Int(sqlite3_column_bytes(stmt, 5)))
        guard sealed.starts(with: Self.prefix) else { throw Failure.invalid }
        let frame = try JSONDecoder().decode(Frame.self, from: AgentCache.open(sealed, as: Data.self))
        guard frame.version == 1, frame.location == location, frames[location] == nil else { throw Failure.invalid }
        frames[location] = frame
      }
    }
    guard let metadata = frames[Self.metadata], UUID(uuidString: metadata.generation) != nil else { throw Failure.invalid }
    let manifest = try JSONDecoder().decode(Manifest.self, from: metadata.body)
    guard Set(manifest.entries).count == manifest.entries.count,
      Set(frames.keys) == Set(manifest.entries + [Self.metadata]),
      var root = try JSONSerialization.jsonObject(with: manifest.root) as? [String: Any] else { throw Failure.invalid }
    for location in manifest.entries {
      guard let frame = frames[location], frame.generation == metadata.generation else { throw Failure.invalid }
      let value = try JSONSerialization.jsonObject(with: frame.body, options: [.fragmentsAllowed])
      if location.type == "dictionary", var entries = root[location.field] as? [String: Any], entries[location.key] == nil {
        entries[location.key] = value; root[location.field] = entries
      } else if location.type == "array", var entries = root[location.field] as? [Any], location.order == entries.count {
        entries.append(value); root[location.field] = entries
      } else { throw Failure.invalid }
    }
    let data = try json(root)
    let rebuilt = try split(data, generation: metadata.generation)
    guard rebuilt.count == frames.count, rebuilt.allSatisfy({ frames[$0.location] == $0 }) else { throw Failure.invalid }
    return data
  }
  private func write(_ frames: [Frame], db: OpaquePointer, creating: Bool) throws {
    try exec("BEGIN IMMEDIATE", db: db)
    do {
      if creating {
        try exec(Self.schema, db: db); try exec("PRAGMA application_id=1414026562; PRAGMA user_version=1", db: db)
      } else { try validateSchema(db) }
      try exec("DELETE FROM cache_rows", db: db)
      try statement("INSERT INTO cache_rows VALUES(?,?,?,?,?,?)", db: db) { stmt in
        for frame in frames {
          try check(sqlite3_reset(stmt)); try check(sqlite3_clear_bindings(stmt))
          let location = frame.location
          for (index, value) in [location.scope, location.key, location.field, location.type].enumerated() {
            try value.withCString { try check(sqlite3_bind_text(stmt, Int32(index + 1), $0, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))) }
          }
          try check(sqlite3_bind_int64(stmt, 5, sqlite3_int64(location.order)))
          let sealed = try AgentCache.seal(JSONEncoder().encode(frame))
          guard sealed.starts(with: Self.prefix), sealed.count <= Int(Int32.max) else { throw Failure.invalid }
          try sealed.withUnsafeBytes { try check(sqlite3_bind_blob(stmt, 6, $0.baseAddress, Int32($0.count), unsafeBitCast(-1, to: sqlite3_destructor_type.self))) }
          try check(sqlite3_step(stmt), expected: SQLITE_DONE)
        }
      }
      // Protect a live DELETE journal too; a COMMIT failure must propagate and roll back.
      guard let filename = sqlite3_db_filename(db, "main") else { throw Failure.invalid }
      let journal = URL(fileURLWithPath: String(cString: filename) + "-journal")
      if try exists(journal) { try protect(journal, directory: false) }
      try exec("COMMIT", db: db)
    } catch { try? exec("ROLLBACK", db: db); throw error }
  }
  private func validateSchema(_ db: OpaquePointer) throws {
    try statement("PRAGMA user_version", db: db) { stmt in
      try check(sqlite3_step(stmt), expected: SQLITE_ROW); guard sqlite3_column_int(stmt, 0) == 1 else { throw Failure.invalid }
    }
    try statement("PRAGMA application_id", db: db) { stmt in
      try check(sqlite3_step(stmt), expected: SQLITE_ROW); guard sqlite3_column_int(stmt, 0) == 1414026562 else { throw Failure.invalid }
    }
    try statement("SELECT sql FROM sqlite_master ORDER BY name", db: db) { stmt in
      try check(sqlite3_step(stmt), expected: SQLITE_ROW)
      guard let sql = sqlite3_column_text(stmt, 0), String(cString: sql) == Self.schema,
        sqlite3_step(stmt) == SQLITE_DONE else { throw Failure.invalid }
    }
  }
  private func install(_ frames: [Frame]) throws {
    let stage = databaseURL.deletingLastPathComponent().appendingPathComponent(".task-hub-" + UUID().uuidString + ".sqlite")
    let fd = Darwin.open(stage.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, 0o600)
    guard fd >= 0 else { throw Failure.unsafePath }; Darwin.close(fd)
    defer { try? FileManager.default.removeItem(at: stage) }
    try database(stage, creating: true) { try write(frames, db: $0, creating: true) }
    // Exclusive atomic rename refuses to overwrite a concurrently installed DB.
    guard Darwin.renamex_np(stage.path, databaseURL.path, UInt32(RENAME_EXCL)) == 0 else { throw Failure.unsafePath }
    let directory = Darwin.open(databaseURL.deletingLastPathComponent().path, O_RDONLY | O_NOFOLLOW)
    guard directory >= 0 else { throw Failure.unsafePath }; defer { Darwin.close(directory) }
    guard Darwin.fsync(directory) == 0 else { throw Failure.unsafePath }
  }
  private func database<T>(_ url: URL, creating: Bool = false, _ body: (OpaquePointer) throws -> T) throws -> T {
    try protect(url, directory: false)
    for suffix in ["-wal", "-shm"] { if try exists(URL(fileURLWithPath: url.path + suffix)) { throw Failure.invalid } }
    let journal = URL(fileURLWithPath: url.path + "-journal")
    if try exists(journal) { try protect(journal, directory: false) }
    var pointer: OpaquePointer?
    let status = sqlite3_open_v2(try sqlitePath(url), &pointer, SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX | SQLITE_OPEN_NOFOLLOW, nil)
    guard status == SQLITE_OK, let db = pointer else {
      let extended = pointer.map { sqlite3_extended_errcode($0) } ?? status
      let system = pointer.map { sqlite3_system_errno($0) } ?? 0
      if let pointer { sqlite3_close(pointer) }
      throw Failure.databaseOpen(status, extended, system)
    }
    defer { sqlite3_close(db) }
    try check(sqlite3_busy_timeout(db, 1500))
    #if TASKHUB_SQLITE_HARNESS
    Self.observeConnection?(db)
    #endif
    if !creating { try validateSchema(db) }
    try statement("PRAGMA journal_mode=DELETE", db: db) { stmt in
      try check(sqlite3_step(stmt), expected: SQLITE_ROW)
      guard let mode = sqlite3_column_text(stmt, 0), String(cString: mode) == "delete" else { throw Failure.invalid }
    }
    try exec("PRAGMA synchronous=FULL; PRAGMA fullfsync=ON; PRAGMA trusted_schema=OFF", db: db)
    return try body(db)
  }
  private func sqlitePath(_ url: URL) throws -> String {
    #if os(iOS)
    // Foundation may return an OS alias again after resolving a parent. Keep the
    // canonical C path verbatim for SQLite, without following the cache file.
    guard let parent = Darwin.realpath(url.deletingLastPathComponent().path, nil) else { throw Failure.unsafePath }
    defer { free(parent) }
    guard let anchor = Darwin.realpath(Self.applicationSupportAnchor().path, nil) else { throw Failure.unsafePath }
    defer { free(anchor) }
    let canonical = String(cString: parent), allowed = String(cString: anchor)
    guard canonical == allowed || canonical.hasPrefix(allowed + "/") else { throw Failure.unsafePath }
    return canonical + "/" + url.lastPathComponent
    #else
    return url.path
    #endif
  }
  private func prepareDirectory() throws {
    guard databaseURL.isFileURL, legacyURL.isFileURL, databaseURL != legacyURL else { throw Failure.unsafePath }
    let parent = databaseURL.deletingLastPathComponent()
    #if os(iOS)
    let anchor = Self.applicationSupportAnchor()
    guard parent.path == anchor.path || parent.path.hasPrefix(anchor.path + "/") else { throw Failure.unsafePath }
    if !(try exists(anchor)) { try FileManager.default.createDirectory(at: anchor, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]) }
    try protect(anchor, directory: true)
    #else
    let anchor = URL(fileURLWithPath: "/", isDirectory: true)
    #endif
    var current = anchor
    // The OS-owned prefix of iOS Application Support is trusted; every cache component is checked.
    let components = parent.path.dropFirst(anchor.path == "/" ? 1 : anchor.path.count).split(separator: "/")
    for component in components {
      current.appendPathComponent(String(component), isDirectory: true)
      if !(try exists(current)) { try FileManager.default.createDirectory(at: current, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]) }
      var info = stat()
      guard lstat(current.path, &info) == 0, (info.st_mode & S_IFMT) == S_IFDIR else { throw Failure.unsafePath }
    }
    if !(try exists(parent)) { try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]) }
    try protect(parent, directory: true)
  }
  private func exists(_ url: URL) throws -> Bool {
    var info = stat()
    if lstat(url.path, &info) == 0 { return true }
    guard errno == ENOENT else { throw Failure.unsafePath }; return false
  }
  private func protect(_ url: URL, directory: Bool) throws {
    var info = stat()
    guard lstat(url.path, &info) == 0, info.st_uid == getuid(),
      (info.st_mode & S_IFMT) == (directory ? S_IFDIR : S_IFREG), directory || info.st_nlink == 1 else { throw Failure.unsafePath }
    var attributes: [FileAttributeKey: Any] = [.posixPermissions: directory ? 0o700 : 0o600]
    #if os(iOS)
    attributes[.protectionKey] = FileProtectionType.completeUntilFirstUserAuthentication
    #endif
    try FileManager.default.setAttributes(attributes, ofItemAtPath: url.path)
  }
  private func json(_ value: Any) throws -> Data {
    try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .sortedKeys, .withoutEscapingSlashes])
  }
  private func statement<T>(_ sql: String, db: OpaquePointer, _ body: (OpaquePointer) throws -> T) throws -> T {
    var pointer: OpaquePointer?; try check(sqlite3_prepare_v2(db, sql, -1, &pointer, nil))
    guard let stmt = pointer else { throw Failure.invalid }; defer { sqlite3_finalize(stmt) }
    return try body(stmt)
  }
  private func exec(_ sql: String, db: OpaquePointer) throws { try check(sqlite3_exec(db, sql, nil, nil, nil)) }
  private func check(_ result: Int32, expected: Int32 = SQLITE_OK) throws {
    guard result == expected else { throw Failure.sqlite(result) }
  }
}
