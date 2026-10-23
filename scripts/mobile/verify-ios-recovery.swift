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
  let waitReceipts = root["waitReceipts"] as! [[String: Any]]
  for row in waitReceipts {
    let state = row["state"] as! String, source = row["source"] as? String, disposition = row["disposition"] as? String
    let present = (row["present"] as? Bool).map { KotlinBoolean(bool: $0) }
    precondition(ReplyRecoveryRules.shared.acceptedReceipt(state: state, source: source, present: present, disposition: disposition) == row["accepted"] as! Bool, row["name"] as! String)
    precondition(ReplyRecoveryRules.shared.releasesReplyWait(state: state, source: source, present: present, disposition: disposition) == row["releases"] as! Bool, row["name"] as! String)
  }
  let merges = root["receiptMerges"] as! [[String: Any]]
  for row in merges {
    precondition(ReplyRecoveryRules.shared.preservesReceiptEvidence(previous: row["previous"] as! String, source: row["source"] as? String, present: (row["present"] as? Bool).map { KotlinBoolean(bool: $0) }, disposition: row["disposition"] as? String, incoming: row["incoming"] as! String) == row["preserve"] as! Bool)
  }
  print("Swift/Kotlin receipt evidence merge passed: \(merges.count) cases")
  let queryBackoff = root["queryBackoff"] as! [[String: Any]]
  for row in queryBackoff {
    precondition(ReplyRecoveryRules.shared.nextReplyQueryDelayMillis(previousMillis: (row["previousMillis"] as! NSNumber).int64Value) == (row["nextMillis"] as! NSNumber).int64Value)
  }
  print("Swift/Kotlin original query backoff passed: \(queryBackoff.count) cases")
  print("Swift/Kotlin original receipt wait passed: \(waitReceipts.count) cases")
  print("Swift/Kotlin reply recovery passed: 4 Host fingerprints and 26 strict proof boundaries")
}
