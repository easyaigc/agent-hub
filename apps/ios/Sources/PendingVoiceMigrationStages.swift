import Foundation
import CryptoKit
import Darwin

extension PendingVoiceMigration {
  static let stagePrefix = ".pending-voice-migration-"
  static let maxJournalBytes = 128 * 1024
  static func boundedNames(_ url: URL, maximum: Int) throws -> [String] {
    var before = stat()
    guard lstat(url.path, &before) == 0, before.st_uid == geteuid(), (before.st_mode & S_IFMT) == S_IFDIR,
      (before.st_mode & 0o022) == 0, let directory = opendir(url.path) else { throw Failure.invalid }
    defer { closedir(directory) }; var opened = stat()
    guard fstat(dirfd(directory), &opened) == 0, Fingerprint(opened) == Fingerprint(before) else { throw Failure.unstable }
    var names: [String] = []
    while true {
      errno = 0
      guard let entry = readdir(directory) else { guard errno == 0 else { throw Failure.unstable }; break }
      var rawName = entry.pointee.d_name
      let capacity = Int(entry.pointee.d_namlen) + 1
      guard capacity > 0, capacity <= MemoryLayout.size(ofValue: rawName) else { throw Failure.invalid }
      let name: String? = withUnsafePointer(to: &rawName) { pointer in
        pointer.withMemoryRebound(to: CChar.self, capacity: capacity) { guard $0[capacity - 1] == 0 else { return nil }; return String(validatingUTF8: $0) }
      }
      guard let name else { throw Failure.invalid }; if name == "." || name == ".." { continue }
      guard names.count < maximum else { throw Failure.bounds }; names.append(name)
    }
    var ended = stat(), current = stat()
    guard fstat(dirfd(directory), &ended) == 0, lstat(url.path, &current) == 0,
      Fingerprint(ended) == Fingerprint(before), Fingerprint(current) == Fingerprint(before) else { throw Failure.unstable }
    return names.sorted()
  }
  final class StageNamespaceLease {
    let support: URL, descriptor: Int32
    init(_ support: URL) throws {
      self.support = support; var parent = stat()
      guard lstat(support.path, &parent) == 0, parent.st_uid == geteuid(), (parent.st_mode & S_IFMT) == S_IFDIR,
        (parent.st_mode & 0o022) == 0 else { throw Failure.invalid }
      let path = support.appendingPathComponent("migration-pending-voice-coordinator-v1.lock")
      let created = Darwin.open(path.path, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
      let fd = created >= 0 ? created : errno == EEXIST ? Darwin.open(path.path, O_RDWR | O_NOFOLLOW | O_CLOEXEC) : -1
      guard fd >= 0 else { throw Failure.invalid }
      var value = stat()
      guard fstat(fd, &value) == 0, value.st_uid == geteuid(), (value.st_mode & S_IFMT) == S_IFREG,
        value.st_nlink == 1, (value.st_mode & 0o077) == 0, value.st_size == 0 else { Darwin.close(fd); throw Failure.invalid }
      do { guard try PendingVoiceMigration.identity(path, directory: false) == Fingerprint(value) else { throw Failure.unstable } }
      catch { Darwin.close(fd); throw error }
      guard flock(fd, LOCK_EX | LOCK_NB) == 0 else { Darwin.close(fd); throw StageFailure(reason: "another migration owns originals", files: 0, bytes: 0) }
      descriptor = fd
    }
    deinit { _ = flock(descriptor, LOCK_UN); Darwin.close(descriptor) }
  }
  struct StageFailure: LocalizedError {
    let reason: String, files: Int, bytes: Int
    var errorDescription: String? {
      L10n.tr("迁移暂存资料已保留，完整性或配额尚未确认，暂时不能继续迁移。") + " (reserved: \(files) files, \(bytes) bytes; \(reason))"
    }
  }
  struct EvidenceRetentionFailure: LocalizedError {
    let original: Error, published: Bool, files: Int, bytes: Int
    var errorDescription: String? {
      let message = published
        ? L10n.tr("会话恢复未完成。只读迁移原件仍保留，后续迁移会再次核对并计入配额。")
        : L10n.tr("会话恢复未完成。暂存原件仍保留，再次迁移前会核对其完整性与配额。")
      return message + " (\(files) files, \(bytes) bytes)"
    }
  }
  struct StageNode: Codable, Equatable {
    let device: Int64, inode: UInt64, uid: UInt32, mode: UInt16, birth: Int64, birthNanos: Int64
    enum CodingKeys: String, CodingKey { case device, inode, uid, mode, birth, birthNanos }
    init(_ url: URL) throws {
      _ = try PendingVoiceMigration.identity(url, directory: true); var value = stat()
      guard lstat(url.path, &value) == 0 else { throw Failure.unstable }
      device = Int64(value.st_dev); inode = UInt64(value.st_ino); uid = UInt32(value.st_uid); mode = UInt16(value.st_mode)
      birth = Int64(value.st_birthtimespec.tv_sec); birthNanos = Int64(value.st_birthtimespec.tv_nsec)
    }
    init(from decoder: Decoder) throws {
      try PendingVoiceMigration.closed(decoder, ["device", "inode", "uid", "mode", "birth", "birthNanos"])
      let c = try decoder.container(keyedBy: CodingKeys.self)
      device = try c.decode(Int64.self, forKey: .device); inode = try c.decode(UInt64.self, forKey: .inode)
      uid = try c.decode(UInt32.self, forKey: .uid); mode = try c.decode(UInt16.self, forKey: .mode)
      birth = try c.decode(Int64.self, forKey: .birth); birthNanos = try c.decode(Int64.self, forKey: .birthNanos)
    }
  }
  static func descriptorIdentity(_ files: [Descriptor]) -> String {
    var hash = SHA256(); hash.update(data: Data("agent-hub/ios-pending-voice-original/v1\0".utf8))
    func field(_ data: Data) { var size = UInt32(data.count).bigEndian; withUnsafeBytes(of: &size) { hash.update(data: Data($0)) }; hash.update(data: data) }
    for file in files { field(Data(file.name.utf8)); field(Data(String(file.size).utf8)); field(Data(file.sha256.utf8)) }
    return hash.finalize().map { String(format: "%02x", $0) }.joined()
  }
  static func manifestIdentity(_ manifest: Manifest) throws -> String {
    guard manifest.version == 1, !manifest.originals.isEmpty, manifest.originals.count <= PendingVoiceMigration.maxOriginals,
      manifest.originals.map(\.id) == Array(Set(manifest.originals.map(\.id))).sorted() else { throw Failure.invalid }
    var count = 0, bytes = 0
    for original in manifest.originals {
      guard !original.files.isEmpty, original.files.map(\.name) == Array(Set(original.files.map(\.name))).sorted() else { throw Failure.invalid }
      for file in original.files {
        _ = try PendingVoiceMigration.operation(file.name); count += 1; guard count <= PendingVoiceMigration.maxFiles, PendingVoiceMigration.digest(file.sha256), file.size >= 0,
          file.size <= (file.name.hasSuffix(".json") ? PendingVoiceMigration.maxMetadata : PendingVoiceMigration.maxAudio) else { throw Failure.bounds }
        bytes = try PendingVoiceMigration.checkedAdd(bytes, file.size)
      }
      guard PendingVoiceMigration.descriptorIdentity(original.files) == original.id else { throw Failure.invalid }
    }
    return PendingVoiceMigration.sha(Data(("agent-hub/ios-pending-voice-bundle/v1\0" + manifest.originals.map(\.id).joined()).utf8))
  }
  struct StageJournal: Codable {
    let version: Int, stageName: String, directory: StageNode, batchId: String, manifest: Manifest
    let manifestSize: Int, manifestSha256: String
    enum CodingKeys: String, CodingKey { case version, stageName, directory, batchId, manifest, manifestSize, manifestSha256 }
    init(bundle: Bundle, stage: URL) throws {
      version = 1; stageName = stage.lastPathComponent; directory = try StageNode(stage); batchId = bundle.id; manifest = Manifest(bundle)
      let data = try bundle.manifest(); manifestSize = data.count; manifestSha256 = PendingVoiceMigration.sha(data)
      try validate()
    }
    init(from decoder: Decoder) throws {
      try PendingVoiceMigration.closed(decoder, ["version", "stageName", "directory", "batchId", "manifest", "manifestSize", "manifestSha256"])
      let c = try decoder.container(keyedBy: CodingKeys.self)
      version = try c.decode(Int.self, forKey: .version); stageName = try c.decode(String.self, forKey: .stageName)
      directory = try c.decode(StageNode.self, forKey: .directory); batchId = try c.decode(String.self, forKey: .batchId)
      manifest = try c.decode(Manifest.self, forKey: .manifest); manifestSize = try c.decode(Int.self, forKey: .manifestSize)
      manifestSha256 = try c.decode(String.self, forKey: .manifestSha256); try validate()
    }
    func validate() throws {
      let suffix = String(stageName.dropFirst(PendingVoiceMigration.stagePrefix.count))
      guard version == 1, stageName.hasPrefix(PendingVoiceMigration.stagePrefix), UUID(uuidString: suffix)?.uuidString.lowercased() == suffix,
        directory.uid == geteuid(), directory.inode > 0, (directory.mode & UInt16(S_IFMT)) == UInt16(S_IFDIR),
        (directory.mode & 0o077) == 0, (0..<1_000_000_000).contains(directory.birthNanos),
        manifestSize > 0, manifestSize <= PendingVoiceMigration.maxJournalBytes, PendingVoiceMigration.digest(manifestSha256),
        try PendingVoiceMigration.manifestIdentity(manifest) == batchId else { throw Failure.invalid }
    }
    func data() throws -> Data { let e = JSONEncoder(); e.outputFormatting = [.sortedKeys]; let data = try e.encode(self); guard data.count <= PendingVoiceMigration.maxJournalBytes else { throw Failure.bounds }; return data }
    var expected: [String: Int] {
      Dictionary(uniqueKeysWithValues: manifest.originals.flatMap { original in original.files.map { (original.id + "/" + $0.name, $0.size) } })
    }
  }
  final class StageLease {
    let file: StableFile
    init(_ url: URL) throws {
      file = try StableFile(url, maximum: PendingVoiceMigration.maxJournalBytes)
      guard flock(file.descriptor, LOCK_EX | LOCK_NB) == 0 else { throw Failure.unstable }
    }
    deinit { _ = flock(file.descriptor, LOCK_UN) }
  }
  struct StageParent {
    let url: URL, fingerprint: Fingerprint, names: [String]
    init(_ url: URL) throws {
      self.url = url; var value = stat()
      // AppSupport may be traversable inside the app sandbox, but never writable by another uid/group.
      guard lstat(url.path, &value) == 0, value.st_uid == geteuid(), (value.st_mode & S_IFMT) == S_IFDIR,
        (value.st_mode & 0o022) == 0 else { throw Failure.invalid }
      fingerprint = Fingerprint(value)
      let all = try PendingVoiceMigration.boundedNames(url, maximum: 1024)
      names = all.filter { $0.hasPrefix(PendingVoiceMigration.stagePrefix) }.sorted()
      guard names.count <= PendingVoiceMigration.maxOriginals else { throw Failure.bounds }
      try unchanged()
    }
    func unchanged() throws {
      var value = stat(); guard lstat(url.path, &value) == 0, Fingerprint(value) == fingerprint,
        try PendingVoiceMigration.boundedNames(url, maximum: 1024).filter({ $0.hasPrefix(PendingVoiceMigration.stagePrefix) }) == names else { throw Failure.unstable }
    }
    func expectAddedStage(_ name: String) throws {
      let current = try StageParent(url)
      guard current.fingerprint.device == fingerprint.device, current.fingerprint.inode == fingerprint.inode,
        current.fingerprint.uid == fingerprint.uid, current.fingerprint.mode == fingerprint.mode,
        current.names == (names + [name]).sorted() else { throw Failure.unstable }
      try current.unchanged()
    }
  }
  final class StageInspection {
    let url: URL
    var directory: DirectorySnapshot?, journal: StageJournal?, lease: StageLease?, problem: Error?
    var actual: [String: Int] = [:], controls: [StableFile] = []
    init(_ url: URL, held: StageLease? = nil) {
      self.url = url
      do {
        let folder = try DirectorySnapshot(url); directory = folder
        do {
          let lease = try held ?? StageLease(url.appendingPathComponent("journal.json")); self.lease = lease
          let bytes = try lease.file.data(); try VoiceMigrationJSON.uniqueKeys(bytes)
          let journal = try JSONDecoder().decode(StageJournal.self, from: bytes); self.journal = journal
          guard journal.stageName == url.lastPathComponent, journal.directory == (try StageNode(url)) else { throw Failure.conflict }
          controls.append(lease.file)
        } catch { problem = error }
        // A bounded shallow inventory counts unexpected files too, without following links.
        for name in folder.names {
          let child = url.appendingPathComponent(name); var value = stat()
          guard lstat(child.path, &value) == 0 else { throw Failure.unstable }
          if name == "journal.json" || name == "manifest.json" {
            guard value.st_size >= 0, value.st_size <= PendingVoiceMigration.maxJournalBytes else { throw Failure.bounds }
            _ = try PendingVoiceMigration.identity(child, directory: false); continue
          }
          if (value.st_mode & S_IFMT) == S_IFDIR {
            let nested = try DirectorySnapshot(child)
            for file in nested.names {
              let path = child.appendingPathComponent(file); var item = stat(); guard lstat(path.path, &item) == 0, item.st_size >= 0 else { throw Failure.invalid }
              actual[name + "/" + file] = Int(item.st_size)
              guard actual.count <= PendingVoiceMigration.maxFiles else { throw Failure.bounds }
              _ = try PendingVoiceMigration.identity(path, directory: false)
            }
            try nested.unchanged()
          } else {
            guard value.st_size >= 0 else { throw Failure.invalid }; actual[name] = Int(value.st_size)
            _ = try PendingVoiceMigration.identity(child, directory: false)
          }
        }
        try folder.unchanged()
      } catch { if problem == nil { problem = error } }
    }
    var reserved: [String: Int] {
      var value = journal?.expected ?? [:]
      for (name, size) in actual { value[name] = max(value[name] ?? 0, size) }; return value
    }
    func complete() throws -> Bundle {
      if let problem { throw problem }
      guard let journal, let directory, actual == journal.expected else { throw Failure.invalid }
      let manifestFile = try StableFile(url.appendingPathComponent("manifest.json"), maximum: PendingVoiceMigration.maxJournalBytes)
      let data = try manifestFile.data(); try VoiceMigrationJSON.uniqueKeys(data)
      let manifest = try JSONDecoder().decode(Manifest.self, from: data)
      guard data.count == journal.manifestSize, manifestFile.sha256 == journal.manifestSha256,
        try PendingVoiceMigration.manifestIdentity(manifest) == journal.batchId,
        directory.names == (["journal.json", "manifest.json"] + manifest.originals.map(\.id)).sorted() else { throw Failure.invalid }
      var originals: [Original] = [], checks = [directory]
      for row in manifest.originals {
        let child = try DirectorySnapshot(url.appendingPathComponent(row.id, isDirectory: true)); checks.append(child)
        guard child.names == row.files.map(\.name) else { throw Failure.invalid }
        let files = try row.files.map { expected -> File in
          let file = File(name: expected.name, source: try StableFile(child.url.appendingPathComponent(expected.name), maximum: expected.name.hasSuffix(".json") ? PendingVoiceMigration.maxMetadata : PendingVoiceMigration.maxAudio))
          guard file.descriptorValue == expected else { throw Failure.invalid }; return file
        }
        let original = Original(files: files); guard original.id == row.id else { throw Failure.invalid }; originals.append(original)
      }
      let bundle = Bundle(originals: originals, checks: checks, witnesses: controls + [manifestFile]); try bundle.validate()
      guard bundle.id == journal.batchId else { throw Failure.invalid }; return bundle
    }
  }
  static func syncDirectory(_ directory: URL) throws {
    let fd = Darwin.open(directory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
    guard fd >= 0 else { throw Failure.invalid }; defer { Darwin.close(fd) }
    guard fsync(fd) == 0 else { throw Failure.unstable }
  }
  static func writePrivate(_ data: Data, _ url: URL) throws {
    let fd = Darwin.open(url.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
    guard fd >= 0 else { throw Failure.conflict }
    let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    try FileManager.default.setAttributes([.posixPermissions: 0o600, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
    try handle.write(contentsOf: data); try handle.synchronize(); try handle.close()
  }
  static func beginStage(_ incoming: Bundle, support: URL, owner: StageNamespaceLease) throws -> (URL, StageLease) {
    guard owner.support == support else { throw Failure.invalid }
    let stage = support.appendingPathComponent(PendingVoiceMigration.stagePrefix + UUID().uuidString.lowercased(), isDirectory: true)
    try FileManager.default.createDirectory(at: stage, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
    // A failed journal write leaves a visible, blocking unknown stage; never delete its only bytes.
    do {
      let journal = try StageJournal(bundle: incoming, stage: stage)
      try PendingVoiceMigration.writePrivate(journal.data(), stage.appendingPathComponent("journal.json")); try PendingVoiceMigration.syncDirectory(stage); try PendingVoiceMigration.syncDirectory(support)
      return (stage, try StageLease(stage.appendingPathComponent("journal.json")))
    } catch { throw StageFailure(reason: "ownership journal incomplete; evidence retained", files: incoming.inventoriedFileCount, bytes: incoming.inventoriedByteCount) }
  }
  static func validateSavedJournal(_ folder: URL, manifest: Manifest, manifestBytes: Data) throws -> StableFile? {
    let path = folder.appendingPathComponent("journal.json"); guard try PendingVoiceMigration.exists(path) else { return nil }
    let file = try StableFile(path, maximum: PendingVoiceMigration.maxJournalBytes), bytes = try file.data(); try VoiceMigrationJSON.uniqueKeys(bytes)
    let journal = try JSONDecoder().decode(StageJournal.self, from: bytes)
    guard try PendingVoiceMigration.manifestIdentity(manifest) == journal.batchId else { throw Failure.conflict }
    guard manifestBytes.count == journal.manifestSize, PendingVoiceMigration.sha(manifestBytes) == journal.manifestSha256 else { throw Failure.conflict }
    return file // Historical ownership identity is retained; it is not rebound to another directory.
  }
  struct SavedStageProof {
    var files: [(URL, Fingerprint)], directories: [DirectorySnapshot]
    func unchanged() throws {
      try directories.forEach { try $0.unchanged() }
      for (url, expected) in files { guard try PendingVoiceMigration.identity(url, directory: false) == expected else { throw Failure.unstable } }
    }
  }
  static func sameFile(_ first: URL, _ second: URL, maximum: Int) throws -> [(URL, Fingerprint)] {
    let a = try StableFile(first, maximum: maximum), b = try StableFile(second, maximum: maximum)
    guard a.size == b.size, a.sha256 == b.sha256 else { throw Failure.conflict }
    var left = [UInt8](repeating: 0, count: 64 * 1024), right = left, offset = 0
    func readExactly(_ fd: Int32, _ buffer: inout [UInt8], _ count: Int, _ offset: Int) throws {
      var read = 0
      while read < count {
        let n = buffer.withUnsafeMutableBytes { pread(fd, $0.baseAddress!.advanced(by: read), count - read, off_t(offset + read)) }
        if n < 0 && errno == EINTR { continue }; guard n > 0 else { throw Failure.unstable }; read += n
      }
    }
    while offset < a.size {
      let count = min(left.count, a.size - offset)
      try readExactly(a.descriptor, &left, count, offset); try readExactly(b.descriptor, &right, count, offset)
      guard left.prefix(count).elementsEqual(right.prefix(count)) else { throw Failure.conflict }; offset += count
    }
    try a.unchanged(); try b.unchanged()
    // A readable cache hit is insufficient before deleting the stage's only other copy.
    let fd = Darwin.open(second.path, O_RDWR | O_NOFOLLOW | O_CLOEXEC)
    guard fd >= 0 else { throw Failure.invalid }; defer { Darwin.close(fd) }
    var value = stat(); guard fstat(fd, &value) == 0, Fingerprint(value) == b.fingerprint, fsync(fd) == 0 else { throw Failure.unstable }
    try b.unchanged()
    return [(first, a.fingerprint), (second, b.fingerprint)]
  }
  static func proveSavedStage(_ stage: StageInspection, target: URL) throws -> SavedStageProof {
    _ = try stage.complete()
    guard let source = stage.directory else { throw Failure.invalid }
    let final = try DirectorySnapshot(target)
    let expectedNames = source.names
    guard final.names == expectedNames else { throw Failure.conflict }
    var proof = SavedStageProof(files: [], directories: [source, final])
    for name in expectedNames {
      if name == "journal.json" || name == "manifest.json" { proof.files += try PendingVoiceMigration.sameFile(source.url.appendingPathComponent(name), final.url.appendingPathComponent(name), maximum: PendingVoiceMigration.maxJournalBytes) }
      else {
        let a = try DirectorySnapshot(source.url.appendingPathComponent(name)), b = try DirectorySnapshot(final.url.appendingPathComponent(name))
        guard a.names == b.names else { throw Failure.conflict }
        proof.directories += [a, b]
        for file in a.names { proof.files += try PendingVoiceMigration.sameFile(a.url.appendingPathComponent(file), b.url.appendingPathComponent(file), maximum: file.hasSuffix(".json") ? PendingVoiceMigration.maxMetadata : PendingVoiceMigration.maxAudio) }
        try a.unchanged(); try b.unchanged(); try PendingVoiceMigration.syncDirectory(b.url)
      }
    }
    try source.unchanged(); try final.unchanged(); try PendingVoiceMigration.syncDirectory(final.url)
    try proof.unchanged(); return proof
  }
  static func removeOnlySavedStage(_ stage: StageInspection, target: URL) throws {
    let proof = try PendingVoiceMigration.proveSavedStage(stage, target: target)
    guard let source = stage.directory else { throw Failure.invalid }
    try PendingVoiceMigration.syncDirectory(target.deletingLastPathComponent())
    try PendingVoiceMigration.syncDirectory(source.url.deletingLastPathComponent())
    try proof.unchanged()
    // This exact checked stage is the only deletion entry. All of its actual file bytes now
    // exist independently in the closed finalized batch, including its ownership journal.
    try FileManager.default.removeItem(at: source.url)
  }
  static func reconcileStages(support: URL) throws {
    do {
      let owner = try StageNamespaceLease(support)
      try PendingVoiceMigration.reconcileStagesHeld(support: support, owner: owner)
    } catch let error as StageFailure { throw error }
    catch { throw StageFailure(reason: "stage inventory or ownership is unknown; usage unverified", files: 0, bytes: 0) }
  }
  static func reconcileStagesHeld(support: URL, owner: StageNamespaceLease) throws {
    guard owner.support == support else { throw Failure.invalid }
    defer { withExtendedLifetime(owner) {} }
    var parent = try StageParent(support); guard !parent.names.isEmpty else { return }
    let inspections = parent.names.map { StageInspection(support.appendingPathComponent($0, isDirectory: true)) }
    var current = try PendingVoiceMigration.captureFinalized(support: support, includeLive: true)
    var files = 0, bytes = 0
    func failure(_ reason: String) -> StageFailure { StageFailure(reason: reason, files: files, bytes: bytes) }
    func charge(_ stages: [StageInspection], existing: Bundle?) throws {
      files = existing?.inventoriedFileCount ?? 0; bytes = existing?.inventoriedByteCount ?? 0
      var ids = Set(existing?.originals.map(\.id) ?? [])
      for stage in stages {
        files += stage.reserved.count
        for size in stage.reserved.values { let added = bytes.addingReportingOverflow(size); guard !added.overflow else { throw failure("quota") }; bytes = added.partialValue }
        stage.journal?.manifest.originals.forEach { ids.insert($0.id) }
      }
      guard files <= PendingVoiceMigration.maxFiles, bytes <= PendingVoiceMigration.maxBytes, ids.count <= PendingVoiceMigration.maxOriginals else { throw failure("quota") }
    }
    // Preserve the physical-copy reservation check before hashing any staged originals.
    try charge(inspections, existing: current.bundle)
    var complete: [(StageInspection, Bundle)] = []
    for stage in inspections {
      do { complete.append((stage, try stage.complete())) } catch { throw failure("incomplete, unknown, busy or conflicting stage") }
    }
    let root = support.appendingPathComponent(PendingVoiceMigration.archiveDirectory, isDirectory: true)
    func preflight(_ remaining: [(StageInspection, Bundle)]) throws -> [String] {
      try charge(remaining.map { $0.0 }, existing: current.bundle)
      // This is the same complete immutable graph assertion used by normal prepare/publish.
      // It includes live evidence and every pending stage, before any formal namespace change.
      try PendingVoiceMigration.validateOriginalUnion([current.bundle] + remaining.map { Optional($0.1) })
      let catalog: [String]
      if try PendingVoiceMigration.exists(root) { catalog = try DirectorySnapshot(root).names } else { catalog = [] }
      let targets = remaining.compactMap { $0.0.journal?.batchId }
      guard targets.count == remaining.count, Set(targets).count == targets.count,
        catalog.count + Set(targets).subtracting(catalog).count <= PendingVoiceMigration.maxOriginals else { throw failure("catalog quota or distinct ownership conflict") }
      // Missing/different saved journals reject in this read-only proof phase. Never repair
      // an old finalized batch merely to manufacture a condition for stage deletion.
      for (stage, _) in remaining where catalog.contains(stage.journal!.batchId) {
        _ = try PendingVoiceMigration.proveSavedStage(stage, target: root.appendingPathComponent(stage.journal!.batchId, isDirectory: true))
      }
      try current.unchanged(); try parent.unchanged()
      for (_, bundle) in remaining { try bundle.unchanged() }
      return catalog
    }
    for index in complete.indices {
      let remaining = Array(complete[index...]), stage = complete[index].0, bundle = complete[index].1
      guard let journal = stage.journal else { throw failure("missing journal") }
      do {
        // The first iteration preflights all stages, including all existing-target proofs.
        // Later iterations use a fresh witness after our preceding atomic namespace change.
        let catalog = try preflight(remaining)
        if !(try PendingVoiceMigration.exists(root)) {
          try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
          let changed = try StageParent(support)
          guard changed.fingerprint.device == parent.fingerprint.device, changed.fingerprint.inode == parent.fingerprint.inode,
            changed.fingerprint.uid == parent.fingerprint.uid, changed.fingerprint.mode == parent.fingerprint.mode,
            changed.names == parent.names else { throw Failure.unstable }; parent = changed
        }
        let catalogWitness = try DirectorySnapshot(root)
        guard catalogWitness.names == catalog else { throw Failure.unstable }
        try current.unchanged(ignoringAbsence: root); try parent.unchanged()
        for (_, pending) in remaining { try pending.unchanged() }
        let target = root.appendingPathComponent(journal.batchId, isDirectory: true)
        if catalog.contains(journal.batchId) {
          try PendingVoiceMigration.removeOnlySavedStage(stage, target: target)
        } else {
          try bundle.unchanged(); try catalogWitness.unchanged()
          guard renamex_np(stage.url.path, target.path, UInt32(RENAME_EXCL)) == 0 else { throw Failure.conflict }
        }
        try PendingVoiceMigration.syncDirectory(root); try PendingVoiceMigration.syncDirectory(support)
        let next = try StageParent(support)
        guard next.names == parent.names.filter({ $0 != stage.url.lastPathComponent }) else { throw Failure.unstable }; parent = next
        current = try PendingVoiceMigration.captureFinalized(support: support, includeLive: true)
      } catch { throw failure("cold union or saved-copy conflict; existing evidence retained") }
    }
  }
}
