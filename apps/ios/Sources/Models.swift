import Foundation
import CryptoKit
import MobileCore

struct Client: Identifiable, Codable, Equatable, Sendable {
  let id: String
  var name: String
  var endpoint: String?
  var isDemo: Bool
  var online: Bool
  var agentConnection: AgentConnection? = nil
  var readingScopeId: String? = nil
  var readingTarget: AgentRuntimeTarget? = nil
  var codexDirect: CodexDirectConnection? = nil
}

struct Project: Identifiable, Codable, Equatable {
  let id: String
  let hostId: String
  var name: String
  var roots: [String]
}

struct Conversation: Identifiable, Codable, Equatable {
  let id: String
  let hostId: String
  let projectId: String?
  var title: String
  var state: String
  var projectIds: [String]? = nil
  var hasUnreadTurn: Bool? = nil
  var readState: ThreadReadState? = nil
  var archived: Bool? = nil
  var pendingAsyncQuestionCount: Int? = nil
  var archiveUnavailableReason: String? = nil
  var lastTurnStatus: String? = nil

  var showsUnreadDot: Bool {
    hasUnreadTurn == true && !["running", "waiting-for-approval"].contains(state)
  }

  func belongs(to project: Project) -> Bool {
    hostId == project.hostId && (projectId == project.id || projectIds?.contains(project.id) == true)
  }
}

struct ChatMessage: Identifiable, Codable, Equatable {
  let id: String
  let role: String
  var text: String
  var files: [String] = []
  var kind: String?
  var status: String?
  var detail: String?
  var attachments: [RemoteAttachment]?
  var blocks: [AgentContentPart]? = nil
  var runId: String? = nil
  var replyPhase: String? = nil
  var replyMessageId: String? = nil

  var isGroupedTool: Bool { role == "tool" && !["contextCompaction", "plan", "planImplementation", "error", "turnFailure"].contains(kind ?? "") }
}

// Presentation only: keep the original messages and their runtime IDs intact.
struct ChatTimeline {
  enum Item: Identifiable {
    case message(ChatMessage)
    case tools(id: String, messages: [ChatMessage])
    case compaction(ChatMessage)
    case errors(id: String, messages: [ChatMessage])
    case replyActions(id: String, text: String, turnId: String?)

    var id: String {
      switch self {
      case .message(let message): return "message-\(message.id)"
      case .tools(let id, _): return "tools-\(id)"
      case .compaction(let message): return "compaction-\(message.id)"
      case .errors(let id, _): return "errors-\(id)"
      case .replyActions(let id, _, _): return "reply-actions-\(id)"
      }
    }
  }

  let items: [Item]
  let hasEarlierMessages: Bool

  init(messages: [ChatMessage], messageLimit: Int, activeRunId: String? = nil, isRunning: Bool = false, focusedMessageId: String? = nil) {
    let projection = SharedMobileRules.timeline(messages, limit: messageLimit, activeRunId: activeRunId, running: isRunning, focusedMessageId: focusedMessageId)
    hasEarlierMessages = projection.hasEarlierMessages
    items = projection.rows.flatMap { row -> [Item] in
      let values = row.indices.map { messages[$0.intValue] }
      switch row.kind {
      case "tools": return [.tools(id: row.id, messages: values)]
      case "errors": return [.errors(id: row.id, messages: values)]
      case "compaction": return [.compaction(values[0])]
      case "replyActions": return [.replyActions(id: row.id, text: row.text ?? "", turnId: row.runId)]
      default: return [.message(values[0])]
      }
    }
  }
}

struct Draft: Codable, Equatable {
  var text = ""
  var attachments: [LocalAttachment] = []
  var operationId: String?
  var desktopOpenOperationId: String?
  var collaborationMode: String? = nil
  var model: String? = nil
  var effort: String? = nil
  var permissionMode: String? = nil
  var speed: String? = nil
}

struct DesktopCreationFile: Codable {
  let id: String
  let name: String
  let mediaType: String
  let sizeBytes: Int
  let sha256: String
}
struct PendingDesktopCreation: Codable {
  let hostId: String
  let clientOperationId: String
  let projectId: String?
  let text: String
  let attachments: [LocalAttachment]
  let manifest: [DesktopCreationFile]
  var phase: String
  var error: String? = nil
  var collaborationMode: String? = nil
  var model: String? = nil
  var effort: String? = nil
  var permissionMode: String? = nil
  var speed: String? = nil
  var stage: String? = nil
  var startedAt: Date? = nil
  var updatedAt: Date? = nil
  var preparation: DesktopCreationPreparation? = nil
  var sharingScope: AISharingScope? = nil
  var capturedTarget: AgentRuntimeTarget? = nil
  var trust: String? = nil

  var progressTitle: String {
    if phase == "rejected" { return L10n.tr("未提交 · 草稿已保留") }
    switch stage {
    case "saving": return L10n.tr("正在保存新建请求")
    case "preparing": return L10n.tr("正在准备聊天")
    case "uploading": return L10n.tr("正在上传首条附件")
    case "submitting": return L10n.tr("正在提交新建请求")
    case "querying": return L10n.tr("正在查询创建结果")
    default: return phase == "prepared" ? L10n.tr("新建尚未确认提交") : L10n.tr("正在等待电脑确认创建")
    }
  }
}
struct DesktopCreationPreparation: Codable, Equatable {
  struct Diagnostic: Codable, Equatable {
    let stage: String
    let code: String
    let disposition: String
  }
  let preparedThreadId: String?
  let preparationPhase: String
  let diagnostic: Diagnostic?
}
struct DesktopCreationOperation: Decodable {
  let hostId: String
  let clientOperationId: String
  let projectId: String?
  let phase: String
  let threadId: String?
  let error: String?
  let uploaded: [String]
  let thread: Conversation?
  var detached: Bool? = nil
  var preparation: DesktopCreationPreparation? = nil
}
struct DesktopOpenOperation: Decodable {
  let hostId: String
  let threadId: String
  let clientOperationId: String
  let phase: String
  let error: String?
}

enum ThreadForkEnvironment: String, Codable {
  case sameDirectory = "same-directory", worktree
  var title: String { self == .sameDirectory ? L10n.tr("在当前工作目录中分支") : L10n.tr("在新工作树中分支") }
}
struct ThreadForkRequest: Codable, Equatable {
  let hostId: String
  let threadId: String
  let turnId: String
  let clientOperationId: String
  let environment: ThreadForkEnvironment
}
struct ThreadForkOptions: Decodable {
  let hostId: String
  let threadId: String
  let sameDirectoryAvailable: Bool
  let worktreeAvailable: Bool
  let reason: String?
  let worktreeReason: String?
}
enum ThreadForkPhase: String, Codable {
  case submitted, confirmed, outcomeUnknown = "outcome-unknown", rejected
  var isPending: Bool { self == .submitted || self == .outcomeUnknown }
}
struct ThreadForkOperation: Decodable {
  let hostId: String
  let threadId: String
  let turnId: String
  let clientOperationId: String
  let environment: ThreadForkEnvironment
  let phase: ThreadForkPhase
  let forkedThreadId: String?
  let projectId: String?
  let error: String?
  var request: ThreadForkRequest {
    ThreadForkRequest(hostId: hostId, threadId: threadId, turnId: turnId,
      clientOperationId: clientOperationId, environment: environment)
  }
}
struct ThreadForkSource: Equatable {
  let target: AgentRuntimeTarget
  let threadId: String
  var key: String {
    ReadingIdentity.key(agentId: target.agentId, authority: target.account?.authority,
      subject: target.account?.subject, hostId: target.hostId, workspaceId: target.workspaceId,
      runtimeId: target.runtimeId, threadId: threadId)
  }
}
struct PendingThreadFork: Identifiable, Codable {
  // Submitted intent and full source identity never change with drafts or selection.
  let request: ThreadForkRequest
  let sourceTarget: AgentRuntimeTarget
  let createdAt: Date
  var phase: ThreadForkPhase
  var forkedThreadId: String?
  var projectId: String?
  var error: String?
  var detached: Bool? = nil
  var id: String { request.clientOperationId }
  var source: ThreadForkSource { ThreadForkSource(target: sourceTarget, threadId: request.threadId) }
  var title: String {
    switch phase {
    case .submitted: return detached == true ? L10n.tr("已结束等待 · 原回执保留") : L10n.tr("正在等待电脑确认分支")
    case .outcomeUnknown: return detached == true ? L10n.tr("已结束等待 · 结果待确认") : L10n.tr("分支结果待确认")
    case .confirmed: return L10n.tr("分支已创建")
    case .rejected: return L10n.tr("分支未创建")
    }
  }
}

struct LocalAttachment: Identifiable, Codable, Equatable {
  let id: String
  let name: String
  let size: Int
  let fileName: String
  let isImage: Bool
}

struct ClientType: Codable {
  let id: String
  let name: String
}

enum WorkspaceManagementAction: String, Codable, CaseIterable, Identifiable {
  case deleteProjects = "projects:delete", archiveThreads = "threads:archive"
  var id: String { rawValue }
  var title: String { self == .deleteProjects ? L10n.tr("删除项目") : L10n.tr("归档对话") }
  var capability: String { self == .archiveThreads ? "threads:archive:unloaded" : rawValue }
}
struct WorkspaceManagementRequest: Codable, Equatable {
  let hostId: String
  let target: AgentRuntimeTarget
  let clientOperationId: String
  let action: WorkspaceManagementAction
  let ids: [String]
}
struct WorkspaceManagementOperation: Codable, Identifiable {
  struct Item: Codable { let id: String; var state: String; var error: String? }
  let hostId: String
  let target: AgentRuntimeTarget
  let clientOperationId: String
  let action: WorkspaceManagementAction
  let ids: [String]
  var items: [Item]
  var labels: [String: String]? = nil
  var id: String { clientOperationId }
  var request: WorkspaceManagementRequest { WorkspaceManagementRequest(hostId: hostId, target: target, clientOperationId: id, action: action, ids: ids) }
  var needsCheck: Bool { items.contains { SharedMobileRules.workspaceArchivePending($0.state) } }
}

struct WorkspaceSidebar: Codable {
  struct Item: Codable {
    let kind: String
    let id: String
    var key: String { kind + ":" + id }
  }
  let version: Int
  let pinned: [Item]
  let recentThreadIds: [String]
  let projectIds: [String]
  let projectThreadIds: [String: [String]]
  enum CodingKeys: String, CodingKey { case version, pinned, recentThreadIds, projectIds, projectThreadIds }
  init(from decoder: Decoder) throws {
    let value = try decoder.container(keyedBy: CodingKeys.self)
    version = try value.decode(Int.self, forKey: .version)
    guard version == 1 else {
      pinned = []; recentThreadIds = []; projectIds = []; projectThreadIds = [:]
      return
    }
    pinned = try value.decode([Item].self, forKey: .pinned)
    recentThreadIds = try value.decode([String].self, forKey: .recentThreadIds)
    projectIds = try value.decode([String].self, forKey: .projectIds)
    projectThreadIds = try value.decode([String: [String]].self, forKey: .projectThreadIds)
  }
}

struct WorkspaceSnapshot: Codable {
  let hostId: String
  let name: String
  let connectionMode: String
  var projects: [Project]
  var threads: [Conversation]
  let nextProjectCursor: String?
  let nextThreadCursor: String?
  var capabilities: [String]?
  var clientType: ClientType? = nil
  var agentConnectionState: AgentConnectionState? = nil
  var readTarget: AgentRuntimeTarget? = nil
  var pinnedThreadIds: [String]? = nil
  var sidebar: WorkspaceSidebar? = nil
}

struct HistorySnapshot: Codable {
  var readTarget: AgentRuntimeTarget? = nil
  let hostId: String
  let threadId: String
  var thread: Conversation?
  let messages: [ChatMessage]
  let nextCursor: String?
  var runtime: ThreadRuntime?
  var epoch: String?
  var cursor: Int?
  var historyWindow: HistoryWindow? = nil
  var synchronization: ConversationSynchronization? = nil
  var historyRevision: Int? = nil
}

struct HistoryWindow: Codable {
  var version: Int
  var kind: String
  private var booleanRepresentation: Bool? = nil
  var isWindowed: Bool { booleanRepresentation ?? (version == 1) }
  init(version: Int, kind: String) { self.version = version; self.kind = kind }
  enum Keys: String, CodingKey { case version, kind }
  init(from decoder: Decoder) throws {
    let scalar = try decoder.singleValueContainer()
    if let flag = try? scalar.decode(Bool.self) {
      version = flag ? 1 : 0; kind = flag ? "latest" : "complete"; booleanRepresentation = flag
    } else {
      let fields = try decoder.container(keyedBy: Keys.self)
      version = try fields.decode(Int.self, forKey: .version)
      kind = try fields.decode(String.self, forKey: .kind)
    }
  }
  func encode(to encoder: Encoder) throws {
    if let booleanRepresentation {
      var scalar = encoder.singleValueContainer(); try scalar.encode(booleanRepresentation)
    } else {
      var fields = encoder.container(keyedBy: Keys.self)
      try fields.encode(version, forKey: .version); try fields.encode(kind, forKey: .kind)
    }
  }
}
struct ConversationSynchronization: Codable { var state: String; var checkedAt: String? = nil }

struct ReadAcknowledgement: Decodable {
  var target: AgentRuntimeTarget
  var hostId: String
  var threadId: String
  var readerId: String
  var read: ReadPosition
}

struct RemoteAttachment: Identifiable, Codable, Equatable {
  let id: String
  let hostId: String
  let threadId: String
  let name: String
  let mediaType: String
  let sizeBytes: Int
  let sha256: String
}
struct PlanStep: Codable {
  let step: String
  let status: String
}
struct ProposedPlan: Identifiable, Codable, Equatable {
  let id: String
  let turnId: String
  let text: String
}
struct ApprovalQuestion: Identifiable, Codable {
  let id: String
  let question: String
  var options: [QuestionOption]?
  var isSecret: Bool?
  var multiple: Bool? = nil
  var custom: Bool? = nil
}
struct QuestionOption: Codable {
  let label: String
  let description: String
}
struct Approval: Identifiable, Codable {
  let id: String
  let threadId: String
  let turnId: String
  let kind: String
  let title: String
  let detail: String
  let questions: [ApprovalQuestion]
  let decisions: [String]
  var notificationRequestId: String? = nil
  var interactionMode: String? = nil
  var sourceItemId: String? = nil
}
struct DeliveryReceipt: Identifiable, Codable {
  var id: String { clientMessageId }
  let clientMessageId: String
  let hostId: String
  let threadId: String
  let state: String
  let turnId: String?
  let error: String?
  var supersededByTurnId: String? = nil
  var text: String? = nil
  var attachmentIds: [String]? = nil
  var queueSource: String? = nil
  var queuePresent: Bool? = nil
  var canExecuteNow: Bool? = nil
  var queueDisposition: String? = nil
  var queueSyncError: String? = nil
  var noticeContext: String? = nil
  var laterTurnId: String? = nil
  var failureTitle: String {
    turnId?.isEmpty == false ? L10n.tr("任务执行失败") : L10n.tr("消息提交失败")
  }
}
struct ThreadRuntime: Codable {
  var state: String
  var turnId: String?
  var plan: [PlanStep]
  var error: String?
  var approvals: [Approval]
  var receipts: [DeliveryReceipt]
  var canControl: Bool?
  var collaborationMode: String? = nil
  var proposedPlan: ProposedPlan? = nil
  var generationSettings: GenerationOptions? = nil
  var suggestionSourceTurnId: String? = nil
}
struct EventBatch: Decodable {
  let hostId: String
  let epoch: String
  let cursor: Int
  let reset: Bool
  let events: [HostEvent]
  var ready: Bool?
  var capabilities: [String]?
}
struct HostEvent: Decodable {
  let threadId: String
  let snapshot: HistorySnapshot
}
struct PendingReply: Codable {
  let hostId: String
  let threadId: String
  let clientMessageId: String
  let text: String
  let attachmentIds: [String]
  let mode: String
  let expectedTurnId: String?
  var model: String?
  var effort: String?
  var collaborationMode: String? = nil
  var expectedPlanId: String? = nil
  var permissionMode: String? = nil
  var speed: String? = nil
  var sourceAttachments: [LocalAttachment]?
  var knownRejected: Bool?
}

struct DetachedReplyRecord: Codable, Identifiable {
  var id: String { original.clientMessageId }
  let original: PendingReply
  var receipt: DeliveryReceipt? = nil
  var error: String? = nil
}

// Recovery authority is separate from the immutable outbox body. Legacy rows
// without this captured context stay query-only; knownRejected is not proof.
struct ReplyRecoveryRecord: Codable {
  let original: PendingReply
  let target: AgentRuntimeTarget
  let trust: String
  let fingerprint: String
  var phase: String
  var inspection: ReplyDeliveryInspection? = nil
  var retryConsumed = false
  var sharingScope: AISharingScope? = nil
}
struct ReplyDeliveryInspection: Codable {
  struct Evidence: Codable {
    let kind: String
    let fingerprint: String
    let oldPid: Int
    let newPid: Int
    let ledgerSha256: String
    let fenceSha256: String
    let ledgerId: String
  }
  let version: Int
  let hostId: String
  let target: AgentRuntimeTarget
  let threadId: String
  let clientMessageId: String
  let status: String
  let observedAt: String
  let evidence: Evidence?
  let capturedTarget: AgentRuntimeTarget?
  let fingerprintVersion: Int?
  let fingerprint: String?
  let receipt: DeliveryReceipt?
}
enum ReplyRecoveryPolicy {
  private static func json(_ value: Any) throws -> String {
    let data = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .withoutEscapingSlashes])
    guard let string = String(data: data, encoding: .utf8) else { throw HostServiceError.invalidResponse }
    return string
  }
  // Match the canonical v2 ordered JSON, including captured legacy metadata.
  // Machine equality is never substituted for the immutable payload fingerprint.
  static func fingerprint(_ reply: PendingReply, target: AgentRuntimeTarget) throws -> String {
    var fields = try [("agentId", json(target.agentId)), ("hostId", json(target.hostId)),
      ("workspaceId", json(target.workspaceId)), ("runtimeId", json(target.runtimeId))]
    if let account = target.account {
      fields.append(("account", try "{\"authority\":" + json(account.authority) + ",\"subject\":" + json(account.subject) + "}"))
    }
    func object(_ fields: [(String, String)]) -> String { "{" + fields.map { "\"" + $0.0 + "\":" + $0.1 }.joined(separator: ",") + "}" }
    var body = try [("hostId", json(reply.hostId)), ("threadId", json(reply.threadId)),
      ("clientMessageId", json(reply.clientMessageId)), ("text", json(reply.text)),
      ("attachmentIds", json(reply.attachmentIds)), ("mode", json(reply.mode))]
    let optional: [(String, String?)] = [("expectedTurnId", reply.expectedTurnId), ("model", reply.model),
      ("effort", reply.effort), ("permissionMode", reply.permissionMode), ("speed", reply.speed),
      ("collaborationMode", reply.collaborationMode), ("expectedPlanId", reply.expectedPlanId)]
    for (key, value) in optional { if let value { body.append((key, try json(value))) } }
    let wire = "{\"version\":2,\"target\":" + object(fields) + ",\"reply\":" + object(body) + "}"
    return SHA256.hash(data: Data(wire.utf8)).map { String(format: "%02x", $0) }.joined()
  }
  static func sameIntent(_ reply: PendingReply, record: ReplyRecoveryRecord) -> Bool {
    reply.hostId == record.target.hostId && reply.hostId == record.original.hostId
      && reply.threadId == record.original.threadId && reply.clientMessageId == record.original.clientMessageId
      && (try? fingerprint(reply, target: record.target)) == record.fingerprint
      && (try? fingerprint(record.original, target: record.target)) == record.fingerprint
  }
  static func strongNotAccepted(_ value: ReplyDeliveryInspection, record: ReplyRecoveryRecord, now: Date = Date()) -> Bool {
    guard record.target.agentId == "codex", sameIntent(record.original, record: record),
      value.version == 2, value.hostId == record.original.hostId,
      value.threadId == record.original.threadId, value.clientMessageId == record.original.clientMessageId,
      value.target.matchesCapturedPayload(record.target), value.capturedTarget?.matchesCapturedPayload(record.target) == true,
      value.status == "not-accepted", value.receipt == nil, value.fingerprintVersion == 2,
      value.fingerprint == record.fingerprint, let evidence = value.evidence,
      evidence.kind == "operator-fenced-checkpoint-v2", evidence.fingerprint == record.fingerprint,
      evidence.oldPid > 0, evidence.newPid > 0, evidence.oldPid != evidence.newPid,
      UUID(uuidString: evidence.ledgerId) != nil,
      [evidence.ledgerSha256, evidence.fenceSha256].allSatisfy({ $0.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil }) else { return false }
    let date = ISO8601DateFormatter(); date.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    guard let observed = date.date(from: value.observedAt) ?? ISO8601DateFormatter().date(from: value.observedAt),
      abs(now.timeIntervalSince(observed)) <= 60 else { return false }
    return true
  }
  static func mayRetry(_ reply: PendingReply, record: ReplyRecoveryRecord?, now: Date = Date()) -> Bool {
    guard let record, !record.retryConsumed, sameIntent(reply, record: record) else { return false }
    if record.phase == "not-written" { return true } // Local durable pre-network disposition only.
    return record.phase == "strong-not-accepted" && record.inspection.map { strongNotAccepted($0, record: record, now: now) } == true
  }
  static func imported(_ record: ReplyRecoveryRecord) -> ReplyRecoveryRecord {
    // A backup cannot attest what the original installation submitted later.
    // Keep its immutable intent and consumed retry fence; query the original ID.
    var restored = record
    restored.phase = "uncertain"
    restored.inspection = nil
    return restored
  }
}

struct CodexModel: Identifiable, Decodable {
  let id: String
  let name: String
  let isDefault: Bool
  var defaultEffort: String? = nil
  var supportedEfforts: [String]? = nil
  var defaultSpeed: String? = nil
  var supportedSpeeds: [String]? = nil
}
struct GenerationOptions: Codable {
  var model: String?
  var effort: String?
  var permissionMode: String?
  var speed: String? = nil
}

enum PermissionOption: String, CaseIterable, Identifiable {
  case ask = "read-only", help = "guardian-approvals", full = "full-access"
  var id: String { rawValue }
  var title: String { switch self { case .ask: L10n.tr("请求批准"); case .help: L10n.tr("帮我批准"); case .full: L10n.tr("完全访问权限") } }
  var detail: String { switch self { case .ask: L10n.tr("编辑外部文件和使用互联网时始终询问"); case .help: L10n.tr("仅对检测到的风险操作请求批准"); case .full: L10n.tr("可访问互联网和电脑上的任何文件") } }
  var icon: String { switch self { case .ask: "hand.raised"; case .help: "checkmark.shield"; case .full: "exclamationmark.shield" } }
}
struct ModelList: Decodable { let models: [CodexModel] }
struct CommandResult: Decodable { let ok: Bool }

enum ComposerAction: String {
  case send, stop, resume, busy

  var symbol: String {
    switch self {
    case .send: return "arrow.up"
    case .stop: return "stop.fill"
    case .resume: return "play.fill"
    case .busy: return "ellipsis"
    }
  }
  var label: String {
    switch self {
    case .send: return L10n.tr("发送消息")
    case .stop: return L10n.tr("停止任务")
    case .resume: return L10n.tr("继续任务")
    case .busy: return L10n.tr("正在处理")
    }
  }
  var identifier: String {
    switch self {
    case .send: return "send-message"
    case .stop: return "stop-turn"
    case .resume: return "resume-task"
    case .busy: return "composer-busy"
    }
  }
}

enum AppSheet: String, Identifiable {
  case clients, addClient, projects, settings, addProject, generation, fileTransfers, aiSharing, taskHub, archivedChats, chatSearch
  var id: String { rawValue }
}

enum Appearance: String, CaseIterable, Codable {
  case system = "跟随系统"
  case light = "浅色"
  case dark = "深色"

  var title: String {
    switch self {
    case .system: return L10n.tr("跟随系统")
    case .light: return L10n.tr("浅色")
    case .dark: return L10n.tr("深色")
    }
  }
}

struct SessionHistoryPage: Codable {
  let targetKey: String
  let messages: [ChatMessage]
  let encrypted: Bool
}

struct SavedSession: Codable {
  var workspaceManagement: [WorkspaceManagementOperation]? = nil
  var demoDeletedProjectIds: [String]? = nil
  var demoArchivedThreadIds: [String]? = nil
  var mobileCacheVersion: Int? = nil
  var aiSharingGrants: [AISharingGrant]? = nil
  var readingDeviceId: String? = nil
  var codexReaderScopeIds: [String: String]? = nil
  var codexStorageAliases: [String: String]? = nil
  var mobileReads: [String: MobileReadRecord]? = nil
  var mobileReadBaselines: [String: MobileReadBaseline]? = nil
  var historyCacheNamespace: String? = nil
  var historyCacheKeys: [String]? = nil
  var historyCacheEncrypted: Bool? = nil
  var clients: [Client] = []
  var workspaces: [String: WorkspaceSnapshot] = [:]
  var drafts: [String: Draft] = [:]
  var history: [String: [ChatMessage]] = [:]
  var demoThreads: [Conversation] = []
  var lastThreads: [String: String] = [:]
  var lastThreadProjects: [String: String]?
  var newProjects: [String: String] = [:]
  var expanded: [String: Set<String>] = [:]
  var selectedClientId: String?
  var appearance: Appearance = .system
  var runtimes: [String: ThreadRuntime]?
  var outbox: [String: PendingReply]?
  var replyRecovery: [String: ReplyRecoveryRecord]? = nil
  var detachedReplyWaits: [String]? = nil
  var detachedReplies: [String: [DetachedReplyRecord]]? = nil
  var dismissedReceiptFailures: [String: Set<String>]?
  var agentEventCursors: [String: AgentEventCursor]?
  var desktopCreations: [String: PendingDesktopCreation]?
  var detachedDesktopCreations: [String: [PendingDesktopCreation]]?
  var chatActionReceipts: [String: ChatActionReceipt]?
  var threadForks: [String: PendingThreadFork]?
  var generationPreferences: [String: GenerationOptions]?
}

struct AISharingScope: Codable, Equatable {
  var version = 1
  let target: AgentRuntimeTarget
  let model: String
  static func == (lhs: Self, rhs: Self) -> Bool {
    lhs.version == 1 && rhs.version == 1 && AISharingRules.shared.matches(
      a: SharedMobileRules.scope(lhs.target), aModel: lhs.model,
      b: SharedMobileRules.scope(rhs.target), bModel: rhs.model)
  }
}

struct AISharingGrant: Codable, Identifiable {
  let id: String
  let scope: AISharingScope
  let computerName: String
  let agentName: String
  let modelName: String
  let grantedAt: Date
}

struct AISharingRequest: Identifiable {
  let id = UUID()
  let scope: AISharingScope
  let targetKey: String
  let computerName: String
  let agentName: String
  let modelName: String
}

enum DemoData {
  static let clients = [
    Client(id: "demo-mac", name: L10n.tr("我的 MacBook"), isDemo: true, online: true),
    Client(id: "demo-studio", name: L10n.tr("工作室 Mac"), isDemo: true, online: true),
    Client(id: "demo-home", name: L10n.tr("家里的 Mac"), isDemo: true, online: false),
  ]
  static let projects = [
    Project(id: "mobile", hostId: "demo-mac", name: "Agent Hub", roots: []),
    Project(id: "api", hostId: "demo-mac", name: "api-service", roots: []),
    Project(id: "design", hostId: "demo-mac", name: "design-system", roots: []),
    Project(id: "web", hostId: "demo-studio", name: "web-studio", roots: []),
    Project(id: "notes", hostId: "demo-home", name: L10n.tr("个人笔记"), roots: []),
  ]
  static let threads = [
    Conversation(
      id: "navigation", hostId: "demo-mac", projectId: "mobile", title: L10n.tr("设计移动端导航"),
      state: "idle"),
    Conversation(
      id: "pairing", hostId: "demo-mac", projectId: "mobile", title: L10n.tr("梳理设备配对流程"), state: "idle"
    ),
    Conversation(
      id: "query", hostId: "demo-mac", projectId: "api", title: L10n.tr("优化查询性能"), state: "idle"),
    Conversation(
      id: "spacing", hostId: "demo-mac", projectId: "design", title: L10n.tr("统一组件间距"), state: "idle"),
    Conversation(
      id: "website", hostId: "demo-studio", projectId: "web", title: L10n.tr("完善网站首页"), state: "idle"),
    Conversation(
      id: "reading", hostId: "demo-home", projectId: "notes", title: L10n.tr("整理读书笔记"),
      state: "unknown"),
  ]
  static let messages = [
    ChatMessage(
      id: "demo-user", role: "user", text: L10n.tr("帮我整理移动端开发任务，列出下一步工作。")),
    ChatMessage(
      id: "demo-assistant", role: "assistant",
      text:
        L10n.tr("下一步可以按三个阶段推进：\n\n1. **连接电脑**：在同一局域网中自动发现并添加客户端。\n2. **组织任务**：按项目浏览对话，继续已有工作或新建任务。\n3. **查看结果**：检查回复和工具进度，处理需要确认的操作。\n\n当前项目为 **Agent Hub**。切换电脑后，会进入对应电脑上的项目与对话。\n\n这是本地界面示例，不会向电脑或 AI 服务发送内容。")
    ),
  ]
}

struct AgentPermissionOption: Identifiable {
  let id: String
  let title: String
  let detail: String
  let icon: String
  var highPrivilege: Bool = false
}


enum ChatAction: String, Codable, Sendable {
  case pin, unpin, archive, unarchive, delete
  var title: String { switch self {
    case .pin: L10n.tr("置顶")
    case .unpin: L10n.tr("取消置顶")
    case .archive: L10n.tr("归档")
    case .unarchive: L10n.tr("恢复对话")
    case .delete: L10n.tr("删除")
  } }
  static func errorDescription(_ message: String) -> String {
    switch message {
    case "电脑端拒绝了此次操作：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。":
      return L10n.tr("电脑端拒绝了此次操作：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。")
    case "电脑端拒绝了此次归档：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。":
      return L10n.tr("电脑端拒绝了此次归档：当前连接暂不支持管理桌面已加载的对话，请在桌面端操作。对话未改变。")
    default: return message
    }
  }
}
struct ChatActionRequest: Codable, Equatable, Sendable {
  let hostId: String
  let target: AgentRuntimeTarget
  let threadId: String
  let clientOperationId: String
  let action: ChatAction
}
struct ChatActionReceipt: Codable, Equatable, Identifiable, Sendable {
  let hostId: String
  let target: AgentRuntimeTarget
  let threadId: String
  let clientOperationId: String
  let action: ChatAction
  var phase: String
  var error: String?
  var waitingEnded: Bool? = nil
  var id: String { clientOperationId }
  var request: ChatActionRequest { ChatActionRequest(hostId: hostId, target: target, threadId: threadId, clientOperationId: clientOperationId, action: action) }
  var pending: Bool { SharedMobileRules.chatActionPending(phase) }
}
struct ChatSearchJump: Equatable {
  let targetKey: String
  let message: ChatMessage
  let query: String
  let token = UUID()
}
extension ChatMessage {
  var searchableText: String {
    let parts = (blocks ?? []).flatMap { part -> [String] in
      switch part {
      case .text(let text): return [text]
      case .resource(_, let name, _): return [name]
      case .tool(_, let name, _, let input, let output): return [name, input ?? "", output ?? ""]
      case .diff(_, let text): return [text]
      case .extensionData: return []
      }
    }
    return SharedMobileRules.searchDocument(text: text, detail: detail, files: files, attachments: (attachments ?? []).map(\.name), parts: parts)
  }
}
