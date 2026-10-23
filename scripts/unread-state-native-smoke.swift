import Foundation

@main struct ReadingSmoke {
  static func main() throws {
    let text = "中文回复🙂\nwith : and |"
    let hash = ReadPosition.contentVersion(messageId: "reply", text: text, status: "completed")
    let position = ReadPosition(revision: 1, messageId: "reply", contentVersion: hash)
    precondition(position.matches(messageId: "reply", text: text, status: "completed"))
    precondition(!position.matches(messageId: "reply", text: text + "new", status: "completed"))
    precondition(!position.matches(messageId: "reply", text: text, status: "inProgress"))
    let keys = [
      ReadingIdentity.key(agentId: "codex", authority: "local", subject: "a", hostId: "host", workspaceId: "space", runtimeId: "desktop", threadId: "thread"),
      ReadingIdentity.key(agentId: "easywork", authority: "local", subject: "a", hostId: "host", workspaceId: "space", runtimeId: "desktop", threadId: "thread"),
      ReadingIdentity.key(agentId: "codex", authority: "local", subject: "b", hostId: "host", workspaceId: "space", runtimeId: "desktop", threadId: "thread"),
      ReadingIdentity.key(agentId: "codex", authority: "other", subject: "a", hostId: "host", workspaceId: "space", runtimeId: "desktop", threadId: "thread"),
      ReadingIdentity.key(agentId: "codex", authority: "local", subject: "a", hostId: "other-host", workspaceId: "space", runtimeId: "desktop", threadId: "thread"),
      ReadingIdentity.key(agentId: "codex", authority: "local", subject: "a", hostId: "host", workspaceId: "other-space", runtimeId: "desktop", threadId: "thread"),
      ReadingIdentity.key(agentId: "codex", authority: "local", subject: "a", hostId: "host", workspaceId: "space", runtimeId: "managed", threadId: "thread"),
      ReadingIdentity.key(agentId: "codex", authority: "local", subject: "a", hostId: "host", workspaceId: "space", runtimeId: "desktop", threadId: "other-thread"),
      ReadingIdentity.key(agentId: "codex", authority: nil, subject: nil, hostId: "host", workspaceId: "space", runtimeId: "desktop", threadId: "thread"),
    ]
    precondition(Set(keys).count == keys.count)
    precondition(ReadingIdentity.readerId(deviceId: "phone-a", targetKey: keys[0]) != ReadingIdentity.readerId(deviceId: "phone-b", targetKey: keys[0]))
    let pending = MobileReadRecord(position: position, pending: true)
    let restored = try JSONDecoder().decode(MobileReadRecord.self, from: JSONEncoder().encode(pending))
    precondition(restored == pending)
    print("Native reading identity, UTF-8 content and pending persistence passed: " + hash)
  }
}
