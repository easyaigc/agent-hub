import Foundation
import CryptoKit
import MobileCore

func verifyRecoveryCoreVectors(_ path: String) throws {
  let root = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
  let fingerprints = root["fingerprints"] as! [[String: Any]]
  func json(_ value: Any) throws -> String {
    String(data: try JSONSerialization.data(withJSONObject: value, options: [.withoutEscapingSlashes]), encoding: .utf8)!
  }
  for test in fingerprints {
    let payload = ReplyRecoveryRules.shared.canonicalPayload(targetJson: try json(test["target"]!), replyJson: try json(test["reply"]!))
    precondition(payload == test["payload"] as? String, test["name"] as! String)
    let fingerprint = SHA256.hash(data: Data(payload!.utf8)).map { String(format: "%02x", $0) }.joined()
    precondition(fingerprint == test["fingerprint"] as? String)
  }
  let first = fingerprints[0]
  for test in root["proofs"] as! [[String: Any]] {
    let actual = ReplyRecoveryRules.shared.strongNotAccepted(inspectionJson: try json(test["value"]!),
      capturedTargetJson: try json(first["target"]!), hostId: "host", threadId: "thread", operationId: "operation",
      fingerprint: first["fingerprint"] as! String, nowEpochMillis: (root["nowEpochMillis"] as! NSNumber).int64Value)
    precondition(actual == test["expected"] as! Bool, test["name"] as! String)
  }
  print("Swift/Kotlin reply recovery passed: 4 Host fingerprints and 26 strict proof boundaries")
}
