import Foundation
import CryptoKit
import Darwin

/// Migration evidence only. This namespace has no recording, upload, consent or retry API.
/// Never write these files into voice-transcriptions, including after the original expiry.
enum PendingVoiceMigration {
  static let liveDirectory = "voice-transcriptions"
  static let archiveDirectory = "migration-pending-voice-originals-v1"
  static let maxFiles = 128, maxOriginals = 32, maxBytes = 64 * 1024 * 1024
  static let maxAudio = 12 * 1024 * 1024, maxMetadata = 64 * 1024
  enum Failure: Error { case invalid, bounds, unstable, conflict }
  struct Key: CodingKey {
    var stringValue: String; var intValue: Int? { nil }
    init(_ value: String) { stringValue = value }
    init?(stringValue: String) { self.init(stringValue) }
    init?(intValue: Int) { return nil }
  }
  static func closed(_ decoder: Decoder, _ names: Set<String>) throws {
    guard Set(try decoder.container(keyedBy: Key.self).allKeys.map(\.stringValue)) == names else { throw Failure.invalid }
  }
  static func sha(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
  static func digest(_ value: String) -> Bool { value.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) } }
  static func operation(_ name: String) throws -> String {
    let suffix = name.hasSuffix(".json") ? ".json" : name.hasSuffix(".wav") ? ".wav" : ""
    let id = String(name.dropLast(suffix.count))
    guard !suffix.isEmpty, UUID(uuidString: id)?.uuidString.lowercased() == id else { throw Failure.invalid }
    return id
  }
  static func checkedAdd(_ first: Int, _ second: Int) throws -> Int {
    let value = first.addingReportingOverflow(second)
    guard !value.overflow, value.partialValue <= PendingVoiceMigration.maxBytes else { throw Failure.bounds }; return value.partialValue
  }

  /// A lazy original retains its opened file identity, never an audio-sized export snapshot.
  struct Bytes: Codable {
    let retained: Data?
    let source: StableFile?
    init(_ value: Data) { retained = value; source = nil }
    init(_ value: StableFile) { retained = nil; source = value }
    init(from decoder: Decoder) throws {
      let text = try decoder.singleValueContainer().decode(String.self)
      guard text.utf8.count <= 4 * ((PendingVoiceMigration.maxAudio + 2) / 3), let value = Data(base64Encoded: text), value.base64EncodedString() == text else { throw Failure.bounds }
      retained = value; source = nil
    }
    func encode(to encoder: Encoder) throws { var value = encoder.singleValueContainer(); try value.encode(try data()) }
    var count: Int { source?.size ?? retained!.count }
    func data() throws -> Data { if let source { return try source.data() }; return retained! }
    func verify(size: Int, sha256: String) throws {
      guard count == size else { throw Failure.invalid }
      if let source { try source.unchanged(); guard source.sha256 == sha256 else { throw Failure.invalid } }
      else { guard PendingVoiceMigration.sha(retained!) == sha256 else { throw Failure.invalid } }
    }
  }
  struct File: Codable {
    let name: String, size: Int, sha256: String
    let bytes: Bytes
    enum CodingKeys: String, CodingKey { case name, size, sha256, bytes }
    init(name: String, source: StableFile) { self.name = name; size = source.size; sha256 = source.sha256; bytes = Bytes(source) }
    init(name: String, data: Data) { self.name = name; size = data.count; sha256 = PendingVoiceMigration.sha(data); bytes = Bytes(data) }
    init(from decoder: Decoder) throws {
      try PendingVoiceMigration.closed(decoder, ["name", "size", "sha256", "bytes"])
      let value = try decoder.container(keyedBy: CodingKeys.self)
      let decodedName = try value.decode(String.self, forKey: .name), decodedSize = try value.decode(Int.self, forKey: .size)
      _ = try PendingVoiceMigration.operation(decodedName)
      guard decodedSize >= 0, decodedSize <= (decodedName.hasSuffix(".json") ? PendingVoiceMigration.maxMetadata : PendingVoiceMigration.maxAudio) else { throw Failure.bounds }
      name = decodedName; size = decodedSize
      sha256 = try value.decode(String.self, forKey: .sha256); bytes = try value.decode(Bytes.self, forKey: .bytes)
    }
    var descriptorValue: Descriptor { Descriptor(name: name, size: size, sha256: sha256) }
  }
  struct Descriptor: Codable, Equatable {
    let name: String, size: Int, sha256: String
    enum CodingKeys: String, CodingKey { case name, size, sha256 }
    init(name: String, size: Int, sha256: String) { self.name = name; self.size = size; self.sha256 = sha256 }
    init(from decoder: Decoder) throws {
      try PendingVoiceMigration.closed(decoder, ["name", "size", "sha256"]); let value = try decoder.container(keyedBy: CodingKeys.self)
      name = try value.decode(String.self, forKey: .name); size = try value.decode(Int.self, forKey: .size); sha256 = try value.decode(String.self, forKey: .sha256)
    }
  }
  struct ManifestOriginal: Codable {
    let id: String, files: [Descriptor]
    enum CodingKeys: String, CodingKey { case id, files }
    init(_ original: Original) { id = original.id; files = original.files.map(\.descriptorValue) }
    init(from decoder: Decoder) throws {
      try PendingVoiceMigration.closed(decoder, ["id", "files"]); let value = try decoder.container(keyedBy: CodingKeys.self)
      id = try value.decode(String.self, forKey: .id); files = try value.decode([Descriptor].self, forKey: .files)
    }
  }
  struct Manifest: Codable {
    let version: Int, originals: [ManifestOriginal]
    enum CodingKeys: String, CodingKey { case version, originals }
    init(_ bundle: Bundle) { version = bundle.version; originals = bundle.originals.map(ManifestOriginal.init) }
    init(from decoder: Decoder) throws {
      try PendingVoiceMigration.closed(decoder, ["version", "originals"]); let value = try decoder.container(keyedBy: CodingKeys.self)
      version = try value.decode(Int.self, forKey: .version); originals = try value.decode([ManifestOriginal].self, forKey: .originals)
    }
  }
  struct Original: Codable {
    let id: String, files: [File]
    enum CodingKeys: String, CodingKey { case id, files }
    init(files: [File]) { self.files = files; id = Self.identity(files) }
    init(from decoder: Decoder) throws {
      try PendingVoiceMigration.closed(decoder, ["id", "files"]); let value = try decoder.container(keyedBy: CodingKeys.self)
      id = try value.decode(String.self, forKey: .id); files = try value.decode([File].self, forKey: .files)
    }
    static func identity(_ files: [File]) -> String {
      var hash = SHA256(); hash.update(data: Data("agent-hub/ios-pending-voice-original/v1\0".utf8))
      func field(_ data: Data) { var size = UInt32(data.count).bigEndian; withUnsafeBytes(of: &size) { hash.update(data: Data($0)) }; hash.update(data: data) }
      for file in files { field(Data(file.name.utf8)); field(Data(String(file.size).utf8)); field(Data(file.sha256.utf8)) }
      return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    func validate() throws {
      guard !files.isEmpty, files.count <= PendingVoiceMigration.maxFiles, files.map(\.name) == Array(Set(files.map(\.name))).sorted(),
        Self.identity(files) == id else { throw Failure.invalid }
      var total = 0
      let byName = Dictionary(uniqueKeysWithValues: files.map { ($0.name, $0) })
      for file in files {
        let id = try PendingVoiceMigration.operation(file.name)
        guard PendingVoiceMigration.digest(file.sha256), file.size >= 0, file.size <= (file.name.hasSuffix(".json") ? PendingVoiceMigration.maxMetadata : PendingVoiceMigration.maxAudio) else { throw Failure.bounds }
        total = try PendingVoiceMigration.checkedAdd(total, file.size); try file.bytes.verify(size: file.size, sha256: file.sha256)
        if file.name.hasSuffix(".json") {
          let metadata = try file.bytes.data()
          let root = try VoiceMigrationJSON.object(metadata)
          let required: Set<String> = ["target", "scopeKey", "originalText", "operationId", "sha256", "durationMs", "sizeBytes", "createdAt"]
          guard required.isSubset(of: Set(root.keys)), Set(root.keys).isSubset(of: required.union(["locallyResolvedAt", "discardedAt"])) else { throw Failure.invalid }
          let row = try JSONDecoder().decode(Metadata.self, from: metadata)
          guard row.operationId == id, !row.target.isEmpty, row.target.utf8.count <= 8192,
            !row.scopeKey.isEmpty, row.scopeKey.utf8.count <= 8192, PendingVoiceMigration.digest(row.sha256),
            row.durationMs > 0, row.durationMs <= 300000, row.sizeBytes > 0, row.sizeBytes <= PendingVoiceMigration.maxAudio,
            row.createdAt.timeIntervalSinceReferenceDate.isFinite,
            row.locallyResolvedAt?.timeIntervalSinceReferenceDate.isFinite != false,
            row.discardedAt?.timeIntervalSinceReferenceDate.isFinite != false else { throw Failure.invalid }
          if let audio = byName[id + ".wav"] {
            // A stable interrupted write may be shorter, including empty. Do not repair it or
            // fabricate a terminal. Equal-length damaged or oversized originals are rejected.
            guard audio.size <= row.sizeBytes, audio.size != row.sizeBytes || audio.sha256 == row.sha256 else { throw Failure.invalid }
          }
        }
      }
    }
  }
  struct Metadata: Decodable {
    let target: String, scopeKey: String, originalText: String, operationId: String, sha256: String
    let durationMs: Int, sizeBytes: Int, createdAt: Date
    let locallyResolvedAt: Date?, discardedAt: Date?
  }
  struct Bundle: Codable {
    let version: Int, originals: [Original]
    let checks: [DirectorySnapshot]
    let absent: [URL]
    let witnesses: [StableFile]
    let inventoriedFileCount: Int, inventoriedByteCount: Int
    enum CodingKeys: String, CodingKey { case version, originals }
    init(originals: [Original], checks: [DirectorySnapshot] = [], absent: [URL] = [], witnesses: [StableFile] = [], inventoriedFileCount: Int? = nil, inventoriedByteCount: Int? = nil) {
      version = 1; self.originals = originals; self.checks = checks; self.absent = absent; self.witnesses = witnesses
      self.inventoriedFileCount = inventoriedFileCount ?? originals.reduce(0) { $0 + $1.files.count }
      self.inventoriedByteCount = inventoriedByteCount ?? originals.flatMap(\.files).reduce(0) { $0 + $1.size }
    }
    init(from decoder: Decoder) throws {
      try PendingVoiceMigration.closed(decoder, ["version", "originals"]); let value = try decoder.container(keyedBy: CodingKeys.self)
      let decodedOriginals = try value.decode([Original].self, forKey: .originals)
      version = try value.decode(Int.self, forKey: .version); originals = decodedOriginals; checks = []; absent = []; witnesses = []
      inventoriedFileCount = decodedOriginals.reduce(0) { $0 + $1.files.count }; inventoriedByteCount = decodedOriginals.flatMap(\.files).reduce(0) { $0 + $1.size }
      try validate()
    }
    func encode(to encoder: Encoder) throws {
      try validate(); var value = encoder.container(keyedBy: CodingKeys.self)
      try value.encode(version, forKey: .version); try value.encode(originals, forKey: .originals)
      try unchanged()
    }
    var id: String { PendingVoiceMigration.sha(Data(("agent-hub/ios-pending-voice-bundle/v1\0" + originals.map(\.id).joined()).utf8)) }
    func unchanged(ignoringAbsence: URL? = nil) throws {
      try checks.forEach { try $0.unchanged() }; try originals.flatMap(\.files).forEach { try $0.bytes.source?.unchanged() }
      try witnesses.forEach { try $0.unchanged() }
      for path in absent where path != ignoringAbsence { guard !(try PendingVoiceMigration.exists(path)) else { throw Failure.unstable } }
    }
    func validate() throws {
      guard version == 1, !originals.isEmpty, originals.count <= PendingVoiceMigration.maxOriginals,
        originals.map(\.id) == Array(Set(originals.map(\.id))).sorted(), originals.reduce(0, { $0 + $1.files.count }) <= PendingVoiceMigration.maxFiles else { throw Failure.bounds }
      var total = 0; var names: [String: (Int, String)] = [:]
      for original in originals {
        try original.validate()
        for file in original.files {
          total = try PendingVoiceMigration.checkedAdd(total, file.size)
          if let prior = names[file.name] { guard prior.0 == file.size, prior.1 == file.sha256 else { throw Failure.conflict } }
          names[file.name] = (file.size, file.sha256)
        }
      }
      try unchanged()
    }
    func manifest() throws -> Data {
      let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]; return try encoder.encode(Manifest(self))
    }
  }

  struct Fingerprint: Equatable {
    let device: Int64, inode: UInt64, size: Int64, mode: UInt16, uid: UInt32, links: UInt16
    let modified: Int64, modifiedNanos: Int64, changed: Int64, changedNanos: Int64
    init(_ value: stat) {
      device = Int64(value.st_dev); inode = UInt64(value.st_ino); size = Int64(value.st_size); mode = UInt16(value.st_mode)
      uid = UInt32(value.st_uid); links = UInt16(value.st_nlink)
      modified = Int64(value.st_mtimespec.tv_sec); modifiedNanos = Int64(value.st_mtimespec.tv_nsec)
      changed = Int64(value.st_ctimespec.tv_sec); changedNanos = Int64(value.st_ctimespec.tv_nsec)
    }
  }
  static func identity(_ url: URL, directory: Bool) throws -> Fingerprint {
    var value = stat()
    guard lstat(url.path, &value) == 0, value.st_uid == geteuid(), (value.st_mode & 0o077) == 0,
      (value.st_mode & S_IFMT) == (directory ? S_IFDIR : S_IFREG), directory || value.st_nlink == 1 else { throw Failure.invalid }
    return Fingerprint(value)
  }
  final class DirectorySnapshot {
    let url: URL, fingerprint: Fingerprint, names: [String]
    init(_ url: URL) throws {
      self.url = url; fingerprint = try PendingVoiceMigration.identity(url, directory: true)
      names = try PendingVoiceMigration.boundedNames(url, maximum: PendingVoiceMigration.maxFiles + PendingVoiceMigration.maxOriginals + 1)
      guard names.count <= PendingVoiceMigration.maxFiles + PendingVoiceMigration.maxOriginals + 1 else { throw Failure.bounds }; try unchanged()
    }
    func unchanged() throws {
      guard try PendingVoiceMigration.identity(url, directory: true) == fingerprint,
        try PendingVoiceMigration.boundedNames(url, maximum: PendingVoiceMigration.maxFiles + PendingVoiceMigration.maxOriginals + 1) == names else { throw Failure.unstable }
    }
  }
  final class StableFile {
    let url: URL, size: Int, fingerprint: Fingerprint
    private(set) var descriptor: Int32 = -1
    private(set) var sha256 = ""
    init(_ url: URL, maximum: Int) throws {
      let observed = try PendingVoiceMigration.identity(url, directory: false)
      guard observed.size >= 0, observed.size <= maximum else { throw Failure.bounds }
      self.url = url; fingerprint = observed; size = Int(observed.size)
      descriptor = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
      guard descriptor >= 0 else { throw Failure.invalid }
      do { try unchanged(); sha256 = try stream(nil) } catch { Darwin.close(descriptor); descriptor = -1; throw error }
    }
    deinit { if descriptor >= 0 { Darwin.close(descriptor) } }
    func unchanged() throws {
      var value = stat()
      guard fstat(descriptor, &value) == 0, Fingerprint(value) == fingerprint,
        try PendingVoiceMigration.identity(url, directory: false) == fingerprint else { throw Failure.unstable }
    }
    private func stream(_ consume: ((Data) -> Void)?) throws -> String {
      try unchanged(); guard lseek(descriptor, 0, SEEK_SET) == 0 else { throw Failure.unstable }
      var buffer = [UInt8](repeating: 0, count: 64 * 1024), total = 0, hash = SHA256()
      while true {
        let count = buffer.withUnsafeMutableBytes { Darwin.read(descriptor, $0.baseAddress!, $0.count) }
        if count < 0 && errno == EINTR { continue }; guard count >= 0 else { throw Failure.unstable }; if count == 0 { break }
        guard count <= size - total else { throw Failure.unstable }; total += count
        let chunk = Data(buffer.prefix(count)); hash.update(data: chunk); consume?(chunk)
      }
      guard total == size else { throw Failure.unstable }; try unchanged()
      let digest = hash.finalize().map { String(format: "%02x", $0) }.joined()
      guard sha256.isEmpty || sha256 == digest else { throw Failure.unstable }; return digest
    }
    func data() throws -> Data { var value = Data(); value.reserveCapacity(size); _ = try stream { value.append($0) }; return value }
  }
  static func exists(_ url: URL) throws -> Bool {
    var value = stat(); if lstat(url.path, &value) == 0 { return true }
    guard errno == ENOENT else { throw Failure.invalid }; return false
  }
  static func supportDirectory() throws -> URL {
    try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: false)
  }
  struct Capture {
    let bundle: Bundle?, checks: [DirectorySnapshot], absent: [URL]
    var stageParent: StageParent? = nil
    func unchanged(ignoringAbsence: URL? = nil, allowingStage: URL? = nil) throws {
      if let allowingStage { try stageParent?.expectAddedStage(allowingStage.lastPathComponent) } else { try stageParent?.unchanged() }
      if let bundle { try bundle.unchanged(ignoringAbsence: ignoringAbsence); return }
      try checks.forEach { try $0.unchanged() }
      for path in absent where path != ignoringAbsence { guard !(try PendingVoiceMigration.exists(path)) else { throw Failure.unstable } }
    }
  }
  /// Existing inert batches are re-exported, so a second migration cannot silently lose evidence.
  static func snapshot(support: URL? = nil, includeLive: Bool = true) throws -> Bundle? {
    try PendingVoiceMigration.capture(support: support, includeLive: includeLive).bundle
  }
  static func capture(support: URL? = nil, includeLive: Bool = true) throws -> Capture {
    let support = try support ?? PendingVoiceMigration.supportDirectory()
    try PendingVoiceMigration.reconcileStages(support: support)
    var result = try PendingVoiceMigration.captureFinalized(support: support, includeLive: includeLive)
    result.stageParent = try StageParent(support)
    guard result.stageParent?.names.isEmpty == true else { throw StageFailure(reason: "stage appeared during capture", files: 0, bytes: 0) }
    try result.unchanged(); return result
  }
  static func captureFinalized(support: URL? = nil, includeLive: Bool = true) throws -> Capture {
    let support = try support ?? PendingVoiceMigration.supportDirectory()
    var originals: [Original] = [], checks: [DirectorySnapshot] = [], absent: [URL] = []
    var witnesses: [StableFile] = []
    var inventoriedFiles = 0, inventoriedBytes = 0
    func original(_ directory: DirectorySnapshot, expected: [Descriptor]? = nil) throws -> Original {
      guard !directory.names.isEmpty, directory.names.count <= PendingVoiceMigration.maxFiles else { throw Failure.bounds }
      var files: [File] = []
      for name in directory.names {
        _ = try PendingVoiceMigration.operation(name)
        inventoriedFiles += 1; guard inventoriedFiles <= PendingVoiceMigration.maxFiles else { throw Failure.bounds }
        let fingerprint = try PendingVoiceMigration.identity(directory.url.appendingPathComponent(name), directory: false)
        guard fingerprint.size >= 0, fingerprint.size <= (name.hasSuffix(".json") ? PendingVoiceMigration.maxMetadata : PendingVoiceMigration.maxAudio) else { throw Failure.bounds }
        inventoriedBytes = try PendingVoiceMigration.checkedAdd(inventoriedBytes, Int(fingerprint.size))
        let source = try StableFile(directory.url.appendingPathComponent(name), maximum: name.hasSuffix(".json") ? PendingVoiceMigration.maxMetadata : PendingVoiceMigration.maxAudio)
        // Graph-ID deduplication must not drop a live/archived physical file's witness.
        witnesses.append(source); files.append(File(name: name, source: source))
      }
      let value = Original(files: files); try value.validate()
      if let expected {
        guard value.files.map(\.descriptorValue) == expected else { throw Failure.invalid }
      }
      checks.append(directory); return value
    }
    let live = support.appendingPathComponent(PendingVoiceMigration.liveDirectory, isDirectory: true)
    if includeLive, try PendingVoiceMigration.exists(live) {
      let directory = try DirectorySnapshot(live)
      if !directory.names.isEmpty { originals.append(try original(directory)) } else { checks.append(directory) }
    } else if includeLive { absent.append(live) }
    let archive = support.appendingPathComponent(PendingVoiceMigration.archiveDirectory, isDirectory: true)
    if try PendingVoiceMigration.exists(archive) {
      let root = try DirectorySnapshot(archive); checks.append(root)
      guard root.names.count <= PendingVoiceMigration.maxOriginals else { throw Failure.bounds }
      for name in root.names {
        guard PendingVoiceMigration.digest(name) else { throw Failure.invalid }
        let batch = try DirectorySnapshot(archive.appendingPathComponent(name, isDirectory: true)); checks.append(batch)
        let manifestFile = try StableFile(batch.url.appendingPathComponent("manifest.json"), maximum: 128 * 1024)
        witnesses.append(manifestFile)
        let manifestBytes = try manifestFile.data(); try VoiceMigrationJSON.uniqueKeys(manifestBytes)
        let manifest = try JSONDecoder().decode(Manifest.self, from: manifestBytes)
        guard manifest.version == 1, !manifest.originals.isEmpty, manifest.originals.count <= PendingVoiceMigration.maxOriginals else { throw Failure.invalid }
        var batchOriginals: [Original] = []
        for row in manifest.originals {
          guard PendingVoiceMigration.digest(row.id) else { throw Failure.invalid }
          let value = try original(DirectorySnapshot(batch.url.appendingPathComponent(row.id, isDirectory: true)), expected: row.files)
          guard value.id == row.id else { throw Failure.invalid }; batchOriginals.append(value)
        }
        let value = Bundle(originals: batchOriginals); try value.validate()
        let savedJournal = try PendingVoiceMigration.validateSavedJournal(batch.url, manifest: manifest, manifestBytes: manifestBytes)
        if let savedJournal { witnesses.append(savedJournal) }
        guard value.id == name, batch.names == (["manifest.json"] + (savedJournal == nil ? [] : ["journal.json"]) + batchOriginals.map(\.id)).sorted() else { throw Failure.invalid }
        try manifestFile.unchanged(); originals += batchOriginals
      }
    } else { absent.append(archive) }
    if originals.isEmpty { let result = Capture(bundle: nil, checks: checks, absent: absent); try result.unchanged(); return result }
    var unique: [String: Original] = [:]
    for value in originals { if let prior = unique[value.id] { guard prior.files.map(\.sha256) == value.files.map(\.sha256) else { throw Failure.conflict } }; unique[value.id] = value }
    let result = Bundle(originals: unique.values.sorted { $0.id < $1.id }, checks: checks, absent: absent, witnesses: witnesses, inventoriedFileCount: inventoriedFiles, inventoriedByteCount: inventoriedBytes); try result.validate()
    return Capture(bundle: result, checks: checks, absent: absent)
  }
  /// Read-only validation precedes every destination write. Existing original targets stay literal.
  static func prepareRestore(_ incoming: Bundle?, support: URL? = nil) throws -> Restore? {
    let support = try support ?? PendingVoiceMigration.supportDirectory(), captured = try PendingVoiceMigration.capture(support: support), current = captured.bundle
    guard let incoming else { return nil }; try incoming.validate()
    try PendingVoiceMigration.destinationBudget(incoming, current: current, support: support)
    try PendingVoiceMigration.validateOriginalUnion([current, incoming])
    return Restore(incoming: incoming, support: support, previous: captured)
  }
  /// One immutable graph assertion for ordinary import and cold-stage admission.
  /// Physical copies are budgeted separately; identical graph IDs are not duplicated here.
  static func validateOriginalUnion(_ bundles: [Bundle?]) throws {
    var originals: [String: Original] = [:], names: [String: Descriptor] = [:]
    for case let bundle? in bundles {
      try bundle.validate()
      for original in bundle.originals {
        if let previous = originals[original.id] { guard previous.files.map(\.descriptorValue) == original.files.map(\.descriptorValue) else { throw Failure.conflict } }
        for file in original.files {
          if let previous = names[file.name] { guard previous == file.descriptorValue else { throw Failure.conflict } }
          names[file.name] = file.descriptorValue
        }
        originals[original.id] = original
      }
    }
    if !originals.isEmpty { try Bundle(originals: originals.values.sorted { $0.id < $1.id }).validate() }
  }
  static func destinationBudget(_ incoming: Bundle, current: Bundle?, support: URL) throws {
    let root = support.appendingPathComponent(PendingVoiceMigration.archiveDirectory, isDirectory: true)
    if try PendingVoiceMigration.exists(root.appendingPathComponent(incoming.id, isDirectory: true)) { return }
    guard (current?.inventoriedFileCount ?? 0) + incoming.inventoriedFileCount <= PendingVoiceMigration.maxFiles,
      Set((current?.originals.map(\.id) ?? []) + incoming.originals.map(\.id)).count <= PendingVoiceMigration.maxOriginals else { throw Failure.bounds }
    _ = try PendingVoiceMigration.checkedAdd(current?.inventoriedByteCount ?? 0, incoming.inventoriedByteCount)
    if try PendingVoiceMigration.exists(root) { guard try DirectorySnapshot(root).names.count < PendingVoiceMigration.maxOriginals else { throw Failure.bounds } }
  }
  final class Restore {
    let incoming: Bundle, support: URL, previous: Capture
    private var published: URL?, stage: URL?, lease: StageLease?
    init(incoming: Bundle, support: URL, previous: Capture) { self.incoming = incoming; self.support = support; self.previous = previous }
    func publish() throws {
      try incoming.validate(); try previous.unchanged()
      let owner = try StageNamespaceLease(support)
      defer { withExtendedLifetime(owner) {} }
      try PendingVoiceMigration.reconcileStagesHeld(support: support, owner: owner)
      let fresh = try PendingVoiceMigration.captureFinalized(support: support).bundle
      guard fresh?.id == previous.bundle?.id else { throw Failure.conflict }
      try PendingVoiceMigration.destinationBudget(incoming, current: fresh, support: support)
      try PendingVoiceMigration.validateOriginalUnion([fresh, incoming])
      let fm = FileManager.default, root = support.appendingPathComponent(PendingVoiceMigration.archiveDirectory, isDirectory: true)
      let target = root.appendingPathComponent(incoming.id, isDirectory: true)
      if try PendingVoiceMigration.exists(target) { return }
      do {
        if !(try PendingVoiceMigration.exists(root)) { try fm.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) }
        _ = try PendingVoiceMigration.identity(root, directory: true)
        let rootSnapshot = try DirectorySnapshot(root)
        let prepared = try PendingVoiceMigration.beginStage(incoming, support: support, owner: owner)
        let temporary = prepared.0; stage = temporary; lease = prepared.1
        for original in incoming.originals {
          let folder = temporary.appendingPathComponent(original.id, isDirectory: true)
          try fm.createDirectory(at: folder, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
          for file in original.files { try PendingVoiceMigration.writePrivate(file.bytes.data(), folder.appendingPathComponent(file.name)) }
          try PendingVoiceMigration.syncDirectory(folder)
        }
        try PendingVoiceMigration.writePrivate(incoming.manifest(), temporary.appendingPathComponent("manifest.json"))
        try PendingVoiceMigration.syncDirectory(temporary)
        let staged = try StageInspection(temporary, held: lease).complete()
        guard staged.id == incoming.id else { throw Failure.conflict }; try staged.unchanged()
        try incoming.unchanged(); try previous.unchanged(ignoringAbsence: root, allowingStage: temporary); try rootSnapshot.unchanged()
        guard renamex_np(temporary.path, target.path, UInt32(RENAME_EXCL)) == 0 else { throw Failure.conflict }
        stage = nil; published = target; lease = nil
        try PendingVoiceMigration.syncDirectory(root); try PendingVoiceMigration.syncDirectory(support)
      } catch { throw preserveEvidenceAfterFailure(error) }
    }
    /// A failed business-state restore never deletes the only local original evidence.
    /// Published inert batches and unfinished stages remain counted and discoverable.
    func preserveEvidenceAfterFailure(_ error: Error) -> Error {
      lease = nil
      guard stage != nil || published != nil else { return error }
      return EvidenceRetentionFailure(original: error, published: published != nil, files: incoming.inventoriedFileCount, bytes: incoming.inventoriedByteCount)
    }
  }

}

/// Reject duplicate (including escaped-equivalent) keys before Foundation can discard them.
/// Values are scanned without copying base64 audio; Foundation remains the value/type decoder.
enum VoiceMigrationJSON {
  static func object(_ data: Data) throws -> [String: Any] {
    try uniqueKeys(data)
    guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw PendingVoiceMigration.Failure.invalid }; return value
  }
  static func uniqueKeys(_ data: Data) throws {
    _ = try topLevelKeys(data)
  }
  static func topLevelKeys(_ data: Data) throws -> Set<String> {
    try data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
      var parser = Parser(bytes: bytes); try parser.value(0); parser.space()
      guard parser.position == bytes.count else { throw PendingVoiceMigration.Failure.invalid }
      return parser.topKeys
    }
  }
  private struct Parser {
    let bytes: UnsafeRawBufferPointer; var position = 0, nodes = 0
    var topKeys = Set<String>()
    mutating func space() { while position < bytes.count && [9, 10, 13, 32].contains(bytes[position]) { position += 1 } }
    mutating func consume(_ byte: UInt8) throws { space(); guard position < bytes.count, bytes[position] == byte else { throw PendingVoiceMigration.Failure.invalid }; position += 1 }
    mutating func string() throws -> Range<Int> {
      space(); let start = position; try consume(34)
      while position < bytes.count {
        let byte = bytes[position]; position += 1
        if byte == 34 { return start..<position }
        guard byte >= 32 else { throw PendingVoiceMigration.Failure.invalid }
        if byte == 92 {
          guard position < bytes.count else { throw PendingVoiceMigration.Failure.invalid }
          let escape = bytes[position]; position += 1
          if escape == 117 {
            guard position + 4 <= bytes.count else { throw PendingVoiceMigration.Failure.invalid }
            for _ in 0..<4 { guard (48...57).contains(bytes[position]) || (65...70).contains(bytes[position]) || (97...102).contains(bytes[position]) else { throw PendingVoiceMigration.Failure.invalid }; position += 1 }
          } else { guard [34, 92, 47, 98, 102, 110, 114, 116].contains(escape) else { throw PendingVoiceMigration.Failure.invalid } }
        }
      }
      throw PendingVoiceMigration.Failure.invalid
    }
    mutating func value(_ depth: Int) throws {
      nodes += 1; guard depth <= 128, nodes <= 1_000_000 else { throw PendingVoiceMigration.Failure.bounds }
      space(); guard position < bytes.count else { throw PendingVoiceMigration.Failure.invalid }
      if bytes[position] == 123 {
        position += 1; space(); var keys = Set<String>()
        if position < bytes.count && bytes[position] == 125 { position += 1; return }
        while true {
          let range = try string(); guard range.count <= 8192 else { throw PendingVoiceMigration.Failure.bounds }
          guard let key = try JSONSerialization.jsonObject(with: Data(bytes[range]), options: [.fragmentsAllowed]) as? String, keys.insert(key).inserted else { throw PendingVoiceMigration.Failure.invalid }
          try consume(58); try value(depth + 1); space()
          guard position < bytes.count else { throw PendingVoiceMigration.Failure.invalid }
          if bytes[position] == 125 { position += 1; if depth == 0 { topKeys = keys }; return }; try consume(44)
        }
      }
      if bytes[position] == 91 {
        position += 1; space(); if position < bytes.count && bytes[position] == 93 { position += 1; return }
        while true { try value(depth + 1); space(); guard position < bytes.count else { throw PendingVoiceMigration.Failure.invalid }; if bytes[position] == 93 { position += 1; return }; try consume(44) }
      }
      if bytes[position] == 34 { _ = try string(); return }
      let start = position
      while position < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[position]) { position += 1 }
      guard position > start else { throw PendingVoiceMigration.Failure.invalid }
    }
  }
}
