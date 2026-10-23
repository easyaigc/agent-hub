import CryptoKit
import Foundation

struct BusinessNoticeTarget: Codable, Equatable, Sendable {
  var deploymentId: String; var serverInstanceId: String; var primaryHostId: String; var actorId: String
}
enum BusinessNoticeKind: String, Codable, CaseIterable, Sendable {
  case response_required, clarification_required, acceptance_required, acceptance_failed, completed, blocked, operation_uncertain
  var title: String {
    switch self {
    case .response_required: return L10n.tr("任务待响应")
    case .clarification_required: return L10n.tr("任务待澄清")
    case .acceptance_required: return L10n.tr("任务待验收")
    case .acceptance_failed: return L10n.tr("验收失败，等待返修")
    case .completed: return L10n.tr("业务任务已完成")
    case .blocked: return L10n.tr("任务受阻")
    case .operation_uncertain: return L10n.tr("提交结果待核对")
    }
  }
}
struct BusinessNotice: Codable, Equatable, Sendable {
  var version: Int; var domain: String; var eventId: String
  var deploymentId: String; var serverInstanceId: String; var primaryHostId: String; var actorId: String
  var projectId: String; var taskId: String; var roundId: String; var revision: Int
  var kind: BusinessNoticeKind; var occurredAt: String; var title: String?; var summary: String?
  var target: BusinessNoticeTarget { BusinessNoticeTarget(deploymentId: deploymentId, serverInstanceId: serverInstanceId, primaryHostId: primaryHostId, actorId: actorId) }
  func validate() throws {
    guard version == 1, domain == "taskhub.business", BusinessNotificationShared.targetValid(target),
      [projectId, taskId, roundId].allSatisfy(BusinessNotificationShared.uuid), revision > 0, revision <= 9_007_199_254_740_991,
      TaskNotificationValidation.eventId(eventId), Self.date(occurredAt) != nil,
      [title, summary].compactMap({ $0 }).allSatisfy({ $0.utf16.count <= 160 && !$0.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) }) else { throw BusinessNotificationError.invalidPayload }
  }
  static func date(_ value: String) -> Date? {
    let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
  }
}
struct BusinessNotificationPreferences: Codable, Equatable, Sendable {
  var enabled = false; var generation = 1; var kinds = BusinessNoticeKind.allCases
  var showPreview = false; var sound = "default"; var foregroundBanner = true
  func validate() throws {
    guard generation > 0, generation <= 9_007_199_254_740_991, Set(kinds).count == kinds.count, TaskNotificationValidation.sound(sound) else { throw BusinessNotificationError.invalidPayload }
  }
}
struct BusinessNotificationBinding: Codable, Sendable {
  var target: BusinessNoticeTarget; var projectId: String; var bindingId: String; var publicKey: String
  var preferences: BusinessNotificationPreferences; var relayURL: String; var managementToken: String
}
struct BusinessNotificationStatus: Codable, Sendable {
  struct Delivery: Codable, Sendable { var eventId: String; var state: String; var occurredAt: String }
  var lastDelivery: Delivery? = nil
  var version: Int; var domain: String; var target: BusinessNoticeTarget; var projectId: String
  var delivery: String; var state: String; var publisherAvailable: Bool; var reason: String?
  var publicKey: String?; var bindingId: String?; var relayURL: String?; var managementToken: String?
}
enum BusinessNotificationError: LocalizedError, Equatable {
  case invalidPayload, changedTarget
  var errorDescription: String? { self == .invalidPayload ? L10n.tr("业务通知载荷无效") : L10n.tr("业务通知所属服务、账户或项目已变化") }
}
enum BusinessNotificationShared {
  static let category = "agenthub.business"
  static func uuid(_ value: String) -> Bool { value.range(of: "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", options: .regularExpression) != nil }
  static func targetValid(_ value: BusinessNoticeTarget) -> Bool {
    [value.deploymentId, value.serverInstanceId, value.primaryHostId].allSatisfy { $0.range(of: "^[A-Za-z0-9._-]{1,128}$", options: .regularExpression) != nil && !["__proto__", "prototype", "constructor"].contains($0) } && uuid(value.actorId)
  }
  static var bindings: [String: BusinessNotificationBinding] {
    guard let data = TaskNotificationShared.secret("business-bindings-v1") else { return [:] }
    return (try? JSONDecoder().decode([String: BusinessNotificationBinding].self, from: data)) ?? [:]
  }
  static func setBindings(_ value: [String: BusinessNotificationBinding]) throws { try TaskNotificationShared.setSecret(JSONEncoder().encode(value), account: "business-bindings-v1") }
  static func privateKey() throws -> Curve25519.KeyAgreement.PrivateKey {
    if let raw = TaskNotificationShared.secret("business-device-key-v1") { return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw) }
    let key = Curve25519.KeyAgreement.PrivateKey(); try TaskNotificationShared.setSecret(key.rawRepresentation, account: "business-device-key-v1"); return key
  }
  static func decode(_ info: [AnyHashable: Any]) throws -> (BusinessNotice, BusinessNotificationBinding) {
    guard info["notification"] == nil, info["event"] == nil, let envelope = info["businessNotification"] as? [String: Any],
      Set(envelope.keys).isSubset(of: ["domain", "version", "bindingId", "eventId", "nonce", "ciphertext", "tag", "expiresAt", "sound", "timeSensitive"]),
      envelope["domain"] as? String == "taskhub.business", envelope["version"] as? Int == 1,
      let sound = envelope["sound"] as? String, TaskNotificationValidation.sound(sound), envelope["timeSensitive"] is Bool,
      let expiry = envelope["expiresAt"] as? String, BusinessNotice.date(expiry) != nil,
      let encoded = envelope["ciphertext"] as? String, encoded.utf8.count <= 2800,
      let bindingId = envelope["bindingId"] as? String, let binding = bindings[bindingId], binding.bindingId == bindingId,
      let eventId = envelope["eventId"] as? String, TaskNotificationValidation.eventId(eventId),
      let nonce = (envelope["nonce"] as? String).flatMap({ Data(base64Encoded: $0) }), nonce.count == 12,
      let ciphertext = (envelope["ciphertext"] as? String).flatMap({ Data(base64Encoded: $0) }), ciphertext.count <= 4096,
      let tag = (envelope["tag"] as? String).flatMap({ Data(base64Encoded: $0) }), tag.count == 16,
      let peer = Data(base64Encoded: binding.publicKey), peer.count == 32 else { throw BusinessNotificationError.invalidPayload }
    try binding.preferences.validate()
    guard TaskNotificationShared.preferences.enabled, binding.preferences.enabled, targetValid(binding.target), uuid(binding.projectId) else { throw BusinessNotificationError.changedTarget }
    let shared = try privateKey().sharedSecretFromKeyAgreement(with: Curve25519.KeyAgreement.PublicKey(rawRepresentation: peer))
    let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(bindingId.utf8), sharedInfo: Data("agenthub/taskhub.business/v1".utf8), outputByteCount: 32)
    let data = try AES.GCM.open(AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: ciphertext, tag: tag), using: key, authenticating: Data(("taskhub.business:" + eventId).utf8))
    guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any], Set(json.keys).isSubset(of: ["version", "domain", "eventId", "deploymentId", "serverInstanceId", "primaryHostId", "actorId", "projectId", "taskId", "roundId", "revision", "kind", "occurredAt", "title", "summary"]) else { throw BusinessNotificationError.invalidPayload }
    let event = try JSONDecoder().decode(BusinessNotice.self, from: data); try event.validate()
    guard event.eventId == eventId, event.target == binding.target, event.projectId == binding.projectId else { throw BusinessNotificationError.changedTarget }
    return (event, binding)
  }
}
