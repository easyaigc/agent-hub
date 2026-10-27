import Foundation
import MobileCore

func verifyMessageLinkVectors(_ path: String) throws {
  let root = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
  precondition(root["schemaVersion"] as? Int == 1)
  let cases = root["cases"] as! [[String: Any]]
  for row in cases {
    guard let url = URL(string: row["reference"] as! String) else {
      precondition(row["expected"] as! String == "unsupported", row["name"] as! String); continue
    }
    let result = MessageLinkDestination.resolve(url)
    let kind: String
    switch result {
    case .attachment(let id):
      kind = "attachment"; precondition(id == row["attachmentId"] as! String, row["name"] as! String)
    case .web: kind = "web"
    case .external: kind = "external"
    case .computerFile: kind = "computerFile"
    case .computerWeb: kind = "computerWeb"
    case .unsupported: kind = "unsupported"
    }
    precondition(kind == row["expected"] as! String, row["name"] as! String)
  }
  print("Message link vectors: \(cases.count)")
}
