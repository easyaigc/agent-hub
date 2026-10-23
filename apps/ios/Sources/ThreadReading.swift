import CryptoKit
import Foundation

struct ReadPosition: Codable, Equatable, Sendable {
  var revision: Int
  var messageId: String
  var contentVersion: String

  func matches(messageId: String, text: String, status: String?) -> Bool {
    self.messageId == messageId && contentVersion == Self.contentVersion(messageId: messageId, text: text, status: status ?? "completed")
  }
  static func contentVersion(messageId: String, text: String, status: String) -> String {
    var hash = SHA256()
    for value in [messageId, text, status] {
      hash.update(data: Data((String(value.utf8.count) + ":" + value).utf8))
    }
    return hash.finalize().map { String(format: "%02x", $0) }.joined()
  }
}

struct ThreadReadState: Codable, Equatable, Sendable {
  var version: Int
  var latest: ReadPosition
  var readerId: String?
  var read: ReadPosition?
}
struct MobileReadRecord: Codable, Equatable, Sendable {
  var position: ReadPosition
  var pending: Bool
}
struct MobileReadBaseline: Codable, Equatable, Sendable {
  var position: ReadPosition
  var unread: Bool
}

enum ReadingIdentity {
  // A typed tuple, independent of LAN/relay addresses and project display grouping.
  static func key(agentId: String, authority: String?, subject: String?, hostId: String,
    workspaceId: String, runtimeId: String, threadId: String?) -> String {
    if agentId == "codex" {
      return CodexMachineStorage.readingKey(AgentRuntimeTarget(agentId: agentId, hostId: hostId, workspaceId: workspaceId, runtimeId: runtimeId), threadId: threadId)
    }
    return legacyKey(agentId: agentId, authority: authority, subject: subject, hostId: hostId, workspaceId: workspaceId, runtimeId: runtimeId, threadId: threadId)
  }
  static func legacyKey(agentId: String, authority: String?, subject: String?, hostId: String,
    workspaceId: String, runtimeId: String, threadId: String?) -> String {
    let values: [Any] = ["mobile-reading-v1", agentId, authority as Any? ?? NSNull(),
      subject as Any? ?? NSNull(), hostId, workspaceId, runtimeId, threadId as Any? ?? NSNull()]
    let data = try! JSONSerialization.data(withJSONObject: values)
    return data.base64EncodedString()
  }
  static func readerId(deviceId: String, targetKey: String) -> String {
    SHA256.hash(data: Data((deviceId + ":" + targetKey).utf8)).map { String(format: "%02x", $0) }.joined()
  }
}
