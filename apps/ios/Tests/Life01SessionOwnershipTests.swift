// Synthetic keys, targets, frames and commands; no production service is contacted.
import Foundation
import XCTest
import CryptoKit

private actor Life01OwnershipPeer: AgentTransport {
  let binding: EasyWorkBinding
  let inbound: SymmetricKey
  let outbound: SymmetricKey
  let confirmation: String
  let session = UUID().uuidString
  var sequence = 1
  var frames: [Data] = []
  var heldFrames: [String:Data] = [:]
  var heldCallbacks: [String: CheckedContinuation<Void,Never>] = [:]
  var requests: [[String: Any]] = []
  var closedCount = 0
  var acknowledgements = 0
  var holdConfirmation = false
  func hold() { holdConfirmation = true }
  init(_ credential: EasyWorkCredential) throws {
    binding = credential.binding
    let privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(base64URL: credential.privateKey)!)
    let desktop = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: Data(base64URL: credential.binding.desktopPublicKey)!)
    let secret = try privateKey.sharedSecretFromKeyAgreement(with: desktop)
    let salt = Data("easyai-remote:\(binding.bindingId)".utf8)
    inbound = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: Data("v1:mobile-to-desktop".utf8), outputByteCount: 32)
    outbound = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: Data("v1:desktop-to-mobile".utf8), outputByteCount: 32)
    let key = secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(binding.bindingId.utf8), sharedInfo: Data("easyai-remote:key-confirmation:v1".utf8), outputByteCount: 16)
    confirmation = key.withUnsafeBytes { Data($0).base64URL }
  }
  func trace() -> [[String: Any]] { requests }
  func release(_ id: String) { if let frame=heldFrames.removeValue(forKey:id) { frames.append(frame) } }
  func releaseCallback(_ id:String) { heldCallbacks.removeValue(forKey:id)?.resume() }
  func header(_ e: [String: Any]) throws -> Data {
    try easyWorkJSON([1,e["bindingId"]!,e["senderDeviceId"]!,e["senderSessionId"]!,e["recipientDeviceId"]!,e["sequence"]!,e["sentAt"]!])
  }
  func send(_ frame: Data) async throws {
    let e = try easyWorkObject(frame)
    guard e["bindingId"] as? String == binding.bindingId, e["senderDeviceId"] as? String == binding.mobileDeviceId,
      e["recipientDeviceId"] as? String == binding.desktopDeviceId,
      let n = Data(base64URL: e["nonce"] as? String ?? ""), let c = Data(base64URL: e["ciphertext"] as? String ?? ""), c.count >= 16 else { throw HostServiceError.wrongHost }
    let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data:n), ciphertext:c.dropLast(16), tag:c.suffix(16))
    let payload = try easyWorkObject(AES.GCM.open(box, using: inbound, authenticating: header(e)))
    var answer: [String: Any]
    var requestID: String?
    if payload["kind"] as? String == "key-confirmation" {
      guard payload["value"] as? String == confirmation else { throw HostServiceError.wrongHost }
      if holdConfirmation { return }
      answer = ["kind":"key-confirmation", "value":confirmation]
    } else {
      guard let req = payload["envelope"] as? [String: Any], let target = req["target"] as? [String:Any],
        try JSONDecoder().decode(EasyWorkTarget.self, from: easyWorkJSON(target)) == binding.target,
        let method = req["method"] as? String, let id = req["requestId"] as? String else { throw HostServiceError.wrongHost }
      requests.append(req); requestID = id
      let result: Any = ["accepted": true]
      answer = ["kind":"protocol","envelope":["protocolVersion":2,"kind":"response","requestId":id,"target":binding.target.dictionary,"ok":true,"result":result]]
    }
    let date = ISO8601DateFormatter(); date.formatOptions = [.withInternetDateTime,.withFractionalSeconds]
    var response: [String: Any] = ["version":1,"bindingId":binding.bindingId,"senderDeviceId":binding.desktopDeviceId,"senderSessionId":session,"recipientDeviceId":binding.mobileDeviceId,"sequence":sequence,"sentAt":date.string(from:Date())]; sequence += 1
    let sealed = try AES.GCM.seal(easyWorkJSON(answer), using:outbound, authenticating:header(response))
    response["nonce"] = sealed.nonce.withUnsafeBytes { Data($0).base64URL }; response["ciphertext"] = (sealed.ciphertext + sealed.tag).base64URL
    let encoded = try easyWorkJSON(response)
    if let id=requestID, ["slow-read", "held-write", "cap-write"].contains(id) { heldFrames[id]=encoded } else { frames.append(encoded) }
    if requestID == "blocked-send" { try? await Task.sleep(for:.seconds(35)) }
    if let id=requestID, id.hasPrefix("cap-") && id != "cap-after-old" { await withCheckedContinuation { heldCallbacks[id]=$0 } }
  }
  func receive() async throws -> Data {
    while frames.isEmpty { try Task.checkCancellation(); try await Task.sleep(for:.milliseconds(10)) }
    return frames.removeFirst()
  }
  func acknowledge(_ data: Data) async throws { acknowledgements += 1 }
  func releaseAllCallbacks() { let callbacks=heldCallbacks.values; heldCallbacks.removeAll(); for callback in callbacks { callback.resume() } }
  func close() async { closedCount += 1; releaseAllCallbacks() }
}


private actor Life01OwnershipFactory {
  let old: Life01OwnershipPeer
  let fresh: Life01OwnershipPeer
  var calls = 0
  var release: CheckedContinuation<Void, Never>?
  init(_ old: Life01OwnershipPeer, _ fresh: Life01OwnershipPeer) { self.old = old; self.fresh = fresh }
  func make(_ options: AgentTransportOptions) async -> any AgentTransport {
    calls += 1
    if calls == 1 { await withCheckedContinuation { release = $0 }; return old }
    return fresh
  }
  func unblock() { release?.resume(); release = nil }
}

private actor Life01OwnershipSequenceFactory {
  let peers: [Life01OwnershipPeer]
  var calls = 0
  var starts: [ContinuousClock.Instant] = []
  init(_ peers: [Life01OwnershipPeer]) { self.peers = peers }
  func make(_ options: AgentTransportOptions) -> any AgentTransport {
    let index = min(calls,peers.count - 1); calls += 1; starts.append(.now); return peers[index]
  }
}

private actor Life01ReadOutcome {
  enum Value: Equatable { case pending, timedOut, failed(String), succeeded }
  private(set) var value: Value = .pending
  func record(_ value: Value) { self.value = value }
}

final class Life01SessionOwnershipTests: XCTestCase {
  private func fixture() throws -> (EasyWorkCredential, AgentConnection) {
    let account = AgentAccount(authority:"https://acceptance.example",subject:"synthetic-user")
    let target = EasyWorkTarget(account:account,desktopDeviceId:"desktop-vector",workspaceId:"workspace-vector",runtimeId:"runtime-vector")
    let binding = EasyWorkBinding(bindingId:"binding-vector",account:account,desktopDeviceId:"desktop-vector",mobileDeviceId:"mobile-vector",desktopPublicKey:"3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08",mobilePublicKey:"hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo",target:target,status:"active",createdAt:"2026-10-14T00:00:00Z",confirmedAt:nil)
    let credential = EasyWorkCredential(binding:binding,privateKey:"dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo",relayBinding:nil,accessToken:nil)
    let domain = AgentRuntimeTarget(agentId:"easywork",hostId:target.desktopDeviceId,workspaceId:target.workspaceId,runtimeId:target.runtimeId,account:account)
    let candidates = (0..<4).map { AgentConnectionCandidate(id:"fixture-\($0)",transportId:"life01-synthetic",endpoint:"wss://fixture-\($0).invalid",peerFingerprint:nil) }
    return (credential,AgentConnection(target:domain,candidates:candidates,preference:.automatic,credentialRef:"t14-synthetic"))
  }
  private func waitForFactory(_ gate: Life01OwnershipFactory) async throws {
    for _ in 0..<200 { if await gate.calls > 0 { return }; try await Task.sleep(for:.milliseconds(10)) }
    XCTFail("factory was not reached")
    throw HostServiceError.unavailable
  }
  private func cleanup(_ session: EasyWorkRemoteSession, peers: [Life01OwnershipPeer], gate: Life01OwnershipFactory? = nil) {
    addTeardownBlock {
      if let gate { await gate.unblock() }
      await session.close()
      for peer in peers { await peer.releaseAllCallbacks() }
      await AgentTransportRegistry.shared.register("life01-synthetic") { _ in throw HostServiceError.unavailable }
    }
  }
  func testCancelledReadDoesNotCancelReadOrWrite() async throws {
    let (credential,connection) = try fixture()
    let peer = try Life01OwnershipPeer(credential), gate = Life01OwnershipFactory(peer,peer)
    await AgentTransportRegistry.shared.register("life01-synthetic") { options in await gate.make(options) }
    let session = try EasyWorkRemoteSession(connection:connection,credential:credential)
    cleanup(session,peers:[peer],gate:gate)
    let first = Task { try await session.request("project/list",[:],id:"read-first") }
    try await waitForFactory(gate)
    let second = Task { try await session.request("project/list",[:],id:"read-second") }
    let write = Task { try await session.request("turn/cancel",["threadId":"thread-t14","runId":"turn-t14"],id:"cancel-t14") }
    try await Task.sleep(for:.milliseconds(200)); first.cancel(); await gate.unblock()
    do { _ = try await first.value; XCTFail("first read should cancel") }
    catch is CancellationError { }
    catch { XCTFail("expected CancellationError, got \(error)") }
    _ = try await second.value; _ = try await write.value
    let requests = await peer.trace()
    let writes = requests.filter { $0["method"] as? String == "turn/cancel" }
    XCTAssertEqual(writes.count,1)
    let request = try XCTUnwrap(writes.first)
    XCTAssertEqual(request["requestId"] as? String,"cancel-t14")
    XCTAssertEqual(try easyWorkJSON(try XCTUnwrap(request["target"] as? [String:Any])),try easyWorkJSON(credential.binding.target.dictionary))
    XCTAssertEqual(try easyWorkJSON(try XCTUnwrap(request["params"] as? [String:Any])),try easyWorkJSON(["threadId":"thread-t14","runId":"turn-t14"]))
    XCTAssertEqual(requests.filter { $0["requestId"] as? String == "read-second" }.count,1)
    await session.close()
  }
  func testDeadlineAndLateFactoryCannotReplaceNewOwner() async throws {
    let (credential,connection) = try fixture()
    let old = try Life01OwnershipPeer(credential), fresh = try Life01OwnershipPeer(credential)
    let gate = Life01OwnershipFactory(old,fresh)
    await AgentTransportRegistry.shared.register("life01-synthetic") { options in await gate.make(options) }
    let session = try EasyWorkRemoteSession(connection:connection,credential:credential)
    cleanup(session,peers:[old,fresh],gate:gate)
    let started = ContinuousClock.now
    let first = Task { try await session.request("project/list",[:],id:"deadline-old") }
    try await waitForFactory(gate)
    do { _ = try await first.value; XCTFail("stalled factory must expire") } catch {}
    let elapsed = started.duration(to:.now)
    XCTAssertGreaterThanOrEqual(elapsed,.seconds(29)); XCTAssertLessThan(elapsed,.seconds(36))
    _ = try await session.request("project/list",[:],id:"fresh-before")
    let epoch = await session.epoch
    await gate.unblock()
    try await Task.sleep(for:.milliseconds(300))
    let actualEpoch = await session.epoch
    let oldCloseCount = await old.closedCount
    XCTAssertEqual(actualEpoch,epoch)
    XCTAssertEqual(oldCloseCount,1)
    _ = try await session.request("project/list",[:],id:"fresh-after")
    let requests = await fresh.trace()
    XCTAssertEqual(requests.filter { $0["requestId"] as? String == "fresh-after" }.count,1)
    await session.close()
  }
  func testFourCandidateHandshakeDeadlinesShareThirtySeconds() async throws {
    let (credential,connection) = try fixture()
    let peers = try (0..<4).map { _ in try Life01OwnershipPeer(credential) }
    for peer in peers { await peer.hold() }
    let factory = Life01OwnershipSequenceFactory(peers)
    await AgentTransportRegistry.shared.register("life01-synthetic") { options in await factory.make(options) }
    let session = try EasyWorkRemoteSession(connection:connection,credential:credential)
    cleanup(session,peers:peers)
    let started = ContinuousClock.now
    do { _ = try await session.request("project/list",[:],id:"four-candidate"); XCTFail("handshakes must expire") } catch {}
    let elapsed = started.duration(to:.now)
    let calls = await factory.calls
    XCTAssertGreaterThanOrEqual(elapsed,.seconds(29)); XCTAssertLessThan(elapsed,.seconds(36))
    XCTAssertEqual(calls,4)
    let starts = await factory.starts
    if starts.count == 4 {
      for i in 1..<4 {
        let span = starts[i-1].duration(to:starts[i])
        XCTAssertGreaterThanOrEqual(span,.seconds(7)); XCTAssertLessThan(span,.seconds(10))
      }
    }
    try await Task.sleep(for:.milliseconds(300))
    for peer in peers { let closes = await peer.closedCount; XCTAssertGreaterThanOrEqual(closes,1) }
    await session.close()
  }

  func testSlowReadDeadlineDoesNotCloseHealthySharedRoute() async throws {
    let (credential,connection)=try fixture();let peer=try Life01OwnershipPeer(credential)
    await AgentTransportRegistry.shared.register("life01-synthetic") { _ in peer }
    let session=try EasyWorkRemoteSession(connection:connection,credential:credential)
    cleanup(session,peers:[peer]);let outcome=Life01ReadOutcome()
    let start=ContinuousClock.now
    let slow=Task { do { _ = try await session.request("project/list",[:],id:"slow-read");await outcome.record(.succeeded) }
      catch let error as URLError where error.code == .timedOut { await outcome.record(.timedOut) }
      catch { await outcome.record(.failed(String(describing:error))) } }
    var slowSeen=false
    for _ in 0..<500 { if await peer.trace().contains(where:{$0["requestId"] as? String == "slow-read"}) { slowSeen=true;break };try await Task.sleep(for:.milliseconds(10)) }
    XCTAssertTrue(slowSeen,"slow request must actually reach peer")
    for n in 0..<4 { try await Task.sleep(for:.seconds(5)); _ = try await session.request("project/list",[:],id:"heartbeat-\(n)") }
    let write=Task { try await session.request("turn/cancel",["threadId":"thread-t14","runId":"turn-t14"],id:"held-write") }
    var writeSeen=false
    for _ in 0..<300 { if await peer.trace().contains(where:{$0["requestId"] as? String == "held-write"}) { writeSeen=true;break };try await Task.sleep(for:.milliseconds(10)) }
    XCTAssertTrue(writeSeen,"captured write must actually reach peer")
    try await Task.sleep(for:.seconds(12));let readOutcome=await outcome.value
    XCTAssertEqual(readOutcome,.timedOut,"slow read must independently finish with URLError.timedOut")
    let elapsed=start.duration(to:.now);XCTAssertGreaterThanOrEqual(elapsed,.seconds(31));XCTAssertLessThan(elapsed,.seconds(36))
    let closed=await peer.closedCount;print("LIFE01_T14_SLOW elapsed=\(elapsed) closes=\(closed) requests=\(await peer.trace().count)");XCTAssertEqual(closed,0,"single slow read must not close healthy shared route")
    await peer.release("held-write")
    do { _ = try await write.value } catch { XCTFail("in-flight original write must survive slow read: \(error)") }
    do { _ = try await session.request("project/list",[:],id:"after-deadline") } catch { XCTFail("other read must survive slow read: \(error)") }
    let requests=await peer.trace();XCTAssertEqual(requests.filter{$0["requestId"] as? String == "held-write"}.count,1)
    slow.cancel(); await session.close()
  }
  func testVerifiedResponseBeforeBlockedSendDoesNotCloseRoute() async throws {
    let (credential,connection)=try fixture();let peer=try Life01OwnershipPeer(credential)
    await AgentTransportRegistry.shared.register("life01-synthetic") { _ in peer }
    let session=try EasyWorkRemoteSession(connection:connection,credential:credential);cleanup(session,peers:[peer]);let start=ContinuousClock.now
    let waiting=Task { try await session.request("project/list",[:],id:"blocked-send") }
    try await Task.sleep(for:.seconds(2));let ack=await peer.acknowledgements
    print("LIFE01_T14_SEND_ACK_BEFORE_DEADLINE=\(ack)");XCTAssertGreaterThanOrEqual(ack,2,"pump must acknowledge the authenticated response while send remains suspended")
    do { _ = try await waiting.value } catch { XCTFail("verified response must remain available: \(error)") }
    let elapsed=start.duration(to:.now);XCTAssertLessThan(elapsed,.seconds(34),"verified response must end a blocked send within budget")
    let closed=await peer.closedCount;print("LIFE01_T14_SEND elapsed=\(elapsed) closes=\(closed)");XCTAssertEqual(closed,0,"verified response must not close healthy route")
    await session.close()
  }

  func testRetiredReadCapacityAndOldOwnerCannotUnlockNewWrite() async throws {
    let (credential,connection)=try fixture();let peer=try Life01OwnershipPeer(credential)
    await AgentTransportRegistry.shared.register("life01-synthetic") { _ in peer }
    let session=try EasyWorkRemoteSession(connection:connection,credential:credential)
    cleanup(session,peers:[peer])
    for n in 0..<16 { let start=ContinuousClock.now;_ = try await session.request("project/list",[:],id:"cap-\(n)");XCTAssertLessThan(start.duration(to:.now),.seconds(3)) }
    let held=await peer.heldCallbacks.count;XCTAssertEqual(held,16)
    do { _ = try await session.request("project/list",[:],id:"cap-16");XCTFail("capacity must reject the seventeenth") }
    catch let e as URLError { XCTAssertEqual(e.code,.timedOut) } catch { XCTFail("expected explicit timeout: \(error)") }
    var trace=await peer.trace();XCTAssertFalse(trace.contains{$0["requestId"] as? String == "cap-16"});let before=await peer.closedCount;XCTAssertEqual(before,0)
    await peer.releaseCallback("cap-0")
    let write=Task { try await session.request("turn/cancel",["threadId":"thread-t14","runId":"turn-t14"],id:"cap-write") }
    var writeSeen=false
    for _ in 0..<300 { if await peer.trace().contains(where:{$0["requestId"] as? String == "cap-write"}) { writeSeen=true;break };try await Task.sleep(for:.milliseconds(10)) }
    XCTAssertTrue(writeSeen,"new publisher must actually enter transport")
    await peer.releaseCallback("cap-1");try await Task.sleep(for:.milliseconds(100))
    let later=Task { try await session.request("project/list",[:],id:"cap-after-old") }
    try await Task.sleep(for:.seconds(1));trace=await peer.trace();XCTAssertFalse(trace.contains{$0["requestId"] as? String == "cap-after-old"},"old callback must not release the new write publisher")
    await peer.release("cap-write");await peer.releaseCallback("cap-write")
    _ = try await write.value;_ = try await later.value
    trace=await peer.trace();XCTAssertEqual(trace.filter{$0["requestId"] as? String == "cap-write"}.count,1);XCTAssertEqual(trace.filter{$0["requestId"] as? String == "cap-after-old"}.count,1)
    let closed=await peer.closedCount;print("LIFE01_T14_CAP held=\(held) close=\(closed) writeCount=1");XCTAssertEqual(closed,0)
    for n in 2..<16 { await peer.releaseCallback("cap-\(n)") };await session.close()
  }

}
