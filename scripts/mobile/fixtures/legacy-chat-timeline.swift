// Frozen pre-shared-core timeline algorithm for independent migration comparisons.
// Kept as source so public clean checkouts do not depend on private Git history.
struct ChatTimeline {
  enum Item: Identifiable {
    case message(ChatMessage)
    case tools(id: String, messages: [ChatMessage])
    case compaction(ChatMessage)
    case errors(id: String, messages: [ChatMessage])
    case replyActions(id: String, text: String)

    var id: String {
      switch self {
      case .message(let message): return "message-\(message.id)"
      case .tools(let id, _): return "tools-\(id)"
      case .compaction(let message): return "compaction-\(message.id)"
      case .errors(let id, _): return "errors-\(id)"
      case .replyActions(let id, _): return "reply-actions-\(id)"
      }
    }
  }

  let items: [Item]
  let hasEarlierMessages: Bool

  init(messages: [ChatMessage], messageLimit: Int, activeRunId: String? = nil, isRunning: Bool = false) {
    // New adapters supply the actual run identity, including same-run steering.
    // Older hosts/caches fall back to the reply sequence following each user input.
    var groups: [String: [Int]] = [:]
    var key = "legacy-start"
    for (index, message) in messages.enumerated() {
      if let runId = message.runId, !runId.isEmpty { key = "run-\(runId)" }
      else if message.role == "user" { key = "legacy-\(message.id)" }
      groups[key, default: []].append(index)
    }
    let latestKey = key
    var replies: [Int: (id: String, text: String)] = [:]
    for (groupKey, indices) in groups {
      let isActive = groupKey == activeRunId.map { "run-\($0)" }
        || (groupKey == latestKey && (activeRunId == nil || groupKey.hasPrefix("legacy-")))
      guard !(isRunning && isActive),
        !indices.contains(where: { ["inProgress", "running", "streaming"].contains(messages[$0].status ?? "") })
      else { continue }
      let candidates = indices.map { messages[$0] }.filter {
        $0.role == "assistant" && ($0.kind == nil || $0.kind == "agentMessage")
          && $0.replyPhase != "commentary" && !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
      }
      guard let last = candidates.last, let end = indices.last else { continue }
      var finalParts = candidates.filter { $0.replyPhase == "final_answer" }
      if finalParts.isEmpty, let sourceId = last.replyMessageId {
        finalParts = candidates.filter { $0.replyMessageId == sourceId }
      }
      replies[end] = ((finalParts.last ?? last).id, finalParts.isEmpty ? last.text : finalParts.map(\.text).joined(separator: "\n\n"))
    }
    var start = max(0, messages.count - max(1, messageLimit))
    // A display window must not split a continuous tool group.
    while start > 0, messages[start].isGroupedTool, messages[start - 1].isGroupedTool {
      start -= 1
    }
    hasEarlierMessages = start > 0
    var rows: [Item] = []
    var tools: [ChatMessage] = []
    var errors: [ChatMessage] = []
    for (index, message) in messages.enumerated().dropFirst(start) {
      if message.kind != "error", let first = errors.first {
        rows.append(.errors(id: first.id, messages: errors)); errors.removeAll(keepingCapacity: true)
      }
      if message.isGroupedTool {
        tools.append(message)
      } else {
        if let first = tools.first {
          rows.append(.tools(id: first.id, messages: tools))
          tools.removeAll(keepingCapacity: true)
        }
        if message.kind == "error" { errors.append(message) }
        else { rows.append(message.kind == "contextCompaction" ? .compaction(message) : .message(message)) }
      }
      if let reply = replies[index] {
        if let first = tools.first {
          rows.append(.tools(id: first.id, messages: tools)); tools.removeAll(keepingCapacity: true)
        }
        if let first = errors.first {
          rows.append(.errors(id: first.id, messages: errors)); errors.removeAll(keepingCapacity: true)
        }
        rows.append(.replyActions(id: reply.id, text: reply.text))
      }
    }
    if let first = tools.first { rows.append(.tools(id: first.id, messages: tools)) }
    if let first = errors.first { rows.append(.errors(id: first.id, messages: errors)) }
    items = rows
  }
}
