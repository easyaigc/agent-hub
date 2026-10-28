import Foundation

// Read-only host comparison of the numeric guard currently present in the iOS source.
// No app, device, encryption, transport, identity or key storage is initialized.
precondition(CommandLine.arguments.count == 4)
let vectors = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [String: Any]
let source = try String(contentsOfFile: CommandLine.arguments[3], encoding: .utf8)
precondition(source.contains("guard envelope[\"version\"] as? Int == 1"))
precondition(source.contains("let sequence = envelope[\"sequence\"] as? Int, sequence > 0"))
func currentSourceNumericGuard(_ envelope: [String: Any]) -> Bool {
  guard envelope["version"] as? Int == 1,
        let sequence = envelope["sequence"] as? Int, sequence > 0 else { return false }
  return true
}
var rows: [[String: Any]] = []
for item in vectors["cases"] as! [[String: Any]] {
  let field = item["field"] as! String
  let token = item["token"] as! String
  let sequence = field == "sequence" ? token : "42"
  let version = field == "version" ? token : "1"
  let raw = "{\"version\":\(version),\"sequence\":\(sequence)}"
  let expected = item["accepted"] as! Bool
  var accepted = false
  var row: [String: Any] = ["id": item["id"]!, "expectedStrictPolicy": expected]
  do {
    let envelope = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as! [String: Any]
    accepted = currentSourceNumericGuard(envelope)
    row["parse"] = "accepted"
    row["sequenceAsInt"] = (envelope["sequence"] as? Int).map(String.init) ?? "nil"
    row["versionAsInt"] = (envelope["version"] as? Int).map(String.init) ?? "nil"
    if let number = envelope[field] as? NSNumber { row["numberObjCType"] = String(cString: number.objCType) }
  } catch {
    row["parse"] = "rejected"
  }
  row["currentSourceGuardAccepted"] = accepted
  row["matchesStrictPolicy"] = accepted == expected
  rows.append(row)
}
for name in ["minimum", "maximum"] {
  let frames = vectors["frames"] as! [String: Any]
  let envelope = frames[name] as! [String: Any]
  let accepted = currentSourceNumericGuard(envelope)
  rows.append(["id": "boundary-" + name, "expectedStrictPolicy": true,
               "currentSourceGuardAccepted": accepted, "matchesStrictPolicy": accepted,
               "parse": "independent Node frame numeric metadata only"])
}
let differences = rows.filter { !($0["matchesStrictPolicy"] as! Bool) }
let report: [String: Any] = [
  "status": "read-only-host-Foundation-comparison-completed",
  "nativeIOSExecuted": false, "fullEnvelopeGCMExecuted": false,
  "source": "apps/ios/Sources/EasyWorkTransport.swift:EasyWorkEnvelopeCrypto.open numeric guard",
  "numericCases": rows.count, "differences": differences, "cases": rows,
  "meaning": "Differences are numeric pre-authentication guard observations on macOS Foundation; no iOS product change or full-envelope acceptance claim"
]
try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]).write(to: URL(fileURLWithPath: CommandLine.arguments[2]), options: .atomic)
print("Foundation numeric review: \(rows.count) cases, \(differences.count) numeric-guard differences; no iOS/device/GCM execution")
