import Foundation

/// Maps EasyWork requests to the same optional async question contract as Codex Host.
enum EasyWorkQuestionProjection {
  static func resolutionSucceeded(_ result: [String: Any], decision: String) -> Bool {
    let field = decision == "deny" || decision == "skip" ? "abandoned" : "accepted"
    return result[field] as? Bool == true
  }

  static func approvals(snapshot: [String: Any], threadId: String) throws -> [Approval] {
    let states = snapshot["questionStates"] as? [[String: Any]]
    var requests = (states ?? []).filter {
      $0["status"] as? String == "pending" && $0["async"] as? Bool == true
    }
    if let current = snapshot["pendingQuestion"] as? [String: Any],
       current["async"] as? Bool != true || states == nil {
      requests.insert(current, at: 0)
    }
    var identities = Set<String>()
    return try requests.map { request in
      guard request["threadId"] as? String == threadId,
        let run = request["runId"] as? String, !run.isEmpty,
        let session = request["sessionId"] as? String, !session.isEmpty,
        let call = request["toolCallId"] as? String, !call.isEmpty,
        let items = request["questions"] as? [[String: Any]], !items.isEmpty else {
        throw HostServiceError.invalidResponse
      }
      let asynchronous = request["async"] as? Bool == true
      let identity = try easyWorkJSON(["threadId": threadId, "runId": run,
        "sessionId": session, "toolCallId": call]).base64URL
      guard identities.insert(identity).inserted else { throw HostServiceError.invalidResponse }
      let questions = try items.map { item -> ApprovalQuestion in
        guard let header = item["header"] as? String, !header.isEmpty,
          let text = item["question"] as? String, !text.isEmpty,
          ["isSecret", "multiple", "custom"].allSatisfy({ item[$0] == nil || item[$0] is Bool }) else {
          throw HostServiceError.invalidResponse
        }
        let options = try (item["options"] as? [[String: Any]])?.map { option -> QuestionOption in
          guard let label = option["label"] as? String, !label.isEmpty,
            let description = option["description"] as? String else { throw HostServiceError.invalidResponse }
          return QuestionOption(label: label, description: description)
        }
        return ApprovalQuestion(id: header, question: text, options: options,
          isSecret: item["isSecret"] as? Bool, multiple: item["multiple"] as? Bool,
          custom: item["custom"] as? Bool)
      }
      guard Set(questions.map(\.id)).count == questions.count else { throw HostServiceError.invalidResponse }
      // Mutable answered/status metadata is not part of the original request identity.
      let captured: [String: Any] = ["threadId": threadId, "runId": run,
        "sessionId": session, "toolCallId": call, "questions": items, "async": asynchronous]
      return Approval(id: "question." + (try easyWorkJSON(captured)).base64URL,
        threadId: threadId, turnId: run, kind: "question", title: L10n.tr("需要你的回答"),
        detail: "", questions: questions, decisions: asynchronous ? [] : ["answer", "deny"],
        notificationRequestId: call, interactionMode: asynchronous ? "async" : nil)
    }
  }

  static func resolution(approval: Approval, decision: String, answers: [String: [String]]) throws -> [String: Any] {
    guard approval.id.hasPrefix("question."),
      let data = Data(base64URL: String(approval.id.dropFirst(9))) else { throw HostServiceError.invalidResponse }
    let question = try easyWorkObject(data)
    guard question["threadId"] as? String == approval.threadId,
      question["runId"] as? String == approval.turnId,
      let call = question["toolCallId"] as? String, !call.isEmpty,
      let session = question["sessionId"] as? String, !session.isEmpty,
      ["", "answer", "deny"].contains(decision) || decision == "skip" && approval.interactionMode == "async" else {
      throw HostServiceError.invalidResponse
    }
    let abandon = decision == "deny" || decision == "skip"
    var params: [String: Any] = ["threadId": approval.threadId, "kind": "question",
      "action": abandon ? "abandon" : "answer", "serverRequestId": call,
      "sessionId": session, "runId": approval.turnId]
    if !abandon {
      guard Set(answers.keys) == Set(approval.questions.map(\.id)) else { throw HostServiceError.invalidResponse }
      params["answer"] = ["questions": try approval.questions.map { item -> [String: Any] in
        let values = (answers[item.id] ?? []).filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let labels = Set((item.options ?? []).map(\.label))
        let selected = values.filter { labels.contains($0) }
        let custom = values.filter { !labels.contains($0) }.joined(separator: "\n")
        guard !values.isEmpty, item.multiple == true || selected.count <= 1,
          custom.isEmpty || item.custom != false else {
          throw HostServiceError.message(L10n.tr("回答不符合此问题的选项规则"))
        }
        var answer: [String: Any] = ["header": item.id]
        if !selected.isEmpty { answer["selectedOptions"] = selected }
        if !custom.isEmpty { answer["customAnswer"] = custom }
        return answer
      }]
    }
    return params
  }
}
