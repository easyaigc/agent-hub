import XCTest
import Foundation
import Darwin

#if !os(iOS) || !targetEnvironment(simulator)
#error("Stage64SessionStoreNativeTests requires its dedicated signed iOS Simulator host")
#endif

// Three business cases construct actual production SessionStore; the probe does not.
// Run EACH business case alone on its own newly created empty Simulator.
// Probe this signed blank host first; admit its exact installed data container.
// Default production storage/keychain/notification access is intentional.
final class P7SessionStoreRestoreNativeTests: XCTestCase {
  private let operation = "00000000-0000-4000-8000-000000000073"
  private let credentialID = "easywork-device-identity"
  private let attachmentName = "p7-synthetic-original.txt"
  private struct Admission: Decodable {
    let taskID: String
    let caseName: String
    let deviceUDID: String
    let admissionVersion: Int
    let expectedDeviceDataRoot: String
    let expectedMainBundleIdentifier: String
    let createdEmptyForThisCase: Bool
  }
  private struct Paths {
    let support: URL, session: URL, legacy: URL, state: URL, notification: URL, sounds: URL
  }
  private enum HarnessFailure: Error { case admission, unexpectedPath, notEmpty }

  private struct DurableTuple: Equatable {
    let legacy: Data?, state: Data?, backup: Data?
  }
  private func optionalData(_ url: URL) throws -> Data? {
    do { return try data(url) }
    catch {
      let failure = error as NSError
      if failure.domain == NSCocoaErrorDomain && failure.code == NSFileReadNoSuchFileError { return nil }
      throw error
    }
  }
  private func durableTuple(_ paths: Paths) throws -> DurableTuple {
    try DurableTuple(legacy: optionalData(paths.legacy), state: optionalData(paths.state),
      backup: optionalData(paths.session.appendingPathComponent("session-state-v1.backup.json")))
  }
  private func errorChain(_ failure: Error) -> [NSError] {
    var result: [NSError] = [], seen = Set<ObjectIdentifier>(), next: NSError? = failure as NSError
    while let current = next, seen.insert(ObjectIdentifier(current)).inserted {
      result.append(current); next = current.userInfo[NSUnderlyingErrorKey] as? NSError
    }
    return result
  }

  // No admission marker, default-directory lookup, Keychain, Store or fixture.
  // The runner/Simulator identities below are observations, not creation proof.
  func testEnvironmentProbe() throws {
    let test = Bundle(for: Self.self), main = Bundle.main
    let fields: [String: Any] = [
      "testBundleIdentifier": test.bundleIdentifier.map { $0 as Any } ?? NSNull(),
      "mainBundleIdentifier": main.bundleIdentifier.map { $0 as Any } ?? NSNull(),
      "home": NSHomeDirectory(),
      "simulatorUDID": ProcessInfo.processInfo.environment["SIMULATOR_UDID"].map { $0 as Any } ?? NSNull(),
      "injectedAdmission": ProcessInfo.processInfo.environment["P7_SESSIONSTORE_ADMISSION"].map { $0 as Any } ?? NSNull(),
      "mainNotificationAppGroup": main.object(forInfoDictionaryKey: "NotificationAppGroup") ?? NSNull(),
      "testNotificationAppGroup": test.object(forInfoDictionaryKey: "NotificationAppGroup") ?? NSNull(),
      "mainNotificationKeychainGroup": main.object(forInfoDictionaryKey: "NotificationKeychainGroup") ?? NSNull(),
      "testNotificationKeychainGroup": test.object(forInfoDictionaryKey: "NotificationKeychainGroup") ?? NSNull()
    ]
    let line = try JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys])
    print("P7_SESSIONSTORE_ENVIRONMENT_PROBE " + String(decoding: line, as: UTF8.self))
  }

  private func encoded<T: Encodable>(_ value: T) throws -> Data {
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    return try encoder.encode(value)
  }
  private func data(_ url: URL) throws -> Data { try Data(contentsOf: url) }
  private func canonical(_ url: URL) -> URL { url.standardizedFileURL.resolvingSymlinksInPath() }

  // Admission is an execution guard, not independent evidence of device creation.
  // The formal executor must retain its real CoreSimulator creation/install receipt.
  private func admit(_ caseName: String) throws -> Paths {
    let env = ProcessInfo.processInfo.environment, args = ProcessInfo.processInfo.arguments
    guard let raw = env["P7_SESSIONSTORE_ADMISSION"],
      let admission = try? JSONDecoder().decode(Admission.self, from: Data(raw.utf8)),
      admission.taskID == "p7-sessionstore-native-next", admission.caseName == caseName,
      admission.admissionVersion == 2,
      admission.createdEmptyForThisCase, UUID(uuidString: admission.deviceUDID) != nil,
      env["SIMULATOR_UDID"]?.lowercased() == admission.deviceUDID.lowercased(),
      Bundle(for: Self.self).bundleIdentifier == "com.easyai.agenthub.acceptance.stage64store.tests",
      let mainID = Bundle.main.bundleIdentifier, mainID == admission.expectedMainBundleIdentifier,
      mainID == "com.easyai.agenthub.acceptance.stage64store",
      Bundle.main.object(forInfoDictionaryKey: "NotificationAppGroup") == nil,
      Bundle.main.object(forInfoDictionaryKey: "NotificationKeychainGroup") == nil,
      Bundle(for: Self.self).object(forInfoDictionaryKey: "NotificationAppGroup") == nil,
      Bundle(for: Self.self).object(forInfoDictionaryKey: "NotificationKeychainGroup") == nil,
      !args.contains("-ui-testing"), !args.contains("-restore-testing"),
      !args.contains(where: { $0.hasPrefix("-easywork-") }),
      env["USAGE_ACCEPTANCE_FIXTURE"] == nil,
      env["CODEX_NOTIFICATION_TEST_DIRECTORY"] == nil else { throw HarnessFailure.admission }
    let home = canonical(URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true))
    let deviceRoot = canonical(URL(fileURLWithPath: admission.expectedDeviceDataRoot, isDirectory: true))
    let bundle = canonical(Bundle.main.bundleURL)
    let dataParent = deviceRoot.appendingPathComponent("Containers/Data/Application", isDirectory: true)
    let bundleParent = deviceRoot.appendingPathComponent("Containers/Bundle/Application", isDirectory: true)
    guard deviceRoot.path.lowercased().hasSuffix("/coresimulator/devices/" + admission.deviceUDID.lowercased() + "/data"),
      UUID(uuidString: home.lastPathComponent) != nil,
      home.deletingLastPathComponent().path == dataParent.path,
      bundle.lastPathComponent == "Stage64StoreTestHost.app",
      UUID(uuidString: bundle.deletingLastPathComponent().lastPathComponent) != nil,
      bundle.deletingLastPathComponent().deletingLastPathComponent().path == bundleParent.path else {
      throw HarnessFailure.unexpectedPath
    }
    // Xcode may install a new data container between runs. Bind this actual OS
    // home and signed host inside the exact independently admitted fresh device.
    // The executor must match both paths to post-run simctl app/data receipts.
    let binding: [String: Any] = ["caseName": caseName, "home": home.path,
      "bundle": bundle.path, "deviceDataRoot": deviceRoot.path,
      "deviceUDID": admission.deviceUDID, "processID": ProcessInfo.processInfo.processIdentifier,
      "mainBundleIdentifier": mainID,
      "testBundleIdentifier": Bundle(for: Self.self).bundleIdentifier ?? ""]
    let observed = try JSONSerialization.data(withJSONObject: binding, options: [.sortedKeys])
    print("P7_SESSIONSTORE_ADMITTED_CONTAINER " + String(decoding: observed, as: UTF8.self))
    func own(_ url: URL) throws -> URL {
      let checked = canonical(url)
      guard checked.path.hasPrefix(home.path + "/") else { throw HarnessFailure.unexpectedPath }
      return checked
    }
    let fm = FileManager.default
    let support = try own(fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0])
    let session = try own(support.appendingPathComponent("CodexManage", isDirectory: true))
    let notification = try own(TaskNotificationShared.directory)
    let sounds = try own(TaskNotificationSoundLibrary.directory)
    guard TaskNotificationShared.group == nil,
      notification.path == support.appendingPathComponent("TaskNotifications", isDirectory: true).path,
      sounds.path == home.appendingPathComponent("Library/Sounds", isDirectory: true).path else { throw HarnessFailure.unexpectedPath }
    for url in [session, notification, sounds,
      support.appendingPathComponent(PendingVoiceMigration.liveDirectory),
      support.appendingPathComponent(PendingVoiceMigration.archiveDirectory),
      support.appendingPathComponent("p7-sessionstore-admission")] {
      guard !fm.fileExists(atPath: url.path) else { throw HarnessFailure.notEmpty }
    }
    // These are real reads of this signed host/new-device keychain.
    for id in [credentialID, "easywork-oidc-session", "agent-cache-key-v1"] {
      guard try CredentialVault.existingToken(id) == nil else { throw HarnessFailure.notEmpty }
    }
    guard TaskNotificationShared.secret("device-key") == nil,
      TaskNotificationShared.secret("bindings") == nil else { throw HarnessFailure.notEmpty }
    try fm.createDirectory(at: support.appendingPathComponent("p7-sessionstore-admission"),
      withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    guard canonical(try PendingVoiceMigration.supportDirectory()) == support else { throw HarnessFailure.unexpectedPath }
    return Paths(support: support, session: session,
      legacy: session.appendingPathComponent("session.json"),
      state: session.appendingPathComponent("session-state-v1.json"), notification: notification, sounds: sounds)
  }

  private func original(_ operation: String) throws -> PendingVoiceMigration.Bundle {
    // Synthetic PCM16 mono 16 kHz WAV; no recording/VoiceAudioSession is used.
    let audio = Data([0x52,0x49,0x46,0x46,52,0,0,0,0x57,0x41,0x56,0x45,
      0x66,0x6d,0x74,0x20,16,0,0,0,1,0,1,0,0x80,0x3e,0,0,0,0x7d,0,0,2,0,16,0,
      0x64,0x61,0x74,0x61,16,0,0,0] + [UInt8](repeating: 0, count: 16))
    let row = try JSONSerialization.data(withJSONObject: [
      "target": "synthetic-target", "scopeKey": "synthetic-scope", "originalText": "synthetic voice evidence",
      "operationId": operation, "sha256": PendingVoiceMigration.sha(audio), "durationMs": 1,
      "sizeBytes": audio.count, "createdAt": Date(timeIntervalSince1970: 1).timeIntervalSinceReferenceDate
    ], options: [.sortedKeys])
    let files = [PendingVoiceMigration.File(name: operation + ".json", data: row),
      PendingVoiceMigration.File(name: operation + ".wav", data: audio)]
    let bundle = PendingVoiceMigration.Bundle(originals: [.init(files: files)])
    try bundle.validate(); return bundle
  }

  private func state(_ text: String, bytes: Data) throws -> SavedSession {
    var value = SavedSession()
    value.mobileCacheVersion = 2; value.readingDeviceId = "p7-local-reading-device"
    value.historyCacheNamespace = "p7-synthetic-history"; value.clients = []
    let file = LocalAttachment(id: "p7-synthetic-attachment", name: "synthetic.txt", size: bytes.count,
      fileName: attachmentName, isImage: false)
    var draft = Draft(); draft.text = text; draft.attachments = [file]
    value.drafts = ["p7-synthetic-draft": draft]
    let pending = PendingReply(hostId: "p7-synthetic-host", threadId: "p7-synthetic-thread",
      clientMessageId: operation, text: "immutable synthetic submitted body", attachmentIds: [], mode: "default",
      expectedTurnId: nil, model: nil, effort: nil, sourceAttachments: nil, knownRejected: nil)
    let target = AgentRuntimeTarget(agentId: "codex", hostId: pending.hostId,
      workspaceId: "p7-synthetic-workspace", runtimeId: "p7-synthetic-runtime", account: nil)
    let recovery = ReplyRecoveryRecord(original: pending, target: target, trust: "synthetic-query-only",
      fingerprint: try ReplyRecoveryPolicy.fingerprint(pending, target: target), phase: "uncertain", retryConsumed: true)
    value.outbox = [operation: pending]; value.replyRecovery = [operation: recovery]
    return value
  }

  @MainActor private func fixture(_ paths: Paths) throws -> (SessionStore, Data, EncryptedMigration.Payload, PendingVoiceMigration.Bundle) {
    let fm = FileManager.default, oldAttachment = Data("old synthetic attachment".utf8)
    try fm.createDirectory(at: paths.session.appendingPathComponent("Attachments"), withIntermediateDirectories: true)
    let oldState = try encoded(state("old synthetic draft", bytes: oldAttachment))
    try oldState.write(to: paths.legacy, options: [.atomic]); try oldState.write(to: paths.state, options: [.atomic])
    try oldState.write(to: paths.session.appendingPathComponent("session-state-v1.backup.json"), options: [.atomic])
    try oldAttachment.write(to: paths.session.appendingPathComponent("Attachments/" + attachmentName))
    try CredentialVault.set("p7-old-synthetic-identity", id: credentialID)
    try TaskNotificationShared.setSecret(Data(repeating: 0x11, count: 32), account: "device-key")
    try TaskNotificationShared.setBindings([:])
    var oldPrefs = TaskNotificationPreferences(); oldPrefs.generation = 7; oldPrefs.revision = "p7-old"
    try TaskNotificationShared.write(oldPrefs, name: "preferences.json")
    try TaskNotificationShared.write("p7-old-device", name: "device-id.json")
    try TaskNotificationShared.write([String](), name: "seen.json")
    try TaskNotificationShared.write([String](), name: "pending-revocations.json")
    try TaskNotificationShared.write([String: TaskHostNotificationRevocation](), name: "pending-host-revocations.json")
    try TaskNotificationShared.write([TaskNotificationSound](), name: "sounds.json")
    // Synthetic valid empty-ledger fixture with a nonzero generation exercises reset + rollback.
    try Data(#"{"version":1,"generation":9,"scopes":[],"threads":{}}"#.utf8)
      .write(to: paths.notification.appendingPathComponent("mobile-unread-v1.json"))
    XCTAssertEqual(MobileUnreadBadge.revision, "9")
    try TaskNotificationSoundLibrary.prepare()
    let oldOriginal = try original("00000000-0000-4000-8000-000000000071")
    try XCTUnwrap(PendingVoiceMigration.prepareRestore(oldOriginal)).publish()
    let store = SessionStore()
    XCTAssertFalse(store.localRecoveryRequired); XCTAssertTrue(store.saved.clients.isEmpty)
    XCTAssertNil(store.saved.selectedClientId)
    let incoming = try original("00000000-0000-4000-8000-000000000072")
    let newAttachment = Data("new synthetic attachment".utf8)
    var prefs = TaskNotificationPreferences(); prefs.enabled = true; prefs.generation = 20
    let payload = EncryptedMigration.Payload(version: 2, bundleIdentifier: try XCTUnwrap(Bundle.main.bundleIdentifier),
      state: try state("imported synthetic draft", bytes: newAttachment),
      credentials: [credentialID: "p7-new-synthetic-identity"], attachments: [attachmentName: newAttachment],
      notificationPreferences: prefs, notificationKey: Data(repeating: 0x22, count: 32),
      notificationDeviceId: "p7-import-device", notificationBindings: [:], notificationSounds: [], soundFiles: [:],
      pendingVoiceOriginals: incoming)
    return (store, oldState, payload, oldOriginal)
  }

  private func authoritativeFiles(_ paths: Paths) throws -> [String: Data] {
    var result: [String: Data] = [:]
    for name in ["sounds.json", "device-id.json", "preferences.json", "seen.json", "pending-revocations.json",
      "pending-host-revocations.json", "mobile-unread-v1.json"] {
      result["notification/" + name] = try data(paths.notification.appendingPathComponent(name))
    }
    result["attachment"] = try data(paths.session.appendingPathComponent("Attachments/" + attachmentName))
    if let bytes = try optionalData(paths.session.appendingPathComponent("session-state-v1.backup.json")) { result["backup"] = bytes }
    for name in ["soft.caf", "chime.caf", "attention.caf"] { result["sound/" + name] = try data(paths.sounds.appendingPathComponent(name)) }
    return result
  }

  @MainActor private func assertUnknownIntent(_ store: SessionStore, equals source: SavedSession) throws {
    XCTAssertTrue(store.saved.clients.isEmpty); XCTAssertNil(store.saved.selectedClientId)
    XCTAssertEqual(try encoded(store.saved.outbox), try encoded(source.outbox))
    let pending = try XCTUnwrap(store.saved.outbox?[operation]), record = try XCTUnwrap(store.saved.replyRecovery?[operation])
    let original = try XCTUnwrap(source.replyRecovery?[operation])
    XCTAssertEqual(try encoded(record.original), try encoded(original.original))
    XCTAssertEqual(record.fingerprint, original.fingerprint)
    XCTAssertEqual(try ReplyRecoveryPolicy.fingerprint(pending, target: record.target), original.fingerprint)
    XCTAssertEqual(record.phase, "uncertain"); XCTAssertNil(record.inspection)
    XCTAssertTrue(record.retryConsumed); XCTAssertFalse(ReplyRecoveryPolicy.mayRetry(pending, record: record))
  }

  @MainActor private func assertOriginals(_ store: SessionStore, paths: Paths,
    old: PendingVoiceMigration.Bundle, incoming: PendingVoiceMigration.Bundle) throws {
    let capture = try PendingVoiceMigration.capture(includeLive: false), bundle = try XCTUnwrap(capture.bundle)
    let expected = old.originals + incoming.originals
    XCTAssertEqual(Set(bundle.originals.map(\.id)), Set(expected.map(\.id)))
    XCTAssertEqual(bundle.inventoriedFileCount, old.inventoriedFileCount + incoming.inventoriedFileCount)
    XCTAssertEqual(bundle.inventoriedByteCount, old.inventoriedByteCount + incoming.inventoriedByteCount)
    for original in expected {
      let actual = try XCTUnwrap(bundle.originals.first { $0.id == original.id })
      for file in original.files {
        let retained = try XCTUnwrap(actual.files.first { $0.name == file.name })
        XCTAssertEqual(try retained.bytes.data(), try file.bytes.data())
      }
    }
    let export = try store.migrationPayload()
    XCTAssertEqual(export.pendingVoiceOriginals?.id, bundle.id)
    // Exercise the real Payload wire export and re-import, not only its capture object.
    let archive = try EncryptedMigration.seal(export, password: "p7-synthetic-native-password")
    let reopened = try EncryptedMigration.open(archive, password: "p7-synthetic-native-password")
    XCTAssertEqual(reopened.pendingVoiceOriginals?.id, bundle.id)
    XCTAssertEqual(try encoded(reopened.state.outbox), try encoded(export.state.outbox))
    XCTAssertFalse(FileManager.default.fileExists(atPath: paths.support.appendingPathComponent(PendingVoiceMigration.liveDirectory).path))
  }

  @MainActor func testActualSessionStoreRestoreSucceedsWithInertOriginalsAndUnknownIntent() throws {
    let paths = try admit("testActualSessionStoreRestoreSucceedsWithInertOriginalsAndUnknownIntent")
    let (store, _, payload, old) = try fixture(paths)
    try store.restoreMigration(payload)
    try assertUnknownIntent(store, equals: payload.state)
    XCTAssertEqual(store.drafts.values, payload.state.drafts)
    XCTAssertEqual(try data(paths.session.appendingPathComponent("Attachments/" + attachmentName)), payload.attachments[attachmentName])
    XCTAssertEqual(try CredentialVault.existingToken(credentialID), payload.credentials[credentialID])
    XCTAssertEqual(TaskNotificationShared.secret("device-key"), payload.notificationKey)
    XCTAssertFalse(TaskNotificationShared.preferences.enabled)
    XCTAssertEqual(TaskNotificationShared.preferences.generation, payload.notificationPreferences.generation + 1)
    XCTAssertEqual(MobileUnreadBadge.count, 0)
    let persisted = try JSONDecoder().decode(SavedSession.self, from: data(paths.state))
    XCTAssertEqual(try data(paths.legacy), try data(paths.state))
    XCTAssertEqual(persisted.drafts, payload.state.drafts)
    XCTAssertEqual(try encoded(persisted.outbox), try encoded(payload.state.outbox))
    XCTAssertTrue(persisted.clients.isEmpty)
    try assertOriginals(store, paths: paths, old: old, incoming: XCTUnwrap(payload.pendingVoiceOriginals))
  }

  @MainActor func testActualSessionStorePostOriginalPublicationPreWriterWritesFailureRetainsState() throws {
    let paths = try admit("testActualSessionStorePostOriginalPublicationPreWriterWritesFailureRetainsState")
    let (store, oldState, payload, old) = try fixture(paths)
    let beforeSaved = try encoded(store.saved), beforeDrafts = try encoded(store.drafts.values)
    let beforeFiles = try authoritativeFiles(paths), beforeBindings = TaskNotificationShared.secret("bindings")
    // Nonregular writer preflight failure after original publication. This does
    // not establish that the writer entered its compensating catch branch.
    // Keep the old primary bytes in the blocker: injection itself must not lose them.
    let fm = FileManager.default, retained = paths.state.appendingPathComponent("p7-retained-old-primary.json")
    XCTAssertEqual(try data(paths.state), oldState)
    try fm.removeItem(at: paths.state); try fm.createDirectory(at: paths.state, withIntermediateDirectories: false)
    try oldState.write(to: retained, options: [.atomic])
    var caught: Error?
    do { try store.restoreMigration(payload) } catch { caught = error }
    let retention = try XCTUnwrap(caught as? PendingVoiceMigration.EvidenceRetentionFailure)
    XCTAssertTrue(retention.published)
    XCTAssertEqual(retention.files, payload.pendingVoiceOriginals?.inventoriedFileCount)
    XCTAssertEqual(retention.bytes, payload.pendingVoiceOriginals?.inventoriedByteCount)
    // Require a real state-path error; writer R2 rejects this directory at
    // preflight, before it writes any durable destination.
    let io = retention.original as NSError
    XCTAssertTrue(io.domain == NSCocoaErrorDomain || io.domain == NSPOSIXErrorDomain)
    let errorPath = (io.userInfo[NSFilePathErrorKey] as? String)
      ?? (io.userInfo[NSURLErrorKey] as? URL)?.path
    XCTAssertEqual(errorPath, paths.state.path)
    XCTAssertEqual(try encoded(store.saved), beforeSaved)
    XCTAssertEqual(try encoded(store.drafts.values), beforeDrafts)
    XCTAssertEqual(try authoritativeFiles(paths), beforeFiles)
    XCTAssertEqual(try data(retained), oldState)
    XCTAssertEqual(try fm.contentsOfDirectory(atPath: paths.state.path), [retained.lastPathComponent])
    XCTAssertEqual(try CredentialVault.existingToken(credentialID), "p7-old-synthetic-identity")
    XCTAssertEqual(TaskNotificationShared.secret("device-key"), Data(repeating: 0x11, count: 32))
    XCTAssertEqual(TaskNotificationShared.secret("bindings"), beforeBindings)
    XCTAssertEqual(MobileUnreadBadge.revision, "9")
    try assertUnknownIntent(store, equals: payload.state)
    try assertOriginals(store, paths: paths, old: old, incoming: XCTUnwrap(payload.pendingVoiceOriginals))
    // Keep the old-legacy invariant. Passing this preflight case alone is not
    // evidence that post-write compensating rollback was exercised.
    XCTAssertEqual(try data(paths.legacy), oldState, "A failed restore must not replace the old legacy session")
  }

  @MainActor func testActualSessionStoreImmutableRegularStateFailureCompensatesWriterChanges() throws {
    let paths = try admit("testActualSessionStoreImmutableRegularStateFailureCompensatesWriterChanges")
    let (store, oldState, payload, old) = try fixture(paths)
    // Legal old regular state/legacy have already been loaded by the real Store.
    // Absence is part of the prior durable tuple; rollback must remove a backup
    // created by the failed replacement, not keep that new file as a "repair".
    let fm = FileManager.default, backup = paths.session.appendingPathComponent("session-state-v1.backup.json")
    try fm.removeItem(at: backup)
    let beforeTuple = try durableTuple(paths)
    XCTAssertEqual(beforeTuple.state, oldState); XCTAssertEqual(beforeTuple.legacy, oldState)
    XCTAssertNil(beforeTuple.backup)
    let beforeSaved = try encoded(store.saved), beforeDrafts = try encoded(store.drafts.values)
    let beforeFiles = try authoritativeFiles(paths), beforeBindings = TaskNotificationShared.secret("bindings")
    var regular = stat()
    guard Darwin.lstat(paths.state.path, &regular) == 0,
      regular.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
      regular.st_flags & UInt32(UF_IMMUTABLE) == 0,
      Darwin.access(paths.legacy.path, W_OK) == 0,
      Darwin.access(paths.session.path, W_OK) == 0 else { throw HarnessFailure.unexpectedPath }
    let originalFlags = regular.st_flags, device = regular.st_dev, inode = regular.st_ino
    let stateURL = paths.state
    // Clear only this exact runner-owned original file, including when the case
    // fails. The formal executor deletes its own Simulator only after runner exit
    // and confirmed flag cleanup. Never clear flags on any unrelated path.
    addTeardownBlock {
      var current = stat()
      guard Darwin.lstat(stateURL.path, &current) == 0,
        current.st_dev == device, current.st_ino == inode,
        current.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG) else {
        throw NSError(domain: "P7.ImmutableCleanup", code: 1, userInfo: [NSFilePathErrorKey: stateURL.path])
      }
      guard Darwin.chflags(stateURL.path, originalFlags) == 0 else {
        throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno), userInfo: [NSFilePathErrorKey: stateURL.path])
      }
      var cleared = stat()
      guard Darwin.lstat(stateURL.path, &cleared) == 0,
        cleared.st_flags == originalFlags else {
        throw NSError(domain: "P7.ImmutableCleanup", code: 2, userInfo: [NSFilePathErrorKey: stateURL.path])
      }
    }
    guard Darwin.chflags(stateURL.path, originalFlags | UInt32(UF_IMMUTABLE)) == 0 else {
      // A failed/unsupported OS flag operation is a formal capability blocker,
      // never XCTSkip and never evidence that compensating rollback passed.
      throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno), userInfo: [NSFilePathErrorKey: stateURL.path,
        NSLocalizedDescriptionKey: "P7 UF_IMMUTABLE unavailable: native compensation case remains pending"])
    }
    var flagged = stat()
    guard Darwin.lstat(stateURL.path, &flagged) == 0,
      flagged.st_dev == device, flagged.st_ino == inode,
      flagged.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
      flagged.st_flags & UInt32(UF_IMMUTABLE) != 0,
      Darwin.access(paths.session.path, W_OK) == 0 else { throw HarnessFailure.unexpectedPath }
    XCTAssertEqual(try data(stateURL), oldState) // Regular, readable preflight snapshot input.
    XCTAssertEqual(try durableTuple(paths), beforeTuple)
    var caught: Error?
    do { try store.restoreMigration(payload) } catch { caught = error }
    let retention = try XCTUnwrap(caught as? PendingVoiceMigration.EvidenceRetentionFailure)
    XCTAssertTrue(retention.published)
    XCTAssertEqual(retention.files, payload.pendingVoiceOriginals?.inventoriedFileCount)
    XCTAssertEqual(retention.bytes, payload.pendingVoiceOriginals?.inventoriedByteCount)
    // Require a write-permission failure, not a mapped snapshot/read error.
    let chain = errorChain(retention.original)
    XCTAssertTrue(chain.contains { $0.domain == NSCocoaErrorDomain && $0.code == NSFileWriteNoPermissionError })
    XCTAssertTrue(chain.contains { $0.domain == NSPOSIXErrorDomain && $0.code == Int(EPERM) },
      "Require the real immutable-file permission error, not preflight validation")
    XCTAssertTrue(chain.contains {
      ($0.userInfo[NSFilePathErrorKey] as? String) == stateURL.path
        || ($0.userInfo[NSURLErrorKey] as? URL)?.path == stateURL.path
    }, "Require the actual state-file path in the OS error chain")
    XCTAssertEqual(try durableTuple(paths), beforeTuple)
    XCTAssertEqual(try encoded(store.saved), beforeSaved)
    XCTAssertEqual(try encoded(store.drafts.values), beforeDrafts)
    XCTAssertEqual(try authoritativeFiles(paths), beforeFiles)
    XCTAssertEqual(try CredentialVault.existingToken(credentialID), "p7-old-synthetic-identity")
    XCTAssertEqual(TaskNotificationShared.secret("device-key"), Data(repeating: 0x11, count: 32))
    XCTAssertEqual(TaskNotificationShared.secret("bindings"), beforeBindings)
    XCTAssertEqual(MobileUnreadBadge.revision, "9")
    try assertUnknownIntent(store, equals: payload.state)
    try assertOriginals(store, paths: paths, old: old, incoming: XCTUnwrap(payload.pendingVoiceOriginals))
  }
}
