import Foundation
import MobileCore

func verifyGenerationPreferenceVectors(_ path: String) throws {
  let root = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
  precondition(root["schemaVersion"] as! Int == 1)
  let cases = root["cases"] as! [[String: Any]]
  func values(_ row: [String: Any]) -> GenerationPreferenceValues {
    GenerationPreferenceValues(model: row["model"] as? String, effort: row["effort"] as? String,
      permissionMode: row["permissionMode"] as? String, speed: row["speed"] as? String)
  }
  let edits: [String: GenerationPreferenceEdit] = ["model": .model, "effort": .effort,
    "permission": .permission, "speed": .speed, "all": .all]
  for row in cases {
    let result = GenerationPreferenceRules.shared.remember(previous: values(row["previous"] as! [String: Any]),
      current: values(row["current"] as! [String: Any]), edit: edits[row["edit"] as! String]!)
    let expected = values(row["expected"] as! [String: Any])
    precondition(result.model == expected.model && result.effort == expected.effort &&
      result.permissionMode == expected.permissionMode && result.speed == expected.speed, row["name"] as! String)
  }
  print("Swift/Kotlin generation preferences passed: \(cases.count) cases")
}
