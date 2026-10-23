import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash, createPublicKey, randomBytes, randomUUID, verify } from 'node:crypto';
import { resolve, join } from 'node:path';

if (process.platform !== 'darwin') {
  console.log('Relay transport fixture requires macOS Swift; not verified here.');
  process.exit(0);
}
const directory = resolve('.local/easywork-relay-transport', randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
const source = await readFile('apps/ios/Sources/EasyWorkTransport.swift', 'utf8');
const authorizationSource = await readFile(
  'apps/ios/Sources/EasyWorkDeviceAuthorization.swift',
  'utf8',
);
const hostSource = await readFile('apps/ios/Sources/HostService.swift', 'utf8');
const modelsSource = await readFile('apps/ios/Sources/NotificationModels.swift', 'utf8');
function declaration(source, marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, marker);
  let depth = 0;
  for (let index = source.indexOf('{', start); index < source.length; index++) {
    if (source[index] === '{') depth++;
    if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
  }
  throw Error('Unclosed declaration: ' + marker);
}
const cryptoEnd = source.indexOf('/// Only the specifically paired certificate');
assert(cryptoEnd > 0);
const keyLookup = 'SecItemCopyMatching(query as CFDictionary, &item)';
const cryptoSource = source.slice(0, cryptoEnd);
assert.equal(cryptoSource.split(keyLookup).length, 2);
// Substitute only the OS key lookup, supplying an ephemeral software SecKey.
// Grant validation, DPoP signing and all Relay authorization checks stay real.
const fixtureCrypto = cryptoSource.replace(
  keyLookup,
  'fixtureSecItemCopyMatching(query as CFDictionary, &item)',
);
const start = source.indexOf('actor RelayAgentTransport: AgentTransport {');
const end = source.indexOf('\nstruct EasyWorkEnvelopeCrypto {', start);
assert(start >= 0 && end > start);
const sessionSource = await readFile('apps/ios/Sources/EasyWorkSession.swift', 'utf8');
const publishStart = sessionSource.indexOf('  private func publish(');
const publishEnd = sessionSource.indexOf('  private func pump(', publishStart);
assert(publishStart >= 0 && publishEnd > publishStart);
const publishGate = sessionSource.match(/ {2}private var publishingEpochs[^\n]+/);
assert(publishGate);
const acknowledged = new Set();
const polls = [];
const accessToken = randomBytes(32).toString('base64url');
const refreshToken = randomBytes(32).toString('base64url');
const deviceRequests = [];
const proofIds = new Set();
let signerThumbprint;
let bindingsRequests = 0;
let redirectedRequests = 0;
let serverFailure;
const digest = (value) => createHash('sha256').update(value).digest('base64url');
function checkProof(request, bytes) {
  assert.equal(request.headers.authorization, 'DPoP ' + accessToken);
  const parts = request.headers.dpop?.split('.');
  assert.equal(parts?.length, 3, 'Device-grant requests require a signed DPoP JWT');
  const [header, claims] = parts
    .slice(0, 2)
    .map((part) => JSON.parse(Buffer.from(part, 'base64url')));
  assert.equal(header.typ, 'dpop+jwt');
  assert.equal(header.alg, 'ES256');
  assert.deepEqual(Object.keys(header.jwk).sort(), ['crv', 'kty', 'x', 'y']);
  assert.equal(header.jwk.kty, 'EC');
  assert.equal(header.jwk.crv, 'P-256');
  assert(
    verify(
      'sha256',
      Buffer.from(parts.slice(0, 2).join('.')),
      {
        key: createPublicKey({ key: header.jwk, format: 'jwk' }),
        dsaEncoding: 'ieee-p1363',
      },
      Buffer.from(parts[2], 'base64url'),
    ),
    'The production signer must generate a valid ES256 signature',
  );
  const thumbprint = digest(JSON.stringify(Object.fromEntries(Object.entries(header.jwk).sort())));
  signerThumbprint ??= thumbprint;
  assert.equal(thumbprint, signerThumbprint, 'The grant stays bound to one signing key');
  assert.equal(claims.htm, request.method);
  assert.equal(claims.htu, `http://127.0.0.1:${server.address().port}${request.url}`);
  assert.equal(claims.ath, digest(accessToken));
  assert.equal(claims.bht, bytes.length ? digest(bytes) : undefined);
  assert(Number.isSafeInteger(claims.iat) && Math.abs(claims.iat - Date.now() / 1000) < 60);
  assert.equal(typeof claims.jti, 'string');
  assert(!proofIds.has(claims.jti), 'Each request needs a fresh proof ID');
  proofIds.add(claims.jti);
}
const server = createServer(async (request, response) => {
  try {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const bytes = Buffer.concat(chunks);
    if (request.url === '/redirect-target') {
      redirectedRequests++;
      response.writeHead(200).end('{}');
      return;
    }
    if (request.url.startsWith('/v2/remote-access/')) {
      checkProof(request, bytes);
      deviceRequests.push(request.method + ' ' + request.url);
      if (request.method === 'POST' && request.url.endsWith('/relay/envelopes')) {
        assert.deepEqual(JSON.parse(bytes), { bindingId: 'device-fixture', sequence: 1 });
        response.writeHead(200).end('{}');
      } else if (request.method === 'GET' && request.url.endsWith('/bindings')) {
        bindingsRequests++;
        if (bindingsRequests === 1) response.writeHead(200).end('{"bindings":[]}');
        else if (bindingsRequests === 2)
          response.writeHead(403).end('{"error":{"code":"grant_revoked"}}');
        else if (bindingsRequests === 3)
          response
            .writeHead(302, {
              Location: `http://127.0.0.1:${server.address().port}/redirect-target`,
            })
            .end('{}');
        else assert.fail('Unexpected device-grant request');
      } else assert.fail('A device grant reached a route outside this fixture allowlist');
      return;
    }
    assert.equal(request.headers.authorization, 'Bearer fixture');
    if (request.url.endsWith('/relay/acknowledgements')) {
      for (const envelope of JSON.parse(bytes).envelopes) acknowledged.add(envelope.sequence);
      response.writeHead(200).end('{}');
    } else if (request.url.endsWith('/relay/pending')) {
      polls.push(performance.now());
      // A real durable mailbox retains each envelope until its ACK is committed.
      const batch = acknowledged.size < 3 ? [1, 2, 3] : [4, 5];
      response.writeHead(200).end(
        JSON.stringify({
          envelopes: batch
            .filter((sequence) => !acknowledged.has(sequence))
            .map((sequence) => ({ bindingId: 'fixture', sequence })),
        }),
      );
    } else response.writeHead(404).end();
  } catch (error) {
    serverFailure ??= error;
    response.writeHead(500).end('{}');
  }
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
try {
  const swift = `import Foundation
import CryptoKit
import Security
struct AgentConnectionCandidate { let endpoint: String }
${declaration(modelsSource, 'struct AgentAccount:')}
enum HostServiceError: Error { case invalidAddress, invalidResponse, unavailable, wrongHost, message(String) }
enum HostService {
${declaration(hostSource, '  static func baseURL(')}
${declaration(hostSource, '  static func request(')}
${declaration(hostSource, '  static func hash(')}
}
// Only secret persistence is synthetic. Nothing reads the user's Keychain.
enum FixtureSecrets {
  static let lock = NSLock()
  static var values: [String: String] = [:]
  static var legacyCalls = 0, keyLookups = 0
  static let signingKey: SecKey = {
    var error: Unmanaged<CFError>?
    let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
      kSecAttrKeySizeInBits as String: 256,
      kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: false]]
    guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else { fatalError("Ephemeral fixture key unavailable") }
    return key
  }()
}
enum CredentialVault {
  static func token(_ id: String) -> String {
    FixtureSecrets.lock.lock(); defer { FixtureSecrets.lock.unlock() }
    return FixtureSecrets.values[id] ?? ""
  }
  static func set(_ value: String, id: String) throws {
    FixtureSecrets.lock.lock(); defer { FixtureSecrets.lock.unlock() }
    FixtureSecrets.values[id] = value
  }
}
func fixtureSecItemCopyMatching(_ query: CFDictionary, _ result: UnsafeMutablePointer<CFTypeRef?>?) -> OSStatus {
  let fields = query as NSDictionary
  guard let bytes = CredentialVault.token("easywork-device-identity").data(using: .utf8),
    let identity = try? JSONDecoder().decode(EasyWorkIdentity.self, from: bytes), let result,
    fields[kSecClass as String] as? String == kSecClassKey as String,
    fields[kSecAttrKeyType as String] as? String == kSecAttrKeyTypeECSECPrimeRandom as String,
    fields[kSecAttrKeyClass as String] as? String == kSecAttrKeyClassPrivate as String,
    fields[kSecReturnRef as String] as? Bool == true,
    fields[kSecAttrApplicationTag as String] as? Data == Data(("agent-hub-easywork-dpop-" + Data(SHA256.hash(data: Data(identity.deviceId.utf8))).base64URL).utf8)
    else { return errSecParam }
  FixtureSecrets.lock.lock(); FixtureSecrets.keyLookups += 1; FixtureSecrets.lock.unlock()
  result.pointee = FixtureSecrets.signingKey
  return errSecSuccess
}
enum EasyWorkLogin {
  static func validToken(fallback: String) async throws -> String {
    FixtureSecrets.lock.withLock { FixtureSecrets.legacyCalls += 1 }
    return fallback
  }
}
${fixtureCrypto}
${authorizationSource}
protocol AgentTransport: AnyObject, Sendable {
  func send(_ frame: Data) async throws
  func receive() async throws -> Data
  func acknowledge(_ frame: Data) async throws
  func close() async
}
${source.slice(start, end)}
struct FixtureCrypto {
  func seal(_ payload: [String: Any], sequence: Int) throws -> Data { try easyWorkJSON(["sequence": sequence]) }
}
actor PublishProbe: AgentTransport {
  var active = 0, maximumActive = 0
  var completed: [Int] = []
  func send(_ frame: Data) async throws {
    let sequence = try easyWorkObject(frame)["sequence"] as! Int
    active += 1; maximumActive = max(maximumActive, active)
    try await Task.sleep(for: .milliseconds(sequence == 1 ? 80 : 1))
    completed.append(sequence); active -= 1
  }
  func receive() async throws -> Data { throw HostServiceError.unavailable }
  func acknowledge(_ frame: Data) async throws {}
  func close() async {}
}
actor PublishFixture {
  private var transport: (any AgentTransport)?
  private var crypto: FixtureCrypto? = FixtureCrypto()
  private var sequence = 1
  private var epoch = "fixture", closed = false
${publishGate[0]}
  init(_ route: any AgentTransport) { transport = route }
${sessionSource.slice(publishStart, publishEnd)}
  func submit() async throws { try await publish([:]) }
}
@main struct Smoke {
  static func main() async throws {
    let probe = PublishProbe(), publisher: PublishFixture
    publisher = PublishFixture(probe)
    try await withThrowingTaskGroup(of: Void.self) { group in
      for _ in 0..<6 { group.addTask { try await publisher.submit() } }
      try await group.waitForAll()
    }
    guard await probe.maximumActive == 1, await probe.completed == Array(1...6) else { fatalError("Concurrent publishes reordered authenticated sequences") }
    let transport = try RelayAgentTransport(candidate: AgentConnectionCandidate(endpoint: "http://127.0.0.1:${server.address().port}"), bindingId: "fixture", token: "fixture")
    for expected in 1...5 {
      let frame = try await transport.receive()
      guard try easyWorkObject(frame)["sequence"] as? Int == expected else { fatalError("Repeated an unacknowledged batch") }
      try await transport.acknowledge(frame)
    }
    let waiting = Task { try await transport.receive() }
    try await Task.sleep(for: .milliseconds(1250))
    waiting.cancel()
    do { _ = try await waiting.value; fatalError("Unexpected envelope") } catch { if !waiting.isCancelled { throw error } }
    await transport.close()
    let legacyCalls = FixtureSecrets.lock.withLock { FixtureSecrets.legacyCalls }
    let identity = try EasyWorkIdentity.load(), signer = try EasyWorkDPoP.load(identity)
    let account = AgentAccount(authority: "https://fixture.invalid", subject: "synthetic-account")
    let target = EasyWorkTarget(account: account, desktopDeviceId: "synthetic-desktop", workspaceId: "synthetic-workspace", runtimeId: "synthetic-runtime")
    let binding = EasyWorkBinding(bindingId: "device-fixture", account: account, desktopDeviceId: target.desktopDeviceId,
      mobileDeviceId: identity.deviceId, desktopPublicKey: Curve25519.KeyAgreement.PrivateKey().publicKey.rawRepresentation.base64URL,
      mobilePublicKey: identity.publicKey, target: target, status: "active", createdAt: "2026-01-01T00:00:00Z", confirmedAt: nil,
      deviceAuthorization: EasyWorkDeviceBindingAuthorization(protocol: "EWDAP/1", grantId: "synthetic-grant", keyVersion: 1, authKeyThumbprint: try signer.thumbprint))
    let formatter = ISO8601DateFormatter()
    let grant = EasyWorkDeviceGrant(protocol: "EWDAP/1", deliveryId: "synthetic-delivery", grantId: "synthetic-grant", deviceId: identity.deviceId,
      binding: binding, target: target, keyVersion: 1, scopes: ["relay:read", "relay:write", "runtime:read", "runtime:control"],
      accessToken: ${JSON.stringify(accessToken)}, accessExpiresAt: formatter.string(from: Date().addingTimeInterval(600)),
      refreshToken: ${JSON.stringify(refreshToken)}, refreshExpiresAt: formatter.string(from: Date().addingTimeInterval(3600)))
    let endpoint = "http://127.0.0.1:${server.address().port}", ref = "synthetic-device-grant"
    func saveGrant(_ value: EasyWorkDeviceGrant, ref: String) throws {
      let saved = EasyWorkSavedGrant(origin: endpoint, grant: value, refreshOperation: nil)
      try CredentialVault.set(String(decoding: JSONEncoder().encode(saved), as: UTF8.self), id: ref)
    }
    func expectLocalRejection(_ action: () async throws -> Void) async throws {
      do { try await action(); fatalError("An invalid grant or route reached the network") }
      catch is HostServiceError {}
    }
    try saveGrant(grant, ref: ref)
    do {
      _ = try RelayAgentTransport(candidate: AgentConnectionCandidate(endpoint: endpoint), bindingId: binding.bindingId, token: "must-not-use-account-token", grantRef: ref)
      fatalError("A device grant without its binding was accepted")
    } catch HostServiceError.invalidResponse {}
    let device = try RelayAgentTransport(candidate: AgentConnectionCandidate(endpoint: endpoint), bindingId: binding.bindingId,
      token: "must-not-use-account-token", grantRef: ref, binding: binding)
    try await device.send(easyWorkJSON(["bindingId": binding.bindingId, "sequence": 1]))
    _ = try await device.api("/bindings")
    try await expectLocalRejection { _ = try await device.api("/arbitrary-method", method: "POST", body: [:]) }
    var wrongBinding = binding
    wrongBinding.target.workspaceId = "different-synthetic-workspace"
    let wrongTarget = try RelayAgentTransport(candidate: AgentConnectionCandidate(endpoint: endpoint), bindingId: binding.bindingId,
      token: "must-not-use-account-token", grantRef: ref, binding: wrongBinding)
    try await expectLocalRejection { _ = try await wrongTarget.api("/bindings") }
    await wrongTarget.close()
    var wrongKeyBinding = binding
    wrongKeyBinding.deviceAuthorization?.authKeyThumbprint = String(repeating: "x", count: 43)
    var wrongKeyGrant = try easyWorkObject(JSONEncoder().encode(grant))
    wrongKeyGrant["binding"] = try easyWorkObject(JSONEncoder().encode(wrongKeyBinding))
    try saveGrant(JSONDecoder().decode(EasyWorkDeviceGrant.self, from: easyWorkJSON(wrongKeyGrant)), ref: "synthetic-wrong-key-grant")
    let wrongKey = try RelayAgentTransport(candidate: AgentConnectionCandidate(endpoint: endpoint), bindingId: binding.bindingId,
      token: "must-not-use-account-token", grantRef: "synthetic-wrong-key-grant", binding: wrongKeyBinding)
    try await expectLocalRejection { _ = try await wrongKey.api("/bindings") }
    await wrongKey.close()
    var restricted = try easyWorkObject(JSONEncoder().encode(grant))
    restricted["scopes"] = ["relay:read", "relay:write", "runtime:read"]
    try saveGrant(JSONDecoder().decode(EasyWorkDeviceGrant.self, from: easyWorkJSON(restricted)), ref: "synthetic-restricted-grant")
    let missingScope = try RelayAgentTransport(candidate: AgentConnectionCandidate(endpoint: endpoint), bindingId: binding.bindingId,
      token: "must-not-use-account-token", grantRef: "synthetic-restricted-grant", binding: binding)
    try await expectLocalRejection { _ = try await missingScope.api("/bindings") }
    await missingScope.close()
    do { _ = try await device.api("/bindings"); fatalError("A revoked grant was accepted") }
    catch let failure as EasyWorkDeviceAuthorizationFailure {
      guard failure.status == 403, failure.code == "grant_revoked" else { throw failure }
    }
    do { _ = try await device.api("/bindings"); fatalError("A credential-bearing redirect was followed") }
    catch let failure as EasyWorkDeviceAuthorizationFailure {
      guard failure.status == 302, failure.code == "request_rejected" else { throw failure }
    }
    await device.close()
    do { _ = try await device.api("/bindings"); fatalError("A closed route reached the network") }
    catch is CancellationError {}
    guard FixtureSecrets.lock.withLock({ FixtureSecrets.legacyCalls }) == legacyCalls,
      FixtureSecrets.lock.withLock({ FixtureSecrets.keyLookups }) > 0 else { fatalError("Device grants fell back to account login or OS key storage") }
    print("Relay ACK and bounded polling passed; session publishes serialized")
    print("Device-grant validation, production DPoP signing, error mapping and redirect refusal passed; synthetic in-memory credentials only")
  }
}
`;
  const file = join(directory, 'fixture.swift');
  const binary = join(directory, 'fixture');
  await writeFile(file, swift, { mode: 0o600 });
  const run = promisify(execFile);
  const env = {
    ...process.env,
    DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer',
  };
  await run('swiftc', ['-parse-as-library', file, '-o', binary], {
    env,
    timeout: 60000,
    maxBuffer: 4 * 1024 * 1024,
  });
  const result = await run(binary, [], { env, timeout: 15000 });
  assert.ifError(serverFailure);
  assert.match(result.stdout, /bounded polling passed/);
  assert.match(result.stdout, /production DPoP signing/);
  assert.deepEqual([...acknowledged].sort(), [1, 2, 3, 4, 5]);
  assert(polls.length >= 4 && polls.length <= 5);
  for (let index = 1; index < polls.length; index++)
    assert(polls[index] - polls[index - 1] >= 450, 'Mailbox polling exceeded its budget');
  assert.deepEqual(deviceRequests, [
    'POST /v2/remote-access/relay/envelopes',
    'GET /v2/remote-access/bindings',
    'GET /v2/remote-access/bindings',
    'GET /v2/remote-access/bindings',
  ]);
  assert.equal(proofIds.size, 4);
  assert.equal(redirectedRequests, 0, 'The actual delegate must not forward device credentials');
  await writeFile(
    join(directory, 'result.json'),
    JSON.stringify(
      {
        kind: 'native-swift-relay-localhost-fixture',
        passed: true,
        sourceSHA256: {
          transport: createHash('sha256').update(source).digest('hex'),
          deviceAuthorization: createHash('sha256').update(authorizationSource).digest('hex'),
          session: createHash('sha256').update(sessionSource).digest('hex'),
          hostService: createHash('sha256').update(hostSource).digest('hex'),
          models: createHash('sha256').update(modelsSource).digest('hex'),
        },
        generatedSwiftSHA256: createHash('sha256').update(swift).digest('hex'),
        fixtureSHA256: createHash('sha256')
          .update(await readFile(new URL(import.meta.url)))
          .digest('hex'),
        acknowledged: [...acknowledged].sort(),
        mailboxPolls: polls.length,
        validDeviceProofs: proofIds.size,
        redirectedRequests,
        limitations: [
          'Synthetic grants and in-memory secret storage',
          'No real account login, Keychain, device pairing or refresh, physical phone or model',
        ],
      },
      null,
      2,
    ) + '\n',
    { mode: 0o600 },
  );
  console.log(
    'Native Swift Relay transport passed: retained batches ACK before repoll; idle reads bounded; concurrent session publishes preserve sequence; device-grant guards and ES256 DPoP verified on localhost, revoked errors preserved and redirects refused. Fixture evidence only.',
  );
} finally {
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
