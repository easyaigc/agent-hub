import Foundation
import MobileCore

func verifyAttachmentPreparationVectors(_ path: String) throws {
  let root = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
  precondition(root["schemaVersion"] as? Int == 1)
  let cases = root["cases"] as! [[String: Any]]
  for row in cases {
    let actual: Bool
    switch row["kind"] as! String {
    case "reserve":
      actual = AttachmentPreparationRules.shared.canReserve(existing: Int32(row["existing"] as! Int),
        reserved: Int32(row["reserved"] as! Int), requested: Int32(row["requested"] as! Int))
    case "commit":
      actual = AttachmentPreparationRules.shared.canCommit(capturedTarget: row["target"] as! String,
        currentTarget: row["currentTarget"] as! String, capturedGeneration: row["generation"] as! String,
        currentGeneration: row["currentGeneration"] as? String, alreadyCompleted: row["completed"] as! Bool)
    case "suggest":
      actual = AttachmentPreparationRules.shared.canSuggest(enabled: row["enabled"] as! Bool,
        readable: row["readable"] as! Bool, baselineKnown: row["baseline"] as! Bool, ignored: row["ignored"] as! Bool,
        addedAtSeconds: (row["added"] as? NSNumber).map { KotlinDouble(value: $0.doubleValue) },
        nowSeconds: (row["now"] as! NSNumber).doubleValue)
    default: preconditionFailure("Unknown attachment vector kind")
    }
    precondition(actual == row["expected"] as! Bool, row["name"] as! String)
  }
  print("Attachment preparation vectors: \(cases.count)")
}
