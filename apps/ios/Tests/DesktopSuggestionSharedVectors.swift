import CryptoKit
import Foundation

@main
struct DesktopSuggestionSharedVectors {
  static func main() throws {
    let root = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))) as! [String: Any]
    precondition(root["schemaVersion"] as? Int == 1)
    let base = root["value"] as! [String: Any], baseTarget = root["target"] as! [String: Any]
    func merge(_ base: [String: Any], _ patch: Any?) -> [String: Any] {
      base.merging(patch as? [String: Any] ?? [:]) { _, new in new }
    }
    func value(_ v: [String: Any]) -> DesktopSuggestion {
      DesktopSuggestion(id: v["id"] as! String, text: v["text"] as! String,
        contentSha256: v["contentSha256"] as! String, expiresAt: v["expiresAt"] as! String,
        validity: v["validity"] as? String)
    }
    func target(_ t: [String: Any]) -> AgentRuntimeTarget {
      let account = (t["accountAuthority"] as? String).flatMap { a in (t["accountSubject"] as? String).map { AgentAccount(authority: a, subject: $0) } }
      return AgentRuntimeTarget(agentId: t["agentId"] as! String, hostId: t["hostId"] as! String,
        workspaceId: t["workspaceId"] as! String, runtimeId: t["runtimeId"] as! String, account: account)
    }
    func scope(_ t: AgentRuntimeTarget, _ p: Any? = nil) -> DesktopSuggestionScope {
      let p = p as? [String: Any] ?? [:]
      return DesktopSuggestionScope(target: t, threadId: p["threadId"] as? String ?? "thread",
        sourceTurnId: p["sourceTurnId"] as? String ?? "turn", navigation: p["navigation"] as? String ?? "navigation",
        connection: p["connection"] as? String ?? "connection")
    }
    let rows = root["cases"] as! [[String: Any]]
    precondition(rows.count == 56)
    for row in rows {
      let v = merge(base, row["value"]), t = merge(baseTarget, row["target"]), s = scope(target(merge(baseTarget, row["target"])))
      let now = Date(timeIntervalSince1970: (row["nowMillis"] as? Double ?? root["nowMillis"] as! Double) / 1000)
      let actual: Any?
      switch row["kind"] as! String {
      case "valid": actual = value(v).isValid(at: now)
      case "version":
        let a = merge(base, row["base"])
        actual = value(a) == value(merge(a, row["other"]))
      case "matches":
        actual = DesktopSuggestionResponse(schemaVersion: row["schemaVersion"] as? Int ?? 1,
          target: target(merge(t, row["responseTarget"])), threadId: row["threadId"] as? String ?? "thread",
          sourceTurnId: row["sourceTurnId"] as? String ?? "turn", readOnly: row["readOnly"] as? Bool ?? true,
          suggestion: value(v), source: row.keys.contains("source") ? row["source"] as? String : "native-trace").matches(s)
      case "proposal":
        let draft = DesktopSuggestionDraftSnapshot(targetKey: "draft", text: row["text"] as! String, revision: 1)
        let current = DesktopSuggestionDraftSnapshot(targetKey: row["currentTargetKey"] as? String ?? "draft",
          text: row["currentText"] as? String ?? draft.text, revision: UInt64(row["currentRevision"] as? String ?? "1")!)
        let action = DesktopSuggestionDraftAction(scope: s, suggestion: value(base), draft: draft)
        let mode: DesktopSuggestionAdoptionMode?
        switch row["mode"] as! String { case "fill": mode = .fill; case "append": mode = .append; case "replace": mode = .replace; default: mode = nil }
        actual = mode.flatMap { action.proposedText(mode: $0, currentScope: scope(target(t), row["currentScope"]),
          currentSuggestion: value(merge(base, row["currentSuggestion"])), currentDraft: current, at: now) }
      case "undo":
        let p = row["current"] as? [String: Any] ?? [:]
        let undo = DesktopSuggestionDraftUndo(scope: s, suggestionId: "v1", originalText: "original",
          applied: DesktopSuggestionDraftSnapshot(targetKey: "draft", text: "applied", revision: 1))
        actual = undo.restoredText(currentDraft: DesktopSuggestionDraftSnapshot(targetKey: p["targetKey"] as? String ?? "draft",
          text: p["text"] as? String ?? "applied", revision: UInt64(p["revision"] as? String ?? "1")!))
      default: fatalError("Unknown vector kind")
      }
      let output = try JSONSerialization.data(withJSONObject: [actual ?? NSNull()], options: [.sortedKeys])
      let expected = try JSONSerialization.data(withJSONObject: [row["expected"]!], options: [.sortedKeys])
      precondition(output == expected, "Shared vector failed: \(row["name"]!)")
    }
    // Native CryptoKit/UTF-8 adapter and common policy both participate here.
    func bounded(_ id: String, _ text: String) -> DesktopSuggestion {
      let digest = SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
      return DesktopSuggestion(id: id, text: text, contentSha256: digest, expiresAt: "1970-01-01T00:00:02Z", validity: "native-version")
    }
    let now = Date(timeIntervalSince1970: 1)
    precondition(bounded(String(repeating: "é", count: 128), "x").isValid(at: now))
    precondition(!bounded(String(repeating: "é", count: 129), "x").isValid(at: now))
    precondition(bounded("id", String(repeating: "😀", count: 16000)).isValid(at: now))
    precondition(!bounded("id", String(repeating: "😀", count: 16001)).isValid(at: now))
    precondition(!DesktopSuggestionResponse(schemaVersion: Int.max, target: target(baseTarget), threadId: "thread",
      sourceTurnId: "turn", readOnly: true, suggestion: nil).matches(scope(target(baseTarget))))
    let handled = bounded("same-id", "old").handledVersionKey(targetKey: "draft", sourceTurnId: "source")
    precondition(handled != bounded("same-id", "new").handledVersionKey(targetKey: "draft", sourceTurnId: "source"))
    precondition(handled != bounded("same-id", "old").handledVersionKey(targetKey: "other", sourceTurnId: "source"))
    precondition(handled != bounded("same-id", "old").handledVersionKey(targetKey: "draft", sourceTurnId: "replacement"))
    let renewed = DesktopSuggestion(id: "same-id", text: "old", contentSha256: bounded("same-id", "old").contentSha256,
      expiresAt: "1970-01-01T00:00:03Z", validity: "native-version")
    precondition(handled == renewed.handledVersionKey(targetKey: "draft", sourceTurnId: "source"))
    print("Shared suggestion vectors passed: 56 policy cases,5 native UTF-8/hash/schema and4 handled-version boundaries; no transport or model")
  }
}
