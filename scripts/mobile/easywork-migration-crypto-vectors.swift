import Foundation
import CryptoKit

// Compile with the actual production EncryptedMigration.swift on macOS.
enum L10n { static func tr(_ value: String) -> String { value } }

@main struct MigrationCryptoVectors {
  static func main() throws {
    let password = "EasyWork-迁移-🔐-2026"
    let plain = Data("EasyWork immutable intent\n中文 😀\n".utf8)
    let archive = try EncryptedMigrationCrypto.seal(plain, password: password)
    let restored = try EncryptedMigrationCrypto.open(archive, password: password)
    precondition(restored == plain)
    let result: [String: Any] = ["version": 1, "producer": "production Swift EncryptedMigrationCrypto", "password": password,
      "plaintextBase64": plain.base64EncodedString(), "envelope": try JSONSerialization.jsonObject(with: archive)]
    let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
    try data.write(to: URL(fileURLWithPath: CommandLine.arguments[1]), options: [.atomic])
    print("Production Swift migration crypto UTF-8 fixture generated and decrypted")
  }
}
