import Foundation
import MobileCore

struct AgentConnectionCandidate: Codable, Equatable, Sendable {
  var id: String
  var transportId: String
  var endpoint: String
  var peerFingerprint: String?
  enum NetworkKind: String, Codable, Sendable { case lan, vpn, unknown }
  var networkKind: NetworkKind? = nil
}
enum AgentConnectionPreference: String, Codable, CaseIterable, Sendable {
  case automatic, lanOnly = "lan-only", relayOnly = "relay-only"
  var title: String { switch self { case .automatic: return L10n.tr("自动"); case .lanOnly: return L10n.tr("仅直连"); case .relayOnly: return L10n.tr("仅中继") } }
}
struct AgentConnection: Codable, Equatable, Sendable {
  var target: AgentRuntimeTarget
  var candidates: [AgentConnectionCandidate]
  var preference: AgentConnectionPreference
  var credentialRef: String
}

enum AgentJSONValue: Codable, Equatable, Sendable {
  case null, bool(Bool), number(Decimal), string(String), array([AgentJSONValue]), object([String: AgentJSONValue])
  init(from decoder: Decoder) throws {
    let value = try decoder.singleValueContainer()
    if value.decodeNil() { self = .null }
    else if let v = try? value.decode(Bool.self) { self = .bool(v) }
    else if let v = try? value.decode(Decimal.self) { self = .number(v) }
    else if let v = try? value.decode(String.self) { self = .string(v) }
    else if let v = try? value.decode([AgentJSONValue].self) { self = .array(v) }
    else { self = .object(try value.decode([String: AgentJSONValue].self)) }
  }
  func encode(to encoder: Encoder) throws { var v = encoder.singleValueContainer(); switch self { case .null: try v.encodeNil(); case .bool(let x): try v.encode(x); case .number(let x): try v.encode(x); case .string(let x): try v.encode(x); case .array(let x): try v.encode(x); case .object(let x): try v.encode(x) } }
}
enum AgentContentPart: Codable, Equatable, Sendable {
  case text(String), resource(id: String, name: String, mediaType: String), tool(id: String, name: String, status: String, input: String?, output: String?), diff(id: String, text: String), extensionData(namespace: String, data: [String: AgentJSONValue])
  enum Keys: String, CodingKey { case type, text, resourceId, name, mediaType, id, status, input, output, namespace, data }
  init(from decoder: Decoder) throws {
    try SharedMobileRules.validateDomain(decoder, definition: "part")
    let v = try decoder.container(keyedBy: Keys.self)
    switch try v.decode(String.self, forKey: .type) {
    case "text": self = .text(try v.decode(String.self, forKey: .text))
    case "resource": self = .resource(id: try v.decode(String.self, forKey: .resourceId), name: try v.decode(String.self, forKey: .name), mediaType: try v.decode(String.self, forKey: .mediaType))
    case "tool": self = .tool(id: try v.decode(String.self, forKey: .id), name: try v.decode(String.self, forKey: .name), status: try v.decode(String.self, forKey: .status), input: try v.decodeIfPresent(String.self, forKey: .input), output: try v.decodeIfPresent(String.self, forKey: .output))
    case "diff": self = .diff(id: try v.decode(String.self, forKey: .id), text: try v.decode(String.self, forKey: .text))
    case "extension": self = .extensionData(namespace: try v.decode(String.self, forKey: .namespace), data: try v.decode([String: AgentJSONValue].self, forKey: .data))
    default: throw DecodingError.dataCorruptedError(forKey: .type, in: v, debugDescription: "Unknown management content part")
    }
  }
  func encode(to encoder: Encoder) throws {
    var v = encoder.container(keyedBy: Keys.self)
    switch self {
    case .text(let text): try v.encode("text", forKey: .type); try v.encode(text, forKey: .text)
    case .resource(let id, let name, let mediaType): try v.encode("resource", forKey: .type); try v.encode(id, forKey: .resourceId); try v.encode(name, forKey: .name); try v.encode(mediaType, forKey: .mediaType)
    case .tool(let id, let name, let status, let input, let output): try v.encode("tool", forKey: .type); try v.encode(id, forKey: .id); try v.encode(name, forKey: .name); try v.encode(status, forKey: .status); try v.encodeIfPresent(input, forKey: .input); try v.encodeIfPresent(output, forKey: .output)
    case .diff(let id, let text): try v.encode("diff", forKey: .type); try v.encode(id, forKey: .id); try v.encode(text, forKey: .text)
    case .extensionData(let namespace, let data): try v.encode("extension", forKey: .type); try v.encode(namespace, forKey: .namespace); try v.encode(data, forKey: .data)
    }
  }
}
struct AgentProject: Codable, Equatable, Sendable { var id: String; var target: AgentRuntimeTarget; var name: String; var roots: [String] }
struct AgentThread: Codable, Equatable, Sendable {
  enum State: String, Codable, Sendable { case unknown, idle, running, waitingForApproval = "waiting-for-approval", failed }
  var id: String; var target: AgentRuntimeTarget; var projectId: String?; var title: String; var state: State
}
struct AgentResource: Codable, Equatable, Sendable { var id: String; var target: AgentRuntimeTarget; var threadId: String; var name: String; var mediaType: String; var sizeBytes: Int; var sha256: String }
struct AgentMessage: Codable, Equatable, Sendable { enum Role: String, Codable, Sendable { case user, assistant, tool }; var id: String; var role: Role; var parts: [AgentContentPart]; var status: String }
struct AgentApproval: Codable, Equatable, Sendable {
  struct Question: Codable, Equatable, Sendable { struct Option: Codable, Equatable, Sendable { var label: String; var description: String }; var id: String; var question: String; var options: [Option]; var multiple: Bool?; var custom: Bool?; var isSecret: Bool? }
  enum Kind: String, Codable, Sendable { case permission, question }
  var id: String; var target: AgentRuntimeTarget; var threadId: String; var runId: String; var kind: Kind; var title: String; var detail: String; var decisions: [String]; var questions: [Question]
}
struct AgentOperationReceipt: Codable, Equatable, Sendable {
  enum State: String, Codable, Sendable { case received, submitting, completed, failed, uncertain }
  var operationId: String; var target: AgentRuntimeTarget; var threadId: String?; var state: State; var result: AgentJSONValue?; var error: String?
}
struct AgentEventCursor: Codable, Equatable, Sendable { var target: AgentRuntimeTarget; var threadId: String; var epoch: String; var sequence: Int }
struct AgentConnectionState: Codable, Equatable, Sendable {
  enum Phase: String, Codable, Sendable { case disconnected, connecting, connected, recovering }
  var target: AgentRuntimeTarget; var phase: Phase; var transportId: String?; var processHealthy: Bool; var runtimeConnected: Bool; var historyReadable: Bool
}

struct AgentGenerationSettings: Codable, Equatable, Sendable {
  enum Mode: String, Codable, Sendable { case normal, plan }
  var model: String?; var effort: String?; var permissionMode: String?; var mode: Mode?
}
struct AgentModel: Codable, Equatable, Sendable { var id: String; var name: String; var isDefault: Bool; var supportedEfforts: [String] }
struct AgentPlan: Codable, Equatable, Sendable {
  struct Step: Codable, Equatable, Sendable { var title: String; var status: String }
  var id: String; var version: Int; var runId: String; var status: String; var text: String; var steps: [Step]
}
enum AgentSendMode: String, Codable, Sendable { case nextTurn = "next-turn", queue, steer }
enum AgentOperation: Codable, Equatable, Sendable {
  case createThread(projectId: String?), sendMessage(threadId: String, parts: [AgentContentPart], mode: AgentSendMode? = nil, expectedRunId: String? = nil, settings: AgentGenerationSettings? = nil), stopRun(threadId: String, runId: String), respondApproval(threadId: String, approvalId: String, decision: String), answerQuestion(threadId: String, approvalId: String, answers: [String: [String]]), readResource(threadId: String, resourceId: String), operationStatus(threadId: String, operationId: String)
  case listModels, approvePlan(threadId: String, planId: String, version: Int, settings: AgentGenerationSettings?), revisePlan(threadId: String, planId: String, version: Int, parts: [AgentContentPart], settings: AgentGenerationSettings?), cancelQueue(threadId: String, messageId: String), executeQueue(threadId: String, messageId: String, expectedRunId: String? = nil)
  enum Keys: String, CodingKey { case operation, projectId, threadId, parts, runId, approvalId, decision, answers, resourceId, operationId, mode, expectedRunId, settings, planId, version, messageId }
  init(from decoder: Decoder) throws {
    let v = try decoder.container(keyedBy: Keys.self)
    switch try v.decode(String.self, forKey: .operation) {
    case "thread.create": self = .createThread(projectId: try v.decodeIfPresent(String.self, forKey: .projectId))
    case "message.send": self = .sendMessage(threadId: try v.decode(String.self, forKey: .threadId), parts: try v.decode([AgentContentPart].self, forKey: .parts), mode: try v.decodeIfPresent(AgentSendMode.self, forKey: .mode), expectedRunId: try v.decodeIfPresent(String.self, forKey: .expectedRunId), settings: try v.decodeIfPresent(AgentGenerationSettings.self, forKey: .settings))
    case "models.list": self = .listModels
    case "plan.approve": self = .approvePlan(threadId: try v.decode(String.self, forKey: .threadId), planId: try v.decode(String.self, forKey: .planId), version: try v.decode(Int.self, forKey: .version), settings: try v.decodeIfPresent(AgentGenerationSettings.self, forKey: .settings))
    case "plan.revise": self = .revisePlan(threadId: try v.decode(String.self, forKey: .threadId), planId: try v.decode(String.self, forKey: .planId), version: try v.decode(Int.self, forKey: .version), parts: try v.decode([AgentContentPart].self, forKey: .parts), settings: try v.decodeIfPresent(AgentGenerationSettings.self, forKey: .settings))
    case "queue.execute": self = .executeQueue(threadId: try v.decode(String.self, forKey: .threadId), messageId: try v.decode(String.self, forKey: .messageId), expectedRunId: try v.decodeIfPresent(String.self, forKey: .expectedRunId))
    case "queue.cancel": self = .cancelQueue(threadId: try v.decode(String.self, forKey: .threadId), messageId: try v.decode(String.self, forKey: .messageId))
    case "run.stop": self = .stopRun(threadId: try v.decode(String.self, forKey: .threadId), runId: try v.decode(String.self, forKey: .runId))
    case "approval.respond": self = .respondApproval(threadId: try v.decode(String.self, forKey: .threadId), approvalId: try v.decode(String.self, forKey: .approvalId), decision: try v.decode(String.self, forKey: .decision))
    case "question.answer": self = .answerQuestion(threadId: try v.decode(String.self, forKey: .threadId), approvalId: try v.decode(String.self, forKey: .approvalId), answers: try v.decode([String: [String]].self, forKey: .answers))
    case "resource.read": self = .readResource(threadId: try v.decode(String.self, forKey: .threadId), resourceId: try v.decode(String.self, forKey: .resourceId))
    case "operation.status": self = .operationStatus(threadId: try v.decode(String.self, forKey: .threadId), operationId: try v.decode(String.self, forKey: .operationId))
    default: throw DecodingError.dataCorruptedError(forKey: .operation, in: v, debugDescription: "Unregistered management operation")
    }
  }
  func encode(to encoder: Encoder) throws {
    var v = encoder.container(keyedBy: Keys.self)
    switch self {
    case .createThread(let project): try v.encode("thread.create", forKey: .operation); try v.encodeIfPresent(project, forKey: .projectId)
    case .sendMessage(let thread, let parts, let mode, let run, let settings): try v.encode("message.send", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(parts, forKey: .parts); try v.encodeIfPresent(mode, forKey: .mode); try v.encodeIfPresent(run, forKey: .expectedRunId); try v.encodeIfPresent(settings, forKey: .settings)
    case .listModels: try v.encode("models.list", forKey: .operation)
    case .approvePlan(let thread, let plan, let version, let settings): try v.encode("plan.approve", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(plan, forKey: .planId); try v.encode(version, forKey: .version); try v.encodeIfPresent(settings, forKey: .settings)
    case .revisePlan(let thread, let plan, let version, let parts, let settings): try v.encode("plan.revise", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(plan, forKey: .planId); try v.encode(version, forKey: .version); try v.encode(parts, forKey: .parts); try v.encodeIfPresent(settings, forKey: .settings)
    case .executeQueue(let thread, let message, let run): try v.encode("queue.execute", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(message, forKey: .messageId); try v.encodeIfPresent(run, forKey: .expectedRunId)
    case .cancelQueue(let thread, let message): try v.encode("queue.cancel", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(message, forKey: .messageId)
    case .stopRun(let thread, let run): try v.encode("run.stop", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(run, forKey: .runId)
    case .respondApproval(let thread, let approval, let decision): try v.encode("approval.respond", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(approval, forKey: .approvalId); try v.encode(decision, forKey: .decision)
    case .answerQuestion(let thread, let approval, let answers): try v.encode("question.answer", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(approval, forKey: .approvalId); try v.encode(answers, forKey: .answers)
    case .readResource(let thread, let resource): try v.encode("resource.read", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(resource, forKey: .resourceId)
    case .operationStatus(let thread, let operation): try v.encode("operation.status", forKey: .operation); try v.encode(thread, forKey: .threadId); try v.encode(operation, forKey: .operationId)
    }
  }
}
struct AgentCommand: Codable, Equatable, Sendable {
  enum Version: Int, Codable, Sendable { case v1 = 1 }
  var version: Version = .v1; var target: AgentRuntimeTarget; var operationId: String; var command: AgentOperation
}

enum AgentDomainCodec {
  // Explicit registrations prevent mixing management v1 with a provider's wire DTOs.
  private static let definitions: [ObjectIdentifier: String] = [
    ObjectIdentifier(AgentConnection.self): "connection", ObjectIdentifier(AgentRuntimeTarget.self): "target",
    ObjectIdentifier(AgentConnectionCandidate.self): "candidate", ObjectIdentifier(AgentContentPart.self): "part",
    ObjectIdentifier(AgentMessage.self): "message", ObjectIdentifier(AgentOperationReceipt.self): "receipt",
    ObjectIdentifier(AgentEventCursor.self): "cursor", ObjectIdentifier(AgentProject.self): "project",
    ObjectIdentifier(AgentThread.self): "thread", ObjectIdentifier(AgentResource.self): "resource",
    ObjectIdentifier(AgentApproval.self): "approval", ObjectIdentifier(AgentConnectionState.self): "connectionState",
    ObjectIdentifier(AgentGenerationSettings.self): "generationSettings", ObjectIdentifier(AgentModel.self): "model",
    ObjectIdentifier(AgentPlan.self): "plan", ObjectIdentifier(AgentCommand.self): "command",
  ]
  static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
    guard let definition = definitions[ObjectIdentifier(type)], let json = String(data: data, encoding: .utf8),
      DomainContract.shared.validate(json: json, definition: definition) == nil else {
      throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Invalid management v1 contract"))
    }
    return try JSONDecoder().decode(type, from: data)
  }
}

/// Product operations. Providers own wire, authentication and target mapping.
protocol AgentClient: Sendable {
  func voiceReadiness(_ client: Client) async throws -> VoiceTranscriptionReadiness
  func transcribeVoice(_ client: Client, recording: VoiceRecording, threadId: String?, draftId: String) async throws -> String
  func permissionOptions(_ client: Client) -> [AgentPermissionOption]
  func registerNotifications(_ client: Client, registration: TaskNotificationRegistration) async throws -> TaskNotificationStatus
  func unregisterNotifications(_ client: Client, deviceId: String) async throws
  func notificationEvents(_ client: Client, deviceId: String, after: String) async throws -> TaskNotificationFeed
  func updateNotificationPreferences(_ client: Client, deviceId: String, target: AgentRuntimeTarget, preferences: TaskNotificationPreferences) async throws -> TaskNotificationStatus
  #if DEBUG
  func devReplay(_ client: Client, operation: AgentDEVReplayOperation) async throws -> [String]
  #endif
  func normalizedCapabilities(_ capabilities: [String], connectionMode: String?) -> [String]
  func connectionTitle(_ client: Client) async -> String?
  func close(_ client: Client) async
  func workspace(_ client: Client, projectCursor: String?, threadCursor: String?) async throws -> WorkspaceSnapshot
  func history(_ client: Client, threadId: String, cursor: String?) async throws -> HistorySnapshot
  func nextTurnSuggestion(_ client: Client, scope: DesktopSuggestionScope) async throws -> DesktopSuggestionResponse
  func acknowledgeRead(_ client: Client, threadId: String, readerId: String, position: ReadPosition) async throws -> ReadAcknowledgement
  func send(_ client: Client, reply: PendingReply) async throws -> DeliveryReceipt
  func receipt(_ client: Client, id: String) async throws -> DeliveryReceipt
  func replyDeliveryStatus(_ client: Client, reply: PendingReply, target: AgentRuntimeTarget) async throws -> ReplyDeliveryInspection
  func sendCapturedReply(_ client: Client, reply: PendingReply, target: AgentRuntimeTarget) async throws -> DeliveryReceipt
  func events(_ client: Client, epoch: String, after: Int, threadId: String) async throws -> EventBatch
  func createThread(_ client: Client, projectId: String?, operationId: String) async throws -> Conversation
  func stop(_ client: Client, threadId: String, turnId: String) async throws -> CommandResult
  func cancelQueue(_ client: Client, threadId: String, messageId: String) async throws -> CommandResult
  func executeQueue(_ client: Client, threadId: String, messageId: String, expectedTurnId: String?) async throws -> CommandResult
  func approve(_ client: Client, approval: Approval, decision: String, answers: [String: [String]]) async throws -> CommandResult
  func manageWorkspace(_ client: Client, request: WorkspaceManagementRequest) async throws -> WorkspaceManagementOperation
  func workspaceManagementStatus(_ client: Client, request: WorkspaceManagementRequest) async throws -> WorkspaceManagementOperation
  func createProject(_ client: Client, name: String, directory: String, operationId: String) async throws -> WorkspaceSnapshot
  func upload(_ client: Client, threadId: String, file: LocalAttachment, data: Data) async throws -> RemoteAttachment
  func download(_ client: Client, file: RemoteAttachment) async throws -> URL
  func fileTransfers(_ client: Client) async throws -> ComputerFileCatalog
  func prepareFileTransfer(_ client: Client, file: ComputerFileManifest) async throws -> ComputerFileReceipt
  func fileTransfer(_ client: Client, id: String) async throws -> ComputerFileReceipt
  func uploadFileTransfer(_ client: Client, file: ComputerFileManifest, data: Data) async throws -> ComputerFileReceipt
  func models(_ client: Client) async throws -> ModelList
  func openThread(_ client: Client, threadId: String, operationId: String) async throws -> DesktopOpenOperation
  func openStatus(_ client: Client, threadId: String, operationId: String) async throws -> DesktopOpenOperation
  func chatAction(_ client: Client, request: ChatActionRequest) async throws -> ChatActionReceipt
  func chatActionStatus(_ client: Client, threadId: String, operationId: String) async throws -> ChatActionReceipt
  func searchHistory(_ client: Client, threadId: String, cursor: String?) async throws -> HistorySnapshot
  func forkOptions(_ client: Client, threadId: String) async throws -> ThreadForkOptions
  func forkThread(_ client: Client, request: ThreadForkRequest) async throws -> ThreadForkOperation
  func forkStatus(_ client: Client, threadId: String, operationId: String) async throws -> ThreadForkOperation
  func prepareCreation(_ client: Client, pending: PendingDesktopCreation) async throws -> DesktopCreationOperation
  func creation(_ client: Client, id: String) async throws -> DesktopCreationOperation
  func creationReceipt(_ client: Client, id: String) async throws -> DesktopCreationOperation
  func submitCreation(_ client: Client, id: String) async throws -> DesktopCreationOperation
  func detachCreation(_ client: Client, id: String) async throws -> DesktopCreationOperation
  func uploadCreation(_ client: Client, id: String, file: LocalAttachment, data: Data) async throws
}
extension AgentClient {
  func nextTurnSuggestion(_ client: Client, scope: DesktopSuggestionScope) async throws -> DesktopSuggestionResponse { throw HostServiceError.unavailable }
  func chatAction(_ client: Client, request: ChatActionRequest) async throws -> ChatActionReceipt { throw HostServiceError.unavailable }
  func chatActionStatus(_ client: Client, threadId: String, operationId: String) async throws -> ChatActionReceipt { throw HostServiceError.unavailable }
  func searchHistory(_ client: Client, threadId: String, cursor: String?) async throws -> HistorySnapshot { try await history(client, threadId: threadId, cursor: cursor) }
  func manageWorkspace(_ client: Client, request: WorkspaceManagementRequest) async throws -> WorkspaceManagementOperation { throw HostServiceError.unavailable }
  func workspaceManagementStatus(_ client: Client, request: WorkspaceManagementRequest) async throws -> WorkspaceManagementOperation { throw HostServiceError.unavailable }

  func replyDeliveryStatus(_ client: Client, reply: PendingReply, target: AgentRuntimeTarget) async throws -> ReplyDeliveryInspection { throw HostServiceError.unavailable }
  func sendCapturedReply(_ client: Client, reply: PendingReply, target: AgentRuntimeTarget) async throws -> DeliveryReceipt {
    // Other adapters retain their own full account/session semantics. No Codex
    // checkpoint is translated into a broker retry permission.
    guard client.agentConnection?.target == target else { throw HostServiceError.wrongHost }
    return try await send(client, reply: reply)
  }
  func voiceReadiness(_ client: Client) async throws -> VoiceTranscriptionReadiness { throw HostServiceError.message(L10n.tr("此电脑尚未支持录音转写，请更新电脑连接程序。")) }
  func transcribeVoice(_ client: Client, recording: VoiceRecording, threadId: String?, draftId: String) async throws -> String { throw HostServiceError.unavailable }
  func fileTransfers(_ client: Client) async throws -> ComputerFileCatalog { throw HostServiceError.message(L10n.tr("此连接尚未支持文件互传，请更新电脑连接程序。")) }
  func prepareFileTransfer(_ client: Client, file: ComputerFileManifest) async throws -> ComputerFileReceipt { throw HostServiceError.unavailable }
  func fileTransfer(_ client: Client, id: String) async throws -> ComputerFileReceipt { throw HostServiceError.unavailable }
  func uploadFileTransfer(_ client: Client, file: ComputerFileManifest, data: Data) async throws -> ComputerFileReceipt { throw HostServiceError.unavailable }
  func acknowledgeRead(_ client: Client, threadId: String, readerId: String, position: ReadPosition) async throws -> ReadAcknowledgement { throw HostServiceError.unavailable }
  func permissionOptions(_ client: Client) -> [AgentPermissionOption] { PermissionOption.allCases.map { AgentPermissionOption(id: $0.rawValue, title: $0.title, detail: $0.detail, icon: $0.icon, highPrivilege: $0 == .full) } }
  func registerNotifications(_ client: Client, registration: TaskNotificationRegistration) async throws -> TaskNotificationStatus { throw HostServiceError.message(L10n.tr("此连接尚未支持通知订阅")) }
  func unregisterNotifications(_ client: Client, deviceId: String) async throws { throw HostServiceError.unavailable }
  func notificationEvents(_ client: Client, deviceId: String, after: String) async throws -> TaskNotificationFeed { throw HostServiceError.unavailable }
  func updateNotificationPreferences(_ client: Client, deviceId: String, target: AgentRuntimeTarget, preferences: TaskNotificationPreferences) async throws -> TaskNotificationStatus { throw HostServiceError.unavailable }
  #if DEBUG
  func devReplay(_ client: Client, operation: AgentDEVReplayOperation) async throws -> [String] { throw HostServiceError.unavailable }
  #endif
  func normalizedCapabilities(_ capabilities: [String], connectionMode: String?) -> [String] { capabilities }
  func connectionTitle(_ client: Client) async -> String? { nil }
  func close(_ client: Client) async {}
  func models(_ client: Client) async throws -> ModelList { throw HostServiceError.message(L10n.tr("此客户端未开放模型设置")) }
  func executeQueue(_ client: Client, threadId: String, messageId: String, expectedTurnId: String?) async throws -> CommandResult { throw HostServiceError.message(L10n.tr("此客户端未开放立即执行队列")) }
  func cancelQueue(_ client: Client, threadId: String, messageId: String) async throws -> CommandResult { throw HostServiceError.message(L10n.tr("此客户端未开放取消队列")) }
  func createProject(_ client: Client, name: String, directory: String, operationId: String) async throws -> WorkspaceSnapshot { throw HostServiceError.message(L10n.tr("请先在桌面授权项目目录")) }
  func openThread(_ client: Client, threadId: String, operationId: String) async throws -> DesktopOpenOperation { throw HostServiceError.unavailable }
  func openStatus(_ client: Client, threadId: String, operationId: String) async throws -> DesktopOpenOperation { throw HostServiceError.unavailable }
  func forkOptions(_ client: Client, threadId: String) async throws -> ThreadForkOptions { throw HostServiceError.message(L10n.tr("此客户端尚未支持对话分支")) }
  func forkThread(_ client: Client, request: ThreadForkRequest) async throws -> ThreadForkOperation { throw HostServiceError.unavailable }
  func forkStatus(_ client: Client, threadId: String, operationId: String) async throws -> ThreadForkOperation { throw HostServiceError.unavailable }
  func prepareCreation(_ client: Client, pending: PendingDesktopCreation) async throws -> DesktopCreationOperation { throw HostServiceError.unavailable }
  func creation(_ client: Client, id: String) async throws -> DesktopCreationOperation { throw HostServiceError.unavailable }
  func creationReceipt(_ client: Client, id: String) async throws -> DesktopCreationOperation { throw HostServiceError.unavailable }
  func submitCreation(_ client: Client, id: String) async throws -> DesktopCreationOperation { throw HostServiceError.unavailable }
  func detachCreation(_ client: Client, id: String) async throws -> DesktopCreationOperation { throw HostServiceError.unavailable }
  func uploadCreation(_ client: Client, id: String, file: LocalAttachment, data: Data) async throws { throw HostServiceError.unavailable }
}
#if DEBUG
enum AgentDEVReplayOperation: String, Sendable { case create, reply }
#endif
struct CodexAgentClient: AgentClient {
  func chatAction(_ client: Client, request: ChatActionRequest) async throws -> ChatActionReceipt {
    guard request.hostId == client.id else { throw HostServiceError.wrongHost }
    return try await HostService.load(HostService.request(try HostService.url(client, "v1/threads/actions"), token: CredentialVault.token(client.id), method: "POST", body: JSONEncoder().encode(request)))
  }
  func chatActionStatus(_ client: Client, threadId: String, operationId: String) async throws -> ChatActionReceipt {
    try await HostService.load(HostService.request(try HostService.url(client, "v1/threads/actions", ["threadId": threadId, "id": operationId]), token: CredentialVault.token(client.id)))
  }
  func searchHistory(_ client: Client, threadId: String, cursor: String?) async throws -> HistorySnapshot {
    var query = ["threadId": threadId]; if let cursor { query["cursor"] = cursor }
    return try await HostService.load(HostService.request(try HostService.url(client, "v1/threads/search-history", query), token: CredentialVault.token(client.id)))
  }
  func manageWorkspace(_ client: Client, request: WorkspaceManagementRequest) async throws -> WorkspaceManagementOperation {
    try await HostService.load(HostService.request(try HostService.url(client, "v1/workspace-management"),
      token: CredentialVault.token(client.id), method: "POST", body: try JSONEncoder().encode(request)))
  }
  func workspaceManagementStatus(_ client: Client, request: WorkspaceManagementRequest) async throws -> WorkspaceManagementOperation {
    let target = String(data: try JSONEncoder().encode(request.target), encoding: .utf8)!
    return try await HostService.load(HostService.request(try HostService.url(client, "v1/workspace-management", ["id": request.clientOperationId, "target": target]), token: CredentialVault.token(client.id)))
  }

  func voiceReadiness(_ client: Client) async throws -> VoiceTranscriptionReadiness {
    let value: VoiceTranscriptionReadiness = try await HostService.load(HostService.request(try HostService.url(client, "v1/voice/readiness"), token: CredentialVault.token(client.id)))
    guard value.hostId == client.id else { throw HostServiceError.wrongHost }
    return value
  }
  func transcribeVoice(_ client: Client, recording: VoiceRecording, threadId: String?, draftId: String) async throws -> String {
    let manifest = VoiceTranscriptionRequest(hostId: client.id, threadId: threadId, draftId: draftId,
      operationId: recording.operationId, mediaType: "audio/wav", sizeBytes: recording.data.count,
      sha256: recording.sha256, durationMs: recording.durationMs)
    func validate(_ receipt: VoiceTranscriptionReceipt) throws {
      guard try receipt.matches(manifest) else { throw HostServiceError.message(L10n.tr("转写回执与原录音或目标不一致")) }
    }
    func status() async throws -> VoiceTranscriptionReceipt {
      try await HostService.load(HostService.request(try HostService.url(client, "v1/voice/transcriptions", ["id": manifest.operationId]), token: CredentialVault.token(client.id)))
    }
    var receipt: VoiceTranscriptionReceipt
    do { receipt = try await status() }
    catch HostServiceError.rejected(404, _) {
      // Prepare stores metadata only. If the response is lost, query the same ID on explicit retry.
      receipt = try await HostService.load(HostService.request(try HostService.url(client, "v1/voice/transcriptions"), token: CredentialVault.token(client.id), method: "POST", body: JSONEncoder().encode(manifest)))
    }
    try validate(receipt)
    if receipt.phase == "awaiting-audio" {
      var request = HostService.request(try HostService.url(client, "v1/voice/transcriptions/audio", ["id": manifest.operationId]), token: CredentialVault.token(client.id), method: "POST", body: recording.data)
      request.setValue("audio/wav", forHTTPHeaderField: "Content-Type")
      receipt = try await HostService.load(request)
      try validate(receipt)
    }
    let deadline = Date().addingTimeInterval(100)
    while receipt.phase == "transcribing", Date() < deadline {
      try await Task.sleep(for: .milliseconds(700))
      try Task.checkCancellation()
      receipt = try await status()
      try validate(receipt)
    }
    if receipt.phase == "completed", let text = receipt.text { return text }
    throw HostServiceError.message(receipt.error ?? L10n.tr("转写仍在处理中，录音已保留；点击重试查询原结果。"))
  }
  func fileTransfers(_ client: Client) async throws -> ComputerFileCatalog {
    do {
      let value: ComputerFileCatalog = try await HostService.load(HostService.request(try HostService.url(client, "v1/file-transfers"), token: CredentialVault.token(client.id)))
      guard value.hostId == client.id else { throw HostServiceError.wrongHost }
      return value
    } catch HostServiceError.rejected(404, _) { throw HostServiceError.message(L10n.tr("此电脑尚未支持文件互传，请更新电脑连接程序。")) }
  }
  func prepareFileTransfer(_ client: Client, file: ComputerFileManifest) async throws -> ComputerFileReceipt {
    guard file.hostId == client.id else { throw HostServiceError.wrongHost }
    let body = try JSONSerialization.jsonObject(with: JSONEncoder().encode(file)) as! [String: Any]
    let value: ComputerFileReceipt = try await HostService.post(client, "v1/file-transfers", body)
    try value.validate(file)
    return value
  }
  func fileTransfer(_ client: Client, id: String) async throws -> ComputerFileReceipt {
    let value: ComputerFileReceipt = try await HostService.load(HostService.request(try HostService.url(client, "v1/file-transfers", ["id": id]), token: CredentialVault.token(client.id)))
    guard value.hostId == client.id, value.id == id else { throw HostServiceError.wrongHost }
    return value
  }
  func uploadFileTransfer(_ client: Client, file: ComputerFileManifest, data: Data) async throws -> ComputerFileReceipt {
    guard file.hostId == client.id, data.count == file.sizeBytes, HostService.hash(data) == file.sha256 else { throw HostServiceError.invalidResponse }
    var request = HostService.request(try HostService.url(client, "v1/file-transfers/content", ["id": file.id]), token: CredentialVault.token(client.id), method: "POST", body: data)
    request.setValue(file.mediaType, forHTTPHeaderField: "Content-Type")
    let value: ComputerFileReceipt = try await HostService.load(request)
    try value.validate(file)
    guard value.phase == "completed" else { throw HostServiceError.invalidResponse }
    return value
  }
  func registerNotifications(_ client: Client, registration: TaskNotificationRegistration) async throws -> TaskNotificationStatus {
    let value = try JSONSerialization.jsonObject(with: JSONEncoder().encode(registration))
    return try await HostService.post(client, "v1/notifications/register", ["registration": value])
  }
  func unregisterNotifications(_ client: Client, deviceId: String) async throws {
    let _: TaskNotificationStatus = try await HostService.post(client, "v1/notifications/unregister", ["deviceId": deviceId])
  }
  func notificationEvents(_ client: Client, deviceId: String, after: String) async throws -> TaskNotificationFeed {
    try await HostService.load(HostService.request(try HostService.url(client, "v1/notifications/events", ["deviceId": deviceId, "after": after]), token: CredentialVault.token(client.id)))
  }
  func updateNotificationPreferences(_ client: Client, deviceId: String, target: AgentRuntimeTarget, preferences: TaskNotificationPreferences) async throws -> TaskNotificationStatus {
    let prefs = try JSONSerialization.jsonObject(with: JSONEncoder().encode(preferences))
    let identity = try JSONSerialization.jsonObject(with: JSONEncoder().encode(target))
    return try await HostService.post(client, "v1/notifications/preferences", ["deviceId": deviceId, "target": identity, "preferences": prefs])
  }
  // Apply the same domain requirements to catalog and event updates. They must
  // not disappear when a raw Host capability list replaces a cached catalog.
  func normalizedCapabilities(_ capabilities: [String], connectionMode: String?) -> [String] {
    return SessionPolicies.shared.codexCapabilities(capabilities: capabilities, connectionMode: connectionMode)
  }
  func workspace(_ client: Client, projectCursor: String?, threadCursor: String?) async throws -> WorkspaceSnapshot {
    var snapshot = try await HostService.workspace(client.endpoint ?? "", token: CredentialVault.token(client.id), projectCursor: projectCursor, threadCursor: threadCursor, readerId: client.readingScopeId)
    if let direct = client.codexDirect, snapshot.readTarget != direct.target { throw HostServiceError.wrongHost }
    snapshot.capabilities = normalizedCapabilities(snapshot.capabilities ?? [], connectionMode: snapshot.connectionMode)
    return snapshot
  }
  func history(_ client: Client, threadId: String, cursor: String?) async throws -> HistorySnapshot { try await HostService.history(client, threadId: threadId, cursor: cursor) }
  func nextTurnSuggestion(_ client: Client, scope: DesktopSuggestionScope) async throws -> DesktopSuggestionResponse {
    try await HostService.nextTurnSuggestion(client, scope: scope)
  }
  func acknowledgeRead(_ client: Client, threadId: String, readerId: String, position: ReadPosition) async throws -> ReadAcknowledgement {
    let value = try JSONSerialization.jsonObject(with: JSONEncoder().encode(position))
    guard let target = client.readingTarget else { throw HostServiceError.wrongHost }
    let identity = try JSONSerialization.jsonObject(with: JSONEncoder().encode(target))
    return try await HostService.post(client, "v1/threads/read", ["threadId": threadId, "readerId": readerId, "position": value, "target": identity])
  }
  func send(_ client: Client, reply: PendingReply) async throws -> DeliveryReceipt { try await HostService.send(client, reply) }
  func receipt(_ client: Client, id: String) async throws -> DeliveryReceipt { try await HostService.receipt(client, id: id) }
  func replyDeliveryStatus(_ client: Client, reply: PendingReply, target: AgentRuntimeTarget) async throws -> ReplyDeliveryInspection {
    // Legacy connections can query their validated reading target as well.
    // Retry authority still requires a pinned direct connection in SessionStore.
    guard (client.codexDirect?.target ?? client.readingTarget) == target else { throw HostServiceError.wrongHost }
    return try await HostService.replyDeliveryStatus(client, reply: reply, target: target)
  }
  func sendCapturedReply(_ client: Client, reply: PendingReply, target: AgentRuntimeTarget) async throws -> DeliveryReceipt {
    guard client.codexDirect?.target == target else { throw HostServiceError.wrongHost }
    return try await HostService.send(client, reply, target: target)
  }
  func events(_ client: Client, epoch: String, after: Int, threadId: String) async throws -> EventBatch { try await HostService.events(client, epoch: epoch, after: after, threadId: threadId) }
  func createThread(_ client: Client, projectId: String?, operationId: String) async throws -> Conversation { try await HostService.post(client, "v1/threads", ["projectId": projectId as Any? ?? NSNull(), "clientOperationId": operationId]) }
  func stop(_ client: Client, threadId: String, turnId: String) async throws -> CommandResult { try await HostService.post(client, "v1/stop", ["threadId": threadId, "turnId": turnId]) }
  func executeQueue(_ client: Client, threadId: String, messageId: String, expectedTurnId: String?) async throws -> CommandResult {
    var body: [String: Any] = ["threadId": threadId, "clientMessageId": messageId]
    if let expectedTurnId { body["expectedTurnId"] = expectedTurnId }
    return try await HostService.post(client, "v1/queue/execute", body)
  }
  func cancelQueue(_ client: Client, threadId: String, messageId: String) async throws -> CommandResult { try await HostService.post(client, "v1/queue/cancel", ["threadId": threadId, "clientMessageId": messageId]) }
  func approve(_ client: Client, approval: Approval, decision: String, answers: [String: [String]]) async throws -> CommandResult { try await HostService.post(client, "v1/approvals", ["threadId": approval.threadId, "turnId": approval.turnId, "approvalId": approval.id, "decision": decision, "answers": answers]) }
  func createProject(_ client: Client, name: String, directory: String, operationId: String) async throws -> WorkspaceSnapshot { try await HostService.post(client, "v1/projects", ["name": name, "directory": directory, "clientOperationId": operationId]) }
  func upload(_ client: Client, threadId: String, file: LocalAttachment, data: Data) async throws -> RemoteAttachment { try await HostService.upload(client, threadId: threadId, file: file, data: data) }
  func download(_ client: Client, file: RemoteAttachment) async throws -> URL { try await HostService.download(client, file: file) }
  func models(_ client: Client) async throws -> ModelList { try await HostService.load(HostService.request(try HostService.url(client, "v1/models"), token: CredentialVault.token(client.id))) }
  func openThread(_ client: Client, threadId: String, operationId: String) async throws -> DesktopOpenOperation { try await HostService.post(client, "v1/desktop/open", ["threadId": threadId, "clientOperationId": operationId]) }
  func openStatus(_ client: Client, threadId: String, operationId: String) async throws -> DesktopOpenOperation { try await HostService.load(HostService.request(try HostService.url(client, "v1/desktop/open-status", ["threadId": threadId, "id": operationId]), token: CredentialVault.token(client.id))) }
  func forkOptions(_ client: Client, threadId: String) async throws -> ThreadForkOptions {
    try await HostService.load(HostService.request(try HostService.url(client, "v1/threads/fork-options", ["threadId": threadId]), token: CredentialVault.token(client.id)))
  }
  func forkThread(_ client: Client, request: ThreadForkRequest) async throws -> ThreadForkOperation {
    guard request.hostId == client.id else { throw HostServiceError.wrongHost }
    return try await HostService.post(client, "v1/threads/fork", ["threadId": request.threadId,
      "turnId": request.turnId, "clientOperationId": request.clientOperationId, "environment": request.environment.rawValue])
  }
  func forkStatus(_ client: Client, threadId: String, operationId: String) async throws -> ThreadForkOperation {
    try await HostService.load(HostService.request(try HostService.url(client, "v1/threads/fork", ["threadId": threadId, "id": operationId]), token: CredentialVault.token(client.id)))
  }
  func prepareCreation(_ client: Client, pending: PendingDesktopCreation) async throws -> DesktopCreationOperation { try await HostService.prepareCreation(client, pending: pending) }
  func creation(_ client: Client, id: String) async throws -> DesktopCreationOperation { try await HostService.creation(client, id: id) }
  func creationReceipt(_ client: Client, id: String) async throws -> DesktopCreationOperation { try await HostService.creationReceipt(client, id: id) }
  func submitCreation(_ client: Client, id: String) async throws -> DesktopCreationOperation { try await HostService.post(client, "v1/desktop/creations/submit", ["clientOperationId": id]) }
  func detachCreation(_ client: Client, id: String) async throws -> DesktopCreationOperation { try await HostService.post(client, "v1/desktop/creations/detach", ["clientOperationId": id, "acknowledgeUncertain": true]) }
  func uploadCreation(_ client: Client, id: String, file: LocalAttachment, data: Data) async throws { try await HostService.uploadCreation(client, id: id, file: file, data: data) }
}
/// Explicit registration keeps UI/state management independent of provider names.
@MainActor enum AgentClients {
  private static var providers: [String: any AgentClient] = ["codex": CodexAgentClient(), "easywork": EasyWorkAgentClient()]
  static func register(_ id: String, client: any AgentClient) { providers[id] = client }
  static func resolve(_ client: Client) throws -> any AgentClient {
    guard let provider = providers[client.agentConnection?.target.agentId ?? "codex"] else { throw HostServiceError.message(L10n.tr("此 Agent 客户端尚未注册")) }
    return provider
  }
}


extension AgentConnection {
  enum Keys: String, CodingKey { case target, candidates, preference, credentialRef }
  init(from decoder: Decoder) throws {
    try SharedMobileRules.validateDomain(decoder, definition: "connection")
    let values = try decoder.container(keyedBy: Keys.self)
    target = try values.decode(AgentRuntimeTarget.self, forKey: .target)
    candidates = try values.decode([AgentConnectionCandidate].self, forKey: .candidates)
    preference = try values.decode(AgentConnectionPreference.self, forKey: .preference)
    credentialRef = try values.decode(String.self, forKey: .credentialRef)
  }
}
