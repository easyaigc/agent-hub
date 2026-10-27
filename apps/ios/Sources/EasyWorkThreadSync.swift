import CryptoKit
import Foundation

enum EasyWorkSyncError: Error { case invalidDelta }

/// Session-local wire cache. Server versions are opaque; JSON key order is not rehashed in Swift.
struct EasyWorkThreadSync {
  struct Version: Codable, Equatable {
    let id: String
    let hash: String
    let textLength: Int?
    let textHash: String?
    let shapeHash: String?
  }
  private(set) var versions: [Version] = []
  private var messages: [String: [String: Any]] = [:]
  private var branchRevision: Int?

  func parameters(threadId: String) throws -> [String: Any] {
    var result: [String: Any] = ["threadId": threadId,
      "known": try JSONSerialization.jsonObject(with: JSONEncoder().encode(versions))]
    if let branchRevision { result["branchRevision"] = branchRevision }
    return result
  }

  func merging(_ result: [String: Any], threadId: String) throws -> (EasyWorkThreadSync, Data) {
    guard let snapshot = result["snapshot"] as? [String: Any],
      let thread = snapshot["thread"] as? [String: Any], thread["id"] as? String == threadId
    else { throw HostServiceError.wrongHost }
    guard let changed = result["messages"] as? [[String: Any]] else { throw EasyWorkSyncError.invalidDelta }
    // Older peers returning only complete messages remain compatible and never acquire delta versions.
    guard let rawHead = result["head"] else {
      guard (result["patches"] as? [Any] ?? []).isEmpty else { throw EasyWorkSyncError.invalidDelta }
      return (EasyWorkThreadSync(), try easyWorkJSON(result))
    }
    let head = try JSONDecoder().decode([Version].self, from: easyWorkJSON(rawHead))
    guard head.count <= 500, Set(head.map(\.id)).count == head.count,
      head.allSatisfy({ !$0.id.isEmpty && !$0.hash.isEmpty && $0.hash.count <= 128 }),
      let revision = result["branchRevision"] as? Int, revision >= 0,
      let reset = result["reset"] as? Bool,
      reset || branchRevision == nil || branchRevision == revision,
      let patches = result["patches"] as? [[String: Any]]
    else { throw EasyWorkSyncError.invalidDelta }
    let headIds = Set(head.map(\.id))
    let previousVersions = Dictionary(uniqueKeysWithValues: versions.map { ($0.id, $0) })
    var resolved = reset ? [:] : messages
    var changedIds = Set<String>()
    for message in changed {
      guard let id = message["id"] as? String, headIds.contains(id), changedIds.insert(id).inserted
      else { throw EasyWorkSyncError.invalidDelta }
      resolved[id] = message
    }
    for patch in patches {
      guard !reset, let id = patch["id"] as? String, headIds.contains(id),
        changedIds.insert(id).inserted, let base = patch["baseHash"] as? String,
        previousVersions[id]?.hash == base, let append = patch["append"] as? String,
        var message = resolved[id], var parts = message["content"] as? [[String: Any]],
        parts.count == 1, parts[0]["type"] as? String == "text", let text = parts[0]["text"] as? String
      else { throw EasyWorkSyncError.invalidDelta }
      parts[0]["text"] = text + append
      message["content"] = parts
      if let status = patch["status"] {
        guard let status = status as? String, ["complete", "partial", "interrupted", "failed"].contains(status)
        else { throw EasyWorkSyncError.invalidDelta }
        message["status"] = status
      }
      resolved[id] = message
    }
    var ordered: [[String: Any]] = []
    for version in head {
      guard let message = resolved[version.id],
        changedIds.contains(version.id) || previousVersions[version.id]?.hash == version.hash
      else { throw EasyWorkSyncError.invalidDelta }
      // Retained content was already verified before this cache was committed.
      // Recheck any changed message or metadata, even if its opaque hash is reused.
      let verifiedUnchanged = !reset && !changedIds.contains(version.id) && previousVersions[version.id] == version
      if let length = version.textLength, !verifiedUnchanged {
        guard let parts = message["content"] as? [[String: Any]], parts.count == 1,
          parts[0]["type"] as? String == "text", let text = parts[0]["text"] as? String,
          length == text.utf16.count,
          version.textHash == SHA256.hash(data: Data(text.utf8)).map({ String(format: "%02x", $0) }).joined()
        else { throw EasyWorkSyncError.invalidDelta }
      }
      ordered.append(message)
    }
    var merged = result
    merged["messages"] = ordered
    var next = EasyWorkThreadSync()
    next.versions = head
    next.branchRevision = revision
    next.messages = Dictionary(uniqueKeysWithValues: zip(head.map(\.id), ordered))
    return (next, try easyWorkJSON(merged))
  }
}
