import CryptoKit
import Foundation
import Security

struct EasyWorkTarget: Codable, Equatable, Sendable {
  var account: AgentAccount
  var desktopDeviceId: String
  var workspaceId: String
  var runtimeId: String
  var dictionary: [String: Any] { (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(self))) as? [String: Any] ?? [:] }
}
struct EasyWorkBinding: Codable, Equatable, Sendable {
  var bindingId: String
  var account: AgentAccount
  var desktopDeviceId: String
  var mobileDeviceId: String
  var desktopPublicKey: String
  var mobilePublicKey: String
  var target: EasyWorkTarget
  var status: String
  var createdAt: String
  var confirmedAt: String?
  var deviceAuthorization: EasyWorkDeviceBindingAuthorization? = nil
}
struct EasyWorkDeviceBindingAuthorization: Codable, Equatable, Sendable {
  var `protocol`: String
  var grantId: String
  var keyVersion: Int
  var authKeyThumbprint: String
}
struct EasyWorkCredential: Codable {
  var binding: EasyWorkBinding
  var privateKey: String
  var relayBinding: EasyWorkBinding?
  var accessToken: String?
  var grantRef: String? = nil
}
struct EasyWorkIdentity: Codable {
  var deviceId: String
  var privateKey: String
  var publicKey: String
  private static let creationLock = NSLock()
  static func load() throws -> EasyWorkIdentity {
    creationLock.lock(); defer { creationLock.unlock() }
    let saved = CredentialVault.token("easywork-device-identity")
    if let data = saved.data(using: .utf8), !saved.isEmpty {
      let value = try JSONDecoder().decode(Self.self, from: data)
      guard let privateData = Data(base64URL: value.privateKey), let publicData = Data(base64URL: value.publicKey), privateData.count == 32, publicData.count == 32,
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateData).publicKey.rawRepresentation == publicData else { throw HostServiceError.invalidResponse }
      return value
    }
    let key = Curve25519.KeyAgreement.PrivateKey()
    let value = Self(deviceId: "mobile-" + UUID().uuidString, privateKey: key.rawRepresentation.base64URL, publicKey: key.publicKey.rawRepresentation.base64URL)
    try CredentialVault.set(String(decoding: JSONEncoder().encode(value), as: UTF8.self), id: "easywork-device-identity")
    return value
  }
}
extension Data {
  var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
  init?(base64URL: String) { var value = base64URL.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/"); value += String(repeating: "=", count: (4 - value.count % 4) % 4); self.init(base64Encoded: value) }
}
func easyWorkJSON(_ value: Any) throws -> Data { try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .withoutEscapingSlashes, .fragmentsAllowed]) }

/// RFC 9449 signing is separate from the content encryption identity.
final class EasyWorkDPoP: @unchecked Sendable {
  private let key: SecKey
  let jwk: [String: String]
  private static let creationLock = NSLock()
  private init(key: SecKey) throws {
    self.key = key
    var error: Unmanaged<CFError>?
    guard let publicKey = SecKeyCopyPublicKey(key), let bytes = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?, bytes.count == 65, bytes.first == 4 else { throw HostServiceError.invalidResponse }
    jwk = ["kty": "EC", "crv": "P-256", "x": bytes.subdata(in: 1..<33).base64URL, "y": bytes.subdata(in: 33..<65).base64URL]
  }
  var thumbprint: String { get throws { Data(SHA256.hash(data: try easyWorkJSON(jwk))).base64URL } }
  static func load(_ identity: EasyWorkIdentity) throws -> EasyWorkDPoP {
    creationLock.lock(); defer { creationLock.unlock() }
    let tag = Data(("agent-hub-easywork-dpop-" + Data(SHA256.hash(data: Data(identity.deviceId.utf8))).base64URL).utf8)
    let query: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tag,
      kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeyClass as String: kSecAttrKeyClassPrivate, kSecReturnRef as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    if status == errSecSuccess, let item { return try EasyWorkDPoP(key: item as! SecKey) }
    guard status == errSecItemNotFound else { throw HostServiceError.message("设备签名密钥暂不可用（\(status)）") }
    var privateAttributes: [String: Any] = [kSecAttrIsPermanent as String: true, kSecAttrApplicationTag as String: tag]
    var attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits as String: 256]
    var error: Unmanaged<CFError>?
    #if targetEnvironment(simulator)
    privateAttributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    #else
    guard let control = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, &error) else { throw HostServiceError.invalidResponse }
    attributes[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
    privateAttributes[kSecAttrAccessControl as String] = control
    #endif
    attributes[kSecPrivateKeyAttrs as String] = privateAttributes
    guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else { throw HostServiceError.message("设备签名密钥创建失败") }
    return try EasyWorkDPoP(key: key)
  }
  func proof(method: String, url: URL, accessToken: String? = nil, nonce: String? = nil, body: Data? = nil) throws -> String {
    guard ["GET", "POST", "DELETE"].contains(method), var components = URLComponents(url: url, resolvingAgainstBaseURL: false), components.user == nil, components.password == nil,
      let scheme = components.scheme?.lowercased(), let host = components.host?.lowercased(), scheme == "https" || scheme == "http" && ["localhost", "127.0.0.1", "::1", "[::1]"].contains(host) else { throw HostServiceError.invalidResponse }
    components.scheme = scheme; components.host = host; components.query = nil; components.fragment = nil
    if scheme == "https" && components.port == 443 || scheme == "http" && components.port == 80 { components.port = nil }
    if components.percentEncodedPath.isEmpty { components.percentEncodedPath = "/" }
    guard let htu = components.url?.absoluteString else { throw HostServiceError.invalidResponse }
    var payload: [String: Any] = ["jti": UUID().uuidString.lowercased(), "htm": method, "htu": htu, "iat": Int(Date().timeIntervalSince1970)]
    if let accessToken {
      guard !accessToken.isEmpty, accessToken.utf8.allSatisfy({ (33...126).contains(Int($0)) }) else { throw HostServiceError.invalidResponse }
      payload["ath"] = Data(SHA256.hash(data: Data(accessToken.utf8))).base64URL
    }
    if let nonce {
      guard (1...512).contains(nonce.utf8.count), nonce.utf8.allSatisfy({ (33...126).contains(Int($0)) }) else { throw HostServiceError.invalidResponse }
      payload["nonce"] = nonce
    }
    if let body, !body.isEmpty { payload["bht"] = Data(SHA256.hash(data: body)).base64URL }
    let header: [String: Any] = ["typ": "dpop+jwt", "alg": "ES256", "jwk": jwk]
    let input = try easyWorkJSON(header).base64URL + "." + easyWorkJSON(payload).base64URL
    var error: Unmanaged<CFError>?
    guard let der = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, Data(input.utf8) as CFData, &error) as Data? else { throw HostServiceError.message("设备授权签名失败") }
    return input + "." + (try P256.Signing.ECDSASignature(derRepresentation: der)).rawRepresentation.base64URL
  }
}
func easyWorkObject(_ data: Data) throws -> [String: Any] { guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw HostServiceError.invalidResponse }; return result }

/// Only the specifically paired certificate may override platform trust for its endpoint.
final class PairedHostTrust: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  let fingerprint: String
  let host: String
  init(endpoint: String, fingerprint: String) { self.host = (URL(string: endpoint)?.host ?? "").lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".[]")); self.fingerprint = fingerprint.lowercased().replacingOccurrences(of: ":", with: "") }
  func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
    guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust, challenge.protectionSpace.host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".[]")) == host, let trust = challenge.protectionSpace.serverTrust, let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let cert = chain.first, HostService.hash(SecCertificateCopyData(cert) as Data) == fingerprint else { completionHandler(.cancelAuthenticationChallenge, nil); return }
    completionHandler(.useCredential, URLCredential(trust: trust))
  }
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
/// First connection is local-only; subsequent traffic uses the certificate saved automatically here.
final class DiscoveredHostTrust: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  private let host: String
  private let lock = NSLock()
  private var certificate: String?
  var fingerprint: String? { lock.withLock { certificate } }
  init(endpoint: URL) { host = Self.normalizedHost(endpoint.host ?? "") }
  private static func normalizedHost(_ host: String) -> String { host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ".[]")) }
  func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
    guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust, Self.normalizedHost(challenge.protectionSpace.host) == host, let trust = challenge.protectionSpace.serverTrust, let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let cert = chain.first else { completionHandler(.cancelAuthenticationChallenge, nil); return }
    let pin = HostService.hash(SecCertificateCopyData(cert) as Data)
    let accepted = lock.withLock { if let certificate { return certificate == pin }; certificate = pin; return true }
    completionHandler(accepted ? .useCredential : .cancelAuthenticationChallenge, accepted ? URLCredential(trust: trust) : nil)
  }
  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
protocol AgentTransport: AnyObject, Sendable {
  func send(_ frame: Data) async throws
  func receive() async throws -> Data
  func close() async
  func acknowledge(_ frame: Data) async throws
}
extension AgentTransport { func acknowledge(_ frame: Data) async throws {} }
actor LANAgentTransport: AgentTransport {
  private let session: URLSession
  private let socket: URLSessionWebSocketTask
  init(candidate: AgentConnectionCandidate, bindingId: String, headers: [String: String] = [:]) throws {
    guard let pin = candidate.peerFingerprint, pin.count == 64, var url = URLComponents(string: candidate.endpoint), url.scheme == "https" else { throw HostServiceError.invalidAddress }
    let delegate = PairedHostTrust(endpoint: candidate.endpoint, fingerprint: pin)
    session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
    url.scheme = "wss"; url.path = "/channel"; url.queryItems = [URLQueryItem(name: "bindingId", value: bindingId)]
    guard let endpoint = url.url else { throw HostServiceError.invalidAddress }
    var request = URLRequest(url: endpoint)
    for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
    socket = session.webSocketTask(with: request)
    // Foundation defaults to 1 MiB. Encrypted, base64-encoded history and tool
    // output can exceed that even within the Host's paginated message window.
    // Keep a finite receive bound; this does not relax the Host's input limit.
    socket.maximumMessageSize = 32 * 1024 * 1024
    socket.resume()
  }
  func send(_ frame: Data) async throws { try await socket.send(.string(String(decoding: frame, as: UTF8.self))) }
  func receive() async throws -> Data { switch try await socket.receive() { case .data(let data): return data; case .string(let value): return Data(value.utf8); @unknown default: throw HostServiceError.invalidResponse } }
  func close() async { socket.cancel(with: .goingAway, reason: nil); session.invalidateAndCancel() }
}
actor RelayAgentTransport: AgentTransport {
  let endpoint: String
  let token: String
  let bindingId: String?
  let grantRef: String?
  let binding: EasyWorkBinding?
  private let http = URLSession(configuration: .ephemeral, delegate: EasyWorkDeviceHTTPDelegate(), delegateQueue: nil)
  private var pending: [[String: Any]] = []
  private var acknowledgements: [String: [String: Any]] = [:]
  private var acknowledgementTask: Task<Void, Never>?
  private var acknowledgementFailure: Error?
  private var nextMailboxPollAt = ContinuousClock.now
  private var closed = false
  init(candidate: AgentConnectionCandidate, bindingId: String? = nil, token: String, grantRef: String? = nil, binding: EasyWorkBinding? = nil) throws {
    _ = try HostService.baseURL(candidate.endpoint); endpoint = candidate.endpoint; self.token = token; self.bindingId = bindingId; self.grantRef = grantRef; self.binding = binding
    guard grantRef == nil || binding?.bindingId == bindingId else { throw HostServiceError.invalidResponse }
  }
  nonisolated func api(_ path: String, method: String = "GET", body: Any? = nil) async throws -> Data { try await apiData(path, method: method, body: body.map(easyWorkJSON)) }
  private func apiData(_ path: String, method: String, body: Data?) async throws -> Data {
    try Task.checkCancellation()
    guard !closed else { throw CancellationError() }
    let url = try HostService.baseURL(endpoint).appendingPathComponent((grantRef == nil ? "v1" : "v2") + "/remote-access" + path)
    var request: URLRequest
    if let grantRef, let binding {
      guard ["/relay/envelopes", "/relay/pending", "/relay/acknowledgements", "/bindings"].contains(path) else { throw HostServiceError.invalidResponse }
      request = URLRequest(url: url); request.httpMethod = method; request.httpBody = body
      request.setValue("application/json", forHTTPHeaderField: "Accept")
      if body != nil { request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type") }
      for (key, value) in try await EasyWorkDeviceAuthorization.shared.headers(ref: grantRef, binding: binding, method: method, url: url, body: body) { request.setValue(value, forHTTPHeaderField: key) }
    } else { request = HostService.request(url, token: try await EasyWorkLogin.validToken(fallback: token), method: method, body: body) }
    request.timeoutInterval = 20
    // Credential refresh suspends this actor. A reconnect can close the route
    // during that suspension; Foundation must never receive a new task on an
    // invalidated URLSession.
    try Task.checkCancellation()
    guard !closed else { throw CancellationError() }
    let (data, response) = try await http.data(for: request)
    guard let http = response as? HTTPURLResponse else { throw HostServiceError.invalidResponse }
    if !(200..<300).contains(http.statusCode) {
      if grantRef != nil {
        let code = (try? easyWorkObject(Data(data.prefix(4096)))["error"] as? [String: Any])?["code"] as? String
        throw EasyWorkDeviceAuthorizationFailure(status: http.statusCode, code: code ?? "request_rejected")
      }
      throw HostServiceError.unavailable
    }
    return data.isEmpty ? Data("{}".utf8) : data
  }
  func send(_ frame: Data) async throws { _ = try await api("/relay/envelopes", method: "POST", body: easyWorkObject(frame)) }
  func receive() async throws -> Data {
    guard let bindingId else { throw HostServiceError.invalidResponse }
    while !closed {
      try Task.checkCancellation()
      if let acknowledgementFailure { throw acknowledgementFailure }
      if !pending.isEmpty {
        let value = pending.removeFirst()
        return try easyWorkJSON(value)
      }
      // Finish acknowledging this batch before asking for another one. Pulling
      // again while ACKs are queued repeatedly downloads the same envelopes and
      // can exhaust the Relay's per-device request budget during an active Run.
      if let acknowledgementTask { await acknowledgementTask.value }
      if let acknowledgementFailure { throw acknowledgementFailure }
      let delay = ContinuousClock.now.duration(to: nextMailboxPollAt)
      if delay > .zero { try await Task.sleep(for: delay) }
      try Task.checkCancellation()
      guard !closed else { throw CancellationError() }
      nextMailboxPollAt = ContinuousClock.now.advanced(by: .milliseconds(500))
      let mailbox = try easyWorkObject(await api("/relay/pending"))["envelopes"] as? [[String: Any]] ?? []
      // Other runtime targets share the device mailbox and have independent sessions.
      pending = mailbox.filter { $0["bindingId"] as? String == bindingId }
    }
    throw CancellationError()
  }
  func acknowledge(_ frame: Data) async throws {
    try Task.checkCancellation()
    guard !closed else { throw CancellationError() }
    if let acknowledgementFailure { throw acknowledgementFailure }
    let value = try easyWorkObject(frame).filter { ["bindingId", "senderDeviceId", "senderSessionId", "sequence"].contains($0.key) }
    guard value["bindingId"] as? String == bindingId else { throw HostServiceError.wrongHost }
    let key = try easyWorkJSON(value).base64URL
    acknowledgements[key] = value
    guard acknowledgements.count <= 1000 else { throw HostServiceError.unavailable }
    if acknowledgementTask == nil {
      acknowledgementTask = Task { await flushAcknowledgements() }
    }
  }
  private func flushAcknowledgements() async {
    defer { acknowledgementTask = nil }
    do {
      try await Task.sleep(for: .milliseconds(50))
      while !closed && !acknowledgements.isEmpty {
        let batch = Array(acknowledgements.prefix(1000))
        _ = try await api("/relay/acknowledgements", method: "POST", body: ["envelopes": batch.map(\.value)])
        for (key, _) in batch { acknowledgements.removeValue(forKey: key) }
      }
    } catch {
      // Undelivered acknowledgements remain in the Relay mailbox. Reconnect may
      // redeliver them; the session's authenticated sequence window deduplicates.
      if !closed && !Task.isCancelled { acknowledgementFailure = error }
    }
  }
  func close() async { guard !closed else { return }; closed = true; acknowledgementTask?.cancel(); acknowledgementTask = nil; acknowledgements.removeAll(); pending.removeAll(); http.invalidateAndCancel() }
}
struct EasyWorkEnvelopeCrypto {
  let binding: EasyWorkBinding
  let sessionId = UUID().uuidString
  let outbound: SymmetricKey
  let inbound: SymmetricKey
  let confirmation: String
  init(credential: EasyWorkCredential, binding: EasyWorkBinding) throws {
    guard binding.status == "active", let privateData = Data(base64URL: credential.privateKey), let publicData = Data(base64URL: binding.desktopPublicKey), let mobilePublicData = Data(base64URL: binding.mobilePublicKey) else { throw HostServiceError.invalidResponse }
    let privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateData)
    guard privateKey.publicKey.rawRepresentation == mobilePublicData else { throw HostServiceError.wrongHost }
    let secret = try privateKey.sharedSecretFromKeyAgreement(with: Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicData))
    let salt = Data("easyai-remote:\(binding.bindingId)".utf8)
    outbound = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: Data("v1:mobile-to-desktop".utf8), outputByteCount: 32)
    inbound = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: Data("v1:desktop-to-mobile".utf8), outputByteCount: 32)
    let confirmationKey = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(binding.bindingId.utf8), sharedInfo: Data("easyai-remote:key-confirmation:v1".utf8), outputByteCount: 16)
    confirmation = confirmationKey.withUnsafeBytes { Data($0).base64URL }; self.binding = binding
  }
  func header(_ envelope: [String: Any]) throws -> Data {
    var values: [Any] = [1, envelope["bindingId"]!, envelope["senderDeviceId"]!]
    if let session = envelope["senderSessionId"] { values.append(session) }
    values += [envelope["recipientDeviceId"]!, envelope["sequence"]!, envelope["sentAt"]!]
    return try easyWorkJSON(values)
  }
  func seal(_ payload: [String: Any], sequence: Int) throws -> Data {
    let date = ISO8601DateFormatter(); date.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    var envelope: [String: Any] = ["version": 1, "bindingId": binding.bindingId, "senderDeviceId": binding.mobileDeviceId, "senderSessionId": sessionId, "recipientDeviceId": binding.desktopDeviceId, "sequence": sequence, "sentAt": date.string(from: Date())]
    let sealed = try AES.GCM.seal(easyWorkJSON(payload), using: outbound, authenticating: header(envelope))
    envelope["nonce"] = sealed.nonce.withUnsafeBytes { Data($0).base64URL }
    envelope["ciphertext"] = (sealed.ciphertext + sealed.tag).base64URL
    return try easyWorkJSON(envelope)
  }
  func open(_ data: Data) throws -> ([String: Any], String) {
    let envelope = try easyWorkObject(data)
    guard envelope["version"] as? Int == 1, envelope["bindingId"] as? String == binding.bindingId, envelope["senderDeviceId"] as? String == binding.desktopDeviceId, envelope["recipientDeviceId"] as? String == binding.mobileDeviceId, let sequence = envelope["sequence"] as? Int, sequence > 0, let time = envelope["sentAt"] as? String, !time.isEmpty, let nonce = Data(base64URL: envelope["nonce"] as? String ?? ""), let bytes = Data(base64URL: envelope["ciphertext"] as? String ?? ""), bytes.count >= 16 else { throw HostServiceError.wrongHost }
    let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: bytes.dropLast(16), tag: bytes.suffix(16))
    let plain = try AES.GCM.open(box, using: inbound, authenticating: header(envelope))
    return (try easyWorkObject(plain), "\(envelope["senderSessionId"] as? String ?? "legacy"):\(sequence)")
  }
}
