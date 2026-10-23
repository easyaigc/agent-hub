import Foundation

struct TaskHubPairing: Decodable, Sendable {
  var version: Int
  var type: String
  var deploymentId: String
  var serverInstanceId: String
  var primaryHostId: String
  var name: String
  var endpoint: String
  var canonicalOrigin: String
  var fingerprint: String
  var registryKeySha256: String

  static func parse(_ text: String) throws -> Self {
    let data = Data(text.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
    guard data.count <= 16_384 else { throw TaskHubError.message(L10n.tr("任务服务二维码内容过长")) }
    let value = try JSONDecoder().decode(Self.self, from: data)
    let address = try EasyWorkPairing.localEndpoint(value.endpoint)
    let canonical = try EasyWorkPairing.localEndpoint(value.canonicalOrigin)
    guard value.version == 1, value.type == "agenthub-task", address == canonical,
      [value.deploymentId, value.serverInstanceId, value.primaryHostId].allSatisfy({ $0.range(of: "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$", options: .regularExpression) != nil }),
      value.fingerprint.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil,
      value.registryKeySha256.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil,
      !value.name.isEmpty, value.name.utf8.count <= 80 else { throw TaskHubError.message(L10n.tr("任务服务二维码不兼容或身份无效")) }
    return value
  }
  static func probe(_ text: String) async throws -> TaskHubBinding {
    let value = try parse(text)
    let binding = try await TaskHubClient.probe(endpoint: value.endpoint, pin: value.fingerprint)
    guard binding.service.deploymentId == value.deploymentId,
      binding.service.serverInstanceId == value.serverInstanceId,
      binding.service.primaryHostId == value.primaryHostId else { throw TaskHubError.changedIdentity }
    return binding
  }
}
