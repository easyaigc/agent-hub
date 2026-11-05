import CommonCrypto
import CryptoKit
import Foundation
import Security

enum MigrationIdentityPolicy {
  static func accepts(source: String, destination: String?) -> Bool {
    guard !source.isEmpty, let destination, !destination.isEmpty else { return false }
    if source == destination { return true }
    // The formal Agent Hub app can explicitly import the original personal installation.
    return source == "com.easyai.codexmanage" && destination == "com.easyai.agenthub"
  }
}

enum EncryptedMigrationCrypto {
  struct Envelope: Codable { var version = 1; var salt: Data; var ciphertext: Data }
  static let context = Data("codex-manage/encrypted-migration/v1".utf8)
  static func key(_ password: String, salt: Data) throws -> SymmetricKey {
    guard password.count >= 12, salt.count == 16 else { throw NSError(domain: "Migration", code: 1, userInfo: [NSLocalizedDescriptionKey: L10n.tr("迁移密码至少需要 12 个字符。")]) }
    let input = password.utf8CString; var result = [UInt8](repeating: 0, count: 32)
    let status = input.withUnsafeBufferPointer { passwordBytes in salt.withUnsafeBytes { saltBytes in
      CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), passwordBytes.baseAddress!, input.count - 1, saltBytes.bindMemory(to: UInt8.self).baseAddress!, salt.count,
        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 210_000, &result, 32)
    } }
    guard status == kCCSuccess else { throw NSError(domain: "Migration", code: 2) }
    return SymmetricKey(data: result)
  }
  static func seal(_ data: Data, password: String, version: Int = 1) throws -> Data {
    guard version == 1 || version == 2 else { throw NSError(domain: "Migration", code: 6) }
    guard data.count <= 256 * 1024 * 1024 else { throw NSError(domain: "Migration", code: 3) }
    var bytes = [UInt8](repeating: 0, count: 16)
    guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw NSError(domain: "Migration", code: 4) }
    let salt = Data(bytes), secret = try key(password, salt: salt)
    guard let combined = try AES.GCM.seal(data, using: secret, authenticating: version == 1 ? context : Data("codex-manage/encrypted-migration/v2".utf8)).combined else { throw NSError(domain: "Migration", code: 5) }
    let result = try JSONEncoder().encode(Envelope(version: version, salt: salt, ciphertext: combined))
    guard result.count <= 256 * 1024 * 1024 else { throw NSError(domain: "Migration", code: 3) }
    return result
  }
  static func open(_ data: Data, password: String) throws -> Data { try openVersioned(data, password: password).bytes }
  static func openVersioned(_ data: Data, password: String) throws -> (version: Int, bytes: Data) {
    guard data.count <= 256 * 1024 * 1024 else { throw NSError(domain: "Migration", code: 3) }
    guard try VoiceMigrationJSON.topLevelKeys(data) == ["version", "salt", "ciphertext"] else { throw NSError(domain: "Migration", code: 6) }
    let envelope = try JSONDecoder().decode(Envelope.self, from: data)
    guard envelope.version == 1 || envelope.version == 2 else { throw NSError(domain: "Migration", code: 6) }
    let authentication = envelope.version == 1 ? Self.context : Data("codex-manage/encrypted-migration/v2".utf8)
    let clear = try AES.GCM.open(AES.GCM.SealedBox(combined: envelope.ciphertext), using: key(password, salt: envelope.salt), authenticating: authentication)
    return (envelope.version, clear)
  }
}

#if os(iOS)
import SwiftUI
import UniformTypeIdentifiers

enum EncryptedMigration {
  // All fields are value snapshots; export workers receive an immutable copy.
  struct Payload: Codable, @unchecked Sendable {
    var version = 1
    var bundleIdentifier: String
    var state: SavedSession
    var credentials: [String: String]
    var attachments: [String: Data]
    var notificationPreferences: TaskNotificationPreferences
    var notificationKey: Data?
    var notificationDeviceId: String?
    var notificationBindings: [String: TaskNoticeBinding]
    var notificationSounds: [TaskNotificationSound]
    var soundFiles: [String: Data]
    var notificationSeen: [String]? = nil
    var notificationNotices: [TaskNotificationEvent]? = nil
    var notificationHostRevocations: [String: TaskHostNotificationRevocation]? = nil
    var easyworkAuthorizationPolicyVersion: Int? = nil
    var notificationInstallationPolicyVersion: Int? = nil
    var pendingVoiceOriginals: PendingVoiceMigration.Bundle? = nil
    // Export-only stable source witness; never appears in the encrypted wire payload.
    var pendingVoiceSource: PendingVoiceMigration.Capture? = nil
    enum CodingKeys: String, CodingKey {
      case version, bundleIdentifier, state, credentials, attachments, notificationPreferences
      case notificationKey, notificationDeviceId, notificationBindings, notificationSounds, soundFiles
      case notificationSeen, notificationNotices, notificationHostRevocations, pendingVoiceOriginals
      case easyworkAuthorizationPolicyVersion, notificationInstallationPolicyVersion
    }
  }
  static func validateOriginals(_ payload: Payload) throws {
    guard payload.easyworkAuthorizationPolicyVersion == nil || payload.easyworkAuthorizationPolicyVersion == 1,
      payload.notificationInstallationPolicyVersion == nil || payload.notificationInstallationPolicyVersion == 1 else { throw HostServiceError.invalidResponse }
    guard (payload.version == 1 && payload.pendingVoiceOriginals == nil) ||
      (payload.version == 2 && payload.pendingVoiceOriginals != nil) else { throw HostServiceError.invalidResponse }
    if let source = payload.pendingVoiceSource { guard source.bundle?.id == payload.pendingVoiceOriginals?.id else { throw HostServiceError.invalidResponse } }
    try payload.pendingVoiceOriginals?.validate()
  }
  static let installationCredentials: Set<String> = ["easywork-device-identity", "easywork-oidc-session", "easywork-oidc-pending", "easywork-oidc-last"]
  static func credentialIds(_ clients: [Client], requireGrant: Bool, read: (String) throws -> String?) throws -> Set<String> {
    var ids = Set(clients.filter { !$0.isDemo }.flatMap { [$0.id, $0.agentConnection?.credentialRef].compactMap { $0 } } + ["agent-cache-key-v1"])
    for client in clients where !client.isDemo && client.agentConnection?.target.agentId == "easywork" {
      guard let connection = client.agentConnection, let value = try read(connection.credentialRef) else { throw HostServiceError.invalidResponse }
      let credential = try JSONDecoder().decode(EasyWorkCredential.self, from: Data(value.utf8))
      let binding = credential.binding
      let target = AgentRuntimeTarget(agentId: "easywork", hostId: binding.desktopDeviceId, workspaceId: binding.target.workspaceId, runtimeId: binding.target.runtimeId, account: binding.account)
      guard connection.target == target, let secret = Data(base64URL: credential.privateKey), secret.count == 32,
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: secret).publicKey.rawRepresentation.base64URL == binding.mobilePublicKey else { throw HostServiceError.invalidResponse }
      if let ref = credential.grantRef {
        guard ref.range(of: "^easywork-device-grant:[a-f0-9]{64}$", options: .regularExpression) != nil else { throw HostServiceError.invalidResponse }
        ids.insert(ref)
        if let saved = try read(ref) {
          let record = try easyWorkObject(Data(saved.utf8))
          guard let grantValue = record["grant"], let origin = record["origin"] as? String else { throw HostServiceError.invalidResponse }
          _ = try EasyWorkDeviceOffer.origin(origin)
          let grant = try JSONDecoder().decode(EasyWorkDeviceGrant.self, from: easyWorkJSON(grantValue))
          let expected = credential.relayBinding ?? binding
          guard grant.binding == expected, grant.deviceId == binding.mobileDeviceId, grant.grantId == expected.deviceAuthorization?.grantId else { throw HostServiceError.invalidResponse }
        } else if requireGrant { throw HostServiceError.invalidResponse }
      } else if binding.deviceAuthorization != nil { throw HostServiceError.invalidResponse }
    }
    return ids
  }
  static func seal(_ payload: Payload, password: String) throws -> Data {
    try validateOriginals(payload)
    try payload.pendingVoiceSource?.unchanged()
    let result = try EncryptedMigrationCrypto.seal(JSONEncoder().encode(payload), password: password, version: payload.version)
    try payload.pendingVoiceSource?.unchanged()
    return result
  }
  static func open(_ data: Data, password: String) throws -> Payload {
    guard data.count <= 256 * 1024 * 1024 else { throw HostServiceError.message(L10n.tr("迁移文件过大。")) }
    do {
      let clear = try EncryptedMigrationCrypto.openVersioned(data, password: password)
      let keys = try VoiceMigrationJSON.topLevelKeys(clear.bytes)
      let known: Set<String> = ["version", "bundleIdentifier", "state", "credentials", "attachments", "notificationPreferences", "notificationKey", "notificationDeviceId", "notificationBindings", "notificationSounds", "soundFiles", "notificationSeen", "notificationNotices", "notificationHostRevocations", "pendingVoiceOriginals", "easyworkAuthorizationPolicyVersion", "notificationInstallationPolicyVersion"]
      guard keys.isSubset(of: known), clear.version != 1 || !keys.contains("pendingVoiceOriginals") else { throw HostServiceError.invalidResponse }
      let payload = try JSONDecoder().decode(Payload.self, from: clear.bytes)
      try validateOriginals(payload)
      guard payload.version == clear.version, MigrationIdentityPolicy.accepts(source: payload.bundleIdentifier, destination: Bundle.main.bundleIdentifier),
        payload.attachments.keys.allSatisfy(safeFilename), payload.soundFiles.keys.allSatisfy(safeFilename) else { throw HostServiceError.invalidResponse }
      return payload
    } catch { throw HostServiceError.message(L10n.tr("密码错误或迁移文件已损坏，原有数据未更改。")) }
  }
  static func safeFilename(_ name: String) -> Bool { !name.isEmpty && name != "." && name != ".." && !name.contains("/") && !name.contains("\\") && !name.contains("\0") }
}

struct MigrationFile: FileDocument {
  static var readableContentTypes: [UTType] { [.data] }
  var data: Data
  init(data: Data) { self.data = data }
  init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
  func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

/// Read-only projection of the canonical inert originals. No second archive wire format or restore path.
enum PreservedVoiceOriginals {
  struct Row: Identifiable, Equatable, Codable, Sendable {
    let originalId: String
    let fileName: String
    let size: Int
    let sha256: String
    var id: String { originalId + ":" + fileName }
  }
  static func rows(includeLive: Bool = false) throws -> [Row] {
    let captured = try PendingVoiceMigration.captureFinalized(includeLive: includeLive)
    let result = captured.bundle?.originals.flatMap { original in
      original.files.map { Row(originalId: original.id, fileName: $0.name, size: $0.size, sha256: $0.sha256) }
    } ?? []
    try captured.unchanged()
    return result
  }
  static func data(_ row: Row) throws -> Data {
    let captured = try PendingVoiceMigration.captureFinalized(includeLive: false)
    guard let original = captured.bundle?.originals.first(where: { $0.id == row.originalId }),
      let file = original.files.first(where: { $0.name == row.fileName }),
      file.size == row.size, file.sha256 == row.sha256 else { throw HostServiceError.invalidResponse }
    let bytes = try file.bytes.data()
    try captured.unchanged()
    guard bytes.count == row.size, PendingVoiceMigration.sha(bytes) == row.sha256 else { throw HostServiceError.invalidResponse }
    return bytes
  }
}

private struct PreservedVoiceOriginalFile: FileDocument {
  static var readableContentTypes: [UTType] { [.data] }
  var data: Data
  init(data: Data) { self.data = data }
  init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
  func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

private struct PreservedVoiceOriginalSection: View {
  let records: [PreservedVoiceOriginals.Row]
  let readError: String?
  @State private var document: PreservedVoiceOriginalFile?
  @State private var filename = "original.wav"
  @State private var exporting = false
  @State private var busy = false
  @State private var result: String?
  var body: some View {
    Section {
      ForEach(records) { record in
        VStack(alignment: .leading, spacing: 6) {
          Text(L10n.tr("原录音 · 只读保留")).font(.headline)
          Text(record.fileName).font(.caption).textSelection(.enabled)
          Text(ByteCountFormatter.string(fromByteCount: Int64(record.size), countStyle: .file)).font(.caption).foregroundStyle(.secondary)
          Button(L10n.tr("保存文件")) { save(record) }.disabled(busy)
            .accessibilityIdentifier("migration-save-voice-" + record.id)
        }.accessibilityIdentifier("migration-voice-record-" + record.id)
      }
      if let readError { Text(readError).accessibilityIdentifier("migration-voice-read-error") }
      if let result { Text(result).accessibilityIdentifier("migration-voice-result") }
    } header: { if !records.isEmpty { Text(L10n.tr("迁移的录音记录")) } }
      footer: { if !records.isEmpty { Text(L10n.tr("保留原音频、原目标及操作 ID；不会自动转写或重传。取消保存不会更改原记录。")) } }
      .fileExporter(isPresented: $exporting, document: document, contentTypes: [.data], defaultFilename: filename, onCompletion: { value in
        result = if case .success = value { L10n.tr("原录音已保存，原记录仍保留。") } else { L10n.tr("原录音未保存，原记录仍保留。") }
        document = nil
      }, onCancellation: { result = L10n.tr("原录音未保存，原记录仍保留。"); document = nil })
  }
  private func save(_ record: PreservedVoiceOriginals.Row) {
    busy = true
    Task { @MainActor in
      defer { busy = false }
      do {
        let bytes = try await Task.detached { try PreservedVoiceOriginals.data(record) }.value
        document = PreservedVoiceOriginalFile(data: bytes); filename = record.fileName; exporting = true
      } catch { result = error.localizedDescription }
    }
  }
}

struct EncryptedMigrationView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  @State private var password = ""
  @State private var document: MigrationFile?
  @State private var exportFilename = "AgentHub.codexmigration"
  @State private var exporting = false
  @State private var importing = false
  @State private var busy = false
  @State private var result: String?
  @State private var voiceRecords: [PreservedVoiceOriginals.Row] = []
  @State private var voiceReadError: String?
  var body: some View {
    Form {
      Section {
        SecureField(L10n.tr("迁移密码（至少 12 个字符）"), text: $password).textContentType(.newPassword)
          .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("migration-password")
        Button { export() } label: { Label(busy ? L10n.tr("正在准备…") : L10n.tr("导出加密迁移文件"), systemImage: "lock.doc") }.disabled(busy || password.count < 12).accessibilityIdentifier("migration-export")
        Button(L10n.tr("导入加密迁移文件"), systemImage: "square.and.arrow.down") { importing = true }.disabled(busy || password.count < 12).accessibilityIdentifier("migration-import")
      } footer: { Text(L10n.tr("切换签名团队前先导出，并妥善保存密码。Agent Hub 可导入原 Codex Manage 的加密备份，恢复连接、草稿、附件及未确认回执。文件只通过你选择的位置保存；导入适用于尚未添加真实电脑的新安装，导入后需重新开启通知。")) }
      if let result { Section { Text(result).accessibilityIdentifier("migration-result") } }
      PreservedVoiceOriginalSection(records: voiceRecords, readError: voiceReadError).id(store.saved.historyCacheNamespace)
    }.navigationTitle(L10n.tr("安全迁移"))
      // An empty lazy Form section has no row whose appearance can start a task.
      // The page owns loading, including the first render and post-import namespace.
      .task(id: store.saved.historyCacheNamespace) {
        await Task.yield()
        guard !Task.isCancelled else { return }
        voiceRecords = []; voiceReadError = nil
        do {
          let records = try await Task.detached { try PreservedVoiceOriginals.rows() }.value
          guard !Task.isCancelled else { return }
          voiceRecords = records
        } catch {
          guard !Task.isCancelled else { return }
          voiceReadError = error.localizedDescription
        }
      }
      .fileExporter(isPresented: $exporting, document: document, contentTypes: [.data], defaultFilename: exportFilename, onCompletion: { value in
        #if DEBUG
        store.observeHostedMigrationExport(value)
        #endif
        if case .failure = value { result = L10n.tr("文件未保存，请重新导出。") } else { result = L10n.tr("加密文件已保存。确认文件和密码可用后再更换签名。") }
        document = nil
      }, onCancellation: {
        #if DEBUG
        store.observeHostedMigrationExport(nil)
        #endif
        result = L10n.tr("文件未保存，请重新导出。")
        document = nil
      })
      .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { value in
        guard case .success(let url) = value else { return }
        busy = true
        Task { @MainActor in
          let accessed = url.startAccessingSecurityScopedResource(); defer { if accessed { url.stopAccessingSecurityScopedResource() }; busy = false }
          do {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= 256 * 1024 * 1024 else { throw HostServiceError.message(L10n.tr("迁移文件过大。")) }
            let passphrase = password
            let payload = try await Task.detached { try EncryptedMigration.open(Data(contentsOf: url), password: passphrase) }.value
            try store.restoreMigration(payload); password = ""; result = L10n.tr("连接、草稿和回执已恢复。通知已关闭，请先核对电脑身份再开启。")
          } catch { result = error.localizedDescription }
        }
      }
  }
  private func export() {
    busy = true
    Task { @MainActor in
      defer { busy = false }
      do {
        let payload = try store.migrationPayload(), passphrase = password
        let data = try await Task.detached { try EncryptedMigration.seal(payload, password: passphrase) }.value
        document = MigrationFile(data: data)
        exportFilename = "AgentHub-" + UUID().uuidString.lowercased() + ".codexmigration"
        exporting = true
      } catch { result = error.localizedDescription }
    }
  }
}
#endif
