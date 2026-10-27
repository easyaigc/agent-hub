import Foundation
import CryptoKit

// Synthetic envelope interoperability only; never reads an app container or restores a payload.
enum L10n { static func tr(_ value: String) -> String { value } }

@main struct MigrationVectors {
  static func main() throws {
    if CommandLine.arguments.count == 2 {
      let rows = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [[String: String]]
      for row in rows {
        let clear = try EncryptedMigrationCrypto.open(Data(base64Encoded: row["envelope"]!)!, password: row["password"]!)
        precondition(clear == Data(base64Encoded: row["plaintext"]!)!)
      }
      print("Swift original crypto authenticated \(rows.count) Android synthetic envelopes")
      return
    }
    let passwords = ["synthetic-only-password", "测试迁移密码十二个字符一二三四", "e\u{301}" + "12345678901", "👩‍💻" + "12345678901", "synthetic\0password"]
    let salt = Data(0..<16)
    let rows = try passwords.enumerated().map { index, password -> [String: String] in
      let clear = Data([0, 1, 127, 128, 255]) + Data("synthetic draft \(index)\n草稿".utf8)
      let key = try EncryptedMigrationCrypto.key(password, salt: salt).withUnsafeBytes { Data($0) }
      return ["password": password, "plaintext": clear.base64EncodedString(), "salt": salt.base64EncodedString(), "key": key.base64EncodedString(),
        "envelope": try EncryptedMigrationCrypto.seal(clear, password: password).base64EncodedString()]
    }
    FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: rows, options: [.prettyPrinted, .sortedKeys]))
    FileHandle.standardOutput.write(Data("\n".utf8))
  }
}
