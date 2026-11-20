import Foundation

/// Read-only projection of the desktop's committed compression watermark.
/// Missing or unsupported optional metadata leaves legacy history readable.
enum EasyWorkCompressionProjection {
  static func applying(_ snapshot: [String: Any], threadId: String, branchRevision: Int,
    to messages: [ChatMessage]) -> [ChatMessage] {
    guard let state = snapshot["contextCompressionState"] as? [String: Any],
      let thread = snapshot["thread"] as? [String: Any], thread["id"] as? String == threadId,
      let active = snapshot["activeRun"] as? [String: Any], active["threadId"] as? String == threadId,
      let runId = nonempty(active["runId"]), state["threadId"] as? String == threadId,
      state["runId"] as? String == runId,
      (try? EasyWorkReading.integer(thread["branchRevision"])) == branchRevision,
      (try? EasyWorkReading.integer(active["branchRevision"])) == branchRevision,
      (try? EasyWorkReading.integer(state["branchRevision"])) == branchRevision,
      let cursor = state["cursor"] as? [String: Any],
      ["protocol", "runtime"].contains(cursor["source"] as? String ?? ""),
      (try? EasyWorkReading.integer(cursor["sequence"])) != nil,
      let pending = state["pending"] as? [[String: Any]]
    else { return messages }

    var entries: [Entry] = []
    for value in pending {
      guard let entry = entry(value, threadId: threadId, runId: runId),
        entry.event["type"] as? String == "context_compression_started"
      else { return messages }
      entries.append(entry)
    }
    guard Set(entries.map(\.id)).count == entries.count else { return messages }
    var latest: Entry?
    if let value = state["latestEvent"] {
      guard let value = value as? [String: Any],
        let decoded = entry(value, threadId: threadId, runId: runId)
      else { return messages }
      latest = decoded
    }
    // An empty pending list is authoritative even when the terminal run
    // watermark retained an earlier "started" event.
    if let latest, latest.event["type"] as? String != "context_compression_started" {
      guard !entries.contains(where: { $0.id == latest.id }) else { return messages }
      entries.append(latest)
    }
    var result = messages.filter {
      !($0.kind == "contextCompaction" && $0.runId == runId && $0.status == "inProgress")
    }
    for entry in entries {
      let status = status(entry.event)
      let message = ChatMessage(id: entry.id, role: "tool", text: L10n.tr("上下文压缩"),
        kind: "contextCompaction", status: status,
        detail: String(decoding: entry.detail, as: UTF8.self), runId: runId)
      if let index = result.firstIndex(where: { $0.id == message.id }) { result[index] = message }
      else { result.append(message) }
    }
    return result
  }

  private struct Entry {
    let id: String
    let event: [String: Any]
    let detail: Data
  }

  private static func nonempty(_ value: Any?) -> String? {
    guard let value = value as? String, !value.isEmpty else { return nil }
    return value
  }

  private static func entry(_ value: [String: Any], threadId: String, runId: String) -> Entry? {
    guard let createdAt = nonempty(value["createdAt"]), let event = value["event"] as? [String: Any],
      let type = event["type"] as? String,
      ["context_compression_started", "context_compression_block_created",
        "context_compression_block_rejected"].contains(type)
    else { return nil }
    // Do not reinterpret malformed identities as an uncorrelated legacy event.
    for key in ["compressionOperationId", "generationId"] {
      if event[key] != nil, nonempty(event[key]) == nil { return nil }
    }
    let identity: [String]
    if let operation = nonempty(event["compressionOperationId"]) { identity = ["operation", operation] }
    else if let generation = nonempty(event["generationId"]) { identity = ["generation", generation] }
    else { identity = ["legacy", nonempty(event["traceId"]) ?? "", createdAt, type] }
    guard let key = try? easyWorkJSON([threadId, runId] + identity),
      let detail = try? easyWorkJSON(event) else { return nil }
    return Entry(id: "easywork-compaction." + key.base64URL, event: event, detail: detail)
  }

  private static func status(_ event: [String: Any]) -> String {
    switch event["type"] as? String {
    case "context_compression_started": return "inProgress"
    case "context_compression_block_rejected":
      if let value = event["status"], nonempty(value) == nil { return "unknown" }
      switch event["status"] as? String {
      case "cancelled": return "cancelled"
      case "skipped": return "skipped"
      case nil, "failed": return "failed"
      default: return "unknown"
      }
    case "context_compression_block_created":
      for key in ["logicalStatus", "persistenceState", "resultStatus", "generationStatus"] {
        if let value = event[key], nonempty(value) == nil { return "unknown" }
      }
      let logical = event["logicalStatus"] as? String
      let persistence = event["persistenceState"] as? String
      if logical == "failed" || persistence == "degraded" || event["resultStatus"] as? String == "model_failed" { return "failed" }
      if logical == "skipped" || event["resultStatus"] as? String == "skipped" { return "skipped" }
      guard nonempty(event["blockRef"]) != nil,
        logical == nil || ["installed", "reused"].contains(logical!),
        persistence == nil || persistence == "durable" else { return "unknown" }
      // A failed semantic generation may still install a valid structural
      // fallback. The installed checkpoint, not model success, determines this.
      return "completed"
    default: return "unknown"
    }
  }
}
