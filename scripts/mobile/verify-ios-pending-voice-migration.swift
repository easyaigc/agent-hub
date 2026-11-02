import Foundation
import CryptoKit

// Synthetic local files only. Compile with PendingVoiceMigration.swift,
// PendingVoiceMigrationStages.swift and EncryptedMigration.swift
// on macOS; this test is not part of the iOS application target and never opens an app container.
enum L10n { static func tr(_ value: String) -> String { value } }

private func check(_ condition: @autoclosure () throws -> Bool) rethrows {
  let value = try condition(); precondition(value)
}
private func rejected(_ label: String, _ action: () throws -> Void) {
  do { try action() } catch { return }
  fatalError("Expected rejection: " + label)
}
private final class Fixture {
  let root: URL
  let id = UUID().uuidString.lowercased()
  init() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent("pending-voice-migration-test-" + UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
  }
  deinit { try? FileManager.default.removeItem(at: root) }
  func directory(_ name: String) throws -> URL {
    let value = root.appendingPathComponent(name, isDirectory: true)
    try FileManager.default.createDirectory(at: value, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]); return value
  }
  func write(_ data: Data, to url: URL) throws {
    try data.write(to: url); try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
  }
  func metadata(audio: Data, terminal: Bool = false, extra: [String: Any] = [:]) throws -> Data {
    var value: [String: Any] = ["target": "fixture-original-target", "scopeKey": "fixture-original-scope", "originalText": "unchanged fixture text", "operationId": id,
      "sha256": PendingVoiceMigration.sha(audio), "durationMs": 1000, "sizeBytes": audio.count,
      "createdAt": Date(timeIntervalSince1970: 1).timeIntervalSinceReferenceDate]
    if terminal { value["discardedAt"] = Date(timeIntervalSince1970: 2).timeIntervalSinceReferenceDate }
    value.merge(extra, uniquingKeysWith: { _, new in new }); return try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
  }
  func pair(audio: Data, actual: Data? = nil, terminal: Bool = false) throws -> (URL, Data) {
    let live = try directory(PendingVoiceMigration.liveDirectory), row = try metadata(audio: audio, terminal: terminal)
    try write(row, to: live.appendingPathComponent(id + ".json")); try write(actual ?? audio, to: live.appendingPathComponent(id + ".wav")); return (live, row)
  }
}

// Construct persisted files directly: this is a cold on-disk scene, not an exception catch.
private func coldStage(_ f: Fixture, _ bundle: PendingVoiceMigration.Bundle, journal: Bool = true, partial: Bool = false, sparse: Bool = false) throws -> URL {
  let stage = try f.directory(PendingVoiceMigration.stagePrefix + UUID().uuidString.lowercased())
  if journal { try f.write(PendingVoiceMigration.StageJournal(bundle: bundle, stage: stage).data(), to: stage.appendingPathComponent("journal.json")) }
  for original in bundle.originals {
    let child = stage.appendingPathComponent(original.id, isDirectory: true)
    try FileManager.default.createDirectory(at: child, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    for file in original.files {
      let path = child.appendingPathComponent(file.name)
      if sparse {
        try f.write(Data(), to: path); let handle = try FileHandle(forWritingTo: path); try handle.truncate(atOffset: UInt64(file.size)); try handle.close()
      } else { try f.write(partial && file.name.hasSuffix(".wav") ? Data(try file.bytes.data().prefix(1)) : file.bytes.data(), to: path) }
    }
  }
  try f.write(bundle.manifest(), to: stage.appendingPathComponent("manifest.json")); return stage
}
private func finalized(_ f: Fixture, _ bundle: PendingVoiceMigration.Bundle, journal: Data? = nil) throws -> URL {
  let root = try f.directory(PendingVoiceMigration.archiveDirectory), batch = root.appendingPathComponent(bundle.id, isDirectory: true)
  try FileManager.default.createDirectory(at: batch, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
  for original in bundle.originals {
    let child = batch.appendingPathComponent(original.id, isDirectory: true)
    try FileManager.default.createDirectory(at: child, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    for file in original.files { try f.write(file.bytes.data(), to: child.appendingPathComponent(file.name)) }
  }
  try f.write(bundle.manifest(), to: batch.appendingPathComponent("manifest.json"))
  if let journal { try f.write(journal, to: batch.appendingPathComponent("journal.json")) }; return batch
}
private func retainedBundle(audio: Data) throws -> PendingVoiceMigration.Bundle {
  let source = try Fixture(); _ = try source.pair(audio: audio, terminal: true)
  return try JSONDecoder().decode(PendingVoiceMigration.Bundle.self, from: JSONEncoder().encode(PendingVoiceMigration.snapshot(support: source.root)!))
}

private func conflictingBundle(_ bundle: PendingVoiceMigration.Bundle, audioConflict: Bool = false) throws -> PendingVoiceMigration.Bundle {
  var files = bundle.originals[0].files
  let metadataIndex = files.firstIndex { $0.name.hasSuffix(".json") }!
  var metadata = try JSONSerialization.jsonObject(with: files[metadataIndex].bytes.data()) as! [String: Any]
  if audioConflict {
    let index = files.firstIndex { $0.name.hasSuffix(".wav") }!, bytes = Data(repeating: 9, count: min(3, files[index].size))
    // Preserve the metadata bytes: two stable shorter originals may both be valid
    // raw partial evidence while their equal-length actual audio bytes conflict.
    files[index] = PendingVoiceMigration.File(name: files[index].name, data: bytes)
  } else {
    metadata["originalText"] = "independently valid conflicting original text"
    files[metadataIndex] = PendingVoiceMigration.File(name: files[metadataIndex].name, data: try JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys]))
  }
  let result = PendingVoiceMigration.Bundle(originals: [.init(files: files)])
  try result.validate(); return result
}
// Independent byte/catalog snapshots of fixture data, including directory names.
private func treeState(_ root: URL) throws -> [String: Data] {
  var state: [String: Data] = [:]
  func walk(_ directory: URL, _ prefix: String, _ depth: Int) throws {
    guard depth <= 4 else { throw PendingVoiceMigration.Failure.bounds }; state[prefix + "/"] = Data()
    for name in try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted() {
      let child = directory.appendingPathComponent(name), relative = prefix + "/" + name; var isDirectory = ObjCBool(false)
      guard FileManager.default.fileExists(atPath: child.path, isDirectory: &isDirectory) else { throw PendingVoiceMigration.Failure.invalid }
      if isDirectory.boolValue { try walk(child, relative, depth + 1) } else { state[relative] = try Data(contentsOf: child) }
    }
  }
  try walk(root, "", 0); return state
}
private func liveOriginals(_ fixture: Fixture, _ bundle: PendingVoiceMigration.Bundle) throws -> URL {
  let live = try fixture.directory(PendingVoiceMigration.liveDirectory)
  for file in bundle.originals.flatMap(\.files) { try fixture.write(file.bytes.data(), to: live.appendingPathComponent(file.name)) }; return live
}

@main struct VerifyPendingVoiceMigration {
  static func main() throws {
    let audio = Data([0x52, 0x49, 0x46, 0x46, 0, 1, 2, 3])
    do {
      let f = try Fixture(), empty = try PendingVoiceMigration.capture(support: f.root)
      try check(empty.bundle == nil)
      _ = try f.pair(audio: audio)
      rejected("empty source must not silently become a v1 backup") { try empty.unchanged() }
    }
    do {
      let f = try Fixture(), (live, metadata) = try f.pair(audio: audio, terminal: true)
      try FileManager.default.removeItem(at: live.appendingPathComponent(f.id + ".wav"))
      let value = try PendingVoiceMigration.snapshot(support: f.root)!
      try check(value.originals[0].files.count == 1)
      try check(try value.originals[0].files[0].bytes.data() == metadata)
    }
    // Expiry and terminal are preserved byte for byte, with no call to PendingVoiceStore.restore.
    do {
      let f = try Fixture(), (_, metadata) = try f.pair(audio: audio, terminal: true)
      let source = try PendingVoiceMigration.snapshot(support: f.root)!
      try check(source.originals.flatMap(\.files).allSatisfy { $0.bytes.retained == nil })
      let encoded = try JSONEncoder().encode(source), decoded = try JSONDecoder().decode(PendingVoiceMigration.Bundle.self, from: encoded)
      let files = decoded.originals[0].files
      try check(try files.first { $0.name.hasSuffix(".json") }!.bytes.data() == metadata)
      try check(try files.first { $0.name.hasSuffix(".wav") }!.bytes.data() == audio)
      let destination = try Fixture()
      let restore = try PendingVoiceMigration.prepareRestore(decoded, support: destination.root)!
      try check(!(try PendingVoiceMigration.exists(destination.root.appendingPathComponent(PendingVoiceMigration.archiveDirectory))))
      try restore.publish()
      try check(!(try PendingVoiceMigration.exists(destination.root.appendingPathComponent(PendingVoiceMigration.liveDirectory))))
      let exportedAgain = try PendingVoiceMigration.snapshot(support: destination.root)!
      try check(exportedAgain.id == decoded.id)
      let repeated = try PendingVoiceMigration.prepareRestore(decoded, support: destination.root)!
      try repeated.publish(); _ = repeated.preserveEvidenceAfterFailure(PendingVoiceMigration.Failure.invalid) // An idempotent failure cannot remove saved evidence.
      try check(try PendingVoiceMigration.snapshot(support: destination.root)!.id == decoded.id)
      _ = restore.preserveEvidenceAfterFailure(PendingVoiceMigration.Failure.invalid)
      try check(try PendingVoiceMigration.snapshot(support: destination.root)!.id == decoded.id)

      var root = try JSONSerialization.jsonObject(with: encoded) as! [String: Any]
      root["future"] = true
      rejected("unknown archive field") { _ = try JSONDecoder().decode(PendingVoiceMigration.Bundle.self, from: JSONSerialization.data(withJSONObject: root)) }
      root.removeValue(forKey: "future"); root["version"] = 2
      rejected("unknown domain version") { _ = try JSONDecoder().decode(PendingVoiceMigration.Bundle.self, from: JSONSerialization.data(withJSONObject: root)) }
      var original = source.originals[0].files
      original[0] = PendingVoiceMigration.File(name: "../" + original[0].name, data: try original[0].bytes.data())
      rejected("path traversal") { try PendingVoiceMigration.Bundle(originals: [.init(files: original)]).validate() }
      var changed = source.originals[0].files
      changed[0] = PendingVoiceMigration.File(name: changed[0].name, data: Data([0]))
      rejected("truncated metadata is not a fabricated terminal") { try PendingVoiceMigration.Bundle(originals: [.init(files: changed)]).validate() }
      let corrupted = String(decoding: encoded, as: UTF8.self).replacingOccurrences(of: "\"sha256\":\"", with: "\"sha256\":\"0")
      rejected("archive digest damage") { _ = try JSONDecoder().decode(PendingVoiceMigration.Bundle.self, from: Data(corrupted.utf8)) }
    }
    // Stable partial/orphan files remain raw inert evidence; no bytes are padded or regenerated.
    do {
      let f = try Fixture(), (live, _) = try f.pair(audio: audio, actual: Data(audio.prefix(3)))
      let value = try PendingVoiceMigration.snapshot(support: f.root)!
      try check(try value.originals[0].files.first { $0.name.hasSuffix(".wav") }!.bytes.data() == Data(audio.prefix(3)))
      let orphan = UUID().uuidString.lowercased() + ".wav"; try f.write(Data(), to: live.appendingPathComponent(orphan))
      let withEmpty = try PendingVoiceMigration.snapshot(support: f.root)!
      try check(withEmpty.originals[0].files.first { $0.name == orphan }!.size == 0)
      rejected("inventory changed after lazy snapshot") { _ = try JSONEncoder().encode(value) }
    }
    do {
      let f = try Fixture(), (live, _) = try f.pair(audio: audio)
      let value = try PendingVoiceMigration.snapshot(support: f.root)!
      try f.write(Data(repeating: 9, count: audio.count), to: live.appendingPathComponent(f.id + ".wav"))
      rejected("same-size source mutation") { _ = try JSONEncoder().encode(value) }
      rejected("same-size original corruption") { _ = try PendingVoiceMigration.snapshot(support: f.root) }
    }
    do {
      let f = try Fixture(), (live, _) = try f.pair(audio: audio)
      try f.write(try f.metadata(audio: audio, extra: ["unknownAttempt": true]), to: live.appendingPathComponent(f.id + ".json"))
      rejected("unknown metadata field") { _ = try PendingVoiceMigration.snapshot(support: f.root) }
    }
    do {
      let f = try Fixture(), (live, _) = try f.pair(audio: audio)
      try FileManager.default.createSymbolicLink(at: live.appendingPathComponent(UUID().uuidString.lowercased() + ".wav"), withDestinationURL: live.appendingPathComponent(f.id + ".wav"))
      rejected("symbolic link") { _ = try PendingVoiceMigration.snapshot(support: f.root) }
    }
    do {
      let f = try Fixture(), live = try f.directory(PendingVoiceMigration.liveDirectory), path = live.appendingPathComponent(f.id + ".wav")
      try f.write(Data(), to: path)
      let handle = try FileHandle(forWritingTo: path); try handle.truncate(atOffset: UInt64(PendingVoiceMigration.maxAudio + 1)); try handle.close()
      rejected("oversized source before audio read") { _ = try PendingVoiceMigration.snapshot(support: f.root) }
    }
    do {
      let f = try Fixture(), (live, _) = try f.pair(audio: audio)
      let incoming = try JSONDecoder().decode(PendingVoiceMigration.Bundle.self, from: JSONEncoder().encode(PendingVoiceMigration.snapshot(support: f.root)!))
      let destination = try f.directory("destination"), conflict = destination.appendingPathComponent(PendingVoiceMigration.liveDirectory)
      try FileManager.default.createDirectory(at: conflict, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
      let different = Data([8, 8, 8])
      try f.write(try f.metadata(audio: different), to: conflict.appendingPathComponent(f.id + ".json")); try f.write(different, to: conflict.appendingPathComponent(f.id + ".wav"))
      rejected("destination evidence conflict") { _ = try PendingVoiceMigration.prepareRestore(incoming, support: destination) }
      try check(try Data(contentsOf: conflict.appendingPathComponent(f.id + ".wav")) == different)
      try check(!(try PendingVoiceMigration.exists(destination.appendingPathComponent(PendingVoiceMigration.archiveDirectory))))
      try check(try Data(contentsOf: live.appendingPathComponent(f.id + ".wav")) == audio)
    }
    rejected("escaped duplicate JSON keys") { try VoiceMigrationJSON.uniqueKeys(Data("{\"version\":1,\"\\u0076ersion\":2}".utf8)) }

    // Complete cold stage recovery preserves exact terminal/expired bytes in an inert batch.
    do {
      let incoming = try retainedBundle(audio: audio), f = try Fixture(), stage = try coldStage(f, incoming)
      let bytes = try Data(contentsOf: stage.appendingPathComponent("journal.json"))
      let result = try PendingVoiceMigration.capture(support: f.root)
      try check(result.bundle!.id == incoming.id); try check(!(try PendingVoiceMigration.exists(stage)))
      let batch = f.root.appendingPathComponent(PendingVoiceMigration.archiveDirectory).appendingPathComponent(incoming.id)
      try check(try Data(contentsOf: batch.appendingPathComponent("journal.json")) == bytes)
      try check(!(try PendingVoiceMigration.exists(f.root.appendingPathComponent(PendingVoiceMigration.liveDirectory))))
      try check(try PendingVoiceMigration.snapshot(support: f.root)!.id == incoming.id)
    }
    // R4: two disjoint complete cold stages publish in one recovery, including catalog creation.
    do {
      let left = try retainedBundle(audio: audio), right = try retainedBundle(audio: Data([9, 8, 7, 6, 5]))
      let f = try Fixture()
      try check(Set(left.originals.map(\.id)).isDisjoint(with: Set(right.originals.map(\.id))))
      try check(Set(left.originals.flatMap(\.files).map(\.name)).isDisjoint(with: Set(right.originals.flatMap(\.files).map(\.name))))
      let expected = PendingVoiceMigration.Bundle(originals: (left.originals + right.originals).sorted { $0.id < $1.id })
      try expected.validate()
      try check(expected.originals.count == 2 && expected.inventoriedFileCount == 4)
      try check(expected.inventoriedByteCount < PendingVoiceMigration.maxBytes)
      let leftStage = try coldStage(f, left), rightStage = try coldStage(f, right)
      let leftBefore = try treeState(leftStage), rightBefore = try treeState(rightStage)
      let archive = f.root.appendingPathComponent(PendingVoiceMigration.archiveDirectory)
      try check(!(try PendingVoiceMigration.exists(archive)))
      try check(try PendingVoiceMigration.StageParent(f.root).names.count == 2)
      // One production capture must process both stages despite its own first rename.
      let recovered = try PendingVoiceMigration.capture(support: f.root), actual = recovered.bundle!
      try check(!(try PendingVoiceMigration.exists(leftStage)))
      try check(!(try PendingVoiceMigration.exists(rightStage)))
      try check(try PendingVoiceMigration.StageParent(f.root).names.isEmpty)
      try check(try FileManager.default.contentsOfDirectory(atPath: archive.path).sorted() == [left.id, right.id].sorted())
      try check(try treeState(archive.appendingPathComponent(left.id)) == leftBefore)
      try check(try treeState(archive.appendingPathComponent(right.id)) == rightBefore)
      try check(actual.originals.map(\.id) == expected.originals.map(\.id))
      try check(actual.inventoriedFileCount == 4 && actual.inventoriedByteCount == expected.inventoriedByteCount)
      let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
      let expectedBytes = try encoder.encode(expected)
      try check(try encoder.encode(actual) == expectedBytes)
      try check(try actual.manifest() == expected.manifest())
      try recovered.unchanged()
      let archiveBeforeRepeat = try treeState(archive), repeated = try PendingVoiceMigration.capture(support: f.root)
      try check(try encoder.encode(repeated.bundle!) == expectedBytes)
      try check(repeated.bundle!.inventoriedFileCount == 4 && repeated.bundle!.inventoriedByteCount == expected.inventoriedByteCount)
      try check(try treeState(archive) == archiveBeforeRepeat)
      try check(try PendingVoiceMigration.StageParent(f.root).names.isEmpty)
      try check(!(try PendingVoiceMigration.exists(f.root.appendingPathComponent(PendingVoiceMigration.liveDirectory))))
    }
    // R4: exact saved-copy deletion is first, then a disjoint stage must still publish.
    do {
      let left = try retainedBundle(audio: audio), right = try retainedBundle(audio: Data([4, 3, 2, 1, 0, 9]))
      let f = try Fixture(), leftStage = try coldStage(f, left), rightStage = try coldStage(f, right)
      let stages = [(url: leftStage, bundle: left), (url: rightStage, bundle: right)]
        .sorted { $0.url.lastPathComponent < $1.url.lastPathComponent }
      let duplicate = stages[0], pending = stages[1]
      // Reconciler order is directory-name order; bind the earlier real stage to the saved copy.
      try check(duplicate.url.lastPathComponent < pending.url.lastPathComponent)
      let duplicateBefore = try treeState(duplicate.url), pendingBefore = try treeState(pending.url)
      let journal = try Data(contentsOf: duplicate.url.appendingPathComponent("journal.json"))
      let saved = try finalized(f, duplicate.bundle, journal: journal), archive = saved.deletingLastPathComponent()
      let savedBefore = try treeState(saved)
      try check(savedBefore == duplicateBefore)
      try check(try FileManager.default.contentsOfDirectory(atPath: archive.path) == [duplicate.bundle.id])
      try check(try PendingVoiceMigration.StageParent(f.root).names == stages.map { $0.url.lastPathComponent })
      try check(Set(left.originals.map(\.id)).isDisjoint(with: Set(right.originals.map(\.id))))
      try check(Set(left.originals.flatMap(\.files).map(\.name)).isDisjoint(with: Set(right.originals.flatMap(\.files).map(\.name))))
      let expected = PendingVoiceMigration.Bundle(originals: (left.originals + right.originals).sorted { $0.id < $1.id })
      try expected.validate()
      // Initially there are six physical original files: two saved, two duplicate, two new.
      try check(expected.inventoriedFileCount + duplicate.bundle.inventoriedFileCount == 6)
      try check(expected.inventoriedByteCount + duplicate.bundle.inventoriedByteCount < PendingVoiceMigration.maxBytes)
      let recovered = try PendingVoiceMigration.capture(support: f.root), actual = recovered.bundle!
      try check(!(try PendingVoiceMigration.exists(duplicate.url)))
      try check(!(try PendingVoiceMigration.exists(pending.url)))
      try check(try PendingVoiceMigration.StageParent(f.root).names.isEmpty)
      try check(try treeState(saved) == savedBefore)
      try check(try Data(contentsOf: saved.appendingPathComponent("journal.json")) == journal)
      try check(try treeState(archive.appendingPathComponent(pending.bundle.id)) == pendingBefore)
      try check(try FileManager.default.contentsOfDirectory(atPath: archive.path).sorted() == [duplicate.bundle.id, pending.bundle.id].sorted())
      try check(actual.originals.count == 2 && actual.originals.map(\.id) == expected.originals.map(\.id))
      try check(actual.inventoriedFileCount == 4 && actual.inventoriedByteCount == expected.inventoriedByteCount)
      let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
      let expectedBytes = try encoder.encode(expected)
      try check(try encoder.encode(actual) == expectedBytes)
      try check(try actual.manifest() == expected.manifest())
      try recovered.unchanged()
      let beforeRepeat = try treeState(archive), repeated = try PendingVoiceMigration.capture(support: f.root)
      try check(try encoder.encode(repeated.bundle!) == expectedBytes)
      try check(repeated.bundle!.inventoriedFileCount == 4 && repeated.bundle!.inventoriedByteCount == expected.inventoriedByteCount)
      try check(try treeState(archive) == beforeRepeat)
      try check(try PendingVoiceMigration.StageParent(f.root).names.isEmpty)
      try check(!(try PendingVoiceMigration.exists(f.root.appendingPathComponent(PendingVoiceMigration.liveDirectory))))
    }
    // Partially copied bytes and missing journals are retained, reserved and block even v1 import.
    for withJournal in [true, false] {
      let incoming = try retainedBundle(audio: audio), f = try Fixture(), stage = try coldStage(f, incoming, journal: withJournal, partial: withJournal)
      do { _ = try PendingVoiceMigration.capture(support: f.root); fatalError("cold partial must reject") }
      catch let failure as PendingVoiceMigration.StageFailure { try check(failure.files >= incoming.inventoriedFileCount); try check(failure.bytes > 0) }
      rejected("partial/unknown is never an empty legacy import") { _ = try PendingVoiceMigration.prepareRestore(nil, support: f.root) }
      try check(try PendingVoiceMigration.exists(stage)); try check(try PendingVoiceMigration.StageParent(f.root).names.count == 1)
    }
    do {
      let incoming = try retainedBundle(audio: audio), f = try Fixture(), stage = try coldStage(f, incoming)
      try FileManager.default.removeItem(at: stage.appendingPathComponent("manifest.json"))
      rejected("copied originals without final manifest stay retained") { _ = try PendingVoiceMigration.prepareRestore(nil, support: f.root) }
      try check(try PendingVoiceMigration.exists(stage))
    }
    for field in ["version", "unknown", "identity"] {
      let incoming = try retainedBundle(audio: audio), f = try Fixture(), stage = try coldStage(f, incoming), path = stage.appendingPathComponent("journal.json")
      var value = try JSONSerialization.jsonObject(with: Data(contentsOf: path)) as! [String: Any]
      if field == "version" { value["version"] = 2 }
      else if field == "unknown" { value["futureCompletion"] = true }
      else { var node = value["directory"] as! [String: Any]; node["inode"] = (node["inode"] as! NSNumber).uint64Value + 1; value["directory"] = node }
      try f.write(JSONSerialization.data(withJSONObject: value), to: path)
      rejected("closed journal and original directory identity") { _ = try PendingVoiceMigration.capture(support: f.root) }
      try check(try PendingVoiceMigration.exists(stage))
    }
    do {
      let f = try Fixture(), owner = try PendingVoiceMigration.StageNamespaceLease(f.root)
      rejected("concurrent migration cannot bypass reservation") { _ = try PendingVoiceMigration.prepareRestore(nil, support: f.root) }
      withExtendedLifetime(owner) {}
      try check(try PendingVoiceMigration.StageParent(f.root).names.isEmpty)
    }
    // Every declared original in every recognized stage reserves aggregate quota before hashing.
    do {
      let files = (0..<4).map { _ in PendingVoiceMigration.File(name: UUID().uuidString.lowercased() + ".wav", data: Data(repeating: 0, count: 9 * 1024 * 1024)) }.sorted { $0.name < $1.name }
      let incoming = PendingVoiceMigration.Bundle(originals: [.init(files: files)]); try incoming.validate()
      let f = try Fixture(), first = try coldStage(f, incoming, sparse: true), second = try coldStage(f, incoming, sparse: true)
      do { _ = try PendingVoiceMigration.capture(support: f.root); fatalError("aggregate bytes must reject") }
      catch let failure as PendingVoiceMigration.StageFailure { try check(failure.bytes == 72 * 1024 * 1024); try check(failure.files == 8) }
      rejected("new import cannot accumulate another interrupted stage") { _ = try PendingVoiceMigration.prepareRestore(incoming, support: f.root) }
      try check(try PendingVoiceMigration.exists(first)); try check(try PendingVoiceMigration.exists(second))
      try check(try PendingVoiceMigration.StageParent(f.root).names.count == 2)
    }
    do {
      let files = (0..<65).map { _ in PendingVoiceMigration.File(name: UUID().uuidString.lowercased() + ".wav", data: Data([0])) }.sorted { $0.name < $1.name }
      let incoming = PendingVoiceMigration.Bundle(originals: [.init(files: files)]), f = try Fixture()
      _ = try coldStage(f, incoming); _ = try coldStage(f, incoming)
      do { _ = try PendingVoiceMigration.capture(support: f.root); fatalError("aggregate count must reject") }
      catch let failure as PendingVoiceMigration.StageFailure { try check(failure.files == 130) }
      try check(try PendingVoiceMigration.StageParent(f.root).names.count == 2)
    }
    // A historical batch is never modified to manufacture a saved-copy deletion proof.
    do {
      let incoming = try retainedBundle(audio: audio), f = try Fixture(), stage = try coldStage(f, incoming)
      let journal = try Data(contentsOf: stage.appendingPathComponent("journal.json")), target = try finalized(f, incoming)
      let root = target.deletingLastPathComponent(), before = try treeState(root)
      rejected("missing finalized journal retains stage without changing old batch") { _ = try PendingVoiceMigration.capture(support: f.root) }
      try check(try treeState(root) == before); try check(try PendingVoiceMigration.exists(stage))
      try check(try Data(contentsOf: stage.appendingPathComponent("journal.json")) == journal)
      try check(!(try PendingVoiceMigration.exists(target.appendingPathComponent("journal.json"))))
      try check(try PendingVoiceMigration.captureFinalized(support: f.root).bundle!.id == incoming.id)
    }
    do {
      let incoming = try retainedBundle(audio: audio), f = try Fixture(), stage = try coldStage(f, incoming)
      let journal = try Data(contentsOf: stage.appendingPathComponent("journal.json")), target = try finalized(f, incoming, journal: journal)
      let savedRoot = target.deletingLastPathComponent(), live = try liveOriginals(f, incoming)
      let savedBefore = try treeState(savedRoot), liveBefore = try treeState(live)
      try check(try PendingVoiceMigration.capture(support: f.root).bundle!.id == incoming.id)
      try check(!(try PendingVoiceMigration.exists(stage)))
      try check(try treeState(savedRoot) == savedBefore); try check(try treeState(live) == liveBefore)
      // A later terminal duplicate with distinct ownership bytes is retained, never prefix-deleted.
      let later = try coldStage(f, incoming), root = f.root.appendingPathComponent(PendingVoiceMigration.archiveDirectory)
      let before = try treeState(root), uniqueJournal = try Data(contentsOf: later.appendingPathComponent("journal.json"))
      rejected("distinct journal is unique evidence") { _ = try PendingVoiceMigration.capture(support: f.root) }
      try check(try PendingVoiceMigration.exists(later)); try check(try treeState(root) == before)
      try check(try Data(contentsOf: later.appendingPathComponent("journal.json")) == uniqueJournal)
      try check(try PendingVoiceMigration.captureFinalized(support: f.root).bundle!.id == incoming.id)
    }
    // Both graphs are individually valid: cold union conflicts must reject before publication.
    do {
      let saved = try retainedBundle(audio: audio), conflict = try conflictingBundle(saved), f = try Fixture()
      let target = try finalized(f, saved), root = target.deletingLastPathComponent(), before = try treeState(root)
      let stage = try coldStage(f, conflict)
      rejected("valid finalized versus valid conflicting cold metadata") { _ = try PendingVoiceMigration.capture(support: f.root) }
      try check(try treeState(root) == before); try check(try PendingVoiceMigration.exists(stage))
      try check(try PendingVoiceMigration.captureFinalized(support: f.root).bundle!.id == saved.id)
    }
    do {
      let full = try retainedBundle(audio: audio), f = try Fixture()
      var partial = full.originals[0].files; let wav = partial.firstIndex { $0.name.hasSuffix(".wav") }!
      partial[wav] = PendingVoiceMigration.File(name: partial[wav].name, data: Data(repeating: 1, count: 3))
      let saved = PendingVoiceMigration.Bundle(originals: [.init(files: partial)]); try saved.validate()
      let conflict = try conflictingBundle(saved, audioConflict: true)
      let live = try liveOriginals(f, saved), before = try treeState(live), stage = try coldStage(f, conflict)
      rejected("live original bytes participate in the cold union") { _ = try PendingVoiceMigration.capture(support: f.root) }
      try check(try treeState(live) == before); try check(try PendingVoiceMigration.exists(stage))
      try check(!(try PendingVoiceMigration.exists(f.root.appendingPathComponent(PendingVoiceMigration.archiveDirectory))))
      try check(try PendingVoiceMigration.captureFinalized(support: f.root).bundle!.id == saved.id)
    }
    do {
      let first = try retainedBundle(audio: audio), second = try conflictingBundle(first), f = try Fixture()
      let a = try coldStage(f, first), b = try coldStage(f, second)
      rejected("all complete cold stages are jointly preflighted") { _ = try PendingVoiceMigration.capture(support: f.root) }
      try check(try PendingVoiceMigration.exists(a)); try check(try PendingVoiceMigration.exists(b))
      try check(!(try PendingVoiceMigration.exists(f.root.appendingPathComponent(PendingVoiceMigration.archiveDirectory))))
    }
    do {
      let existing = try retainedBundle(audio: audio), other = try retainedBundle(audio: audio), f = try Fixture()
      let target = try finalized(f, existing), root = target.deletingLastPathComponent(), before = try treeState(root)
      let blocked = try coldStage(f, existing), otherwisePublishable = try coldStage(f, other)
      rejected("all saved-target journal proofs precede the first cold publication") { _ = try PendingVoiceMigration.capture(support: f.root) }
      try check(try treeState(root) == before); try check(try PendingVoiceMigration.exists(blocked))
      try check(try PendingVoiceMigration.exists(otherwisePublishable))
    }
    do {
      let incoming = try retainedBundle(audio: audio), f = try Fixture()
      let target = try finalized(f, incoming), live = try liveOriginals(f, incoming)
      let savedBefore = try treeState(target.deletingLastPathComponent()), witness = try PendingVoiceMigration.captureFinalized(support: f.root)
      let wav = incoming.originals[0].files.first { $0.name.hasSuffix(".wav") }!
      // In-place mutation does not rename the live directory entry. Its physical witness
      // must survive graph-ID dedup against the byte-identical archived graph.
      try f.write(Data(repeating: 4, count: wav.size), to: live.appendingPathComponent(wav.name))
      rejected("every physical live file retains its witness after graph dedup") { try witness.unchanged() }
      try check(try treeState(target.deletingLastPathComponent()) == savedBefore)
      try check(try PendingVoiceMigration.captureFinalized(support: f.root, includeLive: false).bundle!.id == incoming.id)
    }
    // A valid no-stage historical batch supports ordinary repeated imports without a journal.
    // Sixty-five files would exceed 128 if the same graph were accidentally counted twice.
    do {
      let files = (0..<65).map { _ in PendingVoiceMigration.File(name: UUID().uuidString.lowercased() + ".wav", data: Data([0])) }.sorted { $0.name < $1.name }
      let incoming = PendingVoiceMigration.Bundle(originals: [.init(files: files)]), f = try Fixture()
      let target = try finalized(f, incoming), root = target.deletingLastPathComponent(), before = try treeState(root)
      for _ in 0..<2 {
        let repeated = try PendingVoiceMigration.prepareRestore(incoming, support: f.root)!
        try repeated.publish(); try check(try PendingVoiceMigration.StageParent(f.root).names.isEmpty)
        try check(try treeState(root) == before)
      }
      try check(!(try PendingVoiceMigration.exists(target.appendingPathComponent("journal.json"))))
    }
    for hardlink in [false, true] {
      let incoming = try retainedBundle(audio: audio), f = try Fixture(), stage = try coldStage(f, incoming)
      let original = incoming.originals[0], wav = original.files.first { $0.name.hasSuffix(".wav") }!
      let path = stage.appendingPathComponent(original.id).appendingPathComponent(wav.name), outside = f.root.appendingPathComponent("fixture-original.wav")
      try f.write(audio, to: outside); try FileManager.default.removeItem(at: path)
      if hardlink { try FileManager.default.linkItem(at: outside, to: path) } else { try FileManager.default.createSymbolicLink(at: path, withDestinationURL: outside) }
      rejected("cold stage link cannot be promoted or removed") { _ = try PendingVoiceMigration.capture(support: f.root) }
      try check(try PendingVoiceMigration.exists(stage)); try check(try Data(contentsOf: outside) == audio)
    }
    do {
      let incoming = try retainedBundle(audio: audio), f = try Fixture(), stage = try coldStage(f, incoming)
      let target = try finalized(f, incoming), original = incoming.originals[0], file = original.files.first { $0.name.hasSuffix(".wav") }!
      try f.write(Data(repeating: 7, count: audio.count), to: target.appendingPathComponent(original.id).appendingPathComponent(file.name))
      rejected("conflicting finalized bytes retain cold source") { _ = try PendingVoiceMigration.capture(support: f.root) }
      try check(try PendingVoiceMigration.exists(stage))
    }

    // New nonempty domain packages are explicitly rejected by the historical v1 reader.
    let password = "synthetic-fixture-password", clear = Data("version fixture".utf8)
    let old = try EncryptedMigrationCrypto.seal(clear, password: password)
    try check(try EncryptedMigrationCrypto.openVersioned(old, password: password).version == 1)
    let new = try EncryptedMigrationCrypto.seal(clear, password: password, version: 2)
    let envelope = try JSONDecoder().decode(EncryptedMigrationCrypto.Envelope.self, from: new)
    rejected("historical reader version guard") { guard envelope.version == 1 else { throw PendingVoiceMigration.Failure.invalid } }
    try check(try EncryptedMigrationCrypto.openVersioned(new, password: password).bytes == clear)
    let downgraded = try JSONEncoder().encode(EncryptedMigrationCrypto.Envelope(version: 1, salt: envelope.salt, ciphertext: envelope.ciphertext))
    rejected("outer version downgrade fails AEAD") { _ = try EncryptedMigrationCrypto.open(downgraded, password: password) }
    rejected("unknown envelope version") { _ = try EncryptedMigrationCrypto.seal(clear, password: password, version: 3) }
    print("pending voice migration boundaries passed (synthetic local codec/files only)")
  }
}
