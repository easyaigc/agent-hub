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
  static func seal(_ data: Data, password: String) throws -> Data {
    guard data.count <= 256 * 1024 * 1024 else { throw NSError(domain: "Migration", code: 3) }
    var bytes = [UInt8](repeating: 0, count: 16)
    guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw NSError(domain: "Migration", code: 4) }
    let salt = Data(bytes), secret = try key(password, salt: salt)
    guard let combined = try AES.GCM.seal(data, using: secret, authenticating: context).combined else { throw NSError(domain: "Migration", code: 5) }
    return try JSONEncoder().encode(Envelope(salt: salt, ciphertext: combined))
  }
  static func open(_ data: Data, password: String) throws -> Data {
    guard data.count <= 256 * 1024 * 1024 else { throw NSError(domain: "Migration", code: 3) }
    let envelope = try JSONDecoder().decode(Envelope.self, from: data)
    guard envelope.version == 1 else { throw NSError(domain: "Migration", code: 6) }
    return try AES.GCM.open(AES.GCM.SealedBox(combined: envelope.ciphertext), using: key(password, salt: envelope.salt), authenticating: context)
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
  }
  static func seal(_ payload: Payload, password: String) throws -> Data {
    try EncryptedMigrationCrypto.seal(JSONEncoder().encode(payload), password: password)
  }
  static func open(_ data: Data, password: String) throws -> Payload {
    guard data.count <= 256 * 1024 * 1024 else { throw HostServiceError.message(L10n.tr("迁移文件过大。")) }
    do {
      let bytes = try EncryptedMigrationCrypto.open(data, password: password)
      let payload = try JSONDecoder().decode(Payload.self, from: bytes)
      guard payload.version == 1, MigrationIdentityPolicy.accepts(source: payload.bundleIdentifier, destination: Bundle.main.bundleIdentifier),
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

struct EncryptedMigrationView: View {
  @AppStorage(L10n.storageKey) private var appLanguage = AppLanguage.system.rawValue
  @ObservedObject var store: SessionStore
  @State private var password = ""
  @State private var document: MigrationFile?
  @State private var exporting = false
  @State private var importing = false
  @State private var busy = false
  @State private var result: String?
  var body: some View {
    Form {
      Section {
        SecureField(L10n.tr("迁移密码（至少 12 个字符）"), text: $password).textContentType(.newPassword)
        Button { export() } label: { Label(busy ? L10n.tr("正在准备…") : L10n.tr("导出加密迁移文件"), systemImage: "lock.doc") }.disabled(busy || password.count < 12)
        Button(L10n.tr("导入加密迁移文件"), systemImage: "square.and.arrow.down") { importing = true }.disabled(busy || password.count < 12)
      } footer: { Text(L10n.tr("切换签名团队前先导出，并妥善保存密码。Agent Hub 可导入原 Codex Manage 的加密备份，恢复连接、草稿、附件及未确认回执。文件只通过你选择的位置保存；导入适用于尚未添加真实电脑的新安装，导入后需重新开启通知。")) }
      if let result { Section { Text(result).accessibilityIdentifier("migration-result") } }
    }.navigationTitle(L10n.tr("安全迁移"))
      .fileExporter(isPresented: $exporting, document: document, contentType: .data, defaultFilename: "AgentHub.codexmigration") { value in
        if case .failure = value { result = L10n.tr("文件未保存，请重新导出。") } else { result = L10n.tr("加密文件已保存。确认文件和密码可用后再更换签名。") }
        document = nil
      }
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
        document = MigrationFile(data: data); exporting = true
      } catch { result = error.localizedDescription }
    }
  }
}
#endif
