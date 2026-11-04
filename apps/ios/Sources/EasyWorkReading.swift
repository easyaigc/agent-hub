import Foundation
import MobileCore
import CryptoKit
import CoreFoundation

/// Strict provider boundary. Local aliases are applied only after authenticating the wire target.
enum EasyWorkReading {
  struct Summary { var counter: Int; var state: ThreadReadState? }
  static func integer(_ value: Any?) throws -> Int {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
      number.doubleValue >= 0, number.doubleValue <= 9_007_199_254_740_991,
      number.doubleValue.rounded(.down) == number.doubleValue else { throw HostServiceError.invalidResponse }
    return number.intValue
  }
  static func position(_ value: Any?) throws -> ReadPosition {
    guard let value = value as? [String: Any], Set(value.keys) == Set(["revision", "messageId", "contentVersion"]),
      let id = value["messageId"] as? String, !id.isEmpty, id.utf16.count <= 512,
      let digest = value["contentVersion"] as? String, digest.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw HostServiceError.invalidResponse }
    let revision = try integer(value["revision"])
    guard revision > 0 else { throw HostServiceError.invalidResponse }
    return ReadPosition(revision: revision, messageId: id, contentVersion: digest)
  }
  static func decode(_ value: [String: Any], client: Client, target: EasyWorkTarget,
    threadId: String, readerId: String, expected: ReadPosition? = nil) throws -> Summary {
    var fields: Set<String> = ["target", "hostId", "threadId", "readerId", "readRevision", "readState"]
    if expected != nil { fields.insert("read") }
    guard Set(value.keys) == fields, let machine = client.agentConnection?.target,
      machine.agentId == "easywork", machine.hostId == target.desktopDeviceId,
      machine.workspaceId == target.workspaceId, machine.runtimeId == target.runtimeId, machine.account == target.account,
      try easyWorkJSON(value["target"] ?? [:]) == easyWorkJSON(target.dictionary),
      value["hostId"] as? String == target.desktopDeviceId, value["threadId"] as? String == threadId,
      value["readerId"] as? String == readerId,
      readerId.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw HostServiceError.wrongHost }
    let counter = try integer(value["readRevision"])
    var state: ThreadReadState?
    if !(value["readState"] is NSNull) {
      guard let raw = value["readState"] as? [String: Any], Set(raw.keys).isSubset(of: ["version", "latest", "readerId", "read"]),
        try integer(raw["version"]) == 1, raw["readerId"] as? String == readerId else { throw HostServiceError.invalidResponse }
      let latest = try position(raw["latest"])
      let read = raw["read"] == nil || raw["read"] is NSNull ? nil : try position(raw["read"])
      guard latest.revision == counter, read == nil || read!.revision < counter || read == latest else { throw HostServiceError.invalidResponse }
      state = ThreadReadState(version: 1, latest: latest, readerId: readerId, read: read)
    }
    if let expected {
      guard try position(value["read"]) == expected, state?.read == expected else { throw HostServiceError.invalidResponse }
    }
    return Summary(counter: counter, state: state)
  }
  static func read(_ channel: EasyWorkRemoteSession, client: Client, threadId: String) async throws -> Summary {
    guard let reader = client.readingScopeId else { throw HostServiceError.invalidResponse }
    let value = try easyWorkObject(await channel.request("thread/read-state", ["threadId": threadId, "readerId": reader]))
    return try decode(value, client: client, target: channel.target, threadId: threadId, readerId: reader)
  }
  static func decodeList(_ value: [String: Any], client: Client, target: EasyWorkTarget,
    threadIds: [String], readerId: String) throws -> [Summary] {
    guard Set(value.keys) == Set(["target", "hostId", "readerId", "items"]), !threadIds.isEmpty,
      threadIds.count <= 100, Set(threadIds).count == threadIds.count,
      let items = value["items"] as? [[String: Any]], items.count == threadIds.count,
      items.compactMap({ $0["threadId"] as? String }) == threadIds else { throw HostServiceError.invalidResponse }
    return try items.enumerated().map { index, item in
      guard Set(item.keys) == Set(["threadId", "readRevision", "readState"]) else { throw HostServiceError.invalidResponse }
      var response = value; response.removeValue(forKey: "items")
      response.merge(item) { _, new in new }
      return try decode(response, client: client, target: target, threadId: threadIds[index], readerId: readerId)
    }
  }
  static func readList(_ channel: EasyWorkRemoteSession, client: Client, threadIds: [String]) async throws -> [Summary] {
    if threadIds.isEmpty { return [] }
    guard let reader = client.readingScopeId else { throw HostServiceError.invalidResponse }
    let value = try easyWorkObject(await channel.request("thread/read-state-list", ["threadIds": threadIds, "readerId": reader]))
    return try decodeList(value, client: client, target: channel.target, threadIds: threadIds, readerId: reader)
  }
  static func acknowledge(_ channel: EasyWorkRemoteSession, client: Client, threadId: String,
    readerId: String, position: ReadPosition) async throws -> ReadAcknowledgement {
    guard client.readingScopeId == readerId, let target = client.readingTarget,
      target == client.agentConnection?.target else { throw HostServiceError.wrongHost }
    let current = try await read(channel, client: client, threadId: threadId)
    if current.state?.read != position {
      guard current.state?.latest == position else { throw HostServiceError.rejected(409, "stale_read_position") }
      let params: [String: Any] = ["threadId": threadId, "readerId": readerId,
        "position": ["revision": position.revision, "messageId": position.messageId, "contentVersion": position.contentVersion]]
      let intent = try easyWorkJSON(["target": channel.target.dictionary, "params": params])
      let operation = "read-" + SHA256.hash(data: intent).map { String(format: "%02x", $0) }.joined()
      do {
        let result = try easyWorkObject(await channel.request("thread/acknowledge-read", params, id: operation))
        _ = try decode(result, client: client, target: channel.target, threadId: threadId, readerId: readerId, expected: position)
      } catch HostServiceError.rejected(let status, let message) where status == 409 {
        throw HostServiceError.rejected(status, message)
      } catch {
        let original = error
        try Task.checkCancellation()
        // A lost receipt is reconciled by a read; the original write is never republished here.
        let observed: Summary
        do { observed = try await read(channel, client: client, threadId: threadId) } catch { throw original }
        guard observed.state?.read == position else { throw original }
      }
    }
    return ReadAcknowledgement(target: target, hostId: client.id, threadId: threadId, readerId: readerId, read: position)
  }
}

actor EasyWorkReadingCapabilities {
  static let shared = EasyWorkReadingCapabilities()
  private var entries: [String: Bool] = [:]
  private var order: [String] = []
  private func key(_ channel: EasyWorkRemoteSession) async throws -> String {
    let data = try easyWorkJSON(["target": channel.target.dictionary, "device": await channel.mobileDeviceId, "epoch": await channel.epoch])
    return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
  }
  func remember(_ channel: EasyWorkRemoteSession, capabilities: [String]) async throws {
    let id = try await key(channel)
    if entries[id] == nil { order.append(id) }
    entries[id] = capabilities.contains("threads:read-state")
    if order.count > 32 { entries.removeValue(forKey: order.removeFirst()) }
  }
  func supports(_ channel: EasyWorkRemoteSession) async throws -> Bool {
    if let value = entries[try await key(channel)] { return value }
    let context = try easyWorkObject(await channel.request("runtime/context", [:]))
    guard try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(context["target"] ?? [:])) == channel.target else { throw HostServiceError.wrongHost }
    let capabilities = context["capabilities"] as? [String] ?? []
    try await remember(channel, capabilities: capabilities)
    return capabilities.contains("threads:read-state")
  }
}

extension EasyWorkAgentClient {
  func acknowledgeRead(_ client: Client, threadId: String, readerId: String, position: ReadPosition) async throws -> ReadAcknowledgement {
    let channel = try await EasyWorkSessions.shared.session(client)
    return try await EasyWorkReading.acknowledge(channel, client: client, threadId: threadId, readerId: readerId, position: position)
  }
}

// The reading adapter owns its platform DTO bridge; identity-only consumers remain narrow.
extension SharedMobileRules {
  static func mergeDeviceReading(_ old: MobileReadingAuthority.Value, counter: Int, latest: ReadPosition?, read: ReadPosition?) throws -> MobileReadingAuthority.Value {
    func shared(_ value: ReadPosition?) -> DeviceReadingVersion? {
      value.map { DeviceReadingVersion(revision: Int64($0.revision), messageId: $0.messageId, contentVersion: $0.contentVersion) }
    }
    func native(_ value: DeviceReadingVersion?) -> ReadPosition? {
      value.map { ReadPosition(revision: Int($0.revision), messageId: $0.messageId, contentVersion: $0.contentVersion) }
    }
    let previous = DeviceReadingSnapshot(authoritative: old.counter != nil, counter: Int64(old.counter ?? 0),
      latest: shared(old.latest), read: shared(old.read), unread: old.unread, pending: old.pending)
    let result = DeviceReadingRules.shared.mergeAuthority(old: previous, counter: Int64(counter), latest: shared(latest), remoteRead: shared(read))
    guard result.error == nil else { throw HostServiceError.invalidResponse }
    let value = result.snapshot
    return .init(counter: Int(value.counter), latest: native(value.latest), read: native(value.read), unread: value.unread, pending: value.pending)
  }
}
