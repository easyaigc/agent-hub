import Foundation
import CryptoKit
import MobileCore

// Error DTO only; the complete credential opener and Data codec below are copied byte-for-byte from production at runtime.
enum HostServiceError: Error { case invalidResponse }

func verifyPrivateCredential(_ path: String) throws {
  let root = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
  let identity = root["identity"] as! [String: String]
  let target = root["target"] as! [String: Any]
  let account = target["account"] as! [String: String]
  let scope = TargetScope(agentId: target["agentId"] as! String, accountAuthority: account["authority"], accountSubject: account["subject"], hostId: target["hostId"] as! String, workspaceId: target["workspaceId"] as! String, runtimeId: target["runtimeId"] as! String)
  let reply = root["reply"] as! [String: Any]
  func encode(_ value: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) }
  let clear = try CodexPrivateCredential.open(encode(reply), target: scope, privateKey: identity["privateKey"]!, publicKey: identity["publicKey"]!)
  precondition(clear == root["expectedToken"] as? String)
  let context = PrivateConnectionRules.shared.context(target: scope, publicKey: identity["publicKey"]!)
  precondition(Data(context.utf8).map { String(format: "%02x", $0) }.joined() == root["contextUTF8Hex"] as? String)
  let mutations: [(inout [String: Any]) -> Void] = [
    { value in var target = value["target"] as! [String: Any]; var account = target["account"] as! [String: String]; account["subject"] = "different"; target["account"] = account; value["target"] = target },
    { $0["publicKey"] = String(repeating: "K", count: 43) },
    { $0["ephemeralPublicKey"] = String(repeating: "A", count: 43) },
    { value in var bytes = Data(base64URL: value["ciphertext"] as! String)!; bytes[0] ^= 1; value["ciphertext"] = bytes.base64URL },
    { value in value["nonce"] = (value["nonce"] as! String) + "=" },
    { $0["unexpected"] = true },
  ]
  for mutate in mutations {
    var bad = reply; mutate(&bad)
    var rejected = false
    do { _ = try CodexPrivateCredential.open(encode(bad), target: scope, privateKey: identity["privateKey"]!, publicKey: identity["publicKey"]!) } catch { rejected = true }
    precondition(rejected, "A malformed private credential was accepted")
  }
  print("Swift/Kotlin private credential passed: actual iOS helper + cross-platform vector and 6 rejection cases")
}
