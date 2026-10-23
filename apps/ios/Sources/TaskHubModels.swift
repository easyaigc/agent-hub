import Foundation

struct TaskHubService: Codable, Equatable, Sendable {
  var version: Int
  var deploymentId: String
  var serverInstanceId: String
  var primaryHostId: String
  var name: String
  var contractVersion: String
  var capabilities: [String]
  var identity: String { deploymentId + "/" + serverInstanceId + "/" + primaryHostId }
  func validate() throws {
    guard version == 1, contractVersion == "1", !deploymentId.isEmpty, !serverInstanceId.isEmpty, !primaryHostId.isEmpty else { throw TaskHubError.message(L10n.tr("主服务尚未完成配置或版本不兼容")) }
  }
}
struct TaskHubBinding: Codable, Equatable, Sendable {
  var service: TaskHubService
  var endpoint: String
  var fingerprint: String
}
struct TaskHubActor: Codable, Identifiable, Sendable {
  var id: String
  var name: String
  var kind: String
  var role: String
  var hostId: String?
}
struct TaskHubLogin: Codable, Sendable {
  var actor: TaskHubActor
  var token: String
  var expiresAt: String
}
struct TaskHubDeviceSession: Codable, Identifiable, Sendable {
  var id: String; var deviceId: String; var deviceName: String
  var createdAt: String; var expiresAt: String; var revokedAt: String?
  var status: String; var current: Bool
}
struct TaskHubProject: Codable, Identifiable, Sendable {
  var id: String
  var key: String
  var name: String
  var description: String
  var updatedAt: String
  var archivedAt: String?
  var archived: Bool?
  var workflowPolicy: TaskHubJSON?
}
struct TaskHubAttachment: Codable, Identifiable, Sendable {
  var id: String
  var name: String
  var mediaType: String
  var sizeBytes: Int
  var url: String
  var thumbnailUrl: String?
  var sha256: String?
}
struct TaskHubTask: Codable, Identifiable, Sendable {
  var id: String
  var key: String
  var projectId: String
  var title: String
  var description: String
  var acceptanceCriteria: String
  var type: String
  var priority: String
  var status: String
  var ownerId: String
  var hostId: String?
  var dueDate: String?
  var currentRound: Int
  var revision: Int
  var pendingResponses: Int
  var latestFeedback: String?
  var acceptanceMode: String
  var reviewerId: String
  var updatedAt: String
  var attachments: [TaskHubAttachment]
  var workflowStage: String?
  var workflowPolicy: TaskHubJSON?
  var closing: TaskHubJSON?
  var statusTitle: String { ["todo":L10n.tr("待处理"), "doing":L10n.tr("进行中"), "review":L10n.tr("待验收"), "done":L10n.tr("已完成")][status] ?? status }
}
struct TaskHubResponse: Codable, Identifiable, Sendable {
  var id: String
  var actorId: String
  var body: String
  var decision: String
  var createdAt: String
  var attachments: [TaskHubAttachment]
}
struct TaskHubFeedback: Codable, Identifiable, Sendable {
  var id: String
  var roundId: String
  var title: String
  var body: String
  var authorId: String
  var responderId: String
  var status: String
  var createdAt: String
  var responses: [TaskHubResponse]
  var attachments: [TaskHubAttachment]
}
struct TaskHubDelivery: Codable, Identifiable, Sendable {
  var id: String
  var summary: String
  var candidateRef: String
  var feedbackIds: [String]
  var createdAt: String
  var actorId: String
  var attachments: [TaskHubAttachment]
  var candidate: TaskHubJSON?
}
struct TaskHubAcceptance: Codable, Identifiable, Sendable {
  var id: String
  var mode: String
  var reviewerId: String
  var hostId: String?
  var candidateRef: String
  var status: String
  var decisionNote: String?
  var decidedAt: String?
  var attachments: [TaskHubAttachment]
}
struct TaskHubRound: Codable, Identifiable, Sendable {
  var id: String
  var number: Int
  var status: String
  var createdAt: String
  var feedbacks: [TaskHubFeedback]
  var delivery: TaskHubDelivery?
  var acceptance: TaskHubAcceptance?
  var acceptances: [TaskHubAcceptance]?
  var policy: TaskHubJSON?
  var selfCheck: TaskHubJSON?
}
struct TaskHubEvent: Codable, Identifiable, Sendable {
  var id: String
  var kind: String
  var body: String
  var actorId: String
  var createdAt: String
  var roundNumber: Int
  var attachments: [TaskHubAttachment]
}
struct TaskHubDetail: Codable, Sendable {
  var task: TaskHubTask
  var rounds: [TaskHubRound]
  var events: [TaskHubEvent]
  init(task: TaskHubTask, rounds: [TaskHubRound], events: [TaskHubEvent]) { self.task = task; self.rounds = rounds; self.events = events }
  // The API returns a flattened TaskDetail. Preserve the domain boundary here.
  init(from decoder: Decoder) throws {
    task = try TaskHubTask(from: decoder)
    let container = try decoder.container(keyedBy: Keys.self)
    rounds = try container.decode([TaskHubRound].self, forKey: .rounds)
    events = try container.decode([TaskHubEvent].self, forKey: .events)
  }
  func encode(to encoder: Encoder) throws {
    try task.encode(to: encoder)
    var container = encoder.container(keyedBy: Keys.self)
    try container.encode(rounds, forKey: .rounds); try container.encode(events, forKey: .events)
  }
  enum Keys: String, CodingKey { case rounds, events }
  var current: TaskHubRound? { rounds.first { $0.number == task.currentRound } }
}
struct TaskHubTaskList: Codable, Sendable {
  var items: [TaskHubTask]
  var total: Int
  var page: Int
  var pageSize: Int
  var statusCounts: [String: Int]?
  var syncCursor: String?
}

struct TaskHubReadSnapshot: Codable, Sendable {
  var token: String; var taskId: String; var projectId: String; var currentRoundId: String?
  var revision: Int; var syncCursor: String; var expiresAt: String
}
struct TaskHubSnapshot: Decodable, Sendable {
  var task: TaskHubTask; var currentRoundDetail: TaskHubRound?; var snapshot: TaskHubReadSnapshot
  init(from decoder: Decoder) throws {
    task = try TaskHubTask(from: decoder)
    let container = try decoder.container(keyedBy: Keys.self)
    currentRoundDetail = try container.decodeIfPresent(TaskHubRound.self, forKey: .currentRoundDetail)
    snapshot = try container.decode(TaskHubReadSnapshot.self, forKey: .snapshot)
  }
  private enum Keys: String, CodingKey { case currentRoundDetail, snapshot }
}
struct TaskHubRoundSummary: Codable, Identifiable, Sendable {
  var id: String; var number: Int; var status: String; var createdAt: String
  var feedbackRevision: Int; var feedbackCount: Int; var eventCount: Int
}
struct TaskHubRoundsPage: Decodable, Sendable { var items: [TaskHubRoundSummary]; var snapshot: TaskHubReadSnapshot; var nextCursor: String? }
struct TaskHubRoundDetail: Decodable, Sendable { var round: TaskHubRound; var snapshot: TaskHubReadSnapshot }
struct TaskHubEventsPage: Decodable, Sendable { var items: [TaskHubEvent]; var snapshot: TaskHubReadSnapshot; var nextCursor: String? }
struct TaskHubHost: Codable, Identifiable, Sendable {
  var id: String
  var name: String
  var status: String
  var capabilities: [String]
  var error: String?
  var target: AgentRuntimeTarget?
}
struct TaskHubHostProject: Codable, Identifiable, Sendable {
  var id: String
  var name: String
  var roots: [String]
}
struct TaskHubProjectBinding: Codable, Identifiable, Sendable {
  var id: String
  var projectId: String
  var hostId: String
  var remoteProjectId: String
  var remoteProjectName: String
  var root: String
  var role: String
  var actorId: String
  var status: String
  var sourceRevision: Int?
  var verifiedAt: String?
  var target: AgentRuntimeTarget?
}
struct TaskHubRun: Codable, Identifiable, Sendable {
  var id: String
  var taskId: String
  var roundId: String
  var role: String
  var bindingId: String
  var hostId: String
  var remoteProjectId: String
  var threadId: String?
  var turnId: String?
  var operationId: String
  var state: String
  var lastError: String?
  var requestedEffort: String
  var actualEffort: String?
  var target: AgentRuntimeTarget?
  var createdAt: String
  var updatedAt: String
}
struct TaskHubExecutionNavigation: Codable, Sendable {
  var target: AgentRuntimeTarget?
  var threadId: String?
  var remoteProjectId: String?
  var runId: String
}
enum TaskHubJSON: Codable, Equatable, Sendable {
  case object([String: TaskHubJSON]), array([TaskHubJSON]), string(String), number(Double), bool(Bool), null
  init(from decoder: Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() { self = .null }
    else if let v = try? c.decode(Bool.self) { self = .bool(v) }
    else if let v = try? c.decode(Double.self) { self = .number(v) }
    else if let v = try? c.decode(String.self) { self = .string(v) }
    else if let v = try? c.decode([String: Self].self) { self = .object(v) }
    else { self = .array(try c.decode([Self].self)) }
  }
  func encode(to encoder: Encoder) throws {
    var c = encoder.singleValueContainer()
    switch self {
    case .object(let v): try c.encode(v)
    case .array(let v): try c.encode(v)
    case .string(let v): try c.encode(v)
    case .number(let v): try c.encode(v)
    case .bool(let v): try c.encode(v)
    case .null: try c.encodeNil()
    }
  }
  subscript(_ key: String) -> TaskHubJSON? { if case .object(let v) = self { return v[key] }; return nil }
  var string: String? { if case .string(let v) = self { return v }; return nil }
  var bool: Bool? { if case .bool(let v) = self { return v }; return nil }
  var array: [TaskHubJSON] { if case .array(let v) = self { return v }; return [] }
}
struct TaskHubOperation: Codable, Identifiable, Sendable {
  var id: String
  var scope: String
  var path: String
  var method: String
  var body: Data
  var query: [String: String]?
  var contentType: String?
  var phase: String
  var submittedAt: Date
  var response: Data?
  var message: String?
  var waitEndedAt: Date?
  var bodySha256: String?
  var uploadFile: String?
}

struct TaskHubBookmark: Codable, Sendable {
  var projectId: String
  var taskId: String?
  var roundId: String?
}

struct TaskHubCreationAssociation: Codable, Sendable {
  var scope: String
  var draftKey: String
  var draftId: String
  var operationId: String
  var bodySha256: String
  var projectId: String
}

struct TaskHubUploadAssociation: Codable, Sendable {
  var scope: String
  var draftKey: String
  var evidenceId: String
  var operationId: String
  var projectId: String
  var sha256: String
  var ticketId: String?
  var prepareOperationId: String?
  var draftId: String?
  var taskId: String?
  var roundId: String?
  var action: String?
}
struct TaskHubReceipt: Codable, Sendable {
  var operationId: String
  var phase: String
  var result: TaskHubJSON?
  var error: TaskHubErrorBody?
}
struct TaskHubErrorBody: Codable, Sendable { var code: String; var message: String }
enum TaskHubError: LocalizedError {
  case message(String), rejected(Int, String), changedIdentity
  var errorDescription: String? {
    switch self {
    case .message(let value), .rejected(_, let value): return value
    case .changedIdentity: return L10n.tr("主服务身份发生变化，已停止连接。请核对原主服务，避免连接到另一套数据。")
    }
  }
}
