import Foundation
import SwiftUI

enum EasyWorkDevPreview {
  struct Prepared { let receipt: Data; let request: URLRequest; let session: URLSession }
  static func prepare(channel: EasyWorkRemoteSession, configuration: DevPreviewConfiguration,
    id: String, url: String, port: UInt16, secret: String) async throws -> Prepared {
    let candidate = try await channel.devPreviewCandidate()
    let context = try easyWorkObject(await channel.request("runtime/context", [:]))
    guard (context["capabilities"] as? [String])?.contains("preview:http:v1") == true else {
      throw HostServiceError.message(L10n.tr("当前电脑连接尚不支持开发服务预览，请检查连接方式。"))
    }
    let response = try await channel.request("dev/preview/create", ["id": id, "threadId": configuration.threadId,
      "url": url, "browserPort": port, "browserSecret": secret], id: id)
    let value = try easyWorkObject(response)
    let actual = try JSONDecoder().decode(AgentRuntimeTarget.self, from: easyWorkJSON(value["target"] ?? [:]))
    guard actual == configuration.target, value["id"] as? String == id,
      value["threadId"] as? String == configuration.threadId else { throw HostServiceError.wrongHost }
    guard let ticket = value["token"] as? String,
      ticket.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
      let pin = candidate.peerFingerprint, pin.count == 64,
      var endpoint = URLComponents(string: candidate.endpoint), endpoint.scheme == "https" else { throw HostServiceError.invalidResponse }
    endpoint.scheme = "wss"; endpoint.path = "/v1/dev-previews/connect"; endpoint.fragment = nil
    let machine = ["agentId": "easywork", "hostId": configuration.target.hostId,
      "workspaceId": configuration.target.workspaceId, "runtimeId": configuration.target.runtimeId]
    endpoint.queryItems = [URLQueryItem(name: "hostId", value: configuration.target.hostId),
      URLQueryItem(name: "id", value: id), URLQueryItem(name: "target", value: String(decoding: try easyWorkJSON(machine), as: UTF8.self))]
    guard let address = endpoint.url else { throw HostServiceError.invalidAddress }
    var request = URLRequest(url: address); request.timeoutInterval = 30
    request.setValue("Bearer " + ticket, forHTTPHeaderField: "Authorization")
    let session = URLSession(configuration: .ephemeral,
      delegate: PairedHostTrust(endpoint: candidate.endpoint, fingerprint: pin), delegateQueue: nil)
    return Prepared(receipt: response, request: request, session: session)
  }
}

#if DEBUG
/// Uses the production pairing, identity, encryption and preview paths in an isolated test App.
struct EasyWorkDevPreviewAcceptanceView: View {
  static var enabled: Bool { ProcessInfo.processInfo.arguments.contains("-easywork-dev-preview-acceptance") }
  @State private var configuration: DevPreviewConfiguration?
  @State private var error: String?
  var body: some View {
    Group {
      if let configuration { DevServicePreview(configuration: configuration) }
      else if let error { Text(error).accessibilityIdentifier("dev-preview-error") }
      else { ProgressView("Pairing EasyWork") }
    }.task {
      do {
        guard let raw = ProcessInfo.processInfo.environment["EASYWORK_PREVIEW_CONFIG"] else { throw HostServiceError.invalidResponse }
        let value = try easyWorkObject(Data(raw.utf8))
        guard let pairingURI = value["pairingURI"] as? String, let threadId = value["threadId"] as? String,
          let url = value["url"] as? String else { throw HostServiceError.invalidResponse }
        let (binding, candidates) = try await EasyWorkPairing.lan(pairingURI, name: "F76 isolated native acceptance")
        let identity = try EasyWorkIdentity.load()
        let ref = "f76-isolated-" + UUID().uuidString
        let credential = EasyWorkCredential(binding: binding, privateKey: identity.privateKey)
        try CredentialVault.set(String(decoding: JSONEncoder().encode(credential), as: UTF8.self), id: ref)
        let target = AgentRuntimeTarget(agentId: "easywork", hostId: binding.desktopDeviceId,
          workspaceId: binding.target.workspaceId, runtimeId: binding.target.runtimeId, account: binding.account)
        let connection = AgentConnection(target: target, candidates: candidates, preference: .lanOnly, credentialRef: ref)
        let client = Client(id: ref, name: "EasyWork DEV", endpoint: candidates.first?.endpoint,
          isDemo: false, online: true, agentConnection: connection, readingTarget: target)
        configuration = DevPreviewConfiguration(endpoint: client.endpoint ?? "", token: "", target: target,
          threadId: threadId, url: url, computerName: client.name, client: client)
      } catch { self.error = error.localizedDescription }
    }
  }
}
#endif
