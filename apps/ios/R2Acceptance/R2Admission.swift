import Foundation
import Security

#if !R2_REAL_DEV_ACCEPTANCE || !DEBUG
#error("R2 admission belongs only to the dedicated Debug acceptance target")
#endif

enum R2Admission {
  private final class State: @unchecked Sendable {
    let lock = NSLock()
    var context: Context?
  }
  struct Context {
    let data: Data
    let manifest: R2AdmissionPolicy.Manifest
    let lease: R2AdmissionPolicy.Lease
  }
  private static let state = State()
  static func start() throws {
    let env = ProcessInfo.processInfo.environment
    guard let manifest = env["R2_MANIFEST_BASE64"].flatMap({ Data(base64Encoded: $0) }), manifest.count <= 16384,
      let lease = env["R2_LEASE_BASE64"].flatMap({ Data(base64Encoded: $0) }), lease.count <= 65536 else {
      throw R2AdmissionPolicy.Denied(reason: "explicit manifest/lease required")
    }
    let context = Context(data: manifest, manifest: try JSONDecoder().decode(R2AdmissionPolicy.Manifest.self, from: manifest),
      lease: try JSONDecoder().decode(R2AdmissionPolicy.Lease.self, from: lease))
    try validate(context)
    let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    let marker = root.appendingPathComponent("r2-origin.sha256")
    let digest = Data(R2AdmissionPolicy.hash(manifest).utf8)
    if FileManager.default.fileExists(atPath: marker.path) {
      let attrs = try FileManager.default.attributesOfItem(atPath: marker.path)
      let existing = try Data(contentsOf: marker)
      try R2AdmissionPolicy.require(attrs[.type] as? FileAttributeType == .typeRegular &&
        existing == digest, "container origin mismatch")
    } else {
      // No reset and no reads of a saved credential. Uninstall/reinstall with a
      // surviving own-group Keychain is deliberately not treated as a fresh run.
      let contents = FileManager.default.fileExists(atPath: root.path)
        ? try FileManager.default.contentsOfDirectory(atPath: root.path) : []
      try R2AdmissionPolicy.require(contents.isEmpty, "first product storage is not empty")
      let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      let documentsEmpty = try !FileManager.default.fileExists(atPath: documents.path)
        || FileManager.default.contentsOfDirectory(atPath: documents.path).isEmpty
      try R2AdmissionPolicy.require(documentsEmpty &&
        (UserDefaults.standard.persistentDomain(forName: R2AdmissionPolicy.bundle) ?? [:]).isEmpty,
        "first documents/preferences are not empty")
      guard let group = Bundle.main.object(forInfoDictionaryKey: "R2OwnKeychainGroup") as? String,
        group.hasSuffix(R2AdmissionPolicy.bundle), !group.contains("$(") else {
        throw R2AdmissionPolicy.Denied(reason: "own Keychain group missing")
      }
      for kind in [kSecClassGenericPassword, kSecClassKey] {
        let query: [String: Any] = [kSecClass as String: kind, kSecAttrAccessGroup as String: group,
          kSecReturnAttributes as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        // Presence only; never request key/token bytes or inspect another group.
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        try R2AdmissionPolicy.require(status == errSecItemNotFound, "own Keychain not empty or unavailable (\(status))")
      }
      try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
      try digest.write(to: marker, options: [.withoutOverwriting, .completeFileProtectionUntilFirstUserAuthentication])
      try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: marker.path)
    }
    state.lock.lock(); defer { state.lock.unlock() }
    state.context = context
  }
  private static func validate(_ context: Context) throws {
    try R2AdmissionPolicy.validate(context.manifest, lease: context.lease, data: context.data,
      bundle: Bundle.main.bundleIdentifier, now: Date().timeIntervalSince1970)
  }
  @discardableResult static func require() throws -> Context {
    state.lock.lock(); let context = state.context; state.lock.unlock()
    guard let context else { throw R2AdmissionPolicy.Denied(reason: "App admission has not completed") }
    try validate(context)
    return context
  }
  static func connection(_ connection: AgentConnection) throws {
    let context = try require()
    try R2AdmissionPolicy.connection(context.manifest, target: connection.target,
      candidates: connection.candidates.map { ($0.transportId, $0.endpoint, $0.peerFingerprint) })
  }
  static func client(_ client: Client) throws {
    guard !client.isDemo, let connection = client.agentConnection else {
      throw R2AdmissionPolicy.Denied(reason: "legacy/demo client")
    }
    try self.connection(connection)
  }
  static func restored(_ saved: SavedSession, initial: Bool = false) throws {
    let context = try require()
    try R2AdmissionPolicy.require(!initial || context.lease.mode != "enroll" || saved.clients.isEmpty, "existing enrollment; query original IDs")
    for client in saved.clients { try self.client(client) }
    let ids = Set(saved.clients.map(\.id))
    let creations = Array((saved.desktopCreations ?? [:]).values) + (saved.detachedDesktopCreations ?? [:]).values.flatMap { $0 }
    for pending in creations {
      try R2AdmissionPolicy.require(ids.contains(pending.hostId) &&
        pending.capturedTarget?.matchesCapturedPayload(context.manifest.target) == true, "foreign creation receipt")
    }
    for pending in (saved.threadForks ?? [:]).values {
      try R2AdmissionPolicy.require(pending.sourceTarget.matchesCapturedPayload(context.manifest.target), "foreign fork receipt")
    }
    for pending in (saved.outbox ?? [:]).values {
      try R2AdmissionPolicy.require(ids.contains(pending.hostId), "foreign reply receipt")
    }
    // Existing UNKNOWN records remain untouched. New-target restoration never
    // starts their observers; a native examiner must query the captured ID.
  }
  static func peer(target: EasyWorkTarget?, fingerprint: String?, publicKey: String?, endpoints: [String]) throws {
    let context = try require()
    guard let target else { throw R2AdmissionPolicy.Denied(reason: "target required before host probe") }
    let full = AgentRuntimeTarget(agentId: "easywork", hostId: target.desktopDeviceId,
      workspaceId: target.workspaceId, runtimeId: target.runtimeId, account: target.account)
    try R2AdmissionPolicy.require(publicKey == context.manifest.publicKey, "peer public key")
    try R2AdmissionPolicy.connection(context.manifest, target: full,
      candidates: endpoints.map { ("lan-wss", $0, fingerprint) })
  }
  static func offer(_ offer: EasyWorkLANOffer) throws {
    let context = try require()
    try R2AdmissionPolicy.require(context.lease.mode == "enroll", "fresh enrollment lease required")
    try peer(target: offer.target, fingerprint: offer.fingerprint, publicKey: offer.publicKey, endpoints: offer.endpoints)
    let payload = try JSONSerialization.data(withJSONObject: [
      "version": 1, "taskId": R2AdmissionPolicy.task, "caseId": context.manifest.caseId,
      "manifestSHA256": R2AdmissionPolicy.hash(context.data), "leaseId": context.lease.leaseId,
      "offerId": offer.offerId, "phase": "outcome-unknown", "capturedBeforeIdentityAndTransport": true
    ], options: .sortedKeys)
    let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    try R2AdmissionPolicy.captureEnrollment(root: root, payload: payload)
  }
  static func request(_ method: String, id: String, params: Data, connection: AgentConnection) throws {
    let context = try require()
    try self.connection(connection)
    try R2AdmissionPolicy.request(context.lease, method: method, id: id, params: params)
  }
}
