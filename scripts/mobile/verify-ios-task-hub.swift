import Foundation
import MobileCore

func verifyTaskHubVectors(_ file: String) throws {
  let input = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: file))) as! [String: Any]
  let cursors = input["cursors"] as! [[String: Any]]
  for value in cursors {
    precondition(TaskHubRules.shared.validCursor(value: value["value"] as! String) == value["valid"] as! Bool)
  }
  let phases = input["phases"] as! [[String: Any]]
  for value in input["contracts"] as! [[String: Any]] {
    let data = try JSONSerialization.data(withJSONObject: value["value"]!)
    precondition((TaskHubContract.shared.validate(json: String(data: data, encoding: .utf8)!, definition: value["definition"] as! String) == nil) == value["valid"] as! Bool)
  }
  for value in phases {
    precondition(TaskHubRules.shared.acceptsPhase(current: value["current"] as! String, next: value["next"] as! String) == value["accept"] as! Bool)
  }
  precondition(TaskHubRules.shared.currentSession(expectedScope: "scope", scope: "scope", expectedGeneration: "one", generation: "one", actorKind: "human", needsLogin: false))
  precondition(!TaskHubRules.shared.currentSession(expectedScope: "scope", scope: "scope", expectedGeneration: "old", generation: "new", actorKind: "human", needsLogin: false))
  precondition(!TaskHubRules.shared.currentSession(expectedScope: "scope", scope: "scope", expectedGeneration: "one", generation: "one", actorKind: "agent", needsLogin: false))
  var framer = TaskHubSSEFramer()
  var events: [TaskHubSSEEvent] = []
  for byte in Data(":keepalive\r\nid: 42\r\ndata: 中文😀\r\ndata: tail\r\n\r\nid: 43\ndata: partial".utf8) {
    if let event = try framer.consume(byte) { events.append(event) }
  }
  precondition(events.count == 1 && events[0].cursor == "42" && String(data: events[0].data, encoding: .utf8) == "中文😀\ntail")
  for bytes in [Data([0xc3, 10]), Data("id: 9007199254740992\ndata: x\n\n".utf8), Data(("data:" + String(repeating: "x", count: 256*1024) + "\n\n").utf8)] {
    var broken = TaskHubSSEFramer(); var rejected = false
    do { for byte in bytes { _ = try broken.consume(byte) } } catch { rejected = true }
    precondition(rejected)
  }
  print("Swift/Kotlin Task Hub passed: \(cursors.count) cursors, \(phases.count) phases; actual iOS byte framer and session fences")
}
