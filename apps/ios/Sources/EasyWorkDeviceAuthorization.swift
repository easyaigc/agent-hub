import CryptoKit
import Foundation

struct EasyWorkDeviceOffer: Sendable {
  let origin: String
  let code: String
  static func origin(_ value: String) throws -> String {
    guard var url = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)), let host = url.host?.lowercased(), url.user == nil, url.password == nil, url.query == nil, url.fragment == nil, url.path.isEmpty || url.path == "/" else { throw HostServiceError.invalidAddress }
    var valid = url.scheme == "https"
    #if DEBUG
    valid = valid || url.scheme == "http" && ["localhost", "127.0.0.1", "::1", "[::1]"].contains(host)
    #endif
    guard valid else { throw HostServiceError.message("设备授权需要 HTTPS") }
    url.host = host; url.path = ""
    if url.scheme == "https" && url.port == 443 || url.scheme == "http" && url.port == 80 { url.port = nil }
    guard let origin = url.url?.absoluteString else { throw HostServiceError.invalidAddress }
    return origin
  }
  static func code(_ value: String) throws -> String {
    let result = value.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "").uppercased()
    guard result.range(of: "^[0-9A-HJKMNP-TV-Z]{16}$", options: .regularExpression) != nil else { throw HostServiceError.message("请输入电脑显示的 16 位连接码") }
    return result
  }
  static func parse(_ value: String) throws -> Self {
    guard let url = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)), url.scheme == "easywork", url.host == "pair", url.path.isEmpty, url.fragment == nil, let items = url.queryItems,
      items.count == 3, Set(items.map(\.name)) == Set(["version", "relay", "code"]), items.first(where: { $0.name == "version" })?.value == "1", let relay = items.first(where: { $0.name == "relay" })?.value, let code = items.first(where: { $0.name == "code" })?.value else { throw HostServiceError.invalidAddress }
    let origin = try origin(relay)
    let endpoint = URLComponents(string: origin)!
    var trusted = endpoint.scheme == "https" && endpoint.host == "51easyai.com" && (endpoint.port == nil || endpoint.port == 443)
    #if DEBUG
    trusted = trusted || ["localhost", "127.0.0.1", "::1", "[::1]"].contains(endpoint.host ?? "")
    #endif
    guard trusted else { throw HostServiceError.message("二维码的中继服务未受信任，请核对电脑上的服务地址") }
    return Self(origin: origin, code: try Self.code(code))
  }
}

struct EasyWorkDeviceGrant: Codable, Sendable {
  let `protocol`: String
  let deliveryId: String
  let grantId: String
  let deviceId: String
  let binding: EasyWorkBinding
  let target: EasyWorkTarget
  let keyVersion: Int
  let scopes: [String]
  let accessToken: String
  let accessExpiresAt: String
  let refreshToken: String
  let refreshExpiresAt: String
  func verify(_ identity: EasyWorkIdentity, signer: EasyWorkDPoP, expected: EasyWorkBinding? = nil) throws {
    guard `protocol` == "EWDAP/1", deviceId == identity.deviceId, binding.status == "active", binding.mobileDeviceId == identity.deviceId, binding.mobilePublicKey == identity.publicKey,
      target == binding.target, binding.account == target.account, binding.desktopDeviceId == target.desktopDeviceId, Data(base64URL: binding.desktopPublicKey)?.count == 32,
      let metadata = binding.deviceAuthorization, metadata.protocol == "EWDAP/1", metadata.grantId == grantId, metadata.keyVersion == keyVersion, keyVersion > 0,
      metadata.authKeyThumbprint == (try signer.thumbprint), expected == nil || binding == expected,
      scopes.count == Set(scopes).count, Set(scopes).isSubset(of: Set(["relay:read", "relay:write", "runtime:read", "runtime:control", "files:read", "files:write"])), Set(["relay:read", "relay:write", "runtime:read", "runtime:control"]).isSubset(of: Set(scopes)),
      [accessToken, refreshToken].allSatisfy({ $0.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil }),
      try Self.date(accessExpiresAt) > Date(timeIntervalSince1970: 0), try Self.date(refreshExpiresAt) > Date(timeIntervalSince1970: 0) else { throw HostServiceError.message("设备授权目标或密钥不匹配，请重新配对") }
  }
  static func date(_ value: String) throws -> Date {
    let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = formatter.date(from: value) { return date }
    formatter.formatOptions = [.withInternetDateTime]
    guard let date = formatter.date(from: value) else { throw HostServiceError.invalidResponse }; return date
  }
}

private struct EasyWorkSavedClaim: Codable {
  let origin: String
  let body: String
  var claimId: String?
  var offerId: String?
  var pendingToken: String?
  var expiresAt: String?
  var verificationCode: String?
  var grantRef: String?
}
private struct EasyWorkSavedGrant: Codable {
  let origin: String
  var grant: EasyWorkDeviceGrant
  var refreshOperation: EasyWorkGrantRefresh?
}
private struct EasyWorkGrantRefresh: Codable { let operationId: String; let token: String }
final class EasyWorkDeviceHTTPDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

/// The phone holds scoped device credentials, never the Desktop's account login.
struct EasyWorkDeviceAuthorizationFailure: LocalizedError {
  let status: Int
  let code: String
  var errorDescription: String? {
    switch code {
    case "offer_not_found": return "连接码无效，请在电脑上重新生成"
    case "offer_unavailable", "claim_unavailable": return "连接码已使用，请在电脑上重新生成"
    case "grant_inactive", "binding_inactive", "grant_revoked": return "电脑已撤销设备授权，请重新配对"
    case "offer_expired", "refresh_expired": return "设备授权已过期，请在电脑上重新配对"
    case "unauthorized", "credential_rotated", "invalid_dpop": return "设备授权校验失败，请重新连接电脑"
    case "scope_denied", "feature_disabled": return "电脑尚未允许此设备操作"
    default: return "设备授权请求被拒绝（\(status)）"
    }
  }
}

actor EasyWorkDeviceAuthorization {
  static let shared = EasyWorkDeviceAuthorization()
  private var pairing = false
  private var refreshing: Set<String> = []
  private func read<T: Decodable>(_ key: String, as type: T.Type) throws -> T? {
    let value = CredentialVault.token(key); return value.isEmpty ? nil : try JSONDecoder().decode(type, from: Data(value.utf8))
  }
  private func write<T: Encodable>(_ key: String, _ value: T) throws { try CredentialVault.set(String(decoding: JSONEncoder().encode(value), as: UTF8.self), id: key) }
  private func request(origin: String, path: String, method: String, signer: EasyWorkDPoP, token: String? = nil, body: Data? = nil) async throws -> Data {
    guard let url = URL(string: try EasyWorkDeviceOffer.origin(origin) + "/v2/remote-access" + path) else { throw HostServiceError.invalidAddress }
    var request = URLRequest(url: url); request.httpMethod = method; request.httpBody = body; request.timeoutInterval = 40
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    if body != nil { request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type") }
    if let token { request.setValue("DPoP " + token, forHTTPHeaderField: "Authorization") }
    request.setValue(try signer.proof(method: method, url: url, accessToken: token, body: body), forHTTPHeaderField: "DPoP")
    let session = URLSession(configuration: .ephemeral, delegate: EasyWorkDeviceHTTPDelegate(), delegateQueue: nil)
    defer { session.invalidateAndCancel() }
    let (stream, response) = try await session.bytes(for: request)
    guard let http = response as? HTTPURLResponse else { throw HostServiceError.invalidResponse }
    var data = Data(); let limit = (200..<300).contains(http.statusCode) ? 65_536 : 4096
    for try await byte in stream { guard data.count < limit else { throw HostServiceError.invalidResponse }; data.append(byte) }
    guard (200..<300).contains(http.statusCode) else {
      let code = (try? easyWorkObject(data)["error"] as? [String: Any])?["code"] as? String
      throw EasyWorkDeviceAuthorizationFailure(status: http.statusCode, code: code ?? "request_rejected")
    }
    return data.isEmpty ? Data("{}".utf8) : data
  }
  func pair(_ offer: EasyWorkDeviceOffer, deviceName: String, onPending: @escaping @MainActor @Sendable (String) -> Void) async throws -> (EasyWorkBinding, String) {
    while pairing { try await Task.sleep(for: .milliseconds(100)) }
    pairing = true; defer { pairing = false }
    let identity = try EasyWorkIdentity.load(), signer = try EasyWorkDPoP.load(identity)
    let key = "easywork-device-claim:" + HostService.hash(Data((offer.origin + "\n" + (try signer.thumbprint) + "\n" + offer.code).utf8))
    var state: EasyWorkSavedClaim
    if let saved = try read(key, as: EasyWorkSavedClaim.self) { state = saved }
    else {
      let body = try easyWorkJSON(["operationId": UUID().uuidString.lowercased(), "connectionCode": offer.code, "device": ["deviceId": identity.deviceId, "platform": "ios", "name": String(deviceName.prefix(100)), "publicKey": identity.publicKey, "authPublicKey": signer.jwk]])
      state = EasyWorkSavedClaim(origin: offer.origin, body: String(decoding: body, as: UTF8.self)); try write(key, state)
    }
    guard state.origin == offer.origin else { throw HostServiceError.invalidResponse }
    if state.claimId == nil {
      let response = try easyWorkObject(await request(origin: offer.origin, path: "/pairing/claims", method: "POST", signer: signer, body: Data(state.body.utf8)))
      guard response["protocol"] as? String == "EWDAP/1", response["status"] as? String == "pending", let id = response["claimId"] as? String, UUID(uuidString: id) != nil,
        let sealed = response["sealedDelivery"] as? String, let offerId = response["offerId"] as? String, let expiry = response["expiresAt"] as? String else { throw HostServiceError.invalidResponse }
      let pending = try EasyWorkDeviceDelivery.open(identity: identity, delivery: sealed, expectedDeliveryId: id)
      guard pending["claimId"] as? String == id, let token = pending["pendingToken"] as? String, token.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil,
        let tokenExpiry = pending["expiresAt"] as? String, try EasyWorkDeviceGrant.date(tokenExpiry) > Date() else { throw HostServiceError.invalidResponse }
      state.claimId = id; state.offerId = offerId; state.pendingToken = token; state.expiresAt = expiry; state.verificationCode = response["verificationCode"] as? String; try write(key, state)
    }
    await onPending(state.verificationCode.map { "请在电脑上确认此设备，核对码：" + $0 } ?? "请在已登录的电脑上确认此设备")
    guard let id = state.claimId, let token = state.pendingToken else { throw HostServiceError.invalidResponse }
    let deadline = Date().addingTimeInterval(360)
    while Date() < deadline {
      try Task.checkCancellation()
      let response = try easyWorkObject(await request(origin: offer.origin, path: "/pairing/claims/" + id, method: "GET", signer: signer, token: token))
      guard response["protocol"] as? String == "EWDAP/1", response["claimId"] as? String == id, response["offerId"] as? String == state.offerId else { throw HostServiceError.invalidResponse }
      switch response["status"] as? String {
      case "approved":
        guard let sealed = response["sealedDelivery"] as? String else { throw HostServiceError.invalidResponse }
        let clear = try EasyWorkDeviceDelivery.open(identity: identity, delivery: sealed, expectedDeliveryId: id)
        let grant = try JSONDecoder().decode(EasyWorkDeviceGrant.self, from: easyWorkJSON(clear)); try grant.verify(identity, signer: signer)
        let ref = "easywork-device-grant:" + HostService.hash(Data((offer.origin + "\n" + grant.grantId).utf8))
        try write(ref, EasyWorkSavedGrant(origin: offer.origin, grant: grant)); state.grantRef = ref; try write(key, state)
        return (grant.binding, ref)
      case "pending":
        guard let expiry = response["expiresAt"] as? String, try EasyWorkDeviceGrant.date(expiry) > Date() else { throw HostServiceError.message("连接码已过期，请在电脑上重新生成") }
      case "rejected":
        try CredentialVault.set("", id: key); throw HostServiceError.message("电脑拒绝了此设备，请重新配对")
      case "expired", "revoked":
        try CredentialVault.set("", id: key); throw HostServiceError.message("设备授权已过期或撤销，请重新配对")
      default: throw HostServiceError.invalidResponse
      }
      try await Task.sleep(for: .seconds(1))
    }
    throw HostServiceError.message("等待电脑确认超时，可继续核对原配对请求")
  }
  func headers(ref: String, binding: EasyWorkBinding, method: String, url: URL, body: Data? = nil) async throws -> [String: String] {
    let token = try await validAccess(ref: ref, binding: binding), identity = try EasyWorkIdentity.load()
    return ["Authorization": "DPoP " + token, "DPoP": try EasyWorkDPoP.load(identity).proof(method: method, url: url, accessToken: token, body: body)]
  }
  private func validAccess(ref: String, binding: EasyWorkBinding) async throws -> String {
    while refreshing.contains(ref) { try await Task.sleep(for: .milliseconds(100)) }
    refreshing.insert(ref); defer { refreshing.remove(ref) }
    guard var saved = try read(ref, as: EasyWorkSavedGrant.self) else { throw HostServiceError.message("设备授权已移除，请重新配对") }
    let identity = try EasyWorkIdentity.load(), signer = try EasyWorkDPoP.load(identity)
    try saved.grant.verify(identity, signer: signer, expected: binding)
    guard try EasyWorkDeviceGrant.date(saved.grant.refreshExpiresAt) > Date() else { throw HostServiceError.message("设备授权已过期，请在电脑上重新配对") }
    if saved.refreshOperation == nil, try EasyWorkDeviceGrant.date(saved.grant.accessExpiresAt) > Date().addingTimeInterval(60) { return saved.grant.accessToken }
    if saved.refreshOperation == nil { saved.refreshOperation = EasyWorkGrantRefresh(operationId: UUID().uuidString.lowercased(), token: saved.grant.refreshToken); try write(ref, saved) }
    let operation = saved.refreshOperation!
    let response = try easyWorkObject(await request(origin: saved.origin, path: "/device-grants/refresh", method: "POST", signer: signer, token: operation.token, body: easyWorkJSON(["operationId": operation.operationId])))
    guard response["protocol"] as? String == "EWDAP/1", response["grantId"] as? String == saved.grant.grantId, let sealed = response["sealedDelivery"] as? String else { throw HostServiceError.invalidResponse }
    let clear = try EasyWorkDeviceDelivery.open(identity: identity, delivery: sealed, expectedDeliveryId: operation.operationId)
    let grant = try JSONDecoder().decode(EasyWorkDeviceGrant.self, from: easyWorkJSON(clear)); try grant.verify(identity, signer: signer, expected: binding)
    guard try EasyWorkDeviceGrant.date(grant.accessExpiresAt) > Date() else { throw HostServiceError.invalidResponse }
    saved.grant = grant; saved.refreshOperation = nil; try write(ref, saved)
    return grant.accessToken
  }
}

/// RFC 7518 Concat KDF is distinct from the binding's HKDF encryption.
enum EasyWorkDeviceDelivery {
  static func open(identity: EasyWorkIdentity, delivery: String, expectedDeliveryId: String) throws -> [String: Any] {
    guard (1...65_536).contains(delivery.utf8.count) else { throw HostServiceError.invalidResponse }
    let parts = delivery.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
    guard parts.count == 5, parts[1].isEmpty else { throw HostServiceError.invalidResponse }
    func bytes(_ value: String) throws -> Data {
      guard !value.isEmpty, value.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95 }), let data = Data(base64URL: value) else { throw HostServiceError.invalidResponse }
      return data
    }
    let header = try easyWorkObject(bytes(parts[0]))
    guard header["alg"] as? String == "ECDH-ES", header["enc"] as? String == "A256GCM", header["typ"] as? String == "ewdap+jwe", header["version"] as? Int == 1,
      header["deliveryId"] as? String == expectedDeliveryId, ["apu", "apv", "crit", "zip"].allSatisfy({ header[$0] == nil }),
      let epk = header["epk"] as? [String: Any], epk["kty"] as? String == "OKP", epk["crv"] as? String == "X25519", epk["d"] == nil, let encodedKey = epk["x"] as? String,
      let privateData = Data(base64URL: identity.privateKey), privateData.count == 32 else { throw HostServiceError.invalidResponse }
    let publicData = try bytes(encodedKey)
    guard publicData.count == 32 else { throw HostServiceError.invalidResponse }
    let privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateData)
    guard privateKey.publicKey.rawRepresentation.base64URL == identity.publicKey else { throw HostServiceError.invalidResponse }
    let secret = try privateKey.sharedSecretFromKeyAgreement(with: Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicData))
    func uint32(_ value: UInt32) -> Data { var value = value.bigEndian; return withUnsafeBytes(of: &value) { Data($0) } }
    let algorithm = Data("A256GCM".utf8)
    var input = secret.withUnsafeBytes { uint32(1) + Data($0) + uint32(UInt32(algorithm.count)) + algorithm + uint32(0) + uint32(0) + uint32(256) }
    defer { input.resetBytes(in: 0..<input.count) }
    let key = SymmetricKey(data: Data(SHA256.hash(data: input)))
    let nonce = try bytes(parts[2]), ciphertext = try bytes(parts[3]), tag = try bytes(parts[4])
    guard nonce.count == 12, tag.count == 16, ciphertext.count <= 32_768 else { throw HostServiceError.invalidResponse }
    let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: ciphertext, tag: tag)
    var clear = try AES.GCM.open(box, using: key, authenticating: Data(parts[0].utf8))
    defer { clear.resetBytes(in: 0..<clear.count) }
    let value = try easyWorkObject(clear)
    guard value["protocol"] as? String == "EWDAP/1", value["deliveryId"] as? String == expectedDeliveryId else { throw HostServiceError.invalidResponse }
    return value
  }
}
