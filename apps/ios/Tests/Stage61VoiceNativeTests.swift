import XCTest
import Foundation

#if !os(iOS) || !targetEnvironment(simulator)
#error("Stage61VoiceNativeTests is an iOS Simulator-only logic test bundle")
#endif

// Compiled alongside the unmodified production sources, without CodexManageApp.
// Never construct SessionStore or touch the default family/keychain/notification store.
final class Stage61VoiceNativeTests: XCTestCase {
  private let password = "synthetic-stage61-password"
  private let operation = "00000000-0000-4000-8000-000000000061"
  private var roots: [URL] = []
  private enum HarnessFailure: Error { case wrongBundle, unexpectedFixturePath }
  private enum SyntheticBusinessFailure: Error { case afterOriginalPublication }

  override func setUpWithError() throws {
    try super.setUpWithError()
    guard Bundle(for: Self.self).bundleIdentifier == "com.easyai.agenthub.acceptance.stage61voice.tests" else {
      throw HarnessFailure.wrongBundle
    }
    guard !["com.easyai.agenthub", "com.easyai.codexmanage", "com.codexmanage.ios"].contains(Bundle.main.bundleIdentifier ?? ""),
      Bundle.main.object(forInfoDictionaryKey: "NotificationAppGroup") == nil else { throw HarnessFailure.wrongBundle }
  }

  override func tearDownWithError() throws {
    var first: Error?
    for root in roots.reversed() {
      do {
        guard root.lastPathComponent.hasPrefix("stage61-voice-native-"),
          root.deletingLastPathComponent().standardizedFileURL == FileManager.default.temporaryDirectory.standardizedFileURL else {
          throw HarnessFailure.unexpectedFixturePath
        }
        try FileManager.default.removeItem(at: root)
      } catch { if first == nil { first = error } }
    }
    roots.removeAll()
    try super.tearDownWithError()
    if let first { throw first }
  }

  private func root() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("stage61-voice-native-" + UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    roots.append(url)
    return url
  }

  private func write(_ data: Data, to url: URL) throws {
    try data.write(to: url)
    try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
  }

  private func encoded<T: Encodable>(_ value: T) throws -> Data {
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    return try encoder.encode(value)
  }

  private func object(_ data: Data) throws -> [String: Any] {
    try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
  }

  private func json(_ value: [String: Any]) throws -> Data {
    try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
  }

  private func duplicatePrefix(_ data: Data, _ field: String) -> Data {
    Data(("{" + field + ",").utf8) + Data(data.dropFirst())
  }

  private func payload(_ originals: PendingVoiceMigration.Bundle? = nil) throws -> EncryptedMigration.Payload {
    var state = SavedSession()
    // An unresolved submitted intent stays byte-identical; this harness has no transport.
    let pending = PendingReply(hostId: "synthetic-host", threadId: "synthetic-thread", clientMessageId: operation,
      text: "immutable submitted body", attachmentIds: [], mode: "default", expectedTurnId: nil,
      model: nil, effort: nil, sourceAttachments: nil, knownRejected: nil)
    state.outbox = [operation: pending]
    return EncryptedMigration.Payload(version: originals == nil ? 1 : 2,
      bundleIdentifier: try XCTUnwrap(Bundle.main.bundleIdentifier), state: state,
      credentials: [:], attachments: [:], notificationPreferences: TaskNotificationPreferences(),
      notificationKey: nil, notificationDeviceId: nil, notificationBindings: [:], notificationSounds: [], soundFiles: [:],
      pendingVoiceOriginals: originals)
  }

  private func audioPair(in support: URL) throws -> [String: Data] {
    let live = support.appendingPathComponent(PendingVoiceMigration.liveDirectory, isDirectory: true)
    try FileManager.default.createDirectory(at: live, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    // A synthetic mono PCM16/16k WAV: header + eight silent samples, never microphone input.
    let audio = Data([0x52,0x49,0x46,0x46,52,0,0,0,0x57,0x41,0x56,0x45,
      0x66,0x6d,0x74,0x20,16,0,0,0,1,0,1,0,0x80,0x3e,0,0,0,0x7d,0,0,2,0,16,0,
      0x64,0x61,0x74,0x61,16,0,0,0] + [UInt8](repeating: 0, count: 16))
    let row = try json(["target": "synthetic-target", "scopeKey": "synthetic-scope", "originalText": "immutable original text",
      "operationId": operation, "sha256": PendingVoiceMigration.sha(audio), "durationMs": 1,
      "sizeBytes": audio.count, "createdAt": Date(timeIntervalSince1970: 1).timeIntervalSinceReferenceDate])
    let files = [operation + ".json": row, operation + ".wav": audio]
    for (name, data) in files { try write(data, to: live.appendingPathComponent(name)) }
    return files
  }

  private func fixture() throws -> (URL, [String: Data], PendingVoiceMigration.Capture) {
    let support = try root(), files = try audioPair(in: support)
    return (support, files, try PendingVoiceMigration.capture(support: support))
  }

  private func assertFiles(_ bundle: PendingVoiceMigration.Bundle, equal expected: [String: Data], lazy: Bool) throws {
    try bundle.validate()
    let files = bundle.originals.flatMap(\.files)
    XCTAssertEqual(Set(files.map(\.name)), Set(expected.keys))
    for file in files {
      XCTAssertEqual(try file.bytes.data(), expected[file.name])
      if lazy { XCTAssertNil(file.bytes.retained); XCTAssertNotNil(file.bytes.source) }
    }
  }

  func testV1EmptyPayloadRoundTripPreservesUnknownIntent() throws {
    let support = try root(), capture = try PendingVoiceMigration.capture(support: support)
    XCTAssertNil(capture.bundle)
    var value = try payload(capture.bundle); value.pendingVoiceSource = capture
    let clear = try encoded(value)
    XCTAssertFalse(try object(clear).keys.contains("pendingVoiceOriginals"))
    let sealed = try EncryptedMigration.seal(value, password: password)
    XCTAssertEqual(try EncryptedMigrationCrypto.openVersioned(sealed, password: password).version, 1)
    let read = try EncryptedMigration.open(sealed, password: password)
    XCTAssertEqual(read.version, 1); XCTAssertNil(read.pendingVoiceOriginals); XCTAssertNil(read.pendingVoiceSource)
    XCTAssertNil(read.state.replyRecovery)
    XCTAssertEqual(try encoded(read.state.outbox), try encoded(value.state.outbox))
  }

  func testV2NonemptyPayloadRoundTripAndSourceWitnessIsNotWireData() throws {
    let (_, files, capture) = try fixture()
    var value = try payload(XCTUnwrap(capture.bundle)); value.pendingVoiceSource = capture
    let wire = try encoded(value)
    XCTAssertFalse(try object(wire).keys.contains("pendingVoiceSource"))
    let sealed = try EncryptedMigration.seal(value, password: password)
    XCTAssertEqual(try EncryptedMigrationCrypto.openVersioned(sealed, password: password).version, 2)
    let read = try EncryptedMigration.open(sealed, password: password)
    XCTAssertEqual(read.version, 2); XCTAssertNil(read.pendingVoiceSource)
    try assertFiles(XCTUnwrap(read.pendingVoiceOriginals), equal: files, lazy: false)
    XCTAssertEqual(try encoded(read.state.outbox), try encoded(value.state.outbox))
    XCTAssertNil(read.state.replyRecovery)
  }

  func testPayloadVersionAndClosedFieldBoundaries() throws {
    let (_, _, capture) = try fixture()
    let value = try payload(XCTUnwrap(capture.bundle)), original = try object(encoded(value))
    func rejects(_ fields: [String: Any], outer: Int = 2) throws {
      let data = try EncryptedMigrationCrypto.seal(json(fields), password: password, version: outer)
      XCTAssertThrowsError(try EncryptedMigration.open(data, password: password))
    }
    var fields = original; fields["future"] = true; try rejects(fields)
    fields = original; fields["pendingVoiceSource"] = [:]; try rejects(fields)
    fields = original; fields["version"] = 3; try rejects(fields)
    fields = original; fields.removeValue(forKey: "pendingVoiceOriginals"); try rejects(fields)
    fields = original; fields["pendingVoiceOriginals"] = NSNull(); try rejects(fields)
    fields = original; fields["pendingVoiceOriginals"] = ["version": 1, "originals": []]; try rejects(fields)
    fields = original; fields["version"] = 1; try rejects(fields, outer: 1)
    fields = try object(encoded(payload())); fields["pendingVoiceOriginals"] = NSNull(); try rejects(fields, outer: 1)
    fields = try object(encoded(payload())); try rejects(fields, outer: 2)
    fields = original; fields["bundleIdentifier"] = "synthetic.unrelated.installation"; try rejects(fields)
    fields = original; fields["attachments"] = ["../escape": Data([1]).base64EncodedString()]; try rejects(fields)
    fields = original; fields.removeValue(forKey: "state"); try rejects(fields)
    var bundle = try XCTUnwrap(original["pendingVoiceOriginals"] as? [String: Any]); bundle["future"] = true
    fields = original; fields["pendingVoiceOriginals"] = bundle; try rejects(fields)
  }

  func testPayloadDuplicateAndEscapedEquivalentFieldsAreRejected() throws {
    let clear = try encoded(payload())
    for field in ["\"version\":1", "\"vers\\u0069on\":1"] {
      let sealed = try EncryptedMigrationCrypto.seal(duplicatePrefix(clear, field), password: password)
      XCTAssertThrowsError(try EncryptedMigration.open(sealed, password: password))
    }
  }

  func testEnvelopeClosedFieldsVersionsAndAEADAreRejected() throws {
    let (_, _, capture) = try fixture()
    for value in [try payload(), try payload(XCTUnwrap(capture.bundle))] {
      let sealed = try EncryptedMigration.seal(value, password: password), initial = try object(sealed)
      var fields = initial; fields["future"] = 1
      XCTAssertThrowsError(try EncryptedMigration.open(json(fields), password: password))
      fields = initial; fields["version"] = 3
      XCTAssertThrowsError(try EncryptedMigration.open(json(fields), password: password))
      // Changing the outer version switches AAD, even when the ciphertext/key are intact.
      fields = initial; fields["version"] = value.version == 1 ? 2 : 1
      XCTAssertThrowsError(try EncryptedMigrationCrypto.openVersioned(json(fields), password: password))
      fields = initial; fields["salt"] = Data([0]).base64EncodedString()
      XCTAssertThrowsError(try EncryptedMigration.open(json(fields), password: password))
      fields = initial; fields["ciphertext"] = Data([0,1,2]).base64EncodedString()
      XCTAssertThrowsError(try EncryptedMigration.open(json(fields), password: password))
      var cipher = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(initial["ciphertext"] as? String)))
      cipher[cipher.startIndex] ^= 1; fields = initial; fields["ciphertext"] = cipher.base64EncodedString()
      XCTAssertThrowsError(try EncryptedMigration.open(json(fields), password: password))
      XCTAssertThrowsError(try EncryptedMigration.open(sealed, password: "different-synthetic-password"))
      for field in ["\"version\":1", "\"vers\\u0069on\":1"] {
        XCTAssertThrowsError(try EncryptedMigration.open(duplicatePrefix(sealed, field), password: password))
      }
    }
  }

  func testOriginalTamperIsRejectedInsideAuthenticatedPayload() throws {
    let (_, _, capture) = try fixture()
    var fields = try object(encoded(payload(XCTUnwrap(capture.bundle))))
    var bundle = try XCTUnwrap(fields["pendingVoiceOriginals"] as? [String: Any])
    var originals = try XCTUnwrap(bundle["originals"] as? [[String: Any]])
    var files = try XCTUnwrap(originals[0]["files"] as? [[String: Any]])
    files[0]["bytes"] = Data("tampered".utf8).base64EncodedString()
    originals[0]["files"] = files; bundle["originals"] = originals; fields["pendingVoiceOriginals"] = bundle
    let sealed = try EncryptedMigrationCrypto.seal(json(fields), password: password, version: 2)
    XCTAssertThrowsError(try EncryptedMigration.open(sealed, password: password))
  }

  func testChangedLiveSourceCannotBeExportedAsCapturedOriginal() throws {
    let (support, files, capture) = try fixture()
    var value = try payload(XCTUnwrap(capture.bundle)); value.pendingVoiceSource = capture
    let audio = support.appendingPathComponent(PendingVoiceMigration.liveDirectory).appendingPathComponent(operation + ".wav")
    try write(Data("different bytes".utf8), to: audio)
    XCTAssertThrowsError(try EncryptedMigration.seal(value, password: password))
    let metadata = audio.deletingLastPathComponent().appendingPathComponent(operation + ".json")
    XCTAssertEqual(try Data(contentsOf: metadata), files[operation + ".json"])
  }

  func testNativeOriginalImportIsInertLazyAndReexportable() throws {
    let (_, files, capture) = try fixture(), destination = try root()
    let incoming = try EncryptedMigration.open(EncryptedMigration.seal(payload(XCTUnwrap(capture.bundle)), password: password), password: password)
    let restore = try XCTUnwrap(PendingVoiceMigration.prepareRestore(incoming.pendingVoiceOriginals, support: destination))
    let live = destination.appendingPathComponent(PendingVoiceMigration.liveDirectory)
    XCTAssertFalse(FileManager.default.fileExists(atPath: live.path))
    try restore.publish()
    XCTAssertFalse(FileManager.default.fileExists(atPath: live.path))
    let archived = try PendingVoiceMigration.capture(support: destination, includeLive: false)
    try assertFiles(XCTUnwrap(archived.bundle), equal: files, lazy: true)
    var second = try payload(XCTUnwrap(archived.bundle)); second.pendingVoiceSource = archived
    let read = try EncryptedMigration.open(EncryptedMigration.seal(second, password: password), password: password)
    try assertFiles(XCTUnwrap(read.pendingVoiceOriginals), equal: files, lazy: false)
    XCTAssertEqual(read.pendingVoiceOriginals?.id, incoming.pendingVoiceOriginals?.id)
    XCTAssertEqual(try encoded(read.state.outbox), try encoded(incoming.state.outbox))
    XCTAssertNil(read.state.replyRecovery)
    XCTAssertFalse(FileManager.default.fileExists(atPath: live.path))
  }

  func testPublishedOriginalsSurviveReportedFailureAtOriginalBoundary() throws {
    // This is NOT SessionStore.restoreMigration rollback coverage.
    let (_, files, capture) = try fixture(), destination = try root()
    let bundle = try XCTUnwrap(capture.bundle)
    let restore = try XCTUnwrap(PendingVoiceMigration.prepareRestore(bundle, support: destination))
    try restore.publish()
    let retained = try XCTUnwrap(restore.preserveEvidenceAfterFailure(SyntheticBusinessFailure.afterOriginalPublication)
      as? PendingVoiceMigration.EvidenceRetentionFailure)
    XCTAssertTrue(retained.published)
    XCTAssertEqual(retained.files, bundle.inventoriedFileCount)
    XCTAssertEqual(retained.bytes, bundle.inventoriedByteCount)
    let after = try XCTUnwrap(PendingVoiceMigration.snapshot(support: destination, includeLive: false))
    try assertFiles(after, equal: files, lazy: true)
    XCTAssertEqual(after.id, bundle.id)
    XCTAssertFalse(FileManager.default.fileExists(atPath: destination.appendingPathComponent(PendingVoiceMigration.liveDirectory).path))
  }
}
