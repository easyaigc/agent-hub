import Foundation
import MobileCore

// Synthetic versioned vectors; this does not record audio or call a transcription service.
func verifySharingVoiceVectors(_ path: String) throws {
  let root = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
  func encode(_ value: Any) throws -> String { String(data: try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), encoding: .utf8)! }
  func scope(_ value: [String: Any]) -> TargetScope {
    TargetScope(agentId: value["agentId"] as! String, accountAuthority: value["accountAuthority"] as? String, accountSubject: value["accountSubject"] as? String,
      hostId: value["hostId"] as! String, workspaceId: value["workspaceId"] as! String, runtimeId: value["runtimeId"] as! String)
  }
  let consent = root["consent"] as! [[String: Any]], voice = root["voice"] as! [[String: Any]], manifests = root["manifests"] as! [[String: Any]]
  for row in consent {
    let a = scope(row["a"] as! [String: Any]), b = scope(row["b"] as! [String: Any])
    precondition(AISharingRules.shared.matches(a: a, aModel: row["aModel"] as! String, b: b, bModel: row["bModel"] as! String) == row["expected"] as! Bool, row["name"] as! String)
  }
  for row in voice {
    let original = try encode(row["manifest"]!), receipt = try encode(row["receipt"]!)
    precondition(VoiceTranscriptionRules.shared.receiptMatches(expected: original, receipt: receipt) == row["expected"] as! Bool, row["name"] as! String)
  }
  for row in manifests {
    let json = try encode(row["value"]!), expected = row["expected"] as! Bool
    precondition(VoiceTranscriptionRules.shared.validateManifest(json: json) == expected, row["name"] as! String)
    precondition((CodexHttpContract.shared.validate(json: json, definition: "voiceManifest") == nil) == expected, row["name"] as! String)
  }
  print("Swift/Kotlin sharing/voice vectors: \(consent.count) consent, \(voice.count) receipts, \(manifests.count) manifests")
}
