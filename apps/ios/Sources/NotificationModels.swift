import CryptoKit
import Foundation
import Security
import UserNotifications

struct AgentRuntimeTarget: Codable, Equatable, Sendable {
  var agentId: String
  var hostId: String
  var workspaceId: String
  var runtimeId: String
  var account: AgentAccount?
  // Codex account is legacy/display metadata. Other providers retain their
  // original account semantics. Codable still preserves the captured payload.
  static func == (lhs: Self, rhs: Self) -> Bool {
    lhs.agentId == rhs.agentId && lhs.hostId == rhs.hostId && lhs.workspaceId == rhs.workspaceId
      && lhs.runtimeId == rhs.runtimeId && (lhs.agentId == "codex" || lhs.account == rhs.account)
  }
  func matchesCapturedPayload(_ other: Self) -> Bool {
    agentId == other.agentId && hostId == other.hostId && workspaceId == other.workspaceId
      && runtimeId == other.runtimeId && account == other.account
  }
}
struct AgentAccount: Codable, Equatable, Sendable { var authority: String; var subject: String }

// Machine identity is distinct from immutable operation/fingerprint payloads.
// These helpers only select existing storage; they never rename/remove records.
enum CodexMachineStorage {
  static func encoded(_ values: [Any]) -> String {
    (try! JSONSerialization.data(withJSONObject: values)).base64EncodedString()
  }
  static func machine(_ target: AgentRuntimeTarget) -> [String] {
    [target.agentId, target.hostId, target.workspaceId, target.runtimeId]
  }
  static func cacheKey(_ target: AgentRuntimeTarget, threadId: String?, projectId: String?) -> String {
    "codex-machine-v2:" + encoded(machine(target).map { $0 as Any } + [threadId == nil ? "draft" : "thread", threadId as Any? ?? projectId as Any? ?? NSNull()])
  }
  static func readingKey(_ target: AgentRuntimeTarget, threadId: String?) -> String {
    "codex-reading-v2:" + encoded(machine(target).map { $0 as Any } + [threadId as Any? ?? NSNull()])
  }
  static func readerScope(_ target: AgentRuntimeTarget) -> String { "codex-machine-v2:" + encoded(machine(target)) }
  private static func tuple(_ key: String, prefix: String) -> [Any]? {
    guard key.hasPrefix(prefix), let data = Data(base64Encoded: String(key.dropFirst(prefix.count))),
      data.count <= 8192, let values = try? JSONSerialization.jsonObject(with: data) as? [Any] else { return nil }
    return values
  }
  private static func selectorMatches(_ value: Any, _ expected: String?) -> Bool {
    if let expected { return value as? String == expected }
    return value is NSNull
  }
  static func compatibleCacheKeys(_ target: AgentRuntimeTarget, threadId: String?, projectId: String?, keys: [String], legacyDirectTarget: AgentRuntimeTarget? = nil) -> [String] {
    guard target.agentId == "codex" else { return [] }
    let canonical = cacheKey(target, threadId: threadId, projectId: projectId)
    let legacyDirect = target.hostId + ":" + (threadId ?? "new:" + (projectId ?? "none"))
    return keys.filter { key in
      if key == canonical { return true }
      if key == legacyDirect { return legacyDirectTarget?.agentId == "codex" && legacyDirectTarget == target }
      guard let row = tuple(key, prefix: "agent-v1:"), row.count == 8,
        let agent = row[0] as? String, let host = row[3] as? String, let workspace = row[4] as? String, let runtime = row[5] as? String,
        [agent, host, workspace, runtime] == machine(target), row[6] as? String == (threadId == nil ? "draft" : "thread") else { return false }
      return selectorMatches(row[7], threadId ?? projectId)
    }.sorted()
  }
  static func compatibleReadingKeys(_ target: AgentRuntimeTarget, threadId: String?, keys: [String]) -> [String] {
    guard target.agentId == "codex" else { return [] }
    let canonical = readingKey(target, threadId: threadId)
    return keys.filter { key in
      if key == canonical { return true }
      guard let row = tuple(key, prefix: ""), row.count == 8, row[0] as? String == "mobile-reading-v1",
        let agent = row[1] as? String, let host = row[4] as? String, let workspace = row[5] as? String, let runtime = row[6] as? String,
        [agent, host, workspace, runtime] == machine(target) else { return false }
      return selectorMatches(row[7], threadId)
    }.sorted()
  }
  /// Decode each legacy tuple once per storage revision, rather than once per
  /// conversation. Preserve all candidates: ambiguous old records still resolve
  /// to the canonical key, never to an arbitrary account's reading position.
  struct ReadingKeyIndex {
    private var candidates: [String: [String]] = [:]

    init(keys: Set<String> = []) {
      for key in keys {
        if key.hasPrefix("codex-reading-v2:") {
          candidates[key, default: []].append(key)
          continue
        }
        guard let row = CodexMachineStorage.tuple(key, prefix: ""), row.count == 8,
          row[0] as? String == "mobile-reading-v1", row[1] as? String == "codex",
          let host = row[4] as? String, let workspace = row[5] as? String,
          let runtime = row[6] as? String,
          row[7] is String || row[7] is NSNull else { continue }
        let target = AgentRuntimeTarget(agentId: "codex", hostId: host,
          workspaceId: workspace, runtimeId: runtime)
        let canonical = CodexMachineStorage.readingKey(target, threadId: row[7] as? String)
        candidates[canonical, default: []].append(key)
      }
      for key in candidates.keys { candidates[key]?.sort() }
    }

    func preferredKey(_ canonical: String, candidateKey: String? = nil) -> String {
      CodexMachineStorage.preferredKey(canonical, candidates: candidates[candidateKey ?? canonical] ?? [])
    }
  }
  static func preferredKey(_ canonical: String, candidates: [String], selectedLegacy: String? = nil) -> String {
    // Explicit review selects the original record without overwriting the current
    // draft. Otherwise prefer the current key, never a random old draft.
    if let selectedLegacy, candidates.contains(selectedLegacy) { return selectedLegacy }
    if candidates.contains(canonical) { return canonical }
    return candidates.count == 1 ? candidates[0] : canonical
  }
}

enum TaskNoticeKind: String, Codable, CaseIterable, Identifiable, Sendable {
  case approval, question, plan, paused, interrupted, failed, uncertain, completed, connection, stopped
  var id: String { rawValue }
  var title: String {
    switch self {
    case .approval: return L10n.tr("待审批")
    case .question: return L10n.tr("待回答")
    case .plan: return L10n.tr("计划待确认")
    case .paused: return L10n.tr("任务暂停")
    case .interrupted: return L10n.tr("异常中断")
    case .failed: return L10n.tr("任务失败")
    case .uncertain: return L10n.tr("操作结果待确认")
    case .completed: return L10n.tr("任务完成")
    case .connection: return L10n.tr("连接异常")
    case .stopped: return L10n.tr("手机主动停止")
    }
  }
  var actionable: Bool { [.approval, .question, .plan, .uncertain, .paused].contains(self) }
}
struct TaskNotificationPreferences: Codable, Equatable, Sendable {
  var version = 1
  var revision = "initial"
  var generation = 0
  var enabled = false
  var kinds: [TaskNoticeKind] = [.approval, .question, .plan, .paused, .interrupted, .failed, .uncertain]
  var excludedHosts: [String] = []
  var foregroundBanner = true
  var soundEnabled = true
  var badgeEnabled = true
  var showThreadTitle = true
  var timeSensitive = false
  var sound = "default"
  var categorySounds: [String: String] = [:]
  mutating func resetSoundReferences(_ id: String) {
    if sound == id { sound = "default" }
    categorySounds = categorySounds.filter { $0.value != id }
  }
}
struct TaskNotificationEvent: Codable, Equatable, Sendable {
  var version: Int
  var id: String
  var target: AgentRuntimeTarget
  var kind: TaskNoticeKind
  var occurredAt: String
  var threadId: String?
  var runId: String?
  var requestId: String?
  var operationId: String?
  var threadTitle: String?
  var taskSummary: String?
  var readTarget: AgentRuntimeTarget? = nil
  var readPosition: ReadPosition? = nil
}
struct TaskNotificationPresentation {
  static let category = "agenthub.task"
  let title: String
  let summary: String
  let computer: String
  let kind: String
  var metadata: String { computer + " · " + kind }
  init(event: TaskNotificationEvent, computer: String, showPreview: Bool) {
    self.computer = computer
    kind = event.kind.title
    title = showPreview ? (event.threadTitle.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 } ?? event.threadId.map { L10n.tr("对话") + " · " + String($0.prefix(8)) } ?? L10n.tr("任务")) : event.kind.title
    summary = showPreview ? (event.taskSummary.flatMap { $0.isEmpty ? nil : $0 } ?? Self.fallback(event.kind)) : ""
  }
  func apply(to content: UNMutableNotificationContent) {
    content.title = title
    content.subtitle = metadata
    content.body = summary
    content.categoryIdentifier = Self.category
  }
  private static func fallback(_ kind: TaskNoticeKind) -> String {
    switch kind {
    case .approval: return L10n.tr("有操作需要你审批")
    case .question: return L10n.tr("任务正在等待你的回答")
    case .plan: return L10n.tr("任务计划等待确认")
    case .paused: return L10n.tr("任务已暂停，请查看当前状态")
    case .interrupted: return L10n.tr("任务异常中断，请查看原因")
    case .failed: return L10n.tr("任务执行失败，请查看详情")
    case .uncertain: return L10n.tr("请打开对话核对已提交操作是否执行，避免重复提交")
    case .completed: return L10n.tr("任务已完成")
    case .connection: return L10n.tr("电脑连接发生变化")
    case .stopped: return L10n.tr("任务已主动停止")
    }
  }
}
struct TaskNotificationRegistration: Codable, Sendable {
  var version = 1
  var deviceId: String
  var target: AgentRuntimeTarget
  var publicKey: String
  var deviceToken: String?
  var environment: String?
  var preferences: TaskNotificationPreferences
}
struct TaskNotificationStatus: Codable, Sendable {
  struct Delivery: Codable, Sendable {
    var state: String; var occurredAt: String; var reason: String?
    var label: String {
      switch state {
      case "accepted": return L10n.tr("APNs 已接受，手机展示需由系统确认")
      case "pending": return L10n.tr("云端等待投递")
      case "submitting": return L10n.tr("正在提交给 APNs")
      case "uncertain": return L10n.tr("投递结果待确认")
      case "rejected": return L10n.tr("投递未被接受")
      default: return L10n.tr("投递状态待查询")
      }
    }
  }
  var version: Int
  var target: AgentRuntimeTarget
  var delivery: String
  var state: String
  var reason: String?
  var revision: String?
  var bindingId: String?
  var publicKey: String?
  var relayURL: String?
  var managementToken: String?
  var lastDelivery: Delivery?
  var label: String {
    if state == "disabled" { return L10n.tr("已关闭") }
    if state == "ready", delivery == "apns" { return L10n.tr("后台推送已连接") }
    if delivery == "foreground-only" { return L10n.tr("仅前台提醒") }
    if state == "unavailable" { return L10n.tr("后台推送不可用") }
    return L10n.tr("推送待同步")
  }
}
struct TaskNotificationFeed: Decodable, Sendable {
  var events: [TaskNotificationEvent]
  var cursor: String
  var activeIds: [String]?
  var status: TaskNotificationStatus?
}
struct TaskNoticeBinding: Codable, Sendable {
  var target: AgentRuntimeTarget
  var clientName: String
  var publicKey: String
  var relayURL: String
  var managementToken: String
}
enum TaskNotificationValidation {
  static func identifier(_ value: String) -> Bool { !value.isEmpty && value.utf16.count <= 256 && value.unicodeScalars.allSatisfy { $0.value >= 32 } }
  static func eventId(_ value: String) -> Bool { value.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil }
  static func sound(_ value: String) -> Bool { value.range(of: "^(default|none|[a-zA-Z0-9_-]{1,72}\\.caf)$", options: .regularExpression) != nil }
  static func taskSummary(_ value: String) -> Bool {
    guard !value.isEmpty, value.utf16.count <= 160, !value.hasPrefix(" "), !value.hasSuffix(" "), !value.contains("  ") else { return false }
    return value.unicodeScalars.allSatisfy { scalar in
      if scalar.value != 32 && CharacterSet.whitespacesAndNewlines.contains(scalar) { return false }
      switch scalar.value {
      case 0...31, 127...159, 173, 1564, 6158, 8203...8207, 8234...8238, 8288...8303, 65279: return false
      default: return true
      }
    }
  }
  static func target(_ value: AgentRuntimeTarget) -> Bool {
    [value.agentId, value.hostId, value.workspaceId, value.runtimeId].allSatisfy(identifier)
      && (value.agentId == "codex" || (value.account.map { identifier($0.authority) && identifier($0.subject) } ?? true))
  }
  static func preferences(_ value: TaskNotificationPreferences) -> Bool {
    value.version == 1 && identifier(value.revision) && value.generation >= 0 && value.generation < 9_007_199_254_740_991
      && Set(value.kinds).count == value.kinds.count && value.excludedHosts.count <= 256 && value.excludedHosts.allSatisfy(identifier)
      && sound(value.sound) && value.categorySounds.allSatisfy { TaskNoticeKind(rawValue: $0.key) != nil && sound($0.value) }
  }
  static func event(_ value: TaskNotificationEvent) -> Bool {
    let date = ISO8601DateFormatter(); date.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return value.version == 1 && eventId(value.id) && target(value.target)
      && (date.date(from: value.occurredAt) != nil || ISO8601DateFormatter().date(from: value.occurredAt) != nil)
      && [value.threadId, value.runId, value.requestId, value.operationId].compactMap { $0 }.allSatisfy(identifier)
      && (value.threadTitle.map { $0.count <= 100 } ?? true)
      && (value.taskSummary.map(taskSummary) ?? true)
      && (value.readTarget.map { reading in
        target(reading) && reading.agentId == value.target.agentId && reading.hostId == value.target.hostId
          && reading.workspaceId == value.target.workspaceId && reading.runtimeId == value.target.runtimeId
          && (value.target.account == nil || reading == value.target)
      } ?? true)
      && (value.readPosition.map { position in
        value.readTarget != nil && value.threadId != nil && value.kind == .completed
          && position.revision > 0 && position.revision <= 9_007_199_254_740_991
          && !position.messageId.isEmpty && position.messageId.utf16.count <= 512 && eventId(position.contentVersion)
      } ?? true)
  }
}
enum TaskNotificationShared {
  static var group: String? { Bundle.main.object(forInfoDictionaryKey: "NotificationAppGroup") as? String }
  static var directory: URL {
    #if DEBUG
    if let path = ProcessInfo.processInfo.environment["CODEX_NOTIFICATION_TEST_DIRECTORY"] { return URL(fileURLWithPath: path, isDirectory: true) }
    #endif
    let shared = group.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }
    return shared ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TaskNotifications", isDirectory: true)
  }
  static var soundDirectory: URL {
    #if DEBUG
    if let path = ProcessInfo.processInfo.environment["CODEX_NOTIFICATION_TEST_DIRECTORY"] { return URL(fileURLWithPath: path, isDirectory: true).appendingPathComponent("Library/Sounds", isDirectory: true) }
    #endif
    if let shared = group.flatMap({ FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }) { return shared.appendingPathComponent("Library/Sounds", isDirectory: true) }
    return FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Sounds", isDirectory: true)
  }
  static func read<T: Decodable>(_ type: T.Type, name: String) -> T? {
    guard let data = try? Data(contentsOf: directory.appendingPathComponent(name)) else { return nil }
    return try? JSONDecoder().decode(type, from: data)
  }
  static func write<T: Encodable>(_ value: T, name: String) throws {
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let url = directory.appendingPathComponent(name)
    try JSONEncoder().encode(value).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
  }
  static var preferences: TaskNotificationPreferences {
    guard let value = read(TaskNotificationPreferences.self, name: "preferences.json"), TaskNotificationValidation.preferences(value) else { return TaskNotificationPreferences() }
    return value
  }
  static func keychainQuery(_ account: String) -> [String: Any] {
    var result: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.codexmanage.notifications", kSecAttrAccount as String: account]
    #if !targetEnvironment(simulator)
    if let group = Bundle.main.object(forInfoDictionaryKey: "NotificationKeychainGroup") as? String, !group.contains("$(") { result[kSecAttrAccessGroup as String] = group }
    #endif
    return result
  }
  static func secret(_ account: String) -> Data? {
    var query = keychainQuery(account); query[kSecReturnData as String] = true; query[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
    return result as? Data
  }
  static func setSecret(_ data: Data, account: String) throws {
    let query = keychainQuery(account)
    let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
    let updated = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    let status = updated == errSecItemNotFound ? SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) : updated
    guard status == errSecSuccess else { throw NSError(domain: "TaskNotifications.Keychain", code: Int(status), userInfo: [NSLocalizedDescriptionKey: L10n.tr("无法安全保存通知密钥")]) }
  }
  static func removeSecret(_ account: String) { SecItemDelete(keychainQuery(account) as CFDictionary) }
  static func privateKey() throws -> Curve25519.KeyAgreement.PrivateKey {
    if let raw = secret("device-key") { return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: raw) }
    let key = Curve25519.KeyAgreement.PrivateKey(); try setSecret(key.rawRepresentation, account: "device-key"); return key
  }
  static var bindings: [String: TaskNoticeBinding] {
    guard let data = secret("bindings") else { return [:] }
    return (try? JSONDecoder().decode([String: TaskNoticeBinding].self, from: data)) ?? [:]
  }
  static func setBindings(_ bindings: [String: TaskNoticeBinding]) throws { try setSecret(JSONEncoder().encode(bindings), account: "bindings") }
  static func decodeEnvelope(_ info: [AnyHashable: Any]) throws -> TaskNotificationEvent {
    guard info["businessNotification"] == nil else { throw NSError(domain: "TaskNotifications.Domain", code: 1) }
    guard let envelope = info["notification"] as? [String: Any], let bindingId = envelope["bindingId"] as? String, let binding = bindings[bindingId] else { throw NSError(domain: "TaskNotifications", code: 1) }
    return try openEnvelope(envelope, privateKey: privateKey(), binding: binding)
  }
  static func openEnvelope(_ envelope: [String: Any], privateKey: Curve25519.KeyAgreement.PrivateKey, binding: TaskNoticeBinding) throws -> TaskNotificationEvent {
    guard envelope["version"] as? Int == 1, let bindingId = envelope["bindingId"] as? String,
      let eventId = envelope["eventId"] as? String, TaskNotificationValidation.eventId(eventId),
      let nonce = (envelope["nonce"] as? String).flatMap({ Data(base64Encoded: $0) }),
      let ciphertext = (envelope["ciphertext"] as? String).flatMap({ Data(base64Encoded: $0) }),
      let tag = (envelope["tag"] as? String).flatMap({ Data(base64Encoded: $0) }),
      let peer = Data(base64Encoded: binding.publicKey) else { throw NSError(domain: "TaskNotifications", code: 1) }
    let shared = try privateKey.sharedSecretFromKeyAgreement(with: Curve25519.KeyAgreement.PublicKey(rawRepresentation: peer))
    let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(bindingId.utf8), sharedInfo: Data("codex-manage/notifications/v1".utf8), outputByteCount: 32)
    let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: ciphertext, tag: tag)
    let data = try AES.GCM.open(box, using: key, authenticating: Data(eventId.utf8))
    let event = try JSONDecoder().decode(TaskNotificationEvent.self, from: data)
    guard TaskNotificationValidation.event(event), event.id == eventId, event.target == binding.target else { throw NSError(domain: "TaskNotifications", code: 2) }
    return event
  }
  static func remember(_ event: TaskNotificationEvent) throws {
    guard TaskNotificationValidation.event(event) else { throw NSError(domain: "TaskNotifications", code: 3) }
    try write(event, name: "notice-" + event.id + ".json")
  }
  static var notices: [TaskNotificationEvent] {
    ((try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [])
      .filter { $0.lastPathComponent.hasPrefix("notice-") }.compactMap { read(TaskNotificationEvent.self, name: $0.lastPathComponent) }
  }
}
